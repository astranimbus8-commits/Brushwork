package com.brushwork.paint.v19.a7b

import android.graphics.Path
import android.os.Handler
import android.os.Looper
import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.EditorController
import com.brushwork.paint.EditorSession
import com.brushwork.paint.model.Selection
import com.brushwork.paint.storage.NewCanvasSpec
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorLayers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * v1.9 A7b step 5 (design 3.2 A7b, R9): leaving the screen (Home, app switch, screen off) saves
 * the work still landing without holding the main thread. [EditorSession.saveOnLeaving] returns
 * at once; on the app scope it waits up to the limit for a render still running (the main thread
 * keeps running meanwhile) and until a saved selection is compressed, lands what is still pending
 * on the spot through the synchronous path, and saves: the reopened file holds it all. Nothing
 * is cancelled on leaving.
 */
@RunWith(RobolectricTestRunner::class)
class SaveOnLeavingTest {
    private val app get() = RuntimeEnvironment.getApplication() as BrushworkApp
    private val gate = GatedWorker()
    private var id: String? = null

    @After
    fun tearDown() {
        gate.close()
        id?.let { runBlocking { app.repository.delete(it) } }
    }

    private fun pumpUntil(what: String, timeoutMs: Long = 10_000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!done() && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            Thread.sleep(2)
        }
        assertTrue("timed out waiting for $what", done())
    }

    private fun box(l: Float, t: Float, r: Float, b: Float) = VPath(
        0,
        subpaths = listOf(VSubpath(listOf(VAnchor(l, t, true), VAnchor(r, t, true), VAnchor(r, b, true), VAnchor(l, b, true)), closed = true)),
        fill = VPaint.Solid(0xFF2266AA.toInt()),
    )

    /** An open editor on a new 400 x 300 artwork with a vector layer holding one box; renders go to the gated worker. */
    private fun open(name: String): Pair<EditorSession, EditorController> {
        app.settings.autosaveSeconds = 600
        val pid = runBlocking { app.repository.create(NewCanvasSpec(name, 400, 300, 72f)) }
        id = pid
        val session = EditorSession(app, pid)
        pumpUntil("the editor to open") { session.state is EditorSession.State.Ready }
        val c = (session.state as EditorSession.State.Ready).controller
        assertTrue(c.convertToVectorLayer(c.activeLayer))
        c.vectors.addObjects(c.activeLayer, listOf(box(20f, 20f, 120f, 100f)), "Add")
        c.vectors.workerDispatcher = gate
        c.vectors.policy = VectorLayers.Policy.ASYNC
        c.deferredWaitLimitMs = LIMIT_MS
        return session to c
    }

    /** Home: [EditorSession.saveOnLeaving] returns at once, and the main thread keeps running while it waits. */
    private fun leave(session: EditorSession, c: EditorController): Job {
        val job = session.saveOnLeaving()
        assertEquals("saveOnLeaving returns without waiting for the worker", 0, gate.letThrough)
        assertTrue("the render is still running", c.vectors.isRendering)
        var ran = false
        Handler(Looper.getMainLooper()).post { ran = true }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
        assertTrue("the main thread runs meanwhile", ran)
        assertFalse("still waiting", job.isCompleted)
        return job
    }

    @Test
    fun aRenderThatEndsWithinTheWaitIsSaved() {
        val (session, c) = open("Leaving 1")
        val layer = c.activeLayer
        c.vectors.update(layer, layer.vector!!.plus(listOf(box(200f, 120f, 360f, 260f))).first, "Add")
        assertTrue(c.vectors.isRendering)
        val job = leave(session, c)
        // The worker ends well within the wait.
        Thread { Thread.sleep(LIMIT_MS / 4); gate.open() }.start()
        pumpUntil("the leaving save") { job.isCompleted }
        assertFalse(c.vectors.isRendering)
        assertEquals("nothing cancelled", 0, c.deferredWaitTimeouts)
        val saved = runBlocking { app.repository.load(id!!) }
        val l = saved.layers[saved.activeLayerIndex]
        assertEquals("the render that was running is in the saved file", 2, l.vector!!.objects.size)
        assertTrue("with its pixels", l.bitmap.getPixel(280, 190) ushr 24 > 0)
    }

    @Test
    fun workStillPendingAfterTheWaitLandsOnTheSpotAndIsSaved() {
        val (session, c) = open("Leaving 2")
        val layer = c.activeLayer
        // A saved selection compressing for longer than the render wait, and a render whose
        // worker never ends while the app is away.
        c.setSelection(Selection.fromPath(Path().apply { addRect(40f, 30f, 200f, 150f, Path.Direction.CW) }, 400, 300, antiAlias = false), recordUndo = false)
        c.beforeSavedSelectionPack = { delay(LIMIT_MS * 3) }
        assertTrue(c.saveSelection())
        val pending = c.pendingSavedSelections.single()
        c.vectors.update(layer, layer.vector!!.plus(listOf(box(200f, 120f, 360f, 260f))).first, "Add")
        assertTrue(c.vectors.isRendering)
        val job = leave(session, c)
        pumpUntil("the leaving save") { job.isCompleted }
        assertFalse("landed on the spot", c.vectors.isRendering)
        assertTrue(c.pendingSavedSelections.isEmpty())
        assertEquals("nothing cancelled", 0, c.deferredWaitTimeouts)
        assertFalse(c.message.orEmpty().contains("took too long"))
        assertFalse(c.leavingDeferred)
        val saved = runBlocking { app.repository.load(id!!) }
        val l = saved.layers[saved.activeLayerIndex]
        assertEquals("the render is in the saved file", 2, l.vector!!.objects.size)
        assertTrue("with its pixels", l.bitmap.getPixel(280, 190) ushr 24 > 0)
        assertEquals("the saved selection too", listOf(pending.id), saved.savedSelections.map { it.id })
        // The worker ends later: nothing lands twice.
        val steps = c.undoManager.undoCount
        gate.open()
        val end = System.currentTimeMillis() + 400
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            Thread.sleep(5)
        }
        assertEquals(steps, c.undoManager.undoCount)
    }

    private companion object {
        /** The render wait in these tests (1.5 s in the app). */
        const val LIMIT_MS = 300L
    }
}
