package com.brushwork.paint.v110.a8

import android.graphics.Bitmap
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.MemoryPressure
import com.brushwork.paint.engine.tiles.ParkCutter
import com.brushwork.paint.engine.tiles.ResidencyState
import com.brushwork.paint.engine.tiles.SolidTiles
import com.brushwork.paint.model.Document
import com.brushwork.paint.testing.EngineRule
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 A8 step 2 (design 2.0 P2, 2.2, I29): an open `PixelPin` forbids park. A pinned layer is
 * never parked, neither by the 2 s rule nor under pressure, and parks at the next tick once the
 * pin closes; a pin taken while the worker cuts the layer drops the cut on main (the layer stays
 * FLAT, the pieces and their solid colors go back); a parked layer's pin keeps it parked.
 */
@RunWith(RobolectricTestRunner::class)
class PinPreventsParkTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.TILES)

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

    private fun activate(d: Document, i: Int) {
        d.activeLayerIndex = i
        A8Kit.idle()
    }

    @Test
    fun aPinnedLayerNeverParksUntilThePinCloses() {
        val d = A8Kit.doc(2, made).also { docs += it }
        val (p, _) = A8Kit.policy(d)
        activate(d, 0)
        activate(d, 1)
        val l0 = d.layers[0]
        val pin = l0.pin()
        A8Kit.advance(5000)
        p.idleTick()
        p.pressure(MemoryPressure.CRITICAL)
        assertFalse(ParkCutter.isCutting(l0))
        A8Kit.drain()
        assertEquals(ResidencyState.FLAT, l0.residency)

        pin.close()
        p.idleTick()
        A8Kit.drain()
        assertEquals("due since long ago", ResidencyState.PARKED, l0.residency)
    }

    @Test
    fun aPinTakenDuringTheCutDropsThePark() {
        val d = A8Kit.doc(2, made).also { docs += it }
        val (p, _) = A8Kit.policy(d)
        activate(d, 0)
        activate(d, 1)
        val l0 = d.layers[0]
        val ledger = A8Kit.tiles()
        val colors = SolidTiles.colors()
        A8Kit.advance(2000)
        p.idleTick()
        assertTrue(ParkCutter.isCutting(l0))
        val pin = l0.pin()
        A8Kit.drain()
        assertEquals("the pin won", ResidencyState.FLAT, l0.residency)
        assertEquals("the pieces were recycled", ledger, A8Kit.tiles())
        assertEquals("no solid color is left over", colors, SolidTiles.colors())
        assertEquals("only the test's pin is open", 1, l0.pins)

        p.idleTick()
        A8Kit.drain()
        assertEquals("still pinned", ResidencyState.FLAT, l0.residency)
        pin.close()
        p.idleTick()
        A8Kit.drain()
        assertEquals(ResidencyState.PARKED, l0.residency)
    }

    @Test
    fun aPinnedParkedLayerStaysParked() {
        val d = A8Kit.doc(3, made).also { docs += it }
        val (p, _) = A8Kit.policy(d)
        activate(d, 0)
        p.pressure(MemoryPressure.LOW)
        A8Kit.drain()
        val l1 = d.layers[1]
        assertEquals(ResidencyState.PARKED, l1.residency)
        val pin = l1.pin()
        try {
            A8Kit.advance(3000)
            p.idleTick()
            A8Kit.drain()
            assertEquals("a plain pin keeps what the store holds", ResidencyState.PARKED, l1.residency)
            assertTrue("its tiles are pinned", A8Kit.tilesOf(l1).all { it.pins > 0 })
        } finally {
            pin.close()
        }
        assertTrue(A8Kit.tilesOf(l1).all { it.pins == 0 })
    }
}
