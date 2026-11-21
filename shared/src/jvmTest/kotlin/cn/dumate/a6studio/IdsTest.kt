package cn.dumate.a6studio

import cn.dumate.a6studio.model.Ids
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdsTest {

    private val shape = Regex("""b_[a-z0-9]+_\d+""")

    @Test
    fun `block ids keep the legacy shape`() {
        repeat(200) {
            val id = Ids.next()
            assertTrue(shape.matches(id), "unexpected id format: $id")
        }
        assertTrue(Regex("""mod_[a-z0-9]+_\d+""").matches(Ids.nextWith("mod")))
        assertEquals(8, Ids.randomHex().length)
    }

    /**
     * The previous implementation bumped a plain `counter`, so two threads could hand out the same
     * id inside one millisecond; duplicate ids then confuse selection, undo and the code markers.
     */
    @Test
    fun `ids generated from many threads never collide`() {
        val seen = ConcurrentHashMap.newKeySet<String>()
        val duplicates = ConcurrentHashMap.newKeySet<String>()
        val threads = (1..8).map {
            thread {
                repeat(2_000) {
                    val id = Ids.next()
                    if (!seen.add(id)) duplicates += id
                }
            }
        }
        threads.forEach { it.join() }
        assertEquals(16_000, seen.size, "collisions: ${duplicates.take(5)}")
        assertTrue(duplicates.isEmpty())
    }
}
