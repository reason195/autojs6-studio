package cn.dumate.a6studio

import cn.dumate.a6studio.model.Samples
import cn.dumate.a6studio.runtime.Runner
import org.mozilla.javascript.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * Rhino keeps one *thread-local* Context. If any code path enters without a matching exit, the
 * thread stays entered forever and every later run reuses the leaked context — including its
 * factory, so a new run's stop switch and deadline are never installed.
 */
class ContextLeakTest {

    private fun onFreshThread(block: () -> Unit) {
        val done = CountDownLatch(1)
        val err = arrayOfNulls<Throwable>(1)
        Thread {
            try { block() } catch (t: Throwable) { err[0] = t } finally { done.countDown() }
        }.also { it.isDaemon = true }.start()
        check(done.await(60, TimeUnit.SECONDS)) { "worker thread did not finish" }
        err[0]?.let { throw it }
    }

    @Test
    fun `running the sample project leaves no Context entered`() {
        onFreshThread {
            Runner.run(Samples.exampleProject())
            val leaked = Context.getCurrentContext()
            assertNull(leaked, "Context leaked after run: enterCount still > 0")
        }
    }

    @Test
    fun `running tests leaves no Context entered`() {
        onFreshThread {
            Runner.runTests(Samples.exampleProject())
            val leaked = Context.getCurrentContext()
            assertNull(leaked, "Context leaked after runTests")
        }
    }
}
