package com.brushwork.paint.segmentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The load-time check of the rewired scene model: its logits must agree with the original
 * argmax model, which a misrouted tensor or a wrong channel order cannot.
 */
class SceneSelfTestTest {
    private val c = SceneClasses.COUNT
    private val size = 512
    private val g = 64

    /** Logits on a g x g grid: [classAt] per cell gets 3 (after dequantization), the rest 0. */
    private fun logits(classAt: (cx: Int, cy: Int) -> Int): SceneScores.Logits {
        val v = FloatArray(g * g * c)
        for (cy in 0 until g) for (cx in 0 until g) v[(cy * g + cx) * c + classAt(cx, cy)] = 3f
        return SceneScores.Logits(g, g, v)
    }

    /** The fused-argmax output for a sky / tree boundary at input row [boundary]. */
    private fun labels(boundary: Int) = ByteArray(size * size) { i ->
        (if (i / size < boundary) SceneClasses.SKY else SceneClasses.TREE).toByte()
    }

    private val skyOverTrees = logits { _, cy -> if (cy < g / 2) SceneClasses.SKY else SceneClasses.TREE }

    @Test
    fun aCorrectlyRewiredModelAgreesEvenWhenTheFinalizerShiftsTheBoundary() {
        assertEquals(1f, SceneSelfTest.argmaxAgreement(skyOverTrees, labels(256), size), 0f)
        // The finalizer's resize may move a boundary by up to a cell (8 input px) either way.
        for (b in listOf(248, 252, 260, 264)) {
            assertTrue("boundary $b", SceneSelfTest.argmaxAgreement(skyOverTrees, labels(b), size) >= 0.98f)
        }
    }

    @Test
    fun misroutedOrPermutedOutputsFail() {
        // Channels shifted by one (wrong channel order): no cell agrees.
        val shifted = logits { _, cy -> (if (cy < g / 2) SceneClasses.SKY else SceneClasses.TREE) + 1 }
        assertEquals(0f, SceneSelfTest.argmaxAgreement(shifted, labels(256), size), 0f)
        // Rows flipped (e.g. a transposed or reversed tensor): only a sliver agrees.
        val flipped = logits { _, cy -> if (cy >= g / 2) SceneClasses.SKY else SceneClasses.TREE }
        assertTrue(SceneSelfTest.argmaxAgreement(flipped, labels(256), size) < SceneSelfTest.MIN_AGREEMENT)
        // A constant answer only matches the half it happens to name.
        val constant = logits { _, _ -> SceneClasses.SKY }
        assertEquals(0.5f, SceneSelfTest.argmaxAgreement(constant, labels(256), size), 0.01f)
        // Malformed inputs never pass.
        assertEquals(0f, SceneSelfTest.argmaxAgreement(SceneScores.Logits(g, g, FloatArray(10)), labels(256), size), 0f)
        assertEquals(0f, SceneSelfTest.argmaxAgreement(skyOverTrees, ByteArray(7), size), 0f)
    }

    @Test
    fun theFallbackCheckWantsTheTwoHalvesToDiffer() {
        assertTrue(SceneSelfTest.halvesDiffer(skyOverTrees))
        assertFalse(SceneSelfTest.halvesDiffer(logits { _, _ -> SceneClasses.GRASS }))
        assertFalse(SceneSelfTest.halvesDiffer(SceneScores.Logits(g, g, FloatArray(3))))
    }

    @Test
    fun theSyntheticSceneIsSkyOverGreenGround() {
        val img = SceneSelfTest.image(size)
        assertEquals(size, img.width)
        assertEquals(size, img.height)
        val top = img[100, 60]; val bottom = img[100, 400]
        assertTrue((top and 0xFF) > ((top shr 16) and 0xFF) + 80) // blue
        assertTrue(((bottom shr 8) and 0xFF) > (bottom and 0xFF) + 40) // green
        assertTrue(img.pixels.all { it ushr 24 == 0xFF })
    }
}
