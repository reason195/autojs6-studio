package cn.dumate.a6studio.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.dumate.a6studio.ai.BlockPatch
import cn.dumate.a6studio.ai.BlockPatchOp
import cn.dumate.a6studio.ai.LlmClient
import cn.dumate.a6studio.ai.LlmConfig
import cn.dumate.a6studio.ai.LlmProviderId
import cn.dumate.a6studio.ai.LlmRefineInput
import cn.dumate.a6studio.ai.LlmRefineOutput
import cn.dumate.a6studio.audit.AuditController
import cn.dumate.a6studio.audit.AuditRow
import cn.dumate.a6studio.bridge.BridgeException
import cn.dumate.a6studio.bridge.CallMcpGatewayOptions
import cn.dumate.a6studio.bridge.DeviceInfo
import cn.dumate.a6studio.bridge.DualMcpGateway
import cn.dumate.a6studio.bridge.McpClient
import cn.dumate.a6studio.bridge.McpServerId
import cn.dumate.a6studio.bridge.McpTransport
import cn.dumate.a6studio.bridge.ProxyPinBridge
import cn.dumate.a6studio.security.Redaction
import cn.dumate.a6studio.security.RedactionReport
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
import cn.dumate.a6studio.platform.nowMillis
import cn.dumate.a6studio.recorder.RecorderController
import cn.dumate.a6studio.recorder.RecorderState
import cn.dumate.a6studio.runtime.LogEntry
import cn.dumate.a6studio.runtime.RunResult
import cn.dumate.a6studio.runtime.RunControl
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class Pane { CODE, RUN, TEST, DIAG, DEVICE, AI, HELP }

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
    // Reused across import/export: constructing a Json format per call is measurable when
    // exporting a large project, and the compiler flags it.
    private val prettyJson = Json { prettyPrint = true }
    private val lenientJson = Json { ignoreUnknownKeys = true }

    val repo = ProjectRepo(storage)
    // M2: without a handler, any exception that escapes a launch {} (a device reply in an
    // unexpected shape, an engine bug) crashes the Android app. Log it and keep the UI alive.
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
            running = false
            testsRunning = false
            runControl = null
            testControl = null
            toast("内部错误：${e.message ?: e::class.simpleName}")
        }
    )

    // Timeouts are installed once here; McpClient overrides them per request via
    // HttpTimeoutCapability. Previously McpClient called http.config{} on every request, which
    // creates a NEW client (with its own connection pool) that was never closed — a leak that
    // grew with every MCP call.
    private val http = HttpClient(engineFactory) {
        expectSuccess = false
        install(io.ktor.client.plugins.HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 8_000
            socketTimeoutMillis = 15_000
        }
    }
    val bridge = McpClient(http)
    val proxyPin = ProxyPinBridge(http, autojsClient = bridge)
    val mcpGateway: DualMcpGateway get() = proxyPin.gateway
    val llmClient = LlmClient(http)

    var devMode by mutableStateOf(false)
    var llmProvider by mutableStateOf(LlmProviderId.AUTO)
    var llmConfig by mutableStateOf(LlmConfig())
    var mcpTransport by mutableStateOf(McpTransport.HTTP_JSONRPC)
    var proxyPinUrl by mutableStateOf("http://127.0.0.1:9000/mcp")
    var proxyPinToken by mutableStateOf("")
    var proxyPinDomainFilter by mutableStateOf("")
    var lastAutoJsStateReport by mutableStateOf<RedactionReport?>(null)
        private set
    var lastAutoJsIsSynthetic by mutableStateOf(false)
        private set
    var lastTrafficReport by mutableStateOf<RedactionReport?>(null)
        private set
    var lastTrafficIsSynthetic by mutableStateOf(false)
        private set
    var lastRefineOutput by mutableStateOf<LlmRefineOutput?>(null)
        private set
    var lastRefineIsSynthetic by mutableStateOf(false)
        private set
    var aiBusy by mutableStateOf(false)
        private set
    var aiStatusMsg by mutableStateOf("")
        private set
    var pendingCloudPreview by mutableStateOf<String?>(null)
        private set
    private var pendingCloudGoal: String = ""
    var localMcpEndpoint by mutableStateOf<String?>(null)
        private set
    var localMcpToken by mutableStateOf<String?>(null)
        private set
    var localMcpEnabled by mutableStateOf(false)
        private set
    var onToggleLocalMcp: ((Boolean) -> Unit)? = null
    /** R-2：从工程本身派生（已持久化），不再是只存在内存里的标记。 */
    val hasExternalMcpChanges: Boolean
        get() = repo.current.externalPatchPending
    var pendingExternalDeviceConfirm by mutableStateOf(false)
        private set

    fun attachLocalMcpEndpoint(url: String?, token: String? = null) {
        localMcpEndpoint = url
        localMcpToken = token
        localMcpEnabled = url != null
    }

    fun toggleLocalMcp(enable: Boolean) {
        val cb = onToggleLocalMcp
        if (cb == null) {
            toast("当前平台不支持本地 MCP 服务端")
            return
        }
        cb(enable)
    }

    fun copyLocalMcpEndpoint() {
        val url = localMcpEndpoint ?: return
        val tok = localMcpToken.orEmpty()
        val payload = "{\"url\":\"$url\",\"headers\":{\"Authorization\":\"Bearer $tok\"}}"
        platform.copyToClipboard(payload)
        toast("已复制本地 MCP 地址与 Bearer 令牌")
    }

    fun applyExternalMcpProject(p: cn.dumate.a6studio.model.Project) {
        repo.snapshot("外部 MCP 补丁")
        // setProjectDirect 内部会 save()，标记随工程一起写盘
        repo.setProjectDirect(p.copy(externalPatchPending = true))
        refreshAll()
    }

    fun confirmAndRunOnDevice() {
        // 不用 setProjectDirect：它会重置作用域和选中状态，这里只需清标记并落盘
        repo.markExternalPatchConfirmed()
        commit()
        pendingExternalDeviceConfirm = false
        runOnDevice()
    }

    fun dismissExternalDeviceConfirm() {
        pendingExternalDeviceConfirm = false
    }

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
    var testsRunning by mutableStateOf(false)
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

    private var disposed = false

    /**
     * Releases the coroutine scope and the HTTP client.
     *
     * Both outlive individual screens: the scope drives the run/test jobs and UI intents,
     * jobs, and the client owns the connection pool. Without this, closing a desktop window or
     * leaving the Android activity left the poller firing and sockets open until GC.
     * Safe to call more than once.
     */
    fun dispose() {
        if (disposed) return
        disposed = true
        diagJob?.cancel()
        bubbleJob?.cancel()
        runControl?.cancel()
        testControl?.cancel()
        scope.cancel()
        runCatching { http.close() }
    }

    /** Stop switches for the simulation / test run in flight (null when idle). */
    private var runControl: RunControl? = null
    private var testControl: RunControl? = null

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
        // A stored project that cannot be parsed must not disappear silently: the sample project
        // would then be saved over the user's file on the next edit. `load()` returns a message
        // when that happened (and keeps a backup when the host supports it).
        val loadWarning = repo.load()
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
        loadWarning?.let {
            statusHint = it
            toast(it)
        }
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
        // The beginner tab row is 运行 / 检查问题 / 帮助, so CODE is hidden too.
        if (on && pane !in listOf(Pane.RUN, Pane.DIAG, Pane.HELP)) showPane(Pane.RUN)
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
        reportSaveFailure()
    }

    /** Shown once per distinct failure: commits happen on every keystroke. */
    private var lastSaveFailShown: String? = null

    private fun reportSaveFailure() {
        val err = repo.lastSaveError
        if (err == null) {
            lastSaveFailShown = null
            return
        }
        if (err != lastSaveFailShown) {
            lastSaveFailShown = err
            toast("保存失败：$err（改动还在，修好后可再点保存）")
        }
    }

    // ---- editor intents ------------------------------------------------------
    fun addBlock(type: String) {
        repo.addBlock(type)
        commit()
        toast("已添加：${cn.dumate.a6studio.catalog.Catalog.cleanName(cn.dumate.a6studio.catalog.Catalog.byType(type)!!)}")
    }

    /** Drag from the palette onto the canvas. Shared by both panels so they see one gesture. */
    val paletteDrag = cn.dumate.a6studio.ui.canvas.PaletteDragState()

    /** Inserts a block where it was dropped (one undo step, selected afterwards). */
    fun dropBlock(type: String, target: cn.dumate.a6studio.ui.canvas.DropTarget) {
        val def = cn.dumate.a6studio.catalog.Catalog.byType(type) ?: return
        repo.addBlock(type, parentId = target.parentId, slot = target.slot, index = target.index)
        commit()
        toast("已放入：${cn.dumate.a6studio.catalog.Catalog.cleanName(def)}")
    }

    fun removeSelected() {
        val id = repo.selectedId ?: return
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
        val control = RunControl()
        runControl = control
        // The simulator runs on a worker thread and may render thousands of times per second;
        // a StateFlow conflates that to the latest frame so the UI thread only draws what it can.
        val frames = MutableStateFlow<Pair<List<SimNode>, SimStateView>?>(null)
        val sink = object : SimSink {
            override fun onRender(nodes: List<SimNode>, state: SimStateView) {
                frames.value = nodes to state
            }
            override fun onToast(msg: String) {
                scope.launch {
                    simBubble = msg
                    bubbleJob?.cancel()
                    bubbleJob = scope.launch { delay(2400); simBubble = null }
                }
            }
        }
        val project = repo.current
        val frameJob = scope.launch {
            frames.collect { f -> if (f != null) { simNodes = f.first; simView = f.second } }
        }
        scope.launch {
            val result = try {
                withContext(Dispatchers.Default) { engine.run(project, sink, control) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // M2/M3: StackOverflowError, OutOfMemoryError or an engine bug must still end
                // the run and report it, or 运行 stays greyed out forever.
                cn.dumate.a6studio.runtime.RunResult(
                    status = "error", error = e.message ?: e::class.simpleName ?: "未知错误",
                    logs = emptyList(), code = "", virtualMs = 0, sim = null
                )
            } finally {
                frameJob.cancel()
                frames.value?.let { simNodes = it.first; simView = it.second }
                runControl = null
                running = false
            }
            lastRun = result
            runLogs = result.logs
            when (result.status) {
                "error" -> {
                    runStatus = "异常"
                    runStatusLevel = "err"
                    runLogs = runLogs + LogEntry("error", "异常：${result.error}", result.virtualMs)
                }
                "exited" -> { runStatus = "已退出"; runStatusLevel = "warn" }
                "stopped" -> { runStatus = "已停止"; runStatusLevel = "warn" }
                "timeout" -> { runStatus = "超时"; runStatusLevel = "err" }
                else -> { runStatus = "完成 · 模拟 ${result.virtualMs / 1000}s"; runStatusLevel = "ok" }
            }
            recomputeDiag()
        }
    }

    /** Stops the simulation or test run in flight; on a real device, stops its scripts. */
    fun stopRun() {
        runControl?.cancel()
        testControl?.cancel()
        if (running && sendToDevice && runControl == null) {
            scope.launch {
                try {
                    bridge.stopAllScripts()
                    appendDevice("sys", "已请求手机停止所有脚本")
                } catch (e: BridgeException) {
                    appendDevice("error", "停止失败：${e.message}")
                }
            }
        }
    }

    private fun runOnDevice() {
        if (hasExternalMcpChanges) {
            pendingExternalDeviceConfirm = true
            showPane(Pane.AI)
            toast("工程含外部 MCP 修改，请先在「AI 抓包」面板确认后再真机运行")
            return
        }
        running = true
        runStatus = "运行中"
        runStatusLevel = "run"
        applyBridgeConfig()
        scope.launch {
            try {
                val name = (repo.current.name.ifBlank { "a6studio" }) + "_" + nowMillis()
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // M2: a malformed reply (e.g. .jsonObject on a JSON array) is not a BridgeException.
                appendDevice("error", "真机返回无法解析：${e.message ?: e}")
                runStatus = "真机返回异常"
                runStatusLevel = "err"
            } finally {
                running = false
            }
        }
    }

    // ---- tests ---------------------------------------------------------------
    fun runTests(onlyIds: List<String>? = null) {
        if (testsRunning) return
        showPane(Pane.TEST)
        testSummary = "运行中…"
        testsRunning = true
        val control = RunControl()
        testControl = control
        val project = repo.current
        scope.launch {
            val results = try {
                withContext(Dispatchers.Default) { engine.runTests(project, onlyIds, control = control) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                toast("测试运行失败：${e.message ?: e::class.simpleName}")
                emptyList()
            } finally {
                testControl = null
                testsRunning = false
            }
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // M1: cancellation must propagate, or Stop/close never takes effect
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // M1: cancellation must propagate, or Stop/close never takes effect
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // M1: cancellation must propagate, or Stop/close never takes effect
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

    // ---- MCP config import ---------------------------------------------------------------
    /** Text in the "粘贴配置" box on the device pane. */
    var mcpImportText by mutableStateOf("")

    /**
     * Saves every device found in [text], keeps their tokens (in memory, or on disk when
     * 「记住令牌」 is on) and switches to the first one. Returns how many were imported.
     */
    fun importMcpConfig(text: String, source: String): Int {
        val found = cn.dumate.a6studio.bridge.McpConfigParser.parse(text)
        if (found.isEmpty()) {
            toast("$source 里没有找到 MCP 地址（支持 mcpServers JSON、URL、IP，每行一台）")
            return 0
        }
        found.forEach { c ->
            repo.saveDevice(c.url, cn.dumate.a6studio.bridge.McpConfigParser.label(c))
            if (c.token.isNotEmpty()) bridge.setAuthFor(c.url, c.token)
        }
        persistTokens()
        val first = found.first()
        updateBridgeUrl(first.url)
        applyBridgeConfig()
        val withToken = found.count { it.token.isNotEmpty() }
        val names = found.joinToString("、") { cn.dumate.a6studio.bridge.McpConfigParser.label(it) }
        appendDevice("sys", "从$source 导入 ${found.size} 台设备（$withToken 台带令牌）：$names")
        if (withToken > 0 && !rememberToken) {
            appendDevice("info", "令牌目前只在内存里，重启后需要重新导入；要保留请勾「记住令牌」")
        }
        toast("已导入 ${found.size} 台设备，当前：${cn.dumate.a6studio.bridge.McpConfigParser.label(first)}")
        return found.size
    }

    fun importMcpFromClipboard() {
        val text = platform.readClipboard()
        if (text.isNullOrBlank()) { toast("剪贴板是空的：先在 AutoJs6 插件页复制配置"); return }
        importMcpConfig(text, "剪贴板")
    }

    fun importMcpFromFile() {
        scope.launch {
            val picked = platform.openTextFile(listOf("json", "txt")) ?: return@launch
            importMcpConfig(picked.second, "文件「${picked.first}」")
        }
    }

    fun importMcpFromText() {
        if (importMcpConfig(mcpImportText, "粘贴内容") > 0) mcpImportText = ""
    }

    /** Copies the saved devices back out as an mcpServers JSON (tokens included only if known). */
    fun exportMcpConfig() {
        val servers = buildJsonObject {
            repo.current.bridge.devices.forEach { d ->
                put(d.name.ifBlank { d.url }, buildJsonObject {
                    put("url", d.url)
                    bridge.authMap()[d.url]?.let { auth -> put("headers", buildJsonObject { put("Authorization", auth) }) }
                })
            }
        }
        platform.copyToClipboard(buildJsonObject { put("mcpServers", servers) }.toString())
        toast("已复制 ${repo.current.bridge.devices.size} 台设备的 MCP 配置（含令牌，注意别发给别人）")
    }

    // ---- project io ----------------------------------------------------------
    fun exportJs() {
        scope.launch {
            val code = CodeGen.code(repo.current)
            val leaked = Redaction.assertNoPlaintextSecrets(code)
            if (leaked.isNotEmpty()) {
                toast("导出已拦截：脚本包含疑似明文凭据（${leaked.joinToString("、")}）")
                return@launch
            }
            val name = (repo.current.name.ifBlank { "autojs6" }) + ".js"
            val saved = platform.saveTextAs(name, code, "text/javascript")
            toast(if (saved != null) "已导出 JS" else "已取消导出")
        }
    }

    fun toggleDevMode(enable: Boolean) {
        devMode = enable
        if (!enable && mcpTransport == McpTransport.LOCAL_BRIDGE) {
            mcpTransport = McpTransport.HTTP_JSONRPC
        }
    }

    fun fetchAutoJsDeviceState() {
        scope.launch {
            aiBusy = true
            aiStatusMsg = "正在通过 AutoJs6 MCP 采集设备与控件树状态…"
            try {
                val effectiveTransport = if (!devMode && mcpTransport == McpTransport.LOCAL_BRIDGE) {
                    McpTransport.HTTP_JSONRPC
                } else mcpTransport
                if (effectiveTransport == McpTransport.HTTP_JSONRPC) {
                    applyBridgeConfig()
                }
                val resp = withContext(Dispatchers.Default) {
                    mcpGateway.call(
                        CallMcpGatewayOptions(
                            serverId = McpServerId.AUTOJS6,
                            transport = effectiveTransport,
                            endpointUrl = bridgeUrl,
                            method = "tools/call",
                            toolName = "get_device_state",
                            allowLocal = true,
                            authToken = bridgeToken,
                            devMode = devMode
                        )
                    )
                }
                if (!resp.ok) {
                    aiStatusMsg = "AutoJs6 MCP 采集失败：${resp.errorMessage}"
                    toast(aiStatusMsg)
                } else {
                    lastAutoJsStateReport = resp.redactionReport ?: Redaction.redactTrafficPayload(resp.data)
                    lastAutoJsIsSynthetic = resp.isSynthetic
                    val tag = if (resp.isSynthetic) "【合成数据】" else ""
                    aiStatusMsg = "${tag}已采集 AutoJs6 设备状态（耗时 ${resp.latencyMs}ms）"
                    toast("${tag}AutoJs6 设备状态已载入")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                aiStatusMsg = "AutoJs6 状态采集失败：${e.message}"
                toast(aiStatusMsg)
            } finally {
                aiBusy = false
            }
        }
    }

    fun fetchProxyPinTraffic() {
        scope.launch {
            aiBusy = true
            aiStatusMsg = "正在连接 ProxyPin MCP 并拉取流量…"
            try {
                val effectiveTransport = if (!devMode && mcpTransport == McpTransport.LOCAL_BRIDGE) {
                    McpTransport.HTTP_JSONRPC
                } else mcpTransport
                if (effectiveTransport == McpTransport.HTTP_JSONRPC) {
                    proxyPin.configure(proxyPinUrl, proxyPinToken, allowLocal = true)
                }
                val resp = withContext(Dispatchers.Default) {
                    proxyPin.listHttpFlows(
                        domain = proxyPinDomainFilter,
                        limit = 20,
                        transport = effectiveTransport,
                        devMode = devMode
                    )
                }
                if (!resp.ok) {
                    // G-5：仅当远端明确返回「工具不存在」时才回退到 get_flows
                    if (effectiveTransport == McpTransport.HTTP_JSONRPC &&
                        DualMcpGateway.isToolNotFoundMessage(resp.errorKind, resp.errorMessage)
                    ) {
                        val fallbackReport = withContext(Dispatchers.Default) {
                            proxyPin.fetchRedactedTraffic(toolName = "get_flows", transport = effectiveTransport, devMode = devMode)
                        }
                        lastTrafficReport = fallbackReport
                        lastTrafficIsSynthetic = false
                        aiStatusMsg = "已获取并脱敏：屏蔽键 ${fallbackReport.stats.redactedKeyCount}，掩码 ${fallbackReport.stats.maskedPatternCount}"
                        toast("ProxyPin 流量已脱敏载入")
                    } else {
                        aiStatusMsg = "ProxyPin 拉取失败：${resp.errorMessage}"
                        toast(aiStatusMsg)
                    }
                } else {
                    val report = resp.redactionReport ?: Redaction.redactTrafficPayload(resp.data)
                    lastTrafficReport = report
                    lastTrafficIsSynthetic = resp.isSynthetic
                    val tag = if (resp.isSynthetic) "【合成数据】" else ""
                    aiStatusMsg = "${tag}已获取并脱敏（${resp.latencyMs}ms）：屏蔽键 ${report.stats.redactedKeyCount}，掩码 ${report.stats.maskedPatternCount}"
                    toast("${tag}ProxyPin 流量已脱敏载入")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                aiStatusMsg = "ProxyPin 拉取失败：${e.message}"
                toast(aiStatusMsg)
            } finally {
                aiBusy = false
            }
        }
    }

    private fun mayUseCloudProvider(): Boolean = when (llmProvider) {
        LlmProviderId.GEMINI -> llmConfig.geminiApiKey.isNotBlank()
        LlmProviderId.OPENAI_COMPATIBLE -> llmConfig.openAiApiKey.isNotBlank() && llmConfig.openAiBaseUrl.isNotBlank()
        LlmProviderId.AUTO -> llmConfig.geminiApiKey.isNotBlank() ||
            (llmConfig.openAiApiKey.isNotBlank() && llmConfig.openAiBaseUrl.isNotBlank())
        else -> false
    }

    fun requestAiRefine(goal: String) {
        if (mayUseCloudProvider() && pendingCloudPreview == null) {
            val devState = lastAutoJsStateReport?.redactedPayload ?: kotlinx.serialization.json.JsonNull
            val traffic = lastTrafficReport?.redactedPayload ?: kotlinx.serialization.json.JsonNull
            val combined = buildJsonObject {
                put("autojsDeviceState", Redaction.redactTrafficPayload(devState).redactedPayload)
                put("proxypinTraffic", Redaction.redactTrafficPayload(traffic).redactedPayload)
            }
            pendingCloudGoal = goal
            pendingCloudPreview = combined.toString().take(1200)
            aiStatusMsg = "即将把双 MCP 脱敏上下文发送往云端模型，请先核对下方脱敏预览并点「确认发送云端」"
            return
        }
        pendingCloudPreview = null
        runAiRefine(goal)
    }

    fun confirmCloudRefine() {
        val g = pendingCloudGoal
        pendingCloudPreview = null
        runAiRefine(g)
    }

    fun cancelCloudRefine() {
        pendingCloudPreview = null
        aiStatusMsg = "已取消发送云端"
    }

    fun runAiRefine(goal: String) {
        scope.launch {
            aiBusy = true
            aiStatusMsg = "正在联合双 MCP 上下文提炼…"
            try {
                val devState = lastAutoJsStateReport?.redactedPayload ?: kotlinx.serialization.json.JsonNull
                val traffic = lastTrafficReport?.redactedPayload ?: kotlinx.serialization.json.JsonNull
                // N-L16：从工程里的 kitBoot 读包名，找不到时用默认值
                val kitBootBlock = cn.dumate.a6studio.ai.BlockPatch.locateBlock(repo.current,
                    repo.current.root.children["body"].orEmpty()
                        .firstOrNull { it.type == "kitBoot" }?.id.orEmpty()
                ) ?: repo.current.root.children["body"].orEmpty().firstOrNull { it.type == "kitBoot" }
                val appPackage = kitBootBlock?.params?.get("pkg")?.asText()?.ifBlank { null }
                    ?: "com.jingdong.app.mall"
                val out = withContext(Dispatchers.Default) {
                    llmClient.refine(
                        LlmRefineInput(
                            goal = goal.ifBlank { "提取可执行任务按钮与推荐等待时长" },
                            workflowName = repo.current.name,
                            appPackage = appPackage,
                            fallbackKeywords = listOf("去完成", "立即签到", "去浏览"),
                            autojsState = devState,
                            proxypinTraffic = traffic,
                            provider = llmProvider
                        ),
                        llmConfig
                    )
                }
                val isSyn = (lastAutoJsStateReport != null && lastAutoJsIsSynthetic) ||
                    (lastTrafficReport != null && lastTrafficIsSynthetic) ||
                    (devMode && mcpTransport == McpTransport.LOCAL_BRIDGE)
                lastRefineOutput = out
                lastRefineIsSynthetic = isSyn
                val tag = if (isSyn) "【合成数据】" else ""
                aiStatusMsg = tag + out.routingNote
                toast("${tag}提炼完成（${out.providerUsed.label}）")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                aiStatusMsg = "提炼失败：${e.message}"
                toast(aiStatusMsg)
            } finally {
                aiBusy = false
            }
        }
    }

    fun applyRefinedToSelectedOrInsert() {
        val out = lastRefineOutput ?: return
        if (lastRefineIsSynthetic || lastAutoJsIsSynthetic || lastTrafficIsSynthetic) {
            toast("合成数据仅供调测预览，禁止回填到工程积木")
            return
        }
        val keyword = out.refinedKeywords.firstOrNull() ?: return
        val selId = repo.selectedId
        val selBlock = selId?.let { BlockPatch.locateBlock(repo.current, it) }
        val ops = if (selBlock != null && selBlock.type in setOf("kitClick", "kitWaitFor")) {
            listOf(BlockPatchOp.UpdateParam(selBlock.id, "value", ParamValue.Text(keyword)))
        } else {
            listOf(
                BlockPatchOp.AddBlock(
                    cn.dumate.a6studio.model.newBlock(
                        "kitClick",
                        mapOf(
                            "by" to ParamValue.Text("text"),
                            "match" to ParamValue.Text("contains"),
                            "value" to ParamValue.Text(keyword)
                        )
                    )
                )
            )
        }
        val res = BlockPatch.guardAndApply(repo.current, ops)
        if (!res.accepted) {
            toast(res.errors.firstOrNull() ?: "护栏拦截了修改")
        } else {
            repo.snapshot("AI 护栏回填")
            repo.setProjectDirect(res.project)
            refreshAll()
            toast("已通过护栏回填关键词「$keyword」")
        }
    }

    fun exportProject() {
        scope.launch {
            val json = prettyJson.encodeToString(
                cn.dumate.a6studio.model.Project.serializer(), repo.current
            )
            val name = (repo.current.name.ifBlank { "autojs6" }) + ".a6proj.json"
            val saved = platform.saveTextAs(name, json, "application/json")
            toast(if (saved != null) "已导出项目" else "已取消导出")
        }
    }

    /**
     * Imports a plain AutoJs6 `.js` script. The previous「导入」only understood Studio's own project
     * JSON, so any real script failed with "导入失败" — which is what the user hit.
     */
    fun importScript() {
        scope.launch {
            val picked = platform.openTextFile(listOf("js", "json", "txt")) ?: return@launch
            val (fileName, content) = picked
            val name = fileName.substringBeforeLast('.')
            // N-M9：与 importProject 一致，导入失败给出明确提示，而不是落到全局 handler 只显示「内部错误」
            val result = try {
                cn.dumate.a6studio.importer.ScriptImporter.import(content, name)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("导入失败：${e.message ?: e::class.simpleName}"); return@launch
            }
            if (result.total == 0) {
                toast("这个文件是空的，没有可导入的内容")
                return@launch
            }
            repo.snapshot("导入脚本")
            repo.setProjectDirect(result.project)
            refreshAll()
            showPane(Pane.RUN)
            val detail = result.notes.joinToString("；")
            toast("已导入「$name」：$detail")
        }
    }

    fun importProject() {
        scope.launch {
            val picked = platform.openTextFile(listOf("json", "js"))
            if (picked == null) return@launch
            try {
                val fallbackName = picked.first.substringBeforeLast('.')
                val irImported = cn.dumate.a6studio.importer.FlowIrImporter.tryImport(picked.second, fallbackName)
                val p = irImported?.project ?: lenientJson.decodeFromString(
                    cn.dumate.a6studio.model.Project.serializer(), picked.second
                )
                // Every other destructive path snapshots first; without this, importing the wrong
                // .a6proj.json overwrote the project with no way back (save() also persisted it).
                repo.snapshot("导入项目")
                repo.setProjectDirect(p)
                refreshAll()
                toast("已导入：${p.name}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // M1: cancellation must propagate, or Stop/close never takes effect
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
        if (repo.lastSaveError == null) toast("已保存到本机")
        else {
            lastSaveFailShown = repo.lastSaveError
            toast("保存失败：${repo.lastSaveError}")
        }
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
    // The picker/audit talk to the phone, and a single `ui_find` can sit for 15 s. Without a
    // handle the work outlived the panel: closing it and reopening another block queued a second
    // dump behind the first, so the UI felt frozen and stale results could arrive late.
    private var pickerJob: Job? = null
    private var auditJob: Job? = null

    private fun launchPicker(block: suspend () -> Unit) {
        pickerJob?.cancel()
        pickerJob = scope.launch { block() }
    }

    /**
     * 桌面截图/布局核对用：给拾取器塞一份固定样本，省得为了看一个面板去连真机。
     * 只被 desktop 的 A6STUDIO_SCREENSHOT_ACTION=picker 调用。
     */
    fun previewPicker(preview: cn.dumate.a6studio.picker.PickerState) {
        pickerState = preview
        overlay = Overlay.PICKER
    }

    fun openPicker(blockId: String, paramKey: String, kind: PickKind) {
        overlay = Overlay.PICKER
        applyBridgeConfig()
        launchPicker { picker.open(PickTarget(blockId, paramKey, kind)) }
    }

    fun closeOverlay() {
        overlay = Overlay.NONE
        // Closing must actually stop the phone calls, not just hide the panel.
        pickerJob?.cancel()
        auditJob?.cancel()
        picker.close()
    }

    fun pickerSetView(v: cn.dumate.a6studio.picker.PickerView) { launchPicker { picker.setView(v) } }
    fun pickerSetQuery(q: String) { picker.setQuery(q) }
    fun pickerSetOnlyClickable(v: Boolean) { picker.setOnlyClickable(v) }
    fun pickerRefresh() { launchPicker { picker.refresh() } }
    fun pickerSelect(node: cn.dumate.a6studio.picker.UiNode) { launchPicker { picker.selectNode(node) } }
    fun pickerCapture() { launchPicker { picker.capture() } }
    fun pickerDrill(l: Int, t: Int, r: Int, b: Int) { launchPicker { picker.drill(l, t, r, b) } }
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
        startAudit()
    }

    fun rerunAudit() {
        applyBridgeConfig()
        startAudit()
    }

    /** Restarting stops the previous pass first: two passes interleaved their row updates. */
    private fun startAudit() {
        auditJob?.cancel()
        auditJob = scope.launch { audit.run(repo.current) }
    }

    fun cancelAudit() {
        auditJob?.cancel()
        auditNote = "已停止体检"
    }

    fun applyAuditFixes() {
        val fixable = auditRows.filter { it.fix != null }
        if (fixable.isEmpty()) { toast("没有可自动修好的选择器"); return }
        val patches = fixable.mapNotNull { row ->
            val fix = row.fix ?: return@mapNotNull null
            val targetBlock = cn.dumate.a6studio.model.TreeOps.find(repo.current.root.children["body"].orEmpty(), row.blockId)
                ?: repo.current.modules.firstNotNullOfOrNull { m ->
                    cn.dumate.a6studio.model.TreeOps.find(m.children["body"].orEmpty(), row.blockId)
                }
            val paramMap = if (targetBlock?.type in setOf("kitWaitFor", "kitClick")) {
                val (baseBy, matchMode) = when {
                    fix.first.endsWith("Contains") -> fix.first.removeSuffix("Contains") to "contains"
                    fix.first.endsWith("Matches") -> fix.first.removeSuffix("Matches") to "regex"
                    else -> fix.first to "eq"
                }
                if (baseBy !in setOf("text", "desc")) return@mapNotNull null
                mapOf(
                    "by" to ParamValue.Text(baseBy),
                    "match" to ParamValue.Text(matchMode),
                    "value" to ParamValue.Text(fix.second)
                )
            } else {
                mapOf(
                    "by" to ParamValue.Text(fix.first),
                    "value" to ParamValue.Text(fix.second)
                )
            }
            row.blockId to paramMap
        }
        repo.patchParamsBatch(patches, "体检修好选择器")
        val n = patches.size
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
        // 录制期间刻意不轮询：脚本一旦常驻触摸监听，同进程的 MCP Server 就不再应答，
        // 之前的 900ms tick 会把状态从 RECORDING 改成 FETCHING/DONE/ERROR，
        // 界面显示「读取失败」，而手机其实还在录。
    }

    fun recorderStop() {
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

    val auditController: AuditController get() = audit
    val recorderController: RecorderController get() = recorder
    val platformRef: Platform get() = platform
}