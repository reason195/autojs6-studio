package cn.dumate.a6studio

import cn.dumate.a6studio.model.MemoryProjectStorage
import cn.dumate.a6studio.model.Samples
import cn.dumate.a6studio.store.ProjectRepo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Regression for "the module row buttons did nothing": repeated clicks created duplicate
 * 「模块1」 entries because the name came from a stale UI snapshot.
 */
class ModuleReactivityTest {

    private fun repo(): ProjectRepo = ProjectRepo(MemoryProjectStorage()).also {
        it.load()
        it.setProject(Samples.exampleProject(), "reset")
    }

    @Test
    fun `repeated addModule never produces duplicate names`() {
        val r = repo()
        repeat(5) { r.addModule(r.nextModuleName()) }
        val names = r.current.modules.map { it.name }
        assertEquals(names.distinct().size, names.size, "duplicate module names: $names")
    }

    @Test
    fun `nextModuleName skips taken numbers`() {
        val r = repo()
        val before = r.current.modules.map { it.name }.toSet()
        val suggested = r.nextModuleName()
        assertTrue(suggested !in before, "suggested a taken name: $suggested")
        r.addModule(suggested)
        assertNotEquals(suggested, r.nextModuleName())
    }

    @Test
    fun `adding then removing a module updates the project`() {
        val r = repo()
        val countBefore = r.current.modules.size
        r.addModule(r.nextModuleName())
        assertEquals(countBefore + 1, r.current.modules.size)
        val added = r.current.modules.last()
        r.removeModule(added.id)
        assertEquals(countBefore, r.current.modules.size)
    }

    /** The UI reads `repo.currentScope`; a scope change must be visible immediately. */
    @Test
    fun `scope value reflects setScope synchronously`() {
        val r = repo()
        r.addModule("模块A")
        val id = r.current.modules.first { it.name == "模块A" }.id
        r.setScope(cn.dumate.a6studio.model.Scope.Module(id))
        val scope = r.currentScope
        assertTrue(scope is cn.dumate.a6studio.model.Scope.Module && scope.id == id, "scope not applied: $scope")
        r.setScope(cn.dumate.a6studio.model.Scope.Root)
        assertTrue(r.currentScope is cn.dumate.a6studio.model.Scope.Root)
    }
}
