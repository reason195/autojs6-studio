package cn.dumate.a6studio.security

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

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
 * 流量脱敏与导出密钥泄露扫描（移植并适配自 OmniForge-Studio `src/core/redaction.ts`）。
 * 确保发送给外部大模型或导出的脚本不包含敏感凭据与个人隐私数据。
 */
object Redaction {

    private val SENSITIVE_KEY_REGEX = Regex(
        "^(authorization|proxy-authorization|cookie|set-cookie|x-api-key|api[-_]?key|apikey|token|access[-_]?token|refresh[-_]?token|id[-_]?token|csrf[-_]?token|secret|client[-_]?secret|app[-_]?secret|password|passwd|pwd|phone|mobile|tel|telephone|session|session[-_]?id|sid|signature|sign|credential|private[-_]?key|ssn|id[-_]?card|bank[-_]?card|cvv|pt[-_]?key|pt[-_]?pin)$",
        RegexOption.IGNORE_CASE
    )

    private val SENSITIVE_KEY_CONTAINS_REGEX = Regex(
        "(authorization|cookie|secret|password|passwd|token|apikey|api_key|private_key|credential|phone|mobile)",
        RegexOption.IGNORE_CASE
    )

    private val NON_SECRET_TOKEN_KEYS = setOf(
        "maxtoken", "maxtokens", "maxoutputtokens", "maxcompletiontokens",
        "tokencount", "tokenscount", "tokenlimit", "tokensused", "tokenusage",
        "totaltokens", "prompttokens", "completiontokens", "inputtokens",
        "outputtokens", "cachedtokens", "reasoningtokens", "tokentype"
    )

    private data class PatternRule(val name: String, val regex: Regex, val replacement: String)

    private val SECRET_STRING_PATTERNS = listOf(
        PatternRule(
            "bearer_token",
            Regex("\\bBearer\\s+[A-Za-z0-9\\-._~+/]+=*", RegexOption.IGNORE_CASE),
            "Bearer [REDACTED]"
        ),
        PatternRule(
            "jwt",
            Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b"),
            "[REDACTED_JWT]"
        ),
        PatternRule(
            "api_key_prefix",
            Regex("\\b(AIza[0-9A-Za-z_-]{20,}|sk-[0-9A-Za-z_-]{20,}|gh[pousr]_[0-9A-Za-z]{20,}|github_pat_[0-9A-Za-z_]{20,}|AKIA[0-9A-Z]{16})"),
            "[REDACTED_API_KEY]"
        ),
        PatternRule(
            "query_secret_param",
            Regex("([?&](?:token|access_token|secret|password|phone|mobile|key|api_key|sign|sig|cookie)=)([^&#\\s]+)", RegexOption.IGNORE_CASE),
            "$1[REDACTED]"
        ),
        PatternRule(
            "cn_id_card",
            Regex("(?<![0-9A-Za-z])(\\d{6})(?:18|19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])(\\d{3}[\\dXx])(?![0-9A-Za-z])"),
            "$1********$2"
        ),
        PatternRule(
            "cn_mobile",
            Regex("(?<!\\d)(1[3-9]\\d)\\d{4}(\\d{4})(?!\\d)"),
            "$1****$2"
        ),
        PatternRule(
            "intl_phone",
            Regex("(\\+\\d{1,3}[-\\s]?)\\d{3,4}[-\\s]?\\d{3,4}[-\\s]?(\\d{2,4})"),
            "$1***-$2"
        ),
        PatternRule(
            "email",
            Regex("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b"),
            "[REDACTED_EMAIL]"
        )
    )

    private val EXPORT_FORBIDDEN_SECRET_PATTERNS: List<Pair<String, Regex>> = listOf(
        "Google API Key" to Regex("AIza[0-9A-Za-z_-]{20,}"),
        "OpenAI/Secret Key" to Regex("sk-[0-9A-Za-z_-]{20,}"),
        "GitHub PAT" to Regex("gh[pousr]_[0-9A-Za-z]{20,}|github_pat_[0-9A-Za-z_]{20,}"),
        "Bearer Credential" to Regex("Bearer\\s+[A-Za-z0-9._-]{16,}", RegexOption.IGNORE_CASE),
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
        var text = input
        var maskedCount = 0

        for (rule in SECRET_STRING_PATTERNS) {
            val matches = rule.regex.findAll(text).count()
            if (matches > 0) {
                maskedCount += matches
                text = rule.regex.replace(text, rule.replacement)
            }
        }

        var truncated = false
        if (text.length > maxStringLength) {
            text = text.take(maxStringLength) + "...[TRUNCATED:${input.length}chars]"
            truncated = true
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
                        val res = redactTextPatterns(value.content, options.maxStringLength)
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
                    buildJsonObject {
                        for ((k, v) in value) {
                            if (isSensitiveKey(k)) {
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
}
