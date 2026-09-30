package com.brushwork.paint.segmentation

import com.brushwork.paint.core.PixelBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class ColorGuidedFilterTest {
    private val w = 64
    private val h = 16

    /** Two colors with (almost exactly) the same Rec.601 luma: a blue and an orange. */
    private val blue = SegTestImages.rgb(60, 110, 230)   // luma ~ 0.426
    private val orange = SegTestImages.rgb(210, 74, 20)  // luma ~ 0.426

    private fun planes(img: PixelBuffer) = ColorPlanes.of(img)

    private fun split(edge: Int, left: Int, right: Int) = PixelBuffer(w, h).also { img ->
        for (y in 0 until h) for (x in 0 until w) img[x, y] = if (x < edge) left else right
    }

    @Test
    fun theTwoTestColorsReallyAreIsoLuminant() {
        assertEquals(MaskOps.luma01(blue), MaskOps.luma01(orange), 0.01f)
    }

    @Test
    fun constantInputIsPreserved() {
        val rnd = Random(3)
        val img = PixelBuffer(w, h).also { for (i in 0 until w * h) it.pixels[i] = SegTestImages.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)) }
        val q = ColorGuidedFilter.filter(planes(img), FloatArray(w * h) { 0.37f }, r = 4, eps = 1e-3f)
        for (v in q) assertEquals(0.37f, v, 1e-3f)
    }

    @Test
    fun outputStaysInRange() {
        val rnd = Random(5)
        val img = PixelBuffer(w, h).also { for (i in 0 until w * h) it.pixels[i] = SegTestImages.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)) }
        val p = FloatArray(w * h) { if (rnd.nextBoolean()) 1f else 0f }
        for (r in listOf(1, 3, 8, 40)) assertTrue(ColorGuidedFilter.filter(planes(img), p, r, eps = 1e-4f).all { it in 0f..1f })
    }

    /** Position and size of the largest downward step of row 8 of [q]. */
    private fun biggestDrop(q: FloatArray): Pair<Int, Float> {
        val row = 8 * w
        var best = 0f; var at = -1
        for (x in 1 until w) {
            val d = q[row + x - 1] - q[row + x]
            if (d > best) { best = d; at = x }
        }
        return at to best
    }

    @Test
    fun separatesIsoLuminantColorsThatAGrayGuideCannotSee() {
        // The coarse mask switches 3 px too late; the output must drop at the COLOR edge.
        val img = split(32, blue, orange)
        val p = FloatArray(w * h) { if (it % w < 35) 1f else 0f }
        val q = ColorGuidedFilter.filter(planes(img), p, r = 4, eps = 1e-4f)
        val (at, drop) = biggestDrop(q)
        assertEquals(32, at)
        assertTrue("drop $drop", drop > 0.3f)
        assertTrue("left of edge ${q[8 * w + 31]}", q[8 * w + 31] > 0.95f)
        // A luma-only guide is flat here, so its guided filter degenerates to a box blur whose
        // steps are ~1/(2r+1): it cannot see this edge at all.
        val gray = PixelBuffer(w, h).also { for (i in 0 until w * h) it.pixels[i] = MaskOps.luma01(img.pixels[i]).let { l -> val v = (l * 255).toInt(); SegTestImages.rgb(v, v, v) } }
        val g = ColorGuidedFilter.filter(planes(gray), p, r = 4, eps = 1e-4f)
        val (gAt, gDrop) = biggestDrop(g)
        assertTrue("gray drop $gDrop at $gAt", gDrop < 0.15f)
        assertTrue(drop > 2.5f * gDrop)
    }

    @Test
    fun smoothsNoiseInFlatRegions() {
        val rnd = Random(9)
        val img = PixelBuffer.filled(w, h, SegTestImages.GRAY_BG)
        val p = FloatArray(w * h) { 0.5f + (rnd.nextFloat() - 0.5f) * 0.6f }
        val q = ColorGuidedFilter.filter(planes(img), p, r = 4, eps = 1e-3f, clamp = false)
        fun variance(a: FloatArray): Double { val m = a.average(); return a.sumOf { (it - m) * (it - m) } / a.size }
        assertTrue(variance(q) < variance(p) / 5)
        assertTrue(abs(q.average() - p.average()) < 0.01)
    }

    @Test
    fun coefficientsReproduceTheFilterAndUpsampleAtTheSameSize() {
        val rnd = Random(11)
        val img = PixelBuffer(w, h).also { for (i in 0 until w * h) it.pixels[i] = SegTestImages.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)) }
        val pl = planes(img)
        val p = FloatArray(w * h) { rnd.nextFloat() }
        val k = ColorGuidedFilter.coefficients(pl, p, 3, 1e-2f)
        val q = ColorGuidedFilter.filter(pl, p, 3, 1e-2f)
        val up = ColorGuidedFilter.upsample(img, k)
        for (i in q.indices) {
            val direct = MaskOps.clamp01(k.aR[i] * pl.r[i] + k.aG[i] * pl.g[i] + k.aB[i] * pl.b[i] + k.b[i])
            assertEquals(direct, q[i], 1e-5f)
            assertEquals(direct, up[i], 1e-5f)
        }
    }

    @Test
    fun upsampleFollowsFullResolutionColorEdges() {
        // Coefficients at 1/4 resolution; the full-resolution edge falls between low-res pixels.
        val fw = 256; val fh = 32; val edge = 129
        val full = PixelBuffer(fw, fh).also { for (y in 0 until fh) for (x in 0 until fw) it[x, y] = if (x < edge) blue else orange }
        val small = MaskOps.resample(full, fw / 4, fh / 4)
        val sp = planes(small)
        val mask = FloatArray(small.size) { i -> MaskOps.clamp01(1f - ((i % small.width) * 4 + 2 - edge + 2) / 4f) }
        val k = ColorGuidedFilter.coefficients(sp, mask, 2, 1e-4f)
        val q = ColorGuidedFilter.upsample(full, k)
        val row = 16 * fw
        assertTrue("x=${edge - 1}: ${q[row + edge - 1]}", q[row + edge - 1] > 0.85f)
        assertTrue("x=$edge: ${q[row + edge]}", q[row + edge] < 0.15f)
        assertTrue(q.all { it in 0f..1f })
    }
}
