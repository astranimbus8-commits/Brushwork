package com.brushwork.paint.tools.vector.spline

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.vector.VectorContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.7 item 19 (§3.19): with the FIRST point of an open path selected (at least 2 points), a tap
 * on empty canvas (or a numeric add) PREPENDS a new point with the selected point's thickness
 * and keeps it selected, so the path grows from its start. A cyclic path and a single point are
 * unchanged, the last point still appends, a middle point still inserts after itself, and
 * Curve / Polyline keep v1.6 (a tap appends and deselects).
 */
@RunWith(RobolectricTestRunner::class)
class PathPrependRobolectricTest {
    private val w = 400
    private val h = 300

    private fun controller(): EditorController {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("brushwork_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h))
        doc.layers += Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(w, h)).also { it.vector = VectorContent.EMPTY }
        doc.activeLayerIndex = 1
        return EditorController(ctx, doc, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), AppSettings(ctx)).also {
            it.tools
            it.brush = BrushLibrary.defaultBrush.copy(size = 6f, pressureSize = false, taperStart = 0f, taperEnd = 0f)
            it.snapping.enabled = false
        }
    }

    private fun EditorController.tool(id: ToolId): CurveTool {
        selectTool(id)
        return tools.getValue(id) as CurveTool
    }

    private fun EditorController.tap(x: Float, y: Float) {
        pointerDown(ToolPoint(x, y))
        pointerUp(ToolPoint(x, y))
    }

    private fun CurveTool.xs() = spline!!.points.map { it.x }

    private fun CurveTool.positions() = spline!!.points.map { Vec2(it.x, it.y) }

    @Test
    fun twoTapsWithTheFirstPointSelectedExtendThePathFromItsStart() {
        val c = controller()
        val tool = c.tool(ToolId.PATH)
        c.tap(100f, 150f)
        c.tap(300f, 150f)
        tool.setWidth(0, 2f)
        tool.endNumericEdit()
        // Tap the first point to select it, as the user does.
        c.tap(100f, 150f)
        assertEquals(0, tool.selectedPoint)
        c.tap(60f, 60f)
        assertEquals(listOf(60f, 100f, 300f), tool.xs())
        assertEquals("still extending from the start", 0, tool.selectedPoint)
        assertEquals("the selected point's thickness", 2f, tool.spline!!.points[0].width, 0f)
        c.tap(30f, 200f)
        assertEquals(listOf(30f, 60f, 100f, 300f), tool.xs())
        assertEquals(0, tool.selectedPoint)
        assertEquals(2f, tool.spline!!.points[0].width, 0f)
        // The curve now starts at the new first point (clamped ends).
        assertEquals(Vec2(30f, 200f), tool.anchors.first().pos)
        // Undo walks back one point per step.
        assertTrue(tool.undoStep())
        assertEquals(listOf(60f, 100f, 300f), tool.xs())
        assertTrue(tool.undoStep())
        assertEquals(listOf(100f, 300f), tool.xs())
    }

    @Test
    fun aCyclicPathInsertsAfterTheFirstPoint() {
        val c = controller()
        val tool = c.tool(ToolId.PATH)
        c.tap(100f, 100f)
        c.tap(300f, 100f)
        c.tap(200f, 250f)
        tool.setCyclic(true)
        tool.select(0)
        c.tap(40f, 40f)
        assertEquals(listOf(100f, 40f, 300f, 200f), tool.xs())
        assertEquals(1, tool.selectedPoint)
    }

    @Test
    fun aSinglePointAppends() {
        val c = controller()
        val tool = c.tool(ToolId.PATH)
        c.tap(100f, 100f)
        tool.select(0)
        c.tap(200f, 50f)
        assertEquals(listOf(100f, 200f), tool.xs())
        assertEquals(1, tool.selectedPoint)
    }

    @Test
    fun theLastPointStillAppendsAndAMiddleOneInsertsAfterItself() {
        val c = controller()
        val tool = c.tool(ToolId.PATH)
        c.tap(50f, 150f)
        c.tap(200f, 60f)
        c.tap(350f, 150f)
        tool.select(2)
        c.tap(380f, 260f)
        assertEquals(listOf(Vec2(50f, 150f), Vec2(200f, 60f), Vec2(350f, 150f), Vec2(380f, 260f)), tool.positions())
        assertEquals(3, tool.selectedPoint)
        tool.select(1)
        c.tap(200f, 280f)
        assertEquals(
            listOf(Vec2(50f, 150f), Vec2(200f, 60f), Vec2(200f, 280f), Vec2(350f, 150f), Vec2(380f, 260f)),
            tool.positions(),
        )
        assertEquals(2, tool.selectedPoint)
    }

    @Test
    fun aNumericAddFollowsTheSameRule() {
        val c = controller()
        val tool = c.tool(ToolId.PATH)
        c.tap(100f, 150f)
        c.tap(300f, 150f)
        tool.setWidth(0, 0.5f)
        tool.endNumericEdit()
        tool.select(0)
        assertTrue(tool.addAnchor(Vec2(60f, 60f)))
        assertEquals(listOf(60f, 100f, 300f), tool.xs())
        assertEquals(0, tool.selectedPoint)
        assertEquals(0.5f, tool.spline!!.points[0].width, 0f)
        assertTrue(tool.addAnchor(Vec2(30f, 30f)))
        assertEquals(listOf(30f, 60f, 100f, 300f), tool.xs())
        assertEquals(0, tool.selectedPoint)
        // The last point appends.
        tool.select(3)
        assertTrue(tool.addAnchor(Vec2(380f, 30f)))
        assertEquals(listOf(30f, 60f, 100f, 300f, 380f), tool.xs())
        assertEquals(4, tool.selectedPoint)
    }

    @Test
    fun curveAndPolylineWithAnchorZeroSelectedStillAppend() {
        for (id in listOf(ToolId.CURVE, ToolId.POLYLINE)) {
            val c = controller()
            val tool = c.tool(id)
            c.tap(50f, 150f)
            c.tap(350f, 150f)
            tool.select(0)
            assertEquals("$id", 0, tool.selected)
            c.tap(80f, 40f)
            assertEquals("$id", listOf(50f, 350f, 80f), tool.anchors.map { it.x })
            assertEquals("$id deselects", -1, tool.selected)
        }
    }
}
