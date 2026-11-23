package cn.dumate.a6studio

import cn.dumate.a6studio.bridge.McpConfigParser
import cn.dumate.a6studio.bridge.McpServerConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every way a user might hand us MCP connection details. */
class McpConfigParserTest {

    @Test
    fun `AutoJs6 plugin export with two objects back to back`() {
        // Shape of mcp.txt: the plugin page exports one object per device, pasted one after another.
        val text = """
            {"mcpServers":{"AutoJs6-Oneplus-12":{"type":"http","url":"http://192.168.1.6:9637/mcp","headers":{"Authorization":"Bearer AAAA1111"}}}}
            {"mcpServers":{"AutoJs6-Meizu-17-Pro":{"url":"http://192.168.1.17:9637/mcp","headers":{"authorization":"Bearer BBBB2222"}}}}
        """.trimIndent()
        assertEquals(
            listOf(
                McpServerConfig("AutoJs6-Oneplus-12", "http://192.168.1.6:9637/mcp", "AAAA1111"),
                McpServerConfig("AutoJs6-Meizu-17-Pro", "http://192.168.1.17:9637/mcp", "BBBB2222")
            ),
            McpConfigParser.parse(text)
        )
    }

    @Test
    fun `one mcpServers object with several devices`() {
        val text = """{"mcpServers":{"a":{"url":"http://10.0.0.2:9637/mcp"},"b":{"url":"http://10.0.0.3:9637/mcp","token":"tok12345"}}}"""
        val r = McpConfigParser.parse(text)
        assertEquals(listOf("a", "b"), r.map { it.name })
        assertEquals("tok12345", r[1].token)
    }

    @Test
    fun `VS Code servers key, bare object and array`() {
        assertEquals("http://h:1/mcp", McpConfigParser.parse("""{"servers":{"x":{"url":"http://h:1/mcp"}}}""").single().url)
        assertEquals("k1234567", McpConfigParser.parse("""{"url":"http://h:1/mcp","token":"Bearer k1234567"}""").single().token)
        assertEquals(2, McpConfigParser.parse("""[{"url":"http://a:1/mcp"},{"url":"http://b:1/mcp"}]""").size)
    }

    @Test
    fun `plain lines with url and token`() {
        val text = """
            # 我的两台手机
            http://192.168.1.6:9637/mcp  5L0Fxxxxm0
            url=http://192.168.1.17:9637/mcp token=I5OYxxxxtY name=魅族
        """.trimIndent()
        val r = McpConfigParser.parse(text)
        assertEquals(2, r.size)
        assertEquals("5L0Fxxxxm0", r[0].token)
        assertEquals(McpServerConfig("魅族", "http://192.168.1.17:9637/mcp", "I5OYxxxxtY"), r[1])
    }

    @Test
    fun `bare ip gets default port and path`() {
        assertEquals("http://192.168.1.6:9637/mcp", McpConfigParser.parse("192.168.1.6").single().url)
        assertEquals("http://192.168.1.6:8765/mcp", McpConfigParser.parse("192.168.1.6:8765").single().url)
        assertEquals("http://192.168.1.6:9637/mcp", McpConfigParser.parse("http://192.168.1.6:9637").single().url)
    }

    @Test
    fun `token on the next line belongs to the url above`() {
        val r = McpConfigParser.parse("http://192.168.1.6:9637/mcp\nAuthorization: Bearer ZZZZ9999")
        assertEquals("ZZZZ9999", r.single().token)
    }

    @Test
    fun `same address twice keeps the one with a token`() {
        val text = "http://192.168.1.6:9637/mcp\n" +
            """{"mcpServers":{"一加":{"url":"http://192.168.1.6:9637/mcp/","headers":{"Authorization":"Bearer T0KEN123"}}}}"""
        val r = McpConfigParser.parse(text)
        assertEquals(1, r.size)
        assertEquals("T0KEN123", r.single().token)
    }

    @Test
    fun `garbage yields nothing and never throws`() {
        assertTrue(McpConfigParser.parse("").isEmpty())
        assertTrue(McpConfigParser.parse("hello world").isEmpty())
        assertTrue(McpConfigParser.parse("{ not json").isEmpty())
        assertTrue(McpConfigParser.parse("""{"mcpServers":{"x":{"command":"npx"}}}""").isEmpty())
    }
}
