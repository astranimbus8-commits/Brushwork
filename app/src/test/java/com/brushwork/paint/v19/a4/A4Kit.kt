package com.brushwork.paint.v19.a4

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Looper
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.fail
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import kotlin.math.abs

/**
 * Shared set-up of A4's merge and duplicate tests (R19, R14): a [w] x [h] document with a white
 * raster "Background", identity view, snapping off; text, shape and vector layers built the way
 * the tools build them (their pixels the rendering of their data); the flattened picture on white.
 */
internal class A4Kit(val w: Int = 240, val h: Int = 180) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun close() = scope.cancel()

    fun controller(): EditorController {
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h).also { it.eraseColor(WHITE) })
        doc.activeLayerIndex = 0
        return EditorController(app, doc, scope, settings).also {
            it.viewTransform.set(Matrix())
            it.snapping.enabled = false
        }
    }

    /** A text at ([cx], [cy]) in [color], 32 px. */
    fun text(s: String, cx: Float, cy: Float, color: Int = 0xFF203080.toInt()) =
        TextItem(s, spec = TextSpec(sizePx = 32f, color = color), cx = cx, cy = cy)

    /** A text layer of [items] (bottom first) above the active layer, painted as the Text tool paints it. */
    fun textLayer(c: EditorController, vararg items: TextItem, name: String = "Text"): Layer =
        c.addLayerWithContent(name, "Add text", textData = TextCodec.encodeAll(items.toList())) { cv ->
            for (item in items) TextRenderer.drawItem(cv, item, TextRenderer.prepare(item), null)
        }!!

    /** A filled and outlined rectangle shape. */
    fun rect(cx: Float, cy: Float, sw: Float, sh: Float, fill: Int = 0xFFF0A030.toInt()) = ShapeObject(
        ShapeType.RECTANGLE, cx = cx, cy = cy, w = sw, h = sh, style = ShapeStyle.STROKE_FILL, strokeWidth = 4f,
        strokeColor = 0xFF202080.toInt(), fillColor = fill,
    )

    /** A legacy shape layer of [o] above the active layer, painted as its vector form renders. */
    fun shapeLayer(c: EditorController, o: ShapeObject, name: String = "Shape"): Layer =
        c.addLayerWithContent(name, "Add shape", shapeData = ShapeCodec.encode(o)) { cv ->
            draw(cv, VectorContent(objects = listOf(VShape(0L, shape = o))))
        }!!

    /** A closed filled box path. */
    fun box(l: Float, t: Float, r: Float, b: Float, color: Int = 0xFFE04020.toInt()) = VPath(
        0,
        subpaths = listOf(VSubpath(listOf(VAnchor(l, t, true), VAnchor(r, t, true), VAnchor(r, b, true), VAnchor(l, b, true)), closed = true)),
        fill = VPaint.Solid(color),
        stroke = VStrokeStyle(color = 0xFF101010.toInt(), width = 3f),
    )

    /** A vector layer of [objects] above the active layer, its cache rendered. */
    fun vectorLayer(c: EditorController, objects: List<VObject>, name: String? = null): Layer {
        val layer = c.addVectorLayer(name)!!
        if (objects.isNotEmpty()) c.vectors.addObjects(layer, objects, "Add")
        settle(c)
        return layer
    }

    fun settle(c: EditorController) {
        c.vectors.flushPending()
        idleUntil { !c.vectors.isRendering }
    }

    fun draw(cv: Canvas, content: VectorContent) {
        val doc = Rect(0, 0, w, h)
        VectorLayerRenderer.render(cv, content, doc, tips = TipCache(), document = doc)
    }

    fun pixels(b: Bitmap): IntArray = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }

    /** The document flattened on white. */
    fun picture(c: EditorController): IntArray {
        val b = Compositor(c.doc) { null }.renderFlattened(WHITE)
        return pixels(b).also { b.recycle() }
    }

    /** A fresh rendering of [layer]'s texts (bottom first). */
    fun renderTexts(layer: Layer): IntArray {
        val b = BitmapUtils.createLayerBitmap(w, h)
        val cv = Canvas(b)
        for (item in TextCodec.decodeAll(layer.textData)) TextRenderer.drawItem(cv, item, TextRenderer.prepare(item), null)
        return pixels(b).also { b.recycle() }
    }

    /** A fresh rendering of [content]. */
    fun render(content: VectorContent): IntArray {
        val b = BitmapUtils.createLayerBitmap(w, h)
        draw(Canvas(b), content)
        return pixels(b).also { b.recycle() }
    }

    /** Runs the main looper (and lets background work finish) until [done] or about 5 s. */
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

    companion object {
        const val WHITE = 0xFFFFFFFF.toInt()

        /** Fails with [what] when a channel of [a] and [b] differs by more than [tol] levels anywhere. */
        fun assertClose(what: String, a: IntArray, b: IntArray, tol: Int = 1) {
            if (a.size != b.size) fail("$what: sizes ${a.size} and ${b.size}")
            var worst = 0
            var at = -1
            for (i in a.indices) {
                if (a[i] == b[i]) continue
                for (s in intArrayOf(0, 8, 16, 24)) {
                    val d = abs(((a[i] ushr s) and 0xFF) - ((b[i] ushr s) and 0xFF))
                    if (d > worst) { worst = d; at = i }
                }
            }
            if (worst > tol) fail("$what: off by $worst levels at pixel $at (${Integer.toHexString(a[at])} against ${Integer.toHexString(b[at])})")
        }
    }
}
