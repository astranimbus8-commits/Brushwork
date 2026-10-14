package com.brushwork.paint.v19.a7a

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.EditorController
import com.brushwork.paint.diagnostics.CrashLog
import com.brushwork.paint.diagnostics.CrashReport
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.select.magnetic.MagneticLassoTool
import com.brushwork.paint.ui.common.StabilityLabels19
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.util.Collections

/**
 * v1.9 A7a step 6 (design 3.2 A7a, R9; the user's crash "while using the magnetic lasso"): the
 * Magnetic lasso's jobs are guarded.
 * - A trace that throws is kept as a quiet report, [StabilityLabels19.ERROR_TOAST] names the tool,
 *   and the tool goes back to straight segments: the touch ends, the edge map goes and is not
 *   built again for the same pixels; the next taps still place anchors and the outline still
 *   closes as ONE selection step. Nothing reaches the scope's handler. The next activation builds
 *   the map again.
 * - The edge map is rendered off the main thread; a render that throws is tried once more, then
 *   kept and said the same way.
 * - A closing whose trace throws still selects the outline as drawn.
 */
@RunWith(RobolectricTestRunner::class)
class MagneticLassoGuardTest {
    private val app: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clean() {
        ShadowLog.stream = null
        CrashLog.deleteAll(app)
        CrashLog.resetRepeats()
        Smoke.scopeErrors.clear()
    }

    @After
    fun drain() {
        CrashLog.drain()
        CrashLog.deleteAll(app)
    }

    private fun tap(c: EditorController, x: Float, y: Float) {
        c.pointerDown(ToolPoint(x, y))
        c.pointerUp(ToolPoint(x, y))
    }

    /** A 400 x 300 canvas with a dark square: edges to follow. */
    private fun editor(): Pair<EditorController, MagneticLassoTool> {
        val doc = Smoke.document(400, 300, layers = 1, whiteBottom = true)
        Canvas(doc.layers[0].bitmap).drawRect(120f, 90f, 280f, 210f, Paint().apply { color = Color.DKGRAY })
        doc.activeLayerIndex = 0
        val c = Smoke.controller(app, doc)
        val tool = c.tools.getValue(ToolId.MAGNETIC_LASSO) as MagneticLassoTool
        return c to tool
    }

    private fun handled(where: String): CrashReport {
        CrashLog.drain()
        val r = CrashLog.reports(app).singleOrNull() ?: throw AssertionError("one report: ${CrashLog.reports(app).map { it.id }}")
        assertTrue(r.id, r.id.endsWith("-crash-quiet"))
        assertEquals("Handled error: IllegalStateException in magnetic lasso: $where", r.summary)
        assertNull("a handled error is never on the gallery's card", CrashLog.unseen(app))
        return r
    }

    @Test
    fun aThrowingTraceIsKeptSaidAndTheToolGoesBackToStraightSegments() {
        val (c, tool) = editor()
        val threads = Collections.synchronizedList(mutableListOf<Thread>())
        val real = tool.renderSource
        tool.renderSource = { threads += Thread.currentThread(); real(it) }
        c.selectTool(ToolId.MAGNETIC_LASSO)
        assertTrue("the edges are found", Smoke.pumpUntil { tool.edgeMapReady && tool.idle })
        assertEquals("rendered once", 1, threads.size)
        assertNotSame("off the main thread", Looper.getMainLooper().thread, threads[0])
        tap(c, 120f, 90f)
        assertTrue(Smoke.pumpUntil { tool.idle })
        assertEquals(1, tool.anchorCount)
        c.message = null

        // The live segment's trace throws while the finger is down.
        var traces = 0
        tool.beforeTrace = { traces++; throw IllegalStateException("trace test") }
        c.pointerDown(ToolPoint(280f, 90f))
        assertNotNull("the finger's loupe", tool.loupe)
        assertTrue(Smoke.pumpUntil { tool.idle && c.message != null })
        assertEquals(StabilityLabels19.ERROR_TOAST.format(ToolId.MAGNETIC_LASSO.label), c.message)
        assertEquals("traced once", 1, traces)
        assertNull("the touch ended: no loupe", tool.loupe)
        assertFalse("the edge map went", tool.edgeMapReady)
        c.pointerUp(ToolPoint(280f, 90f))
        assertEquals("the ended touch placed nothing", 1, tool.anchorCount)
        handled("trace")

        // Straight segments from now on: taps place anchors where the finger is, nothing is traced
        // or rendered again for the same pixels, nothing more is said.
        c.message = null
        tap(c, 280f, 92f)
        tap(c, 280f, 210f)
        tap(c, 121f, 210f)
        assertTrue(Smoke.pumpUntil { tool.idle })
        assertEquals(4, tool.anchorCount)
        for (i in 0 until 3) assertFalse("segment $i stays straight", tool.isTraced(i))
        assertEquals(1, traces)
        assertEquals("no new render for the same pixels", 1, threads.size)
        assertNull(c.message)

        // The outline still closes (the strip's close): ONE selection step.
        val steps = c.undoManager.undoCount
        tool.commit()
        assertTrue("closed", Smoke.pumpUntil { tool.idle && tool.anchorCount == 0 && c.busyMessage == null && c.selection != null })
        assertEquals(steps + 1, c.undoManager.undoCount)
        val sel = c.selection!!
        assertTrue("inside the square", sel.toBytes()[150 * 400 + 200].toInt() != 0)
        assertTrue("outside it", sel.toBytes()[20 * 400 + 20].toInt() == 0)
        Smoke.assertQuiet(c, "a throwing trace")

        // The next activation builds the map again.
        tool.beforeTrace = {}
        c.selectTool(ToolId.LASSO)
        c.selectTool(ToolId.MAGNETIC_LASSO)
        assertTrue(Smoke.pumpUntil { tool.edgeMapReady && tool.idle })
        assertEquals(2, threads.size)
        Smoke.assertQuiet(c, "the next activation")
    }

    @Test
    fun aThrowingRenderIsTriedOnceMoreThenKeptAndSaid() {
        val (c, tool) = editor()
        var tries = 0
        tool.renderSource = { tries++; throw IllegalStateException("render test") }
        c.message = null
        c.selectTool(ToolId.MAGNETIC_LASSO)
        assertTrue(Smoke.pumpUntil { tool.idle && c.message != null })
        assertEquals("tried once more", 2, tries)
        assertFalse(tool.edgeMapReady)
        assertEquals(StabilityLabels19.ERROR_TOAST.format(ToolId.MAGNETIC_LASSO.label), c.message)
        handled("edge map")
        // Touches on the same pixels try nothing again; the anchors still go where the finger is.
        tap(c, 100f, 100f)
        tap(c, 300f, 120f)
        assertTrue(Smoke.pumpUntil { tool.idle })
        assertEquals(2, tries)
        assertEquals(2, tool.anchorCount)
        tool.discard()
        Smoke.assertQuiet(c, "a throwing render")
    }

    @Test
    fun aClosingWhoseTraceThrowsStillSelectsTheOutlineAsDrawn() {
        val (c, tool) = editor()
        c.selectTool(ToolId.MAGNETIC_LASSO)
        assertTrue(Smoke.pumpUntil { tool.edgeMapReady && tool.idle })
        for ((x, y) in listOf(120f to 90f, 280f to 90f, 280f to 210f)) {
            tap(c, x, y)
            assertTrue(Smoke.pumpUntil { tool.idle })
        }
        assertEquals(3, tool.anchorCount)
        c.message = null
        // The closing segment (third anchor to the first) is traced when the outline closes: it throws.
        tool.beforeTrace = { throw IllegalStateException("close test") }
        val steps = c.undoManager.undoCount
        tool.commit()
        assertTrue(Smoke.pumpUntil { tool.idle && c.busyMessage == null && c.selection != null })
        assertEquals(StabilityLabels19.ERROR_TOAST.format(ToolId.MAGNETIC_LASSO.label), c.message)
        assertEquals("ONE selection step", steps + 1, c.undoManager.undoCount)
        assertTrue("the outline is selected", c.selection!!.toBytes()[120 * 400 + 240].toInt() != 0)
        handled("close")
        Smoke.assertQuiet(c, "a throwing closing")
    }
}
