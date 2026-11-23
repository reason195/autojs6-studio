package cn.dumate.a6studio

import cn.dumate.a6studio.catalog.Catalog
import cn.dumate.a6studio.catalog.CodeGen
import cn.dumate.a6studio.model.Templates
import cn.dumate.a6studio.runtime.Aj6KitRuntime
import org.mozilla.javascript.Context
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Aj6KitIntegrationTest {

    @Test
    fun aj6KitRuntimeParsesAndBootsInRhino() {
        val cx = Context.enter()
        try {
            cx.isInterpretedMode = true
            cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects()
            cx.evaluateString(scope, Aj6KitRuntime.SOURCE, "aj6_kit.js", 1, null)
            val hasBoot = cx.evaluateString(scope, "typeof Kit === 'object' && typeof Kit.boot === 'function'", "check", 1, null)
            assertEquals(true, hasBoot)
        } finally {
            Context.exit()
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
            assertTrue(code.contains("Settlement|Checkout|ConfirmOrder"), "$id 应包含危险页拦截正则")

            val cx = Context.enter()
            try {
                cx.isInterpretedMode = true
                cx.languageVersion = Context.VERSION_ES6
                val scope = cx.initStandardObjects()
                cx.evaluateString(scope, "function exit(){}; var auto={waitFor:function(){}};", "stub", 1, null)
                // 仅检查语法解析（用 Function 构造器避免实际执行阻塞循环）
                cx.compileString(code, "$id.js", 1, null)
            } finally {
                Context.exit()
            }
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
            val res = cn.dumate.a6studio.runtime.Runner.run(proj)
            val allMsgs = res.logs.joinToString(" | ") { "[${it.level}] ${it.msg}" }
            assertTrue(res.status == "finished" || res.status == "exited", "$id STATUS=${res.status}, ERR=${res.error}")
            assertTrue(
                allMsgs.contains("护栏点击动作") || allMsgs.contains("进入频道"),
                "$id 在模拟器中应命中虚拟节点并触发点击: $allMsgs"
            )
        }
    }
}
