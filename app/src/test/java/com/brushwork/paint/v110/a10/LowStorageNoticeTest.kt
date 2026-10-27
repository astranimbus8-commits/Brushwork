package com.brushwork.paint.v110.a10

import android.os.Looper
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.budget.StorageProbe
import com.brushwork.paint.engine.tiles.NoSwap
import com.brushwork.paint.engine.tiles.SlabSwapStore
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeClock
import com.brushwork.paint.testing.FakeStorageProbe
import com.brushwork.paint.ui.common.EngineLabels110
import com.brushwork.paint.ui.engine.LowStorageNotice
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.util.Collections

/**
 * v1.10 A10 (J10, UQ13; design step 6): the low-storage message shows once per FULL session, the
 * first time the session's swap store reads less than 1.5 GiB free (the store reads again once 60 s
 * have passed); at exactly 1.5 GiB, with an unknown reading or without a store it shows nothing. The
 * editor's watch shows it at once when it is due and returns at once without a store.
 */
@RunWith(RobolectricTestRunner::class)
class LowStorageNoticeTest {
    @get:Rule
    val engine = EngineRule(level = EngineLevel.FULL, storage = FakeStorageProbe())

    private val gib = 1L shl 30

    @Before
    fun setUp() = LowStorageNotice.reset()

    @After
    fun tearDown() {
        LowStorageNotice.reset()
        SwapKit.cleanUp()
    }

    @Test
    fun underOneAndAHalfGiBItShowsOncePerSession() {
        val storage = FakeStorageProbe(free = 2 * gib)
        val clock = FakeClock()
        val store = SwapKit.store(storage, clock)
        assertNull("2 GiB free: nothing", LowStorageNotice.due(store))

        storage.free = gib + gib / 4
        clock.advance(SlabSwapStore.CAP_REFRESH_MS - 1)
        assertNull("the store reads again only after 60 s", LowStorageNotice.due(store))
        clock.advance(1)
        assertEquals(EngineLabels110.LOW_STORAGE, LowStorageNotice.due(store))

        storage.free = gib / 2
        clock.advance(SlabSwapStore.CAP_REFRESH_MS)
        assertNull("once per session", LowStorageNotice.due(store))
        assertEquals("the next session shows it again", EngineLabels110.LOW_STORAGE, LowStorageNotice.due(SwapKit.store(storage, clock)))
    }

    @Test
    fun atTheLineUnknownOrWithoutAStoreNothingShows() {
        assertNull("exactly 1.5 GiB", LowStorageNotice.due(SwapKit.store(FakeStorageProbe(free = LowStorageNotice.LOW_FREE))))
        assertEquals("a byte less", EngineLabels110.LOW_STORAGE, LowStorageNotice.due(SwapKit.store(FakeStorageProbe(free = LowStorageNotice.LOW_FREE - 1))))
        val unknown = FakeStorageProbe().apply { failure = IOException("no reading") }
        assertNull("an unknown reading", LowStorageNotice.due(SwapKit.store(unknown)))
        assertNull("CLASSIC and TILES have no store", LowStorageNotice.due(NoSwap))
    }

    @Test
    fun theEditorsWatchShowsItAtOnceAndReturnsWithoutAStore() {
        val shown = mutableListOf<String>()
        assertTrue(EngineConfig.swapStore === NoSwap)
        runBlocking { LowStorageNotice.watch { shown += it } }
        assertTrue("no store: nothing, and the watch ends", shown.isEmpty())

        EngineConfig.swapStore = SwapKit.store(FakeStorageProbe(free = gib))
        runBlocking { LowStorageNotice.watch { shown += it } }
        assertEquals(listOf(EngineLabels110.LOW_STORAGE), shown)
    }

    /** Review (A10 8/8): the watch runs on main; a new reading of the free space (a `statfs`) never does. */
    @Test
    fun theWatchReadsTheFreeSpaceOffTheMainThread() {
        val readers = Collections.synchronizedList(mutableListOf<Thread>())
        val fake = FakeStorageProbe(free = gib)
        val storage = object : StorageProbe by fake {
            override fun freeBytes(dir: File): Long {
                readers += Thread.currentThread()
                return fake.freeBytes(dir)
            }
        }
        val clock = FakeClock()
        val store = SwapKit.track(SlabSwapStore(SwapKit.root, storage, clock.millis))
        readers.clear() // the store's first reading, when it is made
        clock.advance(SlabSwapStore.CAP_REFRESH_MS)
        EngineConfig.swapStore = store
        val shown = mutableListOf<String>()
        runBlocking { LowStorageNotice.watch { shown += it } }
        assertEquals("shown on the watch's thread", listOf(EngineLabels110.LOW_STORAGE), shown)
        assertEquals("one new reading", 1, readers.size)
        assertNotSame("not on the main thread", Looper.getMainLooper().thread, readers.single())
    }
}
