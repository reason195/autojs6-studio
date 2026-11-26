package cn.dumate.a6studio

import cn.dumate.a6studio.ui.canvas.DropCard
import cn.dumate.a6studio.ui.canvas.DropContainer
import cn.dumate.a6studio.ui.canvas.DropTarget
import cn.dumate.a6studio.ui.canvas.PaletteDragState
import cn.dumate.a6studio.ui.canvas.resolveDrop
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Where a block dragged from the palette lands on the canvas. */
class PaletteDropTest {

    // Root list 0..1000 tall; three cards; card b is a loop whose body (one child) sits inside it.
    private val root = DropContainer("root", "body", 3, Rect(0f, 0f, 400f, 1000f))
    private val loopBody = DropContainer("b", "body", 1, Rect(20f, 260f, 380f, 380f))
    private val cards = listOf(
        DropCard("a", "root", "body", 0, Rect(0f, 0f, 400f, 100f)),
        DropCard("b", "root", "body", 1, Rect(0f, 120f, 400f, 400f)),
        DropCard("c", "root", "body", 2, Rect(0f, 420f, 400f, 520f)),
        DropCard("b1", "b", "body", 0, Rect(30f, 280f, 370f, 340f))
    )
    private val containers = listOf(root, loopBody)

    @Test
    fun `above the first card inserts at the top`() {
        assertEquals(DropTarget("root", "body", 0), resolveDrop(Offset(200f, 20f), containers, cards))
    }

    @Test
    fun `between cards inserts between them`() {
        // Below a's middle (50) and above b's middle (260): after a.
        assertEquals(DropTarget("root", "body", 1), resolveDrop(Offset(200f, 110f), containers, cards))
    }

    @Test
    fun `below the last card appends`() {
        assertEquals(DropTarget("root", "body", 3), resolveDrop(Offset(200f, 900f), containers, cards))
    }

    @Test
    fun `inside a loop body drops into the loop, not the outer list`() {
        // Below b1's middle (310): after b1, inside b.
        assertEquals(DropTarget("b", "body", 1), resolveDrop(Offset(200f, 360f), containers, cards))
        // Above b1's middle: before it.
        assertEquals(DropTarget("b", "body", 0), resolveDrop(Offset(200f, 290f), containers, cards))
    }

    @Test
    fun `empty container takes the drop at index 0`() {
        val empty = DropContainer("x", "body", 0, Rect(0f, 600f, 400f, 700f))
        assertEquals(DropTarget("x", "body", 0), resolveDrop(Offset(200f, 650f), containers + empty, cards))
    }

    @Test
    fun `outside every container is no target`() {
        assertNull(resolveDrop(Offset(900f, 900f), containers, cards))
    }

    @Test
    fun `gesture returns type and target on release, nothing after cancel`() {
        val d = PaletteDragState()
        containers.forEach(d::registerContainer)
        cards.forEach(d::registerCard)
        d.start("toast", Offset(200f, 20f))
        d.move(Offset(0f, 880f))
        assertEquals("toast" to DropTarget("root", "body", 3), d.end())
        assertNull(d.draggingType)

        d.start("toast", Offset(200f, 20f))
        d.cancel()
        assertNull(d.end())
    }

    @Test
    fun `released outside the canvas inserts nothing`() {
        val d = PaletteDragState()
        containers.forEach(d::registerContainer)
        d.start("toast", Offset(900f, 900f))
        assertNull(d.end())
    }
}
