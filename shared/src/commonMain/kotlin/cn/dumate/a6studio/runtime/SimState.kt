package cn.dumate.a6studio.runtime

/**
 * Device-side state machine for the built-in simulator.
 *
 * Mirrors the JD coupon-centre flow that the sample project walks:
 * mall → coupon (sign-in flips the entry label) → panel → task popup → back …
 */
class SimState {
    data class Task(val id: String, val text: String, val desc: String, val crossApp: Boolean)

    /**
     * Current simulated screen. The package/activity follow it here instead of in
     * `buildNodes()`: with `sink == null` (unit tests, CLI) `buildNodes` never runs, so
     * `launchPackage("com.jd.jrapp"); currentPackage()` used to answer the old package.
     */
    var screen: String = "jd.mall"
        set(value) {
            field = value
            packageName = pkgMap[value] ?: packageName
            activity = if (value == "jd.finance") "JrappMainActivity" else "BabelActivity"
        }
    var signed: Boolean = false
    var packageName: String = "com.jingdong.app.mall"
    var activity: String = "MainActivity"
    var clip: String = ""
    var clock: Long = 0
    val tasks: MutableList<Task> = seedTasks().toMutableList()

    val logs = mutableListOf<LogEntry>()

    private val pkgMap = mapOf(
        "jd.mall" to "com.jingdong.app.mall",
        "jd.coupon" to "com.jingdong.app.mall",
        "jd.panel" to "com.jingdong.app.mall",
        "jd.popup" to "com.jingdong.app.mall",
        "jd.finance" to "com.jd.jrapp"
    )

    fun add(level: String, msg: String) {
        logs += LogEntry(level, msg, clock)
        if (logs.size > 2000) logs.removeAt(0)
    }

    fun reset() {
        screen = "jd.mall"
        signed = false
        tasks.clear()
        tasks += seedTasks()
        clip = ""
        clock = 0
        logs.clear()
    }

    /** Node list for the current screen, with an action key for each clickable row. */
    fun buildNodes(): List<SimNode> {
        val out = mutableListOf<SimNode>()
        var i = 0
        fun push(
            text: String = "",
            desc: String = "",
            id: String = "",
            className: String = "android.widget.TextView",
            clickable: Boolean = false,
            editable: Boolean = true
        ): SimNode {
            val top = 110 + i * 82
            val node = SimNode(
                text = text, desc = desc, id = id, className = className,
                clickable = clickable, editable = editable,
                bounds = intArrayOf(40, top, 1040, top + 70)
            )
            i++
            out += node
            return node
        }

        when (screen) {
            "jd.mall" -> {
                push("京东"); push("首页", clickable = true)
                push("我的", clickable = true); push("购物车", clickable = true)
            }
            "jd.coupon" -> {
                push("领券中心")
                if (signed) {
                    push("更多任务", desc = "更多任务", id = "coupon-left-entry", clickable = true)
                } else {
                    push("天天领豆", id = "coupon-sign", clickable = true)
                }
                push("去逛逛", clickable = true)
            }
            "jd.panel" -> {
                push("任务浮层")
                if (tasks.isNotEmpty()) tasks.forEach { t ->
                    push(t.text, desc = t.desc, clickable = true)
                } else {
                    push("已完成", desc = "今日任务已完成")
                }
            }
            "jd.popup" -> push("", desc = "关闭", id = "popup-close", clickable = true)
            "jd.finance" -> {
                push("京东金融"); push("去完成", clickable = true)
            }
        }
        return out
    }

    fun stateView(width: Int, height: Int) = SimStateView(
        screen = screen,
        packageName = packageName,
        signed = signed,
        taskCount = tasks.size,
        clip = clip,
        deviceWidth = width,
        deviceHeight = height
    )

    /** Runs the action attached to a node label; returns true if the screen changed. */
    fun nodeClick(node: SimNode): Boolean {
        when {
            node.id == "coupon-sign" -> {
                signed = true
                add("info", "签到成功，入口文案即将变为「更多任务」")
                return true
            }
            node.id == "coupon-left-entry" -> {
                screen = "jd.panel"
                add("info", "打开任务浮层")
                return true
            }
            node.id == "popup-close" -> {
                screen = "jd.panel"
                add("info", "关闭遮挡弹窗")
                return true
            }
            screen == "jd.panel" && node.desc.startsWith("得") -> {
                val t = tasks.firstOrNull { it.text == node.text && it.desc == node.desc } ?: return false
                tasks.remove(t)
                screen = "jd.popup"
                add("info", "完成任务：${t.text}（${t.desc}）")
                return true
            }
        }
        return false
    }

    fun back() {
        val map = mapOf(
            "jd.popup" to "jd.panel", "jd.panel" to "jd.coupon",
            "jd.finance" to "jd.panel", "jd.coupon" to "jd.mall"
        )
        val next = map[screen] ?: screen
        add("info", "按返回键（$screen → $next）")
        screen = next
    }

    fun home() {
        add("info", "回到桌面")
        screen = "jd.mall"
    }

    private fun seedTasks(): List<Task> = listOf(
        Task("t0", "浏览会场", "得10京豆", false),
        Task("t1", "逛省钱频道", "得10京豆", true),
        Task("t2", "加入购物车", "得15京豆", false)
    )
}