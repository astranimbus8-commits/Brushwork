package com.brushwork.paint.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.7 (design §4.2, F4): the affine map the point editors, the gizmo and ShapeAffine share. */
class Affine2Test {

    private fun near(e: Vec2, a: Vec2, tol: Float = 1e-4f) {
        assertEquals("x of $a", e.x, a.x, tol)
        assertEquals("y of $a", e.y, a.y, tol)
    }

    @Test
    fun mapsInMatrixConvention() {
        val m = Affine2(a = 2f, b = 3f, c = 5f, d = 7f, tx = 11f, ty = 13f)
        // x' = a·x + c·y + tx, y' = b·x + d·y + ty
        assertEquals(Vec2(2f + 5f + 11f, 3f + 7f + 13f), m.map(Vec2(1f, 1f)))
        assertEquals(Vec2(2f + 5f, 3f + 7f), m.mapVector(Vec2(1f, 1f)))
        assertArrayEquals(floatArrayOf(2f, 5f, 11f, 3f, 7f, 13f, 0f, 0f, 1f), m.toArray9(), 0f)
        assertEquals(m, Affine2.fromArray9(m.toArray9()))
        assertNull(Affine2.fromArray9(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0.1f, 0f, 1f)))
        assertEquals(2f * 7f - 3f * 5f, m.det, 0f)
    }

    @Test
    fun identityIsExact() {
        val ps = listOf(Vec2(0.1f, -3.7f), Vec2(1e7f, 1e-7f), Vec2(-123.456f, 789.01f))
        for (p in ps) {
            assertEquals(p, Affine2.IDENTITY.map(p))
            assertEquals(p, Affine2.IDENTITY.mapVector(p))
        }
        assertEquals(Affine2.IDENTITY, Affine2.IDENTITY * Affine2.IDENTITY)
        assertEquals(Affine2.IDENTITY, Affine2.IDENTITY.inverse())
        assertTrue(Affine2.IDENTITY.isIdentity)
        // Gate 2 review: -0 counts as 0 (a turn by 0 has c = -0f, a data-class inequality).
        val still = Affine2.rotateAbout(Vec2(30f, -40f), 0f)
        assertTrue(still.isIdentity)
        assertTrue(Affine2.rotateAbout(Vec2(30f, -40f), 360f).isIdentity)
        assertTrue(Affine2.scaleAbout(Vec2(7f, 9f), 1f, 1f).isIdentity)
        assertTrue(Affine2(b = -0f, c = -0f, tx = -0f, ty = -0f).isIdentity)
        assertTrue(!Affine2.translate(0f, 1e-6f).isIdentity)
        assertTrue(!Affine2.rotateAbout(Vec2.ZERO, 1e-3f).isIdentity)
    }

    @Test
    fun rotationTurnsLikeMatrixSetRotate() {
        // (1, 0) -> (0, 1) for +90° (clockwise on screen, y down); exact at multiples of 90°.
        assertEquals(Vec2(0f, 1f), Affine2.rotateAbout(Vec2.ZERO, 90f).map(Vec2(1f, 0f)))
        assertEquals(Vec2(-1f, 0f), Affine2.rotateAbout(Vec2.ZERO, 180f).map(Vec2(1f, 0f)))
        assertEquals(Vec2(0f, -1f), Affine2.rotateAbout(Vec2.ZERO, -90f).map(Vec2(1f, 0f)))
        assertEquals(Vec2(0f, -1f), Affine2.rotateAbout(Vec2.ZERO, 270f).map(Vec2(1f, 0f)))
        // About a pivot: the pivot stays put.
        val r = Affine2.rotateAbout(Vec2(10f, 20f), 37f)
        near(Vec2(10f, 20f), r.map(Vec2(10f, 20f)))
        near(Vec2(10f + 5f * kotlin.math.cos(Math.toRadians(37.0)).toFloat(), 20f + 5f * kotlin.math.sin(Math.toRadians(37.0)).toFloat()), r.map(Vec2(15f, 20f)))
        assertEquals(1f, r.det, 1e-6f)
    }

    @Test
    fun scaleAboutAPivotAndAnAxis() {
        val s = Affine2.scaleAbout(Vec2(10f, 10f), 2f, 3f)
        assertEquals(Vec2(10f, 10f), s.map(Vec2(10f, 10f)))
        assertEquals(Vec2(12f, 13f), s.map(Vec2(11f, 11f)))
        // Along a 90° axis the factors swap.
        val t = Affine2.scaleAbout(Vec2.ZERO, 2f, 3f, axisDeg = 90f)
        near(Vec2(3f, 2f), t.map(Vec2(1f, 1f)))
        // Along 45°: (1, 1) is on the axis and doubles, (1, -1) is across it and triples.
        val u = Affine2.scaleAbout(Vec2.ZERO, 2f, 3f, axisDeg = 45f)
        near(Vec2(2f, 2f), u.map(Vec2(1f, 1f)))
        near(Vec2(3f, -3f), u.map(Vec2(1f, -1f)))
        assertEquals(6f, u.det, 1e-5f)
    }

    @Test
    fun compositionAndInverse() {
        val a = Affine2.rotateAbout(Vec2(3f, 4f), 30f)
        val b = Affine2.scaleAbout(Vec2(-2f, 5f), 1.5f, 0.5f) * Affine2.translate(7f, -1f)
        val p = Vec2(12.5f, -8.25f)
        near(a.map(b.map(p)), (a * b).map(p))
        val inv = (a * b).inverse()!!
        near(p, inv.map((a * b).map(p)), 1e-3f)
        near(p, ((a * b) * inv).map(p), 1e-3f)
        assertNull(Affine2(a = 0f, d = 5f).inverse())
        assertNull(Affine2(a = Float.NaN).inverse())
        assertNull(Affine2.scaleAbout(Vec2.ZERO, 0f, 1f).inverse())
        // A mirror has a negative determinant.
        assertTrue(Affine2.scaleAbout(Vec2.ZERO, -1f, 1f).det < 0f)
    }
}
