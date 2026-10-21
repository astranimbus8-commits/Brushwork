package com.brushwork.paint.v110.lead

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.FilterValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * v1.10 F0 (R6; lead, frozen): every filter whose [Filter.colorMapper] is non-null maps a color
 * exactly as its apply() does on a 1 x 1 buffer of that color: 4096 random colors plus extremes,
 * for its defaults and random settings. Runs over the whole [FilterRegistry], so CDC is covered
 * once A4 gives it a mapper (none has one in F0). A test filter with a mapper keeps the check
 * honest meanwhile, and a wrong mapper is shown to fail it.
 */
class ColorMapperContractTest {

    private val extremes = intArrayOf(
        0, 0x00FFFFFF, 0x00123456, 0x01000000, 0x01FFFFFF, 0x7F000000, 0x80808080.toInt(), 0xFE00FF00.toInt(),
        0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFF7F7F7F.toInt(),
    )

    private val colors: IntArray by lazy { val rnd = Random(110); extremes + IntArray(4096) { rnd.nextInt() } }

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
            else -> {}
        }
        return v
    }

    /** The colors where [mapper] differs from [f]'s apply() on a 1 x 1 buffer, as "c: mapper != apply". */
    private fun mismatches(f: Filter, v: FilterValues, mapper: (Int) -> Int, limit: Int = 5): List<String> {
        val out = mutableListOf<String>()
        for (c in colors) {
            val applied = f.apply(PixelBuffer(1, 1, intArrayOf(c)), v, FilterContext()).pixels[0]
            val mapped = mapper(c)
            if (mapped != applied) {
                out += "%08X: %08X != %08X".format(c, mapped, applied)
                if (out.size >= limit) break
            }
        }
        return out
    }

    @Test
    fun everyColorMapperEqualsApplyOnOnePixel() {
        val rnd = Random(4)
        var mappers = 0
        for (f in FilterRegistry.all + RecolorTestFilter()) {
            for (v in listOf(f.defaultValues()) + List(3) { randomValues(f, rnd) }) {
                val mapper = f.colorMapper(v) ?: continue
                val bad = mismatches(f, v, mapper)
                assertTrue("${f.id} ${v.asMap()}: colorMapper differs from apply() on 1 x 1: $bad", bad.isEmpty())
                mappers++
            }
        }
        // At least the test filter's four settings.
        assertTrue("mappers checked: $mappers", mappers >= 4)
    }

    @Test
    fun theDefaultIsNullAndAWrongMapperIsCaught() {
        val plain = object : Filter("test.plain", "Plain", FilterCategory.ADJUST) {
            override val params: List<FilterParam> = emptyList()
            override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext) = src.copy()
        }
        assertEquals(null, plain.colorMapper(plain.defaultValues()))
        val f = RecolorTestFilter()
        val v = f.defaultValues()
        val wrong: (Int) -> Int = { c -> ColorUtils.recolor(c, 0xFF102030.toInt(), 0.5f) xor 1 }
        assertFalse("a mapper off by one bit is caught", mismatches(f, v, wrong).isEmpty())
    }

    /** A pointwise filter with a mapper that agrees with its apply() (the recolor math of C3). */
    private class RecolorTestFilter : Filter("test.recolor", "Recolor test", FilterCategory.ADJUST) {
        override val params: List<FilterParam> = listOf(
            FilterParam.Color("color", "Color", 0xFF102030.toInt()),
            FilterParam.Slider("amount", "Strength", 0f, 100f, 50f, 1f, "%"),
        )

        override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
            val out = PixelBuffer(src.width, src.height)
            val s = values.float("amount") / 100f
            val rgb = values.color("color")
            for (i in src.pixels.indices) out.pixels[i] = ColorUtils.recolor(src.pixels[i], rgb, s)
            return out
        }

        override fun colorMapper(values: FilterValues): ((Int) -> Int)? {
            val s = values.float("amount") / 100f
            val rgb = values.color("color")
            return { c -> ColorUtils.recolor(c, rgb, s) }
        }
    }
}
