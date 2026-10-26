package com.brushwork.paint.v110.a8

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.tiles.FlatPixels
import com.brushwork.paint.engine.tiles.PackedPool
import com.brushwork.paint.engine.tiles.TILE_SIZE
import com.brushwork.paint.engine.tiles.TileOps
import com.brushwork.paint.engine.tiles.TileState
import com.brushwork.paint.engine.tiles.TileWorker
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.testing.RandomStacks
import com.brushwork.paint.testing.TiledGoldenKit
import com.brushwork.paint.v110.lead.F1Pixels
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.min

/**
 * v1.10 A8 step 1 (design 2.0, 2.16, J8): the tuned [TileOps] bodies equal F1's reference ones.
 * The reference cut, pack and inflate are kept here as F1 wrote them (a new canvas per tile, a new
 * Deflater and Inflater per call): on the [RandomStacks] documents a tuned cut gives the same
 * tiles (null where transparent, the same solid colors, the same pixels), a tuned pack the same
 * bytes, and each side inflates the other's; a tiled document renders bit-equal to the flat one.
 * The cut's pieces cut on another thread equal the cut on main. The worker's pack-out and read-in
 * keep every pixel and count the packed pool in and out.
 */
@RunWith(RobolectricTestRunner::class)
class TileOpsParityTest {
    private val made = mutableListOf<Bitmap>()

    private val baseline = A8Kit.Baseline()

    @After
    fun tearDown() {
        baseline.assertBack(emptyList())
        F1Pixels.resetEngine()
        made.forEach { if (!it.isRecycled) it.recycle() }
    }

    private fun keep(b: Bitmap) = b.also { made += it }

    // ------------------------------------------------------------ F1's reference bodies

    private val copyPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }

    /** F1's slot kinds: null = transparent, a color = uniform opaque, else the copied pixels. */
    private class RefPiece(val color: Int?, val pixels: IntArray?)

    private fun refCut(b: Bitmap): List<RefPiece?> {
        val cols = (b.width + TILE_SIZE - 1) / TILE_SIZE
        val rows = (b.height + TILE_SIZE - 1) / TILE_SIZE
        val px = IntArray(TILE_SIZE * TILE_SIZE)
        val out = ArrayList<RefPiece?>()
        for (row in 0 until rows) for (col in 0 until cols) {
            val x = col * TILE_SIZE
            val y = row * TILE_SIZE
            val tw = min(TILE_SIZE, b.width - x)
            val th = min(TILE_SIZE, b.height - y)
            b.getPixels(px, 0, tw, x, y, tw, th)
            val n = tw * th
            val first = px[0]
            var visible = false
            var uniform = true
            for (i in 0 until n) {
                if (px[i] ushr 24 != 0) visible = true
                if (px[i] != first) uniform = false
            }
            out += when {
                !visible -> null
                uniform && first ushr 24 == 0xFF -> RefPiece(first, null)
                else -> {
                    val copy = BitmapUtils.createLayerBitmap(tw, th)
                    copy.density = Bitmap.DENSITY_NONE
                    Canvas(copy).drawBitmap(b, -x.toFloat(), -y.toFloat(), copyPaint)
                    RefPiece(null, F1Pixels.pixels(copy)).also { copy.recycle() }
                }
            }
        }
        return out
    }

    private fun refPack(b: Bitmap): ByteArray {
        val raw = ByteBuffer.allocate(b.byteCount)
        b.copyPixelsToBuffer(raw)
        val def = Deflater(1, true)
        try {
            def.setInput(raw.array())
            def.finish()
            val out = ByteArrayOutputStream(b.byteCount / 4 + 64)
            val buf = ByteArray(64 * 1024)
            while (!def.finished()) out.write(buf, 0, def.deflate(buf))
            return out.toByteArray()
        } finally {
            def.end()
        }
    }

    private fun refInflate(packed: ByteArray, w: Int, h: Int): Bitmap {
        val raw = ByteArray(w * h * 4)
        val inf = Inflater(true)
        try {
            inf.setInput(packed.copyOf(packed.size + 1))
            var n = 0
            while (n < raw.size) {
                val k = inf.inflate(raw, n, raw.size - n)
                if (k == 0 && (inf.finished() || inf.needsInput() || inf.needsDictionary())) break
                n += k
            }
            assertEquals("reference inflate", raw.size, n)
        } finally {
            inf.end()
        }
        return BitmapUtils.createLayerBitmap(w, h).apply { copyPixelsFromBuffer(ByteBuffer.wrap(raw)) }
    }

    /** Every flat content and mask bitmap of the [RandomStacks] document of [seed]. */
    private fun bitmapsOf(seed: Long): List<Bitmap> {
        val doc = RandomStacks.document(seed, RandomStacks.Spec(width = 300 + (seed % 7).toInt() * 30, height = 260 + (seed % 5).toInt() * 50))
        val out = ArrayList<Bitmap>()
        for (l in doc.layers) {
            if (l.isFolder) continue
            (l.pixels as? FlatPixels)?.let { out += keep(it.bitmap) }
            (l.maskPixels as? FlatPixels)?.let { out += keep(it.bitmap) }
        }
        return out
    }

    private fun assertSameTiles(message: String, ref: List<RefPiece?>, s: TiledPixels) {
        assertEquals("$message: slots", ref.size, s.cols * s.rows)
        for (i in ref.indices) {
            val r = ref[i]
            val t = s.tiles[i]
            if (r == null) {
                assertNull("$message: slot $i is transparent", t)
                continue
            }
            assertNotNull("$message: slot $i", t)
            assertEquals("$message: slot $i color", r.color, t!!.solidColor)
            if (r.pixels != null) {
                val b = t.bitmapForRead()
                assertEquals("$message: slot $i size", t.w * t.h, r.pixels.size)
                assertArrayEquals("$message: slot $i pixels", r.pixels, F1Pixels.pixels(b))
                assertEquals("$message: slot $i density", Bitmap.DENSITY_NONE, b.density)
            }
        }
    }

    // ------------------------------------------------------------ tests

    @Test
    fun aTunedCutGivesTheReferenceTiles() {
        for (seed in 1L..12L) {
            for ((k, b) in bitmapsOf(seed).withIndex()) {
                val ref = refCut(b)
                val s = TiledPixels.fromBitmap(b)
                assertSameTiles("seed $seed bitmap $k", ref, s)
                F1Pixels.assertBitEqual("seed $seed bitmap $k materialized", b, keep(s.materialize()))
                TileOps.releaseStore(s)
            }
        }
    }

    @Test
    fun aCutWithoutBlankDetectionCopiesEveryTile() {
        val b = keep(F1Pixels.mixed(511, 300, 5L))
        val s = TiledPixels.fromBitmap(b, detectBlank = false)
        assertEquals(s.cols * s.rows, s.occupied())
        for (t in s.tiles) assertNull("no solid tile without detection", t!!.solidColor)
        F1Pixels.assertBitEqual("every pixel", b, keep(s.materialize()))
        TileOps.releaseStore(s)
    }

    @Test
    fun aTunedPackGivesTheReferenceBytesAndBothInflate() {
        for (seed in 1L..6L) {
            for ((k, b) in bitmapsOf(seed).withIndex()) {
                val s = TiledPixels.fromBitmap(b, detectBlank = false)
                for (t in s.tiles) {
                    val tb = t!!.bitmapForRead()
                    val tuned = TileOps.pack(tb)
                    val ref = refPack(tb)
                    assertArrayEquals("seed $seed bitmap $k: the same Deflate-1 bytes", ref, tuned)
                    F1Pixels.assertBitEqual("tuned inflate of the reference bytes", tb, keep(TileOps.inflate(ref, t.w, t.h)))
                    F1Pixels.assertBitEqual("reference inflate of the tuned bytes", tb, keep(refInflate(tuned, t.w, t.h)))
                }
                TileOps.releaseStore(s)
            }
        }
    }

    @Test
    fun packAndInflateRoundTripLargerThanATileAndOnAnotherThread() {
        val big = keep(F1Pixels.noise(300, 280, 9L))
        F1Pixels.assertBitEqual("larger than a tile", big, keep(TileOps.inflate(TileOps.pack(big), 300, 280)))
        val small = keep(F1Pixels.mixed(256, 256, 4L))
        var packed: ByteArray? = null
        val t = Thread { packed = TileOps.pack(small) }
        t.start()
        t.join()
        assertArrayEquals("a pack off main equals one on main", TileOps.pack(small), packed)
        val inflated = keep(TileOps.inflate(packed!!, 256, 256))
        assertEquals(Bitmap.DENSITY_NONE, inflated.density)
        F1Pixels.assertBitEqual("round trip", small, inflated)
    }

    @Test
    fun piecesCutOnAnotherThreadEqualTheCutOnMain() {
        val b = keep(F1Pixels.mixed(512, 512, 21L))
        var pieces: TileOps.CutPieces? = null
        val t = Thread { pieces = TileOps.cutPieces(b, detectBlank = true) }
        t.start()
        t.join()
        val before = A8Kit.tiles()
        val s = TileOps.adoptPieces(pieces!!)
        assertTrue("adopted tiles are counted on main", A8Kit.tiles() > before)
        assertSameTiles("worker cut", refCut(b), s)
        TileOps.releaseStore(s)
        assertEquals("every tile let go", before, A8Kit.tiles())
    }

    @Test
    fun aCancelledCutLeavesNothing() {
        val b = keep(F1Pixels.mixed(512, 512, 22L))
        var n = 0
        assertNull(TileOps.cutPieces(b, detectBlank = true) { ++n > 2 })
    }

    @Test
    fun aTiledDocumentRendersLikeTheFlatOne() {
        for (seed in 101L..108L) {
            val v = TiledGoldenKit.of(seed)
            try {
                assertTrue("seed $seed has tiled layers", TiledGoldenKit.count(v.tiled, TiledGoldenKit.Stores.TILED) > 0)
                TiledGoldenKit.assertSameRender("seed $seed", v)
            } finally {
                v.recycle()
            }
        }
    }

    @Test
    fun theWorkerPacksOutAndReadsBackEveryPixel() {
        val b = keep(F1Pixels.mixed(512, 512, 31L))
        val s = TiledPixels.fromBitmap(b, detectBlank = false)
        val pool = PackedPool.heldBytes
        val tilesBefore = A8Kit.tiles()
        TileOps.nextEpoch()
        val sent = s.tiles.count { TileWorker.packOut(it!!) }
        assertEquals("every plain tile goes", s.tiles.size, sent)
        A8Kit.drain()
        for (t in s.tiles) assertEquals(TileState.PACKED, t!!.state)
        assertTrue("the pool holds the packed bytes", PackedPool.heldBytes > pool)
        assertTrue("the resident bytes left the ledger", A8Kit.tiles() < tilesBefore)
        F1Pixels.assertBitEqual("a packed store reads every pixel", b, keep(s.materialize()))
        var back = 0
        for (t in s.tiles) TileWorker.readIn(t!!, TileWorker.Priority.READ_URGENT) { if (it) back++ }
        A8Kit.drain()
        assertEquals(s.tiles.size, back)
        for (t in s.tiles) assertEquals(TileState.RESIDENT, t!!.state)
        assertEquals("the pool let go", pool, PackedPool.heldBytes)
        assertEquals("the ledger counts them again", tilesBefore, A8Kit.tiles())
        F1Pixels.assertBitEqual("read back", b, keep(s.materialize()))
        TileOps.releaseStore(s)
    }

    @Test
    fun aTileDrawnInThisEpochStaysResident() {
        val b = keep(F1Pixels.noise(256, 256, 41L))
        val s = TiledPixels.fromBitmap(b)
        val t = s.tiles[0]!!
        TileOps.nextEpoch()
        var went: Boolean? = null
        assertTrue(TileWorker.packOut(t) { went = it })
        // A frame draws it before the commit: rule (2) keeps it.
        val target = keep(BitmapUtils.createLayerBitmap(256, 256))
        s.drawTo(Canvas(target), null, android.graphics.Rect(0, 0, 256, 256))
        A8Kit.drain()
        assertEquals(false, went)
        assertEquals(TileState.RESIDENT, t.state)
        TileOps.nextEpoch()
        assertTrue(TileWorker.packOut(t) { went = it })
        A8Kit.drain()
        assertEquals(true, went)
        assertEquals(TileState.PACKED, t.state)
        TileOps.releaseStore(s)
    }

    @Test
    fun aSecondJobOfABusyTileIsRefused() {
        val b = keep(F1Pixels.noise(256, 256, 42L))
        val s = TiledPixels.fromBitmap(b)
        val t = s.tiles[0]!!
        TileOps.nextEpoch()
        assertTrue(TileWorker.packOut(t))
        assertTrue(TileWorker.busy(t))
        assertFalse("one job per tile", TileWorker.packOut(t))
        A8Kit.drain()
        assertFalse(TileWorker.busy(t))
        assertEquals(1, t.refs)
        TileOps.releaseStore(s)
    }

    @Test
    fun theBudgetDecidesThePoolCap() {
        assertEquals(minOf(64L shl 20, EngineConfig.budget.javaMax / 8), PackedPool.cap())
    }
}
