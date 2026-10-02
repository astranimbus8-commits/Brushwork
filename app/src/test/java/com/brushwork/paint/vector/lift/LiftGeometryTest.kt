package com.brushwork.paint.vector.lift

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.transform.TransformState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * v1.5 A2: the document map of a transform of lifted objects equals what the Transform tool shows
 * (the source rect's corners land on the state's corners, also distorted), and pure whole-pixel
 * moves are recognised for the cache-shift fast path.
 */
class LiftGeometryTest {
    private val left = 37
    private val top = 21
    private val w = 120
    private val h = 80
    private val start = TransformState.identity(left, top, w, h)

    private val sourceCorners = listOf(
        Vec2(left.toFloat(), top.toFloat()), Vec2((left + w).toFloat(), top.toFloat()),
        Vec2((left + w).toFloat(), (top + h).toFloat()), Vec2(left.toFloat(), (top + h).toFloat()),
    )

    private fun assertMapsCorners(st: TransformState, eps: Float = 1e-3f) {
        val m = LiftGeometry.matrix(st, left, top)
        assertNotNull(m)
        m!!
        val expected = st.corners()
        for (i in 0 until 4) {
            val q = LiftGeometry.map(m, sourceCorners[i].x, sourceCorners[i].y)
            assertEquals("corner $i x", expected[i].x, q.x, eps)
            assertEquals("corner $i y", expected[i].y, q.y, eps)
        }
    }

    @Test
    fun theUntouchedLiftIsTheIdentity() {
        val m = LiftGeometry.matrix(start, left, top)!!
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), m.map { if (it == -0f) 0f else it }.toFloatArray(), 1e-6f)
        assertArrayEquals(intArrayOf(0, 0), LiftGeometry.wholePixelShift(m))
    }

    @Test
    fun movesScalesRotationsAndFlipsLandOnTheBoxCorners() {
        assertMapsCorners(start.translated(10f, -3f))
        assertMapsCorners(start.scaledAbout(Vec2(50f, 40f), 2f, 0.5f))
        assertMapsCorners(start.rotatedAbout(start.center(), 30f))
        assertMapsCorners(start.flipped(horizontal = true).translated(5.5f, 2f))
        assertMapsCorners(start.rotatedAbout(Vec2(0f, 0f), -135f).scaledAbout(Vec2(10f, 10f), 1.7f, 1.7f))
    }

    @Test
    fun aDistortIsAHomographyThroughTheFourCorners() {
        val c = start.corners()
        val quad = listOf(c[0] + Vec2(15f, 10f), c[1] + Vec2(-5f, 0f), c[2] + Vec2(20f, 25f), c[3])
        val st = start.withCorners(quad)!!
        assertTrue(st.isDistorted)
        assertMapsCorners(st, 2e-3f)
        val m = LiftGeometry.matrix(st, left, top)!!
        assertTrue("perspective terms", m[6] != 0f || m[7] != 0f)
        assertEquals(1f, m[8], 1e-6f)
        // Straight lines stay straight: the middle of the top edge lands on the top edge of the quad.
        val mid = LiftGeometry.map(m, left + w / 2f, top.toFloat())
        val a = quad[0]; val b = quad[1]
        val cross = (b.x - a.x) * (mid.y - a.y) - (b.y - a.y) * (mid.x - a.x)
        assertEquals(0f, cross / (b - a).length, 1e-3f)
        // A distorted then moved box still maps through its (moved) corners.
        assertMapsCorners(st.translated(12f, 7f), 2e-3f)
        // A parallelogram distort is affine.
        val shear = start.withCorners(listOf(c[0] + Vec2(20f, 0f), c[1] + Vec2(20f, 0f), c[2], c[3]))!!
        val ms = LiftGeometry.matrix(shear, left, top)!!
        assertEquals(0f, ms[6], 1e-7f)
        assertEquals(0f, ms[7], 1e-7f)
        assertMapsCorners(shear)
    }

    @Test
    fun wholePixelMovesAreRecognised() {
        val moved = LiftGeometry.matrix(start.translated(256f, -40f), left, top)!!
        assertArrayEquals(intArrayOf(256, -40), LiftGeometry.wholePixelShift(moved))
        // Float noise on a whole-pixel move still counts; half pixels, scales and turns don't.
        assertArrayEquals(intArrayOf(3, 4), LiftGeometry.wholePixelShift(floatArrayOf(1f, 0f, 3.0002f, 0f, 1f, 3.9999f, 0f, 0f, 1f)))
        assertNull(LiftGeometry.wholePixelShift(LiftGeometry.matrix(start.translated(10.5f, 0f), left, top)!!))
        assertNull(LiftGeometry.wholePixelShift(LiftGeometry.matrix(start.scaledAbout(start.center(), 2f, 2f), left, top)!!))
        assertNull(LiftGeometry.wholePixelShift(LiftGeometry.matrix(start.rotatedAbout(start.center(), 90f), left, top)!!))
        assertNull(LiftGeometry.wholePixelShift(LiftGeometry.matrix(start.flipped(true), left, top)!!))
        assertNull(LiftGeometry.wholePixelShift(floatArrayOf(1f, 0f, 3f)))
    }

    @Test
    fun aFlipMirrorsTheGeometry() {
        val m = LiftGeometry.matrix(start.flipped(horizontal = true), left, top)!!
        val det = m[0] * m[4] - m[1] * m[3]
        assertTrue(det < 0f)
        val p = LiftGeometry.map(m, left.toFloat(), top + 10f)
        assertEquals((left + w).toFloat(), p.x, 1e-4f)
        assertEquals(top + 10f, p.y, 1e-4f)
        assertNotEquals(0f, abs(det))
    }

    @Test
    fun translationIsExact() {
        val m = LiftGeometry.translation(16f, -8f)
        val p = LiftGeometry.map(m, 1.25f, 3.5f)
        assertEquals(17.25f, p.x, 0f)
        assertEquals(-4.5f, p.y, 0f)
    }
}
