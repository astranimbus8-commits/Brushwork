package com.brushwork.paint.qa17

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.pathfinder.PathfinderTool
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.vector.CornerStyle
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.tools.vector.spline.SplineBezier
import com.brushwork.paint.ui.common.PathfinderLabels
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.pathfinder.PathConvert
import com.brushwork.paint.vector.pathfinder.PathfinderOp
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.PI
import kotlin.math.abs

/**
 * v1.7 integration flow (design §6.2, areas C, B and G): a rounded rectangle drawn with the Shape
 * tool, its corner taken through "Turn into path", that corner made sharp in the Path tool, then
 * united by the Pathfinder with another shape layer. Each action is ONE undo step; the areas
 * (exact up to flattening) show the rounding before, the square corner after and the union of
 * the two outlines (one contour); every step's pixels are its data drawn (I1); undo walks back
 * through every state.
 */
@RunWith(RobolectricTestRunner::class)
class FlowShapePathPathfinderRobolectricTest {
    private val scopes = ArrayList<CoroutineScope>()

    @After
    fun release() {
        for (s in scopes) s.cancel()
        scopes.clear()
    }

    private val w = 240
    private val h = 180

    private fun controller(): EditorController {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("brushwork_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val d = Document("flow-path", "flow-path", w, h)
        d.layers += Layer(d.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h))
        d.activeLayerIndex = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it }
        return EditorController(ctx, d, scope, AppSettings(ctx)).also {
            it.viewTransform.set(Matrix())
            it.snapping.enabled = false
            it.tools
        }
    }

    private fun EditorController.drag(vararg pts: Pair<Float, Float>) {
        pointerDown(ToolPoint(pts[0].first, pts[0].second))
        for (i in 1 until pts.size) pointerMove(ToolPoint(pts[i].first, pts[i].second))
        pointerUp(ToolPoint(pts.last().first, pts.last().second))
    }

    private fun EditorController.tap(x: Float, y: Float) {
        pointerDown(ToolPoint(x, y))
        pointerUp(ToolPoint(x, y))
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun render(content: VectorContent): IntArray {
        val b = BitmapUtils.createLayerBitmap(w, h)
        VectorLayerRenderer.render(Canvas(b), content, Rect(0, 0, w, h), tips = TipCache(), document = Rect(0, 0, w, h))
        return pixels(b)
    }

    private fun area(o: VObject): Float = PathConvert.area(PathConvert.region(o)!!)

    @Test
    fun aRoundedCornerTurnedIntoAPathMadeSharpAndUnitedIsOneStepPerAction() {
        val c = controller()
        val steps0 = c.undoManager.undoCount

        // 1. The Shape tool: a filled rounded rectangle (40, 40) - (140, 120), radius 20, with
        //    one corner selected in Points mode, then "Turn into path".
        c.selectTool(ToolId.SHAPE)
        val shapes = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        shapes.update {
            it.copy(
                type = ShapeType.RECTANGLE, style = ShapeStyle.FILL, fillColor = BLUE, corner = CornerStyle.ROUND, cornerRadius = R,
                keepProportions = false, fromCenter = false, snapAngle = false, editable = true,
            )
        }
        c.drag(40f to 40f, 90f to 80f, 140f to 120f)
        shapes.setPointEditing(true)
        shapes.selectPoints(PointSelection.of(4, 0))
        assertTrue(shapes.turnIntoPath())
        // The new shape is placed first (its own step), then turned into a path: one step each.
        assertEquals(steps0 + 2, c.undoManager.undoCount)
        assertEquals(HistoryLabels.TURN_INTO_PATH, c.undoManager.undoLabel)
        val layer = c.activeLayer
        assertTrue(layer.isVectorLayer)
        assertNull(layer.shapeData)
        val converted = layer.vector!!.objects.single() as VPath
        val sp = converted.spline!!
        // Four rounded corners, each sharp - smooth - sharp, the smooth point weighted cos(90° / 2).
        assertEquals(12, sp.points.size)
        val smooth = sp.points.filter { !it.sharp }
        assertEquals(4, smooth.size)
        for (p in smooth) assertEquals(Math.cos(PI / 4).toFloat(), p.weight, 1e-4f)
        val rounded = area(converted)
        // (The region flattens each arc into chords: a few px² under the exact area.)
        assertEquals("the rounded rectangle", 100f * 80f - 4f * CORNER_CUT, rounded, 10f)
        assertArrayEquals("I1: the converted path", render(layer.vector!!), pixels(layer.bitmap))

        // The Path tool holds it with the selected corner's middle point selected; that point
        // sits at the rectangle's corner (the tangents' intersection).
        assertEquals(ToolId.PATH, c.activeToolId)
        val pathTool = c.currentTool as CurveTool
        assertTrue(pathTool.isReopened)
        assertFalse(c.currentTool.hasUserChanges)
        val i = pathTool.selectedIndex
        assertFalse(sp.points[i].sharp)
        val cx = sp.points[i].x
        val cy = sp.points[i].y
        assertTrue("($cx, $cy) is a corner of the rectangle", CORNERS.any { (x, y) -> abs(cx - x) < 1e-3f && abs(cy - y) < 1e-3f })

        // 2. "Sharp corner" on that point (one in-tool step), then ✓: ONE step "Edit path".
        val steps1 = c.undoManager.undoCount
        assertTrue(pathTool.canBeSharp(i))
        pathTool.setSharp(i, true)
        pathTool.commit()
        assertEquals(steps1 + 1, c.undoManager.undoCount)
        assertEquals(CurveTool.EDIT_PATH_LABEL, c.undoManager.undoLabel)
        val sharpened = layer.vector!!.objects.single() as VPath
        assertTrue("I9", SplineBezier.matches(sharpened))
        assertTrue(sharpened.spline!!.points[i].sharp)
        assertEquals(3, sharpened.spline!!.points.count { !it.sharp })
        assertTrue(
            "the corner is an anchor of the stored form, exactly at the point",
            sharpened.subpaths[0].anchors.any { it.sharp && abs(it.x - cx) < 1e-3f && abs(it.y - cy) < 1e-3f },
        )
        val square = area(sharpened)
        assertEquals("that corner is square now: its rounding is filled in", CORNER_CUT, square - rounded, 4f)
        assertArrayEquals("I1: the sharpened path", render(layer.vector!!), pixels(layer.bitmap))

        // 3. Another shape layer (120, 60) - (200, 100), above it.
        val barShape = ShapeObject(ShapeType.RECTANGLE, cx = 160f, cy = 80f, w = 80f, h = 40f, style = ShapeStyle.FILL, fillColor = RED)
        val bar = c.addLayerWithContent("Bar", "Add shape", shapeData = ShapeCodec.encode(barShape)) { cv ->
            cv.drawRect(120f, 60f, 200f, 100f, Paint().apply { color = RED })
        }!!
        val barData = bar.shapeData
        val stepsBar = c.undoManager.undoCount

        // 4. Pathfinder: the path and the bar, Unite. ONE step; the result is a new layer above
        //    the top operand, the shape layer goes and the vector layer stays (empty).
        c.selectTool(ToolId.PATHFINDER)
        val pf = (c.currentTool as PathfinderTool).also { it.computeDispatcher = Dispatchers.Unconfined }
        c.tap(60f, 80f)
        c.tap(185f, 80f)
        assertEquals(listOf(layer, bar), pf.operands.map { it.layer })
        pf.apply(PathfinderOp.UNITE)
        assertEquals(stepsBar + 1, c.undoManager.undoCount)
        assertEquals(HistoryLabels.pathfinder("Unite"), c.undoManager.undoLabel)
        val result = c.doc.layers.single { it.name == PathfinderLabels.resultLayer(1) }
        assertSame(result, c.activeLayer)
        assertEquals(listOf(c.doc.layers[0].id, layer.id, result.id), c.doc.layers.map { it.id })
        assertTrue("the vector layer stays, empty", layer.vector!!.objects.isEmpty())
        val united = result.vector!!.objects.single() as VPath
        assertEquals("one contour", 1, united.subpaths.size)
        assertNull("the result is a plain path", united.spline)
        // 3200 px² of bar, 800 of it over the rectangle (away from its rounded corners).
        assertEquals("the union's area", square + 80f * 40f - 20f * 40f, area(united), 6f)
        assertEquals("the top object's style", VPaint.Solid(RED), united.fill)
        assertArrayEquals("I1: the result", render(result.vector!!), pixels(result.bitmap))

        // Undo, one step per action, back through every state.
        c.undo()
        assertEquals(listOf(c.doc.layers[0].id, layer.id, bar.id), c.doc.layers.map { it.id })
        assertEquals("undo Unite: the sharpened path", sharpened, layer.vector!!.objects.single())
        assertEquals(barData, bar.shapeData)
        assertArrayEquals(render(layer.vector!!), pixels(layer.bitmap))
        c.undo()
        assertEquals("undo the bar", -1, c.doc.indexOf(bar))
        c.undo()
        assertEquals("undo Edit path: the converted path", converted, layer.vector!!.objects.single())
        assertArrayEquals(render(layer.vector!!), pixels(layer.bitmap))
        c.undo()
        assertTrue("undo Turn into path: the shape layer", layer.isShapeLayer)
        assertNull(layer.vector)
        val shape = ShapeCodec.decode(layer.shapeData)!!
        assertEquals(CornerStyle.ROUND, shape.corner)
        assertEquals(R, shape.cornerRadius, 0f)
        c.undo()
        assertEquals("undo the shape: nothing placed", -1, c.doc.indexOf(layer))
        assertEquals(steps0, c.undoManager.undoCount)
    }

    private companion object {
        const val R = 20f

        /** What rounding one corner of radius [R] takes off a rectangle: r² (1 − π / 4). */
        val CORNER_CUT = (R * R * (1.0 - PI / 4.0)).toFloat()
        val CORNERS = listOf(40f to 40f, 140f to 40f, 140f to 120f, 40f to 120f)
        const val BLUE = 0xFF2244CC.toInt()
        const val RED = 0xFFDD2211.toInt()
    }
}
