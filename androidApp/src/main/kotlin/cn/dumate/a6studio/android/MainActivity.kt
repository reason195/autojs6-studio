package cn.dumate.a6studio.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

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

    private val notifPermLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { /* 拒绝也不影响前台服务启动，只是通知不显示 */ }

    private val openLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) { SafBridge.onOpenResult(null); return@registerForActivityResult }
        val appCtx = applicationContext
        vm.state.scope.launch(Dispatchers.IO) {
            val text = runCatching {
                appCtx.contentResolver.openInputStream(uri)?.use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > MAX_OPEN_BYTES) throw java.io.IOException("文件超过 10MB 上限")
                        out.write(buf, 0, n)
                    }
                    out.toString(Charsets.UTF_8.name())
                }
            }.getOrNull()
            val name = SafBridge.displayName(appCtx, uri) ?: "project.json"
            withContext(Dispatchers.Main) {
                SafBridge.onOpenResult(if (text != null) name to text else null)
            }
        }
    }

    private val saveLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) { SafBridge.onSaveResult(null); return@registerForActivityResult }
        // Writing happens off the main thread using applicationContext so rotation cannot drop it.
        val appCtx = applicationContext
        vm.state.scope.launch(Dispatchers.IO) {
            val wrote = SafBridge.writePendingTo(appCtx, uri)
            val displayName = if (wrote) SafBridge.displayName(appCtx, uri) else null
            withContext(Dispatchers.Main) {
                SafBridge.onSaveResult(displayName)
            }
        }
    }

    private companion object {
        const val MAX_OPEN_BYTES = 10 * 1024 * 1024
    }

    override fun onStart() {
        super.onStart()
        // 回到前台后不需要保活，交给 StudioState 自己按采集状态再启停
        KeepAliveService.stop(this)
    }

    override fun onStop() {
        // 正在采集/授权倒计时时离开 Studio（切去目标 App），立刻挂前台服务，避免进程被系统回收
        if (vm.state.autoBusy) KeepAliveService.start(this)
        super.onStop()
    }

    override fun onDestroy() {
        SafBridge.detach(safHost)
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().imePadding().padding(0.dp)) {
                        StudioApp(state)
                    }
                }
            }
        }
    }
}
