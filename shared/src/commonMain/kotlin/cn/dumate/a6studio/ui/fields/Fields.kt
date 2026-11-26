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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.dumate.a6studio.ui.Palette as P

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
    minLines: Int = 1
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
            textStyle = TextStyle(
                fontSize = 11.5.sp,
                color = P.text,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default
            ),
            cursorBrush = SolidColor(P.accent),
            modifier = Modifier.fillMaxWidth()
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
                modifier = Modifier.fillMaxWidth().let { m ->
                    if (focusRequester != null) m.focusRequester(focusRequester) else m
                }
            )
        }
    }
}