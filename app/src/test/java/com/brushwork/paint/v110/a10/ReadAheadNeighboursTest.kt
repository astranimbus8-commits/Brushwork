package com.brushwork.paint.v110.a10

import android.graphics.Bitmap
import android.os.Looper
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.MemoryGovernor
import com.brushwork.paint.engine.MemoryPressure
import com.brushwork.paint.engine.UndoManager
import com.brushwork.paint.engine.budget.LedgerCategory
import com.brushwork.paint.engine.tiles.ClassicResidency
import com.brushwork.paint.engine.tiles.FolderPixels
import com.brushwork.paint.engine.tiles.NoSwap
import com.brushwork.paint.engine.tiles.PackedPool
import com.brushwork.paint.engine.tiles.ResidencyPolicy
import com.brushwork.paint.engine.tiles.ResidencyState
import com.brushwork.paint.engine.tiles.SlabSwapStore
import com.brushwork.paint.engine.tiles.SwapReadAhead
import com.brushwork.paint.engine.tiles.Tile
import com.brushwork.paint.engine.tiles.TileOps
import com.brushwork.paint.engine.tiles.TileState
import com.brushwork.paint.engine.tiles.TileWorker
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.FolderSpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeStorageProbe
import com.brushwork.paint.v110.lead.F1Pixels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.RandomAccessFile
import java.time.Duration
import java.util.Locale

/**
 * v1.10 A10 step 8 (cut 1; design 2.7, 2.8 "reads ... ahead", Q5): [SwapReadAhead] reads ahead, on
 * A8's `TileWorker`, the SWAPPED tiles of the two layers next to the active one (the nearest pixel
 * layer above and below, folders skipped; content and mask) once the editor has been idle for
 * 300 ms. Each copy is the slot's packed bytes kept beside the slot (the tile stays SWAPPED), in
 * the packed pool and at most its share of it; the activation then inflates the copies with no read
 * of the swap file. The copies of former neighbours, a memory trim and the stop drop them; a pass
 * waits while the editor is busy or the resident tiles are over budget; a tile read in or let go
 * of meanwhile, a failed read or another kind of swap store keeps nothing. Every test ends with the
 * TILES ledger and the packed pool where they started.
 *
 * The documents: 512 x 512 layers of the engine tests' mixed picture (a noise tile and a
 * translucent one painted, one transparent, one solid), parked as tiled stores whose painted tiles
 * are SWAPPED in a real [SlabSwapStore].
 */
@RunWith(RobolectricTestRunner::class)
class ReadAheadNeighboursTest {
    @get:Rule
    val engine = EngineRule(level = EngineLevel.FULL, storage = FakeStorageProbe(), residency = ClassicResidency)

    private lateinit var store: SlabSwapStore
    private val made = ArrayList<Bitmap>()
    private val docs = ArrayList<Document>()
    private val readAheads = ArrayList<SwapReadAhead>()
    private var tilesBefore = 0L
    private var poolBefore = 0L

    /** The editor as the read-ahead sees it. */
    private class Host(override val document: Document) : SwapReadAhead.Host {
        override var idle = true
    }

    @Before
    fun setUp() {
        store = SwapKit.store()
        EngineConfig.swapStore = store
        tilesBefore = EngineConfig.budget.ledger.of(LedgerCategory.TILES)
        poolBefore = PackedPool.heldBytes
    }

    @After
    fun tearDown() {
        readAheads.forEach { it.stop() }
        drain()
        EngineConfig.swapStore = store
        for (d in docs) for (l in d.layers) l.recycleBitmaps()
        drain()
        assertEquals("the TILES ledger is back", tilesBefore, EngineConfig.budget.ledger.of(LedgerCategory.TILES))
        assertEquals("the packed pool is back", poolBefore, PackedPool.heldBytes)
        made.forEach { if (!it.isRecycled) it.recycle() }
        SwapKit.cleanUp()
    }

    // ---------------------------------------------------------------- helpers

    private fun drain(timeoutMs: Long = 10_000L) {
        val looper = shadowOf(Looper.getMainLooper())
        val until = System.currentTimeMillis() + timeoutMs
        while (true) {
            looper.idle()
            if (TileWorker.isIdle) return
            if (System.currentTimeMillis() > until) fail("the tile-swapper still has ${TileWorker.pendingJobs} jobs after $timeoutMs ms")
            Thread.sleep(1)
        }
    }

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun picture(seed: Long): Bitmap = F1Pixels.mixed(SIDE, SIDE, seed).also { made += it }

    /** A parked layer of [seed]'s picture (and a mask with [mask]), its painted tiles SWAPPED when [swapped]. */
    private fun parked(doc: Document, seed: Long, swapped: Boolean = true, mask: Boolean = false): Layer {
        val l = Layer(doc.newLayerId(), "Layer $seed", TiledPixels.fromBitmap(picture(seed)))
        if (mask) l.maskPixels = TiledPixels.fromBitmap(F1Pixels.mixedMask(SIDE, SIDE, seed + 100).also { made += it })
        if (swapped) swapOut(l)
        return l
    }

    /** Writes every painted resident tile of [l] and commits it as main does after the worker's write. */
    private fun swapOut(l: Layer) {
        for (s in storesOf(l)) for (t in s.tiles) {
            if (t == null || t.solid != null || t.bitmap == null) continue
            store.write(t)
            val b = synchronized(t.lock) { t.bitmap.also { t.bitmap = null; t.packed = null } }
            b?.recycle()
            EngineConfig.budget.ledger.sub(LedgerCategory.TILES, t.bytes.toLong())
            assertEquals(TileState.SWAPPED, t.state)
        }
    }

    private fun storesOf(l: Layer): List<TiledPixels> = listOfNotNull(l.pixels as? TiledPixels, l.maskPixels as? TiledPixels)

    private fun painted(l: Layer): List<Tile> = storesOf(l).flatMap { s -> s.tiles.filterNotNull().filter { it.solid == null } }

    private fun copied(t: Tile): Boolean = synchronized(t.lock) { t.packed != null && t.slot != null && t.bitmap == null }

    private fun copies(l: Layer): Int = painted(l).count { copied(it) }

    /** Layers: 0 far below, 1 next below, 2 active (flat), 3 next above (with a mask), 4 far above. */
    private fun fiveLayers(): Document {
        val doc = Document("ra", "ra", SIDE, SIDE).also { docs += it }
        doc.layers += parked(doc, 10)
        doc.layers += parked(doc, 11)
        doc.layers += Layer(doc.newLayerId(), "Active", picture(12).copy(Bitmap.Config.ARGB_8888, true).also { made += it })
        doc.layers += parked(doc, 13, mask = true)
        doc.layers += parked(doc, 14)
        doc.activeLayerIndex = 2
        return doc
    }

    private fun readAhead(
        host: SwapReadAhead.Host,
        tileRam: () -> Long = { Long.MAX_VALUE },
        shareCap: () -> Long = { PackedPool.cap() / 2 },
    ): SwapReadAhead = SwapReadAhead(host, tileRam, shareCap).also { readAheads += it }

    private fun slotBytes(tiles: List<Tile>): Long = tiles.sumOf { synchronized(it.lock) { it.slot!!.length.toLong() } }

    // ---------------------------------------------------------------- tests

    @Test
    fun theSwappedTilesOfBothNeighboursAreReadAfter300MsIdle() {
        val doc = fiveLayers()
        val (far0, below, _, above, far4) = doc.layers
        val before = painted(below).associateWith { F1Pixels.pixels(it.bitmapForRead().also { b -> made += b }) }
        val ra = readAhead(Host(doc))
        ra.changed()
        advance(SwapReadAhead.IDLE_MS - 1)
        assertTrue("nothing is queued before 300 ms", TileWorker.isIdle)
        assertEquals(0, copies(below) + copies(above))

        advance(1)
        drain()
        val near = painted(below) + painted(above)
        assertEquals("both neighbours' painted tiles (content and mask) are copied", near.size, near.count { copied(it) })
        val maskTiles = storesOf(above)[1].tiles.filterNotNull().filter { it.solid == null }
        assertTrue("the mask has painted tiles, all copied", maskTiles.isNotEmpty() && maskTiles.all { copied(it) })
        assertTrue("they stay SWAPPED", near.all { it.state == TileState.SWAPPED })
        assertEquals("the far layers keep nothing", 0, copies(far0) + copies(far4))
        val bytes = slotBytes(near)
        assertEquals("the copies are the slots' bytes", bytes, ra.heldBytes)
        assertEquals("counted in the packed pool", poolBefore + bytes, PackedPool.heldBytes)
        for ((t, px) in before) {
            val b = t.bitmapForRead().also { made += it }
            assertTrue("a copy inflates to the tile's pixels", px.contentEquals(F1Pixels.pixels(b)))
        }
    }

    @Test
    fun foldersAreSkippedAndANeighbourIsThePixelLayerBeyond() {
        val doc = Document("ra", "ra", SIDE, SIDE).also { docs += it }
        val below = parked(doc, 20)
        doc.layers += below
        doc.layers += Layer(doc.newLayerId(), "Folder", FolderPixels).apply { folder = FolderSpec() }
        doc.layers += Layer(doc.newLayerId(), "Active", picture(21).copy(Bitmap.Config.ARGB_8888, true).also { made += it })
        doc.activeLayerIndex = 2
        readAhead(Host(doc)).changed()
        advance(SwapReadAhead.IDLE_MS)
        drain()
        assertEquals("the layer under the folder is the neighbour below", painted(below).size, copies(below))
    }

    @Test
    fun aPassWaitsWhileTheEditorIsBusyOrTheResidentTilesAreOverBudget() {
        val doc = fiveLayers()
        val below = doc.layers[1]
        val host = Host(doc).apply { idle = false }
        var ram = 0L
        doc.layers += parked(doc, 15, swapped = false)
        val ra = readAhead(host, tileRam = { ram })
        ra.changed()
        advance(1_000)
        drain()
        assertEquals("busy: nothing", 0, copies(below))
        host.idle = true
        advance(1_000)
        drain()
        assertEquals("over the tile budget: nothing", 0, copies(below))
        ram = Long.MAX_VALUE
        advance(SwapReadAhead.IDLE_MS)
        drain()
        assertEquals("idle and under budget: read", painted(below).size, copies(below))
    }

    @Test
    fun aRecheckReadsTilesSwappedOutSince() {
        val doc = Document("ra", "ra", SIDE, SIDE).also { docs += it }
        val below = parked(doc, 30, swapped = false)
        doc.layers += below
        doc.layers += Layer(doc.newLayerId(), "Active", picture(31).copy(Bitmap.Config.ARGB_8888, true).also { made += it })
        doc.activeLayerIndex = 1
        readAhead(Host(doc)).changed()
        advance(SwapReadAhead.IDLE_MS)
        drain()
        assertEquals("resident: nothing to read", 0, copies(below))
        swapOut(below)
        advance(SwapReadAhead.RECHECK_MS)
        drain()
        assertEquals("swapped since: read at the next look", painted(below).size, copies(below))
    }

    @Test
    fun aSwitchDropsTheFormerNeighboursCopiesAndReadsTheNewOnes() {
        val doc = fiveLayers()
        val below = doc.layers[1]
        val above = doc.layers[3]
        val far4 = doc.layers[4]
        val ra = readAhead(Host(doc))
        ra.changed()
        advance(SwapReadAhead.IDLE_MS)
        drain()
        assertTrue(copies(below) > 0 && copies(above) > 0)

        doc.activeLayerIndex = 3
        ra.changed()
        advance(SwapReadAhead.IDLE_MS)
        drain()
        assertEquals("the former neighbour below lets go", 0, copies(below))
        assertEquals("the new active layer's tiles are no neighbour's", 0, copies(above))
        assertEquals("the new neighbour above is read", painted(far4).size, copies(far4))
        assertTrue("dropped copies keep their slots", painted(below).all { it.state == TileState.SWAPPED && synchronized(it.lock) { it.packed == null } })
        assertEquals(slotBytes(painted(far4)), ra.heldBytes)
        assertEquals("the pool holds the new copies only", poolBefore + ra.heldBytes, PackedPool.heldBytes)
    }

    @Test
    fun theActivationInflatesTheCopiesWithoutReadingTheSwapFile() {
        val doc = fiveLayers()
        val above = doc.layers[3]
        val content = picture(13)
        val mask = F1Pixels.mixedMask(SIDE, SIDE, 113).also { made += it }
        readAhead(Host(doc)).changed()
        advance(SwapReadAhead.IDLE_MS)
        drain()
        assertEquals(painted(above).size, copies(above))

        val host = object : ResidencyPolicy.Host {
            override val document = doc
            override val busy = false
            override var activating: Layer? = null
            override val history: UndoManager? = null
        }
        ResidencyPolicy.install(ResidencyPolicy(host, { Long.MAX_VALUE }), start = false)
        // With the swap file closed, any read of a slot fails: only the copies can serve.
        store.close()
        val policy = EngineConfig.residency as ResidencyPolicy
        assertFalse("tiles not resident: asynchronous", policy.prepare(above))
        assertSame(above, host.activating)
        drain()
        assertNull("the hold ended", host.activating)
        assertEquals(ResidencyState.FLAT, above.residency)
        F1Pixels.assertBitEqual("the content from the copies", content, above.bitmap)
        F1Pixels.assertBitEqual("the mask from the copies", mask, above.mask!!)
    }

    @Test
    fun theCopiesStayWithinTheirShareOfThePackedPool() {
        val doc = fiveLayers()
        val near = painted(doc.layers[1]) + painted(doc.layers[3])
        val biggest = near.maxOf { synchronized(it.lock) { it.slot!!.length.toLong() } }
        val cap = biggest + 1
        val ra = readAhead(Host(doc), shareCap = { cap })
        ra.changed()
        advance(SwapReadAhead.IDLE_MS)
        drain()
        assertTrue("something was read", ra.copiedTiles > 0)
        assertTrue("not everything fits", ra.copiedTiles < near.size)
        assertTrue("within the share (${ra.heldBytes} of $cap)", ra.heldBytes <= cap)
        assertEquals(poolBefore + ra.heldBytes, PackedPool.heldBytes)
        assertTrue("the default share is half the pool's cap", PackedPool.cap() / 2 > 0)
    }

    @Test
    fun lowPressureDropsEveryCopyAndQuietsTheReadAheadAndStopDropsTheRest() {
        val doc = fiveLayers()
        val near = painted(doc.layers[1]) + painted(doc.layers[3])
        val ra = readAhead(Host(doc))
        ra.changed()
        advance(SwapReadAhead.IDLE_MS)
        drain()
        assertEquals(near.size, ra.copiedTiles)
        val entry = MemoryGovernor.entries().single { it.name == "read-ahead tiles" }
        assertEquals("the display caches' priority", 0, entry.priority)
        assertEquals(ra.heldBytes, entry.bytes())
        assertEquals("MODERATE keeps them", 0L, entry.trimmable.trim(MemoryPressure.MODERATE))
        assertEquals(near.size, ra.copiedTiles)

        val held = ra.heldBytes
        assertEquals("LOW drops every copy", held, entry.trimmable.trim(MemoryPressure.LOW))
        assertEquals(0, ra.copiedTiles)
        assertEquals(poolBefore, PackedPool.heldBytes)
        assertTrue("the slots stay", near.all { it.state == TileState.SWAPPED })
        advance(SwapReadAhead.RECHECK_MS + SwapReadAhead.IDLE_MS)
        drain()
        assertEquals("quiet after a trim", 0, ra.copiedTiles)
        advance(SwapReadAhead.QUIET_MS)
        drain()
        assertEquals("read again once the quiet ends", near.size, ra.copiedTiles)

        ra.stop()
        assertEquals(0, ra.copiedTiles)
        assertEquals(poolBefore, PackedPool.heldBytes)
        assertTrue("the entry goes with the stop", MemoryGovernor.entries().none { it.name == "read-ahead tiles" })
        advance(SwapReadAhead.RECHECK_MS)
        drain()
        assertEquals("nothing after the stop", 0, copies(doc.layers[1]))
    }

    @Test
    fun aTileReadInOrLetGoOfWhileItsReadIsInFlightKeepsNothing() {
        val doc = fiveLayers()
        val below = doc.layers[1]
        val above = doc.layers[3]
        val belowTiles = painted(below)
        val ra = readAhead(Host(doc))
        ra.changed()
        advance(SwapReadAhead.IDLE_MS)
        // The reads are queued (their results wait on main): the layer below is let go of and the
        // one above is read in on main meanwhile.
        for (s in storesOf(below)) TileOps.releaseStore(s)
        for (t in painted(above)) TileOps.makeResident(t)
        drain()
        assertEquals(0, ra.copiedTiles)
        assertEquals(0L, ra.heldBytes)
        assertEquals(poolBefore, PackedPool.heldBytes)
        assertTrue("let go of: freed, slot and all", belowTiles.all { synchronized(it.lock) { it.slot == null && it.packed == null && it.bitmap == null } })
        assertTrue("read in: resident", painted(above).all { it.state == TileState.RESIDENT && synchronized(it.lock) { it.packed == null } })
    }

    @Test
    fun aSlotThatFailsItsCrcKeepsNothingAndThrowsNothing() {
        val doc = fiveLayers()
        val below = doc.layers[1]
        val bad = painted(below).maxBy { synchronized(it.lock) { it.slot!!.length } }
        val slot = synchronized(bad.lock) { bad.slot!! }
        RandomAccessFile(File(store.dir, String.format(Locale.US, "slab-%03d.bin", slot.slab)), "rw").use { f ->
            f.seek(slot.offset + slot.length / 2)
            val b = f.read()
            f.seek(slot.offset + slot.length / 2)
            f.write(b xor 0xFF)
        }
        val ra = readAhead(Host(doc))
        ra.changed()
        advance(SwapReadAhead.IDLE_MS)
        drain()
        assertFalse("the bad slot is not copied", copied(bad))
        assertEquals("still SWAPPED", TileState.SWAPPED, bad.state)
        assertEquals("the others are", painted(below).size - 1 + painted(doc.layers[3]).size, ra.copiedTiles)
    }

    @Test
    fun withAnotherSwapStoreNothingIsRead() {
        val doc = fiveLayers()
        EngineConfig.swapStore = NoSwap
        val ra = readAhead(Host(doc))
        ra.changed()
        advance(SwapReadAhead.IDLE_MS)
        assertTrue("nothing queued", TileWorker.isIdle)
        assertEquals(0, ra.copiedTiles)
        EngineConfig.swapStore = store
        advance(SwapReadAhead.RECHECK_MS)
        drain()
        assertEquals("no look again without a change", 0, ra.copiedTiles)
    }

    private companion object {
        const val SIDE = 512
    }
}
