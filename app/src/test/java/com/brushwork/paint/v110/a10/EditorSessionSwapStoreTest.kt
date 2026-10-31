package com.brushwork.paint.v110.a10

import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.EditorSession
import com.brushwork.paint.OpeningLine
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.tiles.NoSwap
import com.brushwork.paint.engine.tiles.SlabSwapStore
import com.brushwork.paint.engine.tiles.SwapJanitor
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeExitInfo
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.FakeStorageProbe
import com.brushwork.paint.testing.MemoryPreset
import com.brushwork.paint.v110.a11.A11Kit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v1.10 A10 (design 2.8; region `v110-A10 session` of `EditorSession`): a FULL editor session
 * installs its [SlabSwapStore] as soon as it is made, while the artwork still loads (before the
 * editor exists, so before any tile can be written out), under `noBackupFilesDir/swap`; the
 * editor's chrome then finds it and installs none; the session's end closes it and deletes its
 * directory. At CLASSIC the session installs nothing.
 *
 * The FULL session opens a real small artwork whose load is held at its first layer
 * ([OpeningLine.onLayer]) until the assertions made while it loads have run. (Before, it opened
 * an artwork that does not exist: the session's scope is `Main.immediate`, so `open()` runs
 * inside `openEditor` up to the load's `withContext(IO)`, and when the IO thread threw "This
 * artwork no longer exists" before main reached the suspension, `withContext` rethrew on main at
 * once and `openEditor` returned a session already Failed.)
 */
@RunWith(RobolectricTestRunner::class)
class EditorSessionSwapStoreTest {
    @get:Rule
    val engine = EngineRule(level = EngineLevel.FULL, memory = FakeMemoryProbe(MemoryPreset.T606), storage = FakeStorageProbe(), exits = FakeExitInfo())

    private val app get() = RuntimeEnvironment.getApplication() as BrushworkApp

    /**
     * Lets the held load go on: counted down by the test, and by [tearDown] before the editor
     * closes should the test fail first (cancelling the session's scope does not wake a held load,
     * and the session's end, which closes the store, waits for the load to return).
     */
    private val go = CountDownLatch(1)

    private var project: File? = null

    @After
    fun tearDown() {
        go.countDown()
        OpeningLine.onLayer = null
        app.closeEditor()
        A10Kit.pumpUntil { EngineConfig.swapStore === NoSwap }
        SlabSwapStore.installUnderRobolectric = false
        SwapKit.cleanUp()
        project?.deleteRecursively()
    }

    @Test
    fun aFullSessionInstallsItsStoreBeforeTheEditorAndClosesItAtItsEnd() {
        SlabSwapStore.installUnderRobolectric = true
        val id = "00000000-0000-0000-0000-0000000a1008"
        A11Kit.freshRepo()
        project = A11Kit.writeProject(id, 64, 64, List(2) { i -> { f: File -> A11Kit.writeRows(f, 64, 64, A11Kit.PaintedRows(64, 64, seed = 1008L + i)::fill) } })
        // The load (its IO thread) waits at its first layer until the test lets it go on.
        val reading = CountDownLatch(1)
        val held = AtomicBoolean(false)
        OpeningLine.onLayer = {
            if (held.compareAndSet(false, true)) {
                reading.countDown()
                go.await(20, TimeUnit.SECONDS)
            }
        }
        val session = app.openEditor(id)
        val store = EngineConfig.swapStore
        assertTrue("a SlabSwapStore at once, not $store", store is SlabSwapStore)
        store as SlabSwapStore
        SwapKit.track(store)
        assertTrue("the load reads the first layer", reading.await(20, TimeUnit.SECONDS))
        assertSame("while the artwork still loads", EditorSession.State.Loading, session.state)
        assertEquals("under noBackupFilesDir/swap", SwapJanitor.root(app).canonicalFile, store.dir.parentFile!!.canonicalFile)
        assertTrue("its directory made", store.dir.isDirectory)
        go.countDown()
        assertTrue(A10Kit.pumpUntil { session.state !is EditorSession.State.Loading })
        assertTrue("the editor is ready: ${session.state}", session.state is EditorSession.State.Ready)
        assertSame("kept while the editor shows", store, EngineConfig.swapStore)
        assertNull("the chrome installs none over it", SlabSwapStore.installIfFull(app))

        var closed = false
        app.closeEditor { closed = true }
        assertTrue(A10Kit.pumpUntil { closed && EngineConfig.swapStore === NoSwap })
        SwapKit.awaitClosed(store)
        assertFalse("its directory deleted at the session's end", store.dir.exists())
        assertFalse("no longer a live session", store.sessionId in SwapJanitor.live)
    }

    @Test
    fun aClassicSessionInstallsNone() {
        SlabSwapStore.installUnderRobolectric = true
        // The rule's level is the test level every session opens at; CLASSIC for this one.
        System.setProperty("brushwork.engineMode", "classic")
        app.openEditor("00000000-0000-0000-0000-0000000a1009")
        assertEquals(EngineLevel.CLASSIC, EngineConfig.level)
        assertSame(NoSwap, EngineConfig.swapStore)
    }
}
