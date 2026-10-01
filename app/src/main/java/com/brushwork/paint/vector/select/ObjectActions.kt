package com.brushwork.paint.vector.select

import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.vector.VectorContent

/**
 * The Object bar's actions on the selected vector objects (v1.5 §4.9, A2): Delete, Duplicate,
 * Forward / Backward / Front / Back, Recolor, Transform and Deselect. Each edit is ONE undo step
 * through `controller.vectors.update` (data and pixels together, I1/I2); selecting is not history.
 *
 * Pending tool work is committed first (a transform of these very objects, a reopened shape or
 * path), so an action never works on objects that are still being edited (their edit session would
 * otherwise bring them back on ✓). A transform that was pending is lifted again afterwards when
 * the objects are still there, so the box stays under the finger.
 */
object ObjectActions {
    /** How far (document px, right and down) Duplicate places the copies. */
    const val DUPLICATE_OFFSET = 16f

    const val DELETE_LABEL = "Delete objects"
    const val DUPLICATE_LABEL = "Duplicate objects"
    const val RECOLOR_LABEL = "Recolor objects"
    const val RECOLOR_LINES_LABEL = "Recolor lines"

    /** Deletes the selected objects (one step); the selection is cleared. */
    fun delete(c: EditorController): Boolean = edit(c, relift = false) { layer, content, ids ->
        val after = ObjectEdits.delete(content, ids) ?: return@edit false
        apply(c, layer, after, DELETE_LABEL) { c.vectors.setSelection(null, emptySet()) }
    }

    /** Copies the selected objects [DUPLICATE_OFFSET] px right and down, on top of them (one step); the copies are selected. */
    fun duplicate(c: EditorController): Boolean = edit(c, relift = true) { layer, content, ids ->
        val (after, copies) = ObjectEdits.duplicate(content, ids, DUPLICATE_OFFSET, DUPLICATE_OFFSET) ?: return@edit false
        apply(c, layer, after, DUPLICATE_LABEL) { c.vectors.setSelection(layer, copies) }
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
     * Runs [block] on the selected objects of the active layer after committing pending tool work.
     * With [relift], a transform that was pending is started again afterwards.
     */
    private inline fun edit(c: EditorController, relift: Boolean, block: (Layer, VectorContent, Set<Long>) -> Boolean): Boolean {
        val transform = pendingTransform(c)
        val tool = c.currentTool
        if (tool.hasPendingWork) {
            tool.commit()
            c.invalidateOverlay()
        }
        val (layer, _) = VectorObjectSelection.selected(c) ?: return false
        val content = layer.vector ?: return false
        val ids = c.vectors.selectedIds
        val done = block(layer, content, ids)
        if (relift && transform != null && c.activeToolId == ToolId.TRANSFORM && !transform.hasPendingWork && c.vectors.selectedIds.isNotEmpty()) {
            transform.start()
        }
        c.invalidateOverlay()
        return done
    }

    /** Applies [after] to [layer] as one step [label]; [then] runs when it was applied. */
    private inline fun apply(c: EditorController, layer: Layer, after: VectorContent, label: String, crossinline then: () -> Unit): Boolean {
        var result: Boolean? = null
        c.vectors.update(layer, after, label) { applied ->
            result = applied
            if (applied) then()
        }
        return result ?: true
    }
}
