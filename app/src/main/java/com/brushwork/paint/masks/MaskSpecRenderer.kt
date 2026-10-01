package com.brushwork.paint.masks

import com.brushwork.paint.core.Parallel
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Renders editable masks ([MaskSpec], v1.5 §4.3; owned by A5) in pure Kotlin: per sample point,
 * every visible component's raw value, combined in order (§4.3b):
 * - start: `m = startFull ? 1 : 0`;
 * - per visible component, `r = invert ? 1 − raw : raw`, amount `a`: ADD `m = max(m, a·r)`,
 *   SUBTRACT `m = m·(1 − a·r)`, INTERSECT `m = m·(1 − a + a·r)`;
 * - final `(spec.invert ? 1 − m : m) · density`, written as opaque gray ARGB (white = visible).
 *
 * Raw values: linear `1 − smoothstep(0, 1, t)` with `t` the projection on p0→p1 over |p1 − p0|;
 * radial `1 − smoothstep(1 − feather, 1, d)` with `d` the rotated, normalized elliptical
 * distance; brush: the stroke coverage of [MaskBrushRaster] (soft discs, accumulated per stroke),
 * taken from a [BrushSource] (the Masks tool's caches) or rasterized for the region.
 *
 * Every path evaluates the same arithmetic per sample, so a region render equals the full render's
 * pixels there.
 */
object MaskSpecRenderer {

    /** Exact smoothstep; with [e0] == [e1] a step at [e0] (0 below, 1 from it on). */
    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        if (e1 == e0) return if (x < e0) 0f else 1f
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Raw value of [c] at document point ([x], [y]): 1 on the p0 side, 0 from the line through p1 on. */
    fun linearRaw(c: LinearMask, x: Float, y: Float): Float {
        val dx = c.x1 - c.x0
        val dy = c.y1 - c.y0
        val len2 = dx * dx + dy * dy
        // No direction: there is no ramp, the whole canvas is on the 100 % side.
        if (!(len2 > 0f)) return 1f
        val t = ((x - c.x0) * dx + (y - c.y0) * dy) / len2
        return 1f - smoothstep(0f, 1f, t)
    }

    /** Raw value of [c] at document point ([x], [y]): 1 inside the feathered ellipse, 0 outside the ellipse. */
    fun radialRaw(c: RadialMask, x: Float, y: Float): Float {
        if (!(c.rx > 0f) || !(c.ry > 0f)) return 0f
        val rad = Math.toRadians(c.rotationDeg.toDouble())
        val cs = cos(rad).toFloat()
        val sn = sin(rad).toFloat()
        return radialAt(c, cs, sn, x, y)
    }

    private fun radialAt(c: RadialMask, cs: Float, sn: Float, x: Float, y: Float): Float {
        val px = x - c.cx
        val py = y - c.cy
        // Into the ellipse's own axes (rotation clockwise on screen, like shape boxes).
        val lx = cs * px + sn * py
        val ly = -sn * px + cs * py
        val nx = lx / c.rx
        val ny = ly / c.ry
        val d = sqrt(nx * nx + ny * ny)
        val f = if (c.feather.isFinite()) c.feather.coerceIn(0f, 1f) else 0.5f
        return 1f - smoothstep(1f - f, 1f, d)
    }

    /** Raw value of the brush component [c] at document point ([x], [y]) (one sample rasterized). */
    fun brushRaw(c: BrushMask, x: Float, y: Float): Float {
        val cov = MaskBrushRaster.rasterize(c.strokes, SampleGrid(x - 0.5f, y - 0.5f, 1f, 1, 1), parallel = false)
        return (cov[0].toInt() and 0xFF) / 255f
    }

    /** One combination step: [m] with component value [r] (already inverted if the component is) at [amount]. */
    fun combine(m: Float, mode: MaskMode, amount: Float, r: Float): Float {
        val a = if (amount.isFinite()) amount.coerceIn(0f, 1f) else 0f
        return when (mode) {
            MaskMode.ADD -> max(m, a * r)
            MaskMode.SUBTRACT -> m * (1f - a * r)
            MaskMode.INTERSECT -> m * (1f - a + a * r)
        }
    }

    /** The final mask value of [spec] once its components gave [m]. */
    fun finish(spec: MaskSpec, m: Float): Float {
        val d = if (spec.density.isFinite()) spec.density.coerceIn(0f, 1f) else 1f
        return ((if (spec.invert) 1f - m else m) * d).coerceIn(0f, 1f)
    }

    /** The mask value 0..1 of [spec] at document point ([x], [y]) (pixel centres are at +0.5). */
    fun valueAt(spec: MaskSpec, x: Float, y: Float): Float {
        var m = if (spec.startFull) 1f else 0f
        for (c in spec.components) {
            if (!c.visible) continue
            val raw = when (c) {
                is LinearMask -> linearRaw(c, x, y)
                is RadialMask -> radialRaw(c, x, y)
                is BrushMask -> brushRaw(c, x, y)
            }
            m = combine(m, c.mode, c.amount, if (c.invert) 1f - raw else raw)
        }
        return finish(spec, m)
    }

    /** Opaque gray ARGB for mask value [v] 0..1. */
    fun gray(v: Float): Int {
        val g = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (g shl 16) or (g shl 8) or g
    }

    /**
     * Renders [spec] for the pixels [left]..[left] + [width] × [top]..[top] + [height] of a
     * [w] x [h] document into [out] (row by row, [stride] ints per row, (left, top) at index 0).
     * Large regions are split over [Parallel] rows. Brush components come from [brushes] when it
     * has them, else they are rasterized for the region.
     */
    fun render(spec: MaskSpec, w: Int, h: Int, left: Int, top: Int, width: Int, height: Int, out: IntArray, stride: Int, brushes: BrushSource? = null) {
        if (width <= 0 || height <= 0) return
        renderGrid(spec, SampleGrid.pixels(left, top, width, height), out, stride, brushes)
    }

    /**
     * Renders [spec] at the samples of [grid] into [out] (row by row, [stride] ints per row), as
     * opaque gray ARGB.
     */
    fun renderGrid(spec: MaskSpec, grid: SampleGrid, out: IntArray, stride: Int, brushes: BrushSource? = null) {
        val width = grid.cols; val height = grid.rows
        if (width <= 0 || height <= 0) return
        val visible = spec.components.filter { it.visible }
        if (visible.isEmpty()) {
            val argb = gray(finish(spec, if (spec.startFull) 1f else 0f))
            for (row in 0 until height) out.fill(argb, row * stride, row * stride + width)
            return
        }
        // Per-component constants, computed once; brush coverage for the whole grid.
        val trig = FloatArray(visible.size * 2)
        val coverage = arrayOfNulls<ByteArray>(visible.size)
        visible.forEachIndexed { i, c ->
            when (c) {
                is RadialMask -> {
                    val rad = Math.toRadians(c.rotationDeg.toDouble())
                    trig[i * 2] = cos(rad).toFloat()
                    trig[i * 2 + 1] = sin(rad).toFloat()
                }
                is BrushMask -> coverage[i] = brushes?.coverage(c, grid)?.takeIf { it.size >= grid.size } ?: MaskBrushRaster.rasterize(c.strokes, grid)
                is LinearMask -> {}
            }
        }
        val start = if (spec.startFull) 1f else 0f
        val body = { y0: Int, y1: Int ->
            for (row in y0 until y1) {
                val py = grid.y(row)
                val base = row * stride
                val covBase = row * width
                for (col in 0 until width) {
                    val px = grid.x(col)
                    var m = start
                    for (i in visible.indices) {
                        val c = visible[i]
                        val raw = when (c) {
                            is LinearMask -> linearRaw(c, px, py)
                            is RadialMask -> if (c.rx > 0f && c.ry > 0f) radialAt(c, trig[i * 2], trig[i * 2 + 1], px, py) else 0f
                            is BrushMask -> (coverage[i]!![covBase + col].toInt() and 0xFF) / 255f
                        }
                        m = combine(m, c.mode, c.amount, if (c.invert) 1f - raw else raw)
                    }
                    out[base + col] = gray(finish(spec, m))
                }
            }
        }
        if (width.toLong() * height < PARALLEL_MIN_PIXELS) body(0, height) else Parallel.forRows(height, body)
    }

    /**
     * Document area (left, top, right, bottom) where the rendered mask can be non-zero, within
     * the [w] x [h] document; null = nowhere. Conservative: an inverted or full-start spec, or an
     * inverted ADD component, covers the whole document; otherwise only ADD components can raise
     * the mask above 0 (SUBTRACT and INTERSECT only lower it).
     */
    fun coverageBounds(spec: MaskSpec, w: Int, h: Int): IntArray? {
        if (w <= 0 || h <= 0) return null
        val full = intArrayOf(0, 0, w, h)
        if (!(spec.density > 0f)) return null
        if (spec.invert || spec.startFull) return full
        val acc = Bounds()
        for (c in spec.components) {
            if (!c.visible || c.mode != MaskMode.ADD || !(c.amount > 0f)) continue
            if (c.invert) return full
            val s = support(c, w, h) ?: continue
            acc.add(s[0], s[1], s[2], s[3])
        }
        return acc.toInts(w, h)
    }

    /**
     * Document area (left, top, right, bottom) where [c]'s raw value can be above 0 (the shape
     * itself), within the [w] x [h] document; null = nowhere.
     */
    fun support(c: MaskComponent, w: Int, h: Int): FloatArray? = when (c) {
        is LinearMask -> linearBox(c, w, h)
        is RadialMask -> {
            if (!(c.rx > 0f) || !(c.ry > 0f) || !c.cx.isFinite() || !c.cy.isFinite()) null
            else {
                val rad = Math.toRadians(c.rotationDeg.toDouble())
                val cs = abs(cos(rad)).toFloat(); val sn = abs(sin(rad)).toFloat()
                val ex = sqrt((c.rx * cs) * (c.rx * cs) + (c.ry * sn) * (c.ry * sn))
                val ey = sqrt((c.rx * sn) * (c.rx * sn) + (c.ry * cs) * (c.ry * cs))
                floatArrayOf(c.cx - ex, c.cy - ey, c.cx + ex, c.cy + ey)
            }
        }
        is BrushMask -> {
            val acc = Bounds()
            for (s in c.strokes) {
                if (s.erase) continue
                MaskBrushRaster.bounds(s)?.let { acc.add(it[0], it[1], it[2], it[3]) }
            }
            if (acc.isEmpty) null else floatArrayOf(acc.l, acc.t, acc.r, acc.b)
        }
    }

    /**
     * Document area (left, top, right, bottom) where [c] can change the mask value of a spec,
     * whatever the other components are (where its combined value differs from the neutral one);
     * null = nowhere. Hidden or zero-amount components change nothing.
     */
    fun influence(c: MaskComponent, w: Int, h: Int): IntArray? {
        if (w <= 0 || h <= 0) return null
        if (!c.visible || !(c.amount > 0f)) return null
        val full = intArrayOf(0, 0, w, h)
        // ADD / SUBTRACT act where r > 0, INTERSECT where r < 1 (r = raw, or 1 - raw inverted).
        val actsOnShape = (c.mode == MaskMode.INTERSECT) == c.invert
        if (!actsOnShape) return full
        val s = support(c, w, h) ?: return null
        return Bounds().apply { add(s[0], s[1], s[2], s[3]) }.toInts(w, h)
    }

    /**
     * Document area that may render differently between [before] and [after] (a [w] x [h]
     * document); null = nothing changed. When only one component differs (added, removed or
     * edited, same order otherwise) it is where that component acts; otherwise where either mask
     * can be non-zero.
     */
    fun changedRegion(before: MaskSpec?, after: MaskSpec?, w: Int, h: Int): IntArray? {
        if (before == after) return null
        if (before == null || after == null) return if (w > 0 && h > 0) intArrayOf(0, 0, w, h) else null
        // Outside both coverage bounds both masks are 0.
        val coverage = union(coverageBounds(before, w, h), coverageBounds(after, w, h), w, h)
        if (before.startFull != after.startFull || before.invert != after.invert || before.density != after.density) return coverage
        val changed = singleChange(before.components, after.components) ?: return coverage
        // Outside where the changed component acts, it leaves the value it gets unchanged (in
        // both versions), so every later component sees the same value too.
        val (old, new) = changed
        return union(old?.let { influence(it, w, h) }, new?.let { influence(it, w, h) }, w, h)
    }

    /** The one component that differs between [a] and [b] (old, new), when that is the only difference. */
    private fun singleChange(a: List<MaskComponent>, b: List<MaskComponent>): Pair<MaskComponent?, MaskComponent?>? {
        when {
            a.size == b.size -> {
                var at = -1
                for (i in a.indices) if (a[i] != b[i]) { if (at >= 0) return null; at = i }
                if (at < 0) return null
                return if (a[at].id == b[at].id) a[at] to b[at] else null
            }
            a.size + 1 == b.size -> {
                // One added (anywhere).
                var i = 0
                while (i < a.size && a[i] == b[i]) i++
                for (k in i until a.size) if (a[k] != b[k + 1]) return null
                return null to b[i]
            }
            a.size == b.size + 1 -> {
                var i = 0
                while (i < b.size && a[i] == b[i]) i++
                for (k in i until b.size) if (a[k + 1] != b[k]) return null
                return a[i] to null
            }
            else -> return null
        }
    }

    private fun union(p: IntArray?, q: IntArray?, w: Int, h: Int): IntArray? {
        if (p == null) return q
        if (q == null) return p
        val out = intArrayOf(min(p[0], q[0]), min(p[1], q[1]), max(p[2], q[2]), max(p[3], q[3]))
        out[0] = out[0].coerceIn(0, w); out[2] = out[2].coerceIn(0, w)
        out[1] = out[1].coerceIn(0, h); out[3] = out[3].coerceIn(0, h)
        return if (out[2] <= out[0] || out[3] <= out[1]) null else out
    }

    /**
     * Estimated time (ms, on the reference phone) to render [spec] over the document region
     * [left], [top], [right], [bottom] without a brush cache.
     */
    fun estimateMillis(spec: MaskSpec, left: Int, top: Int, right: Int, bottom: Int): Double {
        val pixels = max(0L, (right - left).toLong()) * max(0L, (bottom - top).toLong())
        if (pixels == 0L) return 0.0
        var ns = (pixels * PIXEL_NS).toDouble()
        val rw = (right - left).toDouble(); val rh = (bottom - top).toDouble()
        for (c in spec.components) {
            if (!c.visible) continue
            ns += pixels * COMPONENT_NS
            if (c !is BrushMask) continue
            for (s in c.strokes) {
                val d = MaskBrushRaster.dabsOf(s)
                if (d.isEmpty || d.right < left || d.left > right || d.bottom < top || d.top > bottom) continue
                // Each dab costs its disc, clipped (roughly) by the region.
                val side = 2.0 * d.radius
                val area = Math.PI * d.radius * d.radius * min(1.0, rw / side) * min(1.0, rh / side)
                ns += d.count * area * DAB_NS
            }
        }
        return ns / PARALLELISM / 1e6
    }

    /** Box of the part of the document where a linear component is not 0 (t < 1), or null. */
    private fun linearBox(c: LinearMask, w: Int, h: Int): FloatArray? {
        val dx = c.x1 - c.x0
        val dy = c.y1 - c.y0
        val len2 = dx * dx + dy * dy
        if (!(len2 > 0f)) return floatArrayOf(0f, 0f, w.toFloat(), h.toFloat())
        // Clip the document rectangle by the half-plane t <= 1 (one Sutherland–Hodgman pass).
        fun tAt(x: Float, y: Float) = ((x - c.x0) * dx + (y - c.y0) * dy) / len2
        val xs = floatArrayOf(0f, w.toFloat(), w.toFloat(), 0f)
        val ys = floatArrayOf(0f, 0f, h.toFloat(), h.toFloat())
        var l = Float.POSITIVE_INFINITY; var t = Float.POSITIVE_INFINITY
        var r = Float.NEGATIVE_INFINITY; var b = Float.NEGATIVE_INFINITY
        var any = false
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            val ti = tAt(xs[i], ys[i]); val tj = tAt(xs[j], ys[j])
            if (ti <= 1f) { any = true; l = min(l, xs[i]); r = max(r, xs[i]); t = min(t, ys[i]); b = max(b, ys[i]) }
            if ((ti < 1f) != (tj < 1f) && ti != tj) {
                val k = (1f - ti) / (tj - ti)
                val x = xs[i] + (xs[j] - xs[i]) * k
                val y = ys[i] + (ys[j] - ys[i]) * k
                any = true; l = min(l, x); r = max(r, x); t = min(t, y); b = max(b, y)
            }
        }
        return if (any) floatArrayOf(l, t, r, b) else null
    }

    /** A growing float box, turned into whole document pixels (one pixel of margin). */
    private class Bounds {
        var l = Float.POSITIVE_INFINITY; var t = Float.POSITIVE_INFINITY
        var r = Float.NEGATIVE_INFINITY; var b = Float.NEGATIVE_INFINITY
        val isEmpty: Boolean get() = l > r || t > b

        fun add(a: Float, bb: Float, c: Float, d: Float) {
            if (!a.isFinite() || !bb.isFinite() || !c.isFinite() || !d.isFinite()) {
                l = Float.NEGATIVE_INFINITY; t = Float.NEGATIVE_INFINITY; r = Float.POSITIVE_INFINITY; b = Float.POSITIVE_INFINITY
                return
            }
            l = min(l, a); t = min(t, bb); r = max(r, c); b = max(b, d)
        }

        fun toInts(w: Int, h: Int): IntArray? {
            if (isEmpty) return null
            val out = intArrayOf(
                max(0, clampToInt(floor(l)) - 1), max(0, clampToInt(floor(t)) - 1),
                min(w, clampToInt(ceil(r)) + 1), min(h, clampToInt(ceil(b)) + 1),
            )
            return if (out[2] <= out[0] || out[3] <= out[1]) null else out
        }

        private fun clampToInt(v: Float): Int = when {
            v.isNaN() -> 0
            v < -1e9f -> -1_000_000_000
            v > 1e9f -> 1_000_000_000
            else -> v.toInt()
        }
    }

    /** Below this many pixels a render stays on the calling thread. */
    private const val PARALLEL_MIN_PIXELS = 65_536L

    // Cost model (ns per unit on the reference phone, single core) and its usable parallelism.
    private const val PIXEL_NS = 6L
    private const val COMPONENT_NS = 12L
    private const val DAB_NS = 8.0
    private const val PARALLELISM = 3.0
}
