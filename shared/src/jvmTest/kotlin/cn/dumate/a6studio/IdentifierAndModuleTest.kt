package cn.dumate.a6studio

import cn.dumate.a6studio.catalog.CodeGen
import cn.dumate.a6studio.diagnose.Diagnose
import cn.dumate.a6studio.diagnose.Level
import cn.dumate.a6studio.model.MemoryProjectStorage
import cn.dumate.a6studio.model.ParamValue
import cn.dumate.a6studio.model.Project
import cn.dumate.a6studio.model.Samples
import cn.dumate.a6studio.model.StudioModule
import cn.dumate.a6studio.model.newBlock
import cn.dumate.a6studio.store.ProjectRepo
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** L10 (module matching) and L11 (identifier params). */
class IdentifierAndModuleTest {

    private fun projectWith(vararg blocks: cn.dumate.a6studio.model.Block, modules: List<StudioModule> = emptyList()): Project {
        val base = Samples.emptyProject()
        return base.copy(root = base.root.copy(children = mapOf("body" to blocks.toList())), modules = modules)
    }

    @Test
    fun `identifier check accepts plain names and rejects the rest`() {
        listOf("a", "_x", "\$el", "task2", "camelCase").forEach { assertTrue(Diagnose.isJsIdentifier(it), it) }
        listOf("2x", "my var", "a-b", "let", "if", "x.y", "名字").forEach { assertFalse(Diagnose.isJsIdentifier(it), it) }
    }

    @Test
    fun `invalid variable name is reported as an error`() {
        val p = projectWith(newBlock("varSet", mapOf("name" to ParamValue.Text("my var"))))
        val issues = Diagnose.scan(p).filter { it.level == Level.ERROR }
        assertTrue(issues.any { it.title.contains("my var") }, issues.joinToString { it.title })
    }

    @Test
    fun `valid variable name is not reported`() {
        val p = projectWith(newBlock("varSet", mapOf("name" to ParamValue.Text("count"))))
        assertTrue(Diagnose.scan(p).none { it.title.startsWith("名称不是合法标识符") })
    }

    @Test
    fun `nodeTextVar quotes the label in its log line`() {
        val p = projectWith(newBlock("nodeTextVar", mapOf("var" to ParamValue.Text("t"))))
        assertContains(CodeGen.code(p), "log(\"t = \" + t);")
    }

    @Test
    fun `moduleCall matches by id, not by name`() {
        val a = StudioModule(id = "m1", name = "签到")
        val b = StudioModule(id = "m2", name = "领券")
        // The block points at m2's id; m1's name must not capture it.
        val p = projectWith(newBlock("moduleCall", mapOf("module" to ParamValue.Text("m2"))), modules = listOf(a, b))
        val code = CodeGen.code(p)
        assertContains(code, "mod_领券();")
        assertFalse(code.lines().any { it.trim() == "mod_签到();" })
    }

    @Test
    fun `old projects storing a module name are migrated to its id on load`() {
        val m = StudioModule(id = "m1", name = "签到")
        val p = projectWith(newBlock("moduleCall", mapOf("module" to ParamValue.Text("签到"))), modules = listOf(m))
        val raw = kotlinx.serialization.json.Json { encodeDefaults = true }.encodeToString(Project.serializer(), p)
        val repo = ProjectRepo(MemoryProjectStorage(raw))
        repo.load()
        val call = repo.current.root.children["body"].orEmpty().single()
        assertEquals("m1", call.params["module"]?.asText())
        assertContains(CodeGen.code(repo.current), "mod_签到();")
    }

    @Test
    fun `module name collision by cleaned identifier is reported`() {
        val a = StudioModule(id = "m1", name = "foo bar")
        val b = StudioModule(id = "m2", name = "foo-bar")
        val p = projectWith(newBlock("toast", mapOf("text" to ParamValue.Text("hi"))), modules = listOf(a, b))
        val issues = Diagnose.scan(p)
        assertTrue(issues.any { it.level == Level.ERROR && it.title.contains("模块名冲突") }, issues.toString())
    }
}
