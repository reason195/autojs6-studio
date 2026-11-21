package cn.dumate.a6studio.ui.panes

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
            items(count = project.tests.size) { i ->
                val t = project.tests[i]
                var name by remember(t.id) { mutableStateOf(t.name) }
                var code by remember(t.id) { mutableStateOf(t.code) }
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
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            "✕", fontSize = 11.sp, color = P.err.copy(alpha = 0.8f),
                            modifier = Modifier.clickable { state.repo.removeTest(t.id); state.commit() }.padding(3.dp)
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
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(5.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SmallButton("运行此用例") { state.runTests(listOf(t.id)) }
                        Spacer(Modifier.width(8.dp))
                        Text("可用：\$setScreen / \$state / \$run / \$log / assert", fontSize = 9.5.sp, color = P.textDim)
                        Spacer(Modifier.weight(1f))
                        result?.let {
                            Badge(if (it.ok) "通过" else "失败", if (it.ok) BadgeKind.OK else BadgeKind.ERR)
                        }
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
            items(count = issues.size) { i ->
                val issue = issues[i]
                val color = when (issue.level) {
                    Level.ERROR -> P.err
                    Level.WARN -> P.warn
                    Level.INFO -> P.info
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(color.copy(alpha = 0.07f))
                        .clickable {
                            issue.blockId?.let { state.repo.select(it) }
                        }
                        .padding(7.dp)
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

@Composable
fun DevicePane(state: StudioState, modifier: Modifier = Modifier) {
    val project = state.repo.current
    Column(modifier.verticalScroll(rememberScrollState()).padding(10.dp)) {
        Text("设备", fontSize = 11.sp, color = P.textDim)
        Spacer(Modifier.height(4.dp))
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
                    Text(
                        project.bridge.devices.firstOrNull { it.url == project.bridge.url }?.let { "${it.name} · ${it.url.removePrefix("http://")}" }
                            ?: "— 已保存设备（${project.bridge.devices.size}）—",
                        fontSize = 11.sp, color = P.text, maxLines = 1, modifier = Modifier.weight(1f)
                    )
                    Text("▾", fontSize = 9.sp, color = P.textDim)
                }
                androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    project.bridge.devices.forEach { d ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("${d.name} · ${d.url}", fontSize = 11.sp) },
                            onClick = { expanded = false; state.switchDevice(d.url) }
                        )
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            SmallButton("保存当前") { state.saveCurrentDevice() }
        }
        Text("保存多台设备后在这里切换；地址写进项目，令牌默认只在内存。", fontSize = 9.5.sp, color = P.textDim, modifier = Modifier.padding(top = 3.dp))

        Spacer(Modifier.height(10.dp))
        Text("MCP 桥接地址", fontSize = 11.sp, color = P.textDim)
        Spacer(Modifier.height(4.dp))
        InlineField(state.bridgeUrl, { state.updateBridgeUrl(it) }, placeholder = "http://127.0.0.1:8765/mcp", mono = true, modifier = Modifier.fillMaxWidth())
        Text("AutoJs6 插件中心开启「MCP Server」，或填真机直连地址 http://<手机IP>:9637/mcp。", fontSize = 9.5.sp, color = P.textDim, modifier = Modifier.padding(top = 3.dp))

        Spacer(Modifier.height(10.dp))
        Text("令牌（可选，默认只存内存）", fontSize = 11.sp, color = P.textDim)
        Spacer(Modifier.height(4.dp))
        InlineField(state.bridgeToken, { state.updateBridgeToken(it) }, placeholder = "粘贴 mcpServers 里的 Bearer token", mono = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        TinyToggle("记住令牌（写进本机存储，公用电脑别勾）", state.rememberToken) { state.changeRememberToken(it) }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallButton("连接并读取设备", primary = true) { state.connectDevice() }
            SmallButton("Ping") { state.pingDevice() }
            SmallButton("界面树") { state.dumpDeviceUi() }
        }

        state.deviceInfo?.let { info ->
            Spacer(Modifier.height(10.dp))
            KV("厂商 / 型号", "${info.manufacturer.ifBlank { "-" }} / ${info.model.ifBlank { "-" }}")
            KV("Android", "${info.androidRelease.ifBlank { "-" }} (API ${info.androidApi.ifBlank { "-" }})")
            KV("无障碍服务", if (info.accessibilityReady) "已开启（可以找控件）" else "未开启（找不到控件）")
            if (info.batteryPercent.isNotBlank()) KV("电量", "${info.batteryPercent}%")
            KV("AutoJs6", "${info.versionName.ifBlank { "-" }} (${info.versionCode.ifBlank { "-" }})")
            KV("MCP 插件", "${info.plugin.ifBlank { "-" }} / ${info.pluginId.ifBlank { "-" }}")
            KV("进程", info.process.ifBlank { "-" })
        }

        Spacer(Modifier.height(10.dp))
        Text("真机输出", fontSize = 11.sp, color = P.textDim)
        Column(Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(5.dp)).background(P.codeBg).padding(6.dp)) {
            if (state.deviceLog.isEmpty()) EmptyHint("暂无输出")
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

@Composable
fun HelpPane(modifier: Modifier = Modifier, beginner: Boolean = false, onGuide: () -> Unit = {}, onTemplates: () -> Unit = {}) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
        if (beginner) {
            HelpSection("三步做出第一个脚本")
            HelpItem("1. 点顶栏「📋 模板」，选一个最接近你想法的现成脚本。")
            HelpItem("2. 点「▶ 运行」，在内置模拟手机里看效果——不会碰你真实的手机。")
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
            HelpItem("点「检查问题」，它会用人话告诉你哪里可能不行。")
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
                "想看到代码、连接真机、录制操作？点顶栏「高级」切换到完整模式。",
                fontSize = 10.5.sp, color = P.textDim
            )
            return@Column
        }

        HelpSection("怎么用")
        HelpItem("1. 左侧点积木追加到画布；点积木卡片选中，右侧实时出代码并高亮对应行。")
        HelpItem("2. 点「运行」在内置模拟设备上执行生成的脚本；勾选「发送到真机」则调用 AutoJs6。")
        HelpItem("3. 点「测试」跑用例；点「诊断」查静态风险与运行日志问题。")
        HelpItem("4. 「我的模块」把一组积木固化为可复用函数。")
        HelpItem("5. 积木参数旁的 ⌖ 从真机界面树点选控件，验唯一后写回选择器或坐标。")
        HelpItem("6. 「⌕ 体检」(F8) 逐个校验项目里所有选择器；「● 录制」(F9) 把手机操作转成积木。")

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
    val color = if (primary) P.accent else P.textDim
    Text(
        label,
        fontSize = 10.5.sp,
        color = if (primary) androidx.compose.ui.graphics.Color.White else P.text,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (primary) color.copy(alpha = 0.25f) else P.panelAlt)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}