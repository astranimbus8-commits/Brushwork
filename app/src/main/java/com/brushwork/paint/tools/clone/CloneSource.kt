package com.brushwork.paint.tools.clone

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Shader
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.CoverageSource
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.CompositeTarget
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.model.Layer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A whole-pixel clone offset: destination − source, in document px. */
data class CloneOffset(val dx: Int, val dy: Int) {
    /** The point [p] (a destination) samples from. */
    fun sourceOf(p: Vec2): Vec2 = Vec2(p.x - dx, p.y - dy)
}

/**
 * Where the clone stamp copies from, and Photoshop's "Aligned" rule (v1.5 §4.2). Main thread.
 *
 * With Aligned on, the first stroke after the source is set fixes the offset (destination −
 * source) and every later stroke keeps it, so the source travels with the strokes: after each
 * stroke the source point is where that stroke's end sampled (a stroke continuing from there
 * samples it). With Aligned off every stroke starts sampling at the source point again. Offsets
 * are whole pixels, so cloned pixels are copied exactly (no resampling blur).
 */
class CloneAnchor {
    /** The source point (document px), null until one is set. Compose state. */
    var source: Vec2? by mutableStateOf(null)
        private set

    /** The offset kept by Aligned (null until the first stroke after setting the source). */
    var fixed: CloneOffset? = null
        private set

    /**
     * A new source: the next stroke fixes the offset again. A point that is not finite is ignored
     * (a typed or computed NaN must never reach the offset math); coordinates are kept within
     * ±[MAX_COORD] so offsets and source rectangles stay far from integer overflow.
     */
    fun set(p: Vec2) {
        if (!p.x.isFinite() || !p.y.isFinite()) return
        source = Vec2(p.x.coerceIn(-MAX_COORD, MAX_COORD), p.y.coerceIn(-MAX_COORD, MAX_COORD))
        fixed = null
    }

    fun clear() {
        source = null
        fixed = null
    }

    /** Puts back a state saved before a gesture that was cancelled. */
    fun restore(source: Vec2?, fixed: CloneOffset?) {
        this.source = source
        this.fixed = fixed
    }

    /** Aligned was switched: the next stroke starts at the source point and fixes the offset again. */
    fun resetAlignment() {
        fixed = null
    }

    /** The offset of a stroke starting at [start] (null without a source). */
    fun offsetFor(start: Vec2, aligned: Boolean): CloneOffset? {
        val s = source ?: return null
        if (aligned) fixed?.let { return it }
        return CloneOffset((start.x - s.x).roundToInt(), (start.y - s.y).roundToInt())
    }

    /**
     * A stroke painted with [offset] ended at [end] (document px). With Aligned the first one
     * fixes the offset and the source moves along to what [end] sampled.
     */
    fun strokeCompleted(offset: CloneOffset, aligned: Boolean, end: Vec2) {
        if (!aligned || source == null) return
        val kept = fixed ?: offset
        val s = kept.sourceOf(end)
        if (s.x.isFinite() && s.y.isFinite()) source = Vec2(s.x.coerceIn(-MAX_COORD, MAX_COORD), s.y.coerceIn(-MAX_COORD, MAX_COORD))
        fixed = kept
    }

    companion object {
        /** The source stays within ±this many document px (far beyond any canvas, far below Int overflow). */
        const val MAX_COORD = 1_000_000f
    }
}

/**
 * The clone stamp's [CoverageSource] (v1.5 §4.2): the brush engine paints its coverage with
 * [shader], which shows the sampled pixels, [CloneOffset] away from the destination, through
 * every coverage path, so the live stroke and its commit look the same.
 *
 * - **This layer** samples the layer's bitmap (or its mask, when the mask is edited) directly:
 *   it does not change until the stroke commits. Before the commit, [prepareCommit] copies the
 *   source region when the commit could otherwise read pixels it has already changed (V13).
 * - **All layers** samples a document-sized snapshot of the composite (adjustment layers
 *   included), filled lazily per [TILE] px tile as the stroke reaches new source areas, and
 *   refilled from scratch at every stroke (the previous stroke changed the document). It is
 *   allocated on first use, within [snapshotBudgetBytes], and freed by [releaseSnapshot].
 *
 * A source outside the document copies nothing: regions whose source leaves the document are
 * sampled through a copy that is transparent there. Main thread only.
 */
class CloneSource(private val controller: EditorController) : CoverageSource {
    enum class Sample { THIS_LAYER, ALL_LAYERS }

    /** What the current (or last) stroke samples. */
    var sample: Sample = Sample.THIS_LAYER
        private set

    /** True between [beginStroke] and [endStroke]. */
    var isActive: Boolean = false
        private set

    /** The All-layers snapshot may not be larger than this (falls back to This layer). */
    internal var snapshotBudgetBytes: Long = max(48L shl 20, Runtime.getRuntime().maxMemory() / 4)

    /** Tiles composited into the snapshot so far (tests, diagnostics). */
    internal var tilesRendered: Int = 0
        private set

    /** Copies made because a source region left the document or overlapped the commit (tests). */
    internal var regionCopies: Int = 0
        private set

    private val doc get() = controller.doc
    private var backing: Bitmap? = null
    private var dx = 0
    private var dy = 0
    private var direct: Shader = idleShader()
    private var current: Shader = direct

    override val shader: Shader get() = current

    private var snapshot: Bitmap? = null
    private var snapshotCanvas: Canvas? = null
    private var snapshotTarget: CompositeTarget? = null

    /**
     * Composites the snapshot tiles. Its own instance, never the controller's: tiles are filled
     * while the display compositor is in the middle of drawing (from the stroke's render
     * override), and a compositor keeps per-instance scratch (adjustment layers) that a nested
     * call must not share. No overrides: the stroke in progress is not part of what it samples.
     */
    private val snapshotCompositor = Compositor(controller.doc) { null }
    private var filled = BooleanArray(0)
    /** Snapshot tiles the stroke's path can sample ([notePath]); the commit fills only those. */
    private var needed = BooleanArray(0)
    private var neededAny = false
    private var cols = 0
    private var rows = 0

    private var scratch: Bitmap? = null
    private var scratchCanvas: Canvas? = null
    private var commitCopy: Bitmap? = null

    private val copyPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
    private val src = Rect()
    private val part = Rect()
    private val dst = Rect()
    private val tile = Rect()

    /**
     * Sets up a stroke on [layer] ([target]: its pixels or its mask) that paints with [offset].
     * Returns what it samples: All layers falls back to This layer when the mask is edited (a
     * mask clones its own values) or when the snapshot can't be allocated.
     */
    fun beginStroke(layer: Layer, target: EditTarget, wanted: Sample, offset: CloneOffset): Sample {
        endStroke()
        dx = offset.dx
        dy = offset.dy
        val maskTarget = target == EditTarget.MASK
        var mode = if (maskTarget) Sample.THIS_LAYER else wanted
        var bmp: Bitmap? = null
        if (mode == Sample.ALL_LAYERS) {
            bmp = ensureSnapshot()
            if (bmp == null) mode = Sample.THIS_LAYER else filled.fill(false)
        }
        needed.fill(false)
        neededAny = false
        if (bmp == null) bmp = (if (maskTarget) layer.mask else null) ?: layer.bitmap
        backing = bmp
        direct = shaderOf(bmp, dx, dy)
        current = direct
        sample = mode
        isActive = true
        return mode
    }

    /**
     * The stroke went from ([ax], [ay]) to ([bx], [by]) (document px, destination) with dabs
     * reaching [radius] around it. All layers: the commit then composites only the snapshot
     * tiles this path can sample instead of every tile of the stroke's bounding box (a long
     * diagonal stroke would otherwise composite most of the document at once).
     */
    fun notePath(ax: Float, ay: Float, bx: Float, by: Float, radius: Float) {
        if (!isActive || sample != Sample.ALL_LAYERS || needed.isEmpty()) return
        neededAny = true
        val len = hypot(bx - ax, by - ay)
        val step = TILE / 4f
        val n = max(1, ceil(len / step).toInt())
        // Samples are at most step / 2 from any point of the segment, and the brush's smoothed
        // path (quadratic curves between the midpoints of input segments) strays from the input
        // polyline by less than a quarter of a segment.
        val r = radius + step / 2f + max(PATH_MARGIN, len * 0.5f)
        for (i in 0..n) {
            val t = i.toFloat() / n
            markNeeded(ax + (bx - ax) * t - dx, ay + (by - ay) * t - dy, r)
        }
    }

    /** Marks the snapshot tiles that come within [r] of ([cx], [cy]) (a disc, not its bounding square). */
    private fun markNeeded(cx: Float, cy: Float, r: Float) {
        val l = max(0, floor(cx - r).toInt())
        val t = max(0, floor(cy - r).toInt())
        val rr = min(doc.width, ceil(cx + r).toInt())
        val b = min(doc.height, ceil(cy + r).toInt())
        if (rr <= l || b <= t) return
        val r2 = r * r
        for (row in t / TILE..(b - 1) / TILE) for (col in l / TILE..(rr - 1) / TILE) {
            val idx = row * cols + col
            if (idx !in needed.indices || needed[idx]) continue
            // Distance from the point to the tile (0 inside it).
            val ex = max(0f, max(col * TILE - cx, cx - (col + 1) * TILE))
            val ey = max(0f, max(row * TILE - cy, cy - (row + 1) * TILE))
            if (ex * ex + ey * ey <= r2) needed[idx] = true
        }
    }

    /** True when everything [destBounds] (document px) samples lies outside the document: the stroke copies nothing. */
    fun readsOnlyOutside(destBounds: Rect): Boolean {
        src.set(destBounds)
        src.offset(-dx, -dy)
        return !src.intersect(0, 0, doc.width, doc.height)
    }

    override fun prepare(destDocRect: Rect) {
        if (!isActive || destDocRect.isEmpty) return
        src.set(destDocRect)
        src.offset(-dx, -dy)
        if (sample == Sample.ALL_LAYERS) fill(src)
        current = if (insideDocument(src)) direct else regionShader(destDocRect) ?: direct
    }

    override fun prepareCommit(commitRect: Rect) {
        if (!isActive || commitRect.isEmpty) return
        src.set(commitRect)
        src.offset(-dx, -dy)
        if (sample == Sample.ALL_LAYERS) fill(src, onlyNeeded = neededAny)
        // This layer: the commit writes the layer it reads, tile by tile; where the regions meet
        // it must read the pixels as they were before the stroke (a 1 px margin for filtering).
        val overlaps = sample == Sample.THIS_LAYER &&
            Rect.intersects(Rect(src.left - 1, src.top - 1, src.right + 1, src.bottom + 1), commitRect)
        if (!overlaps && insideDocument(src)) {
            current = direct
            return
        }
        val copy = try {
            Bitmap.createBitmap(commitRect.width(), commitRect.height(), Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            controller.toast(LOW_MEMORY_MESSAGE)
            current = direct
            return
        }
        copyRegion(Canvas(copy), commitRect)
        commitCopy?.recycle()
        commitCopy = copy
        current = shaderOf(copy, commitRect.left, commitRect.top)
    }

    override fun endStroke() {
        isActive = false
        backing = null
        direct = idleShader()
        current = direct
        commitCopy?.recycle()
        commitCopy = null
    }

    /** Frees the All-layers snapshot (the tool was put away). */
    fun releaseSnapshot() {
        if (isActive && sample == Sample.ALL_LAYERS) return
        snapshot?.recycle()
        snapshot = null
        snapshotCanvas = null
        snapshotTarget = null
        filled = BooleanArray(0)
        needed = BooleanArray(0)
        neededAny = false
    }

    /** Frees everything (the editor closes). */
    fun release() {
        endStroke()
        releaseSnapshot()
        scratch?.recycle()
        scratch = null
        scratchCanvas = null
    }

    /** True while an All-layers snapshot is allocated (tests). */
    internal val hasSnapshot: Boolean get() = snapshot != null

    // ------------------------------------------------------------------ internals

    private fun insideDocument(r: Rect): Boolean = r.left >= 0 && r.top >= 0 && r.right <= doc.width && r.bottom <= doc.height

    /**
     * A shader for [dest] whose source leaves the document: a copy of the source region,
     * transparent outside the document (null when no copy can be made).
     */
    private fun regionShader(dest: Rect): Shader? {
        val w = dest.width()
        val h = dest.height()
        if (w.toLong() * h > MAX_SCRATCH_PIXELS) return null
        var bmp = scratch
        if (bmp == null || bmp.width < w || bmp.height < h) {
            bmp?.recycle()
            bmp = try {
                Bitmap.createBitmap(max(w, bmp?.width ?: 0), max(h, bmp?.height ?: 0), Bitmap.Config.ARGB_8888)
            } catch (e: OutOfMemoryError) {
                scratch = null
                scratchCanvas = null
                return null
            }
            scratch = bmp
            scratchCanvas = Canvas(bmp)
        }
        // Only the part this region uses (the scratch keeps the size of the largest region).
        val canvas = scratchCanvas!!
        canvas.save()
        canvas.clipRect(0, 0, w, h)
        canvas.drawColor(0, PorterDuff.Mode.CLEAR)
        canvas.restore()
        copyRegion(canvas, dest)
        return shaderOf(bmp, dest.left, dest.top)
    }

    /** Copies the source of [dest] (its pixels [dx], [dy] away, within the document) to (0, 0) of [canvas]. */
    private fun copyRegion(canvas: Canvas, dest: Rect) {
        val from = backing ?: return
        part.set(dest)
        part.offset(-dx, -dy)
        val left = part.left
        val top = part.top
        if (!part.intersect(0, 0, min(doc.width, from.width), min(doc.height, from.height))) return
        dst.set(part.left - left, part.top - top, part.right - left, part.bottom - top)
        canvas.drawBitmap(from, part, dst, copyPaint)
        regionCopies++
    }

    private fun ensureSnapshot(): Bitmap? {
        val w = doc.width
        val h = doc.height
        snapshot?.let { s ->
            if (!s.isRecycled && s.width == w && s.height == h) return s
            releaseSnapshot()
        }
        if (w.toLong() * h * 4L > snapshotBudgetBytes) return null
        val bmp = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            return null
        }
        snapshot = bmp
        snapshotCanvas = Canvas(bmp)
        snapshotTarget = CompositeTarget.identity(bmp)
        cols = (w + TILE - 1) / TILE
        rows = (h + TILE - 1) / TILE
        filled = BooleanArray(cols * rows)
        needed = BooleanArray(cols * rows)
        return bmp
    }

    /**
     * Composites every snapshot tile of [region] (document px) that is not filled yet; with
     * [onlyNeeded], only those [notePath] marked.
     */
    private fun fill(region: Rect, onlyNeeded: Boolean = false) {
        val canvas = snapshotCanvas ?: return
        val target = snapshotTarget ?: return
        val w = doc.width
        val h = doc.height
        val l = max(0, region.left)
        val t = max(0, region.top)
        val r = min(w, region.right)
        val b = min(h, region.bottom)
        if (r <= l || b <= t) return
        for (row in t / TILE..(b - 1) / TILE) {
            for (col in l / TILE..(r - 1) / TILE) {
                val idx = row * cols + col
                if (idx !in filled.indices || filled[idx]) continue
                if (onlyNeeded && !needed[idx]) continue
                filled[idx] = true
                tile.set(col * TILE, row * TILE, min(w, (col + 1) * TILE), min(h, (row + 1) * TILE))
                canvas.save()
                canvas.clipRect(tile)
                canvas.drawColor(0, PorterDuff.Mode.CLEAR)
                snapshotCompositor.drawDocument(canvas, tile, useOverrides = false, target = target)
                canvas.restore()
                tilesRendered++
            }
        }
    }

    companion object {
        /** Snapshot tile size (document px). */
        const val TILE = 256

        /** Larger off-document regions are sampled directly (their edge pixels repeat). */
        private const val MAX_SCRATCH_PIXELS = 4L * 1024 * 1024

        /** Least slack around a noted path (document px). */
        private const val PATH_MARGIN = 32f

        const val LOW_MEMORY_MESSAGE = "Low memory: the clone may repeat what it just painted"

        private fun shaderOf(bitmap: Bitmap, left: Int, top: Int): Shader =
            BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply { setTranslate(left.toFloat(), top.toFloat()) })
            }

        private var idle: Shader? = null

        /** A transparent shader for when no stroke runs ([CoverageSource.shader] is never null). */
        private fun idleShader(): Shader = idle ?: BitmapShader(
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
            Shader.TileMode.CLAMP,
            Shader.TileMode.CLAMP,
        ).also { idle = it }
    }
}
