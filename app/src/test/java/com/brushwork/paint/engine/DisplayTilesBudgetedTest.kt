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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.6 foundation (§4.3, §4.9): [DisplayTiles.updateBudgeted]. At least one tile per call,
 * nearest the centre first, skipped tiles left dirty and not counted, `hasMore` true until done,
 * and an unlimited budget renders exactly what [DisplayTiles.update] renders (Robolectric, Skia;
 * time comes from an injected clock).
 */
@RunWith(RobolectricTestRunner::class)
class DisplayTilesBudgetedTest {
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

    /** A clock that advances [stepNs] per reading. */
    private fun ticking(stepNs: Long): () -> Long {
        var now = 0L
        return { now += stepNs; now }
    }

    @Test
    fun atLeastOneTileNearestTheCentreFirst() {
        val doc = document()
        val c = Compositor(doc) { null }
        val tiles = DisplayTiles(w, h)
        assertEquals(9, tiles.tileCount)
        assertEquals(4, tiles.tileIndexOf(1, 1))
        tiles.nanoClock = ticking(1_000_000)
        // A zero budget still renders one tile: the one nearest the centre (tile (2, 1)).
        val r = tiles.updateBudgeted(c, null, 0L, center = PointF(1300f, 700f))
        assertTrue(r.changed)
        assertTrue(r.hasMore)
        assertEquals((0 until 9).toSet() - tiles.tileIndexOf(2, 1), dirtySet(tiles))
        // Centre out: the next ones are the nearest remaining tiles, ties in index order.
        val order = ArrayList<Int>()
        var guard = 0
        while (guard++ < 20) {
            val before = dirtySet(tiles)
            val u = tiles.updateBudgeted(c, null, 0L, center = PointF(1300f, 700f))
            order += (before - dirtySet(tiles)).single()
            if (!u.hasMore) break
        }
        assertEquals(8, order.size)
        assertTrue(dirtySet(tiles).isEmpty())
        val dist = order.map { idx ->
            val r0 = tiles.tileRect(idx % tiles.cols, idx / tiles.cols)
            val dx = r0.exactCenterX() - 1300f; val dy = r0.exactCenterY() - 700f
            dx * dx + dy * dy
        }
        assertEquals(dist.sorted(), dist)
        // Nothing left: no change, no more.
        val done = tiles.updateBudgeted(c, null, 0L)
        assertFalse(done.changed)
        assertFalse(done.hasMore)
    }

    @Test
    fun theBudgetLimitsTheTilesPerCall() {
        val doc = document()
        val c = Compositor(doc) { null }
        val tiles = DisplayTiles(w, h)
        // 1 ms per clock reading, 3 ms budget: the start reading, then one per tile -> 3 tiles.
        tiles.nanoClock = ticking(1_000_000)
        val r = tiles.updateBudgeted(c, null, 3_000_000L)
        assertTrue(r.hasMore)
        assertEquals(6, dirtySet(tiles).size)
    }

    @Test
    fun skippedTilesStayDirtyAndDontCount() {
        val doc = document()
        val c = Compositor(doc) { null }
        val tiles = DisplayTiles(w, h)
        val skipped = setOf(0, 4, 8)
        val r = tiles.updateBudgeted(c, null, Long.MAX_VALUE, skip = { it in skipped })
        assertTrue(r.changed)
        assertFalse("only skipped tiles are left", r.hasMore)
        assertEquals(skipped, dirtySet(tiles))
        // Off-screen tiles stay dirty too (visible first).
        val t2 = DisplayTiles(w, h)
        val visible = Rect(0, 0, 600, 400)
        val r2 = t2.updateBudgeted(c, visible, Long.MAX_VALUE)
        assertFalse(r2.hasMore)
        assertEquals((0 until 9).toSet() - setOf(0, 1), dirtySet(t2))
    }

    @Test
    fun anUnlimitedBudgetEqualsUpdate() {
        val doc = document()
        val c = Compositor(doc) { null }
        val visible = Rect(100, 200, 1200, 900)
        val a = DisplayTiles(w, h)
        val b = DisplayTiles(w, h)
        a.update(c, visible)
        val r = b.updateBudgeted(c, visible, Long.MAX_VALUE, center = PointF(650f, 550f))
        assertTrue(r.changed)
        assertFalse(r.hasMore)
        assertEquals(dirtySet(a), dirtySet(b))
        // A partial invalidation, then both again.
        a.invalidate(Rect(500, 300, 900, 700)); b.invalidate(Rect(500, 300, 900, 700))
        a.update(c, visible)
        b.updateBudgeted(c, visible, Long.MAX_VALUE)
        assertArrayEquals(pixels(a), pixels(b))
    }

    private fun pixels(t: DisplayTiles): IntArray {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        t.draw(Canvas(out), null, smooth = false)
        val px = IntArray(w * h)
        out.getPixels(px, 0, w, 0, 0, w, h)
        out.recycle()
        return px
    }
}
