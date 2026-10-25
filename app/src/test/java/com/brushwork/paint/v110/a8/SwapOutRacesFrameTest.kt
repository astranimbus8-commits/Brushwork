package com.brushwork.paint.v110.a8

import android.graphics.Bitmap
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.MemoryPressure
import com.brushwork.paint.engine.tiles.PackedPool
import com.brushwork.paint.engine.tiles.ResidencyState
import com.brushwork.paint.engine.tiles.TileState
import com.brushwork.paint.engine.tiles.TileWorker
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeSwapStore
import com.brushwork.paint.v110.lead.F1Pixels
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

/**
 * v1.10 A8 step 2 (design 2.7 "The commit rule", I29, never cut): a real `tile-swapper` with a
 * [FakeSwapStore] at zero latency swaps tiles out continuously (P3 over a budget of 0) and reads
 * random ones back while 500 frames composite a parked stack on main. Every frame equals the flat
 * render (no lost tile, no recycled bitmap drawn: a recycled one throws on the canvas); the swap
 * really ran; at the end no slot is leaked, and once the stores go the TILES ledger, the packed
 * pool and the swap are back where they started.
 */
@RunWith(RobolectricTestRunner::class)
class SwapOutRacesFrameTest {
    private val swap = FakeSwapStore()

    @get:Rule
    val engine = EngineRule(EngineLevel.FULL, swap = swap)

    private val made = ArrayList<Bitmap>()
    private lateinit var baseline: A8Kit.Baseline

    @Before
    fun setUp() {
        baseline = A8Kit.Baseline()
    }

    @After
    fun tearDown() {
        baseline.assertBack(emptyList())
        made.forEach { if (!it.isRecycled) it.recycle() }
    }

    @Test
    fun fiveHundredFramesCompositeWhileTilesSwapOutAndIn() {
        val ledger = A8Kit.tiles()
        val pool = PackedPool.heldBytes
        val d = A8Kit.doc(4, made)
        val (p, _) = A8Kit.policy(d, tileRam = { 0L })
        d.activeLayerIndex = 0
        A8Kit.idle()
        val golden = F1Pixels.pixels(F1Pixels.flatten(d).also { made += it })
        p.pressure(MemoryPressure.LOW)
        A8Kit.drain()
        for (i in 1..3) assertEquals(ResidencyState.PARKED, d.layers[i].residency)
        val tiles = d.layers.flatMap { A8Kit.tilesOf(it) }.filter { it.solid == null }
        assertTrue(tiles.size >= 6)

        val rnd = Random(110)
        var swappedSeen = 0
        var residentAgain = 0
        repeat(500) { frame ->
            p.idleTick()
            for (t in tiles) {
                if (t.state != TileState.RESIDENT && rnd.nextInt(3) == 0 && TileWorker.readIn(t, TileWorker.Priority.READ_AHEAD)) residentAgain++
            }
            when (frame % 3) {
                0 -> A8Kit.drain()
                1 -> A8Kit.idle()
                else -> Unit // the commits land after the frame
            }
            swappedSeen += tiles.count { it.state == TileState.SWAPPED }
            val f = F1Pixels.flatten(d)
            try {
                assertArrayEquals("frame $frame", golden, F1Pixels.pixels(f))
            } finally {
                f.recycle()
            }
        }
        A8Kit.drain()
        assertTrue("tiles were swapped out during the frames", swappedSeen > 500)
        assertTrue("and read back", residentAgain > 100)
        assertTrue(swap.writes > 100)

        assertEquals("no slot is leaked", tiles.mapNotNull { it.slot }.toSet(), swap.liveSlots)
        for (t in tiles) assertTrue("every tile holds its pixels", t.bitmap != null || t.slot != null || t.packed != null)
        assertArrayEquals("after the race", golden, F1Pixels.pixels(F1Pixels.flatten(d).also { made += it }))

        // The editor closes: the stores go (the policy never ran on its own: it was not started).
        for (l in d.layers) l.recycleBitmaps()
        assertEquals("the TILES ledger is back", ledger, A8Kit.tiles())
        assertEquals("the packed pool is back", pool, PackedPool.heldBytes)
        assertTrue("every slot was freed", swap.liveSlots.isEmpty())
    }
}
