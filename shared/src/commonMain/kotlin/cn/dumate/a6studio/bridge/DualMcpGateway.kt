package cn.dumate.a6studio.bridge

import cn.dumate.a6studio.platform.nowMillis
import cn.dumate.a6studio.security.Redaction
import cn.dumate.a6studio.security.RedactionOptions
import cn.dumate.a6studio.security.RedactionReport
import cn.dumate.a6studio.security.RedactionStats
import cn.dumate.a6studio.security.UrlSecurity
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

enum class McpServerId(val id: String, val label: String) {
    AUTOJS6("autojs6", "AutoJs6 设备 MCP"),
    PROXYPIN("proxypin", "ProxyPin 抓包 MCP")
}

enum class McpTransport(val id: String, val label: String) {
    HTTP_JSONRPC("http-jsonrpc", "HTTP JSON-RPC（真机/实时服务）"),
    LOCAL_BRIDGE("local-bridge", "Local Bridge（本地合成/离线联调）")
}

enum class McpErrorKind(val id: String) {
    TRANSPORT_ERROR("transport_error"),
    PROTOCOL_ERROR("protocol_error"),
    TOOL_EXECUTION_ERROR("tool_execution_error"),
    TIMEOUT_ERROR("timeout_error"),
    SSRF_BLOCKED("ssrf_blocked")
}

data class McpGatewayResponse(
    val ok: Boolean,
    val degraded: Boolean,
    val serverId: McpServerId,
    val method: String,
    val latencyMs: Long,
    val errorKind: McpErrorKind? = null,
    val errorMessage: String? = null,
    val data: JsonElement = JsonNull,
    val redactionReport: RedactionReport? = null,
    val isSynthetic: Boolean = false
) {
    val redactionStats: RedactionStats? get() = redactionReport?.stats
}

data class CallMcpGatewayOptions(
    val serverId: McpServerId,
    val transport: McpTransport = McpTransport.HTTP_JSONRPC,
    val endpointUrl: String = "",
    val method: String = "tools/call",
    val toolName: String = "",
    val args: JsonObject = buildJsonObject { },
    val timeoutMs: Long = 10_000L,
    val optional: Boolean = false,
    val allowLocal: Boolean = true,
    val authToken: String = "",
    val devMode: Boolean = false
)

/**
 * 双 MCP JSON-RPC 2.0 统一网关（G-1~G-7 修复版）。
 *
 * - G-1：HTTP 路径强制调用 [UrlSecurity.validateEndpointUrlResolved] 进行 DNS 解析后 SSRF 校验；
 * - G-2：不重置已就绪且地址/令牌未变化的 [McpClient] 会话；
 * - G-3：[mapBridgeErrorCode] 完整对齐 [McpClient] 实际抛出的错误码；
 * - G-4/G-5：仅在「工具不存在」（[isToolNotFoundError]）时执行真机协议降级或工具名回退，且全程传递 `timeoutMs`；
 * - G-6：合成数据标记 `isSynthetic = true`，`__simulate*` 仅在 `devMode = true` 时生效；
 * - G-7：移除未脱敏的 `rawPreviewForAudit` 字段。
 */
class DualMcpGateway(
    http: HttpClient,
    val autojsClient: McpClient = McpClient(http, label = "手机 AutoJs6"),
    val proxypinClient: McpClient = McpClient(http, label = "ProxyPin")
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        val AUTOJS6_TOOLS: JsonArray = buildJsonArray {
            add(buildJsonObject {
                put("name", "get_device_state")
                put("description", "获取当前 Android 设备分辨率、前台包名、无障碍服务状态、当前 Activity 及可见动作按钮文本")
            })
            add(buildJsonObject {
                put("name", "capture_ui_tree")
                put("description", "采集当前页面的可点击控件树与 OCR 锚点候选区域")
            })
            add(buildJsonObject {
                put("name", "probe_ocr_region")
                put("description", "在指定归一化比例区域执行 OCR 试跑并返回识别文本与坐标")
            })
        }

        val PROXYPIN_TOOLS: JsonArray = buildJsonArray {
            add(buildJsonObject {
                put("name", "list_http_flows")
                put("description", "按域名、HTTP 方法、状态码或条数检索 ProxyPin 抓包会话列表")
            })
            add(buildJsonObject {
                put("name", "get_flow_detail")
                put("description", "读取指定抓包 ID 的完整请求头、响应头与 JSON 正文")
            })
            add(buildJsonObject {
                put("name", "analyze_endpoints")
                put("description", "聚合统计高频任务接口路径、状态码分布与提取字段建议")
            })
        }

        val SYNTHETIC_PROXYPIN_FLOWS: JsonArray = buildJsonArray {
            add(buildJsonObject {
                put("flowId", "flow_jr_001")
                put("method", "POST")
                put("url", "https://ms.jr.jd.com/gw/generic/hy/h5/m/queryTaskList?token=demo_q_tok_9812")
                put("domain", "ms.jr.jd.com")
                put("path", "/gw/generic/hy/h5/m/queryTaskList")
                put("statusCode", 200)
                put("durationMs", 142)
                put("requestHeaders", buildJsonObject {
                    put("Host", "ms.jr.jd.com")
                    put("Authorization", "Bearer demo-session-token-9f8e7d6c5b4a3210")
                    put("Cookie", "pt_key=demo_cookie_abc123; pt_pin=jd_demo_user;")
                    put("X-Api-Key", "demo-client-key-0042")
                    put("User-Agent", "JDJR-Android/6.8.20")
                })
                put("responseHeaders", buildJsonObject {
                    put("Content-Type", "application/json;charset=UTF-8")
                    put("Set-Cookie", "jr_sid=demo_sid_778899; HttpOnly")
                })
                put("responseBody", buildJsonObject {
                    put("resultCode", 0)
                    put("userPhone", "13800138000")
                    put("data", buildJsonObject {
                        put("pageTitle", "每日做任务领金币")
                        put("tasks", buildJsonArray {
                            add(buildJsonObject {
                                put("taskId", "t_signin_01")
                                put("title", "每日签到领京豆")
                                put("buttonText", "去签到")
                                put("status", "TODO")
                                put("browseSeconds", 0)
                            })
                            add(buildJsonObject {
                                put("taskId", "t_browse_02")
                                put("title", "浏览理财精选会场")
                                put("buttonText", "去浏览15秒")
                                put("status", "TODO")
                                put("browseSeconds", 15)
                            })
                            add(buildJsonObject {
                                put("taskId", "t_reward_03")
                                put("title", "阶段奖励待领取")
                                put("buttonText", "领取奖励")
                                put("status", "CLAIMABLE")
                                put("browseSeconds", 0)
                            })
                            add(buildJsonObject {
                                put("taskId", "t_done_04")
                                put("title", "新人礼包已发放")
                                put("buttonText", "已完成")
                                put("status", "DONE")
                                put("browseSeconds", 0)
                            })
                        })
                    })
                })
            })
            add(buildJsonObject {
                put("flowId", "flow_jr_002")
                put("method", "POST")
                put("url", "https://ms.jr.jd.com/gw/generic/hy/h5/m/doTaskReport")
                put("domain", "ms.jr.jd.com")
                put("path", "/gw/generic/hy/h5/m/doTaskReport")
                put("statusCode", 200)
                put("durationMs", 96)
                put("requestHeaders", buildJsonObject {
                    put("Host", "ms.jr.jd.com")
                    put("Authorization", "Bearer demo-session-token-9f8e7d6c5b4a3210")
                    put("Cookie", "pt_key=demo_cookie_abc123; pt_pin=jd_demo_user;")
                })
                put("responseHeaders", buildJsonObject {
                    put("Content-Type", "application/json")
                })
                put("responseBody", buildJsonObject {
                    put("resultCode", 0)
                    put("rewardAmount", 20)
                    put("nextActionHint", "返回任务列表刷新状态")
                    put("contactEmail", "qa-demo@example.com")
                })
            })
        }

        /**
         * G-3：将 [McpClient] 与 [ProxyPinBridge] 抛出的 [BridgeException.code] 映射为网关错误分类。
         */
        internal fun mapBridgeErrorCode(code: String): McpErrorKind = when (code) {
            "TIMEOUT" -> McpErrorKind.TIMEOUT_ERROR
            "INVALID_URL",
            "BLOCKED_URL" -> McpErrorKind.SSRF_BLOCKED
            "BAD_RESPONSE",
            "EMPTY_BODY",
            "EMPTY_RESPONSE",
            "RPC_ERROR",
            "INVALID_ARGUMENTS",
            "RESPONSE_TOO_LARGE",
            "INVALID_TOKEN" -> McpErrorKind.PROTOCOL_ERROR
            "TOOL_ERROR",
            "RATE_LIMITED",
            "PAIRING_REQUIRED",
            "NODE_REF_STALE",
            "HOST_ERROR",
            "NO_IMAGE",
            "UNAUTHORIZED" -> McpErrorKind.TOOL_EXECUTION_ERROR
            "NETWORK",
            "CLEARTEXT_BLOCKED",
            "NO_URL",
            "SESSION_EXPIRED",
            "BAD_ENDPOINT",
            "HTTP_ERROR" -> McpErrorKind.TRANSPORT_ERROR
            else -> McpErrorKind.TRANSPORT_ERROR
        }

        /**
         * G-4 / G-5：仅当服务端明确返回「工具不存在 / Method not found」时才判定为可降级。
         */
        internal fun isToolNotFoundError(e: BridgeException): Boolean {
            if (e.code != "RPC_ERROR" && e.code != "TOOL_ERROR") return false
            return isToolNotFoundText(e.message)
        }

        internal fun isToolNotFoundMessage(kind: McpErrorKind?, message: String?): Boolean {
            if (kind != McpErrorKind.PROTOCOL_ERROR && kind != McpErrorKind.TOOL_EXECUTION_ERROR) return false
            return isToolNotFoundText(message)
        }

        // AutoJs6 MCP 插件对未知工具的真实回包是 `Tool xxx not found`，故单独匹配该词序
        private val TOOL_NOT_FOUND = Regex(
            "-32601|method not found|tool not found|unknown tool|unknown method|未知工具|工具不存在|no such tool|tool\\s+[a-z0-9_]+\\s+not\\s+found"
        )

        private fun isToolNotFoundText(raw: String?): Boolean {
            val msg = raw.orEmpty().lowercase()
            if (msg.isEmpty()) return false
            // 只认「方法/工具不存在」。不能用裸的 "not found"：TOOL_ERROR 里的
            // "node not found" / "flow not found" / "element not found" 是业务失败，不该触发降级重试。
            return TOOL_NOT_FOUND.containsMatchIn(msg)
        }

        fun finalizeMcpRpcResponse(
            serverId: McpServerId,
            method: String,
            latencyMs: Long,
            optional: Boolean,
            rawPayload: JsonElement,
            isSynthetic: Boolean = false
        ): McpGatewayResponse {
            // 之前用默认脱敏选项（maxArrayLength=20）会把 tools/list 的 45 个工具截成 20 个，
            // files_list / ui_dump 这类数组结果也会被腰斩。这里放宽数组/深度，只保留键名与正则脱敏。
            val report = Redaction.redactTrafficPayload(
                rawPayload,
                RedactionOptions(
                    // ProxyPin 流量里可能带 Cookie/Token 长串，保持默认字符串上限；其余服务放宽
                    maxStringLength = if (serverId == McpServerId.PROXYPIN) RedactionOptions().maxStringLength else 4000,
                    maxArrayLength = 500,
                    maxDepth = 16
                )
            )
            return McpGatewayResponse(
                ok = true,
                degraded = false,
                serverId = serverId,
                method = method,
                latencyMs = latencyMs,
                data = report.redactedPayload,
                redactionReport = report,
                isSynthetic = isSynthetic
            )
        }
    }

    suspend fun call(opts: CallMcpGatewayOptions): McpGatewayResponse {
        val startedAt = nowMillis()
        val timeoutMs = opts.timeoutMs.coerceIn(300L, 30_000L)

        // 先校验 Token 控制字符
        val desiredAuthHeader = try {
            McpClient.buildAuthorizationHeader(opts.authToken)
        } catch (e: BridgeException) {
            return McpGatewayResponse(
                ok = false,
                degraded = opts.optional,
                serverId = opts.serverId,
                method = opts.method,
                latencyMs = nowMillis() - startedAt,
                errorKind = mapBridgeErrorCode(e.code),
                errorMessage = e.message
            )
        }

        if (opts.transport == McpTransport.LOCAL_BRIDGE) {
            return executeLocalMcpJsonRpc(opts, startedAt)
        }

        val client = if (opts.serverId == McpServerId.AUTOJS6) autojsClient else proxypinClient
        val targetUrl = opts.endpointUrl.ifBlank { client.currentUrl }

        // G-1：HTTP JSON-RPC 模式强制调用 DNS 解析后 SSRF 校验
        val urlCheck = UrlSecurity.validateEndpointUrlResolved(targetUrl, allowLocal = opts.allowLocal)
        if (!urlCheck.valid || urlCheck.normalizedUrl == null) {
            return McpGatewayResponse(
                ok = false,
                degraded = opts.optional,
                serverId = opts.serverId,
                method = opts.method,
                latencyMs = nowMillis() - startedAt,
                errorKind = McpErrorKind.SSRF_BLOCKED,
                errorMessage = urlCheck.reason ?: "URL 安全策略拦截"
            )
        }

        // G-2：仅当地址或非空令牌真正发生变化时才更新 client，绝不重置正在运行中的已有会话
        if (urlCheck.normalizedUrl != client.currentUrl) {
            client.setUrl(urlCheck.normalizedUrl)
        }
        if (opts.authToken.isNotBlank() && desiredAuthHeader != client.currentAuthHeader()) {
            client.setAuth(opts.authToken)
        }

        return try {
            val payload: JsonElement = when (opts.method) {
                "tools/list" -> client.listTools(timeoutMs, (opts.args["cursor"] as? JsonPrimitive)?.contentOrNull)
                "tools/call" -> callRemoteToolWithAdapter(client, opts, timeoutMs)
                else -> return McpGatewayResponse(
                    ok = false,
                    degraded = opts.optional,
                    serverId = opts.serverId,
                    method = opts.method,
                    latencyMs = nowMillis() - startedAt,
                    errorKind = McpErrorKind.PROTOCOL_ERROR,
                    errorMessage = "Unsupported JSON-RPC method: ${opts.method}"
                )
            }
            finalizeMcpRpcResponse(
                serverId = opts.serverId,
                method = opts.method,
                latencyMs = nowMillis() - startedAt,
                optional = opts.optional,
                rawPayload = payload,
                isSynthetic = false
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: BridgeException) {
            McpGatewayResponse(
                ok = false,
                degraded = opts.optional,
                serverId = opts.serverId,
                method = opts.method,
                latencyMs = nowMillis() - startedAt,
                errorKind = mapBridgeErrorCode(e.code),
                errorMessage = e.message
            )
        } catch (e: Exception) {
            McpGatewayResponse(
                ok = false,
                degraded = opts.optional,
                serverId = opts.serverId,
                method = opts.method,
                latencyMs = nowMillis() - startedAt,
                errorKind = McpErrorKind.TRANSPORT_ERROR,
                errorMessage = e.message ?: "MCP 网络传输异常"
            )
        }
    }

    /**
     * G-4：调用远端 MCP 工具。仅当远端明确返回「工具不存在」时才降级到 AutoJs6 插件的
     * `device_info` + `ui_current_window` + `ui_dump` 组合查询，且所有降级调用均传递 [timeoutMs]。
     */
    private suspend fun callRemoteToolWithAdapter(
        client: McpClient,
        opts: CallMcpGatewayOptions,
        timeoutMs: Long
    ): JsonElement {
        if (opts.serverId == McpServerId.AUTOJS6 && opts.toolName == "get_device_state") {
            val direct = try {
                client.callTool("get_device_state", opts.args, timeoutMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BridgeException) {
                if (!isToolNotFoundError(e)) throw e
                null
            }
            if (direct != null) {
                return parseTextToJsonOrPrimitive(direct.text)
            }
            val info = client.deviceInfo(timeoutMs)
            val win = try {
                client.currentWindow(timeoutMs)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val dump = try {
                client.uiDumpJson(maxNodes = 250, maxDepth = 24, timeoutMs = timeoutMs)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val visibleTexts = extractVisibleActionTexts(dump)
            return buildJsonObject {
                put("deviceModel", "${info.label} (Android ${info.androidRelease})")
                put("accessibilityReady", info.accessibilityReady)
                put("currentPackage", win?.packageName.orEmpty())
                put("currentActivity", win?.activityName.orEmpty())
                put("visibleActionTexts", buildJsonArray { visibleTexts.forEach { add(JsonPrimitive(it)) } })
            }
        }

        if (opts.serverId == McpServerId.AUTOJS6 && opts.toolName == "capture_ui_tree") {
            val maxNodes = (opts.args["maxNodes"] as? JsonPrimitive)?.intOrNull ?: 250
            return try {
                val res = client.callTool("capture_ui_tree", opts.args, timeoutMs)
                parseTextToJsonOrPrimitive(res.text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BridgeException) {
                if (!isToolNotFoundError(e)) throw e
                client.uiDumpJson(maxNodes = maxNodes, timeoutMs = timeoutMs)
            }
        }

        val res = client.callTool(opts.toolName, opts.args, timeoutMs)
        return parseTextToJsonOrPrimitive(res.text)
    }

    /**
     * 仅提取 `clickable == true` 且可见（`visibleToUser != false`）的节点文案（长度 ≤ 24），最多 50 条，
     * 避免将页面中普通不可点展示文本（如用户名/静态说明）混入动作候选列表。
     */
    internal fun extractVisibleActionTexts(dump: JsonObject?): List<String> {
        if (dump == null) return emptyList()
        val found = linkedSetOf<String>()
        fun walk(el: JsonElement?) {
            if (found.size >= 50) return
            when (el) {
                is JsonObject -> {
                    val clickable = (el["clickable"] as? JsonPrimitive)?.booleanOrNull == true
                    val visible = (el["visibleToUser"] as? JsonPrimitive)?.booleanOrNull != false &&
                        (el["visible"] as? JsonPrimitive)?.booleanOrNull != false
                    if (clickable && visible) {
                        val t = (el["text"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                        val d = (el["desc"] as? JsonPrimitive)?.contentOrNull?.trim()
                            ?: (el["contentDescription"] as? JsonPrimitive)?.contentOrNull?.trim()
                            .orEmpty()
                        if (t.isNotEmpty() && t.length <= 24 && found.size < 50) found += t
                        if (d.isNotEmpty() && d.length <= 24 && found.size < 50) found += d
                    }
                    el.values.forEach(::walk)
                }
                is JsonArray -> el.forEach(::walk)
                else -> Unit
            }
        }
        walk(dump)
        return found.toList()
    }

    private fun parseTextToJsonOrPrimitive(text: String): JsonElement =
        runCatching { json.parseToJsonElement(text) }.getOrElse {
            buildJsonObject { put("rawText", JsonPrimitive(text)) }
        }

    private fun executeLocalMcpJsonRpc(opts: CallMcpGatewayOptions, startedAt: Long): McpGatewayResponse {
        val elapsed = { (nowMillis() - startedAt).coerceAtLeast(1L) }

        if (opts.method == "tools/list") {
            val tools = if (opts.serverId == McpServerId.AUTOJS6) AUTOJS6_TOOLS else PROXYPIN_TOOLS
            return finalizeMcpRpcResponse(
                serverId = opts.serverId,
                method = opts.method,
                latencyMs = elapsed(),
                optional = opts.optional,
                rawPayload = buildJsonObject { put("tools", tools) },
                isSynthetic = true
            )
        }

        if (opts.method != "tools/call") {
            return McpGatewayResponse(
                ok = false,
                degraded = opts.optional,
                serverId = opts.serverId,
                method = opts.method,
                latencyMs = elapsed(),
                errorKind = McpErrorKind.PROTOCOL_ERROR,
                errorMessage = "JSON-RPC 协议错误 (-32601): Unsupported JSON-RPC method: ${opts.method}",
                isSynthetic = true
            )
        }

        val args = opts.args
        // G-6(d)：__simulateToolError / __simulateProtocolError 仅在 devMode 下生效
        if (opts.devMode) {
            if ((args["__simulateToolError"] as? JsonPrimitive)?.booleanOrNull == true) {
                return McpGatewayResponse(
                    ok = false,
                    degraded = opts.optional,
                    serverId = opts.serverId,
                    method = opts.method,
                    latencyMs = elapsed(),
                    errorKind = McpErrorKind.TOOL_EXECUTION_ERROR,
                    errorMessage = "MCP 工具执行错误 (isError=true): Tool \"${opts.toolName}\" executed with device rejection",
                    isSynthetic = true
                )
            }
            if ((args["__simulateProtocolError"] as? JsonPrimitive)?.booleanOrNull == true) {
                return McpGatewayResponse(
                    ok = false,
                    degraded = opts.optional,
                    serverId = opts.serverId,
                    method = opts.method,
                    latencyMs = elapsed(),
                    errorKind = McpErrorKind.PROTOCOL_ERROR,
                    errorMessage = "JSON-RPC 协议错误 (-32602): Invalid params for tool \"${opts.toolName}\"",
                    isSynthetic = true
                )
            }
        }

        val payload: JsonElement = when (opts.serverId) {
            McpServerId.AUTOJS6 -> when (opts.toolName) {
                "get_device_state" -> buildJsonObject {
                    put("deviceModel", "Pixel 8 Pro (Android 14)")
                    put("screenWidth", 1080)
                    put("screenHeight", 2400)
                    put("accessibilityReady", true)
                    put("screenCaptureReady", true)
                    put("currentPackage", "com.jingdong.app.mall")
                    put("currentActivity", "com.jingdong.common.jdreactFramework.activities.JDReactNativeCommonActivity")
                    put("visibleActionTexts", buildJsonArray {
                        listOf("每日做任务领金币", "去签到", "去浏览15秒", "领取奖励", "已完成").forEach { add(JsonPrimitive(it)) }
                    })
                    put("recommendedOcrRegion", buildJsonObject {
                        put("x0", 0.62); put("y0", 0.24); put("x1", 0.96); put("y1", 0.88)
                    })
                }
                "capture_ui_tree", "probe_ocr_region" -> buildJsonObject {
                    put("engine", "paddle")
                    put("nodes", buildJsonArray {
                        add(buildJsonObject {
                            put("text", "去签到")
                            put("boundsRatio", buildJsonArray { listOf(0.68, 0.31, 0.91, 0.36).forEach { add(JsonPrimitive(it)) } })
                            put("clickable", true)
                        })
                        add(buildJsonObject {
                            put("text", "去浏览15秒")
                            put("boundsRatio", buildJsonArray { listOf(0.66, 0.45, 0.92, 0.50).forEach { add(JsonPrimitive(it)) } })
                            put("clickable", true)
                        })
                        add(buildJsonObject {
                            put("text", "领取奖励")
                            put("boundsRatio", buildJsonArray { listOf(0.68, 0.59, 0.91, 0.64).forEach { add(JsonPrimitive(it)) } })
                            put("clickable", true)
                        })
                    })
                }
                else -> return McpGatewayResponse(
                    ok = false,
                    degraded = opts.optional,
                    serverId = opts.serverId,
                    method = opts.method,
                    latencyMs = elapsed(),
                    errorKind = McpErrorKind.PROTOCOL_ERROR,
                    errorMessage = "JSON-RPC 协议错误 (-32601): AutoJs6 MCP 未知工具: ${opts.toolName}",
                    isSynthetic = true
                )
            }

            McpServerId.PROXYPIN -> when (opts.toolName) {
                "list_http_flows", "get_flows" -> {
                    val domainFilter = (args["domain"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase().orEmpty()
                    val methodFilter = (args["method"] as? JsonPrimitive)?.contentOrNull?.trim()?.uppercase().orEmpty()
                    val statusFilter = (args["statusCode"] as? JsonPrimitive)?.intOrNull
                    val limit = ((args["limit"] as? JsonPrimitive)?.intOrNull ?: 20).coerceAtLeast(1)

                    val filtered = SYNTHETIC_PROXYPIN_FLOWS.filter { el ->
                        val f = el as? JsonObject ?: return@filter false
                        val dom = (f["domain"] as? JsonPrimitive)?.contentOrNull?.lowercase().orEmpty()
                        val m = (f["method"] as? JsonPrimitive)?.contentOrNull?.uppercase().orEmpty()
                        val sc = (f["statusCode"] as? JsonPrimitive)?.intOrNull
                        (domainFilter.isEmpty() || dom.contains(domainFilter)) &&
                            (methodFilter.isEmpty() || m == methodFilter) &&
                            (statusFilter == null || sc == statusFilter)
                    }.take(limit)

                    buildJsonObject {
                        put("total", filtered.size)
                        put("flows", buildJsonArray { filtered.forEach(::add) })
                    }
                }
                "get_flow_detail" -> {
                    val flowId = (args["flowId"] as? JsonPrimitive)?.contentOrNull ?: "flow_jr_001"
                    SYNTHETIC_PROXYPIN_FLOWS.firstOrNull {
                        ((it as? JsonObject)?.get("flowId") as? JsonPrimitive)?.contentOrNull == flowId
                    } ?: SYNTHETIC_PROXYPIN_FLOWS.first()
                }
                "analyze_endpoints" -> {
                    val dom = (args["domain"] as? JsonPrimitive)?.contentOrNull?.ifBlank { "ms.jr.jd.com" } ?: "ms.jr.jd.com"
                    buildJsonObject {
                        put("domain", dom)
                        put("endpoints", buildJsonArray {
                            add(buildJsonObject {
                                put("domain", dom)
                                put("path", "/gw/generic/hy/h5/m/queryTaskList")
                                put("method", "POST")
                                put("statusCode", 200)
                                put("extractedHints", buildJsonArray {
                                    add(JsonPrimitive("buttonText=去签到|去浏览15秒|领取奖励"))
                                    add(JsonPrimitive("browseSeconds=15"))
                                })
                            })
                            add(buildJsonObject {
                                put("domain", dom)
                                put("path", "/gw/generic/hy/h5/m/doTaskReport")
                                put("method", "POST")
                                put("statusCode", 200)
                                put("extractedHints", buildJsonArray {
                                    add(JsonPrimitive("nextActionHint=返回任务列表刷新状态"))
                                })
                            })
                        })
                    }
                }
                else -> return McpGatewayResponse(
                    ok = false,
                    degraded = opts.optional,
                    serverId = opts.serverId,
                    method = opts.method,
                    latencyMs = elapsed(),
                    errorKind = McpErrorKind.PROTOCOL_ERROR,
                    errorMessage = "JSON-RPC 协议错误 (-32601): ProxyPin MCP 未知工具: ${opts.toolName}",
                    isSynthetic = true
                )
            }
        }

        return finalizeMcpRpcResponse(
            serverId = opts.serverId,
            method = opts.method,
            latencyMs = elapsed(),
            optional = opts.optional,
            rawPayload = payload,
            isSynthetic = true
        )
    }
}
