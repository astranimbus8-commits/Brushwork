package com.brushwork.paint.filters.art

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.FilterMath
import com.brushwork.paint.filters.art.ArtTestUtil.a
import com.brushwork.paint.filters.art.ArtTestUtil.b
import com.brushwork.paint.filters.art.ArtTestUtil.g
import com.brushwork.paint.filters.art.ArtTestUtil.r
import com.brushwork.paint.filters.art.ArtTestUtil.run
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class OilPaintFilterTest {
    private val oil = OilPaintFilter()
    /** Only the Kuwahara stage: no posterize steps, texture, contrast or detail. */
    private val plain = arrayOf<Pair<String, Any>>("posterize" to 64f, "texture" to 0f, "contrast" to 0f, "detail" to 0f)

    private fun noisyGray(n: Int, amp: Int): PixelBuffer = PixelBuffer(n, n).also { p ->
        for (y in 0 until n) for (x in 0 until n) {
            val v = 128 + ((FilterMath.hash01(x, y, 3) * 2f - 1f) * amp).toInt()
            p[x, y] = ColorUtils.argb(255, v, v, v)
        }
    }

    private fun stdDev(p: PixelBuffer): Double {
        val v = p.pixels.map { r(it).toDouble() }
        val m = v.average()
        return sqrt(v.sumOf { (it - m) * (it - m) } / v.size)
    }

    @Test
    fun flatColorStaysFlat() {
        val src = PixelBuffer.filled(30, 30, 0xFF4080C0.toInt())
        assertArrayEquals(src.pixels, run(oil, src, *plain).pixels)
    }

    @Test
    fun keepsEdgesSharpWhileFlatteningNoise() {
        val src = PixelBuffer(40, 30)
        for (y in 0 until 30) for (x in 0 until 40) src[x, y] = if (x < 20) 0xFF000000.toInt() else -1
        val out = run(oil, src, "size" to 6f, *plain)
        for (y in 0 until 30) {
            assertTrue(r(out[18, y]) < 20)
            assertTrue(r(out[21, y]) > 235)
        }
        val noisy = noisyGray(60, 60)
        val painted = run(oil, noisy, "size" to 5f, *plain)
        assertTrue("std ${stdDev(noisy)} -> ${stdDev(painted)}", stdDev(painted) < stdDev(noisy) * 0.5)
    }

    @Test
    fun keepsHueEdgesOfEqualBrightness() {
        // Red and green of (nearly) the same luminance: the edge must not smear.
        val red = ColorUtils.rgb(200, 60, 60); val green = ColorUtils.rgb(40, 140, 80)
        val src = PixelBuffer(40, 20)
        for (y in 0 until 20) for (x in 0 until 40) src[x, y] = if (x < 20) red else green
        val out = run(oil, src, "size" to 6f, *plain)
        for (y in 0 until 20) {
            val l = out[18, y]; val rr = out[21, y]
            assertTrue("left ${Integer.toHexString(l)}", kotlin.math.abs(r(l) - 200) <= 8 && kotlin.math.abs(g(l) - 60) <= 8)
            assertTrue("right ${Integer.toHexString(rr)}", kotlin.math.abs(r(rr) - 40) <= 8 && kotlin.math.abs(g(rr) - 140) <= 8)
        }
    }

    @Test
    fun sharpnessRaisesEdgeContrastWithoutTouchingFlatAreasOrAlpha() {
        val src = PixelBuffer(40, 20)
        for (y in 0 until 20) for (x in 0 until 40) src[x, y] = if (x < 20) 0xFF404040.toInt() else 0xFFC0C0C0.toInt()
        val soft = run(oil, src, *plain)
        val sharp = run(oil, src, *plain, "sharpness" to 100f)
        assertTrue("dark side ${r(sharp[19, 10])} vs ${r(soft[19, 10])}", r(sharp[19, 10]) < r(soft[19, 10]) - 5)
        assertTrue("light side ${r(sharp[20, 10])} vs ${r(soft[20, 10])}", r(sharp[20, 10]) > r(soft[20, 10]) + 5)
        assertEquals(soft[3, 10], sharp[3, 10]); assertEquals(soft[36, 10], sharp[36, 10])
        assertEquals(r(sharp[20, 10]), b(sharp[20, 10]))
        // A flat patch on a transparent layer gets no halo from the transparency around it.
        val patch = PixelBuffer(40, 40)
        for (y in 10 until 30) for (x in 10 until 30) patch[x, y] = 0xFF6080A0.toInt()
        val plainPatch = run(oil, patch, *plain)
        val sharpPatch = run(oil, patch, *plain, "sharpness" to 100f)
        for (i in patch.pixels.indices) {
            val p = plainPatch.pixels[i]; val s = sharpPatch.pixels[i]
            assertEquals(a(p), a(s))
            if (a(p) == 255) assertTrue("${Integer.toHexString(p)} -> ${Integer.toHexString(s)}", kotlin.math.abs(r(p) - r(s)) <= 3 && kotlin.math.abs(b(p) - b(s)) <= 3)
        }
    }

    @Test
    fun paintOnTransparentLayerStaysNearTheStroke() {
        val src = PixelBuffer(60, 40)
        for (y in 14..25) for (x in 5 until 55) src[x, y] = 0xFF202020.toInt()
        val out = run(oil, src, "size" to 4f)
        assertEquals(255, a(out[30, 19]))
        assertTrue(a(out[30, 26]) < 128)
        for (x in 0 until 60) { assertEquals(0, a(out[x, 0])); assertEquals(0, a(out[x, 39])) }
        val empty = PixelBuffer(20, 20)
        assertArrayEquals(empty.pixels, run(oil, empty).pixels)
    }

    @Test
    fun posterizeReducesTones() {
        val src = ArtTestUtil.gradient(64, 64)
        val coarse = run(oil, src, "posterize" to 3f, "texture" to 0f, "size" to 2f)
        val lumas = coarse.pixels.map { ColorUtils.luminance(it) }.toSet()
        val fine = run(oil, src, "posterize" to 64f, "texture" to 0f, "size" to 2f).pixels.map { ColorUtils.luminance(it) }.toSet()
        assertTrue("coarse ${lumas.size} fine ${fine.size}", lumas.size * 4 < fine.size)
    }

    @Test
    fun contrastSpreadsTones() {
        val src = PixelBuffer(40, 20)
        for (y in 0 until 20) for (x in 0 until 40) src[x, y] = if (x < 20) 0xFF505050.toInt() else 0xFFB0B0B0.toInt()
        val flat = run(oil, src, *plain)
        val strong = run(oil, src, *plain, "contrast" to 80f)
        assertTrue(r(strong[5, 5]) < r(flat[5, 5]) && r(strong[35, 5]) > r(flat[35, 5]))
        assertEquals(r(strong[35, 5]), b(strong[35, 5]))
    }

    @Test
    fun textureAddsReliefOnlyWhenEnabled() {
        val src = PixelBuffer.filled(50, 50, 0xFF808080.toInt())
        val smooth = run(oil, src, "texture" to 0f, "posterize" to 64f)
        assertEquals(1, smooth.pixels.toSet().size)
        val textured = run(oil, src, "texture" to 100f, "posterize" to 64f)
        assertTrue(stdDev(textured) > 2.0)
        // Relief shading is gray: it changes brightness, not hue.
        for (c in textured.pixels) { assertEquals(r(c), g(c)); assertEquals(g(c), b(c)) }
    }

    @Test
    fun detailBringsBackFineStructure() {
        val noisy = noisyGray(50, 30)
        val without = run(oil, noisy, *plain, "size" to 4f)
        val with = run(oil, noisy, *plain, "size" to 4f, "detail" to 100f)
        assertTrue(stdDev(with) > stdDev(without) * 1.5)
    }
}
