package cn.dumate.a6studio.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import cn.dumate.a6studio.model.DesktopProjectStorage
import cn.dumate.a6studio.platform.Platform
import cn.dumate.a6studio.runtime.RhinoRunEngine
import cn.dumate.a6studio.ui.StudioApp
import cn.dumate.a6studio.ui.StudioState
import cn.dumate.a6studio.ui.StudioTheme
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.delay
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.imageio.ImageIO

/**
 * Desktop host: one Compose window, native file dialogs (AWT, no extra dependency), and the
 * Rhino-backed simulator so the exact same UI runs on Windows / macOS / Linux.
 */
private class DesktopPlatform : Platform {
    override val name = "desktop"
    override val supportsFileDialogs = true

    /** App-private directory for markers, remembered tokens and the project file. */
    private val configDir: File
        get() = File(System.getProperty("user.home"), ".a6studio")

    /**
     * Resolves a storage path. A relative name lives under [configDir] and is **confined** to it:
     * every caller passes an internal constant today, but this is the API other code will use and
     * `../` in a name must not escape into the user's home.
     */
    private fun resolve(path: String): File? {
        if (File(path).isAbsolute) return File(path)
        val base = configDir.canonicalFile
        val target = File(configDir, path).canonicalFile
        val inside = target.path == base.path || target.path.startsWith(base.path + File.separator)
        return if (inside) target else null
    }

    override fun readTextFile(path: String): String? =
        resolve(path)?.let { runCatching { it.readText() }.getOrNull() }

    override fun writeTextFile(path: String, content: String): Boolean = runCatching {
        val f = resolve(path) ?: return false
        f.parentFile?.mkdirs()
        f.writeText(content)
        // The remembered tokens are credentials: keep them owner-only. Windows has no POSIX
        // mode (the call is a no-op there; %USERPROFILE% is already per-user).
        if (f.name.startsWith("a6studio.tokens")) {
            runCatching {
                java.nio.file.Files.setPosixFilePermissions(
                    f.toPath(),
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
                )
            }
        }
        true
    }.getOrDefault(false)

    override suspend fun saveTextAs(suggestedName: String, content: String, mimeType: String): String? {
        val dialog = FileDialog(null as Frame?, "保存", FileDialog.SAVE).apply {
            file = suggestedName
            isVisible = true
        }
        val dir = dialog.directory ?: return null
        val name = dialog.file ?: return null
        val target = File(dir, name)
        return runCatching { target.writeText(content); target.name }.getOrNull()
    }

    override suspend fun openTextFile(extensions: List<String>): Pair<String, String>? {
        val dialog = FileDialog(null as Frame?, "打开", FileDialog.LOAD).apply { isVisible = true }
        val dir = dialog.directory ?: return null
        val name = dialog.file ?: return null
        val file = File(dir, name)
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        return file.name to text
    }

    override fun copyToClipboard(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    override fun setWindowTitle(title: String) { }
}

fun main() = application {
    val platform = remember { DesktopPlatform() }
    val state = remember {
        StudioState(
            platform = platform,
            storage = DesktopProjectStorage(platform),
            engineFactory = CIO,
            engine = RhinoRunEngine
        ).also { it.init() }
    }

    val widthDp = (System.getenv("A6STUDIO_WIDTH") ?: "1440").toIntOrNull()?.dp ?: 1440.dp
    val heightDp = (System.getenv("A6STUDIO_HEIGHT") ?: "920").toIntOrNull()?.dp ?: 920.dp
    val windowState = rememberWindowState(width = widthDp, height = heightDp)

    Window(
        onCloseRequest = {
            // Release the coroutine scope and connection pool before the process exits.
            state.dispose()
            exitApplication()
        },
        title = "AutoJs6 Studio",
        state = windowState
    ) {
        if (System.getenv("A6STUDIO_WIDTH") == null) {
            window.minimumSize = java.awt.Dimension(1024, 680)
        } else {
            window.minimumSize = java.awt.Dimension(360, 640)
        }

        val shotPath = System.getProperty("a6studio.screenshot") ?: System.getenv("A6STUDIO_SCREENSHOT")
        // Optional pane/action chosen by the smoke test, so one run can cover every screen.
        val shotPane = System.getProperty("a6studio.screenshotPane") ?: System.getenv("A6STUDIO_SCREENSHOT_PANE")
        val shotAction = System.getProperty("a6studio.screenshotAction") ?: System.getenv("A6STUDIO_SCREENSHOT_ACTION")
        val graphicsLayer = rememberGraphicsLayer()

        StudioTheme {
            Box(
                Modifier.fillMaxSize().drawWithContent {
                    graphicsLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(graphicsLayer)
                }
            ) {
                StudioApp(state)
            }
        }

        // Layout smoke test: dump the composed window to a PNG, then exit. Capture goes through
        // GraphicsLayer because Skia surfaces render blank when repainted via AWT.
        if (shotPath != null) {
            LaunchedEffect(Unit) {
                shotPane?.let { name ->
                    val pane = runCatching {
                        cn.dumate.a6studio.ui.Pane.valueOf(name.uppercase())
                    }.getOrNull()
                    if (pane != null) state.showPane(pane)
                }
                when (shotAction) {
                    "template-run" -> {
                        state.applyTemplate(cn.dumate.a6studio.model.Templates.byId("jdCoupon")!!)
                        delay(300)
                        state.run()
                    }
                    "connect" -> {
                        state.changeBeginnerMode(false)
                        state.showPane(cn.dumate.a6studio.ui.Pane.DEVICE)
                        state.updateBridgeUrl(
                            System.getenv("A6_MCP_URL").orEmpty()
                        )
                        System.getenv("A6_MCP_TOKEN")?.let { state.updateBridgeToken(it) }
                        delay(400)
                        state.connectDevice()
                        delay(4000)
                    }
                    "addmodule" -> {
                        state.changeBeginnerMode(false)
                        delay(300)
                        // Exactly what the button does, including the name helper.
                        state.repo.addModule(state.repo.nextModuleName())
                        state.commit()
                        delay(600)
                    }
                    "templates" -> state.openTemplates()
                    "guide" -> state.showGuide = true
                    "run" -> state.run()
                    "tests" -> state.runTests()
                    "audit" -> state.openAudit()
                    "recorder" -> state.openRecorder()
                    // 拾取器要连真机才有数据；截图核对布局时用一份固定样本，避免依赖设备。
                    "picker" -> {
                        state.changeBeginnerMode(false)
                        val body = state.repo.current.root.children["body"].orEmpty()
                        val anchor = body.firstOrNull { it.params.containsKey("value") } ?: body.firstOrNull()
                        val nodes = listOf(
                            cn.dumate.a6studio.picker.UiNode(
                                0, "android.widget.FrameLayout", left = 0, top = 0, right = 1080, bottom = 2376
                            ),
                            cn.dumate.a6studio.picker.UiNode(
                                1, "android.widget.TextView", text = "京东金融 · 签到", left = 40, top = 180, right = 640, bottom = 260
                            ),
                            cn.dumate.a6studio.picker.UiNode(
                                2, "android.widget.Button", text = "立即领取", desc = "领券按钮",
                                left = 120, top = 560, right = 960, bottom = 680, clickable = true
                            ),
                            cn.dumate.a6studio.picker.UiNode(
                                3, "android.widget.Button", text = "更多任务", left = 120, top = 720, right = 960, bottom = 840, clickable = true
                            ),
                            cn.dumate.a6studio.picker.UiNode(
                                4, "androidx.recyclerview.widget.RecyclerView", desc = "任务列表",
                                left = 0, top = 900, right = 1080, bottom = 2200, scrollable = true
                            )
                        )
                        // 截图页要一张图才看得出布局；给个本地 PNG 路径塞进去，免得为了截图去连真机。
                        val shotPathEnv = System.getenv("A6STUDIO_PICKER_SHOT").orEmpty()
                        val shot = if (shotPathEnv.isEmpty()) null else runCatching {
                            java.util.Base64.getEncoder().encodeToString(java.io.File(shotPathEnv).readBytes())
                        }.getOrNull()
                        val candidates = listOf(
                            cn.dumate.a6studio.picker.Candidate("id", "btn_claim", "id(btn_claim)", count = 1, score = 96),
                            cn.dumate.a6studio.picker.Candidate("text", "立即领取", "text(立即领取)", count = 1, score = 100),
                            cn.dumate.a6studio.picker.Candidate("textContains", "领取", "textContains(领取)", count = 3, score = 70)
                        )
                        state.previewPicker(cn.dumate.a6studio.picker.PickerState(
                            target = cn.dumate.a6studio.picker.PickTarget(
                                blockId = anchor?.id.orEmpty(),
                                paramKey = "value",
                                kind = cn.dumate.a6studio.picker.PickKind.SELECTOR
                            ),
                            nodes = nodes,
                            selected = nodes[2],
                            candidates = candidates,
                            chosen = candidates[1],
                            meta = "1080x2376 · 5 个节点",
                            view = if (shot != null) cn.dumate.a6studio.picker.PickerView.SHOT else cn.dumate.a6studio.picker.PickerView.LIST,
                            shotBase64 = shot,
                            deviceWidth = 1080,
                            deviceHeight = 2376
                        ))
                    }
                }
                delay(if (shotAction == null) 2500 else 4000)
                if (shotAction in setOf("templates", "audit", "recorder", "template-run", "connect")) delay(if (shotAction == "template-run") 5000 else 1200)
                runCatching {
                    val bmp = graphicsLayer.toImageBitmap()
                    ImageIO.write(bmp.toAwtImage(), "png", File(shotPath))
                    println("SCREENSHOT ok=${bmp.width}x${bmp.height} -> $shotPath")
                }.onFailure { println("SCREENSHOT FAILED: $it") }
                delay(200)
                exitApplication()
            }
        }
    }
}