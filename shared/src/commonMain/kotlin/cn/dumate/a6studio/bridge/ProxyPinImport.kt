package cn.dumate.a6studio.bridge

/**
 * ProxyPin MCP 配置导入的纯解析逻辑（与 StudioState / 平台无关，可单测）。
 *
 * 与真机导入（McpConfigParser.parse）共用解析器，但默认端口是 ProxyPin 的 9000，
 * 且多台设备时优先取名字或 URL 里含「proxypin」的那台（不区分大小写），
 * 其余情况取第一台。
 */
object ProxyPinImport {

    /** ProxyPin MCP 的默认端口。仓库里没有单独的常量，来源是 StudioState 的
     *  `proxyPinUrl` 默认值 `http://127.0.0.1:9000/mcp`。 */
    const val DEFAULT_PORT = 9000

    /**
     * 从任意粘贴文本里挑出一台 ProxyPin MCP。
     * 解析行为：[McpConfigParser.parse]（mcpServers JSON、URL、纯 IP 均可，
     * 只写 IP 时补 9000 端口与 /mcp）；多台时优先名字或 URL 含 proxypin 的条目；
     * 一个都解析不到返回 null。
     */
    fun pick(text: String): McpServerConfig? {
        val found = McpConfigParser.parseWithDefaultPort(text, DEFAULT_PORT)
        if (found.isEmpty()) return null
        return found.firstOrNull { it.name.contains("proxypin", true) || it.url.contains("proxypin", true) }
            ?: found.first()
    }
}
