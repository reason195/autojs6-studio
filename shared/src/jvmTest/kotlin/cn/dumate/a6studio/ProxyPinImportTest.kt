package cn.dumate.a6studio

import cn.dumate.a6studio.bridge.McpConfigParser
import cn.dumate.a6studio.bridge.McpServerConfig
import cn.dumate.a6studio.bridge.ProxyPinImport
import cn.dumate.a6studio.bridge.ProxyPinBridge
import cn.dumate.a6studio.security.UrlSecurity
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「ProxyPin MCP 配置导入」回归：
 * 默认端口 9000 / 多台时挑 proxypin / 安全校验先行 / CR-LF 令牌在 configure 内被拒且不崩。
 */
class ProxyPinImportTest {

    @Test
    fun `bare ip gets proxypin default port and path`() {
        assertEquals("http://192.168.1.8:9000/mcp", ProxyPinImport.pick("192.168.1.8")?.url)
        // 显式写了端口就不补默认值
        assertEquals("http://192.168.1.8:8765/mcp", ProxyPinImport.pick("192.168.1.8:8765")?.url)
    }

    @Test
    fun `device pane parse still uses default port 9637`() {
        // 真机导入的默认行为不能被这次改动影响
        assertEquals("http://192.168.1.6:9637/mcp", McpConfigParser.parse("192.168.1.6").single().url)
    }

    @Test
    fun `proxypin entry preferred over autojs6 with token carried`() {
        val text = """
            {"mcpServers":{
              "autojs6":{"url":"http://192.168.1.6:9637/mcp","token":"{{SECRET_szetjgrk}}"},
              "proxypin":{"url":"http://192.168.1.8:9000/mcp","token":"{{SECRET_khsuqywx}}"}
            }}
        """.trimIndent()
        val picked = ProxyPinImport.pick(text)
        assertEquals(McpServerConfig("proxypin", "http://192.168.1.8:9000/mcp", "{{SECRET_khsuqywx}}"), picked)
    }

    @Test
    fun `proxypin matched case insensitively and first entry kept when no match`() {
        // 大小写不敏感
        assertEquals(
            "http://192.168.1.9:9000/mcp",
            ProxyPinImport.pick("""{"mcpServers":{"ProxyPin-Desktop":{"url":"http://192.168.1.9:9000/mcp"}}}""")?.url
        )
        // 都不含 proxypin 时取第一台
        assertEquals(
            "http://192.168.1.10:9000/mcp",
            ProxyPinImport.pick("""{"mcpServers":{"a":{"url":"http://192.168.1.10:9000/mcp"},"b":{"url":"http://192.168.1.11:9000/mcp"}}}""")?.url
        )
    }

    @Test
    fun `ssrf userinfo url rejected leaves url unchanged`() {
        // pick 本身不做安全校验（StudioState 负责先校验再写状态），这里验证
        // validateEndpointUrl 对 userinfo 的拦截——StudioState.importProxyPinConfig 靠它挡住写状态。
        val picked = ProxyPinImport.pick("""{"mcpServers":{"x":{"url":"http://x@169.254.169.254/mcp"}}}""")
        assertEquals("http://x@169.254.169.254/mcp", picked?.url)
        val check = UrlSecurity.validateEndpointUrl(picked!!.url, allowLocal = true)
        assertTrue(!check.valid, "userinfo URL 必须被拒")
        assertTrue((check.reason ?: "").contains("用户信息"), "应报 userinfo 拦截: ${check.reason}")
    }

    @Test
    fun `crlf token rejected inside configure without crash`() {
        val http = HttpClient(CIO)
        try {
            val bridge = ProxyPinBridge(http)
            val url = "http://127.0.0.1:9000/mcp"
            // 令牌带 CR/LF → buildAuthorizationHeader 抛 BridgeException(INVALID_TOKEN)，
            // StudioState.importProxyPinConfig 捕获后 toast，绝不外抛崩溃。
            val bad = "abc\r\ndef"
            val rejected = runCatching { bridge.configure(url, bad, allowLocal = true) }.isFailure
            assertTrue(rejected, "CR/LF 令牌必须在 configure 内被拒")
            runCatching { bridge.configure(url, bad, allowLocal = true) }
                .exceptionOrNull()?.let { e ->
                    assertTrue(e is cn.dumate.a6studio.bridge.BridgeException, "应是 BridgeException: ${e.javaClass}")
                }
            // 正常令牌仍然可用
            bridge.configure(url, "token-ok", allowLocal = true)
        } finally {
            http.close()
        }
    }

    @Test
    fun `empty and garbage text yields null`() {
        assertEquals(null, ProxyPinImport.pick(""))
        assertEquals(null, ProxyPinImport.pick("hello world"))
        assertEquals(null, ProxyPinImport.pick("""{"mcpServers":{"x":{"command":"npx"}}}"""))
    }
}
