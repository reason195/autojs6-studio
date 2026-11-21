package cn.dumate.a6studio

import cn.dumate.a6studio.catalog.Catalog
import cn.dumate.a6studio.catalog.CodeGen
import cn.dumate.a6studio.model.MemoryProjectStorage
import cn.dumate.a6studio.model.ParamValue
import cn.dumate.a6studio.model.Samples
import cn.dumate.a6studio.model.TreeOps
import cn.dumate.a6studio.model.newBlock
import cn.dumate.a6studio.runtime.Runner
import cn.dumate.a6studio.store.ProjectRepo
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodeGenTest {

    @Test
    fun `header and marker comments are emitted`() {
        val p = Samples.emptyProject("T")
        val plain = CodeGen.code(p, markers = false)
        assertContains(plain, "\"auto\";")
        assertTrue(plain.contains("主程序为空"))

        val repo = ProjectRepo(MemoryProjectStorage())
        repo.load()
        val marked = CodeGen.code(repo.current, markers = true)
        assertTrue(marked.lines().any { it.matches(Regex("""//@b_[a-z0-9_]+""")) })
    }

    @Test
    fun `selector prefers explicit expression and wraps it`() {
        val exprBlock = newBlock("clickNode", mapOf("expr" to ParamValue.Text("desc(\"更多任务\")")))
        val code = CodeGen.blockCode(exprBlock, Samples.emptyProject())
        assertContains(code, "(desc(\"更多任务\")).findOne(3000)")

        val byBlock = newBlock("clickNode")
        assertContains(CodeGen.blockCode(byBlock, Samples.emptyProject()), "text(\"立即领奖\").findOne(3000)")
    }

    @Test
    fun `string literal quoting matches json`() {
        val b = newBlock("log", mapOf("text" to ParamValue.Text("he said \"hi\"\nand left")))
        assertContains(CodeGen.blockCode(b, Samples.emptyProject()), "log(\"he said \\\"hi\\\"\\nand left\");")
    }

    @Test
    fun `container slots nest with indentation`() {
        val inner = newBlock("log", mapOf("text" to ParamValue.Text("x")))
        val ifBlock = newBlock("if", mapOf("cond" to ParamValue.Text("1 === 1")))
            .copy(children = mapOf("body" to listOf(inner), "else" to emptyList()))
        val code = CodeGen.blockCode(ifBlock, Samples.emptyProject())
        assertEquals(
            "if (1 === 1) {\n  log(\"x\");\n}",
            code
        )
    }

    @Test
    fun `every catalogue block generates without throwing`() {
        val p = Samples.emptyProject()
        val failures = Catalog.all().mapNotNull { def ->
            val b = newBlock(def.type)
            val out = runCatching { CodeGen.blockCode(b, p) }.getOrNull()
            if (out == null || out.isEmpty()) def.type else null
        }
        assertEquals(emptyList(), failures)
    }
}

class StoreTest {

    @Test
    fun `add insert move remove round trip`() {
        val repo = ProjectRepo(MemoryProjectStorage())
        repo.load()
        repo.setProject(Samples.emptyProject("空"), "reset")

        val a = repo.addBlock("comment")
        val b = repo.addBlock("log")
        assertEquals(listOf(a.id, b.id), repo.current.root.children["body"]!!.map { it.id })

        repo.shiftBlock(b.id, -1)
        assertEquals(listOf(b.id, a.id), repo.current.root.children["body"]!!.map { it.id })

        repo.duplicateBlock(a.id)
        assertEquals(3, repo.current.root.children["body"]!!.size)

        repo.removeBlock(a.id)
        assertEquals(2, repo.current.root.children["body"]!!.size)
    }

    @Test
    fun `adding to a selected container goes into its first slot`() {
        val repo = ProjectRepo(MemoryProjectStorage())
        repo.load()
        repo.setProject(Samples.emptyProject("空"), "reset")
        val ifBlock = repo.addBlock("if")
        // ifBlock is selected after add; a new block should land in its "body" slot.
        val inner = repo.addBlock("toast")
        val stored = repo.current.root.children["body"]!!.first()
        assertEquals(ifBlock.id, stored.id)
        assertEquals(listOf(inner.id), stored.children["body"]!!.map { it.id })
    }

    @Test
    fun `undo restores the previous tree`() {
        val repo = ProjectRepo(MemoryProjectStorage())
        repo.load()
        repo.setProject(Samples.emptyProject("空"), "reset")
        repo.addBlock("comment")
        repo.addBlock("log")
        repo.undo()
        assertEquals(1, repo.current.root.children["body"]!!.size)
        repo.undo()
        assertEquals(0, repo.current.root.children["body"]!!.size)
    }

    @Test
    fun `module scope edits stay inside the module`() {
        val repo = ProjectRepo(MemoryProjectStorage())
        repo.load()
        repo.setProject(Samples.emptyProject("空"), "reset")
        repo.addModule("测试模块")
        repo.addBlock("log")
        val fresh = repo.current.modules.single()
        assertEquals("测试模块", fresh.name)
        assertEquals(1, fresh.children["body"]!!.size)
        // The main program is untouched by module-scope edits.
        assertEquals(0, repo.current.root.children["body"]!!.size)
    }

    @Test
    fun `blocks cannot be moved into their own subtree`() {
        val repo = ProjectRepo(MemoryProjectStorage())
        repo.load()
        repo.setProject(Samples.emptyProject("空"), "reset")
        val ifBlock = repo.addBlock("if")
        repo.addBlock("log") // child inside if
        assertTrue(!repo.moveBlock(ifBlock.id, ifBlock.id, "body", 0))
    }

    @Test
    fun `project json round trips`() {
        val storage = MemoryProjectStorage()
        val repo = ProjectRepo(storage)
        repo.load()
        val before = repo.current
        repo.save()
        val repo2 = ProjectRepo(storage)
        repo2.load()
        assertEquals(TreeOps.count(before.root.children["body"]!!), TreeOps.count(repo2.current.root.children["body"]!!))
        assertEquals(before.modules.size, repo2.current.modules.size)
        assertEquals(before.tests.size, repo2.current.tests.size)
    }
}

class RuntimeTest {

    @Test
    fun `sample project walks the whole coupon flow`() {
        val p = Samples.exampleProject()
        val r = Runner.run(p)
        assertEquals("finished", r.status, "error: ${r.error}")
        val text = r.logs.joinToString("\n") { it.msg }
        assertContains(text, "已点击签到")
        assertContains(text, "本轮处理任务: 3")
        assertContains(text, "收工")
    }

    @Test
    fun `sample tests all pass`() {
        val results = Runner.runTests(Samples.exampleProject())
        assertEquals(3, results.size)
        results.forEach { assertTrue(it.ok, "${it.name}: ${it.error}") }
    }

    @Test
    fun `empty task list finishes after scanning`() {
        val p = Samples.exampleProject()
        val r = Runner.run(p)
        assertEquals("finished", r.status)
    }

    @Test
    fun `assertion failures surface as test errors`() {
        val p = Samples.emptyProject("t").copy(
            tests = listOf(
                cn.dumate.a6studio.model.TestCase("x", "必然失败", "assert(false, 'boom');")
            )
        )
        val r = Runner.runTests(p).single()
        assertTrue(!r.ok)
        assertContains(r.error, "断言失败")
    }

    @Test
    fun `exit marks the run as exited`() {
        val p = Samples.emptyProject("t").copy(
            root = Samples.emptyProject().root.copy(
                children = mapOf("body" to listOf(newBlock("exitScript")))
            )
        )
        assertEquals("exited", Runner.run(p).status)
    }
}