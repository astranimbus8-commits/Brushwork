package com.brushwork.paint.filters.draw

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class BackgroundRemovalTest {
    private val ctx = FilterContext()
    private val red = 0xFFD04A3A.toInt()
    private fun alpha(c: Int) = c ushr 24

    /** Light, slightly noisy background with a red disc and a body reaching the bottom edge. */
    private fun portrait(w: Int = 120, h: Int = 100, hole: Boolean = false): PixelBuffer {
        val b = PixelBuffer(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val n = (Noise.hash(x, y, 1) and 7) - 4
            var c = ColorUtils.argb(255, 240 + n, 242 + n, 238 + n)
            val inDisc = hypot(x - w / 2.0, y - h * 0.4) < h * 0.25
            val inBody = x in (w * 0.35).toInt()..(w * 0.65).toInt() && y > h * 0.6
            if (inDisc || inBody) c = red
            if (hole && hypot(x - w / 2.0, y - h * 0.4) < h * 0.08) c = ColorUtils.argb(255, 240, 242, 238)
            b[x, y] = c
        }
        return b
    }

    private fun colorOnly() = BackgroundRemovalFilter().let { it to it.defaultValues().set("method", BackgroundRemovalFilter.METHOD_COLOR) }

    @Test
    fun removesBorderColoredBackgroundAndKeepsSubject() {
        val (f, v) = colorOnly()
        val src = portrait()
        val out = f.apply(src, v, ctx)
        assertEquals(0, alpha(out[2, 2])); assertEquals(0, alpha(out[117, 50])); assertEquals(0, alpha(out[5, 97]))
        assertEquals(255, alpha(out[60, 40]))
        assertEquals(255, alpha(out[60, 98])) // the body touches the bottom border but is kept
        // RGB of kept pixels is untouched.
        assertEquals(red, out[60, 40])
    }

    @Test
    fun invertKeepsOnlyTheBackground() {
        val (f, v) = colorOnly()
        val out = f.apply(portrait(), v.set("invert", true), ctx)
        assertEquals(255, alpha(out[2, 2]))
        assertEquals(0, alpha(out[60, 40]))
    }

    @Test
    fun contiguousKeepsEnclosedBackgroundColoredAreas() {
        val (f, v) = colorOnly()
        val src = portrait(hole = true)
        assertEquals(255, alpha(f.apply(src, v, ctx)[60, 40]))
        assertEquals(0, alpha(f.apply(src, v.copy().set("contiguous", false), ctx)[60, 40]))
    }

    @Test
    fun edgeShiftGrowsAndShrinksTheSubject() {
        val (f, v) = colorOnly()
        val src = portrait()
        fun opaque(shift: Float) = f.apply(src, v.copy().set("shift", shift).set("softness", 0f), ctx).pixels.count { alpha(it) > 128 }
        val base = opaque(0f)
        assertTrue(opaque(4f) > base + 100)
        assertTrue(opaque(-4f) < base - 100)
    }

    @Test
    fun usesTheSegmentationServiceWithThreshold() {
        val f = BackgroundRemovalFilter()
        var calls = 0
        val services = object : FilterServices {
            override fun subjectMask(image: PixelBuffer): FloatArray {
                calls++
                // Confidence ramps from 0 (left) to 1 (right).
                return FloatArray(image.size) { (it % image.width) / (image.width - 1f) }
            }
        }
        val c = FilterContext(services = services)
        val src = PixelBuffer.filled(101, 10, 0xFF00FF00.toInt())
        val out = f.apply(src, f.defaultValues().set("threshold", 50f).set("softness", 0f), c)
        assertEquals(0, alpha(out[10, 5]))
        assertEquals(255, alpha(out[90, 5]))
        assertEquals(128f, alpha(out[50, 5]).toFloat(), 20f)
        // A distinct buffer with the same content (e.g. a fresh preview copy) reuses the mask.
        val higher = f.apply(src.copy(), f.defaultValues().set("threshold", 80f).set("softness", 0f), c)
        assertEquals(0, alpha(higher[70, 5]))
        assertEquals(1, calls)
        // Different content is segmented again.
        f.apply(PixelBuffer.filled(101, 10, 0xFF0000FF.toInt()), f.defaultValues(), c)
        assertEquals(2, calls)
    }

    @Test
    fun fallsBackToColorKeyWhenTheServiceFails() {
        val f = BackgroundRemovalFilter()
        val failing = object : FilterServices {
            override fun subjectMask(image: PixelBuffer): FloatArray? = throw IllegalStateException("model missing")
        }
        val out = f.apply(portrait(), f.defaultValues(), FilterContext(services = failing))
        assertEquals(0, alpha(out[2, 2]))
        assertEquals(255, alpha(out[60, 40]))
        val nullService = object : FilterServices {
            override fun subjectMask(image: PixelBuffer): FloatArray? = null
        }
        assertEquals(0, alpha(f.apply(portrait(), f.defaultValues(), FilterContext(services = nullService))[2, 2]))
    }

    @Test
    fun softEdgesOnAntialiasedBoundaries() {
        val (f, v) = colorOnly()
        val src = PixelBuffer(100, 100)
        for (y in 0 until 100) for (x in 0 until 100) {
            val cov = (30f - hypot(x + 0.5f - 50f, y + 0.5f - 50f) + 0.5f).coerceIn(0f, 1f)
            src[x, y] = ColorUtils.lerp(-1, 0xFF2040C0.toInt(), cov)
        }
        val out = f.apply(src, v, ctx)
        val partial = out.pixels.count { alpha(it) in 20..235 }
        assertTrue("partial=$partial", partial > 40)
        assertEquals(255, alpha(out[50, 50]))
        assertEquals(0, alpha(out[3, 3]))
    }

    @Test
    fun transparentBorderKeepsOpaqueContent() {
        val (f, v) = colorOnly()
        val src = PixelBuffer(40, 40)
        for (y in 10 until 30) for (x in 10 until 30) src[x, y] = red
        val out = f.apply(src, v, ctx)
        assertEquals(red, out[20, 20])
        assertEquals(0, out[2, 2])
    }
}
