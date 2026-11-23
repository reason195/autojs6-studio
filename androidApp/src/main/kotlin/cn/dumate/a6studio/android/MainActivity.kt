package cn.dumate.a6studio.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.dumate.a6studio.ui.AndroidColors
import cn.dumate.a6studio.ui.AndroidStyle
import cn.dumate.a6studio.ui.Palette
import cn.dumate.a6studio.ui.StudioApp
import cn.dumate.a6studio.ui.StudioColors
import cn.dumate.a6studio.ui.StudioStyle
import cn.dumate.a6studio.ui.StudioTheme

/**
 * Android host. The same Compose UI as desktop; only storage, clipboard and the file pickers
 * differ. Files are exchanged through the Storage Access Framework so no runtime permission
 * is required on any supported API level.
 */
class MainActivity : ComponentActivity() {

    private val vm: StudioViewModel by viewModels()

    /** The Activity is the only thing that can launch a file picker, so it registers itself here. */
    private val safHost = object : SafBridge.Host {
        override fun launchOpen(mimes: Array<String>) { openLauncher.launch(mimes) }
        override fun launchSave(suggestedName: String) { saveLauncher.launch(suggestedName) }
    }

    private val openLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) { SafBridge.onOpenResult(null); return@registerForActivityResult }
        val text = runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        val name = SafBridge.displayName(this, uri) ?: "project.json"
        SafBridge.onOpenResult(if (text != null) name to text else null)
    }

    private val saveLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) { SafBridge.onSaveResult(null); return@registerForActivityResult }
        // Writing happens here (only the Activity has the resolver), but the content itself is
        // kept in SafBridge so a recreated Activity can still finish the save.
        val wrote = SafBridge.writePendingTo(this, uri)
        SafBridge.onSaveResult(if (wrote) SafBridge.displayName(this, uri) else null)
    }

    override fun onDestroy() {
        SafBridge.detach(safHost)
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        SafBridge.attach(safHost)

        // Build a StudioStyle for Android.
        // On Android 12+ (API 31+) pull the system dynamic palette and map it onto StudioColors
        // so the app feels native to the user's wallpaper colour.
        // Below Android 12 use the fixed Material You purple fallback (AndroidColors).
        val androidStyle: StudioStyle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val dyn = dynamicLightColorScheme(this)
            val dynColors = StudioColors(
                bg         = dyn.background,
                panel      = dyn.surface,
                panelAlt   = dyn.surfaceVariant,
                border     = dyn.outline,
                borderSoft = dyn.outlineVariant,
                text       = dyn.onSurface,
                textDim    = dyn.onSurfaceVariant,
                accent     = dyn.primary,
                onAccent   = dyn.onPrimary,
                ok         = AndroidColors.ok,
                warn       = AndroidColors.warn,
                err        = AndroidColors.err,
                info       = AndroidColors.info,
                codeBg     = dyn.surfaceContainer,
                codeHl     = dyn.primaryContainer,
            )
            AndroidStyle.copy(colors = dynColors)
        } else {
            AndroidStyle
        }

        setContent {
            // State lives in the ViewModel: rotation / dark-mode / font-size changes keep the
            // project, undo stack, tokens, a running simulation and an open panel.
            val state = vm.state
            StudioTheme(style = androidStyle) {
                Surface(Modifier.fillMaxSize(), color = Palette.bg) {
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(0.dp)) {
                        StudioApp(state)
                    }
                }
            }
        }
    }
}
