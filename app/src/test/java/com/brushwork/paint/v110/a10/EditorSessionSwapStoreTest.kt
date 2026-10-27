package com.brushwork.paint.v110.a10

import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.EditorSession
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

/**
 * v1.10 A10 (design 2.8; region `v110-A10 session` of `EditorSession`): a FULL editor session
 * installs its [SlabSwapStore] as soon as it is made, while the artwork still loads (before the
 * editor exists, so before any tile can be written out), under `noBackupFilesDir/swap`; the
 * editor's chrome then finds it and installs none; the session's end closes it and deletes its
 * directory. At CLASSIC the session installs nothing.
 */
@RunWith(RobolectricTestRunner::class)
class EditorSessionSwapStoreTest {
    @get:Rule
    val engine = EngineRule(level = EngineLevel.FULL, memory = FakeMemoryProbe(MemoryPreset.T606), storage = FakeStorageProbe(), exits = FakeExitInfo())

    private val app get() = RuntimeEnvironment.getApplication() as BrushworkApp

    @After
    fun tearDown() {
        app.closeEditor()
        A10Kit.pumpUntil { EngineConfig.swapStore === NoSwap }
        SlabSwapStore.installUnderRobolectric = false
        SwapKit.cleanUp()
    }

    @Test
    fun aFullSessionInstallsItsStoreBeforeTheEditorAndClosesItAtItsEnd() {
        SlabSwapStore.installUnderRobolectric = true
        val session = app.openEditor("00000000-0000-0000-0000-0000000a1008")
        val store = EngineConfig.swapStore
        assertTrue("a SlabSwapStore at once, not $store", store is SlabSwapStore)
        store as SlabSwapStore
        SwapKit.track(store)
        assertSame("while the artwork still loads", EditorSession.State.Loading, session.state)
        assertEquals("under noBackupFilesDir/swap", SwapJanitor.root(app).canonicalFile, store.dir.parentFile!!.canonicalFile)
        assertTrue("its directory made", store.dir.isDirectory)
        assertTrue(A10Kit.pumpUntil { session.state !is EditorSession.State.Loading })
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
