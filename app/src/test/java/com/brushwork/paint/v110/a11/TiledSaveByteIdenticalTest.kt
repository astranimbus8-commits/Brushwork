package com.brushwork.paint.v110.a11

import android.graphics.Bitmap
import android.graphics.Rect
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.budget.LedgerCategory
import com.brushwork.paint.engine.tiles.BlankPixels
import com.brushwork.paint.engine.tiles.FlatPixels
import com.brushwork.paint.engine.tiles.PixelStore
import com.brushwork.paint.engine.tiles.TileOps
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.storage.LayerEntries
import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.testing.EngineRule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * v1.10 (A11, X6, design 2.9): a layer whose pixels are equal writes the same pixel file bytes
 * whether it is saved from a full bitmap (v1.9's banded write), from tiles or as a blank store,
 * at each engine level. On a 300 x 520 canvas (edge tiles of 44 and 8 px): a random layer, a
 * sparse one, a solid opaque one (`Tile.solid` tiles), a uniform half-transparent one, a masked one
 * and a blank one, with bands of 256 rows and of 7 rows (bands that cut tiles). And a project saved
 * by v1.9's path, opened at the level (stores as that level builds them) and saved again with
 * every layer changed writes every file again with the same bytes; the tiles the writes held are
 * all let go of afterwards.
 */
abstract class TiledSaveByteIdenticalBase(private val level: EngineLevel) {
    @get:Rule
    val engine = EngineRule(level)

    private val w = 300
    private val h = 520
    private val made = ArrayList<Layer>()

    @After
    fun tearDown() {
        LayerEntries.bandBytes = LayerEntries.BAND_BYTES
        for (l in made) l.recycleBitmaps()
    }

    /** The class KDoc's layers, FLAT (bitmaps), as a new document. */
    private fun flatDocument(id: String): Document {
        val doc = Document(id, id, w, h)
        fun layer(n: Long, paint: (Bitmap) -> Unit): Layer =
            Layer(n, "L$n", BitmapUtils.createLayerBitmap(w, h).also(paint)).also { doc.layers += it; made += it }
        layer(1) { A11Kit.noise(it, Rect(0, 0, w, h), 11) }
        layer(2) {
            A11Kit.noise(it, Rect(260, 300, 270, 310), 12)
            A11Kit.fill(it, Rect(w - 1, h - 1, w, h), 0xFF00FF00.toInt())
        }
        layer(3) { A11Kit.fill(it, Rect(0, 0, w, h), 0xFFCC2200.toInt()) }
        layer(4) { A11Kit.fill(it, Rect(0, 0, w, h), 0x80336699.toInt()) }
        layer(5) { A11Kit.noise(it, Rect(0, 0, w / 2, h), 15) }.mask =
            BitmapUtils.createMaskBitmap(w, h).also { A11Kit.fill(it, Rect(10, 10, 100, 400), 0xFF000000.toInt()) }
        layer(6) {}
        doc.activeLayerIndex = 4
        return doc
    }

    /** The same layers as stores: tiles cut from each bitmap (the blank one a [BlankPixels] store). */
    private fun storeDocument(flat: Document): Document {
        val doc = Document(flat.id, flat.name, w, h)
        for (l in flat.layers) {
            val b = (l.pixels as FlatPixels).bitmap
            val store: PixelStore = if (TileOps.isTransparent(b)) BlankPixels(w, h) else TiledPixels.fromBitmap(b)
            doc.layers += Layer(l.id, l.name, store).also { n ->
                l.maskPixels?.let { m -> n.maskPixels = TiledPixels.fromBitmap((m as FlatPixels).bitmap) }
                made += n
            }
        }
        return doc
    }

    /** Every layer and mask file a Writer writes for [doc] (names to bytes). */
    private fun write(doc: Document, bandBytes: Int): Map<String, ByteArray> {
        val dir = File(A11Kit.context.cacheDir, "x6-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            LayerEntries.bandBytes = bandBytes
            val writer = LayerEntries.Writer(dir, doc, 3L, emptySet(), null)
            runBlocking { for (l in doc.layers) assertTrue(writer.entryFor(l) != null) }
            return dir.listFiles()!!.filter { it.isFile }.associate { it.name to it.readBytes() }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun tilesAndBlankStoresWriteTheFlatBytes() {
        val flat = flatDocument("x6")
        val stores = storeDocument(flat)
        val ledger = EngineConfig.budget.ledger
        for (bandBytes in listOf(LayerEntries.BAND_BYTES, w * 4 * 7 + 5)) {
            val expected = write(flat, bandBytes)
            assertEquals("six layers and a mask", 7, expected.size)
            val tilesBefore = ledger.of(LedgerCategory.TILES)
            val actual = write(stores, bandBytes)
            assertEquals("$level, bands of $bandBytes bytes: the same files", expected.keys, actual.keys)
            for ((name, bytes) in expected) assertArrayEquals("$level, bands of $bandBytes bytes: $name", bytes, actual.getValue(name))
            assertEquals("the tiles the writes held are let go of", tilesBefore, ledger.of(LedgerCategory.TILES))
            for (l in stores.layers) {
                assertEquals("${l.name}: unpinned", 0, l.pins)
                val p = l.pixels
                if (p is TiledPixels) for (r in 0 until p.rows) for (c in 0 until p.cols) p.tileAt(c, r)?.let { assertEquals("${l.name} tile $c,$r: one holder again", 1, it.refs) }
            }
        }
    }

    @Test
    fun aProjectOpenedAtTheLevelSavesItsFilesAgainByteForByte() {
        val repo = A11Kit.freshRepo()
        val flat = flatDocument("x6open")
        runBlocking { repo.save(flat, null) }
        val dir = A11Kit.dirOf("x6open")
        val first = ProjectFormat.read(dir)
        val before = first.layers.associate { it.id to File(dir, it.contentFileName).readBytes() } +
            first.layers.filter { it.hasMask }.associate { -it.id to File(dir, it.maskFileName).readBytes() }
        val loaded = runBlocking { A11Kit.repo().load("x6open") }
        try {
            if (level != EngineLevel.CLASSIC) assertTrue("tiled at $level", loaded.layers[0].pixels is TiledPixels)
            assertTrue("blank", loaded.layers[5].pixels is BlankPixels)
            for (l in loaded.layers) l.markChanged()
            runBlocking { A11Kit.repo().save(loaded, null) }
            val second = ProjectFormat.read(dir)
            assertEquals(first.revision + 1, second.revision)
            for (e in second.layers) {
                assertTrue("${e.contentFileName} is a new file", e.contentFileName !in first.layers.map { it.contentFileName })
                assertArrayEquals("$level: layer ${e.id}", before.getValue(e.id), File(dir, e.contentFileName).readBytes())
                if (e.hasMask) assertArrayEquals("$level: mask ${e.id}", before.getValue(-e.id), File(dir, e.maskFileName).readBytes())
            }
        } finally {
            A11Kit.dispose(loaded)
        }
    }
}

@RunWith(RobolectricTestRunner::class)
class TiledSaveByteIdenticalTest : TiledSaveByteIdenticalBase(EngineLevel.TILES)

@RunWith(RobolectricTestRunner::class)
class TiledSaveByteIdenticalClassicTest : TiledSaveByteIdenticalBase(EngineLevel.CLASSIC)

@RunWith(RobolectricTestRunner::class)
class TiledSaveByteIdenticalFullTest : TiledSaveByteIdenticalBase(EngineLevel.FULL)
