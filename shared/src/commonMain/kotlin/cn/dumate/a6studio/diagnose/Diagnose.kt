package cn.dumate.a6studio.diagnose

import cn.dumate.a6studio.catalog.Catalog
import cn.dumate.a6studio.model.Block
import cn.dumate.a6studio.model.Project
import cn.dumate.a6studio.runtime.RunResult

enum class Level { ERROR, WARN, INFO }

data class Issue(
    val level: Level,
    val title: String,
    val hint: String = "",
    val loc: String = "",
    val blockId: String? = null
)

/** Static rules + run-log replay, ported from the web `diagnose.js`. */
object Diagnose {

    private val containerTypes = setOf(
        "if", "forRange", "forOf", "whileGuard", "tryCatch", "funcDef", "threadStart", "retry", "jdTaskLoop"
    )
    private val uiTypes = setOf(
        "clickNode", "longClickNode", "setTextNode", "waitText", "scrollNode", "nodeTextVar", "existsVar"
    )

    val identifierParams: Map<String, List<String>> = mapOf(
        "varSet" to listOf("name"), "varAssign" to listOf("name"),
        "funcDef" to listOf("name"), "funcCall" to listOf("name"),
        "forRange" to listOf("i"), "forOf" to listOf("item"),
        "tryCatch" to listOf("err"),
        "currentPkgVar" to listOf("name"), "clipGet" to listOf("name"),
        "shellCmd" to listOf("var"), "nodeTextVar" to listOf("var"), "existsVar" to listOf("var"),
        "findImage" to listOf("var"), "ocrText" to listOf("var"), "filesRead" to listOf("var"),
        "filesExistsVar" to listOf("var"), "storageGet" to listOf("var"),
        "httpGet" to listOf("var"), "httpPostJson" to listOf("var"),
        "timerStart" to listOf("var"), "timerLap" to listOf("var"),
        "jdTaskLoop" to listOf("taskVar"), "jdDoTask" to listOf("taskVar")
    )

    private data class Item(val b: Block, val scope: String, val index: Int, val ancestors: List<Block>)

    private fun collect(project: Project): List<Item> {
        val items = mutableListOf<Item>()
        fun walk(list: List<Block>, scope: String, ancestors: List<Block>) {
            list.forEachIndexed { i, b ->
                items += Item(b, scope, i + 1, ancestors)
                b.children.forEach { (_, kids) -> walk(kids, scope, ancestors + b) }
            }
        }
        walk(project.root.children["body"].orEmpty(), "主程序", emptyList())
        project.modules.forEach { m -> walk(m.children["body"].orEmpty(), "模块「${m.name}」", emptyList()) }
        return items
    }

    fun scan(project: Project): List<Issue> {
        val issues = mutableListOf<Issue>()
        val items = collect(project)

        fun add(level: Level, title: String, hint: String = "", it: Item? = null) {
            issues += Issue(
                level = level,
                title = title,
                hint = hint,
                loc = it?.let { "${it.scope} · 第 ${it.index} 块" } ?: "项目",
                blockId = it?.b?.id
            )
        }

        if (project.root.children["body"].isNullOrEmpty()) {
            add(Level.INFO, "主程序还是空的", "把左侧积木拖进来，或点「示例」载入京东领券中心流程")
        }

        val hasAutoWait = items.any { it.b.type == "autoWait" }
        val firstUi = items.firstOrNull { it.b.type in uiTypes }
        val scopeHasFunc = firstUi != null && items.any { it.scope == firstUi.scope && it.b.type == "funcDef" }
        if (firstUi != null && !hasAutoWait && !scopeHasFunc) {
            add(Level.WARN, "UI 操作前没有 auto.waitFor()", "无障碍服务未就绪时选择器会全部落空；在主程序开头加一块「等待无障碍服务就绪」", firstUi)
        }

        // L11: these params are pasted into the script as JS identifiers (`let <var> = …`,
        // `function <name>()`). A space, a dash or a leading digit is a SyntaxError that stops
        // the whole script, so flag it here instead of letting the run fail.
        items.forEach { it ->
            identifierParams[it.b.type]?.forEach { key ->
                val v = it.b.params[key]?.asText().orEmpty().trim()
                if (v.isNotEmpty() && !isJsIdentifier(v)) {
                    add(Level.ERROR, "名称不是合法标识符：$v",
                        "只能用字母、数字、_ 和 $，且不能以数字开头，也不能是 JS 关键字（如 let、if）", it)
                }
            }
        }

        items.forEach { it ->
            val b = it.b
            fun s(key: String) = b.params[key]?.asText().orEmpty()
            fun n(key: String) = b.params[key]?.asNumber() ?: 0.0
            fun hasAncestor(type: String) = it.ancestors.any { a -> a.type == type }

            when (b.type) {
                "clickNode", "longClickNode", "setTextNode" -> {
                    if (s("value").isBlank() && s("expr").isBlank()) {
                        add(Level.ERROR, "控件定位值为空", "空值会匹配到意外节点；填写文本 / desc / id", it)
                    }
                }
                "waitText" -> if (n("timeout") <= 0) {
                    add(Level.WARN, "等待控件未设置超时", "超时为 0 会立即返回，容易误判「控件已出现」", it)
                }
                "sleep" -> {
                    val ms = n("ms")
                    if (ms <= 0) add(Level.WARN, "sleep(0) 无实际等待", "改为 300~1500ms，或改用控件等待", it)
                    else if (ms > 30000) add(Level.WARN, "单次等待超过 30 秒", "长等待会拖慢整条流程，建议拆成「轮询 + 短 sleep」", it)
                    if (ms >= 3000 && hasAncestor("forRange")) {
                        add(Level.INFO, "循环内有 3 秒以上等待", "总耗时会乘上循环次数，注意超时", it)
                    }
                }
                "whileGuard" -> if (n("max") > 50) {
                    add(Level.WARN, "循环上限过大", "上限过大会让脚本长时间挂住，建议 ≤ 30", it)
                }
                "funcCall" -> if (s("name").isBlank()) add(Level.ERROR, "函数名为空", "填上要调用的函数名", it)
                "moduleCall" -> if (s("module").isBlank()) add(Level.ERROR, "未选择模块", "在参数里选择一个「我的模块」", it)
                "varSet", "varAssign" -> if (s("name").isBlank()) add(Level.ERROR, "变量名为空", "补一个合法标识符", it)
                "httpGet", "httpPostJson" -> if (!hasAncestor("tryCatch") && !hasAncestor("retry")) {
                    add(Level.WARN, "网络请求没有异常保护", "用「尝试/捕获」或「重试」包住，避免断网直接终止脚本", it)
                }
                "jdSign" -> if (items.none { x -> x.b.type == "jdEnter" }) {
                    add(Level.INFO, "签到前没有「进入领券中心」", "冷启动时签到模块可能不渲染，先执行 ① 进入领券中心", it)
                }
                "jdTaskLoop" -> {
                    if (items.none { x -> x.b.type == "jdClosePopup" }) {
                        add(Level.WARN, "任务循环缺少「关弹窗并重读」", "任务完成后会弹无文字空弹窗，直接把任务数读成 0，脚本会误收工（分享会话里的核心坑）", it)
                    }
                    if (b.children["body"].isNullOrEmpty()) {
                        add(Level.WARN, "任务循环体为空", "循环体里至少放「执行任务」或「跨 app 切回」", it)
                    }
                }
                "kitWaitFor", "kitClick" -> {
                    if (s("value").isBlank()) {
                        add(Level.ERROR, "护栏控件定位值为空", "空值无法定位目标节点；请填写要匹配的文本 / desc / id", it)
                    }
                    if (b.type == "kitWaitFor" && n("timeoutMs") <= 0) {
                        add(Level.WARN, "护栏等待超时未设置", "建议设置为 5000~15000 毫秒", it)
                    }
                }
                "kitOpenDeepLink" -> {
                    if (s("url").isBlank()) {
                        add(Level.ERROR, "深链地址为空", "请填写 openapp.jdmobile:// 或 https:// 活动深链", it)
                    }
                }
                "kitBoot" -> {
                    if (s("pkg").isBlank()) {
                        add(Level.ERROR, "护栏目标包名为空", "请填写目标应用包名（如 com.jingdong.app.mall）", it)
                    }
                }
            }

            if (b.type.startsWith("kit") && b.type != "kitBoot" && items.none { x -> x.b.type == "kitBoot" }) {
                add(Level.ERROR, "缺少「初始化护栏运行时」", "使用护栏积木前，需在脚本开头添加「初始化护栏运行时 (kitBoot)」以创建 \$kit 实例", it)
            }

            if (b.type in containerTypes && b.children.values.all { it.isEmpty() }) {
                add(Level.WARN, "容器积木是空的", "空容器不产生任何代码，拖入子积木后再运行", it)
            }
        }

        if (items.any { it.b.type == "jdTaskLoop" } && items.none { it.b.type == "jdFinishGuard" }) {
            val last = items.lastOrNull { it.b.type == "jdTaskLoop" }
            add(Level.WARN, "缺少「收工判定」", "浮层首屏可能全是抽奖/券，0 任务要下滑扫描后再收工，否则会漏做京豆任务", last)
        }

        if (items.any { it.b.type == "kitBoot" } && items.none { it.b.type == "kitFinish" }) {
            val lastKit = items.lastOrNull { it.b.type.startsWith("kit") }
            add(Level.WARN, "护栏流程缺少「护栏收尾汇总」", "建议在末尾添加「护栏收尾汇总 (kitFinish)」以输出动作统计与 WARN/SUCCESS 判定", lastKit)
        }

        val seenClean = mutableMapOf<String, String>() // cleaned -> original name
        project.modules.forEach { m ->
            val name = m.name.trim()
            val cleaned = cn.dumate.a6studio.catalog.Js.moduleName(name)
            if (name.isEmpty()) add(Level.ERROR, "模块名为空", "模块名用于生成函数名，不能为空")
            else {
                val prev = seenClean.put(cleaned, name)
                if (prev != null) add(Level.ERROR, "模块名冲突：$prev / $name", "两者清洗后都变成 $cleaned，会生成同名函数互相覆盖")
            }
            if (m.children["body"].isNullOrEmpty()) add(Level.INFO, "模块「$name」是空的", "空模块可以占位，但调用它什么也不做")
        }

        if (project.tests.isEmpty()) {
            add(Level.INFO, "还没有测试用例", "「测试」页可以给关键流程加断言，改积木后先跑测试再上真机")
        }

        return issues.sortedBy { it.level.ordinal }
    }

    fun fromRun(run: RunResult?): List<Issue> {
        if (run == null) return emptyList()
        val issues = mutableListOf<Issue>()
        if (run.status == "error" && run.error != null) {
            issues += Issue(Level.ERROR, "运行抛出异常", run.error, "运行日志")
        }
        if (run.status == "timeout") {
            issues += Issue(Level.ERROR, "运行超时被自动停止", run.error ?: "脚本可能卡在死循环里", "运行日志")
        }
        val logs = run.logs
        fun count(pattern: Regex) = logs.count { pattern.containsMatchIn(it.msg) }

        count(Regex("等待控件超时")).let { miss ->
            if (miss > 0) issues += Issue(Level.WARN, "有 $miss 次控件等待超时", "检查定位值是否随文案变化；入口按钮文案会从「立即领奖」变成「更多任务」", "运行日志")
        }
        count(Regex("循环保护触发")).let { guard ->
            if (guard > 0) issues += Issue(Level.ERROR, "循环保护触发 $guard 次", "循环条件可能永远为真，或没在成功分支里更新条件", "运行日志")
        }
        count(Regex("断言失败")).let { fails ->
            if (fails > 0) issues += Issue(Level.ERROR, "断言失败 $fails 次", "测试用例里的关键假设不成立", "运行日志")
        }

        val hasFinish = logs.any { Regex("收工").containsMatchIn(it.msg) }
        val hasClose = logs.any { Regex("关闭遮挡弹窗|重读任务数").containsMatchIn(it.msg) }
        if (hasFinish && !hasClose) {
            issues += Issue(Level.WARN, "疑似「0 任务」误判收工", "收工前没有关闭弹窗/重读记录，补一块「关遮挡弹窗并重读」", "运行日志")
        }
        if (run.virtualMs > 120_000) {
            issues += Issue(Level.INFO, "模拟耗时 ${run.virtualMs / 1000} 秒", "真机上会更慢，建议精简等待或改成控件等待", "运行日志")
        }
        return issues
    }

    /** Human-readable block label used in diagnostics and the audit panel. */
    private val jsIdentifier = Regex("^[A-Za-z_$][A-Za-z0-9_$]*$")
    private val jsReserved = setOf(
        "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete", "do",
        "else", "export", "extends", "finally", "for", "function", "if", "import", "in", "instanceof",
        "new", "return", "super", "switch", "this", "throw", "try", "typeof", "var", "void", "while",
        "with", "yield", "let", "static", "enum", "await", "null", "true", "false"
    )

    /** ASCII-only on purpose: Rhino's ES6 mode rejects some Unicode identifiers other engines accept. */
    fun isJsIdentifier(text: String): Boolean = jsIdentifier.matches(text) && text !in jsReserved

    fun blockLabel(block: Block): String {
        val def = Catalog.byType(block.type) ?: return block.type
        return Catalog.cleanName(def)
    }
}