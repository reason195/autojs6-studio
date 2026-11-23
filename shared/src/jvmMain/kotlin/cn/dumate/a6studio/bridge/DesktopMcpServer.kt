package cn.dumate.a6studio.bridge

import cn.dumate.a6studio.ai.BlockPatch
import cn.dumate.a6studio.ai.BlockPatchOp
import cn.dumate.a6studio.ai.PatchGuardResult
import cn.dumate.a6studio.catalog.CodeGen
import cn.dumate.a6studio.diagnose.Diagnose
import cn.dumate.a6studio.model.Project
import cn.dumate.a6studio.security.Redaction
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * 桌面端本地 MCP over HTTP 服务端（JSON-RPC 2.0，仅绑定 127.0.0.1）。
 *
 * 安全策略（N-S2 / N-M7）：
 * - 启动时生成 32 字节随机令牌（[authToken]），要求 `Authorization: Bearer <token>`，令牌仅驻留内存；
 * - 强制校验 `Host` 为 `127.0.0.1:<port>` 或 `localhost:<port>`；
 * - 任何携带 `Origin` 头的请求一律返回 403（阻断浏览器跨站请求）；
 * - 强制要求 `Content-Type: application/json`（否则返回 415）；
 * - 请求体上限 1 MB（`readNBytes`，超出返回 413）；
 * - 使用固定大小线程池 `newFixedThreadPool(2)`，补丁修改通过 [mutateProjectSync] 原子执行。
 */
class DesktopMcpServer(
    private val getProject: () -> Project,
    private val setProject: (Project) -> Unit,
    port: Int = 0,
    private val mutateProjectSync: (((Project) -> PatchGuardResult) -> PatchGuardResult)? = null
) {
    companion object {
        const val MAX_BODY_BYTES = 1_048_576
        private fun generateToken(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }

    val authToken: String = generateToken()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0)
    private val executor = Executors.newFixedThreadPool(2)

    val boundPort: Int get() = server.address.port
    val endpointUrl: String get() = "http://127.0.0.1:$boundPort/mcp"

    init {
        server.executor = executor
        server.createContext("/mcp") { exchange ->
            try {
                handleExchange(exchange)
            } catch (e: Throwable) {
                runCatching {
                    val errBytes = rpcError(JsonNull, -32603, "Internal server error").toByteArray(Charsets.UTF_8)
                    exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
                    exchange.sendResponseHeaders(500, errBytes.size.toLong())
                    exchange.responseBody.use { it.write(errBytes) }
                }
            } finally {
                runCatching { exchange.close() }
            }
        }
    }

    private fun handleExchange(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            sendPlain(exchange, 405, "Method Not Allowed")
            return
        }

        // 1. 拒绝任何携带 Origin 头的请求（阻断网页 CSRF / DNS Rebinding）
        val origin = exchange.requestHeaders.getFirst("Origin")
        if (!origin.isNullOrBlank()) {
            sendPlain(exchange, 403, "Forbidden: Origin header is not allowed")
            return
        }

        // 2. 校验 Host 头
        val host = exchange.requestHeaders.getFirst("Host")?.trim()?.lowercase().orEmpty()
        val allowedHosts = setOf("127.0.0.1:$boundPort", "localhost:$boundPort")
        if (host !in allowedHosts) {
            sendPlain(exchange, 403, "Forbidden: Invalid Host header")
            return
        }

        // 3. 校验 Authorization: Bearer <token>
        val auth = exchange.requestHeaders.getFirst("Authorization")?.trim().orEmpty()
        val expectedAuth = "Bearer $authToken"
        val authOk = MessageDigest.isEqual(
            auth.toByteArray(Charsets.UTF_8),
            expectedAuth.toByteArray(Charsets.UTF_8)
        )
        if (!authOk) {
            sendPlain(exchange, 401, "Unauthorized")
            return
        }

        // 4. 校验 Content-Type 必须为 application/json
        val ct = exchange.requestHeaders.getFirst("Content-Type")?.trim()?.lowercase().orEmpty()
        if (!ct.startsWith("application/json")) {
            sendPlain(exchange, 415, "Unsupported Media Type: application/json required")
            return
        }

        // 5. 限制请求体大小不超过 1 MB
        val rawBytes = exchange.requestBody.use { it.readNBytes(MAX_BODY_BYTES + 1) }
        if (rawBytes.size > MAX_BODY_BYTES) {
            sendPlain(exchange, 413, "Payload Too Large")
            return
        }

        val body = rawBytes.toString(Charsets.UTF_8)
        val respText = handleJsonRpc(body)
        if (respText.isEmpty()) {
            // JSON-RPC 通知：202 No Content（MCP spec: notifications 不期待响应）
            exchange.sendResponseHeaders(202, -1)
            return
        }
        val bytes = respText.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun sendPlain(exchange: HttpExchange, status: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    fun start() = server.start()

    fun stop() {
        runCatching { server.stop(0) }
        runCatching { executor.shutdownNow() }
    }

    internal fun handleJsonRpc(rawBody: String): String {
        val req = runCatching { json.parseToJsonElement(rawBody) as? JsonObject }.getOrNull()
            ?: return rpcError(JsonNull, -32700, "Parse error")
        val id = req["id"] ?: JsonNull
        val methodEl = req["method"] as? JsonPrimitive
        if (methodEl == null || !methodEl.isString) {
            return rpcError(id, -32600, "Invalid Request: method must be a string")
        }
        val method = methodEl.contentOrNull.orEmpty()
        val paramsEl = req["params"]
        if (paramsEl != null && paramsEl !is JsonObject) {
            return rpcError(id, -32602, "Invalid params: object expected")
        }
        val params = paramsEl as? JsonObject ?: JsonObject(emptyMap())

        return try {
            when (method) {
                "initialize" -> rpcResult(id, buildJsonObject {
                    put("protocolVersion", "2024-11-05")
                    put("serverInfo", buildJsonObject {
                        put("name", "autojs6-studio-mcp")
                        put("version", "1.0.0")
                    })
                    put("capabilities", buildJsonObject {
                        put("tools", buildJsonObject { })
                    })
                })
                "notifications/initialized" -> {
                    // JSON-RPC 通知不期待响应：用 202 返回空 body（MCP 规范）
                    // handleExchange 检查返回空字符串时会走 202 分支
                    return ""
                }
                "tools/list" -> rpcResult(id, buildJsonObject {
                    put("tools", buildJsonArray {
                        add(toolDef("studio_get_project", "读取当前工程 JSON 结构"))
                        add(toolDef("studio_diagnose", "对当前工程执行静态诊断"))
                        add(toolDef("studio_generate_code", "生成 AutoJs6 JS 代码并执行密钥扫描"))
                        add(toolDef("studio_apply_patch", "通过受控护栏补丁修改当前工程"))
                    })
                })
                "tools/call" -> {
                    val nameEl = params["name"] as? JsonPrimitive
                    if (nameEl == null || !nameEl.isString) {
                        return rpcError(id, -32602, "Invalid params: tool name must be a string")
                    }
                    val name = nameEl.contentOrNull.orEmpty()
                    val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
                    handleToolCall(id, name, args)
                }
                else -> rpcError(id, -32601, "Method not found: $method")
            }
        } catch (e: Throwable) {
            rpcError(id, -32603, "Internal error: ${e.message ?: e::class.simpleName}")
        }
    }

    private fun handleToolCall(id: JsonElement, name: String, args: JsonObject): String {
        return when (name) {
            "studio_get_project" -> {
                // N-L11：rawJs 积木里可能含有用户粘贴的凭据；脱敏后再回传。
                // 不能对整段 JSON 文本跑正则：header_line 是按行匹配到行尾，单行 JSON 会被从 "Cookie:" 一直吃到结尾；
                // redactTextPatterns 还会把 >64KB 的输入截断，大工程返回的就不是合法 JSON。
                // 所以逐个字符串值脱敏，结构保持不变。
                val tree = json.encodeToJsonElement(Project.serializer(), getProject())
                toolOk(id, redactStrings(tree).toString())
            }
            "studio_diagnose" -> {
                val issues = Diagnose.scan(getProject())
                val arr = buildJsonArray {
                    issues.forEach { iss ->
                        add(buildJsonObject {
                            put("level", iss.level.name)
                            put("title", iss.title)
                            put("hint", iss.hint)
                        })
                    }
                }
                toolOk(id, arr.toString())
            }
            "studio_generate_code" -> {
                val code = CodeGen.code(getProject())
                val secrets = Redaction.assertNoPlaintextSecrets(code)
                if (secrets.isNotEmpty()) {
                    toolErr(id, "生成代码命中明文凭据风险：${secrets.joinToString(", ")}")
                } else {
                    toolOk(id, code)
                }
            }
            "studio_apply_patch" -> {
                val opsEl = args["ops"] as? JsonArray
                    ?: return toolErr(id, "缺少 ops 数组参数")
                val ops = runCatching {
                    opsEl.map { json.decodeFromJsonElement(BlockPatchOp.serializer(), it) }
                }.getOrElse {
                    return toolErr(id, "ops 解析失败：${it.message}")
                }
                val runner = mutateProjectSync ?: { transform ->
                    val r = transform(getProject())
                    if (r.accepted) setProject(r.project)
                    r
                }
                val res = runner { current -> BlockPatch.guardAndApply(current, ops) }
                if (!res.accepted) {
                    toolErr(id, "护栏拒绝补丁：\n" + res.errors.joinToString("\n"))
                } else {
                    toolOk(id, "补丁已通过护栏校验并应用，当前工程：${res.project.name}")
                }
            }
            else -> toolErr(id, "未知工具：$name")
        }
    }

    private fun redactStrings(el: JsonElement): JsonElement = when (el) {
        is JsonObject -> JsonObject(el.mapValues { (_, v) -> redactStrings(v) })
        is JsonArray -> JsonArray(el.map { redactStrings(it) })
        is JsonPrimitive -> if (el.isString) {
            JsonPrimitive(Redaction.redactTextPatterns(el.content, maxStringLength = Int.MAX_VALUE).text)
        } else el
        else -> el
    }

    private fun toolDef(name: String, description: String): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        put("inputSchema", buildJsonObject {
            put("type", "object")
            if (name == "studio_apply_patch") {
                put("properties", buildJsonObject {
                    put("ops", buildJsonObject {
                        put("type", "array")
                        put("description", "补丁操作数组，每项用 type 字段区分（\"add\"/\"update\"/\"remove\"/\"rename\"）")
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("required", buildJsonArray { add(JsonPrimitive("type")) })
                            put("properties", buildJsonObject {
                                put("type", buildJsonObject {
                                    put("type", "string")
                                    put("enum", buildJsonArray {
                                        add(JsonPrimitive("add")); add(JsonPrimitive("update"))
                                        add(JsonPrimitive("remove")); add(JsonPrimitive("rename"))
                                    })
                                })
                            })
                        })
                    })
                })
                put("required", buildJsonArray { add(JsonPrimitive("ops")) })
            }
        })
    }

    private fun toolOk(id: JsonElement, text: String): String = rpcResult(id, buildJsonObject {
        put("isError", false)
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", text)
            })
        })
    })

    private fun toolErr(id: JsonElement, text: String): String = rpcResult(id, buildJsonObject {
        put("isError", true)
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", text)
            })
        })
    })

    private fun rpcResult(id: JsonElement, result: JsonObject): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }.toString()

    private fun rpcError(id: JsonElement, code: Int, message: String): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }.toString()
}
