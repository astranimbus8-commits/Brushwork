package com.brushwork.paint.v110.lead

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.budget.LedgerCategory
import com.brushwork.paint.engine.tiles.PixelReads
import com.brushwork.paint.engine.tiles.SolidTiles
import com.brushwork.paint.engine.tiles.TILE_SIZE
import com.brushwork.paint.engine.tiles.Tile
import com.brushwork.paint.engine.tiles.TileOps
import com.brushwork.paint.engine.tiles.TileState
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.PixelFacade
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 F1 (design 2.1, 2.5, X1; lead, frozen): the reference [TiledPixels]. A cut and a
 * materialization give back every pixel (aligned, non-aligned and 1 px stores, with and without
 * blank detection); a cut makes null tiles of transparent ones and shared [SolidTiles] of one
 * opaque color; the reads ([TiledPixels.copyRegion], [PixelReads]) are exact across tile seams and
 * transparent outside the store; packing round trips; snapshots and edits keep their references.
 * X1: the compositor draws a tiled layer bit-equal to the flat one on an integer-translate canvas,
 * for the 17 blend modes, plain and masked, on a 256² canvas and on a 512² one of mixed tiles, at
 * API 34 and on the API 26-28 path. The X2 fallback (a scaled canvas) stays within one level.
 */
@RunWith(RobolectricTestRunner::class)
class TiledPixelsReferenceTest {
    private val made = mutableListOf<Bitmap>()

    @After
    fun tearDown() {
        F1Pixels.resetEngine()
        made.forEach { if (!it.isRecycled) it.recycle() }
    }

    private fun keep(b: Bitmap) = b.also { made += it }

    private fun tiles() = EngineConfig.budget.ledger.of(LedgerCategory.TILES)

    @Test
    fun aCutAndAMaterializationGiveBackEveryPixel() {
        for ((w, h) in listOf(512 to 512, 300 to 200, 256 to 256, 1 to 1, 511 to 257, 257 to 512)) {
            val before = tiles()
            val src = keep(F1Pixels.mixed(w, h, w * 31L + h))
            for (detect in listOf(true, false)) {
                val s = TiledPixels.fromBitmap(src, detectBlank = detect)
                assertEquals("$w x $h cols", (w + TILE_SIZE - 1) / TILE_SIZE, s.cols)
                assertEquals("$w x $h rows", (h + TILE_SIZE - 1) / TILE_SIZE, s.rows)
                F1Pixels.assertBitEqual("$w x $h, detect $detect", src, keep(s.materialize()))
                if (!detect) assertEquals("every tile kept", s.cols * s.rows, s.occupied())
                TileOps.releaseStore(s)
            }
            assertEquals("$w x $h: every tile let go", before, tiles())
        }
    }

    @Test
    fun aCutMakesNullAndSolidTiles() {
        val src = keep(F1Pixels.mixed(512, 512, 11L))
        val s = TiledPixels.fromBitmap(src)
        assertNotNull("noise", s.tileAt(0, 0))
        assertNull("transparent", s.tileAt(1, 0))
        assertEquals("one opaque color", 0xFF20A040.toInt(), s.tileAt(0, 1)!!.solidColor)
        assertNull("a translucent color is an ordinary tile", s.tileAt(1, 1)!!.solidColor)
        assertEquals(3, s.occupied())
        assertEquals("a solid tile holds no bytes of its own", 2L * TILE_SIZE * TILE_SIZE * 4, s.residentBytes())
        assertEquals(3L * TILE_SIZE * TILE_SIZE * 4, s.logicalBytes())
        assertTrue(SolidTiles.refs(0xFF20A040.toInt()) >= 1)
        assertFalse(s.isBlank())
        assertFalse(s.isEmpty())
        assertTrue("the transparent tile", s.isEmptyIn(Rect(300, 10, 500, 200)))
        assertFalse(s.isEmptyIn(Rect(250, 10, 500, 200)))
        assertTrue("outside the store", s.isEmptyIn(Rect(600, 600, 700, 700)))
        val bounds = Rect()
        assertTrue(s.occupiedBounds(bounds))
        assertEquals(Rect(0, 0, 512, 512), bounds)
        TileOps.releaseStore(s)

        val edge = TiledPixels.fromBitmap(keep(F1Pixels.mixed(300, 200, 12L)))
        assertEquals(2, edge.cols)
        assertEquals(1, edge.rows)
        assertNull(edge.tileAt(1, 0))
        assertTrue(edge.occupiedBounds(bounds))
        assertEquals(Rect(0, 0, 256, 200), bounds)
        TileOps.releaseStore(edge)

        val empty = TiledPixels.fromBitmap(keep(BitmapUtils.createLayerBitmap(300, 200)))
        assertEquals(0, empty.occupied())
        assertTrue(empty.isEmpty())
        assertFalse(empty.occupiedBounds(bounds))
    }

    @Test
    fun readsAreExactAcrossSeamsAndTransparentOutside() {
        val src = keep(F1Pixels.mixed(512, 512, 13L))
        val s = TiledPixels.fromBitmap(src)
        val l = Layer(1L, "L", s)
        PixelFacade.strict = true
        val inside = Rect(200, 150, 330, 290)
        val expected = keep(Bitmap.createBitmap(src, inside.left, inside.top, inside.width(), inside.height()))
        F1Pixels.assertBitEqual("PixelReads.region", expected, keep(PixelReads.region(l, inside)))
        val dst = keep(F1Pixels.noise(200, 200, 14L))
        s.copyRegion(inside, dst, 10, 20)
        F1Pixels.assertBitEqual("copyRegion", expected, keep(Bitmap.createBitmap(dst, 10, 20, inside.width(), inside.height())))
        val outside = keep(PixelReads.region(l, Rect(400, 400, 600, 600)))
        for (y in 0 until 200) for (x in 0 until 200) {
            val want = if (x < 112 && y < 112) src.getPixel(400 + x, 400 + y) else 0
            if (outside.getPixel(x, y) != want) throw AssertionError("region outside at ($x, $y)")
        }
        val r = java.util.Random(15L)
        repeat(500) {
            val x = r.nextInt(512)
            val y = r.nextInt(512)
            assertEquals("sample ($x, $y)", src.getPixel(x, y), PixelReads.sample(l, x, y))
        }
        assertEquals(0, PixelReads.sample(l, -1, 0))
        assertEquals(0, PixelReads.sample(l, 0, 512))
        val alpha = ByteArray(inside.width() * inside.height())
        PixelReads.alphaRegion(l, inside, alpha)
        for (i in alpha.indices) {
            val want = expected.getPixel(i % inside.width(), i / inside.width()) ushr 24
            if (alpha[i].toInt() and 0xFF != want) throw AssertionError("alpha $i")
        }
        assertEquals("no read materialized", TiledPixels::class, l.pixels::class)
        PixelFacade.strict = false
        l.recycleBitmaps()
    }

    @Test
    fun packingRoundTrips() {
        val n = keep(F1Pixels.noise(TILE_SIZE, TILE_SIZE, 16L))
        F1Pixels.assertBitEqual("noise", n, keep(TileOps.inflate(TileOps.pack(n), TILE_SIZE, TILE_SIZE)))
        val edge = keep(F1Pixels.mixed(44, 200, 17L))
        F1Pixels.assertBitEqual("an edge tile", edge, keep(TileOps.inflate(TileOps.pack(edge), 44, 200)))
        val flat = keep(BitmapUtils.createLayerBitmap(TILE_SIZE, TILE_SIZE).apply { eraseColor(0xFF336699.toInt()) })
        val packed = TileOps.pack(flat)
        assertTrue("one color packs small (${packed.size})", packed.size < 4096)
        F1Pixels.assertBitEqual("one color", flat, keep(TileOps.inflate(packed, TILE_SIZE, TILE_SIZE)))

        val t = Tile.of(keep(n.copy(Bitmap.Config.ARGB_8888, true)))
        t.refs = 1
        val bytes = TileOps.pack(t.bitmap!!)
        t.bitmap!!.recycle()
        EngineConfig.budget.ledger.sub(LedgerCategory.TILES, t.bytes.toLong())
        t.bitmap = null
        t.packed = bytes
        assertEquals(TileState.PACKED, t.state)
        val read = keep(t.bitmapForRead())
        F1Pixels.assertBitEqual("bitmapForRead", n, read)
        assertEquals("a read leaves it packed", TileState.PACKED, t.state)
        TileOps.makeResident(t)
        assertEquals(TileState.RESIDENT, t.state)
        F1Pixels.assertBitEqual("resident", n, t.bitmap!!)
        TileOps.release(t)
        assertNull(t.bitmap)
    }

    @Test
    fun snapshotsAndEditsKeepTheirReferences() {
        val before = tiles()
        val src = keep(F1Pixels.mixed(512, 512, 18L))
        val s = TiledPixels.fromBitmap(src)
        val a = s.tileAt(0, 0)!!
        assertEquals(1, a.refs)
        val snap = s.snapshot()
        assertEquals(2, a.refs)
        assertSame(a, snap.tileAt(0, 0))
        val fresh = Tile.solid(0xFF000000.toInt(), TILE_SIZE, TILE_SIZE)
        val edited = s.withTiles(mapOf(0 to fresh, 3 to null))
        assertEquals("the edit holds the others", 3, s.tileAt(0, 1)!!.refs)
        assertEquals(1, fresh.refs)
        assertEquals(2, a.refs)
        assertNull(edited.tileAt(1, 1))
        val replaced = s.replaceTiles(mapOf(0 to null))
        assertSame(a, replaced[0])
        assertNull(s.tileAt(0, 0))
        TileOps.release(a)
        assertEquals(1, a.refs)
        assertSame("the snapshot still reads it", a, snap.tileAt(0, 0))
        F1Pixels.assertBitEqual("the snapshot keeps the pixels", src, keep(snap.materialize()))
        TileOps.releaseStore(s)
        TileOps.releaseStore(snap)
        TileOps.releaseStore(edited)
        assertNull(a.bitmap)
        assertEquals("every tile let go", before, tiles())
    }

    // ---------------------------------------------------------------- X1

    private fun doc(w: Int, h: Int, mode: LayerBlendMode, masked: Boolean, mixed: Boolean): Document {
        val d = Document("x1", "x1", w, h)
        val bottom = if (mixed) F1Pixels.mixed(w, h, 21L) else F1Pixels.noise(w, h, 21L, opaque = true)
        d.layers += Layer(d.newLayerId(), "Bottom", keep(bottom))
        val top = if (mixed) F1Pixels.mixed(w, h, 22L).also { rotateTiles(it) } else F1Pixels.noise(w, h, 22L)
        d.layers += Layer(d.newLayerId(), "Top", keep(top)).apply {
            blendMode = mode
            opacity = 0.8f
            if (masked) mask = keep(if (mixed) F1Pixels.mixedMask(w, h, 23L) else F1Pixels.grayNoise(w, h, 23L))
        }
        d.activeLayerIndex = 1
        return d
    }

    /** Mirrors [b] so its tile kinds fall on other tiles than the bottom layer's. */
    private fun rotateTiles(b: Bitmap) {
        val copy = b.copy(Bitmap.Config.ARGB_8888, false)
        val c = Canvas(b)
        c.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
        c.save()
        c.scale(-1f, 1f, b.width / 2f, 0f)
        c.drawBitmap(copy, 0f, 0f, null)
        c.restore()
        copy.recycle()
    }

    /** Every layer's content and mask cut into tiles (the same pixels in another store). */
    private fun tile(d: Document) {
        for (l in d.layers) {
            val flat = l.bitmap
            val mask = l.mask
            l.pixels = TiledPixels.fromBitmap(flat)
            if (mask != null) l.maskPixels = TiledPixels.fromBitmap(mask)
        }
    }

    /** [d] drawn onto a canvas clipped to [clip] (target px), translated by ([dx], [dy]) and scaled by [scale]. */
    private fun render(d: Document, dx: Float = 0f, dy: Float = 0f, scale: Float = 1f, clip: Rect? = null): Bitmap {
        val out = keep(BitmapUtils.createLayerBitmap(d.width, d.height))
        val c = Canvas(out)
        if (clip != null) c.clipRect(clip)
        c.translate(dx, dy)
        c.scale(scale, scale)
        Compositor(d) { null }.drawDocument(c, null, useOverrides = false, target = null)
        return out
    }

    private fun x1(sdk: Int, w: Int, h: Int, mixed: Boolean) {
        assertEquals("the 17 blend modes", 17, LayerBlendMode.entries.size)
        F1Pixels.atSdk(sdk) {
            for (mode in LayerBlendMode.entries) for (masked in listOf(false, true)) {
                val d = doc(w, h, mode, masked, mixed)
                val flat = F1Pixels.flatten(d)
                val moved = render(d, 37f, -21f)
                tile(d)
                PixelFacade.strict = true
                F1Pixels.assertBitEqual("API $sdk, ${w}x$h, $mode, masked $masked", flat, keep(F1Pixels.flatten(d)))
                F1Pixels.assertBitEqual("API $sdk, ${w}x$h, $mode, masked $masked, translated", moved, render(d, 37f, -21f))
                PixelFacade.strict = false
                keep(flat)
                d.layers.forEach { it.recycleBitmaps() }
            }
        }
    }

    @Test
    fun x1On256SquareFor17ModesPlainAndMasked() = x1(34, 256, 256, mixed = false)

    @Test
    fun x1On512MixedTilesFor17ModesPlainAndMasked() = x1(34, 512, 512, mixed = true)

    @Test
    fun x1OnTheApi28PathFor17ModesPlainAndMasked() {
        x1(28, 256, 256, mixed = false)
        x1(28, 512, 512, mixed = true)
    }

    @Test
    fun x1OnANonAlignedCanvas() {
        for (mode in listOf(LayerBlendMode.NORMAL, LayerBlendMode.MULTIPLY, LayerBlendMode.SCREEN, LayerBlendMode.DIFFERENCE)) {
            for (masked in listOf(false, true)) {
                val d = doc(300, 200, mode, masked, mixed = true)
                val flat = keep(F1Pixels.flatten(d))
                tile(d)
                F1Pixels.assertBitEqual("300x200, $mode, masked $masked", flat, keep(F1Pixels.flatten(d)))
                d.layers.forEach { it.recycleBitmaps() }
            }
        }
    }

    @Test
    fun x2FallbackOnAScaledCanvasStaysClose() {
        for (masked in listOf(false, true)) {
            val d = doc(512, 512, LayerBlendMode.NORMAL, masked, mixed = true)
            val flat = render(d, 3f, 5f, 0.5f, Rect(41, 37, 203, 230))
            tile(d)
            PixelFacade.strict = true
            val tiled = render(d, 3f, 5f, 0.5f, Rect(41, 37, 203, 230))
            PixelFacade.strict = false
            val diff = F1Pixels.maxDiff(flat, tiled)
            assertTrue("masked $masked: the band draw differs by $diff", diff <= 1)
            d.layers.forEach { it.recycleBitmaps() }
        }
    }
}
