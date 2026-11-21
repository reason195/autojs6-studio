package cn.dumate.a6studio.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable

/**
 * Keyboard shortcuts matching the web build: F5 run, F6 tests, F7 diagnose, F8 audit,
 * F9 recorder, Ctrl+S save, Ctrl+Z undo, Ctrl+E export, Delete removes the selected block.
 */
@Composable
fun Modifier.studioShortcuts(state: StudioState): Modifier {
    return this
        .focusable()
        .onKeyEvent { ev ->
            if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
            val ctrl = ev.isCtrlPressed || ev.isMetaPressed
            when {
                ev.key == Key.F5 -> { state.run(); true }
                ev.key == Key.F6 -> { state.runTests(); true }
                ev.key == Key.F7 -> { state.showPane(Pane.DIAG); true }
                ev.key == Key.F8 -> { state.openAudit(); true }
                ev.key == Key.F9 -> { state.openRecorder(); true }
                ctrl && ev.key == Key.S -> { state.saveNow(); true }
                ctrl && ev.key == Key.Z -> { state.undo(); true }
                ctrl && ev.key == Key.E -> { state.exportJs(); true }
                ev.key == Key.Escape -> { state.closeOverlay(); true }
                ev.key == Key.Delete -> {
                    if (state.repo.selected.value != null) { state.removeSelected(); true } else false
                }
                else -> false
            }
        }
}

/** Requests focus once so shortcuts work without clicking first. */
@Composable
fun Modifier.requestStudioFocus() = this