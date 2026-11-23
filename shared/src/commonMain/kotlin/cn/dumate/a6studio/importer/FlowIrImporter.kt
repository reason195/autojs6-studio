package cn.dumate.a6studio.importer

import cn.dumate.a6studio.model.Block
import cn.dumate.a6studio.model.ParamValue
import cn.dumate.a6studio.model.Samples
import cn.dumate.a6studio.model.newBlock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * 跨项目 JSON 流程导入器：
 * 1. 支持导入 Jingdou Studio 的 `flow.schema.json` IR 文件（含 `irVersion`, `vars`, `target`, `policy`, `flow`）
 * 2. 支持导入 OmniForge-Studio 的工作流 JSON 文件（含 `nodes` 数组）
 *
 * N-M9 修复：
 * - `policy` 的 skipKeywords / riskKeywords / riskyActivityRe / forbiddenZones / riskWaitMs 原样存进 kitBoot.policyJson，
 *   代码生成时与默认护栏合并（只加严，见 KitPolicy），不再只取 maxRuntimeMs；
 * - 认不出的节点不再一律变成 kitGuard：保留成注释并计入 rawCount 与 notes，用户能看到丢了什么；
 * - 所有字段都用安全取值（非原始值返回 null），畸形输入不再抛 IllegalArgumentException；
 * - 递归深度上限 [MAX_DEPTH]，节点总数上限 [MAX_NODES]，防止深层嵌套导致 StackOverflowError。
 */
object FlowIrImporter {

    const val MAX_DEPTH = 32
    const val MAX_NODES = 2000

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val varPlaceholderRe = Regex("\\$\\{([A-Za-z0-9_]+)\\}")
    private val POLICY_KEYS = listOf("skipKeywords", "riskKeywords", "riskyActivityRe", "forbiddenZones", "riskWaitMs")

    // ---- 安全取值：任何非预期类型都返回 null，绝不抛异常 ----
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        ?.takeIf { it.isFinite() }
    private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }
    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.arr(): JsonArray? = this as? JsonArray

    fun tryImport(source: String, fallbackName: String): ImportResult? {
        val trimmed = source.trim()
        if (!trimmed.startsWith("{")) return null
        val root = runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull() ?: return null
        // 兜底：即便上面的安全取值漏掉了某种情况，也只返回 null 让调用方按普通文件处理，绝不把异常抛给 UI
        return runCatching {
            when {
                root["flow"] is JsonArray -> importJingdouIr(root, fallbackName)
                root["nodes"] is JsonArray -> importOmniForgeIr(root, fallbackName)
                else -> null
            }
        }.getOrNull()
    }

    private fun importJingdouIr(root: JsonObject, fallbackName: String): ImportResult {
        val projName = root["name"].str()?.trim()?.ifBlank { null } ?: fallbackName
        val target = root["target"].obj()
        val policy = root["policy"].obj()
        val vars: Map<String, String> = root["vars"].obj()
            ?.mapNotNull { (k, v) -> (v.str() ?: v.num()?.toString())?.let { k to it } }
            ?.toMap().orEmpty()

        fun resolveVars(raw: String): String =
            varPlaceholderRe.replace(raw) { m -> vars[m.groupValues[1]] ?: m.value }

        val pkg = target?.get("pkg").str()?.ifBlank { null } ?: "com.jingdong.app.mall"
        val maxRuntimeMs = policy?.get("maxRuntimeMs").num()?.takeIf { it > 0 } ?: 900_000.0
        val policyJson = policy
            ?.let { p -> JsonObject(p.filterKeys { it in POLICY_KEYS }) }
            ?.takeIf { it.isNotEmpty() }
            ?.toString()
            .orEmpty()

        val body = mutableListOf<Block>()
        val notes = mutableListOf<String>()
        val unmapped = mutableListOf<String>()
        body += newBlock("autoWait")
        body += newBlock(
            "kitBoot",
            mapOf(
                "pkg" to ParamValue.Text(pkg),
                "name" to ParamValue.Text(projName),
                "maxRuntimeMs" to ParamValue.Num(maxRuntimeMs),
                "policyJson" to ParamValue.Text(policyJson)
            )
        )

        var mapped = 2
        var visited = 0
        var depthHit = false
        var nodeCapHit = false

        fun selectorParams(node: JsonObject): Triple<String, String, String> {
            val sel = node["selector"].obj()
            val by = sel?.get("by").str()?.takeIf { it == "text" || it == "desc" } ?: "text"
            val match = sel?.get("match").str()?.takeIf { it in setOf("contains", "eq", "regex") } ?: "contains"
            val value = resolveVars(sel?.get("value").str().orEmpty())
            return Triple(by, match, value)
        }

        fun keepUnmapped(kind: String, label: String) {
            val tag = kind.ifBlank { "(无 kind)" }
            unmapped += tag
            body += newBlock(
                "comment",
                mapOf("text" to ParamValue.Text("未转换的 IR 节点：$tag${if (label.isNotBlank()) "（$label）" else ""}"))
            )
        }

        fun mapNode(node: JsonObject, depth: Int) {
            if (depth > MAX_DEPTH) { depthHit = true; return }
            if (++visited > MAX_NODES) { nodeCapHit = true; return }
            val kind = node["kind"].str().orEmpty()
            val label = node["label"].str().orEmpty()
            if (label.isNotBlank() && kind != "") {
                body += newBlock("comment", mapOf("text" to ParamValue.Text(label)))
            }
            when (kind) {
                "launch" -> { body += newBlock("kitLaunch"); mapped++ }
                "openDeepLink" -> {
                    val url = resolveVars(node["url"].str().orEmpty())
                    if (url.isNotBlank()) {
                        body += newBlock("kitOpenDeepLink", mapOf("url" to ParamValue.Text(url))); mapped++
                    } else keepUnmapped(kind, "缺少 url")
                }
                "waitFor" -> {
                    val (by, match, value) = selectorParams(node)
                    val timeoutMs = node["timeoutMs"].num()?.takeIf { it > 0 } ?: 15_000.0
                    body += newBlock(
                        "kitWaitFor",
                        mapOf(
                            "by" to ParamValue.Text(by),
                            "match" to ParamValue.Text(match),
                            "value" to ParamValue.Text(value),
                            "timeoutMs" to ParamValue.Num(timeoutMs)
                        )
                    )
                    mapped++
                }
                "click" -> {
                    val (by, match, value) = selectorParams(node)
                    if (value.isNotBlank()) {
                        body += newBlock(
                            "kitClick",
                            mapOf("by" to ParamValue.Text(by), "match" to ParamValue.Text(match), "value" to ParamValue.Text(value))
                        )
                        mapped++
                    } else keepUnmapped(kind, "缺少 selector.value")
                }
                "waitCountdown", "dwell" -> {
                    val minSec = node["minDwellSec"].num()?.takeIf { it > 0 } ?: 12.0
                    val graceSec = node["graceSec"].num()?.takeIf { it >= 0 } ?: 2.0
                    body += newBlock(
                        "kitWaitCountdown",
                        mapOf("minDwellSec" to ParamValue.Num(minSec), "graceSec" to ParamValue.Num(graceSec))
                    )
                    mapped++
                }
                "log" -> {
                    body += newBlock("log", mapOf("text" to ParamValue.Text(resolveVars(node["value"].str().orEmpty()))))
                    mapped++
                }
                "sleep" -> {
                    val ms = node["ms"].num()?.coerceIn(0.0, 600_000.0) ?: 1000.0
                    body += newBlock("sleep", mapOf("ms" to ParamValue.Num(ms)))
                    mapped++
                }
                "back" -> { body += newBlock("pressBack"); mapped++ }
                "guard" -> { body += newBlock("kitGuard"); mapped++ }
                "finish" -> {
                    val res = node["result"].str()?.takeIf { it in setOf("auto", "success", "warn", "failed") } ?: "auto"
                    body += newBlock("kitFinish", mapOf("result" to ParamValue.Text(res)))
                    mapped++
                }
                else -> {
                    val steps = node["steps"].arr()
                    if (steps != null) {
                        // 复合节点（taskLoop / channelRun 等）：先过一次护栏，再展开内部 steps。
                        // 复合节点本身的循环语义无法还原，在注释里说明
                        body += newBlock("kitGuard")
                        mapped++
                        body += newBlock(
                            "comment",
                            mapOf("text" to ParamValue.Text("复合节点「${kind.ifBlank { "?" }}」已按顺序展开一次，原循环/条件语义未保留"))
                        )
                        unmapped += "$kind(语义)"
                        steps.forEach { child -> child.obj()?.let { mapNode(it, depth + 1) } }
                    } else {
                        keepUnmapped(kind, label)
                    }
                }
            }
        }

        root["flow"].arr()?.forEach { el -> el.obj()?.let { mapNode(it, 1) } }
        if (body.none { it.type == "kitFinish" }) {
            body += newBlock("kitFinish", mapOf("result" to ParamValue.Text("auto")))
            mapped++
        }

        notes += "已将 Jingdou Studio IR 流程「$projName」转换为 $mapped 个护栏积木"
        if (policyJson.isNotEmpty()) notes += "已保留 IR 护栏策略（与默认护栏合并）"
        if (unmapped.isNotEmpty()) {
            val summary = unmapped.groupingBy { it }.eachCount().entries.joinToString("、") { "${it.key}×${it.value}" }
            notes += "${unmapped.size} 个节点未能完整转换（已保留为注释）：$summary"
        }
        if (depthHit) notes += "嵌套超过 $MAX_DEPTH 层的节点已忽略"
        if (nodeCapHit) notes += "节点超过 $MAX_NODES 个，其余已忽略"

        val proj = Samples.emptyProject().let {
            it.copy(name = projName, root = it.root.copy(children = mapOf("body" to body)))
        }
        return ImportResult(project = proj, mappedCount = mapped, rawCount = unmapped.size, notes = notes)
    }

    private fun importOmniForgeIr(root: JsonObject, fallbackName: String): ImportResult {
        val projName = root["name"].str()?.trim()?.ifBlank { null } ?: fallbackName
        val pkg = root["appPackage"].str()?.ifBlank { null } ?: "com.jingdong.app.mall"
        val nodesArr = root["nodes"].arr() ?: JsonArray(emptyList())

        val body = mutableListOf<Block>()
        val unmapped = mutableListOf<String>()
        body += newBlock("autoWait")
        body += newBlock(
            "kitBoot",
            mapOf("pkg" to ParamValue.Text(pkg), "name" to ParamValue.Text(projName), "maxRuntimeMs" to ParamValue.Num(600_000.0))
        )
        var mapped = 2

        for (el in nodesArr.take(MAX_NODES)) {
            val n = el.obj() ?: continue
            if (n["enabled"].bool() == false) continue
            val type = n["type"].str().orEmpty()
            val label = n["label"].str().orEmpty()
            val cfg = n["config"].obj() ?: buildJsonObject { }
            if (label.isNotBlank()) body += newBlock("comment", mapOf("text" to ParamValue.Text(label)))
            when (type) {
                "launch_app" -> { body += newBlock("kitLaunch"); mapped++ }
                "open_scheme" -> {
                    val uri = cfg["schemeUrl"].str().orEmpty()
                    if (uri.isNotBlank()) {
                        body += newBlock("kitOpenDeepLink", mapOf("url" to ParamValue.Text(uri))); mapped++
                    } else {
                        unmapped += type
                        body += newBlock("comment", mapOf("text" to ParamValue.Text("未转换：open_scheme 缺少 schemeUrl")))
                    }
                }
                "click_target", "ocr_find" -> {
                    val kw = cfg["keyword"].str()?.ifBlank { null } ?: cfg["targetText"].str()?.ifBlank { null }
                    if (kw != null) {
                        body += newBlock(
                            "kitClick",
                            mapOf("by" to ParamValue.Text("text"), "match" to ParamValue.Text("contains"), "value" to ParamValue.Text(kw))
                        )
                        mapped++
                    } else {
                        // 原实现默认点「去完成」：没给目标就乱点，危险。改为保留注释
                        unmapped += type
                        body += newBlock("comment", mapOf("text" to ParamValue.Text("未转换：$type 缺少 keyword/targetText")))
                    }
                }
                "swipe_wait" -> {
                    val ms = cfg["waitMs"].num()?.coerceIn(0.0, 600_000.0) ?: 3000.0
                    body += newBlock("sleep", mapOf("ms" to ParamValue.Num(ms)))
                    mapped++
                }
                "back_guard" -> { body += newBlock("kitGuard"); mapped++ }
                else -> {
                    unmapped += type.ifBlank { "(无 type)" }
                    body += newBlock("comment", mapOf("text" to ParamValue.Text("未转换的工作流节点：${type.ifBlank { "(无 type)" }}")))
                }
            }
        }
        body += newBlock("kitFinish", mapOf("result" to ParamValue.Text("auto")))
        mapped++

        val notes = mutableListOf("已将 OmniForge-Studio 工作流「$projName」转换为 $mapped 个护栏积木")
        if (unmapped.isNotEmpty()) {
            val summary = unmapped.groupingBy { it }.eachCount().entries.joinToString("、") { "${it.key}×${it.value}" }
            notes += "${unmapped.size} 个节点未能转换（已保留为注释）：$summary"
        }
        if (nodesArr.size > MAX_NODES) notes += "节点超过 $MAX_NODES 个，其余已忽略"

        val proj = Samples.emptyProject().let {
            it.copy(name = projName, root = it.root.copy(children = mapOf("body" to body)))
        }
        return ImportResult(project = proj, mappedCount = mapped, rawCount = unmapped.size, notes = notes)
    }
}
