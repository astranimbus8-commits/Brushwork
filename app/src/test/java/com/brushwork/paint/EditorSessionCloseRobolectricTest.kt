package com.brushwork.paint

import android.graphics.Path
import android.os.Looper
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.model.Selection
import com.brushwork.paint.storage.NewCanvasSpec
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorLayers
import com.brushwork.paint.vector.select.ObjectActions
import com.brushwork.paint.vector.select.PendingRenders
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * v1.5 integration (lead): closing the editor (Back) while a vector edit still renders in the
 * background saves that edit: the render lands before the save instead of being dropped with the
 * controller.
 */
@RunWith(RobolectricTestRunner::class)
class EditorSessionCloseRobolectricTest {

    private fun pumpUntil(what: String, done: () -> Boolean) {
        var n = 0
        while (!done() && n++ < 1000) {
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

    /** A dense zig-zag over the whole 400 x 300 canvas with a big opaque brush (thousands of dabs). */
    private fun scribble(): VStroke {
        val n = 240
        val xs = FloatArray(n) { if (it % 2 == 0) 10f else 390f }
        val ys = FloatArray(n) { 10f + 280f * it / (n - 1) }
        return VStroke(
            0, preset = BrushLibrary.defaultBrush.copy(size = 60f), color = 0xFF20A040.toInt(), seed = 3L, stylus = false,
            points = PackedPoints(xs, ys, FloatArray(n) { 1f }),
        )
    }

    @Test
    fun aVectorEditStillRenderingWhenTheEditorClosesIsSaved() {
        val app = RuntimeEnvironment.getApplication() as BrushworkApp
        val id = runBlocking { app.repository.create(NewCanvasSpec("Close test", 400, 300, 72f)) }
        try {
            val session = EditorSession(app, id)
            pumpUntil("the editor to open") { session.state is EditorSession.State.Ready }
            val c = (session.state as EditorSession.State.Ready).controller
            val layer = c.activeLayer
            assertTrue(c.convertToVectorLayer(layer))
            c.vectors.addObjects(layer, listOf(box(20f, 20f, 120f, 100f)), "Add")
            // The next edit (a long scribble with a big brush: it renders for a while) renders in
            // the background, and Back is pressed right away.
            c.vectors.policy = VectorLayers.Policy.ASYNC
            c.vectors.update(layer, layer.vector!!.plus(listOf(scribble())).first, "Add")
            assertTrue(c.vectors.isRendering)
            var closed = false
            session.close { closed = true }
            pumpUntil("the editor to close") { closed }
            val saved = runBlocking { app.repository.load(id) }
            val v = saved.layers[saved.activeLayerIndex].vector
            assertNotNull("saved as a vector layer", v)
            assertEquals("the edit that was rendering is saved", 2, v!!.objects.size)
            assertTrue("and its pixels", saved.layers[saved.activeLayerIndex].bitmap.getPixel(200, 150) ushr 24 > 0)
        } finally {
            runBlocking { app.repository.delete(id) }
        }
    }

    /**
     * Review fix: an Object bar action pressed while a vector edit still renders waits for it; when
     * Back is pressed then, the action is made before the save (not dropped with the editor, nor
     * made while the layers are being written).
     */
    @Test
    fun anObjectBarActionWaitingForARenderWhenTheEditorClosesIsSaved() {
        val app = RuntimeEnvironment.getApplication() as BrushworkApp
        val id = runBlocking { app.repository.create(NewCanvasSpec("Close test 2", 400, 300, 72f)) }
        try {
            val session = EditorSession(app, id)
            pumpUntil("the editor to open") { session.state is EditorSession.State.Ready }
            val c = (session.state as EditorSession.State.Ready).controller
            val layer = c.activeLayer
            assertTrue(c.convertToVectorLayer(layer))
            val boxes = c.vectors.addObjects(layer, listOf(box(20f, 20f, 120f, 100f), box(250f, 150f, 350f, 250f)), "Add")
            assertEquals(2, boxes.size)
            c.vectors.setSelection(layer, setOf(boxes[0]))
            c.vectors.policy = VectorLayers.Policy.ASYNC
            c.vectors.update(layer, layer.vector!!.plus(listOf(scribble())).first, "Add")
            assertTrue(c.vectors.isRendering)
            // Delete (Object bar) waits for the render; Back right away.
            assertTrue(ObjectActions.delete(c))
            assertTrue(PendingRenders.busy(c))
            var closed = false
            session.close { closed = true }
            pumpUntil("the editor to close") { closed }
            val saved = runBlocking { app.repository.load(id) }
            val v = saved.layers[saved.activeLayerIndex].vector
            assertNotNull(v)
            assertEquals("the render landed and the box was deleted", listOf(boxes[1]), v!!.objects.filterIsInstance<VPath>().map { it.id })
            assertEquals(1, v.objects.filterIsInstance<VStroke>().size)
        } finally {
            runBlocking { app.repository.delete(id) }
        }
    }

    /**
     * v1.7 (§3.14 (c), review): a saved selection still compressing when Back is pressed lands
     * before the save, so it is in the reopened project (the cancelled scope would drop it).
     */
    @Test
    fun aSavedSelectionStillCompressingWhenTheEditorClosesIsSaved() {
        val app = RuntimeEnvironment.getApplication() as BrushworkApp
        val id = runBlocking { app.repository.create(NewCanvasSpec("Close test 3", 400, 300, 72f)) }
        try {
            val session = EditorSession(app, id)
            pumpUntil("the editor to open") { session.state is EditorSession.State.Ready }
            val c = (session.state as EditorSession.State.Ready).controller
            val p = Path().apply { addRect(40f, 30f, 200f, 150f, Path.Direction.CW) }
            c.setSelection(Selection.fromPath(p, 400, 300, antiAlias = false), recordUndo = false)
            c.beforeSavedSelectionPack = { delay(200) }
            assertTrue(c.saveSelection())
            val pending = c.pendingSavedSelections.single()
            var closed = false
            session.close { closed = true }
            pumpUntil("the editor to close") { closed }
            val saved = runBlocking { app.repository.load(id) }
            assertEquals("the pending save is saved", listOf(pending.id), saved.savedSelections.map { it.id })
            assertEquals(pending.name, saved.savedSelections.single().name)
        } finally {
            runBlocking { app.repository.delete(id) }
        }
    }
}
