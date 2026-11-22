package cn.dumate.a6studio

import cn.dumate.a6studio.catalog.CodeGen
import cn.dumate.a6studio.model.ParamValue
import cn.dumate.a6studio.model.Samples
import cn.dumate.a6studio.model.newBlock
import org.mozilla.javascript.Context
import org.mozilla.javascript.EvaluatorException
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Text pasted from web pages/PDFs often carries JS line terminators (U+2028/U+2029) or NUL.
 * They are legal in HTML but terminate a JS string literal, so the generated script would be a
 * syntax error — silently breaking a script that looked fine in the editor.
 */
class QuoteSafetyTest {

    private fun syntaxError(src: String): String? {
        val cx = Context.enter()
        return try {
            cx.optimizationLevel = -1
            cx.languageVersion = Context.VERSION_ES6
            cx.compileFunction(cx.initStandardObjects(), src, "q", 1, null)
            null
        } catch (e: EvaluatorException) {
            e.details()
        } catch (e: Throwable) {
            e.message
        } finally {
            Context.exit()
        }
    }

    private val hostile = listOf(
        "U+2028" to "a\u2028b",
        "U+2029" to "a\u2029b",
        "NUL" to "a\u0000b",
        "backslash-quote" to "a\\\"b",
        "CRLF" to "a\r\nb",
        "bell" to "a\u0007b"
    )

    @Test
    fun `generated string literals stay valid JS for hostile text`() {
        val project = Samples.emptyProject("q")
        val failures = mutableListOf<String>()
        hostile.forEach { (label, text) ->
            val code = CodeGen.blockCode(newBlock("toast", mapOf("text" to ParamValue.Text(text))), project)
            syntaxError("function f() {\n$code\n}")?.let { failures += "$label -> $it :: $code" }
        }
        assertTrue(failures.isEmpty(), "invalid JS for:\n" + failures.joinToString("\n"))
    }
}
