package cn.dumate.a6studio.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.dumate.a6studio.ai.LlmProviderId
import cn.dumate.a6studio.bridge.McpTransport
import cn.dumate.a6studio.ui.components.SectionTabs
import cn.dumate.a6studio.ui.fields.InlineField
import cn.dumate.a6studio.ui.fields.trackTextFocus
import cn.dumate.a6studio.ui.panes.SmallButton
import cn.dumate.a6studio.ui.panes.TinyToggle

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiTrafficPane(state: StudioState, modifier: Modifier = Modifier) {
    var goal by remember { mutableStateOf("用大白话说明当前页面能做什么、下一步该点哪个，按顺序列出来") }
    // 默认停在「连接模型」：先连好大模型，才做分析/提炼（与真机页「连接」在前一致）
    var aiTab by remember { mutableStateOf("model") }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 标签条：按使用逻辑排序——连接模型 → AI 分析 → 双 MCP 网关 → 提炼结果
        SectionTabs(
            tabs = listOf(
                "model" to "连接模型",
                "analyze" to "AI 分析",
                "gateway" to "双 MCP 网关",
                "result" to "提炼结果"
            ),
            selected = aiTab,
            onSelect = { aiTab = it }
        )

        // 分析/提炼/结果都依赖大模型：未配置模型时先引导到「连接模型」页，符合「先连接再分析」。
        if (aiTab != "gateway" && aiTab != "model" && !modelReady(state)) {
            Spacer(Modifier.height(4.dp))
            Surface(
                color = Palette.panelAlt,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, Palette.warn)
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "还没有连接大模型：AI 分析 / 提炼依赖模型连接。先在「连接模型」页选通道并填好 Base URL / Key。",
                        fontSize = 11.sp,
                        color = Palette.warn
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallButton("去连接模型", primary = true) { aiTab = "model" }
                        if (state.llmProvider == cn.dumate.a6studio.ai.LlmProviderId.DETERMINISTIC_RULES) {
                            Text("（当前是离线规则引擎，无云端模型）", fontSize = 9.5.sp, color = Palette.textDim)
                        }
                    }
                }
            }
            return@Column
        }

        when (aiTab) {
            "model" -> AiModelTab(state, goal) { g -> goal = g }
            "analyze" -> AiAnalyzeTab(state, goal)
            "gateway" -> AiGatewayTab(state)
            "result" -> AiResultTab(state)
        }
    }
}

/** 是否已配置可用的云端/本地大模型。纯离线规则引擎（DETERMINISTIC_RULES）算未配置。
 * 注意：OLLAMA/LMSTUDIO 的默认 Base URL 视为「未配置」——用户没动过它就不算连上模型，
 * 否则默认状态下 AUTO 分支恒为 true，「先连接大模型」拦截永不生效。 */
private fun modelReady(state: StudioState): Boolean {
    val p = state.llmProvider
    val c = state.llmConfig
    // 本地服务的默认地址不算「已连接」：只有用户显式改过才认可。
    fun localReady(url: String, default: String) = url.isNotBlank() && url != default
    return when (p) {
        cn.dumate.a6studio.ai.LlmProviderId.AUTO ->
            c.geminiApiKey.isNotBlank() ||
                (c.openAiApiKey.isNotBlank() && c.openAiBaseUrl.isNotBlank()) ||
                localReady(c.ollamaBaseUrl, "http://127.0.0.1:11434/v1") ||
                localReady(c.lmStudioBaseUrl, "http://127.0.0.1:1234/v1")
        cn.dumate.a6studio.ai.LlmProviderId.GEMINI -> c.geminiApiKey.isNotBlank()
        cn.dumate.a6studio.ai.LlmProviderId.OPENAI_COMPATIBLE -> c.openAiApiKey.isNotBlank() && c.openAiBaseUrl.isNotBlank()
        cn.dumate.a6studio.ai.LlmProviderId.OLLAMA -> localReady(c.ollamaBaseUrl, "http://127.0.0.1:11434/v1")
        cn.dumate.a6studio.ai.LlmProviderId.LMSTUDIO -> localReady(c.lmStudioBaseUrl, "http://127.0.0.1:1234/v1")
        else -> false
    }
}

// ===================== AI 分析（新手两步入口 + 本地服务端） =====================
@Composable
private fun AiAnalyzeTab(state: StudioState, goal: String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 0. 本地 Studio MCP 服务端：只有桌面端提供回调；Android 宿主没有，不渲染这张卡片
        if (state.onToggleLocalMcp != null) {
            Surface(
                color = Palette.panelAlt,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, Palette.border)
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                if (state.localMcpEnabled) "本地 Studio MCP 服务端（已启用 · 需 Bearer 令牌）"
                                else "本地 Studio MCP 服务端（默认关闭）",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (state.localMcpEnabled) Palette.ok else Palette.text
                            )
                            Text(
                                state.localMcpEndpoint ?: "开启后仅监听 127.0.0.1，生成一次性 32 字节随机令牌",
                                fontSize = 11.sp,
                                color = Palette.textDim
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (state.localMcpEnabled) {
                                OutlinedButton(onClick = { state.copyLocalMcpEndpoint() }) {
                                    Text("复制地址与令牌", fontSize = 11.sp)
                                }
                            }
                            OutlinedButton(onClick = { state.toggleLocalMcp(!state.localMcpEnabled) }) {
                                Text(if (state.localMcpEnabled) "关闭" else "开启", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }

        // 新手：AI 分析两步入口（采集当前界面 → 开始分析）
        Surface(
            color = Palette.panelAlt,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, Palette.border)
        ) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("用 AI 分析当前手机界面", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
                Text(
                    "先点「① 采集当前界面」自动抓取并脱敏页面，再点「② 开始分析」；没配置模型时用离线规则引擎，不会上传到云端。",
                    fontSize = 11.sp,
                    color = Palette.textDim
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton("① 采集当前界面") { state.fetchAutoJsDeviceState() }
                    SmallButton("② AI 分析（说人话）", primary = true) { state.analyzeCurrentScreenWithAi(goal) }
                }
                if (state.autoJsPlainSummary.isNotBlank()) {
                    Text("已经读到：${state.autoJsPlainSummary}", fontSize = 11.sp, color = Palette.info)
                }
                if (state.refinePlainSummary.isNotBlank()) {
                    Text("AI 说：${state.refinePlainSummary}", fontSize = 11.sp, color = Palette.ok)
                }
            }
        }
    }
}

// ===================== 双 MCP JSON-RPC 网关 =====================
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiGatewayTab(state: StudioState) {
    if (state.beginnerMode) return
    Surface(
        color = Palette.panelAlt,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Palette.border)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("双 MCP JSON-RPC 2.0 网关（自动脱敏）", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val visibleTransports = if (state.devMode) {
                    McpTransport.entries
                } else {
                    listOf(McpTransport.HTTP_JSONRPC)
                }
                visibleTransports.forEach { tr ->
                    FilterChip(
                        selected = state.mcpTransport == tr,
                        onClick = { state.updateMcpTransport(tr) },
                        label = { Text(tr.label, fontSize = 11.sp) }
                    )
                }
                FilterChip(
                    selected = state.devMode,
                    onClick = { state.toggleDevMode(!state.devMode) },
                    label = { Text("开发者合成调测", fontSize = 11.sp) }
                )
            }

            // 连接配置已统一收进「真机」页的「连接」标签（AutoJs6 / ProxyPin 两节），这里只做采集与解析。
            Surface(
                color = Palette.panel,
                shape = RoundedCornerShape(6.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "MCP 连接与令牌请到「真机」页 →「连接」标签配置（AutoJs6 MCP 与 ProxyPin MCP 分两节填写）。",
                        fontSize = 10.5.sp, color = Palette.textDim
                    )
                    OutlinedButton(
                        onClick = { state.showPane(cn.dumate.a6studio.ui.Pane.DEVICE) },
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Text("去真机连接页", fontSize = 11.sp)
                    }
                }
            }
            Text(
                "抓不到目标 App 的包？在 ProxyPin 里开启 VPN/透明代理（或给目标 App 单独配代理）——系统代理对部分 App 无效。",
                fontSize = 9.sp, color = Palette.textDim
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = { state.fetchAutoJsDeviceState() },
                    enabled = !state.aiBusy
                ) {
                    Text("采集 AutoJs6 状态", fontSize = 12.sp)
                }
                Button(
                    onClick = { state.fetchProxyPinTraffic() },
                    enabled = !state.aiBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.onAccent)
                ) {
                    Text("拉取并脱敏流量", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = { state.autoAnalyzeProxyPin() },
                    enabled = !state.aiBusy
                ) {
                    Text("自动解析接口", fontSize = 12.sp)
                }
            }

            state.lastAutoJsStateReport?.let { devRep ->
                val synTag = if (state.lastAutoJsIsSynthetic) "【合成数据】" else ""
                Text(
                    "${synTag}AutoJs6 状态已就绪（脱敏键 ${devRep.stats.redactedKeyCount} / 掩码 ${devRep.stats.maskedPatternCount}）",
                    fontSize = 11.sp,
                    color = if (state.lastAutoJsIsSynthetic) Palette.warn else Palette.ok
                )
            }
            state.lastTrafficReport?.let { rep ->
                val synTag = if (state.lastTrafficIsSynthetic) "【合成数据】" else ""
                Text(
                    "${synTag}ProxyPin 流量已就绪（脱敏键 ${rep.stats.redactedKeyCount} / 掩码 ${rep.stats.maskedPatternCount}）",
                    fontSize = 11.sp,
                    color = if (state.lastTrafficIsSynthetic) Palette.warn else Palette.ok
                )
            }
            if (state.trafficStatus.isNotBlank()) {
                Text(state.trafficStatus, fontSize = 10.5.sp, color = Palette.info)
            }
            if (state.trafficDomains.isNotEmpty()) {
                Text("抓到的域名（点一个自动填入过滤）：", fontSize = 9.5.sp, color = Palette.textDim)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    state.trafficDomains.forEach { d ->
                        SmallButton(d) { state.updateProxyPinDomainFilter(d) }
                    }
                }
            }
            if (state.trafficEndpoints.isNotEmpty()) {
                Text("抓到的主要接口：", fontSize = 9.5.sp, color = Palette.textDim)
                state.trafficEndpoints.take(10).forEach { e ->
                    Text("· $e", fontSize = 10.sp, color = Palette.text, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
                if (state.trafficParamValues.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("抓到的请求参数（已自动填入，可直接改值）：", fontSize = 9.5.sp, color = Palette.textDim)
                    state.trafficParamValues.forEach { (k, v) ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(k, fontSize = 9.5.sp, color = Palette.textDim, modifier = Modifier.weight(0.4f))
                            InlineField(
                                v,
                                { state.setTrafficParam(k, it) },
                                placeholder = k,
                                mono = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                SmallButton("用第一条接口生成请求积木", primary = true) { state.autoGenerateRequestFromTraffic() }
            }
        }
    }
}

// ===================== 多通道大模型提炼 =====================
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiModelTab(state: StudioState, goal: String, onGoalChange: (String) -> Unit) {
    Surface(
        color = Palette.panelAlt,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Palette.border)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("多通道 LLM 提炼（无 Key 自动降级规则引擎）", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LlmProviderId.entries.forEach { pid ->
                    FilterChip(
                        selected = state.llmProvider == pid,
                        onClick = { state.updateLlmProvider(pid) },
                        label = { Text(pid.label.substringBefore("（"), fontSize = 11.sp) }
                    )
                }
            }

            if (state.llmProvider == LlmProviderId.GEMINI || state.llmProvider == LlmProviderId.AUTO) {
                OutlinedTextField(
                    value = state.llmConfig.geminiApiKey,
                    onValueChange = { state.updateLlmConfig(state.llmConfig.copy(geminiApiKey = it)) },
                    label = { Text("Gemini API Key（可选，留空走本地/规则）", fontSize = 11.sp) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().trackTextFocus()
                )
            }

            if (state.llmProvider == LlmProviderId.OPENAI_COMPATIBLE) {
                OutlinedTextField(
                    value = state.llmConfig.openAiBaseUrl,
                    onValueChange = { state.updateLlmConfig(state.llmConfig.copy(openAiBaseUrl = it)) },
                    label = { Text("OpenAI 兼容 Base URL（如 https://api.deepseek.com/v1）", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().trackTextFocus()
                )
                OutlinedTextField(
                    value = state.llmConfig.openAiApiKey,
                    onValueChange = { state.updateLlmConfig(state.llmConfig.copy(openAiApiKey = it)) },
                    label = { Text("API Key（不写入项目文件）", fontSize = 11.sp) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().trackTextFocus()
                )
            }

            if (state.llmProvider == LlmProviderId.OLLAMA || state.llmProvider == LlmProviderId.LMSTUDIO) {
                OutlinedTextField(
                    value = if (state.llmProvider == LlmProviderId.OLLAMA) state.llmConfig.ollamaBaseUrl else state.llmConfig.lmStudioBaseUrl,
                    onValueChange = {
                        state.updateLlmConfig(
                            if (state.llmProvider == LlmProviderId.OLLAMA) {
                                state.llmConfig.copy(ollamaBaseUrl = it)
                            } else {
                                state.llmConfig.copy(lmStudioBaseUrl = it)
                            }
                        )
                    },
                    label = { Text("${state.llmProvider.label.substringBefore("（")} Base URL", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().trackTextFocus()
                )
            }

            if (state.llmProvider != LlmProviderId.AUTO && state.llmProvider != LlmProviderId.DETERMINISTIC_RULES) {
                LlmModelRow(state, state.llmProvider)
            } else if (state.llmProvider == LlmProviderId.AUTO) {
                Text(
                    "自动路由按 Gemini → OpenAI 兼容 → 本地 的顺序尝试，各通道使用其已选模型",
                    fontSize = 11.sp,
                    color = Palette.textDim
                )
            }

            OutlinedTextField(
                value = goal,
                onValueChange = onGoalChange,
                label = { Text("提炼目标", fontSize = 11.sp) },
                modifier = Modifier.fillMaxWidth().trackTextFocus()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { state.requestAiRefine(goal) },
                    enabled = !state.aiBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.onAccent)
                ) {
                    Text(if (state.aiBusy) "处理中…" else "开始提炼", fontSize = 12.sp)
                }
                if (state.lastRefineOutput != null) {
                    val isSyn = state.lastRefineIsSynthetic || state.lastAutoJsIsSynthetic || state.lastTrafficIsSynthetic
                    OutlinedButton(
                        onClick = { state.applyRefinedToSelectedOrInsert() },
                        enabled = !isSyn
                    ) {
                        Text(if (isSyn) "合成数据禁止回填" else "护栏回填到积木", fontSize = 12.sp)
                    }
                }
            }

            state.pendingCloudPreview?.let { preview ->
                Surface(
                    color = Palette.panel,
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, Palette.warn)
                ) {
                    Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("发往云端模型前的脱敏载荷预览（请确认无敏感信息）：", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Palette.warn)
                        Text(preview, fontSize = 10.5.sp, color = Palette.textDim)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { state.confirmCloudRefine() },
                                colors = ButtonDefaults.buttonColors(containerColor = Palette.warn, contentColor = Palette.onAccent)
                            ) {
                                Text("确认发送云端", fontSize = 11.sp)
                            }
                            OutlinedButton(onClick = { state.cancelCloudRefine() }) {
                                Text("取消", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            if (state.aiStatusMsg.isNotBlank()) {
                Text(state.aiStatusMsg, fontSize = 11.sp, color = Palette.textDim)
            }
        }
    }
}

// ===================== 提炼结果预览 =====================
@Composable
private fun AiResultTab(state: StudioState) {
    state.lastRefineOutput?.let { out ->
        Surface(
            color = Palette.panel,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, Palette.border)
        ) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (state.refinePlainSummary.isNotBlank()) {
                    Text("小白解读：${state.refinePlainSummary}", fontSize = 12.sp, color = Palette.ok)
                    Spacer(Modifier.height(2.dp))
                }
                Text("提炼结果 · ${out.modelUsed}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
                Text("候选按钮词：${out.refinedKeywords.joinToString("、").ifEmpty { "无" }}", fontSize = 12.sp, color = Palette.info)
                Text("推荐浏览等待：${out.recommendedBrowseWaitMs} ms", fontSize = 12.sp, color = Palette.text)
                if (out.extractedApiHints.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    out.extractedApiHints.forEach { hint ->
                        Text("• ${hint.endpoint} [${hint.field}]: ${hint.meaning}", fontSize = 11.sp, color = Palette.textDim)
                    }
                }
            }
        }
    } ?: Text("还没有提炼结果：先在「模型提炼」页配置模型并点「开始提炼」。", fontSize = 11.sp, color = Palette.textDim)
}

/** 「模型」一行：手输框（直接写 config 字段）+「拉取模型」按钮 + 下拉选择已拉取的模型。 */
@Composable
private fun LlmModelRow(state: StudioState, pid: LlmProviderId) {
    val currentModel = when (pid) {
        LlmProviderId.GEMINI -> state.llmConfig.geminiModel
        LlmProviderId.OPENAI_COMPATIBLE -> state.llmConfig.openAiModel
        LlmProviderId.OLLAMA -> state.llmConfig.ollamaModel
        LlmProviderId.LMSTUDIO -> state.llmConfig.lmStudioModel
        else -> return
    }
    val onModelChange: (String) -> Unit = { value ->
        state.updateLlmConfig(
            when (pid) {
                LlmProviderId.GEMINI -> state.llmConfig.copy(geminiModel = value)
                LlmProviderId.OPENAI_COMPATIBLE -> state.llmConfig.copy(openAiModel = value)
                LlmProviderId.OLLAMA -> state.llmConfig.copy(ollamaModel = value)
                LlmProviderId.LMSTUDIO -> state.llmConfig.copy(lmStudioModel = value)
                else -> state.llmConfig
            }
        )
    }
    val loading = state.llmModelsLoading == pid
    val options = state.llmModelOptions[pid].orEmpty()
    var expanded by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        OutlinedTextField(
            value = currentModel,
            onValueChange = onModelChange,
            label = { Text("模型（手输或拉取后选择）", fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.weight(1f).trackTextFocus()
        )
        OutlinedButton(
            onClick = { state.fetchLlmModels(pid) },
            enabled = state.llmModelsLoading == null && !state.aiBusy,
            modifier = Modifier.padding(top = 6.dp)
        ) {
            Text(if (loading) "拉取中…" else "拉取模型", fontSize = 11.sp)
        }
    }

    if (options.isNotEmpty()) {
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(Palette.panel)
                    .clickable { expanded = true }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    if (currentModel in options.map { it.id }) "已拉取 ${options.size} 个模型，点此更换 ▾"
                    else "已拉取 ${options.size} 个模型，点此选择 ▾",
                    fontSize = 11.sp,
                    color = Palette.text,
                    modifier = Modifier.weight(1f)
                )
                Text("▾", fontSize = 9.sp, color = Palette.textDim)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { m ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(m.displayName, fontSize = 11.sp)
                                if (m.displayName != m.id) {
                                    Text(m.id, fontSize = 10.sp, color = Palette.textDim)
                                }
                            }
                        },
                        onClick = {
                            expanded = false
                            state.selectLlmModel(pid, m.id)
                        }
                    )
                }
            }
        }
    }

    state.llmModelsError[pid]?.let { err ->
        Text(err, fontSize = 11.sp, color = Palette.err)
    }
}