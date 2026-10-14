package com.brushwork.paint.v19.a5

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
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
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.draw.DoomPreview
import com.brushwork.paint.vector.draw.VectorEraseMode
import com.brushwork.paint.vector.draw.VectorEraserModes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.9 (A5 step 3, design 2.4.4): the vector eraser's preview takes away what will go (v1.8
 * dimmed it). In Object mode the preview is the result, pixel for pixel; its mask is rebuilt at
 * most once per frame, however many moves came in between; in Partial and "to intersection" what
 * the preview shows is still there after the lift (it may hide a little more: the cut ends).
 */
@RunWith(RobolectricTestRunner::class)
class IncrementalErasePreviewTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val w = 300
    private val h = 220

    private fun setup(): EditorController {
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(w, h)).also { it.vector = VectorContent.EMPTY }
        doc.activeLayerIndex = 0
        return EditorController(app, doc, scope, settings).also {
            it.viewTransform.set(Matrix())
            it.tools
        }
    }

    private val EditorController.vec: Layer get() = doc.layers[0]

    private fun pixels(b: Bitmap): IntArray = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }

    private val pen = BrushLibrary.defaultBrush.copy(size = 8f, taperStart = 20f, taperEnd = 20f)

    private fun stroke(x0: Float, y0: Float, x1: Float, y1: Float, n: Int = 31): VStroke {
        val xs = FloatArray(n) { x0 + (x1 - x0) * it / (n - 1) }
        val ys = FloatArray(n) { y0 + (y1 - y0) * it / (n - 1) }
        return VStroke(0, preset = pen, color = 0xFF203040.toInt(), seed = 5L, stylus = false, points = PackedPoints(xs, ys, FloatArray(n) { 1f }))
    }

    private fun box(l: Float, t: Float, r: Float, b: Float) = VPath(
        0, subpaths = listOf(VSubpath(listOf(VAnchor(l, t), VAnchor(r, t), VAnchor(r, b), VAnchor(l, b)), closed = true)),
        fill = VPaint.Solid(0xFF3070C0.toInt()), stroke = VStrokeStyle(color = 0xFF802000.toInt(), width = 3f),
    )

    private fun ring() = VShape(0, shape = ShapeObject(ShapeType.ELLIPSE, cx = 230f, cy = 100f, w = 60f, h = 50f, style = ShapeStyle.STROKE, strokeWidth = 4f))

    /** Apart from each other: a line at y = 40, a box, a ring and a line at y = 190. */
    private fun objects(c: EditorController) {
        c.vectors.addObjects(c.vec, listOf(stroke(20f, 40f, 280f, 40f), box(40f, 80f, 100f, 130f), ring(), stroke(20f, 190f, 280f, 190f)), "Add")
    }

    private fun eraser(c: EditorController, mode: VectorEraseMode) {
        c.selectTool(ToolId.ERASER)
        c.eraser = BrushLibrary.defaultEraser.copy(size = 12f)
        VectorEraserModes.setMode(c, mode)
    }

    private fun shown(c: EditorController): IntArray {
        val ov = c.renderOverride as DoomPreview
        val b = BitmapUtils.createLayerBitmap(w, h)
        assertTrue(ov.drawContent(Canvas(b)))
        return pixels(b)
    }

    @Test
    fun inObjectModeThePreviewIsTheResult() {
        val c = setup()
        objects(c)
        eraser(c, VectorEraseMode.OBJECT)
        // Down through the first line and the box, then across to the ring.
        val pts = listOf(60f to 10f, 60f to 60f, 60f to 105f, 120f to 105f, 180f to 105f, 205f to 105f)
        c.pointerDown(ToolPoint(pts[0].first, pts[0].second))
        for (p in pts.drop(1)) c.pointerMove(ToolPoint(p.first, p.second))
        val preview = shown(c)
        c.pointerUp(ToolPoint(pts.last().first, pts.last().second))
        assertEquals(1, c.vec.vector!!.objects.size)
        assertNull(c.renderOverride)
        assertArrayEquals(pixels(c.vec.bitmap), preview)
    }

    @Test
    fun theMaskIsRebuiltAtMostOncePerFrame() {
        val c = setup()
        objects(c)
        eraser(c, VectorEraseMode.OBJECT)
        c.pointerDown(ToolPoint(60f, 10f))
        for (k in 1..12) c.pointerMove(ToolPoint(60f, 10f + 10f * k))
        val ov = c.renderOverride as DoomPreview
        assertEquals(0, ov.rebuilds)
        shown(c)
        assertEquals("twelve moves, one frame", 1, ov.rebuilds)
        shown(c)
        assertEquals("nothing changed: no rebuild", 1, ov.rebuilds)
        // Moves over empty canvas change nothing; a move onto the ring does.
        c.pointerMove(ToolPoint(150f, 160f))
        shown(c)
        assertEquals(1, ov.rebuilds)
        c.pointerMove(ToolPoint(230f, 125f))
        c.pointerMove(ToolPoint(232f, 126f))
        shown(c)
        assertEquals(2, ov.rebuilds)
        c.pointerCancel()
        assertNull(c.renderOverride)
        assertEquals(4, c.vec.vector!!.objects.size)
    }

    @Test
    fun thePartialPreviewOnlyShowsWhatStays() {
        for (mode in listOf(VectorEraseMode.PARTIAL, VectorEraseMode.TO_INTERSECTION)) {
            val c = setup()
            objects(c)
            c.vectors.addObjects(c.vec, listOf(stroke(150f, 10f, 150f, 210f)), "Add")
            eraser(c, mode)
            c.pointerDown(ToolPoint(100f, 30f))
            c.pointerMove(ToolPoint(110f, 50f))
            c.pointerMove(ToolPoint(150f, 180f))
            c.pointerMove(ToolPoint(152f, 200f))
            val preview = shown(c)
            c.pointerUp(ToolPoint(152f, 200f))
            val result = pixels(c.vec.bitmap)
            var hidden = 0
            for (i in preview.indices) {
                if (preview[i] ushr 24 > 64) assertTrue("$mode: shown at ${i % w},${i / w} but gone", result[i] ushr 24 > 0)
                if (result[i] ushr 24 > 0 && preview[i] ushr 24 == 0) hidden++
            }
            // (The cut ends: a little of what stays is hidden until the lift.)
            assertTrue("$mode: $hidden", hidden < 400)
        }
    }
}
