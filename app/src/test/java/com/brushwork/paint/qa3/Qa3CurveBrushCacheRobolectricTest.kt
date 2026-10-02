package com.brushwork.paint.qa3

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStrokeKind
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs

/**
 * Final QA (v1.5 §4.5 × I1): a curve painted with the CURRENT BRUSH and a 300 % / 0 % thickness
 * profile, committed on a vector layer, then each point's thickness edited after reopening: the
 * layer's pixels stay the rendering of its objects (what a reload, an erase nearby or a canvas
 * resize re-renders), within the brush engine's own replay tolerance.
 */
@RunWith(RobolectricTestRunner::class)
class Qa3CurveBrushCacheRobolectricTest {
    private val w = 400
    private val h = 300
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** Fraction of pixels whose channels differ by more than 2 (and the largest difference) from a fresh render. */
    private fun diff(layer: Layer): Pair<Float, Int> {
        val b = BitmapUtils.createLayerBitmap(w, h)
        VectorLayerRenderer.render(Canvas(b), layer.vector!!, Rect(0, 0, w, h), tips = TipCache(), document = Rect(0, 0, w, h))
        val a = pixels(b)
        val c = pixels(layer.bitmap)
        var bad = 0
        var max = 0
        var ink = 0
        for (i in a.indices) {
            if ((a[i] ushr 24) != 0 || (c[i] ushr 24) != 0) ink++
            var d = 0
            for (s in intArrayOf(0, 8, 16, 24)) d = maxOf(d, abs(((a[i] ushr s) and 255) - ((c[i] ushr s) and 255)))
            if (d > 2) bad++
            if (d > max) max = d
        }
        return (bad.toFloat() / ink.coerceAtLeast(1)) to max
    }

    @Test
    fun aBrushCurveWithThicknessStaysItsRendering() {
        ctx.getSharedPreferences("brushwork_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val doc = Document("qa3brush", "Brush", w, h)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h))
        doc.layers += Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(w, h)).also { it.vector = VectorContent.EMPTY }
        doc.activeLayerIndex = 1
        val c = EditorController(ctx, doc, scope, AppSettings(ctx))
        c.tools
        c.color = 0xFF203080.toInt()
        c.brush = BrushLibrary.defaultBrush.copy(size = 10f)
        c.snapping.enabled = false
        val layer = doc.layers[1]
        c.selectTool(ToolId.CURVE)
        val tool = c.tools.getValue(ToolId.CURVE) as CurveTool
        tool.update { it.copy(stroke = CurveStroke.BRUSH) }
        for (p in listOf(Vec2(40f, 220f), Vec2(130f, 70f), Vec2(250f, 230f), Vec2(360f, 90f))) tool.addAnchor(p)
        tool.setWidth(0, 0f); tool.endNumericEdit()
        tool.setWidth(1, 3f); tool.endNumericEdit()
        tool.setWidth(2, 0.5f); tool.endNumericEdit()
        tool.commit()
        Smoke.pumpUntil(10_000) { c.settleVectorWork(); !tool.hasPendingWork }
        c.settleVectorWork()
        Smoke.pump(100)
        val path = layer.vector!!.objects.single() as VPath
        assertEquals(VStrokeKind.BRUSH, path.stroke!!.kind)
        assertEquals(listOf(0f, 3f, 0.5f, 1f), path.subpaths[0].anchors.map { it.width })
        val (bad, max) = diff(layer)
        assertTrue("the committed pixels are the objects' rendering: ${bad * 100} % off, max $max", bad <= 0.005f && max <= 12)

        // Reopen, thin the 300 % point to 150 %, ✓.
        c.pointerDown(com.brushwork.paint.tools.ToolPoint(130f, 70f))
        c.pointerUp(com.brushwork.paint.tools.ToolPoint(130f, 70f))
        Smoke.pumpUntil(10_000) { tool.isReopened }
        assertTrue(tool.isReopened)
        tool.select(1)
        tool.setWidth(1, 1.5f); tool.endNumericEdit()
        tool.commit()
        Smoke.pumpUntil(10_000) { c.settleVectorWork(); !tool.hasPendingWork }
        c.settleVectorWork()
        Smoke.pump(100)
        assertEquals(listOf(0f, 1.5f, 0.5f, 1f), (layer.vector!!.objects.single() as VPath).subpaths[0].anchors.map { it.width })
        val (bad2, max2) = diff(layer)
        assertTrue("after the edit too: ${bad2 * 100} % off, max $max2", bad2 <= 0.005f && max2 <= 12)
        c.dispose()
    }
}
