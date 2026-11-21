package cn.dumate.a6studio

import cn.dumate.a6studio.model.Samples
import cn.dumate.a6studio.diagnose.Diagnose
import cn.dumate.a6studio.ui.BeginnerCopy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BeginnerCopyTest {

    /** Every issue the diagnostic engine can raise on a real project gets plain wording. */
    @Test
    fun `diagnostic titles are rewritten for beginners`() {
        val issues = Diagnose.scan(Samples.emptyProject("空"))
        assertTrue(issues.isNotEmpty(), "empty project should raise issues")
        issues.forEach { issue ->
            val friendly = BeginnerCopy.issueTitle(issue.title)
            assertTrue(friendly.isNotBlank(), "empty rewrite for ${issue.title}")
            // A rewrite must not leave AutoJs6 vocabulary in the user-facing line.
            if (issue.title != friendly) {
                listOf("auto.waitFor", "控件定位", "容器积木", "调试").forEach { jargon ->
                    assertTrue(!friendly.contains(jargon), "jargon '$jargon' survived in: $friendly")
                }
            }
        }
    }

    @Test
    fun `run status reads like an answer, not a state machine`() {
        assertEquals("还没开始", BeginnerCopy.runStatus("未运行"))
        assertEquals("正在跑…", BeginnerCopy.runStatus("运行中"))
        assertEquals("跑到一半出错了", BeginnerCopy.runStatus("异常"))
        assertTrue(BeginnerCopy.runStatus("完成 · 模拟 40s").startsWith("跑完了"))
    }

    @Test
    fun `log lines explain what a miss means`() {
        val line = BeginnerCopy.logLine("未找到控件：text(\"立即领取\")")
        assertTrue(line.contains("屏幕上看不到"), line)
    }

    @Test
    fun `sample project issues all translate`() {
        val issues = Diagnose.scan(Samples.exampleProject())
        issues.forEach { issue ->
            assertNotEquals("", BeginnerCopy.issueTitle(issue.title))
        }
    }
}
