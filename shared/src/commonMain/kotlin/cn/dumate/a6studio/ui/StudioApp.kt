package cn.dumate.a6studio.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import cn.dumate.a6studio.ui.fields.LocalTextFocusTracker
import cn.dumate.a6studio.ui.fields.trackTextFocus
import cn.dumate.a6studio.ui.palette.PalettePanel
import cn.dumate.a6studio.ui.currentStudioStyle
import cn.dumate.a6studio.ui.panes.CodePane
import cn.dumate.a6studio.ui.panes.DevicePane
import cn.dumate.a6studio.ui.panes.DiagPane
import cn.dumate.a6studio.ui.panes.HelpPane
import cn.dumate.a6studio.ui.panes.RunPane
import cn.dumate.a6studio.ui.panes.SmallButton
import cn.dumate.a6studio.ui.panes.TestPane

@Composable
fun StudioApp(state: StudioState) {
    CompositionLocalProvider(LocalTextFocusTracker provides state.textFocusTracker) {
        StudioAppContent(state)
    }
}

@Composable
private fun StudioAppContent(state: StudioState) {
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
                    PaletteColumn(state, Modifier.width(228.dp).fillMaxHeight(), wideLayout = true)
                    CanvasColumn(state, Modifier.weight(1f).fillMaxHeight(), wideLayout = true)
                    PaneColumn(state, Modifier.width(420.dp).fillMaxHeight())
                }
            } else {
                // Phone layout: the canvas is the primary view; palette and panes become
                // switchable sheets so nothing gets squeezed off-screen.
                Column(Modifier.fillMaxSize()) {
                    PhoneyTabs(state)
                    if (state.mobileSheet == MobileSheet.PANE) {
                        Box(Modifier.weight(1f)) {
                            PaneColumn(state, Modifier.fillMaxSize())
                        }
                    } else {
                        // 手机：画布 + 积木 上下分栏，同时可见，不再来回切
                        Column(Modifier.weight(1f)) {
                            CanvasColumn(state, Modifier.weight(0.55f).fillMaxWidth())
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.border))
                            PaletteColumn(state, Modifier.weight(0.45f).fillMaxWidth())
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

    DragGhost(state)
    ToastHost(state)
}

/** Chip that follows the pointer while a palette block is dragged onto the canvas. */
@Composable
private fun DragGhost(state: StudioState) {
    val drag = state.paletteDrag
    val type = drag.draggingType ?: return
    val def = cn.dumate.a6studio.catalog.Catalog.byType(type) ?: return
    val density = androidx.compose.ui.platform.LocalDensity.current
    val p = drag.pointer
    val ok = drag.target != null
    Text(
        cn.dumate.a6studio.catalog.Catalog.cleanName(def) + if (ok) "" else "  （拖到画布上）",
        fontSize = 11.5.sp,
        color = if (ok) Palette.onAccent else Palette.text,
        modifier = Modifier
            .offset(with(density) { (p.x + 12f).toDp() }, with(density) { (p.y + 8f).toDp() })
            .clip(RoundedCornerShape(7.dp))
            .background(if (ok) Palette.accent else Palette.panelAlt)
            .border(1.dp, if (ok) Palette.accent else Palette.border, RoundedCornerShape(7.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
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
        // 一屏只有一个主按钮。以前这里塞了 15 个同等权重的按钮，宽屏要横向滚动、窄屏要两行，
        // 用户每次都得在按钮堆里找目标；其余动作全收进「更多」菜单（键盘快捷键不变）。
        if (maxWidth < 820.dp) {
            // 窄屏只留一行：标识（右下角小圆点表示真机状态）、项目名、撤销、更多、运行。
            // 所有控件统一 36dp 高；撤销/更多是无边框图标按钮，不再是一排描边方块。
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(start = 10.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    BrandMark()
                    StatusDot(state.deviceStatusLevel, Modifier.align(Alignment.BottomEnd))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    PlainNameField(name) { name = it; state.renameProject(it) }
                    Text(state.deviceStatusText, fontSize = 10.sp, color = Palette.textDim, maxLines = 1)
                }
                QuietTextAction("撤销") { state.undo() }
                MoreMenu(state)
                Spacer(Modifier.width(6.dp))
                RunButton(state)
            }
        } else {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BrandMark()
                Spacer(Modifier.width(9.dp))
                Column {
                    Text("AutoJs6 Studio", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
                    Text("可视化积木脚本", fontSize = 9.5.sp, color = Palette.textDim)
                }
                Spacer(Modifier.width(14.dp))
                InlineField(
                    value = name,
                    onValueChange = { name = it; state.renameProject(it) },
                    placeholder = "项目名称",
                    modifier = Modifier.width(200.dp)
                )
                Spacer(Modifier.width(18.dp))
                // 宽屏：常用动作直接摆在顶栏，按「文件 | 检查 | 项目」分组，组间一条细竖线。
                // 只在 ≥ 1200dp 时全部展开；中等宽度只放文件组，其余仍在 ⋯ 里。
                if (this@BoxWithConstraints.maxWidth >= 1200.dp) {
                    ToolbarGroup {
                        ToolbarText("保存") { state.saveNow() }
                        ToolbarText("导入") { if (state.beginnerMode) state.importProject() else state.importScript() }
                        ToolbarText("导出 JS") { state.exportJs() }
                    }
                    ToolbarDivider()
                    ToolbarGroup {
                        if (state.beginnerMode) {
                            ToolbarText("检查问题") { state.showPane(Pane.DIAG) }
                        } else {
                            ToolbarText("测试") { state.runTests() }
                            ToolbarText("诊断") { state.showPane(Pane.DIAG) }
                            ToolbarText("体检") { state.openAudit() }
                            ToolbarText("录制") { state.openRecorder() }
                        }
                    }
                    ToolbarDivider()
                    ToolbarGroup {
                        ToolbarText("模板") { state.openTemplates() }
                        ToolbarText("示例") { state.loadExample() }
                    }
                } else {
                    ToolbarGroup {
                        ToolbarText("保存") { state.saveNow() }
                        ToolbarText("导出 JS") { state.exportJs() }
                    }
                }
                Spacer(Modifier.weight(1f))
                Badge(state.deviceStatusText, badgeKindOf(state.deviceStatusLevel))
                Spacer(Modifier.width(10.dp))
                QuietTextAction("撤销") { state.undo() }
                MoreMenu(state)
                Spacer(Modifier.width(8.dp))
                RunButton(state)
            }
        }
    }
}

@Composable
private fun BrandMark() {
    Box(
        Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)).background(Palette.accent),
        contentAlignment = Alignment.Center
    ) { Text("A6", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Palette.onAccent) }
}

/** The one primary action: run, or stop while it runs. */
@Composable
private fun RunButton(state: StudioState, modifier: Modifier = Modifier) {
    val running = state.running
    Box(
        modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (running) Palette.err else Palette.accent)
            .clickable { if (running) state.stopRun() else state.run() }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (running) "■ 停止" else "▶ 运行",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Palette.onAccent
        )
    }
}

/**
 * Toolbar action in the same style as the pane tabs (代码 / 运行 / 测试 …): small rounded box with a
 * 1dp border, so every secondary button in the window reads as one family.
 */
@Composable
fun OutlinedChip(label: String, active: Boolean = false, large: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        fontSize = if (large) 13.sp else 11.sp,
        // The top bar is 56dp tall: there the label is darker so it stays readable at a glance.
        color = if (active || large) Palette.text else Palette.textDim,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(if (large) 7.dp else 5.dp))
            .background(if (active) Palette.panelAlt else Palette.panel)
            .border(1.dp, Palette.border, RoundedCornerShape(if (large) 7.dp else 5.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = if (large) 13.dp else 9.dp, vertical = if (large) 7.dp else 4.dp)
    )
}

@Composable
private fun ToolbarText(label: String, onClick: () -> Unit) = OutlinedChip(label, large = true, onClick = onClick)

@Composable
private fun ToolbarGroup(content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

@Composable
private fun ToolbarDivider() {
    Box(Modifier.padding(horizontal = 10.dp).width(1.dp).height(22.dp).background(Palette.border))
}

/** 顶栏的轻量文字按钮（撤销等）：不用图标，直接写中文，小白也认得出。 */
@Composable
private fun QuietTextAction(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 13.sp, color = Palette.text)
    }
}

/** Borderless round icon button, same 36dp height as the run button. */
@Composable
private fun QuietAction(glyph: String, hint: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(glyph, fontSize = 17.sp, color = Palette.text)
    }
}

/** Device connection state as a dot on the logo, instead of a separate badge row. */
@Composable
private fun StatusDot(level: String, modifier: Modifier = Modifier) {
    val c = when (level) {
        "ok" -> Palette.ok
        "err" -> Palette.err
        "warn", "run" -> Palette.warn
        else -> Palette.textDim
    }
    Box(
        modifier.size(11.dp).clip(RoundedCornerShape(6.dp))
            .background(Palette.panel).padding(2.dp)
            .clip(RoundedCornerShape(4.dp)).background(c)
    )
}

/** Project name that reads as a title until tapped (no box around it on the phone). */
@Composable
private fun PlainNameField(value: String, onChange: (String) -> Unit) {
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Palette.text
        ),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Palette.accent),
        modifier = Modifier.fillMaxWidth().trackTextFocus()
    )
}

/**
 * Everything that used to sit in the toolbar, grouped so the menu reads like a table of contents
 * instead of a button dump.
 *
 * 用 ModalBottomSheet 而非 DropdownMenu：DropdownMenu 是 SubcomposeLayout，内容不能加滚动容器
 * （放 LazyColumn 崩 intrinsic measurement，放 Column+verticalScroll 崩 infinity constraints，
 * 真机两次实测）。底部抽屉自带滚动区与高度约束，菜单项再多也不会被屏幕截断且能滑动。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun MoreMenu(state: StudioState) {
    var open by remember { mutableStateOf(false) }

    Box {
        QuietAction("⋯", "更多") { open = true }
        if (open) {
            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = { open = false },
                containerColor = Palette.panel
            ) {
                LazyColumn(
                    Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    item {
                        groupTitle("常用")
                        menuItem("保存", Icons.Filled.Done) { open = false; state.saveNow() }
                        menuItem("导出 JS", Icons.Filled.Share) { open = false; state.exportJs() }
                        menuItem("导出项目", Icons.Filled.Share) { open = false; state.exportProject() }
                        menuItem("载入示例", Icons.Filled.Star) { open = false; state.loadExample() }
                    }
                    item {
                        groupTitle("检查")
                        if (state.beginnerMode) {
                            menuItem("检查问题", Icons.Filled.Warning) { open = false; state.showPane(Pane.DIAG) }
                        } else {
                            menuItem("跑一遍测试", Icons.Filled.Check) { open = false; state.runTests() }
                            menuItem("诊断", Icons.Filled.Warning) { open = false; state.showPane(Pane.DIAG) }
                            menuItem("选择器体检", Icons.Filled.Search) { open = false; state.openAudit() }
                            menuItem("录制操作", Icons.Filled.PlayArrow) { open = false; state.openRecorder() }
                        }
                    }
                    item {
                        groupTitle("视图 / 项目")
                        menuItem(if (state.beginnerMode) "切换到完整模式" else "切换到简单模式", Icons.Filled.Settings) { open = false; state.changeBeginnerMode(!state.beginnerMode) }
                        menuItem("模板库", Icons.AutoMirrored.Filled.List) { open = false; state.openTemplates() }
                        menuItem("怎么做（新手引导）", Icons.Filled.Info) { open = false; state.showGuide = true }
                        if (!state.beginnerMode) {
                            menuItem("导入 JS 脚本", Icons.Filled.Add) { open = false; state.importScript() }
                            menuItem("导入项目", Icons.Filled.Add) { open = false; state.importProject() }
                        }
                        menuItem("新建项目", Icons.Filled.AddCircle) { open = false; state.newProject() }
                    }
                }
            }
        }
    }
}

/** 底部菜单里的一个条目：图标 + 文案，触摸整行触发。 */
@Composable
private fun menuItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, fontSize = 11.5.sp, color = Palette.text) },
        leadingIcon = {
            Icon(
                icon,
                contentDescription = null,
                tint = Palette.textDim,
                modifier = Modifier.size(14.dp)
            )
        },
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 5.dp)
    )
}

/** 底部菜单的分组小标题。 */
@Composable
private fun groupTitle(title: String) {
    Text(
        title,
        fontSize = 9.sp,
        color = Palette.textDim,
        modifier = Modifier.padding(start = 18.dp, top = 8.dp, bottom = 2.dp)
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
            .background(if (active) Palette.accent else Palette.panelAlt)
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = if (onDelete != null && active) 3.dp else 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 10.5.sp,
            color = if (active) Palette.onAccent else Palette.textDim
        )
        if (onDelete != null && active) {
            Text(
                "✕",
                fontSize = 10.sp,
                color = Palette.onAccent.copy(alpha = 0.8f),
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
            Pane.AI to "AI 分析",
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
            Pane.AI to "AI 分析",
            Pane.HELP to "帮助"
        )
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        tabs.forEach { (pane, label) ->
            OutlinedChip(label, active = state.pane == pane) { state.showPane(pane) }
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
private fun PaletteColumn(state: StudioState, modifier: Modifier, wideLayout: Boolean = false) {
    val project = state.repo.current
    Column(modifier.border(width = 1.dp, color = Palette.borderSoft).background(Palette.panel)) {
        PalettePanel(
            onAdd = { state.addBlock(it) },
            modifier = Modifier.weight(1f),
            beginner = state.beginnerMode,
            onSwitchToAdvanced = { state.changeBeginnerMode(false) },
            // Drag only when palette and canvas are on screen together (wide layout). On the
            // phone layout they are separate sheets, so there is nothing to drop onto.
            drag = if (wideLayout) state.paletteDrag else null,
            longPressDrag = state.platformRef.name == "android",
            onDrop = { type, target -> state.dropBlock(type, target) }
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val totalBlocks = cn.dumate.a6studio.model.TreeOps.count(project.root.children["body"].orEmpty()) +
                project.modules.sumOf { cn.dumate.a6studio.model.TreeOps.count(it.children["body"].orEmpty()) }
            Text("$totalBlocks 块", fontSize = 10.sp, color = Palette.textDim)
            Text("${project.modules.size} 模块", fontSize = 10.sp, color = Palette.textDim)
            Text("${project.tests.size} 测试", fontSize = 10.sp, color = Palette.textDim)
        }
    }
}

@Composable
private fun CanvasColumn(state: StudioState, modifier: Modifier, wideLayout: Boolean = false) {
    val project = state.repo.current
    val selected = state.repo.selectedId
    Column(modifier.background(Palette.bg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 标签区占剩余宽度并在内部横向滚动。以前它没有 weight，horizontalScroll 会吃掉整行宽度，
            // 把右侧的「+ 模块」挤出屏幕，模块多一点或手机上就看不到按钮了。
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ScopeTabs(state)
            }
            Spacer(Modifier.width(8.dp))
            if (state.statusHint.isNotEmpty()) {
                Text(state.statusHint, fontSize = 10.sp, color = Palette.textDim, maxLines = 1, modifier = Modifier.widthIn(max = 160.dp))
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
                onShowGuide = { state.showGuide = true },
                drag = if (wideLayout) state.paletteDrag else null
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
                Pane.AI -> AiTrafficPane(state, Modifier.fillMaxSize())
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
    // 分段控件：三段等宽、共用一块底色，读起来是「切换视图」，而不是三个独立按钮。
    Row(
        Modifier.fillMaxWidth().background(Palette.panel).padding(horizontal = 10.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp)).background(Palette.panelAlt).padding(3.dp)
    ) {
        listOf(
            MobileSheet.CANVAS to "编辑（画布 + 积木）",
            MobileSheet.PANE to "面板"
        ).forEach { (sheet, label) ->
            // 编辑模式下画布与积木同屏；只有 CANVAS/PALETTE 都算编辑
            val active = if (sheet == MobileSheet.CANVAS) state.mobileSheet != MobileSheet.PANE else state.mobileSheet == sheet
            Box(
                Modifier.weight(1f).height(30.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (active) Palette.panel else Color.Transparent)
                    .clickable { state.mobileSheet = sheet },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) Palette.text else Palette.textDim
                )
            }
        }
    }
}
