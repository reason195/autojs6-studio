package cn.dumate.a6studio.config

import cn.dumate.a6studio.model.DeviceEntry

/**
 * Frequently used devices, shipped as defaults so the device dropdown is populated on first run.
 * Addresses only — tokens are never written here (they live in memory or, if the user opts in,
 * in platform storage).
 */
object AppConfig {
    /**
     * Placeholder defaults: this repository is public, so real LAN addresses are not committed.
     * Replace them with your own phone's address (AutoJs6 → 插件 → MCP Server → 设置 shows it),
     * or just fill the address field on the 真机 page — it is saved into the project.
     */
    val devices: List<DeviceEntry> = listOf(
        DeviceEntry("http://192.168.1.100:9637/mcp", "示例设备（改成你的手机 IP）")
    )

    val defaultUrl: String get() = devices.firstOrNull()?.url ?: ""
}