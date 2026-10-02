package com.brushwork.paint.tools.vector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStrokeKind
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.5 §4.5 (A4 review): a line whose points are ALL at 0 % thickness draws nothing, with "Current
 * brush" as with "Plain line" (the brush used to fall back to its full size: a profile whose
 * largest factor is 0 was ignored), and a vector layer keeps no invisible object for it. A fill
 * still paints, and on a vector layer the object keeps its line style (thickness can come back)
 * while its cache stays the rendering of its data (I1).
 */
@RunWith(RobolectricTestRunner::class)
class CurveReviewRobolectricTest {

    private val ink = 0xFF203080.toInt()
    private val green = 0xFF40A060.toInt()
    private val w = 320
    private val h = 240

    private fun controller(vector: Boolean): EditorController {
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
            it.brush = BrushLibrary.byId("softround")!!.copy(size = 16f)
            it.snapping.enabled = false
        }
    }

    private fun curveTool(c: EditorController): CurveTool {
        c.selectTool(ToolId.CURVE)
        return c.tools.getValue(ToolId.CURVE) as CurveTool
    }

    private fun EditorController.tap(x: Float, y: Float) {
        pointerDown(ToolPoint(x, y))
        pointerUp(ToolPoint(x, y))
    }

    private fun EditorController.composite(): Bitmap {
        val out = BitmapUtils.createLayerBitmap(doc.width, doc.height)
        compositor.drawDocument(Canvas(out), null, target = null)
        return out
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }

    private fun render(content: VectorContent): IntArray {
        val b = BitmapUtils.createLayerBitmap(w, h)
        VectorLayerRenderer.render(Canvas(b), content, Rect(0, 0, w, h), tips = TipCache(), document = Rect(0, 0, w, h))
        return pixels(b)
    }

    private fun painted(b: Bitmap): Int = pixels(b).count { (it ushr 24) != 0 }

    /** Painted pixels of the line's ink (bluer than green: the fill is green). */
    private fun inkPixels(b: Bitmap): Int = pixels(b).count { (it ushr 24) != 0 && (it and 0xFF) > ((it ushr 8) and 0xFF) }

    /** Three points of a triangle, every one at 0 %. */
    private fun zeroLine(c: EditorController, tool: CurveTool) {
        c.tap(60f, 190f); c.tap(160f, 50f); c.tap(270f, 190f)
        for (i in 0 until 3) tool.setWidth(i, 0f)
        tool.endNumericEdit()
    }

    @Test
    fun aBrushLineAtZeroEverywherePaintsNothingOnARasterLayer() {
        val c = controller(vector = false)
        val layer = c.activeLayer
        val tool = curveTool(c)
        tool.update { it.copy(stroke = CurveStroke.BRUSH, taper = false) }
        zeroLine(c, tool)
        tool.flushPreview()
        assertFalse("no live stroke", tool.brushLive)
        assertEquals("nothing previewed", 0, painted(c.composite()))
        tool.commit()
        assertEquals("nothing painted", 0, painted(layer.bitmap))
        assertEquals("no step", 0, c.undoManager.undoCount)
        assertTrue(tool.anchors.isEmpty())

        // With a fill: only the fill.
        tool.update { it.copy(fill = true, closed = true, fillColor = green) }
        zeroLine(c, tool)
        tool.flushPreview()
        assertFalse(tool.brushLive)
        tool.commit()
        assertEquals(1, c.undoManager.undoCount)
        assertEquals("the fill", green, layer.bitmap.getPixel(160, 150))
        assertEquals("no line", 0, inkPixels(layer.bitmap))
        // The user's brush is as it was.
        assertEquals(16f, c.brush.size, 0f)
    }

    @Test
    fun aBrushLineAtZeroEverywhereLeavesNoInvisibleObjectOnAVectorLayer() {
        val c = controller(vector = true)
        val layer = c.activeLayer
        val tool = curveTool(c)
        tool.update { it.copy(stroke = CurveStroke.BRUSH, taper = false) }
        zeroLine(c, tool)
        tool.flushPreview()
        assertFalse(tool.brushLive)
        assertEquals(0, painted(c.composite()))
        tool.commit()
        assertEquals("no object", VectorContent.EMPTY, layer.vector)
        assertEquals("no step", 0, c.undoManager.undoCount)
        assertEquals(0, painted(layer.bitmap))

        // The same with a plain line.
        tool.update { it.copy(stroke = CurveStroke.PLAIN) }
        zeroLine(c, tool)
        tool.commit()
        assertEquals(VectorContent.EMPTY, layer.vector)
        assertEquals(0, c.undoManager.undoCount)

        // With a fill: one object that keeps its brush line (0 % everywhere), the cache is its rendering.
        tool.update { it.copy(stroke = CurveStroke.BRUSH, fill = true, closed = true, fillColor = green) }
        zeroLine(c, tool)
        tool.flushPreview()
        assertFalse(tool.brushLive)
        tool.commit()
        assertEquals(1, c.undoManager.undoCount)
        val content = layer.vector!!
        val p = content.objects.single() as VPath
        assertEquals(VStrokeKind.BRUSH, p.stroke!!.kind)
        assertEquals(VPaint.Solid(green), p.fill)
        assertEquals(listOf(0f, 0f, 0f), p.subpaths.single().anchors.map { it.width })
        assertArrayEquals("I1: the cache is the rendering of the object", render(content), pixels(layer.bitmap))
        assertEquals(green, layer.bitmap.getPixel(160, 150))
        assertEquals(0, inkPixels(layer.bitmap))
    }

    @Test
    fun aReopenedBrushPathSetToZeroShowsNoLineAndCommitsItsRendering() {
        val c = controller(vector = true)
        val layer = c.activeLayer
        val tool = curveTool(c)
        tool.update { it.copy(stroke = CurveStroke.BRUSH, taper = false, fill = true, closed = true, fillColor = green) }
        c.tap(60f, 190f); c.tap(160f, 50f); c.tap(270f, 190f)
        tool.flushPreview()
        tool.commit()
        assertEquals(1, c.undoManager.undoCount)
        val line = (layer.vector!!.objects.single() as VPath)
        assertTrue("the line is painted", inkPixels(layer.bitmap) > 300)
        // Reopen it by tapping its line, then set every point to 0 %.
        val q = onLine(line)
        c.tap(q.x, q.y)
        assertTrue(tool.isReopened)
        for (i in 0 until 3) tool.setWidth(i, 0f)
        tool.endNumericEdit()
        tool.flushPreview()
        assertFalse("no live stroke", tool.brushLive)
        val preview = c.composite()
        assertEquals("the fill stays", green, preview.getPixel(160, 150))
        assertEquals("the line is gone from the preview", 0, inkPixels(preview))
        tool.commit()
        assertEquals("Edit path", c.undoManager.undoLabel)
        assertEquals(2, c.undoManager.undoCount)
        val edited = layer.vector!!.objects.single() as VPath
        assertEquals(line.id, edited.id)
        assertEquals(line.stroke!!.brush, edited.stroke!!.brush)
        assertArrayEquals(render(layer.vector!!), pixels(layer.bitmap))
        c.undo()
        assertEquals(line, layer.vector!!.objects.single())
    }

    @Test
    fun aReopenedBrushPathDownToOnePointNoLongerShowsItsOldPixels() {
        val c = controller(vector = true)
        val layer = c.activeLayer
        val tool = curveTool(c)
        tool.update { it.copy(stroke = CurveStroke.BRUSH, taper = false) }
        c.tap(60f, 190f); c.tap(160f, 50f); c.tap(270f, 190f)
        tool.flushPreview()
        tool.commit()
        assertTrue(inkPixels(layer.bitmap) > 300)
        // Reopen it on its line, then delete points until one is left.
        val q = onLine(layer.vector!!.objects.single() as VPath)
        c.tap(q.x, q.y)
        assertTrue(tool.isReopened)
        assertTrue("unchanged: its own pixels", inkPixels(c.composite()) > 300)
        tool.deleteAnchor(2)
        tool.flushPreview()
        assertTrue("two points: the new stroke", tool.brushLive)
        tool.deleteAnchor(1)
        tool.flushPreview()
        assertFalse(tool.brushLive)
        assertEquals("one point draws nothing (as ✓ will leave it)", 0, inkPixels(c.composite()))
        tool.commit()
        assertTrue("the object went", layer.vector!!.objects.isEmpty())
        assertEquals(0, painted(layer.bitmap))
        assertEquals(2, c.undoManager.undoCount)
    }

    @Test
    fun aFilledPathReopensFromItsLineAndATapInsideStartsANewPath() {
        val c = controller(vector = true)
        val layer = c.activeLayer
        c.brush = c.brush.copy(size = 8f)
        val tool = curveTool(c)
        tool.update { it.copy(stroke = CurveStroke.PLAIN, fill = true, closed = true, fillColor = green) }
        c.tap(60f, 190f); c.tap(160f, 50f); c.tap(270f, 190f)
        tool.commit()
        val shape = layer.vector!!.objects.single() as VPath
        assertEquals(1, c.undoManager.undoCount)
        // A tap inside the fill, away from the line: the first point of a new curve over it.
        c.tap(160f, 150f)
        assertFalse(tool.isReopened)
        assertEquals(1, tool.anchors.size)
        c.tap(200f, 120f)
        tool.commit()
        assertEquals("a second object on top", 2, layer.vector!!.objects.size)
        assertEquals(shape, layer.vector!!.objects[0])
        assertEquals(2, c.undoManager.undoCount)
        // A tap on its line reopens it.
        val q = onLine(shape)
        c.tap(q.x, q.y)
        assertTrue(tool.isReopened)
        assertEquals(3, tool.anchors.size)
        tool.discard()
        assertFalse(tool.isReopened)

        // A fill alone (no line) reopens from anywhere inside it.
        tool.update { it.copy(stroke = CurveStroke.NONE) }
        c.tap(20f, 230f); c.tap(40f, 200f); c.tap(60f, 230f)
        tool.commit()
        val fillOnly = layer.vector!!.objects.last() as VPath
        assertEquals(null, fillOnly.stroke)
        c.tap(40f, 222f)
        assertTrue(tool.isReopened)
        tool.discard()
        assertEquals(3, c.undoManager.undoCount)
    }

    /** A point on [p]'s line. */
    private fun onLine(p: VPath) = VectorOps.toVectorPath(p).flatten(0.25f).single().points.let { it[it.size / 4] }
}
