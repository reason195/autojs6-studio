package cn.dumate.a6studio

import cn.dumate.a6studio.model.ParamValue
import cn.dumate.a6studio.model.ProjectStorage
import cn.dumate.a6studio.store.ProjectRepo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Verifies that rapid param edits coalesce into one undo point, and switching fields starts new. */
class UndoCoalesceTest {

    private class MemStorage : ProjectStorage {
        var raw: String? = null
        override fun loadRaw(): String? = raw
        override fun saveRaw(json: String) { raw = json }
    }

    private fun repo(): ProjectRepo {
        val r = ProjectRepo(MemStorage())
        r.load()
        return r
    }

    @Test
    fun `typing in the same field produces one undo point`() {
        val r = repo()
        val blk = r.addBlock("sleep")

        val stackBefore = r.canUndo()

        // Simulate three keystrokes on the same field: 1 → 10 → 100
        r.updateParam(blk.id, "ms", ParamValue.Num(1.0))
        r.updateParam(blk.id, "ms", ParamValue.Num(10.0))
        r.updateParam(blk.id, "ms", ParamValue.Num(100.0))

        // Undo once should revert all three keystrokes in one shot.
        val label = r.undo()
        assertEquals("修改参数", label)

        // After one undo the field should be back to its default, and the block still exists.
        val block = r.blockById(blk.id)
        assertNotNull(block)
        // One more undo removes the addBlock snapshot.
        r.undo()
        assertNull(r.blockById(blk.id))
    }

    @Test
    fun `switching to another field starts a new undo point`() {
        val r = repo()
        val blk = r.addBlock("sleep")

        r.updateParam(blk.id, "ms", ParamValue.Num(500.0))
        // Switching to a different field (even same block) ends coalescing.
        r.endParamEdit()
        r.updateParam(blk.id, "ms", ParamValue.Num(999.0))

        // First undo reverts the second edit.
        r.undo()
        val v1 = r.blockById(blk.id)?.params?.get("ms")?.asNumber() ?: 0.0
        assertEquals(500.0, v1)

        // Second undo reverts the first edit.
        r.undo()
        val v2 = r.blockById(blk.id)?.params?.get("ms")?.asNumber() ?: 0.0
        // Should have reverted back to the default (1000ms for sleep).
        assertEquals(1000.0, v2)
    }
}
