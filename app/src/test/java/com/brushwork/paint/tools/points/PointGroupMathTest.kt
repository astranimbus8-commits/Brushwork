package com.brushwork.paint.tools.points

import android.graphics.RectF
import com.brushwork.paint.core.Affine2
import com.brushwork.paint.core.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** v1.7 (item 1, design §3.1): bounds, group maps, the marquee and the pinch of a point group. */
@RunWith(RobolectricTestRunner::class)
class PointGroupMathTest {
    private val pts = listOf(Vec2(10.1f, 20.3f), Vec2(-5f, 7.7f), Vec2(30.25f, -1.5f), Vec2(0f, 0f), Vec2(-0f, 3f))

    private fun near(e: Vec2, a: Vec2, tol: Float = 1e-3f) {
        assertEquals("x of $a", e.x, a.x, tol)
        assertEquals("y of $a", e.y, a.y, tol)
    }

    @Test
    fun boundsOfTheSelectedPoints() {
        val r = PointGroupMath.bounds(pts, PointSelection.of(pts.size, 0, 2))!!
        assertEquals(RectF(10.1f, -1.5f, 30.25f, 20.3f), r)
        assertNull(PointGroupMath.bounds(pts, PointSelection.none(pts.size)))
        // A selection over more points than the list: the missing ones are ignored.
        assertEquals(RectF(-5f, 7.7f, -5f, 7.7f), PointGroupMath.bounds(pts, PointSelection.of(9, 1, 8)))
    }

    @Test
    fun identityLeavesPointsBitwiseUnchanged() {
        val all = PointSelection.all(pts.size)
        val out = PointGroupMath.mapped(pts, all, Affine2.IDENTITY)
        assertEquals(pts.size, out.size)
        for (i in pts.indices) {
            assertEquals(java.lang.Float.floatToRawIntBits(pts[i].x), java.lang.Float.floatToRawIntBits(out[i].x))
            assertEquals(java.lang.Float.floatToRawIntBits(pts[i].y), java.lang.Float.floatToRawIntBits(out[i].y))
        }
        // An identity built by composition is the identity, so it is exact too.
        val composed = Affine2.translate(3f, -2f) * Affine2.translate(-3f, 2f)
        assertEquals(Affine2.IDENTITY, composed)
        assertEquals(pts, PointGroupMath.mapped(pts, all, composed))
    }

    @Test
    fun mapsOnlyTheSelectedPoints() {
        val sel = PointSelection.of(pts.size, 1, 3)
        val out = PointGroupMath.mapped(pts, sel, Affine2.translate(1f, 2f))
        assertEquals(pts[0], out[0])
        assertEquals(Vec2(-4f, 9.7f), out[1])
        assertEquals(pts[2], out[2])
        assertEquals(Vec2(1f, 2f), out[3])
    }

    @Test
    fun marqueeTakesThePointsInside() {
        assertEquals(listOf(0, 3, 4), PointGroupMath.inside(pts, RectF(-1f, 0f, 11f, 21f)))
        // Dragged up and to the left: the same rectangle.
        assertEquals(listOf(0, 3, 4), PointGroupMath.inside(pts, RectF(11f, 21f, -1f, 0f)))
        // Edges count.
        assertEquals(listOf(1), PointGroupMath.inside(pts, RectF(-5f, 7.7f, -5f, 7.7f)))
        assertTrue(PointGroupMath.inside(pts, RectF(100f, 100f, 200f, 200f)).isEmpty())
    }

    @Test
    fun pinchScalesAndTurnsAboutTheBoxCentre() {
        val pivot = Vec2(50f, 50f)
        // Fingers spread twice as far apart about a fixed centroid: a ×2 about the pivot.
        val m = PointGroupMath.pinch(Vec2(40f, 50f), Vec2(60f, 50f), Vec2(30f, 50f), Vec2(70f, 50f), pivot)
        near(pivot, m.map(pivot))
        near(Vec2(70f, 50f), m.map(Vec2(60f, 50f)))
        assertEquals(4f, m.det, 1e-4f)
        // Turned a quarter: +90° about the pivot.
        val r = PointGroupMath.pinch(Vec2(40f, 50f), Vec2(60f, 50f), Vec2(50f, 40f), Vec2(50f, 60f), pivot)
        near(Vec2(50f, 60f), r.map(Vec2(60f, 50f)))
        // The centroid's move translates.
        val t = PointGroupMath.pinch(Vec2(40f, 50f), Vec2(60f, 50f), Vec2(45f, 53f), Vec2(65f, 53f), pivot)
        near(Vec2(15f, 13f), t.map(Vec2(10f, 10f)))
        // Fingers that started on one spot only move.
        val same = PointGroupMath.pinch(Vec2(1f, 1f), Vec2(1f, 1f), Vec2(3f, 1f), Vec2(5f, 1f), pivot)
        assertEquals(Affine2.translate(3f, 0f), same)
    }
}
