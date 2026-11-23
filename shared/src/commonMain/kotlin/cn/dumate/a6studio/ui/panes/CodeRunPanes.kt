package cn.dumate.a6studio.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import cn.dumate.a6studio.model.TreeOps
import cn.dumate.a6studio.ui.Palette as P
import cn.dumate.a6studio.ui.StudioState
import cn.dumate.a6studio.ui.components.Badge
import cn.dumate.a6studio.ui.components.BadgeKind
import cn.dumate.a6studio.ui.components.Chip
import cn.dumate.a6studio.ui.components.EmptyHint
import cn.dumate.a6studio.ui.components.SectionTitle

@Composable
fun CodePane(state: StudioState, modifier: Modifier = Modifier) {
    val lines = state.codeMarkers
    val selected = state.repo.selectedId
    val project = state.repo.current

    // Which lines belong to the selected block (its own code plus every descendant's).
    val highlighted = remember(selected, lines, project) {
        if (selected == null) emptySet<Int>()
        else {
            val root = TreeOps.find(project.root.children["body"].orEmpty(), selected)
                ?: project.modules.firstNotNullOfOrNull { TreeOps.find(it.children["body"].orEmpty(), selected) }
            val ids = root?.let { TreeOps.subtreeIds(it) } ?: setOf(selected)
            val set = mutableSetOf<Int>()
            var inside = false
            lines.forEachIndexed { i, line ->
                if (line.isEmptyMarker) {
                    inside = line.blockId in ids
                } else if (inside) {
                    set += i
                }
            }
            set
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(selected) {
        val first = highlighted.minOrNull()
        if (first != null) listState.animateScrollToItem(first)
    }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(state.cleanCodeInfo.ifEmpty { "生成的 AutoJs6 脚本" }, fontSize = 11.sp, color = P.textDim)
            Spacer(Modifier.weight(1f))
            Text(
                "复制",
                fontSize = 11.sp,
                color = P.accent,
                modifier = Modifier.clip(RoundedCornerShape(4.dp))
                    .background(P.accent.copy(alpha = 0.12f))
                    .clickable { state.copyCode() }
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
        // Code scrolls horizontally so lines keep their shape: wrapping would make the line
        // numbers line up with the wrong text.
        val hScroll = rememberScrollState()
        val widest = remember(lines) {
            // Row width is estimated from the text, so the estimate decides whether long lines
            // are cut off (`device.heigh…`). Count CJK/full-width chars as two columns, and
            // leave room for the desktop fontScale (1.08) plus the line-number gutter.
            var widestCols = 0
            for (l in lines) {
                var cols = 0
                for (ch in l.text) cols += if (ch.code >= 0x2E80) 2 else 1
                if (cols > widestCols) widestCols = cols
            }
            widestCols
        }
        LazyColumn(
            Modifier.weight(1f).background(P.codeBg).horizontalScroll(hScroll),
            state = listState
        ) {
            items(count = lines.size) { i ->
                val line = lines[i]
                if (line.isEmptyMarker) {
                    Box(Modifier.height(0.dp))
                } else {
                    val isHl = i in highlighted
                    Row(
                        Modifier.widthIn(min = 420.dp)
                            .width((widest * 7.8f + 64).dp.coerceAtLeast(420.dp))
                            .background(if (isHl) P.codeHl else androidx.compose.ui.graphics.Color.Transparent)
                            .padding(horizontal = 8.dp, vertical = 1.dp)
                    ) {
                        Text(
                            (i + 1).toString().padStart(3, ' '),
                            fontSize = 10.sp,
                            color = P.textDim.copy(alpha = 0.45f),
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(30.dp)
                        )
                        Text(
                            line.text.ifEmpty { " " },
                            fontSize = 11.5.sp,
                            color = P.text,
                            fontFamily = FontFamily.Monospace,
                            softWrap = false,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RunPane(state: StudioState, modifier: Modifier = Modifier, beginner: Boolean = false) {
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Badge(
                if (beginner) cn.dumate.a6studio.ui.BeginnerCopy.runStatus(state.runStatus) else state.runStatus,
                cn.dumate.a6studio.ui.components.badgeKindOf(state.runStatusLevel)
            )
            Spacer(Modifier.weight(1f))
            TinyToggle("发送到真机", state.sendToDevice) { state.sendToDevice = it }
        }

        SimPhone(state)

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("运行日志", fontSize = 11.sp, color = P.textDim)
            Spacer(Modifier.weight(1f))
            Text(
                "清空",
                fontSize = 10.5.sp,
                color = P.textDim,
                modifier = Modifier.clickable { state.clearRunLogs() }.padding(4.dp)
            )
        }
        LogList(state.runLogs, if (state.sendToDevice) state.deviceLog else state.runLogs, Modifier.weight(1f), beginner = beginner)
    }
}

@Composable
private fun SimPhone(state: StudioState) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 300.dp).fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(P.panelAlt)
                .border(1.dp, P.border, RoundedCornerShape(16.dp))
                .padding(6.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    state.simView?.packageName ?: "com.jingdong.app.mall",
                    fontSize = 9.sp, color = P.textDim, maxLines = 1
                )
                Spacer(Modifier.weight(1f))
                Text("09:41", fontSize = 9.sp, color = P.textDim)
            }
            Column(
                Modifier.fillMaxWidth().height(300.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(P.panel)
                    .border(1.dp, P.borderSoft, RoundedCornerShape(10.dp))
                    .padding(6.dp)
            ) {
                if (state.simNodes.isEmpty()) {
                    Text("（未运行）", fontSize = 11.sp, color = P.textDim, modifier = Modifier.padding(12.dp))
                }
                state.simNodes.forEach { node ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (node.clickable) P.accent.copy(alpha = 0.10f) else androidx.compose.ui.graphics.Color.Transparent)
                            .padding(horizontal = 6.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            node.text.ifEmpty { "✕" },
                            fontSize = 11.5.sp,
                            color = if (node.clickable) P.text else P.textDim
                        )
                        if (node.desc.isNotEmpty()) {
                            Spacer(Modifier.width(5.dp))
                            Text(node.desc, fontSize = 9.sp, color = P.textDim, maxLines = 1)
                        }
                    }
                }
                state.simBubble?.let {
                    Text(
                        it,
                        fontSize = 9.5.sp,
                        color = P.text,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(P.warn.copy(alpha = 0.18f))
                            .padding(5.dp)
                    )
                }
            }
            Text(
                "模拟设备 · 由积木脚本驱动",
                fontSize = 8.5.sp,
                color = P.textDim.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp)
            )
        }
    }
}

@Composable
fun LogList(
    logs: List<cn.dumate.a6studio.runtime.LogEntry>,
    alt: List<cn.dumate.a6studio.runtime.LogEntry>,
    modifier: Modifier = Modifier,
    beginner: Boolean = false
) {
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }
    // LazyColumn 不写 fillMaxWidth 时会按内容宽度收缩，空日志时「暂无日志」就变成一块
    // 贴着左边的窄方块，右边露出面板底色。
    LazyColumn(modifier.fillMaxWidth().background(P.codeBg).padding(horizontal = 8.dp), state = listState) {
        if (logs.isEmpty()) {
            item { EmptyHint("暂无日志") }
        }
        items(count = logs.size) { i ->
            val l = logs[i]
            val color = when (l.level) {
                "error" -> P.err
                "warn" -> P.warn
                "ok" -> P.ok
                "debug" -> P.textDim
                else -> P.text
            }
            Row(Modifier.padding(vertical = 1.dp)) {
                if (l.t > 0) {
                    Text("[${l.t}]", fontSize = 9.5.sp, color = P.textDim.copy(alpha = 0.6f), fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    if (beginner) cn.dumate.a6studio.ui.BeginnerCopy.logLine(l.msg)
                    else l.msg,
                    fontSize = 11.sp, color = color, fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
fun TinyToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(4.dp))
            .clickable { onChange(!checked) }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (checked) "☑" else "☐", fontSize = 10.sp, color = if (checked) P.accent else P.textDim)
        Spacer(Modifier.width(3.dp))
        Text(label, fontSize = 10.5.sp, color = P.textDim)
    }
}