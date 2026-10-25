package com.brushwork.paint.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import com.brushwork.paint.ColorModeOps
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Parallel
import com.brushwork.paint.engine.budget.NativePurpose
import com.brushwork.paint.engine.tiles.BlankPixels
import com.brushwork.paint.engine.tiles.FlatPixels
import com.brushwork.paint.engine.tiles.PixelPin
import com.brushwork.paint.engine.tiles.PixelStore
import com.brushwork.paint.engine.tiles.TILE_SIZE
import com.brushwork.paint.engine.tiles.TileOps
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.masks.MaskSpecs
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.GridSettings
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.model.RulerSettings
import com.brushwork.paint.model.SavedSelection
import com.brushwork.paint.model.recycleUnlessShared
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.select.SavedSelectionOps
import com.brushwork.paint.ui.common.EngineLabels110
import com.brushwork.paint.vector.LayerDataTransforms
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.geom.ObjectIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Resampling method for [CanvasOps.resizeImage]. */
enum class Resample(val label: String, val description: String) {
    NEAREST("Nearest (pixel art)", "Hard pixel edges. Best for pixel art and exact 200%/400% enlargements."),
    BILINEAR("Bilinear", "Smooth linear interpolation; averages pixels when shrinking."),
    HIGH_QUALITY("High quality", "Sharp bicubic interpolation with antialiased, multi-step shrinking."),
}

/**
 * Immutable view of the document taken on the main thread. Background work reads only this
 * (source pixels are never mutated), so the live document stays consistent.
 *
 * v1.7 (X6): [layers] are the PIXEL layers only (folders have no bitmaps and are never mapped);
 * [savedSelections] are the document's saved selections, mapped along by
 * `SavedSelectionOps.mappedForCanvas` (item 14).
 *
 * v1.10 (A11, design 2.5 and 4.4 A11): a layer snapshot holds the layer's [PixelStore]s, never a
 * bitmap the facade made: [of] reads no pixels and materializes nothing (the stores as they are;
 * for a validation or a read on main), [take] also leases them for a canvas operation off main (a
 * `snapshot()` of every tiled store and a pin on every pixel layer, let go of by [Lease.release]
 * on main). Visibility is found in one pass from the top ([shownByIndex]).
 */
class CanvasSnapshot(
    val width: Int,
    val height: Int,
    val dpi: Float,
    val colorMode: ColorMode,
    val layers: List<LayerSnapshot>,
    val savedSelections: List<SavedSelection> = emptyList(),
) {
    class LayerSnapshot(
        val layer: Layer,
        /** v1.10 (A11): the content's store when the snapshot was taken (under a [Lease], a tiled one's `snapshot()`). Never changed. */
        val pixels: PixelStore,
        /** v1.10 (A11): the mask's store, null = no mask (as [pixels]). */
        val maskPixels: PixelStore?,
        val visible: Boolean,
        /** [Layer.contentVersion] when the snapshot was taken (detects pixel edits in between). */
        val contentVersion: Long = layer.contentVersion,
        /** The layer's editable data when the snapshot was taken (immutable: vector content, specs). */
        val data: LayerData = layer.dataSnapshot(),
    ) {
        /** The vector content (v1.5): scaling operations re-render it instead of resampling the cache. */
        val vector: VectorContent? get() = data.vector

        /**
         * v1.10 (F0, C16): [Layer.pixelGeneration] when the snapshot was taken (detects a bitmap or
         * mask replaced in between).
         */
        val pixelGeneration: Long = layer.pixelGeneration

        /** v1.10 (A11): the layer had a mask (never reads it). */
        val hasMask: Boolean get() = maskPixels != null

        /** v1.10 (F0, C16): [b] is this snapshot's own bitmap or mask (as [Layer.owns]; A11: a FLAT store's bitmap). */
        fun owns(b: Bitmap?): Boolean = b != null && (flatBitmapOf(pixels) === b || flatBitmapOf(maskPixels) === b)
    }

    /** Number of full-size bitmaps (layers + masks). */
    val bitmapCount: Int get() = layers.size + layers.count { it.hasMask }

    /** v1.10 (A11): results of layers other than [active] are cut into tiles as they are made (a [take] above Classic). */
    internal var parks: Boolean = false

    /** v1.10 (A11): the active layer when the snapshot was taken; its results stay FLAT. */
    internal var active: Layer? = null

    /** v1.10 (A11): tiled results let go of off main; [Lease.release] releases them on main. */
    internal val orphans = java.util.concurrent.ConcurrentLinkedQueue<TiledPixels>()

    /**
     * True while [doc] still has exactly the layers, bitmaps, pixels, size, dpi and color mode
     * this snapshot was taken from (so a result computed from it can be applied).
     */
    fun matches(doc: Document): Boolean {
        if (doc.width != width || doc.height != height || doc.pixelLayerCount != layers.size) return false
        if (doc.dpi != dpi || doc.colorMode != colorMode) return false
        var i = 0
        for (l in doc.layers) {
            if (l.isFolder) continue
            val s = layers[i++]
            if (!(l === s.layer && l.pixelGeneration == s.pixelGeneration && l.contentVersion == s.contentVersion)) return false
        }
        return true
    }

    /**
     * v1.10 (A11): a snapshot whose tiled stores are `snapshot()`s and whose pixel layers are pinned
     * (nothing parks, swaps out or frees what the operation reads off main). [release] once, on main,
     * after the operation committed or gave up.
     */
    class Lease internal constructor(val snapshot: CanvasSnapshot, private val pins: List<PixelPin>, private val copies: List<TiledPixels>) {
        private var released = false

        fun release() {
            if (released) return
            released = true
            for (p in pins) p.close()
            for (s in copies) TileOps.releaseStore(s)
            while (true) TileOps.releaseStore(snapshot.orphans.poll() ?: break)
        }
    }

    companion object {
        /** The stores as they are: reads no pixels, takes no lease. */
        fun of(doc: Document): CanvasSnapshot = build(doc) { it }

        /** v1.10 (A11): [of] with a lease for a canvas operation off main. Main only. */
        fun take(doc: Document): Lease {
            val copies = ArrayList<TiledPixels>()
            val pins = ArrayList<PixelPin>()
            try {
                val snap = build(doc) { p -> if (p is TiledPixels) TileOps.snapshot(p).also { copies += it } else p }
                for (l in doc.layers) if (!l.isFolder) pins += l.pin()
                snap.parks = EngineConfig.level != EngineLevel.CLASSIC
                snap.active = doc.layers.getOrNull(doc.activeLayerIndex)
                return Lease(snap, pins, copies)
            } catch (e: Throwable) {
                for (p in pins) p.close()
                for (s in copies) TileOps.releaseStore(s)
                throw e
            }
        }

        private fun build(doc: Document, storeOf: (PixelStore) -> PixelStore): CanvasSnapshot {
            val shown = shownByIndex(doc)
            val out = ArrayList<LayerSnapshot>(doc.layers.size)
            for ((i, l) in doc.layers.withIndex()) {
                if (l.isFolder) continue
                out += LayerSnapshot(l, storeOf(l.pixels), l.maskPixels?.let(storeOf), shown[i])
            }
            return CanvasSnapshot(doc.width, doc.height, doc.dpi, doc.colorMode, out, doc.savedSelections)
        }

        /**
         * v1.10 (A11): `doc.effectiveVisible` of every layer by flat index, in one pass from the top
         * (a folder lies above its children; a parent that is not a folder, or missing, counts as
         * the top level, as `LayerTree.parentOf`).
         */
        internal fun shownByIndex(doc: Document): BooleanArray {
            val layers = doc.layers
            val shown = BooleanArray(layers.size)
            val chain = HashMap<Long, Boolean>(layers.size * 2)
            for (i in layers.indices.reversed()) {
                val l = layers[i]
                shown[i] = l.visible && (l.parentId == Layer.ROOT_ID || chain[l.parentId] ?: true)
                chain[l.id] = if (l.isFolder) shown[i] else true
            }
            return shown
        }
    }
}

/** v1.10 (A11): the bitmap of a FLAT store, else null (never materializes). */
internal fun flatBitmapOf(p: PixelStore?): Bitmap? = (p as? FlatPixels)?.bitmap // I28: one layer

/**
 * v1.10 (A11, design 2.5): [b], a bitmap no layer holds yet, parked: cut into tiles (a
 * [BlankPixels] when it has no pixels) and recycled. Null, with [b] untouched, when the budget
 * refuses the tiles (the caller keeps it FLAT, as v1.9 did).
 */
internal fun parkedStoreOf(b: Bitmap): PixelStore? {
    val w = b.width
    val h = b.height
    val bytes = w.toLong() * h * 4
    val budget = EngineConfig.budget
    if (!budget.admitNative(bytes, NativePurpose.TILE)) return null
    val store = try {
        val tiles = TileOps.cut(b, detectBlank = true)
        if (tiles.tiles.all { it == null }) BlankPixels(w, h) else tiles
    } finally {
        // Tile.of counted the tiles under TILES; the reservation made room for them until then.
        budget.release(bytes, NativePurpose.TILE)
    }
    b.recycle()
    return store
}

/**
 * Output of a canvas operation: the new document size / dpi / color mode and the new bitmaps of
 * every layer ([layers] is empty for metadata-only changes such as a new dpi). [geometry] maps old
 * document coordinates to new ones (ruler and grid follow it).
 */
class CanvasResult(
    val width: Int,
    val height: Int,
    val dpi: Float,
    val colorMode: ColorMode,
    val layers: List<LayerResult>,
    val geometry: CanvasGeometry,
) {
    /**
     * One layer's new [pixels] and [maskPixels]; [data]: its editable data mapped along (computed
     * in the background with the pixels; null = map it at commit with `LayerDataTransforms`).
     * v1.10 (A11): stores; a layer whose pixels stay keeps the snapshot's own store, which [commit]
     * reads as the layer's store at that time. Results of a snapshot made by [CanvasSnapshot.of]
     * are FLAT (or the snapshot's own store), so [bitmap] and [mask] give them as v1.9 did.
     */
    class LayerResult(val layer: Layer, val pixels: PixelStore, val maskPixels: PixelStore?, val data: LayerData? = null) {
        /** The new content of a FLAT result. */
        val bitmap: Bitmap get() = flatBitmapOf(pixels) ?: error("a ${pixels.javaClass.simpleName} result has no bitmap")

        /** The new mask of a FLAT result; null without a mask. */
        val mask: Bitmap? get() = maskPixels?.let { flatBitmapOf(it) ?: error("a ${it.javaClass.simpleName} mask has no bitmap") }
    }

    /** Frees the pixels this result created (never the snapshot's own stores). */
    fun recycle(snapshot: CanvasSnapshot) = recycleCreated(layers, snapshot)

    internal companion object {
        fun recycleCreated(layers: List<LayerResult>, snapshot: CanvasSnapshot) {
            val source = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<PixelStore, Boolean>())
            snapshot.layers.forEach { source += it.pixels; it.maskPixels?.let { m -> source += m } }
            // (Rule B: never the folders' shared bitmap.)
            for (l in layers) {
                if (l.pixels !in source) disposeCreated(snapshot, l.pixels)
                l.maskPixels?.let { if (it !in source) disposeCreated(snapshot, it) }
            }
        }

        /** v1.10 (A11): lets go of [p], made for a result: a FLAT bitmap is recycled, a tiled store waits for [CanvasSnapshot.Lease.release] (main). */
        fun disposeCreated(snapshot: CanvasSnapshot, p: PixelStore) {
            when (p) {
                is FlatPixels -> flatBitmapOf(p)?.recycleUnlessShared()
                is TiledPixels -> snapshot.orphans += p
                else -> Unit
            }
        }
    }
}

/** A canvas operation can't run; [message] is shown to the user. */
class CanvasOpException(message: String) : Exception(message)

/** Thrown from a progress callback to abandon an operation (the user tapped Stop). */
class CanvasOpCancelledException : RuntimeException("Stopped")

/**
 * Document-wide operations: resize image, canvas size, trim/crop, rotate/flip canvas, resolution
 * and color mode.
 *
 * The first group of functions are pure: they read a [CanvasSnapshot] and build NEW bitmaps for
 * every layer and mask (safe on a background thread, usable from tests). `progress` callbacks get
 * 0..1 on the calling thread and may throw (e.g. [CanvasOpCancelledException]) to abort; bitmaps
 * created so far are then freed. The `apply*` functions are the UI entry points: they commit
 * pending tool work, compute on [Dispatchers.Default] under the controller's busy overlay (with
 * a Stop button) and record a single undo step ([commit]).
 */
object CanvasOps {
    /** Largest supported side, in pixels. */
    const val MAX_SIDE = 10_000

    /** Share of the heap all layer bitmaps may use (same budget as the controller's layer limit). */
    const val MEMORY_FRACTION = 0.55

    const val MIN_DPI = 1f
    const val MAX_DPI = 10_000f

    // ------------------------------------------------------------------ validation

    /** Bytes needed by [bitmapCount] ARGB bitmaps of [width] x [height]. */
    fun estimateBytes(bitmapCount: Int, width: Int, height: Int): Long = bitmapCount.toLong() * width * height * 4

    /** v1.10 (F0, C15): [LayerBudget.canvasBudgetBytes] ([LayerBudgetV19]: `maxMemory` x [MEMORY_FRACTION], as v1.9). */
    fun memoryBudget(): Long = LayerBudget.current.canvasBudgetBytes()

    /** Returns null when a [width] x [height] document with [bitmapCount] bitmaps is allowed, else why not. */
    fun validateSize(width: Int, height: Int, bitmapCount: Int, budget: Long = memoryBudget()): String? {
        if (width < 1 || height < 1) return "Width and height must be at least 1 px."
        if (width > MAX_SIDE || height > MAX_SIDE) return "Each side can be at most $MAX_SIDE px."
        val bytes = estimateBytes(bitmapCount, width, height)
        if (bytes > budget) {
            return "Needs ${formatBytes(bytes)} for $bitmapCount layer bitmaps, but only about ${formatBytes(budget)} is " +
                "available. Choose a smaller size, or merge or delete layers first."
        }
        return null
    }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 10L shl 20 -> "${(bytes / (1024.0 * 1024)).roundToInt()} MB"
        bytes >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        else -> "${(bytes / 1024.0).roundToInt().coerceAtLeast(1)} KB"
    }

    /**
     * True when switching [from] -> [to] rewrites pixels. Switching to RGB, or from monochrome to
     * grayscale, only changes the mode (the pixels already qualify).
     */
    fun convertsPixels(from: ColorMode, to: ColorMode): Boolean =
        to != from && to != ColorMode.RGB && !(to == ColorMode.GRAYSCALE && from == ColorMode.MONOCHROME)

    // ------------------------------------------------------------------ pure operations

    /**
     * Resamples every layer and mask to [newWidth] x [newHeight]; the document gets [dpi]. In a
     * monochrome document the smoothly resampled layers are thresholded back to 1-bit.
     */
    fun resizeImage(
        snap: CanvasSnapshot,
        newWidth: Int,
        newHeight: Int,
        resample: Resample,
        dpi: Float = snap.dpi,
        progress: (Float) -> Unit = {},
    ): CanvasResult {
        checkSize(snap, newWidth, newHeight)
        // Grayscale stays gray (equal channels are filtered identically); 1-bit needs a threshold.
        val constrain = snap.colorMode == ColorMode.MONOCHROME && resample != Resample.NEAREST
        val geometry = CanvasGeometry.scale(newWidth.toDouble() / snap.width, newHeight.toDouble() / snap.height)
        // Vector layers are re-rendered from their (scaled) objects: crisp at any size, and
        // editable masks from their scaled specs (v1.5). v1.7 (I14): every vector re-render and
        // exactness check here uses ArrayDraw.effectiveVector (a live array's copies included,
        // placed by the mapped spec).
        val layers = mapLayers(
            snap, progress, newWidth, newHeight,
            data = { l -> mappedData(l, geometry, newWidth, newHeight) },
            content = { _, d, sub -> d?.let { ArrayDraw.effectiveVector(it) }?.let { v -> renderVector(v, newWidth, newHeight, snap.colorMode, sub) } },
            maskOf = { _, d, sub -> specMask(d, newWidth, newHeight, sub) },
            keepsBlank = { !constrain },
        ) { _, src, isMask, sub ->
            if (constrain && !isMask) {
                val out = resampleBitmap(src, newWidth, newHeight, resample) { f -> sub(f * 0.8f) }
                try {
                    convertInto(out, out, ColorMode.MONOCHROME, 128, false) { f -> sub(0.8f + f * 0.2f) }
                } catch (t: Throwable) { out.recycle(); throw t }
                out
            } else {
                resampleBitmap(src, newWidth, newHeight, resample, sub)
            }
        }
        return CanvasResult(newWidth, newHeight, dpi, snap.colorMode, layers, geometry)
    }

    /**
     * Changes the canvas to [newWidth] x [newHeight], placing the old image's top-left corner at
     * ([offsetX], [offsetY]) (negative = cropped). New area is transparent (white in masks); with
     * [fillBottom] the new area of the bottom layer is filled with that color.
     */
    fun resizeCanvas(
        snap: CanvasSnapshot,
        newWidth: Int,
        newHeight: Int,
        offsetX: Int,
        offsetY: Int,
        fillBottom: Int? = null,
        progress: (Float) -> Unit = {},
    ): CanvasResult {
        checkSize(snap, newWidth, newHeight)
        val fill = fillBottom?.let { ColorModeOps.displayColor(it, snap.colorMode) }
        val bottom = snap.layers.first()
        val geometry = CanvasGeometry.translate(offsetX.toDouble(), offsetY.toDouble())
        val layers = mapLayers(
            snap, progress, newWidth, newHeight,
            data = { l ->
                val d = mappedData(l, geometry, newWidth, newHeight)
                // A filled bottom layer gets pixels its objects don't make: it becomes a raster layer.
                if (fill != null && l === bottom && d.vector != null) d.copy(vector = null, array = null) else d
            },
            content = { l, d, sub ->
                // Objects reaching past the old edges into the new canvas, or paper grain moved
                // off its document grid, are drawn again; otherwise the cache moves exactly.
                val v = d?.let { ArrayDraw.effectiveVector(it) }
                val old = ArrayDraw.effectiveVector(l.data)
                if (v != null && old != null && (exposesObjects(old, snap.width, snap.height, newWidth, newHeight, offsetX, offsetY) ||
                        !LayerDataTransforms.shiftsExactly(old, offsetX, offsetY))) {
                    renderVector(v, newWidth, newHeight, snap.colorMode, sub)
                } else null
            },
            // An editable mask is drawn from its moved spec (the new margin is what the spec
            // renders there, not white).
            maskOf = { _, d, sub -> specMask(d, newWidth, newHeight, sub) },
            keepsBlank = { l -> fill == null || l !== bottom },
        ) { l, src, isMask, _ ->
            val background = when {
                isMask -> MASK_WHITE
                fill != null && l === bottom -> fill
                else -> 0
            }
            shifted(src, newWidth, newHeight, offsetX, offsetY, background)
        }
        return CanvasResult(newWidth, newHeight, snap.dpi, snap.colorMode, layers, geometry)
    }

    /** Crops every layer to [rect] (clamped to the document). */
    fun cropTo(snap: CanvasSnapshot, rect: Rect, progress: (Float) -> Unit = {}): CanvasResult {
        val r = Rect(rect)
        if (!r.intersect(0, 0, snap.width, snap.height) || r.isEmpty) throw CanvasOpException("The crop rectangle is outside the canvas.")
        return resizeCanvas(snap, r.width(), r.height(), -r.left, -r.top, null, progress)
    }

    /**
     * Union of the non-transparent pixels of all visible layers (masks are ignored), or an empty
     * rect when nothing visible is painted.
     */
    fun opaqueBounds(snap: CanvasSnapshot, progress: (Float) -> Unit = {}): Rect {
        val out = Rect()
        val visible = snap.layers.filter { it.visible }
        visible.forEachIndexed { i, l ->
            opaqueBoundsOf(l.pixels) { f -> progress((i + f) / visible.size) }?.let { out.union(it) }
        }
        return out
    }

    /**
     * v1.10 (A11): [opaqueBounds] of one store, never materialized: a FLAT bitmap as v1.9, a tiled
     * store row of tiles by row of tiles (rows without a tile are skipped), nothing for a blank one.
     */
    private fun opaqueBoundsOf(p: PixelStore, progress: (Float) -> Unit): Rect? {
        flatBitmapOf(p)?.let { return opaqueBounds(it, progress) }
        if (p !is TiledPixels) return null.also { progress(1f) }
        if (p.evictedTo != null) throw CanvasOpException(EngineLabels110.CANVAS_OP_TOO_BIG)
        val w = p.width
        val h = p.height
        val bytes = w.toLong() * min(h, TILE_SIZE) * 4
        val budget = EngineConfig.budget
        if (!budget.admitNative(bytes, NativePurpose.TRANSIENT)) throw CanvasOpException(EngineLabels110.CANVAS_OP_TOO_BIG)
        val out = Rect()
        val r = Rect()
        var band: Bitmap? = null
        try {
            var y = 0
            while (y < h) {
                val n = min(TILE_SIZE, h - y)
                r.set(0, y, w, y + n)
                if (!TileOps.isEmptyIn(p, r)) {
                    val b = band?.takeIf { it.height == n }
                        ?: BitmapUtils.createLayerBitmap(w, n).also { band?.recycle(); band = it; it.density = Bitmap.DENSITY_NONE }
                    TileOps.readRegion(p, r, b)
                    opaqueBounds(b)?.let { it.offset(0, y); out.union(it) }
                }
                y += n
                progress(y / h.toFloat())
            }
        } finally {
            band?.recycle()
            budget.release(bytes, NativePurpose.TRANSIENT)
        }
        return if (out.isEmpty) null else out
    }

    /** Crops to [opaqueBounds]. Throws [CanvasOpException] when there's nothing to trim. */
    fun trimTransparent(snap: CanvasSnapshot, progress: (Float) -> Unit = {}): CanvasResult {
        val bounds = opaqueBounds(snap) { f -> progress(f * 0.5f) }
        if (bounds.isEmpty) throw CanvasOpException("Nothing to trim: the visible layers are empty.")
        if (bounds.width() == snap.width && bounds.height() == snap.height) throw CanvasOpException("There are no transparent edges to trim.")
        return cropTo(snap, bounds) { f -> progress(0.5f + f * 0.5f) }
    }

    /** Rotates the whole canvas (all layers and masks). */
    fun rotate(snap: CanvasSnapshot, rotation: CanvasRotation, progress: (Float) -> Unit = {}): CanvasResult {
        if (snap.layers.isEmpty()) throw CanvasOpException("The drawing has no layers.")
        val swap = rotation.quarterTurnsCw % 2 == 1
        val w = if (swap) snap.height else snap.width
        val h = if (swap) snap.width else snap.height
        val geometry = CanvasGeometry.rotate(rotation, snap.width, snap.height)
        // Pixels are remapped exactly; vector objects and mask specs turn with them (a vector
        // layer whose brushes don't turn into themselves is drawn again from its objects).
        val layers = mapLayers(
            snap, progress, w, h,
            data = { l -> mappedData(l, geometry, w, h) },
            content = { l, d, sub ->
                val v = d?.let { ArrayDraw.effectiveVector(it) }
                val old = ArrayDraw.effectiveVector(l.data)
                if (v != null && old != null && !LayerDataTransforms.turnsExactly(old, rotation.quarterTurnsCw, mirror = false)) {
                    renderVector(v, w, h, snap.colorMode, sub)
                } else null
            },
            maskOf = { _, d, sub -> specMask(d, w, h, sub) },
            keepsBlank = { true },
        ) { _, src, _, _ -> transformed(src, w, h, geometry) }
        return CanvasResult(w, h, snap.dpi, snap.colorMode, layers, geometry)
    }

    /** Mirrors the whole canvas (all layers and masks). */
    fun flip(snap: CanvasSnapshot, horizontal: Boolean, progress: (Float) -> Unit = {}): CanvasResult {
        if (snap.layers.isEmpty()) throw CanvasOpException("The drawing has no layers.")
        val geometry = CanvasGeometry.flip(horizontal, snap.width, snap.height)
        val layers = mapLayers(
            snap, progress, snap.width, snap.height,
            data = { l -> mappedData(l, geometry, snap.width, snap.height) },
            content = { l, d, sub ->
                val v = d?.let { ArrayDraw.effectiveVector(it) }
                val old = ArrayDraw.effectiveVector(l.data)
                if (v != null && old != null && !LayerDataTransforms.turnsExactly(old, 0, mirror = true)) {
                    renderVector(v, snap.width, snap.height, snap.colorMode, sub)
                } else null
            },
            maskOf = { _, d, sub -> specMask(d, snap.width, snap.height, sub) },
            keepsBlank = { true },
        ) { _, src, _, _ -> transformed(src, snap.width, snap.height, geometry) }
        return CanvasResult(snap.width, snap.height, snap.dpi, snap.colorMode, layers, geometry)
    }

    /** Changes only the resolution (print size); pixels are untouched. */
    fun setDpi(snap: CanvasSnapshot, dpi: Float): CanvasResult {
        if (!(dpi >= MIN_DPI && dpi <= MAX_DPI)) throw CanvasOpException("Resolution must be between 1 and 10000 dpi.")
        return CanvasResult(snap.width, snap.height, dpi, snap.colorMode, emptyList(), CanvasGeometry.IDENTITY)
    }

    /**
     * Converts every layer's pixels (masks are unchanged) to [mode] and sets the document mode.
     * [threshold] (1..255) and [dither] (Floyd–Steinberg) apply to MONOCHROME. Mode changes that
     * don't need new pixels ([convertsPixels] is false) return no layer bitmaps.
     */
    fun convertColorMode(
        snap: CanvasSnapshot,
        mode: ColorMode,
        threshold: Int = 128,
        dither: Boolean = false,
        progress: (Float) -> Unit = {},
    ): CanvasResult {
        val hasVectors = snap.layers.any { it.vector != null }
        if (!hasVectors) {
            if (!convertsPixels(snap.colorMode, mode)) return CanvasResult(snap.width, snap.height, snap.dpi, mode, emptyList(), CanvasGeometry.IDENTITY)
            val plain = mapLayers(snap, progress, snap.width, snap.height, transformMasks = false) { _, src, _, sub -> converted(src, mode, threshold, dither, sub) }
            return CanvasResult(snap.width, snap.height, snap.dpi, mode, plain, CanvasGeometry.IDENTITY)
        }
        // Vector layers (v1.5): their cache stays exactly what their objects render to in the
        // document's mode (every later partial re-render is held to the mode with the standard
        // conversion), so they keep their objects and never get seams.
        val convertedData: (CanvasSnapshot.LayerSnapshot) -> LayerData = { l -> if (l.data.isEmpty) l.data else LayerDataTransforms.transformed(l.data, Matrix(), snap.width, snap.height) }
        if (!convertsPixels(snap.colorMode, mode)) {
            if (!hasVectors) return CanvasResult(snap.width, snap.height, snap.dpi, mode, emptyList(), CanvasGeometry.IDENTITY)
            // The pixels already qualify, but vector layers were held to the old mode (grey or
            // 1-bit): they are drawn again in the new one. Every other layer keeps its pixels
            // (and its data; v1.10 A11: its store, never read).
            val layers = mapLayers(
                snap, progress, snap.width, snap.height, transformMasks = false,
                data = { l -> l.data },
                content = { _, d, sub -> d?.let { ArrayDraw.effectiveVector(it) }?.let { v -> renderVector(v, snap.width, snap.height, mode, sub) } },
                op = null,
            )
            return CanvasResult(snap.width, snap.height, snap.dpi, mode, layers, CanvasGeometry.IDENTITY)
        }
        val layers = mapLayers(
            snap, progress, snap.width, snap.height, transformMasks = false,
            data = convertedData,
        ) { l, src, _, sub ->
            // A vector layer is converted the standard way (threshold 128, no dithering):
            // what its later re-renders get, so it stays one consistent rendering.
            if (l.vector != null && mode == ColorMode.MONOCHROME && (dither || threshold.coerceIn(1, 255) != 128)) {
                converted(src, mode, 128, false, sub)
            } else {
                converted(src, mode, threshold, dither, sub)
            }
        }
        return CanvasResult(snap.width, snap.height, snap.dpi, mode, layers, CanvasGeometry.IDENTITY)
    }

    /** [src] converted to [mode] into a new bitmap. */
    private fun converted(src: Bitmap, mode: ColorMode, threshold: Int, dither: Boolean, sub: (Float) -> Unit): Bitmap {
        val out = BitmapUtils.createLayerBitmap(src.width, src.height)
        try {
            convertInto(src, out, mode, threshold, dither, sub)
        } catch (t: Throwable) { out.recycle(); throw t }
        return out
    }

    // ------------------------------------------------------------------ commit (main thread)

    /**
     * Applies [result] (computed from [snap]) to the controller's document as ONE undo step:
     * a [StoresAction] (v1.10 A11; wrapped so the ruler/grid follow the artwork, the selection comes
     * back on undo and the active tool is reset around the swap), or a small metadata action when
     * no bitmaps changed. [savedAfter]: the saved selections after the operation (v1.7, item 14;
     * mapped in the background by [run]); an entry whose `packed` changed is given a fresh
     * revision here (`EditorController.withNewSavedRevisions`).
     */
    fun commit(
        c: EditorController,
        label: String,
        snap: CanvasSnapshot,
        result: CanvasResult,
        savedAfter: List<SavedSelection> = SavedSelectionOps.mappedForCanvas(snap.savedSelections, result, snap.width, snap.height),
    ): UndoAction {
        val action: UndoAction = if (result.layers.isEmpty()) {
            require(result.width == snap.width && result.height == snap.height) { "Size changes need new bitmaps" }
            MetadataAction(label, snap.dpi, result.dpi, snap.colorMode, result.colorMode)
        } else {
            require(result.layers.size == snap.layers.size) { "Every layer needs new bitmaps" }
            var bytesBefore = 0L
            var bytesAfter = 0L
            // The layers' editable data follows the artwork (v1.5): vector content and mask specs
            // are mapped by the same affine (in the background with the pixels when the
            // operation did so); data the pixels no longer match is cleared.
            val matrix = matrixOf(result.geometry)
            val entries = result.layers.mapIndexed { i, r ->
                val s = snap.layers[i]
                require(s.layer === r.layer)
                // v1.10 (A11): stores move by reference. "Before" is what the layer holds now (a
                // snapshot's own store is the layer's store, perhaps rehoused since); "after" is
                // the result, or that same store when the pixels stay.
                val content = r.pixels !== s.pixels
                val masked = r.maskPixels !== s.maskPixels
                val before = r.layer.pixels
                val beforeMask = r.layer.maskPixels
                val after = if (content) r.pixels else before
                val afterMask = if (masked) r.maskPixels else beforeMask
                if (content) { bytesBefore += bytesOf(before); bytesAfter += bytesOf(after) }
                if (masked) { bytesBefore += bytesOf(beforeMask); bytesAfter += bytesOf(afterMask) }
                val dataBefore = r.layer.dataSnapshot()
                val dataAfter = when {
                    r.data != null && dataBefore == s.data -> r.data
                    content || masked -> LayerDataTransforms.transformed(dataBefore, matrix, result.width, result.height)
                    else -> dataBefore
                }
                StoresAction.Entry(r.layer, before, beforeMask, after, afterMask, dataBefore, dataAfter)
            }
            val inner = StoresAction(
                label, entries,
                snap.width to snap.height, result.width to result.height,
                snap.dpi, result.dpi, snap.colorMode, result.colorMode,
            )
            // A saved selection whose pixels the operation changed gets a new revision (its file
            // name), never one an undone operation or update used: G's mapping need not track them.
            val savedBefore = c.doc.savedSelections
            CanvasChangeAction(
                inner, result.geometry, snap.width, snap.height, result.width, result.height,
                selectionBefore = c.selection, bytesBefore = bytesBefore, bytesAfter = bytesAfter,
                savedBefore = savedBefore, savedAfter = c.withNewSavedRevisions(savedBefore, savedAfter),
            )
        }
        action.redo(c)
        c.pushUndo(action)
        return action
    }

    /**
     * Runs [compute] on a background thread under the busy overlay (with a Stop button) and
     * commits it on the main thread. Pending tool work is committed first. Returns false if
     * another operation is running.
     */
    fun run(c: EditorController, label: String, compute: (CanvasSnapshot, (Float) -> Unit) -> CanvasResult): Boolean {
        if (c.busyMessage != null) return false
        // v1.10 (A11): a closing editor never starts the work that would let go of the lease.
        if (!c.scope.isActive) return false
        c.filterSession?.cancel()
        // Object bar actions waiting for a vector render land first too (v1.5 QA), while the tool is
        // still active: made later, under the busy overlay, they changed the drawing and the
        // operation was refused ("The drawing changed while …").
        c.settleVectorWork()
        c.currentTool.onDeactivate()
        // A vector edit still rendering lands first (the snapshot must hold its result).
        c.vectors.flushPending()
        // So does a saved selection still compressing (v1.7): it is mapped with the others (made
        // after the operation, its mask would not follow the artwork).
        c.landSavedSelections()
        // v1.10 (A11): the stores, leased (snapshot()s and pins) until the work committed or gave up.
        val lease = CanvasSnapshot.take(c.doc)
        val snap = lease.snapshot
        val stop = AtomicBoolean(false)
        c.runBusy(label, onCancel = { stop.set(true) }) {
            var committed = false
            var saved = snap.savedSelections
            try {
                val result = withContext(Dispatchers.Default) {
                    val context = coroutineContext
                    var lastPosted = -1
                    compute(snap) { p ->
                        if (stop.get()) throw CanvasOpCancelledException()
                        context.ensureActive()
                        val pct = (p * 100).toInt()
                        if (pct != lastPosted) {
                            lastPosted = pct
                            c.scope.launch { if (c.busyMessage == label) c.busyProgress = p.coerceIn(0f, 1f) }
                        }
                    }.also { r ->
                        // v1.7 (item 14): the saved selections follow the artwork, after the layers.
                        saved = try {
                            SavedSelectionOps.mappedForCanvas(snap.savedSelections, r, snap.width, snap.height)
                        } catch (t: Throwable) {
                            r.recycle(snap); throw t
                        }
                    }
                }
                when {
                    stop.get() -> {
                        result.recycle(snap)
                        c.toast("Stopped \"$label\"; nothing was changed.")
                    }
                    !snap.matches(c.doc) -> {
                        result.recycle(snap)
                        c.toast("The drawing changed while \"$label\" was running; nothing was applied.")
                    }
                    else -> {
                        // The action's redo reactivates the tool after swapping the bitmaps.
                        // (A list changed meanwhile is mapped now, on the main thread.)
                        commit(c, label, snap, result, if (c.doc.savedSelections === snap.savedSelections) saved else SavedSelectionOps.mappedForCanvas(c.doc.savedSelections, result, snap.width, snap.height))
                        committed = true
                    }
                }
            } catch (e: CanvasOpCancelledException) {
                c.toast("Stopped \"$label\"; nothing was changed.")
            } catch (e: CanvasOpException) {
                c.toast(e.message ?: label)
            } catch (e: OutOfMemoryError) {
                // v1.10 (A11): above Classic the layers are made one at a time; what did not fit is the canvas with this many layers.
                c.toast(if (EngineConfig.level != EngineLevel.CLASSIC) EngineLabels110.CANVAS_OP_TOO_BIG else "Not enough memory for \"$label\". Try a smaller size or fewer layers.")
            } finally {
                lease.release()
                // Skipped when the editor is closing (scope cancelled): the controller is being disposed.
                if (!committed && coroutineContext.isActive) c.currentTool.onActivate()
            }
        }
        return true
    }

    /** Applies a metadata-only change (no new bitmaps) immediately on the main thread. */
    private fun applyNow(c: EditorController, label: String, compute: (CanvasSnapshot) -> CanvasResult): Boolean {
        if (c.busyMessage != null) return false
        c.settleVectorWork()
        c.vectors.flushPending()
        val snap = CanvasSnapshot.of(c.doc)
        return try {
            commit(c, label, snap, compute(snap))
            true
        } catch (e: CanvasOpException) {
            c.toast(e.message ?: label)
            false
        }
    }

    // ------------------------------------------------------------------ UI entry points

    /** Resize image (resamples pixels). Returns false (with a message) if the size isn't allowed. */
    fun applyResizeImage(c: EditorController, width: Int, height: Int, dpi: Float, resample: Resample): Boolean {
        val doc = c.doc
        if (width == doc.width && height == doc.height) {
            return if (dpi != doc.dpi) applyDpi(c, dpi) else false
        }
        if (!(dpi >= MIN_DPI && dpi <= MAX_DPI)) { c.toast("Resolution must be between 1 and 10000 dpi."); return false }
        // v1.10 (F0 C15, A11): the budget decides (A7: on R).
        LayerBudget.current.canTransformCanvas(doc, width, height)?.let { c.toast(it); return false }
        return run(c, "Resize image") { s, p -> resizeImage(s, width, height, resample, dpi, p) }
    }

    /** Canvas size with a 3x3 anchor ([anchorX], [anchorY] in 0..2). */
    fun applyResizeCanvas(c: EditorController, width: Int, height: Int, anchorX: Int, anchorY: Int, fillBottom: Int?): Boolean {
        val doc = c.doc
        if (width == doc.width && height == doc.height) return false
        LayerBudget.current.canTransformCanvas(doc, width, height)?.let { c.toast(it); return false }
        val ox = CanvasGeometry.anchorOffset(doc.width, width, anchorX)
        val oy = CanvasGeometry.anchorOffset(doc.height, height, anchorY)
        return run(c, "Canvas size") { s, p -> resizeCanvas(s, width, height, ox, oy, fillBottom, p) }
    }

    fun applyTrim(c: EditorController): Boolean = run(c, "Trim") { s, p -> trimTransparent(s, p) }

    fun applyCropToSelection(c: EditorController): Boolean {
        val sel = c.selection
        if (sel == null || sel.isEmpty) { c.toast("Make a selection first"); return false }
        val bounds = Rect(sel.bounds)
        if (bounds.left == 0 && bounds.top == 0 && bounds.right == c.doc.width && bounds.bottom == c.doc.height) {
            c.toast("The selection already covers the whole canvas"); return false
        }
        return run(c, "Crop to selection") { s, p -> cropTo(s, bounds, p) }
    }

    fun applyCrop(c: EditorController, rect: Rect): Boolean {
        val r = Rect(rect)
        if (!r.intersect(0, 0, c.doc.width, c.doc.height) || r.isEmpty) { c.toast("The crop rectangle is outside the canvas"); return false }
        if (r.width() == c.doc.width && r.height() == c.doc.height) { c.toast("The rectangle covers the whole canvas"); return false }
        return run(c, "Crop") { s, p -> cropTo(s, r, p) }
    }

    fun applyRotate(c: EditorController, rotation: CanvasRotation): Boolean =
        run(c, rotation.label) { s, p -> rotate(s, rotation, p) }

    fun applyFlip(c: EditorController, horizontal: Boolean): Boolean =
        run(c, if (horizontal) "Flip canvas horizontally" else "Flip canvas vertically") { s, p -> flip(s, horizontal, p) }

    /** Resolution only (no resampling); applied immediately. */
    fun applyDpi(c: EditorController, dpi: Float): Boolean {
        if (dpi == c.doc.dpi) return false
        return applyNow(c, "Resolution") { s -> setDpi(s, dpi) }
    }

    fun applyColorMode(c: EditorController, mode: ColorMode, threshold: Int, dither: Boolean): Boolean {
        val from = c.doc.colorMode
        if (mode == from) return false
        val label = "Color mode: ${mode.label}"
        // Vector layers are drawn again in the new mode (v1.5): in the background then.
        if (!convertsPixels(from, mode) && c.doc.layers.none { it.isVectorLayer }) return applyNow(c, label) { s -> convertColorMode(s, mode) }
        return run(c, label) { s, p -> convertColorMode(s, mode, threshold, dither, p) }
    }

    // ------------------------------------------------------------------ bitmap helpers

    private const val MASK_WHITE = -1

    private fun checkSize(snap: CanvasSnapshot, w: Int, h: Int) {
        if (w < 1 || h < 1 || w > MAX_SIDE || h > MAX_SIDE) throw CanvasOpException("Each side must be between 1 and $MAX_SIDE px.")
        if (snap.layers.isEmpty()) throw CanvasOpException("The drawing has no layers.")
    }

    /**
     * Builds new bitmaps for every layer (and its mask when [transformMasks]) with
     * `op(layer, source, isMask, subProgress)`; on failure frees what was already created and
     * rethrows. [data] maps a layer's editable data along (v1.5; null = at commit); [content] may
     * draw a layer's new pixels itself (a vector layer re-rendered from its mapped objects, given
     * the mapped data) instead of `op` (null = use `op`; a null `op` keeps the layer's pixels).
     *
     * v1.10 (A11, design 2.5 "Canvas ops"): one layer at a time, [outW] x [outH] each. A layer's
     * source is its FLAT bitmap, or a temporary bitmap of its tiled (or blank) store made on this
     * thread under the snapshot's lease and recycled after `op`; a blank store stays blank where
     * [keepsBlank] says the operation turns transparent into transparent. Each result is admitted
     * (TRANSIENT) before it is made and, when the snapshot [CanvasSnapshot.parks], cut into tiles
     * at once ([settle]), so only one layer is ever FLAT here; a refusal is [CanvasOpException]
     * with `EngineLabels110.CANVAS_OP_TOO_BIG`.
     */
    private fun mapLayers(
        snap: CanvasSnapshot,
        progress: (Float) -> Unit,
        outW: Int,
        outH: Int,
        transformMasks: Boolean = true,
        data: ((CanvasSnapshot.LayerSnapshot) -> LayerData)? = null,
        content: ((CanvasSnapshot.LayerSnapshot, LayerData?, (Float) -> Unit) -> Bitmap?)? = null,
        /** A layer's new mask drawn from its mapped data instead of `op` (null = use `op`). */
        maskOf: ((CanvasSnapshot.LayerSnapshot, LayerData?, (Float) -> Unit) -> Bitmap?)? = null,
        /** v1.10 (A11): a blank content store of this layer gives a blank result (null = never). */
        keepsBlank: ((CanvasSnapshot.LayerSnapshot) -> Boolean)? = null,
        op: ((CanvasSnapshot.LayerSnapshot, Bitmap, Boolean, (Float) -> Unit) -> Bitmap)?,
    ): List<CanvasResult.LayerResult> {
        val out = ArrayList<CanvasResult.LayerResult>(snap.layers.size)
        val total = (snap.layers.size + (if (transformMasks) snap.layers.count { it.hasMask } else 0)).toFloat()
        var done = 0
        fun subProgress(): (Float) -> Unit {
            val base = done
            return { f -> progress((base + f.coerceIn(0f, 1f)) / total) }
        }
        var pending: PixelStore? = null
        try {
            progress(0f)
            for (l in snap.layers) {
                val d = data?.invoke(l)
                val sub = subProgress()
                val keep = keepsBlank?.invoke(l) == true
                val px = mapStore(snap, l, l.pixels, outW, outH, keep, { content?.invoke(l, d, sub) }, op?.let { o -> { src -> o(l, src, false, sub) } })
                // (A layer whose pixels stay keeps the snapshot's own store: never freed here.)
                pending = px.takeIf { it !== l.pixels }
                progress(++done / total)
                val m = l.maskPixels
                val mask = if (transformMasks && m != null) {
                    val msub = subProgress()
                    mapStore(snap, l, m, outW, outH, false, { maskOf?.invoke(l, d, msub) }, op?.let { o -> { src -> o(l, src, true, msub) } })
                        .also { progress(++done / total) }
                } else {
                    m
                }
                out += CanvasResult.LayerResult(l.layer, px, mask, d)
                pending = null
            }
        } catch (t: Throwable) {
            pending?.let { CanvasResult.disposeCreated(snap, it) }
            CanvasResult.recycleCreated(out, snap)
            throw t
        }
        return out
    }

    /**
     * v1.10 (A11): one store of [l] ([store], its content or its mask) mapped to [w] x [h]: what
     * [drawn] draws, else `op` on its source, else (no `op`) the store itself.
     */
    private fun mapStore(
        snap: CanvasSnapshot,
        l: CanvasSnapshot.LayerSnapshot,
        store: PixelStore,
        w: Int,
        h: Int,
        keepsBlank: Boolean,
        drawn: () -> Bitmap?,
        op: ((Bitmap) -> Bitmap)?,
    ): PixelStore {
        val bytes = w.toLong() * h * 4
        val admit = EngineConfig.level != EngineLevel.CLASSIC
        val budget = EngineConfig.budget
        if (admit && !budget.admitNative(bytes, NativePurpose.TRANSIENT)) throw CanvasOpException(EngineLabels110.CANVAS_OP_TOO_BIG)
        try {
            drawn()?.let { return settle(snap, l, it) }
            if (op == null) return store
            if (keepsBlank && store is BlankPixels) return BlankPixels(w, h)
            flatBitmapOf(store)?.let { src ->
                val b = op(src)
                return if (b === src) store else settle(snap, l, b)
            }
            val src = temporaryOf(store)
            try {
                val b = op(src)
                return if (b === src) store else settle(snap, l, b)
            } finally {
                src.recycle()
            }
        } finally {
            if (admit) budget.release(bytes, NativePurpose.TRANSIENT)
        }
    }

    /** v1.10 (A11): a bitmap of a tiled or blank [store], made on this thread (under the snapshot's lease); the caller recycles it. */
    private fun temporaryOf(store: PixelStore): Bitmap = when (store) {
        is TiledPixels -> {
            if (store.evictedTo != null) throw CanvasOpException(EngineLabels110.CANVAS_OP_TOO_BIG)
            TileOps.materialize(store)
        }
        else -> {
            val bytes = store.width.toLong() * store.height * 4
            val budget = EngineConfig.budget
            if (!budget.admitNative(bytes, NativePurpose.LAYER)) throw OutOfMemoryError("No room for a ${store.width}x${store.height} layer")
            budget.release(bytes, NativePurpose.LAYER)
            BitmapUtils.createLayerBitmap(store.width, store.height)
        }
    }

    /**
     * v1.10 (A11): where a new bitmap [b] of [l] goes. FLAT when the snapshot does not park
     * (Classic, or a snapshot of [CanvasSnapshot.of]) or [l] is the active layer; else cut into
     * tiles here and recycled ("park the result", as a load builds a parked layer's tiles off main:
     * fresh tiles no other thread sees, each counted under TILES), blank when no tile is left.
     */
    private fun settle(snap: CanvasSnapshot, l: CanvasSnapshot.LayerSnapshot, b: Bitmap): PixelStore {
        if (!snap.parks || l.layer === snap.active) return FlatPixels(b)
        try {
            val w = b.width
            val h = b.height
            val bytes = w.toLong() * h * 4
            val budget = EngineConfig.budget
            if (!budget.admitNative(bytes, NativePurpose.TILE)) throw CanvasOpException(EngineLabels110.CANVAS_OP_TOO_BIG)
            try {
                val tiles = TileOps.cut(b, detectBlank = true)
                return if (tiles.tiles.all { it == null }) BlankPixels(w, h) else tiles
            } finally {
                // Tile.of counted the tiles under TILES; the reservation made room for them until then.
                budget.release(bytes, NativePurpose.TILE)
            }
        } finally {
            b.recycle()
        }
    }

    private fun newBitmap(w: Int, h: Int, fill: Int): Bitmap =
        BitmapUtils.createLayerBitmap(w, h).also { if (fill != 0) it.eraseColor(fill) }

    // ------------------------------------------------------------------ editable data (v1.5)

    /** [g] as an android Matrix (old -> new document px). */
    internal fun matrixOf(g: CanvasGeometry): Matrix = Matrix().apply {
        setValues(floatArrayOf(g.a.toFloat(), g.b.toFloat(), g.tx.toFloat(), g.c.toFloat(), g.d.toFloat(), g.ty.toFloat(), 0f, 0f, 1f))
    }

    /** [l]'s editable data after the geometry change [g] (see LayerDataTransforms). */
    private fun mappedData(l: CanvasSnapshot.LayerSnapshot, g: CanvasGeometry, newW: Int, newH: Int): LayerData =
        if (l.data.isEmpty) l.data else LayerDataTransforms.transformed(l.data, matrixOf(g), newW, newH)

    /**
     * A layer's new mask when its data [d] (already mapped) keeps an editable mask spec: the
     * spec rendered at the new [w] x [h] (I1: the mask stays exactly its spec's rendering, which
     * resampled pixels — or a new canvas margin filled white — would not be). Null without a
     * spec: the mask's pixels are mapped like the layer's. Rendered in bands of rows (the bytes
     * of [MaskSpecs.newMask]: a spec renders the same whatever the region), reporting progress
     * between them, so Stop is honoured during a long (brush-heavy, large) mask too.
     */
    private fun specMask(d: LayerData?, w: Int, h: Int, sub: (Float) -> Unit): Bitmap? {
        val spec = d?.maskSpec ?: return null
        sub(0f)
        val m = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            val band = max(1, min(h, MASK_BAND_PIXELS / max(1, w)))
            val buf = IntArray(w * band)
            val region = Rect()
            var y = 0
            while (y < h) {
                val rows = min(band, h - y)
                region.set(0, y, w, y + rows)
                MaskSpecs.render(spec, w, h, region, buf, w)
                m.setPixels(buf, 0, w, 0, y, w, rows)
                y += rows
                sub(y.toFloat() / h)
            }
        } catch (t: Throwable) { m.recycle(); throw t }
        return m
    }

    /** Pixels of an editable mask rendered between two progress reports (as MaskSpecs' own bands). */
    private const val MASK_BAND_PIXELS = 1 shl 18

    /**
     * A vector layer's new pixels: its (already mapped) objects rendered at the new size, held to
     * the document's color mode. [sub] reports progress (and may throw to stop).
     */
    private fun renderVector(content: VectorContent, w: Int, h: Int, mode: ColorMode, sub: (Float) -> Unit): Bitmap? {
        val b = LayerDataTransforms.renderScaled(content, w, h, { false }, sub) ?: return null
        if (mode != ColorMode.RGB) {
            try {
                ColorModeOps.constrain(b, Rect(0, 0, w, h), mode)
            } catch (t: Throwable) { b.recycle(); throw t }
        }
        return b
    }

    /**
     * True when objects of [content] (old document px) reach past the old canvas edges into the
     * part of the new canvas ([newW] x [newH], the old one placed at [ox], [oy]) that was not
     * on the old canvas: the cache never held those pixels.
     */
    internal fun exposesObjects(content: VectorContent, oldW: Int, oldH: Int, newW: Int, newH: Int, ox: Int, oy: Int): Boolean {
        val u = ObjectIndex.of(content).unionBounds()
        if (u.isEmpty) return false
        u.offset(ox.toFloat(), oy.toFloat())
        if (!u.intersect(0f, 0f, newW.toFloat(), newH.toFloat())) return false
        val old = RectF(ox.toFloat(), oy.toFloat(), (ox + oldW).toFloat(), (oy + oldH).toFloat())
        return !old.contains(u)
    }

    private fun copyPaint() = Paint().apply {
        isFilterBitmap = false
        isAntiAlias = false
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
    }

    /** [src] drawn at ([dx], [dy]) on a new [w] x [h] bitmap pre-filled with [background]. */
    private fun shifted(src: Bitmap, w: Int, h: Int, dx: Int, dy: Int, background: Int): Bitmap {
        val out = newBitmap(w, h, background)
        try {
            Canvas(out).drawBitmap(src, dx.toFloat(), dy.toFloat(), copyPaint())
        } catch (t: Throwable) { out.recycle(); throw t }
        return out
    }

    /** Exact pixel remap (quarter turns / mirrors) of [src] into a new [w] x [h] bitmap. */
    private fun transformed(src: Bitmap, w: Int, h: Int, g: CanvasGeometry): Bitmap {
        val out = newBitmap(w, h, 0)
        try {
            val m = Matrix()
            m.setValues(floatArrayOf(g.a.toFloat(), g.b.toFloat(), g.tx.toFloat(), g.c.toFloat(), g.d.toFloat(), g.ty.toFloat(), 0f, 0f, 1f))
            Canvas(out).drawBitmap(src, m, copyPaint())
        } catch (t: Throwable) { out.recycle(); throw t }
        return out
    }

    /** Resamples [src] to [w] x [h] (always a new bitmap). */
    internal fun resampleBitmap(src: Bitmap, w: Int, h: Int, resample: Resample, progress: (Float) -> Unit = {}): Bitmap {
        if (w == src.width && h == src.height) return BitmapUtils.copy(src)
        val out = newBitmap(w, h, 0)
        var stage: Bitmap = src
        try {
            if (resample == Resample.NEAREST) {
                Resampler.nearest(BitmapRows(src), w, h, BitmapRows(out), progress)
            } else {
                // Shrink by ~2x steps (an exact 2x2 box average in Skia) until the remaining factor
                // is at most 4, so the final antialiased pass keeps a small, bounded kernel.
                while (stage.width > w * 4 || stage.height > h * 4) {
                    val nw = if (stage.width > w * 4) (stage.width + 1) / 2 else stage.width
                    val nh = if (stage.height > h * 4) (stage.height + 1) / 2 else stage.height
                    val half = skiaScaled(stage, nw, nh)
                    if (stage !== src) stage.recycle()
                    stage = half
                    progress(0f)
                }
                val kernel = if (resample == Resample.BILINEAR) ResampleKernel.TRIANGLE else ResampleKernel.CATMULL_ROM
                Resampler.resample(BitmapRows(stage), w, h, kernel, BitmapRows(out), progress)
            }
        } catch (t: Throwable) {
            out.recycle()
            throw t
        } finally {
            if (stage !== src) stage.recycle()
        }
        return out
    }

    private fun skiaScaled(src: Bitmap, w: Int, h: Int): Bitmap {
        val out = newBitmap(w, h, 0)
        try {
            val c = Canvas(out)
            c.scale(w.toFloat() / src.width, h.toFloat() / src.height)
            c.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) })
        } catch (t: Throwable) { out.recycle(); throw t }
        return out
    }

    /**
     * Converts [src] into [dst] (same size; may be the same bitmap) to [mode], in bounded strips
     * from top to bottom (dithering carries its error down the rows).
     */
    private fun convertInto(src: Bitmap, dst: Bitmap, mode: ColorMode, threshold: Int, dither: Boolean, progress: (Float) -> Unit) {
        val w = src.width
        val h = src.height
        val strip = max(1, min(h, (1 shl 20) / w))
        val buf = IntArray(w * strip)
        val converter = ColorModeConverter(w, mode, threshold, dither)
        var y = 0
        while (y < h) {
            val rows = min(strip, h - y)
            src.getPixels(buf, 0, w, 0, y, w, rows)
            if (dither && mode == ColorMode.MONOCHROME) {
                converter.convertRows(buf, rows)
            } else {
                Parallel.forRange(rows * w, 4096) { from, to -> for (i in from until to) buf[i] = converter.convertPixel(buf[i]) }
            }
            dst.setPixels(buf, 0, w, 0, y, w, rows)
            y += rows
            progress(y / h.toFloat())
        }
    }

    /** Tight bounds of pixels with alpha > 0, or null when fully transparent. */
    fun opaqueBounds(bmp: Bitmap, progress: (Float) -> Unit = {}): Rect? {
        val w = bmp.width
        val h = bmp.height
        val strip = max(1, min(h, (1 shl 20) / w))
        val buf = IntArray(w * strip)
        var minX = w; var maxX = -1; var minY = h; var maxY = -1
        var y0 = 0
        while (y0 < h) {
            val rows = min(strip, h - y0)
            bmp.getPixels(buf, 0, w, 0, y0, w, rows)
            for (r in 0 until rows) {
                val off = r * w
                var x = 0
                while (x < w && buf[off + x] ushr 24 == 0) x++
                if (x == w) continue
                if (x < minX) minX = x
                var xr = w - 1
                while (xr > maxX && buf[off + xr] ushr 24 == 0) xr--
                if (xr > maxX) maxX = xr
                val y = y0 + r
                if (y < minY) minY = y
                maxY = y
            }
            y0 += rows
            progress(y0 / h.toFloat())
        }
        return if (maxX < 0) null else Rect(minX, minY, maxX + 1, maxY + 1)
    }

    /** Row access to an ARGB bitmap for [Resampler]. */
    private class BitmapRows(private val bmp: Bitmap) : RowSource, RowSink {
        override val width: Int get() = bmp.width
        override val height: Int get() = bmp.height
        override fun read(y: Int, rows: Int, out: IntArray) = bmp.getPixels(out, 0, bmp.width, 0, y, bmp.width, rows)
        override fun write(y: Int, rows: Int, pixels: IntArray) = bmp.setPixels(pixels, 0, bmp.width, 0, y, bmp.width, rows)
    }

    // ------------------------------------------------------------------ undo actions

    /**
     * v1.10 (A11): [DocumentBitmapsAction]'s effects with stores held by reference (no pixel is read,
     * copied or materialized). Each apply first takes what the layer holds now as the side it leaves
     * (a store park or unpark rehoused meanwhile is the same pixels), then puts the other side in.
     * [dispose] releases the tiled stores only this step holds (FLAT bitmaps are left to the
     * collector, as v1.9's step did).
     */
    private class StoresAction(
        override val label: String,
        private val entries: List<Entry>,
        private val sizeBefore: Pair<Int, Int>,
        private val sizeAfter: Pair<Int, Int>,
        private val dpiBefore: Float,
        private val dpiAfter: Float,
        private val modeBefore: ColorMode,
        private val modeAfter: ColorMode,
    ) : UndoAction {
        class Entry(
            val layer: Layer,
            var before: PixelStore,
            var beforeMask: PixelStore?,
            var after: PixelStore,
            var afterMask: PixelStore?,
            val dataBefore: LayerData,
            val dataAfter: LayerData,
        )

        /** True while the document shows the "after" side. */
        private var applied = false

        /** [CanvasChangeAction] counts the bytes. */
        override val byteSize: Long get() = 0L

        private fun apply(c: EditorController, before: Boolean) {
            val size = if (before) sizeBefore else sizeAfter
            c.doc.setSize(size.first, size.second)
            c.doc.dpi = if (before) dpiBefore else dpiAfter
            c.doc.colorMode = if (before) modeBefore else modeAfter
            for (e in entries) {
                if (before) {
                    e.after = e.layer.pixels
                    e.afterMask = e.layer.maskPixels
                    e.layer.pixels = e.before
                    e.layer.maskPixels = e.beforeMask
                } else {
                    e.before = e.layer.pixels
                    e.beforeMask = e.layer.maskPixels
                    e.layer.pixels = e.after
                    e.layer.maskPixels = e.afterMask
                }
                e.layer.restoreData(if (before) e.dataBefore else e.dataAfter)
                e.layer.markChanged()
            }
            applied = !before
            c.onDocumentGeometryChanged()
        }

        override fun undo(c: EditorController) = apply(c, true)
        override fun redo(c: EditorController) = apply(c, false)

        override fun dispose() {
            for (e in entries) {
                val content = if (applied) e.before else e.after
                val mask = if (applied) e.beforeMask else e.afterMask
                if (content is TiledPixels && content !== e.layer.pixels) TileOps.releaseStore(content)
                if (mask is TiledPixels && mask !== e.layer.maskPixels) TileOps.releaseStore(mask)
            }
        }
    }

    /** v1.10 (A11): the bytes [p] holds (a FLAT bitmap as v1.9's `byteCount`; a tiled store's resident tiles). */
    private fun bytesOf(p: PixelStore?): Long = when (p) {
        is FlatPixels -> p.width.toLong() * p.height * 4
        is TiledPixels -> TileOps.residentBytes(p)
        else -> 0L
    }

    /**
     * Wraps the [StoresAction] (v1.9: the [DocumentBitmapsAction]):
     *  - the active tool is deactivated before and reactivated after the swap, so it never keeps
     *    buffers or bitmap references of the old geometry (undo/redo guarantee no pending work);
     *  - the ruler and grid move with the artwork ([geometry] / its inverse); the exact settings
     *    are remembered so an undo isn't affected by clamping;
     *  - the selection from before the change comes back on undo, and a selection survives changes
     *    that keep the geometry (color mode), where the document bitmaps action would drop it.
     *
     * [bytesBefore] / [bytesAfter] are the bitmaps only this action holds while it is applied /
     * undone (shared masks are not counted).
     */
    private class CanvasChangeAction(
        private val inner: StoresAction,
        private val geometry: CanvasGeometry,
        private val oldWidth: Int,
        private val oldHeight: Int,
        private val newWidth: Int,
        private val newHeight: Int,
        private val selectionBefore: Selection?,
        private val bytesBefore: Long,
        private val bytesAfter: Long,
        /** v1.7 (item 14): the saved selections before and after (the same list when unchanged). */
        private val savedBefore: List<SavedSelection> = emptyList(),
        private val savedAfter: List<SavedSelection> = savedBefore,
    ) : UndoAction {
        override val label: String get() = inner.label

        private var applied = false

        override val byteSize: Long
            get() = if (applied) bytesBefore + (selectionBefore?.mask?.byteCount ?: 0) + savedBytes(savedBefore) else bytesAfter + savedBytes(savedAfter)

        /** The packed bytes only [list] holds (entries shared by both lists count for neither). */
        private fun savedBytes(list: List<SavedSelection>): Long =
            if (savedBefore === savedAfter) 0L else list.filter { e -> (if (list === savedBefore) savedAfter else savedBefore).none { it === e } }.sumOf { it.bytes }

        private val keepsGeometry = geometry.isIdentity && oldWidth == newWidth && oldHeight == newHeight

        private var rulerBefore: RulerSettings? = null
        private var rulerAfter: RulerSettings? = null
        private var gridBefore: GridSettings? = null
        private var gridAfter: GridSettings? = null

        override fun redo(c: EditorController) {
            val tool = c.currentTool
            tool.onDeactivate()
            val ruler = c.ruler
            val grid = c.grid
            val sel = c.selection
            inner.redo(c)
            applied = true
            val r = rulerAfter?.takeIf { ruler == rulerBefore } ?: geometry.mapRuler(ruler, newWidth, newHeight)
            val g = gridAfter?.takeIf { grid == gridBefore } ?: geometry.mapGrid(grid)
            rulerBefore = ruler; rulerAfter = r
            gridBefore = grid; gridAfter = g
            setGuides(c, r, g)
            if (keepsGeometry && sel != null) c.setSelection(sel, recordUndo = false)
            setSaved(c, savedAfter)
            c.currentTool.onActivate()
        }

        override fun undo(c: EditorController) {
            val tool = c.currentTool
            tool.onDeactivate()
            val ruler = c.ruler
            val grid = c.grid
            inner.undo(c)
            applied = false
            val inverse = geometry.inverse()
            val r = rulerBefore?.takeIf { ruler == rulerAfter } ?: inverse.mapRuler(ruler, oldWidth, oldHeight)
            val g = gridBefore?.takeIf { grid == gridAfter } ?: inverse.mapGrid(grid)
            setGuides(c, r, g)
            c.setSelection(selectionBefore, recordUndo = false)
            setSaved(c, savedBefore)
            c.currentTool.onActivate()
        }

        private fun setSaved(c: EditorController, list: List<SavedSelection>) {
            if (c.doc.savedSelections === list) return
            c.doc.savedSelections = list
            c.notifyLayersChanged()
        }

        private fun setGuides(c: EditorController, r: RulerSettings, g: GridSettings) {
            c.updateRuler(r)
            c.updateGrid(g)
            // onDocumentGeometryChanged() clamps the controller's ruler without storing it in the
            // document; keep the saved settings identical to what's shown.
            c.doc.ruler = c.ruler
            c.doc.grid = c.grid
        }

        override fun dispose() = inner.dispose()
    }

    /** Resolution / color-mode change without pixel changes. */
    private class MetadataAction(
        override val label: String,
        private val dpiBefore: Float,
        private val dpiAfter: Float,
        private val modeBefore: ColorMode,
        private val modeAfter: ColorMode,
    ) : UndoAction {
        override val byteSize: Long = 0L
        private fun set(c: EditorController, dpi: Float, mode: ColorMode) {
            c.doc.dpi = dpi
            c.doc.colorMode = mode
            c.notifyLayersChanged()
            c.invalidateOverlay()
        }
        override fun undo(c: EditorController) = set(c, dpiBefore, modeBefore)
        override fun redo(c: EditorController) = set(c, dpiAfter, modeAfter)
    }
}
