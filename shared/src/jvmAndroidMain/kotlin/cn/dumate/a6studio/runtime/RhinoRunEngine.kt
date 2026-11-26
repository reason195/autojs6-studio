package cn.dumate.a6studio.runtime

import cn.dumate.a6studio.model.Project

/** Rhino-backed [RunEngine], binding the shared UI to the simulator. */
object RhinoRunEngine : RunEngine {
    override fun run(project: Project, sink: SimSink?, control: RunControl): RunResult =
        Runner.run(project, sink, control)

    override fun runTests(project: Project, onlyIds: List<String>?, sink: SimSink?, control: RunControl): List<TestResult> =
        Runner.runTests(project, onlyIds, sink, control)
}
