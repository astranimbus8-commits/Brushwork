package com.brushwork.paint.tools.vector

import android.content.Context
import android.graphics.Matrix
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.6 §3.3 (G): the shape tool's Handles group, through the tool's API (the strip's controls call
 * exactly these). On an ellipse converted to points: a slider drag (begin … several values …
 * end) is ONE in-tool undo step relative to the handles when it began, and reads 100 % again at
 * rest; ‹ › multiply by 1.1 / 0.9, or step by the Scale increment (110 %, 120 %); a typed value is
 * exact; a selected point alone, or every point (none selected, or "All points"); In / Out alone.
 */
@RunWith(RobolectricTestRunner::class)
class ShapeHandleScaleRobolectricTest {

    private val scopes = ArrayList<CoroutineScope>()

    @After
    fun releaseEditors() {
        for (s in scopes) s.cancel()
        scopes.clear()
    }

    private fun controller(): EditorController {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("brushwork_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val doc = Document("t", "t", 200, 200)
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(200, 200))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it }
        return EditorController(ctx, doc, scope, AppSettings(ctx)).also {
            it.color = 0xFFFF0000.toInt()
            it.viewTransform.set(Matrix())
            it.tools
        }
    }

    /** A pending ellipse (40..120, 80..160) converted to its 4 smooth points. */
    private fun ellipsePoints(c: EditorController): ShapeTool {
        c.selectTool(ToolId.SHAPE)
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        tool.update { it.copy(type = ShapeType.ELLIPSE, style = ShapeStyle.STROKE, useBrushSize = false, strokeWidth = 4f, keepProportions = false, fromCenter = false) }
        c.pointerDown(ToolPoint(40f, 80f)); c.pointerMove(ToolPoint(80f, 120f)); c.pointerMove(ToolPoint(120f, 160f)); c.pointerUp(ToolPoint(120f, 160f))
        tool.setPointEditing(true)
        assertEquals(4, tool.docAnchors()!!.size)
        return tool
    }

    private fun handleOut(tool: ShapeTool, i: Int) = ShapePoints.handles(tool.docAnchors()!!, i, true).second.length
    private fun handleIn(tool: ShapeTool, i: Int) = ShapePoints.handles(tool.docAnchors()!!, i, true).first.length

    @Test
    fun aSliderDragIsOneStepRelativeToWhereItBegan() {
        val c = controller()
        val tool = ellipsePoints(c)
        tool.selectPoint(0)
        val out0 = handleOut(tool, 0)
        val other0 = handleOut(tool, 1)
        val steps0 = c.undoManager.undoCount
        assertEquals(1f, tool.handleScale, 0f)
        // A drag of the slider: 120 %, 180 %, 150 %: one change from where it began.
        assertTrue(tool.beginHandleScale())
        tool.scaleHandlesTo(1.2f)
        tool.scaleHandlesTo(1.8f)
        tool.scaleHandlesTo(1.5f)
        assertEquals(1.5f, tool.handleScale, 0f)
        tool.endHandleScale()
        assertEquals("100 % again at rest", 1f, tool.handleScale, 0f)
        assertEquals(out0 * 1.5f, handleOut(tool, 0), 1e-3f)
        assertEquals("the other points keep theirs", other0, handleOut(tool, 1), 1e-3f)
        assertEquals("in-tool steps only (✓ makes the one document step)", steps0, c.undoManager.undoCount)
        // One undo takes the whole drag back.
        assertTrue(tool.undoStep())
        assertEquals(out0, handleOut(tool, 0), 1e-3f)
        assertTrue(tool.redoStep())
        assertEquals(out0 * 1.5f, handleOut(tool, 0), 1e-3f)
        // The next change is relative to the handles as they are now.
        tool.scaleHandles(2f)
        assertEquals(out0 * 3f, handleOut(tool, 0), 1e-3f)
        assertTrue(tool.undoStep())
        assertEquals(out0 * 1.5f, handleOut(tool, 0), 1e-3f)
    }

    @Test
    fun arrowsMultiplyOrStepByTheScaleIncrement() {
        val c = controller()
        val tool = ellipsePoints(c)
        tool.selectPoint(2)
        val out0 = handleOut(tool, 2)
        // Held ›: × 1.1, × 1.21 …; the release ends it (one step).
        tool.stepHandles(longer = true)
        tool.stepHandles(longer = true)
        assertEquals(1.21f, tool.handleScale, 1e-4f)
        tool.endHandleScale()
        assertEquals(out0 * 1.21f, handleOut(tool, 2), 1e-3f)
        assertTrue(tool.undoStep())
        assertEquals(out0, handleOut(tool, 2), 1e-3f)
        // ‹ : × 0.9.
        tool.stepHandles(longer = false)
        tool.endHandleScale()
        assertEquals(out0 * 0.9f, handleOut(tool, 2), 1e-3f)
        assertTrue(tool.undoStep())
        // With increments on (Scale 10 %): 110 %, 120 %, back down 110 %.
        c.increments.update { it.copy(enabled = true, scalePercent = 10f) }
        tool.stepHandles(longer = true)
        assertEquals(1.1f, tool.handleScale, 1e-5f)
        tool.stepHandles(longer = true)
        assertEquals(1.2f, tool.handleScale, 1e-5f)
        tool.stepHandles(longer = false)
        assertEquals(1.1f, tool.handleScale, 1e-5f)
        tool.endHandleScale()
        assertEquals(out0 * 1.1f, handleOut(tool, 2), 1e-3f)
        // A typed value stays exactly as typed.
        tool.scaleHandles(1.37f)
        assertEquals(out0 * 1.1f * 1.37f, handleOut(tool, 2), 1e-3f)
    }

    @Test
    fun allPointsInAndOut() {
        val c = controller()
        val tool = ellipsePoints(c)
        val outs = (0 until 4).map { handleOut(tool, it) }
        val ins = (0 until 4).map { handleIn(tool, it) }
        // No point selected: every point.
        tool.selectPoint(-1)
        tool.scaleHandles(2f)
        for (i in 0 until 4) assertEquals(outs[i] * 2f, handleOut(tool, i), 1e-3f)
        assertTrue(tool.undoStep())
        // A selected point with "All points": every point too.
        tool.selectPoint(1)
        tool.handlesAllPoints = true
        tool.scaleHandles(0.5f)
        for (i in 0 until 4) assertEquals(outs[i] * 0.5f, handleOut(tool, i), 1e-3f)
        assertTrue(tool.undoStep())
        tool.handlesAllPoints = false
        // In only, on the selected point: its out handle stays.
        tool.handleSide = ShapeHandleSide.IN
        tool.scaleHandles(3f)
        assertEquals(ins[1] * 3f, handleIn(tool, 1), 1e-3f)
        assertEquals(outs[1], handleOut(tool, 1), 1e-3f)
        assertEquals(ins[0], handleIn(tool, 0), 1e-3f)
        // ✓: one document step for everything.
        val steps0 = c.undoManager.undoCount
        tool.commit()
        assertEquals(steps0 + 1, c.undoManager.undoCount)
        assertFalse(tool.handleScaling)
    }
}
