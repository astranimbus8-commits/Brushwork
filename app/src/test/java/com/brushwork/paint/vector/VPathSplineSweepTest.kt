package com.brushwork.paint.vector

import com.brushwork.paint.vector.geom.ObjectMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.6 foundation (§4.2 sweep, V8, I9): the two copy sites of [VPath]. An affine transform maps
 * the Path tool's spline with the Bézier form (weights and widths unchanged); a homography
 * (Distort) and subdivision drop it, so the path becomes a plain Bézier path.
 */
@RunWith(RobolectricTestRunner::class)
class VPathSplineSweepTest {

    private val spline = VSpline(
        listOf(VSplinePoint(10f, 20f), VSplinePoint(50f, 80f, weight = 2f, width = 1.5f), VSplinePoint(90f, 20f, weight = 0.5f)),
        order = 3,
    )

    private val path = VPath(
        id = 1,
        subpaths = listOf(VSubpath(listOf(VAnchor(10f, 20f, outX = 20f, outY = 30f), VAnchor(90f, 20f, inX = -20f, inY = 30f)))),
        spline = spline,
    )

    private fun near(a: Float, b: Float) = assertEquals(a, b, 1e-3f)

    @Test
    fun affineTransformsMapTheSpline() {
        // Scale 2, rotate 90° (x, y) -> (-y, x), then move by (100, 5).
        val m = floatArrayOf(0f, -2f, 100f, 2f, 0f, 5f, 0f, 0f, 1f)
        val out = VectorOps.transformed(path, m) as VPath
        val s = assertNotNullSpline(out)
        assertEquals(spline.points.size, s.points.size)
        for (i in spline.points.indices) {
            val p = spline.points[i]
            val q = s.points[i]
            near(-2f * p.y + 100f, q.x)
            near(2f * p.x + 5f, q.y)
            assertEquals("weights unchanged", p.weight, q.weight, 0f)
            assertEquals("widths unchanged", p.width, q.width, 0f)
        }
        assertEquals(spline.order, s.order)
        assertEquals(spline.endpoint, s.endpoint)
        assertEquals(spline.cyclic, s.cyclic)
        // The Bézier form moved the same way.
        val a0 = out.subpaths[0].anchors[0]
        near(-2f * 20f + 100f, a0.x)
        near(2f * 10f + 5f, a0.y)
        // A plain move keeps it too.
        val moved = VectorOps.transformed(path, floatArrayOf(1f, 0f, 7f, 0f, 1f, -3f, 0f, 0f, 1f)) as VPath
        near(17f, assertNotNullSpline(moved).points[0].x)
        near(17f, moved.spline!!.points[0].y)
    }

    private fun assertNotNullSpline(p: VPath): VSpline {
        assertNotNull("the spline is kept under an affine map", p.spline)
        return p.spline!!
    }

    @Test
    fun homographiesAndSubdivisionDropTheSpline() {
        val distort = floatArrayOf(1f, 0.1f, 0f, 0f, 1f, 0f, 0.001f, 0.0005f, 1f)
        val out = VectorOps.transformed(path, distort) as VPath
        assertNull("Distort drops the spline", out.spline)
        assertNull("subdivided pieces are no longer the spline's form", ObjectMapping.subdivided(path).spline)
        // The other fields of the path are kept by the sweep.
        val sub = ObjectMapping.subdivided(path)
        assertEquals(path.id, sub.id)
        assertEquals(path.fillRule, sub.fillRule)
    }

    @Test
    fun mappedAndSanitizedAreConsistent() {
        val m = floatArrayOf(2f, 0f, 1f, 0f, 2f, 1f, 0f, 0f, 2f)
        // A homogeneous scale m[8] = 2 divides: (2x + 1) / 2.
        val s = spline.mapped(m)
        near((2f * 50f + 1f) / 2f, s.points[1].x)
        assertEquals(spline, spline.sanitized())
        assertEquals(3, spline.effectiveOrder)
        assertEquals(2, VSpline(listOf(VSplinePoint(0f, 0f), VSplinePoint(1f, 1f)), order = 4).effectiveOrder)
    }
}
