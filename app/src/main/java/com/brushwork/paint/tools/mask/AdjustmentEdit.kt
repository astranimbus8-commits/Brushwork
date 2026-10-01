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
        if (c.doc.indexOf(layer) < 0) return
        if (spec == layer.adjustment && opacity == layer.opacity && name == layer.name) return
        layer.adjustment = spec
        layer.opacity = opacity.coerceIn(0f, 1f)
        layer.name = name
        if (!registered) {
            c.addDeferredStep(this)
            registered = true
        }
        c.notifyLayersChanged()
        c.invalidateDoc(MaskEdits.effectRegion(c, layer))
    }

    /** Records the pending changes as one step (nothing when they cancel out). */
    override fun flush() {
        if (registered) {
            c.removeDeferredStep(this)
            registered = false
        }
        val spec = layer.adjustment; val opacity = layer.opacity; val name = layer.name
        if (spec == specStart && opacity == opacityStart && name == nameStart) return
        val action = AdjustmentAction(LABEL, layer, specStart, spec, opacityStart, opacity, nameStart, name)
        specStart = spec; opacityStart = opacity; nameStart = name
        layer.markChanged()
        c.pushUndo(action)
    }

    /** Puts back what the edit changed (no step). */
    fun revert() {
        if (registered) {
            c.removeDeferredStep(this)
            registered = false
        }
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
