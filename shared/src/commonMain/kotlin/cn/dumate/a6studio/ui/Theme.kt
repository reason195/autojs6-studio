package cn.dumate.a6studio.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ─────────────────────────────────────────────────────────────────
// Colour set — platform-agnostic data class so both desktop (C) and
// Android (D) can supply their own values, and the Palette object
// (which all call sites reference as Palette.xxx or P.xxx) simply
// delegates to whichever instance is current.
// ─────────────────────────────────────────────────────────────────

data class StudioColors(
    val bg: Color,
    val panel: Color,
    val panelAlt: Color,
    val border: Color,
    val borderSoft: Color,
    val text: Color,
    val textDim: Color,
    val accent: Color,
    /** Text / icon on top of accent-coloured backgrounds (BrandMark, RunButton, tabs). */
    val onAccent: Color,
    val ok: Color,
    val warn: Color,
    val err: Color,
    val info: Color,
    val codeBg: Color,
    val codeHl: Color,
    /** Hover fill for clickable rows/buttons (desktop mouse); a shade between panelAlt and border. */
    val hover: Color,
)

/** Desktop C – bright workbench (Figma / Notion vibe), slightly deeper for contrast. */
val DesktopColors = StudioColors(
    bg         = Color(0xFFE9ECF0),
    panel      = Color(0xFFF7F8FA),
    panelAlt   = Color(0xFFE3E7EC),
    border     = Color(0xFFCDD3DB),
    borderSoft = Color(0xFFDCE1E7),
    text       = Color(0xFF161A1F),
    textDim    = Color(0xFF5B6472),
    accent     = Color(0xFF1D4ED8),
    onAccent   = Color.White,
    ok         = Color(0xFF15803D),
    warn       = Color(0xFFB45309),
    err        = Color(0xFFDC2626),
    info       = Color(0xFF0E7490),
    codeBg     = Color(0xFFEFF2F5),
    codeHl     = Color(0xFFC7DBFB),
    hover      = Color(0xFFDCE2EA),
)

/** Android D – Material You fixed palette (Android < 12 fallback; 12+ uses dynamic colours). */
val AndroidColors = StudioColors(
    bg         = Color(0xFFFEF7FF),
    panel      = Color(0xFFF7F2FA),
    panelAlt   = Color(0xFFECE6F0),
    border     = Color(0xFFE7E0EC),
    borderSoft = Color(0xFFF0EAF4),
    text       = Color(0xFF1D1B20),
    textDim    = Color(0xFF625B71),
    accent     = Color(0xFF6750A4),
    onAccent   = Color.White,
    ok         = Color(0xFF386A20),
    warn       = Color(0xFF7D5700),
    err        = Color(0xFFB3261E),
    info       = Color(0xFF006874),
    codeBg     = Color(0xFFF3EDF7),
    codeHl     = Color(0xFFEADDFF),
    hover      = Color(0xFFE4DDE9),
)

// ─────────────────────────────────────────────────────────────────
// StudioStyle — colours + shape + feature flags passed into StudioTheme.
// ─────────────────────────────────────────────────────────────────

data class StudioStyle(
    val colors: StudioColors,
    /** Larger corner radius for cards, overlays. */
    val shapeLg: Dp = 10.dp,
    /** Smaller corner radius for buttons, chips, fields. */
    val shapeSm: Dp = 6.dp,
    /** Whether to draw a subtle dot-grid on the canvas background. */
    val canvasDots: Boolean = false,
    /** Dot colour for the canvas grid (meaningful only if canvasDots=true). */
    val canvasDotsColor: Color = Color(0xFFBCC3CD),
    /**
     * Uniform font scale multiplier applied via [LocalDensity] in [StudioTheme].
     * 1.08 on desktop gives slightly larger text on HiDPI screens without touching sp values.
     * 1.0 on Android to leave the system font scale unchanged.
     */
    val fontScale: Float = 1.0f,
)

val DesktopStyle = StudioStyle(
    colors      = DesktopColors,
    shapeLg     = 10.dp,
    shapeSm     = 6.dp,
    canvasDots  = true,
    canvasDotsColor = Color(0xFFBCC3CD),
    fontScale   = 1.08f,
)

val AndroidStyle = StudioStyle(
    colors      = AndroidColors,
    shapeLg     = 20.dp,
    shapeSm     = 14.dp,
    canvasDots  = false,
    fontScale   = 1.0f,
)

// ─────────────────────────────────────────────────────────────────
// Palette – thin singleton that every call-site addresses as
// Palette.xxx or (aliased) P.xxx.  All properties just delegate to
// the internal mutable state so a single Compose re-composition
// propagates the new colours everywhere without changing any
// import or reference in the codebase.
// ─────────────────────────────────────────────────────────────────

object Palette {
    private var _colors: StudioColors by mutableStateOf(DesktopColors)

    /** Called once per platform before the first composition. */
    fun applyStyle(style: StudioStyle) {
        _colors = style.colors
    }

    val bg         get() = _colors.bg
    val panel      get() = _colors.panel
    val panelAlt   get() = _colors.panelAlt
    val border     get() = _colors.border
    val borderSoft get() = _colors.borderSoft
    val text       get() = _colors.text
    val textDim    get() = _colors.textDim
    val accent     get() = _colors.accent
    val onAccent   get() = _colors.onAccent
    val ok         get() = _colors.ok
    val warn       get() = _colors.warn
    val err        get() = _colors.err
    val info       get() = _colors.info
    val codeBg     get() = _colors.codeBg
    val codeHl     get() = _colors.codeHl
    val hover      get() = _colors.hover
}

// ─────────────────────────────────────────────────────────────────
// StudioStyle ambient — lets CanvasPanel read shapeSm/shapeLg and
// canvasDots without threading the value through every composable.
// ─────────────────────────────────────────────────────────────────

private var _currentStyle: StudioStyle by mutableStateOf(DesktopStyle)
val currentStudioStyle: StudioStyle get() = _currentStyle

// ─────────────────────────────────────────────────────────────────
// MonoStyle – @Composable getter so it always reads current Palette.
// ─────────────────────────────────────────────────────────────────

val MonoStyle: TextStyle
    @Composable get() = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        color = Palette.text
    )

// ─────────────────────────────────────────────────────────────────
// Theme entry-point.
// ─────────────────────────────────────────────────────────────────

@Composable
fun StudioTheme(style: StudioStyle = DesktopStyle, content: @Composable () -> Unit) {
    // Push colours into the Palette singleton so every Palette.xxx reference updates.
    Palette.applyStyle(style)
    _currentStyle = style

    val c = style.colors
    val scheme = lightColorScheme(
        primary             = c.accent,
        onPrimary           = c.onAccent,
        primaryContainer    = c.codeHl,
        onPrimaryContainer  = c.text,
        secondary           = c.ok,
        onSecondary         = c.onAccent,
        background          = c.bg,
        onBackground        = c.text,
        surface             = c.panel,
        onSurface           = c.text,
        surfaceVariant      = c.panelAlt,
        onSurfaceVariant    = c.textDim,
        outline             = c.border,
        outlineVariant      = c.borderSoft,
        error               = c.err,
        onError             = c.onAccent,
    )

    // Typography without hard-coded colours — colours come from MaterialTheme/LocalContentColor.
    val typography = Typography(
        bodyLarge   = TextStyle(fontSize = 13.sp),
        bodyMedium  = TextStyle(fontSize = 12.5.sp),
        bodySmall   = TextStyle(fontSize = 11.5.sp),
        labelLarge  = TextStyle(fontSize = 12.sp,  fontWeight = FontWeight.Medium),
        labelSmall  = TextStyle(fontSize = 10.5.sp),
        titleMedium = TextStyle(fontSize = 14.sp,  fontWeight = FontWeight.SemiBold),
        titleSmall  = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.Medium),
    )

    val sm = style.shapeSm
    val lg = style.shapeLg
    val shapes = Shapes(
        extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(sm),
        small      = androidx.compose.foundation.shape.RoundedCornerShape(sm),
        medium     = androidx.compose.foundation.shape.RoundedCornerShape(lg),
        large      = androidx.compose.foundation.shape.RoundedCornerShape(lg),
        extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(lg),
    )

    // Apply fontScale by overriding LocalDensity. This scales sp values uniformly without
    // touching dp layout, so HiDPI screens get slightly larger text while layout proportions
    // stay identical.  fontScale=1.0 is a no-op (Android, default).
    val baseDensity = androidx.compose.ui.platform.LocalDensity.current
    val scaledDensity = androidx.compose.ui.unit.Density(
        density   = baseDensity.density,
        fontScale = baseDensity.fontScale * style.fontScale
    )

    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides scaledDensity
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
    }
}
