package com.brushwork.paint.v110.a10

import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.check.AutoCheckRunner
import com.brushwork.paint.engine.check.CheckStore
import com.brushwork.paint.engine.tiles.SlabSwapStore
import com.brushwork.paint.engine.tiles.Tile
import com.brushwork.paint.storage.engine.CheckReasons
import com.brushwork.paint.storage.engine.EngineStateStore
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeExitInfo
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.FakeStorageProbe
import com.brushwork.paint.testing.MemoryPreset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

/**
 * v1.10 A10 (design 2.10 "slow swap reads in use", step 4): every swap read is timed; once the
 * last [SlabSwapStore.SLOW_WINDOW] (50) reads have a p95 (the 48th of 50) above
 * [SlabSwapStore.SLOW_READ_MS] (40 ms), the store turns [SlabSwapStore.slowReads] on and tells
 * its owner once. Two slow reads in 50 do not trip it, three do; exactly 40 ms is not slow; fewer
 * than 50 reads never trip it; the window slides. The installed store's owner records
 * `slowSwapReads`: the stored FULL result then runs at TILES ([CheckReasons.SLOW_STORAGE]) while
 * the file keeps FULL, until the next check, which clears it.
 */
@RunWith(RobolectricTestRunner::class)
class SwapSlowReadsTest {
    private val memory = FakeMemoryProbe(MemoryPreset.T606)

    @get:Rule
    val engine = EngineRule(memory = memory, storage = FakeStorageProbe(), exits = FakeExitInfo())

    /** What the next read takes, in ms: the clock moves by it at each call during a read. */
    @Volatile private var step = 0L
    private var now = 0L
    private val told = AtomicInteger()

    @After
    fun cleanUp() = SwapKit.cleanUp()

    private val clock: () -> Long = { now += step; now }

    private fun store(): SlabSwapStore =
        SwapKit.track(SlabSwapStore(SwapKit.root, FakeStorageProbe(), clock, slabBytes = 1L shl 20, onSlowReads = { told.incrementAndGet() }))

    /** A swapped tile of [store]: written, then committed (main's commit). */
    private fun swapped(store: SlabSwapStore, seed: Long): Tile {
        val (tile, _) = SwapKit.noiseTile(seed, 16, 16)
        step = 0L
        store.write(tile)
        SwapKit.commit(tile)
        return tile
    }

    /** Reads [tile] [n] times, each taking [ms]. */
    private fun read(store: SlabSwapStore, tile: Tile, n: Int, ms: Long) = repeat(n) {
        step = ms
        store.read(tile).recycle()
        step = 0L
    }

    @Test
    fun twoSlowReadsInFiftyDoNotTripThreeDo() {
        val s = store()
        val tile = swapped(s, 1)
        read(s, tile, 48, 1)
        read(s, tile, 2, SlabSwapStore.SLOW_READ_MS + 1)
        assertFalse("2 of 50", s.slowReads)
        read(s, tile, 1, SlabSwapStore.SLOW_READ_MS + 1)
        assertTrue("3 of the last 50", s.slowReads)
        assertEquals("told once", 1, told.get())
        read(s, tile, 60, 500)
        assertEquals("still once", 1, told.get())
    }

    @Test
    fun fortyMillisecondsIsNotSlowAndFewerThanFiftyReadsNeverTrip() {
        val s = store()
        val tile = swapped(s, 2)
        read(s, tile, SlabSwapStore.SLOW_WINDOW - 1, 1_000)
        assertFalse("49 reads", s.slowReads)
        val t = store()
        val other = swapped(t, 3)
        read(t, other, 200, SlabSwapStore.SLOW_READ_MS)
        assertFalse("40 ms is not above 40 ms", t.slowReads)
        assertEquals(0, told.get())
    }

    @Test
    fun theWindowSlides() {
        val s = store()
        val tile = swapped(s, 4)
        read(s, tile, 2, 200)
        read(s, tile, 48, 1)
        assertFalse(s.slowReads)
        read(s, tile, 2, 200)
        assertFalse("the first two left the window: 2 of the last 50", s.slowReads)
        read(s, tile, 1, 200)
        assertTrue(s.slowReads)
    }

    @Test
    fun slowReadsRunTheNextSessionsAtTilesUntilTheNextCheck() {
        val app = SwapKit.app
        CheckStore.write(app, A10Kit.result(EngineLevel.FULL))
        assertEquals(EngineLevel.FULL, CheckStore.read(app)!!.level)

        AutoCheckRunner.markSlowSwapReads(app)
        assertTrue(EngineStateStore.read(app).slowSwapReads)
        val read = CheckStore.read(app)!!
        assertEquals("the sessions' level", EngineLevel.TILES, read.level)
        assertEquals(CheckReasons.SLOW_STORAGE, read.reason)
        assertEquals("the file keeps the check's choice", EngineLevel.FULL, CheckStore.readStored(app)!!.level)
        AutoCheckRunner.markSlowSwapReads(app)
        assertTrue("twice is once", EngineStateStore.read(app).slowSwapReads)

        CheckStore.write(app, A10Kit.result(EngineLevel.CLASSIC))
        assertEquals("only a FULL result steps down", EngineLevel.CLASSIC, CheckStore.read(app)!!.level)

        AutoCheckRunner.requestCheck(app)
        assertEquals(AutoCheckRunner.Outcome.DONE, A10Kit.runner(app, A10Kit.Env()).runIfDue { false })
        assertFalse("the next check clears it", EngineStateStore.read(app).slowSwapReads)
        val checked = CheckStore.read(app)!!
        assertEquals("its own level again", CheckStore.readStored(app)!!.level, checked.level)
        assertEquals(EngineLevel.FULL, checked.level)
    }
}
