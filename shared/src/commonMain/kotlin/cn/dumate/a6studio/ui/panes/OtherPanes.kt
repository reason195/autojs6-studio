package cn.dumate.a6studio.ui.panes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.dumate.a6studio.diagnose.Level
import cn.dumate.a6studio.store.ProjectRepo
import cn.dumate.a6studio.ui.BeginnerCopy
import cn.dumate.a6studio.ui.Palette as P
import cn.dumate.a6studio.ui.StudioState
import cn.dumate.a6studio.ui.components.Badge
import cn.dumate.a6studio.ui.components.BadgeKind
import cn.dumate.a6studio.ui.components.Chip
import cn.dumate.a6studio.ui.components.EmptyHint
import cn.dumate.a6studio.ui.components.KV
import cn.dumate.a6studio.ui.components.SectionTabs
import cn.dumate.a6studio.ui.fields.DropdownField
import cn.dumate.a6studio.ui.fields.EditableDropdownField
import cn.dumate.a6studio.ui.fields.InlineField
import cn.dumate.a6studio.ui.fields.SearchField

@Composable
fun TestPane(state: StudioState, modifier: Modifier = Modifier) {
    val project = state.repo.current
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("测试用例", fontSize = 11.sp, color = P.textDim)
            Spacer(Modifier.weight(1f))
            SmallButton("+ 用例") { state.repo.addTest(); state.commit() }
            Spacer(Modifier.width(5.dp))
            if (state.testsRunning) SmallButton("■ 停止", primary = true) { state.stopRun() }
            else SmallButton("运行全部", primary = true) { state.runTests() }
        }

        LazyColumn(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            if (project.tests.isEmpty()) {
                item { EmptyHint("还没有用例，点右上「+ 用例」") }
            }
            if (project.tests.isNotEmpty()) {
                item {
                    Text(
                        "可用：\$setScreen / \$state / \$run / \$log / assert",
                        fontSize = 9.5.sp, color = P.textDim,
                        modifier = Modifier.padding(top = 6.dp, bottom = 10.dp)
                    )
                }
            }
            items(count = project.tests.size, key = { i -> project.tests[i].id }) { i ->
                val t = project.tests[i]
                var name by remember(t.id, t.name) { mutableStateOf(t.name) }
                var code by remember(t.id, t.code) { mutableStateOf(t.code) }
                val result = state.testResults.firstOrNull { it.id == t.id }

                Column(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(P.panelAlt)
                        .padding(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Chip("T${i + 1}", P.accent)
                        Spacer(Modifier.width(6.dp))
                        InlineField(
                            value = name,
                            onValueChange = { name = it; state.repo.updateTest(t.id, name = it) },
                            placeholder = "用例名",
                            modifier = Modifier.weight(1f),
                            onEditingFinished = { state.repo.endParamEdit() }
                        )
                        // 结果放在标题行：一列用例扫一眼就知道哪个挂了，不用逐张卡片找底部徽标。
                        result?.let {
                            Spacer(Modifier.width(6.dp))
                            Badge(if (it.ok) "通过" else "失败", if (it.ok) BadgeKind.OK else BadgeKind.ERR)
                        }
                        Spacer(Modifier.width(5.dp))
                        Text(
                            "✕", fontSize = 11.sp, color = P.err.copy(alpha = 0.8f),
                            // 删用例是没确认步骤的（跟积木卡片一致），但至少要立刻说清楚能撤销，
                            // 否则手机上误触一次就以为代码没了。
                            modifier = Modifier.clickable {
                                state.repo.removeTest(t.id)
                                state.commit()
                                state.toast("已删除用例，点 ↶ 可撤销")
                            }.padding(3.dp)
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    InlineField(
                        value = code,
                        onValueChange = { code = it; state.repo.updateTest(t.id, code = it) },
                        placeholder = "断言代码",
                        mono = true,
                        singleLine = false,
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                        onEditingFinished = { state.repo.endParamEdit() }
                    )
                    Spacer(Modifier.height(5.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SmallButton("运行此用例") { state.runTests(listOf(t.id)) }
                    }
                    result?.takeIf { !it.ok && it.error.isNotEmpty() }?.let {
                        Text(
                            it.error, fontSize = 10.5.sp, color = P.err,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }

        if (state.testSummary.isNotEmpty()) {
            Text(
                state.testSummary,
                fontSize = 11.sp,
                color = when {
                    state.testSummary.startsWith("运行") -> P.textDim
                    state.testResults.all { it.ok } -> P.ok
                    else -> P.err
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
fun DiagPane(state: StudioState, modifier: Modifier = Modifier, beginner: Boolean = false) {
    val issues = state.issues
    val errors = issues.count { it.level == Level.ERROR }
    val warns = issues.count { it.level == Level.WARN }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (beginner) "这里会告诉你哪里可能跑不通" else "静态诊断 + 运行后诊断",
                fontSize = 11.sp, color = P.textDim
            )
            Spacer(Modifier.weight(1f))
            Badge(
                if (errors > 0) "$errors 错误 · $warns 警告" else if (warns > 0) "$warns 警告" else "无问题",
                if (errors > 0) BadgeKind.ERR else if (warns > 0) BadgeKind.WARN else BadgeKind.OK
            )
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            if (issues.isEmpty()) {
                item {
                    EmptyHint(
                        if (beginner) "目前没发现问题，可以直接点「▶ 运行」试试。改完积木再回来检查一次就行。"
                        else "没有发现问题。改完积木记得跑一遍「测试」和「运行」。"
                    )
                }
            }
            if (issues.isNotEmpty()) {
                item {
                    Text(
                        "点任意一行跳到对应积木；改完再回来检查一次",
                        fontSize = 10.sp, color = P.textDim,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
            }
            items(count = issues.size) { i ->
                val issue = issues[i]
                val color = when (issue.level) {
                    Level.ERROR -> P.err
                    Level.WARN -> P.warn
                    Level.INFO -> P.info
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(color.copy(alpha = 0.07f))
                        .clickable {
                            issue.blockId?.let { id ->
                                state.repo.scopeOfBlock(id)?.let { targetScope ->
                                    if (state.repo.currentScope != targetScope) state.repo.setScope(targetScope)
                                }
                                state.repo.select(id)
                                state.mobileSheet = cn.dumate.a6studio.ui.MobileSheet.CANVAS
                            }
                        }
                        .padding(9.dp)
                ) {
                    Text(
                        when (issue.level) {
                            Level.ERROR -> "错误"
                            Level.WARN -> "警告"
                            Level.INFO -> "提示"
                        },
                        fontSize = 9.5.sp,
                        color = color,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(30.dp)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (beginner) BeginnerCopy.issueTitle(issue.title) else issue.title,
                            fontSize = 11.5.sp, color = P.text
                        )
                        if (issue.hint.isNotEmpty()) {
                            Text(
                                if (beginner) BeginnerCopy.issueHint(issue.title, issue.hint) else issue.hint,
                                fontSize = 10.5.sp, color = P.textDim, lineHeight = 15.sp
                            )
                        }
                        if (issue.loc.isNotEmpty()) Text(issue.loc, fontSize = 9.5.sp, color = P.textDim.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DevicePane(state: StudioState, modifier: Modifier = Modifier) {
    val project = state.repo.current
    // 默认停在「连接」：先连接真机，才有后续的一键上手/工具/输出
    var tab by remember { mutableStateOf("connect") }
    val connected = state.deviceStatusLevel == "ok" || state.deviceInfo != null

    Column(modifier.verticalScroll(rememberScrollState()).padding(10.dp)) {
        // 打开即见全部标签页，不再折叠；按使用逻辑排序：连接 → 一键上手 → 工具 → 真机输出
        SectionTabs(
            tabs = listOf(
                "connect" to "连接",
                "guide" to "一键上手",
                "tools" to "工具",
                "output" to "真机输出"
            ),
            selected = tab,
            onSelect = { tab = it }
        )
        Spacer(Modifier.height(10.dp))

        // 功能依赖连接：先连接 AutoJs6 才有东西可做——所有非「连接」标签都拦截，
        // 一键上手的识别/录制同样依赖真机，因此也纳入拦截。
        if (tab != "connect" && !connected) {
            Surface(
                color = P.panelAlt,
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, P.warn)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "还没有连接真机：先点左侧「连接」页连上 AutoJs6，这里才有内容。",
                        fontSize = 10.5.sp, color = P.warn, modifier = Modifier.weight(1f)
                    )
                    SmallButton("去连接", primary = true) { tab = "connect" }
                }
            }
            Spacer(Modifier.height(10.dp))
            return@Column
        }

        when (tab) {
            "guide" -> {
        // 一键上手：目标 App、按钮、参数全部从真机采集，小白不用手输任何东西
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(P.panelAlt).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("一键上手（不用写代码）", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = P.text)
            Text(
                "先打开要自动化的 App，再回来点「① 识别」——它有 5 秒倒计时，趁这 5 秒切到目标 App 就行；然后点「② 生成脚本」。按钮和参数都从真机自动采集，不用手输。",
                fontSize = 9.5.sp, color = P.textDim
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallButton("① 识别（5秒内切到目标 App）", primary = true) { state.autoDetectForeground() }
                SmallButton("② 生成脚本", primary = true) { state.autoGenerateFlow() }
            }
            if (state.autoStatus.isNotBlank()) {
                Text(state.autoStatus, fontSize = 10.sp, color = P.info)
            }
            if (state.autoPkg.isNotBlank()) {
                Text("目标 App：${state.autoPkgLabel.ifBlank { state.autoPkg }}（${state.autoPkg}）", fontSize = 9.5.sp, color = P.textDim)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallButton("从已装应用选（自动打开并采集）") { state.loadInstalledApps() }
                if (state.appsLoading) Text("读取中…", fontSize = 9.5.sp, color = P.textDim)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallButton("一键自检（连没连上）") { state.autoSelfCheck() }
                SmallButton("自动找本机服务（地址不用填）") { state.autoDiscoverLocalServices() }
            }
            if (state.installedApps.isNotEmpty()) {
                InlineField(
                    state.appQuery,
                    { state.appQuery = it },
                    placeholder = "搜索应用名（不用输包名）",
                    modifier = Modifier.fillMaxWidth()
                )
                val q = state.appQuery.trim().lowercase()
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    state.installedApps
                        .filter { q.isBlank() || it.second.lowercase().contains(q) || it.first.lowercase().contains(q) }
                        .take(12)
                        .forEach { (pkg, label) ->
                            SmallButton(label.ifBlank { pkg }) { state.launchAppAndCapture(pkg, label) }
                        }
                }
            }
            if (state.autoCandidates.isNotEmpty()) {
                Text("识别到的可点按钮（先点选，再生成或追加）：", fontSize = 9.5.sp, color = P.textDim)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    state.autoCandidates.forEach { v ->
                        SmallButton(if (state.autoSelected == v) "✓ $v" else v) { state.selectAutoCandidate(v) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallButton("用选中生成", primary = true) { state.autoGenerateFlow() }
                    SmallButton("生成并试跑") { state.autoGenerateFlow(runAfter = true) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallButton("追加为下一步", primary = true) { state.autoAppendStep() }
                    SmallButton("撤销上一步") { state.undo() }
                    SmallButton("去运行") { state.showPane(cn.dumate.a6studio.ui.Pane.RUN) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallButton("③ 录制我的操作") { state.recorderStart() }
                SmallButton("④ 读取并生成") { state.recorderFetchAndGenerate() }
            }
            Text(
                "③ 点完去操作手机（按音量下键停止），回到本页点 ④，刚才的操作会自动变成积木。",
                fontSize = 9.5.sp, color = P.textDim
            )
            SmallButton("录制失败？从文件导入录制") { state.recorderImport() }
            if (state.recorderState.note.isNotBlank()) {
                Text(state.recorderState.note, fontSize = 10.sp, color = P.info)
            }
            if (state.recorderState.usableCount > 0) {
                Text(
                    "已录到 ${state.recorderState.usableCount} 个操作（待生成 ${state.recorderState.freshCount} 个）",
                    fontSize = 9.5.sp, color = P.ok
                )
            }
        }
            }
            "connect" -> {
            Spacer(Modifier.height(2.dp))
            // ---------- AutoJs6 MCP ----------
            Surface(
                color = P.panelAlt,
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, P.border)
            ) {
                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("AutoJs6 MCP（真机自动化）", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = P.text)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        var expanded by remember { mutableStateOf(false) }
                        androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp))
                                    .background(P.codeBg)
                                    .clickable { expanded = true }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 手机上这块只有 300 多 dp：去掉 http:// 和 /mcp，型号 + IP:端口 才放得下。
                                Text(
                                    project.bridge.devices.firstOrNull { it.url == project.bridge.url }?.let {
                                        val host = it.url.removePrefix("http://").removePrefix("https://").removeSuffix("/mcp")
                                        "${it.name} · $host"
                                    } ?: "已保存设备（${project.bridge.devices.size}）",
                                    fontSize = 11.sp, color = P.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                                )
                                Text("▾", fontSize = 9.sp, color = P.textDim)
                            }
                            androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                project.bridge.devices.forEach { d ->
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("${d.name} · ${d.url}", fontSize = 11.sp, modifier = Modifier.weight(1f))
                                                // ✕ 删除：切走（若为当前设备）再移除，避免下拉里残留无效项。
                                                Text(
                                                    "✕",
                                                    fontSize = 11.sp,
                                                    color = P.err.copy(alpha = 0.8f),
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(3.dp))
                                                        .clickable {
                                                            expanded = false
                                                            state.deleteSavedDevice(d.url)
                                                        }
                                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        },
                                        onClick = { expanded = false; state.switchDevice(d.url) }
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        SmallButton("保存当前") { state.saveCurrentDevice() }
                    }

                    // 导入：插件页「复制配置」→ 这里一键读剪贴板；也能选文件（mcp.txt / .json）或手动粘贴。
                    Text("导入 AutoJs6 MCP 配置", fontSize = 11.sp, color = P.textDim)
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SmallButton("从剪贴板导入", primary = true) { state.importMcpFromClipboard() }
                        if (state.platformRef.supportsFileDialogs) SmallButton("从文件导入") { state.importMcpFromFile() }
                        SmallButton("复制全部配置") { state.exportMcpConfig() }
                    }
                    InlineField(
                        state.mcpImportText, { state.mcpImportText = it },
                        placeholder = "或粘贴到这里：mcpServers JSON / http://IP:9637/mcp 令牌 / 只写 IP，每行一台",
                        mono = true, singleLine = false, minLines = 2, maxLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (state.mcpImportText.isNotBlank()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SmallButton("导入粘贴内容", primary = true) { state.importMcpFromText() }
                            SmallButton("清空") { state.mcpImportText = "" }
                        }
                    }

                    Text("MCP 桥接地址", fontSize = 11.sp, color = P.textDim)
                    InlineField(state.bridgeUrl, { state.updateBridgeUrl(it) }, placeholder = "http://127.0.0.1:8765/mcp", mono = true, modifier = Modifier.fillMaxWidth())
                    Text("从 AutoJs6「插件中心 → MCP Server」复制，或直连 http://手机IP:9637/mcp", fontSize = 9.5.sp, color = P.textDim)

                    Text("令牌（可选，开启下方开关后自动保存在本机）", fontSize = 11.sp, color = P.textDim)
                    var showToken by remember { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        InlineField(
                            state.bridgeToken,
                            { state.updateBridgeToken(it) },
                            placeholder = "Bearer token（没有就留空）",
                            mono = true,
                            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                            modifier = Modifier.weight(1f)
                        )
                        SmallButton(if (showToken) "隐藏" else "显示") { showToken = !showToken }
                    }
                    TinyToggle("记住本机令牌与密钥（写进本机私有存储，不进项目文件）", state.rememberToken) { state.changeRememberToken(it) }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SmallButton("连接并读取设备", primary = true) { state.connectDevice() }
                        SmallButton("Ping") { state.pingDevice() }
                    }

                    // 连接成功 → 设备信息直接显示在连接页（不再单独放一个标签）
                    state.deviceInfo?.let { info ->
                        Spacer(Modifier.height(4.dp))
                        Text("设备信息", fontSize = 11.sp, color = P.textDim)
                        KV("厂商 / 型号", "${info.manufacturer.ifBlank { "-" }} / ${info.model.ifBlank { "-" }}")
                        KV("Android", "${info.androidRelease.ifBlank { "-" }} (API ${info.androidApi.ifBlank { "-" }})")
                        KV("无障碍服务", if (info.accessibilityReady) "已开启（可以找控件）" else "未开启（找不到控件）")
                        if (info.batteryPercent.isNotBlank()) KV("电量", "${info.batteryPercent}%")
                        KV("AutoJs6", "${info.versionName.ifBlank { "-" }} (${info.versionCode.ifBlank { "-" }})")
                        KV("MCP 插件", "${info.plugin.ifBlank { "-" }} / ${info.pluginId.ifBlank { "-" }}")
                        KV("进程", info.process.ifBlank { "-" })
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            // ---------- ProxyPin MCP ----------
            Surface(
                color = P.panelAlt,
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, P.border)
            ) {
                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("ProxyPin MCP（抓包流量）", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = P.text)
                    // 已保存的 ProxyPin 设备：切换 / 删除（与 AutoJs6 设备同一套交互）
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        var ppExpanded by remember { mutableStateOf(false) }
                        androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp))
                                    .background(P.codeBg)
                                    .clickable { ppExpanded = true }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    project.bridge.proxyPinDevices.firstOrNull { it.url == state.proxyPinUrl }?.let {
                                        val host = it.url.removePrefix("http://").removePrefix("https://").removeSuffix("/mcp")
                                        "${it.name} · $host"
                                    } ?: "已保存 ProxyPin 设备（${project.bridge.proxyPinDevices.size}）",
                                    fontSize = 11.sp, color = P.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                                )
                                Text("▾", fontSize = 9.sp, color = P.textDim)
                            }
                            androidx.compose.material3.DropdownMenu(expanded = ppExpanded, onDismissRequest = { ppExpanded = false }) {
                                project.bridge.proxyPinDevices.forEach { d ->
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("${d.name} · ${d.url}", fontSize = 11.sp, modifier = Modifier.weight(1f))
                                                Text(
                                                    "✕",
                                                    fontSize = 11.sp,
                                                    color = P.err.copy(alpha = 0.8f),
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(3.dp))
                                                        .clickable {
                                                            ppExpanded = false
                                                            state.deleteSavedProxyPinDevice(d.url)
                                                        }
                                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        },
                                        onClick = { ppExpanded = false; state.switchProxyPinDevice(d.url) }
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        SmallButton("保存当前") { state.saveCurrentProxyPinDevice() }
                    }
                    InlineField(
                        state.proxyPinUrl, { state.updateProxyPinUrl(it) },
                        placeholder = "ProxyPin MCP 地址，例如 http://127.0.0.1:9000/mcp",
                        mono = true, modifier = Modifier.fillMaxWidth()
                    )
                    var showProxyToken by remember { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        InlineField(
                            state.proxyPinToken,
                            { state.updateProxyPinToken(it) },
                            placeholder = "ProxyPin 鉴权 Token（不写入项目文件）",
                            mono = true,
                            visualTransformation = if (showProxyToken) VisualTransformation.None else PasswordVisualTransformation(),
                            modifier = Modifier.weight(1f)
                        )
                        SmallButton(if (showProxyToken) "隐藏" else "显示") { showProxyToken = !showProxyToken }
                    }
                    Text("导入 ProxyPin 配置（mcpServers JSON / URL / IP）", fontSize = 11.sp, color = P.textDim)
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SmallButton("从剪贴板导入", primary = true) { state.importProxyPinFromClipboard() }
                        if (state.platformRef.supportsFileDialogs) SmallButton("从文件导入") { state.importProxyPinFromFile() }
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
                    Text("抓包域名过滤（可选，如 ms.jr.jd.com）", fontSize = 11.sp, color = P.textDim)
                    InlineField(
                        state.proxyPinDomainFilter, { state.updateProxyPinDomainFilter(it) },
                        placeholder = "留空抓全部域名",
                        mono = true, modifier = Modifier.fillMaxWidth()
                    )
                    if (state.trafficDomains.isNotEmpty()) {
                        Text("抓到的域名（点一个自动填入过滤）：", fontSize = 9.5.sp, color = P.textDim)
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            state.trafficDomains.forEach { d ->
                                SmallButton(d) { state.updateProxyPinDomainFilter(d) }
                            }
                        }
                    }
                }
            }
            }
                "tools" -> {
                    Spacer(Modifier.height(2.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        SmallButton(if (state.deviceToolRunning) "加载中…" else "工具清单", primary = state.deviceToolNames.isEmpty()) { state.listDeviceTools() }
                        if (state.deviceToolNames.isNotEmpty()) Text("已加载 ${state.deviceToolNames.size} 个工具", fontSize = 9.5.sp, color = P.ok)
                    }
                    if (state.deviceToolsText.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(state.deviceToolsText, fontSize = 9.5.sp, color = P.textDim)
                    }
                    if (state.deviceToolNames.isEmpty()) {
                        Text("还没有工具列表：先点上面的「工具清单」。", fontSize = 10.sp, color = P.textDim)
                    }
                    if (state.deviceToolNames.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("直接调用工具", fontSize = 11.sp, color = P.textDim)
                        Text("从下拉里挑一个可用工具，参数也按下拉/输入框填好，点执行；结果会脱敏后显示在下面。", fontSize = 9.5.sp, color = P.textDim)
                        Spacer(Modifier.height(4.dp))
            DropdownField(
                value = state.deviceToolNameDraft,
                onSelect = { state.selectDeviceTool(it) },
                items = state.deviceToolNames,
                placeholder = "选择工具（先点「工具清单」读取）",
                modifier = Modifier.fillMaxWidth(),
                searchable = true,
                searchPlaceholder = "搜索工具名…",
                groupOf = { tool -> toolGroupOf(tool) },
                groupLabelOf = { group -> toolGroupLabel(group) }
            )
            state.deviceToolDescriptions[state.deviceToolNameDraft]?.takeIf { it.isNotBlank() }?.let {
                Text("说明：$it", fontSize = 9.5.sp, color = P.info)
            }
            Spacer(Modifier.height(4.dp))
            val params = state.toolParamsFor(state.deviceToolNameDraft)
            if (params.isNotEmpty()) {
                Text("参数（点 ▾ 选预置值，也可手输）：", fontSize = 9.5.sp, color = P.textDim)
                params.forEach { p ->
                    val cur = state.deviceToolParamValues[p.name].orEmpty()
                    Text(
                        p.name + (if (p.required) " *" else "") + (if (p.type != "string") " · ${p.type}" else ""),
                        fontSize = 10.sp, color = P.text
                    )
                    EditableDropdownField(
                        value = cur,
                        onValueChange = { v ->
                            state.setToolParam(p.name, v)
                            state.rememberToolParamValue(state.deviceToolNameDraft, p, v)
                        },
                        items = state.toolParamPresets(state.deviceToolNameDraft, p),
                        placeholder = p.desc.ifBlank { "可留空" },
                        mono = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (p.desc.isNotBlank()) Text(p.desc, fontSize = 9.sp, color = P.textDim)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallButton(if (state.deviceToolRunning) "执行中…" else "执行工具", primary = true) {
                        if (!state.deviceToolRunning) state.runDeviceToolFromForm(state.deviceToolNameDraft)
                    }
                    SmallButton("清空参数") { params.forEach { state.setToolParam(it.name, "") } }
                }
            } else {
                InlineField(
                    state.deviceToolArgsDraft,
                    { state.deviceToolArgsDraft = it },
                    placeholder = "参数 JSON，例如 {\"lines\": 20}",
                    mono = true,
                    singleLine = false,
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallButton(if (state.deviceToolRunning) "执行中…" else "执行工具", primary = true) {
                        if (!state.deviceToolRunning) state.runDeviceTool(state.deviceToolNameDraft, state.deviceToolArgsDraft)
                    }
                    SmallButton("参数清空 {}") { state.deviceToolArgsDraft = "{}" }
                }
            }
            if (state.deviceToolResult.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    state.deviceToolResult,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = P.text,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp)).background(P.codeBg).padding(6.dp)
                )
            }
        }
            }

        "output" -> {
            Spacer(Modifier.height(2.dp))
            // 界面树：读当前界面控件文本 → 输出到下方日志区
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallButton("界面树", primary = true) { state.dumpDeviceUi() }
                Text("读取当前界面的控件树，结果显示在下方", fontSize = 9.5.sp, color = P.textDim)
            }
            Spacer(Modifier.height(8.dp))
            if (state.deviceLog.isEmpty()) {
                Text("连接并点「界面树」后，这里显示真机返回的内容", fontSize = 9.5.sp, color = P.textDim)
            } else {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp)).background(P.codeBg).padding(6.dp)) {
                    state.deviceLog.forEach { l ->
                        Text(
                            l.msg,
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = when (l.level) {
                                "error" -> P.err
                                "warn" -> P.warn
                                "ok" -> P.ok
                                "debug" -> P.textDim
                                else -> P.text
                            }
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
fun HelpPane(modifier: Modifier = Modifier, beginner: Boolean = false, onGuide: () -> Unit = {}, onTemplates: () -> Unit = {}) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
        if (beginner) {
            HelpSection("三步做出第一个脚本")
            HelpItem("1. 点顶栏「⋯」→「模板库」，选一个最接近你想法的现成脚本。")
            HelpItem("2. 点蓝色「▶ 运行」，在内置模拟手机里看效果——不会碰你真实的手机。")
            HelpItem("3. 想改成你自己的：点中间的积木，把上面的文字改成你屏幕上真实看到的字。")

            HelpSection("积木怎么用")
            HelpItem("左边的积木 = 一个个动作。点一下就会加到中间。")
            HelpItem("「点击控件 立即领取」= 找到屏幕上写着「立即领取」的按钮并点它。")
            HelpItem("「等待 1000 毫秒」= 停 1 秒，等页面加载完。")
            HelpItem("「重复 10 次」= 把里面的动作做 10 遍。")

            HelpSection("找不到要点的按钮？")
            HelpItem("先截图看一眼屏幕上真实写着什么，字要一模一样（连空格都算）。")
            HelpItem("连着手机时，点积木上的 ⌖ 可以直接从屏幕上点选，不用自己打。")
            HelpItem("实在找不到，把「点击控件」换成「点击坐标」，在截图上传坐标（换手机会失效）。")

            HelpSection("跑不通怎么办")
            HelpItem("点「⋯」→「检查问题」，它会用人话告诉你哪里可能不行。")
            HelpItem("看下面的日志：最后一行就是停下来的地方。")
            HelpItem("常见的三种：字写错了、页面还没加载完就点了、按钮在下一屏要往下滑。")

            HelpSection("想上真手机跑")
            HelpItem("先在模拟器里跑通，再考虑真机——真机上出错更难排查。")
            HelpItem("真机需要在手机装 AutoJs6 并打开「插件中心 → MCP Server」。")

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("重看 2 分钟指南") { onGuide() }
                SmallButton("打开模板库") { onTemplates() }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "想看到代码、连接真机、录制操作？点顶栏「⋯」→「切换到完整模式」。",
                fontSize = 10.5.sp, color = P.textDim
            )
            return@Column
        }

        HelpSection("怎么用")
        HelpItem("1. 左侧点积木追加到画布；点积木卡片选中，右侧实时出代码并高亮对应行。")
        HelpItem("2. 点「运行」在内置模拟设备上执行生成的脚本；勾选「发送到真机」则调用 AutoJs6。")
        HelpItem("3. 「⋯」→「跑一遍测试」跑用例；「诊断」查静态风险与运行日志问题。")
        HelpItem("4. 「我的模块」把一组积木固化为可复用函数。")
        HelpItem("5. 积木参数旁的 ⌖ 从真机界面树点选控件，验唯一后写回选择器或坐标。")
        HelpItem("6. 「⋯」→「选择器体检」(F8) 逐个校验项目里所有选择器；「录制操作」(F9) 把手机操作转成积木。")

        HelpSection("快捷键")
        HelpItem("F5 运行 · Shift+F5 停止 · F6 测试 · F7 诊断 · F8 体检 · F9 录制")
        HelpItem("Ctrl+S 保存 · Ctrl+Z 撤销 · Ctrl+E 导出 JS · Ctrl+K 搜索积木")
        HelpItem("Delete 删除选中积木 · Esc 关闭面板/取消选中")

        HelpSection("边界")
        HelpItem("内置模拟器用 Rhino 执行生成的脚本（与 AutoJs6 同引擎），覆盖常用 API 的行为近似；真机差异（H5 渲染时机、无障碍树刷新、厂商弹窗）只能在真机上验证。")
        HelpItem("真机调用依赖 AutoJs6 MCP 插件与网络可达，失败时保留模拟运行能力。")
        HelpItem("生成的是标准 AutoJs6（Rhino）脚本，可直接导入 AutoJs6 运行。")

        HelpSection("真机录制的限制（实测）")
        HelpItem("一旦脚本常驻触摸监听，同进程的 MCP Server 就不再应答，所以录制期不通信：操作完在手机上停止脚本，再点「读取并生成」。")
        HelpItem("部分机型系统不把触摸事件分发给无障碍服务，录不到内容；此时改用 ⌖ 拾取器或「导入文件」。")
    }
}

@Composable
private fun HelpSection(title: String) {
    Text(title, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = P.text, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
}

@Composable
private fun HelpItem(text: String) {
    Text("· $text", fontSize = 11.sp, color = P.textDim, modifier = Modifier.padding(vertical = 1.5.dp))
}

@Composable
fun SmallButton(label: String, primary: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 10.5.sp,
        color = if (primary) P.onAccent else P.text,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (primary) P.accent else P.panelAlt)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

/**
 * 把真机 MCP 工具名按第一个下划线之前的前缀分组（app_launch → "app"、script_run → "script"）。
 * 不带下划线的工具（如 device_info、ui_dump、screen_capture 这类单段名）归到 null，
 * 在下拉里显示在「其他」组之后且不带头。
 */
private fun toolGroupOf(tool: String): String? {
    val idx = tool.indexOf('_')
    return if (idx > 0) tool.substring(0, idx) else null
}

/** 分组标题的人话：script → 脚本、app → 应用、ui → UI、files → 文件、device → 设备、screen → 截屏。 */
private fun toolGroupLabel(group: String): String = when (group) {
    "script" -> "脚本"
    "app" -> "应用"
    "ui" -> "UI / 无障碍"
    "files" -> "文件"
    "device" -> "设备"
    "screen" -> "截屏"
    "shell" -> "Shell"
    "console" -> "控制台"
    "clipboard" -> "剪贴板"
    "http" -> "网络"
    "image" -> "图像"
    else -> group
}