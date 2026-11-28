package cn.dumate.a6studio

import cn.dumate.a6studio.catalog.Catalog
import cn.dumate.a6studio.model.ParamValue
import cn.dumate.a6studio.step.NodeTapStep
import cn.dumate.a6studio.step.StepTemplates
import cn.dumate.a6studio.step.StepTemplates.menu
import cn.dumate.a6studio.ui.StudioState
import io.ktor.client.engine.cio.CIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 交互式单步识别-生成：生成脚本的积木结构回归测试。
 *
 * 覆盖：
 *  1) NodeTapStep.toBlocks —— 一个「点按钮」步骤展开为 kitWaitFor + kitClick（都带按文本匹配）；
 *  2) 四个模板动作都能生成非空且目录内已知的积木（等待 / 关弹窗 / 等倒计时 / 检测跳转拉回）；
 *  3) stepperGenerateAndClose —— 有目标 App 时攒齐的步骤拼成一条完整脚本骨架
 *     （autoWait → kitBoot → kitLaunch → kitGuard → 步骤 → kitFinish），并清空已录状态；
 *  4) 没有目标 App 就点「生成」必须被拦下，而不是用默认包名生成错误的脚本。
 */
class StepFlowTest {

    private val platform = TestPlatform()
    private val storage = cn.dumate.a6studio.model.MemoryProjectStorage()

    private fun state(): StudioState {
        val s = StudioState(platform, storage, CIO, cn.dumate.a6studio.runtime.RhinoRunEngine)
        s.init()
        s.changeBeginnerMode(false)
        return s
    }

    /** 运行时反射写 private set 的步进状态，避免只为测试把生成逻辑改成可注入。 */
    private fun setPkg(s: StudioState, value: String) {
        callSetter(s, "setStepTargetPkg", value)
    }

    private fun setEntries(s: StudioState, value: List<cn.dumate.a6studio.step.StepEntry>) {
        callSetter(s, "setStepEntries", value)
    }

    private fun callSetter(s: StudioState, methodName: String, value: Any) {
        val m = StudioState::class.java.declaredMethods.firstOrNull { m ->
            m.name == methodName && m.parameterCount == 1 && m.parameterTypes[0].isAssignableFrom(value.javaClass)
        } ?: throw NoSuchMethodException("$methodName(${value.javaClass}) 不存在")
        m.isAccessible = true
        m.invoke(s, value)
    }

    // ---- 步骤 → 积木 ----------------------------------------------------------

    @Test
    fun nodeTapStepExpandsToWaitPlusClick() {
        val step = NodeTapStep(id = "s1", by = "text", value = "立即领取")
        val blocks = step.toBlocks()
        assertEquals(2, blocks.size)
        assertEquals("kitWaitFor", blocks[0].type)
        assertEquals(ParamValue.Text("立即领取"), blocks[0].params["value"], "等待必须按识别到的同一段文字匹配")
        assertEquals("kitClick", blocks[1].type)
        assertEquals(ParamValue.Text("立即领取"), blocks[1].params["value"])
    }

    @Test
    fun allTemplateActionsProduceBlocks() {
        val ids = listOf("wait", "closeDialogs", "waitCountdown", "ensureForeground")
        assertEquals(ids, menu.map { it.id }, "模板动作表应保持四个基础动作")
        menu.forEach { def ->
            val blocks = def.build()
            assertTrue(blocks.isNotEmpty(), "模板 ${def.id} 必须生成至少一个积木")
            assertTrue(blocks.all { b -> Catalog.byType(b.type) != null }, "模板 ${def.id} 的积木必须是目录里的已知类型")
        }
    }

    @Test
    fun waitTemplateUsesRequestedSeconds() {
        val entry = StepTemplates.waitSeconds("w1", 5)
        val sleep = entry.blocks.first { it.type == "sleep" }
        assertEquals(ParamValue.Num(5000.0), sleep.params["ms"], "等待 N 秒应生成 sleep(N*1000)")
        assertTrue(entry.blocks.any { it.type == "log" }, "等待模板应先写一条日志")
    }

    // ---- 生成脚本 --------------------------------------------------------------

    @Test
    fun generateWithTargetAppBuildsFullKitSkeletonAndClears() {
        val s = state()
        try {
            setPkg(s, "com.jingdong.app.mall")
            setEntries(
                s,
                listOf(
                    NodeTapStep(id = "n1", by = "text", value = "签到"),
                    StepTemplates.closeDialogs("t1"),
                    StepTemplates.waitCountdown("t2")
                )
            )
            s.stepperGenerateAndClose()

            // 生成后目标包名故意保留：同一 App 连续录第二条脚本时可直接复用；
            // 下次点「识别当前页」会被新识别结果覆盖。
            assertEquals("com.jingdong.app.mall", s.stepTargetPkg)
            assertTrue(s.stepEntries.isEmpty(), "生成后应清空已录步骤")
            assertTrue(s.stepCandidates.isEmpty(), "生成后应清空候选按钮")
            assertTrue("已生成交互脚本" in s.autoStatus, "应给出生成成功提示：${s.autoStatus}")

            val body = s.repo.current.root.children["body"].orEmpty()
            val types = body.map { it.type }
            assertEquals("autoWait", types[0])
            assertEquals("kitBoot", types[1])
            assertEquals("kitLaunch", types[2])
            assertEquals("kitGuard", types[3])
            // 步骤顺序：签到 → 关弹窗 → 等倒计时 → finish
            assertEquals("kitWaitFor", types[4])
            assertEquals("kitClick", types[5])
            assertEquals("kitGuard", types[6])
            assertEquals("kitWaitCountdown", types[7])
            assertEquals("kitFinish", types.last())

            val boot = body.first { it.type == "kitBoot" }
            assertEquals(ParamValue.Text("com.jingdong.app.mall"), boot.params["pkg"])
            assertEquals(
                ParamValue.Text("交互 · com.jingdong.app.mall"),
                boot.params["name"],
                "kitBoot 名称应带交互前缀并落到识别到的 App"
            )
        } finally {
            s.dispose()
        }
    }

    @Test
    fun generateWithoutTargetAppIsBlocked() {
        val s = state()
        try {
            // 只插模板，从不点「识别当前页」→ stepTargetPkg 为空
            setEntries(s, listOf(StepTemplates.closeDialogs("t1")))
            s.stepperGenerateAndClose()

            assertTrue(s.stepEntries.size == 1, "应保留已录步骤")
            assertTrue("先点一次「① 识别当前页」" in s.stepStatus, "应引导先识别目标 App：${s.stepStatus}")
            val hasKit = s.repo.current.root.children["body"].orEmpty().any { it.type == "kitBoot" }
            assertTrue(!hasKit, "没识别目标 App 时不得写出 kitBoot")
        } finally {
            s.dispose()
        }
    }

    @Test
    fun generateWithEmptyEntriesIsNoop() {
        val s = state()
        try {
            s.stepperGenerateAndClose()
            assertTrue("还没有任何步骤" in s.stepStatus)
            assertTrue(s.stepEntries.isEmpty())
        } finally {
            s.dispose()
        }
    }

    private class TestPlatform : cn.dumate.a6studio.platform.Platform {
        override val name = "stepflow-test"
        override val supportsFileDialogs = false
        val files = mutableMapOf<String, String>()
        override fun readTextFile(path: String): String? = files[path]
        override fun writeTextFile(path: String, content: String): Boolean {
            files[path] = content
            return true
        }
        override suspend fun saveTextAs(suggestedName: String, content: String, mimeType: String): String? = null
        override suspend fun openTextFile(extensions: List<String>): Pair<String, String>? = null
        override fun copyToClipboard(text: String) {}
        override fun setWindowTitle(title: String) {}
    }
}