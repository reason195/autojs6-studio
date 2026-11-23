package cn.dumate.a6studio.bridge

import cn.dumate.a6studio.security.Redaction
import cn.dumate.a6studio.security.RedactionReport
import cn.dumate.a6studio.security.UrlSecurity
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * ProxyPin 抓包 MCP 桥接客户端。
 * 所有抓取到的流量在返回给 UI 或大模型之前，均强制经过 [Redaction.redactTrafficPayload] 脱敏，
 * 且连接地址经 [UrlSecurity.validateEndpointUrl] 校验（阻断云元数据 SSRF）。
 */
class ProxyPinBridge(http: HttpClient) {

    val mcp = McpClient(http)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun configure(rawUrl: String, token: String = "", allowLocal: Boolean = true) {
        val check = UrlSecurity.validateEndpointUrl(rawUrl, allowLocal = allowLocal)
        if (!check.valid || check.normalizedUrl == null) {
            throw BridgeException(check.reason ?: "ProxyPin MCP 地址无效", "INVALID_URL")
        }
        mcp.setUrl(check.normalizedUrl)
        mcp.setAuth(token)
    }

    suspend fun listTools(): JsonElement = mcp.listTools()

    /**
     * 调用 ProxyPin MCP 工具拉取抓包流量并自动脱敏。
     * @param toolName 默认尝试常见的 ProxyPin MCP 工具名，如 `get_flows` / `list_requests`。
     */
    suspend fun fetchRedactedTraffic(
        toolName: String = "get_flows",
        args: JsonObject = buildJsonObject { put("limit", 20) }
    ): RedactionReport {
        val result = mcp.callTool(toolName, args)
        val parsed = runCatching {
            json.parseToJsonElement(result.text)
        }.getOrElse {
            buildJsonObject { put("rawText", JsonPrimitive(result.text)) }
        }
        return Redaction.redactTrafficPayload(parsed)
    }
}
