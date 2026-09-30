package com.brushwork.paint.segmentation

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.segmentation.SegTestImages.mean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class InteractiveSegmenterTest {
    private val n = InteractiveSegmenter.MODEL_SIZE

    /**
     * Fake prompted model: floods from the prior pixels through similar colors (so it returns the
     * tapped object only), and records every input it saw.
     */
    private class FloodModel : InteractiveModel {
        val inputs = ArrayList<PixelBuffer>()
        val priors = ArrayList<FloatArray>()
        override fun run(rgb: PixelBuffer, prior: FloatArray): FloatArray {
            inputs += rgb; priors += prior
            val size = rgb.width
            val passable = BooleanArray(size * size)
            val seed = (0 until size * size).firstOrNull { prior[it] > 0.5f } ?: return FloatArray(size * size)
            val sc = rgb.pixels[seed]
            for (i in passable.indices) passable[i] = colorDistance(rgb.pixels[i], sc) < 0.25f
            val reached = Regions.floodFrom(passable, size, size) { x, y -> prior[y * size + x] > 0.5f }
            return FloatArray(size * size) { if (reached[it]) 0.97f else 0.02f }
        }
    }

    private fun twoDiscs(w: Int, h: Int): PixelBuffer {
        val img = PixelBuffer.filled(w, h, SegTestImages.GRAY_BG)
        for ((cx, cy) in listOf(0.3f to 0.5f, 0.75f to 0.4f)) {
            val r = 0.07f * minOf(w, h)
            for (y in 0 until h) for (x in 0 until w) {
                val dx = x + 0.5f - cx * w; val dy = y + 0.5f - cy * h
                if (dx * dx + dy * dy <= r * r) img[x, y] = SegTestImages.RED
            }
        }
        return img
    }

    // ------------------------------------------------------------------ geometry

    @Test
    fun priorMarksTheTapAndTheScribble() {
        val crop = InteractiveSegmenter.Crop(100, 50, 300, 250) // 200 px -> 512: 2.56x
        val tap = InteractiveSegmenter.renderPrior(ObjectPrompt.tap(200f, 150f), crop)
        assertEquals(1f, tap[256 * n + 256], 0f)
        assertEquals(1f, tap[256 * n + 257], 0f)
        assertEquals(0f, tap[256 * n + 260], 0f)
        val marked = tap.count { it > 0f }
        assertTrue("disc of radius ${InteractiveSegmenter.PRIOR_RADIUS}: $marked px", marked in 9..20)
        val scribble = InteractiveSegmenter.renderPrior(ObjectPrompt(floatArrayOf(120f, 150f, 280f, 150f)), crop)
        for (x in 60..450 step 10) assertEquals("x=$x", 1f, scribble[256 * n + x], 0f)
        assertEquals(0f, scribble[240 * n + 256], 0f)
    }

    @Test
    fun firstCropIsTheWholeImageUnlessItIsVeryLong() {
        val c = InteractiveSegmenter.firstCrop(1280, 960, 10f, 10f)
        assertEquals(0, c.x0); assertEquals(1280, c.x1); assertEquals(960, c.y1)
        val pano = InteractiveSegmenter.firstCrop(1280, 400, 1200f, 200f)
        assertEquals(800, pano.width); assertEquals(1280, pano.x1)
        val tall = InteractiveSegmenter.firstCrop(300, 1280, 150f, 10f)
        assertEquals(0, tall.y0); assertEquals(600, tall.height)
    }

    @Test
    fun zoomCropFramesTheObjectWithAMargin() {
        val w = 400; val h = 300
        val comp = BooleanArray(w * h) { val x = it % w; val y = it / w; x in 100..139 && y in 100..119 }
        val z = InteractiveSegmenter.zoomCrop(comp, w, h)!!
        assertTrue(z.x0 <= 92 && z.x1 >= 148 && z.y0 <= 92 && z.y1 >= 128)
        assertTrue("aspect", z.width <= 2 * z.height && z.height <= 2 * z.width)
        assertTrue(z.x0 >= 0 && z.y0 >= 0 && z.x1 <= w && z.y1 <= h)
        assertNull(InteractiveSegmenter.zoomCrop(BooleanArray(w * h) { true }, w, h))
        assertNull(InteractiveSegmenter.zoomCrop(BooleanArray(w * h), w, h))
    }

    // ------------------------------------------------------------------ segmentation

    @Test
    fun tapSelectsOnlyTheTappedObjectWithAZoomedSecondPass() {
        val w = 1200; val h = 900
        val img = twoDiscs(w, h)
        val model = FloodModel()
        val p = InteractiveSegmenter.segment(img, img, ObjectPrompt.tap(360f, 450f), model) {}!!
        assertEquals(w * h, p.size)
        assertTrue(p[450 * w + 360] > 0.9f)
        assertEquals("the other disc", 0f, p[360 * w + 900], 0f)
        assertEquals(0f, p[50 * w + 50], 0f)
        assertEquals("zoom pass ran", 2, model.inputs.size)
        // The zoomed input shows the disc much larger: more red pixels.
        val red0 = model.inputs[0].pixels.count { it == SegTestImages.RED }
        val red1 = model.inputs[1].pixels.count { it == SegTestImages.RED }
        assertTrue("zoom $red0 -> $red1", red1 > 4 * red0)
        // The prior of both passes sits on the disc.
        for (k in 0..1) {
            val i = model.priors[k].indices.first { model.priors[k][it] > 0f }
            assertEquals(SegTestImages.RED, model.inputs[k].pixels[i])
        }
        // Fraction of the true disc recovered.
        val r = 0.07f * 900
        var inDisc = 0; var hit = 0
        for (y in 0 until h) for (x in 0 until w) {
            val dx = x + 0.5f - 360f; val dy = y + 0.5f - 450f
            if (dx * dx + dy * dy <= (r - 2) * (r - 2)) { inDisc++; if (p[y * w + x] >= 0.5f) hit++ }
        }
        assertTrue("$hit / $inDisc", hit > 0.97f * inDisc)
    }

    @Test
    fun tapJustBesideAThinObjectStillFindsIt() {
        val w = 400; val h = 300
        val img = PixelBuffer.filled(w, h, SegTestImages.GRAY_BG)
        for (y in 40 until 260) for (x in 200 until 204) img[x, y] = SegTestImages.RED
        // A model that answers with the red pixels only, whatever was tapped.
        val redOnly = InteractiveModel { rgb, _ -> FloatArray(rgb.size) { if (colorDistance(rgb.pixels[it], SegTestImages.RED) < 0.3f) 1f else 0f } }
        val p = InteractiveSegmenter.segment(img, img, ObjectPrompt.tap(207f, 150f), redOnly) {}!!
        assertTrue(p[150 * w + 201] > 0.5f)
        // Far away from anything: nothing.
        val none = InteractiveSegmenter.segment(img, img, ObjectPrompt.tap(40f, 150f), redOnly) {}!!
        assertTrue(none.all { it == 0f })
    }

    @Test
    fun brokenModelAnswersAreRejected() {
        val img = twoDiscs(200, 150)
        assertNull(InteractiveSegmenter.segment(img, img, ObjectPrompt.tap(60f, 75f), { _, _ -> FloatArray(3) }) {})
        assertNull(InteractiveSegmenter.segment(img, img, ObjectPrompt.tap(60f, 75f), { _, _ -> null }) {})
        assertNull(InteractiveSegmenter.segment(img, img, ObjectPrompt.tap(60f, 75f), { _, _ -> throw IllegalStateException("boom") }) {})
        // NaN is background.
        val nan = InteractiveSegmenter.segment(img, img, ObjectPrompt.tap(60f, 75f), { _, _ -> FloatArray(n * n) { Float.NaN } }) {}!!
        assertTrue(nan.all { it == 0f })
    }

    @Test
    fun fullResolutionDetailIsUsedForTheZoomPass() {
        // The disc is striped with 1 px rows of two reds: the half-size working copy averages
        // them into one color, the full image still has them. The zoom crop must show them.
        val full = twoDiscs(1600, 1200)
        val alt = SegTestImages.rgb(200, 70, 40)
        for (y in 0 until 1200 step 2) for (x in 0 until 1600) if (full[x, y] == SegTestImages.RED) full[x, y] = alt
        val work = MaskOps.resample(full, 800, 600)
        assertEquals(0, work.pixels.count { it == SegTestImages.RED })
        val model = FloodModel()
        InteractiveSegmenter.segment(work, full, ObjectPrompt.tap(240f, 300f), model) {}
        assertEquals(2, model.inputs.size)
        val z = model.inputs[1].pixels
        assertTrue("exact stripe pixels: ${z.count { it == SegTestImages.RED }}", z.count { it == SegTestImages.RED } > 1000)
    }

    // ------------------------------------------------------------------ color fallback

    @Test
    fun regionGrowSelectsTheTappedShadedObject() {
        val w = 300; val h = 200
        val img = PixelBuffer.filled(w, h, SegTestImages.GRAY_BG)
        for (y in 0 until h) for (x in 0 until w) {
            val dx = x - 150; val dy = y - 100
            if (dx * dx + dy * dy <= 60 * 60) {
                val shade = (dx + 60) / 4 // slow shading across the object
                img[x, y] = SegTestImages.rgb(200 - shade, 40, 40 + shade / 3)
            }
        }
        val m = RegionGrow.select(img, ObjectPrompt.tap(150f, 100f))
        assertTrue(mean(m, w, 130, 80, 170, 120) > 0.99f)
        assertTrue(m[100 * w + 100] > 0.5f && m[100 * w + 200] > 0.5f)
        assertEquals(0f, m[10 * w + 10], 0f)
        assertEquals(0f, m[100 * w + 250], 0f)
        // Scribbles seed every point; points off the image are ignored.
        val s = RegionGrow.select(img, ObjectPrompt(floatArrayOf(-50f, -50f, 150f, 100f)))
        assertTrue(s[100 * w + 150] > 0.5f)
    }

    @Test
    fun regionGrowCanBeCancelled() {
        val img = PixelBuffer.filled(800, 800, SegTestImages.GRAY_BG)
        var calls = 0
        try {
            RegionGrow.select(img, ObjectPrompt.tap(10f, 10f)) { if (++calls > 1) throw CancellationException("stop") }
            throw AssertionError("not cancelled")
        } catch (e: CancellationException) {
            assertTrue(calls >= 2)
        }
    }

    // ------------------------------------------------------------------ through the pipeline

    @Test
    fun pipelineSelectsTheObjectAtFullResolutionWithRefinedEdges() {
        val w = 1600; val h = 1200
        val img = twoDiscs(w, h)
        val model = FloodModel()
        val pipeline = SegmentationPipeline(null, null, model)
        val r = pipeline.selectObject(img, ObjectPrompt.tap(480f, 600f))
        assertTrue(r.usedModel)
        assertEquals(w * h, r.mask.size)
        assertTrue(r.mask[600 * w + 480] > 0.98f)
        assertTrue(r.mask[480 * w + 1200] < 0.02f)
        // The edge sits on the full-resolution disc edge (radius 84 px).
        assertTrue(r.mask[600 * w + 480 + 80] > 0.9f)
        assertTrue(r.mask[600 * w + 480 + 88] < 0.1f)
        // Without edge refinement the outline is the model's own (still at the right place).
        val raw = pipeline.selectObject(img, ObjectPrompt.tap(480f, 600f), refineEdges = false)
        assertTrue(raw.mask[600 * w + 480] > 0.98f && raw.mask[480 * w + 1200] < 0.02f)
    }

    @Test
    fun pipelineFallsBackToColorsWithoutAModelAndHonorsCancel() {
        val w = 400; val h = 300
        val img = twoDiscs(w, h)
        val pipeline = SegmentationPipeline(null, null, null)
        val r = pipeline.selectObject(img, ObjectPrompt.tap(120f, 150f))
        assertFalse(r.usedModel)
        assertTrue(r.mask[150 * w + 120] > 0.95f)
        assertTrue(r.mask[120 * w + 300] < 0.05f)
        // A model that is present but fails also falls back.
        val failing = SegmentationPipeline(null, null, InteractiveModel { _, _ -> null })
        val f = failing.selectObject(img, ObjectPrompt.tap(120f, 150f))
        assertFalse(f.usedModel)
        assertTrue(f.mask[150 * w + 120] > 0.95f)
        try {
            pipeline.selectObject(img, ObjectPrompt.tap(120f, 150f), cancelled = { true })
            throw AssertionError("not cancelled")
        } catch (e: CancellationException) {
            assertNotNull(e)
        }
    }

    @Test
    fun subjectWithoutMlKitTapsTheMostSalientObject() {
        val w = 800; val h = 600
        val img = SegTestImages.disc(w, h, SegTestImages.GRAY_BG, SegTestImages.RED, 0.2f)
        val model = FloodModel()
        val fg = SegmentationPipeline(null, null, model).segment(img, SmartTarget.SUBJECT)!!
        assertTrue("the object model was asked", model.inputs.isNotEmpty())
        assertTrue(fg[300 * w + 400] > 0.98f)
        assertTrue(mean(fg, w, 0, 0, 60, 60) < 0.02f)
        // The prior of the first pass is inside the disc.
        val i = model.priors[0].indices.first { model.priors[0][it] > 0f }
        assertEquals(SegTestImages.RED, model.inputs[0].pixels[i])
    }

    @Test
    fun subjectGetsAZoomedSecondPassOnLargePictures() {
        val w = 3200; val h = 2400
        val img = SegTestImages.disc(w, h, SegTestImages.GRAY_BG, SegTestImages.RED, 0.12f)
        val sizes = ArrayList<Pair<Int, Int>>()
        // An ideal subject model: red is the subject, at any scale.
        val backend = SubjectBackend { im ->
            sizes += im.width to im.height
            FloatArray(im.size) { if (colorDistance(im.pixels[it], SegTestImages.RED) < 0.3f) 1f else 0f }
        }
        val fg = SegmentationPipeline(null, backend).segment(img, SmartTarget.SUBJECT)!!
        assertEquals(2, sizes.size)
        assertEquals(1280 to 960, sizes[0])
        assertTrue("zoom input ${sizes[1]}", sizes[1].first in 600..1280 && kotlin.math.abs(sizes[1].first - sizes[1].second) <= 2)
        val r = 0.12f * 2400
        assertTrue(fg[1200 * w + 1600] > 0.98f)
        assertTrue(fg[1200 * w + (1600 + r - 4).toInt()] > 0.9f)
        assertTrue(fg[1200 * w + (1600 + r + 4).toInt()] < 0.1f)
    }
}
