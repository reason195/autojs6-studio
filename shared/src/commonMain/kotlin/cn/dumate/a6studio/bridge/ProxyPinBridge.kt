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
 * ProxyPin 抓包 MCP 桥接客户端（G-1 / G-2 / G-5 修复版）。
 * 所有抓取到的流量在返回给 UI 或大模型之前，均强制经过 [Redaction.redactTrafficPayload] 脱敏，
 * 且连接地址在真正发出请求前经 [UrlSecurity.validateEndpointUrlResolved] 进行 DNS 解析后 SSRF 校验。
 */
class ProxyPinBridge(
    http: HttpClient,
    autojsClient: McpClient? = null
) {
    val mcp = McpClient(http, label = "ProxyPin")
    val gateway = DualMcpGateway(
        http = http,
        // 不能回落到 ProxyPin 的 mcp：AUTOJS6 调用会对它 setUrl，把 ProxyPin 的地址和会话换掉
        autojsClient = autojsClient ?: McpClient(http),
        proxypinClient = mcp
    )
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private var configuredUrl: String = ""
    private var configuredToken: String = ""
    private var configuredAllowLocal: Boolean = true

    fun configure(rawUrl: String, token: String = "", allowLocal: Boolean = true) {
        val check = UrlSecurity.validateEndpointUrl(rawUrl, allowLocal = allowLocal)
        if (!check.valid || check.normalizedUrl == null) {
            throw BridgeException(check.reason ?: "ProxyPin MCP 地址无效", "INVALID_URL")
        }
        configuredUrl = check.normalizedUrl
        configuredToken = token
        configuredAllowLocal = allowLocal
        if (check.normalizedUrl != mcp.currentUrl) {
            mcp.setUrl(check.normalizedUrl)
        }
        val desiredHeader = McpClient.buildAuthorizationHeader(token)
        if (desiredHeader != mcp.currentAuthHeader()) {
            mcp.setAuth(token)
        }
    }

    /** G-1：在发出任何 HTTP 请求前执行 DNS 解析后 SSRF 校验。 */
    private suspend fun ensureResolvedSafe() {
        val target = configuredUrl.ifBlank { mcp.currentUrl }
        val check = UrlSecurity.validateEndpointUrlResolved(target, allowLocal = configuredAllowLocal)
        if (!check.valid || check.normalizedUrl == null) {
            throw BridgeException(check.reason ?: "ProxyPin MCP 地址被安全策略拦截", "INVALID_URL")
        }
    }

    suspend fun listTools(timeoutMs: Long = 15_000): JsonElement {
        ensureResolvedSafe()
        return mcp.listTools(timeoutMs)
    }

    suspend fun listHttpFlows(
        domain: String = "",
        method: String = "",
        statusCode: Int? = null,
        limit: Int = 20,
        transport: McpTransport = McpTransport.HTTP_JSONRPC,
        devMode: Boolean = false
    ): McpGatewayResponse = gateway.call(
        CallMcpGatewayOptions(
            serverId = McpServerId.PROXYPIN,
            transport = transport,
            endpointUrl = configuredUrl,
            method = "tools/call",
            toolName = "list_http_flows",
            args = buildJsonObject {
                if (domain.isNotBlank()) put("domain", domain.trim())
                if (method.isNotBlank()) put("method", method.trim())
                if (statusCode != null) put("statusCode", statusCode)
                put("limit", limit)
            },
            allowLocal = configuredAllowLocal,
            authToken = configuredToken,
            devMode = devMode
        )
    )

    suspend fun getFlowDetail(
        flowId: String,
        transport: McpTransport = McpTransport.HTTP_JSONRPC,
        devMode: Boolean = false
    ): McpGatewayResponse = gateway.call(
        CallMcpGatewayOptions(
            serverId = McpServerId.PROXYPIN,
            transport = transport,
            endpointUrl = configuredUrl,
            method = "tools/call",
            toolName = "get_flow_detail",
            args = buildJsonObject { put("flowId", flowId) },
            allowLocal = configuredAllowLocal,
            authToken = configuredToken,
            devMode = devMode
        )
    )

    suspend fun analyzeEndpoints(
        domain: String = "",
        transport: McpTransport = McpTransport.HTTP_JSONRPC,
        devMode: Boolean = false
    ): McpGatewayResponse = gateway.call(
        CallMcpGatewayOptions(
            serverId = McpServerId.PROXYPIN,
            transport = transport,
            endpointUrl = configuredUrl,
            method = "tools/call",
            toolName = "analyze_endpoints",
            args = buildJsonObject { if (domain.isNotBlank()) put("domain", domain.trim()) },
            allowLocal = configuredAllowLocal,
            authToken = configuredToken,
            devMode = devMode
        )
    )

    /**
     * 调用 ProxyPin MCP 工具拉取抓包流量并自动脱敏。
     * G-1：HTTP 路径先走 [ensureResolvedSafe]；
     * G-5：仅当 `list_http_flows` 返回「工具不存在」时才回退到 `get_flows`。
     */
    suspend fun fetchRedactedTraffic(
        toolName: String = "list_http_flows",
        args: JsonObject = buildJsonObject { put("limit", 20) },
        transport: McpTransport = McpTransport.HTTP_JSONRPC,
        devMode: Boolean = false
    ): RedactionReport {
        if (transport == McpTransport.LOCAL_BRIDGE) {
            val resp = gateway.call(
                CallMcpGatewayOptions(
                    serverId = McpServerId.PROXYPIN,
                    transport = McpTransport.LOCAL_BRIDGE,
                    method = "tools/call",
                    toolName = toolName,
                    args = args,
                    devMode = devMode
                )
            )
            return resp.redactionReport ?: Redaction.redactTrafficPayload(resp.data)
        }
        ensureResolvedSafe()
        val result = try {
            mcp.callTool(toolName, args)
        } catch (e: BridgeException) {
            if (toolName == "list_http_flows" && DualMcpGateway.isToolNotFoundError(e)) {
                mcp.callTool("get_flows", args)
            } else {
                throw e
            }
        }
        val parsed = runCatching {
            json.parseToJsonElement(result.text)
        }.getOrElse {
            buildJsonObject { put("rawText", JsonPrimitive(result.text)) }
        }
        return Redaction.redactTrafficPayload(parsed)
    }
}
