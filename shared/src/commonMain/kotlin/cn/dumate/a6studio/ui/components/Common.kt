package cn.dumate.a6studio.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.dumate.a6studio.ui.Palette

enum class BadgeKind { IDLE, RUN, OK, WARN, ERR }

@Composable
fun Badge(text: String, kind: BadgeKind = BadgeKind.IDLE, modifier: Modifier = Modifier) {
    val color = when (kind) {
        BadgeKind.IDLE -> Palette.textDim
        BadgeKind.RUN -> Palette.accent
        BadgeKind.OK -> Palette.ok
        BadgeKind.WARN -> Palette.warn
        BadgeKind.ERR -> Palette.err
    }
    Text(
        text = text,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.13f))
            .border(BorderStroke(1.dp, color.copy(alpha = 0.35f)), RoundedCornerShape(4.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    )
}

fun badgeKindOf(level: String): BadgeKind = when (level) {
    "ok" -> BadgeKind.OK
    "warn" -> BadgeKind.WARN
    "err" -> BadgeKind.ERR
    "run" -> BadgeKind.RUN
    else -> BadgeKind.IDLE
}

@Composable
fun Chip(text: String, color: Color = Palette.textDim, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = color,
        fontSize = 10.sp,
        modifier = modifier
            .clip(RoundedCornerShape(3.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text, fontSize = 11.5.sp, color = Palette.textDim, fontWeight = FontWeight.Medium)
        trailing?.invoke()
    }
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Palette.textDim,
        fontSize = 11.5.sp,
        modifier = modifier.padding(vertical = 14.dp, horizontal = 4.dp)
    )
}

@Composable
fun Divider(modifier: Modifier = Modifier, color: Color = Palette.borderSoft) {
    androidx.compose.foundation.layout.Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

@Composable
fun KV(key: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Text(key, color = Palette.textDim, fontSize = 11.5.sp, modifier = Modifier.widthIn(min = 84.dp))
        Text(value, color = Palette.text, fontSize = 11.5.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
fun ClickableRow(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().clickable(onClick = onClick)) { content() }
}

/**
 * Hover state for clickable surfaces (desktop mouse users). Returns a [Modifier] that reports
 * hover (via `Modifier.hoverable`) and whether the pointer is currently over the surface.
 * On phone/touch this simply never becomes true, so call sites can use it unconditionally.
 */
@Composable
fun rememberHovered(): Pair<Modifier, Boolean> {
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    return Modifier.hoverable(source) to hovered
}
