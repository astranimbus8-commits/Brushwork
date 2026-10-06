package com.brushwork.paint.model

/**
 * v1.7 (item 8, I11): the layer tree over the FLAT layer list (bottom first). A folder is a
 * [Layer] with `folder != null`; every layer's `parentId` is [Layer.ROOT_ID] or the id of a
 * folder. Block rule: a folder at flat index f with d descendants owns exactly [f − d, f − 1],
 * directly below it. Folders nest at most [MAX_DEPTH] deep (a folder has at most
 * `MAX_DEPTH − 1` ancestors) and a document has at most [MAX_FOLDERS].
 *
 * Pure (reads only `id`, `parentId` and the folder / visible / locked / opacity / clipping
 * fields; never a bitmap); O(n) per query (n ≤ 164). Queries take flat indices; -1 means "top
 * level". Structural edits are [Plan]s the controller applies with ONE `LayerTreeAction`
 * (`engine/LayerStructure.kt`, F2).
 */
object LayerTree {
    const val MAX_DEPTH = 8
    const val MAX_FOLDERS = 64

    /** The load-time repair warning (`FolderLabels.REPAIRED`). */
    private const val REPAIRED = "The folder structure was repaired"

    /** The number of layers inside the folder at [folderIndex] (all levels); 0 for a layer that is not a folder. */
    fun descendantCount(layers: List<Layer>, folderIndex: Int): Int {
        if (folderIndex !in layers.indices || !layers[folderIndex].isFolder) return 0
        val inside = HashSet<Long>()
        inside += layers[folderIndex].id
        var j = folderIndex - 1
        while (j >= 0 && layers[j].parentId in inside) {
            if (layers[j].isFolder) inside += layers[j].id
            j--
        }
        return folderIndex - 1 - j
    }

    /** The layer at [index], or the folder there plus its descendants: [index − d, index]. */
    fun block(layers: List<Layer>, index: Int): IntRange = (index - descendantCount(layers, index))..index

    /** The flat index of the folder [index] is in; -1 = top level (or a parent that is not above it). */
    fun parentOf(layers: List<Layer>, index: Int): Int {
        if (index !in layers.indices) return -1
        val pid = layers[index].parentId
        if (pid == Layer.ROOT_ID) return -1
        for (j in index + 1 until layers.size) if (layers[j].id == pid) return if (layers[j].isFolder) j else -1
        return -1
    }

    /** The number of folders [index] is in; 0 = top level. */
    fun depth(layers: List<Layer>, index: Int): Int = ancestors(layers, index).size

    /** The flat indices of the folders [index] is in, nearest first. */
    fun ancestors(layers: List<Layer>, index: Int): List<Int> {
        val out = ArrayList<Int>(4)
        var p = parentOf(layers, index)
        // Parents lie strictly above their children, so the walk ends.
        while (p >= 0) {
            out += p
            p = parentOf(layers, p)
        }
        return out
    }

    /** The direct children of the folder at [folderIndex] (-1 = the top level), bottom first. */
    fun children(layers: List<Layer>, folderIndex: Int): List<Int> {
        if (folderIndex < 0) {
            val parents = parentIndices(layers)
            return layers.indices.filter { parents[it] < 0 }
        }
        if (folderIndex !in layers.indices || !layers[folderIndex].isFolder) return emptyList()
        val id = layers[folderIndex].id
        return block(layers, folderIndex).filter { it != folderIndex && layers[it].parentId == id }
    }

    /**
     * Sibling units (single layers or whole folder blocks) of the level whose parent is
     * [parentId], bottom first: one unit per layer of [range] whose `parentId` is [parentId],
     * each the full [block] of that layer.
     */
    fun units(layers: List<Layer>, range: IntRange, parentId: Long): List<IntRange> {
        val out = ArrayList<IntRange>()
        for (i in range) {
            if (i !in layers.indices) continue
            if (layers[i].parentId == parentId) out += block(layers, i)
        }
        return out
    }

    /** True when every folder [index] is in is visible (its own eye is not read). */
    fun shownByAncestors(layers: List<Layer>, index: Int): Boolean = ancestors(layers, index).all { layers[it].visible }

    /** True when any folder [index] is in is locked (its own lock is not read). */
    fun lockedByAncestor(layers: List<Layer>, index: Int): Boolean = ancestors(layers, index).any { layers[it].locked }

    /**
     * I5 fast paths: every folder [index] is in is pass-through, visible, at opacity 1, neither
     * a clip base (the sibling directly above it is not clipping) nor clipped. True at the top
     * level.
     */
    fun showsAsIs(layers: List<Layer>, index: Int): Boolean = ancestors(layers, index).all { a ->
        val f = layers[a]
        f.folder?.passThrough == true && f.visible && f.opacity == 1f && !f.clipping && !isClipBase(layers, a)
    }

    /** True when the sibling directly above the unit at [index] clips to it. */
    private fun isClipBase(layers: List<Layer>, index: Int): Boolean {
        val above = index + 1
        return above < layers.size && layers[above].parentId == layers[index].parentId && layers[above].clipping
    }

    // ------------------------------------------------------------------ plans

    /**
     * A structural edit, not yet applied: the new flat [order], the `parentId` of each layer of
     * it ([parents], parallel to [order]) and the flat index of the layer to make active.
     */
    class Plan(val order: List<Layer>, val parents: LongArray, val active: Int)

    /**
     * The block of [from] (the layer, or the folder with its descendants) moved so that its top
     * (the layer at [from]) lands at flat index [toIndex] of the result (the v1.6 `moveLayer`
     * index for a single layer), in the folder [newParent] ([Layer.ROOT_ID] = top level).
     * [toIndex] is limited to the indices the block can reach. Null when [newParent] is inside
     * the block (into itself), is not a folder, or the result would break I11 (the block lands
     * outside [newParent]'s block, or folders nest deeper than [MAX_DEPTH]).
     */
    fun movedBlock(layers: List<Layer>, from: Int, toIndex: Int, newParent: Long): Plan? {
        if (from !in layers.indices) return null
        val b = block(layers, from)
        val moving = layers.subList(b.first, b.last + 1)
        if (newParent != Layer.ROOT_ID) {
            if (moving.any { it.id == newParent }) return null
            if (layers.none { it.id == newParent && it.isFolder }) return null
        }
        val rest = ArrayList<Layer>(layers.size)
        for (i in layers.indices) if (i !in b) rest += layers[i]
        val size = moving.size
        val top = toIndex.coerceIn(size - 1, layers.lastIndex)
        val at = top - (size - 1)
        val order = ArrayList<Layer>(layers.size)
        order.addAll(rest.subList(0, at))
        order.addAll(moving)
        order.addAll(rest.subList(at, rest.size))
        val root = layers[from]
        val parents = LongArray(order.size) { if (order[it] === root) newParent else order[it].parentId }
        if (check(order, parents) != null) return null
        return Plan(order, parents, top)
    }

    /**
     * ibisPaint's swipe right: the unit at [index] goes into the folder directly above it at its
     * level, as that folder's bottom child. The flat order is unchanged. Null when the sibling
     * directly above is not a folder (or there is none), or the nesting would get too deep.
     */
    fun putIntoFolderAbove(layers: List<Layer>, index: Int): Plan? {
        if (index !in layers.indices) return null
        val pid = layers[index].parentId
        var j = index + 1
        while (j < layers.size && layers[j].parentId != pid) {
            if (layers[j].id == pid) return null // [index] is the top child: no sibling above
            j++
        }
        if (j >= layers.size || !layers[j].isFolder) return null
        if (block(layers, j).first != index + 1) return null
        val folderId = layers[j].id
        val parents = LongArray(layers.size) { if (it == index) folderId else layers[it].parentId }
        if (check(layers, parents) != null) return null
        return Plan(ArrayList(layers), parents, index)
    }

    /**
     * ibisPaint's swipe left: the folder's BOTTOM child at [index] leaves the folder and sits
     * directly below the folder's block, at the folder's level. The flat order is unchanged.
     * Null for a top-level layer or a child that is not the bottom one.
     */
    fun takeOutOfFolder(layers: List<Layer>, index: Int): Plan? {
        if (index !in layers.indices) return null
        val p = parentOf(layers, index)
        if (p < 0) return null
        if (block(layers, index).first != block(layers, p).first) return null
        val newParent = layers[p].parentId
        val parents = LongArray(layers.size) { if (it == index) newParent else layers[it].parentId }
        if (check(layers, parents) != null) return null
        return Plan(ArrayList(layers), parents, index)
    }

    /**
     * [block] (a layer, or a folder's block with the folder LAST) inserted so that its first
     * layer is at flat index [at]; the block's top layer gets [parentId], the others keep theirs.
     * The top layer becomes active. Not validated: the caller's `structural {}` checks the result.
     */
    fun inserted(layers: List<Layer>, at: Int, block: List<Layer>, parentId: Long): Plan {
        val pos = at.coerceIn(0, layers.size)
        val order = ArrayList<Layer>(layers.size + block.size)
        order.addAll(layers.subList(0, pos))
        order.addAll(block)
        order.addAll(layers.subList(pos, layers.size))
        val top = block.lastOrNull()
        val parents = LongArray(order.size) { if (top != null && order[it] === top) parentId else order[it].parentId }
        return Plan(order, parents, if (block.isEmpty()) pos.coerceAtMost(order.lastIndex) else pos + block.size - 1)
    }

    // ------------------------------------------------------------------ checking and repair

    /** null = I11 holds, else what is wrong. */
    fun check(layers: List<Layer>): String? = check(layers, LongArray(layers.size) { layers[it].parentId })

    /**
     * [check] for [layers] with the parents [parents] (a [Plan] before it is applied).
     * [duplicates] false skips the duplicate-id test ([sanitize] does not repair ids).
     */
    private fun check(layers: List<Layer>, parents: LongArray, duplicates: Boolean = true): String? {
        val ids = HashSet<Long>(layers.size * 2)
        var folders = 0
        for (l in layers) {
            if (!ids.add(l.id) && duplicates) return "duplicate layer id ${l.id}"
            if (l.isFolder) folders++
        }
        if (folders > MAX_FOLDERS) return "$folders folders (at most $MAX_FOLDERS)"
        // Walk from the top: the open folders (the current ancestor chain) form a stack. A layer's
        // parent must be on it, which is exactly "every block is contiguous and directly below
        // its folder".
        val stack = ArrayList<Layer>()
        for (i in layers.indices.reversed()) {
            val l = layers[i]
            val pid = parents[i]
            if (pid == Layer.ROOT_ID) stack.clear()
            else {
                while (stack.isNotEmpty() && stack.last().id != pid) stack.removeAt(stack.lastIndex)
                if (stack.isEmpty()) {
                    val parent = layers.firstOrNull { it.id == pid }
                    return when {
                        parent == null -> "$l has an unknown parent $pid"
                        !parent.isFolder -> "$l has a parent that is not a folder: $parent"
                        else -> "$l is outside the block of $parent"
                    }
                }
            }
            if (l.isFolder) {
                if (stack.size >= MAX_DEPTH) return "$l is nested ${stack.size + 1} deep (at most $MAX_DEPTH)"
                stack += l
            }
        }
        return null
    }

    /**
     * Load / import repair (I11): unknown, non-folder or forward parents (a parent must lie
     * above its child) go to the top level, which also breaks every cycle; folders beyond
     * [MAX_FOLDERS] (the topmost ones) are ungrouped (their children move up a level and the
     * folder entry goes); folders nested deeper than [MAX_DEPTH] move up to the deepest allowed
     * level; blocks are made contiguous. The flat order is kept where possible. Mutates [layers]
     * and their `parentId`; returns the warnings (empty when nothing was wrong, so a list
     * without folders and parents is never touched). Duplicate ids are not repaired here (the
     * loader gives a repeated id in a file with folders a fresh one); with duplicates, a parent
     * id means the lowest layer with that id.
     */
    fun sanitize(layers: MutableList<Layer>): List<String> {
        if (check(layers, LongArray(layers.size) { layers[it].parentId }, duplicates = false) == null) return emptyList()
        // 1. Parents must be folders strictly above their children.
        var indexOf = indexById(layers)
        for (i in layers.indices) {
            val l = layers[i]
            if (l.parentId == Layer.ROOT_ID) continue
            val p = indexOf[l.parentId]
            if (p == null || p <= i || !layers[p].isFolder) l.parentId = Layer.ROOT_ID
        }
        // 2. At most MAX_FOLDERS folders: ungroup the topmost extra ones.
        val folderIndices = layers.indices.filter { layers[it].isFolder }
        if (folderIndices.size > MAX_FOLDERS) {
            val extra = folderIndices.drop(MAX_FOLDERS).map { layers[it] }.toSet()
            // Re-parent from the top down, so a chain of removed folders ends at a kept one.
            for (f in layers.reversed()) {
                if (f !in extra) continue
                for (l in layers) if (l.parentId == f.id) l.parentId = f.parentId
            }
            layers.removeAll { it in extra }
            indexOf = indexById(layers)
        }
        // 3. Depth: from the top down, a folder too deep moves up to the deepest allowed level.
        val depth = IntArray(layers.size)
        for (i in layers.indices.reversed()) {
            val l = layers[i]
            var p = if (l.parentId == Layer.ROOT_ID) -1 else indexOf.getValue(l.parentId)
            if (l.isFolder) {
                while (p >= 0 && depth[p] + 1 >= MAX_DEPTH) {
                    l.parentId = layers[p].parentId
                    p = if (l.parentId == Layer.ROOT_ID) -1 else indexOf.getValue(l.parentId)
                }
            }
            depth[i] = if (p < 0) 0 else depth[p] + 1
        }
        // 4. Contiguous blocks: each unit's children (in flat order), then the unit itself.
        val children = HashMap<Long, MutableList<Layer>>()
        for (l in layers) children.getOrPut(l.parentId) { ArrayList() } += l
        val order = ArrayList<Layer>(layers.size)
        fun emit(l: Layer) {
            // (remove: each list is emitted once, even if two layers share a damaged id)
            if (l.isFolder) children.remove(l.id)?.forEach { emit(it) }
            order += l
        }
        children.remove(Layer.ROOT_ID)?.forEach { emit(it) }
        check(order.size == layers.size) { "LayerTree.sanitize lost layers" }
        layers.clear()
        layers.addAll(order)
        return listOf(REPAIRED)
    }

    private fun indexById(layers: List<Layer>): HashMap<Long, Int> {
        val m = HashMap<Long, Int>(layers.size * 2)
        for (i in layers.indices) m.putIfAbsent(layers[i].id, i)
        return m
    }

    /** The parent index of every layer (-1 = top level), in one pass. */
    private fun parentIndices(layers: List<Layer>): IntArray {
        val indexOf = indexById(layers)
        return IntArray(layers.size) { i ->
            val pid = layers[i].parentId
            if (pid == Layer.ROOT_ID) -1
            else indexOf[pid]?.takeIf { it > i && layers[it].isFolder } ?: -1
        }
    }
}
