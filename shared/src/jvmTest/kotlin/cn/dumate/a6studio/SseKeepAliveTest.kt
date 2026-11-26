package cn.dumate.a6studio

import cn.dumate.a6studio.bridge.McpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * M11: AutoJs6's MCP Server answers over SSE and may keep the connection open after the
 * reply. A buffered `http.post {}` only returns when the socket closes, so every call took
 * the full timeout. This server sends one JSON-RPC event per request and then holds the
 * socket open; the client must return in well under the timeout.
 */
class SseKeepAliveTest {

    private fun startServer(): Pair<ServerSocket, MutableList<Socket>> {
        val server = ServerSocket(0)
        val open = mutableListOf<Socket>()
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                synchronized(open) { open += s }
                thread(isDaemon = true) { serve(s) }
            }
        }
        return server to open
    }

    private fun serve(s: Socket) {
        val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
        var len = 0
        while (true) {
            val line = r.readLine() ?: return
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) len = line.substringAfter(':').trim().toInt()
        }
        val body = CharArray(len).also { var n = 0; while (n < len) { val k = r.read(it, n, len - n); if (k < 0) break; n += k } }
        val req = String(body)
        val id = Regex("\"id\"\\s*:\\s*(\\d+)").find(req)?.groupValues?.get(1)
        val out = s.getOutputStream()
        if (id == null) {
            // notifications/initialized: no answer expected.
            out.write("HTTP/1.1 202 Accepted\r\nContent-Length: 0\r\n\r\n".toByteArray()); out.flush()
            return
        }
        val result = if (req.contains("\"initialize\"")) """{"protocolVersion":"2024-11-05"}"""
        else """{"content":[{"type":"text","text":"pong"}]}"""
        val event = ": ok\n\ndata: {\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":$result}\n\n"
        val bytes = event.toByteArray(Charsets.UTF_8)
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nmcp-session-id: s1\r\n" +
                "Transfer-Encoding: chunked\r\n\r\n").toByteArray()
        )
        out.write("${bytes.size.toString(16)}\r\n".toByteArray()); out.write(bytes); out.write("\r\n".toByteArray())
        out.flush()
        // Deliberately never send the terminating chunk: the stream stays open.
    }

    @Test
    fun `reply on a keep-alive SSE stream returns without waiting for the timeout`() = runBlocking {
        val (server, open) = startServer()
        try {
            val client = McpClient(HttpClient(CIO) { install(HttpTimeout) })
            client.setUrl("http://127.0.0.1:${server.localPort}/mcp")
            val t0 = System.nanoTime()
            client.ping()
            val ms = (System.nanoTime() - t0) / 1_000_000
            // The default request timeout is 15s; a buffered read would take at least that.
            assertTrue(ms < 3_000, "ping took ${ms}ms: the SSE reply was not read as a stream")
        } finally {
            synchronized(open) { open.forEach { runCatching { it.close() } } }
            server.close()
        }
    }
}
