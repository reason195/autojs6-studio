package cn.dumate.a6studio.audit

import cn.dumate.a6studio.bridge.McpClient
import cn.dumate.a6studio.bridge.parseNodes
import cn.dumate.a6studio.catalog.Catalog
import cn.dumate.a6studio.model.Block
import cn.dumate.a6studio.model.Project
import cn.dumate.a6studio.picker.Candidate
import cn.dumate.a6studio.picker.PickerLogic
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class AuditStatus { IDLE, UNIQUE, MULTIPLE, MISSING, ERROR, EXPR }

data class AuditRow(
    val blockId: String,
    val scope: String,
    val name: String,
    val by: String,
    val value: String,
    val expr: String,
    var status: AuditStatus = AuditStatus.IDLE,
    var count: Int? = null,
    var note: String = "",
    var fix: Pair<String, String>? = null,
    var score: Int? = null
)

/**
 * Selector health check: every by/value selector in the project is verified against the live
 * screen. Multiple hits are narrowed by trying the hit nodes' own candidates, and a single
 * unique alternative becomes a one-click fix. Read-only until the user applies fixes.
 */
class AuditController(
    private val bridge: McpClient,
    private val onRows: (List<AuditRow>, String) -> Unit
) {
    private val bySet = setOf("id", "idMatches", "text", "textContains", "desc", "descContains")
    private val fn = mapOf(
        "id" to "id", "idMatches" to "idMatches", "text" to "text",
        "textContains" to "textContains", "desc" to "desc", "descContains" to "descContains"
    )

    var rows: List<AuditRow> = emptyList()
        private set
    var busy = false
        private set

    fun selText(by: String, value: String): String =
        (fn[by] ?: "text") + "(" + PickerLogic.quote(value) + ")"

    fun collect(project: Project): List<AuditRow> {
        val out = mutableListOf<AuditRow>()
        fun add(b: Block, scope: String) {
            val p = b.params
            val by = p["by"]?.asText() ?: ""
            val value = p["value"]?.asText() ?: ""
            if (by !in bySet || value.isBlank()) return
            val expr = p["expr"]?.asText()?.takeIf { it.isNotBlank() } ?: ""
            val def = Catalog.byType(b.type)
            out += AuditRow(
                blockId = b.id,
                scope = scope,
                name = def?.let { Catalog.cleanName(it) } ?: b.type,
                by = by,
                value = value,
                expr = expr,
                status = if (expr.isNotEmpty()) AuditStatus.EXPR else AuditStatus.IDLE,
                note = if (expr.isNotEmpty()) "已填表达式，代码走 expr，by/value 不再使用" else ""
            )
        }
        fun walk(list: List<Block>, scope: String) {
            list.forEach { b ->
                add(b, scope)
                b.children.forEach { (_, kids) -> walk(kids, scope) }
            }
        }
        walk(project.root.children["body"].orEmpty(), "主程序")
        project.modules.forEach { m -> walk(m.children["body"].orEmpty(), "模块：${m.name}") }
        return out
    }

    suspend fun run(project: Project) {
        if (busy) return
        busy = true
        try {
            rows = collect(project)
            onRows(rows, if (rows.isEmpty()) "项目里没有 by/value 选择器积木" else "正在逐个校验…")
            if (rows.isEmpty()) return

            for (i in rows.indices) {
                // Each auditOne is a phone round-trip (up to 15 s); stop promptly when the user
                // closes the panel instead of grinding through the remaining rows.
                currentCoroutineContext().ensureActive()
                rows = rows.toMutableList().also { it[i] = auditOne(it[i]) }
                onRows(rows, "体检中 ${i + 1}/${rows.size}…")
            }
        } finally {
            busy = false
        }
        val bad = rows.count { it.status in setOf(AuditStatus.MULTIPLE, AuditStatus.MISSING, AuditStatus.ERROR) }
        onRows(
            rows,
            if (bad > 0) "$bad 个选择器有问题：能自动收窄的已给建议，其余用「拾取器」重拾；修好会先快照。"
            else "全部唯一，没有需要改的选择器。"
        )
    }

    private suspend fun auditOne(row: AuditRow): AuditRow {
        if (row.expr.isNotEmpty()) return row.copy(status = AuditStatus.EXPR)
        val selector = buildJsonObject { put(row.by, row.value) }
        return try {
            val f = bridge.uiFind(selector, 20)
            when (f.count) {
                0 -> {
                    var note = "这条选择器在当前页面找不到节点"
                    runCatching {
                        val explain = bridge.explainSelector(selector)
                        val o = PickerLogic.json.parseToJsonElement(explain).let { it as? JsonObject }
                        val fail = o?.get("firstFail")?.let { (it as? JsonPrimitive)?.content }
                            ?: o?.get("firstFailingCondition")?.let { (it as? JsonPrimitive)?.content }
                        if (!fail.isNullOrBlank()) note += "；卡在「$fail」"
                    }
                    row.copy(status = AuditStatus.MISSING, count = 0, note = note)
                }
                1 -> {
                    val node = parseNodes(f.nodesJson).firstOrNull()?.let { PickerLogic.normalize(it) }
                    val score = node?.let { PickerLogic.scoreOf(Candidate(row.by, row.value, ""), it, 1) }
                    row.copy(status = AuditStatus.UNIQUE, count = 1, note = "", score = score)
                }
                else -> {
                    var note = "命中 ${f.count} 个，点击会落到第一个"
                    val first = parseNodes(f.nodesJson).firstOrNull()?.let { PickerLogic.normalize(it) }
                    var fix: Pair<String, String>? = null
                    if (first != null) {
                        val alternatives = PickerLogic.buildCandidates(first)
                            .filterNot { it.by == row.by && it.value == row.value }
                        for (c in alternatives) {
                            val cf = runCatching { bridge.uiFind(PickerLogic.selectorJson(c), 20) }.getOrNull()
                            if (cf?.count == 1) {
                                fix = c.by to c.value
                                note += "；可用 " + selText(c.by, c.value) + " 收窄到唯一"
                                break
                            }
                        }
                    }
                    row.copy(status = AuditStatus.MULTIPLE, count = f.count, note = note, fix = fix)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // M1: cancellation must propagate, or Stop/close never takes effect
        } catch (e: Exception) {
            row.copy(status = AuditStatus.ERROR, note = "体检失败：${e.message}")
        }
    }

    fun report(): String {
        if (rows.isEmpty()) return "选择器体检报告\n\n（还没有体检结果）\n"
        val head = "| 状态 | 积木 | 范围 | 选择器 | 说明 |\n| --- | --- | --- | --- | --- |"
        val lines = rows.map { r ->
            val st = when (r.status) {
                AuditStatus.UNIQUE -> "唯一"
                AuditStatus.MULTIPLE -> "${r.count} 个"
                AuditStatus.MISSING -> "未找到"
                AuditStatus.ERROR -> "出错"
                AuditStatus.EXPR -> "表达式"
                AuditStatus.IDLE -> "待检"
            }
            "| $st | ${r.name} | ${r.scope} | `${selText(r.by, r.value)}` | ${r.note} |"
        }
        return "选择器体检报告\n\n" + (listOf(head) + lines).joinToString("\n") + "\n"
    }
}