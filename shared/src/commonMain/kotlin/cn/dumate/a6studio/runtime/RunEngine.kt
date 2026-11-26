package cn.dumate.a6studio.runtime

import cn.dumate.a6studio.model.Project
import kotlin.concurrent.Volatile

/**
 * Runs generated scripts. The concrete implementation is Rhino-backed and therefore lives in
 * the JVM/Android source set; the UI only depends on this interface.
 *
 * Both calls block until the script ends, so callers must invoke them off the UI thread.
 * [control] lets the caller stop a run and caps its wall-clock time.
 */
interface RunEngine {
    fun run(project: Project, sink: SimSink? = null, control: RunControl = RunControl()): RunResult
    fun runTests(
        project: Project,
        onlyIds: List<String>? = null,
        sink: SimSink? = null,
        control: RunControl = RunControl()
    ): List<TestResult>
}

/**
 * Stop switch and time budget shared between the UI and a running simulation. The engine polls
 * it periodically, so [cancel] takes effect within a few milliseconds even inside `while (true) {}`.
 *
 * [wallTimeoutMs] is real time per script (per test case for test runs); virtual `sleep()` time
 * does not count toward it.
 */
class RunControl(val wallTimeoutMs: Long = DEFAULT_WALL_TIMEOUT_MS) {
    @Volatile
    var cancelled: Boolean = false
        private set

    fun cancel() { cancelled = true }

    companion object {
        const val DEFAULT_WALL_TIMEOUT_MS = 20_000L
    }
}
