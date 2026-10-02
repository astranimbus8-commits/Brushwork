package com.brushwork.paint.tools.mask

import com.brushwork.paint.DeferredStep
import com.brushwork.paint.EditorController
import com.brushwork.paint.masks.AdjustmentAction
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.masks.MaskEdits
import com.brushwork.paint.model.Layer

/**
 * A live edit of an adjustment layer's effect (the Adjust sheet, v1.5 §4.3a): values, effect,
 * Amount (= opacity) and the automatic name preview at once with no undo step; ONE step "Edit
 * adjustment" is recorded when the edit is [flush]ed — when the sheet closes or is minimized,
 * when the Masks tool is deactivated, and (as a [DeferredStep]) before any other history push and
 * before undo / redo. Main thread.
 *
 * The "before" values are taken from the layer when a pending change begins (the first
 * [preview] after a flush), never earlier: undo / redo may have changed the layer in between, and
 * the step must go back to what the layer showed when the user started this change.
 */
class AdjustmentEdit(private val c: EditorController, val layer: Layer) : DeferredStep {
    private var specStart: AdjustmentSpec? = layer.adjustment
    private var opacityStart: Float = layer.opacity
    private var nameStart: String = layer.name
    private var registered = false

    /** True while changes wait to be recorded. */
    val isPending: Boolean get() = registered

    /** Shows [spec] / [opacity] / [name] now (no step yet). */
    fun preview(spec: AdjustmentSpec? = layer.adjustment, opacity: Float = layer.opacity, name: String = layer.name) {
        if (c.doc.indexOf(layer) < 0 || layer.locked) return
        val o = if (opacity.isFinite()) opacity.coerceIn(0f, 1f) else layer.opacity
        if (spec == layer.adjustment && o == layer.opacity && name == layer.name) return
        if (!registered) {
            // A new pending change starts from what the layer shows now.
            specStart = layer.adjustment
            opacityStart = layer.opacity
            nameStart = layer.name
            c.addDeferredStep(this)
            registered = true
        }
        layer.adjustment = spec
        layer.opacity = o
        layer.name = name
        c.notifyLayersChanged()
        c.invalidateDoc(MaskEdits.effectRegion(c, layer))
    }

    /** Records the pending changes as one step (nothing when there are none or they cancel out). */
    override fun flush() {
        if (!registered) return
        c.removeDeferredStep(this)
        registered = false
        // A layer that is gone can't take a step (its removal recorded the pending change first).
        if (c.doc.indexOf(layer) < 0) return
        val spec = layer.adjustment; val opacity = layer.opacity; val name = layer.name
        if (spec == specStart && opacity == opacityStart && name == nameStart) return
        val action = AdjustmentAction(LABEL, layer, specStart, spec, opacityStart, opacity, nameStart, name)
        specStart = spec; opacityStart = opacity; nameStart = name
        layer.markChanged()
        c.pushUndo(action)
    }

    /** Puts back what the pending change did (no step). */
    fun revert() {
        if (!registered) return
        c.removeDeferredStep(this)
        registered = false
        if (layer.adjustment == specStart && layer.opacity == opacityStart && layer.name == nameStart) return
        layer.adjustment = specStart
        layer.opacity = opacityStart
        layer.name = nameStart
        c.notifyLayersChanged()
        c.invalidateDoc(MaskEdits.effectRegion(c, layer))
    }

    companion object {
        const val LABEL = "Edit adjustment"
    }
}
