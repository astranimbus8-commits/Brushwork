package com.brushwork.paint.v110.a3

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.brushwork.paint.array.ArrayLookKit.BLUE
import com.brushwork.paint.array.ArrayLookKit.RED
import com.brushwork.paint.array.ArrayLookKit.WHITE
import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.ArrayColorMode
import com.brushwork.paint.model.ArrayLook
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.ArrayTint
import com.brushwork.paint.model.CopyLook
import com.brushwork.paint.model.LayerArray
import com.brushwork.paint.vector.VStop
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs
import kotlin.random.Random

/**
 * v1.10 (R5 (c), A3 step 5): a Recolored copy draws through
 * `ColorMatrixColorFilter(ColorUtils.recolorMatrix(target, s))` and so equals the exact
 * `ColorUtils.recolor(c, target, s)` within ±1 per channel on opaque pixels (alpha kept; a
 * translucent pixel goes through premultiplied storage, a looser bound). The filters come from a
 * 256-entry LRU in `ArrayDraw` keyed by the target's RGB and the clamped strength; a Tinted copy
 * keeps v1.9's own filter. Robolectric NATIVE graphics.
 */
@RunWith(RobolectricTestRunner::class)
class ArrayRecolorMatchesRecolorTest {

    @After
    fun tearDown() = ArrayDraw.clearCaches()

    private fun ch(c: Int, shift: Int) = (c shr shift) and 0xFF

    private fun maxChannelDiff(a: Int, b: Int): Int = maxOf(abs(ch(a, 16) - ch(b, 16)), abs(ch(a, 8) - ch(b, 8)), abs(ch(a, 0) - ch(b, 0)))

    /** [px] (unpremultiplied, [w] x [w]) drawn through [paint] into a cleared bitmap, read back unpremultiplied. */
    private fun drawn(px: IntArray, w: Int, paint: Paint): IntArray {
        val src = Bitmap.createBitmap(px, w, w, Bitmap.Config.ARGB_8888)
        val dst = Bitmap.createBitmap(w, w, Bitmap.Config.ARGB_8888)
        Canvas(dst).drawBitmap(src, 0f, 0f, paint)
        return IntArray(w * w).also { dst.getPixels(it, 0, w, 0, 0, w, w) }
    }

    @Test
    fun aRecoloredCopysPaintEqualsRecolorWithinOne() {
        val rnd = Random(110)
        val w = 32
        val corners = intArrayOf(0xFF000000.toInt(), WHITE, RED, 0xFF00FFFF.toInt())
        val opaque = IntArray(w * w) { if (it < corners.size) corners[it] else rnd.nextInt() or 0xFF000000.toInt() }
        val translucent = IntArray(w * w) { (rnd.nextInt(128, 256) shl 24) or (rnd.nextInt() and 0xFFFFFF) }
        // The target's alpha is ignored (0x40FF8800 recolors as 0xFFFF8800).
        val targets = intArrayOf(RED, BLUE, 0xFF3D7BFF.toInt(), 0x40FF8800, 0xFF000000.toInt(), WHITE, rnd.nextInt())
        var worst = 0
        for (target in targets) for (s in floatArrayOf(0.25f, 0.5f, 0.7f, 1f)) {
            val paint = ArrayDraw.paintOf(CopyLook(1f, target, s, ArrayColorMode.RECOLOR), null)
            val out = drawn(opaque, w, paint)
            for (i in opaque.indices) {
                val want = ColorUtils.recolor(opaque[i], target, s)
                assertEquals("alpha kept", want ushr 24, out[i] ushr 24)
                val d = maxChannelDiff(want, out[i])
                worst = maxOf(worst, d)
                assertTrue("copy %08X vs recolor %08X (pixel %08X, target %08X, s %s)".format(out[i], want, opaque[i], target, s), d <= 1)
            }
            val outT = drawn(translucent, w, paint)
            for (i in translucent.indices) {
                val want = ColorUtils.recolor(translucent[i], target, s)
                assertEquals("alpha kept", want ushr 24, outT[i] ushr 24)
                assertTrue("translucent %08X vs %08X".format(outT[i], want), maxChannelDiff(want, outT[i]) <= 3)
            }
        }
        assertTrue("checked", worst in 0..1)

        // The copy's opacity still multiplies its alpha; the colors are recolored under it.
        val half = drawn(opaque, w, ArrayDraw.paintOf(CopyLook(0.5f, RED, 0.6f, ArrayColorMode.RECOLOR), null))
        for (i in opaque.indices) {
            assertEquals(128f, (half[i] ushr 24).toFloat(), 1f)
            assertTrue(maxChannelDiff(ColorUtils.recolor(opaque[i], RED, 0.6f), half[i]) <= 3)
        }
    }

    @Test
    fun theFiltersAreSharedAndBounded() {
        ArrayDraw.clearCaches()
        val target = 0xFF3D7BFF.toInt()
        val f = ArrayDraw.recolorFilter(target, 0.5f)
        assertSame("the same (target, s): the same filter", f, ArrayDraw.recolorFilter(target, 0.5f))
        assertSame("the target's alpha is ignored", f, ArrayDraw.recolorFilter(0x203D7BFF, 0.5f))
        assertSame("s is clamped", ArrayDraw.recolorFilter(target, 1f), ArrayDraw.recolorFilter(target, 1.7f))
        assertNotSame(f, ArrayDraw.recolorFilter(target, 0.6f))
        assertNotSame(f, ArrayDraw.recolorFilter(RED, 0.5f))
        // A copy's paint takes it from the cache; Tinted keeps its own (v1.9) filter; no strength, no filter.
        assertSame(f, ArrayDraw.paintOf(CopyLook(0.8f, target, 0.5f, ArrayColorMode.RECOLOR), null).colorFilter)
        assertNotSame(f, ArrayDraw.paintOf(CopyLook(0.8f, target, 0.5f, ArrayColorMode.TINT), null).colorFilter)
        assertNull(ArrayDraw.paintOf(CopyLook(0.8f, target, 0f, ArrayColorMode.RECOLOR), null).colorFilter)

        // 256 kept: the first survives 255 others (and is used again), then 256 more push it out.
        ArrayDraw.clearCaches()
        val first = ArrayDraw.recolorFilter(target, 0.5f)
        for (i in 1 until ArrayDraw.RECOLOR_FILTERS) ArrayDraw.recolorFilter(0xFF000000.toInt() or i, 0.5f)
        assertSame("256 kept", first, ArrayDraw.recolorFilter(target, 0.5f))
        for (i in 1..ArrayDraw.RECOLOR_FILTERS) ArrayDraw.recolorFilter(0xFF100000.toInt() or i, 0.5f)
        assertNotSame("the oldest pushed out", first, ArrayDraw.recolorFilter(target, 0.5f))
        val again = ArrayDraw.recolorFilter(target, 0.5f)
        ArrayDraw.clearCaches()
        assertNotSame("cleared", again, ArrayDraw.recolorFilter(target, 0.5f))
    }

    @Test
    fun aTextOrShapeCopyDrawnThroughItsLayerIsRecolored() {
        // What a text or shape array draws: the source (two flat colors) under each copy's matrix,
        // a copy with a look through a layer with its paint.
        val orange = 0xFFE07020.toInt()
        val navy = 0xFF2050A0.toInt()
        val source = RectF(10f, 10f, 30f, 30f)
        fun drawSource(cv: Canvas) {
            cv.drawRect(10f, 10f, 20f, 30f, Paint().apply { color = orange })
            cv.drawRect(20f, 10f, 30f, 30f, Paint().apply { color = navy })
        }
        val stops = listOf(VStop(0f, RED), VStop(1f, BLUE))
        val look = ArrayLook(tint = stops, tintStrength = 0.6f, colorMode = ArrayColorMode.RECOLOR)
        val spec = ArraySpec(count = 3, relativeX = 1.5f, look = look)
        val out = BitmapUtils.createLayerBitmap(120, 40)
        ArrayDraw.drawWithArray(Canvas(out), LayerArray(spec), source, ::drawSource)
        // Copies 1 and 2 sit 30 and 60 px right of the source, at t = 0 (red) and 1 (blue).
        for ((k, t) in listOf(1 to 0f, 2 to 1f)) {
            val target = ArrayTint.sample(stops, t)
            for ((x, c) in listOf(15 to orange, 25 to navy)) {
                val want = ColorUtils.recolor(c, target, 0.6f)
                val got = out.getPixel(x + 30 * k, 20)
                assertEquals(0xFF, got ushr 24)
                assertTrue("copy $k at $x: %08X vs %08X".format(got, want), maxChannelDiff(want, got) <= 1)
            }
        }
        assertEquals("the source as it is", orange, out.getPixel(15, 20))
        assertEquals(navy, out.getPixel(25, 20))
        // The same look Tinted draws v1.9's tint (the mode is what changes).
        val tinted = BitmapUtils.createLayerBitmap(120, 40)
        ArrayDraw.drawWithArray(Canvas(tinted), LayerArray(spec.copy(look = look.copy(colorMode = ArrayColorMode.TINT))), source, ::drawSource)
        val want = ArrayTint.apply(orange, RED, 0.6f)
        assertTrue(maxChannelDiff(want, tinted.getPixel(45, 20)) <= 2)
        assertTrue("Tinted differs from Recolored", maxChannelDiff(tinted.getPixel(45, 20), out.getPixel(45, 20)) > 8)
    }
}
