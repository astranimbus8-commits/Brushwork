package com.brushwork.paint.vector.draw

import android.graphics.Matrix
import android.os.Looper
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.vector.VFillRule
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VectorContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * v1.5 A3 review: a bucket tap a few dp beside line art fills the area the lines enclose (the
 * common case when coloring line art: small areas are mostly close to a line); only a tap on a
 * line, or right beside its paint, recolors it.
 */
@RunWith(RobolectricTestRunner::class)
class VectorFillTapReviewTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()
    private val red = 0xFFE02020.toInt()
    private val black = 0xFF000000.toInt()

    private fun setup(): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", 400, 300)
        doc.layers += Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(400, 300)).also { it.vector = VectorContent.EMPTY }
        return EditorController(app, doc, scope, settings).also {
            it.viewTransform.set(Matrix())
            it.tools
            it.selectTool(ToolId.FILL)
            it.color = red
        }
    }

    private fun stroke(x0: Float, y0: Float, x1: Float, y1: Float): VStroke {
        val n = 20
        val xs = FloatArray(n) { x0 + (x1 - x0) * it / (n - 1) }
        val ys = FloatArray(n) { y0 + (y1 - y0) * it / (n - 1) }
        return VStroke(0, preset = BrushLibrary.defaultBrush.copy(size = 4f, scatter = 0f), color = black, seed = 3L, stylus = false, points = PackedPoints(xs, ys, FloatArray(n) { 1f }))
    }

    private fun EditorController.tap(x: Float, y: Float) {
        pointerDown(ToolPoint(x, y))
        pointerUp(ToolPoint(x, y))
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
    fun aTapBesideLineArtFillsTheEnclosedAreaAndATapOnTheLineRecolorsIt() {
        val c = setup()
        val layer = c.doc.layers[0]
        // A small square of 4 px strokes (an eye, a gap in the hair...).
        val l = 100f; val t = 100f; val r = 200f; val b = 200f
        c.vectors.addObjects(layer, listOf(stroke(l, t, r, t), stroke(r, t, r, b), stroke(r, b, l, b), stroke(l, b, l, t)), "Add")
        val dp = c.viewTransform.screenToDocLength(c.viewTransform.dp(1f))
        // 6 dp beside the left line's paint: the area is filled, the line keeps its color.
        val x = l + 2f + 6f * dp
        assertTrue(x < (l + r) / 2f)
        c.tap(x, 150f)
        awaitFill(c)
        val after = layer.vector!!
        assertEquals(5, after.objects.size)
        val fill = after.objects.first() as VPath
        assertEquals(VFillRule.EVENODD, fill.fillRule)
        assertEquals(VPaint.Solid(red), fill.fill)
        assertTrue(after.objects.drop(1).all { (it as VStroke).color == black })
        assertEquals(VectorFill.FILL_AREA_LABEL, c.undoManager.undoLabel)
        // Right beside the paint of the top line (within the tolerance): the line takes the color.
        c.color = 0xFF2040E0.toInt()
        c.tap(150f, t + 2f + 0.5f * VectorFill.TAP_TOLERANCE_DP * dp)
        val top = layer.vector!!.objects[1] as VStroke
        assertEquals(0xFF2040E0.toInt(), top.color)
        assertEquals(VectorFill.FILL_OBJECT_LABEL, c.undoManager.undoLabel)
        assertTrue(layer.isVectorLayer)
    }
}
