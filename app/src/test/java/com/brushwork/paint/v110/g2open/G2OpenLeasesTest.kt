package com.brushwork.paint.v110.g2open

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.CanvasOps
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.EngineStartup
import com.brushwork.paint.engine.MemoryPressure
import com.brushwork.paint.engine.budget.AndroidMemoryProbe
import com.brushwork.paint.engine.budget.LedgerCategory
import com.brushwork.paint.engine.tiles.BlankPixels
import com.brushwork.paint.engine.tiles.ResidencyHooks
import com.brushwork.paint.engine.tiles.ResidencyState
import com.brushwork.paint.engine.tiles.Tile
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.FakeSwapStore
import com.brushwork.paint.testing.MemoryPreset
import com.brushwork.paint.ui.canvas.paintedBounds
import com.brushwork.paint.ui.common.EngineLabels110
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * v1.10 Gate 2 (lane open, O6; FULL on the T606 with a swap store smaller than one layer and a
 * residency that parks and swaps nothing):
 *
 * - Trim's painted area (`paintedBounds`, what the Trim tab shows; G2-O-d) reads a lease: on the
 *   reading thread (not main) every pixel layer is pinned and the parked layer's store is the
 *   lease's snapshot, not the layer's own; once the call is back on main every pin and every tile
 *   hold is let go, and the area is that of the two painted rectangles.
 * - The engine's start gives the memory probe the application (`AndroidMemoryProbe.attach`).
 * - With R and the swap store full, "Apply mask" on a blank layer says
 *   [EngineLabels110.STORAGE_FULL], not PAINT_REFUSED, and changes nothing; with room it applies.
 */
@RunWith(RobolectricTestRunner::class)
class G2OpenLeasesTest {
    private val swap = FakeSwapStore(capacityBytes = 64L shl 10)

    @get:Rule
    val engine = EngineRule(EngineLevel.FULL, memory = FakeMemoryProbe(MemoryPreset.T606), residency = Quiet(), swap = swap)

    private var controller: EditorController? = null
    private var scope: CoroutineScope? = null
    private var filled = 0L
    private val made = ArrayList<Bitmap>()

    @After
    fun tearDown() {
        empty()
        controller?.dispose()
        scope?.cancel()
        idle()
        made.forEach { if (!it.isRecycled) it.recycle() }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun open(top: (Document) -> Layer): Pair<EditorController, Document> {
        val doc = Smoke.document(SIZE, SIZE, layers = 1)
        doc.layers[0].bitmap.eraseColor(0)
        doc.layers += top(doc)
        doc.activeLayerIndex = 0
        val s = Smoke.newScope().also { scope = it }
        val c = Smoke.controller(RuntimeEnvironment.getApplication(), doc, s).also { controller = it }
        idle()
        return c to doc
    }

    /** Fills R so that no layer can be admitted (and the swap store is smaller than one layer). */
    private fun fill() {
        val b = EngineConfig.budget
        filled = (b.nativeBudget - (b.ledger.native - b.ledger.of(LedgerCategory.TILES))).coerceAtLeast(0L)
        b.ledger.add(LedgerCategory.TRANSIENT, filled)
    }

    private fun empty() {
        if (filled > 0L) EngineConfig.budget.ledger.sub(LedgerCategory.TRANSIENT, filled)
        filled = 0L
    }

    @Test
    fun trimsPaintedAreaReadsALeaseOffMainAndLetsItGoOnMain() {
        val painted = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).also { made += it }
        painted.eraseColor(0)
        painted.setPixel(300, 400, Color.RED)
        val (c, doc) = open { d -> Layer(d.newLayerId(), "Parked", TiledPixels.fromBitmap(painted)) }
        doc.layers[0].bitmap.setPixel(20, 30, Color.BLUE)
        val parked = doc.layers[1]
        assertEquals(ResidencyState.PARKED, parked.residency)
        val tiles: List<Tile> = (parked.pixels as TiledPixels).tiles.filterNotNull()
        val refs = tiles.map { it.refs }
        val main = Thread.currentThread()
        var reader: Thread? = null
        var pins: List<Int> = emptyList()
        var ownStore = true

        val area = runBlocking {
            paintedBounds(doc) { snap ->
                reader = Thread.currentThread()
                pins = snap.layers.map { it.layer.pins }
                ownStore = snap.layers[1].pixels === parked.pixels
                CanvasOps.opaqueBounds(snap)
            }
        }
        assertEquals("both painted pixels", Rect(20, 30, 301, 401), area)
        assertNotSame("read off main", main, reader)
        assertEquals("every pixel layer pinned during the read", listOf(1, 1), pins)
        assertFalse("the parked layer read through the lease's snapshot", ownStore)
        assertSame("back on main", main, Thread.currentThread())
        assertEquals("every pin let go", listOf(0, 0), doc.layers.map { it.pins })
        assertEquals("every tile hold of the lease let go", refs, tiles.map { it.refs })
        assertEquals(ResidencyState.PARKED, parked.residency)
        assertEquals("the read made nothing undoable", 0, c.undoManager.undoCount)
    }

    @Test
    fun theEnginesStartGivesTheMemoryProbeTheApplication() {
        val app = RuntimeEnvironment.getApplication() as Application
        val field = AndroidMemoryProbe::class.java.getDeclaredField("app").apply { isAccessible = true }
        val before = field.get(null)
        try {
            field.set(null, null)
            EngineStartup.onAppCreate(app)
            assertSame("attached at the start", app.applicationContext, field.get(null))
        } finally {
            field.set(null, before)
        }
    }

    @Test
    fun aRefusedApplyMaskSaysStorageFullAtFull() {
        val (c, doc) = open { d -> Layer(d.newLayerId(), "Blank", BlankPixels(SIZE, SIZE)) }
        val layer = doc.layers[1]
        layer.mask = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).also { it.eraseColor(-1) }
        val steps = c.undoManager.undoCount
        val generation = layer.pixelGeneration
        fill()
        assertFalse(swap.canWrite(doc.layerBytes))
        c.message = null
        c.applyMask(layer)
        assertEquals("the storage refusal is said", EngineLabels110.STORAGE_FULL, c.message)
        assertTrue("the mask stays", layer.hasMask)
        assertEquals(ResidencyState.BLANK, layer.residency)
        assertEquals(steps, c.undoManager.undoCount)
        assertEquals(generation, layer.pixelGeneration)
        // With room in R it applies, one step that undo takes back.
        empty()
        c.message = null
        c.applyMask(layer)
        idle()
        assertNull(c.message)
        assertFalse("applied", layer.hasMask)
        assertEquals(steps + 1, c.undoManager.undoCount)
        c.undo()
        idle()
        assertTrue("undo brings the mask back", layer.hasMask)
    }

    /** A residency that parks, swaps and frees nothing. */
    private class Quiet : ResidencyHooks {
        override fun activate(layer: Layer) = Unit
        override fun deactivated(layer: Layer) = Unit
        override fun pressure(p: MemoryPressure) = Unit
        override fun idleTick() = Unit
    }

    private companion object {
        const val SIZE = 512
    }
}
