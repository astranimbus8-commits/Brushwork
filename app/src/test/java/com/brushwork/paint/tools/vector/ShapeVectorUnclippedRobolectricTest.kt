package com.brushwork.paint.tools.vector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.5 integration (A3 shapes x A4 BrushStrokePreview.unclipped): a brush-outlined shape on a
 * vector layer is previewed as the painting tool's live stroke also while a pixel selection is
 * active or the layer's alpha is locked (objects are clipped by neither), and ✓ keeps those
 * pixels as the object's exact replay, in one step, with the selection and lock left as they were.
 */
@RunWith(RobolectricTestRunner::class)
class ShapeVectorUnclippedRobolectricTest {
    private val scopes = ArrayList<CoroutineScope>()

    @After
    fun releaseEditors() {
        for (s in scopes) s.cancel()
        scopes.clear()
    }

    private val w = 300
    private val h = 240

    private fun controller(): EditorController {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("brushwork_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val d = Document("t", "t", w, h)
        d.layers += Layer(d.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h))
        d.layers += Layer(d.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(w, h)).also { it.vector = VectorContent.EMPTY }
        d.activeLayerIndex = 1
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it }
        return EditorController(ctx, d, scope, AppSettings(ctx)).also {
            it.viewTransform.set(Matrix())
            it.color = 0xFFFF0000.toInt()
            it.tools
            it.brush = BrushLibrary.byId("pen")!!.copy(size = 7f)
        }
    }

    private fun EditorController.drag(vararg pts: Pair<Float, Float>) {
        pointerDown(ToolPoint(pts[0].first, pts[0].second))
        for (i in 1 until pts.size) pointerMove(ToolPoint(pts[i].first, pts[i].second))
        pointerUp(ToolPoint(pts.last().first, pts.last().second))
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun render(content: VectorContent): IntArray {
        val b = BitmapUtils.createLayerBitmap(w, h)
        VectorLayerRenderer.render(Canvas(b), content, Rect(0, 0, w, h), tips = TipCache(), document = Rect(0, 0, w, h))
        return pixels(b)
    }

    /** A selection of the top-left corner only (the rectangle's right edge is outside it). */
    private fun corner(): Selection {
        val bytes = ByteArray(w * h)
        for (y in 0 until 120) for (x in 0 until 120) bytes[y * w + x] = 0xFF.toByte()
        return Selection.fromBytes(bytes, w, h)
    }

    @Test
    fun aBrushOutlineIsLiveAndKeptWithASelectionOrAlphaLock() {
        for (case in listOf("selection", "alpha lock")) {
            val c = controller()
            val layer = c.doc.layers[1]
            val sel = corner()
            if (case == "selection") c.setSelection(sel, recordUndo = false) else layer.alphaLocked = true
            c.selectTool(ToolId.SHAPE)
            val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
            tool.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE, strokeWith = ShapeStroke.BRUSH) }
            c.drag(40f to 40f, 150f to 120f, 230f to 190f)
            tool.flushPreview()
            // The live stroke paints the right edge (x = 230), outside the selection, in the
            // preview of the layer itself (not as an overlay guide).
            val shown = BitmapUtils.createLayerBitmap(w, h)
            assertTrue(c.renderOverride!!.drawContent(Canvas(shown)))
            assertTrue("$case: the outline shows live outside the selection", shown.getPixel(230, 150) ushr 24 > 0)
            tool.commit()
            val s = layer.vector!!.objects.single() as VShape
            assertTrue(s.shape.paintsWithBrush)
            assertEquals(1, c.undoManager.undoCount)
            assertArrayEquals("$case: the kept live pixels are the object's replay", render(layer.vector!!), pixels(layer.bitmap))
            assertTrue("$case: painted outside the selection too", layer.bitmap.getPixel(230, 150) ushr 24 > 0)
            if (case == "selection") assertSame("the selection is left as it was", sel, c.selection) else assertTrue(layer.alphaLocked)
        }
    }
}
