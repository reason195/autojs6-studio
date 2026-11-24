package cn.dumate.a6studio.picker

import cn.dumate.a6studio.bridge.McpClient
import cn.dumate.a6studio.bridge.parseNodes
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Which parameter a picking session writes back into. */
enum class PickKind { SELECTOR, COORD, SWIPE }

data class PickTarget(val blockId: String, val paramKey: String, val kind: PickKind)

enum class PickerView { LIST, SHOT }

/** Everything the picker panel renders. */
data class PickerState(
    val target: PickTarget? = null,
    val view: PickerView = PickerView.LIST,
    val nodes: List<UiNode> = emptyList(),
    val query: String = "",
    val onlyClickable: Boolean = false,
    val selected: UiNode? = null,
    val candidates: List<Candidate> = emptyList(),
    val chosen: Candidate? = null,
    val coord: Pair<Int, Int>? = null,
    val swipePoints: Pair<Pair<Int, Int>?, Pair<Int, Int>?> = (null to null),
    val meta: String = "",
    val hint: String = "",
    val busy: Boolean = false,
    val shotBase64: String? = null,
    val shotMime: String = "image/jpeg",
    val region: IntArray? = null,
    val fullNodes: List<UiNode>? = null,
    val truncated: Boolean = false,
    val error: String? = null,
    val deviceWidth: Int = 1080,
    val deviceHeight: Int = 2376
) {
    val visibleNodes: List<UiNode>
        get() = nodes.filter { PickerLogic.matches(it, query, onlyClickable) }
}

/**
 * Control picker: pulls the accessibility tree from the device, proposes selectors, verifies
 * uniqueness with `ui_find`, and hands the final by/value back to the caller.
 */
class PickerController(
    private val bridge: McpClient,
    private val onState: (PickerState) -> Unit
) {
    var state = PickerState()
        private set

    private fun update(transform: (PickerState) -> PickerState) {
        state = transform(state)
        onState(state)
    }

    suspend fun open(target: PickTarget) {
        update {
            PickerState(
                target = target,
                view = PickerView.LIST,
                swipePoints = null to null
            )
        }
        refresh()
    }

    fun close() = update { PickerState() }

    suspend fun setView(view: PickerView) {
        update { it.copy(view = view) }
        if (view == PickerView.SHOT && state.shotBase64 == null) capture()
    }

    fun setQuery(q: String) = update { it.copy(query = q) }

    fun setOnlyClickable(v: Boolean) = update { it.copy(onlyClickable = v) }

    suspend fun refresh() {
        if (state.target == null) return
        currentCoroutineContext().ensureActive()
        update { it.copy(busy = true, error = null, selected = null, candidates = emptyList(), chosen = null, coord = null) }
        try {
            if (!bridge.isReady) bridge.ping()
            val data = bridge.uiDumpJson(maxNodes = 400, maxDepth = 32)
            val rawNodes = parseNodes(data["nodes"]?.toString() ?: "[]")
            val nodes = rawNodes.map { PickerLogic.normalize(it) }
            val (w, h) = PickerLogic.deviceSize(nodes)
            val window = data["window"] as? JsonObject
            val pkg = window?.get("packageName")?.toString()?.trim('"').orEmpty()
            update {
                it.copy(
                    nodes = nodes,
                    deviceWidth = w,
                    deviceHeight = h,
                    busy = false,
                    region = null,
                    fullNodes = null,
                    truncated = data["truncated"]?.toString() == "true",
                    meta = "$pkg · ${nodes.size} 节点" + if (data["truncated"]?.toString() == "true") " · 已截断" else ""
                )
            }
            if (state.view == PickerView.SHOT) capture()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // M1: cancellation must propagate, or Stop/close never takes effect
        } catch (e: Exception) {
            update { it.copy(busy = false, error = e.message ?: e.toString(), nodes = emptyList()) }
        }
    }

    /** Verifies each candidate in priority order, stopping at the first unique one. */
    suspend fun selectNode(node: UiNode) {
        update { it.copy(selected = node, candidates = emptyList(), chosen = null) }
        if (state.target?.kind == PickKind.SWIPE) {
            takeSwipePoint(node.centerX, node.centerY)
            return
        }
        if (state.target?.kind == PickKind.COORD) return

        val built = PickerLogic.buildCandidates(node)
        update { it.copy(candidates = built.map { c -> c.copy(score = PickerLogic.scoreOf(c, node, null)) }) }

        val verified = mutableListOf<Candidate>()
        var chosen: Candidate? = null
        for (c in built) {
            val v = try {
                val r = bridge.uiFind(PickerLogic.selectorJson(c), 20)
                c.copy(count = r.count).also { it.score = PickerLogic.scoreOf(it, node, r.count) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // M1: cancellation must propagate, or Stop/close never takes effect
            } catch (e: Exception) {
                c.copy(count = -1, error = e.message).also { it.score = PickerLogic.scoreOf(it, node, 0) }
            }
            verified += v
            if (v.count == 1 && chosen == null) chosen = v
            update { it.copy(candidates = verified.toList(), chosen = chosen) }
            if (chosen != null) break
        }
        // Fill in remaining candidates lazily so the panel can show the fallback chain.
        if (chosen != null && verified.size < built.size) {
            val rest = built.drop(verified.size).map { c ->
                try {
                    val r = bridge.uiFind(PickerLogic.selectorJson(c), 20)
                    c.copy(count = r.count).also { it.score = PickerLogic.scoreOf(it, node, r.count) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e // M1: cancellation must propagate, or Stop/close never takes effect
                } catch (e: Exception) {
                    c.copy(count = -1).also { it.score = PickerLogic.scoreOf(it, node, 0) }
                }
            }
            update { it.copy(candidates = verified + rest) }
        }
    }

    fun takeSwipePoint(x: Int, y: Int) {
        update { s ->
            val (a, b) = s.swipePoints
            when {
                a == null -> s.copy(swipePoints = (x to y) to null)
                b == null -> s.copy(swipePoints = a to (x to y))
                else -> s.copy(swipePoints = (x to y) to null)
            }
        }
    }

    fun clearSwipePoints() = update { it.copy(swipePoints = null to null, coord = null) }

    fun setCoord(x: Int, y: Int) = update { it.copy(coord = x to y) }

    suspend fun capture() {
        update { it.copy(hint = "正在截图…") }
        try {
            val shot = bridge.screenCapture()
            update { it.copy(shotBase64 = shot.base64, shotMime = shot.mimeType, hint = "点绿框选控件；点空白处取坐标；拖拽框选区域") }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // M1: cancellation must propagate, or Stop/close never takes effect
        } catch (e: Exception) {
            update { it.copy(hint = "截图失败：${e.message}", shotBase64 = null) }
        }
    }

    /** `ui_dump` cannot take a region, so drill with `ui_find` + `boundsInside`. */
    suspend fun drill(left: Int, top: Int, right: Int, bottom: Int) {
        if (right - left < 12 || bottom - top < 12) {
            update { it.copy(hint = "框选太小，已忽略") }
            return
        }
        update { it.copy(hint = "正在按区域取节点…") }
        try {
            val selector = buildJsonObject {
                put("boundsInside", buildJsonObject {
                    put("left", left); put("top", top); put("right", right); put("bottom", bottom)
                })
            }
            val r = bridge.uiFind(selector, 100)
            val list = parseNodes(r.nodesJson)
                .map { PickerLogic.normalize(it) }
                .filter { it.right > it.left && it.bottom > it.top }
            if (list.isEmpty()) {
                update { it.copy(hint = "该区域没有独立控件：换个范围，或直接点空白取坐标") }
                return
            }
            val hierarchy = PickerLogic.buildHierarchy(list)
            update { s ->
                s.copy(
                    fullNodes = s.fullNodes ?: s.nodes,
                    nodes = hierarchy,
                    region = intArrayOf(left, top, right, bottom),
                    selected = null,
                    candidates = emptyList(),
                    chosen = null,
                    hint = if (r.truncated || r.count > r.returned)
                        "区域命中 ${r.count} 个，仅取到前 ${r.returned} 个，建议再缩小范围"
                    else "已下钻：${hierarchy.size} 个节点"
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // M1: cancellation must propagate, or Stop/close never takes effect
        } catch (e: Exception) {
            update { it.copy(hint = "区域取节点失败：${e.message}") }
        }
    }

    fun clearRegion() {
        val full = state.fullNodes ?: return
        update {
            it.copy(nodes = full, fullNodes = null, region = null, selected = null, candidates = emptyList(), chosen = null, coord = null)
        }
    }
}

/** What the picker hands back to the editor once the user confirms. */
data class PickResult(
    val blockId: String,
    val kind: PickKind,
    val by: String? = null,
    val value: String? = null,
    val expr: String? = null,
    val x: Int? = null,
    val y: Int? = null,
    val x1: Int? = null,
    val y1: Int? = null,
    val x2: Int? = null,
    val y2: Int? = null
)