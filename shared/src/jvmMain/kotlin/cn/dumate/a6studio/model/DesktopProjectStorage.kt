package cn.dumate.a6studio.model

import cn.dumate.a6studio.platform.Platform
import java.io.File

/** Desktop storage: one JSON file under the user's home config dir. */
class DesktopProjectStorage(private val platform: Platform) : ProjectStorage {
    private val file = File(
        System.getProperty("user.home"),
        ".a6studio" + File.separator + "project.json"
    )

    /** Null only when there is no project yet; read errors are thrown (see ProjectRepo.load). */
    override fun loadRaw(): String? = AtomicFiles.readOrNull(file)

    override fun saveRaw(json: String) = AtomicFiles.write(file, json)

    /** Keeps unreadable content beside the project so a bad read cannot destroy the user's work. */
    override fun backupBroken(raw: String): String? = runCatching { AtomicFiles.backupBroken(file, raw) }.getOrNull()
}
