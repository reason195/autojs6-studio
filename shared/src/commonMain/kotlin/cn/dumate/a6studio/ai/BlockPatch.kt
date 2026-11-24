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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface BlockPatchOp {
    @Serializable
    @SerialName("add")
    data class AddBlock(
        val block: Block,
        val parentId: String? = null,
        val slot: String = "body",
        val index: Int = -1
    ) : BlockPatchOp

    @Serializable
    @SerialName("update")
    data class UpdateParam(
        val blockId: String,
        val key: String,
        val value: ParamValue
    ) : BlockPatchOp

    @Serializable
    @SerialName("remove")
    data class RemoveBlock(
        val blockId: String
    ) : BlockPatchOp

    @Serializable
    @SerialName("rename")
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
    private const val MIN_KIT_RUNTIME_MS = 30_000.0
    private const val MAX_KIT_RUNTIME_MS = 3_600_000.0

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

        // 0. 按递进状态逐条校验并应用：允许同批先 AddBlock 再 UpdateParam，同时拦截重复/空 ID
        val existingIds = mutableSetOf<String>().apply {
            add(before.root.id)
            before.modules.forEach { add(it.id) }
            flattenAllProjectBlocks(before).forEach { add(it.id) }
        }
        var cur = before
        for (op in ops) {
            when (op) {
                is BlockPatchOp.AddBlock -> {
                    validateBlockRecursive(op.block, errs)
                    for (nb in flattenBlocks(listOf(op.block))) {
                        if (nb.id.isBlank()) {
                            errs += "拒绝：新增积木缺少非空 id (${nb.type})"
                        } else if (!existingIds.add(nb.id)) {
                            errs += "拒绝：新增积木 id 与现有节点冲突 (${nb.id})"
                        }
                    }
                }
                is BlockPatchOp.UpdateParam -> {
                    val target = locateBlock(cur, op.blockId)
                    if (target == null) {
                        errs += "拒绝：目标积木不存在 (${op.blockId})"
                    } else {
                        validateSingleParam(target.type, op.key, op.value, errs)
                    }
                }
                is BlockPatchOp.RemoveBlock -> {
                    val target = locateBlock(cur, op.blockId)
                    if (target == null) {
                        errs += "拒绝：目标积木不存在 (${op.blockId})"
                    } else {
                        flattenBlocks(listOf(target)).forEach { existingIds.remove(it.id) }
                    }
                }
                is BlockPatchOp.RenameProject -> {
                    if (op.name.isBlank()) errs += "拒绝：项目名不能为空"
                    else if (op.name.length > 64) errs += "拒绝：项目名最长 64 个字符（当前 ${op.name.length}）"
                    else if (op.name.any { it.code < 0x20 }) errs += "拒绝：项目名包含控制字符"
                    else if (op.name.any { it in "\\/:|*?\"<>" }) errs += "拒绝：项目名包含文件名非法字符（\\/:|*?\"<>）"
                }
            }
            cur = try {
                apply(cur, listOf(op))
            } catch (e: Exception) {
                return PatchGuardResult(false, before, (errs + "补丁应用失败：${e.message}").distinct())
            }
        }
        val after = cur

        val flatBefore = flattenAllProjectBlocks(before)
        val flatAfter = flattenAllProjectBlocks(after)
        val rootBefore = flattenBlocks(before.root.children["body"].orEmpty())
        val rootAfter = flattenBlocks(after.root.children["body"].orEmpty())

        // 1. 只校验本次补丁「新增」或「改动」的积木（R-1）。
        //    用户手工搭建的 if.cond / varSet.expr / rawJs 等既有积木不属于补丁通道，
        //    不能因为它们存在就把所有补丁拒掉。
        val beforeById = flatBefore.associateBy { it.id }
        for (b in flatAfter) {
            val old = beforeById[b.id]
            when {
                // 新增积木（含新增积木的嵌套子积木，flatAfter 已展开，故此处不再递归）
                old == null || old.type != b.type -> validateBlockShallow(b, errs)
                else -> {
                    // 既有积木：只校验值发生变化或新出现的参数
                    b.params.forEach { (k, v) ->
                        if (old.params[k] != v) validateSingleParam(b.type, k, v, errs)
                    }
                    // 既有积木新增了插槽
                    val def = Catalog.byType(b.type)
                    b.children.keys.filter { it !in old.children.keys }.forEach { slot ->
                        if (def != null && def.slots.none { it.key == slot }) errs += "拒绝：${b.type} 无插槽 $slot"
                    }
                }
            }
        }

        // 2. 护栏积木不可被删除（同时锁住主程序 root，防止删除主流程护栏并在未调用模块塞入替身）
        for (gt in GUARD_TYPES) {
            if (flatBefore.any { it.type == gt } && flatAfter.none { it.type == gt }) {
                errs += "拒绝：补丁删除了护栏积木「$gt」"
            } else if (rootBefore.any { it.type == gt } && rootAfter.none { it.type == gt }) {
                errs += "拒绝：补丁删除了主程序中的护栏积木「$gt」"
            }
        }

        // 3. 所有 kitBoot 总超时护栏必须有限且落在 [30s, 3600s] 内，且既有 kitBoot 单次不可放大超 2 倍
        val bootsBeforeById = flatBefore.filter { it.type == "kitBoot" }.associateBy { it.id }
        for (bootAfter in flatAfter.filter { it.type == "kitBoot" }) {
            val ms1 = bootAfter.params["maxRuntimeMs"]?.asNumber(Double.NaN) ?: 900_000.0
            if (!ms1.isFinite() || ms1 < MIN_KIT_RUNTIME_MS || ms1 > MAX_KIT_RUNTIME_MS) {
                errs += "拒绝：kitBoot(${bootAfter.id}).maxRuntimeMs 超出安全范围 [${MIN_KIT_RUNTIME_MS.toLong()}, ${MAX_KIT_RUNTIME_MS.toLong()}]"
                continue
            }
            val bootBefore = bootsBeforeById[bootAfter.id]
            if (bootBefore != null) {
                val ms0 = bootBefore.params["maxRuntimeMs"]?.asNumber(900_000.0) ?: 900_000.0
                if (ms0.isFinite() && ms1 > ms0 * 2.0) {
                    errs += "拒绝：kitBoot.maxRuntimeMs 从 ${ms0.toLong()} 放大到 ${ms1.toLong()}（超过 2 倍上限）"
                }
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

        // 5. 生成代码不能因补丁引入新的明文密钥（按命中次数增量比对，防止已有同类标签时漏拦新密钥）
        val generatedBefore = CodeGen.code(before)
        val generatedAfter = CodeGen.code(after)
        val secretsBefore = Redaction.countPlaintextSecrets(generatedBefore)
        val secretsAfter = Redaction.countPlaintextSecrets(generatedAfter)
        val newSecrets = secretsAfter.filter { (label, cnt) -> cnt > (secretsBefore[label] ?: 0) }.keys
        if (newSecrets.isNotEmpty()) {
            errs += "拒绝：补丁在生成代码中引入新的疑似明文凭据（${newSecrets.joinToString(", ")}）"
        }

        return if (errs.isEmpty()) {
            PatchGuardResult(true, after)
        } else {
            PatchGuardResult(false, before, errs.distinct())
        }
    }

    private fun validateBlockRecursive(b: Block, errs: MutableList<String>) = validateBlock(b, errs, recurse = true)

    private fun validateBlockShallow(b: Block, errs: MutableList<String>) = validateBlock(b, errs, recurse = false)

    private fun validateBlock(b: Block, errs: MutableList<String>, recurse: Boolean) {
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
            if (recurse) kids.forEach { validateBlockRecursive(it, errs) }
        }
    }

    private fun validateSingleParam(blockType: String, key: String, value: ParamValue, errs: MutableList<String>) {
        if (blockType == "kitBoot" && key == "policyJson" && value.asText().isNotBlank()) {
            errs += "拒绝：补丁通道禁止修改 kitBoot.policyJson（护栏策略只能经导入设置）"
            return
        }
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
        // funcDef.args 不加引号直接拼进函数签名，必须是逗号分隔的标识符列表
        val listKeys = Diagnose.identifierListParams[blockType].orEmpty()
        if (key in listKeys && !Diagnose.isJsIdentifierList(value.asText())) {
            errs += "拒绝：$blockType.$key 不是合法参数列表 (${value.asText()})"
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
