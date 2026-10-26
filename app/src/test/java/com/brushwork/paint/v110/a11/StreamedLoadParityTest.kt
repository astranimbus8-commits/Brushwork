package com.brushwork.paint.v110.a11

import android.graphics.Bitmap
import android.graphics.Rect
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.budget.LedgerCategory
import com.brushwork.paint.engine.tiles.BlankPixels
import com.brushwork.paint.engine.tiles.FlatPixels
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.storage.CorruptProjectException
import com.brushwork.paint.testing.EngineRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * v1.10 (A11, design 2.9): the streamed load gives every layer and mask exactly the bytes that
 * were saved, at each engine level, on a 300 x 520 canvas (edge tiles of 44 and 8 px): a random
 * layer, a sparse one (two tiles), a solid opaque one (every tile `Tile.solid`), a uniform
 * half-transparent one (own tiles, never solid), a masked one and blank ones. A blank inactive
 * layer is a [BlankPixels] store, the active layer is FLAT, the others FLAT at Classic and tiled
 * at TILES and FULL; the tile ledger is back where it was once the loaded document is released,
 * and a damaged file releases every store the open had built.
 */
abstract class StreamedLoadParityBase(private val level: EngineLevel) {
    @get:Rule
    val engine = EngineRule(level)

    private val w = 300
    private val h = 520

    private fun layer(doc: Document, id: Long, paint: (Bitmap) -> Unit): Layer {
        val b = BitmapUtils.createLayerBitmap(w, h).also(paint)
        return Layer(id, "L$id", b).also { doc.layers += it }
    }

    /** The document of the class KDoc, saved as [id]; returns it with the expected bytes of each layer (and of layer 5's mask last). */
    private fun saved(id: String): Pair<Document, List<ByteArray>> {
        val repo = A11Kit.freshRepo()
        val doc = Document(id, id, w, h)
        layer(doc, 1) {}
        layer(doc, 2) { A11Kit.noise(it, Rect(0, 0, w, h), 2) }
        layer(doc, 3) {
            A11Kit.noise(it, Rect(260, 300, 270, 310), 3)
            A11Kit.fill(it, Rect(w - 1, h - 1, w, h), 0xFF00FF00.toInt())
        }
        layer(doc, 4) { A11Kit.fill(it, Rect(0, 0, w, h), RED) }
        layer(doc, 5) { A11Kit.fill(it, Rect(0, 0, w, h), 0x80336699.toInt()) }
        val masked = layer(doc, 6) { A11Kit.noise(it, Rect(0, 0, w / 2, h), 6) }
        masked.mask = BitmapUtils.createMaskBitmap(w, h).also { A11Kit.fill(it, Rect(10, 10, 100, 400), 0xFF000000.toInt()) }
        layer(doc, 7) {}
        doc.activeLayerIndex = 6
        val expected = doc.layers.map { A11Kit.raw(it.bitmap) } + A11Kit.raw(masked.mask!!)
        runBlocking { repo.save(doc, null) }
        return doc to expected
    }

    @Test
    fun everyLayerLoadsWithItsSavedBytes() {
        val (doc, expected) = saved("parity")
        val ledger = EngineConfig.budget.ledger
        val tilesBefore = ledger.of(LedgerCategory.TILES)
        val loaded = runBlocking { A11Kit.repo().load("parity") }
        try {
            for (i in 0 until 7) assertArrayEquals("layer $i", expected[i], A11Kit.bytesOf(loaded.layers[i].pixels, w, h))
            assertArrayEquals("mask", expected[7], A11Kit.bytesOf(loaded.layers[5].maskPixels!!, w, h))
            assertTrue("a blank inactive layer is BLANK", loaded.layers[0].pixels is BlankPixels)
            assertTrue("the active layer is FLAT", loaded.layers[6].pixels is FlatPixels)
            val painted = listOf(1, 2, 3, 4, 5)
            if (level == EngineLevel.CLASSIC) {
                for (i in painted) assertTrue("Classic: layer $i FLAT", loaded.layers[i].pixels is FlatPixels)
                assertTrue(loaded.layers[5].maskPixels is FlatPixels)
            } else {
                for (i in painted) assertTrue("$level: layer $i tiled", loaded.layers[i].pixels is TiledPixels)
                val sparse = loaded.layers[2].pixels as TiledPixels
                assertEquals("sparse: two tiles", 2, sparse.occupied())
                val solid = loaded.layers[3].pixels as TiledPixels
                for (r in 0 until solid.rows) for (c in 0 until solid.cols) assertEquals("solid tile $c,$r", RED, solid.tileAt(c, r)?.solidColor)
                val translucent = loaded.layers[4].pixels as TiledPixels
                for (r in 0 until translucent.rows) for (c in 0 until translucent.cols) assertEquals(null, translucent.tileAt(c, r)?.solidColor)
                val mask = loaded.layers[5].maskPixels as TiledPixels
                assertEquals("a white mask tile is solid white", WHITE, mask.tileAt(1, 2)?.solidColor)
            }
        } finally {
            A11Kit.dispose(loaded)
            A11Kit.dispose(doc)
        }
        assertEquals("the tile ledger is back", tilesBefore, ledger.of(LedgerCategory.TILES))
    }

    @Test
    fun aDamagedFileReleasesEveryStoreBuilt() {
        val (doc, _) = saved("damaged")
        A11Kit.dispose(doc)
        val dir = A11Kit.dirOf("damaged")
        // The last painted layer's file loses its end: every earlier layer was built when it fails.
        val last = dir.listFiles()!!.filter { it.name.startsWith("layer_6_") }.single()
        last.writeBytes(last.readBytes().copyOf((last.length() / 2).toInt()))
        val ledger = EngineConfig.budget.ledger
        val tilesBefore = ledger.of(LedgerCategory.TILES)
        try {
            runBlocking { A11Kit.repo().load("damaged") }
            fail("a truncated layer file opens")
        } catch (e: CorruptProjectException) {
            assertTrue(e.message, e.message!!.contains("is damaged or missing"))
        }
        assertEquals("every store built is released", tilesBefore, ledger.of(LedgerCategory.TILES))
        assertTrue(File(dir, "project.json").isFile)
    }

    private companion object {
        const val RED = 0xFFCC2200.toInt()
        const val WHITE = -1
    }
}

@RunWith(RobolectricTestRunner::class)
class StreamedLoadParityClassicTest : StreamedLoadParityBase(EngineLevel.CLASSIC)

@RunWith(RobolectricTestRunner::class)
class StreamedLoadParityTilesTest : StreamedLoadParityBase(EngineLevel.TILES)

@RunWith(RobolectricTestRunner::class)
class StreamedLoadParityFullTest : StreamedLoadParityBase(EngineLevel.FULL)
