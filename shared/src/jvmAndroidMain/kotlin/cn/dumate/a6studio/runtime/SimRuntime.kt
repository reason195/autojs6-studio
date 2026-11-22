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

    /**
     * Contexts from this factory call [checkLimits] every [INSTRUCTION_SLICE] interpreted
     * instructions, which is what lets a stop/timeout interrupt a pure-JS infinite loop.
     */
    private val factory = object : ContextFactory() {
        override fun makeContext(): Context = super.makeContext().apply {
            optimizationLevel = -1 // instruction observation only works in interpreted mode
            languageVersion = Context.VERSION_ES6
            instructionObserverThreshold = INSTRUCTION_SLICE
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

    private fun log(level: String, msg: String) = state.add(level, clip(msg))

    private fun clip(s: String) = if (s.length > 4000) s.substring(0, 4000) + "…" else s

    fun execute(source: String, target: Scriptable? = null): Pair<String, String?> {
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
            "error" to (e.wrappedException?.message ?: e.message)
        } catch (e: org.mozilla.javascript.RhinoException) {
            "error" to ((e.details() ?: e.message ?: "") + "（行 ${e.lineNumber()}）")
        } catch (e: Exception) {
            if (findSimExit(e)) return "exited" to null
            "error" to (e.message ?: e.toString())
        } finally {
            Context.exit()
            depth -= 1
            cx = null
            scope = null
        }
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
        val sc = context.initStandardObjects()
        val s = sc as ScriptableObject
        scope = sc
        installSelectors(s)
        installDeviceAndApp(s)
        installDataAndDebug(s)
        return sc
    }

    // ---- function helpers ---------------------------------------------------
    private fun fn(body: (Array<Any>, Scriptable) -> Any?): BaseFunction =
        object : BaseFunction() {
            override fun call(cxx: Context, sc: Scriptable, thisObj: Scriptable, args: Array<Any>): Any? =
                body(args, sc)
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
        val ctx = cx ?: Context.enter()
        val arr = arrayOfNulls<Any>(items.size)
        items.forEachIndexed { i, v -> arr[i] = v }
        @Suppress("UNCHECKED_CAST")
        return ctx.newArray(sc, arr) as NativeArray
    }

    // ---- selectors ----------------------------------------------------------
    private fun selector(label: String, filters: List<(SimNode) -> Boolean>, sc: Scriptable): NativeObject {
        fun nodes(): List<SimNode> = state.buildNodes().filter { n -> filters.all { it(n) } }

        val o = NativeObject()
        put(o, "find") { _, s -> toNodeArray(nodes(), s) }
        put(o, "untilFind") { _, s -> toNodeArray(nodes(), s) }
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

    private fun toNodeArray(nodes: List<SimNode>, sc: Scriptable): NativeArray =
        makeArray(sc, nodes.map { nodeObject(it, sc) })

    private fun nodeObject(node: SimNode, sc: Scriptable): NativeObject {
        val o = NativeObject()
        put(o, "text") { _, _ -> node.text }
        put(o, "desc") { _, _ -> node.desc }
        put(o, "id") { _, _ -> node.id }
        put(o, "className") { _, _ -> node.className }
        put(o, "clickable") { _, _ -> node.clickable }
        put(o, "bounds") { _, ss ->
            val b = NativeObject()
            b.put("left", b, node.bounds[0])
            b.put("top", b, node.bounds[1])
            b.put("right", b, node.bounds[2])
            b.put("bottom", b, node.bounds[3])
            b
        }
        put(o, "click") { _, _ ->
            if (!node.clickable) {
                log("warn", "节点不可点击：${node.label}")
                false
            } else {
                log("info", "点击 → ${node.label}")
                if (state.nodeClick(node)) { publishRender(); true } else false
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
                selector("$name(\"$v\")", listOf { n -> re != null && re.containsMatchIn(field(n)) }, sc)
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
        put(app, "launchPackage") { a, _ ->
            val pkg = a.getOrNull(0)?.toString() ?: ""
            log("info", "启动应用 $pkg")
            when (pkg) {
                "com.jd.jrapp" -> state.screen = "jd.finance"
                "com.jingdong.app.mall" -> state.screen = "jd.mall"
            }
            publishRender()
            true
        }
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
            if (url.contains("VAjs3vpay")) { state.screen = "jd.coupon"; publishRender() }
            true
        }
        put(app, "kill") { a, _ -> log("info", "结束应用 ${a.getOrNull(0)}"); null }
        put(app, "getPackageName") { a, _ -> if (a.getOrNull(0)?.toString() == "京东") "com.jingdong.app.mall" else "" }
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
            val ms = (a.getOrNull(0) as? Number)?.toLong() ?: 0
            checkLimits()
            state.clock += ms
            if (ms >= 1000) log("debug", "sleep ${ms}ms")
            null
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
        put(console, "clear") { _, _ -> state.logs.clear(); null }
        ScriptableObject.putProperty(s, "console", console)

        put(s, "exit") { _, _ -> throw SimExit() }
        put(s, "back") { _, _ -> state.back(); publishRender(); null }
        put(s, "home") { _, _ -> state.home(); publishRender(); null }
        put(s, "recents") { _, _ -> log("info", "任务键"); null }
        put(s, "press") { a, _ ->
            val key = a.getOrNull(0)?.toString() ?: ""
            log("info", "按键 $key")
            if (key == "back") { state.back(); publishRender() }
            null
        }
        put(s, "click") { a, _ ->
            val x = (a.getOrNull(0) as? Number)?.toInt() ?: 0
            val y = (a.getOrNull(1) as? Number)?.toInt() ?: 0
            log("info", "点击坐标 $x, $y")
            val hit = state.buildNodes().firstOrNull { n ->
                n.clickable && x >= n.bounds[0] && x <= n.bounds[2] && y >= n.bounds[1] && y <= n.bounds[3]
            }
            if (hit != null && state.nodeClick(hit)) publishRender()
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
            log("debug", "手势 ${a.getOrNull(1)}")
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

    private fun installDataAndDebug(s: ScriptableObject) {
        val filesObj = NativeObject()
        put(filesObj, "ensureDir") { a, _ -> log("debug", "ensureDir ${a.getOrNull(0)}"); null }
        put(filesObj, "exists") { a, _ -> files.containsKey(a.getOrNull(0)?.toString()) }
        put(filesObj, "read") { a, _ -> files[a.getOrNull(0)?.toString()] }
        put(filesObj, "write") { a, _ ->
            files[a.getOrNull(0)?.toString() ?: ""] = a.getOrNull(1)?.toString() ?: ""
            log("debug", "写入文件 ${a.getOrNull(0)}")
            true
        }
        put(filesObj, "append") { a, _ ->
            val k = a.getOrNull(0)?.toString() ?: ""
            files[k] = (files[k] ?: "") + (a.getOrNull(1)?.toString() ?: "")
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
            put(o, "put") { b, _ -> storage[keyPrefix + (b.getOrNull(0)?.toString() ?: "")] = b.getOrNull(1); true }
            put(o, "remove") { b, _ -> storage.remove(keyPrefix + (b.getOrNull(0)?.toString() ?: "")); null }
            o
        }
        ScriptableObject.putProperty(s, "storages", storages)

        val http = NativeObject()
        fun httpResult(url: String, hasData: Boolean): NativeObject {
            val payload = if (hasData) "{\"ok\":true,\"url\":\"$url\"}" else "{\"ok\":true,\"url\":\"$url\"}"
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
            log("debug", "GET $url"); httpResult(url, false)
        }
        put(http, "postJson") { a, _ ->
            val url = a.getOrNull(0)?.toString() ?: ""
            log("debug", "POST $url"); httpResult(url, true)
        }
        ScriptableObject.putProperty(s, "http", http)

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
            val ctx = cx ?: Context.enter()
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
        }
        put(ocr, "mode") { _, _ -> null }
        put(ocr, "recognizeTextFromImage") { _, sc ->
            val ctx = cx ?: Context.enter()
            ctx.newArray(sc, state.buildNodes().map { it.text.ifEmpty { it.desc } }.filter { it.isNotEmpty() }.toTypedArray())
        }
        ScriptableObject.putProperty(s, "ocr", ocr)

        val threads = NativeObject()
        put(threads, "start") { a, sc ->
            val f = a.getOrNull(0)
            if (f is Function) {
                try { f.call(cx ?: Context.enter(), sc, sc, emptyArray()) }
                catch (e: Exception) { log("error", "子线程异常：${e.message}") }
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
        put(s, "\$log") { _, sc -> arrayOfStrings(sc, state.logs.map { it.msg }) }
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

    private fun truthy(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> v.isNotEmpty()
        else -> true
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
        /** Interpreted instructions between stop/timeout checks (~sub-millisecond). */
        const val INSTRUCTION_SLICE = 10_000
    }
}
