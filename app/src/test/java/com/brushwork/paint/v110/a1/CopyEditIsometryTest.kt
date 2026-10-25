package com.brushwork.paint.v110.a1

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.v110.a1.A1Kit.apply
import com.brushwork.paint.v110.a1.A1Kit.identity
import com.brushwork.paint.v110.a1.A1Kit.mirrorX
import com.brushwork.paint.v110.a1.A1Kit.mul
import com.brushwork.paint.v110.a1.A1Kit.translate
import com.brushwork.paint.v110.a1.A1Kit.turn
import com.brushwork.paint.v110.a1.A1Kit.turnAbout
import com.brushwork.paint.vector.copies.CopyEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.10 (R1 (a), LA4): the geometry under a picked side, pure (no Robolectric). A turn, a flip, a
 * move and their mixes are isometries and a 1 % resize, stretch or skew is not. The nearest
 * isometry of a drifted turn is that turn and of a drifted flip that flip (the det < 0 branch of
 * the polar decomposition), with the pivot going where the drifted map takes it; an exact
 * isometry comes back as it is. Coinciding maps merge; a map `StrokeCopies.isUsable` rejects
 * refuses the whole list.
 */
class CopyEditIsometryTest {

    /** A scale by ([sx], [sy]) about ([px], [py]). */
    private fun scaleAbout(px: Float, py: Float, sx: Float, sy: Float) =
        floatArrayOf(sx, 0f, px - sx * px, 0f, sy, py - sy * py, 0f, 0f, 1f)

    private fun det(m: FloatArray) = m[0] * m[4] - m[1] * m[3]

    /** The linear parts of [a] and [b] agree within [tol]. */
    private fun sameLinear(what: String, a: FloatArray, b: FloatArray, tol: Float = 1e-5f) {
        for (i in intArrayOf(0, 1, 3, 4)) assertEquals("$what [$i]: ${a.toList()} vs ${b.toList()}", a[i], b[i], tol)
    }

    @Test
    fun turnsFlipsAndMovesAreIsometriesAndResizesAreNot() {
        val isometries = listOf(
            "identity" to identity(),
            "move" to translate(37f, -12f),
            "turn" to turn(33.0),
            "flip" to mirrorX(),
            "turn, flip and move" to mul(translate(5f, 9f), mul(turn(-71.0), mirrorX())),
        )
        for ((what, m) in isometries) assertTrue(what, CopyEdit.isIsometry(m))
        assertTrue("a drift within the tolerance", CopyEdit.isIsometry(mul(turn(20.0), scaleAbout(0f, 0f, 1.00002f, 0.99998f))))
        assertFalse("1 % larger", CopyEdit.isIsometry(mul(turn(20.0), scaleAbout(0f, 0f, 1.01f, 1.01f))))
        assertFalse("stretched", CopyEdit.isIsometry(scaleAbout(100f, 50f, 1f, 0.99f)))
        assertFalse("skewed", CopyEdit.isIsometry(floatArrayOf(1f, 0.01f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)))
        assertFalse("not affine", CopyEdit.isIsometry(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0.001f, 0f, 1f)))
    }

    @Test
    fun aDriftSnapsToTheTurnOrTheFlipItHoldsAndThePivotStays() {
        val p = Vec2(300f, 140f)
        val move = translate(40f, -15f)
        val drift = scaleAbout(p.x, p.y, 1.03f, 0.98f)

        // A 25° turn with a 3 % / −2 % drift: the 25° turn, the pivot where the drifted map puts it.
        val turned = mul(move, mul(turnAbout(p.x, p.y, 25.0), drift))
        val a = CopyEdit.nearestIsometry(turned, p)
        assertTrue(CopyEdit.isIsometry(a))
        assertEquals("a turn", 1f, det(a), 1e-5f)
        sameLinear("the turn", turn(25.0), a)
        val at = apply(a, p)
        val want = apply(turned, p)
        assertEquals(want.x, at.x, 1e-3f)
        assertEquals(want.y, at.y, 1e-3f)

        // A turned flip with the same drift: the turned flip (det < 0).
        val flipped = mul(move, mul(turnAbout(p.x, p.y, 25.0), mul(mirrorX(), drift)))
        val b = CopyEdit.nearestIsometry(flipped, p)
        assertTrue(CopyEdit.isIsometry(b))
        assertEquals("a flip", -1f, det(b), 1e-5f)
        sameLinear("the turned flip", mul(turn(25.0), mirrorX()), b)
        val bt = apply(b, p)
        val bw = apply(flipped, p)
        assertEquals(bw.x, bt.x, 1e-3f)
        assertEquals(bw.y, bt.y, 1e-3f)

        // An exact isometry comes back as it is, whatever the pivot.
        val exact = mul(translate(5f, 9f), mul(turn(-71.0), mirrorX()))
        val c = CopyEdit.nearestIsometry(exact, Vec2(17f, 410f))
        sameLinear("unchanged", exact, c)
        assertEquals(exact[2], c[2], 1e-3f)
        assertEquals(exact[5], c[5], 1e-3f)
    }

    @Test
    fun coincidingMapsMergeAndUnusableOnesAreRefused() {
        val a = turn(120.0)
        val b = turn(240.0)
        val nearA = a.copyOf().also { it[0] += 5e-5f }
        val nearIdentity = identity().also { it[5] += 5e-5f }
        val kept = CopyEdit.merged(listOf(identity(), a, nearA, nearIdentity, b))!!
        assertEquals("the identity, a and b", 3, kept.size)
        assertSame(a, kept[1])
        assertSame(b, kept[2])

        assertNull("not finite", CopyEdit.merged(listOf(identity(), floatArrayOf(Float.NaN, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f))))
        assertNull("a coefficient past 1e7", CopyEdit.merged(listOf(identity(), translate(2e7f, 0f))))
        assertNull("flat (det 0)", CopyEdit.merged(listOf(identity(), floatArrayOf(1f, 2f, 0f, 2f, 4f, 0f, 0f, 0f, 1f))))
        assertNull("not affine", CopyEdit.merged(listOf(identity(), floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0.5f, 0f, 1f))))
    }
}
