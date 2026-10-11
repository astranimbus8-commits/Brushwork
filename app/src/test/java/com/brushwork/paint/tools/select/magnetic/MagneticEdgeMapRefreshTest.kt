package com.brushwork.paint.tools.select.magnetic

import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.EditorController
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.select.SampleSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import kotlin.math.abs

/**
 * v1.8 (C, review): the magnetic lasso's edge map follows the pixels Refer names. A stroke
 * painted with another tool is snapped to when the user comes back; an undo or a redo of it
 * while the tool is active is seen on the next touch (its anchor never snaps to the edge that
 * is gone). A pixel undo changes the layer's `contentVersion` but not `layersVersion`, which was
 * all the map's key had: the stale map stayed until the tool was picked again.
 */
@RunWith(RobolectricTestRunner::class)
class MagneticEdgeMapRefreshTest {

    private fun EditorController.stroke(y: Float) {
        pointerDown(ToolPoint(40f, y))
        for (i in 1..30) pointerMove(ToolPoint(40f + 10f * i, y))
        pointerUp(ToolPoint(340f, y))
    }

    @Test
    fun aStrokeAndItsUndoAreSeenByTheEdgeMap() {
        ShadowLog.stream = null
        val c = Smoke.controller(ApplicationProvider.getApplicationContext(), Smoke.document(400, 300, whiteBottom = true))
        c.selectTool(ToolId.MAGNETIC_LASSO)
        val tool = c.currentTool as MagneticLassoTool
        assertEquals(SampleSource.LAYER, tool.settings.source)
        assertTrue(Smoke.pumpUntil { tool.edgeMapReady && tool.idle })
        // The active layer is empty: nothing to snap to.
        assertEquals(200f, tool.snapped(200f, 172f).x)
        assertEquals(172f, tool.snapped(200f, 172f).y)

        // A black stroke on the active layer, painted with the brush (its lower edge at y 162).
        c.selectTool(ToolId.BRUSH)
        c.brush = c.brush.copy(size = 24f, hardness = 1f, pressureSize = false, minSizeRatio = 1f, opacity = 1f, flow = 1f)
        val steps = c.undoManager.undoCount
        c.stroke(150f)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(0xFF, c.activeLayer.bitmap.getPixel(200, 150) ushr 24)

        // Back to the magnetic lasso: a new map, and a point near the stroke snaps to its edge.
        c.selectTool(ToolId.MAGNETIC_LASSO)
        assertTrue(Smoke.pumpUntil { tool.edgeMapReady && tool.idle })
        val near = tool.snapped(200f, 172f)
        assertNotEquals("the stroke's edge is snapped to", 172f, near.y)
        assertTrue("on the stroke's lower edge: ${near.y}", abs(near.y - 162f) <= 4f)

        // The stroke is undone (nothing pending in the tool: a document step).
        assertFalse(tool.hasPendingWork)
        c.undo()
        assertEquals(steps, c.undoManager.undoCount)
        assertEquals(0, c.activeLayer.bitmap.getPixel(200, 150) ushr 24)
        // The next touch sees it: its anchor stays where the finger is, as no edge is there.
        c.pointerDown(ToolPoint(200f, 172f))
        c.pointerUp(ToolPoint(200f, 172f))
        assertEquals(1, tool.anchorCount)
        assertEquals(200f, tool.points.x(0))
        assertEquals(172f, tool.points.y(0))
        assertTrue(Smoke.pumpUntil { tool.edgeMapReady && tool.idle })
        assertEquals(172f, tool.snapped(200f, 172f).y)

        // The outline dropped, the stroke redone: the next touch snaps to it again.
        tool.discard()
        assertFalse(tool.hasPendingWork)
        c.redo()
        assertEquals(steps + 1, c.undoManager.undoCount)
        c.pointerDown(ToolPoint(200f, 30f))
        c.pointerCancel()
        assertTrue(Smoke.pumpUntil { tool.edgeMapReady && tool.idle })
        assertTrue(abs(tool.snapped(200f, 172f).y - 162f) <= 4f)
        Smoke.assertQuiet(c, "magnetic edge map refresh")
    }
}
