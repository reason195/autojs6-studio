package cn.dumate.a6studio.recorder

import cn.dumate.a6studio.bridge.McpClient
import cn.dumate.a6studio.model.ParamValue
import cn.dumate.a6studio.model.newBlock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One recorded device event (tap / swipe / key / markers). */
data class RecEvent(
    val type: String,
    val x: Int = 0,
    val y: Int = 0,
    val x1: Int = 0,
    val y1: Int = 0,
    val x2: Int = 0,
    val y2: Int = 0,
    val dur: Int = 0,
    val key: String = "",
    val pkg: String = "",
    val message: String = "",
    val screenW: Int = 0,
    val screenH: Int = 0,
    val node: RecNode? = null
) {
    val usable: Boolean get() = type == "tap" || type == "swipe" || type == "key"
}

/** A node captured device-side, with hit counts already computed for each candidate selector. */
data class RecNode(
    val text: String = "",
    val desc: String = "",
    val id: String = "",
    val className: String = "",
    val counts: Map<String, Int> = emptyMap()
) {
    val label: String get() = text.ifEmpty { desc.ifEmpty { id.substringAfterLast('/').ifEmpty { className } } }
}

enum class RecorderStatus { IDLE, RECORDING, FETCHING, DONE, ERROR }

data class RecorderState(
    val status: RecorderStatus = RecorderStatus.IDLE,
    val events: List<RecEvent> = emptyList(),
    val inserted: Int = 0,
    val note: String = "",
    val executionId: Long? = null,
    val consecutiveTimeouts: Int = 0
) {
    val usableCount: Int get() = events.count { it.usable }
    val freshCount: Int get() = (usableCount - inserted).coerceAtLeast(0)
}

/**
 * Device-side recorder. The listening script runs on the phone (Rhino, ES5) and appends JSONL
 * events to a file; the IDE reads the file back and turns events into blocks.
 *
 * Known constraint (measured on device): once a script holds `events.observeTouch()`, the same
 * process's MCP server stops answering — so recording is "communicate-free" and the read-back
 * happens after the script stops.
 */
class RecorderController(
    private val bridge: McpClient,
    private val onState: (RecorderState) -> Unit
) {
    companion object {
        const val OUT_PATH = "studio-record.jsonl"

        /** Rhino/ES5 script pushed to the device. Kept verbatim from the web build. */
        val DEVICE_SCRIPT: String = """
var ARGS = (engines.myEngine().execArgv && engines.myEngine().execArgv) || {};
var OUT = ARGS.out || "/sdcard/studio-record.jsonl";
var MAX = Number(ARGS.max || 300);
var n = 0;
// 每次开始录制先清空：之前只 append，第二次录制读到的是「上一轮 + 这一轮」，
// 而随后的生成逻辑按 inserted=0 计算，旧操作会被再生成一遍积木。
try { files.write(OUT, ""); } catch (e) { try { files.remove(OUT); } catch (e2) {} }
function emit(o) {
  if (n >= MAX) return;
  n++;
  try { files.append(OUT, JSON.stringify(o) + "\n"); } catch (e) { console.error("emit " + e); }
}
function cnt(f) { try { var r = f.find(); return r ? r.length : 0; } catch (e) { return -1; } }
function nodeAt(x, y) {
  try {
    var list = clickable(true).boundsContains(x - 4, y - 4, x + 4, y + 4).find();
    if (!list || !list.length) return null;
    var best = null, area = 1e12;
    for (var i = 0; i < list.length; i++) {
      var bd = list[i].bounds();
      var ar = (bd.right - bd.left) * (bd.bottom - bd.top);
      if (ar < area) { area = ar; best = list[i]; }
    }
    if (!best) return null;
    var b2 = best.bounds();
    var txt = String(best.text() || "");
    var dsc = String(best.desc() || "");
    var vid = String(best.id() || "");
    var cs = { id: -1, text: -1, textContains: -1, desc: -1 };
    if (vid) cs.id = cnt(id(vid));
    if (cs.id !== 1 && txt) cs.text = cnt(text(txt));
    if (cs.id !== 1 && cs.text !== 1 && txt) cs.textContains = cnt(textContains(txt));
    if (cs.id !== 1 && cs.text !== 1 && cs.textContains !== 1 && dsc) cs.desc = cnt(desc(dsc));
    return {
      text: txt, desc: dsc, id: vid, className: String(best.className() || ""),
      bounds: { left: b2.left, top: b2.top, right: b2.right, bottom: b2.bottom },
      counts: cs
    };
  } catch (e) { return null; }
}
var pts = [];
var timer = null;
function flush() {
  timer = null;
  if (!pts.length) return;
  var a = pts[0], b = pts[pts.length - 1];
  var dx = b.x - a.x, dy = b.y - a.y;
  var dist = Math.sqrt(dx * dx + dy * dy);
  var pkg = "";
  try { pkg = currentPackage(); } catch (e) {}
  if (pts.length >= 2 && dist >= 40) {
    emit({ type: "swipe", t: a.t, dur: Math.max(200, b.t - a.t), x1: a.x, y1: a.y, x2: b.x, y2: b.y, pkg: pkg });
  } else {
    emit({ type: "tap", t: a.t, x: b.x, y: b.y, pkg: pkg, node: nodeAt(b.x, b.y) });
  }
  pts = [];
}
function onPoint(p) {
  if (!p) return;
  var px = Number(p.x) || 0, py = Number(p.y) || 0;
  if (px > device.width) px = px / 100;
  if (py > device.height) py = py / 100;
  pts.push({ x: Math.round(px), y: Math.round(py), t: Date.now() });
  if (timer) clearTimeout(timer);
  timer = setTimeout(flush, 420);
}
var cap = { observeTouch: typeof events.observeTouch, onTouch: typeof events.onTouch, observeKey: typeof events.observeKey };
emit({ type: "start", t: Date.now(), cap: cap, path: OUT, screen: { w: device.width, h: device.height }, pkg: (function () { try { return currentPackage(); } catch (e) { return ""; } })() });
try { toast("录制已开始：操作手机即可，按音量下键停止"); } catch (e) {}
var ok = true, why = "";
try {
  if (typeof events.observeTouch === "function") events.observeTouch();
  if (typeof events.onTouch === "function") events.onTouch(onPoint);
  else if (typeof events.on === "function") events.on("touch", onPoint);
  else ok = false;
} catch (e) { ok = false; why = String(e); }
if (!ok) emit({ type: "error", t: Date.now(), message: why || "touch listener unavailable" });
try {
  if (typeof events.observeKey === "function") {
    events.observeKey();
    events.onKeyDown("back", function () { emit({ type: "key", key: "back", t: Date.now() }); });
    events.onKeyDown("home", function () { emit({ type: "key", key: "home", t: Date.now() }); });
  }
} catch (e) {}
try { events.onKeyDown("volume_down", function () { try { toast("录制已停止"); } catch (e) {} exit(); }); } catch (e) {}
setInterval(function () {}, 1000);
""".trimIndent()

        /**
         * Execution id from `script_run`'s reply. AutoJs6 hands out a Long that can exceed Int
         * (the old `intOrNull` then produced null, so the recording script could never be
         * stopped). Pure so it can be tested without a bridge.
         */
        fun parseExecutionId(raw: String): Long? = runCatching {
            val lenient = Json { ignoreUnknownKeys = true; isLenient = true }
            lenient.parseToJsonElement(raw).jsonObject.let { o ->
                o["executionId"]?.jsonPrimitive?.longOrNull
                    ?: o["id"]?.jsonPrimitive?.longOrNull
            }
        }.getOrNull()

        val EXPORT_BANNER: String = """
/* AutoJs6 Studio · 录制脚本（离线版）
 * 用法：把本文件放到手机，用 AutoJs6 打开并运行；操作手机即可录制。
 * 停止：按音量下键（或从 AutoJs6 悬浮窗停止）。
 * 产出：/sdcard/studio-record.jsonl（一行一事件）。
 * 回到 Studio：「录制」面板 →「导入文件」选这个 jsonl，再点「生成积木」。
 */
"""
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    var state = RecorderState()
        private set

    private fun update(f: (RecorderState) -> RecorderState) {
        state = f(state)
        onState(state)
    }

    suspend fun start() {
        update { RecorderState(status = RecorderStatus.RECORDING, note = "正在把监听脚本推到手机…") }
        try {
            val args = buildJsonObject {
                put("out", OUT_PATH)
                put("max", 300)
            }
            val raw = bridge.scriptRun(DEVICE_SCRIPT, "a6studio-record", args, waitForCompletion = false)
            val id = parseExecutionId(raw)
            update {
                it.copy(
                    executionId = id,
                    note = "已开始（executionId=$id）。现在在手机上操作；录制期间不与手机通信，" +
                        "操作完在手机上停止脚本，再点「读取并生成」。"
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // M1: cancellation must propagate, or Stop/close never takes effect
        } catch (e: Exception) {
            update { it.copy(status = RecorderStatus.ERROR, note = "启动失败：${e.message}") }
        }
    }

    suspend fun stop() {
        update { it.copy(status = RecorderStatus.IDLE) }
        val id = state.executionId ?: return
        try {
            bridge.scriptStop(id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Swallowing this left the listener script running on the phone while the UI said
            // "stopped"; the next recording then appended to the same file.
            update {
                it.copy(
                    status = RecorderStatus.ERROR,
                    note = "停止失败：${e.message}。手机上的录制脚本可能还在运行，请按音量下键手动停止。"
                )
            }
        }
    }

    /** Reads the JSONL file and normalises events (some devices report 1/100 px coordinates). */
    suspend fun fetchEvents(): Int {
        update { it.copy(status = RecorderStatus.FETCHING, note = "正在读取手机上的 $OUT_PATH…") }
        return try {
            val text = bridge.filesRead(OUT_PATH)
            val events = normalizeEvents(parseLines(text ?: ""))
            update {
                it.copy(
                    status = RecorderStatus.DONE,
                    events = events,
                    consecutiveTimeouts = 0,
                    note = if (events.isEmpty()) "手机上的 $OUT_PATH 里还没有内容（这次录制没有写出事件，或工作目录不对）。"
                    else "已读到 ${events.size} 行事件，点「生成积木」把它们变成积木。"
                )
            }
            events.size
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // M1: cancellation must propagate, or Stop/close never takes effect
        } catch (e: Exception) {
            update { it.copy(status = RecorderStatus.ERROR, note = "读取失败：${e.message}") }
            0
        }
    }

    fun importText(text: String): Int {
        val events = normalizeEvents(parseLines(text))
        update {
            it.copy(
                status = RecorderStatus.DONE,
                events = events,
                inserted = 0,
                note = if (events.isEmpty()) "这个文件里没有可识别的事件"
                else "已导入 ${events.size} 行事件，点「生成积木」把它们变成积木。"
            )
        }
        return events.size
    }

    fun reset() = update { RecorderState() }

    /** Marks the current events as consumed so a second click only appends new ones. */
    fun markInserted() = update { it.copy(inserted = it.usableCount) }

    fun parseLines(text: String): List<RecEvent> {
        val out = mutableListOf<RecEvent>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val obj = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return@forEach
            val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val screen = obj["screen"] as? JsonObject
            val nodeObj = obj["node"] as? JsonObject
            out += RecEvent(
                type = type,
                x = obj["x"]?.int() ?: 0,
                y = obj["y"]?.int() ?: 0,
                x1 = obj["x1"]?.int() ?: 0,
                y1 = obj["y1"]?.int() ?: 0,
                x2 = obj["x2"]?.int() ?: 0,
                y2 = obj["y2"]?.int() ?: 0,
                dur = obj["dur"]?.int() ?: 0,
                key = obj["key"]?.jsonPrimitive?.contentOrNull ?: "",
                pkg = obj["pkg"]?.jsonPrimitive?.contentOrNull ?: "",
                message = obj["message"]?.jsonPrimitive?.contentOrNull ?: "",
                screenW = screen?.get("w")?.int() ?: 0,
                screenH = screen?.get("h")?.int() ?: 0,
                node = nodeObj?.let { n ->
                    val countsObj = n["counts"] as? JsonObject
                    RecNode(
                        text = n["text"]?.jsonPrimitive?.contentOrNull ?: "",
                        desc = n["desc"]?.jsonPrimitive?.contentOrNull ?: "",
                        id = n["id"]?.jsonPrimitive?.contentOrNull ?: "",
                        className = n["className"]?.jsonPrimitive?.contentOrNull ?: "",
                        counts = countsObj?.mapValues { (_, v) -> (v as? JsonPrimitive)?.intOrNull ?: -1 } ?: emptyMap()
                    )
                }
            )
        }
        return out
    }

    fun normalizeEvents(events: List<RecEvent>): List<RecEvent> {
        val start = events.firstOrNull { it.type == "start" }
        val w = start?.screenW ?: 0
        val h = start?.screenH ?: 0
        fun fix(v: Int, max: Int): Int {
            var n = v
            if (max > 0 && n > max) n /= 100
            return n
        }
        return events.map { e ->
            when (e.type) {
                "tap" -> e.copy(x = fix(e.x, w), y = fix(e.y, h))
                "swipe" -> {
                    val x1 = fix(e.x1, w); val y1 = fix(e.y1, h)
                    val x2 = fix(e.x2, w); val y2 = fix(e.y2, h)
                    val dx = x2 - x1; val dy = y2 - y1
                    val dur = if (kotlin.math.sqrt((dx * dx + dy * dy).toDouble()) < 40) 300 else e.dur
                    e.copy(x1 = x1, y1 = y1, x2 = x2, y2 = y2, dur = dur)
                }
                else -> e
            }
        }
    }

    /** Device-side hit counts already decided the best selector; pick the first unique one. */
    fun pickSelector(e: RecEvent): Pair<String, String>? {
        val n = e.node ?: return null
        for (key in listOf("id", "text", "textContains", "desc")) {
            if (n.counts[key] == 1) {
                val v = when (key) {
                    "id" -> n.id
                    "desc" -> n.desc
                    else -> n.text
                }
                if (v.isNotEmpty()) return key to v
            }
        }
        return null
    }

    fun describe(e: RecEvent): String = when (e.type) {
        "start" -> "开始录制（" + (if (e.screenW > 0) "${e.screenW}×${e.screenH}" else "未知尺寸") + "）"
        "error" -> "录制脚本报错：${e.message}"
        "key" -> "按键 ${e.key}"
        "swipe" -> "滑动 (${e.x1},${e.y1}) → (${e.x2},${e.y2}) · ${e.dur}ms"
        "tap" -> {
            val sel = pickSelector(e)
            "点击 (${e.x},${e.y})" +
                (e.node?.label?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "") +
                (sel?.let { " · ${it.first}(${cn.dumate.a6studio.picker.PickerLogic.quote(it.second)})" } ?: " · 无唯一选择器，写坐标")
        }
        else -> e.type
    }

    /** Turns fresh events into blocks ready to append to the current scope. */
    fun blocksFor(events: List<RecEvent>, from: Int): List<cn.dumate.a6studio.model.Block> {
        val useful = events.filter { it.usable }
        return useful.drop(from).mapNotNull { e ->
            when (e.type) {
                "tap" -> {
                    val sel = pickSelector(e)
                    if (sel != null) newBlock("clickNode", mapOf("by" to ParamValue.Text(sel.first), "value" to ParamValue.Text(sel.second)))
                    else newBlock("coordClick", mapOf("x" to ParamValue.Num(e.x.toDouble()), "y" to ParamValue.Num(e.y.toDouble())))
                }
                "swipe" -> newBlock(
                    "swipe",
                    mapOf(
                        "x1" to ParamValue.Num(e.x1.toDouble()), "y1" to ParamValue.Num(e.y1.toDouble()),
                        "x2" to ParamValue.Num(e.x2.toDouble()), "y2" to ParamValue.Num(e.y2.toDouble()),
                        "dur" to ParamValue.Num(e.dur.coerceIn(200, 2000).toDouble())
                    )
                )
                "key" -> newBlock("pressKey", mapOf("key" to ParamValue.Text(e.key.ifEmpty { "back" })))
                else -> null
            }
        }
    }

    private fun kotlinx.serialization.json.JsonElement.int(): Int =
        (this as? JsonPrimitive)?.intOrNull ?: 0
}