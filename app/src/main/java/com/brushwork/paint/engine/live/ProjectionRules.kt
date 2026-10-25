package com.brushwork.paint.engine.live

import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.FolderComposite
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer

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
}
