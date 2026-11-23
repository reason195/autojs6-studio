package cn.dumate.a6studio.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One device found in pasted / imported MCP configuration. */
data class McpServerConfig(val name: String, val url: String, val token: String = "")

/**
 * Reads MCP connection settings from whatever the user has at hand, so nobody has to retype a
 * URL and a 40-character token on a phone keyboard.
 *
 * Accepted, in any mix:
 *  1. AutoJs6 plugin export / Claude / Cursor style: `{"mcpServers": {"名字": {"url": …,
 *     "headers": {"Authorization": "Bearer …"}}}}`. Several objects pasted back to back (what the
 *     plugin page produces when you export twice) are all read — same as tools/live-check.py.
 *  2. VS Code style `{"servers": {…}}`, a bare server object `{"url": …, "token": …}`, or an array.
 *  3. Plain text, one device per line:
 *       http://192.168.1.6:9637/mcp  5L0F…m0
 *       192.168.1.17                      (port 9637 and /mcp are filled in)
 *       url=http://…  token=…  name=一加
 *     A following `Authorization: Bearer …` or `token: …` line belongs to the URL above it.
 *
 * Anything unrecognised is skipped; [parse] never throws.
 */
object McpConfigParser {

    const val DEFAULT_PORT = 9637

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String): List<McpServerConfig> {
        val out = mutableListOf<McpServerConfig>()
        val (objects, rest) = splitJson(text)
        objects.forEach { raw ->
            runCatching { json.parseToJsonElement(raw) }.getOrNull()?.let { fromJson(it, out) }
        }
        fromText(rest, out)
        // Same address twice (exported twice, or pasted JSON + the URL again): keep the entry
        // that carries a token, else the first one.
        return out.groupBy { normalizeUrl(it.url) }
            .map { (_, same) -> same.firstOrNull { it.token.isNotEmpty() } ?: same.first() }
    }

    // ---- JSON -------------------------------------------------------------------------------

    private fun fromJson(el: JsonElement, out: MutableList<McpServerConfig>) {
        when (el) {
            is JsonArray -> el.forEach { fromJson(it, out) }
            is JsonObject -> {
                val group = (el["mcpServers"] ?: el["servers"] ?: el["mcp_servers"]) as? JsonObject
                if (group != null) {
                    group.forEach { (name, cfg) -> (cfg as? JsonObject)?.let { server(name, it)?.let(out::add) } }
                } else {
                    server("", el)?.let(out::add)
                }
            }
            else -> Unit
        }
    }

    private fun str(o: JsonObject, vararg keys: String): String =
        keys.firstNotNullOfOrNull { k -> (o[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } }.orEmpty()

    private fun server(name: String, o: JsonObject): McpServerConfig? {
        val url = str(o, "url", "serverUrl", "endpoint", "baseUrl")
        if (url.isEmpty()) return null
        val headers = o["headers"] as? JsonObject
        val auth = headers?.let { h -> h.entries.firstOrNull { it.key.equals("Authorization", true) }?.value }
            ?.let { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val token = stripBearer(auth.ifEmpty { str(o, "token", "apiKey", "bearer", "authorization") })
        return McpServerConfig(name.ifEmpty { str(o, "name", "title") }, normalizeUrl(url), token)
    }

    /**
     * Pulls every top-level `{…}` / `[…]` out of [text] (quotes and escapes respected) and
     * returns them together with the text that was left over between them.
     */
    private fun splitJson(text: String): Pair<List<String>, String> {
        val objects = mutableListOf<String>()
        val rest = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '{' && c != '[') { rest.append(c); i++; continue }
            var depth = 0
            var inStr = false
            var esc = false
            var j = i
            var closed = -1
            while (j < text.length) {
                val ch = text[j]
                if (inStr) {
                    when {
                        esc -> esc = false
                        ch == '\\' -> esc = true
                        ch == '"' -> inStr = false
                    }
                } else when (ch) {
                    '"' -> inStr = true
                    '{', '[' -> depth++
                    '}', ']' -> { depth--; if (depth == 0) { closed = j; break } }
                }
                j++
            }
            if (closed < 0) { rest.append(text.substring(i)); break }
            objects += text.substring(i, closed + 1)
            rest.append('\n')
            i = closed + 1
        }
        return objects to rest.toString()
    }

    // ---- plain text -------------------------------------------------------------------------

    private val urlRe = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
    private val hostRe = Regex("""^((?:\d{1,3}\.){3}\d{1,3}|localhost)(?::(\d{2,5}))?(/\S*)?$""", RegexOption.IGNORE_CASE)
    private val kvRe = Regex("""(\w+)\s*[=:]\s*("[^"]*"|\S+)""")

    private fun fromText(text: String, out: MutableList<McpServerConfig>) {
        var last: Int = -1 // index in out of the last device read from text, for a token on the next line
        for (rawLine in text.lines()) {
            val line = rawLine.trim().trim(',', ';')
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue

            // `Authorization: Bearer x` / `token: x` on its own line → belongs to the device above.
            val lower = line.lowercase()
            if (last >= 0 && (lower.startsWith("authorization") || lower.startsWith("token") || lower.startsWith("bearer "))) {
                val tok = stripBearer(line.substringAfter(':', line.substringAfter('=', line)).trim())
                if (tok.isNotEmpty() && out[last].token.isEmpty()) out[last] = out[last].copy(token = tok)
                continue
            }

            val kv = kvRe.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2].trim('"') }
            val url = urlRe.find(line)?.value
                ?: kv["url"]
                ?: line.split(Regex("\\s+")).firstOrNull()?.takeIf { hostRe.matches(it) }
                ?: continue
            val tokenFromKv = kv["token"] ?: kv["authorization"] ?: kv["bearer"]
            // Otherwise the token is the first word after the URL that is not another key=value.
            val after = line.substringAfter(url, "").trim()
            val bare = after.split(Regex("\\s+")).firstOrNull { it.isNotEmpty() && !it.contains('=') && !it.contains(':') && it.length >= 8 }
            val token = stripBearer(tokenFromKv ?: bare.orEmpty())
            out += McpServerConfig(kv["name"].orEmpty(), normalizeUrl(url), token)
            last = out.lastIndex
        }
    }

    // ---- helpers ----------------------------------------------------------------------------

    fun stripBearer(s: String): String = s.trim().removePrefix("Bearer ").removePrefix("bearer ").trim()

    /** `192.168.1.6` → `http://192.168.1.6:9637/mcp`; full URLs only lose a trailing slash. */
    fun normalizeUrl(raw: String): String {
        val s = raw.trim().trimEnd('/', ',', ';', ')', '"', '\'')
        if (s.startsWith("http://", true) || s.startsWith("https://", true)) {
            val afterScheme = s.substringAfter("://")
            return if (afterScheme.contains('/')) s else "$s/mcp"
        }
        val m = hostRe.matchEntire(s) ?: return s
        val port = m.groupValues[2].ifEmpty { DEFAULT_PORT.toString() }
        val path = m.groupValues[3].ifEmpty { "/mcp" }
        return "http://${m.groupValues[1]}:$port$path"
    }

    /** Short label for a device without a name: host:port. */
    fun label(c: McpServerConfig): String =
        c.name.ifEmpty { c.url.removePrefix("http://").removePrefix("https://").removeSuffix("/mcp") }
}
