package cn.dumate.a6studio

import cn.dumate.a6studio.catalog.Catalog
import cn.dumate.a6studio.catalog.CodeGen
import cn.dumate.a6studio.model.Samples
import cn.dumate.a6studio.model.Templates
import cn.dumate.a6studio.model.newBlock
import cn.dumate.a6studio.runtime.Aj6KitRuntime
import cn.dumate.a6studio.runtime.Runner
import cn.dumate.a6studio.runtime.SimRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Aj6KitIntegrationTest {

    @Test
    fun aj6KitRuntimeParsesAndBootsInRhino() {
        val rt = SimRuntime()
        try {
            val checkScript = Aj6KitRuntime.SOURCE + "\n" +
                "if (typeof Kit !== 'object' || typeof Kit.boot !== 'function') { throw new Error('Kit.boot missing'); }"
            val (status, err) = rt.execute(checkScript)
            assertEquals("finished", status, "Aj6KitRuntime 应在 SimRuntime 沙箱中正常加载: $err")
        } finally {
            rt.close()
        }
    }

    @Test
    fun nonKitProjectDoesNotInjectAj6Kit() {
        val hello = Templates.byId("hello")!!.build()
        val code = CodeGen.code(hello)
        assertFalse(code.contains("var Kit = (function ()"), "普通模板不应注入 aj6_kit 运行时")
    }

    @Test
    fun kitTemplatesInjectRuntimeAndParseCleanly() {
        val kitIds = listOf("jdServiceKit", "jdJoyKit", "jdChannelKit")
        for (id in kitIds) {
            val tpl = Templates.byId(id)!!
            val proj = tpl.build()
            val code = CodeGen.code(proj)
            assertTrue(code.contains("var Kit = (function ()"), "$id 应注入 aj6_kit 运行时")
            assertTrue(code.contains("Kit.boot("), "$id 应包含 Kit.boot 护栏初始化")
            assertTrue(code.contains("policy: {"), "$id 应将护栏配置写入 policy 对象")
            assertTrue(code.contains("Settlement|Checkout|ConfirmOrder"), "$id 应包含危险页拦截正则")

            val rt = SimRuntime()
            try {
                val (status, err) = rt.execute(code)
                assertTrue(status == "finished" || status == "exited", "$id 应在 SimRuntime 沙箱中跑通: status=$status, err=$err")
            } finally {
                rt.close()
            }
        }
    }

    @Test
    fun kitBootPolicyReachesRuntime() {
        val proj = Samples.emptyProject().let {
            it.copy(
                root = it.root.copy(
                    children = mapOf(
                        "body" to listOf(
                            newBlock("kitBoot"),
                            newBlock("kitGuard")
                        )
                    )
                )
            )
        }
        val code = CodeGen.code(proj)
        val rt = SimRuntime()
        try {
            rt.state.activity = "ConfirmOrderActivity"
            val (status, err) = rt.execute(code)
            assertEquals("finished", status, "执行应完成: $err")
            val logs = rt.state.logs.joinToString("\n") { it.msg }
            assertTrue(logs.contains("安全阀：危险页面 ConfirmOrderActivity"), "应触发危险页面安全阀日志，实际日志:\n$logs")
            assertTrue(logs.contains("按返回键"), "应执行 back 退出危险页面，实际日志:\n$logs")
        } finally {
            rt.close()
        }
    }

    @Test
    fun waitCountdownUsesConfiguredDwell() {
        val proj = Samples.emptyProject().let {
            it.copy(
                root = it.root.copy(
                    children = mapOf(
                        "body" to listOf(
                            newBlock("kitBoot"),
                            newBlock(
                                "kitWaitCountdown",
                                mapOf(
                                    "minDwellSec" to cn.dumate.a6studio.model.ParamValue.Num(5.0),
                                    "graceSec" to cn.dumate.a6studio.model.ParamValue.Num(1.0)
                                )
                            ),
                            newBlock("kitFinish")
                        )
                    )
                )
            )
        }
        val code = CodeGen.code(proj)
        assertTrue(code.contains("fallbackDwellMs: 5000"), "应生成 fallbackDwellMs: 5000，实际:\n$code")
        assertTrue(code.contains("graceMs: 1000"), "应生成 graceMs: 1000，实际:\n$code")
        assertTrue(code.contains("if (!_cd) { \$kit.backToBean(); }"), "未命中返回按钮时应调用 backToBean()")
        assertFalse(code.contains("\$kit.back();"), "不应在 waitCountdown 之后无条件追加 \$kit.back()")

        val rt = SimRuntime()
        try {
            val (status, err) = rt.execute(code)
            assertEquals("finished", status, "执行应完成: $err")
            assertTrue(rt.state.clock >= 5000L, "虚拟时钟应至少推进配置的 5000ms，实际 clock=${rt.state.clock}")
        } finally {
            rt.close()
        }
    }

    @Test
    fun allKitBlocksAreRegisteredInCatalog() {
        val expected = listOf(
            "kitBoot", "kitLaunch", "kitOpenDeepLink", "kitGuard",
            "kitWaitFor", "kitClick", "kitWaitCountdown", "kitFinish"
        )
        for (type in expected) {
            assertTrue(Catalog.byType(type) != null, "Catalog 缺少护栏积木: $type")
        }
    }

    @Test
    fun kitTemplatesExecuteInSim() {
        for (id in listOf("jdServiceKit", "jdJoyKit", "jdChannelKit", "jd99BaoyouKit", "jdMiaoshaKit")) {
            val proj = Templates.byId(id)!!.build()
            val res = Runner.run(proj)
            val allMsgs = res.logs.joinToString(" | ") { "[${it.level}] ${it.msg}" }
            assertTrue(res.status == "finished" || res.status == "exited", "$id STATUS=${res.status}, ERR=${res.error}")
            assertTrue(
                allMsgs.contains("护栏点击动作") || allMsgs.contains("进入频道"),
                "$id 在模拟器中应命中虚拟节点并触发点击: $allMsgs"
            )
        }
    }
}
