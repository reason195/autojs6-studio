package cn.dumate.a6studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.dumate.a6studio.model.Scope
import cn.dumate.a6studio.platform.systemBarPadding
import cn.dumate.a6studio.ui.canvas.CanvasPanel
import cn.dumate.a6studio.ui.components.Badge
import cn.dumate.a6studio.ui.components.badgeKindOf
import cn.dumate.a6studio.ui.fields.InlineField
import cn.dumate.a6studio.ui.palette.PalettePanel
import cn.dumate.a6studio.ui.panes.CodePane
import cn.dumate.a6studio.ui.panes.DevicePane
import cn.dumate.a6studio.ui.panes.DiagPane
import cn.dumate.a6studio.ui.panes.HelpPane
import cn.dumate.a6studio.ui.panes.RunPane
import cn.dumate.a6studio.ui.panes.SmallButton
import cn.dumate.a6studio.ui.panes.TestPane

@Composable
fun StudioApp(state: StudioState) {
    val project = state.repo.current
    val selected = state.repo.selectedId
    val scope = state.repo.currentScope

    Column(
        Modifier.fillMaxSize().background(Palette.bg).systemBarPadding().studioShortcuts(state)
    ) {
        TopBar(state)

            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth >= 900.dp
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    PaletteColumn(state, Modifier.width(228.dp).fillMaxHeight())
                    CanvasColumn(state, Modifier.weight(1f).fillMaxHeight())
                    PaneColumn(state, Modifier.width(420.dp).fillMaxHeight())
                }
            } else {
                // Phone layout: the canvas is the primary view; palette and panes become
                // switchable sheets so nothing gets squeezed off-screen.
                Column(Modifier.fillMaxSize()) {
                    PhoneyTabs(state)
                    Box(Modifier.weight(1f)) {
                        when (state.mobileSheet) {
                            MobileSheet.CANVAS -> CanvasColumn(state, Modifier.fillMaxSize())
                            MobileSheet.PALETTE -> PaletteColumn(state, Modifier.fillMaxSize())
                            MobileSheet.PANE -> PaneColumn(state, Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }

    // ---- overlays ----
    when (state.overlay) {
        Overlay.PICKER -> PickerOverlay(state)
        Overlay.AUDIT -> AuditOverlay(state)
        Overlay.RECORDER -> RecorderOverlay(state)
        Overlay.TEMPLATES -> TemplateOverlay(state)
        Overlay.GUIDE -> Unit
        Overlay.NONE -> Unit
    }

    // These two are driven by their own flags so they can be reopened without going through
    // the tool overlay slot (the guide appears over everything on first run).
    if (state.showTemplatePicker) TemplateOverlay(state)
    if (state.showGuide) GuideOverlay(state)

    ToastHost(state)
}

@Composable
private fun TopBar(state: StudioState) {
    val project = state.repo.current
    var name by remember(project.name) { mutableStateOf(project.name) }

    BoxWithConstraints(
        Modifier.fillMaxWidth()
            .background(Palette.panel)
            .border(width = 1.dp, color = Palette.borderSoft)
    ) {
        if (maxWidth < 820.dp) {
            // Phone: brand + project name on row 1, actions on a scrollable row 2. Cramming
            // everything onto one line made the name field overlap the title.
            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BrandMark()
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("AutoJs6 Studio", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
                        Text("可视化 · 模块化 · 运行 / 测试 / 诊断", fontSize = 8.5.sp, color = Palette.textDim)
                    }
                    Spacer(Modifier.width(6.dp))
                    InlineField(
                        value = name,
                        onValueChange = { name = it; state.renameProject(it) },
                        placeholder = "项目名称",
                        modifier = Modifier.widthIn(min = 110.dp, max = 150.dp)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TopActions(state)
                        Spacer(Modifier.width(8.dp))
                    }
                    // Pinned outside the scroller: on a 360dp phone the mode switch was the last
                    // item in the scrolled row and ended up off-screen.
                    ModeSwitch(state)
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BrandMark()
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("AutoJs6 Studio", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
                    Text("可视化 · 模块化 · 运行 / 测试 / 诊断", fontSize = 9.sp, color = Palette.textDim)
                }
                Spacer(Modifier.width(14.dp))
                InlineField(
                    value = name,
                    onValueChange = { name = it; state.renameProject(it) },
                    placeholder = "项目名称",
                    modifier = Modifier.width(180.dp)
                )
                Spacer(Modifier.width(10.dp))
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TopActions(state)
                }
                Spacer(Modifier.width(8.dp))
                Badge(state.deviceStatusText, badgeKindOf(state.deviceStatusLevel))
            }
        }
    }
}

@Composable
private fun BrandMark() {
    Box(
        Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)).background(Palette.accent),
        contentAlignment = Alignment.Center
    ) { Text("A6", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White) }
}

@Composable
private fun TopActions(state: StudioState) {
    if (state.running) TopAction("■ 停止", primary = true) { state.stopRun() }
    else TopAction("▶ 运行", primary = true) { state.run() }
    TopAction("📋 模板") { state.openTemplates() }
    TopAction("? 怎么做") { state.showGuide = true }
    if (state.beginnerMode) {
        TopAction("检查问题") { state.showPane(Pane.DIAG) }
        TopAction("↶ 撤销") { state.undo() }
        TopAction("保存") { state.saveNow() }
    } else {
        TopAction("✓ 测试") { state.runTests() }
        TopAction("⚕ 诊断") { state.showPane(Pane.DIAG) }
        TopAction("⌕ 体检") { state.openAudit() }
        TopAction("● 录制") { state.openRecorder() }
        TopAction("↶") { state.undo() }
        TopAction("保存") { state.saveNow() }
        TopAction("导出 JS") { state.exportJs() }
        TopAction("导出项目") { state.exportProject() }
        TopAction("导入") { state.importScript() }
    TopAction("导入项目") { state.importProject() }
        TopAction("示例") { state.loadExample() }
        TopAction("新建") { state.newProject() }
    }
}

/** Always-visible switch between the beginner and full toolbars. */
@Composable
private fun ModeSwitch(state: StudioState) {
    val toFull = state.beginnerMode
    Text(
        if (toFull) "高级" else "简单",
        fontSize = 11.sp,
        color = if (toFull) Palette.accent else Palette.ok,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(Palette.panelAlt)
            .border(1.dp, if (toFull) Palette.accent.copy(alpha = 0.5f) else Palette.border, RoundedCornerShape(5.dp))
            .clickable { state.changeBeginnerMode(!toFull) }
            .padding(horizontal = 9.dp, vertical = 5.dp)
    )
}

@Composable
private fun TopAction(label: String, primary: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 11.sp,
        color = if (primary) Color.White else Palette.text,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(if (primary) Palette.accent.copy(alpha = 0.85f) else Palette.panelAlt)
            .border(1.dp, if (primary) Color.Transparent else Palette.border, RoundedCornerShape(5.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp)
    )
}

@Composable
private fun ScopeTabs(state: StudioState) {
    val project = state.repo.current
    val scope = state.repo.currentScope
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        ScopeTab(
            label = "主程序",
            active = scope is Scope.Root,
            onClick = {
                state.repo.setScope(Scope.Root)
                state.setHint("")
            }
        )
        project.modules.forEach { m ->
            val active = scope is Scope.Module && scope.id == m.id
            ScopeTab(
                label = m.name.ifBlank { "未命名模块" },
                active = active,
                onClick = { state.repo.setScope(Scope.Module(m.id)) },
                onDelete = {
                    state.repo.removeModule(m.id)
                    state.commit()
                    state.toast("已删除模块：${m.name}")
                }
            )
        }
    }
}

@Composable
private fun ScopeTab(label: String, active: Boolean, onClick: () -> Unit, onDelete: (() -> Unit)? = null) {
    Row(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (active) Palette.accent.copy(alpha = 0.75f) else Palette.panelAlt)
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = if (onDelete != null && active) 3.dp else 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 10.5.sp,
            color = if (active) Color.White else Palette.textDim
        )
        if (onDelete != null && active) {
            Text(
                "✕",
                fontSize = 10.sp,
                color = Color.White.copy(alpha = 0.75f),
                modifier = Modifier
                    .clickable(onClick = onDelete)
                    .padding(start = 5.dp, end = 2.dp)
            )
        }
    }
}

@Composable
private fun PaneTabs(state: StudioState) {
    // Beginners get three panes with plain names; experts get the full toolbar.
    val tabs = if (state.beginnerMode) {
        listOf(
            Pane.RUN to "运行",
            Pane.DIAG to "检查问题",
            Pane.HELP to "帮助"
        )
    } else {
        listOf(
            Pane.CODE to "代码",
            Pane.RUN to "运行",
            Pane.TEST to "测试",
            Pane.DIAG to "诊断",
            Pane.DEVICE to "真机",
            Pane.HELP to "帮助"
        )
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        tabs.forEach { (pane, label) ->
            val active = state.pane == pane
            Text(
                label,
                fontSize = 10.5.sp,
                color = if (active) Palette.text else Palette.textDim,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (active) Palette.panelAlt else Color.Transparent)
                    .border(if (active) 1.dp else 0.dp, Palette.border, RoundedCornerShape(4.dp))
                    .clickable { state.showPane(pane) }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
fun ToastHost(state: StudioState) {
    val msg = state.toastText.value ?: return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Text(
            msg,
            fontSize = 11.5.sp,
            color = Palette.text,
            modifier = Modifier.padding(bottom = 40.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Palette.panelAlt)
                .border(1.dp, Palette.border, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun PaletteColumn(state: StudioState, modifier: Modifier) {
    val project = state.repo.current
    Column(modifier.border(width = 1.dp, color = Palette.borderSoft).background(Palette.panel)) {
        PalettePanel(onAdd = { state.addBlock(it) }, modifier = Modifier.weight(1f), beginner = state.beginnerMode)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("${cn.dumate.a6studio.model.TreeOps.count(project.root.children["body"].orEmpty())} 块", fontSize = 10.sp, color = Palette.textDim)
            Text("${project.modules.size} 模块", fontSize = 10.sp, color = Palette.textDim)
            Text("${project.tests.size} 测试", fontSize = 10.sp, color = Palette.textDim)
        }
    }
}

@Composable
private fun CanvasColumn(state: StudioState, modifier: Modifier) {
    val project = state.repo.current
    val selected = state.repo.selectedId
    Column(modifier.background(Palette.bg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ScopeTabs(state)
            }
            Spacer(Modifier.weight(1f))
            if (state.statusHint.isNotEmpty()) {
                Text(state.statusHint, fontSize = 10.sp, color = Palette.textDim, maxLines = 1)
                Spacer(Modifier.width(8.dp))
            }
            SmallButton("+ 模块") {
                state.repo.addModule(state.repo.nextModuleName())
                state.commit()
            }
        }
        Box(Modifier.weight(1f)) {
            CanvasPanel(
                repo = state.repo,
                project = project,
                selectedId = selected,
                onAdd = { state.addBlock(it) },
                onPick = { blockId, key, kind -> state.openPicker(blockId, key, kind) },
                onCommit = { state.commit() },
                modifier = Modifier.fillMaxSize(),
                beginner = state.beginnerMode,
                onTemplates = { state.openTemplates() },
                onShowPalette = { state.mobileSheet = MobileSheet.PALETTE },
                onShowGuide = { state.showGuide = true }
            )
        }
    }
}

@Composable
private fun PaneColumn(state: StudioState, modifier: Modifier) {
    Column(modifier.border(width = 1.dp, color = Palette.borderSoft).background(Palette.panel)) {
        PaneTabs(state)
        Box(Modifier.weight(1f)) {
            when (state.pane) {
                Pane.CODE -> CodePane(state, Modifier.fillMaxSize())
                Pane.RUN -> RunPane(state, Modifier.fillMaxSize(), beginner = state.beginnerMode)
                Pane.TEST -> TestPane(state, Modifier.fillMaxSize())
                Pane.DIAG -> DiagPane(state, Modifier.fillMaxSize(), beginner = state.beginnerMode)
                Pane.DEVICE -> DevicePane(state, Modifier.fillMaxSize())
                Pane.HELP -> HelpPane(
                    Modifier.fillMaxSize(),
                    beginner = state.beginnerMode,
                    onGuide = { state.showGuide = true },
                    onTemplates = { state.openTemplates() }
                )
            }
        }
    }
}

@Composable
private fun PhoneyTabs(state: StudioState) {
    Row(Modifier.fillMaxWidth().background(Palette.panel).padding(horizontal = 8.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(
            MobileSheet.CANVAS to "画布",
            MobileSheet.PALETTE to "积木",
            MobileSheet.PANE to "面板"
        ).forEach { (sheet, label) ->
            val active = state.mobileSheet == sheet
            Text(
                label,
                fontSize = 11.sp,
                color = if (active) Color.White else Palette.textDim,
                modifier = Modifier.clip(RoundedCornerShape(4.dp))
                    .background(if (active) Palette.accent.copy(alpha = 0.7f) else Palette.panelAlt)
                    .clickable { state.mobileSheet = sheet }
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}
