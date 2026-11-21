package cn.dumate.a6studio.runtime

data class LogEntry(val level: String, val msg: String, val t: Long)

data class RunResult(
    val status: String,          // finished | exited | error
    val error: String?,
    val logs: List<LogEntry>,
    val code: String,
    val virtualMs: Long,
    val sim: SimStateView?
) {
    val ok: Boolean get() = status == "finished" || status == "exited"
}

data class SimStateView(
    val screen: String,
    val packageName: String,
    val signed: Boolean,
    val taskCount: Int,
    val clip: String,
    val deviceWidth: Int,
    val deviceHeight: Int
)

data class TestResult(
    val id: String,
    val name: String,
    val ok: Boolean,
    val error: String,
    val logs: List<LogEntry>
)

data class SimNode(
    var text: String = "",
    val desc: String = "",
    val id: String = "",
    val className: String = "android.widget.TextView",
    val clickable: Boolean = false,
    val editable: Boolean = true,
    val bounds: IntArray = intArrayOf(0, 0, 0, 0)
) {
    val label: String get() = text.ifEmpty { desc.ifEmpty { id.ifEmpty { className } } }
}

/** Callbacks used by the UI to mirror the simulated phone screen while the script runs. */
interface SimSink {
    fun onRender(nodes: List<SimNode>, state: SimStateView)
    fun onToast(msg: String)
}