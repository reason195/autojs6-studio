package cn.dumate.a6studio

import kotlin.test.Test
import kotlin.test.assertNotNull

/** Regression: the whole JSON-RPC reply arrives inside a single chunk that also has a `:` frame. */
class SseParseTest {

    private val chunk = ":\n\nid: 8\nevent: message\ndata: {\"id\":1,\"result\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"serverInfo\":{\"name\":\"autojs6-mcp-server\",\"version\":\"1.3.1\"}},\"jsonrpc\":\"2.0\"}\n\n"

    @Test
    fun `extracts payload from a chunk that starts with a comment frame`() {
        val out = cn.dumate.a6studio.bridge.SseFrames.extractPayload(chunk)
        assertNotNull(out, "payload not found in: ${chunk.take(80)}")
        println("PARSED = $out")
    }
}
