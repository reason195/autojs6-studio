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

    // Resolve the same way as [writeTextFile]: bare names live under ~/.a6studio, otherwise
    // the marker written by writeTextFile could never be read back.
    private fun resolve(path: String): File =
        if (File(path).isAbsolute) File(path) else File(System.getProperty("user.home"), ".a6studio/$path")

    override fun readTextFile(path: String): String? = runCatching { resolve(path).readText() }.getOrNull()

    override fun writeTextFile(path: String, content: String): Boolean = runCatching {
        val f = if (File(path).isAbsolute) File(path) else File(System.getProperty("user.home"), ".a6studio/$path")
        f.parentFile?.mkdirs()
        f.writeText(content)
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
        onCloseRequest = ::exitApplication,
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