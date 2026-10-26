package com.brushwork.paint.v110.a11

import android.graphics.Rect
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.tiles.BlankPixels
import com.brushwork.paint.engine.tiles.FlatPixels
import com.brushwork.paint.engine.tiles.TileOps
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.storage.CorruptProjectException
import com.brushwork.paint.storage.LayerCodec
import com.brushwork.paint.storage.LayerEntries
import com.brushwork.paint.testing.EngineRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * v1.10 (A11, design 2.9): the blank fast path. A saved transparent layer's file is the cached
 * canonical encoding byte for byte, so it opens as a [BlankPixels] store with nothing decoded
 * (no band is made). Only a whole-file match counts: one opaque pixel in the last row of a
 * 4000 x 5000 and of a 6000² layer (encodings longer than 64 KiB, so a prefix check would miss
 * it) is found and loaded into exactly one tile, and a file of the same length with one byte changed is not
 * blank (its decode reports the damage). At TILES, so the big files never need a bitmap of the
 * canvas.
 */
@RunWith(RobolectricTestRunner::class)
class BlankFastPathTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.TILES)

    @Test
    fun aSavedBlankLayerIsTheCanonicalEncodingAndDecodesNothing() {
        val w = 300
        val h = 520
        val repo = A11Kit.freshRepo()
        val doc = Document("blank", "blank", w, h)
        for (id in 1L..4L) doc.layers += Layer(id, "L$id", BitmapUtils.createLayerBitmap(w, h))
        doc.activeLayerIndex = 3
        runBlocking { repo.save(doc, null) }
        A11Kit.dispose(doc)
        val enc = LayerCodec.blankEncoding(w, h)
        val files = A11Kit.dirOf("blank").listFiles()!!.filter { it.name.startsWith("layer_") }
        assertEquals(4, files.size)
        for (f in files) assertArrayEquals(f.name, enc, f.readBytes())
        LayerEntries.loadScratchBytes = 0
        val loaded = runBlocking { A11Kit.repo().load("blank") }
        try {
            for (i in 0 until 3) assertTrue("layer $i BLANK", loaded.layers[i].pixels is BlankPixels)
            assertTrue("the active layer is FLAT", loaded.layers[3].pixels is FlatPixels)
            assertEquals("nothing was decoded: no band", 0, LayerEntries.loadScratchBytes)
        } finally {
            A11Kit.dispose(loaded)
        }
    }

    @Test
    fun aLastRowPixelAt4000x5000IsFound() = lastRowPixel(4000, 5000)

    @Test
    fun aLastRowPixelAt6000SquaredIsFound() = lastRowPixel(6000, 6000)

    /** A [w] x [h] layer transparent but for one opaque pixel at its bottom right corner. */
    private fun lastRowPixel(w: Int, h: Int) {
        val dir = A11Kit.dirOf("big").apply { deleteRecursively(); mkdirs() }
        val pixel = A11Kit.opaqueBytes(0x3366CC)
        A11Kit.writeRows(File(dir, "layer_1_r1.bin"), w, h) { _, n, into -> java.util.Arrays.fill(into, 0, n * w * 4, 0) }
        A11Kit.writeRows(File(dir, "layer_2_r1.bin"), w, h) { y, n, into ->
            java.util.Arrays.fill(into, 0, n * w * 4, 0)
            if (y + n == h) System.arraycopy(pixel, 0, into, ((n - 1) * w + w - 1) * 4, 4)
        }
        assertTrue(LayerCodec.isBlankEncoding(File(dir, "layer_1_r1.bin"), w, h))
        assertFalse(LayerCodec.isBlankEncoding(File(dir, "layer_2_r1.bin"), w, h))
        val load = LayerEntries.StreamedLoad(dir, w, h, tiled = true)
        try {
            assertTrue(load.read("layer_1_r1.bin", "blank", flat = false) is BlankPixels)
            val store = load.read("layer_2_r1.bin", "one pixel", flat = false) as TiledPixels
            assertEquals("one tile", 1, store.occupied())
            assertNotNull(store.tileAt(store.cols - 1, store.rows - 1))
            assertEquals(0xFF3366CC.toInt(), TileOps.sample(store, w - 1, h - 1))
            assertEquals(0, TileOps.sample(store, w - 2, h - 1))
            val r = Rect()
            assertTrue(store.occupiedBounds(r))
            assertEquals(Rect((store.cols - 1) * 256, (store.rows - 1) * 256, w, h), r)
        } finally {
            load.releaseAll()
            load.close()
        }
    }

    @Test
    fun theSameLengthWithOneByteChangedIsNotBlank() {
        val w = 300
        val h = 520
        val dir = A11Kit.dirOf("flipped").apply { deleteRecursively(); mkdirs() }
        val bytes = LayerCodec.blankEncoding(w, h).copyOf()
        // The last byte is the zlib checksum's: the stream still inflates to zeros, then fails its check.
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        File(dir, "layer_1_r1.bin").writeBytes(bytes)
        assertFalse(LayerCodec.isBlankEncoding(File(dir, "layer_1_r1.bin"), w, h))
        val load = LayerEntries.StreamedLoad(dir, w, h, tiled = true)
        try {
            load.read("layer_1_r1.bin", "Layer \"1\"", flat = false)
            fail("a damaged blank file loads")
        } catch (e: CorruptProjectException) {
            assertTrue(e.message, e.message!!.startsWith("Layer \"1\" is damaged or missing"))
        } finally {
            load.releaseAll()
            load.close()
        }
    }
}
