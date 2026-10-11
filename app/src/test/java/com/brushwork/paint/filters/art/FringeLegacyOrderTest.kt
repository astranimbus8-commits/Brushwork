package com.brushwork.paint.filters.art

import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import com.brushwork.paint.filters.art.FringeFixtures.B
import com.brushwork.paint.filters.art.FringeFixtures.G
import com.brushwork.paint.filters.art.FringeFixtures.R
import com.brushwork.paint.filters.art.FringeFixtures.random
import com.brushwork.paint.filters.art.v17.ChromaticAberrationV17
import com.brushwork.paint.masks.FilterValuesCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v1.8 (item 5, V11, design §3.6 (b)): a v1.7 Chromatic aberration's "order" (RGB, RBG, GRB, GBR,
 * BRG, BGR) is read as fringe colors, letter i of the order's name being the primary of fringe i:
 * the filters' own mapping is the codec's ([FilterValuesCodec.legacyOrderColors]) and gives v1.7's
 * direction factors; values holding only "order" draw that order (F0's frozen-copy test passes
 * such values), stored fringes win over it, and Glitch has no legacy key. JVM.
 */
class FringeLegacyOrderTest {
    private val moving = ChromaticAberrationMovingFilter()
    private val zooming = ChromaticAberrationZoomingFilter()
    private val ctx = FilterContext()

    private fun fringesOf(f: Filter): FilterParam.Fringes = f.params.filterIsInstance<FilterParam.Fringes>().single()

    @Test
    fun theMappingIsTheCodecsAndV17sFactors() {
        for (i in -2..8) {
            val mine = FringeColors.legacyOrder(i)
            val codec = FilterValuesCodec.legacyOrderColors(i)
            if (i in 0..5) {
                assertArrayEquals("order $i", codec, mine)
                assertArrayEquals("order $i", ChromaticAberrationV17.colorOrderFactors(i), FringeColors.oneHotFactors(mine!!))
                assertArrayEquals("order $i", ArtMath.colorOrderFactors(i), FringeColors.oneHotFactors(mine))
            } else {
                assertNull(mine); assertNull(codec)
            }
        }
        assertArrayEquals(intArrayOf(R, G, B), FringeColors.legacyOrder(0))
        assertArrayEquals(intArrayOf(G, B, R), FringeColors.legacyOrder(3))
        assertArrayEquals(intArrayOf(B, G, R), FringeColors.legacyOrder(5))
    }

    @Test
    fun theParamsCarryTheLegacyKeyOnChromaticAberrationOnly() {
        for (f in listOf(moving, zooming)) {
            val p = fringesOf(f)
            assertEquals(FringeColors.KEY, p.key)
            assertEquals(FringeColors.LEGACY_ORDER_KEY, p.legacyOrderKey)
            assertEquals(FringeColors.PRESETS, p.presets)
            assertArrayEquals(intArrayOf(R, G, B), p.default)
            assertEquals("no Choice param left", 0, f.params.count { it is FilterParam.Choice })
        }
    }

    @Test
    fun valuesWithOnlyAnOrderDrawThatOrder() {
        val src = random(31, 27, 6)
        for (order in 0..5) {
            val legacy = FilterValues(mapOf("distance" to 7f, "angle" to 40f, FringeColors.LEGACY_ORDER_KEY to order))
            val colors = legacy.copy().set(FringeColors.KEY, FringeColors.legacyOrder(order)!!)
            assertArrayEquals("order $order", moving.apply(src, colors.copy(), ctx).pixels, moving.apply(src, legacy.copy(), ctx).pixels)
            assertArrayEquals("order $order", ChromaticAberrationV17.moving(src, legacy.copy()).pixels, moving.apply(src, legacy.copy(), ctx).pixels)
            val zl = FilterValues(mapOf("distance" to 9f, FringeColors.LEGACY_ORDER_KEY to order))
            assertArrayEquals("order $order", ChromaticAberrationV17.zooming(src, zl.copy()).pixels, zooming.apply(src, zl.copy(), ctx).pixels)
            // Stored colors win over the order.
            val both = legacy.copy().set(FringeColors.KEY, intArrayOf(R, G, B))
            assertArrayEquals(ChromaticAberrationV17.moving(src, legacy.copy().set(FringeColors.LEGACY_ORDER_KEY, 0)).pixels, moving.apply(src, both, ctx).pixels)
        }
        // Out of range, as the codec: the default.
        val nine = FilterValues(mapOf("distance" to 7f, "angle" to 40f, FringeColors.LEGACY_ORDER_KEY to 9))
        assertArrayEquals(intArrayOf(R, G, B), FringeColors.resolve(nine, fringesOf(moving)))
        assertArrayEquals(intArrayOf(B, G, R), FringeColors.resolve(nine.copy().set(FringeColors.LEGACY_ORDER_KEY, 5f), fringesOf(moving)))
    }

    @Test
    fun aStoredV17PanelReadsBackAsItsOrder() {
        val src = random(29, 23, 12)
        for (order in 0..5) {
            val j = Json.parseToJsonElement("""{"distance":7.0,"angle":40.0,"order":$order}""").jsonObject
            val v = FilterValuesCodec.fromJson(moving, j)
            assertArrayEquals(FringeColors.legacyOrder(order), v.colors(FringeColors.KEY))
            val old = FilterValues(mapOf("distance" to 7f, "angle" to 40f, "order" to order))
            assertArrayEquals("order $order", ChromaticAberrationV17.moving(src, old).pixels, moving.apply(src, v, ctx).pixels)
            // Written back, the colors are stored and the order is gone.
            val back = FilterValuesCodec.toJson(moving, v).toString()
            assertEquals(false, back.contains("\"order\""))
        }
    }


    @Test
    fun glitchHasNoLegacyOrder() {
        val glitch = GlitchFilter()
        val p = fringesOf(glitch)
        assertNull(p.legacyOrderKey)
        assertArrayEquals(intArrayOf(R, G, B), p.default)
        assertArrayEquals(intArrayOf(R, G, B), FringeColors.resolve(FilterValues(mapOf(FringeColors.LEGACY_ORDER_KEY to 5)), p))
        val src = random(30, 40, 2)
        val v = glitch.defaultValues().apply { set("strength", 100f); set("seed", 4) }
        assertArrayEquals(glitch.apply(src, v.copy(), ctx).pixels, glitch.apply(src, v.copy().set(FringeColors.LEGACY_ORDER_KEY, 5), ctx).pixels)
    }
}
