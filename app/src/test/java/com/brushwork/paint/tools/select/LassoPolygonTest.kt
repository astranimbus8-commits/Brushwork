package com.brushwork.paint.tools.select

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.ui.editor.HistoryLabels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/** Polygon lasso: one corner per undo / redo, draggable corners while the polygon is open. */
@RunWith(RobolectricTestRunner::class)
class LassoPolygonTest {

    private fun controller(): EditorController {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("brushwork_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val doc = Document("t", "t", 200, 200)
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(200, 200))
        return EditorController(ctx, doc, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), AppSettings(ctx))
    }

    private fun lasso(c: EditorController): LassoTool {
        c.selectTool(ToolId.LASSO)
        return (c.tools.getValue(ToolId.LASSO) as LassoTool).also { it.setPolygonMode(true) }
    }

    private fun EditorController.tap(x: Float, y: Float) {
        pointerDown(ToolPoint(x, y)); pointerUp(ToolPoint(x, y))
    }

    private fun EditorController.drag(vararg pts: Pair<Float, Float>) {
        pointerDown(ToolPoint(pts[0].first, pts[0].second))
        for (i in 1 until pts.size) pointerMove(ToolPoint(pts[i].first, pts[i].second))
        pointerUp(ToolPoint(pts.last().first, pts.last().second))
    }

    @Test
    fun undoTakesBackOneCornerAndRedoBringsItBack() {
        val c = controller()
        val tool = lasso(c)
        c.tap(50f, 50f); c.tap(150f, 50f); c.tap(150f, 150f)
        assertEquals(3, tool.vertexCount)
        // The feedback of the two-finger tap / undo button names one point, not the polygon.
        assertEquals("Undo: last corner", HistoryLabels.undo(c))
        c.undo()
        assertEquals("only the last corner is gone", 2, tool.vertexCount)
        assertTrue(tool.hasPendingWork)
        assertTrue(tool.canRedoStep)
        assertEquals("Redo: last corner", HistoryLabels.redo(c))
        c.redo()
        assertEquals(3, tool.vertexCount)
        assertEquals(150f to 150f, tool.corner(2))
        assertFalse(tool.canRedoStep)
        // The in-tool buttons do the same.
        tool.undoLastCorner()
        assertEquals(2, tool.vertexCount)
        tool.redoStep()
        assertEquals(3, tool.vertexCount)
        // A new corner drops what could be redone.
        c.undo()
        c.tap(60f, 160f)
        assertEquals(3, tool.vertexCount)
        assertEquals(0, tool.redoCount)
        // Undo down to nothing, then the polygon is gone and the document history is next.
        repeat(3) { c.undo() }
        assertFalse(tool.hasPendingWork)
        assertFalse(c.canUndo)
        // The in-tool redo still brings the corners back.
        assertTrue(tool.redoStep())
        assertEquals(1, tool.vertexCount)
    }

    @Test
    fun cornersCanBeDraggedWhileThePolygonIsOpen() {
        val c = controller()
        val tool = lasso(c)
        c.tap(50f, 50f); c.tap(150f, 50f); c.tap(150f, 150f)
        // 14 px from the second corner: the touch drags it instead of adding a corner.
        c.drag(160f to 60f, 170f to 50f, 175f to 40f)
        assertEquals(3, tool.vertexCount)
        assertEquals(175f to 40f, tool.corner(1))
        // Dragging the first corner moves it (a tap there would close the polygon).
        c.drag(52f to 52f, 40f to 70f, 30f to 80f)
        assertEquals(3, tool.vertexCount)
        assertEquals(30f to 80f, tool.corner(0))
        assertTrue(tool.hasPendingWork)
        // Each drag is one undo step.
        c.undo()
        assertEquals(50f to 50f, tool.corner(0))
        c.undo()
        assertEquals(150f to 50f, tool.corner(1))
        assertEquals(3, tool.vertexCount)
        // A drag cancelled by a second finger puts the corner back.
        c.pointerDown(ToolPoint(150f, 150f))
        c.pointerMove(ToolPoint(120f, 180f))
        assertEquals(120f to 180f, tool.corner(2))
        c.pointerCancel()
        assertEquals(150f to 150f, tool.corner(2))
        assertEquals(2, tool.redoCount)
        // A tap on the first corner still closes the polygon into a selection.
        c.tap(50f, 50f)
        assertFalse(tool.hasPendingWork)
        val deadline = System.currentTimeMillis() + 10_000
        while (c.selection == null && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            Thread.sleep(5)
        }
        assertNotNull(c.selection)
        assertTrue(c.selection!!.mask.getPixel(130, 70) ushr 24 > 0)
    }

    @Test
    fun rubberBandDragStillPlacesANewCorner() {
        val c = controller()
        val tool = lasso(c)
        c.tap(50f, 50f)
        // Starting away from the corners, a drag places a corner where the finger lifts.
        c.drag(120f to 120f, 140f to 150f, 150f to 160f)
        assertEquals(2, tool.vertexCount)
        assertEquals(150f to 160f, tool.corner(1))
        assertEquals(50f to 50f, tool.corner(0))
    }
}
