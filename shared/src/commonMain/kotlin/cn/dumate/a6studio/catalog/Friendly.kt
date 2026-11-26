package cn.dumate.a6studio.catalog

/**
 * Plain-language help for people who have never written code.
 *
 * The catalogue is written for people who already know AutoJs6 terms such as 「遍历」「断言」
 * 「无障碍服务」. A beginner needs three things instead: what the block *does* in everyday words,
 * which blocks they should start with, and what the parameters mean. This file holds that layer
 * so the block definitions stay untouched and the copy can be edited independently.
 */
object Friendly {

    /** Blocks a beginner should see first, in the order they are usually useful. */
    val starterBlocks: List<String> = listOf(
        "launchPackage", "openUrl", "waitText", "clickNode", "setTextNode",
        "pressBack", "pressHome", "sleep", "toast", "comment"
    )

    /** Categories in beginner-friendly order with plain names. */
    data class FriendlyCategory(val id: String, val simpleName: String, val hint: String, val forBeginners: Boolean)

    val categories: List<FriendlyCategory> = listOf(
        FriendlyCategory("flow", "基本操作", "等待、重复、如果…就…", true),
        FriendlyCategory("app", "打开 App / 网页", "启动应用、打开链接", true),
        FriendlyCategory("ui", "点击和输入", "在屏幕上点按钮、填内容", true),
        FriendlyCategory("gesture", "滑动和按键", "滑动屏幕、按返回键", true),
        FriendlyCategory("jd", "京东领券示例", "照着这个改就能用", true),
        FriendlyCategory("kit", "护栏脚本（aj6_kit）", "自带风控停手与防误判机制", false),
        FriendlyCategory("vision", "看图 / 认字", "截图、找图、识别文字", false),
        FriendlyCategory("data", "文件 / 网络", "存数据、发请求", false),
        FriendlyCategory("debug", "调试工具", "检查脚本哪里出错", false),
        FriendlyCategory("module", "我的模块", "把一段操作打包复用", false),
        FriendlyCategory("raw", "原生代码", "从 .js 脚本导入、暂时无法变成积木的代码", false)
    )

    fun categoryName(id: String): String =
        categories.firstOrNull { it.id == id }?.simpleName ?: id

    fun isBeginnerCategory(id: String): Boolean =
        categories.firstOrNull { it.id == id }?.forBeginners ?: false

    /** One sentence, no jargon, saying what this block actually does. */
    fun describe(type: String): String = descriptions[type] ?: ""

    /** Plain explanation of each parameter, shown under the field in beginner mode. */
    fun paramHint(type: String, key: String): String =
        paramHints[type]?.get(key) ?: ""

    private val descriptions: Map<String, String> = mapOf(
        // 基本操作
        "comment" to "写一句给自己看的备注，不会执行任何操作",
        "autoWait" to "先等手机的「无障碍」功能准备好，后面的点击才认得准",
        "consoleShow" to "把脚本的运行过程显示成一个浮动窗口",
        "log" to "在日志里记一行字，方便你看脚本跑到哪了",
        "logExpr" to "把一段计算结果记到日志里（高级）",
        "toast" to "在屏幕下方弹一句提示，一两秒后自动消失",
        "sleep" to "停下来等一会儿，等页面加载完再继续",
        "varSet" to "记住一个值，后面可以反复用（高级）",
        "varAssign" to "改掉之前记住的值（高级）",
        "if" to "如果条件成立，就做里面的操作",
        "forRange" to "把里面的操作重复做几次",
        "forOf" to "把里面的操作对清单里每一项都做一遍（高级）",
        "whileGuard" to "一直重复，直到条件满足，并设一个次数上限防止卡死",
        "tryCatch" to "试着做这些操作，万一出错就执行另一段，不至于整个脚本崩掉",
        "funcDef" to "定义一段可以反复调用的操作（高级）",
        "funcCall" to "执行前面定义好的那段操作（高级）",
        "exitScript" to "立刻结束整个脚本",
        "retry" to "失败了就再试几次，每次间隔一会儿",

        // 打开 App / 网页
        "launchPackage" to "打开一个 App，然后等它加载",
        "openUrl" to "用浏览器或 App 打开一个网址",
        "killApp" to "关掉某个 App",
        "currentPkgVar" to "记下当前正在用的是哪个 App（高级）",
        "waitForPackage" to "等某个 App 打开完成（高级）",
        "keepScreenOn" to "让屏幕别自动熄灭，适合跑很久的脚本",
        "vibrate" to "让手机震动一下",
        "clipSet" to "把一段文字复制到剪贴板",
        "clipGet" to "把剪贴板里的文字读出来（高级）",
        "shellCmd" to "执行一条系统命令（高级，可能没权限）",

        // 护栏运行时（aj6_kit）
        "kitBoot" to "启动护栏底座：自动拦截风控验证码、支付/结算页面和底部禁点区",
        "kitLaunch" to "安全拉起目标 App 并确认已在前台",
        "kitOpenDeepLink" to "通过深链直达活动页，避开首页入口变动",
        "kitGuard" to "检查当前是否触发风控、误入危险页或总时长超时",
        "kitWaitFor" to "只等待真正在屏幕可见区域内的控件出现（忽略离屏节点）",
        "kitClick" to "按控件中心点安全点击，避免误点屏幕边缘",
        "kitWaitCountdown" to "轮询落地页倒计时归零并多等几秒余量再返回，防止浏览时长不足",
        "kitFinish" to "按实际完成情况输出汇总，有未完成任务时标记 WARN",

        // 点击和输入
        "waitText" to "等屏幕上出现某段文字，出现了再继续",
        "clickNode" to "点击屏幕上写着某段文字的按钮",
        "longClickNode" to "长按屏幕上写着某段文字的按钮",
        "setTextNode" to "在一个输入框里填上内容",
        "nodeTextVar" to "读出一个控件上的文字（高级）",
        "existsVar" to "判断屏幕上有没有某个东西（高级）",
        "scrollNode" to "把某个列表向上或向下滚动",
        "pressBack" to "按一下手机的返回键",
        "pressHome" to "回到手机桌面",
        "coordClick" to "点击屏幕上的某个具体位置（换个手机可能就点偏了）",

        // 滑动和按键
        "swipe" to "从一处滑到另一处",
        "swipeUpList" to "把列表向上滑一屏（看下面的内容）",
        "edgeBackGesture" to "从屏幕左边缘右滑，切回上一个 App",
        "pressKey" to "按一个系统按键，比如返回、Home",

        // 看图 / 认字
        "requestCapture" to "申请截屏权限，后面的找图要用",
        "findImage" to "在屏幕上找一张小图的位置（需要先截好模板图）",
        "ocrText" to "把屏幕上的文字识别出来（需要手机支持 OCR）",
        "saveScreen" to "截屏并保存成图片",

        // 文件 / 网络
        "filesWrite" to "把内容写进手机里的一个文件",
        "filesRead" to "读出手机里某个文件的内容",
        "filesExistsVar" to "判断某个文件在不在（高级）",
        "storagePut" to "把一个小数据存起来，下次还能读到",
        "storageGet" to "把之前存的小数据读出来",
        "httpGet" to "向一个网址请求数据（高级，需要懂接口）",
        "httpPostJson" to "向一个网址发送数据（高级，需要懂接口）",
        "threadStart" to "让这段操作在后台同时跑（高级）",

        // 调试工具
        "assertBlock" to "检查某个条件是否成立，不成立就报错停下",
        "timerStart" to "开始计时（高级）",
        "timerLap" to "输出从开始到现在用了多久（高级）",
        "dumpUi" to "把当前屏幕上有哪些控件列出来，用来排查点不到的原因",

        // 京东示例
        "jdEnter" to "【示例】打开京东领券中心页面",
        "jdSign" to "【示例】点签到按钮，并确认签到成功",
        "jdOpenPanel" to "【示例】打开「更多任务」的浮层",
        "jdTaskLoop" to "【示例】把京豆任务一个个点开",
        "jdDoTask" to "【示例】完成一个任务后返回",
        "jdCrossApp" to "【示例】跳到别的 App 后马上切回京东",
        "jdClosePopup" to "【示例】关掉任务完成后弹出的空窗口",
        "jdFinishGuard" to "【示例】确认没有更多任务了才收工",

        // 模块
        "moduleCall" to "执行你自己打包好的那段操作",

        // 原生代码
        "rawJs" to "从你的 .js 脚本原样搬过来的代码；能运行，但暂时不能在画布上改参数"
    )

    private val paramHints: Map<String, Map<String, String>> = mapOf(
        "clickNode" to mapOf(
            "value" to "按钮上写的字，比如「立即领取」",
            "by" to "按什么来认这个按钮（一般用「文本」就行）",
            "timeout" to "最多等多久去找它（毫秒，1000 = 1 秒）"
        ),
        "waitText" to mapOf(
            "value" to "要等的文字",
            "timeout" to "最多等多久（毫秒）"
        ),
        "setTextNode" to mapOf(
            "value" to "输入框上提示的文字或说明",
            "text" to "要填进去的内容"
        ),
        "longClickNode" to mapOf("value" to "按钮上写的字"),
        "scrollNode" to mapOf("value" to "要滚动的那个区域上的文字"),
        "launchPackage" to mapOf(
            "pkg" to "App 的包名，比如京东是 com.jingdong.app.mall",
            "wait" to "打开后等多久（毫秒）"
        ),
        "openUrl" to mapOf("url" to "完整网址，要从 http 开始"),
        "sleep" to mapOf("ms" to "等多久，1000 = 1 秒"),
        "toast" to mapOf("text" to "要提示的那句话"),
        "log" to mapOf("text" to "要记下来的内容"),
        "comment" to mapOf("text" to "给自己的备注"),
        "keepScreenOn" to mapOf("ms" to "保持多久，1800000 = 30 分钟"),
        "swipe" to mapOf(
            "x1" to "起点横坐标", "y1" to "起点纵坐标",
            "x2" to "终点横坐标", "y2" to "终点纵坐标",
            "dur" to "滑动用多久（毫秒）"
        ),
        "if" to mapOf("cond" to "条件，比如 true"),
        "forRange" to mapOf("n" to "重复几次"),
        "whileGuard" to mapOf("max" to "最多重复几次，别设太大")
    )
}