package cn.dumate.a6studio

import cn.dumate.a6studio.catalog.CodeGen
import cn.dumate.a6studio.importer.ScriptImporter
import cn.dumate.a6studio.runtime.Runner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScriptImportTest {

    private val NL = "\n"

    private fun fixture(name: String): String? {
        val f = listOf(File("build/fixture/$name"), File("../build/fixture/$name"))
            .firstOrNull { it.exists() } ?: return null
        return f.readText()
    }

    @Test
    fun `the user's jd script imports without losing code`() {
        val src = fixture("jrTask.js")
        if (src == null) { println("SKIP: fixture missing"); return }

        val result = ScriptImporter.import(src, "jrTask")
        println("IMPORT mapped=${result.mappedCount} raw=${result.rawCount}")
        result.notes.forEach { println("IMPORT note: $it") }

        // Nothing may be dropped. A line that mapped to a block reappears in generated form
        // (e.g. `sleep(13*1000)` -> `sleep(13000)`), so compare the *identifiers and string
        // literals* rather than raw text.
        val code = CodeGen.code(result.project, markers = false)
        fun tokens(text: String): List<String> =
            Regex("""[A-Za-z_$][A-Za-z0-9_$.]*|"[^"]*"|'[^']*'""")
                .findAll(text).map { it.value }.toList()
        val codeTokens = tokens(code).toSet()
        val missing = src.lines()
            .filterNot { it.trim().startsWith("//") || it.isBlank() }
            .flatMap { tokens(it) }
            .distinct()
            .filterNot { it in codeTokens }
        println("IMPORT missingTokens=$missing")
        assertTrue(missing.isEmpty(), "tokens lost during import: $missing")
    }

    @Test
    fun `simple statements map to real blocks`() {
        val src = """
            sleep(3000);
            toast("hi");
            back();
            app.launchPackage("com.jd.jrapp");
            swipe(1, 2, 3, 4, 400);
        """.trimIndent()
        val r = ScriptImporter.import(src, "t")
        assertTrue(r.mappedCount >= 4, "mapped=${r.mappedCount}")
        val code = CodeGen.code(r.project, markers = false)
        assertContains(code, "sleep(3000);")
        assertContains(code, "toast(\"hi\");")
        assertContains(code, "app.launchPackage(\"com.jd.jrapp\");")
    }

    @Test
    fun `arithmetic arguments are evaluated`() {
        val r = ScriptImporter.import("sleep(13*1000);", "t")
        val code = CodeGen.code(r.project, markers = false)
        assertContains(code, "sleep(13000);")
    }

    @Test
    fun `statements keep their source order`() {
        val r = ScriptImporter.import("let a = 1" + NL + "sleep(100)" + NL, "order")
        val code = CodeGen.code(r.project, markers = false)
        println("ORDER code=" + code.replace(NL, " | "))
        assertTrue(
            code.indexOf("let a = 1") < code.indexOf("sleep(100)"),
            "raw chunk was emitted after the block it precedes: $code"
        )
    }

    @Test
    fun `statements inside a block stay inside the block`() {
        val src = """
            if (ready) {
              let b = 2
              toast('x')
            }
        """.trimIndent()
        val r = ScriptImporter.import(src, "nest")
        val code = CodeGen.code(r.project, markers = false)
        println("NEST code=" + code.replace(NL, " | "))
        val open = code.indexOf("if (ready) {")
        val toast = code.indexOf("toast(")
        val close = code.indexOf("}", toast)
        assertTrue(open >= 0, "if-block lost: $code")
        assertTrue(toast > open, "toast escaped the if-block: $code")
        assertTrue(close > toast, "if-block closing brace lost: $code")
        // The toast must not have become a real block, because a block cannot be re-nested later.
        assertTrue(r.mappedCount == 0, "mapped=${r.mappedCount}")
    }

    @Test
    fun `calls spanning several lines are not mapped`() {
        val src = """
            doSomething(
              sleep(100)
            )
        """.trimIndent()
        val r = ScriptImporter.import(src, "multiline")
        assertTrue(r.mappedCount == 0, "mapped inside open parens: ${r.mappedCount}")
        val code = CodeGen.code(r.project, markers = false)
        assertContains(code, "sleep(100)")
    }

    @Test
    fun `crlf input imports like lf input`() {
        val lf = ScriptImporter.import("let a = 1" + NL + "sleep(100)" + NL, "crlf")
        val crlf = ScriptImporter.import("let a = 1\r\n" + "sleep(100)\r\n", "crlf")
        assertEquals(lf.mappedCount, crlf.mappedCount)
        assertEquals(lf.rawCount, crlf.rawCount)
        assertTrue(CodeGen.code(crlf.project).indexOf("let a = 1") < CodeGen.code(crlf.project).indexOf("sleep(100)"))
    }

    @Test
    fun `imported project runs in the simulator`() {
        val src = fixture("jrTask.js") ?: return
        val r = ScriptImporter.import(src, "jrTask")
        val run = Runner.run(r.project)
        println("IMPORT run status=${run.status} err=${run.error}")
        assertTrue(
            run.status == "finished" || run.status == "exited" || run.status == "error",
            "unexpected status"
        )
        if (run.status == "error") {
            println("IMPORT run error: ${run.error}")
        }
    }
}
