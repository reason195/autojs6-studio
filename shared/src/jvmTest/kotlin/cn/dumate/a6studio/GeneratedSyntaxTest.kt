
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
            cx.isInterpretedMode = true
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
            "shell", "gesture", "press", "KeyCode", "click", "longClick", "swipe", "back", "home", "recents", "exit",
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

    @Test
    fun `the same fixed-name block twice in one scope still parses`() {
        // M4: jdSign / dumpUi … declare fixed `let` names; two in a row used to be a
        // redeclaration SyntaxError that stopped the whole script.
        val failures = mutableListOf<String>()
        for (type in listOf("dumpUi", "jdSign", "jdOpenPanel", "jdTaskLoop", "jdFinishGuard")) {
            val base = Samples.emptyProject()
            val twice = base.copy(
                root = base.root.copy(children = mapOf("body" to listOf(newBlock(type), newBlock(type))))
            )
            parses(CodeGen.code(twice, markers = false))?.let { failures += "$type: $it" }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    // ── 文本进注释 / 函数签名 / 目录（审查报告 M5、M13） ────────────────────────

    @Test
    fun `project name cannot close the header comment`() {
        val base = Samples.emptyProject()
        val evil = base.copy(name = "x */ log(\"injected\"); /**")
        val code = CodeGen.code(evil, markers = false)
        parses(code)?.let { assertTrue(false, "header not parseable: $it") }
        // 名字里的 */ 被中和后，头注释的结束标记仍必须在最后（否则后面整段会变成代码）。
        val firstClose = code.indexOf("*/")
        val lastNote = code.indexOf("目标运行时")
        assertTrue(firstClose > lastNote, code.lineSequence().take(8).joinToString("\n"))
        assertTrue(code.contains("x * / log"), code.lineSequence().take(8).joinToString("\n"))
    }

    @Test
    fun `module description cannot close its comment`() {
        val base = Samples.emptyProject()
        val module = StudioModule(
            id = "m1",
            name = "helper",
            desc = "说明 */ log(\"bad\");",
            args = "pkg",
            children = mapOf("body" to listOf(newBlock("log")))
        )
        val code = CodeGen.code(base.copy(modules = listOf(module)), markers = false)
        parses(code)?.let { assertTrue(false, "module not parseable: $it") }
        val descAt = code.indexOf("模块：")
        val closeAt = code.indexOf("*/", descAt)
        val note = code.substring(descAt, closeAt)
        assertTrue(note.contains("说明 * / log"), note)
    }

    @Test
    fun `module args that are not identifiers are dropped`() {
        val base = Samples.emptyProject()
        val module = StudioModule(
            id = "m2",
            name = "helper",
            desc = "",
            args = "pkg, a b, x-y, 1st",
            children = mapOf("body" to listOf(newBlock("log")))
        )
        val code = CodeGen.code(base.copy(modules = listOf(module)), markers = false)
        parses(code)?.let { assertTrue(false, "signature not parseable: $it") }
        assertTrue(code.contains("function mod_helper(pkg)"), code.lineSequence().first { it.contains("function") })
    }

    @Test
    fun `comment block with a carriage return stays a comment`() {
        val block = newBlock("comment").copy(params = mapOf("text" to ParamValue.Text("说明\r下一行()")) )
        val code = CodeGen.blockCode(block, Samples.emptyProject())
        assertFalse(code.contains("\r"), code)
        assertTrue(code.lines().size == 1, "comment must stay one line: $code")
        parses(code)?.let { assertTrue(false, "comment not parseable: $it") }
    }

    @Test
    fun `while guard with a multi-line condition still parses`() {
        val block = newBlock("whileGuard").copy(
            params = mapOf(
                "cond" to ParamValue.Text("a &&\nb"),
                "max" to ParamValue.Num(3.0)
            ),
            children = mapOf("body" to listOf(newBlock("log")))
        )
        val code = CodeGen.blockCode(block, Samples.emptyProject())
        parses(code)?.let { assertTrue(false, "whileGuard not parseable: $it") }
    }

    @Test
    fun `file blocks create the folder the file lives in`() {
        // AutoJs6 的 ensureDir(p) 建的是 p 所在目录（真机实测）；多套一层 dirname 会漏建。
        for (type in listOf("saveScreen", "filesWrite")) {
            val code = CodeGen.blockCode(newBlock(type), Samples.emptyProject())
            assertTrue(
                code.contains("files.ensureDir(\"/sdcard/autojs6"),
                "$type should ensure the file's own folder: $code"
            )
            assertTrue(
                !code.contains("ensureDir(\"/sdcard\")"),
                "$type must not truncate the path to its grandparent: $code"
            )
            parses(code)?.let { assertTrue(false, "$type not parseable: $it") }
        }
    }
}
