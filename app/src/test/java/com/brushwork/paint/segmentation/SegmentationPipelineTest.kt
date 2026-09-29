package com.brushwork.paint.segmentation

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.segmentation.SegTestImages.mean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentationPipelineTest {

    /**
     * Fake scene model: labels each content pixel by its color (like a perfect but coarse model)
     * and fills the letterbox padding with SKY so that a missing crop would show up.
     */
    private class ColorParser : SceneParser {
        var calls = 0
        override fun parse(content: PixelBuffer, letterbox: Letterbox): ByteArray {
            calls++
            val s = letterbox.size
            val out = ByteArray(s * s) { SceneClasses.SKY.toByte() }
            for (y in 0 until letterbox.contentHeight) for (x in 0 until letterbox.contentWidth) {
                out[(y + letterbox.offsetY) * s + x + letterbox.offsetX] = classify(content[x, y]).toByte()
            }
            return out
        }

        private fun classify(c: Int): Int {
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            val luma = (r * 299 + g * 587 + b * 114) / 1000
            return when {
                g > r + 30 && g > b + 30 -> SceneClasses.TREE
                b > r + 50 && b > g + 30 -> if (luma > 110) SceneClasses.SKY else SceneClasses.SEA
                r > 180 && g in 130..200 && b in 100..170 -> SceneClasses.PERSON
                kotlin.math.abs(r - g) < 12 && kotlin.math.abs(g - b) < 16 ->
                    if (luma < 90) SceneClasses.WINDOWPANE else SceneClasses.BUILDING
                else -> SceneClasses.OTHER
            }
        }
    }

    /** Fake subject model: a centered disc whose radius is 25% of the short side. */
    private class DiscSubject : SubjectBackend {
        var calls = 0
        var lastSize = 0 to 0
        override fun subjectMask(image: PixelBuffer): FloatArray {
            calls++
            lastSize = image.width to image.height
            val r = minOf(image.width, image.height) * 0.25f
            return FloatArray(image.size) { i ->
                val dx = i % image.width + 0.5f - image.width / 2f
                val dy = i / image.width + 0.5f - image.height / 2f
                if (dx * dx + dy * dy <= r * r) 1f else 0f
            }
        }
    }

    @Test
    fun skyFollowsTheImageEdgeAtFullResolution() {
        // 2x the working resolution; the horizon (row 961) does not fall on the working grid.
        val w = 2560; val h = 1920
        val img = SegTestImages.skyOverFoliage(w, h, horizon = 961f / 1920f)
        val parser = ColorParser()
        val sky = SegmentationPipeline(parser, null).segment(img, SmartTarget.SKY)!!
        assertEquals(w * h, sky.size)
        assertTrue(mean(sky, w, 0, 0, w, 900) > 0.99f)
        assertTrue(mean(sky, w, 0, 1030, w, h) < 0.01f)
        // Sharp at the exact full-resolution row: a plain upsample would give ~0.5 on both rows.
        val above = mean(sky, w, 0, 960, w, 961)
        val below = mean(sky, w, 0, 961, w, 962)
        assertTrue("row 960 = $above", above > 0.9f)
        assertTrue("row 961 = $below", below < 0.25f)
    }

    @Test
    fun letterboxPaddingIsIgnored() {
        val img = SegTestImages.skyOverFoliage(300, 200, horizon = 0f) // all foliage
        val sky = SegmentationPipeline(ColorParser(), null).segment(img, SmartTarget.SKY)!!
        assertTrue(sky.all { it == 0f })
    }

    @Test
    fun modelThatFindsNothingWinsOverHeuristics() {
        val img = SegTestImages.skyOverFoliage(300, 200)
        val nothing = SceneParser { _, lb -> ByteArray(lb.size * lb.size) }
        val sky = SegmentationPipeline(nothing, null).segment(img, SmartTarget.SKY)!!
        assertTrue(sky.all { it == 0f })
    }

    @Test
    fun heuristicsWhenNoModelIsAvailable() {
        val w = 400; val h = 300
        val img = SegTestImages.skyOverFoliage(w, h)
        val pipeline = SegmentationPipeline(null, null)
        val sky = pipeline.segment(img, SmartTarget.SKY)!!
        assertTrue(mean(sky, w, 0, 0, w, h / 2 - 10) > 0.9f)
        assertTrue(mean(sky, w, 0, h / 2 + 10, w, h) < 0.05f)
        val nature = pipeline.segment(img, SmartTarget.NATURE)!!
        assertTrue(mean(nature, w, 0, h / 2 + 10, w, h) > 0.8f)
        assertTrue(mean(nature, w, 0, 0, w, h / 2 - 10) < 0.05f)
    }

    @Test
    fun parserErrorsFallBackToHeuristics() {
        val w = 300; val h = 200
        val logged = mutableListOf<String>()
        val broken = SceneParser { _, _ -> throw IllegalStateException("boom") }
        val pipeline = SegmentationPipeline(broken, null, log = { msg, _ -> logged += msg })
        val sky = pipeline.segment(SegTestImages.skyOverFoliage(w, h), SmartTarget.SKY)!!
        assertTrue(mean(sky, w, 0, 0, w, h / 2 - 10) > 0.9f)
        assertTrue(logged.isNotEmpty())
        // Wrong output size is rejected too.
        val short = SceneParser { _, _ -> ByteArray(10) }
        assertNotNull(SegmentationPipeline(short, null).segment(SegTestImages.skyOverFoliage(w, h), SmartTarget.SKY))
    }

    @Test
    fun sceneTargetsUseTheirClassSets() {
        val w = 640; val h = 480
        val img = SegTestImages.seascape(w, h)
        val pipeline = SegmentationPipeline(ColorParser(), null)
        val water = pipeline.segment(img, SmartTarget.WATER)!!
        assertTrue(mean(water, w, 0, (h * 0.62f).toInt(), w, h) > 0.97f)
        assertTrue(mean(water, w, 0, 0, w, (h * 0.5f).toInt()) < 0.02f)
        val nature = pipeline.segment(img, SmartTarget.NATURE)!!
        assertTrue(mean(nature, w, 0, (h * 0.43f).toInt(), w, (h * 0.52f).toInt()) > 0.9f)
        val people = pipeline.segment(img, SmartTarget.PEOPLE)!!
        assertTrue(people.all { it == 0f })
    }

    @Test
    fun buildingsIncludeFacadeWindows() {
        val w = 512; val h = 384
        val img = SegTestImages.facade(w, h)
        val b = SegmentationPipeline(ColorParser(), null).segment(img, SmartTarget.BUILDINGS)!!
        assertTrue(mean(b, w, 0, (h * 0.5f).toInt(), w, h) > 0.95f) // walls AND windows
        assertTrue(mean(b, w, 0, 0, w, (h * 0.4f).toInt()) < 0.02f)
    }

    @Test
    fun subjectComesFromTheBackendAndBackgroundIsItsComplement() {
        val w = 800; val h = 600
        val img = SegTestImages.disc(w, h, SegTestImages.GRAY_BG, SegTestImages.RED, 0.25f)
        val subject = DiscSubject()
        val pipeline = SegmentationPipeline(ColorParser(), subject)
        val fg = pipeline.segment(img, SmartTarget.SUBJECT)!!
        val bg = pipeline.segment(img, SmartTarget.BACKGROUND)!!
        assertEquals(1, subject.calls) // cached for BACKGROUND
        assertEquals(800 to 600, subject.lastSize)
        assertTrue(fg[300 * w + 400] > 0.98f)
        assertTrue(fg[10 * w + 10] < 0.02f)
        for (i in fg.indices) assertEquals(1f, fg[i] + bg[i], 1e-6f)
    }

    @Test
    fun subjectFallsBackToSaliencyWhenTheBackendFindsNothing() {
        val w = 400; val h = 300
        val img = SegTestImages.disc(w, h, SegTestImages.GRAY_BG, SegTestImages.RED, 0.2f)
        val empty = SubjectBackend { FloatArray(it.size) }
        for (pipeline in listOf(SegmentationPipeline(ColorParser(), empty), SegmentationPipeline(null, null))) {
            val fg = pipeline.segment(img, SmartTarget.SUBJECT)!!
            assertTrue(fg[150 * w + 200] > 0.95f)
            assertTrue(mean(fg, w, 0, 0, 40, 40) < 0.02f)
        }
    }

    @Test
    fun smallImagesGiveTheSubjectModelEnoughPixels() {
        val subject = DiscSubject()
        val img = SegTestImages.disc(200, 150, SegTestImages.GRAY_BG, SegTestImages.RED, 0.25f)
        val fg = SegmentationPipeline(null, subject).segment(img, SmartTarget.SUBJECT)!!
        assertEquals(512 to 384, subject.lastSize)
        assertEquals(200 * 150, fg.size)
        assertTrue(fg[75 * 200 + 100] > 0.95f)
    }

    @Test
    fun analysisIsSharedBetweenTargetsOfTheSameImage() {
        val parser = ColorParser()
        val pipeline = SegmentationPipeline(parser, null)
        val img = SegTestImages.seascape(320, 240)
        for (t in listOf(SmartTarget.SKY, SmartTarget.NATURE, SmartTarget.BUILDINGS, SmartTarget.WATER, SmartTarget.PEOPLE)) {
            pipeline.segment(img, t)
        }
        assertEquals(1, parser.calls)
        val edited = img.copy().also { it[5, 5] = 0xFFFF0000.toInt() }
        pipeline.segment(edited, SmartTarget.SKY)
        assertEquals(2, parser.calls)
        pipeline.clearCache()
        pipeline.segment(edited, SmartTarget.SKY)
        assertEquals(3, parser.calls)
    }

    @Test
    fun resultsAreFreshArrays() {
        val pipeline = SegmentationPipeline(ColorParser(), DiscSubject())
        val img = SegTestImages.skyOverFoliage(300, 200)
        for (t in SmartTarget.entries) {
            val first = pipeline.segment(img, t)!!
            val copy = first.copyOf()
            first.fill(0.5f)
            assertArrayEquals("$t", copy, pipeline.segment(img, t)!!, 0f)
        }
    }

    @Test
    fun tinyAndDegenerateImages() {
        val backends = listOf(
            SegmentationPipeline(ColorParser(), DiscSubject()),
            SegmentationPipeline(null, null),
        )
        val images = listOf(
            PixelBuffer.filled(1, 1, SegTestImages.SKY_BLUE),
            PixelBuffer.filled(3, 2, 0x00000000),
            PixelBuffer.filled(4000, 2, SegTestImages.FOLIAGE),
            PixelBuffer.filled(2, 1500, SegTestImages.GRAY_BG),
        )
        for (p in backends) for (img in images) for (t in SmartTarget.entries) {
            val m = p.segment(img, t)
            assertNotNull("$t ${img.width}x${img.height}", m)
            assertEquals(img.size, m!!.size)
            assertTrue(m.all { it in 0f..1f })
        }
    }
}
