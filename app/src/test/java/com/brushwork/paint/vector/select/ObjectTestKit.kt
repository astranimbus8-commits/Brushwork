package com.brushwork.paint.vector.select

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
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.TransformTool
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
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Shared set-up of the A2 (object selection / lift / Object bar) Robolectric tests: a document
 * with a Background and an active, empty vector layer "Vector 1", an identity view, snapping off;
 * object builders; reference renders through [VectorLayerRenderer] (as the cache is rendered).
 */
internal class ObjectTestKit(val w: Int = 512, val h: Int = 384) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun close() = scope.cancel()

    fun controller(): EditorController {
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h))
        doc.layers += Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(w, h)).also { it.vector = VectorContent.EMPTY }
        doc.activeLayerIndex = 1
        return EditorController(app, doc, scope, settings).also {
            it.viewTransform.set(Matrix())
            it.snapping.enabled = false
        }
    }

    fun pixels(b: Bitmap): IntArray = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }

    /** A fresh render of [content] over the whole document, as the cache is rendered. */
    fun render(content: VectorContent): IntArray {
        val b = BitmapUtils.createLayerBitmap(w, h)
        val doc = Rect(0, 0, w, h)
        VectorLayerRenderer.render(Canvas(b), content, doc, tips = TipCache(), document = doc)
        return pixels(b).also { b.recycle() }
    }

    fun stroke(x0: Float, y0: Float, x1: Float, y1: Float, color: Int = 0xFF2050C0.toInt(), seed: Long = 7L, size: Float = 10f): VStroke {
        val n = 12
        val xs = FloatArray(n) { x0 + (x1 - x0) * it / (n - 1) }
        val ys = FloatArray(n) { y0 + (y1 - y0) * it / (n - 1) + if (it % 2 == 0) 0f else 3f }
        val ps = FloatArray(n) { 1f }
        return VStroke(0, preset = BrushLibrary.defaultBrush.copy(size = size), color = color, seed = seed, stylus = false, points = PackedPoints(xs, ys, ps))
    }

    fun box(l: Float, t: Float, r: Float, b: Float, color: Int = 0xFFE04020.toInt(), line: Int = 0xFF101010.toInt()) = VPath(
        0,
        subpaths = listOf(VSubpath(listOf(VAnchor(l, t, true), VAnchor(r, t, true), VAnchor(r, b, true), VAnchor(l, b, true)), closed = true)),
        fill = VPaint.Solid(color),
        stroke = VStrokeStyle(color = line, width = 3f),
    )

    fun ellipse(cx: Float, cy: Float, w: Float = 60f, h: Float = 40f) = VShape(
        0,
        shape = ShapeObject(
            ShapeType.ELLIPSE, cx = cx, cy = cy, w = w, h = h, style = ShapeStyle.STROKE_FILL, strokeWidth = 5f,
            strokeColor = 0xFF006030.toInt(), fillColor = 0xFF60D090.toInt(),
        ),
    )

    fun rectangle(cx: Float, cy: Float, w: Float, h: Float) = VShape(
        0,
        shape = ShapeObject(
            ShapeType.RECTANGLE, cx = cx, cy = cy, w = w, h = h, style = ShapeStyle.STROKE_FILL, strokeWidth = 4f,
            strokeColor = 0xFF202080.toInt(), fillColor = 0xFFF0C040.toInt(),
        ),
    )

    /** A hard-edged rectangular pixel selection. */
    fun rectSelection(l: Int, t: Int, r: Int, b: Int): Selection {
        val bytes = ByteArray(w * h)
        for (y in t until b) for (x in l until r) bytes[y * w + x] = 0xFF.toByte()
        return Selection.fromBytes(bytes, w, h)
    }

    /** Runs the main looper (and lets background work finish) until [done] or ~5 s. */
    fun idleUntil(done: () -> Boolean): Boolean {
        repeat(1000) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return true
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return done()
    }

    fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** The Transform tool, current, with whatever its activation lifted. */
    fun transform(c: EditorController): TransformTool {
        c.selectTool(ToolId.TRANSFORM)
        idle()
        return c.tools.getValue(ToolId.TRANSFORM) as TransformTool
    }
}

internal val EditorController.vec: Layer get() = doc.layers[1]
