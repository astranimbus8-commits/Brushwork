package com.brushwork.paint.ui.layers

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.model.Layer

/**
 * Layer operations as offered by the layers panel. Wraps the controller with the guards it leaves
 * to callers (lock checks, memory failures) and implements Clear/Fill so they respect the edit
 * target: while a mask is being edited they paint the mask (black = hidden, fill = the gray
 * luminance of the color). Only the selection bounds are snapshotted for undo when a selection
 * exists. Main thread only.
 *
 * Policy: operations that change pixels require [EditorController.checkEditable] (not locked, not
 * hidden); operations that change a mask's existence require the layer to be unlocked; property
 * changes (visibility, opacity, blend, clipping, locks, name) are always allowed.
 */
object LayerOps {

    /** False (with a message) when [layer] is locked. */
    fun ensureUnlocked(c: EditorController, layer: Layer): Boolean {
        if (layer.locked) { c.toast("Layer \"${layer.name}\" is locked"); return false }
        return true
    }

    /** Runs a controller operation that allocates full-size bitmaps, reporting memory failures. */
    inline fun guardMemory(c: EditorController, label: String, block: () -> Unit) {
        try {
            block()
        } catch (e: OutOfMemoryError) {
            c.toast("Not enough memory for \"$label\"")
        }
    }

    fun addLayer(c: EditorController) = guardMemory(c, "Add layer") { c.addLayer() }

    fun duplicate(c: EditorController, layer: Layer) = guardMemory(c, "Duplicate layer") { c.duplicateLayer(layer) }

    /** Whether [layer] has a layer below it to merge into. */
    fun canMergeDown(c: EditorController, layer: Layer): Boolean = c.doc.indexOf(layer) > 0

    fun mergeDown(c: EditorController, layer: Layer) {
        val idx = c.doc.indexOf(layer)
        if (idx <= 0) { c.toast("There is no layer below to merge into"); return }
        val lower = c.doc.layers[idx - 1]
        if (!c.checkEditable(layer)) return
        if (!ensureUnlocked(c, lower)) return
        guardMemory(c, "Merge down") { c.mergeDown(layer) }
    }

    fun flip(c: EditorController, layer: Layer, horizontal: Boolean) =
        guardMemory(c, if (horizontal) "Flip horizontal" else "Flip vertical") { c.flipLayer(layer, horizontal) }

    /** Label for the Clear action given the current edit target of [layer]. */
    fun clearLabel(c: EditorController, layer: Layer): String =
        if (c.editTargetOf(layer) == EditTarget.MASK) "Clear mask (hide)" else "Clear"

    /** Label for the Fill action given the current edit target of [layer]. */
    fun fillLabel(c: EditorController, layer: Layer): String =
        if (c.editTargetOf(layer) == EditTarget.MASK) "Fill mask with color's gray" else "Fill with current color"

    /**
     * Clears the layer's pixels (or, while editing the mask, hides everything by painting the mask
     * black), limited to the selection when there is one.
     */
    fun clear(c: EditorController, layer: Layer): Boolean {
        if (!c.checkEditable(layer)) return false
        val target = c.editTargetOf(layer)
        val sel = c.selection
        val rect = if (sel != null) Rect(sel.bounds) else Rect(c.doc.bounds)
        if (rect.isEmpty) return false
        val rec = c.beginEdit(layer, target)
        rec.touch(rect)
        val bmp = if (target == EditTarget.MASK) layer.mask!! else layer.bitmap
        if (target == EditTarget.MASK) {
            if (sel == null) bmp.eraseColor(MASK_HIDDEN)
            else Canvas(bmp).drawBitmap(sel.mask, 0f, 0f, Paint().apply { color = MASK_HIDDEN })
        } else {
            if (sel == null) bmp.eraseColor(0)
            else Canvas(bmp).drawBitmap(sel.mask, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
        }
        return c.commitEdit(rec, if (target == EditTarget.MASK) "Clear mask" else "Clear")
    }

    /**
     * Fills the layer (or the selection) with [color]. Respects alpha lock on content. While
     * editing the mask, fills the mask with the color's luminance.
     */
    fun fill(c: EditorController, layer: Layer, color: Int = c.color): Boolean {
        if (!c.checkEditable(layer)) return false
        val target = c.editTargetOf(layer)
        val sel = c.selection
        val rect = if (sel != null) Rect(sel.bounds) else Rect(c.doc.bounds)
        if (rect.isEmpty) return false
        val rec = c.beginEdit(layer, target)
        rec.touch(rect)
        if (target == EditTarget.MASK) {
            val gray = ColorUtils.gray(ColorUtils.luminance(color))
            val bmp = layer.mask!!
            if (sel == null) bmp.eraseColor(gray)
            else Canvas(bmp).drawBitmap(sel.mask, 0f, 0f, Paint().apply { this.color = gray })
        } else {
            val canvas = Canvas(layer.bitmap)
            val alphaLock = layer.alphaLocked
            if (sel == null) {
                if (alphaLock) canvas.drawColor(color, PorterDuff.Mode.SRC_ATOP) else canvas.drawColor(color, PorterDuff.Mode.SRC_OVER)
            } else {
                val p = Paint().apply {
                    this.color = color
                    if (alphaLock) xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
                }
                canvas.drawBitmap(sel.mask, 0f, 0f, p)
            }
        }
        return c.commitEdit(rec, if (target == EditTarget.MASK) "Fill mask" else "Fill")
    }

    // ------------------------------------------------------------------ masks

    fun addMask(c: EditorController, layer: Layer, fromSelection: Boolean) {
        if (layer.mask != null || !ensureUnlocked(c, layer)) return
        guardMemory(c, "Add mask") { c.addMask(layer, fromSelection = fromSelection && c.selection != null) }
    }

    fun deleteMask(c: EditorController, layer: Layer) {
        if (layer.mask == null || !ensureUnlocked(c, layer)) return
        c.deleteMask(layer)
    }

    fun applyMask(c: EditorController, layer: Layer) {
        if (layer.mask == null || !c.checkEditable(layer)) return
        guardMemory(c, "Apply mask") { c.applyMask(layer) }
    }

    fun invertMask(c: EditorController, layer: Layer) {
        if (layer.mask == null || !c.checkEditable(layer)) return
        guardMemory(c, "Invert mask") { c.invertMask(layer) }
    }

    fun setMaskEnabled(c: EditorController, layer: Layer, enabled: Boolean) {
        if (layer.mask == null) return
        c.setMaskEnabled(layer, enabled)
    }

    /** Makes [layer] active and chooses whether painting edits its mask or its pixels. */
    fun editTarget(c: EditorController, layer: Layer, mask: Boolean) {
        c.selectLayer(layer)
        if (mask && layer.mask == null) return
        if (layer.editingMask != mask) c.setEditingMask(layer, mask)
    }

    private const val MASK_HIDDEN = 0xFF000000.toInt()
}
