package cn.dumate.a6studio.store

import cn.dumate.a6studio.model.Block
import cn.dumate.a6studio.model.Ids
import cn.dumate.a6studio.model.Project
import cn.dumate.a6studio.model.ProjectStorage
import cn.dumate.a6studio.model.Samples
import cn.dumate.a6studio.model.Scope
import cn.dumate.a6studio.model.StudioModule
import cn.dumate.a6studio.model.TestCase
import cn.dumate.a6studio.model.TreeOps
import cn.dumate.a6studio.model.newBlock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.Json

/**
 * Single source of truth for the editor: project model, selection, scope and undo stack.
 * Edits go through the small intent methods below so the UI never mutates the tree directly.
 */
class ProjectRepo(private val storage: ProjectStorage) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    // Compose `mutableStateOf`: the UI reads these directly, so a write recomposes immediately.
    //
    // (An earlier version exposed `val project: StateFlow<X> get() = MutableStateFlow(...)`, which
    // built a NEW flow on every property access — reads always "worked" by accident while the
    // observable was thrown away, so nothing ever collected it. Plain Compose state is both
    // cheaper and honest about what it is.)
    private var _projectState by mutableStateOf(Project())
    private var _selectedState by mutableStateOf<String?>(null)
    private var _scopeState by mutableStateOf<Scope>(Scope.Root)
    private var _toastState by mutableStateOf<String?>(null)

    /** Current selection id; [null] means nothing selected. */
    val selectedId: String? get() = _selectedState

    /** Active editing scope (main program or one module). */
    val currentScope: Scope get() = _scopeState

    /** Transient message for the host UI, if any. */
    val toastMessage: String? get() = _toastState

    private val undoStack = ArrayDeque<Pair<String, String>>()
    private val maxUndo = 60

    val current: Project get() = _projectState

    fun load() {
        val raw = try { storage.loadRaw() } catch (e: Exception) { null }
        _projectState = if (raw.isNullOrBlank()) Samples.exampleProject() else {
            try {
                val parsed = json.decodeFromString(Project.serializer(), raw)
                if (parsed.root.type.isEmpty()) Samples.exampleProject() else normalizeProject(parsed)
            } catch (e: Exception) {
                Samples.exampleProject()
            }
        }
    }

    private fun normalizeProject(p: Project): Project = p.copy(
        root = normalizeBlock(p.root),
        modules = p.modules.map { m -> m.copy(children = m.children.mapValues { (_, v) -> v.map(::normalizeBlock) }) }
    )

    private fun normalizeBlock(b: Block): Block = b.copy(
        children = b.children.mapValues { (_, kids) -> kids.map(::normalizeBlock) }
    )

    fun save() {
        try { storage.saveRaw(json.encodeToString(Project.serializer(), current)) } catch (e: Exception) { /* ignore */ }
    }

    fun toast(msg: String) { _toastState = msg }

    fun clearToast() { _toastState = null }

    // ---- undo ---------------------------------------------------------------
    /**
     * Records an undo point. A serialisation failure must never abort the edit the user asked
     * for, so it is caught: losing one undo step is far better than losing the action.
     */
    fun snapshot(label: String) {
        val encoded = try {
            json.encodeToString(Project.serializer(), current)
        } catch (e: Exception) {
            return
        }
        undoStack.addLast(label to encoded)
        while (undoStack.size > maxUndo) undoStack.removeFirst()
    }

    fun undo(): String? {
        val (label, raw) = undoStack.removeLastOrNull() ?: return null
        _projectState = try { json.decodeFromString(Project.serializer(), raw) } catch (e: Exception) { current }
        _selectedState = null
        save()
        return label
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()

    // ---- scope --------------------------------------------------------------
    fun setScope(scope: Scope) {
        _scopeState = scope
        _selectedState = null
    }

    fun currentModule(): StudioModule? =
        (_scopeState as? Scope.Module)?.let { s -> current.modules.firstOrNull { it.id == s.id } }

    fun scopeList(): List<Block> =
        currentModule()?.children?.get("body").orEmpty().ifEmpty {
            if (_scopeState is Scope.Module) emptyList() else current.root.children["body"].orEmpty()
        }

    fun scopeLabel(): String = currentModule()?.let { "模块：${it.name}" } ?: "主程序"

    /** Parent id used in tree edits: "root" for the main program, "mod:<id>" for a module. */
    fun scopeRootId(): String = (_scopeState as? Scope.Module)?.let { "mod:${it.id}" } ?: "root"

    // ---- lookups ------------------------------------------------------------
    fun blockById(id: String): Block? {
        TreeOps.find(current.root.children["body"].orEmpty(), id)?.let { return it }
        for (m in current.modules) TreeOps.find(m.children["body"].orEmpty(), id)?.let { return it }
        return null
    }

    /** Finds the list + index containing [id], so callers can move or delete it. */
    fun locate(id: String): cn.dumate.a6studio.model.LocatedBlock? {
        fun search(list: List<Block>, parentId: String, slot: String): cn.dumate.a6studio.model.LocatedBlock? {
            list.forEachIndexed { i, b ->
                if (b.id == id) return cn.dumate.a6studio.model.LocatedBlock(b, list as MutableList<Block>, i, parentId, slot)
                b.children.forEach { (k, kids) -> search(kids, b.id, k)?.let { return it } }
            }
            return null
        }
        search(current.root.children["body"].orEmpty(), "root", "body")?.let { return it }
        for (m in current.modules) {
            search(m.children["body"].orEmpty(), "mod:${m.id}", "body")?.let { return it }
        }
        return null
    }

    fun containerList(parentId: String, slot: String): List<Block>? {
        if (parentId == "root") return current.root.children["body"].orEmpty()
        if (parentId.startsWith("mod:")) {
            val m = current.modules.firstOrNull { it.id == parentId.removePrefix("mod:") } ?: return null
            return m.children[slot].orEmpty()
        }
        return blockById(parentId)?.children?.get(slot).orEmpty()
    }

    // ---- edits --------------------------------------------------------------
    fun setProject(p: Project, label: String, snapshot: Boolean = true) {
        if (snapshot) snapshot(label)
        _projectState = p
        save()
    }

    fun setName(name: String) {
        _projectState = current.copy(name = name)
        save()
    }

    fun select(id: String?) { _selectedState = id }

    /** Replaces the block children of a container (root, module, or block slot). */
    private fun withBody(body: List<Block>): Project {
        return when (val s = _scopeState) {
            is Scope.Module -> current.copy(
                modules = current.modules.map { m ->
                    if (m.id == s.id) m.copy(children = m.children + ("body" to body)) else m
                }
            )
            Scope.Root -> current.copy(root = current.root.copy(children = current.root.children + ("body" to body)))
        }
    }

    private fun updateScopeBody(body: List<Block>) {
        _projectState = withBody(body)
    }

    fun addBlock(type: String, parentId: String? = null, slot: String = "body", index: Int? = null): Block {
        snapshot("添加积木")
        val blk = newBlock(type)

        // Default insertion point: inside the selected container, after the selected block,
        // otherwise at the end of the current scope.
        val targetParent: String
        val targetSlot: String
        val targetIndex: Int
        if (parentId != null) {
            targetParent = parentId
            targetSlot = slot
            targetIndex = index ?: containerList(parentId, slot)?.size ?: 0
        } else {
            val selId = _selectedState
            val selBlock = selId?.let { blockById(it) }
            val selDef = selBlock?.type?.let { cn.dumate.a6studio.catalog.Catalog.byType(it) }
            if (selBlock != null && selDef != null && selDef.slots.isNotEmpty()) {
                targetParent = selBlock.id
                targetSlot = selDef.slots.first().key
                targetIndex = selBlock.children[targetSlot].orEmpty().size
            } else if (selBlock != null) {
                val loc = locate(selBlock.id)
                targetParent = loc?.parentId ?: scopeRootId()
                targetSlot = loc?.slot ?: "body"
                targetIndex = (loc?.index ?: -1) + 1
            } else {
                targetParent = scopeRootId()
                targetSlot = "body"
                targetIndex = scopeList().size
            }
        }

        val body = insertInto(targetParent, targetSlot, targetIndex, blk)
        updateScopeBody(body)
        _selectedState = blk.id
        save()
        return blk
    }

    private fun insertInto(parentId: String, slot: String, index: Int, blk: Block): List<Block> =
        TreeOps.insert(scopeBodyInternal(), parentId, slot, index, blk, scopeRootId())

    private fun scopeBodyInternal(): List<Block> =
        if (_scopeState is Scope.Module) currentModule()?.children?.get("body").orEmpty()
        else current.root.children["body"].orEmpty()

    fun moveBlock(id: String, parentId: String, slot: String, index: Int): Boolean {
        if (parentId == id || TreeOps.contains(scopeBodyInternal(), id, parentId)) {
            toast("不能把积木放进它自己里面")
            return false
        }
        snapshot("移动积木")
        val loc = locate(id) ?: return false
        val moved = loc.block
        val without = TreeOps.remove(scopeBodyInternal(), id).first
        val inserted = TreeOps.insert(without, parentId, slot, index, moved, scopeRootId())
        updateScopeBody(inserted)
        _selectedState = id
        save()
        return true
    }

    fun shiftBlock(id: String, delta: Int) {
        val loc = locate(id) ?: return
        val j = loc.index + delta
        if (j < 0 || j >= loc.list.size) return
        snapshot("调整顺序")
        val body = scopeBodyInternal().toMutableList()
        // Reordering a nested block is a re-insert into the same parent.
        if (loc.parentId == scopeRootId() && loc.slot == "body") {
            val b = body[loc.index]
            body.removeAt(loc.index)
            body.add(j, b)
            updateScopeBody(body)
        } else {
            val removed = TreeOps.remove(scopeBodyInternal(), id).first
            updateScopeBody(TreeOps.insert(removed, loc.parentId, loc.slot, j, loc.block, scopeRootId()))
        }
        _selectedState = id
        save()
    }

    fun duplicateBlock(id: String) {
        val loc = locate(id) ?: return
        snapshot("复制积木")
        val copy = TreeOps.cloneBlock(loc.block)
        val body = TreeOps.insert(scopeBodyInternal(), loc.parentId, loc.slot, loc.index + 1, copy, scopeRootId())
        updateScopeBody(body)
        _selectedState = copy.id
        save()
    }

    fun removeBlock(id: String) {
        snapshot("删除积木")
        val (body, _) = TreeOps.remove(scopeBodyInternal(), id)
        updateScopeBody(body)
        if (_selectedState == id) _selectedState = null
        save()
    }

    fun updateParam(blockId: String, key: String, value: cn.dumate.a6studio.model.ParamValue, snapshot: Boolean = false) {
        if (snapshot) snapshot("修改参数")
        val body = TreeOps.replace(scopeBodyInternal(), blockId) { b ->
            b.copy(params = b.params + (key to value))
        }
        updateScopeBody(body)
        save()
    }

    /** Applies a param change that may target a block in any scope (picker / audit / recorder). */
    fun patchParams(blockId: String, patch: Map<String, cn.dumate.a6studio.model.ParamValue>, label: String) {
        snapshot(label)
        _projectState = patchInProject(current, blockId, patch)
        save()
    }

    private fun patchInProject(p: Project, blockId: String, patch: Map<String, cn.dumate.a6studio.model.ParamValue>): Project {
        fun apply(list: List<Block>): List<Block> = TreeOps.replace(list, blockId) { b -> b.copy(params = b.params + patch) }
        return p.copy(
            root = p.root.copy(children = p.root.children.mapValues { (_, v) -> apply(v) }),
            modules = p.modules.map { m -> m.copy(children = m.children.mapValues { (_, v) -> apply(v) }) }
        )
    }

    // ---- modules ------------------------------------------------------------
    /** Appends several blocks at once at the end of the current scope (used by the recorder). */
    fun addBlocksAtScopeEnd(blocks: List<Block>) {
        if (blocks.isEmpty()) return
        val body = scopeBodyInternal().toMutableList()
        body += blocks
        updateScopeBody(body)
        _selectedState = blocks.last().id
        save()
    }

    /** Suggests a name that is not already taken, so repeated clicks never create duplicates. */
    fun nextModuleName(): String {
        val used = current.modules.map { it.name }.toSet()
        var n = current.modules.size + 1
        while (used.contains("模块$n")) n++
        return "模块$n"
    }

    fun addModule(name: String) {
        snapshot("新建模块")
        val m = StudioModule(
            id = Ids.nextWith("mod"),
            name = name,
            children = mapOf("body" to emptyList())
        )
        _projectState = current.copy(modules = current.modules + m)
        _scopeState = Scope.Module(m.id)
        save()
    }

    fun updateModule(id: String, name: String? = null, args: String? = null, desc: String? = null) {
        _projectState = current.copy(
            modules = current.modules.map {
                if (it.id == id) it.copy(
                    name = name ?: it.name,
                    args = args ?: it.args,
                    desc = desc ?: it.desc
                ) else it
            }
        )
        save()
    }

    fun removeModule(id: String) {
        snapshot("删除模块")
        _projectState = current.copy(modules = current.modules.filterNot { it.id == id })
        _scopeState = Scope.Root
        save()
    }

    // ---- tests --------------------------------------------------------------
    fun addTest() {
        snapshot("新增测试")
        val n = current.tests.size + 1
        val t = TestCase(
            id = Ids.nextWith("t"),
            name = "用例 $n",
            code = "// 断言示例：\n\$setScreen('jd.coupon');\nassert(text('天天领豆').exists(), '应存在签到按钮');"
        )
        _projectState = current.copy(tests = current.tests + t)
        save()
    }

    fun updateTest(id: String, name: String? = null, code: String? = null) {
        _projectState = current.copy(
            tests = current.tests.map { if (it.id == id) it.copy(name = name ?: it.name, code = code ?: it.code) else it }
        )
        save()
    }

    fun removeTest(id: String) {
        snapshot("删除测试")
        _projectState = current.copy(tests = current.tests.filterNot { it.id == id })
        save()
    }

    // ---- bridge settings ----------------------------------------------------
    fun updateBridgeUrl(url: String) {
        _projectState = current.copy(bridge = current.bridge.copy(url = url))
        save()
    }

    fun saveDevice(url: String, name: String) {
        if (url.isBlank()) return
        val list = current.bridge.devices.toMutableList()
        val hit = list.indexOfFirst { it.url == url }
        val entry = cn.dumate.a6studio.model.DeviceEntry(url, name.ifBlank { url.removePrefix("http://").removePrefix("https://") })
        if (hit >= 0) list[hit] = entry else list += entry
        _projectState = current.copy(bridge = current.bridge.copy(devices = list))
        save()
    }

    fun setProjectDirect(p: Project) {
        _projectState = p
        _scopeState = Scope.Root
        _selectedState = null
        save()
    }
}