package com.brushwork.paint.filters.draw

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.FilterContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MangaLinesTest {
    private val ctx = FilterContext()
    private fun alpha(c: Int) = c ushr 24

    @Test
    fun focusLinesLeaveTheCenterClearAndConverge() {
        val f = RadialLineFilter()
        val v = f.defaultValues().set("count", 60f).set("thickness", 6f)
        val w = 300; val h = 200
        val out = f.apply(PixelBuffer(w, h), v, ctx)
        // Clear area around the center.
        for (y in 90 until 110) for (x in 140 until 160) assertEquals(0, alpha(out[x, y]))
        // Near the edges lines and gaps alternate: a ring close to the border has both.
        var inked = 0; var empty = 0
        for (x in 0 until w) { val a = alpha(out[x, 2]); if (a > 200) inked++ else if (a < 20) empty++ }
        assertTrue("inked=$inked empty=$empty", inked > 10 && empty > 10)
        // Every generated line is drawn along its own ray, beyond its start radius.
        val lines = f.buildLines(v, w, h, 150.0, 100.0, ctx)
        var hits = 0
        for (i in 0 until lines.size) {
            val r = lines.startR[i] + 0.8 * (90.0 - lines.startR[i]).coerceAtLeast(0.0) + 3.0
            val x = (150 + r * lines.cos[i]).toInt(); val y = (100 + r * lines.sin[i]).toInt()
            if (x in 0 until w && y in 0 until h && alpha(out[x, y]) > 0) hits++
        }
        assertTrue("hits=$hits of ${lines.size}", hits > lines.size * 0.8)
    }

    @Test
    fun focusLinesGetThickerOutward() {
        val f = RadialLineFilter()
        val v = f.defaultValues().set("jitter", 0f).set("inner", 10f).set("thickness", 20f).set("count", 12f)
        val out = f.apply(PixelBuffer(401, 401), v, ctx)
        // Inked arc length on a ring = total line width at that radius.
        fun inkedArc(r: Double): Double {
            var sum = 0.0
            for (a in 0 until 1440) {
                val t = Math.toRadians(a * 0.25)
                sum += alpha(out[(200.5 + r * kotlin.math.cos(t)).toInt(), (200.5 + r * kotlin.math.sin(t)).toInt()])
            }
            return sum / 255.0 / 1440 * 2 * Math.PI * r
        }
        val inner = inkedArc(80.0); val outer = inkedArc(190.0)
        assertTrue("inner=$inner outer=$outer", outer > inner * 2 && inner > 5)
    }

    @Test
    fun focusLinesAreDeterministicPerSeed() {
        val f = RadialLineFilter()
        val a = f.apply(PixelBuffer(64, 64), f.defaultValues(), ctx)
        val b = f.apply(PixelBuffer(64, 64), f.defaultValues(), ctx)
        val c = f.apply(PixelBuffer(64, 64), f.defaultValues().set("seed", 7), ctx)
        assertTrue(a.pixels.contentEquals(b.pixels))
        assertFalse(a.pixels.contentEquals(c.pixels))
    }

    @Test
    fun focusLinesPaintOverExistingPixelsWithTheirColor() {
        val f = RadialLineFilter()
        val red = 0xFFFF0000.toInt()
        val src = PixelBuffer.filled(120, 120, -1)
        val out = f.apply(src, f.defaultValues().set("color", red), ctx)
        var redPixels = 0
        for (c in out.pixels) {
            assertEquals(255, alpha(c))
            if (c == red) redPixels++
            // Only white, red or a mix of them (no other hues).
            assertEquals(0xFF, (c shr 16) and 0xFF)
            assertEquals((c shr 8) and 0xFF, c and 0xFF)
        }
        assertTrue(redPixels > 100)
    }

    private fun anisotropy(out: PixelBuffer): Pair<Double, Double> {
        var along = 0.0; var across = 0.0
        for (y in 1 until out.height) for (x in 1 until out.width) {
            along += abs(alpha(out[x, y]) - alpha(out[x - 1, y]))
            across += abs(alpha(out[x, y]) - alpha(out[x, y - 1]))
        }
        return along to across
    }

    @Test
    fun speedLinesRunAlongTheAngle() {
        val f = SpeedLineFilter()
        val v = f.defaultValues().set("thickness", 3f)
        val horizontal = f.apply(PixelBuffer(200, 150), v, ctx)
        val (hx, hy) = anisotropy(horizontal)
        assertTrue("x=$hx y=$hy", hy > hx * 4)
        val vertical = f.apply(PixelBuffer(200, 150), v.copy().set("angle", 90f), ctx)
        val (vx, vy) = anisotropy(vertical)
        assertTrue("x=$vx y=$vy", vx > vy * 4)
        // A decent fraction of the canvas is inked, but not all of it.
        val inked = horizontal.pixels.count { alpha(it) > 128 }
        assertTrue("inked=$inked", inked in 500..20000)
    }

    @Test
    fun speedLinesStayWithinTheirLanes() {
        val f = SpeedLineFilter()
        val v = f.defaultValues().set("density", 90f).set("thickness", 4f).set("thickness_var", 0f)
        val out = f.apply(PixelBuffer(100, 100), v, ctx)
        val st = f.buildStreaks(v, 100, 100, ctx)
        // At angle 0 streaks are horizontal: every inked pixel lies within half a thickness
        // (+ antialiasing) of the axis of a streak that spans its x position.
        var inked = 0
        for (y in 0 until 100) for (x in 0 until 100) {
            if (alpha(out[x, y]) == 0) continue
            inked++
            val u = x + 0.5 - 50.0; val vy = y + 0.5 - 50.0
            val near = st.v.indices.filter { u >= st.u0[it] - 0.5 && u <= st.u1[it] + 0.5 }.minOf { abs(st.v[it] - vy) }
            assertTrue("pixel $x,$y is $near px from a streak", near <= 2.51)
        }
        assertTrue(inked > 100)
    }

    @Test
    fun speedLineWidthsScaleWithPreview() {
        val f = SpeedLineFilter()
        val v = f.defaultValues()
        val full = f.buildStreaks(v, 400, 400, ctx)
        val small = f.buildStreaks(v, 100, 100, FilterContext(scale = 0.25f))
        assertEquals(full.lanes, small.lanes)
        assertEquals(full.u0.size, small.u0.size)
        assertEquals(full.halfW[3] * 0.25f, small.halfW[3], 1e-4f)
        assertEquals(full.v[5] * 0.25, small.v[5], 1e-6)
    }

    @Test
    fun speedLineDensityControlsLaneSpacing() {
        val f = SpeedLineFilter()
        val sparse = f.buildStreaks(f.defaultValues().set("density", 10f), 300, 300, ctx)
        val dense = f.buildStreaks(f.defaultValues().set("density", 100f), 300, 300, ctx)
        assertTrue(dense.lanes > sparse.lanes * 4)
        // At full density lanes sit about 1.2 thicknesses apart.
        assertEquals(6.0 * 1.2, dense.spacing, 0.5)
    }

    @Test
    fun zeroOpacityIsACopy() {
        for (f in listOf(RadialLineFilter(), SpeedLineFilter())) {
            val src = PixelBuffer.filled(10, 10, 0x80112233.toInt())
            val out = f.apply(src, f.defaultValues().set("opacity", 0f), ctx)
            assertTrue(out !== src && out.pixels.contentEquals(src.pixels))
        }
    }
}
