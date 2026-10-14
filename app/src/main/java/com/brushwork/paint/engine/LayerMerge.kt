package com.brushwork.paint.engine

import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import com.brushwork.paint.EditorController
import com.brushwork.paint.LayerListEvent
import com.brushwork.paint.LayerListKind
import com.brushwork.paint.EditEvent
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.ui.common.FolderLabels
import com.brushwork.paint.ui.common.LayerLabels19
import com.brushwork.paint.ui.common.StabilityLabels19
import com.brushwork.paint.vector.VectorLayerOps
import kotlin.math.ceil
import kotlin.math.floor

/** v1.9 (design 2.4.6): what merging two layers keeps (see [LayerMerge.kindOf]). */
enum class MergeKind { TEXT_ITEMS, VECTOR_OBJECTS, RASTER }

/**
 * v1.9 (design 2.4.6; owner A4): "Merge down", moved out of [EditorController], which keeps its
 * `mergeDown` as a one-line delegate (callers and tests do not change). R19: two text layers keep
 * their texts ([MergeKind.TEXT_ITEMS]) and two vector layers their objects
 * ([VectorLayerOps.mergeVector]) when the result looks the same ([kindOf]); anything else is
 * flattened into the layer below through a temporary two-layer document, so the result matches
 * the screen. Every kind is ONE step "Merge down". Never draws the paper (I21).
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
        // v1.9 (R19): two text layers keep their texts.
        if (kindOf(layer, lower) == MergeKind.TEXT_ITEMS && mergeTexts(layer, lower)) {
            c.queueLayerList(LayerListEvent(LayerListKind.MERGED, layer, lower, MERGE_LABEL))
            return
        }
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
     * What merging [upper] into [lower] (the layer right below it) keeps: [MergeKind.VECTOR_OBJECTS]
     * where v1.8 already merges vector onto vector (the guard of [VectorLayerOps.mergeVector],
     * [VectorLayerOps.mergesAsObjects]); R19: [MergeKind.TEXT_ITEMS] for two text layers that
     * [textWhy] lets through; else [MergeKind.RASTER].
     */
    fun kindOf(upper: Layer, lower: Layer): MergeKind = when {
        VectorLayerOps.mergesAsObjects(c, upper, lower) -> MergeKind.VECTOR_OBJECTS
        upper.isTextLayer && lower.isTextLayer && adjacent(upper, lower) && textWhyOf(upper, lower) == null -> MergeKind.TEXT_ITEMS
        else -> MergeKind.RASTER
    }

    /** True when [lower] is the row right below [upper] (the pair "Merge down" works on). */
    private fun adjacent(upper: Layer, lower: Layer): Boolean {
        val idx = c.doc.indexOf(upper)
        return idx > 0 && c.doc.layers[idx - 1] === lower
    }

    /**
     * [textWhy] of the text layers [upper] and [lower]; [UNREADABLE] when either holds no text that
     * decodes (such a layer is merged as pixels, as in v1.8).
     */
    private fun textWhyOf(upper: Layer, lower: Layer): String? {
        val above = TextCodec.decodeAll(upper.textData)
        val below = TextCodec.decodeAll(lower.textData)
        if (above.isEmpty() || below.isEmpty()) return UNREADABLE
        return textWhy(upper, lower, above, below)
    }

    /**
     * R19: why the texts [above] of [upper] can't join the texts [below] of [lower] (a
     * [LayerLabels19] WHY_* text), or null when they can. Both layers Normal, without a mask,
     * clipping or copies; no text a frame of a story or wrapped around a picture (I24: those stay
     * alone in their layer); at most [TextCodec.MAX_ITEMS] texts in all; the same opacity, and
     * under 100 % no text of one layer meeting a text of the other (the merged layer's one opacity
     * would show their overlap differently). Texts meet when their paint bounds share a pixel.
     */
    private fun textWhy(upper: Layer, lower: Layer, above: List<TextItem>, below: List<TextItem>): String? {
        if (upper.mask != null || upper.maskSpec != null || lower.mask != null || lower.maskSpec != null) return LayerLabels19.WHY_MASK
        if (upper.blendMode != LayerBlendMode.NORMAL || lower.blendMode != LayerBlendMode.NORMAL) return LayerLabels19.WHY_BLEND
        if (upper.clipping || lower.clipping) return LayerLabels19.WHY_CLIP
        if (upper.array != null || lower.array != null) return LayerLabels19.WHY_COPIES
        if (above.any { it.threaded || it.wrap.isOn } || below.any { it.threaded || it.wrap.isOn }) return LayerLabels19.WHY_FRAMES
        if (above.size + below.size > TextCodec.MAX_ITEMS) return LayerLabels19.WHY_TOO_MANY
        if (upper.opacity.compareTo(lower.opacity) != 0) return WHY_OPACITY
        if (lower.opacity < 1f) {
            val tops = textPixelRects(above)
            val bottoms = textPixelRects(below)
            if (tops.any { t -> bottoms.any { b -> Rect.intersects(t, b) } }) return LayerLabels19.WHY_OVERLAP
        }
        return null
    }

    /** The pixels each of [items] may paint (its paint bounds rounded out); texts that paint nothing are left out. */
    private fun textPixelRects(items: List<TextItem>): List<Rect> = items.mapNotNull { item ->
        val prep = TextRenderer.prepare(item)
        if (prep.isEmpty) null else pixelRect(prep.docBounds(item)).takeUnless { it.isEmpty }
    }

    /**
     * R19 [MergeKind.TEXT_ITEMS]: the texts of [upper] join those of [lower] on top of them, in
     * picture order (`TextCodec.encodeAll` keeps or assigns their ids); [lower] keeps its name and
     * opacity. The merged pixels are [upper]'s drawn over [lower]'s where [upper]'s texts paint
     * (one SRC_OVER per pixel: the picture before the merge, as the texts of the two layers share
     * no pixel under 100 %). ONE step "Merge down" that undo restores; false (nothing done) when
     * the texts don't decode or the pixels don't fit in memory.
     */
    private fun mergeTexts(upper: Layer, lower: Layer): Boolean {
        val above = TextCodec.decodeAll(upper.textData)
        val below = TextCodec.decodeAll(lower.textData)
        if (above.isEmpty() || below.isEmpty() || above.size + below.size > TextCodec.MAX_ITEMS) return false
        val data = TextCodec.encodeAll(below + above)
        val area = Rect()
        for (r in textPixelRects(above)) area.union(r)
        return mergeData(upper, lower, lower.dataSnapshot().copy(text = data), area)
    }

    /**
     * Stores [after] as [lower]'s data and draws [upper]'s pixels over [lower]'s within [area]
     * (document px), then removes [upper]: ONE step "Merge down" (as in [VectorLayerOps.mergeVector],
     * a locked or hidden lower layer is merged into as well). False, with nothing recorded, when
     * the pixels don't fit in memory.
     */
    private fun mergeData(upper: Layer, lower: Layer, after: LayerData, area: Rect): Boolean {
        val doc = c.doc
        if (!area.intersect(0, 0, doc.width, doc.height)) area.setEmpty()
        var ok = false
        c.groupUndo(MERGE_LABEL) {
            val before = lower.dataSnapshot()
            val dataAction = LayerDataAction(MERGE_LABEL, lower, before, after)
            if (!area.isEmpty) {
                // The data changes with the pixels: the edit keeps it (preserveData).
                val rec = c.beginEdit(lower, EditTarget.CONTENT).also { it.preserveData = true }
                try {
                    rec.touch(area)
                    Canvas(lower.bitmap).drawBitmap(upper.bitmap, area, area, null)
                } catch (e: OutOfMemoryError) {
                    rec.abort()
                    return@groupUndo
                }
                lower.restoreData(after)
                if (!c.commitEdit(rec, MERGE_LABEL, listOf(dataAction))) pushData(lower, dataAction)
            } else {
                lower.restoreData(after)
                pushData(lower, dataAction)
            }
            val remove = RemoveLayerAction(upper, doc.indexOf(upper), MERGE_LABEL)
            remove.redo(c)
            c.pushUndo(remove)
            c.structural { doc.activeLayerIndex = doc.indexOf(lower) }
            ok = true
        }
        return ok
    }

    /** Records [action] (already applied to [layer]) as a data change: saved, redrawn, reported. */
    private fun pushData(layer: Layer, action: LayerDataAction) {
        layer.markChanged()
        c.pushUndo(action)
        c.notifyLayersChanged()
        c.queueEdit(EditEvent(layer, EditTarget.CONTENT, null, MERGE_LABEL))
    }

    /** [r] rounded out to whole pixels (empty for an empty or NaN [r]). */
    private fun pixelRect(r: RectF): Rect =
        if (!(r.left < r.right && r.top < r.bottom)) Rect() else Rect(floor(r.left).toInt(), floor(r.top).toInt(), ceil(r.right).toInt(), ceil(r.bottom).toInt())

    /**
     * Why kindOf is RASTER for two data layers, as a LayerLabels19.WHY_* text; null when either layer is raster.
     * Non-null means the layers panel shows the merge dialog (R19) before calling mergeDown. F1b: null.
     */
    @Suppress("UNUSED_PARAMETER")
    fun rasterReason(upper: Layer, lower: Layer): String? = null

    private companion object {
        const val MERGE_LABEL = "Merge down"

        /**
         * Stand-in for a `LayerLabels19.WHY_OPACITY` entry (LeadRequest): R19 names no reason for two
         * text layers of different opacities, which can't merge as texts (one layer, one opacity).
         */
        const val WHY_OPACITY = "the layers have different opacities"

        /** [textWhyOf] of a text layer whose data does not decode: pixels, with no reason to show. */
        const val UNREADABLE = ""
    }
}
