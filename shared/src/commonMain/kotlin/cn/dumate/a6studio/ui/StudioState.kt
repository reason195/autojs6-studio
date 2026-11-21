package cn.dumate.a6studio.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.dumate.a6studio.audit.AuditController
import cn.dumate.a6studio.audit.AuditRow
import cn.dumate.a6studio.bridge.BridgeException
import cn.dumate.a6studio.bridge.DeviceInfo
import cn.dumate.a6studio.bridge.McpClient
import cn.dumate.a6studio.catalog.CodeGen
import cn.dumate.a6studio.diagnose.Diagnose
import cn.dumate.a6studio.diagnose.Issue
import cn.dumate.a6studio.model.ParamValue
import cn.dumate.a6studio.store.ProjectRepo
import cn.dumate.a6studio.model.Samples
import cn.dumate.a6studio.picker.PickKind
import cn.dumate.a6studio.picker.PickTarget
import cn.dumate.a6studio.picker.PickerController
import cn.dumate.a6studio.picker.PickerState
import cn.dumate.a6studio.platform.Platform
import cn.dumate.a6studio.recorder.RecorderController
import cn.dumate.a6studio.recorder.RecorderState
import cn.dumate.a6studio.runtime.LogEntry
import cn.dumate.a6studio.runtime.RunResult
import cn.dumate.a6studio.runtime.RunEngine
import cn.dumate.a6studio.runtime.SimNode
import cn.dumate.a6studio.runtime.SimSink
import cn.dumate.a6studio.runtime.SimStateView
import cn.dumate.a6studio.runtime.TestResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class Pane { CODE, RUN, TEST, DIAG, DEVICE, HELP }

enum class Overlay { NONE, PICKER, AUDIT, RECORDER, TEMPLATES, GUIDE }

/** Narrow-screen navigation: which of the three columns is visible. */
enum class MobileSheet { CANVAS, PALETTE, PANE }

/**
 * Central UI state holder. Owns the repository, the MCP bridge and the three tool controllers,
 * and exposes plain state the composables read. All long operations run on [scope].
 */
class StudioState(
    private val platform: Platform,
    storage: cn.dumate.a6studio.model.ProjectStorage,
    engineFactory: HttpClientEngineFactory<*>,
    private val engine: RunEngine
) {
    val repo = ProjectRepo(storage)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val http = HttpClient(engineFactory) {
        expectSuccess = false
    }
    val bridge = McpClient(http)

    var pane by mutableStateOf(Pane.CODE)
        private set
    var overlay by mutableStateOf(Overlay.NONE)
        private set
    var mobileSheet by mutableStateOf(MobileSheet.CANVAS)
    /**
     * Beginner mode: hides advanced blocks and jargon, shows plain-language help. On by default
     * for a fresh install and remembered afterwards, because the target user never writes code.
     */
    var beginnerMode by mutableStateOf(true)
    var showGuide by mutableStateOf(false)
    var showTemplatePicker by mutableStateOf(false)


    var pickerState by mutableStateOf(PickerState())
        private set
    var auditRows by mutableStateOf<List<AuditRow>>(emptyList())
        private set
    var auditNote by mutableStateOf("")
        private set
    var recorderState by mutableStateOf(RecorderState())
        private set

    // run pane
    var simNodes by mutableStateOf<List<SimNode>>(emptyList())
        private set
    var simView by mutableStateOf<SimStateView?>(null)
        private set
    var simBubble by mutableStateOf<String?>(null)
        private set
    var runLogs by mutableStateOf<List<LogEntry>>(emptyList())
        private set
    var runStatus by mutableStateOf("未运行")
        private set
    var runStatusLevel by mutableStateOf("idle")
        private set
    var running by mutableStateOf(false)
        private set
    var lastRun: RunResult? = null
        private set
    var sendToDevice by mutableStateOf(false)

    var testResults by mutableStateOf<List<TestResult>>(emptyList())
        private set
    var testSummary by mutableStateOf("")

    var deviceLog by mutableStateOf<List<LogEntry>>(emptyList())
        private set
    var deviceInfo by mutableStateOf<DeviceInfo?>(null)
        private set
    var deviceStatusText by mutableStateOf("真机：未连接")
        private set
    var deviceStatusLevel by mutableStateOf("idle")
        private set
    var bridgeUrl by mutableStateOf("")
        private set
    var bridgeToken by mutableStateOf("")
        private set
    var rememberToken by mutableStateOf(false)

    var issues by mutableStateOf<List<Issue>>(emptyList())
        private set

    var codeMarkers by mutableStateOf<List<CodeLine>>(emptyList())
        private set
    var cleanCodeInfo by mutableStateOf("")
        private set

    var statusHint by mutableStateOf("")
    val toastText = mutableStateOf<String?>(null)

    private var diagJob: Job? = null
    private var bubbleJob: Job? = null
    private var tickJob: Job? = null

    private val picker = PickerController(bridge) { pickerState = it }
    private val audit = AuditController(bridge) { rows, note -> auditRows = rows; auditNote = note }
    private val recorder = RecorderController(bridge) {
        recorderState = it
        if (it.status == cn.dumate.a6studio.recorder.RecorderStatus.ERROR && it.note.isNotEmpty()) {
            deviceStatusLevel = "err"
        }
    }

    data class CodeLine(val text: String, val blockId: String?, val isEmptyMarker: Boolean)

    // ---- lifecycle ----------------------------------------------------------
    fun init() {
        repo.load()
        bridgeUrl = repo.current.bridge.url.ifBlank { cn.dumate.a6studio.config.AppConfig.defaultUrl }
        rememberToken = platform.readTextFile(rememberMarkerPath()) == "1"
        if (rememberToken) loadRememberedTokens()
        bridge.setUrl(bridgeUrl)

        // A first run should not land on an empty canvas with no explanation.
        val seenGuide = platform.readTextFile(GUIDE_MARKER) == "1"
        beginnerMode = platform.readTextFile(BEGINNER_MARKER) != "0"
        if (!seenGuide) {
            if (repo.current.root.children["body"].isNullOrEmpty() && repo.current.modules.isEmpty()) {
                repo.setProjectDirect(cn.dumate.a6studio.model.Samples.emptyProject())
            }
            showGuide = true
        }

        refreshCode()
        recomputeDiag()
        platform.setWindowTitle("AutoJs6 Studio · ${repo.current.name}")
    }

    private companion object {
        const val GUIDE_MARKER = "a6studio.guide.seen"
        const val BEGINNER_MARKER = "a6studio.beginner.mode"
    }

    fun dismissGuide() {
        showGuide = false
        platform.writeTextFile(GUIDE_MARKER, "1")
    }

    fun changeBeginnerMode(on: Boolean) {
        beginnerMode = on
        platform.writeTextFile(BEGINNER_MARKER, if (on) "1" else "0")
        // Beginner mode hides the advanced panes; never leave the user stuck on a hidden one.
        if (on && pane !in listOf(Pane.CODE, Pane.RUN, Pane.HELP)) showPane(Pane.CODE)
    }

    fun openTemplates() { showTemplatePicker = true }

    fun closeTemplates() { showTemplatePicker = false }

    /** Loads a ready-made script and tells the user what to do next. */
    fun applyTemplate(template: cn.dumate.a6studio.model.Template) {
        repo.snapshot("选择模板")
        repo.setProjectDirect(template.build())
        showTemplatePicker = false
        refreshAll()
        showPane(Pane.RUN)
        toast("已载入「${template.title}」，点「▶ 运行」看看效果")
    }

    fun toast(msg: String) {
        toastText.value = msg
        scope.launch {
            delay(1800)
            if (toastText.value == msg) toastText.value = null
        }
    }

    fun showPane(pane: Pane) {
        this.pane = pane
        if (pane == Pane.DIAG) recomputeDiag()
    }

    fun setHint(text: String) { statusHint = text }

    fun clearRunLogs() { runLogs = emptyList() }

    // ---- code / diagnostics -------------------------------------------------
    fun refreshCode() {
        val marked = CodeGen.code(repo.current, markers = true)
        codeMarkers = marked.split('\n').map { line ->
            val m = Regex("""^[ \t]*//@([A-Za-z0-9_]+)$""").find(line)
            if (m != null) CodeLine("", m.groupValues[1], true) else CodeLine(line, null, false)
        }
        val clean = CodeGen.code(repo.current, markers = false)
        cleanCodeInfo = "${clean.split('\n').size} 行 · ${clean.toByteArray().size} B"
    }

    fun recomputeDiag() {
        diagJob?.cancel()
        diagJob = scope.launch {
            delay(200)
            issues = Diagnose.scan(repo.current) + Diagnose.fromRun(lastRun)
        }
    }

    fun commit() {
        refreshCode()
        recomputeDiag()
        repo.save()
    }

    // ---- editor intents ------------------------------------------------------
    fun addBlock(type: String) {
        repo.addBlock(type)
        commit()
        toast("已添加：${cn.dumate.a6studio.catalog.Catalog.cleanName(cn.dumate.a6studio.catalog.Catalog.byType(type)!!)}")
    }

    fun removeSelected() {
        val id = repo.selected.value ?: return
        repo.removeBlock(id)
        commit()
    }

    fun undo() {
        val label = repo.undo()
        if (label != null) {
            commit()
            toast("撤销：$label")
        } else toast("没有可撤销的操作")
    }

    // ---- run ----------------------------------------------------------------
    fun run() {
        if (running) return
        showPane(Pane.RUN)
        // On a phone the panes live behind a sheet; a beginner pressing 运行 expects to *see*
        // the result, so switch there automatically instead of leaving them on the canvas.
        mobileSheet = MobileSheet.PANE
        runLogs = emptyList()
        simNodes = emptyList()
        simBubble = null
        if (sendToDevice) { runOnDevice(); return }

        running = true
        runStatus = "运行中"
        runStatusLevel = "run"
        val sink = object : SimSink {
            override fun onRender(nodes: List<SimNode>, state: SimStateView) {
                simNodes = nodes
                simView = state
            }
            override fun onToast(msg: String) {
                simBubble = msg
                bubbleJob?.cancel()
                bubbleJob = scope.launch { delay(2400); simBubble = null }
            }
        }
        scope.launch {
            val result = engine.run(repo.current, sink)
            lastRun = result
            runLogs = result.logs
            simNodes = simNodes.ifEmpty { emptyList() }
            when (result.status) {
                "error" -> {
                    runStatus = "异常"
                    runStatusLevel = "err"
                    runLogs = runLogs + LogEntry("error", "异常：${result.error}", result.virtualMs)
                }
                "exited" -> { runStatus = "已退出"; runStatusLevel = "warn" }
                else -> { runStatus = "完成 · 模拟 ${result.virtualMs / 1000}s"; runStatusLevel = "ok" }
            }
            running = false
            recomputeDiag()
        }
    }

    private fun runOnDevice() {
        running = true
        runStatus = "运行中"
        runStatusLevel = "run"
        applyBridgeConfig()
        scope.launch {
            try {
                val name = (repo.current.name.ifBlank { "a6studio" }) + "_" + System.currentTimeMillis()
                val r = bridge.runScript(CodeGen.code(repo.current), name)
                appendDevice("sys", "真机返回：${r.take(800)}")
                val tail = bridge.consoleTail(120)
                val obj = runCatching { Json.parseToJsonElement(tail) as? kotlinx.serialization.json.JsonObject }.getOrNull()
                val entries = (obj?.get("entries") ?: obj?.get("lines")) as? kotlinx.serialization.json.JsonArray
                entries?.forEach { el ->
                    val o = el as? kotlinx.serialization.json.JsonObject
                    val level = o?.get("level")?.toString()?.trim('"') ?: "info"
                    val msg = o?.get("message")?.toString()?.trim('"') ?: o?.get("msg")?.toString()?.trim('"') ?: el.toString()
                    appendDevice(level, msg)
                }
                runStatus = "真机 · 完成"
                runStatusLevel = "ok"
                deviceStatusText = "真机：已连接"
                deviceStatusLevel = "ok"
            } catch (e: BridgeException) {
                appendDevice("error", "真机调用失败：${e.message}")
                appendDevice("info", "常见原因：MCP 未开启 / 地址不对 / 网络不可达。可先跑本地模拟。")
                runStatus = "真机不可用"
                runStatusLevel = "err"
                deviceStatusText = "真机：连接失败"
                deviceStatusLevel = "err"
            } finally {
                running = false
            }
        }
    }

    // ---- tests ---------------------------------------------------------------
    fun runTests(onlyIds: List<String>? = null) {
        showPane(Pane.TEST)
        testSummary = "运行中…"
        scope.launch {
            val results = engine.runTests(repo.current, onlyIds)
            testResults = results
            val pass = results.count { it.ok }
            testSummary = "$pass / ${results.size} 通过"
            toast("测试：$pass / ${results.size} 通过")
        }
    }

    // ---- device --------------------------------------------------------------
    fun applyBridgeConfig() {
        bridge.setUrl(bridgeUrl)
        if (bridgeToken.isNotBlank()) bridge.setAuthFor(bridgeUrl, bridgeToken)
    }

    fun connectDevice() {
        deviceLog = emptyList()
        deviceInfo = null
        deviceStatusText = "真机：连接中…"
        deviceStatusLevel = "run"
        applyBridgeConfig()
        scope.launch {
            try {
                val ms = bridge.ping()
                appendDevice("ok", "MCP 握手成功（${ms}ms）")
                val info = bridge.deviceInfo()
                deviceInfo = info
                deviceStatusText = "真机：${info.label}"
                deviceStatusLevel = "ok"
                repo.saveDevice(bridgeUrl, info.label)
            } catch (e: Exception) {
                appendDevice("error", describeBridgeFailure(e))
                deviceStatusText = "真机：连接失败"
                deviceStatusLevel = "err"
            }
        }
    }

    fun pingDevice() {
        applyBridgeConfig()
        scope.launch {
            try {
                val ms = bridge.ping()
                appendDevice("ok", "Ping 正常（${ms}ms）")
            } catch (e: Exception) {
                appendDevice("error", "Ping 失败：${e.message}")
            }
        }
    }

    fun dumpDeviceUi() {
        applyBridgeConfig()
        scope.launch {
            try {
                val text = bridge.uiDumpText(120, 20)
                deviceLog = emptyList()
                text.split('\n').take(80).forEach { appendDevice("debug", it) }
            } catch (e: Exception) {
                appendDevice("error", "取界面树失败：${e.message}")
            }
        }
    }

    /**
     * Device failures need different actions: a pairing prompt is waiting on the phone, a timeout
     * means the MCP server is starved, a network error means the address/port is wrong.
     */
    fun describeBridgeFailure(e: Throwable): String {
        val code = (e as? BridgeException)?.code
        val msg = e.message ?: e.toString()
        val hint = when (code) {
            "PAIRING_REQUIRED" -> "现在去手机上点「允许」完成配对，然后重新点「连接并读取设备」。"
            "TIMEOUT" -> "手机端口在听但不应答：多半是录制脚本或无障碍查询占住了 AutoJs6 主线程。在手机上停止脚本、重启 MCP Server 后重连。"
            "NETWORK" -> "连不上地址：确认手机与电脑同一 Wi-Fi、IP 未变、MCP Server 在前台且已加入后台白名单。"
            "RATE_LIMITED" -> "截屏限流：等一会儿再试。"
            "INVALID_ARGUMENTS" -> "参数被设备拒绝：见下面的原始信息（files_read 的 encoding 只能是 utf-8）。"
            "BAD_ENDPOINT" -> "地址不是 MCP 端点：检查是否少了 /mcp，或桥接未启动。"
            "HOST_ERROR" -> "AutoJs6 工作目录不存在：在手机内部存储根目录新建「脚本」文件夹。"
            "UNAUTHORIZED" -> "令牌不对。手机 → AutoJs6 → 插件 → MCP Server → 设置，复制当前令牌填进来（重新配对后令牌会变）。"
            "EMPTY_BODY", "EMPTY_RESPONSE" -> "手机没返回内容：确认 MCP 插件已启用（开关是打开的）、「允许局域网连接」已打开，然后重新点连接。"
            else -> null
        }
        return if (hint != null) "连接失败：$msg\n→ $hint" else "连接失败：$msg"
    }

    private fun appendDevice(level: String, msg: String) {
        deviceLog = deviceLog + LogEntry(level, msg, 0)
    }

    fun updateBridgeUrl(url: String) {
        bridgeUrl = url
        repo.updateBridgeUrl(url)
        bridge.setUrl(url)
        bridgeToken = bridge.authMap()[url]?.removePrefix("Bearer ").orEmpty()
    }

    fun updateBridgeToken(token: String) {
        bridgeToken = token
        bridge.setAuthFor(bridgeUrl, token)
        persistTokens()
    }

    fun changeRememberToken(v: Boolean) {
        rememberToken = v
        platform.writeTextFile(rememberMarkerPath(), if (v) "1" else "0")
        if (v) loadRememberedTokens() else persistTokens()
        bridgeToken = bridge.authMap()[bridgeUrl]?.removePrefix("Bearer ").orEmpty()
    }

    private fun rememberMarkerPath() = "a6studio.remember.v1"
    private fun tokenPath() = "a6studio.tokens.v1"

    private fun loadRememberedTokens() {
        val raw = platform.readTextFile(tokenPath()) ?: return
        val map = runCatching {
            Json.parseToJsonElement(raw).let { el ->
                (el as? kotlinx.serialization.json.JsonObject)?.mapValues { it.value.toString().trim('"') }.orEmpty()
            }
        }.getOrDefault(emptyMap())
        bridge.setAuthMap(map)
    }

    private fun persistTokens() {
        if (!rememberToken) {
            platform.writeTextFile(tokenPath(), "{}")
            return
        }
        val obj = buildJsonObject { bridge.authMap().forEach { (k, v) -> put(k, v) } }
        platform.writeTextFile(tokenPath(), obj.toString())
    }

    fun saveCurrentDevice() {
        repo.saveDevice(bridgeUrl, "")
        toast("已保存设备")
    }

    fun switchDevice(url: String) {
        if (url.isBlank()) return
        updateBridgeUrl(url)
        applyBridgeConfig()
        toast("已切换到 ${url.removePrefix("http://").removePrefix("https://")}")
    }

    // ---- project io ----------------------------------------------------------
    fun exportJs() {
        scope.launch {
            val code = CodeGen.code(repo.current)
            val name = (repo.current.name.ifBlank { "autojs6" }) + ".js"
            val saved = platform.saveTextAs(name, code, "text/javascript")
            toast(if (saved != null) "已导出 JS" else "已取消导出")
        }
    }

    fun exportProject() {
        scope.launch {
            val json = Json { prettyPrint = true }.encodeToString(
                cn.dumate.a6studio.model.Project.serializer(), repo.current
            )
            val name = (repo.current.name.ifBlank { "autojs6" }) + ".a6proj.json"
            val saved = platform.saveTextAs(name, json, "application/json")
            toast(if (saved != null) "已导出项目" else "已取消导出")
        }
    }

    fun importProject() {
        scope.launch {
            val picked = platform.openTextFile(listOf("json"))
            if (picked == null) return@launch
            try {
                val p = Json { ignoreUnknownKeys = true }.decodeFromString(
                    cn.dumate.a6studio.model.Project.serializer(), picked.second
                )
                repo.setProjectDirect(p)
                refreshAll()
                toast("已导入：${p.name}")
            } catch (e: Exception) {
                toast("导入失败：${e.message}")
            }
        }
    }

    fun loadExample() {
        repo.snapshot("载入示例")
        repo.setProjectDirect(Samples.exampleProject())
        refreshAll()
        toast("已载入示例")
    }

    fun newProject() {
        repo.snapshot("新建项目")
        repo.setProjectDirect(Samples.emptyProject())
        refreshAll()
        toast("已新建空项目")
    }

    fun refreshAll() {
        refreshCode()
        recomputeDiag()
        bridgeUrl = repo.current.bridge.url.ifBlank { bridgeUrl }
    }

    fun saveNow() {
        repo.save()
        toast("已保存到本机")
    }

    fun copyCode() {
        platform.copyToClipboard(CodeGen.code(repo.current))
        toast("代码已复制")
    }

    fun renameProject(name: String) {
        repo.setName(name)
        platform.setWindowTitle("AutoJs6 Studio · $name")
    }

    // ---- picker --------------------------------------------------------------
    fun openPicker(blockId: String, paramKey: String, kind: PickKind) {
        overlay = Overlay.PICKER
        applyBridgeConfig()
        scope.launch { picker.open(PickTarget(blockId, paramKey, kind)) }
    }

    fun closeOverlay() {
        overlay = Overlay.NONE
        picker.close()
    }

    fun pickerSetView(v: cn.dumate.a6studio.picker.PickerView) { scope.launch { picker.setView(v) } }
    fun pickerSetQuery(q: String) { picker.setQuery(q) }
    fun pickerSetOnlyClickable(v: Boolean) { picker.setOnlyClickable(v) }
    fun pickerRefresh() { scope.launch { picker.refresh() } }
    fun pickerSelect(node: cn.dumate.a6studio.picker.UiNode) { scope.launch { picker.selectNode(node) } }
    fun pickerCapture() { scope.launch { picker.capture() } }
    fun pickerDrill(l: Int, t: Int, r: Int, b: Int) { scope.launch { picker.drill(l, t, r, b) } }
    fun pickerClearRegion() { picker.clearRegion() }
    fun pickerCoord(x: Int, y: Int) { picker.setCoord(x, y) }
    fun pickerSwipePoint(x: Int, y: Int) { picker.takeSwipePoint(x, y) }

    fun applyPick(result: cn.dumate.a6studio.picker.PickResult) {
        val loc = repo.locate(result.blockId)
        if (loc == null) { toast("目标积木已被删除"); closeOverlay(); return }
        val patch = mutableMapOf<String, ParamValue>()
        when (result.kind) {
            PickKind.SELECTOR -> {
                if (result.expr != null) patch["expr"] = ParamValue.Text(result.expr)
                else {
                    result.by?.let { patch["by"] = ParamValue.Text(it) }
                    result.value?.let { patch["value"] = ParamValue.Text(it) }
                }
            }
            PickKind.COORD -> {
                result.x?.let { patch["x"] = ParamValue.Num(it.toDouble()) }
                result.y?.let { patch["y"] = ParamValue.Num(it.toDouble()) }
            }
            PickKind.SWIPE -> {
                result.x1?.let { patch["x1"] = ParamValue.Num(it.toDouble()) }
                result.y1?.let { patch["y1"] = ParamValue.Num(it.toDouble()) }
                result.x2?.let { patch["x2"] = ParamValue.Num(it.toDouble()) }
                result.y2?.let { patch["y2"] = ParamValue.Num(it.toDouble()) }
            }
        }
        repo.patchParams(result.blockId, patch, if (result.kind == PickKind.SELECTOR) "拾取控件" else "拾取坐标")
        repo.select(result.blockId)
        commit()
        showPane(Pane.CODE)
        toast(
            when (result.kind) {
                PickKind.SELECTOR -> if (result.expr != null) "已写入表达式：${result.expr.take(44)}" else "已填入：${result.by} = ${result.value}"
                PickKind.COORD -> "已写入坐标：${result.x}, ${result.y}"
                PickKind.SWIPE -> "已写入滑动：(${result.x1},${result.y1}) → (${result.x2},${result.y2})"
            }
        )
        closeOverlay()
    }

    // ---- audit ---------------------------------------------------------------
    fun openAudit() {
        overlay = Overlay.AUDIT
        applyBridgeConfig()
        scope.launch { audit.run(repo.current) }
    }

    fun rerunAudit() {
        applyBridgeConfig()
        scope.launch { audit.run(repo.current) }
    }

    fun applyAuditFixes() {
        val fixable = auditRows.filter { it.fix != null }
        if (fixable.isEmpty()) { toast("没有可自动修好的选择器"); return }
        var n = 0
        fixable.forEach { row ->
            val fix = row.fix ?: return@forEach
            repo.patchParams(row.blockId, mapOf(
                "by" to ParamValue.Text(fix.first),
                "value" to ParamValue.Text(fix.second)
            ), "体检修好选择器")
            n++
        }
        commit()
        auditRows = auditRows.map { r ->
            val fix = r.fix
            if (fix != null) r.copy(
                by = fix.first,
                value = fix.second,
                status = cn.dumate.a6studio.audit.AuditStatus.UNIQUE,
                note = "已改为 ${audit.selText(fix.first, fix.second)}",
                fix = null
            ) else r
        }
        toast("已修好 $n 处选择器")
    }

    fun copyAuditReport() {
        if (auditRows.isEmpty()) { toast("先体检一次"); return }
        platform.copyToClipboard(audit.report())
        toast("报告已复制")
    }

    // ---- recorder ------------------------------------------------------------
    fun openRecorder() { overlay = Overlay.RECORDER }

    fun recorderStart() {
        applyBridgeConfig()
        scope.launch { recorder.start() }
        startTick()
    }

    fun recorderStop() {
        tickJob?.cancel()
        scope.launch { recorder.stop() }
    }

    fun recorderFetchAndGenerate() {
        scope.launch {
            recorder.fetchEvents()
            generateFromRecording()
        }
    }

    fun recorderExportScript() {
        scope.launch {
            val content = RecorderController.EXPORT_BANNER + RecorderController.DEVICE_SCRIPT
            val saved = platform.saveTextAs("a6studio-record.js", content, "text/javascript")
            toast(if (saved != null) "已导出 a6studio-record.js，放到手机上用 AutoJs6 运行" else "已取消导出")
        }
    }

    fun recorderImport() {
        scope.launch {
            val picked = platform.openTextFile(listOf("jsonl", "txt", "log", "json")) ?: return@launch
            val n = recorder.importText(picked.second)
            toast(if (n > 0) "已导入 $n 条事件" else "文件里没有可识别的事件")
        }
    }

    fun recorderClear() { recorder.reset() }

    fun generateFromRecording() {
        val state = recorderState
        if (state.freshCount == 0) { toast("没有新操作可以生成"); return }
        val blocks = recorder.blocksFor(state.events, state.inserted)
        if (blocks.isEmpty()) { toast("没有可转换的操作"); return }
        repo.snapshot("录制生成积木")
        repo.addBlocksAtScopeEnd(blocks)
        recorder.markInserted()
        commit()
        toast("已生成 ${blocks.size} 个积木")
    }

    private fun startTick() {
        tickJob?.cancel()
        tickJob = scope.launch {
            var fails = 0
            while (recorderState.status == cn.dumate.a6studio.recorder.RecorderStatus.RECORDING) {
                delay(900)
                try {
                    recorder.fetchEvents()
                    fails = 0
                } catch (e: Exception) {
                    fails++
                    if (fails >= 3) break
                }
            }
        }
    }

    val auditController: AuditController get() = audit
    val recorderController: RecorderController get() = recorder
    val platformRef: Platform get() = platform
}