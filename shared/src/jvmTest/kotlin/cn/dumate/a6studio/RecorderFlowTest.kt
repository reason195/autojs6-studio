package cn.dumate.a6studio

import cn.dumate.a6studio.recorder.RecorderController
import cn.dumate.a6studio.recorder.RecorderState
import cn.dumate.a6studio.recorder.RecorderStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 录制相关的回归点（审查报告 M6 / L6）。
 *
 * 录制期间刻意不与手机通信：脚本常驻触摸监听后，同进程的 MCP Server 就不再应答。之前的
 * 900ms 轮询会把状态改成 FETCHING/DONE/ERROR，界面显示「读取失败」，而手机其实还在录。
 */
class RecorderFlowTest {

    @Test
    fun `execution id larger than Int is kept`() {
        // AutoJs6 的执行 id 是 Long；旧实现用 intOrNull，超出 Int 就变 null，脚本停不掉。
        val raw = """{"executionId":4294967298,"status":"running"}"""
        assertEquals(4294967298L, RecorderController.parseExecutionId(raw))
    }

    @Test
    fun `execution id falls back to id and tolerates junk`() {
        assertEquals(7L, RecorderController.parseExecutionId("""{"id":7}"""))
        assertNull(RecorderController.parseExecutionId("not json"))
        assertNull(RecorderController.parseExecutionId("""{"status":"ok"}"""))
    }

    @Test
    fun `device script clears the previous run before appending`() {
        val script = RecorderController.DEVICE_SCRIPT
        // 只 append 不清空时，第二次录制读到「旧 + 新」，而 inserted 已归零 → 旧操作重复生成。
        assertTrue(script.contains("""files.write(OUT, "")"""), script.lineSequence().take(8).joinToString("\n"))
    }

    @Test
    fun `fresh count only counts events added since the last insert`() {
        val three = RecorderState(
            status = RecorderStatus.DONE,
            events = listOf(
                cn.dumate.a6studio.recorder.RecEvent(type = "tap"),
                cn.dumate.a6studio.recorder.RecEvent(type = "swipe"),
                // 这条不可用（error 事件），usableCount 不该把它算进去
                cn.dumate.a6studio.recorder.RecEvent(type = "error")
            ),
            inserted = 1
        )
        assertEquals(2, three.usableCount)
        assertEquals(1, three.freshCount)
    }
}
