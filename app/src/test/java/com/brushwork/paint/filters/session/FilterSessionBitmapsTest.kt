package com.brushwork.paint.filters.session

import android.graphics.Bitmap
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.filters.FilterSessionBitmaps
import com.brushwork.paint.filters.FilterSessionMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The preview downscale (real Skia): thin details must survive large reductions. */
@RunWith(RobolectricTestRunner::class)
class FilterSessionBitmapsTest {

    @Test
    fun everyOnePixelSelectionColumnSurvivesTheReduction() {
        val w = 3000; val h = 4
        val (pw, ph) = FilterSessionMath.previewSize(w, h)
        for (col in 1000 until 1075) {
            val sel = BitmapUtils.bytesToAlpha8(ByteArray(w * h) { if (it % w == col) -1 else 0 }, w, h)
            val out = FilterSessionBitmaps.downscale(sel, pw, ph)
            assertEquals(Bitmap.Config.ALPHA_8, out.config)
            assertEquals(pw, out.width)
            assertEquals(ph, out.height)
            assertTrue("selected column $col vanished", BitmapUtils.alpha8ToBytes(out).any { it.toInt() != 0 })
            assertFalse("the source is never recycled", sel.isRecycled)
        }
    }

    @Test
    fun thinLinesStayVisibleAndPlainAreasStayExact() {
        val w = 4000; val h = 40
        val (pw, ph) = FilterSessionMath.previewSize(w, h)
        for (col in 2000 until 2010) {
            val src = BitmapUtils.createLayerBitmap(w, h)
            src.eraseColor(0xFF2040C0.toInt())
            for (y in 0 until h) src.setPixel(col, y, 0xFFFFFFFF.toInt())
            val out = FilterSessionBitmaps.downscale(src, pw, ph)
            val px = IntArray(pw * ph).also { out.getPixels(it, 0, pw, 0, 0, pw, ph) }
            val brightest = px.maxOf { it and 0xFF }
            // A single bilinear 3.125x step skips some columns entirely (blue stays 0xC0).
            assertTrue("white line at $col faded to $brightest", brightest >= 0xC0 + 5)
            assertEquals("plain areas are unchanged", 0xFF2040C0.toInt(), px[0])
        }
    }

    @Test
    fun sameSizeReturnsTheSource() {
        val src = BitmapUtils.createLayerBitmap(10, 10)
        assertSame(src, FilterSessionBitmaps.downscale(src, 10, 10))
        val thin = FilterSessionBitmaps.downscale(BitmapUtils.createLayerBitmap(20000, 3), 1280, 1)
        assertEquals(1280, thin.width)
        assertEquals(1, thin.height)
    }
}
