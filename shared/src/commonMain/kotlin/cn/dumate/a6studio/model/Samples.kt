package cn.dumate.a6studio.model

import cn.dumate.a6studio.config.AppConfig

/** Built-in sample project: the JD coupon-centre flow from the original web build. */
object Samples {

    fun emptyProject(name: String = "未命名项目"): Project = Project(
        name = name,
        root = Project.rootBlock(),
        modules = emptyList(),
        tests = emptyList(),
        bridge = BridgeSettings(
            url = AppConfig.devices.firstOrNull()?.url ?: "",
            devices = AppConfig.devices
        ),
        createdAt = currentTimeMillis()
    )

    fun exampleProject(): Project {
        val body = mutableListOf<Block>()
        fun add(type: String, overrides: Map<String, ParamValue> = emptyMap(), kids: Map<String, List<Block>> = emptyMap()): Block {
            val b = newBlock(type, overrides).let { if (kids.isEmpty()) it else it.copy(children = it.children + kids) }
            body += b
            return b
        }

        add("comment", mapOf("text" to ParamValue.Text("京东领券中心主流程：进入 → 签到 → 打开浮层 → 逐任务执行 → 关弹窗 → 收工判定")))
        add("keepScreenOn", mapOf("ms" to ParamValue.Num(1_800_000.0)))
        add("consoleShow")
        add("jdEnter")
        add("jdSign")
        add("jdOpenPanel")
        add(
            "jdTaskLoop",
            mapOf("taskVar" to ParamValue.Text("task"), "max" to ParamValue.Num(8.0)),
            mapOf(
                "body" to listOf(
                    newBlock("jdDoTask", mapOf("taskVar" to ParamValue.Text("task"), "stay" to ParamValue.Num(7000.0)))
                )
            )
        )
        add("jdClosePopup")
        add("jdFinishGuard", mapOf("rounds" to ParamValue.Num(12.0), "zero" to ParamValue.Num(3.0)))
        add("logExpr", mapOf("expr" to ParamValue.Text("\"流程结束\"")))

        val module = StudioModule(
            id = "mod_sample",
            name = "快速签到",
            desc = "进入领券中心并完成签到，返回布尔值",
            args = "pkg",
            children = mapOf(
                "body" to listOf(
                    newBlock("jdEnter"),
                    newBlock("jdSign"),
                    newBlock("assertBlock", mapOf("cond" to ParamValue.Text("true"), "msg" to ParamValue.Text("签到标志物")))
                )
            )
        )

        val tests = listOf(
            TestCase(
                "t1", "签到按钮：点击后出现「更多任务」",
                """
                ${'$'}setScreen('jd.coupon');
                var btn = text('天天领豆').findOne(1000);
                assert(btn, '应能找到签到按钮');
                btn.click();
                assert(desc('更多任务').exists(), '点击后入口文案应变为「更多任务」');
                """.trimIndent()
            ),
            TestCase(
                "t2", "主流程：能跑完并输出收工判定",
                """
                ${'$'}setScreen('jd.mall');
                ${'$'}run();
                var logs = ${'$'}log().join('\n');
                assert(logs.indexOf('本轮处理任务') >= 0, '应处理到京豆任务');
                assert(logs.indexOf('收工') >= 0, '应输出收工判定');
                """.trimIndent()
            ),
            TestCase(
                "t3", "0 任务时不误判：下滑扫描后收工",
                """
                ${'$'}setScreen('jd.panel');
                ${'$'}state().tasks = [];
                ${'$'}run();
                assert(${'$'}log().join('\n').indexOf('收工') >= 0, '空任务应经扫描后收工');
                """.trimIndent()
            )
        )

        return emptyProject("京东领券中心 · 实战示例").copy(
            root = Project.rootBlock().copy(children = mapOf("body" to body)),
            modules = listOf(module),
            tests = tests
        )
    }
}