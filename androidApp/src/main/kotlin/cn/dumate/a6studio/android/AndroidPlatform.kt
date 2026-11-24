package cn.dumate.a6studio.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import cn.dumate.a6studio.platform.Platform
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Android implementation of [Platform]. Takes an application context, so it can outlive a single
 * Activity: the state holder (ViewModel) keeps it across rotation.
 *
 * Dialogs go through [SafBridge] because the actual launchers belong to the Activity currently
 * on screen — that is the one piece that has to be swapped on every recreation.
 */
class AndroidPlatform(private val ctx: Context) : Platform {
    override val name = "android"
    override val supportsFileDialogs = true

    /**
     * Relative storage names are confined to filesDir, mirroring the desktop platform: the
     * callers all pass internal constants today, but `../` must not be able to write outside
     * the app's private directory.
     */
    private fun resolve(path: String): java.io.File? {
        if (java.io.File(path).isAbsolute) return null
        val base = ctx.filesDir.canonicalFile
        val target = java.io.File(base, path).canonicalFile
        val inside = target.path == base.path || target.path.startsWith(base.path + java.io.File.separator)
        return if (inside) target else null
    }

    override fun readTextFile(path: String): String? =
        resolve(path)?.let { runCatching { it.readText() }.getOrNull() }

    override fun writeTextFile(path: String, content: String): Boolean = runCatching {
        val f = resolve(path) ?: return false
        f.parentFile?.mkdirs()
        f.writeText(content)
        true
    }.getOrDefault(false)

    override suspend fun saveTextAs(suggestedName: String, content: String, mimeType: String): String? =
        suspendCancellableCoroutine { cont ->
            val cb: (String?) -> Unit = { name ->
                if (cont.isActive) cont.resume(name)
            }
            cont.invokeOnCancellation { SafBridge.cancelSave(cb) }
            val started = SafBridge.saveText(suggestedName, content, cb)
            if (!started && cont.isActive) cont.resume(null)
        }

    override suspend fun openTextFile(extensions: List<String>): Pair<String, String>? =
        suspendCancellableCoroutine { cont ->
            val mimes = if (extensions.contains("json")) {
                arrayOf("application/json", "text/plain", "*/*")
            } else {
                arrayOf("text/plain", "*/*")
            }
            val cb: (Pair<String, String>?) -> Unit = { result ->
                if (cont.isActive) cont.resume(result)
            }
            cont.invokeOnCancellation { SafBridge.cancelOpen(cb) }
            val started = SafBridge.openText(mimes, cb)
            if (!started && cont.isActive) cont.resume(null)
        }

    override fun copyToClipboard(text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("AutoJs6 Studio", text))
    }

    // Android 10+ only lets the foreground app read the clipboard; this is called from a
    // button tap, so the app is in front.
    override fun readClipboard(): String? = runCatching {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
    }.getOrNull()

    override fun setWindowTitle(title: String) { }
}
