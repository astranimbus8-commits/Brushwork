package com.brushwork.paint.engine.live

import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.FolderComposite
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.LayerTree

/**
 * v1.10 A9 step 3 (design 2.5, J7): when the projections of the display apply.
 *
 * - The **split** of an active layer is the `StrokeBelowCache` rule ([splitFor]): the first layer
 *   of its clipping group (an adjustment layer is its own group); with folders, the layer itself
 *   when every folder it is in draws its children as is and it is not clipped; 0 or less: none.
 * - The below projection is built only when at least [MIN_LAYERS] visible units lie below the
 *   split ([belowEligible]), and only at TILES and FULL ([on]).
 */
internal object ProjectionRules {
    /** J7 (graft): the fewest visible units below the split for a below projection. */
    const val MIN_LAYERS = 8

    /** True at the levels with projections (TILES and FULL); CLASSIC keeps v1.9's `StrokeBelowCache`. */
    val on: Boolean get() = EngineConfig.level != EngineLevel.CLASSIC

    /** The flat index the stack is split at for [layer] (see the class docs); 0 or less: none. */
    fun splitFor(doc: Document, layer: Layer): Int {
        val layers = doc.layers
        val a = doc.indexOf(layer)
        if (a <= 0 || layer.isFolder) return -1
        if (doc.hasFolders) {
            return if (FolderComposite.drawnAsIs(layers, a) && !FolderComposite.isClipped(layers, a)) a else -1
        }
        var k = a
        while (k > 0 && FolderComposite.isClipped(layers, k)) k--
        return k
    }

    /**
     * The visible units of `[0, split)`: layers and folders shown (their own eye and every folder
     * above them), each clipping group counted once (by its base), adjustment layers included.
     */
    fun unitsBelow(doc: Document, split: Int): Int {
        val layers = doc.layers
        var n = 0
        for (i in 0 until minOf(split, layers.size)) {
            val l = layers[i]
            if (!l.isAdjustmentLayer && FolderComposite.isClipped(layers, i)) continue
            if (l.opacity <= 0f || !doc.effectiveVisible(l)) continue
            n++
        }
        return n
    }

    /** True when a below projection may be built for a stack split at [split]. */
    fun belowEligible(doc: Document, split: Int): Boolean = split > 0 && unitsBelow(doc, split) >= MIN_LAYERS

    // ------------------------------------------------------------------ v1.10 A9 step 7: the above projection (X5)

    /** True when the above projection may run: TILES or FULL, and the check's M5 allowed it (`EngineConfig.aboveProjection`). */
    val aboveOn: Boolean get() = on && EngineConfig.aboveProjection

    /**
     * X5 (a), (d): the first flat index above the active unit of [layer]: the layer and the units
     * clipped to it at its level (they draw every frame, with it). -1 when [layer] is a folder or
     * an adjustment layer, is clipped, or is in a folder that does not draw its children as is.
     * The result is the parent folder's own index when the active unit is the top of its level,
     * or the layer count at the top level; a cut there is a [com.brushwork.paint.engine.Compositor.isSplitBoundary].
     */
    fun aboveStart(doc: Document, layer: Layer): Int {
        val layers = doc.layers
        val a = doc.indexOf(layer)
        if (a < 0 || layer.isFolder || layer.isAdjustmentLayer) return -1
        if (!FolderComposite.drawnAsIs(layers, a) || FolderComposite.isClipped(layers, a)) return -1
        val n = layers.size
        val parent = layer.parentId
        var u = a + 1
        while (u < n) {
            if (layers[u].id == parent) break
            // The next sibling unit's top: its block's descendants come first.
            var t = u
            while (t < n && layers[t].parentId != parent) t++
            if (t >= n) break
            val l = layers[t]
            if (!l.clipping || l.isAdjustmentLayer) break
            u = t + 1
        }
        return u
    }

    /**
     * X5 (b), (c): true when every unit of `[u, n)` above [layer]'s active unit ([aboveStart]) draws
     * onto what is below it as source-over, so their composite onto transparency drawn over the
     * rest equals the full draw within the 8-bit rounding: at the active layer's level and then
     * at each folder level it is in (those folders draw their children as is, (a)), each visible
     * unit is a Normal layer (pixel, vector, text or shape), a clip group whose base draws Normal
     * (its clipped units may blend any way: the group is isolated), an isolated folder drawn
     * Normal (whatever its content), or a pass-through folder at 100 % whose children satisfy
     * this (below 100 % the A8 coverage lerp rounds up to 3 LSB away: not eligible), and no
     * visible adjustment layer is among them (at a level or inside a pass-through folder). Hidden
     * units and units at 0 % draw nothing and are passed over. At least [MIN_LAYERS] visible units
     * must lie above ([unitsAbove]).
     */
    fun aboveEligible(doc: Document, layer: Layer, u: Int): Boolean {
        val layers = doc.layers
        val a = doc.indexOf(layer)
        if (a < 0 || u <= a || u > layers.size) return false
        if (unitsAbove(doc, u) < MIN_LAYERS) return false
        // The active layer's level from u, then each folder it is in from the unit above that folder.
        var level = layer.parentId
        var from = u
        while (true) {
            val end = if (level == Layer.ROOT_ID) layers.size else levelEnd(layers, from, level)
            if (!normalUnits(layers, from until end, level)) return false
            if (level == Layer.ROOT_ID) return true
            // end is the folder's own index: go out to its level, from the unit above it.
            val folder = layers.getOrNull(end) ?: return false
            level = folder.parentId
            from = end + 1
        }
    }

    /** The visible units of `[u, n)`, counted as [unitsBelow] counts them. */
    fun unitsAbove(doc: Document, u: Int): Int {
        val layers = doc.layers
        var n = 0
        for (i in maxOf(u, 0) until layers.size) {
            val l = layers[i]
            if (!l.isAdjustmentLayer && FolderComposite.isClipped(layers, i)) continue
            if (l.opacity <= 0f || !doc.effectiveVisible(l)) continue
            n++
        }
        return n
    }

    /** The flat index of the folder whose id is [parentId], at or after [from] (its children come before it). */
    private fun levelEnd(layers: List<Layer>, from: Int, parentId: Long): Int {
        var i = maxOf(from, 0)
        while (i < layers.size && layers[i].id != parentId) i++
        return i
    }

    /** X5 (b), (c) for the units of one level inside [range] (see [aboveEligible]). */
    private fun normalUnits(layers: List<Layer>, range: IntRange, parentId: Long): Boolean {
        if (range.isEmpty()) return true
        val units = LayerTree.units(layers, range, parentId)
        var i = 0
        while (i < units.size) {
            val unit = units[i]
            val top = layers[unit.last]
            if (top.isAdjustmentLayer) {
                if (top.visible && top.opacity > 0f) return false
                i++
                continue
            }
            var j = i + 1
            while (j < units.size && layers[units[j].last].let { it.clipping && !it.isAdjustmentLayer }) j++
            if (top.visible && top.opacity > 0f) {
                val spec = top.folder
                when {
                    // A clip group: isolated, drawn with its base's blend (a pass-through base draws Normal).
                    j > i + 1 -> if (FolderComposite.drawnBlend(top) != LayerBlendMode.NORMAL) return false
                    spec == null -> if (top.blendMode != LayerBlendMode.NORMAL) return false
                    !spec.passThrough -> if (top.blendMode != LayerBlendMode.NORMAL) return false
                    // A pass-through folder below 100 % draws through the coverage lerp (A8): its 8-bit
                    // rounding differs from source-over by up to 3 LSB (AboveProjectionErrorTest).
                    top.opacity < 1f -> return false
                    else -> if (!normalUnits(layers, unit.first until unit.last, top.id)) return false
                }
            }
            i = j
        }
        return true
    }
}
