package cn.dumate.a6studio.model

import cn.dumate.a6studio.platform.Platform
import java.io.File

/** Desktop storage: one JSON file under the user's home config dir. */
class DesktopProjectStorage(private val platform: Platform) : ProjectStorage {
    private val file = File(
        System.getProperty("user.home"),
        ".a6studio" + File.separator + "project.json"
    )

    override fun loadRaw(): String? =
        if (file.exists()) runCatching { file.readText() }.getOrNull() else null

    override fun saveRaw(json: String) {
        file.parentFile?.mkdirs()
        file.writeText(json)
    }

    /** Keeps unreadable content beside the project so a bad read cannot destroy the user's work. */
    override fun backupBroken(raw: String): String? = runCatching {
        val target = File(file.parentFile, "project.broken-${System.currentTimeMillis()}.json")
        target.writeText(raw)
        target.absolutePath
    }.getOrNull()
}