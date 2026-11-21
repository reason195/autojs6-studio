package cn.dumate.a6studio.runtime

import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/**
 * Runs one test case against a fresh simulated device. The case gets the normal AutoJs6 globals
 * plus `$run()` (replays the project's main program in the same device) and `$state()`.
 */
class ScriptableHarness(private val sim: SimRuntime, private val mainCode: String) {

    private var scope: Scriptable? = null

    fun install() {
        val ctx = Context.enter()
        try {
            ctx.optimizationLevel = -1
            ctx.languageVersion = Context.VERSION_ES6
            val sc = sim.newScope(ctx)
            scope = sc
            val runFn = object : BaseFunction() {
                override fun call(cx: Context, s: Scriptable, thisObj: Scriptable, args: Array<Any>): Any? {
                    // `$state().tasks = []` is written before `$run()`; push it into the sim first
                    // so a "0 tasks" test really starts with an empty panel.
                    sim.syncFromStateObject()
                    val (status, error) = sim.execute(mainCode, s)
                    if (status == "error" && error != null) throw RuntimeException(error)
                    return null
                }
            }
            ScriptableObject.putProperty(sc as ScriptableObject, "\$run", runFn)
        } finally {
            Context.exit()
        }
    }

    fun executeTest(source: String): Pair<String, String?> {
        val sc = scope ?: return "error" to "测试运行器未初始化"
        return sim.execute(source, sc)
    }
}
