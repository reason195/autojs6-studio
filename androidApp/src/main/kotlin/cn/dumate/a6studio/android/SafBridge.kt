package cn.dumate.a6studio.android

import android.content.Context
import android.net.Uri

/**
 * Carries Storage-Access-Framework requests between a long-lived [StudioViewModel] and whichever
 * Activity is currently on screen.
 *
 * Why this exists: the platform object used to live in the Activity, so every rotation rebuilt
 * `StudioState` (undo stack, remembered tokens, a running simulation) and dropped the coroutine
 * waiting for a file picker. The pending request — and the content waiting to be written — now
 * lives here, so a recreated Activity can finish what the old one started.
 */
object SafBridge {

    interface Host {
        fun launchOpen(mimes: Array<String>)
        fun launchSave(suggestedName: String)
    }

    private var host: Host? = null

    private var pendingOpen: ((Pair<String, String>?) -> Unit)? = null
    private var pendingSave: ((String?) -> Unit)? = null

    /** Content waiting for the user to pick a destination; written by the Activity that gets the Uri. */
    private var pendingContent: String? = null

    fun attach(h: Host) { host = h }

    fun detach(h: Host) { if (host === h) host = null }

    fun openText(mimes: Array<String>, onResult: (Pair<String, String>?) -> Unit): Boolean {
        val h = host ?: return false
        pendingOpen?.invoke(null)
        pendingOpen = onResult
        h.launchOpen(mimes)
        return true
    }

    fun saveText(suggestedName: String, content: String, onResult: (String?) -> Unit): Boolean {
        val h = host ?: return false
        pendingSave?.invoke(null)
        pendingSave = onResult
        pendingContent = content
        h.launchSave(suggestedName)
        return true
    }

    fun cancelOpen(cb: (Pair<String, String>?) -> Unit) {
        if (pendingOpen === cb) pendingOpen = null
    }

    fun cancelSave(cb: (String?) -> Unit) {
        if (pendingSave === cb) {
            pendingSave = null
            pendingContent = null
        }
    }

    fun onOpenResult(result: Pair<String, String>?) {
        val cb = pendingOpen
        pendingOpen = null
        cb?.invoke(result)
    }

    fun onSaveResult(name: String?) {
        val cb = pendingSave
        pendingSave = null
        pendingContent = null
        cb?.invoke(name)
    }

    /** Writes [content] to [uri] for the save dialog that just returned; false when it failed. */
    fun writePendingTo(ctx: Context, uri: Uri): Boolean {
        val content = pendingContent ?: return false
        return runCatching {
            // "wt" truncates: the default "w" leaves the previous document's tail on some
            // providers, which produced a corrupt file after overwriting a longer export.
            val out = ctx.contentResolver.openOutputStream(uri, "wt")
                ?: throw java.io.IOException("无法打开目标文件输出流")
            out.use { it.write(content.toByteArray()) }
        }.isSuccess
    }

    fun displayName(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()
}
