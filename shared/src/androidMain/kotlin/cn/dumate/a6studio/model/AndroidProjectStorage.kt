package cn.dumate.a6studio.model

import android.content.Context

/** Android storage: app-private files dir, so projects survive restarts without permissions. */
class AndroidProjectStorage(private val context: Context) : ProjectStorage {
    private val file get() = java.io.File(context.filesDir, "project.json")

    override fun loadRaw(): String? =
        if (file.exists()) runCatching { file.readText() }.getOrNull() else null

    // Throws on failure so directory-full / revoked-storage cases reach the user instead of
    // looking like a successful save.
    override fun saveRaw(json: String) {
        file.writeText(json)
    }

    override fun backupBroken(raw: String): String? = runCatching {
        val target = java.io.File(file.parentFile, "project.broken-${System.currentTimeMillis()}.json")
        target.writeText(raw)
        target.absolutePath
    }.getOrNull()
}