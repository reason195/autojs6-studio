package cn.dumate.a6studio.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * kitBoot 的护栏策略（N-M9）。
 *
 * 导入的 IR 会把原始 `policy` 以 JSON 文本存进 kitBoot.policyJson。生成代码时：
 * - 只认白名单字段（skipKeywords / riskKeywords / riskyActivityRe / forbiddenZones / riskWaitMs），其余丢弃；
 * - 与默认值**合并而不是覆盖**：关键词、禁点带取并集，危险页正则取「默认|导入」，
 *   因此导入或补丁只能让护栏更严，不能把默认护栏关掉；
 * - 输出由 Kotlin 重新序列化，字符串走 [Js.quote]，不会把原文拼进代码。
 */
object KitPolicy {

    const val DEFAULT_RISKY_RE = "Settlement|Checkout|ConfirmOrder|OrderSubmit|Pay|Cart|trade|permissioncontroller|GrantPermissions"
    val DEFAULT_SKIP = listOf("下单", "支付", "绑卡", "邀请好友", "开通")
    /** 与 Aj6KitRuntime 里 riskKws 的默认值保持一致：传了 riskKeywords 运行时就不再用默认值，所以这里要并进去。 */
    val DEFAULT_RISK = listOf("检测到安全风险", "京东验证", "请完成验证", "安全验证")
    val DEFAULT_ZONES = listOf(2130 to 2400)

    private const val MAX_LIST = 64
    private const val MAX_STR = 64
    private const val MAX_RE = 512
    private val json = Json { isLenient = true }

    data class Policy(
        val skipKeywords: List<String> = DEFAULT_SKIP,
        val riskKeywords: List<String> = DEFAULT_RISK,
        val riskyActivityRe: String = DEFAULT_RISKY_RE,
        val forbiddenZones: List<Pair<Int, Int>> = DEFAULT_ZONES,
        val riskWaitMs: Long? = null,
        val dropped: List<String> = emptyList()
    )

    private fun strList(el: JsonElement?, dropped: MutableList<String>, key: String): List<String> {
        val arr = el as? JsonArray ?: run { if (el != null) dropped += key; return emptyList() }
        return arr.take(MAX_LIST).mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
            .map { it.trim().take(MAX_STR) }.filter { it.isNotEmpty() }
    }

    /** 解析 policyJson（可为空/非法），返回与默认值合并后的策略。永不抛异常。 */
    fun parse(policyJson: String?): Policy {
        val dropped = mutableListOf<String>()
        val obj = policyJson?.takeIf { it.isNotBlank() }?.let {
            runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull()
                ?: run { dropped += "policyJson(非法 JSON)"; null }
        } ?: return Policy(dropped = dropped)

        val known = setOf("skipKeywords", "riskKeywords", "riskyActivityRe", "forbiddenZones", "riskWaitMs", "maxRuntimeMs")
        obj.keys.filter { it !in known }.forEach { dropped += it }

        val skip = (DEFAULT_SKIP + strList(obj["skipKeywords"], dropped, "skipKeywords")).distinct()
        val risk = (DEFAULT_RISK + strList(obj["riskKeywords"], dropped, "riskKeywords")).distinct()

        var re = DEFAULT_RISKY_RE
        (obj["riskyActivityRe"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.let { extra ->
            // Kotlin Regex 与 JS RegExp 不完全一致，这里只做「能编译 + 长度限制」的近似校验
            if (extra.isNotEmpty() && extra.length <= MAX_RE && runCatching { Regex(extra) }.isSuccess) {
                if (extra != DEFAULT_RISKY_RE) re = "$DEFAULT_RISKY_RE|(?:$extra)"
            } else if (extra.isNotEmpty()) dropped += "riskyActivityRe(非法或过长)"
        }

        val zones = DEFAULT_ZONES.toMutableList()
        (obj["forbiddenZones"] as? JsonArray)?.take(MAX_LIST)?.forEach { z ->
            val pair = (z as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
            if (pair != null && pair.size == 2 && pair.all { it.isFinite() && it in 0.0..20000.0 } && pair[0] < pair[1]) {
                val p = pair[0].toInt() to pair[1].toInt()
                if (p !in zones) zones += p
            } else dropped += "forbiddenZones 项"
        }

        val wait = (obj["riskWaitMs"] as? JsonPrimitive)?.doubleOrNull
            ?.takeIf { it.isFinite() }?.toLong()?.coerceIn(10_000L, 600_000L)

        return Policy(skip, risk, re, zones, wait, dropped.distinct())
    }

    /** 渲染 `policy: {...}` 的内部字段（每行带两格缩进），供 kitBoot 代码生成使用。 */
    fun renderJs(policyJson: String?, maxRuntimeMs: Long): List<String> {
        val p = parse(policyJson)
        fun arr(xs: List<String>) = xs.joinToString(", ", "[", "]") { Js.quote(it) }
        val lines = mutableListOf(
            "    maxRuntimeMs: $maxRuntimeMs,",
            "    riskyActivityRe: ${Js.quote(p.riskyActivityRe)},",
            "    forbiddenZones: ${p.forbiddenZones.joinToString(", ", "[", "]") { "[${it.first}, ${it.second}]" }},",
            "    riskKeywords: ${arr(p.riskKeywords)},"
        )
        p.riskWaitMs?.let { lines += "    riskWaitMs: $it," }
        lines += "    skipKeywords: ${arr(p.skipKeywords)}"
        return lines
    }
}
