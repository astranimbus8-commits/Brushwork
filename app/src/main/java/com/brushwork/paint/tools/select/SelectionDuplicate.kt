package com.brushwork.paint.tools.select

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItemOps
import com.brushwork.paint.tools.transform.ContentBounds
import com.brushwork.paint.ui.common.LayerLabels19
import com.brushwork.paint.vector.select.ObjectActions
import com.brushwork.paint.vector.select.ObjectEdits
import kotlin.math.roundToInt

/**
 * v1.9 (R14; owner A4): the selection bar's [LayerLabels19.DUPLICATE], which copies what the
 * selection holds within the active layer (never a new layer: the layers panel duplicates layers).
 * One step [LayerLabels19.DUPLICATE_STEP] each, offset right and down like the object bar's
 * Duplicate ([ObjectActions.duplicateOffset]):
 * - a raster layer: the selected pixels (soft edges kept) are drawn again, offset, on the same
 *   layer, and the selection moves onto the copy, so Move or Transform takes it further. (The
 *   design floats the copy in Transform; its placement removes a layer that isn't fresh on ✕, so
 *   until it can place within a layer the copy lands at once: a LeadRequest.)
 * - a vector layer: the objects the selection touches are copied on top of them, and the copies
 *   become the object selection;
 * - a text layer: the texts that meet the selection, through [TextItemOps.duplicateItems]
 *   (offered only while [TextItemOps.available]).
 * The last two toast [LayerLabels19.DUPLICATED] with the number of copies. A legacy shape layer
 * (a pixel copy would rasterize it: I20), an adjustment layer, a folder, a layer with a live array
 * and a mask being edited are not offered ([kindOf] null).
 */
object SelectionDuplicate {
    /** What [duplicate] copies. */
    enum class Kind { PIXELS, OBJECTS, TEXTS }

    /** What [duplicate] would copy on [layer] (the active one) with the current selection; null when the bar hides it. */
    fun kindOf(c: EditorController, layer: Layer = c.activeLayer): Kind? {
        if (c.selection == null || layer.isFolder || layer.isAdjustmentLayer || layer.array != null) return null
        return when {
            layer.editingMask && layer.mask != null -> null
            layer.isVectorLayer -> Kind.OBJECTS
            layer.isTextLayer -> if (TextItemOps.available) Kind.TEXTS else null
            layer.isShapeLayer -> null
            else -> Kind.PIXELS
        }
    }

    /**
     * Duplicates the selection's content within the active layer ([kindOf]); false (with a message
     * when the layer can't be edited or the selection holds nothing to copy) when nothing was done.
     */
    fun duplicate(c: EditorController): Boolean {
        val layer = c.activeLayer
        val sel = c.selection ?: return false
        val kind = kindOf(c, layer) ?: return false
        // Pending tool work lands first (it may paint this layer or move the selection).
        val tool = c.currentTool
        if (tool.hasPendingWork) {
            tool.commit()
            c.invalidateOverlay()
        }
        if (!c.checkEditable(layer)) return false
        val current = c.selection ?: sel
        return when (kind) {
            Kind.PIXELS -> pixels(c, layer, current)
            Kind.OBJECTS -> objects(c, layer, current)
            Kind.TEXTS -> texts(c, layer, current)
        }
    }

    /** The offset of a copy in whole document pixels ([ObjectActions.duplicateOffset]). */
    fun offset(c: EditorController): Int = ObjectActions.duplicateOffset(c).roundToInt()

    /**
     * The selected pixels of [layer], drawn again [offset] px right and down on the same layer
     * (alpha lock respected), and the selection moved with them: ONE step.
     */
    private fun pixels(c: EditorController, layer: Layer, sel: Selection): Boolean {
        val doc = c.doc
        val src = Rect(sel.bounds)
        val d = offset(c)
        val dest = Rect(src).apply { offset(d, d) }
        if (src.isEmpty || !src.intersect(0, 0, doc.width, doc.height) || !dest.intersect(0, 0, doc.width, doc.height)) {
            c.toast(LayerLabels19.NOTHING_TO_DUPLICATE.format(layer.name)); return false
        }
        val copy: Bitmap
        val moved: Selection
        try {
            copy = BitmapUtils.createLayerBitmap(src.width(), src.height())
            Canvas(copy).apply { translate(-src.left.toFloat(), -src.top.toFloat()); SelectionEdits.drawSelected(this, layer.bitmap, sel) }
            moved = shifted(sel, d, doc.width, doc.height)
        } catch (e: OutOfMemoryError) {
            c.toast(LayerLabels19.NO_MEMORY); return false
        }
        var done = false
        try {
            c.groupUndo(LayerLabels19.DUPLICATE_STEP) {
                val rec = c.beginEdit(layer, EditTarget.CONTENT)
                try {
                    rec.touch(dest)
                    val paint = Paint().apply { if (layer.alphaLocked) xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) }
                    Canvas(layer.bitmap).drawBitmap(copy, (src.left + d).toFloat(), (src.top + d).toFloat(), paint)
                } catch (e: OutOfMemoryError) {
                    // The tiles saved so far go back and nothing is recorded (groupUndo finds no step).
                    rec.abort()
                    return@groupUndo
                }
                c.commitEdit(rec, LayerLabels19.DUPLICATE_STEP)
                c.setSelection(moved, recordUndo = true, label = LayerLabels19.DUPLICATE_STEP)
                done = true
            }
        } finally {
            copy.recycle()
        }
        if (!done) c.toast(LayerLabels19.NO_MEMORY)
        return done
    }

    /**
     * [sel] moved [d] px right and down (what leaves the canvas is dropped). Only the selection's
     * bounds are drawn (ALPHA_8 onto a clear ALPHA_8 copies the coverage exactly) and then scanned
     * for the new tight bounds: never a full-document byte copy or rescan (PRINT is 12 Mpx).
     */
    internal fun shifted(sel: Selection, d: Int, width: Int, height: Int): Selection {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        val from = Rect(sel.bounds)
        if (!from.intersect(0, 0, sel.width, sel.height)) return Selection.wrap(out, Rect())
        val to = Rect(from).apply { offset(d, d) }
        Canvas(out).drawBitmap(sel.mask, from, to, null)
        // What is left of the copy may hold only clear pixels along the cut: found, not assumed.
        val region = Rect(to)
        val bounds = if (region.intersect(0, 0, width, height)) ContentBounds.of(out, region = region) else null
        return Selection.wrap(out, bounds ?: Rect())
    }

    /** The objects of [layer] the selection touches, copied with the object bar's offset and selected. */
    private fun objects(c: EditorController, layer: Layer, sel: Selection): Boolean {
        c.vectors.flushPending()
        val content = layer.vector ?: return false
        val ids = c.vectors.touching(layer, sel)
        if (ids.isEmpty()) { c.toast(LayerLabels19.NOTHING_TO_DUPLICATE.format(layer.name)); return false }
        val d = offset(c).toFloat()
        val (after, copies) = ObjectEdits.duplicate(content, ids, d, d) ?: return false
        c.vectors.update(layer, after, LayerLabels19.DUPLICATE_STEP) { applied ->
            if (applied) {
                c.vectors.setSelection(layer, copies)
                c.toast(String.format(LayerLabels19.DUPLICATED, copies.size))
            }
        }
        return true
    }

    /** The texts of [layer] that meet the selection, through [TextItemOps.duplicateItems]. */
    private fun texts(c: EditorController, layer: Layer, sel: Selection): Boolean {
        val before = TextCodec.decodeAll(layer.textData).size
        val steps = c.undoManager.undoCount
        val action = TextItemOps.duplicateItems(c, layer, RectF(sel.bounds)) ?: run {
            c.toast(LayerLabels19.NOTHING_TO_DUPLICATE.format(layer.name)); return false
        }
        // (The step is ours to record when duplicateItems left it out.)
        if (c.undoManager.undoCount == steps) c.pushUndo(action)
        val made = TextCodec.decodeAll(layer.textData).size - before
        c.toast(String.format(LayerLabels19.DUPLICATED, made))
        return true
    }
}
