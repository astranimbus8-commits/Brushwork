package com.brushwork.paint.tools.vector

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.GridSettings
import com.brushwork.paint.model.GridType
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.transform.SnapAxis
import com.brushwork.paint.tools.transform.SnapLine
import com.brushwork.paint.tools.transform.SnapSource
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

/**
 * Snap to objects in the shape tool: new corners, moved shapes, resize handles, line ends, points
 * (to other layers' edges, to the shape's own other points, to other shape layers' points) and
 * tangent handles; nothing when the setting is off; the edited shape layer is never a target.
 * Zoom 1, density 1: things snap within 8 document px.
 */
@RunWith(RobolectricTestRunner::class)
class ShapeSnapTest {

    private val red = 0xFFFF0000.toInt()

    private val scopes = ArrayList<CoroutineScope>()

    /** Ends what the test's editors still observe (a global snapshot observer would keep them). */
    @After
    fun releaseEditors() {
        for (s in scopes) s.cancel()
        scopes.clear()
    }

    /** A 200 x 200 canvas whose bottom layer holds a filled box (150..190, 20..60). */
    private fun controller(): EditorController {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("brushwork_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val doc = Document("t", "t", 200, 200)
        val art = Layer(doc.newLayerId(), "Art", BitmapUtils.createLayerBitmap(200, 200))
        Canvas(art.bitmap).drawRect(150f, 20f, 190f, 60f, Paint().apply { color = 0xFF000000.toInt() })
        art.markChanged()
        doc.layers += art
        doc.layers += Layer(doc.newLayerId(), "Layer 2", BitmapUtils.createLayerBitmap(200, 200))
        doc.activeLayerIndex = 1
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it }
        return EditorController(ctx, doc, scope, AppSettings(ctx)).also {
            it.color = red
            it.viewTransform.set(Matrix())
            it.tools
        }
    }

    private fun EditorController.drag(vararg pts: Pair<Float, Float>) {
        pointerDown(ToolPoint(pts[0].first, pts[0].second))
        for (i in 1 until pts.size) pointerMove(ToolPoint(pts[i].first, pts[i].second))
        pointerUp(ToolPoint(pts.last().first, pts.last().second))
    }

    private fun EditorController.tap(x: Float, y: Float) = drag(x to y)

    private fun shapeTool(c: EditorController): ShapeTool {
        c.selectTool(ToolId.SHAPE)
        return (c.tools.getValue(ToolId.SHAPE) as ShapeTool).also {
            it.update { s -> s.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE, useBrushSize = false, strokeWidth = 4f) }
        }
    }

    private fun assertVec(expected: Vec2, actual: Vec2, eps: Float = 1e-2f) {
        assertEquals("x of $actual", expected.x, actual.x, eps)
        assertEquals("y of $actual", expected.y, actual.y, eps)
    }

    /** A pending rectangle (40..120, 80..160). */
    private fun pendingRect(c: EditorController, tool: ShapeTool) {
        c.drag(40f to 80f, 120f to 160f)
        assertEquals(ShapeBox(80f, 120f, 80f, 80f, 0f), tool.box)
    }

    @Test
    fun newCornersSnap() {
        val c = controller()
        val tool = shapeTool(c)
        // The first corner lands on the box's left edge (150), the second on its right edge (190).
        c.drag(147f to 30f, 170f to 70f, 193f to 77f)
        val b = tool.box!!
        assertEquals(150f, b.cx - b.w / 2f, 1e-3f)
        assertEquals(30f, b.cy - b.h / 2f, 1e-3f)
        assertEquals(190f, b.cx + b.w / 2f, 1e-3f)
        assertEquals(77f, b.cy + b.h / 2f, 1e-3f)
    }

    @Test
    fun movedShapesSnapByTheirOutline() {
        val c = controller()
        val tool = shapeTool(c)
        pendingRect(c, tool)
        // Right edge 147 -> the box's left edge 150 (closer than the canvas center for its center).
        c.drag(80f to 120f, 95f to 120f, 107f to 120f)
        assertEquals(110f, tool.box!!.cx, 1e-3f)
        assertEquals(120f, tool.box!!.cy, 1e-3f)
        // Moving farther than the snap distance lets go.
        c.drag(110f to 120f, 90f to 120f, 70f to 120f)
        assertEquals(70f, tool.box!!.cx, 1e-3f)
    }

    @Test
    fun nothingSnapsWhenTheSettingIsOff() {
        val c = controller()
        c.snapping.enabled = false
        val tool = shapeTool(c)
        pendingRect(c, tool)
        c.drag(80f to 120f, 95f to 120f, 107f to 120f)
        assertEquals(107f, tool.box!!.cx, 1e-3f)
        tool.discard()
        pendingRect(c, tool)
        tool.setPointEditing(true)
        c.drag(120f to 80f, 135f to 95f, 146f to 110f)
        assertVec(Vec2(146f, 110f), tool.docAnchors()!![1].pos)
        // The square grid still applies when grid snapping is on.
        c.updateGrid(GridSettings(enabled = true, type = GridType.SQUARE, spacingPx = 10f, snap = true))
        c.drag(146f to 110f, 140f to 120f, 133f to 124f)
        assertVec(Vec2(130f, 120f), tool.docAnchors()!![1].pos)
    }

    @Test
    fun resizeHandlesSnapTheirSide() {
        val c = controller()
        val tool = shapeTool(c)
        pendingRect(c, tool)
        // Right edge handle dragged to 146: the side snaps to 150.
        c.drag(120f to 120f, 135f to 120f, 146f to 120f)
        val b = tool.box!!
        assertEquals(40f, b.cx - b.w / 2f, 1e-3f)
        assertEquals(150f, b.cx + b.w / 2f, 1e-3f)
        assertEquals(80f, b.h, 1e-3f)
    }

    @Test
    fun lineEndsSnap() {
        val c = controller()
        val tool = shapeTool(c)
        tool.update { it.copy(type = ShapeType.LINE) }
        c.drag(20f to 120f, 120f to 64f)
        assertVec(Vec2(120f, 60f), tool.box!!.end)
        c.drag(120f to 60f, 160f to 30f, 187f to 23f)
        assertVec(Vec2(190f, 20f), tool.box!!.end)
    }

    @Test
    fun pointsSnapToOtherLayersAndToTheShapesOwnPoints() {
        val c = controller()
        val tool = shapeTool(c)
        pendingRect(c, tool)
        tool.setPointEditing(true)
        // Point 1 (120, 80) near the box's left edge: x snaps to 150.
        c.drag(120f to 80f, 135f to 95f, 146f to 110f)
        assertVec(Vec2(150f, 110f), tool.docAnchors()!![1].pos)
        // Point 2 (120, 160) lines up with point 1 horizontally.
        c.drag(120f to 160f, 128f to 120f, 133f to 114f)
        assertVec(Vec2(133f, 110f), tool.docAnchors()!![2].pos)
        // A point inserted with the "+" of the left edge snaps once dragged: x to the other
        // points on that edge, y to the canvas center.
        c.drag(40f to 125f, 42f to 110f, 44f to 100f)
        val a = tool.docAnchors()!!
        assertEquals(5, a.size)
        assertTrue(a.any { it.pos.distanceTo(Vec2(40f, 100f)) < 1e-2f })
    }

    @Test
    fun pointsSnapToTheVerticesOfOtherShapeLayers() {
        val c = controller()
        val tool = shapeTool(c)
        tool.update { it.copy(type = ShapeType.STAR, starPoints = 5, innerRatio = 0.45f) }
        c.drag(20f to 100f, 100f to 180f)
        tool.commit()
        val star = c.activeLayer
        val o = ShapeCodec.decode(star.shapeData)!!
        // An inner vertex of the star: not on its bounds, only a "point" target.
        val inner = ShapePoints.docAnchors(o.box, ShapePoints.fromRegular(ShapeType.STAR, o.outlineParams))[3].pos
        val t = c.snapping.targets()
        assertTrue(t.xs.any { it.source == SnapSource.POINT && kotlin.math.abs(it.pos - inner.x) < 1e-3f && it.label == "${star.name} point" })
        // A new shape's point dragged next to it lands on it.
        tool.update { it.copy(type = ShapeType.RECTANGLE) }
        c.drag(110f to 110f, 170f to 170f)
        tool.setPointEditing(true)
        val p0 = tool.docAnchors()!![0].pos
        val goal = inner + Vec2(3f, -3f)
        c.drag(p0.x to p0.y, (p0.x + goal.x) / 2f to (p0.y + goal.y) / 2f, goal.x to goal.y)
        assertVec(inner, tool.docAnchors()!![0].pos, 1e-3f)
    }

    @Test
    fun pointsSnapToLinesDrawnInLayers() {
        val c = controller()
        val art = c.doc.layers[0]
        // Stands in for the lines the line detector finds in a layer (a Table filter's grid).
        c.snapping.addLayerFeatures { l ->
            if (l === art) listOf(SnapLine.drawn(SnapAxis.Y, 125f, 0f, 200f, l.name), SnapLine.drawn(SnapAxis.X, 75f, 0f, 200f, l.name)) else emptyList()
        }
        val tool = shapeTool(c)
        pendingRect(c, tool)
        tool.setPointEditing(true)
        c.drag(120f to 80f, 100f to 100f, 78f to 121f)
        assertVec(Vec2(75f, 125f), tool.docAnchors()!![1].pos)
    }

    @Test
    fun tangentHandlesSnap() {
        val c = controller()
        val tool = shapeTool(c)
        pendingRect(c, tool)
        tool.setPointEditing(true)
        tool.setPointSmooth(1, true)
        tool.selectPoint(1)
        val a = tool.docAnchors()!!
        val (_, hOut) = ShapePoints.handles(a, 1, true)
        val grab = a[1].pos + hOut
        // The handle end lines up with point 1 vertically (x 120).
        c.drag(grab.x to grab.y, grab.x + 2f to grab.y + 10f, 124f to grab.y + 20f)
        val h = tool.docAnchors()!![1]
        assertEquals(120f, h.pos.x + h.handleOut!!.x, 1e-2f)
    }

    @Test
    fun theEditedShapeLayerIsNotATarget() {
        val c = controller()
        val tool = shapeTool(c)
        tool.update { it.copy(style = ShapeStyle.FILL) }
        pendingRect(c, tool)
        tool.commit()
        val layer = c.activeLayer
        c.tap(80f, 120f)
        assertEquals(layer, tool.editingLayer)
        // Moved by 7 px: its own old bounds (and points) would pull it back.
        c.drag(80f to 120f, 84f to 120f, 87f to 120f)
        assertEquals(87f, tool.box!!.cx, 1e-3f)
        tool.commit()
        // Another shape still snaps to it.
        c.drag(10f to 170f, 60f to 198f)
        c.drag(35f to 185f, 50f to 185f, 69f to 185f)
        val b = tool.box!!
        assertEquals(47f, b.cx - b.w / 2f, 1e-3f)
    }
}
