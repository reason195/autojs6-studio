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
}