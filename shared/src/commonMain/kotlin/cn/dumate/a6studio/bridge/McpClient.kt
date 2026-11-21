package cn.dumate.a6studio.bridge

import cn.dumate.a6studio.platform.nowMillis
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
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
    private val http: HttpClient
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mutex = Mutex()

    private var url: String = ""
    private var sessionId: String = ""
    private var ready: Boolean = false
    private var seq = 0
    private var authHeader: String? = null

    private val authByUrl = mutableMapOf<String, String>()

    var protocolVersion: String = "2024-11-05"

    val currentUrl: String get() = url
    val isReady: Boolean get() = ready

    fun headersSnapshot(): Map<String, String> = authHeader?.let { mapOf("Authorization" to it) } ?: emptyMap()

    fun setUrl(newUrl: String) {
        url = newUrl.trim().trimEnd('/')
        ready = false
        sessionId = ""
        authHeader = authByUrl[url]
    }

    fun setAuth(token: String) {
        val t = token.trim()
        authHeader = when {
            t.isEmpty() -> null
            t.startsWith("Bearer ", ignoreCase = true) -> t
            else -> "Bearer $t"
        }
        ready = false
        sessionId = ""
    }

    fun setAuthFor(addr: String, token: String) {
        val key = addr.trim()
        if (key.isEmpty()) return
        val t = token.trim()
        if (t.isEmpty()) {
            authByUrl.remove(key)
            if (url == key) authHeader = null
        } else {
            val v = if (t.startsWith("Bearer ", ignoreCase = true)) t else "Bearer $t"
            authByUrl[key] = v
            if (url == key) authHeader = v
        }
    }

    fun authMap(): Map<String, String> = authByUrl.toMap()

    fun setAuthMap(map: Map<String, String>) {
        authByUrl.clear()
        authByUrl.putAll(map)
        authHeader = authByUrl[url]
    }

    fun resetPendingState() {
        ready = false
        sessionId = ""
    }

    private suspend fun post(payload: JsonObject, extraHeaders: Map<String, String> = emptyMap(), timeoutMs: Long = 15_000): JsonElement? {
        val target = url.ifBlank { throw BridgeException("未填写桥接地址", "NO_URL") }
        val headers = buildMap {
            put("Accept", "application/json, text/event-stream")
            put("Content-Type", "application/json")
            authHeader?.let { put("Authorization", it) }
            if (sessionId.isNotEmpty()) put("mcp-session-id", sessionId)
            putAll(extraHeaders)
        }

        val client = http.config {
            install(HttpTimeout) {
                requestTimeoutMillis = timeoutMs
                connectTimeoutMillis = minOf(timeoutMs, 8_000)
                socketTimeoutMillis = timeoutMs
            }
        }

        val response: HttpResponse = try {
            client.post(target) {
                contentType(ContentType.Application.Json)
                headers.forEach { (k, v) -> header(k, v) }
                setBody(payload.toString())
            }
        } catch (e: io.ktor.client.plugins.HttpRequestTimeoutException) {
            throw BridgeException(timeoutMessage(target, timeoutMs), "TIMEOUT")
        } catch (e: Exception) {
            throw BridgeException(networkMessage(target, e.message ?: e.toString()), "NETWORK")
        }

        if (response.status.value == 404 || response.status.value == 405) {
            throw BridgeException("HTTP ${response.status.value}：地址不是 MCP 端点，或桥接未启动", "BAD_ENDPOINT")
        }

        response.headers["mcp-session-id"]?.let { sessionId = it }

        // 401 is a token problem, not a network one: say so instead of "no response".
        if (response.status.value == 401) {
            throw BridgeException(
                "手机拒绝了令牌（401）。请在手机的 AutoJs6 → 插件 → MCP Server → 设置里复制当前的" +
                    "「令牌 / Token」，粘贴到这里的令牌框；令牌在重新配对后会变，旧的一定会被拒。",
                "UNAUTHORIZED"
            )
        }

        val contentType = response.headers["content-type"] ?: ""
        val text = try {
            if (contentType.contains("text/event-stream")) {
                // MCP answers over a *keep-alive* SSE stream, so reading until EOF blocks until the
                // socket timeout (measured: raw socket got 200 in <1s while this timed out at 15s).
                // Read event-by-event and stop as soon as one carries a JSON-RPC payload.
                readFirstSsePayload(response, target, timeoutMs)
            } else {
                response.bodyAsText()
            }
        } catch (e: BridgeException) {
            throw e
        } catch (e: Exception) {
            throw BridgeException(timeoutMessage(target, timeoutMs), "TIMEOUT")
        }
        if (text.isBlank()) {
            // Keep the raw SSE for diagnosis: "no response" hides whether the reply was malformed.
            throw BridgeException(
                "手机返回了空的响应体（HTTP ${response.status.value}，Content-Type: $contentType）。" +
                    "若持续出现，请在手机上关闭再打开 MCP Server 插件。",
                "EMPTY_BODY"
            )
        }

        val parsed = if (contentType.contains("text/event-stream")) {
            // The payload may arrive with a leading `:` comment frame; SseFrames handles framing.
            val payload = SseFrames.extractPayload(text) ?: text
            parseJsonOrThrow(payload)
        } else parseJsonOrThrow(text)
        if (parsed == null) {
            throw BridgeException(
                "无法解析 MCP 响应（原文前 160 字符：${text.take(160)}）",
                "BAD_RESPONSE"
            )
        }
        return parsed
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
        val raw = StringBuilder()
        val buffer = ByteArray(4096)
        try {
            withTimeout(timeoutMs) {
                while (true) {
                    val n = channel.readAvailable(buffer, 0, buffer.size)
                    if (n <= 0) break
                    raw.append(String(buffer, 0, n, Charsets.UTF_8))
                    SseFrames.extractPayload(raw.toString())?.let { return@withTimeout it }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw BridgeException(timeoutMessage(target, timeoutMs), "TIMEOUT")
        } finally {
            runCatching { channel.cancel(null) }
        }
        return SseFrames.extractPayload(raw.toString()) ?: ""
    }

    private fun parseJsonOrThrow(text: String): JsonElement? = try {
        json.parseToJsonElement(text)
    } catch (e: Exception) {
        throw BridgeException("返回内容不是 JSON：${text.take(120)}", "BAD_RESPONSE")
    }

    private fun parseSse(text: String): JsonElement? {
        var last: JsonElement? = null
        text.lineSequence().forEach { line ->
            if (line.startsWith("data:")) {
                val data = line.removePrefix("data:").trim()
                if (data.isNotEmpty() && data != "[DONE]") {
                    runCatching { json.parseToJsonElement(data) }.getOrNull()?.let { last = it }
                }
            }
        }
        return last
    }

    private fun timeoutMessage(target: String, ms: Long): String {
        val secs = (ms / 1000).toInt()
        return "手机 MCP 无响应：${secs}s 内没有回包（$target）。" +
            "端口在听却不应答，通常是 AutoJs6 主线程被占满（正在跑的录制脚本、无障碍查询卡住）。" +
            "处理：在手机上按音量下键停掉录制脚本，或从 AutoJs6 停止全部脚本，再重启 MCP Server 后重连。"
    }

    private fun networkMessage(target: String, detail: String): String =
        "连不上 $target：$detail。检查：①手机 AutoJs6 的 MCP Server 是否在运行（屏幕别锁、加到后台白名单）；" +
            "②IP 是否变了；③网络是否可达（同一 Wi-Fi / 桥接代理）。"

    private suspend fun rpc(method: String, params: JsonObject?, timeoutMs: Long = 15_000): JsonElement {
        val id = ++seq
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params ?: buildJsonObject { })
        }
        val response = post(payload, timeoutMs = timeoutMs)
            ?: throw BridgeException("空响应", "EMPTY_RESPONSE")
        val obj = response.jsonObject
        (obj["error"] as? JsonObject)?.let { err ->
            throw BridgeException(err["message"]?.jsonPrimitive?.content ?: err.toString(), "RPC_ERROR")
        }
        return obj["result"] ?: JsonPrimitive("")
    }

    suspend fun initialize(): JsonElement = mutex.withLock {
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", ++seq)
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
            "initialize 无响应：手机没有返回可解析的内容（url=$url，session=$sessionId）。" +
                "常见原因：MCP 插件的「允许局域网连接」被关掉，或插件刚重启还没就绪。",
            "EMPTY_RESPONSE"
        )
        val obj = res.jsonObject
        (obj["error"] as? JsonObject)?.let {
            throw BridgeException(it["message"]?.jsonPrimitive?.content ?: "initialize 失败", "RPC_ERROR")
        }
        runCatching {
            post(buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "notifications/initialized")
                put("params", buildJsonObject { })
            })
        }
        ready = true
        // `post` returns the whole JSON-RPC envelope ({"id":…,"result":…}), so unwrap `result`.
        obj["result"] ?: JsonPrimitive("")
    }

    private suspend fun ensureReady() {
        if (!ready) initialize()
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
    suspend fun deviceInfo(): DeviceInfo {
        val r = callTool("device_info")
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

    suspend fun uiDumpJson(maxNodes: Int = 400, maxDepth: Int = 32): JsonObject {
        val r = callTool("ui_dump", buildJsonObject {
            put("format", "json")
            put("maxNodes", maxNodes)
            put("maxDepth", maxDepth)
            put("visibleOnly", true)
        })
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

    suspend fun currentWindow(): WindowInfo {
        val r = callTool("ui_current_window")
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

private fun JsonPrimitive.contentOrNullSafe(): String? =
    if (this is JsonPrimitive && this.isString) content else content

/** Builds a selector object from a by/value pair, mirroring the picker's output. */
fun selectorJson(by: String, value: String): JsonObject = buildJsonObject { put(by, value) }

/** Reads the node array out of a `ui_find` / `ui_dump` payload. */
fun parseNodes(nodesJson: String): List<JsonObject> {
    val parsed = runCatching { Json.parseToJsonElement(nodesJson) }.getOrNull() ?: return emptyList()
    return (parsed as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
}

private val JsonElement.doubleValue: Double? get() = (this as? JsonPrimitive)?.doubleOrNull

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