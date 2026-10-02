package com.brushwork.paint.vector.draw

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Looper
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VFillRule
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * v1.5 A3: the bucket on vector layers: inside a closed object its fill takes the main color (a
 * fill is added when missing), on a line its stroke color, ONE step "Fill object" with undo;
 * an empty area enclosed by line art becomes a filled path (even-odd holes) UNDER the line art,
 * ONE step "Fill"; the layer stays a vector layer and its cache a fresh render.
 */
@RunWith(RobolectricTestRunner::class)
class VectorFillRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 300
    private val h = 220
    private val red = 0xFFE02020.toInt()
    private val black = 0xFF000000.toInt()

    private fun setup(): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(w, h)).also { it.vector = VectorContent.EMPTY }
        return EditorController(app, doc, scope, settings).also {
            it.viewTransform.set(Matrix())
            it.tools
            it.selectTool(ToolId.FILL)
            it.color = red
        }
    }

    private val EditorController.vec: Layer get() = doc.layers[0]

    private fun pixels(b: Bitmap): IntArray = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }

    private fun render(content: VectorContent): IntArray {
        val b = BitmapUtils.createLayerBitmap(w, h)
        VectorLayerRenderer.render(Canvas(b), content, Rect(0, 0, w, h), tips = TipCache(), document = Rect(0, 0, w, h))
        return pixels(b)
    }

    private fun EditorController.tap(x: Float, y: Float) {
        pointerDown(ToolPoint(x, y))
        pointerUp(ToolPoint(x, y))
    }

    private fun box(l: Float, t: Float, r: Float, b: Float, fill: VPaint? = null) = VPath(
        0,
        subpaths = listOf(VSubpath(listOf(VAnchor(l, t, true), VAnchor(r, t, true), VAnchor(r, b, true), VAnchor(l, b, true)), closed = true)),
        fill = fill,
        stroke = VStrokeStyle(color = black, width = 4f),
    )

    private fun stroke(x0: Float, y0: Float, x1: Float, y1: Float): VStroke {
        val n = 20
        val xs = FloatArray(n) { x0 + (x1 - x0) * it / (n - 1) }
        val ys = FloatArray(n) { y0 + (y1 - y0) * it / (n - 1) }
        return VStroke(0, preset = BrushLibrary.defaultBrush.copy(size = 6f), color = black, seed = 3L, stylus = false, points = PackedPoints(xs, ys, FloatArray(n) { 1f }))
    }

    @Test
    fun insideAClosedPathItsFillTakesTheColorAndOnTheLineTheStroke() {
        val c = setup()
        c.vectors.addObjects(c.vec, listOf(box(40f, 40f, 140f, 140f), stroke(180f, 30f, 280f, 190f)), "Add")
        val s0 = c.vec.vector!!
        val steps = c.undoManager.undoCount
        // Inside the unfilled box: a fill is added.
        c.tap(90f, 90f)
        val s1 = c.vec.vector!!
        assertEquals(VPaint.Solid(red), (s1.byId(1) as VPath).fill)
        assertEquals(black, (s1.byId(1) as VPath).stroke!!.color)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(VectorFill.FILL_OBJECT_LABEL, c.undoManager.undoLabel)
        assertArrayEquals(render(s1), pixels(c.vec.bitmap))
        assertEquals(red, c.vec.bitmap.getPixel(90, 90))
        // On its outline: the line.
        c.color = 0xFF2040E0.toInt()
        c.tap(140f, 90f)
        assertEquals(0xFF2040E0.toInt(), (c.vec.vector!!.byId(1) as VPath).stroke!!.color)
        assertEquals(VPaint.Solid(red), (c.vec.vector!!.byId(1) as VPath).fill)
        // A stroke (a few px beside it counts).
        c.tap(232f, 115f)
        assertEquals(0xFF2040E0.toInt(), (c.vec.vector!!.byId(2) as VStroke).color)
        assertArrayEquals(render(c.vec.vector!!), pixels(c.vec.bitmap))
        // The same color again: no step.
        val n = c.undoManager.undoCount
        c.tap(232f, 115f)
        assertEquals(n, c.undoManager.undoCount)
        repeat(3) { c.undo() }
        assertSame(s0, c.vec.vector)
        assertArrayEquals(render(s0), pixels(c.vec.bitmap))
    }

    @Test
    fun aShapeObjectGetsAFillOrALineColor() {
        val c = setup()
        val rect = VShape(0, shape = ShapeObject(ShapeType.RECTANGLE, cx = 100f, cy = 100f, w = 120f, h = 80f, style = ShapeStyle.STROKE, strokeWidth = 6f, strokeColor = black, fillColor = black))
        val line = VShape(0, shape = ShapeObject(ShapeType.LINE, cx = 230f, cy = 100f, w = 100f, h = 0f, rotation = 90f, strokeWidth = 6f, strokeColor = black))
        c.vectors.addObjects(c.vec, listOf(rect, line), "Add")
        c.tap(100f, 100f)
        val filled = (c.vec.vector!!.byId(1) as VShape).shape
        assertEquals(ShapeStyle.STROKE_FILL, filled.style)
        assertEquals(red, filled.fillColor)
        assertFalse(filled.fillFollowsColor)
        assertEquals(black, filled.strokeColor)
        // The outline: the stroke changes, the fill stays.
        c.color = 0xFF10A030.toInt()
        c.tap(160f, 100f)
        val lined = (c.vec.vector!!.byId(1) as VShape).shape
        assertEquals(0xFF10A030.toInt(), lined.strokeColor)
        assertEquals(red, lined.fillColor)
        // A line shape.
        c.tap(231f, 100f)
        assertEquals(0xFF10A030.toInt(), (c.vec.vector!!.byId(2) as VShape).shape.strokeColor)
        assertArrayEquals(render(c.vec.vector!!), pixels(c.vec.bitmap))
        assertTrue(c.vec.isVectorLayer)
    }

    private fun awaitFill(c: EditorController) {
        val state = VectorDrawState.of(c)
        val deadline = System.currentTimeMillis() + 10_000
        while (state.filling && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("the fill finished", state.filling)
    }

    @Test
    fun anEnclosedEmptyAreaBecomesAFilledPathUnderTheLineArt() {
        val c = setup()
        // A frame of four strokes with a small square of strokes inside it (a hole).
        val art = listOf(
            stroke(40f, 40f, 260f, 40f), stroke(260f, 40f, 260f, 180f), stroke(260f, 180f, 40f, 180f), stroke(40f, 180f, 40f, 40f),
            stroke(130f, 90f, 170f, 90f), stroke(170f, 90f, 170f, 130f), stroke(170f, 130f, 130f, 130f), stroke(130f, 130f, 130f, 90f),
        )
        c.vectors.addObjects(c.vec, art, "Add")
        val before = c.vec.vector!!
        val steps = c.undoManager.undoCount
        c.tap(80f, 110f)
        awaitFill(c)
        val after = c.vec.vector!!
        assertEquals(9, after.objects.size)
        val fill = after.objects.first() as VPath
        assertEquals(9L, fill.id)
        assertEquals(VFillRule.EVENODD, fill.fillRule)
        assertEquals(VPaint.Solid(red), fill.fill)
        assertNull(fill.stroke)
        assertTrue(fill.subpaths.all { it.closed })
        assertEquals(2, fill.subpaths.size) // the outer edge and the hole
        // The line art is still on top, unchanged.
        assertEquals(before.objects, after.objects.drop(1))
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(VectorFill.FILL_AREA_LABEL, c.undoManager.undoLabel)
        assertArrayEquals(render(after), pixels(c.vec.bitmap))
        // Filled inside the frame, not in the hole, not outside.
        assertEquals(red, c.vec.bitmap.getPixel(80, 110))
        assertEquals(0, c.vec.bitmap.getPixel(150, 110))
        assertEquals(0, c.vec.bitmap.getPixel(20, 20))
        // Tapping the fill again recolors it.
        c.color = 0xFF3030F0.toInt()
        c.tap(220f, 150f)
        assertEquals(VPaint.Solid(0xFF3030F0.toInt()), (c.vec.vector!!.objects.first() as VPath).fill)
        assertFalse(VectorDrawState.of(c).filling)
        c.undo()
        c.undo()
        assertSame(before, c.vec.vector)
        assertArrayEquals(render(before), pixels(c.vec.bitmap))
    }

    @Test
    fun theMaskAndRasterLayersAreFilledAsBefore() {
        val c = setup()
        c.vectors.addObjects(c.vec, listOf(box(40f, 40f, 140f, 140f)), "Add")
        val content = c.vec.vector
        // Hit tests ignore far taps; on a raster layer the seam is not called.
        assertNull(FillHits.find(content!!, Vec2(250f, 200f), 4f, TargetCache()))
        c.addMask(c.vec, fromSelection = false)
        assertFalse(VectorFill.tap(c, Vec2(90f, 90f)))
        assertSame(content, c.vec.vector)
    }
}
