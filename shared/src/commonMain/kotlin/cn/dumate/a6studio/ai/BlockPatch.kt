package cn.dumate.a6studio.ai

import cn.dumate.a6studio.catalog.Catalog
import cn.dumate.a6studio.catalog.CodeGen
import cn.dumate.a6studio.catalog.ParamKind
import cn.dumate.a6studio.diagnose.Diagnose
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
 * 受控积木树补丁引擎与护栏守卫（N-S3 / N-M8 修复版）。
 *
 * - 递归白名单校验所有积木（含 children 与 modules）：类型必须存在、禁止 rawJs、禁止写入 CODE/EXPR 参数、
 *   SELECT 取值必须在选项中、NUMBER 必须是 ParamValue.Num、标识符参数通过 Diagnose.isJsIdentifier 校验、
 *   参数 key 与插槽 slot 必须属于该积木定义；
 * - 目标积木或父节点不存在时直接报错，绝不静默跳过；
 * - 护栏积木不可删除、超时不可放大超 2 倍、kitFinish 不可强改 success。
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
                    val targetParent = op.parentId ?: cur.root.id
                    validateParentAndSlot(cur, targetParent, op.slot)
                    var inserted = false
                    val newRoot = insertIntoBlock(cur.root, targetParent, op.slot, op.index, op.block) { inserted = true }
                    val newModules = cur.modules.map { m ->
                        if (m.id == targetParent) {
                            inserted = true
                            val list = m.children[op.slot].orEmpty().toMutableList()
                            val pos = if (op.index < 0 || op.index > list.size) list.size else op.index
                            list.add(pos, op.block)
                            m.copy(children = m.children + (op.slot to list))
                        } else {
                            m.copy(
                                children = m.children.mapValues { (_, list) ->
                                    list.map { insertIntoBlock(it, targetParent, op.slot, op.index, op.block) { inserted = true } }
                                }
                            )
                        }
                    }
                    require(inserted) { "目标父积木不存在: $targetParent" }
                    cur.copy(root = newRoot, modules = newModules)
                }
                is BlockPatchOp.UpdateParam -> {
                    val target = locateBlock(cur, op.blockId) ?: error("目标积木不存在: ${op.blockId}")
                    val def = Catalog.byType(target.type) ?: error("未知积木类型: ${target.type}")
                    require(def.params.any { it.key == op.key }) { "${target.type} 无参数 ${op.key}" }
                    var updated = false
                    val newRoot = updateBlockInTree(cur.root, op.blockId) { b ->
                        updated = true
                        b.copy(params = b.params + (op.key to op.value))
                    }
                    val newModules = cur.modules.map { m ->
                        m.copy(
                            children = m.children.mapValues { (_, list) ->
                                list.map { b ->
                                    updateBlockInTree(b, op.blockId) {
                                        updated = true
                                        it.copy(params = it.params + (op.key to op.value))
                                    }
                                }
                            }
                        )
                    }
                    require(updated) { "目标积木不存在: ${op.blockId}" }
                    cur.copy(root = newRoot, modules = newModules)
                }
                is BlockPatchOp.RemoveBlock -> {
                    require(op.blockId != cur.root.id) { "不能删除根节点" }
                    locateBlock(cur, op.blockId) ?: error("目标积木不存在: ${op.blockId}")
                    var removed = false
                    val newRoot = removeFromTree(cur.root, op.blockId) { removed = true }
                    val newModules = cur.modules.map { m ->
                        m.copy(
                            children = m.children.mapValues { (_, list) ->
                                val filtered = list.filter {
                                    val match = it.id == op.blockId
                                    if (match) removed = true
                                    !match
                                }
                                filtered.map { removeFromTree(it, op.blockId) { removed = true } }
                            }
                        )
                    }
                    require(removed) { "目标积木不存在: ${op.blockId}" }
                    cur.copy(root = newRoot, modules = newModules)
                }
            }
        }
        return cur
    }

    fun guardAndApply(before: Project, ops: List<BlockPatchOp>): PatchGuardResult {
        val errs = mutableListOf<String>()

        // 0. 预先对所有补丁指令中的新增积木和修改参数执行递归白名单校验
        for (op in ops) {
            when (op) {
                is BlockPatchOp.AddBlock -> validateBlockRecursive(op.block, errs)
                is BlockPatchOp.UpdateParam -> {
                    val target = locateBlock(before, op.blockId)
                    if (target == null) {
                        errs += "拒绝：目标积木不存在 (${op.blockId})"
                    } else {
                        validateSingleParam(target.type, op.key, op.value, errs)
                    }
                }
                is BlockPatchOp.RemoveBlock -> {
                    if (locateBlock(before, op.blockId) == null) {
                        errs += "拒绝：目标积木不存在 (${op.blockId})"
                    }
                }
                is BlockPatchOp.RenameProject -> {
                    if (op.name.isBlank()) errs += "拒绝：项目名不能为空"
                }
            }
        }

        val after = try {
            apply(before, ops)
        } catch (e: Exception) {
            return PatchGuardResult(false, before, (errs + "补丁应用失败：${e.message}").distinct())
        }

        // 1. 递归校验应用后的全树（含 root 与所有 modules）
        after.root.children["body"].orEmpty().forEach { validateBlockRecursive(it, errs) }
        after.modules.forEach { m ->
            m.children["body"].orEmpty().forEach { validateBlockRecursive(it, errs) }
        }

        val flatBefore = flattenAllProjectBlocks(before)
        val flatAfter = flattenAllProjectBlocks(after)

        // 2. 护栏积木不可被删除
        for (gt in GUARD_TYPES) {
            if (flatBefore.any { it.type == gt } && flatAfter.none { it.type == gt }) {
                errs += "拒绝：补丁删除了护栏积木「$gt」"
            }
        }

        // 3. kitBoot 总超时护栏不可放大超 2 倍或低于 30s
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

        // 4. kitFinish 不得从 auto/warn 被强行改写为恒 success
        val finishBefore = flatBefore.filter { it.type == "kitFinish" }
        val finishAfter = flatAfter.filter { it.type == "kitFinish" }
        if (finishBefore.isNotEmpty() && finishBefore.all { it.params["result"]?.asText() != "success" }) {
            val forced = finishAfter.firstOrNull { it.params["result"]?.asText() == "success" }
            if (forced != null) {
                errs += "拒绝：补丁将 kitFinish(${forced.id}) 的结果硬改为了 success，可能掩盖未完成任务"
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
            PatchGuardResult(false, before, errs.distinct())
        }
    }

    private fun validateBlockRecursive(b: Block, errs: MutableList<String>) {
        val def = Catalog.byType(b.type)
        if (def == null) {
            errs += "拒绝：未知积木类型 ${b.type}"
            return
        }
        if (b.type == "rawJs") {
            errs += "拒绝：补丁通道禁止 rawJs"
        }
        b.params.forEach { (k, v) ->
            validateSingleParam(b.type, k, v, errs)
        }
        val identKeys = Diagnose.identifierParams[b.type].orEmpty()
        for (ik in identKeys) {
            val effectiveVal = b.params[ik]?.asText()
                ?: def.params.firstOrNull { it.key == ik }?.default?.asText()
                .orEmpty()
            if (!Diagnose.isJsIdentifier(effectiveVal.trim())) {
                errs += "拒绝：${b.type}.$ik 不是合法标识符 ($effectiveVal)"
            }
        }
        b.children.forEach { (slot, kids) ->
            if (def.slots.none { it.key == slot }) {
                errs += "拒绝：${b.type} 无插槽 $slot"
            }
            kids.forEach { validateBlockRecursive(it, errs) }
        }
    }

    private fun validateSingleParam(blockType: String, key: String, value: ParamValue, errs: MutableList<String>) {
        val def = Catalog.byType(blockType) ?: run {
            errs += "拒绝：未知积木类型 $blockType"
            return
        }
        val pd = def.params.firstOrNull { it.key == key } ?: run {
            errs += "拒绝：$blockType 无参数 $key"
            return
        }
        when (pd.kind) {
            ParamKind.CODE, ParamKind.EXPR -> {
                if (value.asText().isNotBlank()) {
                    errs += "拒绝：补丁通道禁止写入表达式/代码参数 $blockType.$key"
                }
            }
            ParamKind.SELECT -> {
                if (pd.options.isNotEmpty() && pd.options.none { it.value == value.asText() }) {
                    errs += "拒绝：$blockType.$key 取值非法 (${value.asText()})"
                }
            }
            ParamKind.NUMBER -> {
                if (value !is ParamValue.Num) {
                    errs += "拒绝：$blockType.$key 需要数字"
                }
            }
            ParamKind.TEXT -> {
                if (CODE_INJECTION_RE.containsMatchIn(value.asText())) {
                    errs += "拒绝：在 $blockType.$key 中检测到疑似可执行代码注入"
                }
            }
            else -> Unit
        }
        val identKeys = Diagnose.identifierParams[blockType].orEmpty()
        if (key in identKeys && !Diagnose.isJsIdentifier(value.asText().trim())) {
            errs += "拒绝：$blockType.$key 不是合法标识符 (${value.asText()})"
        }
    }

    private fun validateParentAndSlot(project: Project, targetParentId: String, slot: String) {
        if (targetParentId == project.root.id) {
            require(slot == "body") { "根节点无插槽 $slot" }
            return
        }
        if (project.modules.any { it.id == targetParentId }) {
            require(slot == "body") { "模块节点无插槽 $slot" }
            return
        }
        val parentBlock = locateBlock(project, targetParentId) ?: error("目标父积木不存在: $targetParentId")
        val def = Catalog.byType(parentBlock.type) ?: error("未知父积木类型: ${parentBlock.type}")
        require(def.slots.any { it.key == slot }) { "${parentBlock.type} 无插槽 $slot" }
    }

    fun locateBlock(project: Project, blockId: String): Block? {
        TreeOps.find(project.root.children["body"].orEmpty(), blockId)?.let { return it }
        for (m in project.modules) {
            TreeOps.find(m.children["body"].orEmpty(), blockId)?.let { return it }
        }
        return null
    }

    private fun flattenAllProjectBlocks(project: Project): List<Block> = buildList {
        addAll(flattenBlocks(project.root.children["body"].orEmpty()))
        project.modules.forEach { m ->
            addAll(flattenBlocks(m.children["body"].orEmpty()))
        }
    }

    private fun flattenBlocks(list: List<Block>): List<Block> = buildList {
        for (b in list) {
            add(b)
            b.children.values.forEach { addAll(flattenBlocks(it)) }
        }
    }

    private fun insertIntoBlock(
        parent: Block,
        targetParentId: String,
        slot: String,
        index: Int,
        newBlock: Block,
        onInserted: () -> Unit
    ): Block {
        if (parent.id == targetParentId) {
            onInserted()
            val curList = parent.children[slot].orEmpty().toMutableList()
            val pos = if (index < 0 || index > curList.size) curList.size else index
            curList.add(pos, newBlock)
            return parent.copy(children = parent.children + (slot to curList))
        }
        return parent.copy(
            children = parent.children.mapValues { (_, list) ->
                list.map { insertIntoBlock(it, targetParentId, slot, index, newBlock, onInserted) }
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

    private fun removeFromTree(node: Block, targetId: String, onRemoved: () -> Unit): Block =
        node.copy(
            children = node.children.mapValues { (_, list) ->
                val filtered = list.filter {
                    val match = it.id == targetId
                    if (match) onRemoved()
                    !match
                }
                filtered.map { removeFromTree(it, targetId, onRemoved) }
            }
        )
}
