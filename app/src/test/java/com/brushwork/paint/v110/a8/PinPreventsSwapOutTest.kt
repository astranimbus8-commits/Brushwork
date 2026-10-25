package com.brushwork.paint.v110.a8

import android.graphics.Bitmap
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.tiles.ClassicResidency
import com.brushwork.paint.engine.tiles.ResidencyPolicy
import com.brushwork.paint.engine.tiles.ResidencyState
import com.brushwork.paint.engine.tiles.Tile
import com.brushwork.paint.engine.tiles.TileOps
import com.brushwork.paint.engine.tiles.TileState
import com.brushwork.paint.engine.tiles.TileWorker
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeSwapStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 A8 step 7 (design 2.0 P3, 2.2 "Pins", 2.7 the commit rule, I29; FULL against a
 * [FakeSwapStore]): an open `PixelPin` keeps a parked layer's tiles in RAM. Under tile-RAM
 * pressure (P3 with no tile RAM at all) a pinned layer's tiles stay RESIDENT while an unpinned
 * layer's go to the swap, and they go once the pin closes; the worker refuses to write or pack a
 * pinned tile; and a pin taken while a tile's write is in flight wins at the commit: the tile stays
 * RESIDENT and its written slot is freed.
 */
@RunWith(RobolectricTestRunner::class)
class PinPreventsSwapOutTest {
    private val swap = FakeSwapStore()

    @get:Rule
    val engine = EngineRule(EngineLevel.FULL, residency = ClassicResidency, swap = swap)

    private val made = ArrayList<Bitmap>()
    private val docs = ArrayList<Document>()
    private lateinit var baseline: A8Kit.Baseline

    @Before
    fun setUp() {
        baseline = A8Kit.Baseline()
    }

    @After
    fun tearDown() {
        (EngineConfig.residency as? ResidencyPolicy)?.stop()
        A8Kit.drain()
        baseline.assertBack(docs)
        made.forEach { if (!it.isRecycled) it.recycle() }
    }

    /** A document of 3 layers (layer 1 masked), layer 2 active, layers 0 and 1 parked by [p]'s own rule; tile RAM unlimited so far. */
    private fun parked(): Pair<Document, ResidencyPolicy> {
        val d = A8Kit.doc(3, made).also { docs += it }
        val (p, _) = A8Kit.policy(d)
        d.activeLayerIndex = 2
        A8Kit.idle()
        p.idleTick()
        A8Kit.advance(5000)
        p.idleTick()
        A8Kit.drain()
        assertEquals(ResidencyState.PARKED, d.layers[0].residency)
        assertEquals(ResidencyState.PARKED, d.layers[1].residency)
        return d to p
    }

    /** The tiles of [layer] that own pixels (a solid tile never leaves RAM). */
    private fun movable(layer: Layer): List<Tile> = A8Kit.tilesOf(layer).filter { it.solid == null }.also { assertTrue(it.isNotEmpty()) }

    private fun pressure(p: ResidencyPolicy) {
        repeat(3) {
            p.idleTick()
            A8Kit.drain()
        }
    }

    @Test
    fun aPinnedLayersTilesStayInRamUnderPressureUntilThePinCloses() {
        val (d, p) = parked()
        val pinned = d.layers[1]
        val free = d.layers[0]
        val pin = pinned.pin()
        p.tileRam = { 0L }
        pressure(p)
        assertTrue("pinned: resident ${movable(pinned).map { it.state }}", movable(pinned).all { it.state == TileState.RESIDENT })
        assertTrue("unpinned: swapped ${movable(free).map { it.state }}", movable(free).all { it.state == TileState.SWAPPED })

        pin.close()
        pressure(p)
        assertTrue("the pin closed: swapped ${movable(pinned).map { it.state }}", movable(pinned).all { it.state == TileState.SWAPPED })
        assertEquals(ResidencyState.PARKED, pinned.residency)
    }

    @Test
    fun theWorkerRefusesToWriteOrPackAPinnedTile() {
        val (d, _) = parked()
        val layer = d.layers[0]
        val t = movable(layer).first()
        val writes = swap.writes
        layer.pin().use {
            TileOps.nextEpoch()
            assertFalse("no write", TileWorker.writeOut(t))
            assertFalse("no pack", TileWorker.packOut(t))
            A8Kit.drain()
            assertEquals(TileState.RESIDENT, t.state)
        }
        assertEquals(writes, swap.writes)
    }

    @Test
    fun aPinTakenWhileAWriteIsInFlightWinsAtTheCommit() {
        val (d, _) = parked()
        val layer = d.layers[0]
        val t = movable(layer).first()
        val frees = swap.frees
        TileOps.nextEpoch()
        assertTrue("queued", TileWorker.writeOut(t))
        // The commit runs on main, after this pin.
        val pin = layer.pin()
        A8Kit.drain()
        assertEquals("kept resident", TileState.RESIDENT, t.state)
        assertNull("no slot", t.slot)
        assertTrue("the written slot was freed", swap.frees > frees)
        pin.close()
        assertEquals(TileState.RESIDENT, t.state)
    }
}
