package com.brushwork.paint.vector.select

import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.lift.VectorLift
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The Object bar's actions on the selected vector objects (v1.5 §4.9, A2): Delete, Duplicate,
 * Forward / Backward / Front / Back, Recolor, Transform and Deselect. Each edit is ONE undo step
 * through `controller.vectors.update` (data and pixels together, I1/I2); selecting is not history.
 *
 * Pending tool work is committed first (a transform of these very objects, a reopened shape or
 * path), so an action never works on objects that are still being edited (their edit session would
 * otherwise bring them back on ✓). Delete while the Transform tool holds exactly these objects
 * deletes them through the transform instead (one step; the pending move goes with them). A
 * transform that was pending is lifted again afterwards when the objects are still there, so the
 * box stays under the finger.
 *
 * An edit asked for while a vector update is still rendering in the background waits for it
 * ([PendingRenders]) and then works on the landed content (never on the content before it).
 */
object ObjectActions {
    /** How far (document px, right and down) Duplicate places the copies, at least. */
    const val DUPLICATE_OFFSET = 16f

    /** Duplicate's offset on screen, at least (dp): 16 document px alone vanish on a big zoomed-out canvas. */
    const val DUPLICATE_OFFSET_DP = 16f

    const val DELETE_LABEL = "Delete objects"
    const val DUPLICATE_LABEL = "Duplicate objects"
    const val RECOLOR_LABEL = "Recolor objects"
    const val RECOLOR_LINES_LABEL = "Recolor lines"

    /** Said when an action is pressed while the previous one still waits for a render. */
    const val STILL_UPDATING = "Still updating the drawing…"

    /**
     * Deletes the selected objects (one step); the selection is cleared. True when it was done
     * (or will be, once the render in flight landed).
     */
    fun delete(c: EditorController): Boolean {
        // The Transform tool holds exactly these objects: its Delete removes them (one step,
        // instead of applying the pending move first and deleting afterwards).
        val transform = pendingTransform(c)
        val lift = VectorLift.activeLift(c)
        val sel = VectorObjectSelection.selected(c)
        if (transform != null && lift != null && sel != null && lift.layer === sel.first && lift.ids == c.vectors.selectedIds) {
            val done = transform.deleteContent()
            c.invalidateOverlay()
            return done
        }
        return edit(c, relift = false) { layer, content, ids ->
            val after = ObjectEdits.delete(content, ids) ?: return@edit false
            apply(c, layer, after, DELETE_LABEL) { c.vectors.setSelection(null, emptySet()) }
        }
    }

    /**
     * Copies the selected objects right and down ([duplicateOffset]: 16 document px, more when
     * that is less than 16 dp on screen), on top of them (one step); the copies are selected.
     */
    fun duplicate(c: EditorController): Boolean = edit(c, relift = true) { layer, content, ids ->
        val d = duplicateOffset(c)
        val (after, copies) = ObjectEdits.duplicate(content, ids, d, d) ?: return@edit false
        apply(c, layer, after, DUPLICATE_LABEL) { c.vectors.setSelection(layer, copies) }
    }

    /**
     * Duplicate's offset (document px, whole pixels so the copies stay on the pixel grid): 16 px,
     * or the document length of 16 dp on screen when that is more (a zoomed-out large canvas).
     */
    fun duplicateOffset(c: EditorController): Float {
        val t = c.viewTransform
        val onScreen = t.screenToDocLength(t.dp(DUPLICATE_OFFSET_DP))
        val d = if (onScreen.isFinite() && onScreen > 0f) max(DUPLICATE_OFFSET, onScreen.roundToInt().toFloat()) else DUPLICATE_OFFSET
        // (Never more than a tenth of the canvas: the copy stays near.)
        return minOf(d, max(DUPLICATE_OFFSET, (minOf(c.doc.width, c.doc.height) / 10).toFloat()))
    }

    /** Moves the selected objects in the stacking order (one step); "Already …" when nothing would change. */
    fun arrange(c: EditorController, how: ObjectEdits.Arrange): Boolean = edit(c, relift = true) { layer, content, ids ->
        val after = ObjectEdits.arrange(content, ids, how) { a, b -> ObjectBounds.overlap(content, a, b) }
        if (after == null) {
            c.toast(
                when (how) {
                    ObjectEdits.Arrange.FORWARD, ObjectEdits.Arrange.FRONT -> "Already in front"
                    ObjectEdits.Arrange.BACKWARD, ObjectEdits.Arrange.BACK -> "Already at the back"
                },
            )
            return@edit false
        }
        apply(c, layer, after, how.label) {}
    }

    /**
     * Paints the selected objects with the main color (one step): their lines and fills, or with
     * [linesOnly] only their lines (strokes, outlines).
     */
    fun recolor(c: EditorController, linesOnly: Boolean): Boolean = edit(c, relift = true) { layer, content, ids ->
        val after = ObjectEdits.recolor(content, ids, c.color, linesOnly)
        if (after == null) {
            val noLines = linesOnly && content.objects.none { it.id in ids && ObjectEdits.hasLines(it) }
            c.toast(if (noLines) "These objects have no lines" else "Already in this color")
            return@edit false
        }
        apply(c, layer, after, if (linesOnly) RECOLOR_LINES_LABEL else RECOLOR_LABEL) {}
    }

    /** The Transform tool lifts the selected objects (handles, Distort, Numbers, X / Y, pinch). */
    fun transform(c: EditorController) {
        val (layer, _) = VectorObjectSelection.selected(c) ?: return
        if (layer !== c.activeLayer) return
        if (c.activeToolId == ToolId.TRANSFORM) {
            val tool = c.tools[ToolId.TRANSFORM] as? TransformTool ?: return
            if (tool.hasPendingWork) tool.commit()
            tool.start()
        } else {
            c.selectTool(ToolId.TRANSFORM)
        }
        c.invalidateOverlay()
    }

    /** Nothing selected any more (a pending transform of the objects is applied first, then everything is lifted). */
    fun deselect(c: EditorController) {
        val transform = pendingTransform(c)
        if (transform != null) transform.commit()
        c.vectors.setSelection(null, emptySet())
        if (transform != null && c.activeToolId == ToolId.TRANSFORM) transform.start()
        c.invalidateOverlay()
    }

    // ------------------------------------------------------------------ helpers

    /** The Transform tool when it is current and has something lifted. */
    private fun pendingTransform(c: EditorController): TransformTool? =
        if (c.activeToolId == ToolId.TRANSFORM) (c.tools[ToolId.TRANSFORM] as? TransformTool)?.takeIf { it.hasPendingWork } else null

    /**
     * Runs [block] on the selected objects of the active layer after committing pending tool work
     * — right away, or once the updates still rendering landed (then true: it is on its way).
     * With [relift], a transform that was pending is started again afterwards.
     */
    private fun edit(c: EditorController, relift: Boolean, block: (Layer, VectorContent, Set<Long>) -> Boolean): Boolean {
        // One action waits at a time: taps while it waits would pile up surprises for later.
        if (PendingRenders.isWaiting(c, this)) {
            c.toast(STILL_UPDATING)
            return false
        }
        val transform = pendingTransform(c)
        val tool = c.currentTool
        if (tool.hasPendingWork) {
            tool.commit()
            c.invalidateOverlay()
        }
        var done = false
        val now = PendingRenders.whenIdle(c, key = this) {
            val (layer, _) = VectorObjectSelection.selected(c) ?: return@whenIdle
            // (Only the layer being worked on: the bar drops another layer's selection.)
            if (layer !== c.activeLayer) return@whenIdle
            val content = layer.vector ?: return@whenIdle
            done = block(layer, content, c.vectors.selectedIds)
            if (relift && transform != null && c.activeToolId == ToolId.TRANSFORM && !transform.hasPendingWork && c.vectors.selectedIds.isNotEmpty()) {
                // (Waits by itself when this edit renders in the background: it lifts the result.)
                transform.start()
            }
            c.invalidateOverlay()
        }
        return if (now) done else true
    }

    /** Applies [after] to [layer] as one step [label]; [then] runs when it was applied. */
    private inline fun apply(c: EditorController, layer: Layer, after: VectorContent, label: String, crossinline then: () -> Unit): Boolean {
        var result: Boolean? = null
        // Edits and lifts asked for until it landed wait for it.
        val landed = PendingRenders.begin(c)
        try {
            c.vectors.update(layer, after, label) { applied ->
                if (result == null) {
                    result = applied
                    if (applied) then()
                    landed()
                }
            }
        } catch (e: Throwable) {
            // (Never leave later edits waiting for an update that failed outright.)
            landed()
            throw e
        }
        return result ?: true
    }
}
