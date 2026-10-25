package com.brushwork.paint.v110.a8

import android.graphics.Bitmap
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.tiles.SolidTiles
import com.brushwork.paint.engine.tiles.TILE_SIZE
import com.brushwork.paint.engine.tiles.TileOps
import com.brushwork.paint.engine.tiles.TileWorker
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.v110.lead.F1Pixels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import com.brushwork.paint.engine.tiles.PackedPool
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 A8 step 1 (design 2.0 "Tile states", 2.16; F1 review gap 2): a uniform opaque tile shares
 * one [SolidTiles] bitmap per color, counted once under TILES; the count follows the tiles (not
 * their holders: a snapshot shares the tile), and the color goes with its last tile whatever state
 * that tile is found in (also one that is no longer resident) and only once no pin holds it. The
 * worker never packs or swaps a solid tile. Colors live for the whole JVM, so every count here is
 * a difference from the one before.
 */
@RunWith(RobolectricTestRunner::class)
class SolidTilesRefcountTest {
    private val made = mutableListOf<Bitmap>()

    private val baseline = A8Kit.Baseline()

    @After
    fun tearDown() {
        baseline.assertBack(emptyList())
        F1Pixels.resetEngine()
        made.forEach { if (!it.isRecycled) it.recycle() }
    }

    private fun uniform(w: Int, h: Int, color: Int): Bitmap =
        BitmapUtils.createLayerBitmap(w, h).apply { eraseColor(color) }.also { made += it }

    @Test
    fun uniformTilesShareOneBitmapCountedOnce() {
        val color = 0xFF3366AA.toInt()
        val colors = SolidTiles.colors()
        val refs = SolidTiles.refs(color)
        val ledger = A8Kit.tiles()
        val s = TiledPixels.fromBitmap(uniform(512, 300, color))
        assertEquals("four tiles share it", refs + 4, SolidTiles.refs(color))
        if (refs == 0) {
            assertEquals("one new color", colors + 1, SolidTiles.colors())
            assertEquals("one bitmap under TILES", ledger + TILE_SIZE.toLong() * TILE_SIZE * 4, A8Kit.tiles())
        }
        val a = s.tiles[0]!!
        val b = s.tiles[3]!!
        assertEquals(color, a.solidColor)
        assertEquals("an edge tile keeps its own size", 300 - TILE_SIZE, b.h)
        assertSame("one shared bitmap", a.bitmapForRead(), b.bitmapForRead())
        TileOps.releaseStore(s)
        assertEquals(refs, SolidTiles.refs(color))
        assertEquals(colors, SolidTiles.colors())
        assertEquals(ledger, A8Kit.tiles())
    }

    @Test
    fun aSnapshotSharesTheTileNotTheColor() {
        val color = 0xFF10C040.toInt()
        val refs = SolidTiles.refs(color)
        val s = TiledPixels.fromBitmap(uniform(256, 256, color))
        val snap = s.snapshot()
        val t = s.tiles[0]!!
        assertSame(t, snap.tiles[0])
        assertEquals("two holders of the tile", 2, t.refs)
        assertEquals("one count of the color per tile", refs + 1, SolidTiles.refs(color))
        TileOps.releaseStore(s)
        assertEquals("the snapshot still holds it", refs + 1, SolidTiles.refs(color))
        TileOps.releaseStore(snap)
        assertEquals(refs, SolidTiles.refs(color))
    }

    @Test
    fun theColorGoesWithATileThatIsNoLongerResident() {
        val color = 0xFF802010.toInt()
        val refs = SolidTiles.refs(color)
        val s = TiledPixels.fromBitmap(uniform(256, 256, color))
        val t = s.tiles[0]!!
        // A state F1's free missed (gap 2): the tile no longer points at the shared bitmap.
        val packed = TileOps.pack(t.bitmap!!)
        assertTrue("packed bytes are always the pool's", PackedPool.tryAdmit(packed.size.toLong()))
        synchronized(t.lock) {
            t.packed = packed
            t.bitmap = null
        }
        assertEquals(refs + 1, SolidTiles.refs(color))
        TileOps.releaseStore(s)
        assertEquals("the color is let go anyway", refs, SolidTiles.refs(color))
        assertNull(t.solidColor)
        assertNull(t.packed)
    }

    @Test
    fun aPinKeepsTheColorUntilItGoes() {
        val color = 0xFF4040F0.toInt()
        val refs = SolidTiles.refs(color)
        val s = TiledPixels.fromBitmap(uniform(256, 256, color))
        val t = s.tiles[0]!!
        t.pins++
        TileOps.releaseStore(s)
        assertEquals("pinned: still shared", refs + 1, SolidTiles.refs(color))
        assertNotNull(t.bitmap)
        TileOps.unpinTile(t)
        assertEquals("unpinned: let go", refs, SolidTiles.refs(color))
        assertNull(t.bitmap)
    }

    @Test
    fun theWorkerNeverPacksOrSwapsASolidTile() {
        val color = 0xFF205020.toInt()
        val s = TiledPixels.fromBitmap(uniform(256, 256, color))
        val t = s.tiles[0]!!
        TileOps.nextEpoch()
        assertFalse("never packed", TileWorker.packOut(t))
        assertFalse("never swapped", TileWorker.writeOut(t))
        assertFalse(TileWorker.busy(t))
        assertEquals(1, t.refs)
        TileOps.releaseStore(s)
    }

    @Test
    fun aTransparentOrTranslucentUniformTileIsNotShared() {
        val colors = SolidTiles.colors()
        val clear = TiledPixels.fromBitmap(uniform(256, 256, 0))
        assertNull("transparent: no tile", clear.tiles[0])
        val half = TiledPixels.fromBitmap(uniform(256, 256, 0x80FF0000.toInt()))
        assertNotNull(half.tiles[0])
        assertNull("translucent: a plain tile", half.tiles[0]!!.solidColor)
        assertEquals(colors, SolidTiles.colors())
        TileOps.releaseStore(clear)
        TileOps.releaseStore(half)
    }

    @Test
    fun piecesCutOffMainAskForTheColorOnlyOnMain() {
        val color = 0xFFAA7700.toInt()
        val refs = SolidTiles.refs(color)
        val src = uniform(512, 512, color)
        var pieces: TileOps.CutPieces? = null
        val th = Thread { pieces = TileOps.cutPieces(src, detectBlank = true) }
        th.start()
        th.join()
        assertEquals("the worker only noted the color", refs, SolidTiles.refs(color))
        val s = TileOps.adoptPieces(pieces!!)
        assertEquals(refs + 4, SolidTiles.refs(color))
        assertTrue(s.tiles.all { it!!.solidColor == color })
        TileOps.releaseStore(s)
        assertEquals(refs, SolidTiles.refs(color))
    }
}
