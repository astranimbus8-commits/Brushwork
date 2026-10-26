package com.brushwork.paint.v110.a8

import android.graphics.Bitmap
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.GovernorPolicyV19
import com.brushwork.paint.engine.MemoryGovernor
import com.brushwork.paint.engine.MemoryPressure
import com.brushwork.paint.engine.UndoManager
import com.brushwork.paint.engine.tiles.BlankPixels
import com.brushwork.paint.engine.tiles.FlatPixels
import com.brushwork.paint.engine.tiles.FolderPixels
import com.brushwork.paint.engine.tiles.ParkCutter
import com.brushwork.paint.engine.tiles.ResidencyPolicy
import com.brushwork.paint.engine.tiles.ResidencyState
import com.brushwork.paint.engine.tiles.Tile
import com.brushwork.paint.engine.tiles.TileOps
import com.brushwork.paint.engine.tiles.TileState
import com.brushwork.paint.engine.tiles.TileWorker
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.FolderSpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.PixelFacade
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.v110.lead.F1Pixels
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v1.10 A8 step 2 (design 2.0 P1-P3, 2.7, J5, J6): the [ResidencyPolicy]'s states at TILES. An
 * inactive layer parks 2 s after its deactivation and not before; switching back within 2 s costs
 * nothing; the policy's own 500 ms tick parks; activation unparks at once when every tile is
 * resident, else holds input while the worker reads the missing tiles; BLANK and folder layers
 * never park; nothing parks while the editor is busy, and a park whose layer moved on meanwhile
 * is dropped; LOW pressure parks every eligible layer at once, coldest first (MODERATE nothing);
 * park and unpark keep the pixels, the `pixelGeneration` and the render; P3 sends undo-only tiles
 * (the oldest step first, never the newest 32 MiB nor the active layer's newest 3 steps) before
 * cold parked ones, those coldest first.
 */
@RunWith(RobolectricTestRunner::class)
class ResidencyStateMachineTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.TILES)

    private val made = ArrayList<Bitmap>()
    private val docs = ArrayList<Document>()
    private lateinit var baseline: A8Kit.Baseline

    @Before
    fun setUp() {
        baseline = A8Kit.Baseline()
    }

    private var started: ResidencyPolicy? = null
    private val histories = ArrayList<UndoManager>()

    @After
    fun tearDown() {
        started?.stop()
        A8Kit.drain()
        // The histories dispose their steps (their tiles go).
        histories.forEach { it.trimOldest(keepAtLeast = 0) { false } }
        baseline.assertBack(docs)
        made.forEach { if (!it.isRecycled) it.recycle() }
    }

    private fun activate(d: Document, i: Int) {
        d.activeLayerIndex = i
        A8Kit.idle()
    }

    private fun assertSamePixels(message: String, expected: Pair<IntArray, IntArray?>, layer: Layer) {
        val now = A8Kit.pixelsOf(layer)
        assertArrayEquals("$message: content", expected.first, now.first)
        assertEquals("$message: a mask", expected.second != null, now.second != null)
        if (expected.second != null) assertArrayEquals("$message: mask", expected.second, now.second)
    }

    @Test
    fun anInactiveLayerParksTwoSecondsAfterItsDeactivation() {
        val d = A8Kit.doc(3, made).also { docs += it }
        val (p, _) = A8Kit.policy(d)
        activate(d, 0)
        activate(d, 1)
        val l0 = d.layers[0]
        val pixels = A8Kit.pixelsOf(l0)
        val generation = l0.pixelGeneration

        A8Kit.advance(1900)
        p.idleTick()
        A8Kit.drain()
        assertEquals("1.9 s after its deactivation", ResidencyState.FLAT, l0.residency)

        A8Kit.advance(200)
        p.idleTick()
        A8Kit.drain()
        assertEquals("2.1 s after its deactivation", ResidencyState.PARKED, l0.residency)
        assertTrue("its mask is untouched (layer 0 has none)", l0.maskPixels == null)
        assertEquals("the active layer", ResidencyState.FLAT, d.layers[1].residency)
        assertEquals("layer 2 was first seen inactive 0.2 s ago", ResidencyState.FLAT, d.layers[2].residency)
        assertSamePixels("parked", pixels, l0)
        assertEquals("J5: a park is no pixel change", generation, l0.pixelGeneration)

        A8Kit.advance(1900)
        p.idleTick()
        A8Kit.drain()
        assertEquals("layer 2, 2.1 s after it was first seen", ResidencyState.PARKED, d.layers[2].residency)
    }

    @Test
    fun switchingBackWithinTwoSecondsParksNothing() {
        val d = A8Kit.doc(2, made).also { docs += it }
        val (p, _) = A8Kit.policy(d)
        activate(d, 0)
        activate(d, 1)
        A8Kit.advance(1500)
        p.idleTick()
        activate(d, 0)
        val ledger = A8Kit.tiles()

        A8Kit.advance(1000)
        p.idleTick()
        assertFalse("J6: no cut was started", d.layers.any { ParkCutter.isCutting(it) })
        A8Kit.drain()
        assertEquals(ResidencyState.FLAT, d.layers[0].residency)
        assertEquals("layer 1 is inactive for 1 s only", ResidencyState.FLAT, d.layers[1].residency)
        assertEquals("no tile was made", ledger, A8Kit.tiles())

        A8Kit.advance(1100)
        p.idleTick()
        A8Kit.drain()
        assertEquals(ResidencyState.FLAT, d.layers[0].residency)
        assertEquals(ResidencyState.PARKED, d.layers[1].residency)
    }

    @Test
    fun itsOwnTickParksEveryHalfSecond() {
        val d = A8Kit.doc(2, made).also { docs += it }
        val (p, _) = A8Kit.policy(d, start = true)
        started = p
        activate(d, 0)
        activate(d, 1)
        A8Kit.advance(1800)
        A8Kit.drain()
        assertEquals("ticks at 0.5, 1 and 1.5 s", ResidencyState.FLAT, d.layers[0].residency)
        A8Kit.advance(700)
        A8Kit.drain()
        assertEquals("the tick at 2 s", ResidencyState.PARKED, d.layers[0].residency)

        // Another residency took over (the session closed): the next tick stops it.
        com.brushwork.paint.engine.EngineConfig.closeSession()
        activate(d, 0)
        A8Kit.advance(600)
        assertTrue("it stopped itself", MemoryGovernor.entries().none { it.name == "parked layers" })
    }

    @Test
    fun activatingAParkedLayerUnparksItAtOnceWhenItsTilesAreResident() {
        val d = A8Kit.doc(2, made).also { docs += it }
        val (p, host) = A8Kit.policy(d)
        activate(d, 0)
        activate(d, 1)
        val l0 = d.layers[0]
        val ledger = A8Kit.tiles()
        val pixels = A8Kit.pixelsOf(l0)
        val generation = l0.pixelGeneration
        A8Kit.advance(2000)
        p.idleTick()
        A8Kit.drain()
        assertEquals(ResidencyState.PARKED, l0.residency)
        assertTrue("the park made tiles", A8Kit.tiles() > ledger)

        activate(d, 0)
        assertEquals("P1: the active layer is FLAT", ResidencyState.FLAT, l0.residency)
        assertTrue("no input was held", host.log.isEmpty())
        assertSamePixels("unparked", pixels, l0)
        assertEquals("J5", generation, l0.pixelGeneration)
        assertEquals("its tiles were freed", ledger, A8Kit.tiles())

        // The press-time unpark (section 2.7): FLAT before the index changes.
        activate(d, 1)
        A8Kit.advance(2000)
        p.idleTick()
        A8Kit.drain()
        assertEquals(ResidencyState.PARKED, l0.residency)
        assertTrue("prepare is done at once", p.prepare(l0))
        assertEquals(ResidencyState.FLAT, l0.residency)
        assertSame("the index did not move", d.layers[1], d.layers[d.activeLayerIndex])
        assertSamePixels("prepared", pixels, l0)
    }

    @Test
    fun activatingALayerWithPackedTilesHoldsInputUntilTheyAreRead() {
        val d = A8Kit.doc(2, made).also { docs += it }
        val (p, host) = A8Kit.policy(d, tileRam = { 0L })
        activate(d, 0)
        activate(d, 1)
        val l0 = d.layers[0]
        val pixels = A8Kit.pixelsOf(l0)
        val generation = l0.pixelGeneration
        A8Kit.advance(2000)
        p.idleTick()
        A8Kit.drain()
        p.idleTick()
        A8Kit.drain()
        val own = A8Kit.tilesOf(l0).filter { it.solid == null }
        assertTrue("layer 0 has tiles of its own", own.isNotEmpty())
        assertTrue("P3 packed them all: ${own.map { it.state }}", own.all { it.state == TileState.PACKED })

        activate(d, 0)
        A8Kit.drain()
        assertEquals("the hold, then its release once the layer is FLAT", listOf("hold PARKED", "release FLAT"), host.log)
        assertNull(host.activating)
        assertEquals(ResidencyState.FLAT, l0.residency)
        assertSamePixels("unparked from packed tiles", pixels, l0)
        assertEquals("J5", generation, l0.pixelGeneration)
    }

    @Test
    fun aSwitchAwayDuringTheReadsEndsTheHold() {
        val d = A8Kit.doc(3, made).also { docs += it }
        val (p, host) = A8Kit.policy(d, tileRam = { 0L })
        activate(d, 0)
        activate(d, 1)
        A8Kit.advance(2000)
        p.idleTick()
        A8Kit.drain()
        p.idleTick()
        A8Kit.drain()
        val l0 = d.layers[0]
        assertTrue(A8Kit.tilesOf(l0).any { it.state == TileState.PACKED })

        // The worker is held, so the reads are still queued when the user switches on.
        val gate = CountDownLatch(1)
        TileWorker.submit(TileWorker.Priority.READ_URGENT, { gate.await(10, TimeUnit.SECONDS) }) {}
        try {
            d.activeLayerIndex = 0
            A8Kit.idle()
            assertEquals(listOf("hold PARKED"), host.log)
            d.activeLayerIndex = 2
            A8Kit.idle()
        } finally {
            gate.countDown()
        }
        A8Kit.drain()
        assertEquals("the switch ended the hold", listOf("hold PARKED", "release PARKED"), host.log)
        assertNull(host.activating)
        assertEquals("the abandoned activation left it parked", ResidencyState.PARKED, l0.residency)
        assertEquals(ResidencyState.FLAT, d.layers[2].residency)
        assertEquals("its pin went with the reads", 0, l0.pins)
    }

    @Test
    fun blankAndFolderLayersNeverPark() {
        val d = Document("a8", "a8", 512, 300).also { docs += it }
        d.layers += Layer(d.newLayerId(), "Active", F1Pixels.mixed(512, 300, 3L).also { made += it })
        d.layers += Layer(d.newLayerId(), "Blank", BlankPixels(512, 300))
        d.layers += Layer(d.newLayerId(), "Folder", FolderPixels).apply { folder = FolderSpec() }
        val (p, _) = A8Kit.policy(d)
        activate(d, 0)
        val ledger = A8Kit.tiles()
        A8Kit.advance(2500)
        p.idleTick()
        p.pressure(MemoryPressure.CRITICAL)
        A8Kit.drain()
        assertEquals(ResidencyState.BLANK, d.layers[1].residency)
        assertEquals(ResidencyState.FOLDER, d.layers[2].residency)
        assertEquals(ResidencyState.FLAT, d.layers[0].residency)
        assertEquals(ledger, A8Kit.tiles())
    }

    @Test
    fun nothingParksWhileTheEditorIsBusy() {
        val d = A8Kit.doc(2, made).also { docs += it }
        val (p, host) = A8Kit.policy(d)
        activate(d, 0)
        activate(d, 1)
        val l0 = d.layers[0]
        val ledger = A8Kit.tiles()
        host.busy = true
        A8Kit.advance(2500)
        p.idleTick()
        p.pressure(MemoryPressure.LOW)
        assertFalse("no cut while a gesture is down", ParkCutter.isCutting(l0))

        host.busy = false
        p.idleTick()
        assertTrue(ParkCutter.isCutting(l0))
        host.busy = true // a gesture began during the cut
        A8Kit.drain()
        assertEquals(ResidencyState.FLAT, l0.residency)
        assertEquals("the cut's pieces were recycled", ledger, A8Kit.tiles())

        host.busy = false
        p.idleTick()
        A8Kit.drain()
        assertEquals("still due", ResidencyState.PARKED, l0.residency)
    }

    @Test
    fun aParkWhoseLayerChangedDuringTheCutIsDropped() {
        val d = A8Kit.doc(2, made).also { docs += it }
        val (p, _) = A8Kit.policy(d)
        activate(d, 0)
        activate(d, 1)
        val l0 = d.layers[0]
        val ledger = A8Kit.tiles()
        A8Kit.advance(2000)
        p.idleTick()
        assertTrue(ParkCutter.isCutting(l0))
        l0.markChanged()
        A8Kit.drain()
        assertEquals("an edit during the cut", ResidencyState.FLAT, l0.residency)
        assertEquals(ledger, A8Kit.tiles())

        p.idleTick()
        assertTrue(ParkCutter.isCutting(l0))
        val other = F1Pixels.noise(512, 300, 9L).also { made += it }
        l0.pixels = FlatPixels(other)
        A8Kit.drain()
        assertEquals("a new store during the cut", ResidencyState.FLAT, l0.residency)
        assertSame(other, (l0.pixels as FlatPixels).bitmap)
        assertEquals(ledger, A8Kit.tiles())

        val pixels = A8Kit.pixelsOf(l0)
        p.idleTick()
        A8Kit.drain()
        assertEquals(ResidencyState.PARKED, l0.residency)
        assertSamePixels("the new pixels parked", pixels, l0)
    }

    @Test
    fun lowPressureParksEveryEligibleLayerAtOnceColdestFirst() {
        val d = A8Kit.doc(4, made).also { docs += it }
        val (p, _) = A8Kit.policy(d, start = true)
        started = p
        val order = ArrayList<String>()
        p.onParked = { order += it.name }
        activate(d, 3)
        activate(d, 1)
        A8Kit.advance(100)
        activate(d, 2)
        A8Kit.advance(100)
        activate(d, 0)
        val entries = MemoryGovernor.entries().filter { it.name == "parked layers" }
        assertEquals("registered once", 1, entries.size)
        assertTrue("LOW reaches it", entries[0].priority < GovernorPolicyV19.PRIORITY_PROXIES)

        GovernorPolicyV19.onPressure(MemoryPressure.MODERATE, entries)
        A8Kit.drain()
        assertTrue("MODERATE parks nothing", order.isEmpty())

        GovernorPolicyV19.onPressure(MemoryPressure.LOW, entries)
        A8Kit.drain()
        assertEquals("coldest first, without waiting 2 s", listOf("Layer 3", "Layer 1", "Layer 2"), order)
        assertEquals(ResidencyState.FLAT, d.layers[0].residency)
        for (i in 1..3) assertEquals(ResidencyState.PARKED, d.layers[i].residency)
    }

    @Test
    fun criticalPressureParksEveryEligibleLayer() {
        val d = A8Kit.doc(3, made).also { docs += it }
        val (p, _) = A8Kit.policy(d)
        activate(d, 0)
        p.pressure(MemoryPressure.CRITICAL)
        A8Kit.drain()
        assertEquals(ResidencyState.FLAT, d.layers[0].residency)
        assertEquals(ResidencyState.PARKED, d.layers[1].residency)
        assertEquals(ResidencyState.PARKED, d.layers[2].residency)
    }

    @Test
    fun parkAndUnparkKeepThePixelsTheGenerationAndTheRender() {
        val d = A8Kit.doc(5, made).also { docs += it }
        val (p, _) = A8Kit.policy(d)
        activate(d, 0)
        val golden = F1Pixels.flatten(d).also { made += it }
        val generations = d.layers.map { it.pixelGeneration }
        val pixels = d.layers.map { A8Kit.pixelsOf(it) }

        p.pressure(MemoryPressure.LOW)
        A8Kit.drain()
        for (i in 1..4) assertEquals(ResidencyState.PARKED, d.layers[i].residency)
        val materialized = PixelFacade.materializations
        F1Pixels.assertBitEqual("the parked stack renders as the flat one", golden, F1Pixels.flatten(d).also { made += it })
        assertEquals("the render materialized nothing", materialized, PixelFacade.materializations)

        for (i in 1..4) activate(d, i)
        for (i in 0..4) {
            assertSamePixels("layer $i after its round", pixels[i], d.layers[i])
            assertEquals("J5, layer $i", generations[i], d.layers[i].pixelGeneration)
        }
        assertEquals(ResidencyState.FLAT, d.layers[4].residency)
        F1Pixels.assertBitEqual("after the unparks", golden, F1Pixels.flatten(d).also { made += it })
    }

    /** [n] undo-only tiles (held by a step alone): a noise cut whose store is released. */
    private fun undoTiles(seed: Long, n: Int = 2): List<Tile> {
        val b = F1Pixels.noise(256 * n, 256, seed).also { made += it }
        val s = TileOps.cut(b, detectBlank = true)
        val tiles = s.tiles.filterNotNull()
        for (t in tiles) t.refs++
        TileOps.releaseStore(s)
        return tiles
    }

    private fun step(history: UndoManager, layer: Layer?, tiles: List<Tile>): A8Kit.TileStep {
        val s = A8Kit.TileStep(layer, tiles, 16L shl 20)
        for (t in tiles) TileOps.release(t) // the step holds them alone now
        history.push(s)
        return s
    }

    @Test
    fun p3SendsUndoOnlyTilesOldestStepFirstThenColdParkedTiles() {
        val d = A8Kit.doc(3, made).also { docs += it }
        val history = UndoManager(1L shl 40).also { histories += it }
        var budget = Long.MAX_VALUE
        val (p, _) = A8Kit.policy(d, history, tileRam = { budget })
        activate(d, 0)
        p.pressure(MemoryPressure.LOW)
        A8Kit.drain()
        val l1 = d.layers[1]
        val shared = A8Kit.tilesOf(l1).first { it.solid == null }
        val t0 = undoTiles(1L)
        val t1 = undoTiles(2L)
        val t2 = undoTiles(3L)
        step(history, null, t0 + shared.also { it.refs++ })
        step(history, d.layers[0], t1)
        step(history, null, t2)
        step(history, null, undoTiles(4L))
        step(history, null, undoTiles(5L))
        // The newest 32 MiB (two steps of 16 MiB) and the active layer's step stay; a tile a store holds is not undo-only.
        assertEquals((t0 + t2).map { System.identityHashCode(it) }, p.undoOnlyTiles().map { System.identityHashCode(it) })

        val cold = p.coldParkedTiles()
        val parkedOwn = (A8Kit.tilesOf(l1) + A8Kit.tilesOf(d.layers[2])).filter { it.solid == null }.toSet()
        assertEquals(parkedOwn, cold.toSet())
        assertEquals("coldest first", cold.map { it.lastUse }.sorted(), cold.map { it.lastUse })

        val tileBytes = t0[0].bytes.toLong()
        budget = A8Kit.tiles() - 2 * tileBytes
        p.idleTick()
        A8Kit.drain()
        assertTrue("the oldest step's tiles left first", t0.all { it.state == TileState.PACKED })
        assertTrue(t2.all { it.state == TileState.RESIDENT })
        assertTrue("no parked tile left yet", parkedOwn.all { it.state == TileState.RESIDENT })

        budget = A8Kit.tiles() - 3 * tileBytes
        p.idleTick()
        A8Kit.drain()
        assertTrue("then the next step's", t2.all { it.state == TileState.PACKED })
        assertEquals("then one cold parked tile", 1, parkedOwn.count { it.state == TileState.PACKED })
        assertTrue("never the active layer's newest steps", t1.all { it.state == TileState.RESIDENT })
    }

    @Test
    fun aPinnedParkedLayerIsNotAColdTile() {
        val d = A8Kit.doc(3, made).also { docs += it }
        val (p, _) = A8Kit.policy(d)
        activate(d, 0)
        p.pressure(MemoryPressure.LOW)
        A8Kit.drain()
        val pin = d.layers[2].pin()
        try {
            val cold = p.coldParkedTiles().toSet()
            assertTrue(A8Kit.tilesOf(d.layers[2]).none { it in cold })
            assertTrue(A8Kit.tilesOf(d.layers[1]).filter { it.solid == null }.all { it in cold })
        } finally {
            pin.close()
        }
    }
}
