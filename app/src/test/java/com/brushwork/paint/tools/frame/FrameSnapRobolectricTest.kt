package com.brushwork.paint.tools.frame

import android.graphics.Matrix
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.transform.SnapAxis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.math.abs

/**
 * "Snap to objects" of the frame divider: a cut starts on the canvas center or on the line that
 * lines its panels up with the neighbouring column's panels; the cut's own straightening wins.
 */
@RunWith(RobolectricTestRunner::class)
class FrameSnapRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()

    /**
     * A 400 x 300 page at zoom 1, density 1 (snap distance 8 px) with a frame layer: margins 15,
     * one panel (15, 15)-(385, 285), gutters 8 between rows and 4 between columns.
     */
    private fun setup(): Pair<EditorController, FrameDividerTool> {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", 400, 300)
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(400, 300))
        val c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        c.selectTool(ToolId.FRAME_DIVIDER)
        val tool = c.tools.getValue(ToolId.FRAME_DIVIDER) as FrameDividerTool
        assertTrue(tool.createFrameLayer())
        assertEquals(8f, tool.settings.gutterH)
        assertEquals(4f, tool.settings.gutterV)
        return c to tool
    }

    private fun EditorController.drag(vararg pts: Pair<Float, Float>) {
        pointerDown(ToolPoint(pts[0].first, pts[0].second))
        for (i in 1 until pts.size) pointerMove(ToolPoint(pts[i].first, pts[i].second))
        pointerUp(ToolPoint(pts.last().first, pts.last().second))
    }

    private fun FrameRect.near(l: Float, t: Float, r: Float, b: Float) =
        abs(left - l) < 0.01f && abs(top - t) < 0.01f && abs(right - r) < 0.01f && abs(bottom - b) < 0.01f

    private fun hasPanel(tool: FrameDividerTool, l: Float, t: Float, r: Float, b: Float): Boolean =
        tool.model!!.panels.any { it.bounds().near(l, t, r, b) }

    @Test
    fun aCutStartedNearTheCanvasCenterDividesExactlyThere() {
        val (c, tool) = setup()
        c.pointerDown(ToolPoint(197f, 5f))
        c.pointerMove(ToolPoint(198f, 150f))
        assertTrue(tool.activeGuides.any { it.axis == SnapAxis.X && it.pos == 200f })
        c.pointerUp(ToolPoint(198f, 295f))
        assertTrue("guides are hidden when the finger lifts", tool.activeGuides.isEmpty())
        assertEquals(2, tool.model!!.panels.size)
        // The 4 px gutter is centered on x = 200.
        assertTrue(hasPanel(tool, 15f, 15f, 198f, 285f))
        assertTrue(hasPanel(tool, 202f, 15f, 385f, 285f))
    }

    @Test
    fun aCutLinesItsPanelsUpWithTheNeighbouringColumn() {
        val (c, tool) = setup()
        c.drag(197f to 5f, 198f to 150f, 198f to 295f)
        // Left column: a cut at y = 100 (nothing to snap to there).
        c.drag(10f to 100f, 100f to 101f, 190f to 102f)
        assertTrue(hasPanel(tool, 15f, 15f, 198f, 96f))
        assertTrue(hasPanel(tool, 15f, 104f, 198f, 285f))
        // Right column, started 3 px off: it snaps to the line that lines the panels up.
        c.pointerDown(ToolPoint(390f, 103f))
        c.pointerMove(ToolPoint(300f, 102f))
        assertTrue(tool.activeGuides.any { it.axis == SnapAxis.Y && abs(it.pos - 100f) < 0.01f && it.label == "Gutter line" })
        c.pointerUp(ToolPoint(210f, 101f))
        assertTrue(hasPanel(tool, 202f, 15f, 385f, 96f))
        assertTrue(hasPanel(tool, 202f, 104f, 385f, 285f))
        assertEquals(4, tool.model!!.panels.size)
    }

    @Test
    fun withSnappingOffCutsAreExactlyWhereTheFingerIs() {
        val (c, tool) = setup()
        c.snapping.enabled = false
        c.drag(197f to 5f, 198f to 150f, 198f to 295f)
        // Straightened (within 5°) at the finger's x, as before.
        assertTrue(hasPanel(tool, 15f, 15f, 195f, 285f))
        assertTrue(hasPanel(tool, 199f, 15f, 385f, 285f))
        assertTrue(tool.activeGuides.isEmpty())
    }

    @Test
    fun aTapStaysATapAndACancelledCutLeavesNoGuide() {
        val (c, tool) = setup()
        c.pointerDown(ToolPoint(197f, 147f)); c.pointerUp(ToolPoint(197f, 147f))
        assertEquals(1, tool.model!!.panels.size)
        assertTrue(tool.activeGuides.isEmpty())
        c.pointerDown(ToolPoint(197f, 5f))
        c.pointerMove(ToolPoint(198f, 150f))
        c.pointerCancel()
        assertTrue(tool.activeGuides.isEmpty())
        assertEquals(1, tool.model!!.panels.size)
    }
}
