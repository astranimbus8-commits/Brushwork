package com.brushwork.paint.vector.draw

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.VAnchor
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.5 A3: the eraser on vector layers through the real Eraser tool: Object / Partial / To
 * intersection on crossing strokes (counts and remaining geometry), one step "Erase" whose cache
 * is a fresh render, undo / redo, the dimmed live preview, a cancelled gesture leaving no trace,
 * the remembered mode, and raster layers erased as before.
 */
@RunWith(RobolectricTestRunner::class)
class VectorEraserRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 300
    private val h = 200

    private fun setup(): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h))
        doc.layers += Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(w, h)).also { it.vector = VectorContent.EMPTY }
        doc.activeLayerIndex = 1
        return EditorController(app, doc, scope, settings).also {
            it.viewTransform.set(Matrix())
            it.tools
        }
    }

    private val EditorController.vec: Layer get() = doc.layers[1]

    private fun pixels(b: Bitmap): IntArray = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }

    private fun render(content: VectorContent): IntArray {
        val b = BitmapUtils.createLayerBitmap(w, h)
        VectorLayerRenderer.render(Canvas(b), content, Rect(0, 0, w, h), tips = TipCache(), document = Rect(0, 0, w, h))
        return pixels(b)
    }

    private val pen = BrushLibrary.defaultBrush.copy(size = 8f, taperStart = 20f, taperEnd = 20f)

    private fun stroke(x0: Float, y0: Float, x1: Float, y1: Float, n: Int = 31): VStroke {
        val xs = FloatArray(n) { x0 + (x1 - x0) * it / (n - 1) }
        val ys = FloatArray(n) { y0 + (y1 - y0) * it / (n - 1) }
        return VStroke(0, preset = pen, color = 0xFF000000.toInt(), seed = 5L, stylus = false, points = PackedPoints(xs, ys, FloatArray(n) { 1f }))
    }

    /** A horizontal line y = 100 (x 0..300) crossed by verticals at x = 100 and x = 200. */
    private fun lines(c: EditorController) {
        c.vectors.addObjects(c.vec, listOf(stroke(0f, 100f, 300f, 100f), stroke(100f, 20f, 100f, 180f), stroke(200f, 20f, 200f, 180f)), "Add")
    }

    private fun eraser(c: EditorController, mode: VectorEraseMode, size: Float = 12f) {
        c.selectTool(ToolId.ERASER)
        c.eraser = BrushLibrary.defaultEraser.copy(size = size)
        VectorEraserModes.setMode(c, mode)
    }

    private fun EditorController.swipe(vararg pts: Pair<Float, Float>) {
        pointerDown(ToolPoint(pts[0].first, pts[0].second))
        for (i in 1 until pts.size) pointerMove(ToolPoint(pts[i].first, pts[i].second))
        pointerUp(ToolPoint(pts.last().first, pts.last().second))
    }

    @Test
    fun objectModeRemovesTouchedObjectsAsOneStep() {
        val c = setup()
        lines(c)
        val before = c.vec.vector!!
        val steps = c.undoManager.undoCount
        eraser(c, VectorEraseMode.OBJECT)
        c.swipe(150f to 60f, 150f to 140f)
        val after = c.vec.vector!!
        assertEquals(listOf(2L, 3L), after.objects.map { it.id })
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(VectorEraserRecorder.ERASE_LABEL, c.undoManager.undoLabel)
        assertArrayEquals(render(after), pixels(c.vec.bitmap))
        assertNull(c.renderOverride)
        c.undo()
        assertSame(before, c.vec.vector)
        assertArrayEquals(render(before), pixels(c.vec.bitmap))
        c.redo()
        assertSame(after, c.vec.vector)
        assertArrayEquals(render(after), pixels(c.vec.bitmap))
        // Nothing touched: no step.
        c.swipe(40f to 30f, 60f to 40f)
        assertEquals(steps + 1, c.undoManager.undoCount)
    }

    @Test
    fun partialModeCutsTheStrokeAndTheCutEndsLoseTheirTaper() {
        val c = setup()
        lines(c)
        eraser(c, VectorEraseMode.PARTIAL)
        c.swipe(150f to 60f, 150f to 140f)
        val after = c.vec.vector!!
        assertEquals(4, after.objects.size)
        val left = after.byId(1) as VStroke
        val right = after.byId(4) as VStroke
        // Eraser radius 6 + pen radius 4.
        assertEquals(140f, left.points.x[left.points.size - 1], 1e-3f)
        assertEquals(160f, right.points.x[0], 1e-3f)
        assertTrue(left.taperIn)
        assertFalse(left.taperOut)
        assertFalse(right.taperIn)
        assertTrue(right.taperOut)
        assertArrayEquals(render(after), pixels(c.vec.bitmap))
        // The cut is a real gap in the pixels.
        assertEquals(0, c.vec.bitmap.getPixel(150, 100))
        assertTrue(c.vec.bitmap.getPixel(120, 100) != 0)
        c.undo()
        assertEquals(3, c.vec.vector!!.objects.size)
        assertArrayEquals(render(c.vec.vector!!), pixels(c.vec.bitmap))
    }

    @Test
    fun partialModeErasesClosedShapesWholeWithAHintOnce() {
        val c = setup()
        val ring = VShape(0, shape = ShapeObject(ShapeType.ELLIPSE, cx = 150f, cy = 100f, w = 120f, h = 80f, style = ShapeStyle.STROKE, strokeWidth = 4f))
        val open = VPath(0, subpaths = listOf(VSubpath(listOf(VAnchor(20f, 20f), VAnchor(150f, 60f), VAnchor(280f, 20f)))), stroke = VStrokeStyle(color = 0xFF802000.toInt(), width = 5f))
        c.vectors.addObjects(c.vec, listOf(ring, open), "Add")
        eraser(c, VectorEraseMode.PARTIAL)
        // Through the ring's left side only.
        c.swipe(90f to 80f, 90f to 120f)
        assertEquals(listOf(2L), c.vec.vector!!.objects.map { it.id })
        assertEquals("Closed shapes and fills are erased whole (\"Partial\" cuts strokes and lines)", c.message)
        c.message = null
        // The open curve is split, not removed.
        c.swipe(150f to 30f, 150f to 90f)
        val pieces = c.vec.vector!!.objects
        assertEquals(2, pieces.size)
        assertTrue(pieces.all { it is VPath && (it as VPath).subpaths.single().anchors.size >= 2 })
        assertNull(c.message)
        assertArrayEquals(render(c.vec.vector!!), pixels(c.vec.bitmap))
    }

    @Test
    fun toIntersectionRemovesTheTouchedPieceUpToTheCrossings() {
        val c = setup()
        lines(c)
        eraser(c, VectorEraseMode.TO_INTERSECTION)
        // The middle piece: the line splits at both crossings.
        c.swipe(150f to 80f, 150f to 120f)
        val content = c.vec.vector!!
        assertEquals(4, content.objects.size)
        fun horizontal(v: VectorContent) = v.objects.filterIsInstance<VStroke>().filter { s -> (0 until s.points.size).all { s.points.y[it] == 100f } }
        val pieces = horizontal(content)
        assertEquals(2, pieces.size)
        assertEquals(0f, pieces[0].points.x[0], 1e-3f)
        assertEquals(100f, pieces[0].points.x[pieces[0].points.size - 1], 1e-3f)
        assertTrue(pieces[0].taperIn)
        assertFalse(pieces[0].taperOut)
        assertEquals(200f, pieces[1].points.x[0], 1e-3f)
        assertFalse(pieces[1].taperIn)
        assertEquals(300f, pieces[1].points.x[pieces[1].points.size - 1], 1e-3f)
        assertArrayEquals(render(content), pixels(c.vec.bitmap))
        // The overhang right of x = 200 (a piece from the crossing to its end) goes whole.
        c.swipe(250f to 80f, 255f to 120f)
        val after = c.vec.vector!!
        assertEquals(3, after.objects.size)
        assertEquals(1, horizontal(after).size)
        // The verticals were never touched.
        assertEquals(content.byId(2), after.byId(2))
        assertEquals(content.byId(3), after.byId(3))
        assertArrayEquals(render(after), pixels(c.vec.bitmap))
    }

    @Test
    fun whatWillGoIsShownDimmedAndACancelLeavesNoTrace() {
        val c = setup()
        lines(c)
        val before = c.vec.vector!!
        val cache = pixels(c.vec.bitmap)
        val steps = c.undoManager.undoCount
        eraser(c, VectorEraseMode.OBJECT)
        c.pointerDown(ToolPoint(150f, 60f))
        c.pointerMove(ToolPoint(150f, 100f))
        c.pointerMove(ToolPoint(150f, 140f))
        val ov = c.renderOverride
        assertNotNull(ov)
        assertSame(c.vec, ov!!.layer)
        val shown = BitmapUtils.createLayerBitmap(w, h)
        assertTrue(ov.drawContent(Canvas(shown)))
        // The doomed line is faded, the others are as they were; the layer is untouched.
        val doomed = shown.getPixel(40, 100) ushr 24
        assertTrue("faded alpha $doomed", doomed in 1..140)
        assertEquals(c.vec.bitmap.getPixel(100, 40), shown.getPixel(100, 40))
        assertArrayEquals(cache, pixels(c.vec.bitmap))
        c.pointerCancel()
        assertNull(c.renderOverride)
        assertSame(before, c.vec.vector)
        assertArrayEquals(cache, pixels(c.vec.bitmap))
        assertEquals(steps, c.undoManager.undoCount)
    }

    @Test
    fun theModeIsRememberedAndRasterLayersEraseAsBefore() {
        val c = setup()
        VectorEraserModes.setMode(c, VectorEraseMode.TO_INTERSECTION)
        assertEquals("TO_INTERSECTION", c.settings.vectorEraserMode)
        val other = EditorController(app, c.doc, scope, c.settings)
        assertEquals(VectorEraseMode.TO_INTERSECTION, VectorEraserModes.mode(other))
        // A raster layer: the eraser removes pixels as before.
        val raster = c.doc.layers[0]
        Canvas(raster.bitmap).drawColor(0xFF00FF00.toInt())
        c.selectLayer(raster)
        eraser(c, VectorEraseMode.OBJECT)
        c.swipe(20f to 20f, 120f to 20f)
        assertEquals(0, raster.bitmap.getPixel(70, 20) ushr 24)
        assertNull(raster.vector)
        // An alpha-locked vector layer: the eraser is refused (nothing changes).
        c.selectLayer(c.vec)
        lines(c)
        val before = c.vec.vector
        c.vec.alphaLocked = true
        val steps = c.undoManager.undoCount
        c.swipe(150f to 60f, 150f to 140f)
        assertSame(before, c.vec.vector)
        assertEquals(steps, c.undoManager.undoCount)
    }
}
