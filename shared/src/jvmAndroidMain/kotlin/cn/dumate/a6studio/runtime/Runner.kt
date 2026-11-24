package cn.dumate.a6studio.runtime

import cn.dumate.a6studio.catalog.CodeGen
import cn.dumate.a6studio.model.Project

/**
 * Facade over [SimRuntime]: runs a project (or single test cases) against the mock device.
 * Kept in `jvmAndroidMain` because Rhino is a JVM dependency.
 */
object Runner {

    fun run(project: Project, sink: SimSink? = null, control: RunControl = RunControl()): RunResult {
        val code = CodeGen.code(project)
        val sim = SimRuntime(sink, control)
        try {
            val (status, error) = sim.execute(code)
            return RunResult(
                status = status,
                error = error,
                logs = synchronized(sim.state.logs) { sim.state.logs.toList() },
                code = code,
                virtualMs = sim.state.clock,
                sim = sim.state.stateView(1080, 2400)
            )
        } finally {
            sim.close()
        }
    }

    fun runTests(
        project: Project,
        only: List<String>? = null,
        sink: SimSink? = null,
        control: RunControl = RunControl()
    ): List<TestResult> {
        val code = CodeGen.code(project)
        val tests = if (only == null) project.tests else project.tests.filter { it.id in only }
        return tests.map { t ->
            if (control.cancelled) {
                return@map TestResult(t.id, t.name, ok = false, error = SimAbort.STOPPED_MESSAGE, logs = emptyList())
            }
            val sim = SimRuntime(sink, control)
            try {
                // $run() replays the main program inside the same simulated device.
                val harness = ScriptableHarness(sim, code)
                harness.install()
                val (status, error) = harness.executeTest(t.code)
                TestResult(
                    id = t.id,
                    name = t.name,
                    ok = status == "finished" || status == "exited",
                    error = error ?: "",
                    logs = synchronized(sim.state.logs) { sim.state.logs.toList() }
                )
            } finally {
                sim.close()
            }
        }
    }
}
