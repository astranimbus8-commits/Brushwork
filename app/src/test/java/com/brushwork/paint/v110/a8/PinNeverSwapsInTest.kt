package com.brushwork.paint.v110.a8

import android.graphics.Bitmap
import android.os.Looper
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.MemoryPressure
import com.brushwork.paint.engine.tiles.ResidencyPolicy
import com.brushwork.paint.engine.tiles.ResidencyState
import com.brushwork.paint.engine.tiles.Tile
import com.brushwork.paint.engine.tiles.TileState
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeSwapStore
import com.brushwork.paint.v110.lead.F1Pixels
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 A8 step 2 (design 2.2, I29, FULL with [FakeSwapStore]): a plain `pin()` keeps nothing
 * resident and never swaps in. Pinning a parked layer whose tiles are SWAPPED reads nothing, adds
 * nothing to the TILES ledger and leaves every tile SWAPPED, through ticks over budget too; an
 * off-main reader under that pin reads each tile through `bitmapForRead()` into private scratch
 * (the right pixels, on its own thread) and the tile stays SWAPPED. A resident pin
 * (`pinResident()`) is the one that swaps every tile in.
 */
@RunWith(RobolectricTestRunner::class)
class PinNeverSwapsInTest {
    private val swap = FakeSwapStore()

    @get:Rule
    val engine = EngineRule(EngineLevel.FULL, swap = swap)

    private val made = ArrayList<Bitmap>()
    private val docs = ArrayList<Document>()
    private lateinit var baseline: A8Kit.Baseline

    @Before
    fun setUp() {
        baseline = A8Kit.Baseline()
    }

    @After
    fun tearDown() {
        baseline.assertBack(docs)
        made.forEach { if (!it.isRecycled) it.recycle() }
    }

    private class Swapped(val doc: Document, val policy: ResidencyPolicy, val layer: Layer, val own: List<Tile>, val pixels: List<IntArray>)

    /** A document whose layer 1 is parked with every tile of its own SWAPPED; its tiles' pixels as they were resident. */
    private fun swapped(): Swapped {
        val d = A8Kit.doc(3, made).also { docs += it }
        val (p, _) = A8Kit.policy(d, tileRam = { 0L })
        d.activeLayerIndex = 0
        A8Kit.idle()
        p.pressure(MemoryPressure.LOW)
        A8Kit.drain()
        val l1 = d.layers[1]
        assertEquals(ResidencyState.PARKED, l1.residency)
        val own = A8Kit.tilesOf(l1).filter { it.solid == null }
        assertTrue(own.isNotEmpty())
        val pixels = own.map { F1Pixels.pixels(it.bitmap!!) }
        p.idleTick()
        A8Kit.drain()
        assertTrue("P3 swapped them out: ${own.map { it.state }}", own.all { it.state == TileState.SWAPPED })
        assertTrue(swap.writes >= own.size)
        return Swapped(d, p, l1, own, pixels)
    }

    @Test
    fun aPlainPinOfASwappedLayerReadsNothing() {
        val s = swapped()
        val reads = swap.reads
        val ledger = A8Kit.tiles()
        val pin = s.layer.pin()
        try {
            A8Kit.drain()
            assertTrue("its tiles are pinned", s.own.all { it.pins == 1 })
            A8Kit.advance(3000)
            s.policy.idleTick()
            A8Kit.drain()
            assertEquals("nothing was read", reads, swap.reads)
            assertEquals("nothing came into RAM", ledger, A8Kit.tiles())
            assertTrue(s.own.all { it.state == TileState.SWAPPED })
            assertEquals(ResidencyState.PARKED, s.layer.residency)
        } finally {
            pin.close()
        }
        A8Kit.drain()
        assertEquals(reads, swap.reads)
        assertTrue(s.own.all { it.state == TileState.SWAPPED && it.pins == 0 })
    }

    @Test
    fun anOffMainReaderUnderAPinReadsIntoScratchAndTheTileStaysSwapped() {
        val s = swapped()
        val reads = swap.reads
        val ledger = A8Kit.tiles()
        val pin = s.layer.pin()
        val seen = arrayOfNulls<IntArray>(s.own.size)
        var failure: Throwable? = null
        try {
            val reader = Thread({
                try {
                    s.own.forEachIndexed { i, t ->
                        val scratch = t.bitmapForRead()
                        try {
                            seen[i] = F1Pixels.pixels(scratch)
                        } finally {
                            scratch.recycle()
                        }
                    }
                } catch (e: Throwable) {
                    failure = e
                }
            }, "a8-reader")
            reader.start()
            reader.join(10_000)
            assertFalse("the reader ended", reader.isAlive)
            assertNull(failure)
            assertEquals("one read per tile", reads + s.own.size, swap.reads)
            val threads = swap.readThreads.takeLast(s.own.size)
            assertTrue("on the reader's thread: $threads", threads.all { it == "a8-reader" })
            assertFalse(threads.contains(Looper.getMainLooper().thread.name))
            s.own.indices.forEach { assertArrayEquals("tile $it", s.pixels[it], seen[it]) }
            assertTrue("never swapped in", s.own.all { it.state == TileState.SWAPPED })
            assertEquals(ledger, A8Kit.tiles())
        } finally {
            pin.close()
        }
    }

    @Test
    fun aResidentPinSwapsEveryTileIn() {
        val s = swapped()
        val reads = swap.reads
        val ledger = A8Kit.tiles()
        val slots = s.own.map { it.slot!! }
        val pin = s.layer.pinResident()
        try {
            assertTrue(s.own.all { it.state == TileState.RESIDENT })
            assertEquals(reads + s.own.size, swap.reads)
            assertTrue("their slots were freed", slots.none { it in swap.liveSlots })
            assertEquals(ledger + s.own.sumOf { it.bytes.toLong() }, A8Kit.tiles())
            s.own.indices.forEach { assertArrayEquals("tile $it", s.pixels[it], F1Pixels.pixels(s.own[it].bitmap!!)) }
        } finally {
            pin.close()
        }
    }
}
