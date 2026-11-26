package cn.dumate.a6studio

import cn.dumate.a6studio.model.MemoryProjectStorage
import cn.dumate.a6studio.model.Project
import cn.dumate.a6studio.model.Samples
import cn.dumate.a6studio.model.newBlock
import cn.dumate.a6studio.store.ProjectRepo
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R-2：外部 MCP 补丁标记必须随工程落盘。
 * 这里在 ProjectRepo 层复现 StudioState.applyExternalMcpProject 的两步（snapshot + 带标记写入），
 * 用同一个 MemoryProjectStorage 新建第二个 repo 来模拟「重启」。
 */
class ExternalPatchFlagTest {

    private fun applyExternal(repo: ProjectRepo, p: Project) {
        repo.snapshot("外部 MCP 补丁")
        repo.setProjectDirect(p.copy(externalPatchPending = true))
    }

    private fun patched(repo: ProjectRepo): Project {
        val cur = repo.current
        return cur.copy(
            root = cur.root.copy(
                children = cur.root.children + ("body" to cur.root.children["body"].orEmpty() + newBlock("log"))
            )
        )
    }

    @Test
    fun flagSurvivesRestart() {
        val storage = MemoryProjectStorage()
        val repo = ProjectRepo(storage).also { it.load() }
        applyExternal(repo, patched(repo))
        assertTrue(repo.current.externalPatchPending)

        val reopened = ProjectRepo(storage)
        reopened.load()
        assertTrue(reopened.current.externalPatchPending, "重启后标记必须仍在")
    }

    @Test
    fun undoRestoresUnflaggedSnapshot() {
        val repo = ProjectRepo(MemoryProjectStorage()).also { it.load() }
        applyExternal(repo, patched(repo))
        assertTrue(repo.current.externalPatchPending)

        assertEquals("外部 MCP 补丁", repo.undo())
        assertFalse(repo.current.externalPatchPending, "撤销到补丁前应无标记")
    }

    @Test
    fun confirmClearsFlagAndPersists() {
        val storage = MemoryProjectStorage()
        val repo = ProjectRepo(storage).also { it.load() }
        applyExternal(repo, patched(repo))

        repo.markExternalPatchConfirmed()
        assertFalse(repo.current.externalPatchPending)

        val reopened = ProjectRepo(storage)
        reopened.load()
        assertFalse(reopened.current.externalPatchPending, "确认后重启不应再要求确认")
    }

    @Test
    fun oldProjectFileWithoutFieldLoadsAsFalse() {
        // 旧版本写出的工程 JSON 没有 externalPatchPending 字段
        val legacy = Json { encodeDefaults = false }.encodeToString(Project.serializer(), Samples.emptyProject())
        assertFalse(legacy.contains("externalPatchPending"))
        val repo = ProjectRepo(MemoryProjectStorage(legacy))
        repo.load()
        assertFalse(repo.current.externalPatchPending)
    }
}
