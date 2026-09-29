package com.brushwork.paint.segmentation

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.segmentation.SegTestImages.mean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SceneHeuristicsTest {

    @Test
    fun blueTopHalfIsSky() {
        for ((w, h) in listOf(64 to 48, 200 to 150, 512 to 384)) {
            val img = SegTestImages.skyOverFoliage(w, h)
            val sky = SceneHeuristics.sky(img)
            assertEquals(w * h, sky.size)
            val top = mean(sky, w, 0, 0, w, h / 2 - 2)
            val bottom = mean(sky, w, 0, h / 2 + 2, w, h)
            assertTrue("$w x $h top=$top", top > 0.9f)
            assertTrue("$w x $h bottom=$bottom", bottom < 0.05f)
        }
    }

    @Test
    fun skyMustTouchTheTopEdge() {
        // A blue lake at the bottom below foliage is not sky.
        val w = 160; val h = 120
        val rnd = Random(4)
        val img = PixelBuffer(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            img[x, y] = if (y < 70) SegTestImages.noisy(SegTestImages.FOLIAGE, 28, rnd) else SegTestImages.noisy(SegTestImages.SKY_BLUE, 2, rnd)
        }
        val sky = SceneHeuristics.sky(img)
        assertTrue(sky.all { it < 0.01f })
    }

    @Test
    fun greenTextureIsVegetation() {
        val w = 200; val h = 150
        val img = SegTestImages.skyOverFoliage(w, h)
        val veg = SceneHeuristics.vegetation(img)
        val top = mean(veg, w, 0, 0, w, h / 2 - 2)
        val bottom = mean(veg, w, 0, h / 2 + 2, w, h)
        assertTrue("bottom=$bottom", bottom > 0.85f)
        assertTrue("top=$top", top < 0.05f)
    }

    @Test
    fun cyanAndGrayAreNotVegetation() {
        val rnd = Random(6)
        val cyan = PixelBuffer(80, 60).also { img -> for (i in img.pixels.indices) img.pixels[i] = SegTestImages.noisy(SegTestImages.rgb(20, 190, 190), 20, rnd) }
        assertTrue(SceneHeuristics.vegetation(cyan).average() < 0.05)
        val rock = PixelBuffer(80, 60).also { img -> for (i in img.pixels.indices) img.pixels[i] = SegTestImages.noisy(0xFF707070.toInt(), 30, rnd) }
        assertTrue(SceneHeuristics.vegetation(rock).average() < 0.05)
    }

    @Test
    fun seaBelowTheHorizonIsWater() {
        val w = 200; val h = 160
        val img = SegTestImages.seascape(w, h)
        val water = SceneHeuristics.water(img)
        val sea = mean(water, w, 0, (h * 0.6f).toInt(), w, h)
        val sky = mean(water, w, 0, 0, w, (h * 0.38f).toInt())
        val land = mean(water, w, 0, (h * 0.43f).toInt(), w, (h * 0.53f).toInt())
        assertTrue("sea=$sea", sea > 0.8f)
        assertTrue("sky=$sky", sky < 0.05f)
        assertTrue("land=$land", land < 0.1f)
    }

    @Test
    fun windowGridIsBuilding() {
        val w = 256; val h = 192
        val img = SegTestImages.facade(w, h)
        val b = SceneHeuristics.buildings(img)
        val facade = mean(b, w, 0, (h * 0.55f).toInt(), w, h)
        val sky = mean(b, w, 0, 0, w, (h * 0.4f).toInt())
        assertTrue("facade=$facade", facade > 0.6f)
        assertTrue("sky=$sky", sky < 0.05f)
        // Foliage is not a building even though it is full of edges.
        val veg = SegTestImages.skyOverFoliage(w, h)
        assertTrue(mean(SceneHeuristics.buildings(veg), w, 0, h / 2 + 4, w, h) < 0.1f)
    }

    @Test
    fun skinDiscIsPeople() {
        val w = 160; val h = 120
        val img = SegTestImages.disc(w, h, SegTestImages.SKY_BLUE, SegTestImages.SKIN, 0.25f)
        val people = SceneHeuristics.people(img)
        assertTrue(people[60 * w + 80] > 0.9f)
        assertTrue(mean(people, w, 0, 0, 20, 20) < 0.02f)
        assertTrue(SceneHeuristics.skinLikelihood(SegTestImages.SKIN) > 0.9f)
        assertTrue(SceneHeuristics.skinLikelihood(SegTestImages.SKY_BLUE) < 0.01f)
        assertTrue(SceneHeuristics.skinLikelihood(0xFF30A030.toInt()) < 0.01f)
    }

    @Test
    fun salientObjectContainingSkinCountsAsPerson() {
        // A red "shirt" disc with a skin "face" disc on top, on gray: the whole figure is a person.
        val w = 200; val h = 160
        val img = SegTestImages.disc(w, h, SegTestImages.GRAY_BG, SegTestImages.RED, 0.3f, cy = 0.6f)
        val face = SegTestImages.disc(w, h, 0, SegTestImages.SKIN, 0.12f, cy = 0.3f)
        for (i in img.pixels.indices) if (face.pixels[i] != 0) img.pixels[i] = face.pixels[i]
        val sal = SceneHeuristics.saliency(img)
        val people = SceneHeuristics.people(img, sal)
        assertTrue("shirt ${people[(h * 0.7f).toInt() * w + w / 2]}", people[(h * 0.7f).toInt() * w + w / 2] > 0.9f)
        assertTrue("face", people[(h * 0.3f).toInt() * w + w / 2] > 0.9f)
        assertTrue(mean(people, w, 0, 0, 15, 15) < 0.02f)
    }

    @Test
    fun redDiscOnGrayIsSalient() {
        val w = 200; val h = 150
        val img = SegTestImages.disc(w, h, SegTestImages.GRAY_BG, SegTestImages.RED, 0.2f)
        val sal = SceneHeuristics.saliency(img)
        assertTrue(sal[75 * w + 100] > 0.95f)
        assertTrue(mean(sal, w, 0, 0, 30, 30) < 0.01f)
        assertTrue(mean(sal, w, 170, 120, 200, 150) < 0.01f)
        // Hollow ring: the enclosed hole is filled (the subject is the whole object).
        val ring = SegTestImages.disc(w, h, SegTestImages.GRAY_BG, SegTestImages.RED, 0.3f)
        val hole = SegTestImages.disc(w, h, 0, SegTestImages.GRAY_BG, 0.12f)
        for (i in ring.pixels.indices) if (hole.pixels[i] != 0) ring.pixels[i] = hole.pixels[i]
        assertTrue(SceneHeuristics.saliency(ring)[75 * w + 100] > 0.95f)
    }

    @Test
    fun uniformImageHasNoSubject() {
        val rnd = Random(8)
        val img = PixelBuffer(120, 90).also { for (i in it.pixels.indices) it.pixels[i] = SegTestImages.noisy(0xFFB0B0B0.toInt(), 2, rnd) }
        assertTrue(SceneHeuristics.saliency(img).all { it == 0f })
    }

    @Test
    fun heuristicsHandleTinyImages() {
        for ((w, h) in listOf(1 to 1, 2 to 1, 3 to 7)) {
            val img = PixelBuffer.filled(w, h, SegTestImages.SKY_BLUE)
            for (m in listOf(
                SceneHeuristics.sky(img), SceneHeuristics.vegetation(img), SceneHeuristics.buildings(img),
                SceneHeuristics.water(img), SceneHeuristics.people(img), SceneHeuristics.saliency(img),
            )) {
                assertEquals(w * h, m.size)
                assertTrue(m.all { it in 0f..1f })
            }
        }
    }

    @Test
    fun otsuSplitsBimodalValues() {
        val v = FloatArray(1000) { if (it < 600) 0.1f + (it % 7) * 0.01f else 0.8f + (it % 5) * 0.01f }
        val t = SceneHeuristics.otsu(v)
        assertTrue("t=$t", t > 0.15f && t < 0.8f)
    }
}
