package cn.dumate.a6studio.ui.fields

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.dumate.a6studio.ui.Palette as P

/** 追踪当前是否有文本输入框获得焦点，供全局快捷键避让 Delete / Ctrl+Z。 */
class TextFocusTracker {
    private val active = mutableSetOf<Any>()
    val hasFocus: Boolean get() = active.isNotEmpty()
    fun setFocused(token: Any, focused: Boolean) {
        if (focused) active += token else active -= token
    }
}

val LocalTextFocusTracker = staticCompositionLocalOf<TextFocusTracker?> { null }

/** 将文本输入框的聚焦生命周期注册到 [LocalTextFocusTracker]，组件销毁时自动清理。 */
@Composable
fun Modifier.trackTextFocus(): Modifier {
    val tracker = LocalTextFocusTracker.current ?: return this
    val token = remember { Any() }
    DisposableEffect(tracker, token) {
        onDispose { tracker.setFocused(token, false) }
    }
    return this.onFocusChanged { tracker.setFocused(token, it.isFocused) }
}

/**
 * Compact text field used everywhere in the studio (block params, names, filters).
 * Deliberately minimal: no labels, no floating placeholder styling — the web build's `input.pin`.
 */
@Composable
fun InlineField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    mono: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    /**
     * Called once when the field loses focus. Parameter edits coalesce into one undo point per
     * field, so this is what ends a run of keystrokes when the user taps somewhere else.
     */
    onEditingFinished: (() -> Unit)? = null
) {
    // `wrapContentHeight` keeps the field one line tall even when its parent row is stretched
    // by a taller sibling (measured on device: the project-name field grew to 129px).
    Box(
        modifier
            .wrapContentHeight(Alignment.CenterVertically)
            .clip(RoundedCornerShape(5.dp))
            .background(P.codeBg)
            .border(1.dp, P.border, RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) {
            Text(placeholder, fontSize = 11.5.sp, color = P.textDim.copy(alpha = 0.7f), maxLines = 1)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = minLines,
            // A long imported script must not turn one card into a full screen: cap the height
            // and let the field scroll internally (BasicTextField scrolls when maxLines is hit).
            maxLines = maxLines,
            visualTransformation = visualTransformation,
            textStyle = TextStyle(
                fontSize = 11.5.sp,
                color = P.text,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default
            ),
            cursorBrush = SolidColor(P.accent),
            modifier = Modifier.fillMaxWidth().trackTextFocus().then(
                if (onEditingFinished == null) Modifier
                else Modifier.onFocusChanged { if (!it.isFocused) onEditingFinished() }
            )
        )
    }
}

/** Search box with a leading icon. Pass [focusRequester] to let keyboard shortcuts focus it. */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null
) {
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(P.panelAlt)
            .border(1.dp, P.border, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("⌕", fontSize = 12.sp, color = P.textDim)
        Box(Modifier.width(6.dp).size(0.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(placeholder, fontSize = 11.5.sp, color = P.textDim.copy(alpha = 0.75f), maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 11.5.sp, color = P.text),
                cursorBrush = SolidColor(P.accent),
                modifier = Modifier.fillMaxWidth().trackTextFocus().let { m ->
                    if (focusRequester != null) m.focusRequester(focusRequester) else m
                }
            )
        }
    }
}

/**
 * Compact dropdown: a read-only "select" box that opens a menu of [items]. Used on the 真机 page
 * so every available MCP tool and every enum/choice parameter shows as a pick-list instead of a
 * free-text input — no more guessing tool names or exact option strings.
 *
 * [items] are the raw values (e.g. tool names, enum members). [labelOf] turns one value into the
 * text shown in the closed box (defaults to the value itself); [placeholder] is shown when
 * [value] is blank. [modifier] is applied to the trigger row so the caller controls width.
 * With [searchable] the open menu shows a filter box on top, so long lists (40+ tools) stay usable.
 *
 * Grouping: when [groupOf] is provided, the open menu renders a header per group (in the order the
 * items first appear). Values for which [groupOf] returns null are shown without any header.
 * [groupLabelOf] turns a group key into the header text.
 */
@Composable
fun DropdownField(
    value: String,
    onSelect: (String) -> Unit,
    items: List<String>,
    modifier: Modifier = Modifier,
    placeholder: String = "选择…",
    labelOf: (String) -> String = { it },
    searchable: Boolean = false,
    searchPlaceholder: String = "搜索…",
    groupOf: ((String) -> String?)? = null,
    groupLabelOf: (String) -> String = { it }
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val visible = if (!searchable) items else {
        val q = query.trim().lowercase()
        if (q.isEmpty()) items else items.filter { labelOf(it).lowercase().contains(q) || it.lowercase().contains(q) }
    }
    val current = items.firstOrNull { it == value }
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(5.dp))
                .background(P.codeBg)
                .border(1.dp, P.border, RoundedCornerShape(5.dp))
                .clickable { if (items.isNotEmpty()) expanded = true }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (current != null) labelOf(current)
                else if (value.isNotEmpty()) labelOf(value)
                else placeholder,
                fontSize = 11.5.sp,
                color = if (current != null || value.isNotEmpty()) P.text else P.textDim.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(" ▾", fontSize = 9.sp, color = P.textDim)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Column(Modifier.widthIn(min = 190.dp)) {
                if (searchable) {
                    InlineField(
                        query,
                        { query = it },
                        placeholder = searchPlaceholder,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                if (visible.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("无匹配项", fontSize = 11.5.sp, color = P.textDim) },
                        onClick = {}
                    )
                } else {
                    // 分组：保持 items 的首次出现顺序；groupOf 返回 null 的项归到无标题的 "" 组。
                    val grouped = LinkedHashMap<String, MutableList<String>>()
                    if (groupOf == null) {
                        grouped[""] = visible.toMutableList()
                    } else {
                        visible.forEach { item ->
                            grouped.getOrPut(groupOf(item) ?: "") { mutableListOf() }.add(item)
                        }
                    }
                    grouped.forEach { (g, members) ->
                        if (g.isNotEmpty()) {
                            Text(
                                groupLabelOf(g),
                                fontSize = 9.sp,
                                color = P.textDim.copy(alpha = 0.75f),
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                            )
                        }
                        members.forEach { item ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        labelOf(item),
                                        fontSize = 11.5.sp,
                                        color = if (item == value) P.accent else P.text,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                onClick = {
                                    expanded = false
                                    query = ""
                                    onSelect(item)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 可编辑下拉：既能点开菜单选预置项，也能直接手输自定义值——「免手输」的同时不堵死输入。
 * 用于工具参数表单：enum 给默认项，string/number/boolean 给类型化预置 + 历史值。
 * 结构：文本输入框（当前值可改）+ 右侧 ▾ 按钮（点击就地展开 [items] 菜单，选中回填输入框）。
 *
 * 注意：不用 Popup 弹层（DropdownMenu），因为本组件常被放在 verticalScroll 容器里，
 * 滚动容器内的 Popup 锚点定位会失效（真机实测点 ▾ 无反应）。改为就地展开列，绝对可靠。
 */
@Composable
fun EditableDropdownField(
    value: String,
    onValueChange: (String) -> Unit,
    items: List<String>,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    labelOf: (String) -> String = { it },
    mono: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(5.dp))
                .background(P.codeBg)
                .border(1.dp, P.border, RoundedCornerShape(5.dp))
                .padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f)) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(placeholder, fontSize = 11.5.sp, color = P.textDim.copy(alpha = 0.7f), maxLines = 1)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 11.5.sp,
                        color = P.text,
                        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default
                    ),
                    cursorBrush = SolidColor(P.accent),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp).trackTextFocus()
                )
            }
            if (items.isNotEmpty()) {
                // 独立大点击区按钮（约 40dp 高），比纯字符可靠；背景色区分可点
                Box(
                    Modifier
                        .padding(3.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(P.panelAlt)
                        .clickable { expanded = !expanded }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (expanded) "预置 ▴" else "预置 ▾",
                        fontSize = 10.sp,
                        color = P.accent,
                        maxLines = 1
                    )
                }
            }
        }
        if (expanded) {
            Spacer(Modifier.height(2.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(5.dp))
                    .background(P.panelAlt)
                    .border(1.dp, P.border, RoundedCornerShape(5.dp))
                    .padding(vertical = 2.dp)
            ) {
                if (items.isEmpty()) {
                    Text(
                        "暂无预置项，可手输",
                        fontSize = 11.sp, color = P.textDim,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
                items.forEach { item ->
                    Text(
                        labelOf(item).ifEmpty { "（空字符串）" },
                        fontSize = 11.5.sp,
                        color = if (item == value) P.accent else P.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                expanded = false
                                onValueChange(item)
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}