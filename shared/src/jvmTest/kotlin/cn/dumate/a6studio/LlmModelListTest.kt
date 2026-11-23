package cn.dumate.a6studio

import cn.dumate.a6studio.ai.LlmClient
import cn.dumate.a6studio.ai.LlmConfig
import cn.dumate.a6studio.ai.LlmProviderId
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 「拉取并选择模型」的 LlmClient.listModels 回归：
 * 解析 / 去重 / 排序、鉴权头、SSRF 拦截、Ollama 原生回退、畸形 JSON 不抛 IllegalArgumentException。
 */
class LlmModelListTest {

    private fun newServer(): Pair<com.sun.net.httpserver.HttpServer, Int> {
        val server = com.sun.net.httpserver.HttpServer.create(
            java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), 0), 0
        )
        server.start()
        return server to server.address.port
    }

    @Test
    fun `openai compatible parses dedups sorts and sends bearer`() = runBlocking {
        val requests = mutableListOf<Map<String, String>>()
        val (server, port) = newServer()
        server.createContext("/v1/models") { ex ->
            val auth = ex.requestHeaders.getFirst("Authorization") ?: ""
            requests += mapOf(
                "path" to ex.requestURI.path,
                "auth" to auth
            )
            val body = """{"data":[{"id":"gpt-4o-mini"},{"id":"chat-b"},{"id":"gpt-4o-mini"}]}"""
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.toByteArray().size.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
        val http = HttpClient(CIO)
        try {
            val client = LlmClient(http)
            val out = client.listModels(
                LlmProviderId.OPENAI_COMPATIBLE,
                LlmConfig(
                    openAiBaseUrl = "http://127.0.0.1:$port/v1",
                    openAiApiKey = "sk-test-dummy-key",
                    allowLocalInference = true
                )
            )
            assertEquals(listOf("chat-b", "gpt-4o-mini"), out.map { it.id })
            assertEquals(1, requests.size)
            assertEquals("Bearer sk-test-dummy-key", requests.single()["auth"])
        } finally {
            http.close()
            server.stop(0)
        }

    }

    @Test
    fun `ollama falls back to native api tags on 404`() = runBlocking {
        val hits = mutableListOf<String>()
        val (server, port) = newServer()
        server.createContext("/v1/models") { ex ->
            hits += "v1"
            ex.sendResponseHeaders(404, -1)
            ex.close()
        }
        server.createContext("/api/tags") { ex ->
            hits += "tags"
            val body = """{"models":[{"name":"qwen2.5:7b"},{"name":"llama3:8b"}]}"""
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.toByteArray().size.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
        val http = HttpClient(CIO)
        try {
            val client = LlmClient(http)
            val out = client.listModels(
                LlmProviderId.OLLAMA,
                LlmConfig(ollamaBaseUrl = "http://127.0.0.1:$port/v1", allowLocalInference = true)
            )
            assertEquals(listOf("llama3:8b", "qwen2.5:7b"), out.map { it.id })
            assertEquals(listOf("v1", "tags"), hits)
        } finally {
            http.close()
            server.stop(0)
        }
    }

    @Test
    fun `401 surfaces api key hint and redacts token in body`() = runBlocking {
        val (server, port) = newServer()
        // 假令牌必须满足 Redaction 的 sk-[0-9A-Za-z_-]{20,} 规则才会被脱敏；
        // 在代码里拼出来，避免字面量里的特殊字符让规则漏匹配。
        val fakeToken = "sk-" + "z".repeat(24)
        server.createContext("/v1/models") { ex ->
            val body = """{"error":{"message":"invalid key $fakeToken"}}"""
            ex.sendResponseHeaders(401, body.toByteArray().size.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
        val http = HttpClient(CIO)
        try {
            val client = LlmClient(http)
            try {
                client.listModels(
                    LlmProviderId.OPENAI_COMPATIBLE,
                    LlmConfig(
                        openAiBaseUrl = "http://127.0.0.1:$port/v1",
                        openAiApiKey = "sk-test-dummy-key",
                        allowLocalInference = true
                    )
                )
                fail("401 应当抛出异常")
            } catch (e: IllegalStateException) {
                val msg = e.message ?: ""
                assertTrue(msg.contains("HTTP 401"), "应包含状态码: $msg")
                assertTrue(msg.contains("API Key 无效或无权限"), "应包含 Key 提示: $msg")
                assertTrue(msg.contains("[REDACTED_API_KEY]"), "应把 sk- 假令牌脱敏: $msg")
            }
        } finally {
            http.close()
            server.stop(0)
        }
    }

    @Test
    fun `local inference disabled blocks ollama and lmstudio with zero requests`() = runBlocking {
        var requestCount = 0
        val (server, port) = newServer()
        server.createContext("/models") { ex ->
            requestCount++
            ex.sendResponseHeaders(200, -1)
            ex.close()
        }
        val http = HttpClient(CIO)
        try {
            val client = LlmClient(http)
            for (pid in listOf(LlmProviderId.OLLAMA, LlmProviderId.LMSTUDIO)) {
                try {
                    client.listModels(
                        pid,
                        LlmConfig(
                            ollamaBaseUrl = "http://127.0.0.1:$port/v1",
                            lmStudioBaseUrl = "http://127.0.0.1:$port/v1",
                            allowLocalInference = false
                        )
                    )
                    fail("$pid 未启用本地推理时必须被拒绝")
                } catch (e: IllegalStateException) {
                    assertTrue(
                        (e.message ?: "").contains("未启用本地推理"),
                        "应报「未启用本地推理」: ${e.message}"
                    )
                }
            }
            assertEquals(0, requestCount, "被拒绝后不应发出任何请求")
        } finally {
            http.close()
            server.stop(0)
        }
    }

    @Test
    fun `ssrf userinfo url is rejected before any request`() = runBlocking {
        var requestCount = 0
        val (server, port) = newServer()
        server.createContext("/v1/models") { ex ->
            requestCount++
            ex.sendResponseHeaders(200, -1)
            ex.close()
        }
        val http = HttpClient(CIO)
        try {
            val client = LlmClient(http)
            try {
                client.listModels(
                    LlmProviderId.OPENAI_COMPATIBLE,
                    LlmConfig(
                        openAiBaseUrl = "http://x@127.0.0.1:$port/v1",
                        openAiApiKey = "sk-test-dummy-key",
                        allowLocalInference = true
                    )
                )
                fail("携带 userinfo 的 URL 必须被拒绝")
            } catch (e: IllegalStateException) {
                assertTrue((e.message ?: "").contains("安全策略拦截"), "应报安全拦截: ${e.message}")
            }
            assertEquals(0, requestCount, "被拒绝后不应发出任何请求")
        } finally {
            http.close()
            server.stop(0)
        }
    }

    @Test
    fun `malformed json does not throw illegal argument`() = runBlocking {
        val payloads = listOf(
            """{"data":{}}""",
            """[]""",
            """plain text not json""",
            """{"data":[{"id":123}]}"""
        )
        val http = HttpClient(CIO)
        try {
            val client = LlmClient(http)
            for (body in payloads) {
                val (server, port) = newServer()
                server.createContext("/v1/models") { ex ->
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(200, body.toByteArray().size.toLong())
                    ex.responseBody.use { it.write(body.toByteArray()) }
                }
                try {
                    val out = client.listModels(
                        LlmProviderId.OPENAI_COMPATIBLE,
                        LlmConfig(
                            openAiBaseUrl = "http://127.0.0.1:$port/v1",
                            openAiApiKey = "sk-test-dummy-key",
                            allowLocalInference = true
                        )
                    )
                    assertTrue(out.isEmpty(), "畸形 JSON 应解析为空列表: $body")
                } catch (e: IllegalArgumentException) {
                    fail("畸形 JSON 不应抛 IllegalArgumentException: ${e.message}")
                } finally {
                    server.stop(0)
                }
            }
        } finally {
            http.close()
        }
    }

    @Test
    fun `gemini key stays in header and pagination merges and filters`() = runBlocking {
        // 重建一个带处理逻辑的服务端：第一页只给 embedding 模型 + nextPageToken，
        // 第二页给两个 generateContent 模型 + 一个重复 id。
        val (server, port) = newServer()
        val page = mapOf(
            "" to """{"models":[{"name":"models/embedding-001","displayName":"Embedding","supportedGenerationMethods":["embedContent"]}],"nextPageToken":"p2"}""",
            "p2" to """{"models":[{"name":"models/gemini-2.5-flash","displayName":"Flash","supportedGenerationMethods":["generateContent","countTokens"]},{"name":"models/gemini-2.5-pro","supportedGenerationMethods":["generateContent"]},{"name":"models/gemini-2.5-flash","supportedGenerationMethods":["generateContent"]}]}"""
        )
        val http = HttpClient(CIO)
        try {
            val client = LlmClient(http)
            LlmClient.Companion.geminiBaseUrl = "http://127.0.0.1:$port"
            val hits = mutableListOf<Pair<String, String?>>()
            server.createContext("/v1beta/models") { ex ->
                val token = ex.requestURI.query?.substringAfter("pageToken=", "")?.takeIf { it.isNotEmpty() }
                hits += (ex.requestURI.query ?: "") to ex.requestHeaders.getFirst("x-goog-api-key")
                val body = page[token ?: ""]
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, body!!.toByteArray().size.toLong())
                ex.responseBody.use { it.write(body.toByteArray()) }
            }
            val out = client.listModels(
                LlmProviderId.GEMINI,
                LlmConfig(geminiApiKey = "{{SECRET_m3rk4lgj}}", allowLocalInference = true)
            )
            assertEquals(listOf("gemini-2.5-flash", "gemini-2.5-pro"), out.map { it.id })
            assertEquals("Flash", out.first { it.id == "gemini-2.5-flash" }.displayName)
            // embedding-001 不支持 generateContent，应被过滤掉
            assertTrue(out.none { it.id.contains("embedding") })
            // Key 只在请求头，绝不进 URL 查询参数
            assertTrue(hits.all { !it.first.contains("key=") && !it.first.contains("Key=") })
            assertTrue(hits.all { it.second == "{{SECRET_m3rk4lgj}}" })
            assertTrue(hits.any { it.first.contains("pageToken=p2") })
        } finally {
            LlmClient.Companion.geminiBaseUrl = "https://generativelanguage.googleapis.com"
            http.close()
            server.stop(0)
        }
    }
}
