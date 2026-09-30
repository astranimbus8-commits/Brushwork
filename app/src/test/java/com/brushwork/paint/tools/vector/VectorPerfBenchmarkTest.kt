package com.brushwork.paint.tools.vector

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.BrushTool
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.editor.CanvasView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Micro-benchmarks of editing curves and shapes on a phone-sized document (1080 x 2408, two
 * layers) through the real canvas view: 60 frames of a finger dragging an anchor / tangent
 * handle / shape handle with the default brush ("Current brush") or a plain line.
 *
 * The budgets are generous (a busy build machine must pass): v1.2 took 30 to 80 ms per frame
 * (worst frames 55 to 165 ms, 4400 to 7500 dabs and 1 to 1.8 MB of allocations per frame) for
 * the brush drags on the machine these numbers were measured on, and re-rendered 6 to 9 display
 * tiles per frame for the plain ones.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi")
class VectorPerfBenchmarkTest {
    private lateinit var activity: ComponentActivity
    private lateinit var c: EditorController
    private lateinit var view: CanvasView
    private lateinit var h: VectorPerfHarness

    private val anchors = listOf(Vec2(150f, 300f), Vec2(900f, 700f), Vec2(200f, 1200f), Vec2(900f, 1700f), Vec2(300f, 2100f))

    @Before
    fun setUp() {
        ShadowLog.clear()
        Smoke.scopeErrors.clear()
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        c = Smoke.controller(activity, Smoke.document(1080, 2408, 2, whiteBottom = true))
        view = CanvasView(activity, c)
        activity.setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        Smoke.pump(100)
        assertTrue(view.width > 0 && view.height > 0)
        c.tools
        c.brush = BrushLibrary.defaultBrush
        c.color = 0xFF202020.toInt()
        h = VectorPerfHarness(c, view)
    }

    @After
    fun tearDown() {
        c.currentTool.discard()
        Smoke.pump(50)
        c.dispose()
    }

    private fun curve(polyline: Boolean, settings: (CurveSettings) -> CurveSettings): CurveTool {
        val id = if (polyline) ToolId.POLYLINE else ToolId.CURVE
        c.selectTool(id)
        val tool = c.tools.getValue(id) as CurveTool
        tool.update(settings)
        for (a in anchors) h.tap(a)
        h.settle()
        return tool
    }

    private fun moving(from: Vec2, dx: Float, dy: Float): (Int) -> Vec2 = { f -> Vec2(from.x + dx * (f + 1), from.y + dy * (f + 1)) }

    /** A drag with the live brush stroke: one bounded replay per frame, exact again after the drag. */
    private fun VectorPerfHarness.Stats.withinBrushBudget(): VectorPerfHarness.Stats {
        assertTrue("$name: ${"%.2f".format(avgFrameMs)} ms per frame", avgFrameMs < 25.0)
        assertTrue("$name: 90th percentile frame ${"%.2f".format(p90FrameMs)} ms", p90FrameMs < 40.0)
        assertTrue("$name: ${"%.0f".format(stampsPerFrame)} dabs per frame", stampsPerFrame < 3500.0)
        assertTrue("$name: $maxFrameStamps dabs in the worst frame", maxFrameStamps < 4000)
        assertTrue("$name: ${"%.0f".format(allocKbPerFrame)} KB allocated per frame", allocKbPerFrame < 250.0)
        // After the drag the stroke becomes exact over several frames, not in one long stall.
        assertTrue("$name: $releaseMaxFrameStamps dabs in one frame after the drag", releaseMaxFrameStamps < 2000)
        assertTrue("$name: 90th percentile frame after the drag ${"%.2f".format(releaseP90FrameMs)} ms", releaseP90FrameMs < 40.0)
        val brush = c.tools.getValue(ToolId.BRUSH) as BrushTool
        assertTrue("$name: the live stroke is still shown", brush.isStroking)
        assertFalse("$name: the stroke is exact again after the drag", brush.isDraft)
        return this
    }

    /** A drag of a plain line / shape: drawn in the overlay, the canvas tiles are never re-rendered. */
    private fun VectorPerfHarness.Stats.withinPlainBudget(): VectorPerfHarness.Stats {
        assertEquals("$name: display tiles re-rendered while dragging", 0L, tiles)
        assertEquals("$name: dabs", 0L, stamps)
        assertTrue("$name: ${"%.2f".format(avgFrameMs)} ms per frame", avgFrameMs < 10.0)
        return this
    }

    private fun shape(settings: (ShapeSettings) -> ShapeSettings): ShapeTool {
        c.selectTool(ToolId.SHAPE)
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        tool.update(settings)
        return tool
    }

    // ------------------------------------------------------------------ curves

    @Test
    fun curveBrushDragLastAnchor() {
        curve(false) { it.copy(stroke = CurveStroke.BRUSH, fill = false, taper = false) }
        h.drag("curve brush: last anchor", anchors[4], moving(anchors[4], 5f, -4f), warmup = 10).withinBrushBudget()
    }

    @Test
    fun curveBrushDragMiddleAnchor() {
        curve(false) { it.copy(stroke = CurveStroke.BRUSH, fill = false, taper = false) }
        h.drag("curve brush: middle anchor", anchors[2], moving(anchors[2], 6f, 3f), warmup = 10).withinBrushBudget()
    }

    @Test
    fun curveBrushDragTangentHandle() {
        val tool = curve(false) { it.copy(stroke = CurveStroke.BRUSH, fill = false, taper = false) }
        h.tap(anchors[2])
        check(tool.selected == 2)
        val grip = anchors[2] + tool.handlesOf(2).second
        h.drag("curve brush: tangent handle", grip, moving(grip, 4f, 6f), warmup = 10).withinBrushBudget()
    }

    @Test
    fun curveBrushTaperDragMiddleAnchor() {
        curve(false) { it.copy(stroke = CurveStroke.BRUSH, fill = false, taper = true) }
        h.drag("curve brush taper: middle anchor", anchors[2], moving(anchors[2], 6f, 3f), warmup = 10).withinBrushBudget()
    }

    @Test
    fun curveBrushFillDragMiddleAnchor() {
        curve(false) { it.copy(stroke = CurveStroke.BRUSH, fill = true, closed = true, taper = false) }
        h.drag("curve brush+fill: middle anchor", anchors[2], moving(anchors[2], 6f, 3f), warmup = 10).withinBrushBudget()
    }

    @Test
    fun curvePlainDragMiddleAnchor() {
        curve(false) { it.copy(stroke = CurveStroke.PLAIN, fill = false) }
        h.drag("curve plain: middle anchor", anchors[2], moving(anchors[2], 6f, 3f), warmup = 10).withinPlainBudget()
    }

    @Test
    fun polylineBrushDragCorner() {
        curve(true) { it.copy(stroke = CurveStroke.BRUSH, fill = false, taper = false) }
        h.drag("polyline brush: corner", anchors[3], moving(anchors[3], -6f, 3f), warmup = 10).withinBrushBudget()
    }

    // ------------------------------------------------------------------ shapes

    @Test
    fun shapeCreatePlainRectangle() {
        shape { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE, strokeWith = ShapeStroke.PLAIN) }
        val from = Vec2(100f, 200f)
        h.drag("shape plain rect: drag-create", from, moving(from, 14f, 32f), warmup = 5).withinPlainBudget()
    }

    @Test
    fun shapeResizePlainRectangle() {
        shape { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE, strokeWith = ShapeStroke.PLAIN) }
        h.drag("(setup)", Vec2(100f, 200f), moving(Vec2(100f, 200f), 15f, 35f), frames = 60)
        h.settle()
        val grip = Vec2(1000f, 2300f)
        h.drag("shape plain rect: resize handle", grip, moving(grip, -5f, -8f), warmup = 10).withinPlainBudget()
    }

    @Test
    fun shapeResizeFilledEllipse() {
        shape { it.copy(type = ShapeType.ELLIPSE, style = ShapeStyle.STROKE_FILL, strokeWith = ShapeStroke.PLAIN) }
        h.drag("(setup)", Vec2(100f, 200f), moving(Vec2(100f, 200f), 15f, 35f), frames = 60)
        h.settle()
        val grip = Vec2(1000f, 2300f)
        h.drag("shape plain ellipse+fill: resize", grip, moving(grip, -5f, -8f), warmup = 10).withinPlainBudget()
    }

    @Test
    fun shapeResizeBrushRectangle() {
        shape { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE, strokeWith = ShapeStroke.BRUSH) }
        h.drag("(setup)", Vec2(100f, 200f), moving(Vec2(100f, 200f), 15f, 35f), frames = 60)
        h.settle()
        val grip = Vec2(1000f, 2300f)
        h.drag("shape brush rect: resize handle", grip, moving(grip, -5f, -8f), warmup = 10).withinBrushBudget()
    }

    @Test
    fun shapeCreateBrushStar() {
        shape { it.copy(type = ShapeType.STAR, style = ShapeStyle.STROKE, strokeWith = ShapeStroke.BRUSH, corner = CornerStyle.ROUND, cornerRadius = 20f) }
        val from = Vec2(100f, 200f)
        h.drag("shape brush star: drag-create", from, moving(from, 14f, 32f), warmup = 5).withinBrushBudget()
    }
}
