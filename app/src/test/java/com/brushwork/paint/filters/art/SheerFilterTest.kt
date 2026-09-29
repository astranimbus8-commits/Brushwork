package com.brushwork.paint.filters.art

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
import kotlin.math.abs

class SheerFilterTest {
    private val gray = 0xFF808080.toInt()
    private fun graySquare(n: Int) = PixelBuffer.filled(n, n, gray)

    @Test
    fun everyShapeIsIdentityAtZeroAmountAndKeepsAlpha() {
        for (shape in SheerShape.entries) {
            val f = SheerFilter(shape)
            val src = ArtTestUtil.gradient(40, 30, alpha = 200)
            for (x in 0 until 40) src[x, 3] = 0
            assertArrayEquals(src.pixels, run(f, src, "amount" to 0f).pixels)
            val out = run(f, src, "amount" to 80f)
            for (i in src.pixels.indices) assertEquals(a(src.pixels[i]), a(out.pixels[i]))
            for (x in 0 until 40) assertEquals(0, out[x, 3])
            assertTrue("${shape.name} changed nothing", ArtTestUtil.countDifferent(src, out) > 50)
        }
    }

    @Test
    fun specksAreGraySignedAndSeeded() {
        for (shape in SheerShape.entries) {
            val f = SheerFilter(shape)
            val src = graySquare(120)
            val out = run(f, src, "amount" to 40f)
            var sum = 0L; var brighter = 0; var darker = 0
            for (c in out.pixels) {
                assertEquals(r(c), g(c)); assertEquals(g(c), b(c))
                sum += r(c) - 128
                if (r(c) > 128) brighter++ else if (r(c) < 128) darker++
            }
            assertTrue("${shape.name} brighter $brighter darker $darker", brighter > 200 && darker > 200)
            assertTrue("${shape.name} bias ${sum / 14400.0}", abs(sum / 14400.0) < 6.0)
            assertArrayEquals(out.pixels, run(f, src, "amount" to 40f).pixels)
            assertTrue(ArtTestUtil.countDifferent(out, run(f, src, "amount" to 40f, "seed" to 9)) > 500)
        }
    }

    /** Sum of absolute differences between neighbors along x and along y. */
    private fun roughness(p: PixelBuffer): Pair<Double, Double> {
        var sx = 0.0; var sy = 0.0
        for (y in 1 until p.height) for (x in 1 until p.width) {
            sx += abs(r(p[x, y]) - r(p[x - 1, y]))
            sy += abs(r(p[x, y]) - r(p[x, y - 1]))
        }
        return sx to sy
    }

    @Test
    fun linesFollowTheDirection() {
        val f = SheerFilter(SheerShape.LINE)
        val (hx, hy) = roughness(run(f, graySquare(150), "size" to 40f, "direction" to 0f, "amount" to 50f))
        assertTrue("horizontal lines: x $hx y $hy", hx * 3 < hy)
        val (vx, vy) = roughness(run(f, graySquare(150), "size" to 40f, "direction" to 90f, "amount" to 50f))
        assertTrue("vertical lines: x $vx y $vy", vy * 3 < vx)
    }

    @Test
    fun crossesAndFilledShapesAreIsotropicAtNeutralAngles() {
        for (shape in listOf(SheerShape.CROSS, SheerShape.SQUARE, SheerShape.CIRCLE, SheerShape.HEX)) {
            val (x, y) = roughness(run(SheerFilter(shape), graySquare(160), "size" to 12f, "amount" to 50f))
            assertTrue("${shape.name} x $x y $y", x < y * 1.5 && y < x * 1.5)
        }
    }

    @Test
    fun filledShapesArePiecewiseFlat() {
        // Big filled specks change the image in flat patches: most pixels equal their left neighbor.
        for (shape in listOf(SheerShape.SQUARE, SheerShape.HEX, SheerShape.CIRCLE)) {
            val out = run(SheerFilter(shape), graySquare(200), "size" to 30f, "amount" to 20f)
            var same = 0; var total = 0
            for (y in 0 until 200) for (x in 1 until 200) { total++; if (out[x, y] == out[x - 1, y]) same++ }
            assertTrue("${shape.name} flat fraction ${same.toDouble() / total}", same > total * 0.85)
            assertTrue(out.pixels.count { it != gray } > 5000)
        }
    }

    @Test
    fun outlineDrawsHollowShapes() {
        val f = SheerFilter(SheerShape.CIRCLE)
        val filled = run(f, graySquare(200), "size" to 30f, "amount" to 10f)
        val hollow = run(f, graySquare(200), "size" to 30f, "amount" to 10f, "outline" to true)
        val nFilled = filled.pixels.count { it != gray }
        val nHollow = hollow.pixels.count { it != gray }
        assertTrue("filled $nFilled hollow $nHollow", nHollow < nFilled * 0.75 && nHollow > 0)
    }

    @Test
    fun precomputedSmallSpecksMatchExactRasterizing() {
        val w = 24
        for (shape in SheerShape.entries) for (outline in listOf(false, true)) {
            if (outline && !shape.filled) continue
            for (size in listOf(1f, 2.5f, 5f)) {
                val dx = ArtMath.unitX(30f); val dy = ArtMath.unitY(30f)
                val fast = SheerFilter.Stamp(shape, size, 1f, outline, dx, dy)
                val exact = SheerFilter.Stamp(shape, size, 1f, outline, dx, dy, exact = true)
                // Tiny specks change energy quickly with their sub-pixel position, so energy is
                // compared over many positions; single pixels must agree closely everywhere.
                var energyFast = 0f; var energyExact = 0f
                for (t in 0 until 32) {
                    val fx = 8f + 8f * FilterMath.hash01(t, 1, 77); val fy = 8f + 8f * FilterMath.hash01(t, 2, 77)
                    val fastAcc = FloatArray(w * w); val exactAcc = FloatArray(w * w)
                    fast.draw(fastAcc, w, 0, w, fx, fy, 1f)
                    exact.draw(exactAcc, w, 0, w, fx, fy, 1f)
                    val what = "$shape outline=$outline size=$size at $fx,$fy"
                    var maxDiff = 0f
                    for (i in fastAcc.indices) maxDiff = maxOf(maxDiff, abs(fastAcc[i] - exactAcc[i]))
                    assertTrue("$what differs by $maxDiff", maxDiff < 0.2f)
                    energyFast += fastAcc.sum(); energyExact += exactAcc.sum()
                    // Band clipping gives exactly the corresponding rows.
                    val band = FloatArray(4 * w)
                    fast.draw(band, w, 10, 14, fx, fy, 1f)
                    for (i in band.indices) assertEquals(fastAcc[10 * w + i], band[i], 0f)
                }
                assertTrue(
                    "$shape outline=$outline size=$size energy $energyFast vs $energyExact",
                    energyExact > 0f && abs(energyFast - energyExact) < 0.03f * energyExact,
                )
            }
        }
    }

    @Test
    fun smallestSpecksAtFullAmountAreDenseUnbiasedAndSeeded() {
        for (shape in SheerShape.entries) {
            val f = SheerFilter(shape)
            val src = graySquare(100)
            val out = run(f, src, "size" to 1f, "amount" to 100f)
            val dev = out.pixels.map { r(it) - 128 }
            assertTrue("${shape.name} changed ${dev.count { it != 0 }}", dev.count { it != 0 } > 5000)
            assertTrue("${shape.name} bias ${dev.average()}", abs(dev.average()) < 4.0)
            assertArrayEquals(out.pixels, run(f, src, "size" to 1f, "amount" to 100f).pixels)
        }
    }

    @Test
    fun previewKeepsTheTextureStrength() {
        val f = SheerFilter(SheerShape.SQUARE)
        fun meanDev(p: PixelBuffer) = p.pixels.sumOf { abs(r(it) - 128).toDouble() } / p.size
        val full = meanDev(run(f, graySquare(200), "size" to 8f))
        val half = meanDev(run(f, graySquare(100), "size" to 8f, scale = 0.5f))
        assertTrue("full $full half $half", half > full * 0.6 && half < full * 1.6)
    }
}
