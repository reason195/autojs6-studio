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
import cn.dumate.a6studio.ui.fields.InlineField
import cn.dumate.a6studio.ui.fields.trackTextFocus
import cn.dumate.a6studio.ui.panes.SmallButton
import cn.dumate.a6studio.ui.panes.TinyToggle

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiTrafficPane(state: StudioState, modifier: Modifier = Modifier) {
    var goal by remember { mutableStateOf("用大白话说明当前页面能做什么、下一步该点哪个，按顺序列出来") }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
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
                if (state.hasExternalMcpChanges || state.pendingExternalDeviceConfirm) {
                    Surface(
                        color = Palette.panel,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, Palette.warn)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "当前工程包含外部 MCP 补丁修改，真机运行前请审查生成代码并确认",
                                fontSize = 11.sp,
                                color = Palette.warn,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = { state.confirmAndRunOnDevice() },
                                colors = ButtonDefaults.buttonColors(containerColor = Palette.warn, contentColor = Palette.onAccent)
                            ) {
                                Text("确认并真机运行", fontSize = 11.sp)
                            }
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

        if (!state.beginnerMode) {
        // 1. 双 MCP JSON-RPC 2.0 网关（AutoJs6 设备 MCP + ProxyPin 抓包 MCP）
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

                if (state.mcpTransport == McpTransport.HTTP_JSONRPC) {
                    OutlinedTextField(
                        value = state.proxyPinUrl,
                        onValueChange = { state.updateProxyPinUrl(it) },
                        label = { Text("ProxyPin MCP 地址（AutoJs6 地址复用「真机」页签配置）", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().trackTextFocus()
                    )
                    var showProxyToken by remember { mutableStateOf(false) }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = state.proxyPinToken,
                            onValueChange = { state.updateProxyPinToken(it) },
                            label = { Text("ProxyPin 鉴权 Token（不写入项目文件）", fontSize = 11.sp) },
                            singleLine = true,
                            visualTransformation = if (showProxyToken) VisualTransformation.None else PasswordVisualTransformation(),
                            modifier = Modifier.weight(1f).trackTextFocus()
                        )
                        SmallButton(if (showProxyToken) "隐藏" else "显示") { showProxyToken = !showProxyToken }
                    }
                    TinyToggle("记住本机令牌与 API Key（写进本机私有存储，不进项目文件）", state.rememberToken) {
                        state.changeRememberToken(it)
                    }
                    // 导入：与「真机」页签同一套入口，但只挑 ProxyPin 那台（默认端口 9000）。
                    Text("导入 ProxyPin 配置（mcpServers JSON / URL / IP）", fontSize = 11.sp, color = Palette.textDim)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SmallButton("从剪贴板导入", primary = true) { state.importProxyPinFromClipboard() }
                        if (state.platformRef.supportsFileDialogs) {
                            SmallButton("从文件导入") { state.importProxyPinFromFile() }
                        }
                    }
                    InlineField(
                        state.proxyPinImportText, { state.proxyPinImportText = it },
                        placeholder = "或粘贴到这里：mcpServers JSON / http://IP:9000/mcp 令牌 / 只写 IP",
                        mono = true, singleLine = false, minLines = 2, maxLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (state.proxyPinImportText.isNotBlank()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SmallButton("导入粘贴内容", primary = true) { state.importProxyPinFromText() }
                            SmallButton("清空") { state.proxyPinImportText = "" }
                        }
                    }
                }

                OutlinedTextField(
                    value = state.proxyPinDomainFilter,
                    onValueChange = { state.updateProxyPinDomainFilter(it) },
                    label = { Text("抓包域名过滤（可选，如 ms.jr.jd.com）", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().trackTextFocus()
                )
                Text(
                    "抓不到目标 App 的包？在 ProxyPin 里开启 VPN/透明代理（或给目标 App 单独配代理）——系统代理对部分 App 无效。",
                    fontSize = 9.sp, color = Palette.textDim
                )

                androidx.compose.foundation.layout.FlowRow(
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
                    androidx.compose.foundation.layout.FlowRow(
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

        // 2. 多通道大模型提炼
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
                    onValueChange = { goal = it },
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

        // 3. 提炼结果预览
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
        }
    }
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
