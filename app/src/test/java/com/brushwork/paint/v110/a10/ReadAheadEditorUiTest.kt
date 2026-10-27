package com.brushwork.paint.v110.a10

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.MemoryGovernor
import com.brushwork.paint.engine.MemoryPressure
import com.brushwork.paint.engine.budget.LedgerCategory
import com.brushwork.paint.engine.tiles.PackedPool
import com.brushwork.paint.engine.tiles.ResidencyHooks
import com.brushwork.paint.engine.tiles.SlabSwapStore
import com.brushwork.paint.engine.tiles.SwapReadAhead
import com.brushwork.paint.engine.tiles.Tile
import com.brushwork.paint.engine.tiles.TileState
import com.brushwork.paint.engine.tiles.TileWorker
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.qa19.Qa19Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.FakeStorageProbe
import com.brushwork.paint.testing.MemoryPreset
import com.brushwork.paint.ui.editor.EditorScreen
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.v110.lead.F1Pixels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.10 A10 step 8 (design 2.7, 2.8 "reads ... ahead"; review): the read-ahead in the real editor
 * at FULL (`EngineEditorChrome` over `EditorScreen`, a [SlabSwapStore] installed), driven through
 * the controller and the activity's lifecycle. Shown and idle, the swapped tiles of the layer next
 * to the active one are copied after 300 ms and the far layers' are not; a switch with
 * `EditorController.selectLayer` moves the copies to the new neighbour; in the background
 * (`ON_STOP`) every copy is dropped and nothing is read, back in front (`ON_START`) they are read
 * again; when the editor goes, every copy is dropped. The packed pool ends where it started, and the
 * slots stay throughout (the tiles stay SWAPPED).
 *
 * No text of its own (nothing to check for overflow or at other font scales): one UI test at
 * 392 dp, in its own sandbox (the test recomposer and the frame clock are global).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392, instrumentedPackages = ["com.brushwork.paint.v110.a10.readaheadsandbox"])
class ReadAheadEditorUiTest {
    /** A residency that parks and frees nothing, so the only copies and packed bytes are the read-ahead's. */
    private class Quiet : ResidencyHooks {
        override fun activate(layer: Layer) = Unit

        override fun deactivated(layer: Layer) = Unit

        override fun pressure(p: MemoryPressure) = Unit

        override fun idleTick() = Unit
    }

    @get:Rule
    val engine = EngineRule(level = EngineLevel.FULL, memory = FakeMemoryProbe(MemoryPreset.T606), storage = FakeStorageProbe(), residency = Quiet())

    private val made = ArrayList<Bitmap>()

    @Test
    fun theEditorReadsAheadWhileStartedAndLetsGoInTheBackgroundAndWhenItGoes() = Qa19Ui.uiTest { h ->
        h.section("the editor at FULL, 392 dp") { editor() }
    }

    private fun editor() {
        val store = SwapKit.store()
        EngineConfig.swapStore = store
        val poolBefore = PackedPool.heldBytes
        // 0 far below, 1 next below, 2 active, 3 a flat layer above, 4 far above (parked).
        val doc = Document("ra-ui", "Read-ahead", SIDE, SIDE)
        val far0 = parked(doc, store, 50)
        val below = parked(doc, store, 51)
        val active = flat(doc, 52, "Active")
        val other = flat(doc, 53, "Other")
        val far4 = parked(doc, store, 54)
        doc.layers.addAll(listOf(far0, below, active, other, far4))
        doc.activeLayerIndex = 2
        val ctl = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        var c: EditorController? = null
        try {
            val activity = ctl.get()
            val ctrl = Smoke.controller(activity, doc).also { c = it }
            activity.setContent { BrushworkTheme { EditorScreen(ctrl, onExit = {}, onSaveNow = {}) } }
            Qa19Ui.settle()

            assertTrue(
                "shown and idle: the layer below is read ahead (${copies(below)} of ${painted(below).size})",
                pump { copies(below) == painted(below).size },
            )
            assertEquals("the far layers keep nothing", 0, copies(far0) + copies(far4))
            assertEquals("counted in the packed pool", poolBefore + slotBytes(painted(below)), PackedPool.heldBytes)
            assertTrue("its memory entry", MemoryGovernor.entries().any { it.name == MEMORY_NAME })

            ctrl.selectLayer(other)
            assertTrue("the switch moves the copies to the new neighbour", pump { copies(far4) == painted(far4).size && copies(below) == 0 })
            assertEquals(poolBefore + slotBytes(painted(far4)), PackedPool.heldBytes)

            ctl.pause().stop()
            assertTrue("in the background every copy goes", pump { copies(far4) == 0 && PackedPool.heldBytes == poolBefore })
            assertTrue("and its memory entry", MemoryGovernor.entries().none { it.name == MEMORY_NAME })
            Smoke.pump(SwapReadAhead.RECHECK_MS + SwapReadAhead.IDLE_MS)
            drain()
            assertEquals("nothing is read in the background", 0, copies(far4) + copies(below))

            ctl.start().resume()
            assertTrue("read again in front", pump { copies(far4) == painted(far4).size })

            ctl.pause().stop().destroy()
            assertTrue("the editor goes: every copy goes", pump { copies(far4) == 0 && PackedPool.heldBytes == poolBefore })
            assertTrue(MemoryGovernor.entries().none { it.name == MEMORY_NAME })
            val all = listOf(far0, below, far4).flatMap { painted(it) }
            assertTrue("the slots stay", all.all { it.state == TileState.SWAPPED })
        } finally {
            if (!ctl.get().isDestroyed) runCatching { ctl.pause().stop().destroy() }
            c?.dispose()
            drain()
            for (l in doc.layers) l.recycleBitmaps()
            drain()
            made.forEach { if (!it.isRecycled) it.recycle() }
            SwapKit.cleanUp()
        }
    }

    /** Pumps the main looper and the frames, giving the tile-swapper real time, until [done]. */
    private fun pump(done: () -> Boolean): Boolean = A10Kit.pumpUntil(15_000) {
        Qa19Ui.settle(1)
        TileWorker.isIdle && done()
    }

    private fun drain() {
        A10Kit.pumpUntil(10_000) { TileWorker.isIdle }
    }

    private fun picture(seed: Long): Bitmap = F1Pixels.mixed(SIDE, SIDE, seed).also { made += it }

    private fun flat(doc: Document, seed: Long, name: String): Layer =
        Layer(doc.newLayerId(), name, picture(seed).copy(Bitmap.Config.ARGB_8888, true).also { made += it })

    /** A parked layer of [seed]'s picture whose painted tiles are SWAPPED in [store] (written, then main's commit). */
    private fun parked(doc: Document, store: SlabSwapStore, seed: Long): Layer {
        val l = Layer(doc.newLayerId(), "Layer $seed", TiledPixels.fromBitmap(picture(seed)))
        for (t in (l.pixels as TiledPixels).tiles) {
            if (t == null || t.solid != null || t.bitmap == null) continue
            store.write(t)
            val b = synchronized(t.lock) { t.bitmap.also { t.bitmap = null; t.packed = null } }
            b?.recycle()
            EngineConfig.budget.ledger.sub(LedgerCategory.TILES, t.bytes.toLong())
        }
        return l
    }

    private fun painted(l: Layer): List<Tile> = (l.pixels as? TiledPixels)?.tiles?.filterNotNull()?.filter { it.solid == null }.orEmpty()

    private fun copies(l: Layer): Int = painted(l).count { synchronized(it.lock) { it.packed != null && it.slot != null && it.bitmap == null } }

    private fun slotBytes(tiles: List<Tile>): Long = tiles.sumOf { synchronized(it.lock) { it.slot!!.length.toLong() } }

    private companion object {
        const val SIDE = 512

        /** The read-ahead's `MemoryGovernor` entry. */
        const val MEMORY_NAME = "read-ahead tiles"
    }
}
