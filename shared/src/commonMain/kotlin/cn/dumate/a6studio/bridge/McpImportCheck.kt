package cn.dumate.a6studio.bridge

import cn.dumate.a6studio.security.UrlSecurity

/**
 * 真机 MCP 配置导入的写入前校验（与 [ProxyPinImport.checkBeforeWrite] 同一套门槛）。
 *
 * 设备在局域网里，所以本地/内网地址照常放行；云元数据地址、非标准 IP 写法、带 userinfo
 * 的地址会被拒，含 CR/LF 等控制字符的令牌也在写进 [McpClient] 之前被拒——否则
 * `setAuthFor` 会在导入循环中途抛异常，前面几台已经写进设备列表、后面几台丢失。
 */
object McpImportCheck {

    /** 返回 null 表示可以导入；否则返回应提示用户的原因。 */
    fun reject(url: String, token: String): String? {
        val check = UrlSecurity.validateEndpointUrl(url, allowLocal = true)
        if (!check.valid || check.normalizedUrl == null) {
            return "地址被拒：${check.reason ?: "URL 无效"}"
        }
        return runCatching { McpClient.buildAuthorizationHeader(token) }
            .exceptionOrNull()
            ?.let { "令牌被拒：${it.message}" }
    }
}
