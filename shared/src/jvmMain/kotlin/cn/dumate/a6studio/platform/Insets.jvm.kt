package cn.dumate.a6studio.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Desktop windows are decorated by the OS, so content is already clear of system UI. */
@Composable
actual fun Modifier.systemBarPadding(): Modifier = this

@Composable
actual fun safeAreaInsets(): SafeAreaInsets = SafeAreaInsets(0, 0, 0, 0)
