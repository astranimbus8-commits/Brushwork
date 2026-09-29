package com.brushwork.paint.segmentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class GuidedFilterTest {
    private val w = 64
    private val h = 16

    /** Guide with a vertical step at x = [edge]: 0.2 left, 0.8 right. */
    private fun stepGuide(edge: Int) = FloatArray(w * h) { if (it % w < edge) 0.2f else 0.8f }

    @Test
    fun constantInputIsPreserved() {
        val rnd = Random(3)
        val guide = FloatArray(w * h) { rnd.nextFloat() }
        val p = FloatArray(w * h) { 0.37f }
        val q = GuidedFilter.filter(guide, p, w, h, r = 4, eps = 1e-3f)
        for (v in q) assertEquals(0.37f, v, 1e-4f)
    }

    @Test
    fun outputStaysInRange() {
        val rnd = Random(5)
        val guide = FloatArray(w * h) { rnd.nextFloat() }
        val p = FloatArray(w * h) { if (rnd.nextBoolean()) 1f else 0f }
        for (r in listOf(1, 3, 8, 40)) {
            val q = GuidedFilter.filter(guide, p, w, h, r, eps = 1e-4f)
            assertTrue(q.all { it in 0f..1f })
        }
    }

    @Test
    fun alignedEdgeIsPreserved() {
        // p is exactly a function of the guide: the filter must not blur the edge.
        val guide = stepGuide(32)
        val p = FloatArray(w * h) { if (it % w < 32) 0f else 1f }
        val q = GuidedFilter.filter(guide, p, w, h, r = 4, eps = 1e-3f)
        for (y in 0 until h) {
            assertTrue("left of edge y=$y: ${q[y * w + 31]}", q[y * w + 31] < 0.05f)
            assertTrue("right of edge y=$y: ${q[y * w + 32]}", q[y * w + 32] > 0.95f)
        }
    }

    @Test
    fun misalignedMaskEdgeSnapsToGuideEdge() {
        // The coarse mask switches 2 px too late; the output must jump at the GUIDE edge, much more
        // sharply than a plain box blur of the mask (whose per-pixel steps are 1/(2r+1)).
        val guide = stepGuide(32)
        val p = FloatArray(w * h) { if (it % w < 34) 0f else 1f }
        val q = GuidedFilter.filter(guide, p, w, h, r = 4, eps = 1e-3f)
        val row = 8 * w
        var bestJump = 0f; var bestX = -1
        for (x in 1 until w) {
            val d = q[row + x] - q[row + x - 1]
            if (d > bestJump) { bestJump = d; bestX = x }
        }
        assertEquals(32, bestX)
        assertTrue("jump $bestJump", bestJump > 0.3f)
        assertTrue(q[row + 31] < 0.05f)
        val box = MaskOps.boxMean(p, w, h, 4)
        var boxJump = 0f
        for (x in 1 until w) boxJump = maxOf(boxJump, box[row + x] - box[row + x - 1])
        assertTrue(bestJump > 2.5f * boxJump)
    }

    @Test
    fun smoothsNoiseInFlatRegions() {
        val rnd = Random(9)
        val guide = FloatArray(w * h) { 0.5f }
        val p = FloatArray(w * h) { 0.5f + (rnd.nextFloat() - 0.5f) * 0.6f }
        val q = GuidedFilter.filter(guide, p, w, h, r = 4, eps = 1e-3f, clamp = false)
        fun variance(a: FloatArray): Double {
            val m = a.average()
            return a.sumOf { (it - m) * (it - m) } / a.size
        }
        assertTrue(variance(q) < variance(p) / 5)
        assertTrue(abs(q.average() - p.average()) < 0.01)
    }

    @Test
    fun coefficientsReproduceFilter() {
        val rnd = Random(11)
        val guide = FloatArray(w * h) { rnd.nextFloat() }
        val p = FloatArray(w * h) { rnd.nextFloat() }
        val (a, b) = GuidedFilter.coefficients(guide, p, w, h, 3, 1e-2f)
        val q = GuidedFilter.filter(guide, p, w, h, 3, 1e-2f, clamp = false)
        for (i in q.indices) assertEquals(a[i] * guide[i] + b[i], q[i], 1e-5f)
    }
}
