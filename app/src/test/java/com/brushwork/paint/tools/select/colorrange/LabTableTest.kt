package com.brushwork.paint.tools.select.colorrange

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * v1.8 (C, design §3.5 c): Color select's table-driven Lab (D65) against the closed form in
 * doubles, over a 32 × 32 × 32 grid of colors, every gray and every primary ramp: under 0.01 ΔE.
 * The hundredths [ColorRange.labQ] packs round-trip, signs included.
 */
class LabTableTest {

    private fun closed(argb: Int): DoubleArray {
        val r = ColorRange.linear((argb shr 16) and 0xFF)
        val g = ColorRange.linear((argb shr 8) and 0xFF)
        val b = ColorRange.linear(argb and 0xFF)
        val m = ColorRange.RGB_TO_XYZ
        val w = ColorRange.WHITE
        val fx = ColorRange.f((m[0] * r + m[1] * g + m[2] * b) / w[0])
        val fy = ColorRange.f((m[3] * r + m[4] * g + m[5] * b) / w[1])
        val fz = ColorRange.f((m[6] * r + m[7] * g + m[8] * b) / w[2])
        return doubleArrayOf(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
    }

    private fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun deltaE(x: FloatArray, y: DoubleArray): Double {
        val dl = x[0] - y[0]
        val da = x[1] - y[1]
        val db = x[2] - y[2]
        return sqrt(dl * dl + da * da + db * db)
    }

    @Test
    fun theTablesMatchTheClosedFormWithinAHundredthOfDeltaE() {
        val colors = ArrayList<Int>()
        for (i in 0 until 32) for (j in 0 until 32) for (k in 0 until 32) {
            colors += rgb(i * 255 / 31, j * 255 / 31, k * 255 / 31)
        }
        for (v in 0..255) {
            colors += rgb(v, v, v); colors += rgb(v, 0, 0); colors += rgb(0, v, 0); colors += rgb(0, 0, v)
            colors += rgb(255, v, 0); colors += rgb(0, 255, v); colors += rgb(v, 0, 255)
        }
        val out = FloatArray(3)
        var worst = 0.0
        var at = 0
        for (c in colors) {
            ColorRange.lab(c, out)
            val e = deltaE(out, closed(c))
            if (e > worst) { worst = e; at = c }
        }
        assertTrue("worst ΔE $worst at #${Integer.toHexString(at)}", worst < 0.01)
    }

    @Test
    fun knownColors() {
        val out = FloatArray(3)
        fun check(argb: Int, l: Double, a: Double, b: Double) {
            ColorRange.lab(argb, out)
            assertEquals(l, out[0].toDouble(), 0.05)
            assertEquals(a, out[1].toDouble(), 0.05)
            assertEquals(b, out[2].toDouble(), 0.05)
        }
        check(rgb(255, 255, 255), 100.0, 0.0, 0.0)
        check(rgb(0, 0, 0), 0.0, 0.0, 0.0)
        check(rgb(255, 0, 0), 53.24, 80.09, 67.20)
        check(rgb(0, 255, 0), 87.73, -86.18, 83.18)
        check(rgb(0, 0, 255), 32.30, 79.19, -107.86)
        // The alpha is not the color's.
        val opaque = FloatArray(3).also { ColorRange.lab(rgb(30, 140, 200), it) }
        ColorRange.lab(0x2A1E8CC8, out)
        assertEquals(opaque.toList(), out.toList())
    }

    @Test
    fun hundredthsPackAndUnpackWithTheirSigns() {
        val out = FloatArray(3)
        for (c in listOf(rgb(0, 0, 255), rgb(0, 255, 0), rgb(255, 0, 255), rgb(255, 255, 255), rgb(0, 0, 0), rgb(12, 200, 99))) {
            ColorRange.lab(c, out)
            val q = ColorRange.labQ(c)
            assertEquals(out[0] * 100.0, ColorRange.qL(q).toDouble(), 0.51)
            assertEquals(out[1] * 100.0, ColorRange.qA(q).toDouble(), 0.51)
            assertEquals(out[2] * 100.0, ColorRange.qB(q).toDouble(), 0.51)
        }
        assertTrue(ColorRange.qB(ColorRange.labQ(rgb(0, 0, 255))) < -10_000)
        assertTrue(ColorRange.qA(ColorRange.labQ(rgb(0, 255, 0))) < -8_000)
        assertEquals(10_000, ColorRange.qL(ColorRange.labQ(rgb(255, 255, 255))))
    }
}
