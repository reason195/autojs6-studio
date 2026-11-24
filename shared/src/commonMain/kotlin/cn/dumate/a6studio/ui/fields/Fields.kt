package cn.dumate.a6studio.ui.fields

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.VisualTransformation
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

/** Search box with a leading icon. */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
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
                modifier = Modifier.fillMaxWidth().trackTextFocus()
            )
        }
    }
}