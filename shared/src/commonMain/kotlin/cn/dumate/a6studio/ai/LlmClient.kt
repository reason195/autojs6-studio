package cn.dumate.a6studio.ai

import cn.dumate.a6studio.catalog.Js
import cn.dumate.a6studio.security.Redaction
import cn.dumate.a6studio.security.RedactionStats
import cn.dumate.a6studio.security.UrlSecurity
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class LlmProviderId(val id: String, val label: String) {
    AUTO("auto", "自动路由（云端 → 本地 → 规则引擎）"),
    GEMINI("gemini", "Google Gemini"),
    OPENAI_COMPATIBLE("openai_compatible", "OpenAI 兼容接口"),
    OLLAMA("ollama", "Ollama（本地）"),
    LMSTUDIO("lmstudio", "LM Studio（本地）"),
    DETERMINISTIC_RULES("deterministic_rules", "确定性规则引擎（离线）")
}

/** 运行时模型配置（仅存内存，绝不写入 .a6proj.json 工程文件）。 */
data class LlmConfig(
    val geminiApiKey: String = "",
    val geminiModel: String = "gemini-2.5-flash",
    val openAiBaseUrl: String = "",
    val openAiApiKey: String = "",
    val openAiModel: String = "gpt-4o-mini",
    val ollamaBaseUrl: String = "http://127.0.0.1:11434/v1",
    val ollamaModel: String = "qwen2.5:7b",
    val lmStudioBaseUrl: String = "http://127.0.0.1:1234/v1",
    val lmStudioModel: String = "local-model",
    val allowLocalInference: Boolean = true
)

data class LlmRefineInput(
    val goal: String,
    val workflowName: String,
    val appPackage: String,
    val fallbackKeywords: List<String> = emptyList(),
    val autojsState: JsonElement = JsonNull,
    val proxypinTraffic: JsonElement = JsonNull,
    val provider: LlmProviderId = LlmProviderId.AUTO,
    val timeoutMs: Long = 20_000L
)

@Serializable
data class BranchConditionHint(
    val `when`: String,
    val action: String,
    val reason: String
)

@Serializable
data class ExtractedApiHint(
    val endpoint: String,
    val field: String,
    val meaning: String
)

data class LlmRefineOutput(
    val refinedKeywords: List<String>,
    val recommendedBrowseWaitMs: Int,
    val branchConditions: List<BranchConditionHint>,
    val extractedApiHints: List<ExtractedApiHint>,
    val scriptSnippet: String,
    val modelUsed: String,
    val providerUsed: LlmProviderId,
    val routingNote: String,
    val redactionStats: RedactionStats
)

/**
 * 多通道 LLM 提炼客户端 + 离线确定性规则兜底引擎。
 * 移植并融合自 OmniForge-Studio `src/server/llmProviders.ts` 与 `src/core/deterministicRefiner.ts`。
 */
class LlmClient(private val http: HttpClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        const val DETERMINISTIC_MODEL_ID = "deterministic-rule-engine-v1"
        private val TERMINAL_TEXTS = setOf("已完成", "已领取", "DONE", "COMPLETED", "已结束", "已失效")
        private val ACTION_PREFIX_RE = Regex("^(去|立即|马上|点击|继续|领取|签到|逛|看)")
        private const val SYSTEM_PROMPT =
            "你是资深移动端自动化与抓包协议分析工程师。严禁输出任何硬编码密钥、Token 或真实手机号。" +
                "必须严格按 JSON 格式返回结果，包含 refinedKeywords (string[])、recommendedBrowseWaitMs (integer)、" +
                "branchConditions (array of {when, action, reason})、extractedApiHints (array of {endpoint, field, meaning})、" +
                "scriptSnippet (string)。按钮候选词应按可执行优先级排序，排除“已完成”等终态词。"
    }

    suspend fun refine(input: LlmRefineInput, config: LlmConfig = LlmConfig()): LlmRefineOutput {
        // 1. 强制在离开本机前执行流量与状态脱敏
        val trafficReport = Redaction.redactTrafficPayload(input.proxypinTraffic)
        val stateReport = Redaction.redactTrafficPayload(input.autojsState)
        val totalStats = RedactionStats(
            redactedKeyCount = trafficReport.stats.redactedKeyCount + stateReport.stats.redactedKeyCount,
            maskedPatternCount = trafficReport.stats.maskedPatternCount + stateReport.stats.maskedPatternCount,
            truncatedCount = trafficReport.stats.truncatedCount + stateReport.stats.truncatedCount
        )

        val candidates = when (input.provider) {
            LlmProviderId.AUTO -> listOf(
                LlmProviderId.GEMINI,
                LlmProviderId.OPENAI_COMPATIBLE,
                LlmProviderId.OLLAMA,
                LlmProviderId.LMSTUDIO,
                LlmProviderId.DETERMINISTIC_RULES
            )
            else -> listOf(input.provider, LlmProviderId.DETERMINISTIC_RULES).distinct()
        }

        val errors = mutableListOf<String>()
        for (pid in candidates) {
            if (pid == LlmProviderId.DETERMINISTIC_RULES) {
                val det = runDeterministicRefiner(
                    fallbackKeywords = input.fallbackKeywords,
                    autojsDeviceState = stateReport.redactedPayload,
                    redactedProxyPinTraffic = trafficReport.redactedPayload
                )
                val note = if (errors.isEmpty()) {
                    "使用离线确定性规则引擎完成提炼"
                } else {
                    "外部模型未就绪或失败（${errors.joinToString("; ")}），已自动降级到离线确定性规则引擎"
                }
                return det.copy(routingNote = note, redactionStats = totalStats)
            }

            val attempt = try {
                Result.success(
                    when (pid) {
                        LlmProviderId.GEMINI -> callGemini(input, config, stateReport.redactedPayload, trafficReport.redactedPayload, totalStats)
                        LlmProviderId.OPENAI_COMPATIBLE -> callOpenAiCompat(
                            pid, config.openAiBaseUrl, config.openAiApiKey, config.openAiModel,
                            allowLocal = config.allowLocalInference, input, stateReport.redactedPayload, trafficReport.redactedPayload, totalStats
                        )
                        LlmProviderId.OLLAMA -> callOpenAiCompat(
                            pid, config.ollamaBaseUrl, "", config.ollamaModel,
                            allowLocal = config.allowLocalInference, input, stateReport.redactedPayload, trafficReport.redactedPayload, totalStats
                        )
                        LlmProviderId.LMSTUDIO -> callOpenAiCompat(
                            pid, config.lmStudioBaseUrl, "", config.lmStudioModel,
                            allowLocal = config.allowLocalInference, input, stateReport.redactedPayload, trafficReport.redactedPayload, totalStats
                        )
                        LlmProviderId.AUTO, LlmProviderId.DETERMINISTIC_RULES -> error("unreachable")
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (attempt.isSuccess) {
                return attempt.getOrThrow()
            } else {
                errors += "${pid.id}: ${attempt.exceptionOrNull()?.message ?: "unknown"}"
            }
        }

        // 保底返回
        return runDeterministicRefiner(
            fallbackKeywords = input.fallbackKeywords,
            autojsDeviceState = stateReport.redactedPayload,
            redactedProxyPinTraffic = trafficReport.redactedPayload
        ).copy(redactionStats = totalStats)
    }

    private suspend fun callGemini(
        input: LlmRefineInput,
        config: LlmConfig,
        sanitizedState: JsonElement,
        sanitizedTraffic: JsonElement,
        stats: RedactionStats
    ): LlmRefineOutput {
        val key = config.geminiApiKey.trim()
        require(key.isNotEmpty()) { "未配置 Gemini API Key" }
        val model = config.geminiModel.ifBlank { "gemini-2.5-flash" }
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/${model.encodeURLPathPart()}:generateContent"
        val userPrompt = buildUserPrompt(input, sanitizedState, sanitizedTraffic)

        val payload = buildJsonObject {
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray { add(buildJsonObject { put("text", SYSTEM_PROMPT) }) })
            })
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", userPrompt) }) })
                })
            })
            put("generationConfig", buildJsonObject {
                put("responseMimeType", "application/json")
            })
        }

        val resp = http.post(endpoint) {
            timeout { requestTimeoutMillis = input.timeoutMs }
            header("x-goog-api-key", key)
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
        val rawText = resp.bodyAsText()
        if (resp.status.value !in 200..299) {
            error("HTTP ${resp.status.value}: ${Redaction.redactTextPatterns(rawText.take(180)).text}")
        }
        val root = json.parseToJsonElement(rawText).jsonObject
        val text = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject
            ?.get("parts")?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("text")?.jsonPrimitive?.contentOrNull
            ?: error("Gemini 返回缺少文本内容")

        return parseRefineJson(text, LlmProviderId.GEMINI, model, "由 ${LlmProviderId.GEMINI.label} ($model) 完成提炼", stats)
    }

    private suspend fun callOpenAiCompat(
        pid: LlmProviderId,
        rawBaseUrl: String,
        apiKey: String,
        model: String,
        allowLocal: Boolean,
        input: LlmRefineInput,
        sanitizedState: JsonElement,
        sanitizedTraffic: JsonElement,
        stats: RedactionStats
    ): LlmRefineOutput {
        require(rawBaseUrl.isNotBlank()) { "未配置 ${pid.label} Base URL" }
        if (pid == LlmProviderId.OPENAI_COMPATIBLE) {
            require(apiKey.isNotBlank()) { "未配置 OpenAI 兼容 API Key" }
        }
        // N-M1：解析 DNS 后逐个地址校验，挡住指向元数据/内网的域名
        val check = UrlSecurity.validateEndpointUrlResolved(rawBaseUrl, allowLocal = allowLocal)
        if (!check.valid || check.normalizedUrl == null) {
            error(check.reason ?: "URL 校验失败")
        }
        val endpoint = "${check.normalizedUrl}/chat/completions"
        val userPrompt = buildUserPrompt(input, sanitizedState, sanitizedTraffic)

        val payload = buildJsonObject {
            put("model", model.ifBlank { "gpt-4o-mini" })
            put("temperature", 0.1)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", SYSTEM_PROMPT) })
                add(buildJsonObject { put("role", "user"); put("content", userPrompt) })
            })
        }

        val resp = http.post(endpoint) {
            timeout { requestTimeoutMillis = input.timeoutMs }
            if (apiKey.isNotBlank()) header("Authorization", "Bearer ${apiKey.trim()}")
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
        val rawText = resp.bodyAsText()
        if (resp.status.value !in 200..299) {
            error("HTTP ${resp.status.value}: ${Redaction.redactTextPatterns(rawText.take(180)).text}")
        }
        val root = json.parseToJsonElement(rawText).jsonObject
        val text = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject
            ?.get("content")?.jsonPrimitive?.contentOrNull
            ?: error("${pid.label} 返回缺少 choices[0].message.content")

        return parseRefineJson(text, pid, model, "由 ${pid.label} ($model) 完成提炼", stats)
    }

    private fun buildUserPrompt(
        input: LlmRefineInput,
        sanitizedState: JsonElement,
        sanitizedTraffic: JsonElement
    ): String = buildJsonObject {
        put("goal", input.goal)
        put("workflowName", input.workflowName)
        put("appPackage", input.appPackage)
        put("fallbackKeywords", buildJsonArray { input.fallbackKeywords.forEach { add(JsonPrimitive(it)) } })
        put("autojsDeviceState", sanitizedState)
        put("redactedProxyPinTraffic", sanitizedTraffic)
    }.toString()

    private fun parseRefineJson(
        rawJsonText: String,
        provider: LlmProviderId,
        model: String,
        routingNote: String,
        stats: RedactionStats
    ): LlmRefineOutput {
        val cleaned = rawJsonText.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val obj = json.parseToJsonElement(cleaned).jsonObject
        val keywords = obj["refinedKeywords"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
            ?.filter { it.isNotEmpty() && it !in TERMINAL_TEXTS }
            .orEmpty()
        val waitMs = (obj["recommendedBrowseWaitMs"]?.jsonPrimitive?.intOrNull ?: 15000).coerceIn(1000, 120_000)
        val branches = obj["branchConditions"]?.jsonArray?.mapNotNull { el ->
            val b = el as? JsonObject ?: return@mapNotNull null
            BranchConditionHint(
                `when` = b["when"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                action = b["action"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                reason = b["reason"]?.jsonPrimitive?.contentOrNull.orEmpty()
            )
        }.orEmpty()
        val apiHints = obj["extractedApiHints"]?.jsonArray?.mapNotNull { el ->
            val h = el as? JsonObject ?: return@mapNotNull null
            ExtractedApiHint(
                endpoint = h["endpoint"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                field = h["field"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                meaning = h["meaning"]?.jsonPrimitive?.contentOrNull.orEmpty()
            )
        }.orEmpty()
        val snippet = obj["scriptSnippet"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val leaked = Redaction.assertNoPlaintextSecrets(snippet)
        require(leaked.isEmpty()) { "模型输出包含疑似明文密钥：${leaked.joinToString(", ")}" }

        return LlmRefineOutput(
            refinedKeywords = keywords,
            recommendedBrowseWaitMs = waitMs,
            branchConditions = branches,
            extractedApiHints = apiHints,
            scriptSnippet = snippet,
            modelUsed = model,
            providerUsed = provider,
            routingNote = routingNote,
            redactionStats = stats
        )
    }

    /** 零外部模型的确定性规则提炼引擎，离线可用。 */
    fun runDeterministicRefiner(
        fallbackKeywords: List<String>,
        autojsDeviceState: JsonElement = JsonNull,
        redactedProxyPinTraffic: JsonElement = JsonNull
    ): LlmRefineOutput {
        val scores = linkedMapOf<String, Int>()
        fun addCandidate(text: String, weight: Int) {
            val clean = text.trim()
            if (clean.isEmpty() || clean in TERMINAL_TEXTS) return
            scores[clean] = (scores[clean] ?: 0) + weight
        }

        var maxBrowseSec = 15
        val hints = mutableListOf<ExtractedApiHint>()

        // 1. 提取 AutoJs6 设备可见动作文本
        val devObj = autojsDeviceState as? JsonObject
        val visibleTexts = devObj?.get("visibleActionTexts") as? JsonArray
        visibleTexts?.forEach { el ->
            val txt = (el as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (ACTION_PREFIX_RE.containsMatchIn(txt.trim())) {
                addCandidate(txt, 3)
            }
        }

        // 2. 提取已脱敏的 ProxyPin 抓包任务列表
        val trafficObj = redactedProxyPinTraffic as? JsonObject
        val flows = trafficObj?.get("flows") as? JsonArray
        flows?.forEach { rawFlow ->
            val flow = rawFlow as? JsonObject ?: return@forEach
            val pathStr = flow["path"]?.jsonPrimitive?.contentOrNull
                ?: flow["url"]?.jsonPrimitive?.contentOrNull
                ?: "/unknown"
            val body = flow["responseBody"] as? JsonObject ?: JsonObject(emptyMap())
            val dataObj = (body["data"] as? JsonObject) ?: body
            val tasks = dataObj["tasks"] as? JsonArray
            if (tasks != null && tasks.isNotEmpty()) {
                hints += ExtractedApiHint(
                    endpoint = pathStr,
                    field = "data.tasks[].buttonText / browseSeconds",
                    meaning = "可执行任务按钮文案与最短浏览秒数"
                )
            }
            tasks?.forEach { rawTask ->
                val t = rawTask as? JsonObject ?: return@forEach
                val status = t["status"]?.jsonPrimitive?.contentOrNull.orEmpty().uppercase()
                val btn = t["buttonText"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (status != "DONE" && status != "COMPLETED" && btn.isNotEmpty()) {
                    addCandidate(btn, if (status == "CLAIMABLE") 5 else 4)
                }
                val sec = t["browseSeconds"]?.jsonPrimitive?.intOrNull ?: 0
                if (sec > 0) maxBrowseSec = maxOf(maxBrowseSec, sec)
            }
            val nextHint = body["nextActionHint"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (nextHint.isNotEmpty()) {
                hints += ExtractedApiHint(
                    endpoint = pathStr,
                    field = "nextActionHint",
                    meaning = nextHint
                )
            }
        }

        // 3. 合并兜底关键词
        fallbackKeywords.forEach { addCandidate(it, 1) }

        val sortedKeywords = scores.entries
            .sortedByDescending { it.value }
            .map { it.key }
        val waitMs = ((maxBrowseSec + 2) * 1000).coerceIn(1_000, 120_000)
        val branches = listOf(
            BranchConditionHint("屏幕出现「安全验证 / 拖动滑块」", "停手等待人工验证", "触发风控保护机制"),
            BranchConditionHint("落地页存在倒计时", "轮询归零后再等 2s 返回", "防止浏览时长不足"),
            BranchConditionHint("页面出现「已完成 / 已领取」", "跳过该任务行", "避免重复点击终态按钮")
        )
        val snippet = buildString {
            appendLine("// 由确定性规则引擎生成（已脱敏）")
            appendLine("var targetKeywords = [${sortedKeywords.joinToString(", ") { Js.quote(it) }}];")
            append("var recommendedWaitMs = $waitMs;")
        }

        return LlmRefineOutput(
            refinedKeywords = sortedKeywords,
            recommendedBrowseWaitMs = waitMs,
            branchConditions = branches,
            extractedApiHints = hints,
            scriptSnippet = snippet,
            modelUsed = DETERMINISTIC_MODEL_ID,
            providerUsed = LlmProviderId.DETERMINISTIC_RULES,
            routingNote = "使用离线确定性规则引擎完成提炼",
            redactionStats = RedactionStats(0, 0, 0)
        )
    }
}
