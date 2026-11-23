package cn.dumate.a6studio.model

import cn.dumate.a6studio.catalog.Catalog

/** A block plus where it lives, for tree edits. */
data class LocatedBlock(
    val block: Block,
    val list: List<Block>,
    val index: Int,
    val parentId: String,
    val slot: String
)

/**
 * Mutual references inside a plain data-class tree are awkward, so tree mutation happens
 * through [ProjectRepo], which rebuilds the immutable model on every edit and keeps an undo
 * stack of snapshots — same contract as the web `A6.store`.
 */
object TreeOps {

    fun normalize(block: Block): Block =
        if (block.id.isNotEmpty() && block.children.isNotEmpty()) block
        else block.copy(
            id = block.id.ifEmpty { Ids.next() },
            children = if (block.children.isEmpty()) mapOf("body" to emptyList()) else block.children
        )

    fun walk(list: List<Block>, visit: (Block) -> Unit) {
        for (b in list) {
            visit(b)
            for ((_, kids) in b.children) walk(kids, visit)
        }
    }

    fun find(list: List<Block>, id: String): Block? {
        for (b in list) {
            if (b.id == id) return b
            for ((_, kids) in b.children) {
                find(kids, id)?.let { return it }
            }
        }
        return null
    }

    /** Immutable replace of the block with [id] by the result of [transform]. */
    fun replace(list: List<Block>, id: String, transform: (Block) -> Block): List<Block> =
        list.map { b ->
            if (b.id == id) transform(b)
            else b.copy(children = b.children.mapValues { (_, kids) -> replace(kids, id, transform) })
        }

    /** Removes [id]; returns the new list plus the removed block. */
    fun remove(list: List<Block>, id: String): Pair<List<Block>, Block?> {
        var removed: Block? = null
        fun go(items: List<Block>): List<Block> = items.mapNotNull { b ->
            if (b.id == id) {
                removed = b
                null
            } else {
                b.copy(children = b.children.mapValues { (_, kids) -> go(kids) })
            }
        }
        val result = go(list)
        // `go` already returns the pruned list; when nothing matched the tree is unchanged.
        return result to removed
    }

    fun insert(list: List<Block>, parentId: String, slot: String, index: Int, block: Block, rootId: String = "root"): List<Block> {
        if (parentId == rootId) {
            return list.toMutableList().apply { add(index.coerceIn(0, size), block) }
        }
        return replace(list, parentId) { parent ->
            val kids = parent.children[slot].orEmpty().toMutableList()
            kids.add(index.coerceIn(0, kids.size), block)
            parent.copy(children = parent.children + (slot to kids))
        }
    }

    fun count(list: List<Block>): Int =
        list.sumOf { b -> 1 + b.children.values.sumOf { count(it) } }

    fun cloneBlock(block: Block): Block = block.copy(
        id = Ids.next(),
        children = block.children.mapValues { (_, kids) -> kids.map { cloneBlock(it) } }
    )

    fun subtreeIds(block: Block): Set<String> {
        val set = linkedSetOf<String>()
        walk(listOf(block)) { set += it.id }
        return set
    }

    /** True if [ancestorId] contains [childId] (used to reject dropping a block into itself). */
    fun contains(list: List<Block>, ancestorId: String, childId: String): Boolean {
        val anc = find(list, ancestorId) ?: return false
        return subtreeIds(anc).contains(childId)
    }
}

fun newBlock(type: String, overrides: Map<String, ParamValue> = emptyMap()): Block {
    val def = Catalog.byType(type)
    val params = mutableMapOf<String, ParamValue>()
    def?.params?.forEach { params[it.key] = it.default }
    params.putAll(overrides)
    val children = mutableMapOf<String, List<Block>>()
    def?.slots?.forEach { children[it.key] = emptyList() }
    return Block(id = Ids.next(), type = type, params = params, children = children)
}