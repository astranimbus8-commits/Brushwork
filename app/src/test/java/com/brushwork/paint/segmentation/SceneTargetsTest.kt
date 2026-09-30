package com.brushwork.paint.segmentation

import com.brushwork.paint.core.PixelBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneTargetsTest {
    private val c = SceneClasses.COUNT

    /** A probability grid built from a per-cell function returning class -> probability. */
    private fun grid(w: Int, h: Int, f: (x: Int, y: Int) -> Map<Int, Float>): FloatArray {
        val out = FloatArray(w * h * c)
        for (y in 0 until h) for (x in 0 until w) {
            val m = f(x, y)
            val rest = (1f - m.values.sum()) / (c - m.size)
            for (k in 0 until c) out[(y * w + x) * c + k] = m[k] ?: rest
        }
        return out
    }

    @Test
    fun classUnionsBeatAnArgmax() {
        // Water 0.35 + sea 0.35 loses to nothing once summed, though floor (0.3) would win an argmax.
        val probs = grid(4, 4) { _, _ -> mapOf(SceneClasses.WATER to 0.35f, SceneClasses.SEA to 0.35f, 4 to 0.29f) }
        val p = SceneTargets.probability(probs, 4, 4, SmartTarget.WATER, null)
        assertTrue(p.all { it > 0.69f })
        val nature = grid(2, 1) { _, _ -> mapOf(SceneClasses.TREE to 0.3f, SceneClasses.GRASS to 0.3f, SceneClasses.PLANT to 0.2f) }
        assertTrue(SceneTargets.probability(nature, 2, 1, SmartTarget.NATURE, null).all { it > 0.79f })
    }

    @Test
    fun waterAdoptsLookalikeOtherBelowTheHorizonWhenConnected() {
        val w = 20; val h = 20
        val lake = SegTestImages.rgb(40, 90, 140)
        val cells = PixelBuffer(w, h)
        // Rows 0..5 sky, 6..19 below the horizon: columns 0..9 labelled water, 10..14 "other" that
        // looks like the water (a river the model has no class for), 15..19 "other" in red.
        val probs = grid(w, h) { x, y ->
            when {
                y < 6 -> mapOf(SceneClasses.SKY to 0.95f)
                x < 10 -> mapOf(SceneClasses.WATER to 0.9f)
                else -> mapOf(SceneClasses.OTHER to 0.9f)
            }
        }
        for (y in 0 until h) for (x in 0 until w) {
            cells[x, y] = when {
                y < 6 -> SegTestImages.SKY_BLUE
                x < 15 -> lake
                else -> SegTestImages.RED
            }
        }
        val p = SceneTargets.probability(probs, w, h, SmartTarget.WATER, cells)
        assertTrue(p[10 * w + 12] > 0.9f) // adopted
        assertTrue(p[10 * w + 17] < 0.1f) // wrong color
        assertTrue(p[2 * w + 12] < 0.1f) // sky
        // Not connected to the water: not adopted.
        val probs2 = grid(w, h) { x, y ->
            when {
                y < 6 -> mapOf(SceneClasses.SKY to 0.95f)
                x < 10 -> mapOf(SceneClasses.WATER to 0.9f)
                x < 12 -> mapOf(SceneClasses.TREE to 0.9f)
                else -> mapOf(SceneClasses.OTHER to 0.9f)
            }
        }
        val p2 = SceneTargets.probability(probs2, w, h, SmartTarget.WATER, cells)
        assertTrue(p2[10 * w + 13] < 0.1f)
    }

    @Test
    fun natureAdoptsGreenOtherRegionsEnclosedByVegetationButNotObjectsOnTheLawn() {
        val w = 16; val h = 16
        fun flowers(x: Int, y: Int) = x in 2..5 && y in 6..9
        fun dog(x: Int, y: Int) = x in 9..12 && y in 9..12
        fun gap(x: Int, y: Int) = x == 7 && y == 3
        fun edgeThing(x: Int, y: Int) = x >= 13 && y < 4
        // In the grass: a flower bed and a dog (both "other" to the model), a one-cell gap, and an
        // "other" object touching the image edge.
        val probs = grid(w, h) { x, y ->
            if (flowers(x, y) || dog(x, y) || gap(x, y) || edgeThing(x, y)) mapOf(SceneClasses.OTHER to 0.9f)
            else mapOf(SceneClasses.GRASS to 0.9f)
        }
        // Vegetation heuristic: the flower bed's leaves are partly green, the dog is not.
        val veg = FloatArray(w * h) { i ->
            val x = i % w; val y = i / w
            when {
                flowers(x, y) -> 0.35f
                dog(x, y) || gap(x, y) || edgeThing(x, y) -> 0f
                else -> 0.9f
            }
        }
        val p = SceneTargets.probability(probs, w, h, SmartTarget.NATURE, null, veg)
        assertTrue("flower bed", p[7 * w + 3] > 0.9f)
        assertTrue("a dog on the lawn is not nature", p[10 * w + 10] < 0.1f)
        assertTrue("gap", p[3 * w + 7] > 0.9f)
        assertTrue("touches the edge, not green", p[1 * w + 14] < 0.1f)
        // Without a vegetation score only the tiny gap is adopted.
        val bare = SceneTargets.probability(probs, w, h, SmartTarget.NATURE, null)
        assertTrue(bare[7 * w + 3] < 0.1f)
        assertTrue(bare[10 * w + 10] < 0.1f)
        assertTrue(bare[3 * w + 7] > 0.9f)
        // An edge-touching green "other" region bordering grass is adopted.
        val greenEdge = FloatArray(w * h) { if (edgeThing(it % w, it / w)) 0.8f else veg[it] }
        val p2 = SceneTargets.probability(probs, w, h, SmartTarget.NATURE, null, greenEdge)
        assertTrue(p2[1 * w + 14] > 0.9f)
    }

    @Test
    fun buildingsIncludeAttachedWindowsOnly() {
        val w = 12; val h = 6
        val probs = grid(w, h) { x, _ ->
            when {
                x < 4 -> mapOf(SceneClasses.BUILDING to 0.9f)
                x < 6 -> mapOf(SceneClasses.WINDOWPANE to 0.9f) // on the facade
                x < 9 -> mapOf(SceneClasses.WALL to 0.9f)
                else -> mapOf(SceneClasses.WINDOWPANE to 0.9f) // indoor window
            }
        }
        val p = SceneTargets.probability(probs, w, h, SmartTarget.BUILDINGS, null)
        assertTrue(p[2 * w + 4] > 0.9f)
        assertTrue(p[2 * w + 10] < 0.1f)
    }

    @Test
    fun hysteresisKeepsSeededRegionsWithSoftEdgesAndDropsTheRest() {
        val w = 20; val h = 10
        val p = FloatArray(w * h)
        // Region A: seeded (0.8) with a 0.4 shoulder and a 0.2 fringe; region B: 0.45 everywhere.
        for (y in 2..7) for (x in 1..6) p[y * w + x] = 0.8f
        for (y in 2..7) p[y * w + 7] = 0.4f
        for (y in 2..7) p[y * w + 8] = 0.2f
        p[4 * w + 3] = 0.1f // a one-cell hole
        for (y in 2..7) for (x in 13..17) p[y * w + x] = 0.45f
        val out = Hysteresis.apply(p, w, h, high = 0.6f, low = 0.35f, minSize = 2)
        assertEquals(0.8f, out[3 * w + 3], 0f)
        assertEquals(0.4f, out[3 * w + 7], 0f)
        assertEquals("soft margin kept", 0.2f, out[3 * w + 8], 0f)
        assertTrue("hole filled", out[4 * w + 3] >= 0.6f)
        assertEquals("unseeded region dropped", 0f, out[4 * w + 15], 0f)
        assertEquals(0f, out[3 * w + 10], 0f)
        // No seed at all: nothing.
        assertTrue(Hysteresis.apply(FloatArray(w * h) { 0.5f }, w, h).all { it == 0f })
        // A single seeded cell survives only if big enough.
        val dot = FloatArray(w * h).also { it[5 * w + 10] = 0.9f }
        assertTrue(Hysteresis.apply(dot, w, h, minSize = 2).all { it == 0f })
        assertEquals(0.9f, Hysteresis.apply(dot, w, h, minSize = 1)[5 * w + 10], 0f)
    }
}
