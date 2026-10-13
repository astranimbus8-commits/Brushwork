package com.brushwork.paint.engine

import com.brushwork.paint.EditorController
import com.brushwork.paint.LayerListEvent
import com.brushwork.paint.LayerListKind
import com.brushwork.paint.EditEvent
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.ui.common.FolderLabels
import com.brushwork.paint.ui.common.StabilityLabels19
import com.brushwork.paint.vector.VectorLayerOps

/** v1.9 (design 2.4.6): what merging two layers keeps (see [LayerMerge.kindOf]). */
enum class MergeKind { TEXT_ITEMS, VECTOR_OBJECTS, RASTER }

/**
 * v1.9 (design 2.4.6; owner A4): "Merge down", moved unchanged out of [EditorController], which
 * keeps its `mergeDown` as a one-line delegate (callers and tests do not change). Two vector
 * layers keep their objects ([VectorLayerOps.mergeVector]); anything else is flattened into the
 * layer below through a temporary two-layer document, so the result matches the screen. Never
 * draws the paper (I21).
 */
internal class LayerMerge(private val c: EditorController) {

    /** Merges [layer] into the layer below it (v1.7: its sibling below; a folder merges as "Merge folder"). */
    fun mergeDown(layer: Layer) {
        if (layer.isFolder) { c.mergeFolder(layer); return }
        val idx = c.doc.indexOf(layer)
        val lower = siblingBelow(idx)
        if (lower == null) { c.toast("There is no layer below to merge into"); return }
        if (lower.isFolder) { c.toast(FolderLabels.MERGE_INTO_REFUSAL); return }
        // An adjustment layer has no pixels to receive the merge (its effect on the layers below
        // would be lost); merging one DOWN applies its effect.
        if (lower.isAdjustmentLayer) { c.toast("Layers can't be merged into an adjustment layer"); return }
        c.withToolPaused { mergeDownNow(layer) }
    }

    /** The layer directly below the row at [idx] at its own level (v1.6: `layers[idx - 1]`), or null. */
    fun siblingBelow(idx: Int): Layer? {
        val doc = c.doc
        if (idx <= 0 || idx > doc.layers.lastIndex) return null
        return doc.layers[idx - 1].takeIf { it.parentId == doc.layers[idx].parentId }
    }

    /** [mergeDown] once the tool is paused: one edit scope around [mergeDownInto]. */
    fun mergeDownNow(layer: Layer) {
        val idx = c.doc.indexOf(layer)
        val lower = siblingBelow(idx) ?: return
        if (lower.isAdjustmentLayer || lower.isFolder) return
        // v1.9 (design 2.4.13): the merged bitmap must fit (A7b's policy; DefaultPolicy admits everything).
        if (!MemoryGovernor.admit(c.doc.width.toLong() * c.doc.height * 4, "Merge down")) {
            c.toast(String.format(StabilityLabels19.NO_MEMORY_FOR, "Merge down"))
            return
        }
        c.editScope { mergeDownInto(layer, idx, lower) }
    }

    /** Merges [layer] (at [idx]) into [lower] as ONE step "Merge down" (inside the caller's edit scope). */
    fun mergeDownInto(layer: Layer, idx: Int, lower: Layer) {
        val doc = c.doc
        // Two vector layers can keep their objects (A1); otherwise the result is pixels.
        if (layer.isVectorLayer && lower.isVectorLayer && VectorLayerOps.mergeVector(c, layer, lower)) {
            c.queueLayerList(LayerListEvent(LayerListKind.MERGED, layer, lower, "Merge down"))
            return
        }
        // Flatten lower (+ its mask, opacity) and upper (with blend, opacity, mask, clipping) via a
        // temporary two-layer document so the result matches what's on screen. When both clip to
        // the same base further down, the upper one is simply drawn over the lower one here (the
        // merged layer keeps the lower layer's clipping). The views carry the adjustment and mask
        // specs: merging an adjustment layer down applies its effect to the layer below.
        val tmpDoc = Document("merge", "merge", doc.width, doc.height)
        val lowerView = Layer(-1, lower.name, lower.bitmap).also {
            it.copyPropsFrom(lower.props().copy(blendMode = LayerBlendMode.NORMAL, visible = true, clipping = false))
            it.mask = lower.mask
            it.maskSpec = lower.maskSpec
            it.adjustment = lower.adjustment
        }
        val upperView = Layer(-2, layer.name, layer.bitmap).also {
            it.copyPropsFrom(layer.props().copy(visible = true, clipping = layer.clipping && !lower.clipping))
            it.mask = layer.mask
            it.maskSpec = layer.maskSpec
            it.adjustment = layer.adjustment
        }
        tmpDoc.layers += lowerView
        tmpDoc.layers += upperView
        val merged = Compositor(tmpDoc) { null }.renderFlattened()
        val beforeBmp = lower.bitmap; val beforeMask = lower.mask; val beforeProps = lower.props()
        val dataBefore = lower.dataSnapshot()
        // The merged pixels are plain pixels: no editable data, no mask.
        val dataAfter = LayerData.NONE
        val afterProps = beforeProps.copy(opacity = 1f, maskEnabled = true)
        val replace = LambdaAction("Merge down", byteSize = beforeBmp.byteCount.toLong() + (beforeMask?.byteCount ?: 0),
            onUndo = { c -> c.structural { lower.bitmap = beforeBmp; lower.mask = beforeMask; lower.restoreData(dataBefore); lower.copyPropsFrom(beforeProps); lower.markChanged() } },
            onRedo = { c -> c.structural { lower.bitmap = merged; lower.mask = null; lower.restoreData(dataAfter); lower.editingMask = false; lower.copyPropsFrom(afterProps); lower.markChanged() } },
        )
        val remove = RemoveLayerAction(layer, idx, "Merge down")
        replace.redo(c)
        remove.redo(c)
        c.structural { doc.activeLayerIndex = doc.indexOf(lower) }
        c.pushUndo(CompositeAction("Merge down", listOf(replace, remove)))
        // A committed edit of the lower layer (text wrapped around it re-flows).
        c.queueEdit(EditEvent(lower, EditTarget.CONTENT, null, "Merge down"))
        // v1.6: the upper layer left the stack (a linked text frame heals).
        c.queueLayerList(LayerListEvent(LayerListKind.MERGED, layer, lower, "Merge down"))
    }

    /**
     * F1b: [MergeKind.VECTOR_OBJECTS] where v1.8 already merges vector onto vector (the guard of
     * [VectorLayerOps.mergeVector], [VectorLayerOps.mergesAsObjects]), else [MergeKind.RASTER].
     * A4 adds [MergeKind.TEXT_ITEMS].
     */
    fun kindOf(upper: Layer, lower: Layer): MergeKind =
        if (VectorLayerOps.mergesAsObjects(c, upper, lower)) MergeKind.VECTOR_OBJECTS else MergeKind.RASTER

    /**
     * Why kindOf is RASTER for two data layers, as a LayerLabels19.WHY_* text; null when either layer is raster.
     * Non-null means the layers panel shows the merge dialog (R19) before calling mergeDown. F1b: null.
     */
    @Suppress("UNUSED_PARAMETER")
    fun rasterReason(upper: Layer, lower: Layer): String? = null
}
