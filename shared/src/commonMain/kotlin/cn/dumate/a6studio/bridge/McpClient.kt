package cn.dumate.a6studio.bridge

import cn.dumate.a6studio.platform.nowMillis
import kotlin.concurrent.Volatile
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.withTimeout
import io.ktor.utils.io.readAvailable
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * MCP-over-HTTP client for the AutoJs6 MCP Server plugin.
 *
 * Protocol: `initialize` → `notifications/initialized` → `tools/call`, over either plain JSON
 * or `text/event-stream` responses. Tokens are held in memory per address only — never written
 * to the project file.
 */
class McpClient(
    private val http: HttpClient,
    /** 在超时/连接失败的错误消息里区分不同的 MCP 服务端（如「ProxyPin」vs「手机 AutoJs6」）。 */
    val label: String = "手机 AutoJs6"
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mutex = Mutex()
    private val seqMutex = Mutex()

    @Volatile private var url: String = ""
    @Volatile private var sessionId: String = ""
    @Volatile private var ready: Boolean = false
    private var seq = 0
    @Volatile private var authHeader: String? = null

    private suspend fun nextSeq(): Int = seqMutex.withLock { ++seq }

    private val authByUrl = mutableMapOf<String, String>()

    var protocolVersion: String = "2024-11-05"

    val currentUrl: String get() = url
    val isReady: Boolean get() = ready

    /**
     * Canonical address form used as the auth-map key. `setUrl` and `setAuthFor` must agree on
     * this: normalising in one place only meant "http://ip:9637/mcp/" stored a token the request
     * never picked up — the device answered 401 and the UI wrongly blamed the token.
     */
    private fun normalizeUrl(addr: String): String = addr.trim().trimEnd('/')

    /** Effective Authorization header for the current address (null when none). */
    fun currentAuthHeader(): String? = authHeader

    /** Same header as a map, for tests and diagnostics. */
    fun headersSnapshot(): Map<String, String> = authHeader?.let { mapOf("Authorization" to it) } ?: emptyMap()

    fun setUrl(newUrl: String) {
        val u = normalizeUrl(newUrl)
        // M10: applyBridgeConfig() calls this before every device action. Resetting an
        // unchanged address dropped the live session mid-run (a 70s device run + opening the
        // picker = a second handshake that invalidated the first).
        if (u == url && ready) return
        url = u
        ready = false
        sessionId = ""
        authHeader = authByUrl[url]
    }

    companion object {
        private val CONTROL_CHAR_RE = Regex("[\\u0000-\\u001f\\u007f]")

        /**
         * 将 MCP 鉴权令牌规范化为 `Bearer <token>`，并拦截 CR/LF 等控制字符防止 HTTP 头注入。
         */
        fun buildAuthorizationHeader(rawToken: String): String? {
            val trimmed = rawToken.trim()
            if (trimmed.isEmpty()) return null
            if (CONTROL_CHAR_RE.containsMatchIn(trimmed)) {
                throw BridgeException("MCP 鉴权令牌包含非法控制字符", "INVALID_TOKEN")
            }
            val token = if (trimmed.startsWith("Bearer ", ignoreCase = true)) {
                trimmed.substring(7).trim()
            } else {
                trimmed
            }
            return if (token.isEmpty()) null else "Bearer $token"
        }
    }

    fun setAuth(token: String) {
        val newHeader = buildAuthorizationHeader(token)
        if (newHeader == authHeader && ready) return
        authHeader = newHeader
        ready = false
        sessionId = ""
    }

    fun setAuthFor(addr: String, token: String) {
        val key = normalizeUrl(addr)
        if (key.isEmpty()) return
        val headerVal = buildAuthorizationHeader(token)
        if (headerVal == null) {
            authByUrl.remove(key)
            if (url == key) authHeader = null
        } else {
            authByUrl[key] = headerVal
            if (url == key) authHeader = headerVal
        }
    }

    fun authMap(): Map<String, String> = authByUrl.toMap()

    fun setAuthMap(map: Map<String, String>) {
        authByUrl.clear()
        // Keys may come from storage written by an older build (with a trailing slash); fold
        // them onto the canonical form so lookups keep working.
        map.forEach { (k, v) -> authByUrl[normalizeUrl(k)] = v }
        authHeader = authByUrl[url]
    }

    fun resetPendingState() {
        ready = false
        sessionId = ""
    }

    private suspend fun post(payload: JsonObject, extraHeaders: Map<String, String> = emptyMap(), timeoutMs: Long = 15_000): JsonElement? {
        val target = url.ifBlank { throw BridgeException("未填写桥接地址", "NO_URL") }
        // Devices live on the LAN, so local addresses stay allowed; cloud metadata endpoints and
        // hostnames that resolve to them do not. IP literals skip DNS, so this is cheap per call.
        val check = cn.dumate.a6studio.security.UrlSecurity.validateEndpointUrlResolved(target, allowLocal = true)
        if (!check.valid) throw BridgeException(check.reason ?: "桥接地址无效", "INVALID_URL")
        val headers = buildMap {
            put("Accept", "application/json, text/event-stream")
            put("Content-Type", "application/json")
            authHeader?.let { put("Authorization", it) }
            if (sessionId.isNotEmpty()) put("mcp-session-id", sessionId)
            // x-a6-* keys are local hints for this method, not headers for the device.
            putAll(extraHeaders.filterKeys { !it.startsWith("x-a6-") })
        }

        // M11: `http.post {}` buffers the whole body (SaveBody) before returning, so against
        // a keep-alive SSE stream it only returned once the server closed the socket or the
        // timeout fired — readFirstSsePayload was reading an in-memory copy. preparePost +
        // execute { } hands over the live response; we read it inside the block and the
        // connection is released as soon as the block returns.
        // Per-request timeout on the *shared* client: no derived client, no leaked pool.
        // Session handling, status checks and body reading all happen inside execute { }:
        // the streamed body is only readable there.
        val raw: Pair<Int, String> = try {
            http.preparePost(target) {
                contentType(ContentType.Application.Json)
                headers.forEach { (k, v) -> header(k, v) }
                setBody(payload.toString())
                timeout {
                    requestTimeoutMillis = timeoutMs
                    connectTimeoutMillis = minOf(timeoutMs, 8_000)
                    socketTimeoutMillis = timeoutMs
                }
            }.execute { response ->
                readResponse(response, target, timeoutMs, extraHeaders)
            }
        } catch (e: BridgeException) {
            throw e
        } catch (e: io.ktor.client.plugins.HttpRequestTimeoutException) {
            throw BridgeException(timeoutMessage(target, timeoutMs), "TIMEOUT")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val detail = e.message ?: e.toString()
            if (detail.contains("CLEARTEXT", ignoreCase = true) || detail.contains("cleartext", ignoreCase = true)) {
                throw BridgeException(
                    "系统拦截了明文 HTTP 连接（$target）。这是 App 的网络安全配置问题，不是手机或网络的问题：" +
                        "请安装包含 network_security_config 的新版本。",
                    "CLEARTEXT_BLOCKED"
                )
            }
            throw BridgeException(networkMessage(target, detail), "NETWORK")
        }
        val (status, text) = raw
        if (text.isBlank()) {
            // Keep the raw SSE for diagnosis: "no response" hides whether the reply was malformed.
            val msg = if (label == "手机 AutoJs6") {
                "手机返回了空的响应体（HTTP $status）。若持续出现，请在手机上关闭再打开 MCP Server 插件。"
            } else {
                "$label 返回了空的响应体（HTTP $status）。若持续出现，请重启 $label 的 MCP Server。"
            }
            throw BridgeException(msg, "EMPTY_BODY")
        }
        return parseJsonOrThrow(text) ?: throw BridgeException(
            "无法解析 MCP 响应（原文前 160 字符：${text.take(160)}）",
            "BAD_RESPONSE"
        )
    }

    /**
     * 发送 MCP 单向通知（如 `notifications/initialized`）。
     * 服务端常返回 202/204 空响应体，因此不走 `post()` 的非空 JSON 校验，且保留 `CancellationException`。
     */
    private suspend fun postNotification(payload: JsonObject, timeoutMs: Long = 5_000) {
        val target = url.ifBlank { return }
        val check = cn.dumate.a6studio.security.UrlSecurity.validateEndpointUrlResolved(target, allowLocal = true)
        if (!check.valid) return
        val headers = buildMap {
            put("Accept", "application/json, text/event-stream")
            put("Content-Type", "application/json")
            authHeader?.let { put("Authorization", it) }
            if (sessionId.isNotEmpty()) put("mcp-session-id", sessionId)
        }
        try {
            http.preparePost(target) {
                contentType(ContentType.Application.Json)
                headers.forEach { (k, v) -> header(k, v) }
                setBody(payload.toString())
                timeout {
                    requestTimeoutMillis = timeoutMs
                    connectTimeoutMillis = minOf(timeoutMs, 3_000)
                    socketTimeoutMillis = timeoutMs
                }
            }.execute { response ->
                response.headers["mcp-session-id"]?.let { sessionId = it }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // 部分服务端不响应或主动关闭 initialized 通知连接，容错忽略
        }
    }

    /** Runs inside execute { }: checks the status, records the session and reads the body. */
    private suspend fun readResponse(
        response: HttpResponse, target: String, timeoutMs: Long, extraHeaders: Map<String, String>
    ): Pair<Int, String> {
        if (response.status.value == 404 && extraHeaders["x-a6-had-session"] == "1") {
            // M10: per the MCP spec an expired session answers 404. That is not a wrong
            // address; rpc() re-initialises and retries once.
            throw BridgeException("MCP 会话已过期", "SESSION_EXPIRED")
        }
        if (response.status.value == 404 || response.status.value == 405) {
            throw BridgeException("HTTP ${response.status.value}：地址不是 MCP 端点，或桥接未启动", "BAD_ENDPOINT")
        }

        response.headers["mcp-session-id"]?.let { sessionId = it }

        // 401 is a token problem, not a network one: say so instead of "no response".
        if (response.status.value == 401) {
            val msg = if (label == "手机 AutoJs6") {
                "手机拒绝了令牌（401）。请在手机的 AutoJs6 → 插件 → MCP Server → 设置里复制当前的" +
                    "「令牌 / Token」，粘贴到这里的令牌框；令牌在重新配对后会变，旧的一定会被拒。"
            } else {
                "$label 拒绝了令牌（401）。请在 $label 的 MCP Server 设置中确认并重新复制当前的「令牌 / Token」。"
            }
            throw BridgeException(msg, "UNAUTHORIZED")
        }

        // L4: any other non-2xx is an HTTP error. Parsing its body as JSON used to produce a
        // misleading "返回内容不是 JSON" instead of the real status code.
        val code = response.status.value
        if (code !in 200..299) {
            throw BridgeException("$label 返回 HTTP $code（${redactUrl(target)}）。若持续出现，请重启 $label 的 MCP Server。", "HTTP_ERROR")
        }

        val contentType = response.headers["content-type"] ?: ""
        val isSse = contentType.contains("text/event-stream", ignoreCase = true)
        val body = try {
            if (isSse) {
                // MCP answers over a *keep-alive* SSE stream, so reading until EOF blocks until the
                // socket timeout (measured: raw socket got 200 in <1s while this timed out at 15s).
                // Read event-by-event and stop as soon as one carries a JSON-RPC payload.
                readFirstSsePayload(response, target, timeoutMs)
            } else {
                readBodyCapped(response)
            }
        } catch (e: BridgeException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw BridgeException(timeoutMessage(target, timeoutMs), "TIMEOUT")
        }
        // The payload may arrive with a leading `:` comment frame; SseFrames handles framing.
        val text = if (isSse && body.isNotBlank()) {
            SseFrames.extractPayload(body) ?: body
        } else body
        return response.status.value to text
    }

    /**
     * Reads the SSE stream until one complete `data:` JSON payload has arrived, then returns it.
     *
     * Two details matter here, both measured against AutoJs6 6.8.0:
     *  - the server keeps the connection open (keep-alive SSE), so waiting for EOF blocks until the
     *    socket timeout — stop as soon as a payload is complete;
     *  - chunks arrive split mid-line (the first chunk is just `":"`), so line-oriented reads stall.
     *    Accumulating raw bytes and scanning for a balanced JSON object is robust to both.
     */
    private suspend fun readFirstSsePayload(
        response: HttpResponse,
        target: String,
        timeoutMs: Long
    ): String {
        val channel = response.bodyAsChannel()
        val acc = SseAccumulator()
        val buffer = ByteArray(4096)
        try {
            withTimeout(timeoutMs) {
                while (true) {
                    val n = channel.readAvailable(buffer, 0, buffer.size)
                    if (n <= 0) break
                    acc.feed(buffer, n)?.let { return@withTimeout it }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw BridgeException(timeoutMessage(target, timeoutMs), "TIMEOUT")
        } finally {
            runCatching { channel.cancel(null) }
        }
        return SseFrames.extractPayload(acc.text()) ?: ""
    }

    /**
     * Plain JSON body, read with the same cap as the SSE path. `bodyAsText()` buffered whatever
     * the server sent, so a hostile or broken endpoint could stream until the app ran out of memory.
     */
    private suspend fun readBodyCapped(response: HttpResponse): String {
        val limit = SseAccumulator.MAX_BYTES
        response.headers["content-length"]?.toLongOrNull()?.let {
            if (it > limit) throw tooLarge(limit)
        }
        val channel = response.bodyAsChannel()
        var data = ByteArray(8 * 1024)
        var size = 0
        val buffer = ByteArray(4096)
        try {
            while (true) {
                val n = channel.readAvailable(buffer, 0, buffer.size)
                if (n <= 0) break
                if (size + n > limit) throw tooLarge(limit)
                if (size + n > data.size) data = data.copyOf(maxOf(data.size * 2, size + n))
                buffer.copyInto(data, size, 0, n)
                size += n
            }
        } finally {
            runCatching { channel.cancel(null) }
        }
        return data.decodeToString(0, size)
    }

    private fun tooLarge(limit: Int) =
        BridgeException("MCP 响应超过 ${limit / 1024 / 1024}MB 上限，已中止", "RESPONSE_TOO_LARGE")

    private fun parseJsonOrThrow(text: String): JsonElement? = try {
        json.parseToJsonElement(text)
    } catch (e: Exception) {
        throw BridgeException("返回内容不是 JSON：${text.take(120)}", "BAD_RESPONSE")
    }

    private fun redactUrl(raw: String): String = raw
        .replace(Regex("""://([^/@\s]+)@"""), "://***@")
        .replace(Regex("""([?&])(token|key|secret|password)=([^&\s]+)""", RegexOption.IGNORE_CASE), "$1$2=***")

    private fun timeoutMessage(target: String, ms: Long): String {
        val secs = (ms / 1000).toInt()
        val base = "$label MCP 无响应：${secs}s 内没有回包（${redactUrl(target)}）。"
        return if (label == "手机 AutoJs6") {
            base + "端口在听却不应答，通常是 $label 主线程被占满（正在跑的录制脚本、无障碍查询卡住）。" +
                "处理：在手机上按音量下键停掉录制脚本，或从 AutoJs6 停止全部脚本，再重启 MCP Server 后重连。"
        } else {
            base + "请检查 $label 是否正在运行、MCP 服务端口是否一致，或在 $label 中重启 MCP 服务后重试。"
        }
    }

    private fun networkMessage(target: String, detail: String): String =
        "连不上 ${redactUrl(target)}：$detail。检查：①$label 的 MCP Server 是否在运行（屏幕别锁、加到后台白名单）；" +
            "②IP 是否变了；③网络是否可达（同一 Wi-Fi / 桥接代理）。"

    private suspend fun rpc(method: String, params: JsonObject?, timeoutMs: Long = 15_000): JsonElement {
        return try {
            rpcOnce(method, params, timeoutMs)
        } catch (e: BridgeException) {
            if (e.code != "SESSION_EXPIRED") throw e
            mutex.withLock {
                ready = false
                sessionId = ""
                initializeLocked()
            }
            rpcOnce(method, params, timeoutMs)
        }
    }

    private suspend fun rpcOnce(method: String, params: JsonObject?, timeoutMs: Long): JsonElement {
        val id = nextSeq()
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params ?: buildJsonObject { })
        }
        val marker = if (sessionId.isNotEmpty()) mapOf("x-a6-had-session" to "1") else emptyMap()
        val response = post(payload, extraHeaders = marker, timeoutMs = timeoutMs)
            ?: throw BridgeException("空响应", "EMPTY_RESPONSE")
        val obj = response as? JsonObject
            ?: throw BridgeException("MCP 响应不是 JSON 对象：${response.toString().take(120)}", "BAD_RESPONSE")
        // M10: a progress notification or a server request read off the SSE stream is not
        // our answer. Say so instead of treating a missing `result` as success.
        val respId = (obj["id"] as? JsonPrimitive)?.content
        if (respId != null && respId != id.toString()) {
            throw BridgeException("MCP 响应 id 不匹配（期望 $id，收到 $respId）", "BAD_RESPONSE")
        }
        if (respId == null && obj["result"] == null && obj["error"] == null) {
            throw BridgeException("收到的是服务端通知而不是结果：${obj["method"] ?: obj.toString().take(80)}", "BAD_RESPONSE")
        }
        (obj["error"] as? JsonObject)?.let { err ->
            throw BridgeException(err["message"]?.jsonPrimitive?.content ?: err.toString(), "RPC_ERROR")
        }
        return obj["result"] ?: JsonPrimitive("")
    }

    suspend fun initialize(): JsonElement = mutex.withLock { initializeLocked() }

    /** Body of [initialize]; the caller must hold [mutex]. */
    private suspend fun initializeLocked(): JsonElement {
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", nextSeq())
            put("method", "initialize")
            put("params", buildJsonObject {
                put("protocolVersion", protocolVersion)
                put("capabilities", buildJsonObject { })
                put("clientInfo", buildJsonObject {
                    put("name", "AutoJs6 Studio")
                    put("version", "1.0.0")
                })
            })
        }
        val res = post(payload) ?: throw BridgeException(
            "initialize 无响应：$label 没有返回可解析的内容（url=${redactUrl(url)}，session=${if (sessionId.isEmpty()) "-" else "***"}）。" +
                "常见原因：MCP 服务的局域网访问未开启，或服务刚重启还没就绪。",
            "EMPTY_RESPONSE"
        )
        val obj = res.jsonObject
        (obj["error"] as? JsonObject)?.let {
            throw BridgeException(it["message"]?.jsonPrimitive?.content ?: "initialize 失败", "RPC_ERROR")
        }
        postNotification(buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "notifications/initialized")
            put("params", buildJsonObject { })
        })
        ready = true
        // `post` returns the whole JSON-RPC envelope ({"id":…,"result":…}), so unwrap `result`.
        return obj["result"] ?: JsonPrimitive("")
    }

    /**
     * Two coroutines starting at once (audit + picker) both saw `!ready` and each sent
     * `initialize`; the second reply overwrote `sessionId` and the first session's next request
     * was rejected by the device. Re-check inside the lock so the handshake happens once.
     */
    private suspend fun ensureReady() {
        if (ready) return
        mutex.withLock { if (!ready) initializeLocked() }
    }

    suspend fun callTool(name: String, args: JsonObject = buildJsonObject { }, timeoutMs: Long = 15_000): McpToolResult {
        ensureReady()
        val result = rpc("tools/call", buildJsonObject {
            put("name", name)
            put("arguments", args)
        }, timeoutMs).jsonObject

        val contents = (result["content"] as? JsonArray)?.mapNotNull { el ->
            val c = el.jsonObject
            McpContent(
                type = c["type"]?.jsonPrimitive?.content ?: "",
                text = c["text"]?.jsonPrimitive?.contentOrNullSafe(),
                data = c["data"]?.jsonPrimitive?.contentOrNullSafe(),
                mimeType = c["mimeType"]?.jsonPrimitive?.contentOrNullSafe()
            )
        }.orEmpty()

        val text = contents.mapNotNull { it.text }.joinToString("\n")
        val isError = (result["isError"] as? JsonPrimitive)?.content?.toBoolean() ?: false
        // Protocol-level failures arrive as tool errors (pairing window, rate limit,
        // bad argument). Turning them into BridgeException gives the UI an actionable code.
        if (isError) throw classifyToolError(name, text)
        return McpToolResult(text = text, content = contents, isError = false)
    }

    suspend fun listTools(timeoutMs: Long = 15_000, cursor: String? = null): JsonElement {
        ensureReady()
        return rpc("tools/list", buildJsonObject {
            if (!cursor.isNullOrBlank()) put("cursor", cursor)
        }, timeoutMs)
    }

    // ---- convenience wrappers used by the UI --------------------------------

    suspend fun ping(): Long {
        resetPendingState()
        val t0 = nowMillis()
        initialize()
        callTool("device_ping")
        return nowMillis() - t0
    }

    /**
     * `device_info` returns a nested schema (measured on AutoJs6 6.8.0 + MCP plugin 1.3.1):
     * `build.{brand,model,sdkInt,release}`, `host.{packageName,versionName,versionCode}`,
     * `accessibility.operational`, `battery.percent`, `plugins.families[].plugins[]`.
     * Older plugin builds returned a flat map, so both shapes are accepted.
     */
    suspend fun deviceInfo(timeoutMs: Long = 15_000): DeviceInfo {
        val r = callTool("device_info", timeoutMs = timeoutMs)
        val root = runCatching { json.parseToJsonElement(r.text).jsonObject }.getOrNull()
            ?: return DeviceInfo()

        fun JsonObject.text(key: String): String =
            this[key]?.jsonPrimitive?.contentOrNullSafe().orEmpty()

        val build = root["build"] as? JsonObject
        val host = root["host"] as? JsonObject
        val acc = root["accessibility"] as? JsonObject
        val plugins = root["plugins"] as? JsonObject
        val families = plugins?.get("families") as? JsonArray

        val pluginNames = families?.mapNotNull { fam ->
            val f = fam as? JsonObject ?: return@mapNotNull null
            val list = f["plugins"] as? JsonArray
            val enabled = list?.mapNotNull { p ->
                val po = p as? JsonObject ?: return@mapNotNull null
                if (po["enabled"]?.jsonPrimitive?.content == "true") {
                    po.text("packageName").substringAfterLast('.')
                } else null
            }.orEmpty()
            if (enabled.isEmpty()) null else f.text("id") + "(" + enabled.size + ")"
        }.orEmpty()

        return DeviceInfo(
            manufacturer = build?.text("manufacturer")?.ifEmpty { build.text("brand") }
                ?: root.text("manufacturer"),
            model = build?.text("model") ?: root.text("model"),
            androidRelease = build?.text("release") ?: root.text("androidRelease"),
            androidApi = build?.text("sdkInt") ?: root.text("androidApi"),
            versionName = host?.text("versionName") ?: root.text("versionName"),
            versionCode = host?.text("versionCode") ?: root.text("versionCode"),
            plugin = if (pluginNames.isEmpty()) root.text("plugin") else pluginNames.joinToString(", "),
            pluginId = host?.text("packageName") ?: root.text("pluginId"),
            process = host?.text("processName") ?: root.text("process"),
            time = root.text("time"),
            accessibilityReady = acc?.get("operational")?.jsonPrimitive?.content == "true",
            batteryPercent = (root["battery"] as? JsonObject)?.text("percent").orEmpty()
        )
    }
    suspend fun runScript(source: String, name: String): String {
        val r = callTool("script_run", buildJsonObject {
            put("source", source)
            put("name", name)
            put("timeoutMs", 60_000)
            put("captureConsole", true)
            put("maxConsoleLines", 300)
        }, timeoutMs = 70_000)
        return r.text
    }

    suspend fun scriptRun(source: String, name: String, args: JsonObject?, waitForCompletion: Boolean = false): String {
        val a = buildJsonObject {
            put("source", source)
            put("name", name)
            put("waitForCompletion", waitForCompletion)
            put("captureConsole", false)
            if (args != null) put("arguments", args)
        }
        return callTool("script_run", a, timeoutMs = 65_000).text
    }

    suspend fun scriptStop(executionId: Long): String =
        callTool("script_stop", buildJsonObject { put("executionId", executionId) }, 5_000).text

    suspend fun consoleTail(lines: Int): String =
        callTool("console_tail", buildJsonObject { put("lines", lines) }).text

    suspend fun uiDumpJson(maxNodes: Int = 400, maxDepth: Int = 32, timeoutMs: Long = 15_000): JsonObject {
        val r = callTool("ui_dump", buildJsonObject {
            put("format", "json")
            put("maxNodes", maxNodes)
            put("maxDepth", maxDepth)
            put("visibleOnly", true)
        }, timeoutMs = timeoutMs)
        return json.parseToJsonElement(r.text).jsonObject
    }

    suspend fun uiDumpText(maxNodes: Int = 120, maxDepth: Int = 20): String =
        callTool("ui_dump", buildJsonObject {
            put("format", "text")
            put("maxNodes", maxNodes)
            put("maxDepth", maxDepth)
        }).text

    suspend fun uiFind(selector: JsonObject, limit: Int = 20): UiFindResult {
        val r = callTool("ui_find", buildJsonObject {
            put("selector", selector)
            put("limit", limit)
        })
        val o = runCatching { json.parseToJsonElement(r.text).jsonObject }.getOrNull()
            ?: return UiFindResult(0, 0, false, r.text)
        return UiFindResult(
            count = o["count"]?.jsonPrimitive?.intOrNull ?: 0,
            returned = o["returned"]?.jsonPrimitive?.intOrNull ?: 0,
            truncated = o["truncated"]?.jsonPrimitive?.content?.toBoolean() ?: false,
            nodesJson = o["nodes"]?.toString() ?: "[]"
        )
    }

    suspend fun explainSelector(selector: JsonObject): String =
        callTool("ui_explain_selector", buildJsonObject { put("selector", selector) }).text

    suspend fun currentWindow(timeoutMs: Long = 15_000): WindowInfo {
        val r = callTool("ui_current_window", timeoutMs = timeoutMs)
        val o = runCatching { json.parseToJsonElement(r.text).jsonObject }.getOrNull() ?: return WindowInfo()
        return WindowInfo(
            packageName = o["packageName"]?.jsonPrimitive?.contentOrNullSafe() ?: o["package"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(),
            activityName = o["activityName"]?.jsonPrimitive?.contentOrNullSafe() ?: o["activity"]?.jsonPrimitive?.contentOrNullSafe().orEmpty()
        )
    }

    suspend fun screenCapture(maxWidth: Int = 1080, quality: Int = 70): ScreenCapture {
        ensureReady()
        val result = rpc("tools/call", buildJsonObject {
            put("name", "screen_capture")
            put("arguments", buildJsonObject {
                put("format", "jpeg")
                put("quality", quality)
                put("maxWidth", maxWidth)
            })
        }, timeoutMs = 20_000).jsonObject

        val contents = result["content"] as? JsonArray ?: JsonArray(emptyList())
        val textPart = contents.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNullSafe() }.joinToString("\n")
        // screen_capture can fail without an empty image: rate limiting (30/min on device) and a
        // pending pair request both arrive as a text part, so classify them here.
        val shotIsError = (result["isError"] as? JsonPrimitive)?.content?.toBoolean() ?: false
        if (shotIsError || textPart.contains("RATE_LIMITED") || textPart.contains("PAIRING_REQUIRED")) {
            throw classifyToolError("screen_capture", textPart)
        }
        val image = contents.firstOrNull { (it as? JsonObject)?.get("type")?.jsonPrimitive?.content == "image" } as? JsonObject
        val data = image?.get("data")?.jsonPrimitive?.contentOrNullSafe()
            ?: (image?.get("resource") as? JsonObject)?.get("blob")?.jsonPrimitive?.contentOrNullSafe()
            ?: throw BridgeException("screen_capture 没有返回图像（手机上可能未授予截屏权限）", "NO_IMAGE")
        val mime = image?.get("mimeType")?.jsonPrimitive?.contentOrNullSafe() ?: "image/jpeg"
        return ScreenCapture(base64 = data, mimeType = mime, metaJson = textPart)
    }

    suspend fun filesRead(path: String): String? {
        val r = callTool("files_read", buildJsonObject {
            put("path", path)
            put("encoding", "utf-8")
        }, timeoutMs = 12_000)
        val o = runCatching { json.parseToJsonElement(r.text).jsonObject }.getOrNull()
        return o?.get("content")?.jsonPrimitive?.contentOrNullSafe() ?: r.text
    }

    suspend fun stopAllScripts(): String = callTool("script_stop_all").text

    /** 直接启动已安装应用（包名来自 app_list，不需要用户输入）。 */
    suspend fun launchApp(pkg: String): String =
        callTool("app_launch", buildJsonObject { put("packageName", pkg) }, timeoutMs = 20_000).text

    /** 列出已安装应用（包名 + 应用名），用于把包名自动填给一键生成，避免手输。 */
    suspend fun listApps(): List<Pair<String, String>> {
        val r = callTool("app_list", buildJsonObject { }, timeoutMs = 20_000)
        val root = runCatching { json.parseToJsonElement(r.text).jsonObject }.getOrNull() ?: return emptyList()
        val arr = root["apps"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val pkg = (o["packageName"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (pkg.isEmpty()) null else pkg to (o["appName"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        }
    }

    suspend fun shellExec(cmd: String): String = callTool("shell_exec", buildJsonObject { put("cmd", cmd) }).text
}

/**
 * Maps device-side tool errors to actionable failures. Measured on AutoJs6 6.8.0:
 *  - `PAIRING_REQUIRED: confirm ... within 59 s` — the first tool call after connecting asks the
 *    user to accept a prompt on the phone; retrying after acceptance just works.
 *  - `RATE_LIMITED` — `screen_capture` is limited to 30 calls/minute.
 *  - `INVALID_ARGUMENTS` — e.g. `files_read` only accepts `encoding: "utf-8"` (not `utf8`).
 *  - `NODE_REF_STALE` — the UI changed since the dump; re-read the tree instead of replaying.
 *  - `HOST_ERROR` — usually a missing AutoJs6 working directory (create 「脚本」 on the phone).
 */
private fun classifyToolError(tool: String, text: String): BridgeException {
    val retry = Regex("""retryAfterMs\s*[":=]+\s*(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    return when {
        text.contains("PAIRING_REQUIRED") -> BridgeException(
            "手机等待配对确认：请在这台手机上点「允许」（约 59 秒内），然后重试。原始信息：${text.take(200)}",
            "PAIRING_REQUIRED"
        )
        text.contains("RATE_LIMITED") -> BridgeException(
            "截屏被限流（设备侧 30 次/分钟）" + if (retry > 0) "，约 ${(retry + 999) / 1000} 秒后可重试" else "",
            "RATE_LIMITED",
            retry
        )
        text.contains("INVALID_ARGUMENTS") -> BridgeException(
            "$tool 参数被拒：${text.take(200)}（例如 files_read 的 encoding 只能传 utf-8）",
            "INVALID_ARGUMENTS"
        )
        text.contains("NODE_REF_STALE") -> BridgeException(
            "节点引用已失效，界面已刷新：重新读取界面树后再操作。",
            "NODE_REF_STALE"
        )
        text.contains("HOST_ERROR") -> BridgeException(
            "$tool 执行失败：${text.take(200)}（常见于 AutoJs6 工作目录不存在，可在手机上新建「脚本」文件夹）",
            "HOST_ERROR"
        )
        else -> BridgeException(text.ifBlank { "$tool 返回错误" }, "TOOL_ERROR")
    }
}

private fun JsonPrimitive.contentOrNullSafe(): String? = contentOrNull

/** Builds a selector object from a by/value pair, mirroring the picker's output. */
fun selectorJson(by: String, value: String): JsonObject = buildJsonObject { put(by, value) }

/** Reads the node array out of a `ui_find` / `ui_dump` payload. */
fun parseNodes(nodesJson: String): List<JsonObject> {
    val parsed = runCatching { Json.parseToJsonElement(nodesJson) }.getOrNull() ?: return emptyList()
    return (parsed as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
}

private val JsonElement.doubleValue: Double? get() = (this as? JsonPrimitive)?.doubleOrNull

/**
 * Incremental SSE byte buffer.
 *
 * Socket reads split wherever the kernel feels like it, and decoding each chunk on its own
 * corrupts any multi-byte character that straddles the boundary: a 3-byte Chinese character cut
 * in half became two U+FFFD, so `ui_dump` labels (and every selector synthesised from them) came
 * back as garbage. Bytes are therefore buffered and the *whole* buffer is decoded when checking
 * for a payload, so a character split across two reads is intact once its tail arrives.
 */
class SseAccumulator(initialCapacity: Int = 8 * 1024) {
    private var data = ByteArray(initialCapacity)
    private var size = 0

    /** Appends [length] bytes from [chunk]; returns the first complete payload, if any. */
    fun feed(chunk: ByteArray, length: Int = chunk.size): String? {
        val need = size + length
        if (need > MAX_BYTES) throw BridgeException("SSE 响应超过 ${MAX_BYTES / 1024 / 1024}MB 上限，已中止", "RESPONSE_TOO_LARGE")
        if (need > data.size) data = data.copyOf(maxOf(data.size * 2, need))
        val oldSize = size
        chunk.copyInto(data, size, 0, length)
        size = need
        // L2: only re-scan when a frame boundary may have appeared in the new bytes (with 2-byte overlap for split `\n\r` + `\n` frames). Avoids O(n^2) on screenshot-sized base64 streams.
        val scanFrom = (oldSize - 2).coerceAtLeast(0)
        var boundary = false
        for (i in scanFrom until size - 1) {
            val b = data[i]
            if (b == LF && (data[i + 1] == LF || (data[i + 1] == CR && i + 2 < size && data[i + 2] == LF))) {
                boundary = true; break
            }
        }
        return if (boundary) SseFrames.extractPayload(text()) else null
    }

    fun text(): String = data.decodeToString(0, size)

    companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        private const val LF: Byte = 10
        private const val CR: Byte = 13
    }
}

/**
 * SSE framing helpers, kept separate so they can be unit-tested without a socket.
 *
 * Measured against AutoJs6 6.8.0: the whole reply arrives in one chunk that begins with a `:`
 * comment frame, e.g. `":\\n\\nid: 8\\nevent: message\\ndata: {…}`.
 * Any parser that stops at the first blank line finds nothing.
 */
object SseFrames {

    /** Line separator inside an SSE block. */
    private const val LF = '\n'

    /** Returns the first complete JSON payload carried by `data:` lines, or null. */
    fun extractPayload(text: String): String? {
        if (text.isBlank()) return null
        val data = StringBuilder()
        for (rawLine in text.split(LF)) {
            val line = rawLine.trimEnd('\r')
            when {
                line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
                // A blank line closes a frame; comments (`:`) and other fields are skipped.
                line.isEmpty() && data.isNotEmpty() -> {
                    val candidate = data.toString()
                    if (isBalanced(candidate)) return candidate
                    data.clear()
                }
            }
        }
        // The stream may end mid-frame; still accept what was collected.
        val tail = data.toString()
        return if (tail.isNotEmpty() && isBalanced(tail)) tail else null
    }

    /** True when [text] is one complete JSON object (brace depth returns to zero outside strings). */
    fun isBalanced(text: String): Boolean {
        val t = text.trim()
        if (!t.startsWith("{")) return false
        var depth = 0
        var inString = false
        var escaped = false
        for (ch in t) {
            when {
                escaped -> escaped = false
                ch == '\\' && inString -> escaped = true
                ch == '"' -> inString = !inString
                inString -> Unit
                ch == '{' -> depth++
                ch == '}' -> {
                    depth--
                    if (depth == 0) return true
                }
            }
        }
        return false
    }
}