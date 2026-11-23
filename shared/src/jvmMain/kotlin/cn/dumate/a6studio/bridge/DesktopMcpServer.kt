package cn.dumate.a6studio.bridge

import cn.dumate.a6studio.ai.BlockPatch
import cn.dumate.a6studio.ai.BlockPatchOp
import cn.dumate.a6studio.catalog.CodeGen
import cn.dumate.a6studio.diagnose.Diagnose
import cn.dumate.a6studio.model.Project
import cn.dumate.a6studio.security.Redaction
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * 桌面端本地 MCP over HTTP 服务端（JSON-RPC 2.0，仅绑定 127.0.0.1）。
 * 允许外部 AI IDE（Cursor / Claude / Antigravity）读取当前工程、执行静态诊断、
 * 生成代码以及通过 [BlockPatch.guardAndApply] 受控修改积木树。
 */
class DesktopMcpServer(
    private val getProject: () -> Project,
    private val setProject: (Project) -> Unit,
    port: Int = 0
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0)
    private val executor = Executors.newCachedThreadPool()

    val boundPort: Int get() = server.address.port
    val endpointUrl: String get() = "http://127.0.0.1:$boundPort/mcp"

    init {
        server.executor = executor
        server.createContext("/mcp") { exchange ->
            if (exchange.requestMethod.equals("POST", ignoreCase = true).not()) {
                val msg = "Method Not Allowed".toByteArray()
                exchange.sendResponseHeaders(405, msg.size.toLong())
                exchange.responseBody.use { it.write(msg) }
                return@createContext
            }
            val body = exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val respText = handleJsonRpc(body)
            val bytes = respText.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    fun start() = server.start()

    fun stop() {
        server.stop(0)
        executor.shutdownNow()
    }

    internal fun handleJsonRpc(rawBody: String): String {
        val req = runCatching { json.parseToJsonElement(rawBody).jsonObject }.getOrNull()
            ?: return rpcError(JsonNull, -32700, "Parse error")
        val id = req["id"] ?: JsonNull
        val method = req["method"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val params = req["params"] as? JsonObject ?: JsonObject(emptyMap())

        return when (method) {
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
            "notifications/initialized" -> rpcResult(id, buildJsonObject { })
            "tools/list" -> rpcResult(id, buildJsonObject {
                put("tools", buildJsonArray {
                    add(toolDef("studio_get_project", "读取当前工程 JSON 结构"))
                    add(toolDef("studio_diagnose", "对当前工程执行静态诊断"))
                    add(toolDef("studio_generate_code", "生成 AutoJs6 JS 代码并执行密钥扫描"))
                    add(toolDef("studio_apply_patch", "通过受控护栏补丁修改当前工程"))
                })
            })
            "tools/call" -> {
                val name = params["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
                handleToolCall(id, name, args)
            }
            else -> rpcError(id, -32601, "Method not found: $method")
        }
    }

    private fun handleToolCall(id: JsonElement, name: String, args: JsonObject): String {
        return when (name) {
            "studio_get_project" -> {
                val pJson = json.encodeToString(getProject())
                toolOk(id, pJson)
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
                val res = BlockPatch.guardAndApply(getProject(), ops)
                if (!res.accepted) {
                    toolErr(id, "护栏拒绝补丁：\n" + res.errors.joinToString("\n"))
                } else {
                    setProject(res.project)
                    toolOk(id, "补丁已通过护栏校验并应用，当前工程：${res.project.name}")
                }
            }
            else -> toolErr(id, "未知工具：$name")
        }
    }

    private fun toolDef(name: String, description: String): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        put("inputSchema", buildJsonObject { put("type", "object") })
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
