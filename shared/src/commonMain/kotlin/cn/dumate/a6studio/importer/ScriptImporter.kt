package cn.dumate.a6studio.importer

import cn.dumate.a6studio.model.Block
import cn.dumate.a6studio.model.ParamValue
import cn.dumate.a6studio.model.Project
import cn.dumate.a6studio.model.Samples
import cn.dumate.a6studio.model.newBlock

/** Outcome of turning an AutoJs6 script into a project. */
data class ImportResult(
    val project: Project,
    val mappedCount: Int,
    val rawCount: Int,
    val notes: List<String>
) {
    val total: Int get() = mappedCount + rawCount
}

/**
 * Imports a plain AutoJs6 (`.js`) script.
 *
 * The block catalogue cannot express arbitrary JavaScript, and silently dropping code would make
 * the imported project *behave differently* from the file the user actually runs. So this is a
 * best-effort mapper: statements it recognises become real blocks, everything else is preserved
 * verbatim inside a `rawJs` block. The generated script therefore stays equivalent to the input.
 */
object ScriptImporter {

    private const val BS = "\\"

    private data class Rule(val regex: Regex, val build: (MatchResult) -> Block?)

    private val rules: List<Rule> = listOf(
        Rule(Regex("""^\s*sleep\(\s*([0-9+\-*/\s.]+)\s*\)\s*;?\s*$""")) { m ->
            evalArithmetic(m.groupValues[1])?.let { newBlock("sleep", mapOf("ms" to ParamValue.Num(it))) }
        },
        Rule(Regex("""^\s*back\(\s*\)\s*;?\s*$""")) { newBlock("pressBack") },
        Rule(Regex("""^\s*home\(\s*\)\s*;?\s*$""")) { newBlock("pressHome") },
        Rule(Regex("""^\s*auto\.waitFor\(\s*\)\s*;?\s*$""")) { newBlock("autoWait") },
        Rule(Regex("""^\s*console\.show\(\s*\)\s*;?\s*$""")) { newBlock("consoleShow") },
        Rule(Regex("""^\s*exit\(\s*\)\s*;?\s*$""")) { newBlock("exitScript") },
        Rule(Regex("""^\s*toast\(\s*(['"`])(.*?)\1\s*\)\s*;?\s*$""")) { m ->
            newBlock("toast", mapOf("text" to ParamValue.Text(m.groupValues[2])))
        },
        Rule(Regex("""^\s*log\(\s*(['"`])(.*?)\1\s*\)\s*;?\s*$""")) { m ->
            newBlock("log", mapOf("text" to ParamValue.Text(m.groupValues[2])))
        },
        Rule(Regex("""^\s*app\.launchPackage\(\s*(['"`])(.*?)\1\s*\)\s*;?\s*$""")) { m ->
            newBlock("launchPackage", mapOf("pkg" to ParamValue.Text(m.groupValues[2])))
        },
        Rule(Regex("""^\s*app\.openUrl\(\s*(['"`])(.*?)\1\s*\)\s*;?\s*$""")) { m ->
            newBlock("openUrl", mapOf("url" to ParamValue.Text(m.groupValues[2])))
        },
        Rule(Regex("""^\s*app\.kill\(\s*(['"`])(.*?)\1\s*\)\s*;?\s*$""")) { m ->
            newBlock("killApp", mapOf("pkg" to ParamValue.Text(m.groupValues[2])))
        },
        Rule(Regex("""^\s*device\.keepScreenOn\(\s*([0-9+\-*/\s.]+)\s*\)\s*;?\s*$""")) { m ->
            evalArithmetic(m.groupValues[1])?.let { newBlock("keepScreenOn", mapOf("ms" to ParamValue.Num(it))) }
        },
        Rule(Regex("""^\s*device\.vibrate\(\s*([0-9+\-*/\s.]+)\s*\)\s*;?\s*$""")) { m ->
            evalArithmetic(m.groupValues[1])?.let { newBlock("vibrate", mapOf("ms" to ParamValue.Num(it))) }
        },
        Rule(Regex("""^\s*text\(\s*(['"`])(.*?)\1\s*\)\s*\.findOne\(\s*(\d+)\s*\)\s*\.click\(\s*\)\s*;?\s*$""")) { m ->
            newBlock("clickNode", mapOf(
                "by" to ParamValue.Text("text"),
                "value" to ParamValue.Text(m.groupValues[2]),
                "timeout" to ParamValue.Num(m.groupValues[3].toDouble())
            ))
        },
        Rule(Regex("""^\s*desc\(\s*(['"`])(.*?)\1\s*\)\s*\.findOne\(\s*(\d+)\s*\)\s*\.click\(\s*\)\s*;?\s*$""")) { m ->
            newBlock("clickNode", mapOf(
                "by" to ParamValue.Text("desc"),
                "value" to ParamValue.Text(m.groupValues[2]),
                "timeout" to ParamValue.Num(m.groupValues[3].toDouble())
            ))
        },
        Rule(Regex("""^\s*id\(\s*(['"`])(.*?)\1\s*\)\s*\.findOne\(\s*(\d+)\s*\)\s*\.click\(\s*\)\s*;?\s*$""")) { m ->
            newBlock("clickNode", mapOf(
                "by" to ParamValue.Text("id"),
                "value" to ParamValue.Text(m.groupValues[2]),
                "timeout" to ParamValue.Num(m.groupValues[3].toDouble())
            ))
        },
        Rule(Regex("""^\s*swipe\(\s*([0-9.]+)\s*,\s*([0-9.]+)\s*,\s*([0-9.]+)\s*,\s*([0-9.]+)\s*(?:,\s*([0-9.]+)\s*)?\)\s*;?\s*$""")) { m ->
            newBlock("swipe", mapOf(
                "x1" to ParamValue.Num(m.groupValues[1].toDouble()),
                "y1" to ParamValue.Num(m.groupValues[2].toDouble()),
                "x2" to ParamValue.Num(m.groupValues[3].toDouble()),
                "y2" to ParamValue.Num(m.groupValues[4].toDouble()),
                "dur" to ParamValue.Num(m.groupValues[5].toDoubleOrNull() ?: 400.0)
            ))
        },
        Rule(Regex("""^\s*click\(\s*([0-9.]+)\s*,\s*([0-9.]+)\s*\)\s*;?\s*$""")) { m ->
            newBlock("coordClick", mapOf(
                "x" to ParamValue.Num(m.groupValues[1].toDouble()),
                "y" to ParamValue.Num(m.groupValues[2].toDouble())
            ))
        }
    )

    private val commentLine = Regex("""^\s*//(.*)$""")

    /**
     * Tracks cross-line structure so a recognised statement is never lifted out of the block it
     * belongs to.
     *
     * Example of what went wrong before: `if (a) {` / `let b = 2` / `toast('x')` / `}` used to
     * emit `toast("x"); if (a) { let b = 2 }`, i.e. the toast fired unconditionally. Keeping
     * everything inside an open `{`/`(`/`[` as raw text preserves the original control flow, which
     * is the whole point of the raw block.
     */
    private class Scanner {
        var depth = 0
            private set
        var inBlockComment = false
            private set
        private var quote: Char? = null
        private var escaped = false

        /** True while we are inside an unfinished construct (block, call arguments, comment). */
        val insideConstruct: Boolean get() = depth > 0 || inBlockComment || quote != null

        fun scan(text: String) {
            var i = 0
            while (i < text.length) {
                val ch = text[i]
                if (inBlockComment) {
                    if (ch == '*' && i + 1 < text.length && text[i + 1] == '/') {
                        inBlockComment = false
                        i += 2
                        continue
                    }
                    i++
                    continue
                }
                val q = quote
                if (q != null) {
                    when {
                        escaped -> escaped = false
                        ch == BS[0] -> escaped = true
                        ch == q -> quote = null
                    }
                    i++
                    continue
                }
                when {
                    ch == '/' && i + 1 < text.length && text[i + 1] == '/' -> return // line comment
                    ch == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                        inBlockComment = true
                        i += 2
                        continue
                    }
                    ch == '"' || ch == SYM_SQUOTE || ch == SYM_BACKTICK -> quote = ch
                    ch == '(' || ch == '{' || ch == '[' -> depth++
                    ch == ')' || ch == '}' || ch == ']' -> if (depth > 0) depth--
                }
                i++
            }
            // A quote never survives a real line break in JS, so drop it at end of line: that
            // keeps one malformed line from swallowing the whole rest of the file.
            quote = null
            escaped = false
        }
    }

    /** Maps [source] into a project: recognised statements become blocks, the rest is kept raw. */
    fun import(source: String, name: String): ImportResult {
        FlowIrImporter.tryImport(source, name)?.let { return it }
        val body = mutableListOf<Block>()
        val notes = mutableListOf<String>()
        var mapped = 0
        var raw = 0

        fun pushRaw(code: String) {
            val text = code.trimEnd()
            if (text.isBlank()) return
            body += newBlock("rawJs", mapOf("code" to ParamValue.Text(text)))
            raw++
        }

        var pendingRaw = StringBuilder()

        fun flushRaw() {
            if (pendingRaw.isNotBlank()) pushRaw(pendingRaw.toString())
            pendingRaw = StringBuilder()
        }

        /** Maps one already-trimmed statement, or returns null when it is not recognised. */
        fun mapStatement(stmt: String): Block? =
            rules.firstNotNullOfOrNull { rule ->
                rule.regex.matchEntire(stmt)?.takeIf(::onlyPlainLiterals)?.let { rule.build(it) }
            }

        val scanner = Scanner()

        for (line in source.lines()) {
            if (line.isBlank()) {
                if (pendingRaw.isNotEmpty()) pendingRaw.append(line).append('\n')
                continue
            }

            // Whole-line comment -> note block, but never split an ongoing raw chunk and never
            // treat a comment inside a construct as a top-level note.
            val asComment = if (scanner.insideConstruct) null
            else commentLine.matchEntire(line)?.groupValues?.get(1)?.trim()
            if (asComment != null) {
                if (pendingRaw.isNotEmpty()) {
                    pendingRaw.append(line).append('\n')
                } else if (asComment.isNotEmpty()) {
                    body += newBlock("comment", mapOf("text" to ParamValue.Text(asComment)))
                }
                continue
            }

            // Several statements can share a line (`sleep(13*1000);back();`), but a line that is
            // part of an open construct must stay as one piece.
            val parts = if (scanner.insideConstruct) listOf(line) else splitTopLevelStatements(line)

            for (part in parts) {
                // Capture the state *before* this part: a statement that starts inside a block,
                // a comment or a multi-line call must never be turned into its own block.
                val startsAtTop = !scanner.insideConstruct
                scanner.scan(part)

                val block = if (startsAtTop) mapStatement(part.trim()) else null
                if (block != null) {
                    // Flush the pending raw chunk *first*: the source had it before this statement.
                    flushRaw()
                    body += block
                    mapped++
                } else {
                    pendingRaw.append(part).append('\n')
                }
            }
        }
        flushRaw()

        if (raw > 0) {
            notes += "$raw 段代码无法对应到积木，已原样放进「原生 JavaScript 代码」积木（不影响运行）"
        }
        if (mapped > 0) {
            notes += "$mapped 条语句已转成积木，可在画布上直接改参数"
        }

        val project = Samples.emptyProject(name.ifBlank { "导入的脚本" })
            .copy(root = Project.rootBlock().copy(children = mapOf("body" to body)))
        return ImportResult(project, mapped, raw, notes)
    }

    /**
     * S5: the rules capture string arguments with a lazy `(.*?)\1`, which also matched
     * `toast("a" + n + "b")` (captured `a" + n + "b`), escapes like `"he said \"hi\""` and
     * template strings with `${…}`. Turning those into a block with that text as a literal
     * silently changed what the script does. Only map when every captured string is plain
     * text; anything else stays verbatim in a rawJs block.
     */
    private fun onlyPlainLiterals(m: MatchResult): Boolean {
        val groups = m.groupValues.drop(1)
        val quoteMarks = setOf("\"", "'", "`")
        val usesTemplate = groups.any { it == "`" }
        return groups.all { g ->
            when {
                g in quoteMarks -> true
                g.contains(BS[0]) -> false
                g.any { it == '"' || it == SYM_SQUOTE || it == SYM_BACKTICK } -> false
                usesTemplate && g.contains("\${") -> false
                else -> true
            }
        }
    }

    /**
     * Splits a line into statements on semicolons that are not inside quotes or brackets.
     * Returns the line unchanged when it holds at most one statement.
     */
    private fun splitTopLevelStatements(line: String): List<String> {
        val parts = mutableListOf<String>()
        val cur = StringBuilder()
        var depth = 0
        var quote: Char? = null
        var escaped = false

        for (ch in line) {
            if (escaped) {
                cur.append(ch)
                escaped = false
                continue
            }
            val q = quote
            if (q != null) {
                cur.append(ch)
                if (ch == BS[0]) escaped = true
                else if (ch == q) quote = null
                continue
            }
            when (ch) {
                '"', SYM_SQUOTE, SYM_BACKTICK -> {
                    quote = ch
                    cur.append(ch)
                }
                '(' , '{', '[' -> {
                    depth++
                    cur.append(ch)
                }
                ')', '}', ']' -> {
                    depth--
                    cur.append(ch)
                }
                ';' -> {
                    if (depth == 0) {
                        // M14: keep the semicolon. Dropping it made raw chunks rely on automatic
                        // semicolon insertion, which joins lines like `a = b` / `(f)()` into
                        // `a = b(f)()`. The mapping rules accept an optional trailing `;`.
                        parts += cur.append(';').toString()
                        cur.clear()
                    } else {
                        cur.append(ch)
                    }
                }
                else -> cur.append(ch)
            }
        }
        if (cur.isNotBlank()) parts += cur.toString()

        val meaningful = parts.map { it.trim() }.filter { it.isNotEmpty() }
        return if (meaningful.size <= 1) listOf(line) else meaningful
    }

    private const val SYM_SQUOTE = '\''
    private const val SYM_BACKTICK = '`'

    /** Evaluates a simple numeric expression like `13*1000`; null when not purely arithmetic. */
    private fun evalArithmetic(expr: String): Double? {
        val clean = expr.replace(Regex("""\s+"""), "").trimEnd(';')
        if (clean.isEmpty()) return null
        if (!clean.all { it.isDigit() || it in "+-*/.()" }) return null
        val tokens = Regex("""\d+\.?\d*|[()*/+-]""").findAll(clean).map { it.value }.toList()
        if (tokens.joinToString("") != clean) return null
        return runCatching { Parser(tokens).parse() }.getOrNull()
    }

    /** Minimal recursive-descent parser: expr = term ((+|-) term)*, term = factor ((*|/) factor)*. */
    private class Parser(private val tokens: List<String>) {
        private var pos = 0

        fun parse(): Double {
            val v = expr()
            require(pos == tokens.size) { "trailing tokens" }
            return v
        }

        private fun expr(): Double {
            var v = term()
            while (pos < tokens.size && (tokens[pos] == "+" || tokens[pos] == "-")) {
                val op = tokens[pos++]
                val r = term()
                v = if (op == "+") v + r else v - r
            }
            return v
        }

        private fun term(): Double {
            var v = factor()
            while (pos < tokens.size && (tokens[pos] == "*" || tokens[pos] == "/")) {
                val op = tokens[pos++]
                val r = factor()
                v = if (op == "*") v * r else v / r
            }
            return v
        }

        private fun factor(): Double {
            require(pos < tokens.size) { "unexpected end" }
            val t = tokens[pos++]
            if (t == "(") {
                val v = expr()
                require(pos < tokens.size && tokens[pos] == ")") { "missing )" }
                pos++
                return v
            }
            return t.toDouble()
        }
    }
}