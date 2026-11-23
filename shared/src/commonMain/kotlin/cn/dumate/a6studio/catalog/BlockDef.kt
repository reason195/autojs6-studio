package cn.dumate.a6studio.catalog

import cn.dumate.a6studio.model.Block
import cn.dumate.a6studio.model.ParamValue
import cn.dumate.a6studio.model.Project

/** Parameter editor kind, mirroring the web `kind` field. */
enum class ParamKind { TEXT, EXPR, NUMBER, SELECT, CODE, CHECKBOX }

data class SelectOption(val value: String, val label: String)

data class ParamDef(
    val key: String,
    val label: String,
    val kind: ParamKind,
    val default: ParamValue = ParamValue.Text(""),
    val options: List<SelectOption> = emptyList()
)

data class SlotDef(val key: String, val label: String)

data class Category(val id: String, val name: String, val color: Long)

data class BlockDef(
    val type: String,
    val cat: String,
    val name: String,
    val params: List<ParamDef> = emptyList(),
    val slots: List<SlotDef> = emptyList(),
    val gen: (Block, GenContext) -> String
)

/**
 * Code-generation context. Kept intentionally close to the original JS `c` helper so the
 * emitted scripts stay byte-for-byte compatible with what the web build produced.
 */
class GenContext(
    val project: Project,
    val markers: Boolean = false
) {
    private var counter = 0

    fun value(block: Block, key: String): ParamValue {
        val def = Catalog.byType(block.type)?.params?.firstOrNull { it.key == key }
        val raw = block.params[key]
        return if (raw == null || raw.isBlank()) (def?.default ?: ParamValue.Text("")) else raw
    }

    fun str(block: Block, key: String): String = value(block, key).asText()

    fun num(block: Block, key: String): Double = value(block, key).asNumber()

    /** JSON.stringify equivalent — used everywhere the original wrote `c.q(...)`. */
    fun quote(text: String?): String = Js.quote(text ?: "")

    fun newVar(prefix: String = "v"): String {
        counter += 1
        return "_" + prefix + counter
    }

    fun has(block: Block, slot: String): Boolean = !block.children[slot].isNullOrEmpty()

    fun body(block: Block, slot: String, level: Int): String = Js.indentLines(
        CodeGen.generateList(block.children[slot].orEmpty(), project, this), level
    )

    /** Selector expression for a UI block: an explicit `expr` wins, then by/value. */
    fun selector(block: Block): String {
        val expr = block.params["expr"]?.asText()?.trim()
        if (!expr.isNullOrEmpty()) return "($expr)"
        val by = block.params["by"]?.asText() ?: "text"
        val value = block.params["value"]?.asText() ?: ""
        val fn = when (by) {
            "id" -> "id"
            "idMatches" -> "idMatches"
            "className" -> "className"
            "classNameMatches" -> "classNameMatches"
            "textContains" -> "textContains"
            "textMatches" -> "textMatches"
            "descContains" -> "descContains"
            "descMatches" -> "descMatches"
            "desc" -> "desc"
            else -> "text"
        }
        return "$fn(${quote(value)})"
    }

    fun callModule(block: Block): String {
        val key = block.params["module"]?.asText() ?: ""
        // L10: match by id only. The old name fallback let a renamed module, or a new module
        // that happened to reuse an old name, silently receive calls meant for another one.
        // Old projects that stored names are migrated to ids once, in ProjectRepo.load().
        val m = project.modules.firstOrNull { it.id == key }
            ?: return "// 未选择模块"
        val args = block.params["args"]?.asText().orEmpty()
        return Js.moduleName(m.name) + "(" + args + ");"
    }
}

object Js {
    /** Equivalent of JSON.stringify on a string: escapes quotes, backslashes and control chars. */
    fun quote(text: String): String {
        val sb = StringBuilder("\"")
        for (ch in text) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                // U+2028 / U+2029 are JS line terminators. Modern engines (ES2019) accept
                // them inside literals, but older Rhino builds choke — escaping is free.
                '\u2028' -> sb.append("\\u2028")
                '\u2029' -> sb.append("\\u2029")
                else ->
                    if (ch < ' ') sb.append("\\u" + ch.code.toString(16).padStart(4, '0'))
                    else sb.append(ch)
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    /**
     * Text that ends up inside a line comment or a block comment.
     *
     * Only line terminators matter here: one stray CR turns the rest of a line comment into
     * code (`// 说明` + CR + `下一行` runs 「下一行」). For block comments the closing marker is
     * neutralised too, otherwise an imported project name could end the comment early and the
     * rest of it would be compiled as code that the UI never shows.
     */
    fun commentSafe(text: String, block: Boolean = false): String {
        var out = text
            .replace("\r\n", " ")
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace("\u2028", " ")
            .replace("\u2029", " ")
        if (block) out = out.replace("*/", "* /")
        return out
    }

    /** Identifier-safe text: keeps letters/digits/underscore/dollar and the CJK range. */
    fun idSafe(text: String): String {
        val cleaned = text.map { ch ->
            if (ch.isLetterOrDigit() || ch == '_' || ch == '$' || (ch.code in 0x4E00..0x9FA5)) ch else '_'
        }.joinToString("")
        return if (cleaned.isEmpty() || cleaned[0].isDigit()) "_$cleaned" else cleaned
    }

    /** True when [text] can be used as a parameter name without quoting. */
    fun isIdentifier(text: String): Boolean =
        text.isNotEmpty() && (text[0].isLetter() || text[0] == '_' || text[0] == '$') &&
            text.all { it.isLetterOrDigit() || it == '_' || it == '$' }

    fun indentLines(text: String, level: Int): String {
        if (text.isEmpty() || level <= 0) return text
        val pad = "  ".repeat(level)
        return text.split('\n').joinToString("\n") { if (it.isEmpty()) it else pad + it }
    }

    /** `mod_<name>` with non-identifier characters replaced (keeps CJK, like the web regex). */
    fun moduleName(name: String): String {
        val cleaned = name.map { ch ->
            if (ch.isLetterOrDigit() || ch == '_' || (ch.code in 0x4E00..0x9FA5)) ch else '_'
        }.joinToString("")
        return "mod_$cleaned"
    }
}

internal fun indentLines(text: String, level: Int) = Js.indentLines(text, level)