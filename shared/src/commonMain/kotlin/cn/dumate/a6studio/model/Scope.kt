package cn.dumate.a6studio.model

/** Where edits go: the main program or one of the user's modules. */
sealed interface Scope {
    data object Root : Scope
    data class Module(val id: String) : Scope
}

/** Project persistence. Desktop uses a file, Android uses app-private storage. */
interface ProjectStorage {
    fun loadRaw(): String?
    fun saveRaw(json: String)

    /**
     * Keeps a copy of content that could not be parsed and returns where it went (for the
     * message shown to the user). Without this, a corrupt read is followed by the sample project
     * and the next edit overwrites the user's file — silent work loss.
     *
     * Returns null when the host cannot write a backup.
     */
    fun backupBroken(raw: String): String? = null
}

/** In-memory fallback for tests and first-run scenarios. */
class MemoryProjectStorage(private var raw: String? = null) : ProjectStorage {
    val backups = mutableListOf<String>()

    override fun loadRaw(): String? = raw
    override fun saveRaw(json: String) { raw = json }
    override fun backupBroken(raw: String): String {
        backups += raw
        return "memory://project.broken-${backups.size}.json"
    }
}