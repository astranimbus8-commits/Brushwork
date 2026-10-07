package com.brushwork.paint.tools.vector.spline

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.vector.CurveGeometry
import com.brushwork.paint.tools.vector.PathOp
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSpline
import com.brushwork.paint.vector.VSplinePoint
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * v1.7 (item 4, step F3): [NurbsGeometry.pieces] splits a spline at its interior sharp points and
 * [SplineBezier] joins the converted pieces into one subpath, one sharp anchor per corner with
 * broken tangents. A spline without sharp points converts exactly as in v1.6 (pinned by digests
 * of a fixed set of random splines, captured on v1.6).
 */
class NurbsPiecesTest {
    private fun pt(x: Float, y: Float, width: Float = 1f, sharp: Boolean = false) = VSplinePoint(x, y, width = width, sharp = sharp)

    private fun spline(vararg sharpAt: Int, n: Int, order: Int = 4, cyclic: Boolean = false, endpoint: Boolean = true): VSpline {
        val rnd = Random(n * 31 + order)
        val pts = SplineTestSupport.randomPoints(rnd, n, widths = true).mapIndexed { i, p -> if (i in sharpAt) p.copy(sharp = true) else p }
        return VSpline(pts, order = order, endpoint = endpoint, cyclic = cyclic)
    }

    private fun assertNear(expected: Double, actual: Float?, tol: Double = 1e-3, what: String = "") {
        assertTrue("$what: expected $expected, was $actual", actual != null && abs(expected - actual) <= tol)
    }

    // ------------------------------------------------------------------ v1.6 unchanged

    @Test
    fun withoutSharpPointsTheConversionIsV16sBitForBit() {
        // Orders 2..4, constant weights: the exact conversion (plain double arithmetic), so the
        // digest holds on every platform.
        assertEquals(V16_EXACT, v16Digest(exactOnly = true))
        val inc = SplineBezier.Incremental()
        assertEquals(V16_EXACT, digest(digestSplines(exactOnly = true).map { inc.toSubpath(it) }))
    }

    @Test
    fun withoutSharpPointsTheApproximationIsV16sBitForBit() {
        // Orders 5..6 and weights go through Math.pow / hypot, whose last bits may differ
        // between platforms: pinned where it was captured.
        assumeTrue(System.getProperty("os.name").orEmpty().startsWith("Windows"))
        assertEquals(V16_ALL, v16Digest(exactOnly = false))
        val inc = SplineBezier.Incremental()
        assertEquals(V16_ALL, digest(digestSplines(exactOnly = false).map { inc.toSubpath(it) }))
    }

    @Test
    fun withoutAnInteriorSharpPointThePieceIsTheSplineItself() {
        val plain = spline(n = 8)
        assertSame(plain, NurbsGeometry.pieces(plain).single())
        // The ends of an open spline are never corners (sanitized or not).
        val ends = spline(0, 7, n = 8)
        assertSame(ends, NurbsGeometry.pieces(ends).single())
        // Two cyclic points are not a closed curve: no interior point.
        val two = spline(0, 1, n = 2, cyclic = true)
        assertSame(two, NurbsGeometry.pieces(two).single())
        val closed = spline(n = 6, cyclic = true)
        assertSame(closed, NurbsGeometry.pieces(closed).single())
    }

    // ------------------------------------------------------------------ pieces

    @Test
    fun anOpenSplineSplitsAtEachInteriorSharpPoint() {
        val s = spline(2, 3, 5, n = 7, order = 4)
        val parts = NurbsGeometry.pieces(s)
        assertEquals(listOf(listOf(0, 1, 2), listOf(2, 3), listOf(3, 4, 5), listOf(5, 6)), parts.map { p -> p.points.map { s.points.indexOf(it) } })
        assertEquals(listOf(3, 2, 3, 2), parts.map { it.order })
        for (p in parts) {
            assertTrue(p.endpoint)
            assertFalse(p.cyclic)
            for (q in p.points) assertTrue(s.points.any { it === q })
        }
        // A long piece keeps the spline's order.
        val long = NurbsGeometry.pieces(spline(3, n = 10, order = 6))
        assertEquals(listOf(4, 7), long.map { it.points.size })
        assertEquals(listOf(4, 6), long.map { it.order })
    }

    @Test
    fun aClosedSplineIsRotatedToItsFirstSharpPoint() {
        val s = spline(2, n = 6, cyclic = true)
        val one = NurbsGeometry.pieces(s).single()
        assertEquals(listOf(2, 3, 4, 5, 0, 1, 2), one.points.map { s.points.indexOf(it) })
        assertSame(one.points.first(), one.points.last())
        assertEquals(4, one.order)
        assertTrue(one.endpoint)
        assertFalse(one.cyclic)
        val t = spline(1, 4, n = 6, cyclic = true)
        assertEquals(listOf(listOf(1, 2, 3, 4), listOf(4, 5, 0, 1)), NurbsGeometry.pieces(t).map { p -> p.points.map { t.points.indexOf(it) } })
    }

    // ------------------------------------------------------------------ the joined subpath

    @Test
    fun aCornerIsOneSharpAnchorAtItsPointWithItsWidthAndBrokenTangents() {
        val p = listOf(pt(100f, 400f), pt(200f, 100f), pt(300f, 400f, width = 2.5f, sharp = true), pt(400f, 100f), pt(500f, 400f))
        val sub = SplineBezier.toSubpath(VSpline(p, order = 4))
        // Two quadratic pieces (3 points each), one span each.
        assertEquals(3, sub.anchors.size)
        assertFalse(sub.closed)
        assertEquals(listOf(false, true, false), sub.anchors.map { it.sharp })
        val c = sub.anchors[1]
        assertEquals(300f, c.x)
        assertEquals(400f, c.y)
        assertEquals(2.5f, c.width)
        // Quadratic end handles, elevated: 2/3 of the way to the neighbouring control point.
        assertNear(2.0 / 3.0 * (200 - 300), c.inX, what = "inX")
        assertNear(2.0 / 3.0 * (100 - 400), c.inY, what = "inY")
        assertNear(2.0 / 3.0 * (400 - 300), c.outX, what = "outX")
        assertNear(2.0 / 3.0 * (100 - 400), c.outY, what = "outY")
        // The open ends are the spline's ends, smooth.
        assertEquals(100f, sub.anchors[0].x); assertEquals(400f, sub.anchors[0].y)
        assertEquals(500f, sub.anchors[2].x); assertEquals(400f, sub.anchors[2].y)
    }

    @Test
    fun aClosedSplinesCornerIsAnchorZero() {
        val s = spline(3, n = 6, cyclic = true)
        val sub = SplineBezier.toSubpath(s)
        assertTrue(sub.closed)
        // One clamped cubic piece of 7 points: 4 spans.
        assertEquals(4, sub.anchors.size)
        assertEquals(listOf(true, false, false, false), sub.anchors.map { it.sharp })
        val c = sub.anchors[0]
        val q = s.points
        assertEquals(q[3].x, c.x); assertEquals(q[3].y, c.y); assertEquals(q[3].width, c.width)
        // A clamped uniform cubic's end handles reach its second and second-to-last points.
        assertNear((q[4].x - q[3].x).toDouble(), c.outX, what = "outX")
        assertNear((q[4].y - q[3].y).toDouble(), c.outY, what = "outY")
        assertNear((q[2].x - q[3].x).toDouble(), c.inX, what = "inX")
        assertNear((q[2].y - q[3].y).toDouble(), c.inY, what = "inY")
    }

    @Test
    fun aClosedSplineWithFewSpansStillHasThreeAnchors() {
        // One corner on 3 points: one clamped cubic piece of 4 points, 1 span, cut in 3.
        val s = spline(1, n = 3, order = 4, cyclic = true)
        val sub = SplineBezier.toSubpath(s)
        assertTrue(sub.closed)
        assertEquals(listOf(true, false, false), sub.anchors.map { it.sharp })
        val piece = NurbsGeometry.pieces(s).single()
        for (j in 1..2) {
            val at = NurbsGeometry.pointAt(piece, j / 3.0)
            val a = sub.anchors[j]
            assertNear(at[0], a.x, 1e-3, "x$j"); assertNear(at[1], a.y, 1e-3, "y$j"); assertNear(at[2], a.width, 1e-4, "width$j")
        }
        assertEquals(3, CurveGeometry.toPath(VectorOps.curveAnchors(sub), true, 0f, false).ops.count { it is PathOp.CubicTo })
        // Two corners on 4 points: two quadratic pieces of 1 span each, cut in 2.
        val t = SplineBezier.toSubpath(spline(0, 2, n = 4, order = 3, cyclic = true))
        assertEquals(listOf(true, false, true, false), t.anchors.map { it.sharp })
    }

    @Test
    fun twoAdjacentSharpPointsAreJoinedByAStraightSegment() {
        // Pieces 0..2 (quadratic), 2..3 (2 points: a segment), 3..5 (quadratic).
        val s = spline(2, 3, n = 6, order = 4)
        val sub = SplineBezier.toSubpath(s)
        assertEquals(listOf(false, true, true, false), sub.anchors.map { it.sharp })
        val a = sub.anchors[1]
        val b = sub.anchors[2]
        val dx = (b.x - a.x).toDouble(); val dy = (b.y - a.y).toDouble()
        assertNear(dx / 3, a.outX); assertNear(dy / 3, a.outY)
        assertNear(-dx / 3, b.inX); assertNear(-dy / 3, b.inY)
        val ops = CurveGeometry.toPath(VectorOps.curveAnchors(sub), sub.closed, 0f, false).ops
        assertTrue(ops[2] is PathOp.LineTo)
        assertTrue(ops[1] is PathOp.CubicTo)
        assertTrue(ops[3] is PathOp.CubicTo)
    }

    @Test
    fun aClosedSplineOfSharpPointsIsItsPolygon() {
        val p = listOf(pt(100f, 100f, sharp = true), pt(400f, 100f, sharp = true), pt(250f, 350f, sharp = true))
        val sub = SplineBezier.toSubpath(VSpline(p, order = 4, cyclic = true))
        assertTrue(sub.closed)
        assertEquals(p.map { Vec2(it.x, it.y) }, sub.anchors.map { Vec2(it.x, it.y) })
        assertTrue(sub.anchors.all { it.sharp })
        val ops = CurveGeometry.toPath(VectorOps.curveAnchors(sub), true, 0f, false).ops
        assertEquals(3, ops.count { it is PathOp.LineTo })
    }

    @Test
    fun withCornersAnOpenSplineWithEndpointOffIsClampedToo() {
        val s = spline(3, n = 6, endpoint = false)
        val sub = SplineBezier.toSubpath(s)
        assertEquals(s.points.first().x, sub.anchors.first().x, 1e-3f)
        assertEquals(s.points.first().y, sub.anchors.first().y, 1e-3f)
        assertEquals(s.points.last().x, sub.anchors.last().x, 1e-3f)
        assertEquals(s.points.last().y, sub.anchors.last().y, 1e-3f)
    }

    @Test
    fun theJoinedCurveFollowsEachPieceWithinTheTolerance() {
        for (seed in 1..60) {
            val rnd = Random(seed)
            val n = rnd.nextInt(3, 14)
            val pts = SplineTestSupport.randomPoints(rnd, n, weights = rnd.nextBoolean(), widths = true, minWeight = 0.3)
                .map { if (rnd.nextInt(3) == 0) it.copy(sharp = true) else it }
            val s = VSpline(pts, order = rnd.nextInt(2, 7), endpoint = rnd.nextBoolean(), cyclic = rnd.nextBoolean())
            val sub = SplineBezier.toSubpath(s)
            val poly = SplineTestSupport.polyline(sub)
            val parts = NurbsGeometry.pieces(s.sanitized())
            for (part in parts) {
                if (part.points.size < 2) continue
                for (q in SplineTestSupport.splineSamples(part, 40)) {
                    val d = SplineTestSupport.distanceToPolyline(q, poly)
                    assertTrue("seed $seed: $d", d <= 2 * SplineBezier.DEFAULT_TOLERANCE + 0.01f)
                }
            }
            val corners = NurbsGeometry.cornerIndices(s.sanitized())
            if (s.effectiveOrder > 2) assertEquals("seed $seed", corners.size, sub.anchors.count { it.sharp })
        }
    }

    @Test
    fun theJoinedFormMatchesItsSplineAndTheSmoothOneNoLonger() {
        val s = spline(3, n = 7)
        val joined = VPath(id = 1L, subpaths = listOf(SplineBezier.toSubpath(s)), spline = s)
        assertTrue(SplineBezier.matches(joined))
        // A path stored before the point was made sharp (v1.6's form) no longer matches: the
        // tool reading it degrades it to a plain Bézier path (I9).
        val smooth = SplineBezier.toSubpath(s.copy(points = s.points.map { it.copy(sharp = false) }))
        assertFalse(SplineBezier.matches(VPath(id = 2L, subpaths = listOf(smooth), spline = s)))
        assertFalse(SplineBezier.structurallyEqual(smooth, joined.subpaths[0], 1f))
    }

    // ------------------------------------------------------------------ incremental

    @Test
    fun incrementalEqualsAFreshConversionBitForBitWithCorners() {
        val rnd = Random(7)
        val inc = SplineBezier.Incremental()
        var s = VSpline(SplineTestSupport.randomPoints(rnd, 12, weights = true, widths = true, minWeight = 0.3), order = 4)
        repeat(400) { step ->
            val pts = s.points.toMutableList()
            when (rnd.nextInt(10)) {
                0, 1, 2 -> { val i = rnd.nextInt(pts.size); pts[i] = pts[i].copy(x = rnd.nextDouble(20.0, 980.0).toFloat(), y = rnd.nextDouble(20.0, 980.0).toFloat()) }
                3 -> { val i = rnd.nextInt(pts.size); pts[i] = pts[i].copy(sharp = !pts[i].sharp) }
                4 -> { val i = rnd.nextInt(pts.size); pts[i] = pts[i].copy(width = rnd.nextDouble(0.0, 3.0).toFloat()) }
                5 -> { val i = rnd.nextInt(pts.size); pts[i] = pts[i].copy(weight = rnd.nextDouble(0.3, 3.0).toFloat()) }
                6 -> if (pts.size < 20) pts.add(rnd.nextInt(pts.size + 1), SplineTestSupport.randomPoints(rnd, 1, widths = true)[0].copy(sharp = rnd.nextBoolean()))
                7 -> if (pts.size > 1) pts.removeAt(rnd.nextInt(pts.size))
                8 -> { s = s.copy(cyclic = !s.cyclic) }
                else -> { s = s.copy(order = rnd.nextInt(2, 7), endpoint = rnd.nextBoolean()) }
            }
            s = s.copy(points = pts)
            assertEquals("step $step", SplineBezier.toSubpath(s), inc.toSubpath(s))
        }
    }

    @Test
    fun incrementalReconvertsOnlyTheTouchedSpansOfAPiece() {
        val s = spline(7, 13, n = 20, order = 4)
        val inc = SplineBezier.Incremental()
        inc.toSubpath(s)
        // Pieces of 8, 7 and 7 points: 5 + 4 + 4 spans.
        assertEquals(13, inc.lastConverted)
        val moved = s.copy(points = s.points.toMutableList().also { it[1] = it[1].copy(x = it[1].x + 5f) })
        assertEquals(SplineBezier.toSubpath(moved), inc.toSubpath(moved))
        assertEquals(2, inc.lastConverted)
        // Moving a corner touches the pieces on both sides (one span each: the corner is an end).
        val corner = moved.copy(points = moved.points.toMutableList().also { it[7] = it[7].copy(y = it[7].y + 5f) })
        assertEquals(SplineBezier.toSubpath(corner), inc.toSubpath(corner))
        assertEquals(1 + 1, inc.lastConverted)
        // Making a point sharp changes the pieces: everything is converted.
        val sharper = corner.copy(points = corner.points.toMutableList().also { it[3] = it[3].copy(sharp = true) })
        assertEquals(SplineBezier.toSubpath(sharper), inc.toSubpath(sharper))
        assertEquals(1 + 2 + 4 + 4, inc.lastConverted)
    }

    @Test
    fun cleaningAPointKeepsItSharp() {
        val p = VSplinePoint(Float.NaN, 5f, weight = 50f, sharp = true)
        assertTrue(SplineEditing.clean(p).sharp)
    }

    companion object {
        /** [v16Digest] captured on v1.6 (before F3). */
        const val V16_EXACT = 0x7a41c71dd8eea33bL
        const val V16_ALL = 0x00cf608cda23b977L

        /** The splines the v1.6 digest covers: every order, Cyclic and Endpoint, 0..40 points. */
        fun digestSplines(exactOnly: Boolean): List<VSpline> {
            val out = ArrayList<VSpline>()
            for (seed in 1..240) {
                val rnd = Random(seed * 104729L)
                val order = if (exactOnly) rnd.nextInt(2, 5) else rnd.nextInt(VSpline.MIN_ORDER, VSpline.MAX_ORDER + 1)
                val n = rnd.nextInt(0, 41)
                val weights = !exactOnly && rnd.nextBoolean()
                out += VSpline(
                    SplineTestSupport.randomPoints(rnd, n, weights = weights, widths = rnd.nextBoolean()),
                    order = order, endpoint = rnd.nextBoolean(), cyclic = rnd.nextBoolean(),
                )
            }
            return out
        }

        private const val FNV_OFFSET = -0x340d631b7bdddcdbL
        private const val FNV_PRIME = 0x100000001b3L

        private fun mix(h: Long, v: Long): Long {
            var x = h
            for (k in 0 until 8) x = (x xor ((v ushr (8 * k)) and 0xFF)) * FNV_PRIME
            return x
        }

        private fun bits(v: Float?): Long = if (v == null) Long.MIN_VALUE else java.lang.Float.floatToRawIntBits(v).toLong()

        /** FNV-1a over every anchor's kind and the raw bits of its numbers. */
        fun digest(subpaths: List<VSubpath>): Long {
            var h = FNV_OFFSET
            for (s in subpaths) {
                h = mix(h, s.anchors.size.toLong())
                h = mix(h, if (s.closed) 1L else 0L)
                for (a in s.anchors) {
                    h = mix(h, if (a.sharp) 1L else 0L)
                    for (f in listOf(a.x, a.y, a.inX, a.inY, a.outX, a.outY, a.width)) h = mix(h, bits(f))
                }
            }
            return h
        }

        /** The digest of the conversion of [digestSplines]. */
        fun v16Digest(exactOnly: Boolean): Long = digest(digestSplines(exactOnly).map { SplineBezier.toSubpath(it) })
    }
}
