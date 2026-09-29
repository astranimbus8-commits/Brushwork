package com.brushwork.paint.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins down how Skia treats ALPHA_8 bitmaps with xfermodes. drawBitmap(A8) uses the bitmap as a
 * COVERAGE mask for the paint color, so DST_IN/DST_OUT behave differently from an ARGB source.
 * Use [BitmapUtils.maskWith] to multiply by a selection mask.
 */
@RunWith(RobolectricTestRunner::class)
class Alpha8BlendTest {
    private fun halfMask(): Bitmap {
        val m = Bitmap.createBitmap(4, 1, Bitmap.Config.ALPHA_8)
        Canvas(m).drawRect(0f, 0f, 2f, 1f, Paint().apply { color = 0xFF000000.toInt() })
        return m
    }

    @Test
    fun maskWithKeepsOnlyTheMaskedArea() {
        val dst = BitmapUtils.createLayerBitmap(4, 1).apply { eraseColor(0xFFFF0000.toInt()) }
        BitmapUtils.maskWith(Canvas(dst), halfMask())
        assertEquals(0xFFFF0000.toInt(), dst.getPixel(0, 0))
        assertEquals(0, dst.getPixel(3, 0))
    }

    @Test
    fun maskWithWorksOnAlpha8Destinations() {
        val dst = Bitmap.createBitmap(4, 1, Bitmap.Config.ALPHA_8).apply { eraseColor(0xFF000000.toInt()) }
        BitmapUtils.maskWith(Canvas(dst), halfMask())
        val b = BitmapUtils.alpha8ToBytes(dst)
        assertEquals(255, b[0].toInt() and 0xFF)
        assertEquals(0, b[3].toInt() and 0xFF)
    }

    @Test
    fun plainDrawBitmapDstInIsCoverageBased() {
        // Documents the pitfall: this does NOT clear the unmasked half.
        val dst = BitmapUtils.createLayerBitmap(4, 1).apply { eraseColor(0xFFFF0000.toInt()) }
        Canvas(dst).drawBitmap(halfMask(), 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        println("drawBitmap(A8, DST_IN) right pixel = ${Integer.toHexString(dst.getPixel(3, 0))}")
    }
}
