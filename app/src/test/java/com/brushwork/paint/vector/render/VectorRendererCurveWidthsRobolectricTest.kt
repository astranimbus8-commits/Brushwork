package com.brushwork.paint.vector.render

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
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.CurveWidths
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStrokeKind
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorOps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs
import kotlin.math.max

/**
 * v1.5 integration (A1 renderer x A4 per-point thickness, §4.5 / §4.9): the vector layer renderer
 * feeds a path's anchor thicknesses exactly as the Curve tool does (CurveWidths.atSamples /
 * CurveWidths.line), so a brush path with varying thickness that the tool kept as live pixels is
 * its object's replay, and a later re-render of part of the layer leaves no seam.
 */
@RunWith(RobolectricTestRunner::class)
class VectorRendererCurveWidthsRobolectricTest {
    private val ink = 0xFF203080.toInt()
    private val w = 360
    private val h = 260

    private fun controller(): EditorController {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("brushwork_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h))
        doc.layers += Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(w, h)).also { it.vector = VectorContent.EMPTY }
        doc.activeLayerIndex = 1
        return EditorController(ctx, doc, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), AppSettings(ctx)).also {
            it.color = ink
            it.tools
            it.snapping.enabled = false
        }
    }

    private fun EditorController.tap(x: Float, y: Float) {
        pointerDown(ToolPoint(x, y))
        pointerUp(ToolPoint(x, y))
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }

    private fun render(content: VectorContent): IntArray {
        val b = BitmapUtils.createLayerBitmap(w, h)
        VectorLayerRenderer.render(Canvas(b), content, Rect(0, 0, w, h), tips = TipCache(), document = Rect(0, 0, w, h))
        return pixels(b)
    }

    private fun channelDiff(a: Int, b: Int): Int {
        var m = 0
        for (s in intArrayOf(24, 16, 8, 0)) m = max(m, abs(((a ushr s) and 0xFF) - ((b ushr s) and 0xFF)))
        return m
    }

    /** The live-vs-replay bar (§4.9e): ≥ 99.5 % of the painted pixels within ±2, max ≤ 12. */
    private fun assertParity(what: String, a: IntArray, b: IntArray) {
        var ok = 0
        var painted = 0
        var worst = 0
        for (i in a.indices) {
            if (a[i] == 0 && b[i] == 0) continue
            painted++
            val d = channelDiff(a[i], b[i])
            if (d <= 2) ok++
            worst = max(worst, d)
        }
        assertTrue("$what: something is painted", painted > 200)
        assertTrue("$what: ${ok * 100.0 / painted} % within ±2 (max $worst)", ok >= painted * 0.995 && worst <= 12)
    }

    @Test
    fun aVaryingWidthBrushPathKeptLiveIsItsReplayAndPartialReRendersLeaveNoSeam() {
        for (id in listOf("pen", "softround")) {
            val c = controller()
            val layer = c.activeLayer
            c.brush = BrushLibrary.byId(id)!!.copy(size = 12f)
            c.selectTool(ToolId.CURVE)
            val tool = c.tools.getValue(ToolId.CURVE) as CurveTool
            tool.update { it.copy(stroke = CurveStroke.BRUSH, taper = false, fill = false, closed = false) }
            c.tap(40f, 200f); c.tap(130f, 50f); c.tap(230f, 210f); c.tap(320f, 60f)
            tool.setWidth(0, 0.4f)
            tool.endNumericEdit()
            tool.setWidth(1, 2.6f)
            tool.endNumericEdit()
            tool.setWidth(3, 1.7f)
            tool.endNumericEdit()
            tool.flushPreview()
            assertTrue("$id: drawn live", tool.brushLive)
            tool.commit()
            val content = layer.vector!!
            val p = content.objects.single() as VPath
            assertEquals(VStrokeKind.BRUSH, p.stroke!!.kind)
            assertEquals(listOf(0.4f, 2.6f, 1f, 1.7f), p.subpaths.single().anchors.map { it.width })
            // The kept live pixels are the object's replay (the bar the uniform case meets).
            assertParity("$id: cache vs render", render(content), pixels(layer.bitmap))
            // A later re-render of part of the layer (here: an object added next to the path,
            // re-rendering the left half's tiles) joins the kept pixels without a seam.
            val dot = VPath(0, subpaths = listOf(VSubpath(listOf(VAnchor(20f, 20f, true), VAnchor(24f, 24f, true)))), stroke = VStrokeStyle(color = ink, width = 3f))
            val after = content.plus(listOf(dot)).first
            var applied = false
            c.vectors.update(layer, after, "Add", dirty = listOf(Rect(0, 0, w / 2, h))) { applied = it }
            assertTrue(applied)
            val fresh = render(after)
            val now = pixels(layer.bitmap)
            // Left half: re-rendered, exactly the rendering; overall: still the replay.
            for (y in 0 until h) for (x in 0 until 128) assertEquals("$id: re-rendered at $x,$y", fresh[y * w + x], now[y * w + x])
            assertParity("$id: after a partial re-render", fresh, now)
        }
    }

    @Test
    fun aVaryingWidthPlainLineIsTheCurveToolsOutline() {
        val c = controller()
        val layer = c.activeLayer
        c.selectTool(ToolId.CURVE)
        val tool = c.tools.getValue(ToolId.CURVE) as CurveTool
        tool.update { it.copy(stroke = CurveStroke.PLAIN, useBrushSize = false, plainWidth = 9f, fill = false, closed = false) }
        c.tap(40f, 200f); c.tap(130f, 50f); c.tap(230f, 210f)
        tool.setWidth(1, 2.2f)
        tool.endNumericEdit()
        tool.commit()
        val content = layer.vector!!
        assertArrayEquals("the cache is the rendering of the object", render(content), pixels(layer.bitmap))
        // The renderer's outline is CurveWidths.line's, point for point.
        val path = content.objects.single() as VPath
        val line = CurveWidths.line(VectorOps.curveAnchors(path.subpaths.single()), false, path.tension, path.polyline, 9f)!!
        assertTrue(line.n > 10)
        assertEquals(9f * 2.2f, (0 until line.n).maxOf { line.ws[it] }, 1e-3f)
    }
}
