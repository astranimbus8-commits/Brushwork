package com.brushwork.paint.tools.select

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.segmentation.ObjectPrompt
import com.brushwork.paint.tools.ToolPoint
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * The object select tool on real Skia. The MagicTouch native runtime cannot load off-device,
 * so these taps go through the color region-growing fallback (and must say so).
 */
@RunWith(RobolectricTestRunner::class)
class ObjectSelectToolRobolectricTest {
    private val red = 0xFFDC2828.toInt()
    private val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())

    private fun controller(w: Int = 400, h: Int = 300): EditorController {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val doc = Document("t", "t", w, h)
        val bmp = BitmapUtils.createLayerBitmap(w, h)
        bmp.eraseColor(-1)
        Canvas(bmp).drawCircle(w / 2f, h / 2f, h / 5f, Paint().apply { color = red })
        doc.layers += Layer(doc.newLayerId(), "Layer 1", bmp)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, t -> errors += t })
        val settings = AppSettings(ctx).also { it.prefs.edit().clear().commit() }
        return EditorController(ctx, doc, scope, settings)
    }

    private fun pumpUntil(timeoutMs: Long = 30_000, done: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            if (done()) return true
            Thread.sleep(5)
        }
        return done()
    }

    private fun tap(tool: ObjectSelectTool, x: Float, y: Float) {
        tool.onDown(ToolPoint(x, y))
        tool.onUp(ToolPoint(x, y))
    }

    private fun alpha(sel: Selection?, x: Int, y: Int) = sel?.alphaAt(x, y) ?: 0

    @Test
    fun tapSelectsTheObjectWithTheColorFallbackAndSaysSo() {
        val c = controller()
        val tool = ObjectSelectTool(c)
        tap(tool, 200f, 150f)
        assertTrue("busy while working", tool.busy || c.selection != null)
        assertTrue(pumpUntil { !tool.busy })
        val sel = c.selection
        assertNotNull(sel)
        assertEquals(255, alpha(sel, 200, 150))
        assertEquals(255, alpha(sel, 200 + 55, 150))
        assertEquals(0, alpha(sel, 200 + 66, 150))
        assertEquals(0, alpha(sel, 10, 10))
        assertTrue("told about the fallback: ${c.message}", c.message?.contains("similar colors") == true)
        assertTrue(c.canUndo)
        c.undo()
        assertNull(c.selection)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun modesCombineWithTheCurrentSelection() {
        val c = controller()
        val tool = ObjectSelectTool(c)
        // Existing selection: the left half of the canvas.
        c.setSelection(Selection.fromBytes(ByteArray(400 * 300) { if (it % 400 < 200) -1 else 0 }, 400, 300))
        tool.mode = SelectionMode.SUBTRACT
        tap(tool, 200f, 150f)
        assertTrue(pumpUntil { !tool.busy })
        assertEquals(0, alpha(c.selection, 180, 150)) // the disc was taken out
        assertEquals(255, alpha(c.selection, 20, 20))
        tool.mode = SelectionMode.ADD
        tap(tool, 230f, 150f)
        assertTrue(pumpUntil { !tool.busy })
        assertEquals(255, alpha(c.selection, 230, 150))
        assertEquals(255, alpha(c.selection, 180, 150))
        assertEquals(0, alpha(c.selection, 350, 20))
        tool.mode = SelectionMode.INTERSECT
        tap(tool, 200f, 150f)
        assertTrue(pumpUntil { !tool.busy })
        assertEquals(255, alpha(c.selection, 200, 150))
        assertEquals(0, alpha(c.selection, 20, 20))
        assertTrue(errors.isEmpty())
    }

    @Test
    fun scribblesSelectAndCancelledGesturesLeaveNoTrace() {
        val c = controller()
        val tool = ObjectSelectTool(c)
        tool.onDown(ToolPoint(170f, 150f))
        tool.onMove(ToolPoint(200f, 150f))
        tool.onMove(ToolPoint(230f, 150f))
        tool.onUp(ToolPoint(230f, 150f))
        assertTrue(pumpUntil { !tool.busy })
        assertEquals(255, alpha(c.selection, 200, 150))
        c.deselect()
        // A second finger cancels the gesture: nothing is computed.
        tool.onDown(ToolPoint(200f, 150f))
        tool.onMove(ToolPoint(260f, 150f))
        tool.onCancel()
        assertFalse(tool.busy)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertNull(c.selection)
    }

    @Test
    fun aScribbleThatLeavesTheCanvasIsCutNotDraggedAlongTheEdge() {
        // Points outside the canvas used to be pulled onto its edge: the white paper along the
        // right edge became part of the prompt and the whole background was selected.
        val c = controller()
        val tool = ObjectSelectTool(c)
        tool.onDown(ToolPoint(175f, 150f))
        tool.onMove(ToolPoint(225f, 150f))
        tool.onMove(ToolPoint(480f, 150f)) // off the 400 px wide canvas
        tool.onMove(ToolPoint(480f, 20f))
        tool.onUp(ToolPoint(470f, 20f))
        assertTrue(pumpUntil { !tool.busy })
        assertEquals(255, alpha(c.selection, 200, 150))
        assertEquals(0, alpha(c.selection, 395, 150))
        assertEquals(0, alpha(c.selection, 10, 10))
        assertTrue(errors.isEmpty())
    }

    @Test
    fun promptIsCutToItsLongestRunOnTheCanvas() {
        val all = ObjectPrompt(floatArrayOf(10f, 10f, 20f, 20f))
        assertTrue(ObjectSelectTool.onCanvas(all, 100, 100) === all)
        assertNull(ObjectSelectTool.onCanvas(ObjectPrompt(floatArrayOf(-1f, 5f, 150f, 5f)), 100, 100))
        assertNull(ObjectSelectTool.onCanvas(ObjectPrompt.tap(Float.NaN, 5f), 100, 100))
        // In (2 points), out, in again (3 points): the second run wins.
        val zigzag = ObjectPrompt(floatArrayOf(10f, 10f, 20f, 10f, 120f, 10f, 50f, 50f, 60f, 50f, 70f, 50f))
        val cut = ObjectSelectTool.onCanvas(zigzag, 100, 100)!!
        assertEquals(listOf(50f, 50f, 60f, 50f, 70f, 50f), cut.points.toList())
        // The right and bottom edges are outside (pixel centers run up to w - 0.5).
        assertNull(ObjectSelectTool.onCanvas(ObjectPrompt.tap(100f, 50f), 100, 100))
        assertEquals(1, ObjectSelectTool.onCanvas(ObjectPrompt.tap(99.9f, 0f), 100, 100)!!.count)
    }

    @Test
    fun tapsOutsideTheCanvasOrWhileBusyAreIgnoredAndCancelChangesNothing() {
        val c = controller()
        val tool = ObjectSelectTool(c)
        tap(tool, -20f, 150f)
        tap(tool, 200f, 900f)
        assertFalse(tool.busy)
        assertNull(c.selection)
        // Cancel right after starting: no selection, no error, the tool is usable again.
        val big = controller(2000, 1500)
        val t2 = ObjectSelectTool(big)
        tap(t2, 1000f, 750f)
        t2.cancel()
        assertTrue(pumpUntil { !t2.busy })
        assertNull(big.selection)
        tap(t2, 1000f, 750f)
        assertTrue(t2.busy)
        tap(t2, 20f, 20f) // ignored while busy
        assertTrue(pumpUntil { !t2.busy })
        assertEquals(255, big.selection?.alphaAt(1000, 750))
        assertEquals(0, big.selection?.alphaAt(20, 20))
        assertTrue(errors.isEmpty())
    }

    @Test
    fun settingsPersist() {
        val c = controller()
        val tool = ObjectSelectTool(c)
        assertEquals(SampleSource.CANVAS, tool.settings.source)
        assertTrue(tool.settings.refineEdges)
        tool.settings = tool.settings.copy(source = SampleSource.LAYER, refineEdges = false)
        val again = ObjectSelectTool(c)
        assertEquals(SampleSource.LAYER, again.settings.source)
        assertFalse(again.settings.refineEdges)
    }
}
