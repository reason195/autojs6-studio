package cn.dumate.a6studio.security

data class UrlValidationResult(
    val valid: Boolean,
    val reason: String? = null,
    val normalizedUrl: String? = null
)

/**
 * 平台 DNS 解析（jvmAndroidMain 用 InetAddress.getAllByName，在 IO 线程执行）。
 * 返回每个地址的原始字节（4 或 16 字节）；解析失败返回空列表。
 */
expect suspend fun resolveHostAddresses(host: String): List<ByteArray>

/**
 * 出站 URL 安全校验（阻断云元数据 SSRF、非 HTTP(S) 协议及可选的本地/内网限制）。
 *
 * N-M1 修复要点：
 * - authority 里带 userinfo（`x@host`）一律拒绝，不再被 `substringBefore(":")` 误解析；
 * - IPv4 支持十进制整数 / 十六进制 / 八进制 / 缺段等「非标准写法」的识别：能被解析成地址但不是
 *   规范点分十进制的，直接拒绝（这类写法在正常配置里不会出现，只会出现在绕过尝试里）；
 * - IPv6 做完整解析（含 `::` 压缩、内嵌 IPv4、v4-mapped / v4-compat），按地址段判断而不是按字符串前缀，
 *   因此 `fdroid.org` 这类域名不再被误判为内网；
 * - 末尾带点的域名（`metadata.google.internal.`）先去点再判断；
 * - 云元数据黑名单补上 AWS IPv6 IMDS `fd00:ec2::254` 与阿里云 `100.100.100.200`，且与 allowLocal 无关，始终拦截；
 * - [validateEndpointUrlResolved] 额外做 DNS 解析后逐个地址校验，挡住 `169.254.169.254.nip.io` 一类域名。
 *
 * 已知限制：解析与真正连接之间仍有 DNS rebinding 窗口（TOCTOU），彻底解决需要在 HTTP 引擎层固定解析结果。
 */
object UrlSecurity {

    private val URL_RE = Regex("^(https?)://([^/?#]*)(.*)$", RegexOption.IGNORE_CASE)
    private val CANONICAL_IPV4_RE = Regex("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$")
    private val METADATA_NAMES = setOf("metadata.google.internal", "metadata.goog", "metadata", "instance-data")
    private val AWS_IMDS_V6 = parseIpv6("fd00:ec2::254")!!

    // ------------------------------------------------------------------ 解析

    /** 宽松解析 IPv4（inet_aton 语义：1~4 段，每段可为十进制 / 0x 十六进制 / 0 开头八进制）。不是 IPv4 则返回 null。 */
    fun parseIpv4Loose(host: String): ByteArray? {
        if (host.isEmpty() || host.length > 64) return null
        val parts = host.split('.')
        if (parts.size !in 1..4 || parts.any { it.isEmpty() }) return null
        val nums = parts.map { p ->
            val lower = p.lowercase()
            when {
                lower.startsWith("0x") -> lower.drop(2).takeIf { it.isNotEmpty() && it.all { c -> c in "0123456789abcdef" } }?.toLongOrNull(16)
                lower.length > 1 && lower.startsWith("0") -> lower.drop(1).takeIf { it.all { c -> c in '0'..'7' } }?.toLongOrNull(8)
                else -> lower.takeIf { it.all { c -> c.isDigit() } }?.toLongOrNull()
            } ?: return null
        }
        // 前 n-1 段各占 1 字节，最后一段填满剩余字节
        for (i in 0 until nums.size - 1) if (nums[i] > 255) return null
        val lastBytes = 4 - (nums.size - 1)
        val lastMax = (1L shl (8 * lastBytes)) - 1
        if (nums.last() > lastMax) return null
        val out = ByteArray(4)
        for (i in 0 until nums.size - 1) out[i] = nums[i].toByte()
        var v = nums.last()
        for (i in 3 downTo nums.size - 1) { out[i] = (v and 0xff).toByte(); v = v shr 8 }
        return out
    }

    /** 解析 IPv6 字面量（不含方括号），支持 `::`、内嵌 IPv4、zone id（会被丢弃）。失败返回 null。 */
    fun parseIpv6(raw: String): ByteArray? {
        var s = raw.substringBefore('%').lowercase()
        if (s.isEmpty() || !s.contains(':')) return null
        val tail = mutableListOf<Int>()
        // 内嵌 IPv4（如 ::ffff:1.2.3.4）
        val lastColon = s.lastIndexOf(':')
        val lastPart = s.substring(lastColon + 1)
        if (lastPart.contains('.')) {
            if (!CANONICAL_IPV4_RE.matches(lastPart)) return null
            val b = lastPart.split('.').map { it.toInt() }
            tail += (b[0] shl 8) or b[1]
            tail += (b[2] shl 8) or b[3]
            s = s.substring(0, lastColon + 1) + "0"   // 占位，下面再去掉
        }
        val dbl = s.indexOf("::")
        if (dbl != s.lastIndexOf("::") ) return null
        fun groups(part: String): List<Int>? {
            if (part.isEmpty()) return emptyList()
            return part.split(':').map { g ->
                if (g.isEmpty() || g.length > 4 || !g.all { it in "0123456789abcdef" }) return null
                g.toInt(16)
            }
        }
        var head: List<Int>
        var rest: List<Int>
        if (dbl >= 0) {
            head = groups(s.substring(0, dbl)) ?: return null
            rest = groups(s.substring(dbl + 2)) ?: return null
        } else {
            head = groups(s) ?: return null
            rest = emptyList()
        }
        // 去掉内嵌 IPv4 的占位并换成真实的两段
        if (tail.isNotEmpty()) {
            if (rest.isNotEmpty()) rest = rest.dropLast(1) + tail else head = head.dropLast(1) + tail
        }
        val total = head.size + rest.size
        val all = if (dbl >= 0) {
            if (total > 7) return null
            head + List(8 - total) { 0 } + rest
        } else {
            if (total != 8) return null
            head
        }
        val out = ByteArray(16)
        all.forEachIndexed { i, g -> out[2 * i] = (g shr 8).toByte(); out[2 * i + 1] = g.toByte() }
        return out
    }

    // ------------------------------------------------------------------ 分类

    private fun u(b: Byte) = b.toInt() and 0xff

    /** IPv6 中嵌入的 IPv4（v4-mapped `::ffff:a.b.c.d` 或 v4-compat `::a.b.c.d`），否则 null。 */
    private fun embeddedV4(a: ByteArray): ByteArray? {
        if (a.size != 16) return null
        if ((0 until 10).any { a[it].toInt() != 0 }) return null
        val mapped = u(a[10]) == 0xff && u(a[11]) == 0xff
        val compat = a[10].toInt() == 0 && a[11].toInt() == 0 && (0 until 4).any { a[12 + it].toInt() != 0 } &&
            !(a[12].toInt() == 0 && a[13].toInt() == 0 && a[14].toInt() == 0 && u(a[15]) <= 1) // 排除 :: 与 ::1
        return if (mapped || compat) a.copyOfRange(12, 16) else null
    }

    fun isMetadataAddress(addr: ByteArray): Boolean {
        val v4 = if (addr.size == 4) addr else embeddedV4(addr)
        if (v4 != null) {
            val a = u(v4[0]); val b = u(v4[1]); val c = u(v4[2]); val d = u(v4[3])
            return (a == 169 && b == 254) || a == 0 || (a == 100 && b == 100 && c == 100 && d == 200)
        }
        return addr.contentEquals(AWS_IMDS_V6) || addr.all { it.toInt() == 0 }
    }

    fun isLocalOrPrivateAddress(addr: ByteArray): Boolean {
        val v4 = if (addr.size == 4) addr else embeddedV4(addr)
        if (v4 != null) {
            val a = u(v4[0]); val b = u(v4[1])
            return a == 0 || a == 10 || a == 127 ||
                (a == 169 && b == 254) ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                (a == 100 && b in 64..127) ||
                a >= 224 // 组播 / 保留
        }
        val first = u(addr[0]); val second = u(addr[1])
        val loopback = (0 until 15).all { addr[it].toInt() == 0 } && u(addr[15]) <= 1 // ::1 与 ::
        val linkLocal = first == 0xfe && (second and 0xc0) == 0x80                      // fe80::/10
        val ula = (first and 0xfe) == 0xfc                                               // fc00::/7
        val multicast = first == 0xff
        return loopback || linkLocal || ula || multicast
    }

    /** 兼容旧调用方：按字符串判断主机名（字面量 IP 走地址判断，域名只认 localhost）。 */
    fun isCloudMetadataHost(hostname: String): Boolean {
        val h = normalizeHost(hostname)
        if (h in METADATA_NAMES) return true
        return literalAddress(h)?.let(::isMetadataAddress) ?: false
    }

    fun isLocalhostOrPrivate(hostname: String): Boolean {
        val h = normalizeHost(hostname)
        if (h == "localhost" || h.endsWith(".localhost")) return true
        return literalAddress(h)?.let(::isLocalOrPrivateAddress) ?: false
    }

    // ------------------------------------------------------------------ URL

    private fun normalizeHost(h: String) = h.trim().trim('[', ']').trimEnd('.').lowercase()

    private fun literalAddress(h: String): ByteArray? = if (h.contains(':')) parseIpv6(h) else parseIpv4Loose(h)

    private data class Parsed(val scheme: String, val authority: String, val host: String, val rest: String)

    private fun parse(rawUrl: String): Pair<Parsed?, String?> {
        val trimmed = rawUrl.trim()
        if (trimmed.any { it.isWhitespace() || it.code < 0x20 }) return null to "端点 URL 含空白或控制字符"
        val m = URL_RE.matchEntire(trimmed) ?: return null to "端点 URL 格式无效，仅支持 http:// 或 https://"
        val scheme = m.groupValues[1].lowercase()
        val authority = m.groupValues[2]
        if (authority.contains('@')) return null to "安全策略拦截：URL 不允许携带用户信息 (userinfo)"
        if (authority.contains('\\')) return null to "端点 URL 主机部分含非法字符"
        val hostPart: String
        val portPart: String
        if (authority.startsWith("[")) {
            val end = authority.indexOf(']')
            if (end < 0) return null to "IPv6 地址缺少右方括号"
            hostPart = authority.substring(1, end)
            portPart = authority.substring(end + 1).removePrefix(":")
            if (authority.length > end + 1 && authority[end + 1] != ':') return null to "端点 URL 端口格式无效"
            if (parseIpv6(hostPart) == null) return null to "IPv6 地址格式无效"
        } else {
            if (authority.count { it == ':' } > 1) return null to "IPv6 地址必须放在方括号内"
            hostPart = authority.substringBefore(':')
            portPart = if (authority.contains(':')) authority.substringAfter(':') else ""
        }
        if (portPart.isNotEmpty() && (portPart.toIntOrNull()?.let { it in 1..65535 } != true)) {
            return null to "端点 URL 端口无效"
        }
        val host = normalizeHost(hostPart)
        if (host.isBlank()) return null to "端点缺少主机名"
        return Parsed(scheme, authority, host, m.groupValues[3]) to null
    }

    private fun checkHost(host: String, allowLocal: Boolean): String? {
        if (host in METADATA_NAMES) return "安全策略拦截：禁止访问云元数据地址 ($host)"
        if (!host.contains(':')) {
            val v4 = parseIpv4Loose(host)
            if (v4 != null && !CANONICAL_IPV4_RE.matches(host)) {
                return "安全策略拦截：不支持非标准写法的 IP 地址 ($host)"
            }
        }
        val lit = literalAddress(host)
        if (lit != null) {
            if (isMetadataAddress(lit)) return "安全策略拦截：禁止访问云元数据地址 ($host)"
            if (!allowLocal && isLocalOrPrivateAddress(lit)) return "安全策略拦截：未启用本地/内网访问 ($host)"
        } else if (!allowLocal && (host == "localhost" || host.endsWith(".localhost"))) {
            return "安全策略拦截：未启用本地/内网访问 ($host)"
        }
        return null
    }

    /** 同步校验（不做 DNS）。可以在任意线程调用。 */
    fun validateEndpointUrl(
        rawUrl: String,
        allowLocal: Boolean = true,
        requireHttpsForPublic: Boolean = false
    ): UrlValidationResult {
        val (p, err) = parse(rawUrl)
        if (p == null) return UrlValidationResult(false, err)
        checkHost(p.host, allowLocal)?.let { return UrlValidationResult(false, it) }
        if (requireHttpsForPublic && p.scheme == "http") {
            val lit = literalAddress(p.host)
            val isLocalName = p.host == "localhost" || p.host.endsWith(".localhost")
            val definitelyPublic = (lit != null && !isLocalOrPrivateAddress(lit)) || (!allowLocal && !isLocalName)
            if (definitelyPublic) {
                return UrlValidationResult(false, "安全策略拦截：携带 API Key 访问公网地址时必须使用 https:// (${p.host})")
            }
        }
        val normalized = "${p.scheme}://${p.authority}${p.rest}".trimEnd('/')
        return UrlValidationResult(true, normalizedUrl = normalized)
    }

    /**
     * 同步校验 + DNS 解析后逐个地址校验。真正发请求前调用。
     * 域名解析失败时不拦截（交给 HTTP 层报「无法连接」），只拦截解析到危险地址的情况。
     */
    suspend fun validateEndpointUrlResolved(
        rawUrl: String,
        allowLocal: Boolean = true,
        requireHttpsForPublic: Boolean = false
    ): UrlValidationResult {
        val base = validateEndpointUrl(rawUrl, allowLocal, requireHttpsForPublic)
        if (!base.valid) return base
        val parsed = parse(rawUrl).first ?: return base
        val host = parsed.host
        if (literalAddress(host) != null) return base
        val addrs = resolveHostAddresses(host)
        for (addr in addrs) {
            if (isMetadataAddress(addr)) {
                return UrlValidationResult(false, "安全策略拦截：$host 解析到云元数据地址")
            }
            if (!allowLocal && isLocalOrPrivateAddress(addr)) {
                return UrlValidationResult(false, "安全策略拦截：$host 解析到本地/内网地址，未启用本地访问")
            }
        }
        if (requireHttpsForPublic && parsed.scheme == "http" && host != "localhost" && !host.endsWith(".localhost")) {
            if (addrs.isEmpty() || addrs.any { !isLocalOrPrivateAddress(it) }) {
                return UrlValidationResult(false, "安全策略拦截：携带 API Key 访问公网域名时必须使用 https:// ($host)")
            }
        }
        return base
    }
}
