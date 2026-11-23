package cn.dumate.a6studio.model

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Crash-safe file writes shared by the desktop and Android storages.
 *
 * `File.writeText` truncates first and then writes, so a crash, power cut or full disk in
 * between leaves a half-written JSON that the next launch cannot read. Here the new content
 * goes to a temp file in the same directory, is fsync'ed, and then replaces the target with an
 * atomic rename. The previous good version is kept as `<name>.bak`.
 */
object AtomicFiles {

    fun write(target: File, text: String) {
        val dir = target.absoluteFile.parentFile ?: throw IOException("没有父目录：$target")
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) throw IOException("无法创建目录：$dir")
        val tmp = File(dir, "${target.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (target.exists()) {
            // Best effort: a failed backup must not block saving the user's latest work.
            runCatching {
                Files.copy(target.toPath(), File(dir, "${target.name}.bak").toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Reads [target]; null only when the file does not exist. Any read error is thrown, so the
     * caller can tell "first run" from "the user's file is there but unreadable".
     */
    fun readOrNull(target: File): String? {
        if (!target.exists()) {
            // A crash between backup and rename can leave only the .bak behind.
            val bak = File(target.absoluteFile.parentFile, "${target.name}.bak")
            return if (bak.exists()) bak.readText(Charsets.UTF_8) else null
        }
        return target.readText(Charsets.UTF_8)
    }

    fun backupBroken(target: File, raw: String): String {
        val copy = File(target.absoluteFile.parentFile, "project.corrupt-${System.currentTimeMillis()}.json")
        write(copy, raw)
        return copy.absolutePath
    }
}
