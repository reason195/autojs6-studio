package cn.dumate.a6studio.platform

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection

/** Keeps content clear of the status bar and gesture bar on an edge-to-edge Activity. */
@Composable
actual fun Modifier.systemBarPadding(): Modifier = this.windowInsetsPadding(WindowInsets.systemBars)

/**
 * Real system-bar sizes in pixels. `PaddingValues` is expressed in dp, so it is converted with
 * the current [LocalDensity].
 */
@Composable
actual fun safeAreaInsets(): SafeAreaInsets {
    val p = WindowInsets.systemBars.asPaddingValues()
    val d = LocalDensity.current
    return with(d) {
        SafeAreaInsets(
            left = p.calculateLeftPadding(LayoutDirection.Ltr).roundToPx(),
            top = p.calculateTopPadding().roundToPx(),
            right = p.calculateRightPadding(LayoutDirection.Ltr).roundToPx(),
            bottom = p.calculateBottomPadding().roundToPx()
        )
    }
}