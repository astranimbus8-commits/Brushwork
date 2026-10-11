package com.brushwork.paint.filters.art

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterValues
import com.brushwork.paint.filters.art.FringeFixtures.contexts
import com.brushwork.paint.filters.art.FringeFixtures.channelOf
import com.brushwork.paint.filters.art.FringeFixtures.gradient
import com.brushwork.paint.filters.art.FringeFixtures.inverse
import com.brushwork.paint.filters.art.FringeFixtures.permute
import com.brushwork.paint.filters.art.FringeFixtures.random
import com.brushwork.paint.filters.art.v17.ChromaticAberrationV17
import com.brushwork.paint.filters.art.v17.GlitchV17
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.8 (item 5, design §3.6 (c), §5.1 A): the fast path is v1.7, byte for byte. For each of the
 * six v1.7 color orders, given as fringe colors, and for the default (RGB), Chromatic aberration
 * Moving and Zooming equal F0's frozen v1.7 copies ([ChromaticAberrationV17]) on a random picture
 * with transparent and translucent pixels and on a gradient, at scales 1, 0.5 and 0.37. Glitch's
 * own checks are in [glitchDefaultAndOrders] (the v1.7 Glitch had RGB only: a permuted order
 * equals v1.7 on the picture with its channels permuted the same way). JVM.
 */
class FringeGoldenTest {
    private val moving = ChromaticAberrationMovingFilter()
    private val zooming = ChromaticAberrationZoomingFilter()
    private val glitch = GlitchFilter()
    private val pictures: List<Pair<String, PixelBuffer>> = listOf("random" to random(61, 47, 3), "gradient" to gradient(53, 64))

    private fun orderColors(order: Int): IntArray = FringeColors.legacyOrder(order) ?: error("order $order")

    @Test
    fun theDefaultIsRgbAndTheSixOrdersTakeTheFastPath() {
        for (f in listOf(moving, zooming)) {
            assertArrayEquals(intArrayOf(FringeColors.RED, FringeColors.GREEN, FringeColors.BLUE), f.defaultValues().colors(FringeColors.KEY))
        }
        for (order in 0..5) {
            val k = FringeColors.oneHotFactors(orderColors(order))
            assertArrayEquals("order $order", ChromaticAberrationV17.colorOrderFactors(order), k)
        }
    }

    @Test
    fun movingIsV17ForTheDefaultAndEveryOldOrder() {
        var n = 0
        for ((name, src) in pictures) for (ctx in contexts) for (distance in listOf(3f, 17.5f, 200f)) for (angle in listOf(0f, 33f, 270f)) {
            val old = { order: Int -> FilterValues(mapOf("distance" to distance, "angle" to angle, "order" to order)) }
            val def = moving.defaultValues().apply { set("distance", distance); set("angle", angle) }
            val what = "$name, distance $distance, angle $angle, scale ${ctx.scale}"
            assertArrayEquals("default, $what", ChromaticAberrationV17.moving(src, old(0), ctx).pixels, moving.apply(src, def, ctx).pixels)
            for (order in 0..5) {
                val v = def.copy().set(FringeColors.KEY, orderColors(order))
                assertArrayEquals("order $order, $what", ChromaticAberrationV17.moving(src, old(order), ctx).pixels, moving.apply(src, v, ctx).pixels)
                n++
            }
        }
        assertEquals(2 * 3 * 3 * 3 * 6, n)
    }

    @Test
    fun zoomingIsV17ForTheDefaultAndEveryOldOrder() {
        val centers = listOf(floatArrayOf(0.5f, 0.5f), floatArrayOf(0f, 1f), floatArrayOf(0.3f, 0.8f))
        for ((name, src) in pictures) for (ctx in contexts) for (distance in listOf(4f, 60f)) for (center in centers) {
            val old = { order: Int -> FilterValues(mapOf("distance" to distance, "order" to order, "center" to center.copyOf())) }
            val def = zooming.defaultValues().apply { set("distance", distance); set("center", center.copyOf()) }
            val what = "$name, distance $distance, center ${center.toList()}, scale ${ctx.scale}"
            assertArrayEquals("default, $what", ChromaticAberrationV17.zooming(src, old(0), ctx).pixels, zooming.apply(src, def, ctx).pixels)
            for (order in 0..5) {
                val v = def.copy().set(FringeColors.KEY, orderColors(order))
                assertArrayEquals("order $order, $what", ChromaticAberrationV17.zooming(src, old(order), ctx).pixels, zooming.apply(src, v, ctx).pixels)
            }
        }
    }

    @Test
    fun glitchDefaultAndOrders() {
        val settings = listOf(
            mapOf<String, Any>(),
            mapOf("strength" to 100f, "noise" to 100f, "blocks" to 100f, "colorShift" to 40f, "height" to 6f),
            mapOf("strength" to 80f, "noise" to 0f, "blocks" to 0f, "colorShift" to 25f, "height" to 3f),
        )
        var changed = 0
        for ((name, src) in pictures) for (ctx in contexts) for (s in settings) for (seed in listOf(1, 12345)) {
            val v = glitch.defaultValues().apply { for ((k, x) in s) set(k, x); set("seed", seed) }
            val what = "$name, settings $s, seed $seed, scale ${ctx.scale}"
            val want = GlitchV17.apply(src, v.copy(), ctx)
            assertArrayEquals("default, $what", want.pixels, glitch.apply(src, v.copy(), ctx).pixels)
            if (!want.pixels.contentEquals(src.pixels)) changed++
            // v1.7 had RGB only: order P equals v1.7 on the picture whose R, G, B hold P's first, middle
            // and last channel, with the channels put back after (the split and the static treat the
            // channels alike).
            for (order in 0..5) {
                val colors = orderColors(order)
                val from = IntArray(3) { channelOf(colors[it]) }
                val old = permute(GlitchV17.apply(permute(src, from), v.copy(), ctx), inverse(from))
                val got = glitch.apply(src, v.copy().set(FringeColors.KEY, colors), ctx)
                assertArrayEquals("order $order, $what", old.pixels, got.pixels)
            }
        }
        assertTrue("the settings glitch the pictures ($changed)", changed >= 30)
    }

    @Test
    fun theGoldenPicturesAreNotTrivial() {
        // The comparisons above see real fringes: v1.7's orders differ from each other on them.
        val (_, src) = pictures[1]
        val ctx = FilterContext()
        val a = ChromaticAberrationV17.moving(src, FilterValues(mapOf("distance" to 17.5f, "angle" to 33f, "order" to 0)), ctx)
        val b = ChromaticAberrationV17.moving(src, FilterValues(mapOf("distance" to 17.5f, "angle" to 33f, "order" to 5)), ctx)
        assertTrue(ArtTestUtil.countDifferent(a, b) > src.pixels.size / 2)
    }
}
