package cn.dumate.a6studio

import cn.dumate.a6studio.bridge.SseAccumulator
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Socket reads split wherever the kernel decides. Decoding each chunk separately corrupted any
 * multi-byte character on the boundary, which is exactly what `ui_dump` is full of (Chinese
 * labels), so this locks the incremental-buffer behaviour.
 */
class SseDecodeTest {

    private val json = """{"id":1,"result":{"nodes":[{"text":"立即领取"},{"text":"更多任务"}]}}"""
    private val frame = ":\n\nevent: message\ndata: $json\n\n"
    private val bytes = frame.toByteArray(Charsets.UTF_8)

    /** Byte offset just after the first byte of a Chinese character (i.e. mid-character). */
    private fun splitInsideChar(): Int {
        val marker = "立即".toByteArray(Charsets.UTF_8)
        for (i in marker.indices step 3) {
            val at = bytes.indexOfSlice(marker)
            if (at >= 0) return at + 1
        }
        return bytes.size / 2
    }

    private fun ByteArray.indexOfSlice(needle: ByteArray): Int {
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    @Test
    fun `payload split inside a chinese character decodes intact`() {
        val cut = splitInsideChar()
        val acc = SseAccumulator()
        assertEquals(null, acc.feed(bytes.copyOfRange(0, cut), cut))
        val payload = acc.feed(bytes.copyOfRange(cut, bytes.size), bytes.size - cut)
        assertNotNull(payload, "payload not found after second chunk")
        assertContains(payload, "立即领取")
        assertContains(payload, "更多任务")
        assertFalse(payload.contains('\uFFFD'), "replacement char leaked: $payload")
    }

    @Test
    fun `one byte per chunk still decodes`() {
        val acc = SseAccumulator()
        var payload: String? = null
        for (b in bytes) {
            payload = acc.feed(byteArrayOf(b), 1) ?: payload
        }
        assertNotNull(payload)
        assertContains(payload, "立即领取")
    }
}
