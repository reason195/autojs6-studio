package cn.dumate.a6studio

import cn.dumate.a6studio.importer.ScriptImporter
import cn.dumate.a6studio.model.Block
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** S5 + M14: importing must never change what the script does. */
class ImportEquivalenceTest {

    private fun body(src: String): List<Block> =
        ScriptImporter.import(src, "t").project.root.children["body"].orEmpty()

    private fun rawText(src: String): String =
        body(src).filter { it.type == "rawJs" }.joinToString("\n") { it.params["code"]?.asText().orEmpty() }

    @Test
    fun `plain literal still becomes a block`() {
        val b = body("""toast("你好");""")
        assertEquals(listOf("toast"), b.map { it.type })
        assertEquals("你好", b[0].params["text"]?.asText())
    }

    @Test
    fun `string concatenation stays raw`() {
        val src = """toast("a" + n + "b");"""
        assertTrue(body(src).none { it.type == "toast" })
        assertContains(rawText(src), """toast("a" + n + "b")""")
    }

    @Test
    fun `escaped quotes stay raw`() {
        val src = """log("he said \"hi\"");"""
        assertTrue(body(src).none { it.type == "log" })
        assertContains(rawText(src), """\"hi\"""")
    }

    @Test
    fun `template strings with interpolation stay raw`() {
        val src = "toast(`第 \${i} 次`);"
        assertTrue(body(src).none { it.type == "toast" })
        assertContains(rawText(src), "\${i}")
    }

    @Test
    fun `semicolons are kept in raw code`() {
        val raw = rawText("let a = 1; let b = a")
        assertContains(raw, "let a = 1;")
    }

    @Test
    fun `mapped statements split from one line keep working`() {
        val b = body("sleep(13*1000);back();")
        assertEquals(listOf("sleep", "pressBack"), b.map { it.type })
    }
}
