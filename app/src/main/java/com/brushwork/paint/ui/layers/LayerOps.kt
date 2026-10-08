package com.brushwork.paint.ui.layers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.CanvasOps
import com.brushwork.paint.engine.CompositeAction
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.engine.FolderComposite
import com.brushwork.paint.engine.LayerPropsAction
import com.brushwork.paint.engine.MaskChangeAction
import com.brushwork.paint.engine.UndoAction
import com.brushwork.paint.masks.AdjustmentLayerOps
import com.brushwork.paint.masks.MaskEdits
import com.brushwork.paint.masks.MaskLayerOps
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.LayerTree
import com.brushwork.paint.model.TransparencyDisplay
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.common.FolderLabels
import com.brushwork.paint.vector.VectorLayerOps

/**
 * Layer operations as offered by the layers panel. Wraps the controller with the guards it leaves
 * to callers (lock checks, memory failures, pending tool work) and implements Clear/Fill so they
 * respect the edit target: while a mask is being edited they paint the mask (black = hidden,
 * fill = the gray luminance of the color). Only the selection bounds are snapshotted for undo
 * when a selection exists. Main thread only.
 *
 * Policy: operations that change pixels require [EditorController.checkEditable] (not locked, not
 * hidden); operations that change a mask's existence require the layer to be unlocked; property
 * changes (visibility, opacity, blend, clipping, locks, name) are always allowed. Clearing the
 * content of an alpha-locked layer is refused (it would change the locked alpha).
 */
object LayerOps {

    /** False (with a message) when [layer] is locked (v1.7, rule L: or is in a locked folder). */
    fun ensureUnlocked(c: EditorController, layer: Layer): Boolean {
        if (layer.locked) { c.toast("Layer \"${layer.name}\" is locked"); return false }
        if (layer.parentId != Layer.ROOT_ID) {
            val layers = c.doc.layers
            LayerTree.ancestors(layers, c.doc.indexOf(layer)).firstOrNull { layers[it].locked }?.let { c.toast(FolderLabels.locked(layers[it].name)); return false }
        }
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

    /**
     * Bakes the current tool's editable pending work (floating transform, unconfirmed text or
     * shape...) as its confirm button would, so a panel operation never interleaves with it.
     * The controller already does this for flip, merge, add/duplicate and edit-target switches.
     */
    fun commitPendingWork(c: EditorController) {
        val tool = c.currentTool
        if (tool.hasPendingWork) {
            tool.commit()
            c.invalidateOverlay()
        }
    }

    /** The window's "+": a vector layer in vector mode (v1.5), else a raster layer. */
    fun addLayer(c: EditorController) = guardMemory(c, "Add layer") { if (c.isVectorMode) c.addVectorLayer() else c.addLayer() }

    // ------------------------------------------------------------------ v1.5: vector & adjustment layers

    fun addVectorLayer(c: EditorController) = guardMemory(c, "New vector layer") { c.addVectorLayer() }

    fun addAdjustmentLayer(c: EditorController) = guardMemory(c, "New adjustment layer") { AdjustmentLayerOps.createDefault(c) }

    /**
     * An empty raster layer or a shape layer can become a vector layer (checks pixels: call on
     * demand). Never a folder: its bitmap is the shared `FOLDER_BITMAP`, which is not read.
     */
    fun canConvertToVector(c: EditorController, layer: Layer): Boolean =
        !layer.isFolder && !layer.isVectorLayer && !layer.isAdjustmentLayer && (layer.isShapeLayer || c.isEmptyPlainLayer(layer))

    fun convertToVector(c: EditorController, layer: Layer) {
        commitPendingWork(c)
        c.convertToVectorLayer(layer)
    }

    /** Turns the vector layer [layer] into a raster layer (its pixels stay; one step). */
    fun rasterizeVector(c: EditorController, layer: Layer) {
        if (!layer.isVectorLayer || !ensureUnlocked(c, layer)) return
        commitPendingWork(c)
        if (VectorLayerOps.rasterize(c, layer)) return
        c.setLayerData(layer, layer.dataSnapshot().copy(vector = null), "Rasterize vector layer")
    }

    /** "Edit objects": the vector layer [layer] with the Transform tool. */
    fun editObjects(c: EditorController, layer: Layer) {
        if (!layer.isVectorLayer || !c.checkEditable(layer)) return
        c.selectLayer(layer)
        c.selectTool(ToolId.TRANSFORM)
    }

    fun editAdjustment(c: EditorController, layer: Layer) = AdjustmentLayerOps.edit(c, layer)

    fun editAdjustmentMask(c: EditorController, layer: Layer) = AdjustmentLayerOps.editMask(c, layer)

    fun addGradientMask(c: EditorController, layer: Layer) {
        if (!ensureUnlocked(c, layer)) return
        commitPendingWork(c)
        MaskLayerOps.addGradientMask(c, layer)
    }

    fun toPixelMask(c: EditorController, layer: Layer) {
        if (!ensureUnlocked(c, layer)) return
        MaskLayerOps.toPixelMask(c, layer)
    }

    /** The mask's luminance as a soft selection. */
    fun maskToSelection(c: EditorController, layer: Layer) {
        commitPendingWork(c)
        guardMemory(c, "Mask to selection") { c.selectionFromMask(layer) }
    }

    fun duplicate(c: EditorController, layer: Layer) = guardMemory(c, "Duplicate layer") { c.duplicateLayer(layer) }

    /**
     * Whether [layer] has a layer below it to merge into (v1.7: at its own level; a folder merges
     * as "Merge folder").
     */
    fun canMergeDown(c: EditorController, layer: Layer): Boolean = layer.isFolder || siblingBelow(c, layer) != null

    /** The row directly below [layer] when it is at the same level (v1.6: `layers[index - 1]`), else null. */
    private fun siblingBelow(c: EditorController, layer: Layer): Layer? {
        val idx = c.doc.indexOf(layer)
        if (idx <= 0) return null
        return c.doc.layers[idx - 1].takeIf { it.parentId == layer.parentId }
    }

    /**
     * Merges [layer] into the layer below. When both are clipped to the same base, the upper one
     * is flattened onto the lower one WITHOUT clipping it to the lower layer's pixels: on screen
     * both are clipped to the shared base, and the merged layer stays clipped to it.
     */
    fun mergeDown(c: EditorController, layer: Layer) {
        // First: committing may insert a layer, which would change the layer below.
        commitPendingWork(c)
        // v1.7 (§4.4): merging a folder down is "Merge folder" (refused, as any merge, when the
        // folder or a layer in it is locked, or the folder is hidden).
        if (layer.isFolder) {
            mergeFolder(c, layer)
            return
        }
        val idx = c.doc.indexOf(layer)
        // v1.7: the layer below at the same level (a folder's bottom child has none); a folder
        // there is refused before any lock prompt about it.
        val lower = siblingBelow(c, layer)
        if (lower == null) { c.toast("There is no layer below to merge into"); return }
        if (lower.isFolder) { c.toast(FolderLabels.MERGE_INTO_REFUSAL); return }
        // An adjustment layer has no pixels of its own (checkEditable refuses those without a
        // mask): merging it applies its effect to the layer below ("Apply to layer below").
        if (layer.isAdjustmentLayer) {
            if (!ensureUnlocked(c, layer)) return
        } else if (!c.checkEditable(layer)) {
            return
        }
        if (!ensureUnlocked(c, lower)) return
        // The controller renders the pair as a two-layer document where the lower layer is the
        // base, so a clipped upper layer would be cut to the lower layer's alpha. Present it as
        // unclipped for the duration of the (synchronous) merge; the flag is restored before
        // anything composites, and the removed layer keeps it for undo.
        val sameGroup = layer.clipping && lower.clipping && idx - 1 > 0
        if (sameGroup) layer.clipping = false
        try {
            guardMemory(c, "Merge down") { c.mergeDown(layer) }
        } finally {
            if (sameGroup) layer.clipping = true
        }
    }

    // ------------------------------------------------------------------ v1.7: folders (item 8)

    /**
     * "Merge folder" (the strip's merge button and merge down on a folder): one raster layer at
     * the folder's place ([EditorController.mergeFolder], one step). The controller merges
     * without checks; the guards are here, as for merge down: refused (with the v1.6 message, no
     * step) when the folder is hidden or locked, itself or through its folders, or when a layer
     * or folder inside it is locked (its content would go). Hidden layers inside are dropped, as
     * the composite leaves them out (undo brings them back).
     */
    fun mergeFolder(c: EditorController, folder: Layer) {
        if (!folder.isFolder) return
        commitPendingWork(c)
        if (!canMergeFolder(c, folder)) return
        guardMemory(c, FolderLabels.MERGE) { c.mergeFolder(folder) }
    }

    /** The guards of [mergeFolder], with their message: false when it is refused. */
    fun canMergeFolder(c: EditorController, folder: Layer): Boolean {
        if (!folder.isFolder || !c.checkUsable(folder, allowFolder = true)) return false
        val layers = c.doc.layers
        val f = c.doc.indexOf(folder)
        if (f < 0) return false
        for (i in LayerTree.block(layers, f)) {
            val l = layers[i]
            if (i == f || !l.locked) continue
            c.toast(if (l.isFolder) FolderLabels.locked(l.name) else "Layer \"${l.name}\" is locked")
            return false
        }
        return true
    }

    /**
     * Whether "Merge folder" changes the picture of the folder at flat index [index] of [layers]
     * (bottom first), so the window asks first ("Blending with layers below the folder will
     * change"). Only a pass-through folder can: it merges as an isolated Normal layer, so what
     * inside it blended with the layers below the folder now blends with transparency. That is a
     * shown adjustment layer, or a shown unit with a blend other than Normal (a clip group by its
     * base's blend), reached through pass-through folders drawn straight onto the canvas (an
     * isolated folder inside counts by its own blend). Opacity alone never changes it:
     * o·(C over B) + (1 − o)·B = o·C + (1 − o·αC)·B for Normal content. A clipped pass-through
     * folder or a pass-through clip base is composited isolated already, Normal
     * ([FolderComposite.drawnBlend]), as the merged layer is.
     */
    fun mergeChangesPicture(layers: List<Layer>, index: Int): Boolean {
        val f = layers.getOrNull(index) ?: return false
        if (f.folder?.passThrough != true || FolderComposite.isClipped(layers, index)) return false
        if (FolderComposite.isClipBase(layers, index)) return false
        return blendsWithBackdrop(layers, index)
    }

    private fun blendsWithBackdrop(layers: List<Layer>, folderIndex: Int): Boolean {
        for (i in LayerTree.children(layers, folderIndex)) {
            val l = layers[i]
            // A clipped unit blends inside its group; the group blends with its base's mode.
            if (!l.visible || l.opacity <= 0f || FolderComposite.isClipped(layers, i)) continue
            if (l.isAdjustmentLayer) return true
            if (l.folder?.passThrough == true && !FolderComposite.isClipBase(layers, i)) {
                if (blendsWithBackdrop(layers, i)) return true
                continue
            }
            // (A pass-through clip base inside draws its group Normal.)
            if (FolderComposite.drawnBlend(l) != LayerBlendMode.NORMAL) return true
        }
        return false
    }

    /** "Layer from folder": the folder's composite as a new layer above it (one step). */
    fun layerFromFolder(c: EditorController, folder: Layer) {
        if (!folder.isFolder) return
        commitPendingWork(c)
        guardMemory(c, FolderLabels.FROM_FOLDER) { c.layerFromFolder(folder) }
    }

    /** "Ungroup folder": its children move to its level and the folder goes (one step). */
    fun ungroupFolder(c: EditorController, folder: Layer) {
        if (!folder.isFolder) return
        commitPendingWork(c)
        c.ungroupFolder(folder)
    }

    /**
     * The blend list's pick [mode] for [layer]. On a pass-through folder it also turns pass
     * through off, as ONE "Blend mode" step (the two actions grouped); else as v1.6.
     */
    fun setBlendMode(c: EditorController, layer: Layer, mode: LayerBlendMode) {
        if (layer.folder?.passThrough != true) {
            c.setBlendMode(layer, mode)
            return
        }
        c.groupUndo(BLEND_STEP) {
            c.setFolderPassThrough(layer, false)
            if (layer.blendMode != mode) c.setBlendMode(layer, mode)
        }
    }

    /** The blend list's "Pass through" on a folder (one "Pass through" step; its blend is kept for later). */
    fun setPassThrough(c: EditorController, folder: Layer) {
        if (folder.folder?.passThrough == false) c.setFolderPassThrough(folder, true)
    }

    /** Undo label of a blend change ([EditorController.setBlendMode]'s, v1.5). */
    const val BLEND_STEP = "Blend mode"

    fun flip(c: EditorController, layer: Layer, horizontal: Boolean) =
        guardMemory(c, if (horizontal) "Flip horizontal" else "Flip vertical") { c.flipLayer(layer, horizontal) }

    /**
     * The left column's canvas flips (v1.6): the whole canvas, as the Canvas panel's "Flip
     * horizontally / vertically" (in the background behind the busy overlay, one step). False
     * when another canvas operation is running.
     */
    fun flipCanvas(c: EditorController, horizontal: Boolean): Boolean = CanvasOps.applyFlip(c, horizontal)

    /** "Transform layer" (v1.6 strip): [layer] active, then the Transform tool (which lifts it). */
    fun transform(c: EditorController, layer: Layer) {
        if (c.activeLayer !== layer) c.selectLayer(layer)
        c.selectTool(ToolId.TRANSFORM)
    }

    /**
     * The transparency squares (v1.6): how the canvas shows transparent areas. A view preference
     * (`AppSettings`), never part of the document, exports or the compositor (I5); the canvas
     * redraws at once.
     */
    fun setTransparencyDisplay(c: EditorController, display: TransparencyDisplay) {
        if (c.settings.transparencyDisplay == display) return
        c.settings.transparencyDisplay = display
        c.invalidateDoc(null)
    }

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
        // A vector layer's content: its objects go, as with the selection bar's Clear (v1.5; the
        // layer stays a vector layer, and alpha lock doesn't apply to objects).
        if (target == EditTarget.CONTENT && layer.isVectorLayer) {
            c.clearLayer(layer)
            return true
        }
        if (target == EditTarget.CONTENT && layer.alphaLocked) {
            c.toast("Alpha lock is on for \"${layer.name}\": turn it off to clear")
            return false
        }
        commitPendingWork(c)
        val sel = c.selection
        val rect = if (sel != null) Rect(sel.bounds) else Rect(c.doc.bounds)
        if (rect.isEmpty) return false
        val label = if (target == EditTarget.MASK) "Clear mask" else "Clear"
        return recordedEdit(c, layer, target, rect, label) { bmp ->
            if (sel == null) {
                bmp.eraseColor(if (target == EditTarget.MASK) MASK_HIDDEN else 0)
            } else {
                val paint = if (target == EditTarget.MASK) Paint().apply { color = MASK_HIDDEN }
                else Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
                Canvas(bmp).apply { clipRect(rect) }.drawBitmap(sel.mask, 0f, 0f, paint)
            }
        }
    }

    /**
     * Fills the layer (or the selection) with [color]. Respects alpha lock on content. While
     * editing the mask, fills the mask with the color's luminance.
     */
    fun fill(c: EditorController, layer: Layer, color: Int = c.color): Boolean {
        if (!c.checkEditable(layer)) return false
        // A vector layer's content gets a filled object, as with the selection bar's Fill (v1.5;
        // the layer stays a vector layer).
        if (c.editTargetOf(layer) == EditTarget.CONTENT && layer.isVectorLayer) {
            c.fillLayer(layer, color)
            return true
        }
        commitPendingWork(c)
        val target = c.editTargetOf(layer)
        val sel = c.selection
        val rect = if (sel != null) Rect(sel.bounds) else Rect(c.doc.bounds)
        if (rect.isEmpty) return false
        val label = if (target == EditTarget.MASK) "Fill mask" else "Fill"
        return recordedEdit(c, layer, target, rect, label) { bmp ->
            if (target == EditTarget.MASK) {
                val gray = ColorUtils.gray(ColorUtils.luminance(color))
                if (sel == null) bmp.eraseColor(gray)
                else Canvas(bmp).apply { clipRect(rect) }.drawBitmap(sel.mask, 0f, 0f, Paint().apply { this.color = gray })
            } else {
                val canvas = Canvas(bmp)
                val alphaLock = layer.alphaLocked
                if (sel == null) {
                    canvas.drawColor(color, if (alphaLock) PorterDuff.Mode.SRC_ATOP else PorterDuff.Mode.SRC_OVER)
                } else {
                    val p = Paint().apply {
                        this.color = color
                        if (alphaLock) xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
                    }
                    canvas.clipRect(rect)
                    canvas.drawBitmap(sel.mask, 0f, 0f, p)
                }
            }
        }
    }

    /**
     * Snapshots [rect] of [layer]'s [target], runs [draw] on the target bitmap and commits one
     * undo step. Out of memory (the snapshot of a big canvas, or the color-mode pass) restores
     * the pixels and reports it instead of crashing.
     */
    private inline fun recordedEdit(
        c: EditorController,
        layer: Layer,
        target: EditTarget,
        rect: Rect,
        label: String,
        draw: (Bitmap) -> Unit,
    ): Boolean {
        val rec = c.beginEdit(layer, target)
        val editsBefore = c.editCount
        return try {
            rec.touch(rect)
            draw(if (target == EditTarget.MASK) layer.mask!! else layer.bitmap)
            c.commitEdit(rec, label)
        } catch (e: OutOfMemoryError) {
            // Once the step reached the history it owns the snapshot tiles; before that, undo it.
            if (c.editCount == editsBefore) rec.abort()
            c.toast("Not enough memory for \"$label\"")
            false
        }
    }

    // ------------------------------------------------------------------ masks

    /**
     * Adds a mask (from the selection when [fromSelection] and one exists) and starts editing it.
     * A layer whose previous mask was deleted or applied while disabled still has
     * `maskEnabled = false`; the new mask is enabled in the same undo step so painting on it
     * actually shows.
     */
    fun addMask(c: EditorController, layer: Layer, fromSelection: Boolean) {
        // v1.7: a folder has no mask (yet); this path would otherwise give it one.
        if (layer.isFolder) { c.toast(FolderLabels.NO_MASK); return }
        if (layer.mask != null || !ensureUnlocked(c, layer)) return
        commitPendingWork(c)
        val useSelection = fromSelection && c.selection != null
        guardMemory(c, "Add mask") {
            if (layer.maskEnabled) {
                c.addMask(layer, fromSelection = useSelection)
            } else {
                addEnabledMask(c, layer, useSelection)
            }
        }
    }

    /** Same as [EditorController.addMask] plus re-enabling the mask, as ONE undo step. */
    private fun addEnabledMask(c: EditorController, layer: Layer, fromSelection: Boolean) {
        val sel = if (fromSelection) c.selection else null
        val mask = BitmapUtils.createMaskBitmap(c.doc.width, c.doc.height, if (sel != null) MASK_HIDDEN else MASK_VISIBLE)
        if (sel != null) Canvas(mask).drawBitmap(sel.mask, 0f, 0f, Paint().apply { color = MASK_VISIBLE })
        val before = layer.props()
        val actions = listOf<UndoAction>(
            MaskChangeAction(layer, null, mask, "Add mask"),
            LayerPropsAction(layer, before, before.copy(maskEnabled = true), "Add mask"),
        )
        actions.forEach { it.redo(c) }
        layer.editingMask = true
        c.pushUndo(CompositeAction("Add mask", actions))
    }

    fun deleteMask(c: EditorController, layer: Layer) {
        if (layer.mask == null || !ensureUnlocked(c, layer)) return
        commitPendingWork(c)
        c.deleteMask(layer)
    }

    fun applyMask(c: EditorController, layer: Layer) {
        // An adjustment layer has no pixels to bake its mask into: "applying" it would only drop
        // the mask, and the effect would suddenly cover everything (v1.5).
        if (layer.isAdjustmentLayer) { c.toast(ADJUSTMENT_APPLY_MASK_MESSAGE); return }
        if (layer.mask == null || !c.checkEditable(layer)) return
        commitPendingWork(c)
        guardMemory(c, "Apply mask") { c.applyMask(layer) }
    }

    fun invertMask(c: EditorController, layer: Layer) {
        if (layer.mask == null || !c.checkEditable(layer)) return
        commitPendingWork(c)
        // An editable mask stays editable: its spec is inverted (v1.5), not its pixels.
        val spec = layer.maskSpec
        if (spec != null) {
            guardMemory(c, "Invert mask") { MaskEdits.apply(c, layer, spec.copy(invert = !spec.invert), "Invert mask") }
            return
        }
        guardMemory(c, "Invert mask") { c.invertMask(layer) }
    }

    fun setMaskEnabled(c: EditorController, layer: Layer, enabled: Boolean) {
        if (layer.mask == null) return
        c.setMaskEnabled(layer, enabled)
    }

    /**
     * Edits the text of the text layer [layer] again: switches to the text tool, loads the text
     * (the layer becomes active) and opens the text editor. A frame of a linked story (v1.6) opens
     * in the Text frames tool with its story editor instead ([EditorController.textThreads]).
     * False (with a message) when the layer isn't an editable text layer or can't be changed
     * (locked / hidden).
     */
    fun editText(c: EditorController, layer: Layer): Boolean {
        if (!layer.isTextLayer) {
            c.toast("\"${layer.name}\" is not a text layer")
            return false
        }
        if (!c.checkEditable(layer)) return false
        // Before the Text tool is picked: the frames tool selects the frame and opens its story.
        if (c.textThreads.isFrame(layer) && c.textThreads.openForEditing(layer, openEditor = true)) return true
        c.selectTool(ToolId.TEXT)
        val tool = c.tools[ToolId.TEXT] as? TextTool ?: return false
        return tool.editLayer(layer, openEditor = true)
    }

    /**
     * Edits the shape of the shape layer [layer] again: switches to the shape tool and opens the
     * shape (the layer becomes active; its handles, points and options are then editable). False
     * (with a message) when the layer isn't an editable shape layer or can't be changed.
     */
    fun editShape(c: EditorController, layer: Layer): Boolean {
        if (!layer.isShapeLayer) {
            c.toast("\"${layer.name}\" is not a shape layer")
            return false
        }
        if (!c.checkEditable(layer)) return false
        c.selectTool(ToolId.SHAPE)
        val tool = c.tools[ToolId.SHAPE] as? ShapeTool ?: return false
        return tool.editLayer(layer)
    }

    /** Makes [layer] active and chooses whether painting edits its mask or its pixels. */
    fun editTarget(c: EditorController, layer: Layer, mask: Boolean) {
        c.selectLayer(layer)
        if (mask && layer.mask == null) return
        if (layer.editingMask != mask) c.setEditingMask(layer, mask)
    }

    private const val MASK_HIDDEN = 0xFF000000.toInt()
    private const val MASK_VISIBLE = -1

    /** "Apply mask" asked for on an adjustment layer (v1.5). */
    const val ADJUSTMENT_APPLY_MASK_MESSAGE = "An adjustment layer has no pixels to apply its mask to — use Apply to layer below, or Delete mask"
}
