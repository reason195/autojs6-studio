package cn.dumate.a6studio.platform

/** Host services the shared UI needs but only a platform can provide. */
interface Platform {
    val name: String

    /** True when the host can open native file pickers / save dialogs. */
    val supportsFileDialogs: Boolean

    fun readTextFile(path: String): String?
    fun writeTextFile(path: String, content: String): Boolean

    /** Writes `content` into a user-chosen location; returns the chosen display name or null. */
    suspend fun saveTextAs(suggestedName: String, content: String, mimeType: String): String?

    /** Reads a user-chosen text file; returns its content or null when cancelled. */
    suspend fun openTextFile(extensions: List<String>): Pair<String, String>?

    fun copyToClipboard(text: String)
    /** Current clipboard text, or null when empty / not text / not available. */
    fun readClipboard(): String? = null
    fun setWindowTitle(title: String)

    /** 采集目标 App 期间保活（Android 用前台服务；其他平台默认无操作）。 */
    fun startKeepAlive() {}
    fun stopKeepAlive() {}
}

/** Default used by previews and tests. */
object HeadlessPlatform : Platform {
    override val name = "headless"
    override val supportsFileDialogs = false
    private val files = mutableMapOf<String, String>()
    private var clipboard: String = ""

    override fun readTextFile(path: String): String? = files[path]
    override fun writeTextFile(path: String, content: String): Boolean { files[path] = content; return true }
    override suspend fun saveTextAs(suggestedName: String, content: String, mimeType: String): String? {
        files[suggestedName] = content; return suggestedName
    }
    override suspend fun openTextFile(extensions: List<String>): Pair<String, String>? =
        files.entries.firstOrNull()?.let { it.key to it.value }
    override fun copyToClipboard(text: String) { clipboard = text }
    override fun setWindowTitle(title: String) { }
}