package cn.dumate.a6studio.model


/**
 * Ready-made scripts. A beginner should never have to face an empty canvas: picking a template
 * gives them a working script they can run immediately, then tweak one field at a time.
 */
data class Template(
    val id: String,
    val title: String,
    val summary: String,
    val needsDevice: Boolean,
    val build: () -> Project
)

object Templates {

    val all: List<Template> = listOf(
        Template(
            id = "hello",
            title = "打个招呼（最简单的第一个脚本）",
            summary = "打开微信，弹一句提示。用来确认脚本能跑起来。",
            needsDevice = true,
            build = { helloWorld() }
        ),
        Template(
            id = "openAndTap",
            title = "打开 App 并点一个按钮",
            summary = "打开淘宝，等首页出现，然后点「我的」。改成你自己的文字就能用。",
            needsDevice = true,
            build = { openAndTap() }
        ),
        Template(
            id = "waitAndType",
            title = "在搜索框里输入并搜索",
            summary = "打开浏览器，等搜索框出现，输入内容再点搜索。",
            needsDevice = true,
            build = { searchFlow() }
        ),
        Template(
            id = "repeatSwipe",
            title = "自动往下滑 10 次",
            summary = "适合刷列表、看短视频之类的重复动作。",
            needsDevice = true,
            build = { repeatSwipe() }
        ),
        Template(
            id = "jdCoupon",
            title = "京东领券中心（完整示例）",
            summary = "进入领券中心、签到、把京豆任务逐个做完。可以先「运行」看效果再上真机。",
            needsDevice = false,
            build = { Samples.exampleProject() }
        ),
        Template(
            id = "jdServiceKit",
            title = "京东服务 · 护栏签到与任务（aj6_kit）",
            summary = "源自 Jingdou Studio：深链直达京东服务、风控停手/危险页拦截、天天签到与任务执行。",
            needsDevice = true,
            build = { jdServiceKitFlow() }
        ),
        Template(
            id = "jdJoyKit",
            title = "京东 JOY 游记 · 倒计时护栏（aj6_kit）",
            summary = "源自 Jingdou Studio：轮询落地页倒计时归零 + 2s 余量再返回，避免浏览时长不足。",
            needsDevice = true,
            build = { jdJoyKitFlow() }
        ),
        Template(
            id = "jdChannelKit",
            title = "京东频道 · 领券中心（aj6_kit）",
            summary = "源自 Jingdou Studio：金刚区进入领券中心、风控拦截与签到领豆。",
            needsDevice = true,
            build = { jdChannelFlow("京东频道 · 领券中心", "领券中心") }
        ),
        Template(
            id = "jd99BaoyouKit",
            title = "京东 9.9 包邮 · 京豆任务（aj6_kit）",
            summary = "源自 Jingdou Studio：进入 9.9 包邮频道、打开任务浮层与防误判收尾。",
            needsDevice = true,
            build = { jdChannelFlow("京东 9.9 包邮", "9.9包邮", actionText = "去完成") }
        ),
        Template(
            id = "jdMiaoshaKit",
            title = "京东秒杀 · 天天刮京豆（aj6_kit）",
            summary = "源自 Jingdou Studio：进入京东秒杀、执行浏览与立即刮卡护栏流程。",
            needsDevice = true,
            build = { jdChannelFlow("京东秒杀 · 刮京豆", "京东秒杀", actionText = "立即刮卡") }
        ),
        Template(
            id = "jdAiZhuangbeiKit",
            title = "京东 AI 装备馆 · 签到任务（aj6_kit）",
            summary = "源自 Jingdou Studio：深链直达 AI 装备馆，执行天天签到与京豆任务。",
            needsDevice = true,
            build = { jdDeepLinkSignFlow("AI装备馆", "https://pro.m.jd.com/mall/active/3xhqjGH1w2A3bF9x/index.html") }
        ),
        Template(
            id = "jdJingxiKit",
            title = "京喜自营 · 签到任务（aj6_kit）",
            summary = "源自 Jingdou Studio：金刚区进入京喜自营，执行天天签到与做任务。",
            needsDevice = true,
            build = { jdChannelFlow("京喜自营 · 签到", "京喜自营", actionText = "立即签到") }
        ),
        Template(
            id = "jdJiadianKit",
            title = "家电家居 · 签到任务（aj6_kit）",
            summary = "源自 Jingdou Studio：深链直达家电家居馆，执行天天签到与做任务。",
            needsDevice = true,
            build = { jdDeepLinkSignFlow("家电家居", "https://pro.m.jd.com/mall/active/4PgpL1xqPSW1sVXCJ/index.html") }
        ),
        Template(
            id = "jdPaipaiKit",
            title = "拍拍二手 · 签到任务（aj6_kit）",
            summary = "源自 Jingdou Studio：金刚区进入拍拍二手，执行签到与浏览领豆。",
            needsDevice = true,
            build = { jdChannelFlow("拍拍二手 · 签到", "拍拍二手", actionText = "去完成") }
        ),
        Template(
            id = "jdZhishuKit",
            title = "指数交易 · 领京豆任务（aj6_kit）",
            summary = "源自 Jingdou Studio：进入指数交易频道，执行签到与任务。",
            needsDevice = true,
            build = { jdChannelFlow("指数交易 · 领豆", "指数交易", actionText = "去完成") }
        ),
        Template(
            id = "jdChannelKaoyanKit",
            title = "京东频道 · 京东校园（aj6_kit）",
            summary = "源自 Jingdou Studio：金刚区进入京东校园频道自动签到领豆。",
            needsDevice = true,
            build = { jdChannelFlow("京东频道 · 京东校园", "京东校园") }
        ),
        Template(
            id = "jdChannelZhishuKit",
            title = "京东频道 · 指数交易简版（aj6_kit）",
            summary = "源自 Jingdou Studio：金刚区进入指数交易频道快速签到。",
            needsDevice = true,
            build = { jdChannelFlow("京东频道 · 指数交易", "指数交易") }
        ),
        Template(
            id = "jdChannelKanyaoKit",
            title = "京东频道 · 看病买药（aj6_kit）",
            summary = "源自 Jingdou Studio：金刚区进入看病买药频道自动签到领豆。",
            needsDevice = true,
            build = { jdChannelFlow("京东频道 · 看病买药", "看病买药") }
        )
    )

    fun byId(id: String): Template? = all.firstOrNull { it.id == id }

    // ---- builders -----------------------------------------------------------
    private fun blank(name: String) = Samples.emptyProject(name)

    private fun Project.withBody(blocks: List<Block>): Project =
        copy(root = root.copy(children = mapOf("body" to blocks)))

    private fun helloWorld(): Project = blank("打招呼").withBody(
        listOf(
            newBlock("comment", mapOf("text" to ParamValue.Text("第一个脚本：打开微信，然后弹一句提示"))),
            newBlock("toast", mapOf("text" to ParamValue.Text("脚本开始啦"))),
            newBlock("launchPackage", mapOf("pkg" to ParamValue.Text("com.tencent.mm"), "wait" to ParamValue.Num(2000.0))),
            newBlock("toast", mapOf("text" to ParamValue.Text("微信已经打开了"))),
            newBlock("comment", mapOf("text" to ParamValue.Text("想改内容？点上面积木里的文字框直接改就行"))),
            newBlock("sleep", mapOf("ms" to ParamValue.Num(2000.0)))
        )
    )

    private fun openAndTap(): Project = blank("打开并点击").withBody(
        listOf(
            newBlock("comment", mapOf("text" to ParamValue.Text("打开淘宝 → 等首页 → 点「我的」"))),
            newBlock("autoWait"),
            newBlock("launchPackage", mapOf("pkg" to ParamValue.Text("com.taobao.taobao"), "wait" to ParamValue.Num(3000.0))),
            newBlock(
                "waitText",
                mapOf("value" to ParamValue.Text("搜索"), "timeout" to ParamValue.Num(8000.0))
            ),
            newBlock("clickNode", mapOf("value" to ParamValue.Text("我的"), "timeout" to ParamValue.Num(3000.0))),
            newBlock("toast", mapOf("text" to ParamValue.Text("已经点进去了")))
        )
    )

    private fun searchFlow(): Project = blank("搜索").withBody(
        listOf(
            newBlock("comment", mapOf("text" to ParamValue.Text("把 input 改成你想搜的词"))),
            newBlock("autoWait"),
            newBlock("launchPackage", mapOf("pkg" to ParamValue.Text("com.android.chrome"), "wait" to ParamValue.Num(3000.0))),
            newBlock(
                "setTextNode",
                mapOf("value" to ParamValue.Text("搜索或输入网址"), "text" to ParamValue.Text("AutoJs6"))
            ),
            newBlock("clickNode", mapOf("by" to ParamValue.Text("text"), "value" to ParamValue.Text("搜索"))),
            newBlock("sleep", mapOf("ms" to ParamValue.Num(3000.0))),
            newBlock("toast", mapOf("text" to ParamValue.Text("搜完啦")))
        )
    )

    private fun repeatSwipe(): Project = blank("自动下滑").withBody(
        listOf(
            newBlock("comment", mapOf("text" to ParamValue.Text("把「10」改成你要滑的次数"))),
            newBlock("autoWait"),
            newBlock(
                "forRange",
                mapOf("i" to ParamValue.Text("i"), "n" to ParamValue.Num(10.0))
            ).copy(
                children = mapOf(
                    "body" to listOf(
                        newBlock("swipeUpList"),
                        newBlock("sleep", mapOf("ms" to ParamValue.Num(800.0)))
                    )
                )
            ),
            newBlock("toast", mapOf("text" to ParamValue.Text("滑完了")))
        )
    )

    private fun jdServiceKitFlow(): Project = blank("京东服务 · 护栏签到").withBody(
        listOf(
            newBlock("autoWait"),
            newBlock("kitBoot", mapOf("pkg" to ParamValue.Text("com.jingdong.app.mall"), "name" to ParamValue.Text("京东服务"), "maxRuntimeMs" to ParamValue.Num(900000.0))),
            newBlock("kitLaunch"),
            newBlock("kitOpenDeepLink", mapOf("url" to ParamValue.Text("openapp.jdmobile://virtual?params={\"needLogin\":\"0\",\"des\":\"m\",\"category\":\"jump\",\"url\":\"https://pro.m.jd.com/mall/active/jpeZLX5Lb3gpwH98tAaB8Y1HY99/index.html\"}"))),
            newBlock("kitGuard"),
            newBlock("kitWaitFor", mapOf("by" to ParamValue.Text("text"), "match" to ParamValue.Text("contains"), "value" to ParamValue.Text("天天签到"), "timeoutMs" to ParamValue.Num(15000.0))),
            newBlock("kitClick", mapOf("by" to ParamValue.Text("text"), "match" to ParamValue.Text("contains"), "value" to ParamValue.Text("天天签到"))),
            newBlock("kitWaitFor", mapOf("by" to ParamValue.Text("text"), "match" to ParamValue.Text("eq"), "value" to ParamValue.Text("立即签到"), "timeoutMs" to ParamValue.Num(8000.0))),
            newBlock("kitClick", mapOf("by" to ParamValue.Text("text"), "match" to ParamValue.Text("eq"), "value" to ParamValue.Text("立即签到"))),
            newBlock("kitFinish", mapOf("result" to ParamValue.Text("auto")))
        )
    )

    private fun jdJoyKitFlow(): Project = blank("京东 JOY 游记 · 倒计时护栏").withBody(
        listOf(
            newBlock("autoWait"),
            newBlock("kitBoot", mapOf("pkg" to ParamValue.Text("com.jingdong.app.mall"), "name" to ParamValue.Text("JOY游记"), "maxRuntimeMs" to ParamValue.Num(600000.0))),
            newBlock("kitLaunch"),
            newBlock("kitOpenDeepLink", mapOf("url" to ParamValue.Text("openapp.jdmobile://virtual?params={\"needLogin\":\"0\",\"des\":\"m\",\"category\":\"jump\",\"url\":\"https://pro.m.jd.com/mall/active/3VeB2YCUT4XLVJKWM3W5wXMA7JXG/index.html\"}"))),
            newBlock("kitGuard"),
            newBlock("kitWaitFor", mapOf("by" to ParamValue.Text("text"), "match" to ParamValue.Text("contains"), "value" to ParamValue.Text("天降京豆"), "timeoutMs" to ParamValue.Num(15000.0))),
            newBlock("kitClick", mapOf("by" to ParamValue.Text("text"), "match" to ParamValue.Text("contains"), "value" to ParamValue.Text("天降京豆"))),
            newBlock("kitClick", mapOf("by" to ParamValue.Text("text"), "match" to ParamValue.Text("contains"), "value" to ParamValue.Text("看10秒领"))),
            newBlock("kitWaitCountdown", mapOf("minDwellSec" to ParamValue.Num(12.0), "graceSec" to ParamValue.Num(2.0))),
            newBlock("kitClick", mapOf("by" to ParamValue.Text("text"), "match" to ParamValue.Text("contains"), "value" to ParamValue.Text("开心收下"))),
            newBlock("kitFinish", mapOf("result" to ParamValue.Text("auto")))
        )
    )

    private fun jdChannelFlow(title: String, channelDesc: String, actionText: String = "签到"): Project =
        blank(title).withBody(
            listOf(
                newBlock("autoWait"),
                newBlock("kitBoot", mapOf("pkg" to ParamValue.Text("com.jingdong.app.mall"), "name" to ParamValue.Text(title), "maxRuntimeMs" to ParamValue.Num(600000.0))),
                newBlock("kitLaunch"),
                newBlock("kitGuard"),
                newBlock("kitWaitFor", mapOf("by" to ParamValue.Text("desc"), "match" to ParamValue.Text("contains"), "value" to ParamValue.Text(channelDesc), "timeoutMs" to ParamValue.Num(12000.0))),
                newBlock("kitClick", mapOf("by" to ParamValue.Text("desc"), "match" to ParamValue.Text("contains"), "value" to ParamValue.Text(channelDesc))),
                newBlock("kitWaitFor", mapOf("by" to ParamValue.Text("text"), "match" to ParamValue.Text("contains"), "value" to ParamValue.Text(actionText), "timeoutMs" to ParamValue.Num(8000.0))),
                // N-L8：只有「立即刮卡」需要精确匹配（运行时注释里的子串撞车事故）；
                // 其他频道的「去完成」等按钮文案常带后缀（如「去完成 >」），保持 contains
                newBlock("kitClick", mapOf("by" to ParamValue.Text("text"), "match" to ParamValue.Text(if (actionText == "立即刮卡") "eq" else "contains"), "value" to ParamValue.Text(actionText))),
                newBlock("kitFinish", mapOf("result" to ParamValue.Text("auto")))
            )
        )

    private fun jdDeepLinkSignFlow(title: String, activeUrl: String): Project =
        blank("$title · 护栏签到").withBody(
            listOf(
                newBlock("autoWait"),
                newBlock("kitBoot", mapOf("pkg" to ParamValue.Text("com.jingdong.app.mall"), "name" to ParamValue.Text(title), "maxRuntimeMs" to ParamValue.Num(900000.0))),
                newBlock("kitLaunch"),
                newBlock("kitOpenDeepLink", mapOf("url" to ParamValue.Text("openapp.jdmobile://virtual?params={\"needLogin\":\"0\",\"des\":\"m\",\"category\":\"jump\",\"url\":\"$activeUrl\"}"))),
                newBlock("kitGuard"),
                newBlock("kitWaitFor", mapOf("by" to ParamValue.Text("text"), "match" to ParamValue.Text("contains"), "value" to ParamValue.Text("天天签到"), "timeoutMs" to ParamValue.Num(15000.0))),
                newBlock("kitClick", mapOf("by" to ParamValue.Text("text"), "match" to ParamValue.Text("contains"), "value" to ParamValue.Text("天天签到"))),
                newBlock("kitWaitFor", mapOf("by" to ParamValue.Text("text"), "match" to ParamValue.Text("eq"), "value" to ParamValue.Text("立即签到"), "timeoutMs" to ParamValue.Num(8000.0))),
                newBlock("kitClick", mapOf("by" to ParamValue.Text("text"), "match" to ParamValue.Text("eq"), "value" to ParamValue.Text("立即签到"))),
                newBlock("kitFinish", mapOf("result" to ParamValue.Text("auto")))
            )
        )
}