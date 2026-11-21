package cn.dumate.a6studio.model

import android.content.Context

/** Android storage: app-private files dir, so projects survive restarts without permissions. */
class AndroidProjectStorage(private val context: Context) : ProjectStorage {
    private val file get() = java.io.File(context.filesDir, "project.json")

    override fun loadRaw(): String? =
        if (file.exists()) runCatching { file.readText() }.getOrNull() else null

    override fun saveRaw(json: String) {
        runCatching { file.writeText(json) }
    }
}