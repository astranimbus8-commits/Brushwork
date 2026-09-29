package com.brushwork.paint.filters.adjust

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.FilterContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LineFiltersTest {

    private fun stepImage(w: Int = 20, h: Int = 10, split: Int = 10): PixelBuffer {
        val img = PixelBuffer(w, h)
        for (y in 0 until h) for (x in 0 until w) img[x, y] = if (x < split) gray(0) else gray(255)
        return img
    }

    // ---------------------------------------------------------------- extract line drawing

    @Test
    fun extractTurnsPaperTransparentAndInkOpaque() {
        val f = adjust<ExtractLineDrawingFilter>()
        val out = f.run(row(gray(255), gray(240), gray(0), gray(128), gray(60), gray(30, 128))).pixels
        assertEquals(0, out[0])
        assertEquals(0, out[1])
        assertEquals(rgb(0, 0, 0, 255), out[2])
        assertTrue(abs(a(out[3]) - 136) <= 2 && (out[3] and 0xFFFFFF) == 0)
        assertTrue(a(out[4]) in (a(out[3]) + 1)..255)
        // Semi-transparent ink reads lighter (composited over white), so it gets partial alpha.
        assertTrue(a(out[5]) in 60..200)
        // A transparent layer stays empty.
        assertTrue(f.run(PixelBuffer(6, 4)).pixels.all { it == 0 })
    }

    @Test
    fun extractLevelsAndMiddleValue() {
        val f = adjust<ExtractLineDrawingFilter>()
        val src = row(gray(128))
        val lighter = a(f.run(src, "middle" to 20f).pixels[0])
        val normal = a(f.run(src).pixels[0])
        val darker = a(f.run(src, "middle" to 80f).pixels[0])
        assertTrue(lighter < normal && normal < darker)
        // Black and White at the same level make a binary (digital pen) result.
        val binary = f.run(row(gray(100), gray(140)), "black" to 50f, "white" to 50f).pixels
        assertEquals(255, a(binary[0])); assertEquals(0, binary[1])
    }

    @Test
    fun extractLineColorAndKeepColor() {
        val f = adjust<ExtractLineDrawingFilter>()
        assertEquals(rgb(10, 20, 200), f.run(row(gray(0)), "lineColor" to rgb(10, 20, 200)).pixels[0])
        val red = rgb(220, 30, 30)
        val kept = f.run(row(red, rgb(20, 40, 160)), "keepColor" to true).pixels
        assertTrue(r(kept[0]) > 150 && g(kept[0]) < 40 && b(kept[0]) < 40)
        // Composited back over white, the red channel of the original is reproduced.
        val k = a(kept[0]) / 255f
        assertTrue(abs(r(kept[0]) * k + 255 * (1 - k) - r(red)) < 3)
        assertTrue(b(kept[1]) > r(kept[1]) + 60)
        // Fully dense pixels keep their exact color.
        assertEquals(rgb(20, 0, 10), f.run(row(rgb(20, 0, 10)), "keepColor" to true).pixels[0])
        // A bright saturated line (yellow pencil: luma ~214, near the paper level) survives with
        // its color, while in single-color mode it is judged by luminance and nearly vanishes.
        val yellow = rgb(255, 220, 0)
        assertEquals(yellow, f.run(row(yellow), "keepColor" to true).pixels[0])
        assertTrue(a(f.run(row(yellow)).pixels[0]) < 40)
        // Gray ink gets the same density in both modes.
        assertEquals(a(f.run(row(gray(90))).pixels[0]), a(f.run(row(gray(90)), "keepColor" to true).pixels[0]))
        // Light, desaturated paper is still dropped with keep color on.
        assertEquals(0, f.run(row(rgb(250, 245, 228)), "keepColor" to true).pixels[0])
    }

    // ---------------------------------------------------------------- find edges

    @Test
    fun findEdgesFlatImageHasNoLines() {
        val flat = PixelBuffer(16, 12).fill(rgb(120, 140, 90))
        for (alg in 0..2) {
            val out = adjust<FindEdgesFilter>().run(flat, "algorithm" to alg)
            assertTrue("algorithm $alg", out.pixels.all { it == 0 })
            val white = adjust<FindEdgesFilter>().run(flat, "algorithm" to alg, "background" to 1)
            assertTrue("algorithm $alg on white", white.pixels.all { it == gray(255) })
        }
    }

    @Test
    fun sobelMarksBothSidesOfAStep() {
        val out = adjust<FindEdgesFilter>().run(stepImage(), "smoothness" to 0f)
        for (y in 0 until 10) for (x in 0 until 20) {
            val expected = if (x == 9 || x == 10) 255 else 0
            assertEquals("($x,$y)", expected, a(out[x, y]))
        }
        assertEquals(rgb(0, 0, 0), out[9, 4])
    }

    @Test
    fun laplacianAndDogDrawOnlyOnTheDarkSide() {
        val lap = adjust<FindEdgesFilter>().run(stepImage(), "algorithm" to 1, "smoothness" to 0f)
        for (x in 0 until 20) assertEquals("laplacian x=$x", if (x == 9) 255 else 0, a(lap[x, 5]))
        val dog = adjust<FindEdgesFilter>().run(stepImage(), "algorithm" to 2, "smoothness" to 1f)
        assertTrue(a(dog[9, 5]) > 200)
        for (x in 10 until 20) assertEquals("dog bright side x=$x", 0, a(dog[x, 5]))
        for (x in 0 until 5) assertEquals("dog far dark side x=$x", 0, a(dog[x, 5]))
    }

    @Test
    fun edgesStillShowOnADownscaledPreview() {
        // At 1/4 scale the smoothing shrinks below one pixel; every algorithm must still find the step.
        for (alg in 0..2) for (smooth in listOf(0f, 1f)) {
            val out = adjust<FindEdgesFilter>().run(stepImage(), "algorithm" to alg, "smoothness" to smooth, ctx = FilterContext(scale = 0.25f))
            assertTrue("algorithm $alg smoothness $smooth", a(out[9, 5]) > 200)
            assertEquals("algorithm $alg smoothness $smooth far from the edge", 0, a(out[2, 5]))
            assertEquals("algorithm $alg smoothness $smooth bright side", 0, a(out[16, 5]))
        }
    }

    @Test
    fun findEdgesLevelsColorAndBackground() {
        val f = adjust<FindEdgesFilter>()
        // A weak step (10 % contrast) is dropped with the default White level and kept when lowered.
        val weak = PixelBuffer(20, 6)
        for (y in 0 until 6) for (x in 0 until 20) weak[x, y] = if (x < 10) gray(128) else gray(153)
        assertTrue(f.run(weak, "smoothness" to 0f, "white" to 12f).pixels.all { it == 0 })
        assertEquals(255, a(f.run(weak, "smoothness" to 0f, "white" to 2f, "black" to 8f)[9, 3]))
        val onWhite = f.run(stepImage(), "smoothness" to 0f, "background" to 1, "lineColor" to rgb(200, 0, 0))
        assertEquals(rgb(200, 0, 0), onWhite[9, 2])
        assertEquals(gray(255), onWhite[3, 2])
        assertTrue(onWhite.pixels.all { a(it) == 255 })
    }
}
