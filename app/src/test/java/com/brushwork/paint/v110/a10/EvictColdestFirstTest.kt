package com.brushwork.paint.v110.a10

import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.NativeWatch
import com.brushwork.paint.engine.tiles.ResidencyState
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.testing.EngineRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * v1.10 A10 (design 2.0 P3 order 1, step 4): eviction takes the coldest layers first, by the epoch
 * of their last read (the newest `lastUse` of their tiles), skips every layer read in the last
 * [NativeWatch.EVICT_MIN_AGE_EPOCHS] epochs (8 epochs ago is old enough, 7 is not), and stops as
 * soon as the bytes asked for are freed.
 */
@RunWith(RobolectricTestRunner::class)
class EvictColdestFirstTest {
    @get:Rule
    val engine = EngineRule(level = EngineLevel.TILES)

    private val dir get() = File(RuntimeEnvironment.getApplication().filesDir, "projects/coldest").apply { mkdirs() }
    private val now = 100L

    /** Five saved, parked layers last read at [epochs] (in the list's order, ids 1..5). */
    private fun candidates(vararg epochs: Long): List<NativeWatch.Candidate> = epochs.mapIndexed { i, epoch ->
        val (layer, ref) = EvictKit.savedParkedLayer(dir, i + 1L, EvictKit.picture(256, 256, 40L + i))
        EvictKit.readAt(layer, epoch)
        NativeWatch.Candidate(layer, ref)
    }

    @Test
    fun theOrderIsColdestFirstAndSkipsTheWarm() {
        val list = candidates(60, 10, 93, 92, 40)
        assertEquals("the read epoch", 60L, NativeWatch.lastRead(list[0].layer.pixels as TiledPixels))
        val order = NativeWatch.evictionOrder(list, now).map { it.layer.id }
        assertEquals("coldest first; 7 epochs ago is warm, 8 is not", listOf(2L, 5L, 1L, 4L), order)
    }

    @Test
    fun evictionStopsOnceTheTargetIsFreed() {
        val list = candidates(60, 10, 93, 92, 40)
        val one = (list[1].layer.pixels as TiledPixels).residentBytes()
        assertTrue("a layer's tiles", one > 0)
        val freed = NativeWatch.evictColdest(list, now, targetBytes = one + 1)
        assertTrue("two layers' tiles: $freed", freed > one)
        val evicted = list.filter { it.layer.residency == ResidencyState.EVICTED }.map { it.layer.id }
        assertEquals("the two coldest only", listOf(2L, 5L), evicted)
        assertEquals("the warm one stays", ResidencyState.PARKED, list[2].layer.residency)

        val rest = NativeWatch.evictColdest(list, now, targetBytes = Long.MAX_VALUE)
        assertTrue("the rest", rest > 0)
        assertEquals(
            "every evictable layer, never the warm one",
            listOf(ResidencyState.EVICTED, ResidencyState.EVICTED, ResidencyState.PARKED, ResidencyState.EVICTED, ResidencyState.EVICTED),
            list.map { it.layer.residency },
        )
        assertEquals("nothing left to evict", 0L, NativeWatch.evictColdest(list, now, Long.MAX_VALUE))
    }

    @Test
    fun aLayerNeverReadIsTheColdest() {
        val list = candidates(50, 0)
        assertEquals(0L, NativeWatch.lastRead(list[1].layer.pixels as TiledPixels))
        assertEquals(listOf(2L, 1L), NativeWatch.evictionOrder(list, now).map { it.layer.id })
        assertEquals("none at epoch 7", emptyList<Long>(), NativeWatch.evictionOrder(list, 7L).map { it.layer.id })
    }
}
