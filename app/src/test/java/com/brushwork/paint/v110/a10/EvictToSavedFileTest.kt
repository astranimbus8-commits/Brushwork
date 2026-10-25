package com.brushwork.paint.v110.a10

import android.graphics.Bitmap
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.NativeWatch
import com.brushwork.paint.engine.budget.LedgerCategory
import com.brushwork.paint.engine.tiles.ResidencyState
import com.brushwork.paint.engine.tiles.SavedFileRef
import com.brushwork.paint.engine.tiles.SolidTiles
import com.brushwork.paint.engine.tiles.TILE_SIZE
import com.brushwork.paint.engine.tiles.TileOps
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.model.Layer
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.v110.lead.F1Pixels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.IOException

/**
 * v1.10 A10 (design 2.0 P3 order 1 and 2.8, step 4): a PARKED layer unchanged since its last save,
 * with its saved file current, is evicted at no write cost: its content becomes an EVICTED store
 * pointing at the file, every tile it alone held is released (the ledger's TILES bytes fall by
 * exactly them and the shared uniform bitmaps it held last), `pixelGeneration`, `contentVersion`
 * and the mask stay, and the file reads back into the same pixels bit for bit. A tile an undo step
 * still holds stays readable. Never evicted: a changed layer, a pinned one, a FLAT one, one
 * already evicted, one read in the last 8 epochs, one whose file is missing or older than its
 * content. A file that does not read back throws `IOException`.
 */
@RunWith(RobolectricTestRunner::class)
class EvictToSavedFileTest {
    @get:Rule
    val engine = EngineRule(level = EngineLevel.TILES)

    private val dir get() = File(RuntimeEnvironment.getApplication().filesDir, "projects/evict").apply { mkdirs() }
    private val now = 100L

    private fun tiles() = EngineConfig.budget.ledger.of(LedgerCategory.TILES)

    /** The shared bitmaps of uniform tiles ([SolidTiles], one per color): a store's release lets go of those it held last. */
    private fun solidBytes() = SolidTiles.colors() * TILE_SIZE.toLong() * TILE_SIZE * 4

    @Test
    fun aCleanColdLayerIsEvictedAndReadsBackBitForBit() {
        val src = EvictKit.picture(600, 520, 1)
        val (layer, ref) = EvictKit.savedParkedLayer(dir, 1, src)
        val parked = layer.pixels as TiledPixels
        val held = parked.residentBytes()
        assertTrue("tiles to evict", held > 0)
        val generation = layer.pixelGeneration
        val version = layer.contentVersion
        val mask = layer.maskPixels
        val before = tiles()
        val solid = solidBytes()

        val freed = NativeWatch.evict(layer, ref, now)
        val sharedFreed = solid - solidBytes()
        assertEquals("every tile it held, and the shared uniform bitmaps it held last", held + sharedFreed, freed)
        assertEquals("the ledger lets them go", before - freed, tiles())
        assertEquals(ResidencyState.EVICTED, layer.residency)
        val evicted = layer.pixels as TiledPixels
        assertSame("it points at its saved file", ref, evicted.evictedTo)
        assertEquals("no tile held", 0, evicted.occupied())
        assertEquals(0L, evicted.residentBytes())
        assertEquals("same size", src.width, evicted.width)
        assertEquals("no generation change (J5)", generation, layer.pixelGeneration)
        assertEquals("still clean", version, layer.contentVersion)
        assertSame("the mask stays", mask, layer.maskPixels)
        assertTrue("not empty while evicted", !evicted.isEmpty())

        val back = NativeWatch.reload(evicted)
        assertNull("parked again", back.evictedTo)
        val pixels = back.materialize()
        F1Pixels.assertBitEqual("the saved file's pixels", src, pixels)
        pixels.recycle()
        TileOps.releaseStore(back)
    }

    @Test
    fun aTileAnUndoStepHoldsStaysReadable() {
        val src = EvictKit.picture(512, 512, 2)
        val (layer, ref) = EvictKit.savedParkedLayer(dir, 2, src)
        val parked = layer.pixels as TiledPixels
        val held = parked.residentBytes()
        val undo = parked.snapshot()
        val freed = NativeWatch.evict(layer, ref, now)
        assertEquals("the shared tiles stay counted", 0L, freed)
        assertEquals(ResidencyState.EVICTED, layer.residency)
        val pixels = undo.materialize()
        F1Pixels.assertBitEqual("the undo step's tiles", src, pixels)
        pixels.recycle()
        val before = tiles()
        val solid = solidBytes()
        TileOps.releaseStore(undo)
        assertEquals("freed with the undo step", before - held - (solid - solidBytes()), tiles())
    }

    @Test
    fun whatIsNeverEvicted() {
        val src = EvictKit.picture(300, 300, 3)
        fun refused(what: String, layer: Layer, ref: SavedFileRef, at: Long = now) {
            val store = layer.pixels
            assertEquals("$what: refused", 0L, NativeWatch.evict(layer, ref, at))
            assertSame("$what: untouched", store, layer.pixels)
        }

        val (changed, changedRef) = EvictKit.savedParkedLayer(dir, 10, src)
        changed.markChanged()
        refused("a changed layer", changed, changedRef)
        val (older, olderRef) = EvictKit.savedParkedLayer(dir, 15, src)
        refused("an older file", older, olderRef.copy(contentVersion = older.contentVersion - 1))
        assertTrue("its current file: evicted", NativeWatch.evict(older, olderRef, now) > 0)

        val (pinned, pinnedRef) = EvictKit.savedParkedLayer(dir, 11, src)
        pinned.pin().use { refused("a pinned layer", pinned, pinnedRef) }
        assertTrue("evicted once the pin is closed", NativeWatch.evict(pinned, pinnedRef, now) > 0)
        refused("an evicted layer", pinned, pinnedRef)

        val (warm, warmRef) = EvictKit.savedParkedLayer(dir, 12, src)
        EvictKit.readAt(warm, now - NativeWatch.EVICT_MIN_AGE_EPOCHS + 1)
        refused("read 7 epochs ago", warm, warmRef)
        EvictKit.readAt(warm, now - NativeWatch.EVICT_MIN_AGE_EPOCHS)
        assertTrue("read 8 epochs ago: evicted", NativeWatch.evict(warm, warmRef, now) > 0)

        val (missing, missingRef) = EvictKit.savedParkedLayer(dir, 13, src)
        assertTrue(File(missingRef.projectDir, missingRef.file).delete())
        refused("a missing file", missing, missingRef)

        val flat = Layer(14, "Flat", src.copy(Bitmap.Config.ARGB_8888, true)).apply { savedVersion = contentVersion }
        refused("a FLAT layer", flat, SavedFileRef(dir, "layer_14_r0.bin", flat.contentVersion))
    }

    @Test
    fun aFileThatDoesNotReadBackThrows() {
        val src = EvictKit.picture(300, 200, 4)
        val (layer, ref) = EvictKit.savedParkedLayer(dir, 20, src)
        assertTrue(NativeWatch.evict(layer, ref, now) > 0)
        File(ref.projectDir, ref.file).writeBytes(ByteArray(64) { 7 })
        try {
            NativeWatch.reload(layer.pixels as TiledPixels)
            fail("a damaged file must not read back")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().isNotEmpty() || e.cause != null)
        }
        assertEquals("the layer stays evicted for the caller to restore", ResidencyState.EVICTED, layer.residency)
    }
}
