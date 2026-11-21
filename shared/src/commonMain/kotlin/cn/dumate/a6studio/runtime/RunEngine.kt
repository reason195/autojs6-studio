package cn.dumate.a6studio.runtime

import cn.dumate.a6studio.model.Project

/**
 * Runs generated scripts. The concrete implementation is Rhino-backed and therefore lives in
 * the JVM/Android source set; the UI only depends on this interface.
 */
interface RunEngine {
    fun run(project: Project, sink: SimSink? = null): RunResult
    fun runTests(project: Project, onlyIds: List<String>? = null, sink: SimSink? = null): List<TestResult>
}