package com.brushwork.paint.tools.mask

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
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
 *
 * v1.6 (§3.1b): a [preview] no longer bumps `layersVersion` (that recomposed the whole layer UI
 * per slider sample); the sheet follows [version] instead, the layer UI refreshes at [flush], or at
 * once only when the layer's name changes (the automatic name of a new effect). The canvas is
 * redrawn through the live adjustment session (`controller.liveAdjust.touch`, then `end` when the
 * edit is recorded or put back, or when a slider is let go: [settled]).
 */
class AdjustmentEdit(private val c: EditorController, val layer: Layer) : DeferredStep {
    private var specStart: AdjustmentSpec? = layer.adjustment
    private var opacityStart: Float = layer.opacity
    private var nameStart: String = layer.name
    private var registered = false

    /** True while changes wait to be recorded. */
    val isPending: Boolean get() = registered

    /**
     * Bumped whenever this edit changes what the layer shows (Compose state): the Adjust sheet
     * reads it, so its controls follow every [preview] without the layer UI recomposing.
     */
    var version by mutableIntStateOf(0)
        private set

    /** Shows [spec] / [opacity] / [name] now (no step yet). */
    fun preview(spec: AdjustmentSpec? = layer.adjustment, opacity: Float = layer.opacity, name: String = layer.name) {
        if (c.doc.indexOf(layer) < 0 || c.doc.effectiveLocked(layer)) return
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
        val renamed = name != layer.name
        layer.adjustment = spec
        layer.opacity = o
        layer.name = name
        version++
        // The layer UI shows the name: only a new name refreshes it now (§3.1b).
        if (renamed) c.notifyLayersChanged()
        c.liveAdjust.touch(layer, MaskEdits.effectRegion(c, layer))
    }

    /**
     * A slider or editor drag was let go (the step is still recorded later): the canvas refines
     * to the exact image now instead of 150 ms later.
     */
    fun settled() {
        if (c.doc.indexOf(layer) >= 0) c.liveAdjust.end(layer)
    }

    /** Records the pending changes as one step (nothing when there are none or they cancel out). */
    override fun flush() {
        if (!registered) return
        c.removeDeferredStep(this)
        registered = false
        // A layer that is gone can't take a step (its removal recorded the pending change first).
        if (c.doc.indexOf(layer) < 0) return
        // The live edit is over: the canvas refines to exact, the layer UI shows the result.
        c.liveAdjust.end(layer)
        c.notifyLayersChanged()
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
        version++
        c.notifyLayersChanged()
        if (c.doc.indexOf(layer) < 0) return
        c.liveAdjust.touch(layer, MaskEdits.effectRegion(c, layer))
        c.liveAdjust.end(layer)
    }

    companion object {
        const val LABEL = "Edit adjustment"
    }
}
