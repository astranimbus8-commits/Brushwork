package com.brushwork.paint.v110.a10

import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.budget.LedgerCategory
import com.brushwork.paint.engine.tiles.SlabSwapStore
import com.brushwork.paint.engine.tiles.Tile
import com.brushwork.paint.engine.tiles.TileOps
import com.brushwork.paint.engine.tiles.TileState
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeStorageProbe
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.v110.lead.F1Pixels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 A10 (design 2.0 PARKED row, 2.8 and 2.17, step 4): a phone layer (1080 x 2400) whose
 * painted tiles are all SWAPPED in a real [SlabSwapStore] unparks (`TiledPixels.materialize`: each
 * swapped tile read, its CRC checked and inflated). The layer is the mixed picture of the engine
 * tests (noise, transparent, solid and partly painted tiles: 30 of its 50 tiles swapped, the blank
 * ones held by none and the solid ones shared). The design's phone figure ("25-40 ms with swapped
 * tiles") is an estimate until the device checks, and it is bound by inflating the tiles: this PC
 * inflates only a few times faster than a phone, so the x 6 proxy of 40 ms (6.7 ms) lies under
 * the time the inflates alone take here. The guard is therefore the unpark against its floor, the
 * same layer unparked with every tile resident plus the plain inflate of the same 30 tiles: at
 * most [LIMIT] times it plus [SLACK_MS], so the store's reads (seek, read, CRC) add at most half.
 * Each is the median of [RUNS] runs after [WARM] warm-ups; the pixels come back bit for bit and
 * the tiles stay SWAPPED. The medians and the x 6 figure are printed for the measurements.
 */
@Category(Timing::class)
@RunWith(RobolectricTestRunner::class)
class UnparkSwappedTimingTest {
    @get:Rule
    val engine = EngineRule(level = EngineLevel.FULL, storage = FakeStorageProbe())

    @After
    fun cleanUp() = SwapKit.cleanUp()

    /** Main's commit of a written tile: its bitmap recycled and let go by the ledger. */
    private fun swapOut(store: SlabSwapStore, t: Tile) {
        store.write(t)
        synchronized(t.lock) {
            t.bitmap?.recycle()
            t.bitmap = null
            t.packed = null
        }
        EngineConfig.budget.ledger.sub(LedgerCategory.TILES, t.bytes.toLong())
        assertEquals(TileState.SWAPPED, t.state)
    }

    private fun median(v: List<Double>): Double = v.sorted()[v.size / 2]

    private fun ms(block: () -> Unit): Double {
        val t0 = System.nanoTime()
        block()
        return (System.nanoTime() - t0) / 1e6
    }

    private fun oneUnpark(s: TiledPixels): Double = ms { s.materialize().recycle() }

    /** The floor: [resident] unparked, then each of [packed] inflated (Deflate-1 bytes of [painted]'s tiles). */
    private fun oneFloor(resident: TiledPixels, packed: List<ByteArray>, painted: List<Tile>): Double = ms {
        resident.materialize().recycle()
        for (i in packed.indices) TileOps.inflate(packed[i], painted[i].w, painted[i].h).recycle()
    }

    @Test
    fun aPhoneLayerWithSwappedTilesUnparksAtMostHalfAgainOverItsInflates() {
        val src = F1Pixels.mixed(W, H, 7)
        val store = SwapKit.store()
        EngineConfig.swapStore = store
        val parked = TiledPixels.fromBitmap(src)
        val resident = TiledPixels.fromBitmap(src)
        val painted = (0 until parked.cols * parked.rows).mapNotNull { parked.tileAt(it % parked.cols, it / parked.cols) }
            .filter { it.solidColor == null }
        val packed = painted.map { TileOps.pack(requireNotNull(it.bitmap)) }
        painted.forEach { swapOut(store, it) }
        assertEquals("the noise and partly painted tiles", 30, painted.size)

        val (unpark, floor) = PerfBudget.measuredRatioWithin(LIMIT, tries = 3, slackMs = PerfBudget.ms(SLACK_MS), what = "unpark of a phone layer with swapped tiles against its inflates") {
            repeat(WARM) { oneUnpark(parked); oneFloor(resident, packed, painted) }
            val unparks = ArrayList<Double>(RUNS)
            val floors = ArrayList<Double>(RUNS)
            repeat(RUNS) {
                unparks += oneUnpark(parked)
                floors += oneFloor(resident, packed, painted)
            }
            median(unparks) to median(floors)
        }
        val back = parked.materialize()
        F1Pixels.assertBitEqual("the swapped tiles' pixels", src, back)
        back.recycle()
        assertTrue("still swapped", painted.all { it.state == TileState.SWAPPED })
        println(
            "v110 A10 UnparkSwappedTimingTest: unpark of a %dx%d layer with %d swapped tiles (%d MiB in swap): median %.2f ms of %d after %d warm-ups (x 6 = %.1f ms; device estimate 40 ms); resident unpark plus plain inflates %.2f ms (ratio %.2f, limit %.1f)"
                .format(W, H, painted.size, store.usedBytes shr 20, unpark, RUNS, WARM, unpark * 6, floor, unpark / floor, LIMIT),
        )
        TileOps.releaseStore(parked)
        TileOps.releaseStore(resident)
        src.recycle()
    }

    private companion object {
        const val W = 1080
        const val H = 2400

        /** The unpark is at most this many times its floor... */
        const val LIMIT = 1.5

        /** ...plus this (desktop ms, scaled on CI). */
        const val SLACK_MS = 2.0
        const val RUNS = 15
        const val WARM = 3
    }
}
