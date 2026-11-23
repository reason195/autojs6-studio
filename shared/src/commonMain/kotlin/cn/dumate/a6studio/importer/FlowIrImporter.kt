package cn.dumate.a6studio.importer

import cn.dumate.a6studio.model.Block
import cn.dumate.a6studio.model.ParamValue
import cn.dumate.a6studio.model.Project
import cn.dumate.a6studio.model.Samples
import cn.dumate.a6studio.model.newBlock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 跨项目 JSON 流程导入器：
 * 1. 支持导入 Jingdou Studio 的 `flow.schema.json` IR 文件（含 `irVersion`, `vars`, `target`, `policy`, `flow`）
 * 2. 支持导入 OmniForge-Studio 的工作流 JSON 文件（含 `nodes` 数组）
 */
object FlowIrImporter {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val varPlaceholderRe = Regex("\\$\\{([A-Za-z0-9_]+)\\}")

    fun tryImport(source: String, fallbackName: String): ImportResult? {
        val trimmed = source.trim()
        if (!trimmed.startsWith("{")) return null
        val root = runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull() ?: return null

        // 1. Jingdou Studio IR 格式
        if (root.containsKey("flow") && root["flow"] is JsonArray) {
            return importJingdouIr(root, fallbackName)
        }
        // 2. OmniForge-Studio Workflow 格式
        if (root.containsKey("nodes") && root["nodes"] is JsonArray) {
            return importOmniForgeIr(root, fallbackName)
        }
        return null
    }

    private fun importJingdouIr(root: JsonObject, fallbackName: String): ImportResult {
        val projName = root["name"]?.jsonPrimitive?.contentOrNull?.ifBlank { fallbackName } ?: fallbackName
        val target = root["target"] as? JsonObject
        val policy = root["policy"] as? JsonObject
        val varsObj = root["vars"] as? JsonObject
        val vars = varsObj?.mapValues { (_, v) -> v.jsonPrimitive.contentOrNull.orEmpty() }.orEmpty()

        fun resolveVars(raw: String): String =
            varPlaceholderRe.replace(raw) { m -> vars[m.groupValues[1]] ?: m.value }

        val pkg = target?.get("pkg")?.jsonPrimitive?.contentOrNull ?: "com.jingdong.app.mall"
        val maxRuntimeMs = policy?.get("maxRuntimeMs")?.jsonPrimitive?.doubleOrNull ?: 900_000.0

        val body = mutableListOf<Block>()
        body += newBlock("autoWait")
        body += newBlock(
            "kitBoot",
            mapOf(
                "pkg" to ParamValue.Text(pkg),
                "name" to ParamValue.Text(projName),
                "maxRuntimeMs" to ParamValue.Num(maxRuntimeMs)
            )
        )

        var mapped = 2
        val flowArr = root["flow"] as? JsonArray ?: JsonArray(emptyList())

        fun mapNode(node: JsonObject) {
            val kind = node["kind"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val label = node["label"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (label.isNotBlank()) {
                body += newBlock("comment", mapOf("text" to ParamValue.Text(label)))
            }
            when (kind) {
                "launch" -> {
                    body += newBlock("kitLaunch")
                    mapped++
                }
                "openDeepLink" -> {
                    val url = resolveVars(node["url"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    body += newBlock("kitOpenDeepLink", mapOf("url" to ParamValue.Text(url)))
                    mapped++
                }
                "waitFor" -> {
                    val sel = node["selector"] as? JsonObject
                    val by = sel?.get("by")?.jsonPrimitive?.contentOrNull ?: "text"
                    val match = sel?.get("match")?.jsonPrimitive?.contentOrNull ?: "contains"
                    val value = resolveVars(sel?.get("value")?.jsonPrimitive?.contentOrNull.orEmpty())
                    val timeoutMs = node["timeoutMs"]?.jsonPrimitive?.doubleOrNull ?: 15_000.0
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
                    val sel = node["selector"] as? JsonObject
                    val by = sel?.get("by")?.jsonPrimitive?.contentOrNull ?: "text"
                    val match = sel?.get("match")?.jsonPrimitive?.contentOrNull ?: "contains"
                    val value = resolveVars(sel?.get("value")?.jsonPrimitive?.contentOrNull.orEmpty())
                    if (value.isNotBlank()) {
                        body += newBlock(
                            "kitClick",
                            mapOf(
                                "by" to ParamValue.Text(by),
                                "match" to ParamValue.Text(match),
                                "value" to ParamValue.Text(value)
                            )
                        )
                        mapped++
                    }
                }
                "waitCountdown", "dwell" -> {
                    val minSec = node["minDwellSec"]?.jsonPrimitive?.doubleOrNull ?: 12.0
                    val graceSec = node["graceSec"]?.jsonPrimitive?.doubleOrNull ?: 2.0
                    body += newBlock(
                        "kitWaitCountdown",
                        mapOf("minDwellSec" to ParamValue.Num(minSec), "graceSec" to ParamValue.Num(graceSec))
                    )
                    mapped++
                }
                "log" -> {
                    val msg = resolveVars(node["value"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    body += newBlock("log", mapOf("text" to ParamValue.Text(msg)))
                    mapped++
                }
                "sleep" -> {
                    val ms = node["ms"]?.jsonPrimitive?.doubleOrNull ?: 1000.0
                    body += newBlock("sleep", mapOf("ms" to ParamValue.Num(ms)))
                    mapped++
                }
                "back" -> {
                    body += newBlock("pressBack")
                    mapped++
                }
                "finish" -> {
                    val res = node["result"]?.jsonPrimitive?.contentOrNull ?: "auto"
                    body += newBlock("kitFinish", mapOf("result" to ParamValue.Text(res)))
                    mapped++
                }
                else -> {
                    // 复合节点（如 taskLoop / channelRun）递归展开其内部 steps 或注入 kitGuard + 默认动作
                    body += newBlock("kitGuard")
                    mapped++
                    val steps = node["steps"] as? JsonArray
                    steps?.forEach { child -> (child as? JsonObject)?.let(::mapNode) }
                }
            }
        }

        flowArr.forEach { el -> (el as? JsonObject)?.let(::mapNode) }
        if (body.none { it.type == "kitFinish" }) {
            body += newBlock("kitFinish", mapOf("result" to ParamValue.Text("auto")))
            mapped++
        }

        val proj = Samples.emptyProject().let {
            it.copy(
                name = projName,
                root = it.root.copy(children = mapOf("body" to body))
            )
        }
        return ImportResult(
            project = proj,
            mappedCount = mapped,
            rawCount = 0,
            notes = listOf("已将 Jingdou Studio IR 流程「$projName」转换为 $mapped 个护栏积木")
        )
    }

    private fun importOmniForgeIr(root: JsonObject, fallbackName: String): ImportResult {
        val projName = root["name"]?.jsonPrimitive?.contentOrNull?.ifBlank { fallbackName } ?: fallbackName
        val pkg = root["appPackage"]?.jsonPrimitive?.contentOrNull ?: "com.jingdong.app.mall"
        val nodesArr = root["nodes"] as? JsonArray ?: JsonArray(emptyList())

        val body = mutableListOf<Block>()
        body += newBlock("autoWait")
        body += newBlock(
            "kitBoot",
            mapOf("pkg" to ParamValue.Text(pkg), "name" to ParamValue.Text(projName), "maxRuntimeMs" to ParamValue.Num(600_000.0))
        )
        var mapped = 2

        for (el in nodesArr) {
            val n = el as? JsonObject ?: continue
            if (n["enabled"]?.jsonPrimitive?.contentOrNull == "false") continue
            val type = n["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val label = n["label"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val cfg = n["config"] as? JsonObject ?: JsonObject(emptyMap())
            if (label.isNotBlank()) body += newBlock("comment", mapOf("text" to ParamValue.Text(label)))
            when (type) {
                "launch_app" -> {
                    body += newBlock("kitLaunch")
                    mapped++
                }
                "open_scheme" -> {
                    val uri = cfg["schemeUrl"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    body += newBlock("kitOpenDeepLink", mapOf("url" to ParamValue.Text(uri)))
                    mapped++
                }
                "click_target", "ocr_find" -> {
                    val kw = cfg["keyword"]?.jsonPrimitive?.contentOrNull
                        ?: cfg["targetText"]?.jsonPrimitive?.contentOrNull
                        ?: "去完成"
                    body += newBlock(
                        "kitClick",
                        mapOf("by" to ParamValue.Text("text"), "match" to ParamValue.Text("contains"), "value" to ParamValue.Text(kw))
                    )
                    mapped++
                }
                "swipe_wait" -> {
                    val ms = cfg["waitMs"]?.jsonPrimitive?.doubleOrNull ?: 3000.0
                    body += newBlock("sleep", mapOf("ms" to ParamValue.Num(ms)))
                    mapped++
                }
                "back_guard" -> {
                    body += newBlock("kitGuard")
                    mapped++
                }
            }
        }
        body += newBlock("kitFinish", mapOf("result" to ParamValue.Text("auto")))
        mapped++

        val proj = Samples.emptyProject().let {
            it.copy(name = projName, root = it.root.copy(children = mapOf("body" to body)))
        }
        return ImportResult(
            project = proj,
            mappedCount = mapped,
            rawCount = 0,
            notes = listOf("已将 OmniForge-Studio 工作流「$projName」转换为 $mapped 个护栏积木")
        )
    }
}
