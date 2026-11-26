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
 *
 * Since switching to initSafeStandardObjects + setClassShutter{false} the sandbox is a true
 * whitelist: only the stubs we install (selectors, device, log, sleep, …) are reachable.
 */
class SimSandboxTest {

    private fun run(src: String): Triple<String, String?, String> {
        val sim = SimRuntime()
        val (status, error) = sim.execute(src)
        return Triple(status, error, sim.state.logs.joinToString("\n") { it.msg })
    }

    // ── original blacklist tests (still required) ──────────────────────────

    @Test
    fun `java classes cannot be called`() {
        val (status, error, _) = run("""log(String(java.lang.System.getProperty("user.home")));""")
        assertEquals("error", status)
        // `java` is now an inert placeholder: calling anything under it is a TypeError,
        // and the home directory must never show up in the message.
        assertContains(error!!, "getProperty")
        kotlin.test.assertFalse(error.contains(System.getProperty("user.home")), error)
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

    // ── whitelist escape tests (new) ───────────────────────────────────────

    @Test
    fun `Packages kotlinx is blocked`() {
        // Before whitelist this reached kotlinx.io.files.FileSystemJvmKt via Packages.
        val (status, _, _) = run(
            """var fs = Packages.kotlinx.io.files.FileSystemJvmKt.getSystemFileSystem();"""
        )
        assertEquals("error", status)
    }

    @Test
    fun `Packages io ktor is blocked`() {
        // Names under Packages resolve to inert placeholders; using one as a class must fail.
        val (status, _, _) = run(
            """var c = new Packages.io.ktor.client.HttpClient();"""
        )
        assertEquals("error", status)
    }

    @Test
    fun `Packages cn dumate is blocked`() {
        // Our own classes are on the classpath — must not be reachable.
        val (status, _, _) = run(
            """var r = new Packages.cn.dumate.a6studio.runtime.SimRuntime();"""
        )
        assertEquals("error", status)
    }

    @Test
    fun `Packages placeholders carry no Java behind them`() {
        val (status, error, logs) = run(
            """log(typeof Packages.cn.dumate.a6studio.runtime.SimRuntime); log(String(java.lang.Runtime));"""
        )
        assertEquals("finished", status, "error: $error")
        assertContains(logs, "object")
        assertContains(logs, "[JavaPackage java.lang.Runtime]")
    }

    @Test
    fun `Packages android is blocked`() {
        // On Android builds android.* is live; on desktop it may not exist but the shutter
        // must refuse before class loading even tries.
        val (status, _, _) = run(
            """var a = Packages.android.app.ActivityThread.currentApplication();"""
        )
        assertEquals("error", status)
    }

    @Test
    fun `dalvik DexClassLoader is blocked`() {
        val (status, _, _) = run(
            """var d = new Packages.dalvik.system.DexClassLoader("x","y","z",null);"""
        )
        assertEquals("error", status)
    }

    @Test
    fun `getClass is not available (LiveConnect removed)`() {
        // initSafeStandardObjects does not register getClass/JavaAdapter.
        val (status, _, _) = run(
            """"hello".getClass().forName("java.lang.Runtime").getMethod("exec","cmd");"""
        )
        assertEquals("error", status)
    }

    @Test
    fun `JavaAdapter is not available (LiveConnect removed)`() {
        val (status, _, _) = run(
            """var r = new JavaAdapter(Packages.java.lang.Runnable, { run: function(){} });"""
        )
        assertEquals("error", status)
    }

    @Test
    fun `java lang Class forName is blocked`() {
        val (status, _, _) = run(
            """java.lang.Class.forName("java.lang.Runtime");"""
        )
        assertEquals("error", status)
    }

    @Test
    fun `shell is stubbed so generated scripts do not crash as ReferenceError`() {
        val (status, error, logs) = run("""log(shell("echo hi"));""")
        assertEquals("finished", status, "error: $error")
        assertContains(logs, "模拟器不执行 shell")
    }
}
