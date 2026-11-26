package cn.dumate.a6studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.dumate.a6studio.audit.AuditStatus
import cn.dumate.a6studio.picker.Candidate
import cn.dumate.a6studio.picker.PickKind
import cn.dumate.a6studio.picker.PickResult
import cn.dumate.a6studio.picker.PickerLogic
import cn.dumate.a6studio.picker.PickerView
import cn.dumate.a6studio.picker.UiNode
import cn.dumate.a6studio.recorder.RecorderStatus
import cn.dumate.a6studio.ui.image.decodeBase64ToImage
import cn.dumate.a6studio.ui.components.Badge
import cn.dumate.a6studio.ui.components.rememberHovered
import cn.dumate.a6studio.ui.components.BadgeKind
import cn.dumate.a6studio.ui.components.Chip
import cn.dumate.a6studio.ui.components.EmptyHint
import cn.dumate.a6studio.ui.components.KV
import cn.dumate.a6studio.ui.fields.InlineField
import cn.dumate.a6studio.ui.fields.SearchField
import cn.dumate.a6studio.ui.panes.SmallButton
import cn.dumate.a6studio.ui.panes.TinyToggle

/** Shared modal frame: full-screen scrim + a wide panel with a header slot. */
@Composable
private fun OverlayFrame(
    title: String,
    subtitle: String,
    onClose: () -> Unit,
    headerActions: @Composable (() -> Unit)? = null,
    body: @Composable (Modifier) -> Unit
) {
    // Scrim does NOT dismiss: an accidental click outside the dialog would otherwise throw
    // away in-flight state (e.g. a running recorder or audit results). Close via ✕ or Esc.
    val (closeHoverMod, closeHovered) = rememberHovered()
    Box(
        Modifier.fillMaxSize().background(Color(0x661F2328)).pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.widthIn(max = 1100.dp).fillMaxWidth(0.94f).fillMaxHeight(0.92f)
                .clip(RoundedCornerShape(10.dp))
                .background(Palette.panel)
                .border(1.dp, Palette.border, RoundedCornerShape(10.dp))
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
                Spacer(Modifier.width(8.dp))
                Text(subtitle, fontSize = 10.5.sp, color = Palette.textDim)
                Spacer(Modifier.weight(1f))
                headerActions?.invoke()
                Spacer(Modifier.width(8.dp))
                Text(
                    "✕", fontSize = 12.sp, color = if (closeHovered) Palette.text else Palette.textDim,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .then(closeHoverMod)
                        .background(if (closeHovered) Palette.hover else Color.Transparent)
                        .clickable(onClick = onClose)
                        .padding(5.dp)
                )
            }
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.border))
            body(Modifier.weight(1f))
        }
    }
}

// ============================ picker ============================

@Composable
fun PickerOverlay(state: StudioState) {
    val s = state.pickerState
    val target = s.target
    val targetLabel = remember(target, state.repo.current) {
        val block = target?.blockId?.let { state.repo.blockById(it) }
        val name = block?.let { b ->
            cn.dumate.a6studio.catalog.Catalog.byType(b.type)?.let { cn.dumate.a6studio.catalog.Catalog.cleanName(it) }
        } ?: target?.blockId ?: ""
        when (target?.kind) {
            PickKind.COORD -> "拾取坐标：$name"
            PickKind.SWIPE -> "拾取滑动起止点：$name"
            PickKind.SELECTOR -> "拾取控件：$name"
            null -> ""
        }
    }

    OverlayFrame(
        title = "控件拾取",
        subtitle = targetLabel + if (s.meta.isNotEmpty()) " · ${s.meta}" else "",
        onClose = { state.closeOverlay() },
        headerActions = {
            SmallButton("刷新") { state.pickerRefresh() }
        }
    ) { bodyMod ->
        Column(bodyMod) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ViewTab("列表", s.view == PickerView.LIST) { state.pickerSetView(PickerView.LIST) }
                Spacer(Modifier.width(4.dp))
                ViewTab("截图", s.view == PickerView.SHOT) { state.pickerSetView(PickerView.SHOT) }
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    SearchField(s.query, { state.pickerSetQuery(it) }, "过滤：文本 / desc / id / 类名")
                }
                Spacer(Modifier.width(10.dp))
                TinyToggle("只看可点击", s.onlyClickable) { state.pickerSetOnlyClickable(it) }
            }

            s.region?.let { r ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("区域 ${r.joinToString(", ")} · ${s.nodes.size} 个节点", fontSize = 10.5.sp, color = Palette.textDim)
                    Spacer(Modifier.weight(1f))
                    SmallButton("清除区域") { state.pickerClearRegion() }
                }
            }

            Row(Modifier.weight(1f).fillMaxWidth()) {
                // tree / shot
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .border(1.dp, Palette.borderSoft)
                        .background(Palette.codeBg)
                ) {
                    if (s.view == PickerView.LIST) PickerTree(state, Modifier.fillMaxSize())
                    else PickerShot(state, Modifier.fillMaxSize())
                }
                // detail
                Column(
                    Modifier.widthIn(min = 320.dp, max = 400.dp).fillMaxHeight()
                        .border(1.dp, Palette.borderSoft)
                        .padding(10.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    PickerDetail(state)
                }
            }

            if (s.hint.isNotEmpty()) {
                Text(
                    s.hint, fontSize = 10.5.sp, color = Palette.textDim,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun ViewTab(label: String, active: Boolean, onClick: () -> Unit) {
    val (hoverMod, hovered) = rememberHovered()
    Text(
        label, fontSize = 10.5.sp,
        color = if (active) Palette.onAccent else Palette.textDim,
        modifier = Modifier.clip(RoundedCornerShape(4.dp))
            .then(hoverMod)
            .background(if (active) Palette.accent else if (hovered) Palette.hover else Palette.panelAlt)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp)
    )
}

@Composable
private fun PickerTree(state: StudioState, modifier: Modifier) {
    val s = state.pickerState
    if (s.error != null) {
        Column(modifier.padding(12.dp)) {
            Text("连接真机失败：${s.error}", fontSize = 11.sp, color = Palette.err)
            Spacer(Modifier.height(6.dp))
            Text("检查「真机」页的 MCP 地址；跨域被拦时先启动 bridge 代理。", fontSize = 10.sp, color = Palette.textDim)
            Spacer(Modifier.height(8.dp))
            SmallButton("去真机页") { state.closeOverlay(); state.showPane(Pane.DEVICE) }
        }
        return
    }
    if (s.busy && s.nodes.isEmpty()) {
        EmptyHint("正在抓取界面…", modifier.padding(12.dp))
        return
    }
    val rows = s.visibleNodes
    LazyColumn(modifier) {
        if (rows.isEmpty()) item { EmptyHint("没有匹配的节点", Modifier.padding(12.dp)) }
        items(count = rows.size) { i ->
            val n = rows[i]
            val isSel = s.selected === n
            Row(
                Modifier.fillMaxWidth()
                    .padding(start = (6 + n.depth * 12).dp, top = 2.dp, bottom = 2.dp, end = 6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (isSel) Palette.accent.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { state.pickerSelect(n) }
                    .padding(horizontal = 4.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(PickerLogic.shortClass(n.className), fontSize = 9.5.sp, color = Palette.textDim, modifier = Modifier.widthIn(min = 60.dp))
                Spacer(Modifier.width(5.dp))
                Text(n.text.ifEmpty { n.desc.ifEmpty { "—" } }, fontSize = 11.sp, color = Palette.text, maxLines = 1, modifier = Modifier.weight(1f))
                if (n.id.isNotEmpty()) {
                    Spacer(Modifier.width(5.dp))
                    Text("#" + PickerLogic.shortId(n.id), fontSize = 9.sp, color = Palette.info)
                }
                if (n.clickable) { Spacer(Modifier.width(4.dp)); Chip("可点", Palette.ok) }
                if (n.editable) { Spacer(Modifier.width(4.dp)); Chip("输入", Palette.warn) }
            }
        }
    }
}

@Composable
private fun PickerShot(state: StudioState, modifier: Modifier) {
    val s = state.pickerState
    val dw = s.deviceWidth
    val dh = s.deviceHeight

    Column(modifier.verticalScroll(rememberScrollState()).padding(8.dp)) {
        val shotBase64 = s.shotBase64
        if (shotBase64 == null) {
            EmptyHint("切到「截图」会自动抓一张当前屏幕；拖拽可框选一块区域下钻", Modifier.padding(8.dp))
            SmallButton("重新截图") { state.pickerCapture() }
            return@Column
        }
        val bitmap = remember(shotBase64) { runCatching { decodeBase64ToImage(shotBase64) }.getOrNull() }
        if (bitmap == null) {
            EmptyHint("截图解码失败（图片数据无效或格式不受支持）", Modifier.padding(8.dp))
            SmallButton("重新截图") { state.pickerCapture() }
            return@Column
        }
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(520.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Palette.panelAlt)
        ) {
            // The image is letterboxed inside this box; map device coords onto the drawn area
            // so hot zones and taps line up with the screenshot.
            val density = LocalDensity.current
            val boxW = maxWidth
            val boxH = maxHeight
            val scale = minOf(boxW / dw.dp, boxH / dh.dp)
            val drawnW = dw.dp * scale
            val drawnH = dh.dp * scale
            val offsetX = (boxW - drawnW) / 2
            val offsetY = (boxH - drawnH) / 2
            val offsetXPx = with(density) { offsetX.toPx() }
            val offsetYPx = with(density) { offsetY.toPx() }
            val drawnWPx = with(density) { drawnW.toPx() }.coerceAtLeast(1f)
            val drawnHPx = with(density) { drawnH.toPx() }.coerceAtLeast(1f)
            var dragStart by remember(shotBase64) { mutableStateOf<Offset?>(null) }
            var dragEnd by remember(shotBase64) { mutableStateOf<Offset?>(null) }

            fun toDevX(pxVal: Float) = ((pxVal - offsetXPx) / drawnWPx * dw).toInt().coerceIn(0, dw)
            fun toDevY(pyVal: Float) = ((pyVal - offsetYPx) / drawnHPx * dh).toInt().coerceIn(0, dh)

            androidx.compose.foundation.Image(
                bitmap = bitmap,
                contentDescription = "device screen",
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                modifier = Modifier.fillMaxSize()
            )

            fun px(x: Int) = offsetX + drawnW * (x.toFloat() / dw)
            fun py(y: Int) = offsetY + drawnH * (y.toFloat() / dh)

            // Hot zones for each visible node.
            s.visibleNodes.forEach { n ->
                val w = n.width.toFloat()
                val h = n.height.toFloat()
                val area = (dw * dh).toFloat().coerceAtLeast(1f)
                if (w <= 0f || h <= 0f) return@forEach
                if (n.text.isEmpty() && n.desc.isEmpty() && (w * h) / area > 0.85f) return@forEach
                val isSel = s.selected === n
                Box(
                    Modifier
                        .offset(x = px(n.left), y = py(n.top))
                        .size(width = drawnW * (w / dw), height = drawnH * (h / dh))
                        .border(
                            if (isSel) 2.dp else 1.dp,
                            if (isSel) Palette.accent else if (n.clickable) Palette.ok.copy(alpha = 0.75f) else Palette.info.copy(alpha = 0.45f),
                            RoundedCornerShape(1.dp)
                        )
                )
            }

            // Tap anywhere on the screenshot or drag to drill into a sub-region.
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(shotBase64, s.target?.kind, s.visibleNodes, offsetXPx, offsetYPx, drawnWPx, drawnHPx, dw, dh) {
                        detectTapGestures { pos ->
                            val dx = toDevX(pos.x)
                            val dy = toDevY(pos.y)
                            when (s.target?.kind) {
                                PickKind.COORD -> state.pickerCoord(dx, dy)
                                PickKind.SWIPE -> {
                                    state.pickerSwipePoint(dx, dy)
                                    state.pickerCoord(dx, dy)
                                }
                                else -> {
                                    val hit = s.visibleNodes.lastOrNull { n ->
                                        dx >= n.left && dx <= n.right && dy >= n.top && dy <= n.bottom
                                    }
                                    if (hit != null) state.pickerSelect(hit) else state.pickerCoord(dx, dy)
                                }
                            }
                        }
                    }
                    .pointerInput(shotBase64, offsetXPx, offsetYPx, drawnWPx, drawnHPx, dw, dh) {
                        detectDragGestures(
                            onDragStart = { start ->
                                dragStart = start
                                dragEnd = start
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                dragEnd = change.position
                            },
                            onDragCancel = {
                                dragStart = null
                                dragEnd = null
                            },
                            onDragEnd = {
                                val a = dragStart
                                val b = dragEnd
                                dragStart = null
                                dragEnd = null
                                if (a != null && b != null) {
                                    val l = minOf(toDevX(a.x), toDevX(b.x))
                                    val t = minOf(toDevY(a.y), toDevY(b.y))
                                    val r = maxOf(toDevX(a.x), toDevX(b.x))
                                    val bot = maxOf(toDevY(a.y), toDevY(b.y))
                                    state.pickerDrill(l, t, r, bot)
                                }
                            }
                        )
                    }
            )

            // Drag selection preview box
            val ds = dragStart
            val de = dragEnd
            if (ds != null && de != null) {
                val leftDp = with(density) { minOf(ds.x, de.x).toDp() }
                val topDp = with(density) { minOf(ds.y, de.y).toDp() }
                val wDp = with(density) { kotlin.math.abs(de.x - ds.x).toDp() }
                val hDp = with(density) { kotlin.math.abs(de.y - ds.y).toDp() }
                Box(
                    Modifier.offset(x = leftDp, y = topDp)
                        .size(width = wDp, height = hDp)
                        .background(Palette.accent.copy(alpha = 0.14f))
                        .border(1.5.dp, Palette.accent, RoundedCornerShape(2.dp))
                )
            }

            // Marks for the current selection / picked point / swipe points.
            s.coord?.let { c ->
                Box(
                    Modifier.offset(x = px(c.first) - 5.dp, y = py(c.second) - 5.dp)
                        .size(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Palette.accent)
                )
            }
            val (sa, sb) = s.swipePoints
            listOf(sa, sb).forEachIndexed { i, pt ->
                if (pt != null) {
                    Box(
                        Modifier.offset(x = px(pt.first) - 8.dp, y = py(pt.second) - 8.dp)
                            .size(16.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (i == 0) Palette.warn else Palette.ok),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(if (i == 0) "①" else "②", fontSize = 8.sp, color = Color.White)
                    }
                }
            }

            if (s.region != null) {
                val r = s.region
                Box(
                    Modifier.offset(x = px(r[0]), y = py(r[1]))
                        .size(width = drawnW * ((r[2] - r[0]).toFloat() / dw), height = drawnH * ((r[3] - r[1]).toFloat() / dh))
                        .border(1.5.dp, Palette.warn, RoundedCornerShape(2.dp))
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        // 大部分人是「在图上点一下」，手动输入坐标是少数场景：默认收起，点开才出现。
        var manual by remember { mutableStateOf(false) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallButton("重新截图") { state.pickerCapture() }
            SmallButton(if (manual) "收起手动输入" else "手动输入坐标") { manual = !manual }
        }
        if (manual) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                var x by remember { mutableStateOf("0") }
                var y by remember { mutableStateOf("0") }
                InlineField(x, { x = it }, placeholder = "x", mono = true, modifier = Modifier.width(70.dp))
                InlineField(y, { y = it }, placeholder = "y", mono = true, modifier = Modifier.width(70.dp))
                SmallButton("取坐标") { state.pickerCoord(x.toIntOrNull() ?: 0, y.toIntOrNull() ?: 0) }
            }
        }
        if (s.coord != null) {
            Text("已取点 ${s.coord.first}, ${s.coord.second}", fontSize = 10.5.sp, color = Palette.accent)
        }
    }
}

@Composable
private fun PickerDetail(state: StudioState) {
    val s = state.pickerState
    val target = s.target

    if (target?.kind == PickKind.SWIPE) {
        val (a, b) = s.swipePoints
        Text("滑动起止点", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
        Spacer(Modifier.height(6.dp))
        KV("起点", if (a != null) "${a.first}, ${a.second}" else "未取")
        KV("终点", if (b != null) "${b.first}, ${b.second}" else "未取")
        if (a != null && b != null) {
            val dx = b.first - a.first
            val dy = b.second - a.second
            KV("位移", "$dx, $dy · 距离 ${kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toInt()}px")
        }
        Spacer(Modifier.height(8.dp))
        Text("在截图上点两下取起止点，也可以点两个控件用它们的中心；第三下会重新取起点。", fontSize = 10.sp, color = Palette.textDim)
        Spacer(Modifier.height(8.dp))
        SmallButton("填入起止坐标", primary = a != null && b != null) {
            if (a != null && b != null) {
                state.applyPick(
                    PickResult(target.blockId, PickKind.SWIPE, x1 = a.first, y1 = a.second, x2 = b.first, y2 = b.second)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        SmallButton("重取") { state.pickerClearSwipePoints() }
        return
    }

    if (target?.kind == PickKind.COORD) {
        val n = s.selected
        val coord = s.coord
        Text("坐标模式", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
        Spacer(Modifier.height(6.dp))
        KV("取点", if (coord != null) "${coord.first}, ${coord.second}" else "未取")
        KV("节点中心", if (n != null) "${n.centerX}, ${n.centerY}" else "—")
        Spacer(Modifier.height(6.dp))
        Text("分辨率变了坐标就会失效；能选控件就不要用坐标。", fontSize = 10.sp, color = Palette.textDim)
        Spacer(Modifier.height(8.dp))
        SmallButton("用这个坐标填入", primary = coord != null || n != null) {
            val pt = coord ?: n?.let { it.centerX to it.centerY }
            if (pt != null) state.applyPick(PickResult(target.blockId, PickKind.COORD, x = pt.first, y = pt.second))
        }
        return
    }

    // selector mode
    val sel = s.selected
    // Every branch below writes into the picked parameter; without a target there is nowhere to
    // write, and `target!!` would have crashed the whole overlay instead of just doing nothing.
    val t = target ?: return
    if (sel == null) {
        EmptyHint("点一个控件查看候选选择器；或切到「截图」取坐标")
        return
    }
    Text(PickerLogic.shortClass(sel.className), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
    Text(sel.text.ifEmpty { sel.desc }, fontSize = 10.5.sp, color = Palette.textDim)
    Spacer(Modifier.height(4.dp))
    // 原来 id / desc / bounds / center 各占一行（四行都是短值），压成两行：
    // 判重主要看哪个控件，位置只要够判断「是不是它」。
    Text("id ${sel.id.ifEmpty { "—" }}", fontSize = 10.sp, color = Palette.textDim, fontFamily = FontFamily.Monospace)
    Text(
        "desc ${sel.desc.ifEmpty { "—" }} · ${sel.left},${sel.top} → ${sel.right},${sel.bottom} · 中心 ${sel.centerX},${sel.centerY}",
        fontSize = 10.sp, color = Palette.textDim, fontFamily = FontFamily.Monospace
    )
    Spacer(Modifier.height(8.dp))

    if (s.candidates.isEmpty()) {
        Text("正在生成候选…", fontSize = 10.5.sp, color = Palette.textDim)
    }
    s.candidates.forEach { c ->
        CandidateRow(c, chosen = c === s.chosen) {
            state.applyPick(
                PickResult(t.blockId, PickKind.SELECTOR, by = c.by, value = c.value)
            )
        }
    }
    val chosen = s.chosen
    if (chosen == null && s.candidates.isNotEmpty()) {
        val best = s.candidates.maxByOrNull { it.score }
        if (best != null) {
            Text(
                "没有唯一选择器：最高分候选 ${best.score}（${best.count ?: "?"} 个命中）。强制写入可能点错，建议改用 id、缩小区域，或直接取坐标。",
                fontSize = 10.sp, color = Palette.warn
            )
        }
    } else if (chosen != null && chosen.score < 60) {
        Text("评分 ${chosen.score}：换版本或换分辨率可能失效，建议优先用 id。", fontSize = 10.sp, color = Palette.warn)
    }

    Spacer(Modifier.height(10.dp))
    // 四个同等权重的按钮（填入积木 / 填入为表达式 / 填入兜底表达式 / 强制写入最稳候选）
    // 让「我到底该点哪个」变成一道选择题。默认动作只留一个，其余是备选。
    var more by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        SmallButton("填入积木", primary = chosen != null) {
            if (chosen != null) state.applyPick(PickResult(t.blockId, PickKind.SELECTOR, by = chosen.by, value = chosen.value))
        }
        // 备选填法不用弹出菜单：面板只有 320-400dp 宽，内联展开既不会被裁掉，
        // 也能一眼看见三种写法分别是什么。
        SmallButton(if (more) "收起备选" else "其他填法…") { more = !more }
    }
    if (more) {
        Spacer(Modifier.height(6.dp))
        Text("备选填法", fontSize = 10.sp, color = Palette.textDim)
        Spacer(Modifier.height(4.dp))
        SmallButton("填入为表达式") {
            val expr = chosen?.let { PickerLogic.toExpr(it) }
                ?: PickerLogic.bestOf(s.candidates)?.let { PickerLogic.toExpr(it) }
            if (expr != null) state.applyPick(PickResult(t.blockId, PickKind.SELECTOR, expr = expr))
        }
        Spacer(Modifier.height(4.dp))
        SmallButton("填入兜底表达式（多个条件依次试）") {
            val expr = PickerLogic.chainExpr(s.candidates)
            if (expr != null) state.applyPick(PickResult(t.blockId, PickKind.SELECTOR, expr = expr))
        }
        Spacer(Modifier.height(4.dp))
        SmallButton("强制写入分数最高的候选") {
            val best = PickerLogic.bestOf(s.candidates)
            if (best != null) state.applyPick(PickResult(t.blockId, PickKind.SELECTOR, by = best.by, value = best.value))
        }
    }
}

@Composable
private fun CandidateRow(c: Candidate, chosen: Boolean, onApply: () -> Unit) {
    val (hoverMod, hovered) = rememberHovered()
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .clip(RoundedCornerShape(4.dp))
            .then(hoverMod)
            .background(if (chosen) Palette.ok.copy(alpha = 0.12f) else if (hovered) Palette.hover else Palette.panelAlt)
            .clickable(onClick = onApply)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            PickerLogic.toExpr(c),
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            color = Palette.text,
            modifier = Modifier.weight(1f),
            maxLines = 1
        )
        Spacer(Modifier.width(6.dp))
        when (c.count) {
            1 -> Chip("唯一", Palette.ok)
            null -> Chip("…", Palette.textDim)
            -1 -> Chip("错误", Palette.err)
            else -> Chip("${c.count} 个", Palette.warn)
        }
        Spacer(Modifier.width(6.dp))
        Text("${c.score}", fontSize = 10.sp, color = Palette.textDim)
    }
}

// ============================ audit ============================

@Composable
fun AuditOverlay(state: StudioState) {
    val rows = state.auditRows
    val ok = rows.count { it.status == AuditStatus.UNIQUE }
    val bad = rows.count { it.status in setOf(AuditStatus.MULTIPLE, AuditStatus.MISSING, AuditStatus.ERROR) }
    val fixable = rows.count { it.fix != null }

    OverlayFrame(
        title = "选择器体检",
        subtitle = if (rows.isEmpty()) "—" else "${rows.size} 个选择器 · 唯一 $ok · 问题 $bad · 可修 $fixable",
        onClose = { state.closeOverlay() },
        headerActions = {
            SmallButton("体检全部") { state.rerunAudit() }
            Spacer(Modifier.width(5.dp))
            SmallButton("修好 $fixable 处", primary = fixable > 0) { state.applyAuditFixes() }
            Spacer(Modifier.width(5.dp))
            SmallButton("复制报告") { state.copyAuditReport() }
        }
    ) { bodyMod ->
        Column(bodyMod) {
            LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 6.dp)) {
                if (rows.isEmpty()) item { EmptyHint("项目里没有 by/value 选择器积木（只有表达式或坐标类积木时没有可体检项）。") }
                items(count = rows.size) { i ->
                    val r = rows[i]
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Palette.panelAlt)
                            .padding(7.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            when (r.status) {
                                AuditStatus.UNIQUE -> Chip("唯一", Palette.ok)
                                AuditStatus.MULTIPLE -> Chip("${r.count} 个", Palette.warn)
                                AuditStatus.MISSING -> Chip("未找到", Palette.err)
                                AuditStatus.ERROR -> Chip("出错", Palette.err)
                                AuditStatus.EXPR -> Chip("表达式", Palette.textDim)
                                AuditStatus.IDLE -> Chip("待检", Palette.textDim)
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(r.name, fontSize = 11.5.sp, color = Palette.text)
                            Spacer(Modifier.width(6.dp))
                            Text(r.scope, fontSize = 9.5.sp, color = Palette.textDim)
                            r.score?.let {
                                Spacer(Modifier.width(6.dp))
                                Text("评分 $it", fontSize = 9.5.sp, color = Palette.textDim)
                            }
                            Spacer(Modifier.weight(1f))
                            SmallButton("拾取器") {
                                state.openPicker(r.blockId, "value", PickKind.SELECTOR)
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            state.auditController.selText(r.by, r.value) + if (r.expr.isNotEmpty()) "  [fx]" else "",
                            fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = Palette.info
                        )
                        if (r.note.isNotEmpty()) Text(r.note, fontSize = 10.sp, color = Palette.textDim)
                        val fix = r.fix
                        if (fix != null) {
                            Text(
                                "可改为 " + state.auditController.selText(fix.first, fix.second),
                                fontSize = 10.sp, color = Palette.ok
                            )
                        }
                    }
                }
            }
            Text(
                state.auditNote.ifEmpty { "体检只读页面、只调 ui_find / ui_explain_selector，不改脚本；修好会先快照，可撤销。" },
                fontSize = 10.sp, color = Palette.textDim,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

// ============================ recorder ============================

@Composable
fun RecorderOverlay(state: StudioState) {
    val s = state.recorderState
    val recording = s.status == RecorderStatus.RECORDING

    OverlayFrame(
        title = "真机录制",
        subtitle = if (recording) "录制中 · ${s.usableCount} 段操作" else if (s.usableCount > 0) "${s.usableCount} 段操作" else "—",
        onClose = { state.closeOverlay() },
        headerActions = {
            SmallButton(if (recording) "停止" else "开始录制", primary = !recording) {
                if (recording) state.recorderStop() else state.recorderStart()
            }
            Spacer(Modifier.width(5.dp))
            SmallButton("生成积木（新 ${s.freshCount}）") { state.generateFromRecording() }
            Spacer(Modifier.width(5.dp))
            SmallButton("读取并生成") { state.recorderFetchAndGenerate() }
            Spacer(Modifier.width(5.dp))
            SmallButton("导出脚本") { state.recorderExportScript() }
            Spacer(Modifier.width(5.dp))
            SmallButton("导入文件") { state.recorderImport() }
            Spacer(Modifier.width(5.dp))
            SmallButton("清空") { state.recorderClear() }
        }
    ) { bodyMod ->
        Column(bodyMod) {
            LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 6.dp)) {
                if (s.events.isEmpty()) {
                    item {
                        EmptyHint(
                            if (recording) "已开始，等你操作手机…"
                            else "还没录到内容。点「开始录制」，然后在手机上操作。"
                        )
                    }
                }
                items(count = s.events.size) { i ->
                    val e = s.events[i]
                    val kind = when (e.type) {
                        "start" -> Chip("开始", Palette.ok)
                        "error" -> Chip("报错", Palette.err)
                        "key" -> Chip("按键", Palette.textDim)
                        "swipe" -> Chip("滑动", Palette.info)
                        else -> if (state.recorderController.pickSelector(e) != null) Chip("点击", Palette.ok)
                        else Chip("点击", Palette.warn)
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Palette.panelAlt)
                            .padding(horizontal = 7.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        kind
                        Spacer(Modifier.width(6.dp))
                        Text("#${i + 1}", fontSize = 10.sp, color = Palette.textDim, modifier = Modifier.width(30.dp))
                        if (e.pkg.isNotEmpty()) {
                            Text(e.pkg, fontSize = 9.5.sp, color = Palette.textDim)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            state.recorderController.describe(e),
                            fontSize = 10.5.sp, color = Palette.text,
                            fontFamily = FontFamily.Monospace, maxLines = 1
                        )
                    }
                }
            }
            Text(
                s.note.ifEmpty {
                    "点「开始录制」把监听脚本推到手机，然后在手机上操作（点按、滑动）。" +
                        "AutoJs6 里脚本一旦常驻触摸监听，同进程的 MCP 服务就不再应答，所以录制期间不读事件——" +
                        "操作完在手机上停止脚本（或点「停止」），再点「读取并生成」。"
                },
                fontSize = 10.sp, color = Palette.textDim,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}