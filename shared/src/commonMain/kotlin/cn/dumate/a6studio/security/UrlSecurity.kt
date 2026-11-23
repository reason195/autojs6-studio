package cn.dumate.a6studio.security

data class UrlValidationResult(
    val valid: Boolean,
    val reason: String? = null,
    val normalizedUrl: String? = null
)

/**
 * 出站 URL 安全校验（阻断云元数据 SSRF、非 HTTP(S) 协议及可选的本地/内网限制）。
 * 移植自 OmniForge-Studio `validateProviderEndpointUrl`。
 */
object UrlSecurity {

    private val IPV4_RE = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
    private val MAPPED_DOTTED_RE = Regex("^::ffff:(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
    private val MAPPED_HEX_RE = Regex("^::ffff:([0-9a-f]{1,4}):([0-9a-f]{1,4})$")
    private val URL_RE = Regex("^(https?)://([^/?#]+)(.*)$", RegexOption.IGNORE_CASE)

    fun extractMappedIpv4Octets(hostname: String): List<Int>? {
        val h = hostname.trim('[', ']').lowercase()
        IPV4_RE.matchEntire(h)?.let { m ->
            val octets = m.groupValues.drop(1).mapNotNull { it.toIntOrNull() }
            if (octets.size == 4 && octets.all { it in 0..255 }) return octets
            return null
        }
        MAPPED_DOTTED_RE.matchEntire(h)?.let { m ->
            val octets = m.groupValues.drop(1).mapNotNull { it.toIntOrNull() }
            if (octets.size == 4 && octets.all { it in 0..255 }) return octets
        }
        MAPPED_HEX_RE.matchEntire(h)?.let { m ->
            val hi = m.groupValues[1].toIntOrNull(16) ?: return null
            val lo = m.groupValues[2].toIntOrNull(16) ?: return null
            return listOf((hi shr 8) and 0xff, hi and 0xff, (lo shr 8) and 0xff, lo and 0xff)
        }
        return null
    }

    fun isCloudMetadataHost(hostname: String): Boolean {
        val h = hostname.trim('[', ']').lowercase()
        if (h == "169.254.169.254" || h == "metadata.google.internal" || h == "metadata.goog" || h == "0.0.0.0") {
            return true
        }
        val octets = extractMappedIpv4Octets(h)
        return octets != null && ((octets[0] == 169 && octets[1] == 254) || octets[0] == 0)
    }

    fun isLocalhostOrPrivate(hostname: String): Boolean {
        val h = hostname.trim('[', ']').lowercase()
        if (h == "localhost" || h == "127.0.0.1" || h == "::1" || h == "::") return true
        if (h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")) return true
        val octets = extractMappedIpv4Octets(h) ?: return false
        val (a, b) = octets
        return a == 0 ||
            a == 10 ||
            a == 127 ||
            (a == 169 && b == 254) ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 100 && b in 64..127)
    }

    fun validateEndpointUrl(rawUrl: String, allowLocal: Boolean = true): UrlValidationResult {
        val trimmed = rawUrl.trim()
        val m = URL_RE.matchEntire(trimmed)
            ?: return UrlValidationResult(false, "端点 URL 格式无效，仅支持 http:// 或 https://")
        val scheme = m.groupValues[1].lowercase()
        val authority = m.groupValues[2]
        val host = if (authority.startsWith("[")) {
            authority.substringBefore("]") + "]"
        } else {
            authority.substringBefore(":")
        }
        if (host.isBlank()) {
            return UrlValidationResult(false, "端点缺少主机名")
        }
        if (isCloudMetadataHost(host)) {
            return UrlValidationResult(false, "安全策略拦截：禁止访问云元数据地址 ($host)")
        }
        if (!allowLocal && isLocalhostOrPrivate(host)) {
            return UrlValidationResult(false, "安全策略拦截：未启用本地/内网访问 ($host)")
        }
        val normalized = "$scheme://$authority${m.groupValues[3]}".trimEnd('/')
        return UrlValidationResult(true, normalizedUrl = normalized)
    }
}
