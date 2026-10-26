package com.brushwork.paint.engine

import android.graphics.Bitmap
import android.graphics.Rect
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.tiles.FlatPixels
import com.brushwork.paint.engine.tiles.FolderPixels
import com.brushwork.paint.engine.tiles.PixelStore
import com.brushwork.paint.engine.tiles.TILE_SIZE
import com.brushwork.paint.engine.tiles.Tile
import com.brushwork.paint.engine.tiles.TileOps
import com.brushwork.paint.engine.tiles.TileState
import com.brushwork.paint.engine.tiles.TileWorker
import com.brushwork.paint.engine.tiles.TiledPixels
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

    /**
     * Pushes a new step; the redo steps are dropped. v1.10 (A8): while an apply waits for its
     * tiles ([beginWaiting]), the dropped redo steps are disposed only once nothing waits any more
     * ([droppedWhileWaiting]): an undo asked before this push, whose apply is still waiting or
     * queued, still applies, and its step is let go after it.
     */
    fun push(action: UndoAction) {
        if (waiting > 0 || deferred.isNotEmpty()) droppedWhileWaiting += redoStack else redoStack.forEach { it.dispose() }
        redoStack.clear()
        undoStack.addLast(action)
        trim()
        onChanged?.invoke()
    }

    fun undo(c: EditorController): Boolean {
        val a = undoStack.removeLastOrNull() ?: return false
        if (!deferIfWaiting { a.undo(c) }) a.undo(c)
        redoStack.addLast(a)
        onChanged?.invoke()
        return true
    }

    fun redo(c: EditorController): Boolean {
        val a = redoStack.removeLastOrNull() ?: return false
        if (!deferIfWaiting { a.redo(c) }) a.redo(c)
        undoStack.addLast(a)
        onChanged?.invoke()
        return true
    }

    /** v1.10 (A8): applies of undo steps waiting for the worker to read their tiles. */
    private var waiting = 0

    /** v1.10 (A8): undos and redos that came while [waiting], run in order once nothing waits. */
    private val deferred = ArrayDeque<() -> Unit>()

    /**
     * v1.10 (A8): redo steps a [push] dropped while an apply waited (one of them may be the
     * waiting one, others may have their undo queued in [deferred]): disposed once nothing waits
     * and nothing is queued, so no apply runs on a disposed step. [clear] disposes them at once.
     */
    private val droppedWhileWaiting = ArrayList<UndoAction>()

    private fun disposeDropped() {
        if (droppedWhileWaiting.isEmpty()) return
        val drop = droppedWhileWaiting.toList()
        droppedWhileWaiting.clear()
        drop.forEach { it.dispose() }
    }

    /**
     * v1.10 (A8, design 2.6): an undo step's apply now waits for the worker to read its tiles
     * (`PixelEditAction` on a FLAT layer with SWAPPED tiles). Until the returned function runs
     * (once, on main, after that apply), every later undo or redo, and every part of a step that
     * defers itself ([deferIfWaiting]), waits behind it, in order: nothing is applied out of turn.
     */
    internal fun beginWaiting(): () -> Unit {
        waiting++
        var ended = false
        return {
            if (!ended) {
                ended = true
                waiting--
                while (waiting == 0) {
                    val op = deferred.removeFirstOrNull() ?: break
                    op()
                }
                if (waiting == 0 && deferred.isEmpty()) disposeDropped()
            }
        }
    }

    /** v1.10 (A8): true (and [op] queued) while an apply waits for its tiles ([beginWaiting]); false when [op] may run now. Main only. */
    internal fun deferIfWaiting(op: () -> Unit): Boolean {
        if (waiting == 0) return false
        deferred.addLast(op)
        return true
    }

    fun clear() {
        // Steps undone or redone while an apply waited are dropped with the history.
        deferred.clear()
        disposeDropped()
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
     * v1.9 (V28, design 2.4.3): drops the oldest steps, one at a time, while [until] returns false
     * and more than [keepAtLeast] steps remain. A no-op while [holdTrim] holds (I2 grouping). Each
     * dropped action is disposed and counted in [dropped]; [onChanged] runs once at the end when a
     * step was dropped. Returns the number of steps dropped. The only public way to drop old steps
     * (a memory policy's, `MemoryGovernor`); the redo steps are kept.
     */
    fun trimOldest(keepAtLeast: Int = 1, until: () -> Boolean): Int {
        if (trimHolds > 0) return 0
        val keep = keepAtLeast.coerceAtLeast(0)
        var n = 0
        while (undoStack.size > keep && !until()) {
            undoStack.removeFirst().dispose()
            dropped++
            n++
        }
        if (n > 0) onChanged?.invoke()
        return n
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

/**
 * v1.10 (A8, design 2.0 P3 order 2, 2.6): an undo step that holds tiles by reference (a tile map,
 * a store snapshot). A tile no store of the document holds any more is undo-only, and the
 * residency policy may swap it out, those of the oldest step first (`ResidencyPolicy`). Main only.
 */
internal interface TileHolder {
    /** The layer the step's tiles belong to, when one does (the undo-age rule keeps the active layer's newest 3 steps). */
    val tileLayer: Layer?

    /** Calls [block] with every tile the step holds (a tile it holds twice, twice). */
    fun forEachTile(block: (Tile) -> Unit)
}

/**
 * v1.10 (A8, design 2.7): an undo step put a tiled store back into [layer]; when it is the active
 * layer it is made resident again (`ResidencyHooks.activate`), as a tap in the layers panel does.
 * A no-op in CLASSIC, where no store is tiled. Main only.
 */
private fun activateIfParked(c: EditorController, layer: Layer) {
    val doc = c.doc
    if (doc.layers.isEmpty() || doc.activeLayer !== layer) return
    if (layer.pixels is TiledPixels || layer.maskPixels is TiledPixels) EngineConfig.residency.activate(layer)
}

/** v1.10 (A8): lets go of [s] when it is tiled and [layer] does not hold it (a flat bitmap is left to the GC, as in v1.9). Main only. */
private fun releaseHeld(s: PixelStore?, layer: Layer) {
    if (s is TiledPixels && s !== layer.pixels && s !== layer.maskPixels) TileOps.releaseStore(s)
}

/** v1.10 (A8): every tile of [s] when it is tiled. */
private fun forEachTileOf(s: PixelStore?, block: (Tile) -> Unit) {
    if (s is TiledPixels) for (t in s.tiles) if (t != null) block(t)
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

    // v1.10 (A8): a saved piece becomes an undo tile, at most TILE_SIZE square.
    init { require(tileSize in 1..TILE_SIZE) { "an undo tile is at most $TILE_SIZE px (got $tileSize)" } }

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

    /**
     * Creates the undo action (or null if nothing was touched). The recorder must not be reused.
     * v1.10 (A8, design 2.6): each saved piece becomes an undo tile held once by the step, a fully
     * transparent one null (its bitmap recycled): the transparent-to-null test, here on main
     * before the step is pushed.
     */
    fun finish(label: String): UndoAction? {
        if (saved.isEmpty()) return null
        val tiles = HashMap<Int, Tile?>(saved.size * 2)
        for ((idx, piece) in saved) tiles[idx] = TileOps.undoTileOf(piece)
        saved.clear()
        return PixelEditAction(label, layer, target, tiles, bitmap.width, bitmap.height, tileSize)
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

/**
 * Swaps saved tiles with the current pixels on each undo/redo.
 *
 * v1.10 (A8, design 2.6): ONE map of undo tiles (by tile index of a [width] x [height] grid of
 * [tileSize] cells; null = transparent), each held once by the step, swapped with the layer's
 * pixels on each undo and redo, so a step costs 1x its touched tiles, as in v1.9, and [byteSize]
 * is fixed at construction (I28). Where the target is:
 *  - FLAT: each entry is copied in and the pixels that were there become its tile (v1.9's swap).
 *    When a tile of the map is SWAPPED (the residency policy swapped it out as undo-only), the
 *    step waits for the worker to read it back (READ_URGENT) and applies on main then: canvas
 *    input is held meanwhile ([EditorController.activating]) and every later undo or redo waits
 *    behind it ([UndoManager.beginWaiting]); main never reads a slot.
 *  - PARKED: `TiledPixels.replaceTiles` swaps the map's tiles into the store in place and the
 *    tiles it replaced go back into the map (no copy, no materialization, the same store so
 *    [Layer.pixelGeneration] unchanged; the references move with the tiles).
 *  - anything else (blank, evicted, another size): made FLAT first (the facade), then as FLAT.
 * Main only.
 */
class PixelEditAction internal constructor(
    override val label: String,
    private val layer: Layer,
    private val target: EditTarget,
    private val tiles: HashMap<Int, Tile?>,
    private val width: Int,
    private val height: Int,
    private val tileSize: Int = TILE_SIZE,
) : UndoAction, TileHolder {
    private val cols = (width + tileSize - 1) / tileSize

    override val byteSize: Long = tiles.values.sumOf { it?.bytes?.toLong() ?: 0L }

    /** Dropped from the history: its tiles are let go, and a read still in flight applies nothing. */
    private var disposed = false

    override val tileLayer: Layer get() = layer

    override fun forEachTile(block: (Tile) -> Unit) {
        for (t in tiles.values) if (t != null) block(t)
    }

    private fun store(): PixelStore? = if (target == EditTarget.MASK) layer.maskPixels else layer.pixels

    /** The target's store when the map's tiles can be swapped into it in place (PARKED, the same grid), else null. */
    private fun parkedStore(): TiledPixels? =
        (store() as? TiledPixels)?.takeIf { it.evictedTo == null && tileSize == TILE_SIZE && it.width == width && it.height == height }

    private fun swap(c: EditorController) {
        if (disposed || c.undoManager.deferIfWaiting { swap(c) }) return
        if (store() == null) return
        if (parkedStore() == null) {
            if (store() !is FlatPixels) layer.bitmapFor(target)
            val held = tiles.values.filterNotNull()
            if (held.any { it.state == TileState.SWAPPED }) {
                val end = c.undoManager.beginWaiting()
                val pin = layer.pin()
                c.activating = layer
                TileWorker.readThen(held) {
                    pin.close()
                    if (c.activating === layer) c.activating = null
                    try {
                        if (!disposed) apply(c)
                    } finally {
                        end()
                    }
                }
                return
            }
        }
        apply(c)
    }

    private fun apply(c: EditorController) {
        val parked = parkedStore()
        val dirty = Rect()
        if (parked != null) {
            for (idx in tiles.keys) dirty.union(rectOf(idx))
            val replaced = parked.replaceTiles(tiles)
            tiles.clear()
            tiles.putAll(replaced)
        } else {
            val flat = store() as? FlatPixels ?: return
            for (idx in tiles.keys.toList()) {
                val put = tiles[idx]
                // A tile whose read failed stays as it is (main never reads a slot).
                if (put != null && put.state == TileState.SWAPPED) continue
                val r = rectOf(idx)
                if (r.right > flat.width || r.bottom > flat.height) continue
                if (put != null && (put.w != r.width() || put.h != r.height())) continue
                tiles[idx] = TileOps.swapFlatTile(flat, r.left, r.top, r.width(), r.height(), put)
                if (put != null) TileOps.release(put)
                dirty.union(r)
            }
        }
        layer.markChanged()
        c.invalidateDoc(dirty)
    }

    /** The document rect of tile [idx] of the step's grid. */
    private fun rectOf(idx: Int): Rect {
        val x = (idx % cols) * tileSize
        val y = (idx / cols) * tileSize
        return Rect(x, y, min(width, x + tileSize), min(height, y + tileSize))
    }

    override fun undo(c: EditorController) = swap(c)
    override fun redo(c: EditorController) = swap(c)

    override fun dispose() {
        disposed = true
        // A tile a read in flight pins is freed by its unpin (TileOps.unpinTile).
        for (t in tiles.values) if (t != null) TileOps.release(t)
        tiles.clear()
    }
}

/** Several actions treated as one step (undone in reverse order). */
class CompositeAction(override val label: String, private val actions: List<UndoAction>) : UndoAction, TileHolder {
    override val byteSize: Long get() = actions.sumOf { it.byteSize }

    // v1.10 (A8, design 2.6): a part whose apply waits for its tiles (UndoManager.beginWaiting)
    // holds the parts after it too, so they apply after it, in order, never out of turn.
    override fun undo(c: EditorController) {
        for (a in actions.asReversed()) if (!c.undoManager.deferIfWaiting { a.undo(c) }) a.undo(c)
    }

    override fun redo(c: EditorController) {
        for (a in actions) if (!c.undoManager.deferIfWaiting { a.redo(c) }) a.redo(c)
    }
    override fun dispose() { actions.forEach { it.dispose() } }

    /** v1.10 (A8): the one layer its parts' tiles belong to, or null (none, or several). */
    override val tileLayer: Layer?
        get() = actions.mapNotNullTo(HashSet()) { (it as? TileHolder)?.tileLayer }.singleOrNull()

    /** v1.10 (A8): the tiles of every part that holds some. */
    override fun forEachTile(block: (Tile) -> Unit) {
        for (a in actions) (a as? TileHolder)?.forEachTile(block)
    }
}

/**
 * v1.6's add (I11: a plain layer at the root of a document without folders). v1.7: undo also
 * selects [activeBefore] again, the row that was active before the add (by reference, as
 * [LayerTreeAction] does); without it (or when it is gone) the index is clamped as before. Redo
 * selects the added layer.
 *
 * v1.10 (A8, design 2.6): created applied (the insert is made first). A duplicate of a parked
 * layer adds a copy-on-write store sharing the source's tiles (`LayerStructure.copyOf`): while
 * undone, the added layer's tiles are held by this step alone (redo-only, [TileHolder]; P3 walks
 * the undo stack only, so they are no swap-out candidates until dropped), and [dispose] (the step
 * dropped from the redo stack) then lets go of its tiled stores, never those of a layer the
 * document holds or a pin holds (a flat bitmap is left to the GC, as in v1.9).
 */
class AddLayerAction(
    private val layer: Layer,
    private val index: Int,
    override val label: String = "Add layer",
    private val activeBefore: Layer? = null,
) : UndoAction, TileHolder {
    override val byteSize: Long get() = 0 // the layer bitmap is owned by the document while present

    /** True while the layer is in the document (by this step). */
    private var applied = true

    /** The document this step was last applied to: [dispose] never lets go of a layer it holds. */
    private var doc: Document? = null

    override fun undo(c: EditorController) {
        c.structural {
            c.doc.layers.remove(layer)
            val i = activeBefore?.let { c.doc.indexOf(it) } ?: -1
            if (i >= 0) c.doc.activeLayerIndex = i
        }
        applied = false
        doc = c.doc
    }

    override fun redo(c: EditorController) {
        c.structural {
            c.doc.layers.add(index.coerceIn(0, c.doc.layers.size), layer)
            c.doc.activeLayerIndex = c.doc.indexOf(layer)
        }
        applied = true
        doc = c.doc
    }

    /** The added layer while undone (its tiles are this step's); null while applied, so P3's undo-age count is as before. */
    override val tileLayer: Layer? get() = if (applied) null else layer

    override fun forEachTile(block: (Tile) -> Unit) {
        if (applied) return
        forEachTileOf(layer.pixels, block)
        forEachTileOf(layer.maskPixels, block)
    }

    override fun dispose() {
        if (applied || layer.pins > 0 || doc?.indexOf(layer)?.let { it >= 0 } == true) return
        for (s in arrayOf(layer.pixels, layer.maskPixels)) if (s is TiledPixels) TileOps.releaseStore(s)
    }
}

/**
 * v1.6's delete (I11, a document without folders). Created applied: every caller removes the
 * layer first or applies the step with [redo] at once.
 *
 * v1.10 (A8, design 2.6): [byteSize] is the content's and the mask's logical bytes, fixed at
 * construction (no `bitmap` getter, so deleting a parked layer materializes nothing). While
 * applied, the removed layer's tiles are held by this step alone (undo-only, [TileHolder]), and
 * [dispose] then lets go of its tiled stores (a flat bitmap is left to the GC, as in v1.9), never
 * those of a layer the document holds again.
 */
class RemoveLayerAction(private val layer: Layer, private val index: Int, override val label: String = "Delete layer") : UndoAction, TileHolder {
    override val byteSize: Long = layer.pixels.logicalBytes() + (layer.maskPixels?.logicalBytes() ?: 0L)

    /** True while the layer is out of the document (by this step). */
    private var applied = true

    /** The document this step was last applied to: [dispose] never lets go of a layer it holds. */
    private var doc: Document? = null

    override fun undo(c: EditorController) {
        c.structural {
            c.doc.layers.add(index.coerceIn(0, c.doc.layers.size), layer)
            c.doc.activeLayerIndex = c.doc.indexOf(layer)
        }
        applied = false
        doc = c.doc
    }

    override fun redo(c: EditorController) {
        c.structural { c.doc.layers.remove(layer) }
        applied = true
        doc = c.doc
    }

    override val tileLayer: Layer get() = layer

    override fun forEachTile(block: (Tile) -> Unit) {
        if (!applied) return
        forEachTileOf(layer.pixels, block)
        forEachTileOf(layer.maskPixels, block)
    }

    override fun dispose() {
        if (!applied || layer.pins > 0 || doc?.indexOf(layer)?.let { it >= 0 } == true) return
        for (s in arrayOf(layer.pixels, layer.maskPixels)) if (s is TiledPixels) TileOps.releaseStore(s)
    }
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
 *
 * v1.10 (A8, design 2.6): holds mask stores. Created unapplied (every caller applies it with
 * [redo] at once): each undo and redo exchanges the layer's mask with the one this step holds, so
 * what comes back is the mask as it was when it left (parked, or made flat again meanwhile), never
 * a stale copy. [byteSize] is fixed at construction from the stores' logical bytes; [dispose] lets
 * go of a held tiled mask (a flat one is left to the GC, as in v1.9).
 */
class MaskChangeAction(private val layer: Layer, before: PixelStore?, after: PixelStore?, override val label: String) : UndoAction, TileHolder {
    /** v1.9's form: the masks as bitmaps. */
    constructor(layer: Layer, before: Bitmap?, after: Bitmap?, label: String) : this(layer, before?.let(::FlatPixels), after?.let(::FlatPixels), label)

    private val enabledBefore = layer.maskEnabled
    override val byteSize: Long = (before?.logicalBytes() ?: 0L) + (after?.logicalBytes() ?: 0L)

    /** The mask not in the layer now: the one to add until the first redo, then the one the last undo or redo took out. */
    private var held: PixelStore? = after

    /** Dropped from the history: an undo or redo still queued behind a waiting apply changes nothing. */
    private var disposed = false

    private fun exchange() {
        val out = layer.maskPixels
        layer.maskPixels = held
        held = out
    }

    override fun undo(c: EditorController) {
        if (disposed || c.undoManager.deferIfWaiting { undo(c) }) return
        c.structural {
            exchange()
            layer.maskEnabled = enabledBefore
            if (!layer.hasMask) layer.editingMask = false
            layer.markChanged()
        }
        activateIfParked(c, layer)
    }

    override fun redo(c: EditorController) {
        if (disposed || c.undoManager.deferIfWaiting { redo(c) }) return
        c.structural {
            exchange()
            if (!layer.hasMask) { layer.editingMask = false; layer.maskEnabled = true }
            layer.markChanged()
        }
        activateIfParked(c, layer)
    }

    override val tileLayer: Layer get() = layer

    override fun forEachTile(block: (Tile) -> Unit) = forEachTileOf(held, block)

    override fun dispose() {
        disposed = true
        releaseHeld(held, layer)
        held = null
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
 *
 * v1.10 (A8, design 2.6): holds stores. Created unapplied (the canvas operations apply it with
 * [redo] at once): each undo and redo exchanges each changed content or mask with the one this
 * step holds, so what comes back is the store as it was when it left (parked, swapped or made
 * flat again meanwhile), never a stale copy of it; parts an entry does not change are not
 * touched. [byteSize] is fixed at construction from the before stores' logical bytes. Afterwards
 * the active layer is made resident again if a tiled store came back into it. [dispose] lets go
 * of the tiled stores it holds (flat bitmaps are left to the GC, as in v1.9).
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
) : UndoAction, TileHolder {
    /**
     * One layer's bitmaps and editable data before and after. By default new pixels make the
     * layer a raster layer ([LayerData.rasterizedContent]) and a new mask a painted mask
     * ([LayerData.rasterizedMask]); a bitmap that stays the same keeps its data (as in v1.4).
     * Canvas operations pass the data mapped along with the pixels
     * (`LayerDataTransforms.transformed`). Undo restores [dataBefore].
     *
     * v1.10 (A8, design 2.6): the stores before and after (`snapshot()`s of parked layers, the
     * old bitmap itself for FLAT); what changes, and the default [dataAfter], compare store
     * identity. v1.9's form takes bitmaps: the same bitmap gives the same store.
     */
    class Entry(
        val layer: Layer,
        internal val storeBefore: PixelStore,
        internal val maskStoreBefore: PixelStore?,
        internal val storeAfter: PixelStore,
        internal val maskStoreAfter: PixelStore?,
        val dataBefore: LayerData = layer.dataSnapshot(),
        val dataAfter: LayerData = dataBefore
            .let { if (storeBefore !== storeAfter) it.rasterizedContent() else it }
            .let { if (maskStoreBefore !== maskStoreAfter) it.rasterizedMask() else it },
    ) {
        /** v1.9's form: the bitmaps before and after. */
        constructor(
            layer: Layer,
            bitmapBefore: Bitmap,
            maskBefore: Bitmap?,
            bitmapAfter: Bitmap,
            maskAfter: Bitmap?,
            dataBefore: LayerData = layer.dataSnapshot(),
            dataAfter: LayerData = dataBefore
                .let { if (bitmapBefore !== bitmapAfter) it.rasterizedContent() else it }
                .let { if (maskBefore !== maskAfter) it.rasterizedMask() else it },
        ) : this(layer, Flats(bitmapBefore, maskBefore, bitmapAfter, maskAfter), dataBefore, dataAfter)

        private constructor(layer: Layer, f: Flats, dataBefore: LayerData, dataAfter: LayerData) :
            this(layer, f.before, f.maskBefore, f.after, f.maskAfter, dataBefore, dataAfter)

        /** The content changes. */
        internal val contentChanges: Boolean get() = storeBefore !== storeAfter

        /** The mask changes (is added, removed or replaced). */
        internal val maskChanges: Boolean get() = maskStoreBefore !== maskStoreAfter

        /** v1.9's bitmaps as stores, the same bitmap giving the same store. */
        private class Flats(bitmapBefore: Bitmap, maskBefore: Bitmap?, bitmapAfter: Bitmap, maskAfter: Bitmap?) {
            val before: PixelStore = storeOf(bitmapBefore)
            val after: PixelStore = if (bitmapAfter === bitmapBefore) before else storeOf(bitmapAfter)
            val maskBefore: PixelStore? = maskBefore?.let(::FlatPixels)
            val maskAfter: PixelStore? = if (maskAfter === maskBefore) this.maskBefore else maskAfter?.let(::FlatPixels)

            private fun storeOf(b: Bitmap): PixelStore = if (b === Layer.FOLDER_BITMAP) FolderPixels else FlatPixels(b)
        }
    }

    override val byteSize: Long = entries.sumOf { it.storeBefore.logicalBytes() + (it.maskStoreBefore?.logicalBytes() ?: 0L) }

    /** Per entry, the content not in the layer now (only where it changes): the after store until the first redo, then the one the last undo or redo took out. */
    private val heldContent = arrayOfNulls<PixelStore>(entries.size)

    /** The same for the masks (null also = no mask; [Entry.maskChanges] tells which entries take part). */
    private val heldMask = arrayOfNulls<PixelStore>(entries.size)

    init {
        for ((i, e) in entries.withIndex()) {
            if (e.contentChanges) heldContent[i] = e.storeAfter
            if (e.maskChanges) heldMask[i] = e.maskStoreAfter
        }
    }

    /** Dropped from the history: an undo or redo still queued behind a waiting apply changes nothing (the stores it held are gone). */
    private var disposed = false

    private fun apply(c: EditorController, before: Boolean) {
        if (disposed || c.undoManager.deferIfWaiting { apply(c, before) }) return
        val size = if (before) sizeBefore else sizeAfter
        c.doc.setSize(size.first, size.second)
        c.doc.dpi = if (before) dpiBefore else dpiAfter
        c.doc.colorMode = if (before) modeBefore else modeAfter
        for ((i, e) in entries.withIndex()) {
            val content = heldContent[i]
            if (e.contentChanges && content != null) {
                heldContent[i] = e.layer.pixels
                e.layer.pixels = content
            }
            if (e.maskChanges) {
                val mask = heldMask[i]
                heldMask[i] = e.layer.maskPixels
                e.layer.maskPixels = mask
            }
            e.layer.restoreData(if (before) e.dataBefore else e.dataAfter)
            e.layer.markChanged()
        }
        c.onDocumentGeometryChanged()
        if (c.doc.layers.isNotEmpty()) activateIfParked(c, c.doc.activeLayer)
    }

    override fun undo(c: EditorController) = apply(c, true)
    override fun redo(c: EditorController) = apply(c, false)

    /** v1.10 (A8): the one layer of a one-entry step, else null. */
    override val tileLayer: Layer? get() = entries.singleOrNull()?.layer

    override fun forEachTile(block: (Tile) -> Unit) {
        for (i in entries.indices) {
            forEachTileOf(heldContent[i], block)
            forEachTileOf(heldMask[i], block)
        }
    }

    override fun dispose() {
        disposed = true
        for ((i, e) in entries.withIndex()) {
            releaseHeld(heldContent[i], e.layer)
            releaseHeld(heldMask[i], e.layer)
            heldContent[i] = null
            heldMask[i] = null
        }
    }
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

    /** Content, masks and array source pixels of the layers present in only one list (logical bytes; a folder counts 0). */
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

        /** v1.10 (A8, design 2.6): from the stores' logical bytes (a folder's are 0), so a deleted folder of parked layers materializes nothing. */
        fun bytesOf(l: Layer): Long =
            l.pixels.logicalBytes() + (l.maskPixels?.logicalBytes() ?: 0L) + (l.array?.pixels?.bytes ?: 0L)
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
