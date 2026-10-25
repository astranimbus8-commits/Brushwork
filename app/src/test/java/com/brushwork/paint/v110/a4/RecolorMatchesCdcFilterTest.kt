package com.brushwork.paint.v110.a4

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.FilterValues
import com.brushwork.paint.filters.adjust.ChangeDrawingColorFilter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

/**
 * v1.10 (R6, A4 step 2): Change Drawing Color's apply() is `ColorUtils.recolor` per pixel, exactly
 * the v1.9 math (pixel layers change exactly as before), and its colorMapper is the same map, so
 * objects recolor as their pixels would. CDC stays out of adjustment layers (no pixelMapper), and
 * it is the only filter with a colorMapper in 1.10.
 */
class RecolorMatchesCdcFilterTest {

    private val cdc = ChangeDrawingColorFilter()

    private val pixels: IntArray by lazy {
        val rnd = Random(64)
        intArrayOf(0, 0x00FFFFFF, 0x01000000, 0x80FF0000.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt()) + IntArray(2042) { rnd.nextInt() }
    }

    private fun values(color: Int, percent: Float): FilterValues =
        cdc.defaultValues().also { it.set("color", color); it.set("amount", percent) }

    /** v1.9's per-pixel math, as `ChangeDrawingColorFilter.apply` wrote it before 1.10. */
    private fun v19(c: Int, color: Int, percent: Float): Int {
        val amount = (percent / 100f).coerceIn(0f, 1f)
        if (amount <= 0f) return c
        val rgb = color and 0xFFFFFF
        val a = c ushr 24
        if (a == 0) return c
        val m = rgb or (a shl 24)
        return if (amount >= 1f) m else ColorUtils.lerp(c, m, amount)
    }

    private val settings = listOf(
        0xFF1E5BD8.toInt() to 100f, 0x801E5BD8.toInt() to 100f, 0xFFFF0000.toInt() to 50f,
        0x00FFFFFF to 37f, 0xFF000000.toInt() to 1f, 0xFF00FF00.toInt() to 99f, 0xFF123456.toInt() to 0f,
    )

    @Test
    fun applyIsRecolorPerPixelAndTheV19Math() {
        for ((color, percent) in settings) {
            val out = cdc.apply(PixelBuffer(64, 32, pixels.copyOf()), values(color, percent), FilterContext()).pixels
            val expected = IntArray(pixels.size) { ColorUtils.recolor(pixels[it], color and 0xFFFFFF, percent / 100f) }
            assertArrayEquals("%08X at %.0f%%".format(color, percent), expected, out)
            assertArrayEquals("as v1.9", IntArray(pixels.size) { v19(pixels[it], color, percent) }, out)
        }
    }

    @Test
    fun theColorMapperMapsEveryColorAsApplyDoes() {
        for ((color, percent) in settings) {
            val v = values(color, percent)
            val mapper = cdc.colorMapper(v)
            val out = cdc.apply(PixelBuffer(64, 32, pixels.copyOf()), v, FilterContext()).pixels
            for (i in pixels.indices) assertEquals("%08X".format(pixels[i]), out[i], mapper(pixels[i]))
        }
    }

    @Test
    fun theTargetAlphaIsIgnoredAndEachColorKeepsItsAlpha() {
        val mapper = cdc.colorMapper(values(0x20FF0000, 100f))
        assertEquals(0x80FF0000.toInt(), mapper(0x8000FF00.toInt()))
        assertEquals("transparent stays as it is", 0x0000FF00, mapper(0x0000FF00))
        assertEquals(0xFFFF0000.toInt(), mapper(0xFF0000FF.toInt()))
    }

    @Test
    fun strengthZeroChangesNothing() {
        val v = values(0xFFFF0000.toInt(), 0f)
        val mapper = cdc.colorMapper(v)
        for (c in pixels) assertEquals(c, mapper(c))
        assertArrayEquals(pixels, cdc.apply(PixelBuffer(64, 32, pixels.copyOf()), v, FilterContext()).pixels)
    }

    @Test
    fun cdcAloneHasAColorMapperAndStaysOutOfAdjustmentLayers() {
        assertNotNull(cdc.colorMapper(cdc.defaultValues()))
        assertNull(cdc.pixelMapper(cdc.defaultValues()))
        assertFalse(cdc.isAdjustmentCapable)
        val withMapper = FilterRegistry.all.filter { it.colorMapper(it.defaultValues()) != null }.map { it.id }
        assertEquals(listOf("adjust.change_drawing_color"), withMapper)
    }
}
