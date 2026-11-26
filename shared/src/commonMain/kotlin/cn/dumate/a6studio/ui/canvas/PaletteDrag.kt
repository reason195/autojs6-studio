package cn.dumate.a6studio.ui.canvas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/** Where a dragged palette block would be inserted: [index] inside [parentId]'s [slot]. */
data class DropTarget(val parentId: String, val slot: String, val index: Int)

/** A list that can receive blocks: the scope body (parentId = scope root id) or a container slot. */
data class DropContainer(val parentId: String, val slot: String, val count: Int, val bounds: Rect)

/** A block card on screen, at [index] in its parent list. */
data class DropCard(val id: String, val parentId: String, val slot: String, val index: Int, val bounds: Rect)

/**
 * Picks the drop target for pointer [p] (root coordinates).
 *
 * The innermost container under the pointer wins, so hovering a loop body drops inside the loop
 * and hovering the loop's own header drops next to it in the outer list. Within the container the
 * index is "after every visible card whose middle is above the pointer". Cards scrolled out of a
 * lazy list are simply not registered; using indices (not positions) keeps the result right.
 */
fun resolveDrop(p: Offset, containers: Collection<DropContainer>, cards: Collection<DropCard>): DropTarget? {
    val box = containers
        .filter { it.bounds.contains(p) }
        .minByOrNull { it.bounds.width * it.bounds.height }
        ?: return null
    val own = cards.filter { it.parentId == box.parentId && it.slot == box.slot }
    val index = when {
        own.isEmpty() -> box.count
        else -> {
            val above = own.filter { it.bounds.center.y < p.y }
            if (above.isNotEmpty()) above.maxOf { it.index } + 1 else own.minOf { it.index }
        }
    }
    return DropTarget(box.parentId, box.slot, index.coerceIn(0, box.count))
}

/**
 * State of one "drag a block from the palette onto the canvas" gesture.
 *
 * Both panels live in the same window, so positions are exchanged in root coordinates: palette
 * items report where the pointer is, canvas cards and slots report their bounds, and the target
 * is recomputed on every move. Drawing happens elsewhere (ghost chip in StudioApp, insertion
 * line in the canvas); this class only holds data.
 */
class PaletteDragState {
    var draggingType by mutableStateOf<String?>(null)
        private set
    var pointer by mutableStateOf(Offset.Zero)
        private set
    var target by mutableStateOf<DropTarget?>(null)
        private set

    private val cards = HashMap<String, DropCard>()
    private val containers = HashMap<String, DropContainer>()

    val active: Boolean get() = draggingType != null

    fun registerCard(card: DropCard) { cards[card.id] = card }
    fun unregisterCard(id: String) { cards.remove(id) }

    fun registerContainer(c: DropContainer) { containers[c.parentId + "/" + c.slot] = c }
    fun unregisterContainer(parentId: String, slot: String) { containers.remove("$parentId/$slot") }

    fun start(type: String, at: Offset) {
        draggingType = type
        pointer = at
        target = resolveDrop(at, containers.values, cards.values)
    }

    fun move(delta: Offset) {
        if (draggingType == null) return
        pointer += delta
        target = resolveDrop(pointer, containers.values, cards.values)
    }

    /** Ends the gesture and returns what to insert where, or null when released outside the canvas. */
    fun end(): Pair<String, DropTarget>? {
        val t = draggingType
        val d = target
        cancel()
        return if (t != null && d != null) t to d else null
    }

    fun cancel() {
        draggingType = null
        target = null
    }
}
