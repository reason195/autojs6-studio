package cn.dumate.a6studio.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Palette carried over from the web build's dark theme. */
object Palette {
    val bg = Color(0xFF0F1116)
    val panel = Color(0xFF151922)
    val panelAlt = Color(0xFF1B2029)
    val border = Color(0xFF262D3A)
    val borderSoft = Color(0xFF1F2531)
    val text = Color(0xFFE6EAF2)
    val textDim = Color(0xFF8A93A6)
    val accent = Color(0xFF4F8CFF)
    val ok = Color(0xFF3ECF8E)
    val warn = Color(0xFFE3B341)
    val err = Color(0xFFF16B6B)
    val info = Color(0xFF6B9BD1)
    val codeBg = Color(0xFF0B0E13)
    val codeHl = Color(0xFF1D2A44)
}

private val scheme = darkColorScheme(
    primary = Palette.accent,
    onPrimary = Color.White,
    secondary = Palette.ok,
    background = Palette.bg,
    onBackground = Palette.text,
    surface = Palette.panel,
    onSurface = Palette.text,
    surfaceVariant = Palette.panelAlt,
    onSurfaceVariant = Palette.textDim,
    outline = Palette.border,
    error = Palette.err
)

private val typography = Typography(
    bodyLarge = TextStyle(fontSize = 13.sp, color = Palette.text),
    bodyMedium = TextStyle(fontSize = 12.5.sp, color = Palette.text),
    bodySmall = TextStyle(fontSize = 11.5.sp, color = Palette.textDim),
    labelLarge = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Palette.text),
    labelSmall = TextStyle(fontSize = 10.5.sp, color = Palette.textDim),
    titleMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Palette.text),
    titleSmall = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = Palette.text)
)

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Palette.text)

@Composable
fun StudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}