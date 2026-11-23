package cn.dumate.a6studio.picker

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A UI node from `ui_dump` / `ui_find`, normalised for the picker. */
data class UiNode(
    val depth: Int = 0,
    val className: String = "",
    val text: String = "",
    val desc: String = "",
    val id: String = "",
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val enabled: Boolean = true,
    val visible: Boolean = true,
    val childCount: Int = 0,
    var parentIndex: Int = -1
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val area: Int get() = width * height
    val label: String get() = text.ifEmpty { desc.ifEmpty { id.ifEmpty { className } } }

    fun contains(other: UiNode): Boolean =
        left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom
}

/** One candidate selector for a node, with the verification hit count and a stability score. */
data class Candidate(
    val by: String,
    val value: String,
    val label: String,
    var count: Int? = null,
    var score: Int = 100,
    var error: String? = null
) {
    val expr: String get() = PickerLogic.toExpr(this)
    val unique: Boolean get() = count == 1
}

/** Pure helpers: no I/O, so they are unit-tested directly. */
object PickerLogic {

    private val shortClassRe = Regex("""^android\.(widget|view|webkit)\.""")
    // `{` `}` are quantifier syntax: an unescaped `a{` makes the whole pattern fail.
    private val regexMeta = Regex("""[.*+?^${'$'}()|\[\]{\}\\]""")

    fun shortClass(className: String): String = shortClassRe.replace(className, "")

    fun shortId(id: String): String = id.substringAfterLast('/')

    fun escapeRegex(text: String): String = regexMeta.replace(text) { "\\" + it.value }

    fun normalize(raw: JsonObject): UiNode {
        val bounds = raw["bounds"] as? JsonObject
        fun bi(key: String) = bounds?.get(key)?.jsonPrimitive?.intOrNull ?: 0
        val center = raw["center"] as? kotlinx.serialization.json.JsonArray
        val cx = center?.getOrNull(0)?.jsonPrimitive?.intOrNull ?: ((bi("left") + bi("right")) / 2)
        val cy = center?.getOrNull(1)?.jsonPrimitive?.intOrNull ?: ((bi("top") + bi("bottom")) / 2)
        return UiNode(
            depth = raw["depth"]?.jsonPrimitive?.intOrNull ?: 0,
            className = raw["className"]?.jsonPrimitive?.contentOrNull ?: "",
            text = raw["text"]?.jsonPrimitive?.contentOrNull ?: "",
            desc = raw["desc"]?.jsonPrimitive?.contentOrNull ?: "",
            id = raw["id"]?.jsonPrimitive?.contentOrNull ?: "",
            left = bi("left"),
            top = bi("top"),
            right = if (bi("right") == 0 && cx > 0) cx else bi("right"),
            bottom = if (bi("bottom") == 0 && cy > 0) cy else bi("bottom"),
            clickable = raw["clickable"]?.jsonPrimitive?.booleanOrNull ?: false,
            editable = raw["editable"]?.jsonPrimitive?.booleanOrNull ?: false,
            scrollable = raw["scrollable"]?.jsonPrimitive?.booleanOrNull ?: false,
            enabled = raw["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
            visible = raw["visible"]?.jsonPrimitive?.booleanOrNull ?: true,
            childCount = raw["childCount"]?.jsonPrimitive?.intOrNull ?: 0
        )
    }

    /**
     * Single-condition candidates, in priority order. The selector used for verification is
     * exactly the one written back into the block, so a "unique" verdict always holds.
     */
    fun buildCandidates(node: UiNode): List<Candidate> {
        val out = mutableListOf<Candidate>()
        if (node.id.isNotEmpty()) {
            out += Candidate("id", node.id, "id")
            val suffix = ".*" + escapeRegex(shortId(node.id)) + "$"
            out += Candidate("idMatches", suffix, "id 正则")
        }
        if (node.text.isNotEmpty()) out += Candidate("text", node.text, "文本")
        if (node.desc.isNotEmpty()) out += Candidate("desc", node.desc, "描述")
        if (node.text.isNotEmpty()) out += Candidate("textContains", node.text, "文本包含")
        if (node.desc.isNotEmpty()) out += Candidate("descContains", node.desc, "描述包含")
        return out
    }

    fun scoreOf(candidate: Candidate, node: UiNode, count: Int?): Int {
        var s = 100
        when (candidate.by) {
            "idMatches" -> s -= 8
            "text" -> s -= 10
            "desc" -> s -= 6
            "textContains" -> s -= 16
            "descContains" -> s -= 14
        }
        when {
            count != null && count > 1 -> s -= 8
            count == 0 -> s -= 25
        }
        if (!node.clickable) s -= 5
        return maxOf(0, s)
    }

    private fun fnName(by: String): String = when (by) {
        "id" -> "id"
        "idMatches" -> "idMatches"
        "text" -> "text"
        "desc" -> "desc"
        "textContains" -> "textContains"
        "descContains" -> "descContains"
        else -> "text"
    }

    fun toExpr(candidate: Candidate): String =
        fnName(candidate.by) + "(" + quote(candidate.value) + ")"

    /**
     * One escaper for the whole app: the picker and the code generator used to have separate
     * copies that drifted (, , control chars and U+2028/29 were only handled in one), so a
     * control character in a widget label could break the generated script.
     */
    fun quote(text: String): String = cn.dumate.a6studio.catalog.Js.quote(text)

    /** Highest-scoring unique candidate, else the highest score overall. */
    fun bestOf(candidates: List<Candidate>): Candidate? {
        val unique = candidates.filter { it.count == 1 }.maxByOrNull { it.score }
        return unique ?: candidates.maxByOrNull { it.score }
    }

    /**
     * Fallback chain `A.exists() ? A : B…` so a label change still has a second chance.
     * Only verified candidates (count >= 0) take part, up to three links.
     */
    fun chainExpr(candidates: List<Candidate>): String? {
        val verified = candidates.filter { it.count != null && it.count!! >= 0 }
        val seen = mutableSetOf<String>()
        val exprs = mutableListOf<String>()
        for (c in verified) {
            val e = toExpr(c)
            if (!seen.add(e)) continue
            exprs += e
            if (exprs.size >= 3) break
        }
        if (exprs.isEmpty()) return null
        var out = exprs.last()
        for (i in exprs.size - 2 downTo 0) {
            out = exprs[i] + ".exists() ? " + exprs[i] + " : " + out
        }
        return out
    }

    /** Device size implied by the node bounds (fallbacks keep phone-ish proportions). */
    fun deviceSize(nodes: List<UiNode>): Pair<Int, Int> {
        var w = 0
        var h = 0
        nodes.forEach { w = maxOf(w, it.right); h = maxOf(h, it.bottom) }
        return (w.takeIf { it > 0 } ?: 1080) to (h.takeIf { it > 0 } ?: 2376)
    }

    fun matches(node: UiNode, query: String, onlyClickable: Boolean): Boolean {
        if (onlyClickable && !node.clickable) return false
        val kw = query.trim().lowercase()
        if (kw.isEmpty()) return true
        return (node.text + " " + node.desc + " " + node.id + " " + node.className).lowercase().contains(kw)
    }

    /**
     * `ui_find` with `boundsInside` returns a flat list; rebuild depth/order from bounds
     * containment so the tree view still nests correctly after a region drill-down.
     */
    fun buildHierarchy(list: List<UiNode>): List<UiNode> {
        val out = list.map { it.copy(parentIndex = -1) }.toMutableList()
        out.forEachIndexed { i, node ->
            var best = -1
            var bestArea = Int.MAX_VALUE
            out.forEachIndexed { j, other ->
                if (i == j) return@forEachIndexed
                val wraps = other.contains(node) &&
                    (other.area > node.area || (other.area == node.area && other.depth < node.depth))
                if (wraps && other.area < bestArea) {
                    best = j
                    bestArea = other.area
                }
            }
            out[i] = out[i].copy(parentIndex = best)
        }
        // Recompute depth, guarding against cycles.
        out.forEachIndexed { i, node ->
            var d = 0
            var p = node.parentIndex
            val seen = mutableSetOf<Int>()
            while (p >= 0 && p < out.size && seen.add(p)) { d++; p = out[p].parentIndex }
            out[i] = out[i].copy(depth = d)
        }
        fun sortKey(n: UiNode) = n.top * 10_000 + n.left
        val roots = out.indices.filter { out[it].parentIndex < 0 }.sortedBy { sortKey(out[it]) }
        val ordered = mutableListOf<UiNode>()
        fun walk(idx: Int) {
            ordered += out[idx]
            out.indices.filter { out[it].parentIndex == idx }
                .sortedBy { sortKey(out[it]) }
                .forEach { walk(it) }
        }
        roots.forEach { walk(it) }
        return ordered
    }

    /** Selector object for `ui_find`, from a candidate. */
    fun selectorJson(candidate: Candidate): JsonObject =
        JsonObject(mapOf(candidate.by to JsonPrimitive(candidate.value)))

    val json = Json { ignoreUnknownKeys = true; isLenient = true }
}