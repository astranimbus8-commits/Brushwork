package com.brushwork.paint.v19.a6

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.engine.DisplayTiles
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 A6 step 3 (P2): the display tiles keep their dirty rects, canvases and composite targets
 * and reuse their scratch state, and the compositor shares its group paints on the main thread;
 * frame after frame the canvas still shows exactly a fresh render of the document.
 */
@RunWith(RobolectricTestRunner::class)
class DisplayTilesReuseTest {

    private fun document(): Document {
        val d = Document("t", "t", W, H)
        val bg = BitmapUtils.createLayerBitmap(W, H).also { it.eraseColor(0xFFEEDDCC.toInt()) }
        d.layers += Layer(d.newLayerId(), "Background", bg)
        for ((k, mode) in listOf(LayerBlendMode.MULTIPLY, LayerBlendMode.SCREEN, LayerBlendMode.NORMAL).withIndex()) {
            val b = BitmapUtils.createLayerBitmap(W, H)
            Canvas(b).drawCircle(120f + 130f * k, 150f, 110f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xC03355AA.toInt() + k * 0x2210 })
            d.layers += Layer(d.newLayerId(), "L$k", b).also { it.blendMode = mode; it.opacity = 0.4f + 0.2f * k }
        }
        return d
    }

    private fun DisplayTiles.pixels(): IntArray {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp), null, smooth = false)
        return IntArray(W * H).also { bmp.getPixels(it, 0, W, 0, 0, W, H) }
    }

    private fun fresh(c: Compositor): IntArray = DisplayTiles(W, H, TILE).also { it.update(c, null) }.pixels()

    @Test
    fun framesAfterFramesShowTheDocument() {
        val doc = document()
        val c = Compositor(doc) { null }
        val tiles = DisplayTiles(W, H, TILE)
        assertTrue(tiles.hasDirty)
        assertTrue(tiles.update(c, null))
        assertFalse(tiles.hasDirty)
        assertFalse("nothing dirty, nothing rendered", tiles.update(c, null))
        val top = doc.layers.last().bitmap
        val dab = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF20A040.toInt() }
        // Strokes across tile corners, a frame after each few dabs, some tiles dirty twice.
        for (k in 0 until 30) {
            val x = 20f + 15.7f * k
            val y = 40f + 7.3f * k
            Canvas(top).drawCircle(x, y, 9f, dab)
            tiles.invalidate(Rect((x - 10).toInt(), (y - 10).toInt(), (x + 11).toInt(), (y + 11).toInt()))
            if (k % 4 == 3) {
                tiles.update(c, null)
                assertFalse(tiles.hasDirty)
            }
        }
        tiles.update(c, null)
        assertArrayEquals(fresh(c), tiles.pixels())
        // Released tiles come back with new canvases and targets.
        tiles.release()
        tiles.invalidate(null)
        tiles.update(c, null)
        assertArrayEquals(fresh(c), tiles.pixels())
    }

    @Test
    fun dirtyRectsAndTheHook() {
        val doc = document()
        val c = Compositor(doc) { null }
        val tiles = DisplayTiles(W, H, TILE)
        tiles.update(c, null)
        val seen = ArrayList<Rect>()
        tiles.invalidationHook = { r ->
            seen += Rect(r)
            // A hook that invalidates again (as nested edits may) leaves the outer call intact.
            if (seen.size == 1) tiles.invalidate(Rect(400, 250, 410, 260))
        }
        tiles.invalidate(Rect(-20, 100, 140, 140))
        tiles.invalidationHook = null
        assertEquals(listOf(Rect(0, 100, 140, 140), Rect(400, 250, 410, 260)), seen)
        assertEquals(Rect(0, 100, 128, 128), tiles.dirtyRect(0))
        assertEquals(Rect(128, 100, 140, 128), tiles.dirtyRect(1))
        assertEquals(Rect(0, 128, 128, 140), tiles.dirtyRect(tiles.tileIndexOf(0, 1)))
        assertEquals(Rect(400, 250, 410, 256), tiles.dirtyRect(tiles.tileIndexOf(3, 1)))
        assertEquals(Rect(400, 256, 410, 260), tiles.dirtyRect(tiles.tileIndexOf(3, 2)))
        assertNull(tiles.dirtyRect(2))
        // The returned rect is a copy.
        tiles.dirtyRect(0)!!.set(0, 0, 1, 1)
        assertEquals(Rect(0, 100, 128, 128), tiles.dirtyRect(0))
        // Budgeted, nearest the centre first, then the rest.
        val r = tiles.updateBudgeted(c, null, 0L, center = PointF(420f, 280f))
        assertTrue(r.changed)
        assertTrue(r.hasMore)
        assertFalse(tiles.isDirty(tiles.tileIndexOf(3, 2)))
        assertTrue(tiles.isDirty(tiles.tileIndexOf(3, 1)))
        val all = tiles.updateBudgeted(c, null, Long.MAX_VALUE)
        assertTrue(all.changed)
        assertFalse(all.hasMore)
        assertFalse(tiles.hasDirty)
        val none = tiles.updateBudgeted(c, null, Long.MAX_VALUE)
        assertFalse(none.changed)
        assertFalse(none.hasMore)
        assertArrayEquals(fresh(c), tiles.pixels())
    }

    private companion object {
        const val W = 512
        const val H = 300
        const val TILE = 128
    }
}
