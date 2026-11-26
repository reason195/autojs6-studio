package cn.dumate.a6studio

import cn.dumate.a6studio.bridge.McpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A trailing slash in the address must not silently drop the token. */
class AuthKeyTest {

    @Test
    fun `trailing slash address still sends the token`() {
        val c = McpClient(HttpClient(CIO))
        c.setUrl("http://192.168.1.5:9637/mcp/")
        c.setAuthFor("http://192.168.1.5:9637/mcp/", "SECRET123")
        val auth = c.currentAuthHeader()
        println("AUTH header=$auth url=${c.currentUrl} keys=${c.authMap().keys}")
        assertEquals("Bearer SECRET123", auth)
        assertEquals("http://192.168.1.5:9637/mcp", c.currentUrl)
    }

    @Test
    fun `token survives url set after auth`() {
        val c = McpClient(HttpClient(CIO))
        // Reverse order: token first, then address (what switchDevice / applyBridgeConfig do).
        c.setAuthFor("http://192.168.1.5:9637/mcp", "abc")
        c.setUrl("http://192.168.1.5:9637/mcp")
        assertTrue(c.currentAuthHeader() == "Bearer abc", "token lost")
    }

    @Test
    fun `legacy stored keys with slash are folded`() {
        val c = McpClient(HttpClient(CIO))
        c.setAuthMap(mapOf("http://h:9637/mcp/" to "Bearer old"))
        c.setUrl("http://h:9637/mcp")
        assertEquals("Bearer old", c.currentAuthHeader())
    }
}
