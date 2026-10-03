package com.brushwork.paint.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.6 §3.1d (area A): what live adjustment sessions use of [DisplayTiles] beyond the foundation's
 * [DisplayTilesBudgetedTest] — the invalidation hook (proxies follow every invalidated area), the
 * dirty part of a tile, drawing with skipped tiles and one tile alone, and refinement of a set of
 * tiles (the session's) one budgeted call at a time until `hasMore` is false.
 */
@RunWith(RobolectricTestRunner::class)
class ProgressiveTilesBudgetTest {
    private val w = 1500
    private val h = 1100

    private fun document(): Document {
        val d = Document("t", "t", w, h)
        val l = Layer(d.newLayerId(), "L", BitmapUtils.createLayerBitmap(w, h))
        val c = Canvas(l.bitmap)
        c.drawColor(0xFFEEDDCC.toInt())
        c.drawCircle(700f, 500f, 400f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3355AA.toInt() })
        d.layers += l
        return d
    }

    private fun dirtySet(t: DisplayTiles): Set<Int> = (0 until t.tileCount).filter { t.isDirty(it) }.toSet()

    private fun pixels(draw: (Canvas) -> Unit): IntArray {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        draw(Canvas(out))
        return IntArray(w * h).also { out.getPixels(it, 0, w, 0, 0, w, h); out.recycle() }
    }

    @Test
    fun theInvalidationHookSeesEveryInvalidatedAreaWithinTheDocument() {
        val tiles = DisplayTiles(w, h)
        val seen = ArrayList<Rect>()
        tiles.invalidationHook = { seen += Rect(it) }
        tiles.invalidate(Rect(100, 100, 300, 200))
        tiles.invalidate(Rect(-50, 1000, 200, 1300))
        tiles.invalidate(null)
        tiles.invalidate(Rect(2000, 2000, 2100, 2100))
        assertEquals(listOf(Rect(100, 100, 300, 200), Rect(0, 1000, 200, 1100), Rect(0, 0, w, h)), seen)
        tiles.invalidationHook = null
        tiles.invalidate(null)
        assertEquals(3, seen.size)
    }

    @Test
    fun theDirtyPartOfATileIsTheUnionOfItsInvalidations() {
        val doc = document()
        val c = Compositor(doc) { null }
        val tiles = DisplayTiles(w, h)
        tiles.update(c, null)
        assertNull(tiles.dirtyRect(0))
        tiles.invalidate(Rect(10, 20, 30, 40))
        tiles.invalidate(Rect(100, 5, 120, 25))
        assertEquals(Rect(10, 5, 120, 40), tiles.dirtyRect(0))
        // A copy: changing it changes nothing.
        tiles.dirtyRect(0)!!.set(0, 0, 512, 512)
        assertEquals(Rect(10, 5, 120, 40), tiles.dirtyRect(0))
        // Across a tile edge each tile gets its own part.
        tiles.invalidate(Rect(500, 600, 530, 610))
        assertEquals(Rect(500, 600, 512, 610), tiles.dirtyRect(tiles.tileIndexOf(0, 1)))
        assertEquals(Rect(512, 600, 530, 610), tiles.dirtyRect(tiles.tileIndexOf(1, 1)))
        assertNull(tiles.dirtyRect(-1))
        assertNull(tiles.dirtyRect(tiles.tileCount))
    }

    @Test
    fun drawSkipsTilesAndDrawTileDrawsOneExactly() {
        val doc = document()
        val c = Compositor(doc) { null }
        val tiles = DisplayTiles(w, h)
        tiles.update(c, null)
        val all = pixels { tiles.draw(it, null, smooth = false) }
        val skipped = pixels { tiles.draw(it, null, smooth = false) { idx -> idx == 4 } }
        val alone = pixels { tiles.drawTile(it, 4, smooth = false) }
        val r = tiles.tileRect(1, 1)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (r.contains(x, y)) {
                assertEquals("skipped tile ($x, $y)", 0, skipped[i])
                assertEquals("tile alone ($x, $y)", all[i], alone[i])
            } else {
                assertEquals(all[i], skipped[i])
                assertEquals(0, alone[i])
            }
        }
        // Nothing for a tile never rendered, or out of range.
        val fresh = DisplayTiles(w, h)
        assertTrue(pixels { fresh.drawTile(it, 0) }.all { it == 0 })
        assertTrue(pixels { tiles.drawTile(it, 99) }.all { it == 0 })
    }

    @Test
    fun refiningASetOfTilesOneBudgetedCallAtATime() {
        val doc = document()
        val c = Compositor(doc) { null }
        val tiles = DisplayTiles(w, h)
        tiles.update(c, null)
        // A "session" changed the centre column; another change touched tile 0.
        tiles.invalidate(Rect(512, 0, 1024, h))
        tiles.invalidate(Rect(0, 0, 50, 50))
        val session = setOf(1, 4, 7)
        var now = 0L
        tiles.nanoClock = { now.also { now += 10_000_000L } }
        val visible = Rect(0, 0, w, h)
        // Everything outside the session first (unlimited), then the session within 8 ms per call.
        val outside = tiles.updateBudgeted(c, visible, Long.MAX_VALUE, skip = { it in session })
        assertTrue(outside.changed)
        assertFalse(outside.hasMore)
        assertEquals(session, dirtySet(tiles))
        val order = ArrayList<Int>()
        var calls = 0
        while (true) {
            val before = dirtySet(tiles)
            val u = tiles.updateBudgeted(c, visible, 8_000_000L, skip = { it !in session }, center = PointF(750f, 1000f))
            calls++
            assertTrue("at least one tile per call", u.changed)
            order += before - dirtySet(tiles)
            if (!u.hasMore) break
            assertTrue(calls < 10)
        }
        assertEquals("one tile per 8 ms call", 3, calls)
        assertEquals("centre out from the bottom", listOf(7, 4, 1), order)
        assertTrue(dirtySet(tiles).isEmpty())
        // The result equals a plain update.
        val ref = DisplayTiles(w, h).also { it.update(c, null) }
        assertArrayEquals(pixels { ref.draw(it, null, smooth = false) }, pixels { tiles.draw(it, null, smooth = false) })
        assertNotEquals(0, order.size)
    }
}
