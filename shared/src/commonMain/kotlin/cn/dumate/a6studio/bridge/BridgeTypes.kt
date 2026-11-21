package cn.dumate.a6studio.bridge

/**
 * Bridge failures carry a machine-readable [code] so the UI can give actionable advice
 * (timeouts on the phone MCP server have a very specific recovery path).
 */
class BridgeException(
    message: String,
    val code: String = "UNKNOWN",
    val retryAfterMs: Long = 0
) : Exception(message)

data class McpToolResult(
    val text: String,
    val content: List<McpContent> = emptyList(),
    val isError: Boolean = false
)

data class McpContent(
    val type: String,
    val text: String? = null,
    val data: String? = null,        // base64 image payload
    val mimeType: String? = null
)

data class ScreenCapture(
    val base64: String,
    val mimeType: String,
    val metaJson: String
)

data class UiFindResult(
    val count: Int,
    val returned: Int,
    val truncated: Boolean,
    val nodesJson: String
)

/** Device info shown on the 真机 page. */
data class DeviceInfo(
    val manufacturer: String = "",
    val model: String = "",
    val androidRelease: String = "",
    val androidApi: String = "",
    val versionName: String = "",
    val versionCode: String = "",
    val plugin: String = "",
    val pluginId: String = "",
    val process: String = "",
    val time: String = "",
    /** True when AutoJs6's accessibility service is running — selectors never work without it. */
    val accessibilityReady: Boolean = false,
    val batteryPercent: String = ""
) {
    val label: String
        get() = listOf(manufacturer, model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "未知设备" }
}

/** Current-foreground-window summary, used to detect screen changes before writing a selector. */
data class WindowInfo(val packageName: String = "", val activityName: String = "")