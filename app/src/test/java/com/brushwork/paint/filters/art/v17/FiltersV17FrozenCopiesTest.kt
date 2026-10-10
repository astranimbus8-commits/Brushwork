package com.brushwork.paint.filters.art.v17

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterValues
import com.brushwork.paint.filters.art.ChromaticAberrationMovingFilter
import com.brushwork.paint.filters.art.ChromaticAberrationZoomingFilter
import com.brushwork.paint.filters.art.GlitchFilter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * v1.8 F0: the frozen v1.7 filters ([ChromaticAberrationV17], [GlitchV17]) give the app's
 * filters' pixels, byte for byte (the app's are still v1.7's at 885793b4): every color order,
 * distances, angles and centers, Glitch's settings and seeds, at full size and in a scaled
 * preview, on a picture with transparent and translucent parts. The values are the v1.7 keys
 * only (Chromatic Aberration without v1.8's `fringes`, which then reads the legacy `order`; Glitch
 * over its own defaults), so the equality is the one v1.8 keeps (§3.6, `FringeGoldenTest`).
 */
class FiltersV17FrozenCopiesTest {
    private fun picture(w: Int, h: Int, seed: Int): PixelBuffer {
        val r = Random(seed)
        val b = PixelBuffer(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val a = when {
                x < 4 -> 0
                (x + y) % 9 == 0 -> 128 + r.nextInt(127)
                else -> 255
            }
            b[x, y] = if (a == 0) 0 else (a shl 24) or (((x * 7 + r.nextInt(30)) and 0xFF) shl 16) or (((y * 5) and 0xFF) shl 8) or r.nextInt(256)
        }
        return b
    }

    private val contexts = listOf(FilterContext(), FilterContext(scale = 0.5f), FilterContext(scale = 0.37f))

    @Test
    fun chromaticAberrationMovingIsV17s() {
        val src = picture(61, 47, 3)
        val app = ChromaticAberrationMovingFilter()
        var n = 0
        for (order in 0..5) for (distance in listOf(0f, 3f, 17.5f, 200f)) for (angle in listOf(0f, 33f, 270f)) for (ctx in contexts) {
            val v = FilterValues(mapOf("distance" to distance, "angle" to angle, "order" to order))
            val want = app.apply(src, v.copy(), ctx).pixels
            val got = ChromaticAberrationV17.moving(src, v.copy(), ctx).pixels
            assertArrayEquals("order $order, distance $distance, angle $angle, scale ${ctx.scale}", want, got)
            n++
        }
        assertEquals(6 * 4 * 3 * 3, n)
    }

    @Test
    fun chromaticAberrationZoomingIsV17s() {
        val src = picture(53, 64, 5)
        val app = ChromaticAberrationZoomingFilter()
        for (order in 0..5) for (distance in listOf(0f, 4f, 60f)) for (center in listOf(floatArrayOf(0.5f, 0.5f), floatArrayOf(0f, 1f), floatArrayOf(0.3f, 0.8f))) for (ctx in contexts) {
            val v = FilterValues(mapOf("distance" to distance, "order" to order, "center" to center))
            assertArrayEquals(
                "order $order, distance $distance, center ${center.toList()}, scale ${ctx.scale}",
                app.apply(src, v.copy(), ctx).pixels, ChromaticAberrationV17.zooming(src, v.copy(), ctx).pixels,
            )
        }
    }

    @Test
    fun glitchIsV17s() {
        val src = picture(72, 90, 9)
        val app = GlitchFilter()
        val settings = listOf(
            mapOf<String, Any>(),
            mapOf("strength" to 100f, "noise" to 100f, "blocks" to 100f, "colorShift" to 40f, "height" to 6f),
            mapOf("strength" to 0f, "noise" to 70f),
            mapOf("strength" to 35f, "noise" to 0f, "blocks" to 0f, "height" to 150f),
        )
        var changed = 0
        for (s in settings) for (seed in listOf(1, 7, 12345)) for (ctx in contexts) {
            val v = app.defaultValues().apply { for ((k, x) in s) set(k, x); set("seed", seed) }
            val want = app.apply(src, v.copy(), ctx).pixels
            assertArrayEquals("settings $s, seed $seed, scale ${ctx.scale}", want, GlitchV17.apply(src, v.copy(), ctx).pixels)
            if (!want.contentEquals(src.pixels)) changed++
        }
        assertTrue("the settings glitch the picture ($changed)", changed >= 24)
    }
}
