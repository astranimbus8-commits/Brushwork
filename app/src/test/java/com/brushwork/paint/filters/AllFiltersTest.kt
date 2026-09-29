package com.brushwork.paint.filters

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.PixelBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smoke-tests EVERY registered filter on the JVM: default params, preview scale, extreme params.
 * Catches crashes, wrong output sizes, NaN colors and mutation of the source buffer.
 */
class AllFiltersTest {

    private fun testImage(w: Int, h: Int): PixelBuffer {
        val b = PixelBuffer(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val a = if ((x / 4 + y / 4) % 5 == 0) 0 else if (x < 3) 128 else 255
            b[x, y] = ColorUtils.argb(a, (x * 255) / w, (y * 255) / h, ((x + y) * 97) % 256)
        }
        return b
    }

    private fun check(filter: Filter, src: PixelBuffer, values: FilterValues, ctx: FilterContext, what: String) {
        val before = src.pixels.copyOf()
        val out = try {
            filter.apply(src, values, ctx)
        } catch (e: Throwable) {
            throw AssertionError("${filter.id} ($what) threw ${e.javaClass.simpleName}: ${e.message}", e)
        }
        assertEquals("${filter.id} ($what) width", src.width, out.width)
        assertEquals("${filter.id} ($what) height", src.height, out.height)
        assertTrue("${filter.id} ($what) mutated its source buffer", before.contentEquals(src.pixels))
    }

    private fun extremes(filter: Filter, pickMax: Boolean): FilterValues {
        val v = filter.defaultValues()
        for (p in filter.params) when (p) {
            is FilterParam.Slider -> v.set(p.key, if (pickMax) p.max else p.min)
            is FilterParam.Choice -> v.set(p.key, if (pickMax) p.options.lastIndex else 0)
            is FilterParam.Toggle -> v.set(p.key, pickMax)
            is FilterParam.Point -> v.set(p.key, if (pickMax) floatArrayOf(1f, 1f) else floatArrayOf(0f, 0f))
            else -> {}
        }
        return v
    }

    @Test
    fun registryIsNotEmptyAndIdsUnique() {
        val all = FilterRegistry.all
        assertEquals(all.size, all.map { it.id }.toSet().size)
    }

    @Test
    fun everyFilterRunsWithDefaults() {
        for (f in FilterRegistry.all) {
            check(f, testImage(48, 40), f.defaultValues(), FilterContext(), "defaults")
        }
    }

    @Test
    fun everyFilterRunsAtPreviewScale() {
        for (f in FilterRegistry.all) {
            check(f, testImage(24, 20), f.defaultValues(), FilterContext(scale = 0.25f), "scale 0.25")
        }
    }

    @Test
    fun everyFilterSurvivesExtremeParams() {
        for (f in FilterRegistry.all) {
            check(f, testImage(33, 29), extremes(f, true), FilterContext(), "max params")
            check(f, testImage(33, 29), extremes(f, false), FilterContext(), "min params")
        }
    }

    @Test
    fun everyFilterHandlesTinyAndTransparentImages() {
        for (f in FilterRegistry.all) {
            check(f, PixelBuffer(1, 1).fill(0xFF336699.toInt()), f.defaultValues(), FilterContext(), "1x1")
            check(f, PixelBuffer(7, 3), f.defaultValues(), FilterContext(), "transparent 7x3")
        }
    }
}
