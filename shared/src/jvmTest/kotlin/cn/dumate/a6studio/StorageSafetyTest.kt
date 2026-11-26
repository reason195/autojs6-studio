package cn.dumate.a6studio

import cn.dumate.a6studio.model.AtomicFiles
import cn.dumate.a6studio.model.ProjectStorage
import cn.dumate.a6studio.store.ProjectRepo
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorageSafetyTest {

    private class CountingStorage(var raw: String? = null, val failRead: Boolean = false) : ProjectStorage {
        var writes = 0
        override fun loadRaw(): String? = if (failRead) throw IOException("locked") else raw
        override fun saveRaw(json: String) { writes++; raw = json }
    }

    @Test
    fun `unreadable project file is never overwritten`() {
        val storage = CountingStorage(raw = "user work", failRead = true)
        val repo = ProjectRepo(storage)
        val msg = repo.load()
        assertNotNull(msg)
        assertFalse(repo.save())
        assertEquals(0, storage.writes)
        assertEquals("user work", storage.raw)
    }

    @Test
    fun `missing file is first run and saving works`() {
        val storage = CountingStorage(raw = null)
        val repo = ProjectRepo(storage)
        assertNull(repo.load())
        assertTrue(repo.save())
        assertEquals(1, storage.writes)
    }

    @Test
    fun `identical saves are written once`() {
        val storage = CountingStorage(raw = null)
        val repo = ProjectRepo(storage)
        repo.load()
        repo.save(); repo.save(); repo.save()
        assertEquals(1, storage.writes)
    }

    @Test
    fun `atomic write replaces content and keeps a backup`() {
        val dir = createTempDirectory("a6store").toFile()
        val f = File(dir, "project.json")
        AtomicFiles.write(f, "v1")
        AtomicFiles.write(f, "v2")
        assertEquals("v2", f.readText())
        assertEquals("v1", File(dir, "project.json.bak").readText())
        assertFalse(File(dir, "project.json.tmp").exists())
    }

    @Test
    fun `read falls back to backup when the main file is gone`() {
        val dir = createTempDirectory("a6store").toFile()
        val f = File(dir, "project.json")
        AtomicFiles.write(f, "v1")
        AtomicFiles.write(f, "v2")
        f.delete()
        assertEquals("v1", AtomicFiles.readOrNull(f))
        assertNull(AtomicFiles.readOrNull(File(dir, "other.json")))
    }

    @Test
    fun `saving right after load does not rewrite the file`() {
        val storage = CountingStorage()
        val repo = ProjectRepo(storage)
        assertNull(repo.load())
        repo.save()
        val writesAfterFirst = storage.writes
        repo.save()
        assertEquals(writesAfterFirst, storage.writes, "identical content must not be written twice")
    }

    @Test
    fun `saving after a real load writes nothing when the model is unchanged`() {
        val storage = CountingStorage()
        val seed = ProjectRepo(storage)
        seed.load()
        seed.addBlock("sleep")
        val writesAfterSeed = storage.writes

        val reopened = ProjectRepo(storage)
        reopened.load()
        reopened.save()

        assertEquals(writesAfterSeed, storage.writes, "重新打开后未改动就保存，不应再写盘")
    }
}
