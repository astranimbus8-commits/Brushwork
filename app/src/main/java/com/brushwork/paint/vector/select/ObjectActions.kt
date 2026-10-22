package com.brushwork.paint.vector.select

import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.CurveKind
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.common.ObjectBarLabels110
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.convert.PathToShape
import com.brushwork.paint.vector.lift.VectorLift
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The Object bar's actions on the selected vector objects (v1.5 §4.9, A2): Delete, Duplicate,
 * Forward / Backward / Front / Back, Recolor, Transform and Deselect (v1.8: and detaching live
 * mirror copies, [detachCopies]). Each edit is ONE undo step
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
     *
     * v1.9 (design 2.4.11): this signature is frozen at Gate 1 (A9's Shape More row calls it);
     * A4 changes only the body.
     */
    fun duplicate(c: EditorController): Boolean = edit(c, relift = true) { layer, content, ids ->
        val d = duplicateOffset(c)
        val (after, copies) = ObjectEdits.duplicate(content, ids, d, d) ?: return@edit false
        apply(c, layer, after, DUPLICATE_LABEL) { c.vectors.setSelection(layer, copies) }
    }

    /**
     * Duplicate's offset (document px, whole pixels so the copies stay on the pixel grid): 16 px,
     * or the document length of 16 dp on screen when that is more (a zoomed-out large canvas).
     *
     * v1.9 (design 2.4.11): this signature is frozen at Gate 1 (A9's Shape More row calls it);
     * A4 changes only the body.
     */
    fun duplicateOffset(c: EditorController): Float {
        val t = c.viewTransform
        val onScreen = t.screenToDocLength(t.dp(DUPLICATE_OFFSET_DP))
        val d = if (onScreen.isFinite() && onScreen > 0f) max(DUPLICATE_OFFSET, onScreen.roundToInt().toFloat()) else DUPLICATE_OFFSET
        // (Never more than a tenth of the canvas: the copy stays near.)
        return minOf(d, max(DUPLICATE_OFFSET, (minOf(c.doc.width, c.doc.height) / 10).toFloat()))
    }

    /**
     * Moves the selected objects in the stacking order (one step); "Already …" when nothing would change.
     *
     * v1.9 (R19, design 2.4.11; the signature is frozen at Gate 1, as A9's Shape More row calls
     * it): Forward and Backward move each selected object past the next object whose paint bounds
     * overlap its own, or one plain step when none does ([ArrangeOrder.step]), so they say
     * "Already …" only at the top or the bottom. Front and Back are v1.5's.
     */
    fun arrange(c: EditorController, how: ObjectEdits.Arrange): Boolean = edit(c, relift = true) { layer, content, ids ->
        val after = when (how) {
            ObjectEdits.Arrange.FORWARD, ObjectEdits.Arrange.BACKWARD -> {
                val objs = content.objects
                val selected = objs.indices.filterTo(HashSet()) { objs[it].id in ids }
                val dir = if (how == ObjectEdits.Arrange.FORWARD) 1 else -1
                // The new list's object i is the old list's object perm[i].
                ArrangeOrder.step(objs.map { ObjectBounds.of(content, it) }, selected, dir)
                    ?.let { perm -> content.copy(objects = List(objs.size) { objs[perm[it]] }) }
            }
            ObjectEdits.Arrange.FRONT, ObjectEdits.Arrange.BACK ->
                ObjectEdits.arrange(content, ids, how) { a, b -> ObjectBounds.overlap(content, a, b) }
        }
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

    /**
     * v1.8 (I15, §3.1): the live copies of the selected objects become objects of their own, in
     * place (ONE step [HistoryLabels.DETACH_MIRROR_COPIES]: each source keeps its id, each copy
     * takes a new one, `ObjectCopies.detached`); every piece is selected. False, recording
     * nothing, when no selected object has copies.
     */
    fun detachCopies(c: EditorController): Boolean = edit(c, relift = true) { layer, content, ids ->
        val (after, pieces) = ObjectCopies.detached(content, ids) ?: return@edit false
        apply(c, layer, after, HistoryLabels.DETACH_MIRROR_COPIES) { c.vectors.setSelection(layer, pieces.toSet()) }
    }

    /** True when a selected object of the active layer has live copies (the Object bar then offers [detachCopies]). */
    fun hasCopiesSelected(c: EditorController): Boolean {
        val ids = c.vectors.selectedIds
        if (ids.isEmpty()) return false
        val layer = c.vectors.selectedLayer ?: return false
        val content = layer.vector ?: return false
        return content.objects.any { it.id in ids && ObjectCopies.hasCopies(it) }
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

    // ------------------------------------------------------------------ v1.10 (F0 stubs, A6 bodies)

    /**
     * v1.10 (A6): the one selected object of the selected layer; null when none or several are
     * selected (or the layer no longer holds it).
     */
    fun selectedObject(c: EditorController): VObject? {
        val ids = c.vectors.selectedIds
        if (ids.size != 1) return null
        val layer = c.vectors.selectedLayer ?: return null
        return layer.vector?.byId(ids.first())
    }

    /**
     * v1.10 (R10): whether the object bar shows Edit: exactly one object is selected and it has an
     * editor (a brush stroke [VStroke] has none). [editRefusal] says when it shows but can't open.
     */
    fun editShown(c: EditorController): Boolean {
        val o = selectedObject(c) ?: return false
        return o !is VStroke
    }

    /** v1.10 (R11): whether the object bar offers [ObjectBarLabels110.TO_SHAPE]: exactly one path is selected. */
    fun toShapeShown(c: EditorController): Boolean = selectedObject(c) is VPath

    /**
     * v1.10 (R10): why the object bar's Edit is disabled for the selection (a path of several
     * parts, a path too small to edit), shown as its toast; null = Edit can open it. The signature
     * is frozen (`FrozenApiSignatureTest`); F0 stub, A6 body: only reasons the user can act on
     * (Transform moves a path of several parts; a path of one point is deleted or redrawn).
     */
    fun editRefusal(c: EditorController): String? {
        val path = selectedObject(c) as? VPath ?: return null
        if (path.subpaths.size > 1) return ObjectBarLabels110.NO_EDITOR_PARTS
        if ((path.subpaths.firstOrNull()?.anchors?.size ?: 0) < 2) return ObjectBarLabels110.NO_EDITOR_SMALL
        return null
    }

    /**
     * v1.10 (R10): opens the one selected object in its own tool (a shape in the Shape tool, a path
     * in the Curve, Path or Polyline tool through `CurveTool.kindOf`). True when an editor opened.
     * The signature is frozen; F0 stub (opens nothing), A6 body.
     *
     * Pending tool work is committed first and the renders in flight are waited for (then true: it
     * opens once they landed); the object selection is cleared, the object's tool becomes current
     * and opens it ([ShapeTool.editShapeObject], [CurveTool.openObject]). A refused object
     * ([editRefusal]) shows the reason and opens nothing. A second tap while the first still waits
     * is ignored (false, no toast); the tools ignore one while their editor is opening. The button
     * records no undo step (the editor's ✓ does); the user stays in that tool after ✓ or ✕.
     */
    fun openInEditor(c: EditorController): Boolean {
        if (PendingRenders.isWaiting(c, EditOpening)) return false
        val o = selectedObject(c) ?: return false
        if (o is VStroke) return false
        editRefusal(c)?.let { reason ->
            c.toast(reason)
            return false
        }
        val tool = c.currentTool
        if (tool.hasPendingWork) {
            tool.commit()
            c.invalidateOverlay()
        }
        var opened = false
        val now = PendingRenders.whenIdle(c, key = EditOpening) { opened = openSelected(c) }
        return if (now) opened else true
    }

    /** [openInEditor] once nothing is in flight: the selection may have changed meanwhile. */
    private fun openSelected(c: EditorController): Boolean {
        val (layer, _) = VectorObjectSelection.selected(c) ?: return false
        // (Only the layer being worked on: the bar drops another layer's selection.)
        if (layer !== c.activeLayer) return false
        val o = selectedObject(c) ?: return false
        if (o is VStroke) return false
        editRefusal(c)?.let { reason ->
            c.toast(reason)
            return false
        }
        c.vectors.setSelection(null, emptySet())
        c.invalidateOverlay()
        return when (o) {
            is VShape -> {
                c.selectTool(ToolId.SHAPE)
                val shape = c.tools[ToolId.SHAPE] as? ShapeTool ?: return false
                c.currentTool === shape && shape.editShapeObject(layer, o.id)
            }
            is VPath -> {
                val kind = CurveTool.kindOf(o)
                val id = curveToolId(kind)
                c.selectTool(id)
                val curve = c.tools[id] as? CurveTool ?: return false
                c.currentTool === curve && curve.openObject(layer.id, o.id, kind)
            }
            else -> false
        }
    }

    /** The tool that draws paths of [kind]. */
    fun curveToolId(kind: CurveKind): ToolId = when (kind) {
        CurveKind.CURVE -> ToolId.CURVE
        CurveKind.POLYLINE -> ToolId.POLYLINE
        CurveKind.PATH -> ToolId.PATH
    }

    /** [PendingRenders] key of an [openInEditor] waiting for the renders in flight. */
    private object EditOpening

    /**
     * v1.10 (R11): turns the one selected path into a shape in place (one step
     * `ObjectBarLabels110.TO_SHAPE`), then opens the Shape tool on it. True when it was done. The
     * signature is frozen; F0 stub (changes nothing), A6 body.
     *
     * Pending tool work is committed first and the renders in flight are waited for (then true: it
     * is on its way), as for the other actions. A path [PathToShape.refusal] refuses (several
     * pieces, too small, too many points) shows the reason and changes nothing. Otherwise the
     * shape ([PathToShape.convert]) replaces the path with its id, at its place in the stacking
     * order and with its copies ([VectorContent.replaced]); once that step landed the object
     * selection is cleared, the Shape tool opens the shape ([ShapeTool.editShapeObject]) and what
     * changed on the way ([PathToShape.Result.notes]) shows as one long note, the notes joined by
     * "; ", whose "Undo" takes the step back ([EditorController.showNote]).
     */
    fun turnIntoShape(c: EditorController): Boolean = edit(c, relift = false) { layer, content, ids ->
        val path = (if (ids.size == 1) content.byId(ids.first()) else null) as? VPath ?: return@edit false
        PathToShape.refusal(path)?.let { reason ->
            c.toast(reason)
            return@edit false
        }
        val result = PathToShape.convert(path)
        val after = content.replaced(mapOf(path.id to listOf<VObject>(result.shape)))
        apply(c, layer, after, ObjectBarLabels110.TO_SHAPE) {
            c.vectors.setSelection(null, emptySet())
            c.selectTool(ToolId.SHAPE)
            val shape = c.tools[ToolId.SHAPE] as? ShapeTool
            if (shape != null && c.currentTool === shape) shape.editShapeObject(layer, path.id)
            // (After the tool opened: its own opening message never hides the note.)
            if (result.notes.isNotEmpty()) c.showNote(result.notes.joinToString(NOTE_SEPARATOR), undoable = true)
        }
    }

    /** Between the notes of one [turnIntoShape] (design R11 (a)). */
    private const val NOTE_SEPARATOR = "; "

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
