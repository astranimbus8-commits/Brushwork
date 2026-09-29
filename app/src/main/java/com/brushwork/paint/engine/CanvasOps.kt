package com.brushwork.paint.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import com.brushwork.paint.ColorModeOps
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Parallel
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Resampling method for [CanvasOps.resizeImage]. */
enum class Resample(val label: String, val description: String) {
    NEAREST("Nearest", "Hard pixel edges. Best for pixel art and exact 200%/400% enlargements."),
    BILINEAR("Bilinear", "Smooth linear interpolation; averages pixels when shrinking."),
    HIGH_QUALITY("High quality", "Sharp bicubic interpolation with antialiased, multi-step shrinking."),
}

/**
 * Immutable view of the document taken on the main thread. Background work reads only this
 * (source bitmaps are never mutated), so the live document stays consistent.
 */
class CanvasSnapshot(
    val width: Int,
    val height: Int,
    val dpi: Float,
    val colorMode: ColorMode,
    val layers: List<LayerSnapshot>,
) {
    class LayerSnapshot(val layer: Layer, val bitmap: Bitmap, val mask: Bitmap?, val visible: Boolean)

    /** Number of full-size bitmaps (layers + masks). */
    val bitmapCount: Int get() = layers.size + layers.count { it.mask != null }

    /** True while [doc] still has exactly the layers/bitmaps/size this snapshot was taken from. */
    fun matches(doc: Document): Boolean {
        if (doc.width != width || doc.height != height || doc.layers.size != layers.size) return false
        return layers.indices.all { i ->
            val l = doc.layers[i]
            val s = layers[i]
            l === s.layer && l.bitmap === s.bitmap && l.mask === s.mask
        }
    }

    companion object {
        fun of(doc: Document) = CanvasSnapshot(
            doc.width, doc.height, doc.dpi, doc.colorMode,
            doc.layers.map { LayerSnapshot(it, it.bitmap, it.mask, it.visible) },
        )
    }
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
    class LayerResult(val layer: Layer, val bitmap: Bitmap, val mask: Bitmap?)

    /** Frees the bitmaps this result created (never the snapshot's own bitmaps). */
    fun recycle(snapshot: CanvasSnapshot) = recycleCreated(layers, snapshot)

    internal companion object {
        fun recycleCreated(layers: List<LayerResult>, snapshot: CanvasSnapshot) {
            val source = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Bitmap, Boolean>())
            snapshot.layers.forEach { source += it.bitmap; it.mask?.let { m -> source += m } }
            for (l in layers) {
                if (l.bitmap !in source) l.bitmap.recycle()
                l.mask?.let { if (it !in source) it.recycle() }
            }
        }
    }
}

/** A canvas operation can't run; [message] is shown to the user. */
class CanvasOpException(message: String) : Exception(message)

/**
 * Document-wide operations: resize image, canvas size, trim/crop, rotate/flip canvas, resolution
 * and color mode.
 *
 * The first group of functions are pure: they read a [CanvasSnapshot] and build NEW bitmaps for
 * every layer and mask (safe on a background thread, usable from tests). The `apply*` functions
 * are the UI entry points: they commit pending tool work, compute on [Dispatchers.Default] under
 * the controller's busy overlay and record a single undo step ([commit]).
 */
object CanvasOps {
    /** Largest supported side, in pixels. */
    const val MAX_SIDE = 10_000

    /** Share of the heap all layer bitmaps may use (same budget as the controller's layer limit). */
    const val MEMORY_FRACTION = 0.55

    // ------------------------------------------------------------------ validation

    /** Bytes needed by [bitmapCount] ARGB bitmaps of [width] x [height]. */
    fun estimateBytes(bitmapCount: Int, width: Int, height: Int): Long = bitmapCount.toLong() * width * height * 4

    fun memoryBudget(): Long = (Runtime.getRuntime().maxMemory() * MEMORY_FRACTION).toLong()

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

    // ------------------------------------------------------------------ pure operations

    /** Resamples every layer and mask to [newWidth] x [newHeight]; the document gets [dpi]. */
    fun resizeImage(
        snap: CanvasSnapshot,
        newWidth: Int,
        newHeight: Int,
        resample: Resample,
        dpi: Float = snap.dpi,
        progress: (Float) -> Unit = {},
    ): CanvasResult {
        checkSize(snap, newWidth, newHeight)
        val layers = mapLayers(snap, progress) { src, _ -> resampleBitmap(src, newWidth, newHeight, resample) }
        val geometry = CanvasGeometry.scale(newWidth.toDouble() / snap.width, newHeight.toDouble() / snap.height)
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
        val bottomBitmap = snap.layers.first().bitmap
        val layers = mapLayers(snap, progress) { src, isMask ->
            val background = when {
                isMask -> MASK_WHITE
                fill != null && src === bottomBitmap -> fill
                else -> 0
            }
            shifted(src, newWidth, newHeight, offsetX, offsetY, background)
        }
        return CanvasResult(newWidth, newHeight, snap.dpi, snap.colorMode, layers, CanvasGeometry.translate(offsetX.toDouble(), offsetY.toDouble()))
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
    fun opaqueBounds(snap: CanvasSnapshot): Rect {
        val out = Rect()
        for (l in snap.layers) {
            if (!l.visible) continue
            opaqueBounds(l.bitmap)?.let { out.union(it) }
        }
        return out
    }

    /** Crops to [opaqueBounds]. Throws [CanvasOpException] when there's nothing to trim. */
    fun trimTransparent(snap: CanvasSnapshot, progress: (Float) -> Unit = {}): CanvasResult {
        val bounds = opaqueBounds(snap)
        if (bounds.isEmpty) throw CanvasOpException("Nothing to trim: the visible layers are empty.")
        if (bounds.width() == snap.width && bounds.height() == snap.height) throw CanvasOpException("There are no transparent edges to trim.")
        return cropTo(snap, bounds, progress)
    }

    /** Rotates the whole canvas (all layers and masks). */
    fun rotate(snap: CanvasSnapshot, rotation: CanvasRotation, progress: (Float) -> Unit = {}): CanvasResult {
        val swap = rotation.quarterTurnsCw % 2 == 1
        val w = if (swap) snap.height else snap.width
        val h = if (swap) snap.width else snap.height
        val geometry = CanvasGeometry.rotate(rotation, snap.width, snap.height)
        val layers = mapLayers(snap, progress) { src, _ -> transformed(src, w, h, geometry) }
        return CanvasResult(w, h, snap.dpi, snap.colorMode, layers, geometry)
    }

    /** Mirrors the whole canvas (all layers and masks). */
    fun flip(snap: CanvasSnapshot, horizontal: Boolean, progress: (Float) -> Unit = {}): CanvasResult {
        val geometry = CanvasGeometry.flip(horizontal, snap.width, snap.height)
        val layers = mapLayers(snap, progress) { src, _ -> transformed(src, snap.width, snap.height, geometry) }
        return CanvasResult(snap.width, snap.height, snap.dpi, snap.colorMode, layers, geometry)
    }

    /** Changes only the resolution (print size); pixels are untouched. */
    fun setDpi(snap: CanvasSnapshot, dpi: Float): CanvasResult {
        if (!(dpi >= 1f && dpi <= 10_000f)) throw CanvasOpException("Resolution must be between 1 and 10000 dpi.")
        return CanvasResult(snap.width, snap.height, dpi, snap.colorMode, emptyList(), CanvasGeometry.IDENTITY)
    }

    /**
     * Converts every layer's pixels (masks are unchanged) to [mode] and sets the document mode.
     * [threshold] (1..255) and [dither] (Floyd–Steinberg) apply to MONOCHROME. Switching to RGB, or
     * from monochrome to grayscale, only changes the mode (the pixels already qualify).
     */
    fun convertColorMode(
        snap: CanvasSnapshot,
        mode: ColorMode,
        threshold: Int = 128,
        dither: Boolean = false,
        progress: (Float) -> Unit = {},
    ): CanvasResult {
        val pixelsQualify = mode == ColorMode.RGB || mode == snap.colorMode ||
            (mode == ColorMode.GRAYSCALE && snap.colorMode == ColorMode.MONOCHROME)
        if (pixelsQualify) return CanvasResult(snap.width, snap.height, snap.dpi, mode, emptyList(), CanvasGeometry.IDENTITY)
        val layers = mapLayers(snap, progress, transformMasks = false) { src, _ -> convertedBitmap(src, mode, threshold, dither) }
        return CanvasResult(snap.width, snap.height, snap.dpi, mode, layers, CanvasGeometry.IDENTITY)
    }

    // ------------------------------------------------------------------ commit (main thread)

    /**
     * Applies [result] (computed from [snap]) to the controller's document as ONE undo step:
     * a [DocumentBitmapsAction] (wrapped so the ruler/grid follow the artwork and a selection
     * survives same-geometry changes), or a small metadata action when no bitmaps changed.
     */
    fun commit(c: EditorController, label: String, snap: CanvasSnapshot, result: CanvasResult): UndoAction {
        val action: UndoAction = if (result.layers.isEmpty()) {
            require(result.width == snap.width && result.height == snap.height) { "Size changes need new bitmaps" }
            MetadataAction(label, snap.dpi, result.dpi, snap.colorMode, result.colorMode)
        } else {
            require(result.layers.size == snap.layers.size) { "Every layer needs new bitmaps" }
            val entries = result.layers.mapIndexed { i, r ->
                val s = snap.layers[i]
                require(s.layer === r.layer)
                DocumentBitmapsAction.Entry(r.layer, s.bitmap, s.mask, r.bitmap, r.mask)
            }
            val inner = DocumentBitmapsAction(
                label, entries,
                snap.width to snap.height, result.width to result.height,
                snap.dpi, result.dpi, snap.colorMode, result.colorMode,
            )
            CanvasChangeAction(inner, result.geometry, snap.width, snap.height, result.width, result.height)
        }
        action.redo(c)
        c.pushUndo(action)
        return action
    }

    /**
     * Runs [compute] on a background thread under the busy overlay and commits it on the main
     * thread. Pending tool work is committed first. Returns false if another operation is running.
     */
    fun run(c: EditorController, label: String, compute: (CanvasSnapshot, (Float) -> Unit) -> CanvasResult): Boolean {
        if (c.busyMessage != null) return false
        c.filterSession?.cancel()
        c.currentTool.onDeactivate()
        val snap = CanvasSnapshot.of(c.doc)
        c.runBusy(label) {
            try {
                var lastPosted = -1
                val result = withContext(Dispatchers.Default) {
                    compute(snap) { p ->
                        val pct = (p * 100).toInt()
                        if (pct != lastPosted) {
                            lastPosted = pct
                            c.scope.launch { if (c.busyMessage == label) c.busyProgress = p.coerceIn(0f, 1f) }
                        }
                    }
                }
                if (!snap.matches(c.doc)) {
                    result.recycle(snap)
                    c.toast("The drawing changed while \"$label\" was running; nothing was applied.")
                } else {
                    commit(c, label, snap, result)
                }
            } catch (e: CanvasOpException) {
                c.toast(e.message ?: label)
            } catch (e: OutOfMemoryError) {
                c.toast("Not enough memory for \"$label\". Try a smaller size or fewer layers.")
            } finally {
                c.currentTool.onActivate()
            }
        }
        return true
    }

    // ------------------------------------------------------------------ UI entry points

    /** Resize image (resamples pixels). Returns false (with a message) if the size isn't allowed. */
    fun applyResizeImage(c: EditorController, width: Int, height: Int, dpi: Float, resample: Resample): Boolean {
        val doc = c.doc
        if (width == doc.width && height == doc.height) {
            return if (dpi != doc.dpi) applyDpi(c, dpi) else false
        }
        validateSize(width, height, CanvasSnapshot.of(doc).bitmapCount)?.let { c.toast(it); return false }
        return run(c, "Resize image") { s, p -> resizeImage(s, width, height, resample, dpi, p) }
    }

    /** Canvas size with a 3x3 anchor ([anchorX], [anchorY] in 0..2). */
    fun applyResizeCanvas(c: EditorController, width: Int, height: Int, anchorX: Int, anchorY: Int, fillBottom: Int?): Boolean {
        val doc = c.doc
        if (width == doc.width && height == doc.height) return false
        validateSize(width, height, CanvasSnapshot.of(doc).bitmapCount)?.let { c.toast(it); return false }
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

    fun applyDpi(c: EditorController, dpi: Float): Boolean {
        if (dpi == c.doc.dpi) return false
        return run(c, "Resolution") { s, _ -> setDpi(s, dpi) }
    }

    fun applyColorMode(c: EditorController, mode: ColorMode, threshold: Int, dither: Boolean): Boolean {
        if (mode == c.doc.colorMode) return false
        return run(c, "Color mode: ${mode.label}") { s, p -> convertColorMode(s, mode, threshold, dither, p) }
    }

    // ------------------------------------------------------------------ bitmap helpers

    private const val MASK_WHITE = -1

    private fun checkSize(snap: CanvasSnapshot, w: Int, h: Int) {
        if (w < 1 || h < 1 || w > MAX_SIDE || h > MAX_SIDE) throw CanvasOpException("Each side must be between 1 and $MAX_SIDE px.")
        if (snap.layers.isEmpty()) throw CanvasOpException("The drawing has no layers.")
    }

    /**
     * Builds new bitmaps for every layer (and its mask when [transformMasks]) with [op]
     * (`op(source, isMask)`); on failure frees what was already created and rethrows.
     */
    private inline fun mapLayers(
        snap: CanvasSnapshot,
        progress: (Float) -> Unit,
        transformMasks: Boolean = true,
        op: (Bitmap, Boolean) -> Bitmap,
    ): List<CanvasResult.LayerResult> {
        val out = ArrayList<CanvasResult.LayerResult>(snap.layers.size)
        val total = snap.layers.size + (if (transformMasks) snap.layers.count { it.mask != null } else 0)
        var done = 0
        var pendingBitmap: Bitmap? = null
        try {
            progress(0f)
            for (l in snap.layers) {
                val bmp = op(l.bitmap, false)
                pendingBitmap = bmp
                progress(++done / total.toFloat())
                val mask = if (transformMasks && l.mask != null) op(l.mask, true).also { progress(++done / total.toFloat()) } else l.mask
                out += CanvasResult.LayerResult(l.layer, bmp, mask)
                pendingBitmap = null
            }
        } catch (t: Throwable) {
            pendingBitmap?.recycle()
            CanvasResult.recycleCreated(out, snap)
            throw t
        }
        return out
    }

    private fun newBitmap(w: Int, h: Int, fill: Int): Bitmap =
        BitmapUtils.createLayerBitmap(w, h).also { if (fill != 0) it.eraseColor(fill) }

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
    internal fun resampleBitmap(src: Bitmap, w: Int, h: Int, resample: Resample): Bitmap {
        if (w == src.width && h == src.height) return BitmapUtils.copy(src)
        val out = newBitmap(w, h, 0)
        var stage: Bitmap = src
        try {
            if (resample == Resample.NEAREST) {
                Resampler.nearest(BitmapRows(src), w, h, BitmapRows(out))
            } else {
                // Shrink by ~2x steps (an exact 2x2 box average in Skia) until the remaining factor
                // is at most 4, so the final antialiased pass keeps a small, bounded kernel.
                while (stage.width > w * 4 || stage.height > h * 4) {
                    val nw = if (stage.width > w * 4) (stage.width + 1) / 2 else stage.width
                    val nh = if (stage.height > h * 4) (stage.height + 1) / 2 else stage.height
                    val half = skiaScaled(stage, nw, nh)
                    if (stage !== src) stage.recycle()
                    stage = half
                }
                val kernel = if (resample == Resample.BILINEAR) ResampleKernel.TRIANGLE else ResampleKernel.CATMULL_ROM
                Resampler.resample(BitmapRows(stage), w, h, kernel, BitmapRows(out))
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

    private fun convertedBitmap(src: Bitmap, mode: ColorMode, threshold: Int, dither: Boolean): Bitmap {
        val w = src.width
        val h = src.height
        val out = newBitmap(w, h, 0)
        try {
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
                out.setPixels(buf, 0, w, 0, y, w, rows)
                y += rows
            }
        } catch (t: Throwable) { out.recycle(); throw t }
        return out
    }

    /** Tight bounds of pixels with alpha > 0, or null when fully transparent. */
    fun opaqueBounds(bmp: Bitmap): Rect? {
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
     * Wraps the [DocumentBitmapsAction] so that, on redo/undo, the ruler and grid are carried
     * along with the artwork ([geometry] / its inverse), and a selection survives changes that
     * keep the geometry (color mode), where the document bitmaps action would drop it.
     */
    private class CanvasChangeAction(
        private val inner: DocumentBitmapsAction,
        private val geometry: CanvasGeometry,
        private val oldWidth: Int,
        private val oldHeight: Int,
        private val newWidth: Int,
        private val newHeight: Int,
    ) : UndoAction {
        override val label: String get() = inner.label
        override val byteSize: Long get() = inner.byteSize

        private fun swap(c: EditorController, forward: Boolean) {
            val ruler = c.ruler
            val grid = c.grid
            val sel = c.selection
            if (forward) inner.redo(c) else inner.undo(c)
            if (!geometry.isIdentity) {
                val g = if (forward) geometry else geometry.inverse()
                val w = if (forward) newWidth else oldWidth
                val h = if (forward) newHeight else oldHeight
                c.updateRuler(g.mapRuler(ruler, w, h))
                c.updateGrid(g.mapGrid(grid))
            } else if (sel != null && oldWidth == newWidth && oldHeight == newHeight) {
                c.setSelection(sel, recordUndo = false)
            }
        }

        override fun redo(c: EditorController) = swap(c, true)
        override fun undo(c: EditorController) = swap(c, false)
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
