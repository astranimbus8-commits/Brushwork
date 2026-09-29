package com.brushwork.paint.filters.session

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.FilterRecents
import com.brushwork.paint.filters.FilterSessionMath
import com.brushwork.paint.filters.PixelRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterSessionMathTest {

    @Test
    fun previewSizeKeepsAspectAndNeverUpscales() {
        assertEquals(1024 to 1280, FilterSessionMath.previewSize(4000, 5000))
        assertEquals(1280 to 85, FilterSessionMath.previewSize(3000, 200))
        assertEquals(100 to 50, FilterSessionMath.previewSize(100, 50))
        assertEquals(1280 to 1280, FilterSessionMath.previewSize(1280, 1280))
        assertEquals(1280 to 1, FilterSessionMath.previewSize(20000, 3))
    }

    @Test
    fun lerp255HitsEndpointsExactlyAndRoundsTheMiddle() {
        val a = 0x10203040; val b = 0xF0E0D0C0.toInt()
        assertEquals(a, FilterSessionMath.lerp255(a, b, 0))
        assertEquals(b, FilterSessionMath.lerp255(a, b, 255))
        val mid = FilterSessionMath.lerp255(0x00000000, 0xFFFFFFFF.toInt(), 128)
        assertEquals(128, ColorUtils.alpha(mid))
        assertEquals(128, ColorUtils.red(mid))
        // Decreasing channels round correctly too.
        assertEquals(0x7F7F7F7F, FilterSessionMath.lerp255(0xFFFFFFFF.toInt(), 0, 128))
    }

    @Test
    fun composeMixesBySelectionIncludingAlpha() {
        val src = PixelBuffer.filled(3, 1, 0xFF000000.toInt())
        val res = PixelBuffer.filled(3, 1, 0x00FFFFFF)
        val sel = byteArrayOf(0, 255.toByte(), 51)
        FilterSessionMath.compose(src, res, sel, alphaLocked = false, maskTarget = false)
        assertEquals(0xFF000000.toInt(), res.pixels[0])
        assertEquals(0x00FFFFFF, res.pixels[1])
        assertEquals(ColorUtils.argb(204, 51, 51, 51), res.pixels[2])
    }

    @Test
    fun composeWithoutSelectionKeepsTheResult() {
        val src = PixelBuffer.filled(2, 2, 0xFF112233.toInt())
        val res = PixelBuffer.filled(2, 2, 0x80445566.toInt())
        FilterSessionMath.compose(src, res, null, alphaLocked = false, maskTarget = false)
        assertTrue(res.pixels.all { it == 0x80445566.toInt() })
    }

    @Test
    fun alphaLockKeepsSourceAlpha() {
        val src = PixelBuffer(2, 1, intArrayOf(0xFF102030.toInt(), 0x00000000))
        val res = PixelBuffer.filled(2, 1, 0x80FF0000.toInt())
        FilterSessionMath.compose(src, res, null, alphaLocked = true, maskTarget = false)
        assertEquals(0xFFFF0000.toInt(), res.pixels[0])
        assertEquals(0, ColorUtils.alpha(res.pixels[1]))
    }

    @Test
    fun maskTargetWritesOpaqueLuminance() {
        val src = PixelBuffer.filled(3, 1, 0xFF000000.toInt())
        val res = PixelBuffer(3, 1, intArrayOf(0xFFFF0000.toInt(), 0x80FFFFFF.toInt(), 0x00FFFFFF))
        FilterSessionMath.compose(src, res, null, alphaLocked = false, maskTarget = true)
        val redLum = ColorUtils.luminance(0xFFFF0000.toInt())
        assertEquals(ColorUtils.gray(redLum), res.pixels[0])
        assertEquals(ColorUtils.gray(128), res.pixels[1]) // white at 50% over black
        assertEquals(ColorUtils.gray(0), res.pixels[2])
    }

    @Test
    fun composeOnlyTouchesTheRegion() {
        val src = PixelBuffer.filled(4, 4, 1)
        val res = PixelBuffer.filled(4, 4, 2)
        val sel = ByteArray(16) { 0 }
        FilterSessionMath.compose(src, res, sel, alphaLocked = false, maskTarget = false, region = PixelRect(1, 1, 3, 3))
        assertEquals(2, res[0, 0]) // outside the region: untouched (not written back anyway)
        assertEquals(1, res[1, 1]) // inside, unselected: source
    }

    @Test
    fun changedBoundsIsTightAndIgnoresInvisibleDifferences() {
        val a = PixelBuffer.filled(10, 8, 0)
        val b = a.copy()
        assertNull(FilterSessionMath.changedBounds(a, b))
        b[3, 2] = 0x00FF0000 // still fully transparent
        assertNull(FilterSessionMath.changedBounds(a, b))
        b[3, 2] = 0xFFFF0000.toInt()
        b[7, 5] = 0x01000000
        assertEquals(PixelRect(3, 2, 8, 6), FilterSessionMath.changedBounds(a, b))
        assertEquals(PixelRect(3, 2, 4, 3), FilterSessionMath.changedBounds(a, b, PixelRect(0, 0, 5, 5)))
        assertNull(FilterSessionMath.changedBounds(a, b, PixelRect(8, 0, 10, 8)))
    }

    @Test
    fun histogramIsWeightedByAlphaAndSelection() {
        val buf = PixelBuffer(3, 1, intArrayOf(0xFFFFFFFF.toInt(), 0x80000000.toInt(), 0x00808080))
        val h = FilterSessionMath.luminanceHistogram(buf)
        assertEquals(255, h[255])
        assertEquals(128, h[0])
        assertEquals(0, h[128])
        val hs = FilterSessionMath.luminanceHistogram(buf, byteArrayOf(0, 255.toByte(), 0))
        assertEquals(0, hs[255])
        assertEquals(128, hs[0])
        assertTrue(FilterSessionMath.isFullyTransparent(PixelBuffer(2, 2)))
        assertTrue(!FilterSessionMath.isFullyTransparent(buf))
    }

    @Test
    fun recentFiltersMoveToFrontAndAreCapped() {
        var list = emptyList<String>()
        for (i in 1..10) list = FilterRecents.push(list, "f$i")
        assertEquals(FilterRecents.MAX, list.size)
        assertEquals("f10", list.first())
        list = FilterRecents.push(list, "f5")
        assertEquals(listOf("f5", "f10", "f9", "f8", "f7", "f6", "f4", "f3"), list)
    }
}
