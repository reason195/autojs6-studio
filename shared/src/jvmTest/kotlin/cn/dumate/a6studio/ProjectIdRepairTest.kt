package cn.dumate.a6studio

import cn.dumate.a6studio.model.MemoryProjectStorage
import cn.dumate.a6studio.model.Project
import cn.dumate.a6studio.model.Samples
import cn.dumate.a6studio.model.TreeOps
import cn.dumate.a6studio.model.newBlock
import cn.dumate.a6studio.store.ProjectRepo
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M15: every block id must be unique after loading, or one edit hits several blocks. */
class ProjectIdRepairTest {

    @Test
    fun `duplicate and empty ids are repaired on load`() {
        val a = newBlock("log")
        val base = Samples.emptyProject()
        val broken: Project = base.copy(
            root = base.root.copy(
                children = mapOf("body" to listOf(a, a.copy(), a.copy(id = "")))
            )
        )
        val raw = Json { encodeDefaults = true }.encodeToString(Project.serializer(), broken)
        val repo = ProjectRepo(MemoryProjectStorage(raw))
        repo.load()

        val ids = mutableListOf<String>()
        TreeOps.walk(repo.current.root.children["body"].orEmpty()) { ids += it.id }
        assertEquals(3, ids.size)
        assertEquals(3, ids.toSet().size, "ids must be unique: $ids")
        assertTrue(ids.none { it.isEmpty() })
        // The first occurrence keeps its id, so references to it stay valid.
        assertEquals(a.id, ids.first())
    }
}
