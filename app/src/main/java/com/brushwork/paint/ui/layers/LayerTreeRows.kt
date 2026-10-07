package com.brushwork.paint.ui.layers

import com.brushwork.paint.engine.FolderComposite
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerTree

/**
 * v1.7 (§3.8): what a drag of the layer window moves: the row of layer [id] and the rows after it
 * in the top-first order, [size] in all (a folder with every layer inside it; 1 for a layer).
 */
internal data class DraggedUnit(val id: Long, val size: Int) {
    companion object {
        /** The unit of [layer] in [layers] (bottom first). */
        fun of(layers: List<Layer>, layer: Layer): DraggedUnit {
            val i = layers.indexOfFirst { it === layer }
            return DraggedUnit(layer.id, if (i < 0) 1 else LayerTree.descendantCount(layers, i) + 1)
        }
    }
}

/**
 * v1.7 (item 8, design §3.8): how the layer window shows the layer tree. Pure (reads ids,
 * parents, the folder fields and the clipping flags; never a bitmap), main thread.
 *
 * - Rows are indented [INDENT_DP] per folder level; the indent stops growing at
 *   [MAX_INDENT_LEVELS] levels (48 dp) so names keep their room on a 392 dp phone, and deeper
 *   rows draw a thin guide line ([hasGuide]).
 * - A closed folder hides the rows of everything inside it; while a folder is dragged its rows
 *   hide too (it moves as one unit with its whole block, [movedBlock]).
 * - Clipping is per level ([clipInfo]): a clipping unit clips to the nearest non-clipping sibling
 *   below it in the same folder, exactly as `FolderComposite` groups the level; a folder's bottom
 *   unit has no base and gets a greyed mark.
 *
 * The tree is read through parent ids ([Tree]), never through the contiguity of the blocks: the
 * window's local drag order moves a block without changing any parent.
 */
internal object LayerTreeRows {
    /** Indent of one folder level (dp). */
    const val INDENT_DP = 12f

    /** The indent stops growing at this many levels (48 dp). */
    const val MAX_INDENT_LEVELS = 4

    /** The indent (dp) of a row [depth] folders deep. */
    fun indent(depth: Int): Float = depth.coerceIn(0, MAX_INDENT_LEVELS) * INDENT_DP

    /** Rows deeper than the indent shows draw a thin guide line at its edge. */
    fun hasGuide(depth: Int): Boolean = depth > MAX_INDENT_LEVELS

    /**
     * The folders above each layer, by parent id (any order of the layers): [depth], the inherited
     * eye and lock, and whether the row is listed ([shown]: no closed folder above it, and not
     * inside the folder being dragged, [draggedId]).
     */
    class Tree(layers: List<Layer>, private val draggedId: Long? = null) {
        private val byId = HashMap<Long, Layer>(layers.size * 2).also { m -> for (l in layers) m[l.id] = l }

        /** The folders [layer] is in, nearest first (a broken parent id ends the walk). */
        fun ancestors(layer: Layer): List<Layer> {
            if (layer.parentId == Layer.ROOT_ID) return emptyList()
            val out = ArrayList<Layer>(4)
            var p = layer.parentId
            // Parents never repeat in a sane tree; the bound stops a broken one.
            while (p != Layer.ROOT_ID && out.size <= LayerTree.MAX_DEPTH) {
                val f = byId[p] ?: break
                if (!f.isFolder || out.any { it === f }) break
                out += f
                p = f.parentId
            }
            return out
        }

        fun depth(layer: Layer): Int = ancestors(layer).size

        /** A folder above [layer] is hidden (its own eye is not read). */
        fun hiddenByFolder(layer: Layer): Boolean = ancestors(layer).any { !it.visible }

        /** A folder above [layer] is locked (its own lock is not read). */
        fun lockedByFolder(layer: Layer): Boolean = ancestors(layer).any { it.locked }

        /** The row of [layer] is listed: no closed folder above it, and not inside the dragged folder. */
        fun shown(layer: Layer): Boolean = ancestors(layer).none { !it.folderOpen || it.id == draggedId }
    }

    /**
     * The clip mark of the row of the flat index [i] of [layers] (bottom first), per level: see
     * [ClipInfo]. [ClipInfo.continuesAbove] and [ClipInfo.lowestInGroup] are only set where the
     * neighbouring row the bracket joins is the one shown next to it (not the rows of an open
     * folder in between).
     */
    fun clipInfo(layers: List<Layer>, i: Int): ClipInfo {
        if (i !in layers.indices) return ClipInfo.NONE
        val l = layers[i]
        if (!FolderComposite.isClipped(layers, i)) {
            val noBase = l.clipping && !l.isAdjustmentLayer && l.parentId != Layer.ROOT_ID
            return if (noBase) ClipInfo(clipped = false, baseIndex = -1, continuesAbove = false, lowestInGroup = false, noBase = true) else ClipInfo.NONE
        }
        // The sibling unit below (isClipped guarantees one), then down to the first that does not clip.
        val below = LayerTree.block(layers, i).first - 1
        var base = below
        while (base >= 0 && FolderComposite.isClipped(layers, base)) base = LayerTree.block(layers, base).first - 1
        val above = siblingAbove(layers, i)
        // The sibling above shows directly above this row unless it is an open folder with rows.
        val continuesAbove = above >= 0 && FolderComposite.isClipped(layers, above) && (above == i + 1 || !layers[above].folderOpen)
        // This row's own rows (an open folder) come between it and the unit below.
        val ownRows = l.isFolder && l.folderOpen && LayerTree.descendantCount(layers, i) > 0
        return ClipInfo(
            clipped = true,
            baseIndex = base,
            continuesAbove = continuesAbove,
            lowestInGroup = !ownRows && !FolderComposite.isClipped(layers, below),
        )
    }

    /** The flat index of the top of the sibling unit directly above [i] (its block), or -1. */
    fun siblingAbove(layers: List<Layer>, i: Int): Int {
        val pid = layers[i].parentId
        var j = i + 1
        while (j < layers.size && layers[j].parentId != pid) {
            if (layers[j].id == pid) return -1
            j++
        }
        return if (j < layers.size) j else -1
    }

    /**
     * The window's local drag order (top first, every layer) after the dragged unit passed the
     * listed row [to] (indices among the rows [shown] lists, the dragged unit at [from]):
     * dragged down, the unit goes below the row together with the rows hidden inside it (a
     * closed folder's block; an open folder's own row only, so the unit lands as its top child);
     * dragged up, it goes right above the row. The dragged unit is [draggedId]'s row and the
     * [blockSize] − 1 rows after it (its descendants). Unchanged when the indices do not match.
     */
    fun movedBlock(order: List<Layer>, shown: (Layer) -> Boolean, draggedId: Long, blockSize: Int, from: Int, to: Int): List<Layer> {
        if (from == to) return order
        val visible = order.filter(shown)
        val dragged = visible.getOrNull(from) ?: return order
        val target = visible.getOrNull(to) ?: return order
        if (dragged.id != draggedId || target === dragged) return order
        val p = order.indexOfFirst { it === dragged }
        val size = blockSize.coerceIn(1, order.size - p)
        val block = order.subList(p, p + size)
        val rest = ArrayList<Layer>(order.size - size).apply {
            addAll(order.subList(0, p))
            addAll(order.subList(p + size, order.size))
        }
        val t = rest.indexOfFirst { it === target }
        if (t < 0) return order
        var at = t
        if (to > from) {
            var e = t
            while (e + 1 < rest.size && !shown(rest[e + 1])) e++
            at = e + 1
        }
        return ArrayList<Layer>(order.size).apply {
            addAll(rest.subList(0, at))
            addAll(block)
            addAll(rest.subList(at, rest.size))
        }
    }

    /** The flat index (bottom first) the top of [layer]'s unit has in the local top-first [order]; -1 when absent. */
    fun flatIndexIn(order: List<Layer>, layer: Layer): Int {
        val p = order.indexOfFirst { it === layer }
        return if (p < 0) -1 else order.size - 1 - p
    }
}
