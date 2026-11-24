package cn.dumate.a6studio.runtime

import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/** Thrown by the simulated `exit()`. */
class SimExit : RuntimeException("exit")

/**
 * Aborts a simulation (user stop or time budget). Deliberately an [Error], not an exception:
 * Rhino runs neither JS `catch` nor `finally` for Java errors, so a script cannot swallow it
 * with `try {} catch {}` or keep looping inside a `finally`.
 */
class SimAbort(val status: String, message: String) : Error(message) {
    companion object {
        const val STOPPED = "stopped"
        const val TIMEOUT = "timeout"
        const val STOPPED_MESSAGE = "已手动停止"
        fun timeoutMessage(ms: Long) =
            "运行超过 ${ms / 1000} 秒被自动停止：脚本可能卡在死循环里（模拟的 sleep 不计时）"
    }
}

/**
 * Wraps a [CharSequence] so Java regex backtracking cannot run forever: [java.util.regex]
 * calls [charAt] in its hot loop, and we piggyback [checkLimits] every [CHECK_EVERY] chars.
 * This covers textMatches/idMatches/... selectors (Kotlin Regex) — Rhino's own JS RegExp
 * engine is separate and still needs a thread-level watchdog.
 */
private class InterruptibleCharSequence(
    private val delegate: CharSequence,
    private val checkLimits: () -> Unit
) : CharSequence {
    private var reads = 0
    override val length: Int get() = delegate.length
    override fun get(index: Int): Char {
        if (reads++ and (CHECK_EVERY - 1) == 0) checkLimits()
        return delegate[index]
    }
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        InterruptibleCharSequence(delegate.subSequence(startIndex, endIndex), checkLimits)
    override fun toString(): String = delegate.toString()
    private companion object { const val CHECK_EVERY = 256 }
}

/**
 * Runs generated AutoJs6 scripts against [SimState] using Rhino — the same engine AutoJs6
 * uses. Selectors and nodes are exposed as native JS objects with attached functions so that
 * interop behaves exactly like the real AutoJs6 object graph.
 */
class SimRuntime(
    private val sink: SimSink? = null,
    private val control: RunControl = RunControl()
) {

    val state = SimState()
    private var cx: Context? = null
    private var depth = 0
    private var deadlineNanos = Long.MAX_VALUE
    @Volatile private var poisoned = false
    @Volatile private var workerThread: Thread? = null
    private val executorHolder = lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "a6-sim").apply { isDaemon = true }.also { workerThread = it }
        }
    }
    private val executor get() = executorHolder.value
    fun close() { if (executorHolder.isInitialized()) executorHolder.value.shutdownNow() }

    /**
     * Contexts from this factory call [checkLimits] every [INSTRUCTION_SLICE] interpreted
     * instructions, which is what lets a stop/timeout interrupt a pure-JS infinite loop.
     */
    private val factory = object : ContextFactory() {
        override fun makeContext(): Context = super.makeContext().apply {
            isInterpretedMode = true // instruction observation only works in interpreted mode
            languageVersion = Context.VERSION_ES6
            instructionObserverThreshold = INSTRUCTION_SLICE
            // M3: deep or runaway recursion used to grow the JVM stack until StackOverflowError,
            // which escaped as a non-Rhino error and left the run "运行中" forever. The interpreter
            // limit turns it into an ordinary script error that execute() reports.
            maximumInterpreterStackDepth = 2_000
            // Whitelist shutter: refuse every Java class, including Packages.kotlinx,
            // Packages.io.ktor, Packages.android, Packages.cn.dumate, etc.
            // The old blacklist only blocked java.*/javax.*/kotlin.* — anything else on the
            // classpath (Ktor, Android framework, our own code) could be reached via
            // `Packages.xxx`. Setting the shutter to always-false closes that completely.
            // The simulated device API is implemented as pure-JS stubs, so no Java classes
            // are needed inside scripts.
            setClassShutter { false }
        }

        override fun observeInstructionCount(cx: Context, instructionCount: Int) = checkLimits()
    }

    /** Enters a Rhino context wired to this runtime's stop switch and time budget. */
    fun enterContext(): Context = factory.enterContext()

    /** Throws [SimAbort] when the user pressed stop or the wall-clock budget ran out. */
    fun checkLimits() {
        if (control.cancelled) throw SimAbort(SimAbort.STOPPED, SimAbort.STOPPED_MESSAGE)
        if (System.nanoTime() > deadlineNanos) {
            throw SimAbort(SimAbort.TIMEOUT, SimAbort.timeoutMessage(control.wallTimeoutMs))
        }
    }
    private var scope: Scriptable? = null
    private var stateObject: NativeObject? = null

    private fun log(level: String, msg: String) = synchronized(state.logs) { state.add(level, clip(msg)) }

    private fun clip(s: String) = if (s.length > 4000) s.substring(0, 4000) + "…" else s

    fun execute(source: String, target: Scriptable? = null): Pair<String, String?> {
        if (poisoned) {
            return "error" to "上一次脚本执行卡死在原生代码里，模拟器线程已废弃；请重新运行以新建运行时"
        }
        // Nested `$run()` already executes on the watchdog thread.
        if (depth > 0) return executeDirectly(source, target)

        val future = executor.submit<Pair<String, String?>> { executeDirectly(source, target) }
        val budgetMs = control.wallTimeoutMs
        val hardDeadline = if (budgetMs > 0) System.nanoTime() + (budgetMs + GRACE_MS) * 1_000_000L else Long.MAX_VALUE
        var cancelDeadline = Long.MAX_VALUE
        try {
            while (true) {
                if (control.cancelled && cancelDeadline == Long.MAX_VALUE) {
                    cancelDeadline = System.nanoTime() + GRACE_MS * 1_000_000L
                }
                val deadline = minOf(hardDeadline, cancelDeadline)
                val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
                if (remainingMs <= 0) break
                try {
                    return future.get(minOf(remainingMs, 250L), java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (_: java.util.concurrent.TimeoutException) {
                    // keep waiting; checkLimits() handles normal timeout/stop inside Rhino
                }
            }
        } catch (e: java.util.concurrent.ExecutionException) {
            val cause = e.cause ?: e
            if (findSimExit(cause)) return "exited" to null
            if (cause is SimAbort) return cause.status to cause.message
            return "error" to (cause.message ?: cause.toString())
        } catch (e: Exception) {
            if (findSimExit(e)) return "exited" to null
            return "error" to (e.message ?: e.toString())
        }
        // Script didn't return within grace period - likely stuck in Java regex/native code.
        control.cancel()
        try {
            future.get(GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            poisoned = true
            // Rhino 的 JS RegExp 不走 ContextFactory 的 instruction observer，卡在回溯里时
            // checkLimits() 永远不会被调用；Thread.stop 在 JDK 20+ 和 Android 上已不可用，
            // 所以只能放弃该线程。把它的优先级降到最低，减少对后续运行/UI 线程的争抢。
            workerThread?.priority = Thread.MIN_PRIORITY
            log("error", "脚本在原生代码中卡死，执行线程已废弃；后续运行需新建模拟器")
        }
        val status = if (budgetMs > 0 && System.nanoTime() >= hardDeadline) SimAbort.TIMEOUT else SimAbort.STOPPED
        return status to if (status == SimAbort.TIMEOUT) SimAbort.timeoutMessage(budgetMs) else SimAbort.STOPPED_MESSAGE
    }

    private fun executeDirectly(source: String, target: Scriptable? = null): Pair<String, String?> {
        // The time budget covers the outermost script; nested `$run()` shares it.
        if (depth == 0) {
            val budget = control.wallTimeoutMs
            deadlineNanos = if (budget > 0) System.nanoTime() + budget * 1_000_000 else Long.MAX_VALUE
        }
        depth += 1
        val context = enterContext()
        cx = context
        return try {
            checkLimits()
            val sc = target ?: newScope(context)
            context.evaluateString(sc, source, "a6studio", 1, null)
            "finished" to null
        } catch (e: SimAbort) {
            // Inside `$run()` the abort must keep unwinding through the outer test script.
            if (depth > 1) throw e
            log("error", e.message ?: e.status)
            e.status to e.message
        } catch (e: SimExit) {
            "exited" to null
        } catch (e: org.mozilla.javascript.WrappedException) {
            if (findSimExit(e)) return "exited" to null
            val we = e.wrappedException
            if (we is SimAbort) {
                if (depth > 1) throw we
                log("error", we.message ?: we.status)
                return we.status to we.message
            }
            "error" to explainJavaBlock(we?.message ?: e.message)
        } catch (e: org.mozilla.javascript.RhinoException) {
            "error" to explainJavaBlock((e.details() ?: e.message ?: "") + "（行 ${e.lineNumber()}）")
        } catch (e: Exception) {
            if (findSimExit(e)) return "exited" to null
            "error" to explainJavaBlock(e.message ?: e.toString())
        } catch (e: StackOverflowError) {
            "error" to "递归太深，脚本已停止（检查是否有函数无限调用自己）"
        } catch (e: OutOfMemoryError) {
            "error" to "脚本占用内存过多，已停止（检查是否在循环里不断拼接大字符串或数组）"
        } finally {
            Context.exit()
            depth -= 1
            // Only clear on the OUTERMOST frame. A nested `$run()` used to null `cx` while the
            // outer Rhino context was still entered; helpers then fell back to a bare
            // `Context.enter()`, which increments enterCount with no matching exit. Rhino keeps
            // one thread-local context, so the leak made every later run on that thread reuse the
            // old context (and its baked-in deadline) — the stop switch silently stopped working.
            if (depth == 0) {
                cx = null
                scope = null
            }
        }
    }

    /**
     * Rhino's own wording for a blocked class ("Access to Java class … is prohibited") is
     * technically true but tells the user nothing about what to do, so add the actionable part.
     */
    private fun explainJavaBlock(message: String?): String? {
        if (message == null) return null
        val blocked = message.contains("Java class") || message.contains("Java constructor") ||
            message.contains("prohibited")
        return if (blocked) message + "\n" + JAVA_INTEROP_HINT else message
    }

    private fun findSimExit(t: Throwable?): Boolean {
        var cur: Throwable? = t
        var guard = 0
        while (cur != null && guard++ < 10) {
            if (cur is SimExit) return true
            cur = cur.cause
        }
        return false
    }

    /** Builds a fresh global object; the test harness uses it to inject `$run`. */
    fun newScope(context: Context): Scriptable {
        // initSafeStandardObjects(null, false) omits the LiveConnect bridge — it does not
        // register Packages, java, JavaAdapter, or getClass. Combined with the always-false
        // ClassShutter this gives a true whitelist sandbox: scripts can only reach what we
        // explicitly install below (selectors, device stubs, etc.).
        val sc = context.initSafeStandardObjects(null, false)
        val s = sc as ScriptableObject
        ScriptableObject.putProperty(s, "globalThis", s)
        scope = sc
        installSelectors(s)
        installDeviceAndApp(s)
        installDataAndDebug(s)
        installJavaStubs(s)
        return sc
    }

    // ---- function helpers ---------------------------------------------------
    private fun fn(body: (Array<Any>, Scriptable) -> Any?): BaseFunction =
        object : BaseFunction() {
            override fun call(cxx: Context, sc: Scriptable, thisObj: Scriptable?, args: Array<Any>): Any? =
                body(args, sc)
        }.apply {
            scope?.let {
                parentScope = it
                prototype = ScriptableObject.getFunctionPrototype(it)
            }
        }

    private fun put(obj: ScriptableObject, name: String, body: (Array<Any>, Scriptable) -> Any?) {
        ScriptableObject.putProperty(obj, name, fn(body))
    }

    private fun arrayOfStrings(sc: Scriptable, items: List<String>): NativeArray =
        makeArray(sc, items)

    /**
     * Rhino's `Context.newArray` insists the argument array's component type is exactly
     * `Object`; Kotlin `toTypedArray()` would give `String[]`/`NativeObject[]` and throw.
     */
    private fun makeArray(sc: Scriptable, items: List<Any?>): NativeArray {
        // Pair Context.enter() with Context.exit() like ocr.detect does: without an own/exit
        // pair, every call made outside a running script leaked one context.
        val own = cx == null
        val ctx = cx ?: Context.enter()
        try {
            val arr = arrayOfNulls<Any>(items.size)
            items.forEachIndexed { i, v -> arr[i] = v }
            @Suppress("UNCHECKED_CAST")
            return ctx.newArray(sc, arr) as NativeArray
        } finally {
            if (own) Context.exit()
        }
    }

    // ---- selectors ----------------------------------------------------------
    private fun selector(label: String, filters: List<(SimNode) -> Boolean>, sc: Scriptable): NativeObject {
        fun nodes(): List<SimNode> = state.buildNodes().filter { n -> filters.all { it(n) } }

        val o = NativeObject()
        put(o, "find") { _, s -> toNodeArray(nodes(), s) }
        put(o, "untilFind") { _, s -> toNodeArray(nodes(), s) }
        put(o, "findOnce") { _, _ -> nodes().firstOrNull()?.let { nodeObject(it, sc) } }
        put(o, "findOne") { args, _ ->
            val timeout = (args.getOrNull(0) as? Number)?.toInt() ?: 0
            val r = nodes()
            if (r.isNotEmpty()) nodeObject(r.first(), sc)
            else {
                log("debug", "未找到控件：$label" + if (timeout > 0) "（已等 ${timeout}ms）" else "")
                null
            }
        }
        put(o, "exists") { _, _ -> nodes().isNotEmpty() }
        put(o, "waitFor") { _, _ ->
            val ok = nodes().isNotEmpty()
            log(if (ok) "info" else "warn", if (ok) "控件已出现：$label" else "等待控件超时：$label")
            ok
        }
        put(o, "clickable") { args, _ ->
            val v = args.getOrNull(0) == true || args.getOrNull(0)?.toString() == "true"
            selector("$label[clickable=$v]", filters + { n -> n.clickable == v }, sc)
        }
        put(o, "id") { args, _ ->
            val v = args.getOrNull(0)?.toString() ?: ""
            selector("$label[id=$v]", filters + { n -> n.id == v }, sc)
        }
        put(o, "text") { args, _ ->
            val v = args.getOrNull(0)?.toString() ?: ""
            selector("$label[text=$v]", filters + { n -> n.text == v }, sc)
        }
        ScriptableObject.putProperty(o, "_label", label)
        return o
    }

    private fun toNodeArray(nodes: List<SimNode>, sc: Scriptable): NativeArray {
        val list = nodes.map { nodeObject(it, sc) }
        return makeArray(sc, list).also { arr ->
            put(arr, "size") { _, _ -> list.size }
            put(arr, "empty") { _, _ -> list.isEmpty() }
            put(arr, "get") { a, _ ->
                val idx = (a.getOrNull(0) as? Number)?.toInt() ?: -1
                list.getOrNull(idx)
            }
        }
    }

    private fun nodeObject(node: SimNode, sc: Scriptable): NativeObject {
        val o = NativeObject()
        put(o, "text") { _, _ -> node.text }
        put(o, "desc") { _, _ -> node.desc }
        put(o, "id") { _, _ -> node.id }
        put(o, "className") { _, _ -> node.className }
        put(o, "clickable") { _, _ -> node.clickable }
        put(o, "bounds") { _, _ ->
            val b = NativeObject()
            val l = node.bounds[0]
            val t = node.bounds[1]
            val r = node.bounds[2]
            val bot = node.bounds[3]
            b.put("left", b, l)
            b.put("top", b, t)
            b.put("right", b, r)
            b.put("bottom", b, bot)
            put(b, "centerX") { _, _ -> (l + r) / 2 }
            put(b, "centerY") { _, _ -> (t + bot) / 2 }
            put(b, "width") { _, _ -> r - l }
            put(b, "height") { _, _ -> bot - t }
            b
        }
        put(o, "click") { _, _ ->
            if (!node.clickable) {
                log("warn", "节点不可点击：${node.label}")
                false
            } else {
                log("info", "点击 → ${node.label}")
                if (synchronized(state.logs) { state.nodeClick(node) }) { publishRender(); true } else false
            }
        }
        put(o, "longClick") { _, _ -> log("info", "长按 → ${node.label}"); true }
        put(o, "setText") { args, _ ->
            node.text = args.getOrNull(0)?.toString() ?: ""
            log("info", "输入 → ${node.text}")
            publishRender()
            true
        }
        put(o, "scrollForward") { _, _ -> log("info", "滚动 ↓ ${node.label}"); true }
        put(o, "scrollBackward") { _, _ -> log("info", "滚动 ↑ ${node.label}"); true }
        put(o, "exists") { _, _ -> true }
        put(o, "parent") { _, _ -> null }
        put(o, "child") { _, _ -> null }
        put(o, "children") { _, s -> arrayOfStrings(s, emptyList()) }
        put(o, "toString") { _, _ -> "[node ${node.label}]" }
        return o
    }

    private fun installSelectors(s: ScriptableObject) {
        fun simple(name: String, field: (SimNode) -> String) {
            put(s, name) { args, sc ->
                val v = args.getOrNull(0)?.toString() ?: ""
                selector("$name(\"$v\")", listOf { n -> field(n) == v }, sc)
            }
        }
        fun contains(name: String, field: (SimNode) -> String) {
            put(s, name) { args, sc ->
                val v = args.getOrNull(0)?.toString() ?: ""
                selector("$name(\"$v\")", listOf { n -> field(n).contains(v) }, sc)
            }
        }
        fun matches(name: String, field: (SimNode) -> String) {
            put(s, name) { args, sc ->
                val v = args.getOrNull(0)?.toString() ?: ""
                val re = runCatching { Regex(v) }.getOrNull()
                selector("$name(\"$v\")", listOf { n ->
                    re != null && re.containsMatchIn(InterruptibleCharSequence(field(n), ::checkLimits))
                }, sc)
            }
        }

        simple("text") { it.text }
        simple("desc") { it.desc }
        simple("id") { it.id }
        simple("className") { it.className }
        contains("textContains") { it.text }
        contains("descContains") { it.desc }
        contains("idContains") { it.id }
        matches("textMatches") { it.text }
        matches("descMatches") { it.desc }
        matches("idMatches") { it.id }
        matches("classNameMatches") { it.className }
    }

    // ---- device / app / interaction ----------------------------------------
    private fun installDeviceAndApp(s: ScriptableObject) {
        val device = NativeObject()
        device.put("width", device, 1080)
        device.put("height", device, 2400)
        device.put("sdkInt", device, 36)
        device.put("brand", device, "OnePlus")
        device.put("model", device, "CPH2581")
        put(device, "keepScreenOn") { a, _ -> log("debug", "保持屏幕常亮 ${a.getOrNull(0)}ms"); null }
        put(device, "vibrate") { a, _ -> log("debug", "震动 ${a.getOrNull(0)}ms"); null }
        put(device, "cancelKeepingAwake") { _, _ -> null }
        ScriptableObject.putProperty(s, "device", device)

        val app = NativeObject()
        val launchPkgFn: (Array<out Any?>) -> Any = { a ->
            val pkg = a.getOrNull(0)?.toString() ?: ""
            log("info", "启动应用 $pkg")
            when (pkg) {
                "com.jd.jrapp" -> state.screen = "jd.finance"
                "com.jingdong.app.mall" -> state.screen = "jd.mall"
            }
            publishRender()
            true
        }
        put(app, "launchPackage") { a, _ -> launchPkgFn(a) }
        put(app, "launch") { a, _ -> launchPkgFn(a) }
        // Real AutoJs6 scripts often launch via an intent instead of launchPackage
        // (measured on a user script: app.startActivity({data:"jdmobile://...", packageName:"com.jd.jrapp"})).
        put(app, "startActivity") { a, _ ->
            val opts = a.getOrNull(0) as? NativeObject
            val data = opts?.get("data")?.toString().orEmpty()
            val pkg = opts?.get("packageName")?.toString().orEmpty()
            log("info", "startActivity：" + listOf(pkg, data).filter { it.isNotEmpty() }.joinToString(" "))
            when {
                pkg == "com.jd.jrapp" -> state.screen = "jd.finance"
                data.contains("jd.com") || data.contains("jdmobile") -> state.screen = "jd.coupon"
            }
            publishRender()
            null
        }
        put(app, "startActivityForResult") { a, _ ->
            log("info", "startActivityForResult")
            null
        }
        put(app, "getPackageName") { a, _ ->
            if (a.getOrNull(0)?.toString() == "京东") "com.jingdong.app.mall" else ""
        }
        put(app, "openUrl") { a, _ ->
            val url = a.getOrNull(0)?.toString() ?: ""
            log("info", "打开 URL $url")
            when {
                url.contains("VAjs3vpay") -> { state.screen = "jd.coupon"; publishRender() }
                url.contains("jd.com") || url.contains("jdmobile") -> { state.screen = "jd.service"; publishRender() }
            }
            true
        }
        put(app, "kill") { a, _ -> log("info", "结束应用 ${a.getOrNull(0)}"); null }
        ScriptableObject.putProperty(s, "app", app)

        // `auto` is the accessibility facade scripts call before any selector work; the simulator
        // always reports it as available. Measured: omitting it broke every template that opens
        // with auto.waitFor().
        val auto = NativeObject()
        put(auto, "waitFor") { _, _ -> log("debug", "无障碍服务已就绪"); null }
        put(auto, "setMode") { a, _ -> log("debug", "auto.setMode(${a.getOrNull(0)})"); null }
        put(auto, "windowRoot") { _, _ -> null }
        ScriptableObject.putProperty(s, "auto", auto)

        put(s, "currentPackage") { _, _ -> state.packageName }
        put(s, "currentActivity") { _, _ -> state.activity }
        put(s, "waitForPackage") { a, _ -> log("debug", "waitForPackage ${a.getOrNull(0)}"); state.packageName == a.getOrNull(0)?.toString() }
        put(s, "waitForActivity") { a, _ -> state.activity == a.getOrNull(0)?.toString() }

        put(s, "sleep") { a, _ ->
            val ms = ((a.getOrNull(0) as? Number)?.toLong() ?: 0L).coerceAtLeast(0L)
            checkLimits()
            state.clock += ms
            if (ms >= 1000) log("debug", "sleep ${ms}ms")
            null
        }
        val simStartEpoch = System.currentTimeMillis()
        put(s, "__simNow") { _, _ -> (simStartEpoch + state.clock).toDouble() }
        (ScriptableObject.getProperty(s, "Date") as? ScriptableObject)?.let { dateObj ->
            runCatching { dateObj.setAttributes("now", 0) }
            put(dateObj, "now") { _, _ -> (simStartEpoch + state.clock).toDouble() }
        }
        put(s, "toast") { a, _ ->
            val msg = a.getOrNull(0)?.toString() ?: ""
            log("info", "Toast：$msg")
            sink?.onToast(msg)
            null
        }
        put(s, "log") { a, _ -> log("info", a.getOrNull(0)?.toString() ?: "undefined"); null }

        val console = NativeObject()
        put(console, "log") { a, _ -> log("info", a.getOrNull(0)?.toString() ?: ""); null }
        put(console, "info") { a, _ -> log("info", a.getOrNull(0)?.toString() ?: ""); null }
        put(console, "warn") { a, _ -> log("warn", a.getOrNull(0)?.toString() ?: ""); null }
        put(console, "error") { a, _ -> log("error", a.getOrNull(0)?.toString() ?: ""); null }
        put(console, "show") { _, _ -> log("debug", "显示控制台"); null }
        put(console, "hide") { _, _ -> log("debug", "隐藏控制台"); null }
        put(console, "clear") { _, _ -> synchronized(state.logs) { state.logs.clear() }; null }
        ScriptableObject.putProperty(s, "console", console)

        put(s, "exit") { _, _ -> throw SimExit() }
        put(s, "back") { _, _ -> synchronized(state.logs) { state.back() }; publishRender(); null }
        put(s, "home") { _, _ -> synchronized(state.logs) { state.home() }; publishRender(); null }
        put(s, "recents") { _, _ -> log("info", "任务键"); null }
        put(s, "KeyCode") { a, _ -> log("info", "按键 ${a.getOrNull(0)}"); true }
        put(s, "press") { a, _ ->
            if (a.size >= 2 && a.getOrNull(0) is Number && a.getOrNull(1) is Number) {
                val x = (a[0] as Number).toInt()
                val y = (a[1] as Number).toInt()
                val dur = (a.getOrNull(2) as? Number)?.toInt() ?: 100
                log("info", "按住坐标 ($x, $y) ${dur}ms")
            } else {
                val key = a.getOrNull(0)?.toString() ?: ""
                log("info", "按键 $key")
                if (key == "back") { synchronized(state.logs) { state.back() }; publishRender() }
            }
            null
        }
        put(s, "click") { a, _ ->
            val x = (a.getOrNull(0) as? Number)?.toInt() ?: 0
            val y = (a.getOrNull(1) as? Number)?.toInt() ?: 0
            log("info", "点击坐标 $x, $y")
            val hit = state.buildNodes().firstOrNull { n ->
                n.clickable && x >= n.bounds[0] && x <= n.bounds[2] && y >= n.bounds[1] && y <= n.bounds[3]
            }
            if (hit != null && synchronized(state.logs) { state.nodeClick(hit) }) publishRender()
            null
        }
        put(s, "longClick") { a, _ -> log("info", "长按坐标 ${a.getOrNull(0)}, ${a.getOrNull(1)}"); null }
        put(s, "swipe") { a, _ ->
            val x1 = (a.getOrNull(0) as? Number)?.toInt() ?: 0
            val y1 = (a.getOrNull(1) as? Number)?.toInt() ?: 0
            val x2 = (a.getOrNull(2) as? Number)?.toInt() ?: 0
            val y2 = (a.getOrNull(3) as? Number)?.toInt() ?: 0
            val dur = (a.getOrNull(4) as? Number)?.toInt() ?: 300
            log("debug", "滑动 ($x1,$y1) → ($x2,$y2) ${dur}ms")
            if (state.screen == "jd.finance" && x1 < 20) { state.screen = "jd.panel"; publishRender() }
            null
        }
        put(s, "gesture") { a, _ ->
            log("debug", "手势 ${a.getOrNull(0) ?: ""}")
            if (state.screen == "jd.finance") {
                state.screen = "jd.panel"
                log("info", "手势右滑切回京东")
                publishRender()
            }
            null
        }
        put(s, "setClip") { a, _ -> state.clip = a.getOrNull(0)?.toString() ?: ""; log("debug", "写入剪贴板"); null }
        put(s, "getClip") { _, _ -> state.clip }
        put(s, "random") { a, _ ->
            val from = (a.getOrNull(0) as? Number)?.toInt() ?: 0
            val to = (a.getOrNull(1) as? Number)?.toInt() ?: 1
            (from until maxOf(from + 1, to)).random()
        }
    }

    // ---- files / storage / vision / debug ----------------------------------
    private val files = mutableMapOf<String, String>()
    private val storage = mutableMapOf<String, Any?>()
    private val storageSizes = mutableMapOf<String, Long>()
    private var totalStorageChars = 0L

    /**
     * The virtual file system lives in the app's heap. Without a cap, `while (true) files.append(...)`
     * grew it until the JVM ran out of memory — on Android that took the whole app down rather
     * than ending the run. Throwing a JS error lets the script catch it, or ends the run as 异常.
     */
    private fun checkFileBudget(key: String, newLength: Long) {
        if (key !in files && files.size >= MAX_FILES) {
            throw org.mozilla.javascript.Context.reportRuntimeError("模拟器文件数已达 $MAX_FILES 个上限")
        }
        val total = files.values.sumOf { it.length.toLong() } - (files[key]?.length?.toLong() ?: 0L) + newLength
        if (total > MAX_FILE_CHARS) {
            throw org.mozilla.javascript.Context.reportRuntimeError(
                "模拟器文件总大小超过 ${MAX_FILE_CHARS / 1024 / 1024}M 字符上限（$key）"
            )
        }
    }

    private fun estimateStorageChars(v: Any?): Long = when (v) {
        null, org.mozilla.javascript.Undefined.instance -> 0L
        is String -> v.length.toLong()
        is Number, is Boolean -> 16L
        else -> v.toString().length.toLong()
    }

    private fun installDataAndDebug(s: ScriptableObject) {
        val filesObj = NativeObject()
        put(filesObj, "ensureDir") { a, _ -> log("debug", "ensureDir ${a.getOrNull(0)}"); null }
        put(filesObj, "exists") { a, _ -> files.containsKey(a.getOrNull(0)?.toString()) }
        put(filesObj, "read") { a, _ -> files[a.getOrNull(0)?.toString()] }
        put(filesObj, "write") { a, _ ->
            val k = a.getOrNull(0)?.toString() ?: ""
            val content = a.getOrNull(1)?.toString() ?: ""
            checkFileBudget(k, content.length.toLong())
            files[k] = content
            log("debug", "写入文件 ${a.getOrNull(0)}")
            true
        }
        put(filesObj, "append") { a, _ ->
            val k = a.getOrNull(0)?.toString() ?: ""
            val addition = a.getOrNull(1)?.toString() ?: ""
            // N-L7：只对模拟器日志文件（jdstudio_*.log）限制大小，避免长时间运行时 O(n²) 累积
            val existing = files[k] ?: ""
            val MAX_LOG_CHARS = 65_536
            val isTrimmedLog = k.contains("jdstudio_") && k.endsWith(".log") && existing.length > MAX_LOG_CHARS
            val baseLen = if (isTrimmedLog) MAX_LOG_CHARS / 2 else existing.length
            checkFileBudget(k, baseLen.toLong() + addition.length.toLong())
            val next = if (isTrimmedLog) {
                existing.takeLast(MAX_LOG_CHARS / 2) + addition
            } else {
                existing + addition
            }
            files[k] = next
            true
        }
        put(filesObj, "remove") { a, _ -> files.remove(a.getOrNull(0)?.toString()); true }
        put(filesObj, "listDir") { a, sc ->
            val prefix = a.getOrNull(0)?.toString() ?: ""
            arrayOfStrings(sc, files.keys.filter { it.startsWith(prefix) })
        }
        ScriptableObject.putProperty(s, "files", filesObj)

        val storages = NativeObject()
        put(storages, "create") { a, _ ->
            val name = a.getOrNull(0)?.toString() ?: ""
            val keyPrefix = "$name:"
            val o = NativeObject()
            put(o, "get") { b, _ -> storage[keyPrefix + (b.getOrNull(0)?.toString() ?: "")] ?: b.getOrNull(1) }
            put(o, "put") { b, _ ->
                val key = keyPrefix + (b.getOrNull(0)?.toString() ?: "")
                val value = b.getOrNull(1)
                if (key !in storage && storage.size >= MAX_STORAGE_KEYS) {
                    throw org.mozilla.javascript.Context.reportRuntimeError(
                        "模拟器存储已达 $MAX_STORAGE_KEYS 个键的上限（真机上请检查是否在循环里不断写新键）"
                    )
                }
                val newSize = estimateStorageChars(value)
                val prevSize = storageSizes[key] ?: 0L
                val nextTotal = totalStorageChars - prevSize + newSize
                if (nextTotal > MAX_STORAGE_CHARS) {
                    throw org.mozilla.javascript.Context.reportRuntimeError(
                        "模拟器存储总大小超过 ${MAX_STORAGE_CHARS / 1024 / 1024}M 字符上限（$key）"
                    )
                }
                storage[key] = value
                storageSizes[key] = newSize
                totalStorageChars = nextTotal
                true
            }
            put(o, "remove") { b, _ ->
                val key = keyPrefix + (b.getOrNull(0)?.toString() ?: "")
                storage.remove(key)
                totalStorageChars = (totalStorageChars - (storageSizes.remove(key) ?: 0L)).coerceAtLeast(0L)
                null
            }
            o
        }
        ScriptableObject.putProperty(s, "storages", storages)

        val http = NativeObject()
        fun httpResult(url: String): NativeObject {
            val payload = "{\"ok\":true,\"url\":" + cn.dumate.a6studio.catalog.Js.quote(url) + "}"
            val body = NativeObject()
            put(body, "string") { _, _ -> payload }
            put(body, "json") { _, _ ->
                val j = NativeObject()
                j.put("ok", j, true)
                j.put("url", j, url)
                j
            }
            val r = NativeObject()
            r.put("statusCode", r, 200)
            r.put("body", r, body)
            return r
        }
        put(http, "get") { a, _ ->
            val url = a.getOrNull(0)?.toString() ?: ""
            log("debug", "GET $url"); httpResult(url)
        }
        put(http, "postJson") { a, _ ->
            val url = a.getOrNull(0)?.toString() ?: ""
            log("debug", "POST $url"); httpResult(url)
        }
        ScriptableObject.putProperty(s, "http", http)

        // `shell(...)` is generated by one block. Without a stub it was a ReferenceError at
        // runtime, which reads like a broken script rather than "the simulator cannot do this".
        val shellFn = object : BaseFunction() {
            override fun call(c: Context?, sc: Scriptable?, t: Scriptable?, a: Array<Any>?): Any? {
                log("warn", "模拟器不执行 shell 命令，需真机验证：${a?.getOrNull(0)}")
                return ""
            }
        }
        ScriptableObject.putProperty(s, "shell", shellFn)

        val images = NativeObject()
        put(images, "requestScreenCapture") { _, _ -> log("debug", "申请截屏权限"); true }
        put(images, "captureScreen") { _, _ ->
            val img = NativeObject()
            img.put("width", img, 1080)
            img.put("height", img, 2400)
            img
        }
        put(images, "read") { a, _ ->
            val img = NativeObject()
            img.put("path", img, a.getOrNull(0))
            img
        }
        put(images, "findImage") { _, _ -> null }
        put(images, "findMultiColors") { _, _ -> null }
        put(images, "save") { a, _ -> log("info", "截图已保存 ${a.getOrNull(1)}"); true }
        ScriptableObject.putProperty(s, "images", images)

        val ocr = NativeObject()
        put(ocr, "recognizeText") { _, _ ->
            state.buildNodes().map { it.text.ifEmpty { it.desc } }.filter { it.isNotEmpty() }.joinToString(" ")
        }

        // `ocr.detect()` returns an array of {label, bounds{...}} — the shape AutoJs6 scripts
        // iterate over (measured on a real script). Bounds mirror the simulated screen nodes so
        // OCR-driven clicking lands on the right widget.
        put(ocr, "detect") { _, sc ->
            // Entering a Context without exiting it leaks one per direct `ocr.detect()` call
            // (the script's own context is already current during execution, so this only fires
            // for API calls made outside a script). Pair it instead of leaking.
            val own = cx == null
            val ctx = cx ?: Context.enter()
            try {
            val items = state.buildNodes().mapNotNull { n ->
                val label = n.text.ifEmpty { n.desc }
                if (label.isEmpty()) return@mapNotNull null
                val o = NativeObject()
                o.put("label", o, label)
                val b = NativeObject()
                b.put("left", b, n.bounds[0])
                b.put("top", b, n.bounds[1])
                b.put("right", b, n.bounds[2])
                b.put("bottom", b, n.bounds[3])
                // Scripts call bounds.centerX()/centerY(); expose them as callables.
                put(b, "centerX") { _, _ -> (n.bounds[0] + n.bounds[2]) / 2 }
                put(b, "centerY") { _, _ -> (n.bounds[1] + n.bounds[3]) / 2 }
                o.put("bounds", o, b)
                o.put("confidence", o, 0.95)
                o
            }
            makeArray(sc, items)
            } finally {
                if (own) Context.exit()
            }
        }
        put(ocr, "mode") { _, _ -> null }
        put(ocr, "recognizeTextFromImage") { _, sc ->
            // Must go through makeArray: Context.newArray rejects a String[] (it insists on
            // component type Object), so the previous toTypedArray() call always threw.
            makeArray(sc, state.buildNodes().map { it.text.ifEmpty { it.desc } }.filter { it.isNotEmpty() })
        }
        ScriptableObject.putProperty(s, "ocr", ocr)

        val threads = NativeObject()
        put(threads, "start") { a, sc ->
            val f = a.getOrNull(0)
            if (f is Function) {
                // L14: the callback runs synchronously in the simulator (no real thread).
                // Say so, and rethrow SimExit so exit() inside the "thread" still ends the script
                // instead of being swallowed by the generic catch below.
                log("info", "模拟器中 threads.start 同步执行回调")
                val own = cx == null
                val ctx = cx ?: Context.enter()
                try {
                    f.call(ctx, sc, sc, emptyArray())
                } catch (e: SimExit) {
                    throw e
                } catch (e: Exception) {
                    log("error", "子线程异常：${e.message}")
                } finally {
                    if (own) Context.exit()
                }
            }
            null
        }
        put(threads, "shutDownAll") { _, _ -> null }
        ScriptableObject.putProperty(s, "threads", threads)

        val engines = NativeObject()
        put(engines, "myEngine") { _, _ -> NativeObject() }
        ScriptableObject.putProperty(s, "engines", engines)

        // ---- test-harness globals -------------------------------------------
        put(s, "assert") { a, _ ->
            if (!truthy(a.getOrNull(0))) throw RuntimeException("断言失败：${a.getOrNull(1) ?: ""}")
            null
        }
        put(s, "\$log") { _, sc -> arrayOfStrings(sc, synchronized(state.logs) { state.logs.map { it.msg } }) }
        put(s, "\$setScreen") { a, _ ->
            state.screen = a.getOrNull(0)?.toString() ?: state.screen
            publishRender()
            null
        }
        put(s, "\$state") { _, sc ->
            val o = NativeObject()
            o.put("screen", o, state.screen)
            o.put("signed", o, state.signed)
            // Task objects keep text+desc so tests can filter by subtitle the way the UI does.
            val tasks = state.tasks.map { t ->
                NativeObject().also { o2 ->
                    o2.put("id", o2, t.id)
                    o2.put("text", o2, t.text)
                    o2.put("desc", o2, t.desc)
                }
            }
            o.put("tasks", o, makeArray(sc, tasks))
            o.put("package", o, state.packageName)
            stateObject = o
            o
        }
    }

    private fun truthy(v: Any?): Boolean = Context.toBoolean(v)

    /**
     * Real AutoJs6 scripts often start with `importClass(java.io.File)`. The simulator blocks
     * Java classes (see the ClassShutter), so these must not look like a typo: they exist, log
     * why they do nothing, and let the rest of the script run.
     */
    private fun installJavaStubs(s: ScriptableObject) {
        val note = "模拟器不执行 Java 互操作（防止导入的脚本在电脑上执行任意 Java 代码）"
        // initSafeStandardObjects no longer defines `java`, `Packages`, …, so a script line like
        // `importClass(java.io.File)` would die evaluating its argument before the stub runs.
        // Inert package objects make those names resolve to harmless path placeholders: any
        // attempt to call or construct them is a TypeError, and nothing reaches the JVM.
        for (root in listOf("Packages", "java", "javax", "android", "androidx", "dalvik")) {
            ScriptableObject.putProperty(s, root, InertPackage(if (root == "Packages") "" else root))
        }
        put(s, "importClass") { a, _ ->
            log("warn", "$note：已忽略 importClass(${a.getOrNull(0)})，需要在真机上验证")
            null
        }
        put(s, "importPackage") { a, _ ->
            log("warn", "$note：已忽略 importPackage(${a.getOrNull(0)})，需要在真机上验证")
            null
        }
    }

    /** A Java package name with no Java behind it: property access yields deeper placeholders. */
    private class InertPackage(private val path: String) : ScriptableObject() {
        override fun getClassName(): String = "JavaPackage"

        override fun get(name: String, start: Scriptable): Any? {
            val own = super.get(name, start)
            if (own !== Scriptable.NOT_FOUND) return own
            return InertPackage(if (path.isEmpty()) name else "$path.$name")
        }

        override fun getDefaultValue(typeHint: Class<*>?): Any = toString()

        override fun toString(): String = "[JavaPackage ${path.ifEmpty { "Packages" }}]"
    }

    /** Applies `$state().tasks = [...]` written by test code before replaying the program. */
    fun syncFromStateObject() {
        val o = stateObject ?: return
        (o.get("screen") as? String)?.let { if (it.isNotEmpty()) state.screen = it }
        (o.get("signed") as? Boolean)?.let { state.signed = it }
        val tasks = o.get("tasks")
        if (tasks is NativeArray) {
            val newTasks = (0 until tasks.length).mapNotNull { i ->
                when (val t = tasks.get(i)) {
                    is NativeObject -> SimState.Task(
                        id = t.get("id")?.toString() ?: "t$i",
                        text = t.get("text")?.toString().orEmpty(),
                        desc = t.get("desc")?.toString().orEmpty(),
                        crossApp = false
                    )
                    else -> t?.toString()?.let { SimState.Task("t$i", it, "", false) }
                }
            }
            state.tasks.clear()
            state.tasks += newTasks
        }
    }

    fun publishRender() {
        sink?.onRender(state.buildNodes(), state.stateView(1080, 2400))
    }

    private companion object {
        /** Virtual file system caps (see [checkFileBudget]): 16M chars ≈ 32MB of heap. */
        const val MAX_FILES = 1_000
        const val MAX_FILE_CHARS = 16L * 1024 * 1024
        /** storages.create(...).put keys and total character budget across all named storages. */
        const val MAX_STORAGE_KEYS = 10_000
        const val MAX_STORAGE_CHARS = 16L * 1024 * 1024
        /** Interpreted instructions between stop/timeout checks (~sub-millisecond). */
        const val INSTRUCTION_SLICE = 10_000
        /** Grace period after wall-clock budget before abandoning a stuck native thread. */
        const val GRACE_MS = 2_000L

        const val JAVA_INTEROP_HINT =
            "（模拟器不提供 Java 互操作，避免导入的脚本在电脑上执行任意 Java 代码；这段请在真机运行）"
    }
}
