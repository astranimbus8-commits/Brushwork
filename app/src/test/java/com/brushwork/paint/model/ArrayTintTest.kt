package com.brushwork.paint.model

import com.brushwork.paint.vector.VStop
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * v1.8 F1 (item 2, D9): the Array look's tint, rgb' = (1-s)·rgb + s·T·Y(rgb) with Y the Rec.709
 * luma. Strength 0 is the identity; alpha never changes; a gray keeps its luminance under a white
 * tint and becomes the tint's shade of its brightness at full strength; [ArrayTint.apply] (vector
 * paints) and [ArrayTint.matrix] (pixels) agree. JVM.
 */
class ArrayTintTest {
    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun a(c: Int) = (c ushr 24) and 0xFF
    private fun r(c: Int) = (c shr 16) and 0xFF
    private fun g(c: Int) = (c shr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF
    private fun luma(c: Int) = 0.2126 * r(c) + 0.7152 * g(c) + 0.0722 * b(c)

    /** Within one step of 255 (rounding). */
    private fun assertNear(expected: Int, actual: Int) = assertTrue("$expected vs $actual", abs(expected - actual) <= 1)

    private val white = 0xFFFFFFFF.toInt()
    private val identity = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    @Test
    fun strengthZeroIsTheIdentity() {
        val rnd = Random(3)
        repeat(500) {
            val c = rnd.nextInt()
            val t = rnd.nextInt()
            assertEquals(c, ArrayTint.apply(c, t, 0f))
            // A strength that is not a usable number is none too.
            assertEquals(c, ArrayTint.apply(c, t, Float.NaN))
            assertEquals(c, ArrayTint.apply(c, t, -0.5f))
        }
        assertArrayEquals(identity, ArrayTint.matrix(0xFF3366CC.toInt(), 0f), 0f)
        assertArrayEquals(identity, ArrayTint.matrix(0xFF3366CC.toInt(), Float.NaN), 0f)
        assertEquals(null, CopyLook(0.5f, 0xFF3366CC.toInt(), 0f).colorMatrix())
    }

    @Test
    fun alphaNeverChanges() {
        val rnd = Random(5)
        repeat(1000) {
            val c = rnd.nextInt()
            val out = ArrayTint.apply(c, rnd.nextInt(), rnd.nextFloat())
            assertEquals(a(c), a(out))
        }
        val m = ArrayTint.matrix(0x80FF8000.toInt(), 0.7f)
        // The alpha row is the identity's, no color row reads alpha, and nothing is added.
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f, 0f), m.copyOfRange(15, 20), 0f)
        for (row in 0 until 3) {
            assertEquals(0f, m[row * 5 + 3], 0f)
            assertEquals(0f, m[row * 5 + 4], 0f)
        }
    }

    @Test
    fun aGrayKeepsItsLuminanceUnderAWhiteTint() {
        for (v in 0..255) {
            val gray = argb(255, v, v, v)
            for (s in listOf(0.25f, 0.5f, 1f)) {
                assertEquals("gray $v at $s", gray, ArrayTint.apply(gray, white, s))
            }
        }
    }

    @Test
    fun atFullStrengthAGrayBecomesTheTintsShadeOfItsBrightness() {
        val tint = 0xFFFF8040.toInt()
        for (v in listOf(0, 17, 128, 200, 255)) {
            val out = ArrayTint.apply(argb(255, v, v, v), tint, 1f)
            assertNear(v, r(out))
            assertNear((128 / 255.0 * v).roundToInt(), g(out))
            assertNear((64 / 255.0 * v).roundToInt(), b(out))
            assertEquals(luma(tint) / 255.0 * v, luma(out), 1.0)
        }
        // Black stays black whatever the tint.
        assertEquals(0xFF000000.toInt(), ArrayTint.apply(0xFF000000.toInt(), tint, 1f))
    }

    @Test
    fun aColorUnderAWhiteTintTurnsTowardItsGray() {
        val red = 0xFFFF0000.toInt()
        val y = luma(red) // 54.2
        val full = ArrayTint.apply(red, white, 1f)
        assertEquals(y.roundToInt(), r(full))
        assertEquals(y.roundToInt(), g(full))
        assertEquals(y.roundToInt(), b(full))
        val half = ArrayTint.apply(red, white, 0.5f)
        assertEquals((0.5 * 255 + 0.5 * y).roundToInt(), r(half))
        assertEquals((0.5 * y).roundToInt(), g(half))
    }

    @Test
    fun applyAndTheMatrixAgree() {
        val rnd = Random(11)
        repeat(2000) {
            val c = rnd.nextInt()
            val t = rnd.nextInt()
            val s = if (it % 10 == 0) 1f else rnd.nextFloat()
            val m = ArrayTint.matrix(t, s)
            val rgba = floatArrayOf(r(c).toFloat(), g(c).toFloat(), b(c).toFloat(), a(c).toFloat())
            val out = ArrayTint.apply(c, t, s)
            for (row in 0 until 3) {
                var v = m[row * 5 + 4]
                for (i in 0 until 4) v += m[row * 5 + i] * rgba[i]
                val expected = v.roundToInt().coerceIn(0, 255)
                val actual = when (row) { 0 -> r(out); 1 -> g(out); else -> b(out) }
                assertTrue("row $row of ${Integer.toHexString(c)} by ${Integer.toHexString(t)} at $s: $expected vs $actual", abs(expected - actual) <= 1)
            }
        }
    }

    @Test
    fun theTintColorIsTheGradientSampledAtT() {
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF0000FF.toInt()
        val stops = listOf(VStop(0.8f, blue), VStop(0.2f, red))
        assertEquals("flat before the first stop", red, ArrayTint.sample(stops, 0f))
        assertEquals(red, ArrayTint.sample(stops, 0.2f))
        assertEquals("flat after the last stop", blue, ArrayTint.sample(stops, 1f))
        val mid = ArrayTint.sample(stops, 0.5f)
        assertNear(128, r(mid))
        assertNear(128, b(mid))
        assertEquals(255, a(mid))
        assertEquals("one stop is its color", blue, ArrayTint.sample(listOf(VStop(0.5f, blue)), 0.1f))
        assertEquals("no stops: opaque black", 0xFF000000.toInt(), ArrayTint.sample(emptyList(), 0.5f))
    }
}
