package com.brushwork.paint.masks

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.CurvePoint
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.FilterValues
import com.brushwork.paint.filters.GradientStop
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * v1.5 A5: adjustment effects — the values codec for every parameter type of every filter, which
 * effects are pass-through, and the pixel mapper contract (§4.8) for every adjustment-capable
 * filter. JVM.
 */
class AdjustmentEffectsTest {
    private val rnd = Random(21)

    /** A random valid value for [p]. */
    private fun randomValue(p: FilterParam): Any = when (p) {
        is FilterParam.Slider -> {
            val v = p.min + rnd.nextFloat() * (p.max - p.min)
            if (p.step >= 1f) Math.round(v).toFloat() else v
        }
        is FilterParam.Toggle -> rnd.nextBoolean()
        is FilterParam.Choice -> rnd.nextInt(p.options.size)
        is FilterParam.Seed -> rnd.nextInt(1, 1_000_000)
        is FilterParam.Color -> rnd.nextInt()
        is FilterParam.Point -> floatArrayOf(rnd.nextFloat(), rnd.nextFloat())
        is FilterParam.Curve -> listOf(CurvePoint(0f, rnd.nextFloat()), CurvePoint(0.4f, rnd.nextFloat()), CurvePoint(1f, rnd.nextFloat()))
        is FilterParam.Gradient -> listOf(GradientStop(0f, rnd.nextInt()), GradientStop(0.6f, rnd.nextInt()), GradientStop(1f, rnd.nextInt()))
        is FilterParam.Text -> "text ${rnd.nextInt()}"
    }

    private fun assertSameValue(p: FilterParam, a: Any?, b: Any?) {
        when (p) {
            is FilterParam.Point -> assertArrayEquals(p.key, a as FloatArray, b as FloatArray, 0f)
            is FilterParam.Slider -> assertEquals(p.key, (a as Number).toFloat(), (b as Number).toFloat(), 0f)
            else -> assertEquals(p.key, a, b)
        }
    }

    @Test
    fun filterValuesRoundTripForEveryParameterTypeOfEveryFilter() {
        val types = HashSet<String>()
        for (f in FilterRegistry.all) {
            repeat(3) {
                val v = f.defaultValues()
                for (p in f.params) v.set(p.key, randomValue(p))
                val back = FilterValuesCodec.fromJson(f, FilterValuesCodec.toJson(f, v))
                for (p in f.params) {
                    types += p::class.simpleName!!
                    assertSameValue(p, v.raw(p.key), back.raw(p.key))
                }
                // Through the stored spec string as well.
                val spec = AdjustmentEffects.spec(f, v)
                val decoded = AdjustmentCodec.decode(AdjustmentCodec.encode(spec))!!
                val again = AdjustmentEffects.valuesOf(decoded, f)
                for (p in f.params) assertSameValue(p, v.raw(p.key), again.raw(p.key))
            }
        }
        assertTrue("every parameter type is covered: $types", types.containsAll(listOf("Slider", "Toggle", "Choice", "Color", "Point", "Curve", "Gradient", "Text", "Seed")))
    }

    @Test
    fun unknownKeysAreIgnoredAndMissingOrBrokenValuesTakeTheDefaults() {
        val f = FilterRegistry.byId("adjust.tone")!!
        val j = JsonObject(mapOf("exposure" to JsonPrimitive(1.5f), "nonsense" to JsonPrimitive(3), "contrast" to JsonPrimitive("high")))
        val v = FilterValuesCodec.fromJson(f, j)
        assertEquals(1.5f, v.float("exposure"), 0f)
        assertEquals(0f, v.float("contrast"), 0f)
        assertEquals(0f, v.float("shadows"), 0f)
        assertNull(AdjustmentCodec.decode("{not json"))
    }

    @Test
    fun passThroughEffects() {
        // Tone at its defaults is exactly the identity: skipped.
        val tone = AdjustmentEffects.defaultSpec()
        assertEquals("adjust.tone", tone.filterId)
        assertTrue(AdjustmentEffects.isIdentity(tone))
        assertNull(AdjustmentEffects.mapperOf(tone))
        assertTrue(AdjustmentEffects.isIdentity(AdjustmentSpec()))
        // Changed values are not.
        val f = FilterRegistry.byId("adjust.tone")!!
        val moved = AdjustmentEffects.spec(f, f.defaultValues().set("exposure", 0.5f))
        assertFalse(AdjustmentEffects.isIdentity(moved))
        // An unknown effect is pass-through; Invert is live.
        assertNull(AdjustmentEffects.mapperOf(AdjustmentSpec(filterId = "adjust.from_the_future")))
        assertEquals("Unknown effect", AdjustmentEffects.displayName(AdjustmentSpec(filterId = "adjust.from_the_future")))
        assertNotNull(AdjustmentEffects.mapperOf(AdjustmentEffects.defaultSpec(FilterRegistry.byId("adjust.invert"))))
        // Only pointwise filters are offered, Tone first.
        assertEquals("adjust.tone", AdjustmentEffects.filters.first().id)
        assertTrue(AdjustmentEffects.filters.all { it.isAdjustmentCapable })
        assertTrue(AdjustmentEffects.filters.any { it.id == "adjust.invert" })
    }

    private fun randomPixels(n: Int) = IntArray(n) { rnd.nextInt() }.also { px ->
        // Some fully transparent and fully opaque pixels too.
        for (i in 0 until n step 7) px[i] = px[i] and 0x00FFFFFF
        for (i in 3 until n step 5) px[i] = px[i] or 0xFF000000.toInt()
    }

    /**
     * §4.8 contract for every adjustment-capable filter at default and random values. Alpha is
     * kept, except that Gradation Map lowers it where its stops are semi-transparent, exactly as
     * its apply() does (the stated exception of PixelMapper; the stage shows the image below
     * there, see AdjustmentStageIntegrationRobolectricTest); no mapper raises it.
     */
    @Test
    fun pixelMappersArePointwiseKeepAlphaAndEqualApply() {
        for (f in AdjustmentEffects.filters) {
            val valueSets = listOf(f.defaultValues()) + List(3) { f.defaultValues().also { v -> for (p in f.params) if (p !is FilterParam.Point) v.set(p.key, randomValue(p)) } }
            for ((k, values) in valueSets.withIndex()) {
                val mapper = f.pixelMapper(values) ?: continue
                val w = 64; val h = 32
                val src = randomPixels(w * h)
                val whole = src.copyOf()
                mapper.map(whole, 0, whole.size)
                // Alpha is untouched, or (Gradation Map's semi-transparent stops) only lowered.
                val lowers = f.id == ALPHA_LOWERING && k > 0
                for (i in src.indices) {
                    if (lowers) assertTrue("${f.id} alpha raised at $i", whole[i] ushr 24 <= src[i] ushr 24)
                    else assertEquals("${f.id} alpha at $i", src[i] ushr 24, whole[i] ushr 24)
                }
                // Pieces in any order give the same result, and nothing outside the range changes.
                val pieces = src.copyOf()
                mapper.map(pieces, 100, 700)
                for (i in 0 until 100) assertEquals("${f.id}: outside the range", src[i], pieces[i])
                for (i in 700 until pieces.size) assertEquals("${f.id}: outside the range", src[i], pieces[i])
                mapper.map(pieces, 700, pieces.size)
                mapper.map(pieces, 0, 100)
                assertArrayEquals("${f.id} pointwise", whole, pieces)
                // The same as apply(), per pixel.
                val buf = PixelBuffer(w, h).also { System.arraycopy(src, 0, it.pixels, 0, src.size) }
                val applied = f.apply(buf, values, FilterContext())
                for (i in src.indices) {
                    if (src[i] ushr 24 == 0) continue
                    assertEquals("${f.id} = apply() at $i (${Integer.toHexString(src[i])})", applied.pixels[i], whole[i])
                }
            }
        }
    }

    /** The one mapper allowed to lower alpha (see PixelMapper's KDoc). */
    private val ALPHA_LOWERING = "adjust.gradation_map"

    /** Gradation Map's semi-transparent stops lower alpha (= apply()); its default, opaque stops don't. */
    @Test
    fun onlyGradationMapLowersAlphaAndOnlyWithSemiTransparentStops() {
        val f = FilterRegistry.byId(ALPHA_LOWERING)!!
        assertTrue(f.isAdjustmentCapable)
        val src = intArrayOf(0xFF000000.toInt(), 0xFF808080.toInt(), 0xFFFFFFFF.toInt(), 0x80808080.toInt())
        val defaults = src.copyOf().also { f.pixelMapper(f.defaultValues())!!.map(it, 0, it.size) }
        for (i in src.indices) assertEquals(src[i] ushr 24, defaults[i] ushr 24)
        val clear = f.defaultValues().set(
            "gradient",
            listOf(GradientStop(0f, 0x00FF0000), GradientStop(1f, 0x00FF0000)),
        )
        val out = src.copyOf().also { f.pixelMapper(clear)!!.map(it, 0, it.size) }
        for (i in src.indices) assertEquals("fully transparent stops give alpha 0 at $i", 0, out[i] ushr 24)
        val applied = f.apply(PixelBuffer(4, 1).also { System.arraycopy(src, 0, it.pixels, 0, 4) }, clear, FilterContext())
        assertArrayEquals(applied.pixels, out)
    }

    @Test
    fun defaultValuesFollowTheDrawingColor() {
        val mono: Filter? = FilterRegistry.byId("adjust.monocolor")
        if (mono != null) {
            val v: FilterValues = AdjustmentEffects.defaultValues(mono, 0xFF123456.toInt())
            assertEquals(0xFF123456.toInt(), v.color("color"))
        }
    }
}
