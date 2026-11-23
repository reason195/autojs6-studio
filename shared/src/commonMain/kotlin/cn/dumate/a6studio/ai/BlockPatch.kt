package cn.dumate.a6studio.ai

import cn.dumate.a6studio.catalog.Catalog
import cn.dumate.a6studio.catalog.CodeGen
import cn.dumate.a6studio.model.Block
import cn.dumate.a6studio.model.ParamValue
import cn.dumate.a6studio.model.Project
import cn.dumate.a6studio.model.TreeOps
import cn.dumate.a6studio.security.Redaction
import kotlinx.serialization.Serializable

@Serializable
sealed interface BlockPatchOp {
    @Serializable
    data class AddBlock(
        val block: Block,
        val parentId: String? = null,
        val slot: String = "body",
        val index: Int = -1
    ) : BlockPatchOp

    @Serializable
    data class UpdateParam(
        val blockId: String,
        val key: String,
        val value: ParamValue
    ) : BlockPatchOp

    @Serializable
    data class RemoveBlock(
        val blockId: String
    ) : BlockPatchOp

    @Serializable
    data class RenameProject(
        val name: String
    ) : BlockPatchOp
}

data class PatchGuardResult(
    val accepted: Boolean,
    val project: Project,
    val errors: List<String> = emptyList()
)

/**
 * 受控积木树补丁引擎与护栏守卫（源自 Jingdou Studio `core/patch.mjs`）。
 * 作为 AI 或外部 MCP 代理修改工程的唯一受控通道：先干跑校验护栏，任一违规整体拒绝。
 */
object BlockPatch {

    private val GUARD_TYPES = setOf("kitBoot", "kitGuard", "kitFinish")
    private val CODE_INJECTION_RE = Regex("\\bfunction\\s*\\(|=>|\\brequire\\s*\\(|\\beval\\s*\\(")

    fun apply(project: Project, ops: List<BlockPatchOp>): Project {
        var cur = project
        for (op in ops) {
            cur = when (op) {
                is BlockPatchOp.RenameProject -> cur.copy(name = op.name)
                is BlockPatchOp.AddBlock -> {
                    require(Catalog.byType(op.block.type) != null) { "未知积木类型: ${op.block.type}" }
                    val targetParent = op.parentId ?: cur.root.id
                    val newRoot = insertIntoRoot(cur.root, targetParent, op.slot, op.index, op.block)
                    cur.copy(root = newRoot)
                }
                is BlockPatchOp.UpdateParam -> {
                    val newRoot = updateBlockInTree(cur.root, op.blockId) { b ->
                        b.copy(params = b.params + (op.key to op.value))
                    }
                    cur.copy(root = newRoot)
                }
                is BlockPatchOp.RemoveBlock -> {
                    val newRoot = removeFromTree(cur.root, op.blockId)
                    cur.copy(root = newRoot)
                }
            }
        }
        return cur
    }

    fun guardAndApply(before: Project, ops: List<BlockPatchOp>): PatchGuardResult {
        val after = try {
            apply(before, ops)
        } catch (e: Exception) {
            return PatchGuardResult(false, before, listOf("补丁应用失败：${e.message}"))
        }

        val errs = mutableListOf<String>()
        val flatBefore = flattenBlocks(before.root.children["body"].orEmpty())
        val flatAfter = flattenBlocks(after.root.children["body"].orEmpty())

        // 1. 护栏积木不可被删除
        for (gt in GUARD_TYPES) {
            if (flatBefore.any { it.type == gt } && flatAfter.none { it.type == gt }) {
                errs += "拒绝：补丁删除了护栏积木「$gt」"
            }
        }

        // 2. kitBoot 总超时护栏不可放大超 2 倍或低于 30s
        val bootBefore = flatBefore.firstOrNull { it.type == "kitBoot" }
        val bootAfter = flatAfter.firstOrNull { it.type == "kitBoot" }
        if (bootBefore != null && bootAfter != null) {
            val ms0 = bootBefore.params["maxRuntimeMs"]?.asNumber(900_000.0) ?: 900_000.0
            val ms1 = bootAfter.params["maxRuntimeMs"]?.asNumber(900_000.0) ?: 900_000.0
            if (ms1 > ms0 * 2.0) {
                errs += "拒绝：kitBoot.maxRuntimeMs 从 ${ms0.toLong()} 放大到 ${ms1.toLong()}（超过 2 倍上限）"
            }
            if (ms1 < 30_000.0) {
                errs += "拒绝：kitBoot.maxRuntimeMs 不得低于 30000ms"
            }
        }

        // 3. kitFinish 不得从 auto/warn 被强行改写为恒 success
        val finishBefore = flatBefore.filter { it.type == "kitFinish" }
        val finishAfter = flatAfter.filter { it.type == "kitFinish" }
        if (finishBefore.isNotEmpty() && finishBefore.all { it.params["result"]?.asText() != "success" }) {
            val forced = finishAfter.firstOrNull { it.params["result"]?.asText() == "success" }
            if (forced != null) {
                errs += "拒绝：补丁将 kitFinish(${forced.id}) 的结果硬改为了 success，可能掩盖未完成任务"
            }
        }

        // 4. 禁止非 rawJs 积木注入可执行代码
        for (op in ops) {
            when (op) {
                is BlockPatchOp.UpdateParam -> {
                    val target = TreeOps.find(after.root.children["body"].orEmpty(), op.blockId)
                    if (target != null && target.type != "rawJs" && op.value is ParamValue.Text) {
                        if (CODE_INJECTION_RE.containsMatchIn(op.value.value)) {
                            errs += "拒绝：在积木 ${op.blockId}.${op.key} 中检测到疑似可执行代码注入"
                        }
                    }
                }
                is BlockPatchOp.AddBlock -> {
                    if (op.block.type != "rawJs") {
                        op.block.params.forEach { (k, v) ->
                            if (v is ParamValue.Text && CODE_INJECTION_RE.containsMatchIn(v.value)) {
                                errs += "拒绝：新增积木 ${op.block.type}.$k 中包含疑似可执行代码注入"
                            }
                        }
                    }
                }
                else -> Unit
            }
        }

        // 5. 生成代码不能包含明文密钥
        val generated = CodeGen.code(after)
        val secrets = Redaction.assertNoPlaintextSecrets(generated)
        if (secrets.isNotEmpty()) {
            errs += "拒绝：补丁导致生成代码中出现疑似明文凭据（${secrets.joinToString(", ")}）"
        }

        return if (errs.isEmpty()) {
            PatchGuardResult(true, after)
        } else {
            PatchGuardResult(false, before, errs)
        }
    }

    private fun flattenBlocks(list: List<Block>): List<Block> = buildList {
        for (b in list) {
            add(b)
            b.children.values.forEach { addAll(flattenBlocks(it)) }
        }
    }

    private fun insertIntoRoot(parent: Block, targetParentId: String, slot: String, index: Int, newBlock: Block): Block {
        if (parent.id == targetParentId) {
            val curList = parent.children[slot].orEmpty().toMutableList()
            val pos = if (index < 0 || index > curList.size) curList.size else index
            curList.add(pos, newBlock)
            return parent.copy(children = parent.children + (slot to curList))
        }
        return parent.copy(
            children = parent.children.mapValues { (_, list) ->
                list.map { insertIntoRoot(it, targetParentId, slot, index, newBlock) }
            }
        )
    }

    private fun updateBlockInTree(node: Block, targetId: String, transform: (Block) -> Block): Block {
        val updated = if (node.id == targetId) transform(node) else node
        return updated.copy(
            children = updated.children.mapValues { (_, list) ->
                list.map { updateBlockInTree(it, targetId, transform) }
            }
        )
    }

    private fun removeFromTree(node: Block, targetId: String): Block =
        node.copy(
            children = node.children.mapValues { (_, list) ->
                list.filterNot { it.id == targetId }.map { removeFromTree(it, targetId) }
            }
        )
}
