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
 * Renders editable masks ([MaskSpec], v1.5 §4.3; owned by A5) in pure Kotlin: per pixel centre,
 * every visible component's raw value, combined in order (§4.3b):
 * - start: `m = startFull ? 1 : 0`;
 * - per visible component, `r = invert ? 1 − raw : raw`, amount `a`: ADD `m = max(m, a·r)`,
 *   SUBTRACT `m = m·(1 − a·r)`, INTERSECT `m = m·(1 − a + a·r)`;
 * - final `(spec.invert ? 1 − m : m) · density`, written as opaque gray ARGB (white = visible).
 *
 * Raw values: linear `1 − smoothstep(0, 1, t)` with `t` the projection on p0→p1 over |p1 − p0|;
 * radial `1 − smoothstep(1 − feather, 1, d)` with `d` the rotated, normalized elliptical
 * distance.
 *
 * F2 reference: linear and radial components; brush components are not drawn yet (raw 0: an
 * ADD brush adds nothing, a SUBTRACT one removes nothing; A5 writes them with their per-component
 * coverage cache).
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
                is BrushMask -> 0f
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
     * Large regions are split over [Parallel] rows.
     */
    fun render(spec: MaskSpec, w: Int, h: Int, left: Int, top: Int, width: Int, height: Int, out: IntArray, stride: Int) {
        if (width <= 0 || height <= 0) return
        val visible = spec.components.filter { it.visible }
        if (visible.isEmpty()) {
            val argb = gray(finish(spec, if (spec.startFull) 1f else 0f))
            for (row in 0 until height) out.fill(argb, row * stride, row * stride + width)
            return
        }
        // Per-component constants, computed once.
        val trig = FloatArray(visible.size * 2)
        visible.forEachIndexed { i, c ->
            if (c is RadialMask) {
                val rad = Math.toRadians(c.rotationDeg.toDouble())
                trig[i * 2] = cos(rad).toFloat()
                trig[i * 2 + 1] = sin(rad).toFloat()
            }
        }
        val start = if (spec.startFull) 1f else 0f
        val body = { y0: Int, y1: Int ->
            for (row in y0 until y1) {
                val py = top + row + 0.5f
                val base = row * stride
                for (col in 0 until width) {
                    val px = left + col + 0.5f
                    var m = start
                    for (i in visible.indices) {
                        val c = visible[i]
                        val raw = when (c) {
                            is LinearMask -> linearRaw(c, px, py)
                            is RadialMask -> if (c.rx > 0f && c.ry > 0f) radialAt(c, trig[i * 2], trig[i * 2 + 1], px, py) else 0f
                            is BrushMask -> 0f
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
        var l = Float.POSITIVE_INFINITY; var t = Float.POSITIVE_INFINITY
        var r = Float.NEGATIVE_INFINITY; var b = Float.NEGATIVE_INFINITY
        fun add(a: Float, bb: Float, c: Float, d: Float) { l = min(l, a); t = min(t, bb); r = max(r, c); b = max(b, d) }
        for (c in spec.components) {
            if (!c.visible || c.mode != MaskMode.ADD || !(c.amount > 0f)) continue
            if (c.invert) return full
            when (c) {
                is LinearMask -> {
                    val box = linearBox(c, w, h) ?: continue
                    add(box[0], box[1], box[2], box[3])
                }
                is RadialMask -> {
                    if (!(c.rx > 0f) || !(c.ry > 0f)) continue
                    val rad = Math.toRadians(c.rotationDeg.toDouble())
                    val cs = abs(cos(rad)).toFloat(); val sn = abs(sin(rad)).toFloat()
                    val ex = sqrt((c.rx * cs) * (c.rx * cs) + (c.ry * sn) * (c.ry * sn))
                    val ey = sqrt((c.rx * sn) * (c.rx * sn) + (c.ry * cs) * (c.ry * cs))
                    add(c.cx - ex, c.cy - ey, c.cx + ex, c.cy + ey)
                }
                is BrushMask -> for (s in c.strokes) {
                    if (s.erase || s.points.size == 0) continue
                    val e = max(0f, s.size) / 2f
                    for (i in 0 until s.points.size) {
                        val x = s.points.x[i]; val y = s.points.y[i]
                        if (x.isFinite() && y.isFinite()) add(x - e, y - e, x + e, y + e)
                    }
                }
            }
        }
        if (l > r || t > b) return null
        val out = intArrayOf(
            max(0, floor(l).toInt() - 1), max(0, floor(t).toInt() - 1),
            min(w, ceil(r).toInt() + 1), min(h, ceil(b).toInt() + 1),
        )
        return if (out[2] <= out[0] || out[3] <= out[1]) null else out
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

    /** Below this many pixels a render stays on the calling thread. */
    private const val PARALLEL_MIN_PIXELS = 65_536L
}
