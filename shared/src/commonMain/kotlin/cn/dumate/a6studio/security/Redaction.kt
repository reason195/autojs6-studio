package cn.dumate.a6studio.security

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

data class RedactionOptions(
    val maxStringLength: Int = 512,
    val maxArrayLength: Int = 20,
    val maxDepth: Int = 8
)

data class RedactTextResult(
    val text: String,
    val maskedCount: Int,
    val truncated: Boolean
)

data class RedactionStats(
    val redactedKeyCount: Int,
    val maskedPatternCount: Int,
    val truncatedCount: Int
)

data class RedactionReport(
    val stats: RedactionStats,
    val redactedPayload: JsonElement
)

/**
 * 流量脱敏与导出密钥泄露扫描（N-S4 增强版）：
 * - 支持 HAR `{name, value}` / `{key, value}` 头部结构；
 * - 新增 `header_line`、`cookie_pair`、`basic_auth` 正则掩码；
 * - 字符串以 `{` 或 `[` 开头时先尝试解析成 JSON 递归脱敏；
 * - 敏感键名补充 `key`, `wskey`, `pin`, `h5st`, `eid`, `uuid`, `openudid`；
 * - 超大字符串先截断到 64 KB 再执行单遍正则替换。
 */
object Redaction {

    const val HARD_MAX_INPUT_CHARS = 65_536
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val SENSITIVE_KEY_REGEX = Regex(
        "^(authorization|proxy-authorization|cookie|set-cookie|x-api-key|api[-_]?key|apikey|key|wskey|pin|h5st|eid|uuid|openudid|token|access[-_]?token|refresh[-_]?token|id[-_]?token|csrf[-_]?token|secret|client[-_]?secret|app[-_]?secret|password|passwd|pwd|phone|mobile|tel|telephone|session|session[-_]?id|sid|connect\\.sid|signature|sign|credential|private[-_]?key|ssn|id[-_]?card|bank[-_]?card|cvv|pt[-_]?key|pt[-_]?pin|auth|x-auth|ticket)$",
        RegexOption.IGNORE_CASE
    )

    private val SENSITIVE_KEY_CONTAINS_REGEX = Regex(
        "(authorization|cookie|secret|password|passwd|token|apikey|api_key|private_key|credential|phone|mobile|wskey|h5st|openudid|pt_key|pt_pin|sessid|session[-_]?id|access[-_]?key|auth[-_]?key|ticket)",
        RegexOption.IGNORE_CASE
    )

    private val NON_SECRET_TOKEN_KEYS = setOf(
        "maxtoken", "maxtokens", "maxoutputtokens", "maxcompletiontokens",
        "tokencount", "tokenscount", "tokenlimit", "tokensused", "tokenusage",
        "totaltokens", "prompttokens", "completiontokens", "inputtokens",
        "outputtokens", "cachedtokens", "reasoningtokens", "tokentype"
    )

    private data class PatternRule(val name: String, val regex: Regex, val transform: (MatchResult) -> String)

    /**
     * JSON 片段里敏感字段的开头：`"password":"`。字符串超过 64 KB 或深度超限时不会被解析成对象，
     * 按键名脱敏就失效了，只能靠 [maskJsonSecretFields] 兜住（值必须是字符串，"max_tokens": 1024
     * 这类计数不受影响）。值的结尾不用正则匹配：`(?:[^"\\]|\\.)*` 在 java.util.regex 里每个字符
     * 递归一层，长值直接 StackOverflowError，所以改成手工扫到未转义的引号。
     */
    private val JSON_SECRET_FIELD_START = Regex(
        "(?i)\"[a-z0-9_.-]*(?:password|passwd|pwd|secret|token|api[-_]?key|apikey|authorization|cookie|" +
            "session[-_]?id|sessid|session|sid|ticket|wskey|pt[-_]?key|pt[-_]?pin|access[-_]?key|auth[-_]?key|" +
            "credential|private[-_]?key|signature|sign|auth|h5st|phone|mobile|cvv)[a-z0-9_.-]*\"\\s*:\\s*\""
    )

    private fun maskJsonSecretFields(input: String): Pair<String, Int> {
        var count = 0
        val out = StringBuilder(input.length)
        var pos = 0
        while (pos < input.length) {
            val m = JSON_SECRET_FIELD_START.find(input, pos) ?: break
            val keyName = m.value.substring(1, m.value.indexOf('"', 1))
            val valueStart = m.range.last + 1
            out.append(input, pos, valueStart)
            if (!isSensitiveKey(keyName)) {
                // e.g. "tokenType":"bearer" 只是包含 token，不是凭据
                pos = valueStart
                continue
            }
            var i = valueStart
            while (i < input.length && input[i] != '"') i += if (input[i] == '\\') 2 else 1
            out.append("[REDACTED]")
            count++
            pos = minOf(i, input.length)
        }
        if (count == 0) return input to 0
        out.append(input, pos, input.length)
        return out.toString() to count
    }

    private val SECRET_STRING_PATTERNS = listOf(
        // 表单 / 查询串里的密码类参数，包括串首的第一个参数（password=[REDACTED_PASSWORD]
        // query_secret_param 要求前面有 ? 或 &，cookie_pair 又只认 *token / *key。
        PatternRule(
            "form_secret_param",
            Regex("(?i)(?<![A-Za-z0-9_.-])((?:[a-z_]*pass(?:word|wd)|pwd|[a-z_]*secret|[a-z_]*sessid|[a-z_]*session_?id|ticket|auth)=)(?!\\[REDACTED\\])([^&;#\\s\"']+)")
        ) { m -> "${m.groupValues[1]}[REDACTED]" },
        PatternRule(
            "header_line",
            Regex("(?im)^(\\s*(?:cookie|set-cookie|authorization|proxy-authorization|x-api-key)\\s*:\\s*).+$")
        ) { m -> "${m.groupValues[1]}[REDACTED]" },
        PatternRule(
            "cookie_pair",
            Regex("(?i)\\b(pt_key|pt_pin|wskey|sid|session|token|[a-z_]*token|[a-z_]*key)=([^;&\\s\"']+)")
        ) { m -> "${m.groupValues[1]}=[REDACTED]" },
        PatternRule(
            "basic_auth",
            Regex("(?i)\\bBasic\\s+[A-Za-z0-9+/=]{8,}")
        ) { "Basic [REDACTED]" },
        PatternRule(
            "bearer_token",
            Regex("\\bBearer\\s+[A-Za-z0-9\\-._~+/]+=*", RegexOption.IGNORE_CASE)
        ) { "Bearer [REDACTED]" },
        PatternRule(
            "jwt",
            Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b")
        ) { "[REDACTED_JWT]" },
        PatternRule(
            "api_key_prefix",
            Regex("\\b(AIza[0-9A-Za-z_-]{20,}|sk-[0-9A-Za-z_-]{20,}|gh[pousr]_[0-9A-Za-z]{20,}|github_pat_[0-9A-Za-z_]{20,}|AKIA[0-9A-Z]{16})")
        ) { "[REDACTED_API_KEY]" },
        PatternRule(
            "query_secret_param",
            Regex("([?&](?:token|access_token|secret|password|phone|mobile|key|api_key|sign|sig|cookie)=)([^&#\\s]+)", RegexOption.IGNORE_CASE)
        ) { m -> "${m.groupValues[1]}[REDACTED]" },
        PatternRule(
            "cn_id_card",
            Regex("(?<![0-9A-Za-z])(\\d{6})(?:18|19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])(\\d{3}[\\dXx])(?![0-9A-Za-z])")
        ) { m -> "${m.groupValues[1]}********${m.groupValues[2]}" },
        PatternRule(
            "cn_mobile",
            Regex("(?<!\\d)(1[3-9]\\d)\\d{4}(\\d{4})(?!\\d)")
        ) { m -> "${m.groupValues[1]}****${m.groupValues[2]}" },
        PatternRule(
            "intl_phone",
            Regex("(\\+\\d{1,3}[-\\s]?)\\d{3,4}[-\\s]?\\d{3,4}[-\\s]?(\\d{2,4})")
        ) { m -> "${m.groupValues[1]}***-${m.groupValues[2]}" },
        PatternRule(
            "email",
            Regex("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b")
        ) { "[REDACTED_EMAIL]" }
    )

    private val EXPORT_FORBIDDEN_SECRET_PATTERNS: List<Pair<String, Regex>> = listOf(
        "Google API Key" to Regex("AIza[0-9A-Za-z_-]{20,}"),
        "OpenAI/Secret Key" to Regex("sk-[0-9A-Za-z_-]{20,}"),
        "GitHub PAT" to Regex("gh[pousr]_[0-9A-Za-z]{20,}|github_pat_[0-9A-Za-z_]{20,}"),
        "AWS Access Key" to Regex("\\bAKIA[0-9A-Z]{16}\\b"),
        "Bearer Credential" to Regex("Bearer\\s+[A-Za-z0-9._~+/=-]{16,}", RegexOption.IGNORE_CASE),
        "JWT Token" to Regex("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\."),
        "Hardcoded GEMINI_API_KEY" to Regex("GEMINI_API_KEY\\s*[:=]\\s*[\"'][^\"']{8,}[\"']", RegexOption.IGNORE_CASE),
        "Hardcoded MCP Token" to Regex("MCP_TOKEN\\s*[:=]\\s*[\"'][^\"']{8,}[\"']", RegexOption.IGNORE_CASE)
    )

    fun isSensitiveKey(key: String): Boolean {
        val normalized = key.trim()
        if (normalized.isEmpty()) return false
        if (SENSITIVE_KEY_REGEX.matches(normalized)) return true
        val compact = normalized.lowercase().replace("-", "").replace("_", "")
        if (compact in NON_SECRET_TOKEN_KEYS) return false
        return SENSITIVE_KEY_CONTAINS_REGEX.containsMatchIn(normalized)
    }

    fun redactTextPatterns(input: String, maxStringLength: Int = 512): RedactTextResult {
        val preTruncated = input.length > HARD_MAX_INPUT_CHARS
        var text = if (preTruncated) input.take(HARD_MAX_INPUT_CHARS) else input
        var maskedCount = 0

        maskJsonSecretFields(text).let { (masked, n) -> text = masked; maskedCount += n }
        for (rule in SECRET_STRING_PATTERNS) {
            text = rule.regex.replace(text) { m ->
                maskedCount++
                rule.transform(m)
            }
        }

        var truncated = preTruncated
        if (text.length > maxStringLength) {
            text = text.take(maxStringLength) + "...[TRUNCATED:${input.length}chars]"
            truncated = true
        } else if (preTruncated) {
            text = "$text...[TRUNCATED:${input.length}chars]"
        }

        return RedactTextResult(text, maskedCount, truncated)
    }

    fun redactTrafficPayload(
        payload: JsonElement,
        options: RedactionOptions = RedactionOptions()
    ): RedactionReport {
        var redactedKeyCount = 0
        var maskedPatternCount = 0
        var truncatedCount = 0

        fun walk(value: JsonElement, depth: Int): JsonElement {
            return when (value) {
                is JsonNull -> JsonNull
                is JsonPrimitive -> {
                    if (value.isString) {
                        val raw = value.content
                        val trimmed = raw.trim()
                        if (depth < options.maxDepth && trimmed.length <= HARD_MAX_INPUT_CHARS &&
                            ((trimmed.startsWith("{") && trimmed.endsWith("}")) ||
                                (trimmed.startsWith("[") && trimmed.endsWith("]")))
                        ) {
                            val parsed = runCatching { json.parseToJsonElement(trimmed) }.getOrNull()
                            if (parsed is JsonObject || parsed is JsonArray) {
                                val nested = walk(parsed, depth + 1).toString()
                                return if (nested.length > options.maxStringLength) {
                                    truncatedCount += 1
                                    JsonPrimitive(nested.take(options.maxStringLength) + "...[TRUNCATED:${nested.length}chars]")
                                } else {
                                    JsonPrimitive(nested)
                                }
                            }
                        }
                        val res = redactTextPatterns(raw, options.maxStringLength)
                        maskedPatternCount += res.maskedCount
                        if (res.truncated) truncatedCount += 1
                        JsonPrimitive(res.text)
                    } else {
                        value
                    }
                }
                is JsonArray -> {
                    if (depth >= options.maxDepth) {
                        truncatedCount += 1
                        return JsonPrimitive("[TRUNCATED_MAX_DEPTH]")
                    }
                    buildJsonArray {
                        val sliceCount = minOf(value.size, options.maxArrayLength)
                        for (i in 0 until sliceCount) {
                            add(walk(value[i], depth + 1))
                        }
                        if (value.size > options.maxArrayLength) {
                            truncatedCount += 1
                            add(JsonPrimitive("[TRUNCATED_ARRAY:${value.size - options.maxArrayLength}_MORE]"))
                        }
                    }
                }
                is JsonObject -> {
                    if (depth >= options.maxDepth) {
                        truncatedCount += 1
                        return JsonPrimitive("[TRUNCATED_MAX_DEPTH]")
                    }
                    // 支持 HAR 的 {name, value} 或 {key, value} 结构（key 是头名载体，不因 key 在敏感词表里被误抹）
                    val headerName = (value["name"] ?: value["key"])
                        ?.let { it as? JsonPrimitive }
                        ?.takeIf { it.isString }
                        ?.contentOrNull
                    val isHarEntry = value.containsKey("value") && headerName != null
                    val isHarSensitive = isHarEntry && isSensitiveKey(headerName)

                    buildJsonObject {
                        for ((k, v) in value) {
                            val isHarKeyName = isHarEntry && k == "key"
                            if ((isHarSensitive && k == "value") || (!isHarKeyName && isSensitiveKey(k))) {
                                redactedKeyCount += 1
                                put(k, JsonPrimitive("[REDACTED]"))
                            } else {
                                put(k, walk(v, depth + 1))
                            }
                        }
                    }
                }
            }
        }

        val redacted = walk(payload, 0)
        return RedactionReport(
            stats = RedactionStats(redactedKeyCount, maskedPatternCount, truncatedCount),
            redactedPayload = redacted
        )
    }

    /** 导出前扫描脚本中的明文凭据，返回命中的风险标签列表（空列表表示通过）。 */
    fun assertNoPlaintextSecrets(scriptText: String): List<String> =
        EXPORT_FORBIDDEN_SECRET_PATTERNS.mapNotNull { (label, regex) ->
            if (regex.containsMatchIn(scriptText)) label else null
        }

    /** 按风险标签统计脚本中的明文凭据命中次数，用于补丁增量校验。 */
    fun countPlaintextSecrets(scriptText: String): Map<String, Int> =
        EXPORT_FORBIDDEN_SECRET_PATTERNS.associate { (label, regex) ->
            label to regex.findAll(scriptText).count()
        }.filterValues { it > 0 }
}
