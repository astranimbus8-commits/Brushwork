package com.brushwork.paint.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.SelectionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Runs real Skia (Robolectric NATIVE graphics, see robolectric.properties) to verify the
 * bitmap/canvas behaviors the engine relies on. Use this file as the pattern for engine tests.
 */
@RunWith(RobolectricTestRunner::class)
class EngineRobolectricTest {

    @Test
    fun alpha8RoundTripAndCanvas() {
        val bytes = ByteArray(10 * 7) { (it * 3).toByte() }
        val bmp = BitmapUtils.bytesToAlpha8(bytes, 10, 7)
        val back = BitmapUtils.alpha8ToBytes(bmp)
        assertTrue(bytes.contentEquals(back))
        // Drawing into an ALPHA_8 canvas works and only alpha is stored.
        val m = Bitmap.createBitmap(4, 4, Bitmap.Config.ALPHA_8)
        Canvas(m).drawRect(0f, 0f, 2f, 4f, Paint().apply { color = 0xFF000000.toInt() })
        val b = BitmapUtils.alpha8ToBytes(m)
        assertEquals(255, b[0].toInt() and 0xFF)
        assertEquals(0, b[3].toInt() and 0xFF)
    }

    @Test
    fun selectionCombineAndBounds() {
        val a = Selection.fromBytes(ByteArray(16) { if (it % 4 < 2) -1 else 0 }, 4, 4)
        assertEquals(Rect(0, 0, 2, 4), a.bounds)
        val inv = a.inverted()
        assertEquals(Rect(2, 0, 4, 4), inv.bounds)
        val all = a.combine(inv, SelectionMode.ADD)
        assertEquals(Rect(0, 0, 4, 4), all.bounds)
        val none = a.combine(a, SelectionMode.SUBTRACT)
        assertTrue(none.isEmpty)
    }

    @Test
    fun compositorBlendsAndClips() {
        val doc = Document("t", "t", 4, 4)
        val base = Layer(1, "base", BitmapUtils.createLayerBitmap(4, 4))
        Canvas(base.bitmap).drawRect(0f, 0f, 2f, 4f, Paint().apply { color = 0xFFFF0000.toInt() })
        val clip = Layer(2, "clip", BitmapUtils.createLayerBitmap(4, 4)).apply { clipping = true }
        clip.bitmap.eraseColor(0xFF0000FF.toInt())
        doc.layers += base
        doc.layers += clip
        val out = Compositor(doc) { null }.renderFlattened()
        assertEquals(0xFF0000FF.toInt(), out.getPixel(0, 0)) // clipped layer visible over base
        assertEquals(0, out.getPixel(3, 0))                   // but not outside the base's alpha
        clip.blendMode = LayerBlendMode.MULTIPLY
        val out2 = Compositor(doc) { null }.renderFlattened()
        assertEquals(0xFF000000.toInt(), out2.getPixel(0, 0)) // red * blue = black
    }

    @Test
    fun pixelRecorderRestoresExactly() {
        val layer = Layer(1, "l", BitmapUtils.createLayerBitmap(300, 300))
        layer.bitmap.eraseColor(0x80336699.toInt())
        val before = IntArray(300 * 300).also { layer.bitmap.getPixels(it, 0, 300, 0, 0, 300, 300) }
        val rec = PixelEditRecorder(layer, EditTarget.CONTENT)
        rec.touch(Rect(260, 260, 300, 300)) // only the bottom-right 256px tile
        Canvas(layer.bitmap).drawColor(0xFFFFFFFF.toInt())
        rec.abort()
        val after = IntArray(300 * 300).also { layer.bitmap.getPixels(it, 0, 300, 0, 0, 300, 300) }
        // Only the touched tile was snapshotted/restored; the rest keeps the white fill.
        assertEquals(before[299 * 300 + 299], after[299 * 300 + 299])
        assertEquals(0xFFFFFFFF.toInt(), after[0])
    }
}
