package com.brushwork.paint.v110.a10

import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.EngineStartup
import com.brushwork.paint.engine.tiles.SlabSwapStore
import com.brushwork.paint.engine.tiles.SwapJanitor
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.v110.lead.F1Pixels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v1.10 A10 (design 2.8, J9, I34, step 3): swap is a cache. At app start
 * (`EngineStartup`, on its background thread) the janitor deletes everything under
 * `noBackupFilesDir/swap` that is not a live session's directory: the sessions of earlier
 * processes with their slabs, stray files, nested folders. It never deletes the `swap` root, never
 * makes it, never touches `engine-probe/` or the check's files beside it, and never a live
 * session's directory, also one opened while the purge runs (after a process death `MainActivity`
 * reopens the artwork at once): that session's slabs survive and read back. A closed session's
 * directory goes with its close, and the session leaves the live set.
 */
@RunWith(RobolectricTestRunner::class)
class SwapJanitorPurgeTest {
    @get:Rule
    val engine = EngineRule(level = EngineLevel.FULL)

    @After
    fun tearDown() = SwapKit.cleanUp()

    /** A leftover session of an earlier process: a UUID directory with [slabs] slab files. */
    private fun leftover(slabs: Int = 2): File = File(SwapKit.root, UUID.randomUUID().toString()).apply {
        mkdirs()
        repeat(slabs) { File(this, "slab-%03d.bin".format(it)).writeBytes(ByteArray(8192) { b -> b.toByte() }) }
    }

    @Test
    fun theLeftoversOfEarlierProcessesGoAndTheRootStays() {
        val old = (1..3).map { leftover() }
        val stray = File(SwapKit.root, "stray.tmp").apply { writeText("x") }
        val nested = File(SwapKit.root, "a/b/c").apply { mkdirs(); File(this, "deep.bin").writeBytes(ByteArray(10)) }
        val probe = File(SwapKit.app.noBackupFilesDir, "engine-probe").apply { mkdirs(); File(this, "probe-0.bin").writeBytes(ByteArray(10)) }
        val beside = File(SwapKit.app.noBackupFilesDir, "engine-check.json").apply { writeText("{}") }

        assertEquals("three sessions, a stray file and a folder", 5, SwapJanitor.purge(SwapKit.root))
        assertTrue(old.none { it.exists() })
        assertFalse(stray.exists())
        assertFalse(nested.exists())
        assertTrue("the root stays", SwapKit.root.isDirectory)
        assertEquals(0, SwapKit.root.list()!!.size)
        assertTrue("engine-probe is not the janitor's", File(probe, "probe-0.bin").exists())
        assertTrue(beside.exists())
        assertEquals("nothing left: nothing to do", 0, SwapJanitor.purge(SwapKit.root))
        probe.deleteRecursively()
        beside.delete()
    }

    @Test
    fun noRootNothingMade() {
        SwapKit.root.deleteRecursively()
        assertEquals(0, SwapJanitor.purge(SwapKit.root))
        assertFalse(SwapKit.root.exists())
    }

    @Test
    fun aLiveSessionSurvivesAndAClosedOneGoes() {
        val store = SwapKit.store()
        val (tile, src) = SwapKit.noiseTile(1)
        store.write(tile)
        SwapKit.commit(tile)
        val old = leftover()

        assertEquals(1, SwapJanitor.purge(SwapKit.root))
        assertFalse(old.exists())
        assertTrue("the live session's directory", store.dir.isDirectory)
        assertTrue("and its slab", File(store.dir, "slab-000.bin").isFile)
        F1Pixels.assertBitEqual("its slot reads", src, store.read(tile))

        store.close()
        SwapKit.awaitClosed(store)
        assertFalse("a close deletes its directory", store.dir.exists())
        assertFalse(store.sessionId in SwapJanitor.live)
        assertTrue(SwapKit.root.isDirectory)
    }

    @Test
    fun aSessionOpenedDuringThePurgeKeepsItsSlabs() {
        repeat(20) { round ->
            val olds = (1..40).map { leftover(1) }
            val go = CountDownLatch(1)
            var store: SlabSwapStore? = null
            var failure: Throwable? = null
            val purge = Thread { go.await(); SwapJanitor.purge(SwapKit.root) }
            val open = Thread {
                try {
                    go.await()
                    val s = SwapKit.store()
                    store = s
                    val tiles = (0L until 4L).map { SwapKit.noiseTile(round * 10L + it).also { (t, _) -> s.write(t) } }
                    for ((t, src) in tiles) F1Pixels.assertBitEqual("round $round", src, s.read(t))
                } catch (e: Throwable) {
                    failure = e
                }
            }
            purge.start()
            open.start()
            go.countDown()
            purge.join(TimeUnit.SECONDS.toMillis(30))
            open.join(TimeUnit.SECONDS.toMillis(30))
            failure?.let { throw AssertionError("round $round", it) }
            val s = store!!
            assertTrue("round $round: the new session's directory survives", s.dir.isDirectory)
            assertTrue("round $round: and its slab", File(s.dir, "slab-000.bin").isFile)
            // Whatever the purge did not reach yet goes at the next one; the session still stays.
            SwapJanitor.purge(SwapKit.root)
            assertTrue(olds.none { it.exists() })
            assertTrue(s.dir.isDirectory)
            s.close()
            SwapKit.awaitClosed(s)
        }
    }

    @Test
    fun theStartPurges() {
        val old = leftover()
        val live = SwapKit.store()
        EngineStartup.atStart(SwapKit.app as BrushworkApp)
        assertFalse("the start deleted the leftover", old.exists())
        assertTrue("and kept the live session", live.dir.isDirectory)
    }
}
