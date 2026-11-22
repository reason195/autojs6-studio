package cn.dumate.a6studio

import cn.dumate.a6studio.runtime.SimRuntime
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * The simulator executes scripts that can come from anywhere (an imported `.js`, a pasted raw
 * block), so it must not hand them the JVM. Measured before the ClassShutter was added:
 * `java.lang.System.getProperty("user.home")` returned a live Java object, which puts
 * `Runtime.exec` and arbitrary file access one step away — on the user's desktop.
 */
class SimSandboxTest {

    private fun run(src: String): Triple<String, String?, String> {
        val sim = SimRuntime()
        val (status, error) = sim.execute(src)
        return Triple(status, error, sim.state.logs.joinToString("\n") { it.msg })
    }

    @Test
    fun `java classes cannot be called`() {
        val (status, error, _) = run("""log(String(java.lang.System.getProperty("user.home")));""")
        assertEquals("error", status)
        assertContains(error!!, "getProperty")
    }

    @Test
    fun `runtime exec is out of reach`() {
        val (status, _, _) = run("""java.lang.Runtime.getRuntime().exec("cmd /c echo hi");""")
        assertEquals("error", status)
    }

    @Test
    fun `constructing a java class through Packages is refused`() {
        // Packages resolution returns a package object, never a constructible class.
        val (status, _, _) = run("""var f = new Packages.java.io.File("x");""")
        assertEquals("error", status)
    }

    @Test
    fun `importClass is a no-op that explains itself`() {
        val (status, _, logs) = run("""importClass(java.io.File); log("still running");""")
        assertEquals("finished", status)
        assertContains(logs, "Java 互操作")
        assertContains(logs, "still running")
    }

    @Test
    fun `plain scripts and the simulated device API still work`() {
        val (status, error, logs) = run(
            """
            let n = 1;
            for (let i = 0; i < 3; i++) { n += i; }
            log("n=" + n);
            toast("ok");
            sleep(500);
            """.trimIndent()
        )
        assertEquals("finished", status, "error: $error")
        assertContains(logs, "n=4")
    }
}
