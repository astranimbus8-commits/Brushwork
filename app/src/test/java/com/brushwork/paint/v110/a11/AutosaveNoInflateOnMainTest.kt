package com.brushwork.paint.v110.a11

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Looper
import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.EditorSession
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.budget.LedgerCategory
import com.brushwork.paint.engine.tiles.FlatPixels
import com.brushwork.paint.engine.tiles.Tile
import com.brushwork.paint.engine.tiles.TileState
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.masks.AdjustmentCodec
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.GridSettings
import com.brushwork.paint.model.PixelFacade
import com.brushwork.paint.storage.LayerCodec
import com.brushwork.paint.storage.LayerEntryDto
import com.brushwork.paint.storage.ProjectFileDto
import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.storage.SaveThumbnail
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeSwapStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration

/**
 * v1.10 (A11, design 2.2 and 2.9, findings 47 and 54, I29): an autosave of a FULL artwork whose
 * changed layers are swapped out inflates nothing on main. The editor's save takes `snapshot()`s
 * and non-resident pins on main; the 512 px thumbnail is rendered from them on the save writer
 * (an adjustment layer's tiled mask is made into a bitmap there too), and the changed layers are
 * encoded from their snapshots on IO. Every swapped tile is read off main (the fake swap store
 * records the reading threads), nothing is materialized (`PixelFacade.strict` is on during the
 * save), no tile is made resident, every pin and snapshot is let go of, and `thumb.png` is the
 * picture the editor's compositor draws. The tiles are swapped out by the test, as A10's worker
 * and main would (F1 has no swap): A10 adds its own swap-in half when it lands.
 */
@RunWith(RobolectricTestRunner::class)
class AutosaveNoInflateOnMainTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.FULL)

    private val side = 512

    /** Rows of a mask: visible in the top half, hidden in the bottom half. */
    private fun maskRows(y: Int, n: Int, into: ByteArray) {
        val white = A11Kit.opaqueBytes(0xFFFFFF)
        val black = A11Kit.opaqueBytes(0x000000)
        for (r in 0 until n) {
            val px = if (y + r < side / 2) white else black
            for (x in 0 until side) System.arraycopy(px, 0, into, (r * side + x) * 4, 4)
        }
    }

    /** Four painted layers and an invert adjustment layer with a mask in the middle; the active one (index 3) is painted. */
    private fun write(id: String): File {
        A11Kit.freshRepo()
        val dir = A11Kit.dirOf(id)
        dir.mkdirs()
        val entries = (1L..5L).map { layerId ->
            val file = ProjectFormat.layerFile(layerId, 1)
            if (layerId == ADJUSTMENT_ID) {
                LayerCodec.writeBlank(File(dir, file), side, side)
                val mask = ProjectFormat.maskFile(layerId, 1)
                A11Kit.writeRows(File(dir, mask), side, side, ::maskRows)
                LayerEntryDto(
                    layerId, "Invert 1", ProjectFormat.defaultProps("Invert 1"), hasMask = true, file = file, maskFile = mask,
                    adjustment = AdjustmentCodec.encode(AdjustmentSpec(filterId = "adjust.invert")),
                )
            } else {
                A11Kit.writeRows(File(dir, file), side, side, A11Kit.PaintedRows(side, side, seed = 900L + layerId)::fill)
                LayerEntryDto(layerId, "Layer $layerId", ProjectFormat.defaultProps("Layer $layerId"), file = file)
            }
        }
        ProjectFormat.write(dir, ProjectFileDto(formatVersion = 3, id = id, name = id, width = side, height = side, activeLayerIndex = 3, layers = entries, revision = 1))
        return dir
    }

    /** Every tile of [doc]'s tiled stores (content and masks). */
    private fun tilesOf(doc: Document): List<Tile> = doc.layers.flatMap { l ->
        listOf(l.pixels, l.maskPixels).filterIsInstance<TiledPixels>().flatMap { s -> s.tiles.filterNotNull() }
    }

    @Test
    fun anAutosaveOfSwappedChangedLayersInflatesNothingOnMain() {
        val dir = write(ID)
        val app = RuntimeEnvironment.getApplication() as BrushworkApp
        val session = EditorSession(app, ID)
        pumpUntil("the editor") { session.state is EditorSession.State.Ready }
        val c = (session.state as EditorSession.State.Ready).controller
        val doc = c.doc
        val adjustment = doc.layers.single { it.isAdjustmentLayer }
        assertTrue("the adjustment layer's mask is tiled", adjustment.maskPixels is TiledPixels)
        assertTrue("the active layer is FLAT", doc.layers[3].pixels is FlatPixels)

        // The session's swap store (EditorSession opened the engine session with none): every
        // tile that is not a shared solid one goes out, as A10's worker and main would do it.
        val swap = FakeSwapStore(capacityBytes = 1L shl 30)
        EngineConfig.swapStore = swap
        val ledger = EngineConfig.budget.ledger
        val tiles = tilesOf(doc)
        for (t in tiles) {
            if (t.solidColor != null) continue
            val b = t.bitmap
            swap.swapOut(t)
            b?.recycle()
            ledger.sub(LedgerCategory.TILES, t.bytes.toLong())
        }
        val swapped = tiles.filter { it.state == TileState.SWAPPED }
        assertTrue("tiles of four layers and the mask are swapped", swapped.size >= 5)
        val refs = tiles.map { it.refs }

        // Every layer changed (a whole-canvas edit) and an edit for the session to save.
        for (l in doc.layers) l.markChanged()
        c.updateGrid(GridSettings(enabled = !c.grid.enabled))
        val main = Thread.currentThread()
        val materialized = PixelFacade.materializations
        SaveThumbnail.renderThread = null
        PixelFacade.strict = true
        try {
            runBlocking { session.save() }
        } finally {
            PixelFacade.strict = false
        }

        assertTrue("saved", doc.layers.all { it.savedVersion == it.contentVersion })
        assertEquals("nothing was materialized", materialized, PixelFacade.materializations)
        assertTrue("the swapped tiles were read", swap.reads >= swapped.size)
        assertFalse("no swapped tile was read on main: ${swap.readThreads.toSet()}", swap.readThreads.contains(main.name))
        val thread = SaveThumbnail.renderThread
        assertNotNull("the thumbnail came from the snapshots", thread)
        assertNotSame("the thumbnail was rendered off main", main, thread)
        assertTrue("no tile was made resident", swapped.all { it.state == TileState.SWAPPED })
        assertEquals("every pin is let go of", 0, doc.layers.sumOf { it.pins })
        assertEquals("every snapshot is let go of", refs, tiles.map { it.refs })

        // thumb.png is the editor's own picture (both through PNG, which unpremultiplies).
        val saved = BitmapFactory.decodeFile(File(dir, ProjectFormat.THUMB_FILE).path)
        assertNotNull("thumb.png written", saved)
        val reference = PixelFacade.allowing { c.compositor.renderThumbnail(512) }
        val expected = throughPng(reference)
        assertEquals(expected.width, saved.width)
        assertEquals(expected.height, saved.height)
        assertArrayEquals("thumb.png", pixels(expected), pixels(saved))
        reference.recycle()

        var closed = false
        session.close { closed = true }
        pumpUntil("the session to close") { closed }
    }

    private fun throughPng(b: Bitmap): Bitmap {
        val out = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.PNG, 100, out)
        return BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun pumpUntil(what: String, done: () -> Boolean) {
        var n = 0
        while (!done() && n++ < 2000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            Thread.sleep(2)
        }
        assertTrue("timed out waiting for $what", done())
    }

    private companion object {
        const val ID = "autosave"
        const val ADJUSTMENT_ID = 3L
    }
}
