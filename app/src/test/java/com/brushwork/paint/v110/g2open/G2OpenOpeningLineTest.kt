package com.brushwork.paint.v110.g2open

import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.EditorSession
import com.brushwork.paint.OpeningLine
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeExitInfo
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.FakeStorageProbe
import com.brushwork.paint.testing.MemoryPreset
import com.brushwork.paint.v110.a10.A10Kit
import com.brushwork.paint.v110.a11.A11Kit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v1.10 Gate 2 (lane open, O2; design 4.4 A11 step 7): an editor session's "Opening layer n of
 * m" line (`EditorSession.opening`, shown by `MainActivity`'s OpeningScreen) driven by real opens
 * at FULL: once the open has run its delay it shows every layer the load reads, in order, while
 * the state is still Loading (held at layer 5 here), and it is gone when the editor is ready; an
 * open that fails at a damaged layer takes it away with the failure; an open that ends before the
 * delay never shows it.
 */
@RunWith(RobolectricTestRunner::class)
class G2OpenOpeningLineTest {
    @get:Rule
    val engine = EngineRule(level = EngineLevel.FULL, memory = FakeMemoryProbe(MemoryPreset.T606), storage = FakeStorageProbe(), exits = FakeExitInfo())

    private val app get() = RuntimeEnvironment.getApplication() as BrushworkApp
    private val w = 256
    private val h = 256

    @After
    fun tearDown() {
        OpeningLine.delayMs = 1000L
        OpeningLine.onLayer = null
        var closed = false
        app.closeEditor { closed = true }
        A10Kit.pumpUntil { closed }
    }

    /** Project [id] of 12 painted layers, the top one active; returns its folder. */
    private fun project(id: String): File {
        A11Kit.freshRepo()
        val files = List(LAYERS) { i -> { f: File -> A11Kit.writeRows(f, w, h, A11Kit.PaintedRows(w, h, seed = 500L + i)::fill) } }
        return A11Kit.writeProject(id, w, h, files, active = LAYERS - 1)
    }

    private fun shown(n: Int) = EditorSession.OpenProgress(n, LAYERS)

    @Test
    fun theLineFollowsTheLayersAndGoesWhenTheEditorIsReady() {
        val id = "00000000-0000-0000-0000-0000000c2e01"
        project(id)
        OpeningLine.delayMs = 0L
        val heard = Collections.synchronizedList(ArrayList<EditorSession.OpenProgress?>())
        val atFive = CountDownLatch(1)
        val go = CountDownLatch(1)
        OpeningLine.onLayer = { p ->
            heard += p
            if (p?.layer == 5) {
                atFive.countDown()
                go.await(20, TimeUnit.SECONDS)
            }
        }
        val session = app.openEditor(id)
        assertTrue("the open reads layer 5", atFive.await(20, TimeUnit.SECONDS))
        assertEquals("the line shows layer 5 of 12", shown(5), session.opening.value)
        assertSame("while the artwork still loads", EditorSession.State.Loading, session.state)
        go.countDown()
        assertTrue(A10Kit.pumpUntil { session.state !is EditorSession.State.Loading })
        assertTrue("the editor is ready: ${session.state}", session.state is EditorSession.State.Ready)
        assertNull("the line is gone", session.opening.value)
        assertEquals("every layer in order, updated as each decodes", (1..LAYERS).map { shown(it) }, heard.toList())
    }

    @Test
    fun anOpenThatFailsTakesTheLineAway() {
        val id = "00000000-0000-0000-0000-0000000c2e02"
        val dir = project(id)
        val file = File(dir, ProjectFormat.layerFile(8L, 1))
        file.writeBytes(file.readBytes().copyOf((file.length() / 2).toInt()))
        OpeningLine.delayMs = 0L
        val heard = Collections.synchronizedList(ArrayList<EditorSession.OpenProgress?>())
        OpeningLine.onLayer = { heard += it }
        val session = app.openEditor(id)
        assertTrue(A10Kit.pumpUntil { session.state !is EditorSession.State.Loading })
        assertTrue("the open failed: ${session.state}", session.state is EditorSession.State.Failed)
        assertNull("the line is gone with the failure", session.opening.value)
        assertEquals("the layers before the damaged one", (1..7).map { shown(it) }, heard.toList())
    }

    @Test
    fun anOpenThatEndsBeforeTheDelayNeverShowsTheLine() {
        val id = "00000000-0000-0000-0000-0000000c2e03"
        project(id)
        // Robolectric's main clock moves 20 ms per pump: the open ends long before a minute.
        OpeningLine.delayMs = 60_000L
        val heard = Collections.synchronizedList(ArrayList<EditorSession.OpenProgress?>())
        OpeningLine.onLayer = { heard += it }
        val session = app.openEditor(id)
        assertTrue(A10Kit.pumpUntil { session.state !is EditorSession.State.Loading })
        assertTrue("the editor is ready: ${session.state}", session.state is EditorSession.State.Ready)
        assertEquals("every layer was read", LAYERS, heard.size)
        assertTrue("nothing was shown: $heard", heard.all { it == null })
        assertNull(session.opening.value)
    }

    private companion object {
        const val LAYERS = 12
    }
}
