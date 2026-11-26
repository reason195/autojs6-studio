package cn.dumate.a6studio.model

import android.content.Context

/** Android storage: app-private files dir, so projects survive restarts without permissions. */
class AndroidProjectStorage(private val context: Context) : ProjectStorage {
    private val file get() = java.io.File(context.filesDir, "project.json")

    /** Null only when there is no project yet; read errors are thrown (see ProjectRepo.load). */
    override fun loadRaw(): String? = AtomicFiles.readOrNull(file)

    // Throws on failure so directory-full / revoked-storage cases reach the user instead of
    // looking like a successful save.
    override fun saveRaw(json: String) = AtomicFiles.write(file, json)

    override fun backupBroken(raw: String): String? = runCatching { AtomicFiles.backupBroken(file, raw) }.getOrNull()
}
