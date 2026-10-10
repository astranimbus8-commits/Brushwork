package com.brushwork.paint.engine

import android.graphics.Bitmap
import android.graphics.Rect
import com.brushwork.paint.EditorController
import com.brushwork.paint.masks.MaskEdits
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.model.LayerProps
import com.brushwork.paint.model.SavedSelection
import com.brushwork.paint.model.Selection
import kotlin.math.min

/** One reversible edit. Actions reference [Layer] objects, never indices. */
interface UndoAction {
    val label: String
    /** Approximate retained memory, used to bound the history size. */
    val byteSize: Long
    fun undo(c: EditorController)
    fun redo(c: EditorController)
    /** Called when the action is dropped from history; recycle owned bitmaps here. */
    fun dispose() {}
}

class UndoManager(private val maxBytes: Long, private val maxSteps: Int = 150) {
    private val undoStack = ArrayDeque<UndoAction>()
    private val redoStack = ArrayDeque<UndoAction>()
    var onChanged: (() -> Unit)? = null

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val undoCount: Int get() = undoStack.size

    /** Removes and returns the actions pushed after the stack had [mark] entries (oldest first). */
    fun takeSince(mark: Int): List<UndoAction> {
        val out = ArrayList<UndoAction>()
        while (undoStack.size > mark.coerceAtLeast(0)) out.add(0, undoStack.removeLast())
        if (out.isNotEmpty()) onChanged?.invoke()
        return out
    }

    /** Pushes [action] without clearing the redo stack (used to re-add grouped actions). */
    fun pushRaw(action: UndoAction) {
        undoStack.addLast(action)
        trim()
        onChanged?.invoke()
    }

    /** Removes the newest undo action without undoing or disposing it. */
    fun popLast(): UndoAction? = undoStack.removeLastOrNull()?.also { onChanged?.invoke() }
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoLabel: String? get() = undoStack.lastOrNull()?.label
    val redoLabel: String? get() = redoStack.lastOrNull()?.label

    fun push(action: UndoAction) {
        redoStack.forEach { it.dispose() }
        redoStack.clear()
        undoStack.addLast(action)
        trim()
        onChanged?.invoke()
    }

    fun undo(c: EditorController): Boolean {
        val a = undoStack.removeLastOrNull() ?: return false
        a.undo(c)
        redoStack.addLast(a)
        onChanged?.invoke()
        return true
    }

    fun redo(c: EditorController): Boolean {
        val a = redoStack.removeLastOrNull() ?: return false
        a.redo(c)
        undoStack.addLast(a)
        onChanged?.invoke()
        return true
    }

    fun clear() {
        dropped += undoStack.size
        undoStack.forEach { it.dispose() }; undoStack.clear()
        redoStack.forEach { it.dispose() }; redoStack.clear()
        onChanged?.invoke()
    }

    private fun totalBytes(): Long = undoStack.sumOf { it.byteSize } + redoStack.sumOf { it.byteSize }

    /** Nesting depth of [holdTrim] (0 = the history is trimmed on every push). */
    private var trimHolds = 0

    /**
     * Stops trimming the oldest steps until the matching [releaseTrim] (nestable; v1.5). While
     * held, a mark taken as [undoCount] stays valid: code that groups the steps pushed since a
     * mark ([takeSince]) also works when the history is full (it would otherwise lose track of
     * them as the oldest steps are dropped). `EditorController.editScope` holds it.
     */
    fun holdTrim() {
        trimHolds++
    }

    /** Ends one [holdTrim]; the last one trims the history down to its limits again. */
    fun releaseTrim() {
        if (trimHolds == 0) return
        trimHolds--
        if (trimHolds == 0) {
            val before = undoStack.size
            trim()
            if (undoStack.size != before) onChanged?.invoke()
        }
    }

    private fun trim() {
        if (trimHolds > 0) return
        while (undoStack.size > 1 && (undoStack.size > maxSteps || totalBytes() > maxBytes)) {
            undoStack.removeFirst().dispose()
            dropped++
        }
    }

    /**
     * v1.7 (item 10): how many undo steps were ever dropped from the oldest end (trimmed) or by
     * [clear]. A history mark compares it to know whether steps it covers are gone.
     */
    internal var dropped: Long = 0
        private set

    /** v1.7: the position (0 = oldest) of [action] on the undo stack, by identity; -1 when absent. */
    internal fun undoIndexOf(action: UndoAction): Int = undoStack.indexOfLast { it === action }

    /** v1.7: the undo step at [index] (0 = oldest), or null. */
    internal fun undoAt(index: Int): UndoAction? = undoStack.getOrNull(index)
}

/** Which bitmap of a layer an edit applies to. */
enum class EditTarget { CONTENT, MASK }

private fun Layer.bitmapFor(target: EditTarget): Bitmap? = if (target == EditTarget.MASK) mask else bitmap

/**
 * Copy-on-write snapshotting for pixel edits. Call [touch] with the region you are ABOUT to
 * modify (before modifying it); the first touch of each tile saves its old pixels. [finish]
 * turns the saved tiles into an undo action; [abort] restores them (cancelled stroke).
 */
class PixelEditRecorder(val layer: Layer, val target: EditTarget, private val tileSize: Int = 256) {
    /**
     * Set when the layer's editable data is re-rendered together with this edit (text, shape and
     * vector layers, editable masks; see `EditorController.updateLayerData`): the layer keeps its
     * data. Any other edit of such a layer clears the data of its target (see commitEdit).
     */
    var preserveData: Boolean = false
    private val bitmap: Bitmap = requireNotNull(layer.bitmapFor(target)) { "Layer has no ${target.name.lowercase()} bitmap" }
    private val cols = (bitmap.width + tileSize - 1) / tileSize
    private val rows = (bitmap.height + tileSize - 1) / tileSize
    private val saved = HashMap<Int, Bitmap>()
    /** Union of everything touched, document coords. */
    val touched = Rect()

    fun touch(rect: Rect) {
        val r = Rect(rect)
        if (!r.intersect(0, 0, bitmap.width, bitmap.height)) return
        touched.union(r)
        val c0 = r.left / tileSize; val c1 = (r.right - 1) / tileSize
        val r0 = r.top / tileSize; val r1 = (r.bottom - 1) / tileSize
        for (row in r0..r1) for (col in c0..c1) {
            val idx = row * cols + col
            if (saved.containsKey(idx)) continue
            val x = col * tileSize; val y = row * tileSize
            val w = min(tileSize, bitmap.width - x); val h = min(tileSize, bitmap.height - y)
            saved[idx] = Bitmap.createBitmap(bitmap, x, y, w, h)
        }
    }

    fun touchAll() = touch(Rect(0, 0, bitmap.width, bitmap.height))

    val isEmpty: Boolean get() = saved.isEmpty()

    /** Rects (document coords) of the tiles snapshotted so far. */
    fun touchedTileRects(): List<Rect> = saved.keys.map { idx ->
        val x = (idx % cols) * tileSize; val y = (idx / cols) * tileSize
        Rect(x, y, min(bitmap.width, x + tileSize), min(bitmap.height, y + tileSize))
    }

    /** Creates the undo action (or null if nothing was touched). The recorder must not be reused. */
    fun finish(label: String): UndoAction? {
        if (saved.isEmpty()) return null
        return PixelEditAction(label, layer, target, tileSize, cols, HashMap(saved))
    }

    /** Restores all touched tiles (discarding the edit). */
    fun abort() {
        for ((idx, tile) in saved) {
            val col = idx % cols; val row = idx / cols
            BitmapUtils.blitExact(bitmap, tile, col * tileSize, row * tileSize)
            tile.recycle()
        }
        saved.clear()
    }
}

/** Swaps saved tiles with the current pixels on each undo/redo. */
class PixelEditAction(
    override val label: String,
    private val layer: Layer,
    private val target: EditTarget,
    private val tileSize: Int,
    private val cols: Int,
    private val tiles: HashMap<Int, Bitmap>,
) : UndoAction {
    override val byteSize: Long get() = tiles.values.sumOf { it.byteCount.toLong() }

    private fun swap(c: EditorController) {
        val bmp = layer.bitmapFor(target) ?: return
        val dirty = Rect()
        for (idx in tiles.keys.toList()) {
            val col = idx % cols; val row = idx / cols
            val x = col * tileSize; val y = row * tileSize
            val old = tiles.getValue(idx)
            if (x + old.width > bmp.width || y + old.height > bmp.height) continue
            val current = Bitmap.createBitmap(bmp, x, y, old.width, old.height)
            BitmapUtils.blitExact(bmp, old, x, y)
            old.recycle()
            tiles[idx] = current
            dirty.union(x, y, x + current.width, y + current.height)
        }
        layer.markChanged()
        c.invalidateDoc(dirty)
    }

    override fun undo(c: EditorController) = swap(c)
    override fun redo(c: EditorController) = swap(c)
    override fun dispose() { tiles.values.forEach { it.recycle() }; tiles.clear() }
}

/** Several actions treated as one step (undone in reverse order). */
class CompositeAction(override val label: String, private val actions: List<UndoAction>) : UndoAction {
    override val byteSize: Long get() = actions.sumOf { it.byteSize }
    override fun undo(c: EditorController) { for (a in actions.asReversed()) a.undo(c) }
    override fun redo(c: EditorController) { for (a in actions) a.redo(c) }
    override fun dispose() { actions.forEach { it.dispose() } }
}

/**
 * v1.6's add (I11: a plain layer at the root of a document without folders). v1.7: undo also
 * selects [activeBefore] again, the row that was active before the add (by reference, as
 * [LayerTreeAction] does); without it (or when it is gone) the index is clamped as before. Redo
 * selects the added layer.
 */
class AddLayerAction(
    private val layer: Layer,
    private val index: Int,
    override val label: String = "Add layer",
    private val activeBefore: Layer? = null,
) : UndoAction {
    override val byteSize: Long get() = 0 // the layer bitmap is owned by the document while present
    override fun undo(c: EditorController) = c.structural {
        c.doc.layers.remove(layer)
        val i = activeBefore?.let { c.doc.indexOf(it) } ?: -1
        if (i >= 0) c.doc.activeLayerIndex = i
    }
    override fun redo(c: EditorController) = c.structural {
        c.doc.layers.add(index.coerceIn(0, c.doc.layers.size), layer)
        c.doc.activeLayerIndex = c.doc.indexOf(layer)
    }
}

class RemoveLayerAction(private val layer: Layer, private val index: Int, override val label: String = "Delete layer") : UndoAction {
    override val byteSize: Long get() = layer.bitmap.byteCount.toLong() + (layer.mask?.byteCount ?: 0)
    override fun undo(c: EditorController) = c.structural {
        c.doc.layers.add(index.coerceIn(0, c.doc.layers.size), layer)
        c.doc.activeLayerIndex = c.doc.indexOf(layer)
    }
    override fun redo(c: EditorController) = c.structural { c.doc.layers.remove(layer) }
}

class MoveLayerAction(private val layer: Layer, private val from: Int, private val to: Int) : UndoAction {
    override val label = "Move layer"
    override val byteSize = 0L
    private fun move(c: EditorController, target: Int) = c.structural {
        c.doc.layers.remove(layer)
        c.doc.layers.add(target.coerceIn(0, c.doc.layers.size), layer)
        c.doc.activeLayerIndex = c.doc.indexOf(layer)
    }
    override fun undo(c: EditorController) = move(c, from)
    override fun redo(c: EditorController) = move(c, to)
}

class LayerPropsAction(private val layer: Layer, private val before: LayerProps, private val after: LayerProps, override val label: String = "Layer properties") : UndoAction {
    override val byteSize = 0L
    override fun undo(c: EditorController) = set(c, before)
    override fun redo(c: EditorController) = set(c, after)

    /**
     * v1.6: only an adjustment layer's opacity changing is redrawn as the change itself was (the
     * layer window's slider, −/+, a typed value): where the effect shows, through the live session
     * (design §3.1 C2, `changed` = the one-shot form for undo), not the whole canvas at once.
     */
    private fun set(c: EditorController, props: LayerProps) {
        if (layer.isAdjustmentLayer && c.doc.indexOf(layer) >= 0 && layer.props().copy(opacity = props.opacity) == props) {
            layer.opacity = props.opacity
            c.notifyLayersChanged()
            MaskEdits.redraw(c, layer, MaskEdits.effectRegion(c, layer))
        } else {
            c.structural { layer.copyPropsFrom(props) }
        }
    }
}

/**
 * Adds/removes/replaces a layer mask bitmap. Removing a mask also re-enables the mask flag (so a
 * mask added later isn't silently disabled); undo restores the previous flag.
 */
class MaskChangeAction(private val layer: Layer, private val before: Bitmap?, private val after: Bitmap?, override val label: String) : UndoAction {
    private val enabledBefore = layer.maskEnabled
    override val byteSize: Long get() = (before?.byteCount ?: 0).toLong() + (after?.byteCount ?: 0).toLong()
    override fun undo(c: EditorController) = c.structural {
        layer.mask = before
        layer.maskEnabled = enabledBefore
        if (before == null) layer.editingMask = false
        layer.markChanged()
    }
    override fun redo(c: EditorController) = c.structural {
        layer.mask = after
        if (after == null) { layer.editingMask = false; layer.maskEnabled = true }
        layer.markChanged()
    }
}

class SelectionAction(private val before: Selection?, private val after: Selection?, override val label: String = "Selection") : UndoAction {
    override val byteSize: Long get() = (before?.mask?.byteCount ?: 0).toLong() + (after?.mask?.byteCount ?: 0).toLong()
    override fun undo(c: EditorController) = c.setSelection(before, recordUndo = false)
    override fun redo(c: EditorController) = c.setSelection(after, recordUndo = false)
}

/**
 * Replaces the bitmaps of every layer and possibly the document size / dpi / color mode
 * (resize image, canvas size, trim, rotate canvas, color-mode change).
 */
class DocumentBitmapsAction(
    override val label: String,
    private val entries: List<Entry>,
    private val sizeBefore: Pair<Int, Int>,
    private val sizeAfter: Pair<Int, Int>,
    private val dpiBefore: Float,
    private val dpiAfter: Float,
    private val modeBefore: ColorMode,
    private val modeAfter: ColorMode,
) : UndoAction {
    /**
     * One layer's bitmaps and editable data before and after. By default new pixels make the
     * layer a raster layer ([LayerData.rasterizedContent]) and a new mask a painted mask
     * ([LayerData.rasterizedMask]); a bitmap that stays the same keeps its data (as in v1.4).
     * Canvas operations pass the data mapped along with the pixels
     * (`LayerDataTransforms.transformed`). Undo restores [dataBefore].
     */
    class Entry(
        val layer: Layer,
        val bitmapBefore: Bitmap,
        val maskBefore: Bitmap?,
        val bitmapAfter: Bitmap,
        val maskAfter: Bitmap?,
        val dataBefore: LayerData = layer.dataSnapshot(),
        val dataAfter: LayerData = dataBefore
            .let { if (bitmapBefore !== bitmapAfter) it.rasterizedContent() else it }
            .let { if (maskBefore !== maskAfter) it.rasterizedMask() else it },
    )

    override val byteSize: Long
        get() = entries.sumOf { it.bitmapBefore.byteCount.toLong() + (it.maskBefore?.byteCount ?: 0) }

    private fun apply(c: EditorController, before: Boolean) {
        val size = if (before) sizeBefore else sizeAfter
        c.doc.setSize(size.first, size.second)
        c.doc.dpi = if (before) dpiBefore else dpiAfter
        c.doc.colorMode = if (before) modeBefore else modeAfter
        for (e in entries) {
            e.layer.bitmap = if (before) e.bitmapBefore else e.bitmapAfter
            e.layer.mask = if (before) e.maskBefore else e.maskAfter
            e.layer.restoreData(if (before) e.dataBefore else e.dataAfter)
            e.layer.markChanged()
        }
        c.onDocumentGeometryChanged()
    }

    override fun undo(c: EditorController) = apply(c, true)
    override fun redo(c: EditorController) = apply(c, false)
}

/**
 * Data-only step (v1.5): sets [layer]'s editable data ([LayerData]) to [after] (redo) or [before]
 * (undo), marks the layer changed (so saving picks it up), refreshes the layer panels and redraws
 * [dirty] (document px, null = nothing). The pixels must already match the data, or be changed
 * by another action of the same step (tiles of `EditorController.updateLayerData`).
 */
class LayerDataAction(
    override val label: String,
    private val layer: Layer,
    private val before: LayerData,
    private val after: LayerData,
    private val dirty: Rect? = null,
) : UndoAction {
    // The data is immutable: measured once (UndoManager.trim sums every step on each push).
    override val byteSize: Long = before.approxBytes() + after.approxBytes()

    private fun set(c: EditorController, d: LayerData) {
        layer.restoreData(d)
        layer.markChanged()
        c.notifyLayersChanged()
        dirty?.let { c.invalidateDoc(it) }
    }

    override fun undo(c: EditorController) = set(c, before)
    override fun redo(c: EditorController) = set(c, after)
}

/** v1.7 (I11): one layer's place in the stack: the layer (by reference) and the folder it is in. */
data class LayerSlot(val layer: Layer, val parentId: Long)

/**
 * v1.7 (I11): ONE structural edit of a document that has a folder before or after it (without
 * folders `LayerStructure` pushes the v1.6 [AddLayerAction] / [RemoveLayerAction] /
 * [MoveLayerAction]). Holds the whole order with every parent before and after, by reference.
 * Created applied: `LayerStructure` applies it with [redo].
 */
class LayerTreeAction(
    override val label: String,
    private val before: List<LayerSlot>,
    private val after: List<LayerSlot>,
    private val activeBefore: Layer?,
    private val activeAfter: Layer?,
) : UndoAction {
    /** True while the document shows [after]. */
    private var applied = true

    /** The document this step was last applied to: [dispose] never recycles a layer it holds. */
    private var doc: Document? = null

    /** Bitmaps, masks and array source pixels of the layers present in only one list; [Layer.FOLDER_BITMAP] counts 0. */
    override val byteSize: Long = (only(before, after) + only(after, before)).sumOf { bytesOf(it) }

    override fun undo(c: EditorController) = set(c, before, activeBefore, applied = false)
    override fun redo(c: EditorController) = set(c, after, activeAfter, applied = true)

    private fun set(c: EditorController, slots: List<LayerSlot>, active: Layer?, applied: Boolean) {
        c.structural {
            c.doc.restoreSlots(slots)
            val i = active?.let { c.doc.indexOf(it) } ?: -1
            if (i >= 0) c.doc.activeLayerIndex = i
        }
        this.applied = applied
        doc = c.doc
    }

    /**
     * Recycles the bitmaps of the layers this step holds alone: while applied, the layers it
     * removed; while undone (dropped from the redo stack), the layers it added. Never a layer the
     * document still holds, never [Layer.FOLDER_BITMAP], never array source pixels (shared by
     * [LayerData] snapshots).
     */
    override fun dispose() {
        val gone = if (applied) only(before, after) else only(after, before)
        if (gone.isEmpty()) return
        val live = identitySet(doc?.layers.orEmpty())
        for (l in gone) if (l !in live) l.recycleBitmaps()
    }

    private companion object {
        fun identitySet(layers: Iterable<Layer>): MutableSet<Layer> =
            java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Layer, Boolean>()).also { s -> layers.forEach { s += it } }

        fun only(a: List<LayerSlot>, b: List<LayerSlot>): List<Layer> {
            val other = identitySet(b.map { it.layer })
            return a.map { it.layer }.filter { it !in other }
        }

        fun bytesOf(l: Layer): Long =
            (if (l.bitmap === Layer.FOLDER_BITMAP) 0L else l.bitmap.byteCount.toLong()) +
                (l.mask?.byteCount ?: 0) + (l.array?.pixels?.bytes ?: 0L)
    }
}

/**
 * v1.7 (item 14): the saved-selection list before and after ("Save selection", "Update from
 * selection", "Rename selection", "Delete saved selection"). Entries are immutable and shared by
 * reference, so nothing is recycled.
 */
class SavedSelectionsAction(
    private val before: List<SavedSelection>,
    private val after: List<SavedSelection>,
    override val label: String,
) : UndoAction {
    override val byteSize: Long = run {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<SavedSelection, Boolean>())
        (before + after).filter { seen.add(it) }.sumOf { it.bytes }
    }

    override fun undo(c: EditorController) = set(c, before)
    override fun redo(c: EditorController) = set(c, after)

    private fun set(c: EditorController, list: List<SavedSelection>) {
        c.doc.savedSelections = list
        c.notifyLayersChanged()
    }
}

/** Generic action from lambdas, for simple state changes. */
class LambdaAction(override val label: String, override val byteSize: Long = 0L, private val onUndo: (EditorController) -> Unit, private val onRedo: (EditorController) -> Unit) : UndoAction {
    override fun undo(c: EditorController) = onUndo(c)
    override fun redo(c: EditorController) = onRedo(c)
}
