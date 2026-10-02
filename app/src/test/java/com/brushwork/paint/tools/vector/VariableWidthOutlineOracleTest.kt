package com.brushwork.paint.tools.vector

import com.brushwork.paint.core.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * v1.5 §4.5 (A4): the production [VariableWidthOutline] against an exact oracle. The area a
 * disc of the local width sweeps along a polyline is the union of the discs at the points and of
 * the convex hulls of neighbouring discs (= discs with linearly interpolated centre and radius);
 * every point clearly inside it must be filled (non-zero) and every point clearly outside it must
 * not, for random lines, widths, closed loops, U-turns, cusps and discs holding their neighbours.
 */
class VariableWidthOutlineOracleTest {

    /** Non-zero winding of [p] in the flattened [path] (android's default fill). */
    private fun inside(path: VectorPath, p: Vec2): Boolean = winding(path, p) != 0

    private fun winding(path: VectorPath, p: Vec2): Int {
        var winding = 0
        for (poly in path.flatten(0.05f)) {
            val pts = poly.points
            for (i in pts.indices) {
                val a = pts[i]
                val b = pts[(i + 1) % pts.size]
                val cross = (b.x - a.x) * (p.y - a.y) - (p.x - a.x) * (b.y - a.y)
                if (a.y <= p.y) { if (b.y > p.y && cross > 0f) winding++ } else if (b.y <= p.y && cross < 0f) winding--
            }
        }
        return winding
    }

    /** Signed distance of [p] to the swept shape (negative inside). */
    private fun sdf(xs: FloatArray, ys: FloatArray, ws: FloatArray, closed: Boolean, p: Vec2): Float {
        val n = xs.size
        var best = Float.POSITIVE_INFINITY
        for (i in 0 until n) best = min(best, hypot(p.x - xs[i], p.y - ys[i]) - max(0f, ws[i]) / 2f)
        val segs = if (closed && n > 2) n else n - 1
        for (s in 0 until segs) {
            val a = s
            val b = (s + 1) % n
            // |p − c(t)| − r(t) is convex in t: ternary search for its minimum (exact hull distance).
            fun f(t: Double): Double {
                val cx = xs[a] + (xs[b] - xs[a]) * t
                val cy = ys[a] + (ys[b] - ys[a]) * t
                val r = (max(0f, ws[a]) + (max(0f, ws[b]) - max(0f, ws[a])) * t) / 2.0
                return Math.hypot(p.x - cx, p.y - cy) - r
            }
            var lo = 0.0
            var hi = 1.0
            repeat(80) {
                val m1 = lo + (hi - lo) / 3
                val m2 = hi - (hi - lo) / 3
                if (f(m1) < f(m2)) hi = m2 else lo = m1
            }
            best = min(best, f((lo + hi) / 2).toFloat())
        }
        return best
    }

    private fun check(what: String, xs: FloatArray, ys: FloatArray, ws: FloatArray, closed: Boolean, r: Random, samples: Int = 500) {
        val path = VariableWidthOutline.build(xs, ys, ws, xs.size, closed)
        var l = Float.POSITIVE_INFINITY; var t = Float.POSITIVE_INFINITY; var rr = Float.NEGATIVE_INFINITY; var b = Float.NEGATIVE_INFINITY
        for (i in xs.indices) {
            val h = ws[i] / 2f + 4f
            l = min(l, xs[i] - h); t = min(t, ys[i] - h); rr = max(rr, xs[i] + h); b = max(b, ys[i] + h)
        }
        var checked = 0
        repeat(samples) {
            val p = Vec2(l + r.nextFloat() * (rr - l), t + r.nextFloat() * (b - t))
            val d = sdf(xs, ys, ws, closed, p)
            val data = "xs=${xs.toList()} ys=${ys.toList()} ws=${ws.toList()}"
            if (d <= -0.5f) { assertTrue("$what: $p is inside (sdf $d) but not filled (winding ${winding(path, p)}) $data", inside(path, p)); checked++ }
            if (d >= 0.5f) { assertFalse("$what: $p is outside (sdf $d) but filled (winding ${winding(path, p)}) $data", inside(path, p)); checked++ }
        }
        assertTrue("$what: nothing was checked", checked > samples / 3)
    }

    @Test
    fun randomLinesMatchTheSweptDiscs() {
        val r = Random(1505)
        repeat(160) { case ->
            val n = 2 + r.nextInt(14)
            val xs = FloatArray(n) { 20f + r.nextFloat() * 220f }
            val ys = FloatArray(n) { 20f + r.nextFloat() * 220f }
            val ws = FloatArray(n) { if (r.nextInt(6) == 0) 0f else r.nextFloat() * 70f }
            val closed = r.nextInt(3) == 0
            check("random $case (n $n, closed $closed)", xs, ys, ws, closed, r)
        }
    }

    @Test
    fun denseSmoothCurvesWithBigWidthsMatchTheSweptDiscs() {
        // Points every couple of pixels along tight curves with widths far larger than the
        // curvature radius (the inner offset folds over itself): the line a CurveWidths.line gives.
        val r = Random(7)
        repeat(12) { case ->
            val n = 60 + r.nextInt(80)
            val cx = 150f; val cy = 150f
            val turns = 0.4f + r.nextFloat() * 1.6f
            val radius = 15f + r.nextFloat() * 60f
            val xs = FloatArray(n); val ys = FloatArray(n); val ws = FloatArray(n)
            for (i in 0 until n) {
                val f = i.toFloat() / (n - 1)
                val a = f * turns * 2f * Math.PI.toFloat()
                val rad = radius * (0.6f + 0.4f * f)
                xs[i] = cx + rad * cos(a); ys[i] = cy + rad * sin(a)
                val e = CurveGeometry.smoothstep(if (f < 0.5f) f * 2f else 2f - f * 2f)
                ws[i] = 4f + (40f + r.nextFloat() * 120f) * e
            }
            check("spiral $case", xs, ys, ws, closed = false, r = r, samples = 400)
        }
    }

    @Test
    fun curveWidthLinesMatchTheSweptDiscs() {
        // The lines the Curve tool outlines: smoothstep widths between anchors of 0..300 %.
        val r = Random(42)
        repeat(25) { case ->
            val n = 2 + r.nextInt(5)
            val anchors = List(n) { CurveAnchor(30f + r.nextFloat() * 200f, 30f + r.nextFloat() * 200f, sharp = r.nextBoolean(), width = r.nextFloat() * 3f) }
            val closed = r.nextInt(3) == 0
            val l = CurveWidths.line(anchors, closed, r.nextFloat(), r.nextBoolean(), 6f + r.nextFloat() * 30f)!!
            check(
                "curve $case", l.xs.copyOf(l.n), l.ys.copyOf(l.n), l.ws.copyOf(l.n), closed && n > 2, r, samples = 300,
            )
        }
        // A straight line whose width swells and shrinks several times.
        val n = 200
        val xs = FloatArray(n) { 20f + it * 1.5f }
        val ys = FloatArray(n) { 120f }
        val ws = FloatArray(n) { 4f + 60f * abs(sin(it / 17f)) }
        check("wavy", xs, ys, ws, false, r)
    }

    @Test
    fun noSliversAcrossTheLine() {
        // Thin gaps across a line (where a joint's pieces don't quite meet) would show as seams:
        // every point across the width, everywhere along the line, is filled.
        val r = Random(11)
        repeat(12) { case ->
            val anchors = List(4) { CurveAnchor(40f + r.nextFloat() * 220f, 40f + r.nextFloat() * 220f, width = 0.2f + r.nextFloat() * 2.8f) }
            val l = CurveWidths.line(anchors, false, r.nextFloat() * 0.5f, false, 10f + r.nextFloat() * 40f)!!
            val path = VariableWidthOutline.build(l.xs, l.ys, l.ws, l.n)
            for (i in 0 until l.n - 1) {
                val dx = l.xs[i + 1] - l.xs[i]
                val dy = l.ys[i + 1] - l.ys[i]
                val d = hypot(dx, dy)
                if (d < 1e-3f) continue
                val nx = -dy / d
                val ny = dx / d
                for (f in listOf(0f, 0.37f, 0.71f)) {
                    val cx = l.xs[i] + dx * f
                    val cy = l.ys[i] + dy * f
                    val half = (l.ws[i] + (l.ws[i + 1] - l.ws[i]) * f) / 2f - 0.6f
                    if (half <= 0f) continue
                    for (o in listOf(-0.95f, -0.5f, 0f, 0.5f, 0.95f)) {
                        val p = Vec2(cx + nx * half * o, cy + ny * half * o)
                        assertTrue("case $case: gap at $p (point $i)", inside(path, p))
                    }
                }
            }
        }
    }

    @Test
    fun uTurnsCuspsAndContainedDiscs() {
        val r = Random(3)
        // A hairpin: there and straight back.
        check("U-turn", floatArrayOf(40f, 200f, 40f), floatArrayOf(100f, 100f, 100.0001f), floatArrayOf(20f, 30f, 20f), false, r)
        check("U-turn closed", floatArrayOf(40f, 200f, 40f, 30f), floatArrayOf(100f, 100f, 110f, 105f), floatArrayOf(20f, 30f, 20f, 10f), true, r)
        // A tiny disc next to a huge one (the huge one holds it), then normal segments.
        check("contained", floatArrayOf(50f, 60f, 140f, 220f), floatArrayOf(100f, 100f, 110f, 90f), floatArrayOf(4f, 80f, 10f, 30f), false, r)
        check("contained both sides", floatArrayOf(60f, 100f, 140f), floatArrayOf(100f, 100f, 100f), floatArrayOf(100f, 2f, 100f), false, r)
        // A sharp zigzag with growing widths.
        val zx = FloatArray(9) { 30f + it * 25f }
        val zy = FloatArray(9) { if (it % 2 == 0) 80f else 160f }
        check("zigzag", zx, zy, FloatArray(9) { 5f + it * 8f }, false, r)
        // A closed square loop keeps its hole; a loop with a contained disc too.
        check("square", floatArrayOf(40f, 200f, 200f, 40f), floatArrayOf(40f, 40f, 200f, 200f), floatArrayOf(10f, 30f, 10f, 50f), true, r)
        check("loop with a blob", floatArrayOf(40f, 200f, 205f, 200f, 40f), floatArrayOf(40f, 40f, 45f, 200f, 200f), floatArrayOf(10f, 10f, 90f, 10f, 10f), true, r)
        // Zero widths along part of the line.
        check("zero part", floatArrayOf(40f, 100f, 160f, 220f), floatArrayOf(100f, 140f, 100f, 140f), floatArrayOf(0f, 0f, 30f, 0f), false, r)
    }

    @Test
    fun theOutlineIsPiecewiseAndWithinTheTolerance() {
        // A long constant line: pieces of about CHUNK px (tight dirty regions), together exact.
        val n = 401
        val xs = FloatArray(n) { 10f + it * 2f }
        val ys = FloatArray(n) { 100f }
        val path = VariableWidthOutline.build(xs, ys, FloatArray(n) { 12f }, n)
        val pieces = path.subpathControlBounds()
        assertTrue("several pieces: ${pieces.size}", pieces.size in 6..8)
        for (b in pieces) assertTrue("piece of about ${VariableWidthOutline.CHUNK} px: $b", b.width <= VariableWidthOutline.CHUNK + 14f)
        // The edges are within the tolerance of the true width (6 px half-width).
        for (x in listOf(20f, 300f, 555f, 805f)) {
            assertTrue(inside(path, Vec2(x, 105.7f)))
            assertFalse(inside(path, Vec2(x, 106.3f)))
        }
        // Round caps within 0.25 px.
        assertTrue(inside(path, Vec2(10f - 5.7f, 100f)))
        assertFalse(inside(path, Vec2(10f - 6.3f, 100f)))
        val d = 6f / kotlin.math.sqrt(2f)
        assertTrue(inside(path, Vec2(810f + d - 0.3f, 100f - d + 0.3f)))
    }

    @Test
    fun aSingleClosedLoopIsOneShapeWithAHole() {
        val n = 90
        val xs = FloatArray(n) { 150f + 80f * cos(it * 2f * Math.PI.toFloat() / n) }
        val ys = FloatArray(n) { 150f + 80f * sin(it * 2f * Math.PI.toFloat() / n) }
        val path = VariableWidthOutline.build(xs, ys, FloatArray(n) { 20f }, n, closed = true)
        assertFalse("the inside stays empty", inside(path, Vec2(150f, 150f)))
        for (k in 0 until 36) {
            val a = k * 10f * Math.PI.toFloat() / 180f
            assertTrue("on the ring at $k", inside(path, Vec2(150f + 80f * cos(a), 150f + 80f * sin(a))))
            assertFalse("beyond the ring at $k", inside(path, Vec2(150f + 92f * cos(a), 150f + 92f * sin(a))))
            assertFalse("inside the ring at $k", inside(path, Vec2(150f + 68f * cos(a), 150f + 68f * sin(a))))
        }
    }

    @Test
    fun aTwoThousandPointLineIsBuiltQuickly() {
        val n = 2000
        val xs = FloatArray(n) { 10f + it * 0.5f }
        val ys = FloatArray(n) { 300f + 120f * sin(it / 90f) }
        val ws = FloatArray(n) { 6f + 30f * abs(sin(it / 250f)) }
        repeat(5) { VariableWidthOutline.build(xs, ys, ws, n) } // warm up
        val t0 = System.nanoTime()
        repeat(10) { VariableWidthOutline.build(xs, ys, ws, n) }
        val ms = (System.nanoTime() - t0) / 1e6 / 10
        assertTrue("$ms ms per 2000-point outline", ms < 25.0)
        assertEquals(VectorPath.EMPTY.ops, VariableWidthOutline.build(xs, ys, FloatArray(n), n).ops)
    }
}
