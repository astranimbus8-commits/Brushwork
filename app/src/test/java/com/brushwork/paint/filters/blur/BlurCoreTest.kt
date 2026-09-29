package com.brushwork.paint.filters.blur

import com.brushwork.paint.filters.FilterContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BlurCoreTest {
    private val ctx = FilterContext()

    @Test
    fun extendedBoxHasRequestedVarianceAndKeepsMass() {
        for (sigma in floatArrayOf(0.4f, 0.9f, 1.3f, 2.7f, 6f, 15.5f, 40f)) {
            val w = 801
            val plane = FloatArray(w).also { it[w / 2] = 1f }
            BlurCore.blurPlane(plane, w, 1, sigma, 0f, ctx)
            var sum = 0.0; var mean = 0.0
            for (i in 0 until w) { sum += plane[i]; mean += i * plane[i].toDouble() }
            mean /= sum
            var variance = 0.0
            for (i in 0 until w) variance += (i - mean) * (i - mean) * plane[i]
            variance /= sum
            assertEquals("mass for sigma $sigma", 1.0, sum, 1e-4)
            assertEquals("centre for sigma $sigma", (w / 2).toDouble(), mean, 1e-3)
            assertEquals("variance for sigma $sigma", sigma.toDouble() * sigma, variance, sigma * sigma * 0.01 + 1e-3)
        }
    }

    @Test
    fun blurGrowsContinuouslyWithSigma() {
        // No jumps between integer box widths: the peak falls monotonically and smoothly.
        var previous = 1f
        var s = 0.1f
        while (s < 6f) {
            val plane = FloatArray(101).also { it[50] = 1f }
            BlurCore.blurPlane(plane, 101, 1, s, 0f, ctx)
            val peak = plane[50]
            assertTrue("peak rose at sigma $s", peak <= previous + 1e-6f)
            assertTrue("peak jumped at sigma $s: $previous -> $peak", previous - peak < 0.12f)
            previous = peak
            s += 0.05f
        }
    }

    @Test
    fun constantPlaneStaysConstantIncludingEdges() {
        val w = 37; val h = 23
        val plane = FloatArray(w * h) { 7.5f }
        BlurCore.blurPlane(plane, w, h, 30f, 30f, ctx)
        for (v in plane) assertEquals(7.5f, v, 1e-3f)
    }

    @Test
    fun verticalPassMatchesHorizontalPassOnTransposedData() {
        val w = 70; val h = 45 // wider than one 32-column strip
        val rnd = Random(3)
        val plane = FloatArray(w * h) { rnd.nextFloat() * 255f }
        val transposed = FloatArray(w * h) { i -> val x = i / h; val y = i % h; plane[y * w + x] }
        BlurCore.blurPlane(plane, w, h, 3.3f, 0f, ctx)
        BlurCore.blurPlane(transposed, h, w, 0f, 3.3f, ctx)
        for (y in 0 until h) for (x in 0 until w) {
            assertEquals("($x,$y)", plane[y * w + x], transposed[x * h + y], 1e-3f)
        }
    }

    @Test
    fun tinyOrInvalidSigmaMeansNoBlur() {
        assertNull(BlurCore.boxSpec(0f))
        assertNull(BlurCore.boxSpec(Float.NaN))
        assertNull(BlurCore.boxSpec(-3f))
    }

    @Test
    fun progressivePassCountCoversRequestedSamples() {
        assertEquals(1, Progressive.passesFor(1f))
        assertEquals(1, Progressive.passesFor(4f))
        assertEquals(2, Progressive.passesFor(5f))
        assertEquals(5, Progressive.passesFor(1001f))
        assertEquals(7, Progressive.passesFor(1e9f))
    }

    @Test
    fun progressivePassesEnumerateTheKernelAndKeepPartialSumsInside() {
        val passes = 3
        val m = Progressive.effectiveSamples(passes)
        val lo = -10.0
        val hi = lo + (m - 1) // unit spacing
        val p = Array(passes) { Progressive.passParams(it, passes, lo, hi) }
        val seen = HashSet<Long>()
        for (a in p[0]) for (b in p[1]) for (c in p[2]) {
            seen += Math.round(a + b + c)
            // The coarsest pass is applied first from the output pixel: every partial sum must stay
            // within [lo, hi] or clamped/out-of-image samples would punch holes into the streak.
            assertTrue(c in lo..hi && (b + c) in lo..hi)
        }
        assertEquals(m, seen.size)
        assertEquals(lo.toLong(), seen.min())
        assertEquals(hi.toLong(), seen.max())
    }
}
