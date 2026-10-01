package com.brushwork.paint.filters.adjust

import com.brushwork.paint.core.Parallel
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.CurvePoint
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.FilterValues
import com.brushwork.paint.filters.GradientStop
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * v1.5 §4.8: every filter with a [com.brushwork.paint.filters.PixelMapper] maps pixels exactly
 * like its apply() (±0), for its defaults and random settings, whatever the row chunks are.
 * Runs over the whole [FilterRegistry], so a filter that gains a mapper later is checked too.
 */
class PixelMapperContractTest {

    private fun randomImage(w: Int, h: Int, seed: Int): PixelBuffer {
        val rnd = Random(seed)
        val b = PixelBuffer(w, h)
        for (i in b.pixels.indices) {
            b.pixels[i] = when (i % 11) {
                0 -> 0
                1 -> rnd.nextInt() and 0x00FFFFFF // transparent with a color
                2 -> rnd.nextInt() or 0xFF000000.toInt()
                3 -> (rnd.nextInt(256) * 0x010101) or 0xFF000000.toInt() // grays
                else -> rnd.nextInt()
            }
        }
        return b
    }

    private fun randomValues(f: Filter, rnd: Random): FilterValues {
        val v = f.defaultValues()
        for (p in f.params) when (p) {
            is FilterParam.Slider -> {
                var x = p.min + rnd.nextFloat() * (p.max - p.min)
                if (p.step > 0f) x = (Math.round((x - p.min) / p.step) * p.step + p.min).coerceIn(p.min, p.max)
                v.set(p.key, x)
            }
            is FilterParam.Toggle -> v.set(p.key, rnd.nextBoolean())
            is FilterParam.Choice -> v.set(p.key, rnd.nextInt(p.options.size))
            is FilterParam.Color -> v.set(p.key, rnd.nextInt())
            is FilterParam.Point -> v.set(p.key, floatArrayOf(rnd.nextFloat(), rnd.nextFloat()))
            is FilterParam.Curve -> {
                val n = 2 + rnd.nextInt(4)
                v.set(p.key, List(n) { i -> CurvePoint(if (i == 0) 0f else if (i == n - 1) 1f else rnd.nextFloat(), rnd.nextFloat()) }.sortedBy { it.x })
            }
            is FilterParam.Gradient -> {
                val n = 2 + rnd.nextInt(3)
                v.set(p.key, List(n) { GradientStop(rnd.nextFloat(), rnd.nextInt() or 0xFF000000.toInt()) })
            }
            is FilterParam.Seed -> v.set(p.key, rnd.nextInt(1, 1000))
            is FilterParam.Text -> {}
        }
        return v
    }

    @Test
    fun everyMapperEqualsApplyPerPixel() {
        val src = randomImage(41, 29, 21)
        val rnd = Random(4)
        var checked = 0
        for (f in FilterRegistry.all) {
            val sets = listOf(f.defaultValues()) + List(15) { randomValues(f, rnd) }
            for ((k, v) in sets.withIndex()) {
                val mapper = f.pixelMapper(v) ?: continue
                val expected = f.apply(src, v, FilterContext()).pixels
                val px = src.pixels.copyOf()
                // Uneven chunks, like the adjustment stage's row chunks.
                mapper.map(px, 0, 7)
                mapper.map(px, 7, 300)
                mapper.map(px, 300, px.size)
                assertArrayEquals("${f.id} set $k", expected, px)
                checked++
            }
        }
        assertTrue("mappers were exercised ($checked)", checked > 150)
    }

    @Test
    fun mappersAreThreadSafe() {
        val src = randomImage(256, 256, 8)
        val rnd = Random(9)
        for (f in FilterRegistry.all) {
            val v = randomValues(f, rnd)
            val mapper = f.pixelMapper(v) ?: continue
            val serial = src.pixels.copyOf().also { mapper.map(it, 0, it.size) }
            val parallel = src.pixels.copyOf()
            Parallel.forRows(256) { y0, y1 -> mapper.map(parallel, y0 * 256, y1 * 256) }
            assertArrayEquals(f.id, serial, parallel)
        }
    }

    @Test
    fun thePointwiseColorFiltersCanBeLiveEffects() {
        // §4.3: the effects an adjustment layer can have (Levels without Auto, Black & White
        // without smoothing / anti-aliasing: their defaults).
        val capable = FilterRegistry.all.filter { it.isAdjustmentCapable }.map { it.id }.toSet()
        for (id in listOf(
            "adjust.tone", "adjust.brightness_contrast", "adjust.tone_curve", "adjust.levels", "adjust.color_balance",
            "adjust.hue_saturation", "adjust.gradation_map", "adjust.posterize", "adjust.invert", "adjust.grayscale",
            "adjust.black_white", "adjust.monocolor",
        )) assertTrue("$id is adjustment-capable", id in capable)
        // Content-dependent settings have no mapper.
        val levels = adjust<LevelsFilter>()
        assertNull(levels.pixelMapper(levels.defaultValues().set("auto", true)))
        val bw = adjust<BlackWhiteFilter>()
        assertNull(bw.pixelMapper(bw.defaultValues().set("smoothing", 2f)))
        assertNull(bw.pixelMapper(bw.defaultValues().set("antialias", true)))
        assertNotNull(bw.pixelMapper(bw.defaultValues().set("smoothing", 0.2f)))
        // Replace Color: a reference point reads the image (no mapper); a chosen target color is pointwise.
        val replace = adjust<ReplaceColorFilter>()
        assertNull(replace.pixelMapper(replace.defaultValues()))
        assertNotNull(replace.pixelMapper(replace.defaultValues().set("source", 1)))
        assertFalse(adjust<FindEdgesFilter>().isAdjustmentCapable)
    }

    @Test
    fun identitySettingsMapNothing() {
        val src = randomImage(32, 32, 2)
        for (f in listOf(adjust<ToneFilter>(), adjust<BrightnessContrastFilter>(), adjust<ColorBalanceFilter>(), adjust<HueSaturationFilter>())) {
            val px = src.pixels.copyOf()
            val mapper: Filter = f
            requireNotNull(mapper.pixelMapper(f.defaultValues())).map(px, 0, px.size)
            assertArrayEquals(f.id, src.pixels, px)
        }
        val gray = adjust<GrayscaleFilter>()
        val px = src.pixels.copyOf()
        gray.pixelMapper(gray.defaultValues().set("amount", 0f)).map(px, 0, px.size)
        assertArrayEquals(src.pixels, px)
        assertEquals(0, AdjustMath.IDENTITY_MAPPER.let { m -> IntArray(1).also { m.map(it, 0, 1) }[0] })
    }
}
