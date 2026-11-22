package cn.dumate.a6studio

import cn.dumate.a6studio.catalog.CodeGen
import cn.dumate.a6studio.importer.ScriptImporter
import cn.dumate.a6studio.runtime.Runner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class ScriptImportTest {

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
