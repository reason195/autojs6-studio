package cn.dumate.a6studio

import cn.dumate.a6studio.catalog.Js
import cn.dumate.a6studio.picker.PickerLogic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The picker and the code generator must escape identically — they used to drift. */
class EscapingTest {

    private val bell = 1.toChar()
    private val formFeed = 12.toChar()
    private val lineSep = 0x2028.toChar()
    private val paraSep = 0x2029.toChar()
    private val bs = '\\'

    @Test
    fun `both escapers agree on awkward text`() {
        val samples = listOf(
            "plain",
            "he said \"hi\"",
            "back${bs}slash",
            "line\nbreak",
            "tab\there",
            "bell${bell}char",
            "form${formFeed}feed",
            "sep${lineSep}arator",
            "para${paraSep}graph",
            "中文标签"
        )
        for (s in samples) {
            assertEquals(Js.quote(s), PickerLogic.quote(s), "mismatch for ${s.map { it.code }}")
        }
    }

    @Test
    fun `control characters are escaped not emitted raw`() {
        val quoted = PickerLogic.quote("a${bell}b")
        assertEquals("\"a${bs}u0001b\"", quoted)
        assertFalse(quoted.contains(bell))
        // Padding matters: "%04x" is JVM-only, padStart keeps commonMain portable.
        assertEquals("\"${bs}u000b\"", Js.quote(11.toChar().toString()))
    }

    @Test
    fun `line terminators inside literals are escaped`() {
        assertTrue(Js.quote("a${lineSep}b").contains("${bs}u2028"))
        assertTrue(Js.quote("a${paraSep}b").contains("${bs}u2029"))
    }
}
