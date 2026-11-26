package cn.dumate.a6studio.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.dumate.a6studio.model.AndroidProjectStorage
import cn.dumate.a6studio.platform.Platform
import cn.dumate.a6studio.runtime.RhinoRunEngine
import cn.dumate.a6studio.ui.MobileSheet
import cn.dumate.a6studio.ui.Overlay
import cn.dumate.a6studio.ui.Palette
import cn.dumate.a6studio.ui.StudioApp
import cn.dumate.a6studio.ui.StudioState
import cn.dumate.a6studio.ui.StudioTheme
import io.ktor.client.engine.okhttp.OkHttp

/**
 * Android host. The same Compose UI as desktop; only storage, clipboard and the file pickers
 * differ. Files are exchanged through the Storage Access Framework so no runtime permission
 * is required on any supported API level.
 */
class MainActivity : ComponentActivity() {

    private var pendingOpen: ((Pair<String, String>?) -> Unit)? = null
    private var pendingSave: ((String?) -> Unit)? = null

    private val openLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        val cb = pendingOpen
        pendingOpen = null
        if (uri == null || cb == null) { cb?.invoke(null); return@registerForActivityResult }
        val text = runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        val name = queryDisplayName(uri) ?: "project.json"
        cb(if (text != null) name to text else null)
    }

    private val saveLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val cb = pendingSave
        pendingSave = null
        if (uri != null && pendingSaveData != null) {
            runCatching {
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(pendingSaveData) }
            }
        }
        cb?.invoke(uri?.let { queryDisplayName(it) })
        pendingSaveData = null
    }

    private var pendingSaveData: String? = null

    private fun queryDisplayName(uri: android.net.Uri): String? = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()

    inner class AndroidPlatform(private val ctx: Context) : Platform {
        override val name = "android"
        override val supportsFileDialogs = true

        override fun readTextFile(path: String): String? =
            runCatching { java.io.File(ctx.filesDir, path).readText() }.getOrNull()

        override fun writeTextFile(path: String, content: String): Boolean = runCatching {
            val f = java.io.File(ctx.filesDir, path)
            f.parentFile?.mkdirs()
            f.writeText(content)
            true
        }.getOrDefault(false)

        override suspend fun saveTextAs(suggestedName: String, content: String, mimeType: String): String? {
            return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                pendingSaveData = content
                pendingSave = { name -> if (cont.isActive) cont.resume(name) { } }
                saveLauncher.launch(suggestedName)
            }
        }

        override suspend fun openTextFile(extensions: List<String>): Pair<String, String>? {
            return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                pendingOpen = { result -> if (cont.isActive) cont.resume(result) { } }
                val mimes = if (extensions.contains("json")) arrayOf("application/json", "text/plain", "*/*")
                else arrayOf("text/plain", "*/*")
                openLauncher.launch(mimes)
            }
        }

        override fun copyToClipboard(text: String) {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("AutoJs6 Studio", text))
        }

        override fun setWindowTitle(title: String) { }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val platform = AndroidPlatform(this)
        setContent {
            val state = remember {
                StudioState(
                    platform = platform,
                    storage = AndroidProjectStorage(applicationContext),
                    engineFactory = OkHttp,
                    engine = RhinoRunEngine
                ).also { it.init() }
            }
            // System back closes transient UI (overlay → guide → templates → sheet) instead of
            // dropping the user straight out of the app.
            val backHandled = state.overlay != Overlay.NONE || state.showGuide ||
                state.showTemplatePicker || state.mobileSheet != MobileSheet.CANVAS
            BackHandler(enabled = backHandled) {
                when {
                    state.overlay != Overlay.NONE -> state.closeOverlay()
                    state.showGuide -> state.dismissGuide()
                    state.showTemplatePicker -> state.closeTemplates()
                    else -> state.mobileSheet = MobileSheet.CANVAS
                }
            }
            StudioTheme {
                Surface(Modifier.fillMaxSize(), color = Palette.bg) {
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(0.dp)) {
                        StudioApp(state)
                    }
                }
            }
        }
    }
}