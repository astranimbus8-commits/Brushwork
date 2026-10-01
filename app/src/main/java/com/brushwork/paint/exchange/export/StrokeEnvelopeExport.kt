package com.brushwork.paint.exchange.export

import com.brushwork.paint.brush.BrushPreset
import com.brushwork.paint.brush.BrushTip
import com.brushwork.paint.brush.Dab
import com.brushwork.paint.brush.StrokeCost
import com.brushwork.paint.brush.StrokeDynamics
import com.brushwork.paint.brush.StrokeRaster
import com.brushwork.paint.brush.StrokeSampler
import com.brushwork.paint.brush.sanitized
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.vector.PathOp
import com.brushwork.paint.tools.vector.VariableWidthOutline
import com.brushwork.paint.tools.vector.VectorPath
import com.brushwork.paint.vector.VStroke
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Brush strokes as filled outlines for SVG / PDF (v1.5 §4.10b, A8). A stroke of a solid brush
 * (pen, marker, calligraphy or square tip, hardness ≥ 0.75, no scatter or grain, opacity not
 * following pressure, flow ≥ 0.95) looks like the union of its dabs' shapes: its outline is
 * built from the exact dabs the replay stamps (the same sampler, dynamics and cost-limited
 * spacing as `StrokeRaster`), each dab's visible edge at its 50 % coverage radius
 * (`radius · (1 + hardness) / 2`, where the tip's hardness falloff crosses one half). Round tips go
 * through [VariableWidthOutline]; elliptical tips (calligraphy, roundness < 1) through the same
 * in the tip's circle space; square and marker tips as convex hulls of consecutive dab shapes.
 * Every piece winds the same way: fill with the non-zero rule. Other brushes are exported as
 * pictures. Pure Kotlin; thread-safe.
 */
object StrokeEnvelopeExport {
    private val SOLID_TIPS = setOf(BrushTip.ROUND_HARD, BrushTip.CALLIGRAPHY, BrushTip.SQUARE, BrushTip.MARKER)

    /** Largest deviation (document px) allowed when dropping dabs from the outline. */
    private const val TOLERANCE = 0.2f

    /** True when strokes of [preset] can be exported as a filled outline. */
    fun isSolid(preset: BrushPreset): Boolean {
        val p = preset.sanitized()
        return p.tip in SOLID_TIPS && p.hardness >= 0.75f && p.scatter == 0f && p.grain == 0f &&
            !p.pressureOpacity && p.flow >= 0.95f && p.antiAlias
    }

    /** The outline of [s] (document px), or null when its brush is not solid or it paints nothing. */
    fun of(s: VStroke): VectorPath? = outline(s.preset, s.stylus, s.seed, s.points, s.sizeScale, s.taperIn, s.taperOut)

    /**
     * The outline of a stroke replayed from [points] (raw pressures) with [preset] and the replay's
     * size scale and taper flags; null when the brush is not solid or nothing is painted.
     */
    fun outline(
        preset: BrushPreset,
        stylus: Boolean,
        seed: Long,
        points: PackedPoints,
        sizeScale: Float = 1f,
        taperIn: Boolean = true,
        taperOut: Boolean = true,
    ): VectorPath? {
        if (!isSolid(preset) || points.size == 0) return null
        val p = StrokeRaster.replayPreset(preset, sizeScale, taperIn, taperOut)
        val edge = (1f + p.hardness) / 2f
        val dabs = dabs(p, stylus, seed, points)
        if (dabs.isEmpty()) return null
        val n = dabs.size
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        val rs = FloatArray(n)
        var m = 0
        for (d in dabs) {
            if (!(d.alpha > 0f) || !d.cx.isFinite() || !d.cy.isFinite()) continue
            xs[m] = d.cx; ys[m] = d.cy; rs[m] = d.diameter / 2f * edge
            m++
        }
        if (m == 0) return null
        val angle = p.angle
        val rho = p.roundness.coerceIn(0.05f, 1f)
        // The tip's frame: T = rotate(angle) · scale(1, roundness) maps a circle onto the dab shape.
        val round = p.tip == BrushTip.ROUND_HARD && rho >= 0.999f
        val toTip = if (round) null else frame(angle, rho)
        if (toTip != null) {
            val inv = toTip.inverse
            for (i in 0 until m) {
                val x = xs[i]; val y = ys[i]
                xs[i] = inv[0] * x + inv[1] * y
                ys[i] = inv[2] * x + inv[3] * y
            }
        }
        val keep = simplify(xs, ys, rs, m, TOLERANCE)
        val kx = FloatArray(keep.size) { xs[keep[it]] }
        val ky = FloatArray(keep.size) { ys[keep[it]] }
        val kr = FloatArray(keep.size) { rs[keep[it]] }
        val circleSpace = when (p.tip) {
            BrushTip.SQUARE -> hulls(kx, ky, kr, SQUARE)
            BrushTip.MARKER -> hulls(kx, ky, kr, SQUIRCLE)
            else -> VariableWidthOutline.build(kx, ky, FloatArray(kr.size) { kr[it] * 2f }, kr.size, closed = false, tolerance = 0.25f)
        }
        if (circleSpace.isEmpty) return null
        if (toTip == null) return circleSpace
        val f = toTip.forward
        return circleSpace.transformed { q -> Vec2(f[0] * q.x + f[1] * q.y, f[2] * q.x + f[3] * q.y) }
    }

    /** The RGB of [color] at the stroke opacity (the coverage is painted with the color's RGB only). */
    fun fillColor(color: Int, preset: BrushPreset, opacity: Float): Int {
        val a = (preset.sanitized().opacity * opacity.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0xFFFFFF)
    }

    /** The resolved dabs of the replay (as `StrokeRaster.sample` makes them). */
    internal fun dabs(p: BrushPreset, stylus: Boolean, seed: Long, points: PackedPoints): List<Dab> {
        val dynamics = StrokeDynamics(p, stylus, seed)
        val out = ArrayList<Dab>()
        val xs = points.x; val ys = points.y; val ps = points.p
        var spacingScale = 1f
        val sampler = StrokeSampler(
            spacingAt = { pr, d -> dynamics.spacing(pr, d) * spacingScale },
            onSample = { x, y, pr, d -> out += dynamics.newDab(x, y, pr, d) },
        )
        var lastX = xs[0]
        var lastY = ys[0]
        sampler.begin(xs[0], ys[0], StrokeRaster.pressureOf(stylus, ps[0]))
        for (i in 1 until points.size) {
            val x = xs[i]; val y = ys[i]
            val len = hypot(x - lastX, y - lastY)
            lastX = x; lastY = y
            spacingScale = StrokeCost.spacingScale(len, dynamics.size, p.spacing, { d -> d * d }, StrokeCost.BUFFER_BUDGET)
            sampler.add(x, y, StrokeRaster.pressureOf(stylus, ps[i]))
        }
        sampler.end()
        val total = sampler.length
        for (d in out) dynamics.resolve(d, total)
        return out
    }

    /** A 2x2 linear map and its inverse (row-major a, b, c, d). */
    private class Frame(val forward: FloatArray, val inverse: FloatArray)

    private fun frame(angleDeg: Float, rho: Float): Frame {
        val r = angleDeg * PI.toFloat() / 180f
        val c = cos(r); val s = sin(r)
        // rotate(angle) · scale(1, rho)
        val f = floatArrayOf(c, -s * rho, s, c * rho)
        val det = f[0] * f[3] - f[1] * f[2]
        val inv = floatArrayOf(f[3] / det, -f[1] / det, -f[2] / det, f[0] / det)
        return Frame(f, inv)
    }

    /**
     * Indices of the dabs to keep: a Douglas-Peucker pass over (x, y, r) where a dab may go when
     * the line between its kept neighbours is within [tol] of its center and radius.
     */
    internal fun simplify(xs: FloatArray, ys: FloatArray, rs: FloatArray, n: Int, tol: Float): IntArray {
        if (n <= 2) return IntArray(n) { it }
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        val stack = ArrayDeque<Int>()
        stack.addLast(0); stack.addLast(n - 1)
        while (stack.isNotEmpty()) {
            val j = stack.removeLast()
            val i = stack.removeLast()
            if (j - i < 2) continue
            val ax = xs[i]; val ay = ys[i]; val bx = xs[j]; val by = ys[j]
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            var worst = -1f
            var at = -1
            for (k in i + 1 until j) {
                val t = if (len2 > 0f) (((xs[k] - ax) * dx + (ys[k] - ay) * dy) / len2).coerceIn(0f, 1f) else 0f
                val px = ax + dx * t; val py = ay + dy * t
                val dev = hypot(xs[k] - px, ys[k] - py) + abs(rs[k] - (rs[i] + (rs[j] - rs[i]) * t))
                if (dev > worst) { worst = dev; at = k }
            }
            if (worst > tol && at > 0) {
                keep[at] = true
                stack.addLast(i); stack.addLast(at)
                stack.addLast(at); stack.addLast(j)
            }
        }
        var c = 0
        for (k in 0 until n) if (keep[k]) c++
        val out = IntArray(c)
        var o = 0
        for (k in 0 until n) if (keep[k]) out[o++] = k
        return out
    }

    /** A unit square (corners at ±1). */
    private val SQUARE: List<Vec2> = listOf(Vec2(1f, 1f), Vec2(-1f, 1f), Vec2(-1f, -1f), Vec2(1f, -1f))

    /** The marker tip's superellipse |x|⁴ + |y|⁴ = 1 as a polygon. */
    private val SQUIRCLE: List<Vec2> = List(32) { i ->
        val t = i * 2.0 * PI / 32
        val c = cos(t); val s = sin(t)
        fun f(v: Double) = (abs(v).pow(0.5) * (if (v < 0) -1.0 else 1.0)).toFloat()
        Vec2(f(c), f(s))
    }

    /**
     * Convex hulls of consecutive dab shapes ([shape] scaled by each radius), all wound the same
     * way: the area every pair sweeps (their union is the stroke).
     */
    private fun hulls(xs: FloatArray, ys: FloatArray, rs: FloatArray, shape: List<Vec2>): VectorPath {
        val n = xs.size
        if (n == 0) return VectorPath.EMPTY
        val ops = ArrayList<PathOp>()
        fun corners(i: Int): List<Vec2> = shape.map { Vec2(xs[i] + it.x * rs[i], ys[i] + it.y * rs[i]) }
        if (n == 1) {
            addPolygon(convexHull(corners(0)), ops)
        } else {
            for (i in 0 until n - 1) addPolygon(convexHull(corners(i) + corners(i + 1)), ops)
        }
        return if (ops.isEmpty()) VectorPath.EMPTY else VectorPath(ops)
    }

    private fun addPolygon(pts: List<Vec2>, ops: MutableList<PathOp>) {
        if (pts.size < 3) return
        ops += PathOp.MoveTo(pts[0])
        for (k in 1 until pts.size) ops += PathOp.LineTo(pts[k])
        ops += PathOp.Close
    }

    /** Andrew's monotone chain: the hull counter-clockwise (y up), without collinear points. */
    internal fun convexHull(points: List<Vec2>): List<Vec2> {
        val p = points.filter { it.x.isFinite() && it.y.isFinite() }.sortedWith(compareBy<Vec2>({ it.x }, { it.y }))
        if (p.size < 3) return p
        fun cross(o: Vec2, a: Vec2, b: Vec2) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        val lower = ArrayList<Vec2>()
        for (q in p) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], q) <= 0f) lower.removeAt(lower.lastIndex)
            lower += q
        }
        val upper = ArrayList<Vec2>()
        for (q in p.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], q) <= 0f) upper.removeAt(upper.lastIndex)
            upper += q
        }
        lower.removeAt(lower.lastIndex)
        upper.removeAt(upper.lastIndex)
        return lower + upper
    }
}
