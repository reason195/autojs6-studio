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
}

/** In-memory fallback for tests and first-run scenarios. */
class MemoryProjectStorage(private var raw: String? = null) : ProjectStorage {
    override fun loadRaw(): String? = raw
    override fun saveRaw(json: String) { raw = json }
}