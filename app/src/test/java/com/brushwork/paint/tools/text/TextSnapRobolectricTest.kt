package com.brushwork.paint.tools.text

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.os.Looper
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import kotlin.math.abs

/**
 * "Snap to objects" of the text tool: a dragged text box snaps like the transform tool (to the
 * canvas center, other layers), the box edge handle snaps its edge, text path points snap, and
 * the text layer being edited is never a target.
 */
@RunWith(RobolectricTestRunner::class)
class TextSnapRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()

    /** Vertical lines to snap to: canvas 0 / 200 / 400, "Layer 1" 100 / 130 / 160. */
    private val xLines = listOf(0f, 200f, 400f, 100f, 130f, 160f)

    /** Horizontal lines: canvas 0 / 150 / 300, "Layer 1" 60 / 90 / 120. */
    private val yLines = listOf(0f, 150f, 300f, 60f, 90f, 120f)

    /** A 400 x 300 canvas at zoom 1, density 1 (snap distance 8 px); "Layer 1" has content at (100, 60)-(160, 120). */
    private fun setup(): Pair<EditorController, TextTool> {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", 400, 300)
        repeat(2) { i -> doc.layers += Layer(doc.newLayerId(), "Layer ${i + 1}", BitmapUtils.createLayerBitmap(400, 300)) }
        doc.activeLayerIndex = 1
        Canvas(doc.layers[0].bitmap).drawRect(Rect(100, 60, 160, 120), Paint().apply { color = 0xFF000000.toInt() })
        doc.layers[0].markChanged()
        val c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        c.selectTool(ToolId.TEXT)
        return c to (c.tools.getValue(ToolId.TEXT) as TextTool)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun text(tool: TextTool, x: Float, y: Float, s: String = "Hi") {
        tool.startTextAt(x, y)
        tool.setText(s)
        tool.setSizePx(30f)
        tool.confirmEditor()
    }

    private fun width(tool: TextTool) = tool.blockFor(tool.item!!).width
    private fun height(tool: TextTool) = tool.blockFor(tool.item!!).height

    /** Fails unless every one of [values] is farther than [min] px from every line of [lines]. */
    private fun assertClear(what: String, values: List<Float>, lines: List<Float>, min: Float = 8f) {
        for (v in values) for (l in lines) assertTrue("$what: $v is ${abs(v - l)} px from line $l", abs(v - l) > min)
    }

    @Test
    fun aDraggedTextSnapsItsCenterToTheCanvasCenter() {
        val (c, tool) = setup()
        text(tool, 150f, 230f)
        val w = width(tool); val h = height(tool)
        // Moved so its center is 3 px left of the canvas center; its sides and height are clear.
        assertClear("sides", listOf(197f - w / 2f, 197f + w / 2f), xLines)
        assertClear("top / center / bottom", listOf(233f - h / 2f, 233f, 233f + h / 2f), yLines)
        c.pointerDown(ToolPoint(150f, 230f))
        c.pointerMove(ToolPoint(175f, 232f))
        c.pointerMove(ToolPoint(197f, 233f))
        assertEquals(200f, tool.item!!.cx, 1e-3f)
        assertEquals(233f, tool.item!!.cy, 1e-3f)
        val g = tool.activeGuides.single { it.axis == SnapAxis.X }
        assertEquals(200f, g.pos)
        assertEquals("Canvas center", g.label)
        c.pointerUp(ToolPoint(197f, 233f))
        assertEquals(200f, tool.item!!.cx, 1e-3f)
        assertTrue("guides are hidden when the finger lifts", tool.activeGuides.isEmpty())
    }

    @Test
    fun aDraggedTextSnapsItsLeftEdgeToAnotherLayersRightEdgeAndCancelLeavesNoTrace() {
        val (c, tool) = setup()
        text(tool, 150f, 230f)
        tool.setFixedBox(true)
        tool.setBoxLength(50f)
        val w = width(tool); val h = height(tool)
        // Left side 3 px right of the layer's right edge (160); center and right side are clear.
        val cx = 163f + w / 2f
        assertClear("center / right", listOf(cx, 163f + w), xLines)
        assertClear("top / center / bottom", listOf(231f - h / 2f, 231f, 231f + h / 2f), yLines)
        c.pointerDown(ToolPoint(150f, 230f))
        c.pointerMove(ToolPoint(170f, 231f))
        c.pointerMove(ToolPoint(cx, 231f))
        assertEquals(160f + w / 2f, tool.item!!.cx, 1e-3f)
        assertEquals("Layer 1 right", tool.activeGuides.single { it.axis == SnapAxis.X }.label)
        // A second finger: back where it was, no guides.
        c.pointerCancel()
        assertEquals(150f, tool.item!!.cx, 1e-3f)
        assertEquals(230f, tool.item!!.cy, 1e-3f)
        assertTrue(tool.activeGuides.isEmpty())
    }

    @Test
    fun theTextLayerBeingEditedIsNotATarget() {
        val (c, tool) = setup()
        text(tool, 300f, 230f)
        assertTrue(tool.commitItem())
        val layer = c.doc.layers.last()
        assertTrue(layer.isTextLayer)
        // Re-open it: its old pixels (still in the layer, only hidden) are not snapped to.
        c.pointerDown(ToolPoint(300f, 230f)); c.pointerUp(ToolPoint(300f, 230f))
        idle()
        assertSame(layer, tool.editingLayer)
        val w = width(tool); val h = height(tool)
        assertClear("sides", listOf(305f - w / 2f, 305f, 305f + w / 2f), xLines)
        assertClear("top / bottom", listOf(231f - h / 2f, 231f, 231f + h / 2f), yLines)
        c.pointerDown(ToolPoint(300f, 230f))
        c.pointerMove(ToolPoint(320f, 230f))
        c.pointerMove(ToolPoint(305f, 231f))
        // 5 px from where it was: it would snap back onto its own old ink if that were a target.
        assertEquals(305f, tool.item!!.cx, 1e-3f)
        assertEquals(231f, tool.item!!.cy, 1e-3f)
        assertTrue(tool.activeGuides.isEmpty())
        c.pointerUp(ToolPoint(305f, 231f))
    }

    @Test
    fun theBoxEdgeHandleSnapsTheDraggedEdge() {
        val (c, tool) = setup()
        text(tool, 300f, 230f)
        tool.setFixedBox(true)
        tool.setBoxLength(50f)
        val w0 = width(tool)
        val left = 300f - w0 / 2f
        // The width handle sits on the right edge, 6 px (the box padding) outside it.
        val grip = 300f + w0 / 2f + 6f
        c.pointerDown(ToolPoint(grip, 230f))
        c.pointerMove(ToolPoint(grip + 30f, 230f))
        // The right edge 3 px short of the canvas edge: it lands on it, the left edge stays.
        c.pointerMove(ToolPoint(397f + 6f, 230f))
        assertEquals(400f - left, width(tool), 0.01f)
        assertEquals(left, tool.item!!.cx - width(tool) / 2f, 0.01f)
        assertTrue(tool.activeGuides.any { it.axis == SnapAxis.X && it.pos == 400f })
        c.pointerUp(ToolPoint(397f + 6f, 230f))
        assertTrue(tool.activeGuides.isEmpty())
        // Snapping off: the edge follows the finger.
        c.snapping.enabled = false
        val grip2 = 400f + 6f
        c.pointerDown(ToolPoint(grip2, 230f))
        c.pointerMove(ToolPoint(grip2 - 30f, 230f))
        c.pointerMove(ToolPoint(grip2 - 3f, 230f))
        assertEquals(397f - left, width(tool), 0.01f)
        c.pointerUp(ToolPoint(grip2 - 3f, 230f))
    }

    @Test
    fun textPathPointsSnapToTheOtherPointsOfThePath() {
        val (c, tool) = setup()
        text(tool, 300f, 230f)
        tool.setPath(TextPathSpec(type = TextPathType.LINE))
        tool.setPath(tool.item!!.path.copy(x1 = 250f, y1 = 200f, x2 = 350f, y2 = 260f))
        assertNotNull(tool.item)
        c.pointerDown(ToolPoint(350f, 260f))
        c.pointerMove(ToolPoint(360f, 240f))
        c.pointerMove(ToolPoint(370f, 205f))
        val p = tool.item!!.path
        assertEquals(370f, p.x2, 1e-3f)
        assertEquals("level with the line's start", 200f, p.y2, 1e-3f)
        val g = tool.activeGuides.single { it.axis == SnapAxis.Y }
        assertEquals("Path point", g.label)
        c.pointerUp(ToolPoint(370f, 205f))
        assertTrue(tool.activeGuides.isEmpty())
    }

    @Test
    fun theCircleRadiusHandleSnapsSoTheCircleTouchesALine() {
        val (c, tool) = setup()
        text(tool, 300f, 230f)
        tool.setPath(TextPathSpec(type = TextPathType.CIRCLE))
        // Center (300, 40), radius 50: the radius handle sits below the center (opposite the text).
        tool.setPath(tool.item!!.path.copy(cx = 300f, cy = 40f, radius = 50f, startAngleDeg = -90f))
        assertEquals(Vec2(300f, 90f), TextOnPath.handles(tool.item!!.path)[1])
        c.pointerDown(ToolPoint(300f, 90f))
        c.pointerMove(ToolPoint(301f, 100f))
        // The finger 3 px right of the handle's line: radius hypot(3, 77) = 77.06, so the circle's
        // bottom (117.06) is 3 px from the layer's bottom edge (120); its sides (223 / 377) and
        // top are clear. The radius makes the circle touch that edge exactly.
        c.pointerMove(ToolPoint(303f, 117f))
        assertEquals(80f, tool.item!!.path.radius, 1e-3f)
        val g = tool.activeGuides.single()
        assertEquals(SnapAxis.Y, g.axis)
        assertEquals(120f, g.pos)
        assertEquals("Layer 1 bottom", g.label)
        // Farther than the snap distance (radius 65: every side clear): it follows the finger again.
        c.pointerMove(ToolPoint(300f, 105f))
        assertEquals(65f, tool.item!!.path.radius, 1e-3f)
        assertTrue(tool.activeGuides.isEmpty())
        c.pointerMove(ToolPoint(303f, 117f))
        c.pointerUp(ToolPoint(303f, 117f))
        assertEquals(80f, tool.item!!.path.radius, 1e-3f)
        assertTrue("guides are hidden when the finger lifts", tool.activeGuides.isEmpty())
        // Snapping off: exactly the finger's distance from the center, as before.
        c.snapping.enabled = false
        c.drag(300f to 120f, 300f to 105f, 303f to 117f)
        assertEquals(Vec2(3f, 77f).length, tool.item!!.path.radius, 1e-3f)
        assertTrue(tool.activeGuides.isEmpty())
    }

    @Test
    fun aSquarePathsSizeCornerSnapsItsSideOntoALine() {
        val (c, tool) = setup()
        text(tool, 300f, 230f)
        tool.setPath(TextPathSpec(type = TextPathType.RECT))
        tool.setPath(tool.item!!.path.copy(cx = 300f, cy = 200f, width = 60f, height = 60f, rotationDeg = 0f, keepSquare = true))
        assertEquals(Vec2(330f, 230f), TextOnPath.handles(tool.item!!.path)[1])
        c.pointerDown(ToolPoint(330f, 230f))
        c.pointerMove(ToolPoint(340f, 238f))
        // Half side (45 + 49) / 2 = 47: the top (153) is 3 px from the canvas center line (150).
        c.pointerMove(ToolPoint(345f, 249f))
        val p = tool.item!!.path
        assertEquals(100f, p.width, 1e-3f)
        assertEquals(100f, p.height, 1e-3f)
        assertTrue(tool.activeGuides.any { it.axis == SnapAxis.Y && it.pos == 150f })
        c.pointerUp(ToolPoint(345f, 249f))
        assertTrue(tool.activeGuides.isEmpty())
    }

    private fun EditorController.drag(vararg pts: Pair<Float, Float>) {
        pointerDown(ToolPoint(pts[0].first, pts[0].second))
        for (i in 1 until pts.size) pointerMove(ToolPoint(pts[i].first, pts[i].second))
        pointerUp(ToolPoint(pts.last().first, pts.last().second))
    }
}
