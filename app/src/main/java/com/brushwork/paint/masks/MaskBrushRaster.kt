package com.brushwork.paint.masks

import com.brushwork.paint.core.Parallel
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Sample points of a mask render (pure Kotlin): sample (col, row) sits at document point
 * (x0 + (col + 0.5) · step, y0 + (row + 0.5) · step). A full-resolution region is
 * `SampleGrid(left, top, 1f, width, height)` (pixel centres); the Masks tool's drag preview uses a
 * coarser [step].
 */
class SampleGrid(val x0: Float, val y0: Float, val step: Float, val cols: Int, val rows: Int) {
    /** Document x of column [col]. */
    fun x(col: Int): Float = x0 + (col + 0.5f) * step

    /** Document y of row [row]. */
    fun y(row: Int): Float = y0 + (row + 0.5f) * step

    val size: Int get() = cols * rows

    /** The rows [r0] until [r1] of this grid as a grid of their own. */
    fun rows(r0: Int, r1: Int): SampleGrid = SampleGrid(x0, y0 + r0 * step, step, cols, r1 - r0)

    /** True when the samples are the pixel centres of a document region (step 1, whole-pixel origin). */
    val isPixelAligned: Boolean get() = step == 1f && x0 == floor(x0) && y0 == floor(y0)

    companion object {
        /** The pixel centres of the document region [left], [top], [width] x [height]. */
        fun pixels(left: Int, top: Int, width: Int, height: Int) = SampleGrid(left.toFloat(), top.toFloat(), 1f, width, height)
    }
}

/**
 * Brush components of editable masks (§4.3b), rasterized in pure Kotlin. A stroke is a row of
 * soft discs ("dabs") along its points, [SPACING] × size apart. Each dab of a stroke with flow
 * `f` adds `c += (1 − c) · a · profile` (an erase stroke: `c *= 1 − a · profile`), where the
 * per-dab strength `a` is chosen so that one pass of a stroke reaches about `f` at its centre
 * line, and `profile` is 1 inside `hardness · radius` and falls off with a smoothstep to 0 at the
 * radius.
 *
 * The canonical arithmetic, shared by every render path (full renders, region renders, the 1 B/px
 * coverage cache and its incremental updates): per sample, strokes in order; within a stroke the
 * value is a Float updated dab by dab in order, starting from the coverage byte / 255; at the end
 * of each stroke it is rounded back to a byte. Any region of any grid therefore gets exactly the
 * bytes a full render gets there.
 */
object MaskBrushRaster {
    /** Dab spacing, as a fraction of the brush size. */
    const val SPACING = 0.1f

    /** Smallest soft edge of a dab (px), so hard dabs are still anti-aliased. */
    private const val MIN_RAMP = 0.75f

    /** Below this many samples a raster stays on the calling thread. */
    private const val PARALLEL_MIN = 65_536

    /** The dabs of one stroke: centres, radius, soft-edge start and per-dab strength. */
    class Dabs(
        val x: FloatArray,
        val y: FloatArray,
        val count: Int,
        val radius: Float,
        val inner: Float,
        val strength: Float,
        val erase: Boolean,
    ) {
        /** Document bounds of every dab (left, top, right, bottom); empty when there are none. */
        val left: Float
        val top: Float
        val right: Float
        val bottom: Float

        init {
            var l = Float.POSITIVE_INFINITY; var t = Float.POSITIVE_INFINITY
            var r = Float.NEGATIVE_INFINITY; var b = Float.NEGATIVE_INFINITY
            for (i in 0 until count) {
                l = min(l, x[i] - radius); r = max(r, x[i] + radius)
                t = min(t, y[i] - radius); b = max(b, y[i] + radius)
            }
            left = l; top = t; right = r; bottom = b
        }

        val isEmpty: Boolean get() = count == 0 || strength <= 0f || radius <= 0f
    }

    /** The dabs of [s]: one at the first point, then every spacing along the polyline, plus the end. */
    fun dabsOf(s: MaskStroke): Dabs {
        val size = if (s.size.isFinite()) s.size.coerceIn(1f, 5000f) else 1f
        val radius = size / 2f
        val hard = if (s.hardness.isFinite()) s.hardness.coerceIn(0f, 1f) else 0.5f
        val inner = min(hard * radius, radius - MIN_RAMP).coerceAtLeast(0f)
        val spacing = max(1f, SPACING * size)
        val flow = if (s.flow.isFinite()) s.flow.coerceIn(0f, 1f) else 1f
        // Dabs over one point of the centre line weigh about (radius + inner) / spacing in total.
        val passes = max(1f, (radius + inner) / spacing)
        val strength = if (flow >= 1f) 1f else (1.0 - (1.0 - flow).pow(1.0 / passes)).toFloat()
        val pts = s.points
        // Unboxed growing arrays (long strokes have thousands of dabs).
        var xs = FloatArray(max(16, pts.size * 2))
        var ys = FloatArray(xs.size)
        var n = 0
        fun add(x: Float, y: Float) {
            if (n == xs.size) { xs = xs.copyOf(n * 2); ys = ys.copyOf(n * 2) }
            xs[n] = x; ys[n] = y; n++
        }
        var px = Float.NaN; var py = Float.NaN
        var carry = 0f
        for (i in 0 until pts.size) {
            val x = pts.x[i]; val y = pts.y[i]
            if (!x.isFinite() || !y.isFinite()) continue
            if (px.isNaN()) {
                add(x, y)
                px = x; py = y
                continue
            }
            val dx = x - px; val dy = y - py
            val len = sqrt(dx * dx + dy * dy)
            if (len <= 0f) continue
            // The next dab lies (spacing - carry) further along.
            var d = spacing - carry
            while (d <= len) {
                val t = d / len
                add(px + dx * t, py + dy * t)
                d += spacing
            }
            carry = len - (d - spacing)
            px = x; py = y
        }
        // The end point gets a dab of its own unless the last one is close to it.
        if (!px.isNaN() && n > 0 && carry > spacing * 0.25f) add(px, py)
        return Dabs(xs.copyOf(n), ys.copyOf(n), n, radius, inner, strength, s.erase)
    }

    /**
     * Document bounds (left, top, right, bottom) a stroke can change, or null when it is empty:
     * its points' box grown by the brush radius (every dab lies on the polyline). Cheap: no dabs
     * are made (the compositor and the previews ask for it often).
     */
    fun bounds(s: MaskStroke): FloatArray? {
        val flow = if (s.flow.isFinite()) s.flow.coerceIn(0f, 1f) else 1f
        if (flow <= 0f) return null
        val size = if (s.size.isFinite()) s.size.coerceIn(1f, 5000f) else 1f
        val r = size / 2f
        val p = s.points
        var l = Float.POSITIVE_INFINITY; var t = Float.POSITIVE_INFINITY
        var rr = Float.NEGATIVE_INFINITY; var b = Float.NEGATIVE_INFINITY
        for (i in 0 until p.size) {
            val x = p.x[i]; val y = p.y[i]
            if (!x.isFinite() || !y.isFinite()) continue
            l = min(l, x); rr = max(rr, x); t = min(t, y); b = max(b, y)
        }
        if (l > rr) return null
        return floatArrayOf(l - r, t - r, rr + r, b + r)
    }

    /**
     * Coverage bytes of [strokes] on [grid] (row by row, [grid].cols per row), from nothing.
     * Large grids are split over [Parallel] rows.
     */
    fun rasterize(strokes: List<MaskStroke>, grid: SampleGrid, parallel: Boolean = true): ByteArray {
        val out = ByteArray(grid.size)
        apply(strokes.map { dabsOf(it) }, grid, out, parallel)
        return out
    }

    /** Applies the [dabs] of strokes, in order, to [cov] (coverage bytes on [grid]). */
    fun apply(dabs: List<Dabs>, grid: SampleGrid, cov: ByteArray, parallel: Boolean = true) {
        if (grid.cols <= 0 || grid.rows <= 0 || dabs.all { it.isEmpty }) return
        require(cov.size >= grid.size) { "coverage too small" }
        if (!parallel || grid.size < PARALLEL_MIN) {
            applyRows(dabs, grid, cov, 0, grid.rows, FloatArray(grid.cols))
            return
        }
        Parallel.forRows(grid.rows) { r0, r1 -> applyRows(dabs, grid, cov, r0, r1, FloatArray(grid.cols)) }
    }

    /**
     * Rows [r0] until [r1] of [apply]: per stroke, the touched samples are read as floats, every
     * dab is applied in order and the result is rounded back to bytes.
     */
    private fun applyRows(dabs: List<Dabs>, grid: SampleGrid, cov: ByteArray, r0: Int, r1: Int, rowBuf: FloatArray) {
        val step = grid.step
        val cols = grid.cols
        for (d in dabs) {
            if (d.isEmpty) continue
            // The stroke's sample range on this band (one sample wider than needed: the distance
            // test below decides, so every grid takes the same decisions for the same sample).
            val c0 = max(0, firstIndex(d.left, grid.x0, step) - 1)
            val c1 = min(cols - 1, lastIndex(d.right, grid.x0, step) + 1)
            val sr0 = max(r0, firstIndex(d.top, grid.y0, step) - 1)
            val sr1 = min(r1 - 1, lastIndex(d.bottom, grid.y0, step) + 1)
            if (c0 > c1 || sr0 > sr1) continue
            val r = d.radius
            val r2 = r * r
            for (row in sr0..sr1) {
                val py = grid.y(row)
                val base = row * cols
                // Dabs crossing this row, in order: load the row once, apply, store (loading and
                // storing an untouched sample gives back the same byte).
                var loaded = false
                for (k in 0 until d.count) {
                    val dy = py - d.y[k]
                    if (dy * dy >= r2) continue
                    val dc0 = max(c0, firstIndex(d.x[k] - r, grid.x0, step) - 1)
                    val dc1 = min(c1, lastIndex(d.x[k] + r, grid.x0, step) + 1)
                    if (dc0 > dc1) continue
                    if (!loaded) {
                        for (c in c0..c1) rowBuf[c] = (cov[base + c].toInt() and 0xFF) / 255f
                        loaded = true
                    }
                    val dy2 = dy * dy
                    val cx = d.x[k]
                    for (c in dc0..dc1) {
                        val dx = grid.x(c) - cx
                        val dist2 = dx * dx + dy2
                        if (dist2 >= r2) continue
                        val p = profile(sqrt(dist2), d.inner, r)
                        if (p <= 0f) continue
                        val v = rowBuf[c]
                        rowBuf[c] = if (d.erase) v * (1f - d.strength * p) else v + (1f - v) * d.strength * p
                    }
                }
                if (loaded) for (c in c0..c1) cov[base + c] = quantize(rowBuf[c])
            }
        }
    }

    /** 1 inside [inner], a smoothstep fall to 0 at [radius]. */
    fun profile(dist: Float, inner: Float, radius: Float): Float {
        if (dist <= inner) return 1f
        if (dist >= radius) return 0f
        return 1f - MaskSpecRenderer.smoothstep(inner, radius, dist)
    }

    /** The coverage byte of value [v] 0..1. */
    fun quantize(v: Float): Byte = (v * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()

    /** First sample index whose position is >= [pos] (positions are origin + (i + 0.5) · step). */
    private fun firstIndex(pos: Float, origin: Float, step: Float): Int {
        val v = ceil((pos - origin) / step - 0.5f)
        return if (v < -1e9f) Int.MIN_VALUE / 2 else if (v > 1e9f) Int.MAX_VALUE / 2 else v.toInt()
    }

    /** Last sample index whose position is <= [pos]. */
    private fun lastIndex(pos: Float, origin: Float, step: Float): Int {
        val v = floor((pos - origin) / step - 0.5f)
        return if (v < -1e9f) Int.MIN_VALUE / 2 else if (v > 1e9f) Int.MAX_VALUE / 2 else v.toInt()
    }
}

/**
 * Where a mask render takes a brush component's coverage from (the Masks tool's caches): the
 * coverage bytes of [c] on [grid] (row by row, `grid.cols` per row), or null to rasterize it.
 */
fun interface BrushSource {
    fun coverage(c: BrushMask, grid: SampleGrid): ByteArray?
}

/**
 * The 1 B/px full-resolution coverage of ONE brush component (the one being painted in the Masks
 * tool), kept while the tool is active. A new stroke is added incrementally; any other change
 * rebuilds it. Main thread; not for documents above [MAX_PIXELS].
 */
class MaskBrushCache(val width: Int, val height: Int) : BrushSource {
    private var id: Long = Long.MIN_VALUE
    private var strokes: List<MaskStroke> = emptyList()
    private var bytes: ByteArray? = null

    /** The coverage of [c] on the whole document, up to date (null when it can't be cached). */
    fun full(c: BrushMask): ByteArray? {
        val cur = bytes
        val grid = SampleGrid.pixels(0, 0, width, height)
        if (cur != null && c.id == id && c.strokes.size >= strokes.size && strokes.indices.all { c.strokes[it] === strokes[it] }) {
            if (c.strokes.size > strokes.size) {
                MaskBrushRaster.apply(c.strokes.subList(strokes.size, c.strokes.size).map { MaskBrushRaster.dabsOf(it) }, grid, cur)
                strokes = c.strokes.toList()
            }
            return cur
        }
        if (width.toLong() * height > MAX_PIXELS) return null
        val fresh = try {
            cur?.takeIf { it.size == width * height }?.also { it.fill(0) } ?: ByteArray(width * height)
        } catch (e: OutOfMemoryError) {
            return null
        }
        MaskBrushRaster.apply(c.strokes.map { MaskBrushRaster.dabsOf(it) }, grid, fresh)
        bytes = fresh
        id = c.id
        strokes = c.strokes.toList()
        return fresh
    }

    /** True when the cache holds [c] exactly (no work needed to use it). */
    fun holds(c: BrushMask): Boolean =
        bytes != null && c.id == id && c.strokes.size == strokes.size && strokes.indices.all { c.strokes[it] === strokes[it] }

    /** True when the cache holds [c] or its strokes so far ([c] adds strokes: an incremental update). */
    fun serves(c: BrushMask): Boolean =
        bytes != null && c.id == id && c.strokes.size >= strokes.size && strokes.indices.all { c.strokes[it] === strokes[it] }

    /** Coverage of [c] on [grid] when the cache serves it (see [serves]); null otherwise (it isn't rebuilt here). */
    override fun coverage(c: BrushMask, grid: SampleGrid): ByteArray? {
        if (!serves(c) || !grid.isPixelAligned) return null
        val left = grid.x0.toInt(); val top = grid.y0.toInt()
        if (left < 0 || top < 0 || left + grid.cols > width || top + grid.rows > height) return null
        val all = full(c) ?: return null
        if (left == 0 && top == 0 && grid.cols == width && grid.rows == height) return all.copyOf()
        val out = ByteArray(grid.size)
        for (r in 0 until grid.rows) System.arraycopy(all, (top + r) * width + left, out, r * grid.cols, grid.cols)
        return out
    }

    /** Value 0..255 of the cached coverage at document pixel ([x], [y]) for [c], or -1 when not cached. */
    fun sample(c: BrushMask, x: Int, y: Int): Int {
        if (!holds(c) || x < 0 || y < 0 || x >= width || y >= height) return -1
        return bytes!![y * width + x].toInt() and 0xFF
    }

    fun clear() {
        bytes = null
        strokes = emptyList()
        id = Long.MIN_VALUE
    }

    companion object {
        /** Largest document cached (16 MB). */
        const val MAX_PIXELS = 16L shl 20
    }
}
