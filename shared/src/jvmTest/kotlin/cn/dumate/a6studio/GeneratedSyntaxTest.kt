package cn.dumate.a6studio

import cn.dumate.a6studio.catalog.Catalog
import cn.dumate.a6studio.catalog.CodeGen
import cn.dumate.a6studio.model.ParamValue
import cn.dumate.a6studio.model.Project
import cn.dumate.a6studio.model.Samples
import cn.dumate.a6studio.model.StudioModule
import cn.dumate.a6studio.model.newBlock
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.EvaluatorException
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Compile-level guard: every block's generated snippet must be parseable by the Rhino build we
 * ship. Catches syntax that looks fine in modern JS but that this engine rejects — e.g.
 * `for (const x of ...)`, which Rhino 1.9.x refuses while `for (let x of ...)` works.
 *
 * Only *syntax* is asserted here: the snippets reference AutoJs6 globals that do not exist on
 * the desktop, so `evaluateString` is fed a permissive stand-in and any non-syntax failure is
 * ignored (it means the parser accepted the code).
 */
class GeneratedSyntaxTest {

    private fun parses(source: String): String? {
        val cx = Context.enter()
        return try {
            cx.optimizationLevel = -1
            cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects()
            installPermissiveGlobals(cx, scope as ScriptableObject)
            cx.evaluateString(scope, source, "block", 1, null)
            null
        } catch (e: EvaluatorException) {
            "${e.details()}（行 ${e.lineNumber()}）"
        } catch (e: Throwable) {
            null // Runtime failure => parsed fine, which is all this guard checks.
        } finally {
            Context.exit()
        }
    }

    /** Chainable no-op object: any method call or property read returns itself. */
    private fun chainable(): NativeObject {
        val o = NativeObject()
        val fn = object : BaseFunction() {
            override fun call(c: Context?, s: Scriptable?, t: Scriptable?, args: Array<Any>?): Any = o
        }
        for (name in arrayOf(
            "click", "longClick", "setText", "scrollForward", "scrollBackward", "text", "desc",
            "id", "className", "bounds", "exists", "findOne", "parent", "child", "string", "json",
            "get", "put", "create", "read", "write", "append", "ensureDir", "launchPackage",
            "openUrl", "kill", "keepScreenOn", "vibrate", "requestScreenCapture", "captureScreen",
            "save", "recognizeText", "findImage", "start", "shutDownAll", "show", "hide", "clear",
            "log", "info", "warn", "error", "close", "length", "statusCode", "body", "forEach"
        )) {
            ScriptableObject.putProperty(o, name, fn)
        }
        val arr = object : BaseFunction() {
            override fun call(c: Context?, s: Scriptable?, t: Scriptable?, args: Array<Any>?): Any =
                (c ?: Context.enter()).newArray(s, 0)
        }
        ScriptableObject.putProperty(o, "find", arr)
        return o
    }

    private fun installPermissiveGlobals(cx: Context, scope: ScriptableObject) {
        val proxy = chainable()
        val globals = arrayOf(
            "auto", "console", "device", "app", "images", "ocr", "files", "storages", "http",
            "threads", "engines", "text", "desc", "id", "className", "textContains",
            "descContains", "textMatches", "descMatches", "idMatches", "classNameMatches",
            "shell", "gesture", "press", "click", "longClick", "swipe", "back", "home", "exit",
            "toast", "log", "sleep", "currentPackage", "currentActivity", "waitForPackage",
            "waitForActivity", "setClip", "getClip", "doTask", "task", "t0", "setTimeout",
            "clearTimeout", "setInterval"
        )
        globals.forEach { ScriptableObject.putProperty(scope, it, proxy) }
        // Things the snippets call as constructors / statics.
        ScriptableObject.putProperty(proxy, "now", object : BaseFunction() {
            override fun call(c: Context?, s: Scriptable?, t: Scriptable?, a: Array<Any>?): Any = 0L
        })
        ScriptableObject.putProperty(scope, "Error", BaseFunction())
        ScriptableObject.putProperty(scope, "JSON", chainable())
    }

    @Test
    fun `every block's generated code parses`() {
        val project = Samples.emptyProject("syntax")
        val failures = mutableListOf<String>()

        Catalog.all().forEach { def ->
            val block = newBlock(def.type)
            // Give containers a child so the nested-body path is parsed as well.
            val filled = if (def.slots.isNotEmpty()) {
                block.copy(children = block.children.mapValues { listOf(newBlock("log")) })
            } else block
            val code = CodeGen.blockCode(filled, project)
            if (code.isBlank()) return@forEach
            parses(code)?.let { err -> failures += "${def.type}: $err" }
        }

        assertTrue(failures.isEmpty(), "unparseable generated code:\n" + failures.joinToString("\n"))
    }

    @Test
    fun `sample project parses as a whole`() {
        val err = parses(CodeGen.code(Samples.exampleProject(), markers = false))
        assertTrue(err == null, "sample project failed to parse: $err")
    }

    @Test
    fun `module function bodies parse`() {
        val base = Samples.exampleProject()
        val extra = StudioModule(
            id = "m2",
            name = "带参数模块",
            args = "a, b",
            children = mapOf(
                "body" to listOf(
                    newBlock("logExpr", mapOf("expr" to ParamValue.Text("a + b"))),
                    newBlock("if", mapOf("cond" to ParamValue.Text("a > 0"))).let {
                        it.copy(children = mapOf("body" to listOf(newBlock("log")), "else" to emptyList()))
                    },
                    newBlock("forOf", mapOf("item" to ParamValue.Text("it"), "list" to ParamValue.Text("[1,2]"))).let {
                        it.copy(children = mapOf("body" to listOf(newBlock("log"))))
                    }
                )
            )
        )
        val project: Project = base.copy(modules = base.modules + extra)
        val err = parses(CodeGen.code(project, markers = false))
        assertTrue(err == null, "module code failed to parse: $err")
    }

    @Test
    fun `forOf emits let because const heads do not parse`() {
        val code = CodeGen.blockCode(newBlock("forOf"), Samples.emptyProject())
        assertTrue(code.contains("for (let "), "forOf must emit let: $code")
        assertFalse(code.contains("for (const "), "forOf must not emit const: $code")
    }
}