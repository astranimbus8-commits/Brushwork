package com.brushwork.paint.vector.convert

import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.engine.LayerConvert
import com.brushwork.paint.engine.UndoAction
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorLayers
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.lift.VectorLift
import com.brushwork.paint.vector.lift.VectorLiftProvider
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * v1.10 (R1 (a), C9): a legacy one-shape layer whose shape has live copies takes part in the side
 * picks of the Transform tool. A tap on its shape converts the layer to a vector layer in place
 * (`LayerConvert`, unchanged: the same content v1.9 joins make) WITHOUT a step, holds the
 * conversion, and hands the tap to the vector lift, whose first tap picks the side under the
 * finger. Then:
 * - ✓ (or Delete one side) records the conversion and the edit as ONE step ([fold]: inside one
 *   `groupUndo`, rendered at once); undo gives the shape layer back.
 * - ✕, an unchanged lift, leaving the tool ([release]): the conversion is taken back, the layer is
 *   the shape layer it was, and nothing is recorded. A same-spot second tap (all sides) on an
 *   unchanged side is such an end: all sides are the shape layer's v1.9 data lift.
 *
 * A shape layer without copies keeps v1.9's data lift. One conversion is held per editor.
 */
internal object LegacyShapes {
    /** The conversion of [layer] held until the edit is committed: the data [before] and [after] it, and its undo [action]. */
    private class Held(layer: Layer, val before: LayerData, val after: LayerData, val action: UndoAction) {
        private val ref = WeakReference(layer)
        val layer: Layer? get() = ref.get()
    }

    private val held = WeakHashMap<EditorController, Held>()

    /** True when [layer] is a legacy shape layer whose one shape has live copies. */
    fun hasCopies(layer: Layer): Boolean {
        if (!layer.isShapeLayer) return false
        val o = LayerConvert.shapeToVector(layer)?.objects?.singleOrNull() ?: return false
        return ObjectCopies.hasCopies(o)
    }

    /** True while [layer]'s conversion is held in [c] (a vector layer for now, a shape layer unless committed). */
    fun isHeld(c: EditorController, layer: Layer): Boolean = held[c]?.layer === layer

    /**
     * A tap at [p] (document px) by the Transform tool on the active layer, a legacy shape layer
     * with copies whose data lift has not [moved]: converts it (held, no step) and gives the tap
     * to the vector lift. False, with nothing changed, when the tap misses the shape, the layer is
     * locked or hidden, or another conversion is held.
     */
    fun tap(c: EditorController, p: Vec2, moved: Boolean, label: String): Boolean {
        val layer = c.activeLayer
        if (moved || held[c] != null || !hasCopies(layer) || !p.x.isFinite() || !p.y.isFinite()) return false
        if (c.doc.effectiveLocked(layer) || !layer.visible) return false
        val content: VectorContent = LayerConvert.shapeToVector(layer) ?: return false
        val o = content.objects.single()
        val t = c.viewTransform
        val tol = t.screenToDocLength(t.dp(VectorLiftProvider.TAP_TOLERANCE_DP))
        if (VectorOps.hitCopy(o, p, tol, -1) < 0) return false
        val before = layer.dataSnapshot()
        var action: UndoAction? = null
        LayerConvert.convert(c, layer, content, null, label) { action = it }
        val a = action
        if (a == null || !layer.isVectorLayer) return false
        held[c] = Held(layer, before, layer.dataSnapshot(), a)
        VectorLift.providerOf(c).tap(p, inside = false, moved = false)
        return true
    }

    /**
     * Runs [edit] (a commit or a delete of the lift on [layer]); while [layer]'s conversion is held,
     * the conversion and the edit are ONE step [label] (rendered at once, no render lands as a step
     * of its own), and an edit that records nothing takes the conversion back. Returns [edit]'s
     * answer (false when it was taken back).
     */
    fun fold(c: EditorController, layer: Layer, label: String, edit: () -> Boolean): Boolean {
        val h = held[c]?.takeIf { it.layer === layer } ?: return edit()
        held.remove(c)
        val vectors = c.vectors
        val policy = vectors.policy
        vectors.policy = VectorLayers.Policy.SYNC
        val mark = c.undoManager.undoCount
        var done = false
        try {
            c.groupUndo(label) {
                c.pushUndo(h.action)
                done = edit()
                vectors.flushPending()
            }
        } finally {
            vectors.policy = policy
        }
        if (done && layer.vector != null && layer.vector !== h.after.vector) return true
        // Only the conversion was recorded: take it back.
        if (c.undoManager.undoCount > mark) c.dropLastUndo()
        restore(c, layer, h)
        return false
    }

    /**
     * The lift on [layer] ended without a commit (any layer when null: the tool closed): a held
     * conversion is taken back and nothing is recorded.
     */
    fun release(c: EditorController, layer: Layer?) {
        val h = held[c] ?: return
        if (layer != null && h.layer !== layer) return
        held.remove(c)
        h.layer?.let { restore(c, it, h) }
    }

    /**
     * [layer] gets its shape data back (no step), only while it still holds the conversion's
     * content; its object selection and picked side go (it has no objects as a shape layer).
     */
    private fun restore(c: EditorController, layer: Layer, h: Held) {
        val v = c.vectors
        if (v.selectedLayer === layer) v.setSelection(null, emptySet())
        VectorLift.sideDeleted(c)
        if (c.doc.indexOf(layer) < 0 || layer.vector !== h.after.vector) return
        c.storeData(layer, layer.dataSnapshot(), h.before, TransformTool.TRANSFORM_OBJECTS_LABEL, EditTarget.CONTENT) {}
    }
}
