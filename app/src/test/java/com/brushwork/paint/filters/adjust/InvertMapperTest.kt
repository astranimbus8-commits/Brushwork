package com.brushwork.paint.filters.adjust

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.FilterContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** v1.5 F2: Invert Color's pixel mapper (the first non-identity live effect) equals apply(). */
class InvertMapperTest {
    @Test
    fun theMapperEqualsApplyPerPixel() {
        val f = InvertFilter()
        val rnd = Random(11)
        val src = PixelBuffer(64, 48)
        for (i in src.pixels.indices) {
            src.pixels[i] = when (i % 7) {
                0 -> 0 // transparent: left alone
                1 -> 0x00FF8040 // transparent with a color: left alone
                else -> rnd.nextInt()
            }
        }
        for (amount in listOf(0f, 37f, 100f)) {
            val v = f.defaultValues().set("amount", amount)
            val expected = f.apply(src, v, FilterContext()).pixels
            val px = src.pixels.copyOf()
            val mapper = f.pixelMapper(v)
            mapper.map(px, 0, 1000)
            mapper.map(px, 1000, px.size)
            assertArrayEquals("amount $amount", expected, px)
        }
        assertTrue(f.isAdjustmentCapable)
        val white = intArrayOf(0xFFFFFFFF.toInt(), 0x80000000.toInt())
        f.pixelMapper(f.defaultValues()).map(white, 0, 2)
        assertEquals(0xFF000000.toInt(), white[0])
        assertEquals(0x80FFFFFF.toInt(), white[1])
    }
}
