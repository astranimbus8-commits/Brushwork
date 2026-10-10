package com.brushwork.paint.masks

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import com.brushwork.paint.filters.FringePreset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

/**
 * v1.8 F1 (item 5, V11, §4.3): a [FilterParam.Fringes] value is a JSON array of 3 ints. When it
 * is absent, a v1.7 Chromatic aberration's legacy "order" index is read instead (letter i of the
 * order's name is the primary of fringe i); anything unreadable takes the default. The filter is
 * this test's own (no shipped filter has fringes before area A). JVM.
 */
class FringesParamCodecTest {
    private val R = 0xFFFF0000.toInt()
    private val G = 0xFF00FF00.toInt()
    private val B = 0xFF0000FF.toInt()

    private val presets = listOf(FringePreset("RGB", intArrayOf(R, G, B)), FringePreset("Two", intArrayOf(R, 0, 0xFF00FFFF.toInt())))

    /** "fringes" reads the legacy "order"; "glitch" has no legacy key. */
    private class FringeTestFilter(presets: List<FringePreset>, default: IntArray) : Filter("test.fringes", "Fringe test", FilterCategory.ART) {
        override val params: List<FilterParam> = listOf(
            FilterParam.Slider("distance", "Distance", 0f, 200f, 5f),
            FilterParam.Fringes("fringes", "Fringe colors", presets, default, legacyOrderKey = "order"),
            FilterParam.Fringes("glitch", "Glitch fringes", presets, intArrayOf(0xFF00FFFF.toInt(), 0, 0xFFFF00FF.toInt())),
        )

        override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer = src
    }

    private val filter = FringeTestFilter(presets, intArrayOf(R, G, B))

    private fun read(json: String): FilterValues = FilterValuesCodec.fromJson(filter, Json.parseToJsonElement(json).jsonObject)

    @Test
    fun valuesRoundTripAsAnArrayOfThreeInts() {
        val rnd = Random(8)
        repeat(50) {
            val a = IntArray(3) { i -> if (i == 1 && it % 5 == 0) 0 else rnd.nextInt() }
            val b = IntArray(3) { rnd.nextInt() }
            val v = filter.defaultValues().set("fringes", a).set("glitch", b)
            val j = FilterValuesCodec.toJson(filter, v)
            val written = j.getValue("fringes") as JsonArray
            assertEquals(3, written.size)
            assertEquals(a.map { JsonPrimitive(it) }, written.toList())
            val back = FilterValuesCodec.fromJson(filter, j)
            assertArrayEquals(a, back.colors("fringes"))
            assertArrayEquals(b, back.colors("glitch"))
            // Through the stored string as well.
            val again = FilterValuesCodec.fromJson(filter, Json.parseToJsonElement(j.toString()).jsonObject)
            assertArrayEquals(a, again.colors("fringes"))
        }
    }

    @Test
    fun theLegacyOrderIsReadWhenTheFringesAreAbsent() {
        val expected = listOf(
            intArrayOf(R, G, B), intArrayOf(R, B, G), intArrayOf(G, R, B),
            intArrayOf(G, B, R), intArrayOf(B, R, G), intArrayOf(B, G, R),
        )
        for ((i, e) in expected.withIndex()) {
            assertArrayEquals("order $i", e, read("""{"distance":12.0,"order":$i}""").colors("fringes"))
            assertArrayEquals(e, FilterValuesCodec.legacyOrderColors(i))
        }
        // The fringes win over the order.
        assertArrayEquals(intArrayOf(1, 2, 3), read("""{"order":5,"fringes":[1,2,3]}""").colors("fringes"))
        // A parameter without a legacy key ignores the order.
        assertArrayEquals(filter.params.filterIsInstance<FilterParam.Fringes>()[1].default, read("""{"order":3}""").colors("glitch"))
    }

    @Test
    fun aMissingOrUnreadableValueTakesTheDefault() {
        val def = intArrayOf(R, G, B)
        for (json in listOf(
            "{}", """{"order":-1}""", """{"order":6}""", """{"order":99}""", """{"order":"GRB"}""", """{"order":[1]}""",
            """{"fringes":[1,2]}""", """{"fringes":[1,2,3,4]}""", """{"fringes":"RGB"}""", """{"fringes":[1,"x",3]}""",
            """{"fringes":[1,[2],3]}""", """{"fringes":null}""", """{"fringes":{"a":1}}""",
        )) {
            assertArrayEquals(json, def, read(json).colors("fringes"))
        }
        assertNull(FilterValuesCodec.legacyOrderColors(6))
        assertNull(FilterValuesCodec.legacyOrderColors(-1))
        // A value of the wrong shape is written as the default.
        val j = FilterValuesCodec.toJson(filter, filter.defaultValues().set("fringes", intArrayOf(1, 2)))
        assertEquals(JsonArray(def.map { JsonPrimitive(it) }), j["fringes"])
        // A missing value too.
        assertEquals(JsonArray(def.map { JsonPrimitive(it) }), FilterValuesCodec.toJson(filter, FilterValues())["fringes"])
    }

    @Test
    fun theDefaultAndTheGetterAreCopies() {
        val p = filter.params.filterIsInstance<FilterParam.Fringes>()[0]
        val a = p.defaultValue() as IntArray
        a[0] = 7
        assertArrayEquals(intArrayOf(R, G, B), p.default)
        val v = filter.defaultValues()
        val c = v.colors("fringes")
        c[1] = 9
        assertArrayEquals(intArrayOf(R, G, B), v.colors("fringes"))
        assertNotSame(v.colors("fringes"), v.colors("fringes"))
        // Not a fringe value: RGB primaries.
        assertArrayEquals(intArrayOf(R, G, B), FilterValues().colors("nothing"))
        assertArrayEquals(intArrayOf(R, G, B), FilterValues(mapOf("x" to intArrayOf(1))).colors("x"))
        // Equal by content.
        assertEquals(p, FilterParam.Fringes("fringes", "Fringe colors", presets.map { it.copy(colors = it.colors.copyOf()) }, intArrayOf(R, G, B), "order"))
        assertEquals(p.hashCode(), FilterParam.Fringes("fringes", "Fringe colors", presets, intArrayOf(R, G, B), "order").hashCode())
    }

    @Test
    fun anAdjustmentKeepsItsFringes() {
        val v = filter.defaultValues().set("fringes", intArrayOf(R, 0, B))
        val spec = AdjustmentEffects.spec(filter, v)
        val decoded = AdjustmentCodec.decode(AdjustmentCodec.encode(spec))!!
        assertArrayEquals(intArrayOf(R, 0, B), AdjustmentEffects.valuesOf(decoded, filter).colors("fringes"))
        val j: JsonObject = decoded.values
        assertEquals(3, (j.getValue("fringes") as JsonArray).size)
    }
}
