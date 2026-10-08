package com.brushwork.paint.tools.vector.spline

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.vector.CurveGeometry
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSpline
import com.brushwork.paint.vector.VSplinePoint
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

/**
 * v1.6 §3.2(c, d) and I9: properties of the Bézier form. Clamped ends hit the end points;
 * cyclic seams are C² (exact cubics); the conversion commutes with affine maps; the order is
 * clamped to the point count; damaged input (NaN, out-of-range weights, too few points) is
 * sanitized; widths blend; and the I9 check accepts the conversion (also an affine-mapped
 * approximation) and refuses a stale spline.
 */
@RunWith(RobolectricTestRunner::class)
class SplineInvariantsTest {

    private val rnd = Random(2024)

    private fun near(a: Vec2, b: Vec2, tol: Float, what: String) {
        assertEquals("$what x", a.x, b.x, tol)
        assertEquals("$what y", a.y, b.y, tol)
    }

    @Test
    fun clampedEndsHitTheEndPointsWithMirroredSmoothHandles() {
        for (order in 2..6) {
            val pts = SplineTestSupport.randomPoints(rnd, 8, weights = true)
            val sub = SplineBezier.toSubpath(VSpline(pts, order))
            near(Vec2(pts.first().x, pts.first().y), Vec2(sub.anchors.first().x, sub.anchors.first().y), 1e-3f, "order $order start")
            near(Vec2(pts.last().x, pts.last().y), Vec2(sub.anchors.last().x, sub.anchors.last().y), 1e-3f, "order $order end")
            assertFalse(sub.closed)
            if (order > 2) {
                val a = sub.anchors.first()
                assertFalse("open ends are smooth", a.sharp)
                assertEquals("the unused in-handle mirrors the out-handle", -a.outX!!, a.inX!!, 1e-6f)
                assertEquals(-a.outY!!, a.inY!!, 1e-6f)
            }
        }
    }

    @Test
    fun cyclicSeamsAreC2() {
        for (order in 3..4) {
            val pts = SplineTestSupport.randomPoints(rnd, 7)
            val sub = SplineBezier.toSubpath(VSpline(pts, order, cyclic = true))
            assertTrue(sub.closed)
            val n = SplineTestSupport.segmentCount(sub)
            assertEquals(7, n)
            // Every joint, the seam (last segment → first) included: equal 1st derivatives, and
            // for cubics (order 4) equal 2nd derivatives (a quadratic B-spline is C¹ only).
            for (j in 0 until n) {
                val a = SplineTestSupport.segment(sub, (j - 1 + n) % n)
                val b = SplineTestSupport.segment(sub, j)
                near(a[3], b[0], 1e-3f, "order $order joint $j")
                val d1a = (a[3] - a[2]) * 3f
                val d1b = (b[1] - b[0]) * 3f
                near(d1a, d1b, 2e-3f, "order $order joint $j first derivative")
                if (order == 4) {
                    val d2a = (a[3] - a[2] * 2f + a[1]) * 6f
                    val d2b = (b[2] - b[1] * 2f + b[0]) * 6f
                    near(d2a, d2b, 5e-3f, "order $order joint $j second derivative")
                }
            }
        }
    }

    @Test
    fun theConversionCommutesWithAffineMaps() {
        val m = floatArrayOf(1.7f, -0.6f, 31f, 0.4f, 1.2f, -12f, 0f, 0f, 1f)
        fun map(p: Vec2) = Vec2(m[0] * p.x + m[1] * p.y + m[2], m[3] * p.x + m[4] * p.y + m[5])
        for (order in 2..4) {
            val s = VSpline(SplineTestSupport.randomPoints(rnd, 9), order, cyclic = order == 3)
            val direct = SplineBezier.toSubpath(s.mapped(m))
            val mapped = SplineBezier.toSubpath(s)
            assertEquals(mapped.anchors.size, direct.anchors.size)
            for (i in mapped.anchors.indices) {
                val a = mapped.anchors[i]
                val b = direct.anchors[i]
                near(map(Vec2(a.x, a.y)), Vec2(b.x, b.y), 2e-3f, "order $order anchor $i")
                if (a.outX != null) {
                    val o = map(Vec2(a.x + a.outX!!, a.y + a.outY!!))
                    near(o, Vec2(b.x + b.outX!!, b.y + b.outY!!), 2e-3f, "order $order handle $i")
                }
            }
        }
    }

    @Test
    fun theOrderIsClampedToThePointCount() {
        val pts = SplineTestSupport.randomPoints(rnd, 3)
        // Order 6 with 3 points is a quadratic: one exact span.
        val s = VSpline(pts, 6)
        assertEquals(3, s.effectiveOrder)
        val sub = SplineBezier.toSubpath(s)
        assertEquals(1, SplineTestSupport.segmentCount(sub))
        assertEquals(SplineBezier.toSubpath(VSpline(pts, 3)), sub)
        // Two points: a straight line with sharp ends; one point: one anchor; none: nothing.
        val two = SplineBezier.toSubpath(VSpline(pts.take(2), 4))
        assertEquals(2, two.anchors.size)
        assertTrue(two.anchors.all { it.sharp && it.inX == null && it.outX == null })
        assertEquals(1, SplineBezier.toSubpath(VSpline(pts.take(1), 4)).anchors.size)
        assertTrue(SplineBezier.toSubpath(VSpline(emptyList(), 4)).anchors.isEmpty())
        // Cyclic needs three points: two stay an open line.
        assertFalse(SplineBezier.toSubpath(VSpline(pts.take(2), 4, cyclic = true)).closed)
        // Order 2: the control polygon, corners at the points.
        val poly = SplineBezier.toSubpath(VSpline(pts, 2, cyclic = true))
        assertTrue(poly.closed)
        assertEquals(pts.map { Vec2(it.x, it.y) }, poly.anchors.map { Vec2(it.x, it.y) })
    }

    @Test
    fun degenerateAndDamagedInputIsSanitized() {
        val pts = listOf(
            VSplinePoint(10f, 10f, weight = 50f),
            VSplinePoint(Float.NaN, 5f),
            VSplinePoint(60f, Float.POSITIVE_INFINITY),
            VSplinePoint(100f, 40f, weight = Float.NaN, width = 9f),
            VSplinePoint(140f, 10f, weight = -3f),
        )
        val spline = VSpline(pts, 99)
        val clean = spline.sanitized()
        // NaN / infinite points dropped: 3 left (order 6, effectively 3: one span); weights held
        // to 0.1..10 (NaN → 1), so the span is rational: at most 16 pieces for its geometry, and
        // (v1.7 §3.5, its widths 1 / 3 / 1 vary) at most 32 in all.
        assertEquals(3, clean.points.size)
        assertEquals(VSpline.MAX_ORDER, clean.order)
        assertEquals(listOf(VSpline.MAX_WEIGHT, 1f, VSpline.MIN_WEIGHT), clean.points.map { it.weight })
        val sub = SplineBezier.toSubpath(spline)
        assertEquals(sub, SplineBezier.toSubpath(clean))
        assertTrue(SplineTestSupport.segmentCount(sub) in 1..SplineBezier.WIDTH_MAX_PIECES_PER_SPAN)
        for (a in sub.anchors) {
            assertTrue(a.x.isFinite() && a.y.isFinite() && a.inX!!.isFinite() && a.outY!!.isFinite())
            assertTrue(a.width in 0f..VSpline.MAX_WIDTH)
        }
        // Coincident points: a curve of zero length, still finite.
        val same = SplineBezier.toSubpath(VSpline(List(5) { VSplinePoint(7f, 7f) }, 4))
        assertTrue(same.anchors.all { it.x == 7f && it.y == 7f && it.outX == 0f })
    }

    @Test
    fun widthsBlendLikeTheSpline() {
        val pts = SplineTestSupport.randomPoints(rnd, 6).mapIndexed { i, p -> p.copy(width = if (i < 3) 0.5f else 2f) }
        val sub = SplineBezier.toSubpath(VSpline(pts, 4))
        assertEquals(0.5f, sub.anchors.first().width, 1e-4f)
        assertEquals(2f, sub.anchors.last().width, 1e-4f)
        val inner = sub.anchors.drop(1).dropLast(1)
        assertTrue(inner.all { it.width in 0.5f..2f })
        // Equal widths everywhere stay that width (no blending artefacts).
        val flat = SplineBezier.toSubpath(VSpline(pts.map { it.copy(width = 1.25f) }, 5))
        assertTrue(flat.anchors.all { kotlin.math.abs(it.width - 1.25f) < 1e-5f })
    }

    @Test
    fun interiorAnchorsAreSmoothWithBothHandlesSoTheCurveToolDrawsThemExactly() {
        val sub = SplineBezier.toSubpath(VSpline(SplineTestSupport.randomPoints(rnd, 9, weights = true), 5))
        val anchors = VectorOps.curveAnchors(sub)
        for (i in anchors.indices) {
            val a = sub.anchors[i]
            assertFalse(a.sharp)
            assertTrue(a.inX != null && a.inY != null && a.outX != null && a.outY != null)
            val (hIn, hOut) = CurveGeometry.handles(anchors, i, sub.closed, 0f)
            assertEquals(a.inX!!, hIn.x, 0f); assertEquals(a.outY!!, hOut.y, 0f)
        }
    }

    // ------------------------------------------------------------------ I9

    private fun pathOf(s: VSpline) = VPath(id = 1, subpaths = listOf(SplineBezier.toSubpath(s)), spline = s)

    @Test
    fun theI9CheckAcceptsTheConversionAndRefusesStaleSplines() {
        val s = VSpline(SplineTestSupport.randomPoints(rnd, 8), 4)
        val p = pathOf(s)
        assertTrue(SplineBezier.matches(p))
        assertFalse("no spline: a plain Bézier path", SplineBezier.matches(p.copy(spline = null)))
        // The Bézier form edited without clearing the spline (a bug or crafted data): refused.
        val moved = p.subpaths[0].anchors.toMutableList().also { it[3] = it[3].copy(x = it[3].x + 4f) }
        assertFalse(SplineBezier.matches(p.copy(subpaths = listOf(VSubpath(moved)))))
        val other = s.copy(points = s.points.toMutableList().also { it[2] = it[2].copy(y = it[2].y + 30f) })
        assertFalse(SplineBezier.matches(p.copy(spline = other)))
        assertFalse("two subpaths", SplineBezier.matches(p.copy(subpaths = p.subpaths + p.subpaths)))
        assertFalse(SplineBezier.matches(p.copy(spline = s.copy(cyclic = true))))
    }

    @Test
    fun affineMappedPathsKeepTheirSplineThroughTheI9Check() {
        // Exact conversions map exactly; approximations (weights, order 6) are mapped piece by
        // piece and may be cut differently from a fresh conversion: the geometric check keeps them.
        val scale2 = floatArrayOf(2f, 0f, 15f, 0f, 2f, -9f, 0f, 0f, 1f)
        val rotate = floatArrayOf(0f, -1.3f, 400f, 1.3f, 0f, 2f, 0f, 0f, 1f)
        val splines = listOf(
            VSpline(SplineTestSupport.randomPoints(rnd, 10), 4),
            VSpline(SplineTestSupport.randomPoints(rnd, 10, weights = true), 4),
            VSpline(SplineTestSupport.randomPoints(rnd, 9), 6, cyclic = true),
        )
        for (s in splines) for (m in listOf(scale2, rotate)) {
            val mapped = VectorOps.transformed(pathOf(s), m) as VPath
            assertTrue("the spline is mapped", mapped.spline != null)
            assertTrue("order ${s.order}: still the spline's form", SplineBezier.matches(mapped))
        }
        // A homography drops the spline (foundation sweep).
        val distort = floatArrayOf(1f, 0.1f, 0f, 0f, 1f, 0f, 0.0004f, 0.0002f, 1f)
        assertNull((VectorOps.transformed(pathOf(splines[0]), distort) as VPath).spline)
    }

    @Test
    fun structuralEqualityLooksAtEveryField() {
        val a = VSubpath(listOf(VAnchor(0f, 0f, inX = 1f, inY = 1f, outX = -1f, outY = -1f), VAnchor(10f, 0f, inX = 1f, inY = 0f, outX = -1f, outY = 0f)))
        assertTrue(SplineBezier.structurallyEqual(a, a.copy(), 0.01f))
        assertFalse(SplineBezier.structurallyEqual(a, a.copy(closed = true), 0.01f))
        assertFalse(SplineBezier.structurallyEqual(a, VSubpath(a.anchors.map { it.copy(sharp = true) }), 0.01f))
        assertFalse(SplineBezier.structurallyEqual(a, VSubpath(a.anchors.map { it.copy(outX = null) }), 0.01f))
        assertTrue(SplineBezier.structurallyEqual(a, VSubpath(a.anchors.map { it.copy(x = it.x + 0.005f) }), 0.01f))
        assertFalse(SplineBezier.structurallyEqual(a, VSubpath(a.anchors.map { it.copy(width = 2f) }), 0.01f))
    }
}
