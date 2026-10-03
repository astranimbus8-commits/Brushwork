package com.brushwork.paint.tools.vector.spline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.vector.CurveKind
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSpline
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.6 §3.2(d): the Path tool end to end. Taps add control points (after the selected one, or
 * on the dashed control polygon), Order / Endpoint / Cyclic and the quick starts are in-tool
 * steps with undo and redo, ✓ adds ONE `VPath` whose subpaths are exactly its spline's Bézier
 * form (I9) as the step "Path", a tap reopens it ("Edit path"; ✕ restores the layer exactly),
 * Curve / Polyline and Path hand paths to each other on a tap, and a raster layer gets pixels.
 */
@RunWith(RobolectricTestRunner::class)
class PathToolRobolectricTest {

    private val ink = 0xFF203080.toInt()
    private val w = 400
    private val h = 300

    private fun controller(vector: Boolean = true): EditorController {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("brushwork_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h))
        doc.layers += Layer(doc.newLayerId(), if (vector) "Vector 1" else "Layer 2", BitmapUtils.createLayerBitmap(w, h)).also {
            if (vector) it.vector = VectorContent.EMPTY
        }
        doc.activeLayerIndex = 1
        return EditorController(ctx, doc, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), AppSettings(ctx)).also {
            it.color = ink
            it.tools
            it.brush = BrushLibrary.defaultBrush.copy(size = 6f, pressureSize = false, taperStart = 0f, taperEnd = 0f)
            it.snapping.enabled = false
        }
    }

    private fun EditorController.tool(id: ToolId): CurveTool {
        selectTool(id)
        return tools.getValue(id) as CurveTool
    }

    private fun EditorController.drag(vararg pts: Pair<Float, Float>) {
        pointerDown(ToolPoint(pts[0].first, pts[0].second))
        for (i in 1 until pts.size) pointerMove(ToolPoint(pts[i].first, pts[i].second))
        pointerUp(ToolPoint(pts.last().first, pts.last().second))
    }

    private fun EditorController.tap(x: Float, y: Float) = drag(x to y)

    private fun pixels(b: Bitmap): IntArray = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }

    private fun render(content: VectorContent): IntArray {
        val b = BitmapUtils.createLayerBitmap(w, h)
        VectorLayerRenderer.render(Canvas(b), content, Rect(0, 0, w, h), tips = TipCache(), document = Rect(0, 0, w, h))
        return pixels(b)
    }

    /** A point on [p]'s line (the middle of its flattened curve). */
    private fun onLine(p: VPath): Vec2 {
        val pts = VectorOps.toVectorPath(p).flatten(0.25f).first().points
        return pts[pts.size / 2]
    }

    private val five = listOf(60f to 200f, 120f to 80f, 200f to 220f, 280f to 70f, 340f to 190f)

    private fun CurveTool.plainLine() = update { it.copy(stroke = CurveStroke.PLAIN, useBrushSize = false, plainWidth = 4f) }

    @Test
    fun fivePointsOrderEndpointCyclicAndCommitAddOnePathWithItsSpline() {
        val c = controller()
        val tool = c.tool(ToolId.PATH)
        assertEquals(CurveKind.PATH, tool.kind)
        tool.plainLine()
        for ((x, y) in five) c.tap(x, y)
        val s = tool.spline!!
        assertEquals(5, s.points.size)
        assertEquals(listOf(60f, 120f, 200f, 280f, 340f), s.points.map { it.x })
        assertEquals("order 4, endpoint on by default", 4, s.order)
        assertTrue(s.endpoint)
        assertFalse(s.cyclic)
        // The anchors are the Bézier form: clamped ends on the end points, 2 spans → 3 anchors.
        assertEquals(SplineBezier.toSubpath(s).anchors.size, tool.anchors.size)
        assertEquals(3, tool.anchors.size)
        assertEquals(Vec2(60f, 200f), tool.anchors.first().pos)
        assertEquals(340f, tool.anchors.last().x, 1e-3f)
        assertEquals("taps don't select", -1, tool.selectedPoint)

        tool.setOrder(3)
        assertEquals(3, tool.spline!!.order)
        assertEquals(3, tool.pathOrder)
        tool.setEndpoint(false)
        assertFalse(tool.spline!!.endpoint)
        // Uniform knots: the curve starts inside the polygon.
        assertTrue(tool.anchors.first().pos.distanceTo(Vec2(60f, 200f)) > 1f)
        tool.setCyclic(true)
        assertTrue(tool.spline!!.cyclic)
        assertEquals("a cyclic quadratic of 5 points: 5 spans", 5, tool.anchors.size)
        // Undo takes back one edit at a time (cyclic, endpoint, order), redo brings it back.
        assertTrue(tool.undoStep())
        assertFalse(tool.spline!!.cyclic)
        assertTrue(tool.undoStep())
        assertTrue(tool.spline!!.endpoint)
        assertTrue(tool.redoStep())
        assertFalse(tool.spline!!.endpoint)
        assertTrue(tool.redoStep())
        assertTrue(tool.spline!!.cyclic)

        tool.commit()
        assertFalse(tool.hasPendingWork)
        assertEquals(1, c.undoManager.undoCount)
        assertEquals("Path", c.undoManager.undoLabel)
        val p = c.activeLayer.vector!!.objects.single() as VPath
        val sp = p.spline!!
        assertEquals(5, sp.points.size)
        assertEquals(3, sp.order)
        assertTrue(sp.cyclic)
        assertEquals("I9: exactly the spline's Bézier form", listOf(SplineBezier.toSubpath(sp)), p.subpaths)
        assertTrue(SplineBezier.matches(p))
        assertEquals(0f, p.tension, 0f)
        assertFalse(p.polyline)
        assertArrayEquals(render(c.activeLayer.vector!!), pixels(c.activeLayer.bitmap))
        // The next path starts with the order and cyclic used last.
        assertEquals(3, tool.pathOrder)
        assertTrue(tool.pathCyclic)
    }

    @Test
    fun inToolUndoAndRedoTakeBackOnePointEdit() {
        val c = controller()
        val tool = c.tool(ToolId.PATH)
        for ((x, y) in five.take(3)) c.tap(x, y)
        // A drag of a point is one step.
        c.drag(120f to 80f, 130f to 95f, 150f to 110f)
        assertEquals(Vec2(150f, 110f), SplineEditing.pos(tool.spline!!.points[1]))
        c.undo()
        assertEquals(Vec2(120f, 80f), SplineEditing.pos(tool.spline!!.points[1]))
        c.undo()
        assertEquals(2, tool.spline!!.points.size)
        c.redo()
        assertEquals(3, tool.spline!!.points.size)
        c.redo()
        assertEquals(Vec2(150f, 110f), SplineEditing.pos(tool.spline!!.points[1]))
        assertEquals("no document step yet", 0, c.undoManager.undoCount)
        // Undo down to nothing, then the path is gone.
        repeat(4) { c.undo() }
        assertFalse(tool.hasPendingWork)
        assertNull(tool.spline)
    }

    @Test
    fun aTapNearThePolygonInsertsThereAndASelectedPointExtrudes() {
        val c = controller()
        val tool = c.tool(ToolId.PATH)
        c.tap(50f, 150f)
        c.tap(350f, 150f)
        // Near the polygon (8 px away): inserted between the two.
        c.tap(200f, 158f)
        assertEquals(listOf(50f, 200f, 350f), tool.spline!!.points.map { it.x })
        assertEquals("on the polygon", 150f, tool.spline!!.points[1].y, 1e-3f)
        // Tap a point to select it; a tap elsewhere then adds right after it and selects that.
        c.tap(50f, 150f)
        assertEquals(0, tool.selectedPoint)
        c.tap(80f, 40f)
        assertEquals(listOf(50f, 80f, 200f, 350f), tool.spline!!.points.map { it.x })
        assertEquals(1, tool.selectedPoint)
        c.tap(120f, 30f)
        assertEquals(listOf(50f, 80f, 120f, 200f, 350f), tool.spline!!.points.map { it.x })
        assertEquals(2, tool.selectedPoint)
        // Tapping the selected point again deselects it; the next one goes to the end.
        c.tap(120f, 30f)
        assertEquals(-1, tool.selectedPoint)
        c.tap(380f, 290f)
        assertEquals(380f, tool.spline!!.points.last().x, 0f)
        // Weight and thickness of the selected point.
        tool.select(1)
        tool.setWeight(1, 4f)
        tool.endNumericEdit()
        tool.setWidth(1, 2f)
        tool.endNumericEdit()
        assertEquals(4f, tool.spline!!.points[1].weight, 0f)
        assertEquals(2f, tool.spline!!.points[1].width, 0f)
        tool.setWeight(1, 99f)
        assertEquals(VSpline.MAX_WEIGHT, tool.spline!!.points[1].weight, 0f)
        // The X / Y pill moves the selected point (one stable source, null while none is selected).
        val pos = tool.splinePointPosition!!
        assertEquals(Vec2(80f, 40f), pos.position)
        pos.beginPositionEdit()
        pos.setPosition(90f, null)
        pos.setPosition(95f, null)
        pos.endPositionEdit()
        assertEquals(Vec2(95f, 40f), SplineEditing.pos(tool.spline!!.points[1]))
        tool.undoStep()
        assertEquals("one step per pill drag", Vec2(80f, 40f), SplineEditing.pos(tool.spline!!.points[1]))
        tool.deselect()
        assertNull(pos.position)
        assertSame(pos, tool.splinePointPosition)
        assertNull("Curve uses its own adapter", (c.tools.getValue(ToolId.CURVE) as CurveTool).splinePointPosition)
    }

    @Test
    fun theCapsuleAndCircleQuickStarts() {
        val c = controller()
        val tool = c.tool(ToolId.PATH)
        val area = RectF(0f, 0f, w.toFloat(), h.toFloat())
        assertTrue(tool.startShape(CurveTool.PathShape.CAPSULE, area))
        val s = tool.spline!!
        assertEquals(12, s.points.size)
        assertTrue(s.cyclic)
        assertEquals(4, s.order)
        assertTrue("fill on", tool.settings.fill)
        assertEquals("stroke off", CurveStroke.NONE, tool.settings.stroke)
        // Only while no point exists.
        assertFalse(tool.startShape(CurveTool.PathShape.CIRCLE, area))
        tool.commit()
        val p = c.activeLayer.vector!!.objects.single() as VPath
        assertNotNull(p.fill)
        assertNull(p.stroke)
        assertTrue(SplineBezier.matches(p))
        assertEquals("Path", c.undoManager.undoLabel)
        // The Curve tool's own settings were not touched.
        val curve = c.tools.getValue(ToolId.CURVE) as CurveTool
        assertFalse(curve.settings.fill)
        assertEquals(CurveStroke.BRUSH, curve.settings.stroke)
        // Circle: 8 points, undo removes the whole quick start.
        assertTrue(tool.startShape(CurveTool.PathShape.CIRCLE, area))
        assertEquals(8, tool.spline!!.points.size)
        c.undo()
        assertFalse(tool.hasPendingWork)
    }

    @Test
    fun reopenEditAndCancelRestoreExactly() {
        val c = controller()
        val layer = c.activeLayer
        val tool = c.tool(ToolId.PATH)
        tool.plainLine()
        for ((x, y) in five) c.tap(x, y)
        tool.commit()
        val original = layer.vector!!
        val before = pixels(layer.bitmap)
        val p = original.objects.single() as VPath
        val q = onLine(p)
        // A tap on its line reopens it with its control points.
        c.tap(q.x, q.y)
        assertTrue(tool.isReopened)
        assertEquals(p.spline, tool.spline)
        assertFalse("an untouched reopen is not the user's work", tool.hasUserChanges)
        // ✕ after a drag of a point: exactly as before, no step.
        c.drag(200f to 220f, 210f to 250f, 220f to 260f)
        assertTrue(tool.hasUserChanges)
        tool.discard()
        assertFalse(tool.isReopened)
        assertSame(original, layer.vector)
        assertArrayEquals(before, pixels(layer.bitmap))
        assertEquals(1, c.undoManager.undoCount)
        // ✓ untouched: nothing recorded.
        c.tap(q.x, q.y)
        tool.commit()
        assertSame(original, layer.vector)
        assertEquals(1, c.undoManager.undoCount)
        // ✓ after a drag: ONE step "Edit path", same id, the new spline, I9 kept.
        c.tap(q.x, q.y)
        c.drag(200f to 220f, 210f to 250f, 220f to 260f)
        tool.commit()
        assertEquals(2, c.undoManager.undoCount)
        assertEquals(CurveTool.EDIT_PATH_LABEL, c.undoManager.undoLabel)
        val edited = layer.vector!!.objects.single() as VPath
        assertEquals(p.id, edited.id)
        assertEquals(Vec2(220f, 260f), SplineEditing.pos(edited.spline!!.points[2]))
        assertTrue(SplineBezier.matches(edited))
        assertArrayEquals(render(layer.vector!!), pixels(layer.bitmap))
        c.undo()
        assertSame(original, layer.vector)
        assertArrayEquals(before, pixels(layer.bitmap))
    }

    @Test
    fun curveAndPathHandPathsToEachOtherOnATap() {
        val c = controller()
        val layer = c.activeLayer
        val path = c.tool(ToolId.PATH)
        path.plainLine()
        for ((x, y) in five) c.tap(x, y)
        path.commit()
        val spline = layer.vector!!.objects.single() as VPath
        // A plain curve too.
        val plain = VPath(
            id = 0, subpaths = listOf(VSubpath(listOf(VAnchor(40f, 270f), VAnchor(360f, 280f)))),
            stroke = VStrokeStyle(color = ink, width = 4f),
        )
        c.vectors.addObjects(layer, listOf(plain), "Import")
        val plainObj = layer.vector!!.objects.last() as VPath
        // The Curve tool tapping the spline path switches to Path, which reopens it.
        val curve = c.tool(ToolId.CURVE)
        val q = onLine(spline)
        c.tap(q.x, q.y)
        assertEquals(ToolId.PATH, c.activeToolId)
        assertTrue(path.isReopened)
        assertEquals(spline.spline, path.spline)
        assertFalse(curve.hasPendingWork)
        path.discard()
        // The Path tool tapping the plain curve switches to Curve, which reopens it.
        val q2 = onLine(plainObj)
        c.tap(q2.x, q2.y)
        assertEquals(ToolId.CURVE, c.activeToolId)
        assertTrue(curve.isReopened)
        assertEquals(2, curve.anchors.size)
        curve.discard()
        // The Polyline tool also hands spline paths to Path.
        c.tool(ToolId.POLYLINE)
        c.tap(q.x, q.y)
        assertEquals(ToolId.PATH, c.activeToolId)
        assertTrue(path.isReopened)
        path.discard()
        // A spline that fails the I9 check is a plain Bézier path: the Curve tool edits it.
        val stale = spline.copy(id = 0, spline = spline.spline!!.copy(points = spline.spline!!.points.map { it.copy(y = it.y + 40f) }))
        c.vectors.update(layer, VectorContent.EMPTY.plus(listOf(stale)).first, "Replace")
        val staleObj = layer.vector!!.objects.single() as VPath
        assertFalse(SplineBezier.matches(staleObj))
        c.tool(ToolId.CURVE)
        val q3 = onLine(staleObj)
        c.tap(q3.x, q3.y)
        assertEquals(ToolId.CURVE, c.activeToolId)
        assertTrue(curve.isReopened)
    }

    @Test
    fun aRasterLayerGetsPixelsInOneStep() {
        val c = controller(vector = false)
        val layer = c.activeLayer
        val tool = c.tool(ToolId.PATH)
        tool.plainLine()
        for ((x, y) in five) c.tap(x, y)
        tool.commit()
        assertEquals(1, c.undoManager.undoCount)
        assertEquals("Path", c.undoManager.undoLabel)
        assertNull(layer.vector)
        // The curve is painted: pixels along its Bézier form.
        val px = pixels(layer.bitmap)
        assertTrue(px.count { (it ushr 24) > 128 } > 300)
        val mid = SplineBezier.toSubpath(VSpline(five.map { com.brushwork.paint.vector.VSplinePoint(it.first, it.second) })).anchors[1]
        assertTrue("painted on the curve", (layer.bitmap.getPixel(mid.x.toInt(), mid.y.toInt()) ushr 24) > 0)
        c.undo()
        assertTrue(pixels(layer.bitmap).all { it == 0 })
    }

    @Test
    fun deletingEveryPointOfAReopenedPathRemovesIt() {
        val c = controller()
        val layer = c.activeLayer
        val tool = c.tool(ToolId.PATH)
        tool.plainLine()
        for ((x, y) in five.take(3)) c.tap(x, y)
        tool.commit()
        val p = layer.vector!!.objects.single() as VPath
        val q = onLine(p)
        c.tap(q.x, q.y)
        assertTrue(tool.isReopened)
        repeat(3) { tool.deleteAnchor(0) }
        assertFalse(tool.isReopened)
        assertTrue(layer.vector!!.objects.isEmpty())
        assertEquals(2, c.undoManager.undoCount)
    }
}
