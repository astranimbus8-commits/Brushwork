package com.brushwork.paint.masks

import android.graphics.Bitmap
import android.graphics.Rect
import com.brushwork.paint.EditEvent
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.CompositeAction
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.engine.LayerDataAction
import com.brushwork.paint.engine.LayerPropsAction
import com.brushwork.paint.engine.MaskChangeAction
import com.brushwork.paint.engine.PixelEditRecorder
import com.brushwork.paint.engine.UndoAction
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerData

/*
 * Undo steps of editable masks and adjustment layers (v1.5 §4.3b; owned by A5).
 *
 * A mask spec change is stored as DATA and the mask is re-rendered on undo / redo (a gradient
 * touches the whole mask: tiles would cost 10 MB per step at 1080 x 2408, V6). When re-rendering
 * the changed area would take longer than [MaskEdits.TILE_THRESHOLD_MS] (brush-heavy specs), or
 * the old mask is no spec rendering (a painted mask being replaced), the step records the mask
 * tiles instead and undo swaps them. Brush strokes painted live record their tiles as they paint.
 */

/**
 * Data-only step of an editable mask: [layer]'s data goes from [before] to [after] (their mask
 * specs differ) and the mask bitmap is re-rendered from the restored spec within [region] (where
 * the two renderings can differ). The mask pixels must equal the rendering of the current spec
 * when the step runs (I1).
 */
class MaskSpecAction(
    override val label: String,
    private val layer: Layer,
    private val before: LayerData,
    private val after: LayerData,
    region: Rect,
) : UndoAction {
    private val region = Rect(region)
    override val byteSize: Long = before.approxBytes() + after.approxBytes()

    private fun set(c: EditorController, d: LayerData) {
        layer.restoreData(d)
        val mask = layer.mask
        val spec = d.maskSpec
        if (mask != null && spec != null) MaskSpecs.renderInto(mask, spec, region)
        layer.markChanged()
        c.notifyLayersChanged()
        c.invalidateDoc(region)
    }

    override fun undo(c: EditorController) = set(c, before)
    override fun redo(c: EditorController) = set(c, after)
}

/**
 * Data-only step of an adjustment layer's effect: its [AdjustmentSpec], opacity ("Amount") and
 * name go from the "before" to the "after" values. Only these three fields are touched (the step
 * may be recorded after another action changed other properties, see `DeferredStep`).
 */
class AdjustmentAction(
    override val label: String,
    private val layer: Layer,
    private val specBefore: AdjustmentSpec?,
    private val specAfter: AdjustmentSpec?,
    private val opacityBefore: Float,
    private val opacityAfter: Float,
    private val nameBefore: String,
    private val nameAfter: String,
) : UndoAction {
    override val byteSize: Long = 256L

    private fun set(c: EditorController, spec: AdjustmentSpec?, opacity: Float, name: String) {
        layer.adjustment = spec
        layer.opacity = opacity
        layer.name = name
        layer.markChanged()
        c.notifyLayersChanged()
        c.invalidateDoc(MaskEdits.effectRegion(c, layer))
    }

    override fun undo(c: EditorController) = set(c, specBefore, opacityBefore, nameBefore)
    override fun redo(c: EditorController) = set(c, specAfter, opacityAfter, nameAfter)
}

/** Editable-mask edits as controller operations (one undo step each, I1 / I2). */
object MaskEdits {
    /** Above this estimated re-render time (ms) a mask step keeps tiles instead of re-rendering. */
    const val TILE_THRESHOLD_MS = 150.0

    /** False (with a message) when [layer] is gone, locked or hidden. */
    fun usable(c: EditorController, layer: Layer): Boolean {
        if (c.doc.indexOf(layer) < 0) return false
        if (layer.locked) { c.toast("Layer \"${layer.name}\" is locked"); return false }
        if (!layer.visible) { c.toast("Layer \"${layer.name}\" is hidden"); return false }
        return true
    }

    /** The document area an adjustment layer's effect shows in (null = the whole document). */
    fun effectRegion(c: EditorController, layer: Layer): Rect? {
        val mask = if (layer.maskEnabled) layer.mask else null
        if (mask == null) return null
        val spec = layer.maskSpec ?: return null
        return MaskSpecs.coverageBounds(spec, c.doc.width, c.doc.height) ?: Rect()
    }

    /**
     * Sets [layer]'s editable mask to [after] and renders it (I1), as ONE undo step [label]. A
     * layer without a mask bitmap gets one (enabled) in the same step. [region] (document px) is
     * where the rendering changes (null = computed from the two specs). Brush components come
     * from [brushes] when it has them. Returns false (with a message) when the layer is gone,
     * locked or hidden, or memory is short.
     */
    fun apply(c: EditorController, layer: Layer, after: MaskSpec, label: String, region: Rect? = null, brushes: BrushSource? = null): Boolean = c.editScope {
        if (!usable(c, layer)) return@editScope false
        val mask = layer.mask ?: return@editScope addSpecMask(c, layer, after, label, brushes)
        val w = c.doc.width; val h = c.doc.height
        val before = layer.maskSpec
        if (before == after) return@editScope true
        val dataBefore = layer.dataSnapshot()
        val dataAfter = dataBefore.copy(maskSpec = after)
        val reg = Rect(region ?: MaskSpecs.changedRegion(before, after, w, h) ?: Rect())
        if (!reg.intersect(0, 0, w, h)) reg.setEmpty()
        if (reg.isEmpty) {
            // Nothing renders differently: the data only.
            layer.restoreData(dataAfter)
            layer.markChanged()
            c.pushUndo(LayerDataAction(label, layer, dataBefore, dataAfter))
            c.notifyLayersChanged()
            c.queueEdit(EditEvent(layer, EditTarget.MASK, null, label))
            return@editScope true
        }
        val heavy = before == null || MaskSpecs.estimateMillis(after, reg) > TILE_THRESHOLD_MS || MaskSpecs.estimateMillis(before, reg) > TILE_THRESHOLD_MS
        if (heavy) {
            val rec = c.beginEdit(layer, EditTarget.MASK).also { it.preserveData = true }
            try {
                rec.touch(reg)
                MaskSpecs.renderInto(mask, after, reg, brushes)
            } catch (e: OutOfMemoryError) {
                rec.abort()
                c.toast("Not enough memory for \"$label\"")
                return@editScope false
            }
            layer.restoreData(dataAfter)
            c.commitEdit(rec, label, listOf(LayerDataAction(label, layer, dataBefore, dataAfter)))
        } else {
            MaskSpecs.renderInto(mask, after, reg, brushes)
            layer.restoreData(dataAfter)
            layer.markChanged()
            c.pushUndo(MaskSpecAction(label, layer, dataBefore, dataAfter, reg))
            c.notifyLayersChanged()
            c.invalidateDoc(reg)
            c.queueEdit(EditEvent(layer, EditTarget.MASK, Rect(reg), label))
        }
        true
    }

    /**
     * Gives the mask-less [layer] an editable mask rendered from [spec] (one step [label]; the
     * mask is enabled in the same step).
     */
    private fun addSpecMask(c: EditorController, layer: Layer, spec: MaskSpec, label: String, brushes: BrushSource?): Boolean {
        val bmp: Bitmap = try {
            MaskSpecs.newMask(spec, c.doc.width, c.doc.height, brushes)
        } catch (e: OutOfMemoryError) {
            c.toast("Not enough memory for \"$label\"")
            return false
        }
        val dataBefore = layer.dataSnapshot()
        val dataAfter = dataBefore.copy(maskSpec = spec)
        val actions = ArrayList<UndoAction>()
        actions += MaskChangeAction(layer, null, bmp, label)
        if (!layer.maskEnabled) {
            val p = layer.props()
            actions += LayerPropsAction(layer, p, p.copy(maskEnabled = true), label)
        }
        actions += LayerDataAction(label, layer, dataBefore, dataAfter)
        actions.forEach { it.redo(c) }
        c.pushUndo(CompositeAction(label, actions))
        c.queueEdit(EditEvent(layer, EditTarget.MASK, null, label))
        return true
    }

    /**
     * Ends a live brush stroke painted into [rec] (a MASK recorder of [layer] whose touched tiles
     * already show [after]'s rendering): the spec becomes [after] in the same step as the tiles.
     * Returns false when nothing was painted (the data still changes then).
     */
    fun commitStroke(c: EditorController, rec: PixelEditRecorder, layer: Layer, after: MaskSpec, label: String): Boolean = c.editScope {
        rec.preserveData = true
        val dataBefore = layer.dataSnapshot()
        val dataAfter = dataBefore.copy(maskSpec = after)
        layer.restoreData(dataAfter)
        val data = LayerDataAction(label, layer, dataBefore, dataAfter)
        if (c.commitEdit(rec, label, listOf(data))) return@editScope true
        if (dataBefore != dataAfter) {
            layer.markChanged()
            c.pushUndo(data)
            c.notifyLayersChanged()
        }
        false
    }

    /**
     * "Delete mask" of the Masks tool's sheet: the mask and its spec go in one step (the effect of
     * an adjustment layer then shows everywhere). False (with a message) when [layer] is gone,
     * locked or hidden.
     */
    fun deleteMask(c: EditorController, layer: Layer): Boolean {
        if (layer.mask == null || !usable(c, layer)) return false
        c.deleteMask(layer)
        return true
    }
}
