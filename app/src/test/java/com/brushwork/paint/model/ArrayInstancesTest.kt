package com.brushwork.paint.model

import android.graphics.RectF
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VStop
import com.brushwork.paint.vector.VSubpath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.8 F1 (item 2, I19): [ArrayInstances], the one source of per-copy matrices and looks. Without
 * a look every matrix is v1.7's bit for bit and no copy has a look; with one, copy k takes its
 * values at t_k = ease((k-1)/(n-2)) (t = 1 for n = 2) over the PLACED copies, grows and turns about
 * its own centre, and the source never changes. (Robolectric: RectF.)
 */
@RunWith(RobolectricTestRunner::class)
class ArrayInstancesTest {
    /** 40 × 20, centre (30, 30). */
    private val src = RectF(10f, 20f, 50f, 40f)
    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private val lGuide = VSubpath(listOf(VAnchor(0f, 0f), VAnchor(100f, 0f, sharp = true), VAnchor(100f, 100f)))

    private fun everyMode(count: Int) = listOf(
        ArraySpec(count = count),
        ArraySpec(mode = ArrayMode.CIRCLE, count = count),
        ArraySpec(mode = ArrayMode.CIRCLE, count = count, rotateCopies = false, sweepDeg = 120f),
        ArraySpec(mode = ArrayMode.CURVE, count = count, guide = lGuide),
        ArraySpec(mode = ArrayMode.TRANSFORM, count = count, turnDeg = 20f, scale = 0.9f),
    )

    private fun bits(m: FloatArray) = m.map { java.lang.Float.floatToRawIntBits(it) }

    private fun map(m: FloatArray, x: Float, y: Float) = floatArrayOf(m[0] * x + m[1] * y + m[2], m[3] * x + m[4] * y + m[5])

    private fun assertRect(msg: String, e: RectF, a: RectF, eps: Float = 0f) {
        assertEquals("$msg left", e.left, a.left, eps)
        assertEquals("$msg top", e.top, a.top, eps)
        assertEquals("$msg right", e.right, a.right, eps)
        assertEquals("$msg bottom", e.bottom, a.bottom, eps)
    }

    @Test
    fun withoutALookEveryMatrixIsV17sBitForBit() {
        val specs = everyMode(5) + everyMode(1) + everyMode(5).map { it.copy(look = ArrayLook.IDENTITY) } +
            listOf(ArraySpec(count = 4, look = ArrayLook(easing = ArrayEasing.EASE_IN)))
        for (s in specs) {
            val ms = ArrayLayout.matrices(s, src)
            val inst = ArrayInstances.of(s, src)
            assertEquals(s.toString(), (ms.size - 1 downTo 0).toList(), inst.map { it.k })
            for (i in inst) {
                assertEquals("${s.mode} copy ${i.k}", bits(ms[i.k]), bits(i.matrix))
                assertNull(i.look)
            }
            assertRect("${s.mode} bounds", ArrayLayout.bounds(s, src), ArrayInstances.bounds(s, src))
        }
    }

    @Test
    fun theSourceNeverChanges() {
        val look = ArrayLook(opacityStart = 0.1f, opacityEnd = 0.1f, sizeStart = 3f, sizeEnd = 3f, turnStart = 45f, turnEnd = 45f, tint = listOf(VStop(0f, -1), VStop(1f, -1)), tintStrength = 1f)
        for (s in everyMode(5)) {
            val inst = ArrayInstances.of(s.copy(look = look), src)
            val source = inst.last()
            assertEquals(0, source.k)
            assertTrue(identity.contentEquals(source.matrix))
            assertNull(source.look)
            assertTrue(inst.dropLast(1).all { it.look != null })
        }
    }

    @Test
    fun tForTwoThreeAndFiveCopiesUnderEachPace() {
        for (e in ArrayEasing.entries) {
            for (n in listOf(2, 3, 5)) {
                // Opacity 0 to 1: a copy's opacity is its t.
                val spec = ArraySpec(count = n, look = ArrayLook(opacityStart = 0f, opacityEnd = 1f, easing = e))
                val inst = ArrayInstances.of(spec, src).associateBy { it.k }
                assertEquals(n, inst.size)
                for (k in 1 until n) {
                    val t = if (n == 2) 1f else ArrayInstances.ease(e, (k - 1).toFloat() / (n - 2))
                    assertEquals("$e n=$n k=$k", t, inst.getValue(k).look!!.opacity, 1e-6f)
                }
                // The first copy takes the start values, the last the end values.
                if (n > 2) assertEquals(0f, inst.getValue(1).look!!.opacity, 0f)
                assertEquals(1f, inst.getValue(n - 1).look!!.opacity, 0f)
            }
        }
        // n = 5, copy 2: u = 1/3.
        val expected = mapOf(
            ArrayEasing.LINEAR to 1f / 3f, ArrayEasing.EASE_IN to 1f / 9f, ArrayEasing.EASE_OUT to 5f / 9f, ArrayEasing.EASE_IN_OUT to 7f / 27f,
        )
        for ((e, t) in expected) {
            val spec = ArraySpec(count = 5, look = ArrayLook(opacityStart = 0f, opacityEnd = 1f, easing = e))
            assertEquals("$e", t, ArrayInstances.of(spec, src).single { it.k == 2 }.look!!.opacity, 1e-5f)
        }
    }

    @Test
    fun curveModeCountsOnlyThePlacedCopies() {
        val straight = VSubpath(listOf(VAnchor(0f, 0f), VAnchor(300f, 0f)))
        // Every 120 px on a 300 px guide: 3 of the 5 are placed.
        val spec = ArraySpec(mode = ArrayMode.CURVE, count = 5, guide = straight, spacing = 120f, look = ArrayLook(opacityStart = 0f, opacityEnd = 1f))
        assertEquals(3, ArrayLayout.matrices(spec, src).size)
        val inst = ArrayInstances.of(spec, src)
        assertEquals(listOf(2, 1, 0), inst.map { it.k })
        assertEquals("the first placed copy starts", 0f, inst[1].look!!.opacity, 0f)
        assertEquals("the last placed copy ends", 1f, inst[0].look!!.opacity, 0f)
    }

    @Test
    fun aCopyGrowsAndTurnsAboutItsOwnCentre() {
        val plain = ArraySpec(count = 3)
        val ms = ArrayLayout.matrices(plain, src)
        val spec = plain.copy(look = ArrayLook(sizeStart = 2f, sizeEnd = 2f, turnStart = 90f, turnEnd = 90f))
        val inst = ArrayInstances.of(spec, src).associateBy { it.k }
        for (k in 1..2) {
            val m = inst.getValue(k).matrix
            val c = map(ms[k], 30f, 30f)
            val c2 = map(m, 30f, 30f)
            assertEquals("copy $k's centre stays", c[0], c2[0], 1e-3f)
            assertEquals(c[1], c2[1], 1e-3f)
            // One px right of the centre: turned a quarter (clockwise on screen) and doubled.
            val p = map(m, 31f, 30f)
            assertEquals(c[0], p[0], 1e-3f)
            assertEquals(c[1] + 2f, p[1], 1e-3f)
        }
        // Opacity or tint alone keeps v1.7's matrix itself.
        val faded = ArrayInstances.of(plain.copy(look = ArrayLook(opacityEnd = 0.5f)), src).associateBy { it.k }
        for (k in 0..2) assertEquals(bits(ms[k]), bits(faded.getValue(k).matrix))
    }

    @Test
    fun theBoundsCoverTheGrownCopies() {
        val plain = ArraySpec(count = 3)
        val grown = plain.copy(look = ArrayLook(sizeStart = 3f, sizeEnd = 3f))
        val a = ArrayLayout.bounds(plain, src)
        val b = ArrayInstances.bounds(grown, src)
        assertTrue("$b contains $a", b.left <= a.left && b.top <= a.top && b.right >= a.right && b.bottom >= a.bottom)
        assertTrue("taller: $b", b.height() > a.height())
        // The last copy, three times as big about its centre (110, 30): 60 px tall.
        assertEquals(0f, b.top, 1e-3f)
        assertEquals(60f, b.bottom, 1e-3f)
    }

    @Test
    fun theTintColorsSampleTheGradient() {
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF0000FF.toInt()
        val spec = ArraySpec(count = 3, look = ArrayLook(tint = listOf(VStop(0f, red), VStop(1f, blue)), tintStrength = 0.5f))
        val inst = ArrayInstances.of(spec, src).associateBy { it.k }
        assertEquals(red, inst.getValue(1).look!!.tintColor)
        assertEquals(blue, inst.getValue(2).look!!.tintColor)
        assertEquals(0.5f, inst.getValue(2).look!!.tintStrength, 0f)
        assertEquals(1f, inst.getValue(2).look!!.opacity, 0f)
        assertNotNull(inst.getValue(2).look!!.colorMatrix())
        // A tint without strength is none.
        val none = ArrayInstances.of(ArraySpec(count = 3, look = ArrayLook(opacityEnd = 0.5f, tint = listOf(VStop(0f, red), VStop(1f, blue)))), src)
        for (i in none.dropLast(1)) {
            assertEquals(0f, i.look!!.tintStrength, 0f)
            assertNull(i.look.colorMatrix())
        }
    }
}
