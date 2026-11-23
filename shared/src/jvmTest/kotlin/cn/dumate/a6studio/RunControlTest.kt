package cn.dumate.a6studio

import cn.dumate.a6studio.model.Samples
import cn.dumate.a6studio.model.TestCase
import cn.dumate.a6studio.runtime.RunControl
import cn.dumate.a6studio.runtime.Runner
import cn.dumate.a6studio.runtime.ScriptableHarness
import cn.dumate.a6studio.runtime.SimRuntime
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunControlTest {

    private fun timed(block: () -> Unit): Long {
        val t0 = System.nanoTime()
        block()
        return (System.nanoTime() - t0) / 1_000_000
    }

    @Test
    fun `pure JS infinite loop hits the wall-clock budget`() {
        val sim = SimRuntime(control = RunControl(wallTimeoutMs = 300))
        var result: Pair<String, String?>? = null
        val ms = timed { result = sim.execute("while (true) {}") }
        assertEquals("timeout", result!!.first)
        assertContains(result!!.second!!, "死循环")
        assertTrue(ms < 3_000, "took $ms ms")
    }

    @Test
    fun `script cannot swallow the abort with try-catch or finally`() {
        val src = """
            while (true) {
              try { while (true) {} } catch (e) { log('caught'); } finally { while (true) {} }
            }
        """.trimIndent()
        val sim = SimRuntime(control = RunControl(wallTimeoutMs = 300))
        val (status, _) = sim.execute(src)
        assertEquals("timeout", status)
        assertFalse(sim.state.logs.any { it.msg.contains("caught") })
    }

    @Test
    fun `virtual sleep loops are stopped too`() {
        val sim = SimRuntime(control = RunControl(wallTimeoutMs = 300))
        assertEquals("timeout", sim.execute("while (true) { sleep(1000); }").first)
    }

    @Test
    fun `cancel stops a running script from another thread`() {
        val control = RunControl(wallTimeoutMs = 0) // no budget: only cancel can end it
        val sim = SimRuntime(control = control)
        var status = ""
        val worker = thread { status = sim.execute("for (;;) {}").first }
        Thread.sleep(150)
        val ms = timed {
            control.cancel()
            worker.join(3_000)
        }
        assertFalse(worker.isAlive, "script still running after cancel")
        assertEquals("stopped", status)
        assertTrue(ms < 1_000, "stop took $ms ms")
    }

    @Test
    fun `timeout inside nested run fails the test case instead of continuing`() {
        val sim = SimRuntime(control = RunControl(wallTimeoutMs = 300))
        val harness = ScriptableHarness(sim, "while (true) {}")
        harness.install()
        val (status, error) = harness.executeTest("try { \$run(); } catch (e) {} log('after');")
        assertEquals("timeout", status)
        assertContains(error!!, "自动停止")
        assertFalse(sim.state.logs.any { it.msg == "after" })
    }

    @Test
    fun `spinning test case is reported as failed`() {
        val project = Samples.emptyProject("t").copy(tests = listOf(TestCase("x", "卡死", "while (true) {}")))
        val r = Runner.runTests(project, control = RunControl(wallTimeoutMs = 300)).single()
        assertFalse(r.ok)
        assertContains(r.error, "自动停止")
    }

    @Test
    fun `cancelled control skips remaining tests`() {
        val control = RunControl().also { it.cancel() }
        val results = Runner.runTests(Samples.exampleProject(), control = control)
        assertTrue(results.isNotEmpty())
        assertTrue(results.all { !it.ok && it.error.contains("停止") })
    }

    @Test
    fun `normal sample run is unaffected by the budget`() {
        assertEquals("finished", Runner.run(Samples.exampleProject()).status)
    }

    @Test
    fun `screen change updates package and activity without rendering`() {
        // sink == null 时 publishRender 不跑 buildNodes；旧实现把 packageName 的同步绑在
        // buildNodes 里，于是 launchPackage("com.jd.jrapp"); currentPackage() 返回旧包名。
        val sim = SimRuntime()
        sim.state.screen = "jd.finance"
        assertEquals("com.jd.jrapp", sim.state.packageName)
        assertEquals("JrappMainActivity", sim.state.activity)
        sim.state.screen = "jd.mall"
        assertEquals("com.jingdong.app.mall", sim.state.packageName)
    }
}
