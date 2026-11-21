package cn.dumate.a6studio.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Padding that keeps content clear of system UI (status bar, gesture bar, notch).
 *
 * Android draws edge-to-edge, so without this the status-bar clock and battery icons sit on top
 * of the top bar — measured on a OnePlus CPH2581. Desktop windows manage their own decorations,
 * so the default is a no-op.
 */
@Composable
expect fun Modifier.systemBarPadding(): Modifier

/** Safe-area insets, for surfaces that need the raw values (e.g. bottom sheets). */
@Composable
expect fun safeAreaInsets(): SafeAreaInsets

data class SafeAreaInsets(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)