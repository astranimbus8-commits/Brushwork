package com.brushwork.paint.v110.a8

import android.graphics.Bitmap
import android.os.Looper
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.tiles.ClassicResidency
import com.brushwork.paint.engine.tiles.ResidencyPolicy
import com.brushwork.paint.engine.tiles.ResidencyState
import com.brushwork.paint.engine.tiles.TileState
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeSwapStore
import com.brushwork.paint.tools.ToolPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.10 A8 step 3 (design 2.0 P1, 2.7; FULL with a [FakeSwapStore] that takes 2 ms a read): the
 * editor's own policy (`ResidencyPolicy.attach`, region `v110-A8 activate`). A switch to a layer
 * whose tiles are SWAPPED sets `EditorController.activating` before `selectLayer` returns; a
 * gesture meanwhile is ignored (no tool takes it); the worker reads the tiles (never on main);
 * the hold ends once the layer is FLAT with its pixels, and the next gesture reaches the tool.
 * The press-time unpark (`prepareLayer`) starts the same hold before the switch. A switch to a
 * parked layer whose tiles are resident is FLAT when `selectLayer` returns, with no hold; a switch
 * away while the tiles are read ends the hold at once and leaves the layer parked.
 */
@RunWith(RobolectricTestRunner::class)
class ActivationAsyncHoldsInputTest {
    private val swap = FakeSwapStore(latencyMs = 2L)

    @get:Rule
    val engine = EngineRule(EngineLevel.FULL, residency = ClassicResidency, swap = swap)

    private val made = ArrayList<Bitmap>()
    private val docs = ArrayList<Document>()
    private lateinit var baseline: A8Kit.Baseline
    private var controller: EditorController? = null
    private var scope: CoroutineScope? = null

    @Before
    fun setUp() {
        baseline = A8Kit.Baseline()
    }

    @After
    fun tearDown() {
        (EngineConfig.residency as? ResidencyPolicy)?.stop()
        A8Kit.drain()
        controller?.dispose()
        scope?.cancel()
        baseline.assertBack(docs)
        made.forEach { if (!it.isRecycled) it.recycle() }
    }

    /** An editor over 3 noisy layers, the top one active, with its policy (installed by the editor). */
    private fun editor(): Pair<EditorController, ResidencyPolicy> {
        val doc = A8Kit.doc(3, made).also { docs += it }
        doc.activeLayerIndex = 2
        val sc = Smoke.newScope().also { scope = it }
        val c = Smoke.controller(RuntimeEnvironment.getApplication(), doc, sc).also { controller = it }
        val p = EngineConfig.residency as? ResidencyPolicy
        assertNotNull("the editor installed its policy at FULL", p)
        A8Kit.idle()
        return c to p!!
    }

    /** Lets [layer] park (its own tick), then, with a budget of 0, every tile of its own go to the swap. */
    private fun parkAndSwap(p: ResidencyPolicy, layer: Layer) {
        A8Kit.advance(2600)
        A8Kit.drain()
        assertEquals(ResidencyState.PARKED, layer.residency)
        p.tileRam = { 0L }
        A8Kit.advance(600)
        A8Kit.drain()
        p.tileRam = { Long.MAX_VALUE }
        val own = A8Kit.tilesOf(layer).filter { it.solid == null }
        assertTrue(own.isNotEmpty())
        assertTrue("swapped: ${own.map { it.state }}", own.all { it.state == TileState.SWAPPED })
    }

    private val mainName: String get() = Looper.getMainLooper().thread.name

    @Test
    fun aSwitchToASwappedLayerHoldsInputUntilItIsFlat() {
        val (c, p) = editor()
        val l0 = c.doc.layers[0]
        val pixels = A8Kit.pixelsOf(l0)
        val generation = l0.pixelGeneration
        parkAndSwap(p, l0)
        val reads = swap.reads

        c.selectLayer(l0)
        assertSame("the hold is on when selectLayer returns", l0, c.activating)
        assertSame(l0, c.doc.activeLayer)
        val steps = c.undoManager.undoCount
        c.pointerDown(ToolPoint(40f, 40f))
        assertFalse("no tool took the gesture", c.isInteracting)
        c.pointerMove(ToolPoint(80f, 60f))
        c.pointerUp(ToolPoint(120f, 80f))
        A8Kit.drain()

        assertNull("the hold ended", c.activating)
        assertEquals(ResidencyState.FLAT, l0.residency)
        assertEquals("nothing was drawn while held", steps, c.undoManager.undoCount)
        assertTrue("the tiles were read", swap.reads > reads)
        val threads = swap.readThreads.drop(reads)
        assertFalse("no read on main: $threads", threads.contains(mainName))
        val now = A8Kit.pixelsOf(l0)
        assertArrayEquals(pixels.first, now.first)
        assertEquals("J5", generation, l0.pixelGeneration)

        c.pointerDown(ToolPoint(40f, 40f))
        assertTrue("the next gesture reaches the tool", c.isInteracting)
        c.pointerUp(ToolPoint(40f, 40f))
        A8Kit.drain()
    }

    @Test
    fun thePressStartsTheHoldBeforeTheSwitch() {
        val (c, p) = editor()
        val l1 = c.doc.layers[1]
        val pixels = A8Kit.pixelsOf(l1)
        assertNotNull("layer 1 is masked", pixels.second)
        parkAndSwap(p, l1)
        val reads = swap.reads

        c.prepareLayer(l1)
        assertSame("held from the press", l1, c.activating)
        assertSame("not switched yet", c.doc.layers[2], c.doc.activeLayer)
        c.selectLayer(l1)
        assertSame(l1, c.doc.activeLayer)
        A8Kit.drain()
        assertNull(c.activating)
        assertEquals(ResidencyState.FLAT, l1.residency)
        assertFalse(swap.readThreads.drop(reads).contains(mainName))
        val now = A8Kit.pixelsOf(l1)
        assertArrayEquals("the content, copied tile by tile as the reads landed", pixels.first, now.first)
        assertArrayEquals("the mask too", pixels.second, now.second)
    }

    @Test
    fun aSwitchToAParkedLayerWithResidentTilesIsFlatAtOnce() {
        val (c, _) = editor()
        val l0 = c.doc.layers[0]
        A8Kit.advance(2600)
        A8Kit.drain()
        assertEquals(ResidencyState.PARKED, l0.residency)
        val reads = swap.reads

        c.selectLayer(l0)
        assertEquals("FLAT when selectLayer returns", ResidencyState.FLAT, l0.residency)
        assertNull("no hold", c.activating)
        A8Kit.drain()
        assertEquals(reads, swap.reads)
    }

    @Test
    fun aSwitchAwayDuringTheReadsEndsTheHoldAndTheLayerStaysParked() {
        val (c, p) = editor()
        val l0 = c.doc.layers[0]
        parkAndSwap(p, l0)
        c.selectLayer(l0)
        assertSame(l0, c.activating)
        c.selectLayer(c.doc.layers[2])
        assertNull("the switch away ended the hold at once", c.activating)
        A8Kit.drain()
        assertNull(c.activating)
        assertEquals(ResidencyState.PARKED, l0.residency)
        assertEquals("the read's pin went", 0, l0.pins)
    }
}
