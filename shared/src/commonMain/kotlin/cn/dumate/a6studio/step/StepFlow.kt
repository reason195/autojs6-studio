package cn.dumate.a6studio.step

import cn.dumate.a6studio.model.Block
import cn.dumate.a6studio.model.Ids
import cn.dumate.a6studio.model.ParamValue
import cn.dumate.a6studio.model.newBlock

/**
 * 交互式单步识别-生成：用户在被自动化 App 上一步步点，每次点击都
 *  ① 真实下发到手机（bridge.uiClick），让目标 App 翻到下一屏；
 *  ② 在 App 里记下「这一步点了什么 / 插了什么模板动作」，攒成脚本。
 *
 * 每一步都既能回放（真机点击）又能落成积木（生成脚本），两种用途共用一份数据。
 */
sealed interface StepEntry {
    val id: String
    val label: String

    /** 转成脚本积木：节点点击 → 等它可见再点它；模板动作 → 对应该动作的积木序列。 */
    fun toBlocks(): List<Block>
}

/** 用户点了识别列表里的某个可点按钮：真实点击它，并记下「点这个按钮」。 */
data class NodeTapStep(
    override val id: String,
    val by: String,   // "text" | "desc"
    val value: String,
    val pkg: String = ""
) : StepEntry {
    override val label: String get() = "点「$value」"

    override fun toBlocks(): List<Block> = listOf(
        newBlock(
            "kitWaitFor",
            mapOf(
                "by" to ParamValue.Text(by),
                "match" to ParamValue.Text("contains"),
                "value" to ParamValue.Text(value),
                "timeoutMs" to ParamValue.Num(12000.0)
            )
        ),
        newBlock(
            "kitClick",
            mapOf(
                "by" to ParamValue.Text(by),
                "match" to ParamValue.Text("contains"),
                "value" to ParamValue.Text(value)
            )
        )
    )
}

/** 用户插的一条模板动作（等待 / 关弹窗 / 等倒计时 / 检测跳 App），可扩展。 */
data class TemplateStep(
    override val id: String,
    val templateId: String,
    override val label: String,
    val blocks: List<Block>
) : StepEntry {
    override fun toBlocks(): List<Block> = blocks
}

/**
 * 可扩展的模板动作表：加一个新动作 = 在这个列表末尾加一项。
 * 每个动作有 id / 展示名 / 一行描述 / 生成积木的回调。
 */
data class TemplateActionDef(
    val id: String,
    val title: String,
    val desc: String,
    val build: () -> List<Block>
)

object StepTemplates {
    /** 「等待 N 秒」：N 由用户在插入时选择，其余参数固定。 */
    fun waitSeconds(defId: String, seconds: Int): TemplateStep {
        val sec = seconds.coerceAtLeast(1)
        val label = "等 $sec 秒"
        return TemplateStep(
            id = defId,
            templateId = "wait",
            label = label,
            blocks = listOf(
                newBlock("log", mapOf("text" to ParamValue.Text("等待 $sec 秒"))),
                newBlock("sleep", mapOf("ms" to ParamValue.Num((sec * 1000).toDouble())))
            )
        )
    }

    /** 「检测弹窗并关闭」：护栏 runtime 的 dismissDialogs() 只点取消/暂不/以后再说类按钮。
     *  用 kitGuard 把风控 + 系统弹窗 + 危险页面一起兜住。 */
    fun closeDialogs(defId: String): TemplateStep = TemplateStep(
        id = defId,
        templateId = "closeDialogs",
        label = "检测弹窗并关闭",
        blocks = listOf(
            newBlock("kitGuard")
        )
    )

    /** 「检测倒计时器并等待」：等屏幕上倒计时归零 + 余量（kitWaitCountdown）。 */
    fun waitCountdown(defId: String, minSec: Int = 12, graceSec: Int = 2): TemplateStep = TemplateStep(
        id = defId,
        templateId = "waitCountdown",
        label = "等倒计时归零",
        blocks = listOf(
            newBlock(
                "kitWaitCountdown",
                mapOf(
                    "minDwellSec" to ParamValue.Num(minSec.toDouble()),
                    "graceSec" to ParamValue.Num(graceSec.toDouble())
                )
            )
        )
    )

    /** 「检测是否跳转到其他 App」：目标 App 不在前台时用 launch 拉回（recoverForeground）。 */
    fun ensureForeground(defId: String): TemplateStep = TemplateStep(
        id = defId,
        templateId = "ensureForeground",
        label = "检测跳转并拉回",
        blocks = listOf(
            newBlock("kitEnsureForeground")
        )
    )

    val menu: List<TemplateActionDef> = listOf(
        TemplateActionDef("wait", "等待 N 秒", "原地停 N 秒，适合等动画/加载", { waitSeconds(Ids.next(), 3).blocks }),
        TemplateActionDef("closeDialogs", "检测弹窗并关闭", "只点「取消/暂不/以后再说」，不代授权", { closeDialogs(Ids.next()).blocks }),
        TemplateActionDef("waitCountdown", "检测倒计时器并等待", "等倒计时归零 + 2s 余量（浏览时长类任务）", { waitCountdown(Ids.next()).blocks }),
        TemplateActionDef("ensureForeground", "检测是否跳转到其他 App", "离开目标 App 时自动 launch 拉回（防跳飞）", { ensureForeground(Ids.next()).blocks })
    )

    fun byId(id: String): TemplateActionDef? = menu.firstOrNull { it.id == id }
}