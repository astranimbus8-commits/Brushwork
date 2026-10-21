package com.brushwork.paint.v110.lead

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.adjust.ChangeDrawingColorFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs
import kotlin.random.Random

/**
 * v1.10 F0 (C3, finding 6; lead, frozen): [ColorUtils.recolor] is v1.9's Change Drawing Color
 * per pixel, bit for bit: against a copy of v1.9's `ChangeDrawingColorFilter.apply` kept here
 * ([v19]), and against the filter itself (until A4 makes the filter call recolor, this compares
 * two implementations). [ColorUtils.recolorMatrix] drawn with drawBitmap (Robolectric NATIVE
 * graphics) equals recolor() within ±1 per channel on opaque pixels, and keeps alpha.
 */
@RunWith(RobolectricTestRunner::class)
class ColorUtilsRecolorTest {

    /** v1.9's ChangeDrawingColorFilter.apply for one pixel ([amountPercent] = the Strength slider, 0..100). */
    private fun v19(c: Int, color: Int, amountPercent: Float): Int {
        val amount = (amountPercent / 100f).coerceIn(0f, 1f)
        if (amount <= 0f) return c
        val rgb = color and 0xFFFFFF
        val a = c ushr 24
        return if (a == 0) c else {
            val m = rgb or (a shl 24)
            if (amount >= 1f) m else ColorUtils.lerp(c, m, amount)
        }
    }

    private val extremes = intArrayOf(
        0, 0x00FFFFFF, 0x00123456, 0x01000000, 0x01FFFFFF, 0x80808080.toInt(), 0x7F000000, 0xFE00FF00.toInt(),
        0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFF7F7F7F.toInt(),
    )

    private fun pixels(seed: Int, n: Int): IntArray {
        val rnd = Random(seed)
        return extremes + IntArray(n) { rnd.nextInt() }
    }

    private val amounts = floatArrayOf(-10f, 0f, 0.4f, 1f, 12.5f, 37f, 50f, 99.6f, 100f, 150f)

    @Test
    fun recolorIsV19ChangeDrawingColorBitForBit() {
        val px = pixels(6, 4096)
        val rnd = Random(61)
        val targets = intArrayOf(0xFF1E5BD8.toInt(), 0x00FF8800, 0x80FFFFFF.toInt(), 0, 0xFFFFFFFF.toInt()) + IntArray(6) { rnd.nextInt() }
        val filter = ChangeDrawingColorFilter()
        var checked = 0
        for (target in targets) for (amount in amounts) {
            val s = amount / 100f
            val values = filter.defaultValues().set("color", target).set("amount", amount)
            val applied = filter.apply(PixelBuffer(px.size, 1, px.copyOf()), values, FilterContext()).pixels
            for (i in px.indices) {
                val c = px[i]
                val r = ColorUtils.recolor(c, target, s)
                if (r != v19(c, target, amount) || r != applied[i]) {
                    throw AssertionError(
                        "recolor(%08X, %08X, %s) = %08X; v1.9 %08X; filter %08X".format(c, target, s, r, v19(c, target, amount), applied[i]),
                    )
                }
                checked++
            }
        }
        assertEquals(targets.size * amounts.size * px.size, checked)
        // The rules spelled out: alpha 0 and s <= 0 unchanged; alpha kept; the target's alpha ignored.
        assertEquals(0x00123456, ColorUtils.recolor(0x00123456, 0xFFFF0000.toInt(), 1f))
        assertEquals(0x80123456.toInt(), ColorUtils.recolor(0x80123456.toInt(), 0xFFFF0000.toInt(), 0f))
        assertEquals(0x80FF0000.toInt(), ColorUtils.recolor(0x80123456.toInt(), 0x00FF0000, 1f))
        assertEquals(0x80FF0000.toInt(), ColorUtils.recolor(0x80123456.toInt(), 0x33FF0000, 2f))
        assertEquals(0x80123456.toInt(), ColorUtils.recolor(0x80123456.toInt(), 0x33FF0000, Float.NaN))
    }

    @Test
    fun recolorMatrixDrawsAsRecolorWithinOne() {
        val rnd = Random(9)
        val opaque = IntArray(64 * 64) { if (it < 4) intArrayOf(0xFF000000.toInt(), -1, 0xFFFF0000.toInt(), 0xFF00FFFF.toInt())[it] else rnd.nextInt() or 0xFF000000.toInt() }
        val translucent = IntArray(64 * 64) { (rnd.nextInt(128, 255) shl 24) or (rnd.nextInt() and 0xFFFFFF) }
        val targets = intArrayOf(0xFF1E5BD8.toInt(), 0x40FF8800, 0xFF000000.toInt(), -1, rnd.nextInt())
        var worst = 0
        for (target in targets) for (s in floatArrayOf(0f, 0.25f, 0.5f, 0.9f, 1f, 1.5f)) {
            val outOpaque = draw(opaque, ColorUtils.recolorMatrix(target, s))
            for (i in opaque.indices) {
                val want = ColorUtils.recolor(opaque[i], target, s)
                val got = outOpaque[i]
                assertEquals("alpha kept", want ushr 24, got ushr 24)
                val d = maxChannelDiff(want, got)
                worst = maxOf(worst, d)
                assertTrue("matrix %08X vs recolor %08X (pixel %08X, target %08X, s %s)".format(got, want, opaque[i], target, s), d <= 1)
            }
            // Translucent pixels: alpha is kept exactly; the colors go through premultiplied storage,
            // so only a loose bound holds (the ±1 of the design is for the color math).
            val outTranslucent = draw(translucent, ColorUtils.recolorMatrix(target, s))
            for (i in translucent.indices) {
                val want = ColorUtils.recolor(translucent[i], target, s)
                assertEquals("alpha kept", want ushr 24, outTranslucent[i] ushr 24)
                assertTrue(maxChannelDiff(want, outTranslucent[i]) <= 3)
            }
        }
        assertTrue("checked", worst in 0..1)
    }

    /** [px] (unpremultiplied, 64 x 64) drawn through [m] into a cleared bitmap, read back unpremultiplied. */
    private fun draw(px: IntArray, m: android.graphics.ColorMatrix): IntArray {
        val src = Bitmap.createBitmap(px, 64, 64, Bitmap.Config.ARGB_8888)
        val dst = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(m)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        }
        Canvas(dst).drawBitmap(src, 0f, 0f, paint)
        val out = IntArray(64 * 64)
        dst.getPixels(out, 0, 64, 0, 0, 64, 64)
        return out
    }

    private fun maxChannelDiff(a: Int, b: Int): Int =
        maxOf(abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)), abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)), abs((a and 0xFF) - (b and 0xFF)))
}
