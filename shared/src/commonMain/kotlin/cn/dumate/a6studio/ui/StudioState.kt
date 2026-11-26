package cn.dumate.a6studio.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.dumate.a6studio.ai.BlockPatch
import cn.dumate.a6studio.ai.BlockPatchOp
import cn.dumate.a6studio.ai.LlmClient
import cn.dumate.a6studio.ai.LlmConfig
import cn.dumate.a6studio.ai.LlmModelInfo
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
import cn.dumate.a6studio.security.UrlSecurity
import cn.dumate.a6studio.catalog.CodeGen
import cn.dumate.a6studio.diagnose.Diagnose
import cn.dumate.a6studio.diagnose.Issue
import cn.dumate.a6studio.model.ParamValue
import cn.dumate.a6studio.model.Block
import cn.dumate.a6studio.model.Project
import cn.dumate.a6studio.model.newBlock
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
    // Run/test state is not touched here: every run path resets it in its own finally, and
    // clearing it from an unrelated failure (a toast bubble, the recorder) re-enabled 运行
    // while a simulation was still going, so a second run could start alongside it.
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
            toast("内部错误：${e.message ?: e::class.simpleName}")
        }
    )

    // Timeouts are installed once here; McpClient overrides them per request via
    // HttpTimeoutCapability. Previously McpClient called http.config{} on every request, which
    // creates a NEW client (with its own connection pool) that was never closed — a leak that
    // grew with every MCP call.
    private val http = HttpClient(engineFactory) {
        expectSuccess = false
        followRedirects = false
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
    /** 「拉取并选择模型」：每个通道拉回的模型清单（内存，不入工程文件）。 */
    var llmModelOptions by mutableStateOf<Map<LlmProviderId, List<LlmModelInfo>>>(emptyMap())
        private set
    /** 正在拉取模型的通道（null = 没有进行中的拉取）。 */
    var llmModelsLoading by mutableStateOf<LlmProviderId?>(null)
        private set
    /** 每个通道最近一次拉取失败的错误文案，界面红字显示。 */
    var llmModelsError by mutableStateOf<Map<LlmProviderId, String>>(emptyMap())
        private set
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

    /** 把「采集到的原始设备/控件数据」翻成小白能看懂的一句话。 */
    val autoJsPlainSummary: String
        get() {
            val o = lastAutoJsStateReport?.redactedPayload as? JsonObject ?: return ""
            fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val model = str("deviceModel")
            val pkg = str("currentPackage")
            val texts = (o["visibleActionTexts"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            val sb = StringBuilder()
            if (model.isNotBlank()) sb.append("手机：").append(model).append("；")
            if (pkg.isNotBlank()) sb.append("前台应用：").append(pkg).append("；")
            if (texts.isNotEmpty()) sb.append("现在屏幕上能点的：").append(texts.joinToString("、")).append("。")
            else sb.append("没识别到明显的可点按钮，先手动把目标页面打开再采集一次。")
            return sb.toString()
        }

    /** 把 AI 提炼结果翻成「照着做就行」的步骤，不暴露任何字段名。 */
    val refinePlainSummary: String
        get() {
            val out = lastRefineOutput ?: return ""
            if (out.refinedKeywords.isEmpty() && out.branchConditions.isEmpty()) return ""
            val wait = (out.recommendedBrowseWaitMs / 1000).coerceAtLeast(1)
            val sb = StringBuilder()
            if (out.refinedKeywords.isNotEmpty()) {
                sb.append("按顺序点：").append(out.refinedKeywords.joinToString(" → "))
                    .append("。每点一个等约 ").append(wait).append(" 秒再返回。")
            }
            if (out.branchConditions.isNotEmpty()) {
                sb.append("遇到这些情况就停手：")
                    .append(out.branchConditions.joinToString("；") { "${it.`when`} → ${it.action}" })
                    .append("。")
            }
            return sb.toString()
        }
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
    val textFocusTracker = cn.dumate.a6studio.ui.fields.TextFocusTracker()


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
    /** 「工具清单」按钮的输出：真机 MCP 实际暴露的工具名，用来回答"能不能调全部工具"。 */
    var deviceToolsText by mutableStateOf("")
        private set
    var deviceToolNames by mutableStateOf<List<String>>(emptyList())
        private set
    var deviceToolDescriptions by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var deviceToolSchemas by mutableStateOf<Map<String, JsonObject>>(emptyMap())
        private set
    var deviceToolParamValues by mutableStateOf<Map<String, String>>(emptyMap())
    var deviceToolNameDraft by mutableStateOf("")
    var deviceToolArgsDraft by mutableStateOf("{}")
    var deviceToolRunning by mutableStateOf(false)
        private set
    var deviceToolResult by mutableStateOf("")
        private set

    // ---- 一键上手：从当前真机页面自动生成脚本，不需要用户输入任何命令/参数 ----
    var autoPkg by mutableStateOf("")
        private set
    var autoPkgLabel by mutableStateOf("")
        private set
    var autoCandidates by mutableStateOf<List<String>>(emptyList())
        private set
    var autoSelected by mutableStateOf("")
        private set
    var installedApps by mutableStateOf<List<Pair<String, String>>>(emptyList())
        private set
    var appQuery by mutableStateOf("")
    var appsLoading by mutableStateOf(false)
        private set
    var autoBusy by mutableStateOf(false)
        private set
    var autoStatus by mutableStateOf("")
        private set
    /** ProxyPin 自动解析结果：不用用户手输域名/接口。 */
    var trafficDomains by mutableStateOf<List<String>>(emptyList())
        private set
    var trafficEndpoints by mutableStateOf<List<String>>(emptyList())
        private set
    data class TrafficFlow(val flowId: String, val method: String, val url: String, val domain: String, val path: String)
    var trafficFlows by mutableStateOf<List<TrafficFlow>>(emptyList())
        private set
    /** 从抓包请求体里拆出来的参数（键 → 值），自动填好，用户只改需要的值。 */
    var trafficParamValues by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    private var trafficPayloadRaw: String = "{}"
    var trafficStatus by mutableStateOf("")
        private set
    /** 上次识别到的候选（by/value），生成时直接复用，不需要再把 App 切到前台。 */
    private var autoCands: List<AutoCand> = emptyList()
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
        val savedBridge = readSavedBridgeConfig()
        rememberToken = platform.readTextFile(rememberMarkerPath()) != "0"
        if (rememberToken) loadRememberedTokens()

        val defaultUrl = cn.dumate.a6studio.config.AppConfig.defaultUrl
        val projectUrl = repo.current.bridge.url
        bridgeUrl = when {
            !savedBridge?.first.isNullOrBlank() -> savedBridge!!.first
            projectUrl.isNotBlank() && projectUrl != defaultUrl -> projectUrl
            // 兼容旧版本：若尚未有独立桥接配置文件且仅记住了一台设备令牌，优先恢复该设备地址
            rememberToken && bridge.authMap().size == 1 -> bridge.authMap().keys.first()
            else -> projectUrl.ifBlank { defaultUrl }
        }
        if (bridgeUrl.isNotBlank() && repo.current.bridge.url != bridgeUrl) {
            repo.updateBridgeUrl(bridgeUrl)
        }
        savedBridge?.second?.forEach { (u, n) ->
            if (u.isNotBlank() && repo.current.bridge.devices.none { normUrl(it.url) == normUrl(u) }) {
                repo.saveDevice(u, n)
            }
        }
        bridge.setUrl(bridgeUrl)
        bridgeToken = bridge.authMap()[normUrl(bridgeUrl)]?.removePrefix("Bearer ").orEmpty()
        loadAiConfig()

        // A first run should not land on an empty canvas with no explanation.
        val seenGuide = platform.readTextFile(GUIDE_MARKER) == "1"
        // 直接显示完整模式：默认不再进入简单模式（老用户仍可在「更多」里手动切换）
        beginnerMode = platform.readTextFile(BEGINNER_MARKER) == "1"
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
        const val BRIDGE_CONFIG_PATH = "a6studio.bridge.v1"
        const val AI_CONFIG_PATH = "a6studio.ai.v1"
        const val AI_TOKENS_PATH = "a6studio.tokens.ai.v1"
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

    fun clearDeviceLog() { deviceLog = emptyList() }

    fun clearActiveRunLogs() {
        if (sendToDevice) deviceLog = emptyList() else runLogs = emptyList()
    }

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
        val def = cn.dumate.a6studio.catalog.Catalog.byType(type) ?: return
        repo.addBlock(type)
        commit()
        toast("已添加：${cn.dumate.a6studio.catalog.Catalog.cleanName(def)}")
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
                    val level = (o?.get("level") as? JsonPrimitive)?.contentOrNull ?: "info"
                    val msg = (o?.get("message") as? JsonPrimitive)?.contentOrNull
                        ?: (o?.get("msg") as? JsonPrimitive)?.contentOrNull
                        ?: el.toString()
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
    private fun normUrl(url: String): String = url.trim().trimEnd('/')

    private fun redactUrlForLog(raw: String): String = raw
        .replace(Regex("""://([^/@\s]+)@"""), "://***@")
        .replace(Regex("""([?&])(token|key|secret|password)=([^&\s]+)""", RegexOption.IGNORE_CASE), "$1$2=***")

    fun applyBridgeConfig() {
        bridge.setUrl(bridgeUrl)
        if (bridgeToken.isNotBlank()) {
            try {
                bridge.setAuthFor(bridgeUrl, bridgeToken)
            } catch (e: BridgeException) {
                toast(e.message ?: "MCP 令牌无效")
            }
        }
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

    /** 读取真机 MCP 的 tools/list，把可用工具名显示在真机页；只读，不执行任何脚本。 */
    private data class AutoCand(val by: String, val value: String)

    /** 从 ui_dump 里挑出「可见 + 可点 + 有文字」的控件，动作类文案排前面。 */
    private fun extractAutoCandidates(dump: JsonObject?): List<AutoCand> {
        if (dump == null) return emptyList()
        val out = LinkedHashMap<String, AutoCand>()
        val action = Regex("^(去|立即|马上|点击|继续|领取|签到|逛|看|完成|打开|搜索|查|抢|报名|参与|做任务)")
        // 任务类按钮优先：签到/领取/去完成/浏览/逛/看视频…；底部导航和频道入口排在后面。
        val taskWords = Regex("(签到|领取|去完成|做任务|浏览|逛|看视频|赚|开宝箱|抽奖|参与|报名|抢|领券|领豆|金币|奖励|打卡)")
        val navWords = setOf(
            "首页", "推荐", "关注", "我的", "购物车", "消息", "分类", "二手", "爱车",
            "看病买药", "国家补贴", "大牌奥莱", "秒杀", "医药健康", "家电家居", "京东宠物",
            "直播", "更多", "返回", "关闭"
        )
        fun score(v: String): Int {
            if (v in navWords) return -1
            var s = 0
            if (taskWords.containsMatchIn(v)) s += 3
            if (action.containsMatchIn(v)) s += 1
            return s
        }
        var seen = 0
        fun walk(el: JsonElement?) {
            if (seen >= 300 || out.size >= 40) return
            when (el) {
                is JsonObject -> {
                    seen++
                    val clickable = (el["clickable"] as? JsonPrimitive)?.booleanOrNull == true ||
                        (el["clickable"] as? JsonPrimitive)?.contentOrNull == "true"
                    val visible = (el["visibleToUser"] as? JsonPrimitive)?.booleanOrNull != false &&
                        (el["visible"] as? JsonPrimitive)?.booleanOrNull != false
                    if (clickable && visible) {
                        val text = (el["text"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                        val desc = (el["desc"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                            .ifEmpty { (el["contentDescription"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty() }
                        val label = text.ifEmpty { desc }
                        if (label.isNotEmpty() && label.length <= 24 && !out.containsKey(label)) {
                            out[label] = AutoCand(if (text.isNotEmpty()) "text" else "desc", label)
                        }
                    }
                    el.values.forEach(::walk)
                }
                is JsonArray -> el.forEach(::walk)
                else -> Unit
            }
        }
        walk(dump)
        val ranked = out.values
            .map { it to score(it.value) }
            .filter { it.second >= 0 }
            .sortedWith(compareByDescending<Pair<AutoCand, Int>> { it.second }.thenBy { it.first.value.length })
            .map { it.first }
        return (if (ranked.isNotEmpty()) ranked else out.values.toList()).take(12)
    }

    /**
     * 一键识别：给 5 秒倒计时让用户切到目标 App，然后由 AutoJs6 读它当前的前台窗口与控件树。
     * 整个过程中不要求用户输入包名、选择器或参数。
     */
    fun autoDetectForeground(delaySec: Int = 5) {
        if (autoBusy) return
        scope.launch {
            autoBusy = true
            platformRef.startKeepAlive()
            try {
                applyBridgeConfig()
                for (s in delaySec downTo 1) {
                    autoStatus = "$s 秒后采集：现在请切到要自动化的 App（保持屏幕亮着）…"
                    kotlinx.coroutines.delay(1000)
                }
                autoStatus = "正在读取目标 App 的页面…"
                val win = withContext(Dispatchers.Default) { bridge.currentWindow() }
                val pkg = win.packageName
                if (pkg.isBlank() || pkg == "cn.dumate.a6studio") {
                    autoStatus = "没读到目标 App：请确认已切到目标 App 且无障碍正常，再点一次"
                    return@launch
                }
                val label = runCatching { bridge.listApps() }.getOrDefault(emptyList())
                    .firstOrNull { it.first == pkg }?.second.orEmpty()
                autoPkg = pkg
                autoPkgLabel = label.ifBlank { pkg }
                val dump = withContext(Dispatchers.Default) { bridge.uiDumpJson(maxNodes = 350, maxDepth = 30) }
                autoCands = extractAutoCandidates(dump)
                autoCandidates = autoCands.map { it.value }
                autoStatus = if (autoCands.isEmpty()) {
                    "已识别 ${autoPkgLabel}（$pkg），但没找到可点按钮；先手动把目标页打开再识别"
                } else {
                    "已识别 ${autoPkgLabel}（$pkg）；可点：${autoCandidates.take(6).joinToString("、")}"
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                autoStatus = "识别失败：${e.message}"
            } finally {
                autoBusy = false
                platformRef.stopKeepAlive()
            }
        }
    }

    /** 一键生成：打开目标 App → 等候选按钮 → 点它 → 收工，全部参数来自真机采集。 */
    fun selectAutoCandidate(value: String) {
        autoSelected = value
        autoStatus = "已选按钮：$value（可生成新脚本或追加为下一步）"
    }

    fun autoGenerateFlow(pickedValue: String? = null, runAfter: Boolean = false) {
        if (autoBusy) return
        scope.launch {
            autoBusy = true
            autoStatus = "正在采集当前页面并生成脚本…"
            try {
                applyBridgeConfig()
                val win = withContext(Dispatchers.Default) { bridge.currentWindow() }
                val pkg = autoPkg.ifBlank { win.packageName }
                if (pkg.isBlank() || pkg == "cn.dumate.a6studio") {
                    autoStatus = "先打开要自动化的 App（或点「从已装应用选」），再点「生成」"
                    return@launch
                }
                val cands = if (autoCands.isNotEmpty()) autoCands else {
                    val dump = withContext(Dispatchers.Default) { bridge.uiDumpJson(maxNodes = 400, maxDepth = 32) }
                    extractAutoCandidates(dump).also { autoCands = it }
                }
                if (cands.isEmpty()) {
                    autoStatus = "还没有识别到按钮：先点「① 识别当前页面」"
                    return@launch
                }
                val chosen = (pickedValue ?: autoSelected).takeIf { it.isNotBlank() }
                    ?.let { v -> cands.firstOrNull { it.value == v } } ?: cands.first()
                val label = autoPkgLabel.substringAfterLast('.').ifBlank { pkg }
                val body = mutableListOf<Block>()
                body += newBlock("autoWait")
                body += newBlock(
                    "kitBoot",
                    mapOf(
                        "pkg" to ParamValue.Text(pkg),
                        "name" to ParamValue.Text("自动 · $label"),
                        "maxRuntimeMs" to ParamValue.Num(600000.0)
                    )
                )
                body += newBlock("kitLaunch")
                body += newBlock("kitGuard")
                body += newBlock(
                    "kitWaitFor",
                    mapOf(
                        "by" to ParamValue.Text(chosen.by),
                        "match" to ParamValue.Text("contains"),
                        "value" to ParamValue.Text(chosen.value),
                        "timeoutMs" to ParamValue.Num(12000.0)
                    )
                )
                body += newBlock(
                    "kitClick",
                    mapOf(
                        "by" to ParamValue.Text(chosen.by),
                        "match" to ParamValue.Text("contains"),
                        "value" to ParamValue.Text(chosen.value)
                    )
                )
                body += newBlock("kitFinish", mapOf("result" to ParamValue.Text("auto")))
                val project = Project(
                    name = "自动 · $label",
                    root = Project.rootBlock().copy(children = mapOf("body" to body))
                )
                repo.snapshot("一键生成")
                repo.setProjectDirect(project)
                autoPkg = pkg
                autoStatus = "已生成：打开 $label → 等「${chosen.value}」→ 点它 → 结束。可去「运行」页试跑。"
                refreshAll()
                toast("已生成脚本，共 ${body.size} 个积木")
                autoSelected = chosen.value
                // 生成后清空旧候选：下一步必须在目标 App 的新页面上重新识别，避免把同一步重复追加
                autoCands = emptyList()
                autoCandidates = emptyList()
                if (runAfter) run()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                autoStatus = "生成失败：${e.message}"
            } finally {
                autoBusy = false
            }
        }
    }

    /**
     * 多步流程：在当前「一键生成」的脚本后面再追加一步（等一个按钮 → 点它）。
     * 用法：在真机上操作到下一页 → 回本页点「① 识别」→ 点「追加为下一步」。
     */
    fun autoAppendStep(pickedValue: String? = null) {
        if (autoBusy) return
        val hasKitBoot = repo.current.root.children["body"].orEmpty().any { it.type == "kitBoot" }
        if (!hasKitBoot) {
            autoStatus = "当前脚本还不是可运行的一键流程：先点「② 生成脚本」"
            return
        }
        scope.launch {
            autoBusy = true
            autoStatus = "正在追加下一步…"
            try {
                val cands = if (autoCands.isNotEmpty()) autoCands else {
                    applyBridgeConfig()
                    val dump = withContext(Dispatchers.Default) { bridge.uiDumpJson(maxNodes = 400, maxDepth = 32) }
                    extractAutoCandidates(dump).also { autoCands = it }
                }
                if (cands.isEmpty()) {
                    autoStatus = "先点「① 识别」拿到当前页面的按钮，再追加"
                    return@launch
                }
                val chosen = (pickedValue ?: autoSelected).takeIf { it.isNotBlank() }
                    ?.let { v -> cands.firstOrNull { it.value == v } } ?: cands.first()
                val blocks = listOf(
                    newBlock(
                        "kitWaitFor",
                        mapOf(
                            "by" to ParamValue.Text(chosen.by),
                            "match" to ParamValue.Text("contains"),
                            "value" to ParamValue.Text(chosen.value),
                            "timeoutMs" to ParamValue.Num(12000.0)
                        )
                    ),
                    newBlock(
                        "kitClick",
                        mapOf(
                            "by" to ParamValue.Text(chosen.by),
                            "match" to ParamValue.Text("contains"),
                            "value" to ParamValue.Text(chosen.value)
                        )
                    )
                )
                repo.addBlocksAtScopeEnd(blocks)
                commit()
                autoStatus = "已追加下一步：等「${chosen.value}」→ 点它。到新页面后点「① 识别」可再追加。"
                toast("已追加 1 步")
                autoCands = emptyList()
                autoCandidates = emptyList()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                autoStatus = "追加失败：${e.message}"
            } finally {
                autoBusy = false
            }
        }
    }

    fun listDeviceTools() {
        applyBridgeConfig()
        deviceToolsText = "正在读取工具清单…"
        scope.launch {
            try {
                val all = LinkedHashMap<String, String>()
                val schemas = LinkedHashMap<String, JsonObject>()
                var cursor: String? = null
                var pages = 0
                var lastError: String? = null
                // MCP 的 tools/list 可能分页：每页 20 个并带 nextCursor，必须循环取完。
                do {
                    pages++
                    val cur = cursor
                    val resp = withContext(Dispatchers.Default) {
                        mcpGateway.call(
                            CallMcpGatewayOptions(
                                serverId = McpServerId.AUTOJS6,
                                method = "tools/list",
                                endpointUrl = bridgeUrl,
                                allowLocal = true,
                                authToken = bridgeToken,
                                args = buildJsonObject { if (!cur.isNullOrBlank()) put("cursor", cur) }
                            )
                        )
                    }
                    if (!resp.ok) { lastError = resp.errorMessage; break }
                    val obj = resp.data as? JsonObject
                    val arr = obj?.get("tools") as? JsonArray
                    arr?.forEach { el ->
                        val jo = el as? JsonObject ?: return@forEach
                        val n = (jo["name"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                        val d = (jo["description"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        all[n] = d
                        (jo["inputSchema"] as? JsonObject)?.let { schemas[n] = it }
                    }
                    cursor = (obj?.get("nextCursor") as? JsonPrimitive)?.contentOrNull
                } while (!cursor.isNullOrBlank() && pages < 10)

                deviceToolNames = all.keys.toList()
                deviceToolDescriptions = all
                deviceToolSchemas = schemas
                deviceToolParamValues = emptyMap()
                if (deviceToolNameDraft.isBlank()) deviceToolNameDraft = deviceToolNames.firstOrNull().orEmpty()
                deviceToolsText = when {
                    lastError != null && all.isEmpty() -> "读取工具清单失败：$lastError"
                    all.isEmpty() -> "该版本没有返回工具清单（可能是旧版 MCP 插件）"
                    else -> buildString {
                        append("可用工具 ${all.size} 个（$pages 页）：")
                        append(all.keys.joinToString("、"))
                        if (all.size < 40) {
                            append("\n只看到 ${all.size} 个通常是 AutoJs6 默认关掉了一些工具组。")
                            append("到 AutoJs6 → 插件 → MCP Server → 设置 → 工具组，把「脚本 / 文件 / Shell / 手势 / 应用」等打开，再点一次工具清单。")
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                deviceToolsText = "读取工具清单失败：${e.message}"
            }
        }
    }

    /** 直接调用任意真机 MCP 工具：工具名 + JSON 参数，结果脱敏后显示。 */
    fun runDeviceTool(name: String, argsJson: String) {
        if (deviceToolRunning) return
        val tool = name.trim()
        if (tool.isEmpty()) { deviceToolResult = "先填工具名，或点上面的「工具清单」挑一个"; return }
        scope.launch {
            deviceToolRunning = true
            deviceToolResult = "正在调用 $tool …"
            try {
                val argsObj = runCatching {
                    Json.parseToJsonElement(argsJson.ifBlank { "{}" }) as? JsonObject
                }.getOrNull() ?: throw IllegalArgumentException("参数必须是 JSON 对象，例如 {\"lines\": 20}")
                applyBridgeConfig()
                val resp = withContext(Dispatchers.Default) {
                    mcpGateway.call(
                        CallMcpGatewayOptions(
                            serverId = McpServerId.AUTOJS6,
                            method = "tools/call",
                            toolName = tool,
                            args = argsObj,
                            endpointUrl = bridgeUrl,
                            allowLocal = true,
                            authToken = bridgeToken
                        )
                    )
                }
                deviceToolResult = if (resp.ok) {
                    val text = resp.data.toString()
                    "成功（${resp.latencyMs}ms）：\n" + text.take(6000)
                } else {
                    "失败：${resp.errorMessage}"
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                deviceToolResult = "调用失败：${e.message}"
            } finally {
                deviceToolRunning = false
            }
        }
    }

    data class ToolParamField(
        val name: String,
        val type: String,
        val desc: String,
        val required: Boolean,
        val options: List<String> = emptyList()
    )

    /** 按工具的 inputSchema 生成表单字段：用户只点选/填值，不再手写 JSON。 */
    fun toolParamsFor(name: String): List<ToolParamField> {
        val schema = deviceToolSchemas[name] ?: return emptyList()
        val props = schema["properties"] as? JsonObject ?: return emptyList()
        val required = (schema["required"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty().toSet()
        return props.map { (k, v) ->
            val o = v as? JsonObject
            val type = (o?.get("type") as? JsonPrimitive)?.contentOrNull.orEmpty().ifBlank { "string" }
            val desc = (o?.get("description") as? JsonPrimitive)?.contentOrNull.orEmpty()
            val options = (o?.get("enum") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            ToolParamField(k, type, desc, k in required, options)
        }
    }

    fun setToolParam(name: String, value: String) {
        deviceToolParamValues = deviceToolParamValues + (name to value)
    }

    /** 用表单值拼出 JSON 参数并调用；数字/开关按 schema 类型转换。 */
    fun runDeviceToolFromForm(name: String) {
        val tool = name.trim()
        if (tool.isEmpty()) { deviceToolResult = "先点「工具清单」并选一个工具"; return }
        val args = buildJsonObject {
            toolParamsFor(tool).forEach { p ->
                val raw = deviceToolParamValues[p.name]?.trim().orEmpty()
                if (raw.isNotEmpty()) {
                    when (p.type.lowercase()) {
                        "number", "integer" -> put(p.name, raw.toDoubleOrNull() ?: 0.0)
                        "boolean" -> put(p.name, raw.equals("true", true))
                        else -> put(p.name, raw)
                    }
                }
            }
        }
        runDeviceToolJson(tool, args)
    }

    private fun runDeviceToolJson(tool: String, argsObj: JsonObject) {
        if (deviceToolRunning) return
        scope.launch {
            deviceToolRunning = true
            deviceToolResult = "正在调用 $tool …"
            try {
                applyBridgeConfig()
                val resp = withContext(Dispatchers.Default) {
                    mcpGateway.call(
                        CallMcpGatewayOptions(
                            serverId = McpServerId.AUTOJS6,
                            method = "tools/call",
                            toolName = tool,
                            args = argsObj,
                            endpointUrl = bridgeUrl,
                            allowLocal = true,
                            authToken = bridgeToken
                        )
                    )
                }
                deviceToolResult = if (resp.ok) "成功（${resp.latencyMs}ms）：\n" + resp.data.toString().take(6000)
                else "失败：${resp.errorMessage}"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                deviceToolResult = "调用失败：${e.message}"
            } finally {
                deviceToolRunning = false
            }
        }
    }

    /** 读取已安装应用列表，供「选 App 自动打开并采集」使用（不需要打开 App 再切换）。 */
    fun loadInstalledApps() {
        if (appsLoading) return
        scope.launch {
            appsLoading = true
            autoStatus = "正在读取已装应用…"
            try {
                applyBridgeConfig()
                installedApps = withContext(Dispatchers.Default) {
                    kotlinx.coroutines.withTimeout(20_000) { bridge.listApps() }
                }.sortedBy { it.second.lowercase() }
                autoStatus = if (installedApps.isEmpty()) {
                    "没读到应用列表：确认 AutoJs6 MCP 的 app_list 工具可用（工具组已开），或改用「① 识别」"
                } else {
                    "读到 ${installedApps.size} 个应用，点一个就自动打开并采集"
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                autoStatus = "读取应用列表失败：${e.message}（可改用「① 识别」）"
            } finally {
                appsLoading = false
            }
        }
    }

    /** 选中一个已安装应用：自动启动它、等页面出来、采集控件，仍不需要用户切 App 或输包名。 */
    fun launchAppAndCapture(pkg: String, label: String) {
        if (autoBusy) return
        scope.launch {
            autoBusy = true
            autoStatus = "正在打开 ${label.ifBlank { pkg }} 并采集页面…"
            platformRef.startKeepAlive()
            try {
                applyBridgeConfig()
                withContext(Dispatchers.Default) { bridge.launchApp(pkg) }
                kotlinx.coroutines.delay(2500)
                val win = withContext(Dispatchers.Default) { bridge.currentWindow() }
                autoPkg = pkg
                autoPkgLabel = label.ifBlank { win.packageName }
                val dump = withContext(Dispatchers.Default) { bridge.uiDumpJson(maxNodes = 350, maxDepth = 30) }
                autoCands = extractAutoCandidates(dump)
                autoCandidates = autoCands.map { it.value }
                autoStatus = if (autoCands.isEmpty()) {
                    "已打开 ${autoPkgLabel}，但没找到可点按钮；可等页面加载后点「① 识别」"
                } else {
                    "已打开并识别 ${autoPkgLabel}；可点：${autoCandidates.take(6).joinToString("、")}"
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                autoStatus = "打开并采集失败：${e.message}"
            } finally {
                autoBusy = false
                platformRef.stopKeepAlive()
            }
        }
    }

    /** 自动读 ProxyPin 抓包，列出域名与接口，供一键选用（不需要手输域名）。 */
    /** 一键自检：确认 AutoJs6 连接、设备信息，结果直接用人话显示在卡片上。 */
    fun autoSelfCheck() {
        if (autoBusy) return
        scope.launch {
            autoBusy = true
            autoStatus = "正在检查 AutoJs6 连接…"
            try {
                applyBridgeConfig()
                val ms = bridge.ping()
                val info = bridge.deviceInfo()
                deviceInfo = info
                deviceStatusText = "真机：${info.label}"
                deviceStatusLevel = "ok"
                autoStatus = "✅ 已连接 ${info.label}（${ms}ms，Android ${info.androidRelease}）"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                autoStatus = "❌ 未连接：" + describeBridgeFailure(e).replace("\n", " ")
                deviceStatusLevel = "err"
            } finally {
                autoBusy = false
            }
        }
    }

    /** 自动扫本机回环端口，找出 AutoJs6（9637/8765）与 ProxyPin（9127/9000）地址，省去手填。 */
    fun autoDiscoverLocalServices() {
        if (autoBusy) return
        scope.launch {
            autoBusy = true
            autoStatus = "正在找本机的 AutoJs6 / ProxyPin 服务…"
            try {
                val autoCandidates = listOf(
                    "http://127.0.0.1:9637/mcp",
                    "http://127.0.0.1:8765/mcp",
                    "http://127.0.0.1:9127/mcp"
                )
                var autoUrl: String? = null
                for (u in autoCandidates) {
                    val tok = bridge.authMap()[normUrl(u)]?.removePrefix("Bearer ").orEmpty()
                    val r = mcpGateway.call(
                        CallMcpGatewayOptions(
                            serverId = McpServerId.AUTOJS6,
                            method = "tools/list",
                            endpointUrl = u,
                            allowLocal = true,
                            authToken = tok,
                            timeoutMs = 2500
                        )
                    )
                    val msg = r.errorMessage.orEmpty()
                    if (r.ok || msg.contains("UNAUTHORIZED") || msg.contains("PAIRING")) { autoUrl = u; break }
                }
                val proxyCandidates = listOf(
                    "http://127.0.0.1:9127/mcp",
                    "http://127.0.0.1:9000/mcp",
                    "http://127.0.0.1:8765/mcp"
                )
                var proxyUrl: String? = null
                for (u in proxyCandidates) {
                    val r = proxyPin.gateway.call(
                        CallMcpGatewayOptions(
                            serverId = McpServerId.PROXYPIN,
                            method = "tools/list",
                            endpointUrl = u,
                            allowLocal = true,
                            authToken = proxyPinToken,
                            timeoutMs = 2500
                        )
                    )
                    val msg = r.errorMessage.orEmpty()
                    if (r.ok || msg.contains("UNAUTHORIZED") || msg.contains("PAIRING")) { proxyUrl = u; break }
                }
                if (autoUrl != null) {
                    updateBridgeUrl(autoUrl)
                    bridgeToken = bridge.authMap()[normUrl(autoUrl)]?.removePrefix("Bearer ").orEmpty()
                }
                if (proxyUrl != null) updateProxyPinUrl(proxyUrl)
                autoStatus = buildString {
                    append(if (autoUrl != null) "AutoJs6：$autoUrl" else "没找到 AutoJs6 MCP（9637）")
                    append("；")
                    append(if (proxyUrl != null) "ProxyPin：$proxyUrl" else "没找到 ProxyPin（9127/9000）")
                    if ((autoUrl != null && bridgeToken.isBlank()) || (proxyUrl != null && proxyPinToken.isBlank())) {
                        append("。有服务但没有令牌：从对应 App 的 MCP 设置里复制一次令牌即可")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                autoStatus = "扫描失败：${e.message}"
            } finally {
                autoBusy = false
            }
        }
    }

    fun autoAnalyzeProxyPin() {
        if (aiBusy) return
        scope.launch {
            aiBusy = true
            trafficStatus = "正在读取 ProxyPin 抓包…"
            try {
                val effectiveTransport = if (!devMode && mcpTransport == McpTransport.LOCAL_BRIDGE) {
                    McpTransport.HTTP_JSONRPC
                } else mcpTransport
                if (effectiveTransport == McpTransport.HTTP_JSONRPC) {
                    proxyPin.configure(proxyPinUrl, proxyPinToken, allowLocal = true)
                }
                val resp = withContext(Dispatchers.Default) {
                    proxyPin.listHttpFlows(domain = proxyPinDomainFilter, limit = 50, transport = effectiveTransport, devMode = devMode)
                }
                if (!resp.ok) {
                    trafficStatus = "读取抓包失败：${resp.errorMessage}"
                    return@launch
                }
                val flows = ((resp.data as? JsonObject)?.get("flows") as? JsonArray) ?: JsonArray(emptyList())
                val doms = LinkedHashSet<String>()
                val eps = LinkedHashSet<String>()
                val tf = mutableListOf<TrafficFlow>()
                fun s(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
                flows.forEach { el ->
                    val o = el as? JsonObject ?: return@forEach
                    val url = s(o, "url").ifBlank { s(o, "requestUrl") }
                    val dom = s(o, "domain").ifBlank {
                        Regex("^https?://([^/:]+)").find(url)?.groupValues?.get(1).orEmpty()
                    }
                    val path = s(o, "path").ifBlank { Regex("^https?://[^/]+(/.*)$").find(url)?.groupValues?.get(1).orEmpty() }
                    val method = s(o, "method").ifBlank { "GET" }
                    if (dom.isNotBlank()) doms += dom
                    if (path.isNotBlank()) eps += "$method $path"
                    tf += TrafficFlow(s(o, "flowId").ifBlank { s(o, "id") }, method, url, dom, path)
                }
                trafficDomains = doms.toList().take(12)
                trafficEndpoints = eps.toList().take(30)
                trafficFlows = tf
                // 自动把第一条 POST 的请求体拆成参数，后面生成请求积木直接用它，无需用户写 JSON
                trafficParamValues = emptyMap()
                trafficPayloadRaw = "{}"
                tf.firstOrNull { it.method.uppercase() in setOf("POST", "PUT", "PATCH") }?.let { first ->
                    if (first.method.uppercase() in setOf("POST", "PUT", "PATCH") && first.flowId.isNotBlank()) {
                        val detail = runCatching {
                            withContext(Dispatchers.Default) {
                                proxyPin.getFlowDetail(first.flowId, transport = effectiveTransport, devMode = devMode)
                            }
                        }.getOrNull()
                        val payload = if (detail?.ok == true) extractRequestPayload(detail.data) else null
                        if (!payload.isNullOrBlank()) {
                            trafficPayloadRaw = payload
                            val obj = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
                            val map = LinkedHashMap<String, String>()
                            obj?.forEach { (k, v) ->
                                if (v is JsonPrimitive) map[k] = v.content
                            }
                            trafficParamValues = map
                        }
                    }
                }
                if (proxyPinDomainFilter.isBlank() && trafficDomains.isNotEmpty()) {
                    updateProxyPinDomainFilter(trafficDomains.first())
                }
                trafficStatus = when {
                    flows.isEmpty() -> "没读到抓包：先在 ProxyPin 里抓一次目标 App 的请求，再点一次"
                    else -> "解析到 ${flows.size} 条包、${trafficDomains.size} 个域名、${trafficEndpoints.size} 个接口"
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                trafficStatus = "读取抓包失败：${e.message}"
            } finally {
                aiBusy = false
            }
        }
    }

    fun setTrafficParam(name: String, value: String) {
        trafficParamValues = trafficParamValues + (name to value)
    }

    private fun extractRequestPayload(el: JsonElement?): String? {
        when (el) {
            is JsonObject -> {
                for ((k, v) in el) {
                    val key = k.lowercase()
                    // ProxyPin 1.4: detail.request.body = { available, binary, text: "..." }
                    if (key == "body" && v is JsonObject) {
                        val binary = (v["binary"] as? JsonPrimitive)?.contentOrNull == "true"
                        val t = (v["text"] as? JsonPrimitive)?.contentOrNull
                        if (!binary && !t.isNullOrBlank()) return t
                    }
                    if (key in setOf("requestbody", "postdata", "body", "reqbody", "request_body") &&
                        v is JsonPrimitive && v.isString && v.content.isNotBlank()
                    ) return v.content
                    // body 块里的 text 字段（父对象带 returned/available）
                    if (key == "text" && el.containsKey("returned") && v is JsonPrimitive && v.isString && v.content.isNotBlank()) {
                        return v.content
                    }
                    extractRequestPayload(v)?.let { return it }
                }
            }
            is JsonArray -> el.forEach { extractRequestPayload(it)?.let { r -> return r } }
            else -> Unit
        }
        return null
    }

    /** 用抓到的第一条接口，自动生成一个 GET/POST 请求积木（URL、方法、请求体都来自抓包）。 */
    fun autoGenerateRequestFromTraffic() {
        val f = trafficFlows.firstOrNull { it.method.uppercase() in setOf("POST", "PUT", "PATCH") }
            ?: trafficFlows.firstOrNull()
        if (f == null) { trafficStatus = "先点「自动解析接口」拿到抓包"; return }
        if (aiBusy) return
        scope.launch {
            aiBusy = true
            trafficStatus = "正在按抓包生成请求积木…"
            try {
                val effectiveTransport = if (!devMode && mcpTransport == McpTransport.LOCAL_BRIDGE) {
                    McpTransport.HTTP_JSONRPC
                } else mcpTransport
                if (effectiveTransport == McpTransport.HTTP_JSONRPC) {
                    proxyPin.configure(proxyPinUrl, proxyPinToken, allowLocal = true)
                }
                var payload = "{}"
                val m = f.method.uppercase()
                if (trafficParamValues.isNotEmpty()) {
                    payload = buildJsonObject {
                        trafficParamValues.forEach { (k, v) -> if (v.isNotBlank()) put(k, v) }
                    }.toString()
                } else if (m in setOf("POST", "PUT", "PATCH") && f.flowId.isNotBlank()) {
                    payload = trafficPayloadRaw
                    if (payload == "{}") {
                        val detail = withContext(Dispatchers.Default) {
                            proxyPin.getFlowDetail(f.flowId, transport = effectiveTransport, devMode = devMode)
                        }
                        if (detail.ok) extractRequestPayload(detail.data)?.let { payload = it }
                    }
                }
                val url = f.url.ifBlank { "https://${f.domain}${f.path}" }
                val blocks = mutableListOf<Block>()
                if (m == "GET") {
                    blocks += newBlock("httpGet", mapOf("url" to ParamValue.Text(url), "var" to ParamValue.Text("res")))
                } else {
                    blocks += newBlock(
                        "httpPostJson",
                        mapOf(
                            "url" to ParamValue.Text(url),
                            "payload" to ParamValue.Text(payload),
                            "var" to ParamValue.Text("res")
                        )
                    )
                }
                repo.addBlocksAtScopeEnd(blocks)
                commit()
                trafficStatus = "已生成请求积木：$m $url（请求体来自抓包，已脱敏）"
                toast("已按抓包生成 ${blocks.size} 个请求积木")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                trafficStatus = "生成请求积木失败：${e.message}"
            } finally {
                aiBusy = false
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
        val prevToken = bridgeToken
        bridgeUrl = url
        repo.updateBridgeUrl(url)
        bridge.setUrl(url)
        val savedForNew = bridge.authMap()[normUrl(url)]?.removePrefix("Bearer ").orEmpty()
        if (savedForNew.isNotEmpty()) {
            bridgeToken = savedForNew
        } else if (prevToken.isNotBlank()) {
            // 手工编辑地址时保留当前已填令牌并绑定到新地址，避免每改一个字符就清空令牌
            bridgeToken = prevToken
            try {
                bridge.setAuthFor(url, prevToken)
                persistTokens()
            } catch (_: BridgeException) {
                // 地址或令牌尚在输入中时忽略中间态异常
            }
        } else {
            bridgeToken = ""
        }
        persistBridgeConfig()
    }

    fun updateBridgeToken(token: String) {
        bridgeToken = token
        try {
            bridge.setAuthFor(bridgeUrl, token)
            persistTokens()
        } catch (e: BridgeException) {
            toast(e.message ?: "MCP 令牌无效")
        }
    }

    fun changeRememberToken(v: Boolean) {
        rememberToken = v
        platform.writeTextFile(rememberMarkerPath(), if (v) "1" else "0")
        if (v) {
            // 合并磁盘已有令牌与当前内存令牌（当前内存优先），绝不清空用户刚输入的令牌
            val merged = readRememberedTokensMap().toMutableMap()
            merged.putAll(bridge.authMap())
            if (bridgeToken.isNotBlank() && bridgeUrl.isNotBlank()) {
                runCatching { McpClient.buildAuthorizationHeader(bridgeToken) }.getOrNull()?.let { hdr ->
                    merged[normUrl(bridgeUrl)] = hdr
                }
            }
            bridge.setAuthMap(merged)
            persistTokens()
            persistAiTokens()
        } else {
            // 关闭记住开关只清空磁盘凭据文件，保留当前会话内存中的输入
            platform.writeTextFile(tokenPath(), "{}")
            platform.writeTextFile(AI_TOKENS_PATH, "{}")
        }
    }

    private fun rememberMarkerPath() = "a6studio.remember.v1"
    private fun tokenPath() = "a6studio.tokens.v1"

    private fun readRememberedTokensMap(): Map<String, String> {
        val raw = platform.readTextFile(tokenPath()) ?: return emptyMap()
        return runCatching {
            Json.parseToJsonElement(raw).let { el ->
                (el as? JsonObject)
                    ?.entries
                    ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { normUrl(k) to it } }
                    ?.toMap()
                    .orEmpty()
            }
        }.getOrDefault(emptyMap())
    }

    private fun loadRememberedTokens() {
        bridge.setAuthMap(readRememberedTokensMap())
    }

    private fun persistTokens() {
        if (!rememberToken) {
            platform.writeTextFile(tokenPath(), "{}")
            return
        }
        val obj = buildJsonObject { bridge.authMap().forEach { (k, v) -> put(k, v) } }
        platform.writeTextFile(tokenPath(), obj.toString())
    }

    private fun readSavedBridgeConfig(): Pair<String, List<Pair<String, String>>>? {
        val raw = platform.readTextFile(BRIDGE_CONFIG_PATH) ?: return null
        return runCatching {
            val obj = Json.parseToJsonElement(raw) as? JsonObject ?: return null
            val url = (obj["url"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val devices = (obj["devices"] as? JsonArray)?.mapNotNull { el ->
                val d = el as? JsonObject ?: return@mapNotNull null
                val u = (d["url"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                val n = (d["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                if (u.isEmpty()) null else u to n
            }.orEmpty()
            url to devices
        }.getOrNull()
    }

    private fun persistBridgeConfig() {
        val obj = buildJsonObject {
            put("version", 1)
            put("url", bridgeUrl)
            put("devices", buildJsonArray {
                repo.current.bridge.devices.forEach { d ->
                    add(buildJsonObject {
                        put("url", d.url)
                        put("name", d.name)
                    })
                }
            })
        }
        platform.writeTextFile(BRIDGE_CONFIG_PATH, obj.toString())
    }

    private fun loadAiConfig() {
        var legacyProxyToken = ""
        var legacyGeminiKey = ""
        var legacyOpenAiKey = ""
        var migratedLegacySecrets = false

        platform.readTextFile(AI_CONFIG_PATH)?.let { raw ->
            runCatching {
                val obj = Json.parseToJsonElement(raw) as? JsonObject ?: return@runCatching
                (obj["mcpTransport"] as? JsonPrimitive)?.contentOrNull?.let { trId ->
                    McpTransport.entries.firstOrNull { it.id == trId || it.name == trId }?.let { tr ->
                        if (tr == McpTransport.HTTP_JSONRPC || devMode) mcpTransport = tr
                    }
                }
                (obj["proxyPinUrl"] as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotBlank()) proxyPinUrl = it }
                (obj["proxyPinDomainFilter"] as? JsonPrimitive)?.contentOrNull?.let { proxyPinDomainFilter = it }
                (obj["llmProvider"] as? JsonPrimitive)?.contentOrNull?.let { pid ->
                    LlmProviderId.entries.firstOrNull { it.id == pid || it.name == pid }?.let { llmProvider = it }
                }
                (obj["proxyPinToken"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                    legacyProxyToken = it
                    migratedLegacySecrets = true
                }
                val lc = obj["llmConfig"] as? JsonObject
                if (lc != null) {
                    fun s(k: String, fallback: String): String =
                        (lc[k] as? JsonPrimitive)?.contentOrNull ?: fallback
                    (lc["geminiApiKey"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                        legacyGeminiKey = it
                        migratedLegacySecrets = true
                    }
                    (lc["openAiApiKey"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                        legacyOpenAiKey = it
                        migratedLegacySecrets = true
                    }
                    llmConfig = llmConfig.copy(
                        geminiModel = s("geminiModel", llmConfig.geminiModel),
                        openAiBaseUrl = s("openAiBaseUrl", llmConfig.openAiBaseUrl),
                        openAiModel = s("openAiModel", llmConfig.openAiModel),
                        ollamaBaseUrl = s("ollamaBaseUrl", llmConfig.ollamaBaseUrl),
                        ollamaModel = s("ollamaModel", llmConfig.ollamaModel),
                        lmStudioBaseUrl = s("lmStudioBaseUrl", llmConfig.lmStudioBaseUrl),
                        lmStudioModel = s("lmStudioModel", llmConfig.lmStudioModel),
                        allowLocalInference = (lc["allowLocalInference"] as? JsonPrimitive)?.booleanOrNull
                            ?: llmConfig.allowLocalInference
                    )
                }
            }
        }

        if (rememberToken) {
            var tokenFromFile = ""
            var geminiFromFile = ""
            var openAiFromFile = ""
            platform.readTextFile(AI_TOKENS_PATH)?.let { raw ->
                runCatching {
                    val obj = Json.parseToJsonElement(raw) as? JsonObject ?: return@runCatching
                    tokenFromFile = (obj["proxyPinToken"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    geminiFromFile = (obj["geminiApiKey"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    openAiFromFile = (obj["openAiApiKey"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                }
            }
            proxyPinToken = tokenFromFile.ifEmpty { legacyProxyToken }
            llmConfig = llmConfig.copy(
                geminiApiKey = geminiFromFile.ifEmpty { legacyGeminiKey },
                openAiApiKey = openAiFromFile.ifEmpty { legacyOpenAiKey }
            )
            if (migratedLegacySecrets) {
                persistAiTokens()
                persistAiConfig()
            }
        } else if (migratedLegacySecrets) {
            // 关闭记住令牌时，也顺手清理旧版配置文件中的明文凭据
            persistAiConfig()
        }

        if (proxyPinUrl.isNotBlank()) {
            runCatching { proxyPin.configure(proxyPinUrl, proxyPinToken, allowLocal = true) }
        }
    }

    private fun persistAiConfig() {
        val obj = buildJsonObject {
            put("version", 1)
            put("mcpTransport", mcpTransport.id)
            put("proxyPinUrl", proxyPinUrl)
            put("proxyPinDomainFilter", proxyPinDomainFilter)
            put("llmProvider", llmProvider.id)
            put("llmConfig", buildJsonObject {
                put("geminiModel", llmConfig.geminiModel)
                put("openAiBaseUrl", llmConfig.openAiBaseUrl)
                put("openAiModel", llmConfig.openAiModel)
                put("ollamaBaseUrl", llmConfig.ollamaBaseUrl)
                put("ollamaModel", llmConfig.ollamaModel)
                put("lmStudioBaseUrl", llmConfig.lmStudioBaseUrl)
                put("lmStudioModel", llmConfig.lmStudioModel)
                put("allowLocalInference", llmConfig.allowLocalInference)
            })
        }
        platform.writeTextFile(AI_CONFIG_PATH, obj.toString())
    }

    private fun persistAiTokens() {
        if (!rememberToken) {
            platform.writeTextFile(AI_TOKENS_PATH, "{}")
            return
        }
        val obj = buildJsonObject {
            if (proxyPinToken.isNotEmpty()) put("proxyPinToken", proxyPinToken)
            if (llmConfig.geminiApiKey.isNotEmpty()) put("geminiApiKey", llmConfig.geminiApiKey)
            if (llmConfig.openAiApiKey.isNotEmpty()) put("openAiApiKey", llmConfig.openAiApiKey)
        }
        platform.writeTextFile(AI_TOKENS_PATH, obj.toString())
    }

    fun updateMcpTransport(tr: McpTransport) {
        mcpTransport = tr
        persistAiConfig()
    }

    fun updateProxyPinUrl(url: String) {
        proxyPinUrl = url
        persistAiConfig()
    }

    fun updateProxyPinToken(token: String) {
        proxyPinToken = token
        persistAiTokens()
    }

    fun updateProxyPinDomainFilter(filter: String) {
        proxyPinDomainFilter = filter
        persistAiConfig()
    }

    fun updateLlmProvider(pid: LlmProviderId) {
        llmProvider = pid
        persistAiConfig()
    }

    fun updateLlmConfig(cfg: LlmConfig) {
        val secretChanged = cfg.geminiApiKey != llmConfig.geminiApiKey || cfg.openAiApiKey != llmConfig.openAiApiKey
        llmConfig = cfg
        persistAiConfig()
        if (secretChanged) persistAiTokens()
    }

    fun saveCurrentDevice() {
        repo.saveDevice(bridgeUrl, "")
        persistBridgeConfig()
        toast("已保存设备")
    }

    fun switchDevice(url: String) {
        if (url.isBlank()) return
        bridgeUrl = url
        repo.updateBridgeUrl(url)
        bridge.setUrl(url)
        bridgeToken = bridge.authMap()[normUrl(url)]?.removePrefix("Bearer ").orEmpty()
        persistBridgeConfig()
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
        val parsed = cn.dumate.a6studio.bridge.McpConfigParser.parse(text)
        if (parsed.isEmpty()) {
            toast("$source 里没有找到 MCP 地址（支持 mcpServers JSON、URL、IP，每行一台）")
            return 0
        }
        // Same gate as ProxyPin import: an imported file must not be able to point the client at
        // a metadata endpoint or smuggle CR/LF into the Authorization header.
        val rejected = mutableListOf<String>()
        val found = parsed.filter { c ->
            val reason = cn.dumate.a6studio.bridge.McpImportCheck.reject(c.url, c.token)
            if (reason != null) rejected += "${redactUrlForLog(c.url)}：$reason"
            reason == null
        }
        rejected.forEach { appendDevice("error", "已跳过 $it") }
        if (found.isEmpty()) {
            toast("$source 里的 MCP 地址都被安全策略拒绝了，详情见设备日志")
            return 0
        }
        found.forEach { c ->
            repo.saveDevice(c.url, cn.dumate.a6studio.bridge.McpConfigParser.label(c))
            if (c.token.isNotEmpty()) bridge.setAuthFor(c.url, c.token)
        }
        persistTokens()
        val first = found.first()
        bridgeUrl = first.url
        repo.updateBridgeUrl(first.url)
        bridge.setUrl(first.url)
        bridgeToken = first.token.ifBlank { bridge.authMap()[normUrl(first.url)]?.removePrefix("Bearer ").orEmpty() }
        persistBridgeConfig()
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

    // ---- ProxyPin MCP config import -------------------------------------------------------
    /** AI 面板「粘贴 ProxyPin 配置」框里的文本。 */
    var proxyPinImportText by mutableStateOf("")

    /**
     * 从 [text] 里挑出一台 ProxyPin MCP（多台时优先名字或 URL 含 proxypin 的），
     * 校验通过后写进 [proxyPinUrl] / [proxyPinToken]（Token 仅内存持有，绝不走
     * 「记住令牌」持久化）并 configure 桥接。返回 1 表示已应用，0 表示没找到或被拒。
     */
    fun importProxyPinConfig(text: String, source: String): Int {
        val picked = cn.dumate.a6studio.bridge.ProxyPinImport.pick(text)
        if (picked == null) {
            toast("$source 里没有找到 ProxyPin MCP 地址（支持 mcpServers JSON、URL、IP）")
            return 0
        }
        // 写状态之前先过地址 + 令牌两道门槛。坏令牌（如含 CR/LF）在 configure 之前就被拒，
        // 绝不会先写进上面的输入框再失败。
        cn.dumate.a6studio.bridge.ProxyPinImport.checkBeforeWrite(picked.url, picked.token)
            ?.let { toast(it); return 0 }
        try {
            proxyPin.configure(picked.url, picked.token, allowLocal = true)
        } catch (e: BridgeException) {
            toast("ProxyPin 配置失败：${e.message}")
            return 0
        }
        // configure 成功后才写状态，界面上与桥接里是同一份干净配置，并持久化到本机私有存储。
        proxyPinUrl = picked.url
        proxyPinToken = picked.token
        persistAiConfig()
        persistAiTokens()
        // 多台时提示挑了哪台；只有一台就不用说。
        val foundCount = cn.dumate.a6studio.bridge.McpConfigParser
            .parseWithDefaultPort(text, cn.dumate.a6studio.bridge.ProxyPinImport.DEFAULT_PORT)
            .size
        val multiPrefix = if (foundCount > 1) {
            val label = picked.name.ifBlank {
                picked.url.removePrefix("http://").removePrefix("https://").removeSuffix("/mcp")
            }
            "检测到 $foundCount 个，已使用 $label · "
        } else ""
        if (picked.token.isEmpty()) {
            toast("${multiPrefix}已应用 ProxyPin 地址（未带令牌）：${redactUrlForLog(picked.url)}")
        } else {
            val suffix = if (rememberToken) "已保存在本机" else "仅内存持有"
            toast("${multiPrefix}已应用 ProxyPin 地址与令牌（$suffix）")
        }
        return 1
    }

    fun importProxyPinFromClipboard() {
        val text = platform.readClipboard()
        if (text.isNullOrBlank()) { toast("剪贴板是空的：先复制 ProxyPin 的 MCP 配置"); return }
        importProxyPinConfig(text, "剪贴板")
    }

    fun importProxyPinFromFile() {
        scope.launch {
            val picked = platform.openTextFile(listOf("json", "txt")) ?: return@launch
            importProxyPinConfig(picked.second, "文件「${picked.first}」")
        }
    }

    fun importProxyPinFromText() {
        if (importProxyPinConfig(proxyPinImportText, "粘贴内容") > 0) proxyPinImportText = ""
    }

    /** Copies the saved devices back out as an mcpServers JSON (tokens included only if known). */
    fun exportMcpConfig() {
        val servers = buildJsonObject {
            repo.current.bridge.devices.forEach { d ->
                put(d.name.ifBlank { d.url }, buildJsonObject {
                    put("url", d.url)
                    bridge.authMap()[normUrl(d.url)]?.let { auth -> put("headers", buildJsonObject { put("Authorization", auth) }) }
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
            persistAiConfig()
        }
    }

    private suspend fun collectAutoJsState(): Boolean {
        aiStatusMsg = "正在通过 AutoJs6 MCP 采集设备与控件树状态…"
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
            return false
        }
        lastAutoJsStateReport = resp.redactionReport ?: Redaction.redactTrafficPayload(resp.data)
        lastAutoJsIsSynthetic = resp.isSynthetic
        val tag = if (resp.isSynthetic) "【合成数据】" else ""
        aiStatusMsg = "${tag}已采集 AutoJs6 设备状态（耗时 ${resp.latencyMs}ms）"
        toast("${tag}AutoJs6 设备状态已载入")
        return true
    }

    fun fetchAutoJsDeviceState() {
        if (aiBusy) return
        scope.launch {
            aiBusy = true
            try {
                collectAutoJsState()
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

    /** 小白一键：先采集当前界面，再让 AI 用人话给出下一步；不配置模型时走离线规则引擎。 */
    fun analyzeCurrentScreenWithAi(goal: String) {
        if (aiBusy) return
        scope.launch {
            aiBusy = true
            try {
                if (!collectAutoJsState()) return@launch
                aiBusy = false
                requestAiRefine(goal)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                aiStatusMsg = "AI 分析失败：${e.message}"
            } finally {
                if (aiBusy) aiBusy = false
            }
        }
    }

    fun fetchProxyPinTraffic() {
        if (aiBusy) return
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
        if (aiBusy) return
        if (mayUseCloudProvider()) {
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
        if (aiBusy) return
        val g = pendingCloudGoal
        pendingCloudPreview = null
        runAiRefine(g)
    }

    fun cancelCloudRefine() {
        pendingCloudPreview = null
        aiStatusMsg = "已取消发送云端"
    }

    fun runAiRefine(goal: String) {
        if (aiBusy) return
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

    // ---- LLM 模型拉取与选择 --------------------------------------------------------------

    /** 拉取当前通道的可用模型清单；成功写入 [llmModelOptions] 并 toast，失败写入 [llmModelsError]。 */
    fun fetchLlmModels(pid: LlmProviderId) {
        if (llmModelsLoading != null) return
        scope.launch {
            llmModelsLoading = pid
            try {
                val models = withContext(Dispatchers.Default) { llmClient.listModels(pid, llmConfig) }
                llmModelOptions = llmModelOptions + (pid to models)
                llmModelsError = llmModelsError - pid
                if (models.isEmpty()) {
                    aiStatusMsg = "${pid.label} 返回了空模型列表（当前模型不会被改动）"
                    toast(aiStatusMsg)
                } else {
                    aiStatusMsg = "已拉取 ${models.size} 个模型（${pid.label}）"
                    toast("已拉取 ${models.size} 个模型")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                llmModelsError = llmModelsError + (pid to (e.message ?: e::class.simpleName ?: "拉取失败"))
                aiStatusMsg = "模型拉取失败（${pid.label}）：${e.message}"
                toast(aiStatusMsg)
            } finally {
                llmModelsLoading = null
            }
        }
    }

    /** 把选中的模型 id 写进 llmConfig 对应通道并持久化到本机 AI 配置。 */
    fun selectLlmModel(pid: LlmProviderId, modelId: String) {
        if (modelId.isBlank()) return
        llmConfig = when (pid) {
            LlmProviderId.GEMINI -> llmConfig.copy(geminiModel = modelId)
            LlmProviderId.OPENAI_COMPATIBLE -> llmConfig.copy(openAiModel = modelId)
            LlmProviderId.OLLAMA -> llmConfig.copy(ollamaModel = modelId)
            LlmProviderId.LMSTUDIO -> llmConfig.copy(lmStudioModel = modelId)
            else -> return
        }
        persistAiConfig()
        toast("已选择模型：$modelId")
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
        // 导入脚本/切换模板时保留本机已保存的真机地址与设备列表，不被默认占位地址冲掉
        val defaultUrl = cn.dumate.a6studio.config.AppConfig.defaultUrl
        val projUrl = repo.current.bridge.url
        if (bridgeUrl.isNotBlank() && (projUrl.isBlank() || projUrl == defaultUrl)) {
            repo.updateBridgeUrl(bridgeUrl)
        } else if (projUrl.isNotBlank() && projUrl != bridgeUrl) {
            bridgeUrl = projUrl
            bridge.setUrl(bridgeUrl)
            bridgeToken = bridge.authMap()[normUrl(bridgeUrl)]?.removePrefix("Bearer ").orEmpty()
            persistBridgeConfig()
        }
        readSavedBridgeConfig()?.second?.forEach { (u, n) ->
            if (u.isNotBlank() && repo.current.bridge.devices.none { normUrl(it.url) == normUrl(u) }) {
                repo.saveDevice(u, n)
            }
        }
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
        val code = CodeGen.code(repo.current)
        val leaked = Redaction.assertNoPlaintextSecrets(code)
        if (leaked.isNotEmpty()) {
            toast("复制已拦截：脚本包含疑似明文凭据（${leaked.joinToString("、")}）")
            return
        }
        platform.copyToClipboard(code)
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
    fun pickerClearSwipePoints() { picker.clearSwipePoints() }

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
        if (patches.isEmpty()) {
            toast("没有可自动写入的修复项（护栏积木不支持 id 定位，请用拾取器重拾）")
            return
        }
        val appliedIds = patches.map { it.first }.toSet()
        repo.patchParamsBatch(patches, "体检修好选择器")
        val n = patches.size
        val skipped = fixable.size - n
        commit()
        auditRows = auditRows.map { r ->
            val fix = r.fix
            if (fix != null && r.blockId in appliedIds) r.copy(
                by = fix.first,
                value = fix.second,
                status = cn.dumate.a6studio.audit.AuditStatus.UNIQUE,
                note = "已改为 ${audit.selText(fix.first, fix.second)}",
                fix = null
            ) else r
        }
        toast(
            if (skipped > 0) "已修好 $n 处选择器（另有 $skipped 处护栏积木不支持该定位方式，请用拾取器重拾）"
            else "已修好 $n 处选择器"
        )
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