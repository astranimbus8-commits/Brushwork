package com.brushwork.paint.exchange

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.masks.LinearMask
import com.brushwork.paint.masks.MaskSpec
import com.brushwork.paint.masks.MaskSpecs
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeOutlines
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.tools.vector.VectorRenderer
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.render.VectorLayerRenderer
import org.robolectric.RuntimeEnvironment

/** Documents with every kind of layer for the exchange tests (v1.5 A8). */
internal object ExchangeFixtures {
    val app get() = RuntimeEnvironment.getApplication()

    fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** A vector layer's cache: the content rendered like the editor does. */
    fun render(content: VectorContent, w: Int, h: Int): Bitmap {
        val b = BitmapUtils.createLayerBitmap(w, h)
        VectorLayerRenderer.render(Canvas(b), content, Rect(0, 0, w, h), tips = TipCache(), document = Rect(0, 0, w, h))
        return b
    }

    fun stroke(x0: Float, y0: Float, x1: Float, y1: Float, preset: com.brushwork.paint.brush.BrushPreset = BrushLibrary.defaultBrush.copy(size = 9f), color: Int = 0xFF2050C0.toInt()): VStroke {
        val n = 14
        val xs = FloatArray(n) { x0 + (x1 - x0) * it / (n - 1) }
        val ys = FloatArray(n) { y0 + (y1 - y0) * it / (n - 1) + if (it % 2 == 0) 0f else 4f }
        return VStroke(0, preset = preset, color = color, seed = 11L, stylus = false, points = PackedPoints(xs, ys, FloatArray(n) { 1f }))
    }

    fun box(l: Float, t: Float, r: Float, b: Float, fill: Int = 0xFFE04020.toInt()) = VPath(
        0,
        subpaths = listOf(VSubpath(listOf(VAnchor(l, t, true), VAnchor(r, t, true), VAnchor(r, b, true), VAnchor(l, b, true)), closed = true)),
        fill = VPaint.Solid(fill),
        stroke = VStrokeStyle(color = 0xFF101010.toInt(), width = 3f),
    )

    fun vectorContent(): VectorContent = VectorContent.EMPTY.plus(
        listOf(
            box(20f, 20f, 90f, 70f),
            stroke(30f, 100f, 260f, 120f),
            stroke(40f, 150f, 250f, 140f, preset = BrushLibrary.all.first { it.id == "pencil" }.copy(size = 10f), color = 0xFF206030.toInt()),
            stroke(50f, 170f, 240f, 175f, preset = BrushLibrary.all.first { it.id == "chalk" }.copy(size = 12f), color = 0xFF603020.toInt()),
            VShape(0, shape = ShapeObject(ShapeType.ELLIPSE, cx = 200f, cy = 60f, w = 70f, h = 40f, style = ShapeStyle.STROKE_FILL, strokeWidth = 4f, strokeColor = 0xFF003366.toInt(), fillColor = 0xFF66CCFF.toInt())),
        ),
    ).first

    /**
     * A 300 x 200 document: Background (white), Photo (raster with soft alpha and a mask,
     * multiply), Vector, Text, Shape, Hidden (hidden raster), Base + Clipped (a clipping group),
     * and an adjustment layer at the bottom when [adjustment].
     */
    fun document(adjustment: Boolean = false, colorMode: ColorMode = ColorMode.RGB): Document {
        val w = 300
        val h = 200
        val doc = Document("x", "Exchange test", w, h, dpi = 300f)
        doc.colorMode = colorMode
        fun layer(name: String) = Layer(doc.newLayerId(), name, BitmapUtils.createLayerBitmap(w, h)).also { doc.layers += it }

        layer("Background").bitmap.eraseColor(0xFFFFFFFF.toInt())
        if (adjustment) {
            layer("Invert 1").apply {
                this.adjustment = AdjustmentSpec(filterId = "adjust.invert")
                val spec = MaskSpec(components = listOf(LinearMask(1, x0 = 0f, y0 = 0f, x1 = 300f, y1 = 0f)), nextId = 2)
                mask = BitmapUtils.createMaskBitmap(w, h, 0xFF000000.toInt()).also { m ->
                    val px = IntArray(w * h)
                    MaskSpecs.render(spec, w, h, Rect(0, 0, w, h), px, w)
                    m.setPixels(px, 0, w, 0, 0, w, h)
                }
                maskSpec = spec
            }
        }
        layer("Photo").apply {
            val c = Canvas(bitmap)
            val p = Paint()
            for (i in 0 until 10) {
                p.color = (((i + 1) * 25) shl 24) or (0x00FF0000 shr (i % 3) * 8)
                c.drawRect(40f + i * 10, 30f, 50f + i * 10, 120f, p)
            }
            mask = BitmapUtils.createMaskBitmap(w, h, -1).also { m -> Canvas(m).drawRect(60f, 60f, 100f, 90f, Paint().apply { color = 0xFF000000.toInt() }) }
            blendMode = LayerBlendMode.MULTIPLY
            opacity = 0.7f
        }
        layer("Vector").apply {
            val content = vectorContent()
            vector = content
            Canvas(bitmap).drawBitmap(render(content, w, h), 0f, 0f, null)
        }
        layer("Text").apply {
            val item = TextItem("Hello", TextSpec(sizePx = 40f, color = 0xFF883300.toInt()), cx = 150f, cy = 100f)
            TextRenderer.drawItem(Canvas(bitmap), item, TextRenderer.prepare(item), null)
            textData = TextCodec.encode(item)
        }
        layer("Shape").apply {
            val o = ShapeObject(ShapeType.STAR, cx = 240f, cy = 150f, w = 60f, h = 60f, style = ShapeStyle.STROKE_FILL, strokeWidth = 3f, strokeColor = 0xFF000000.toInt(), fillColor = 0xFFFFCC00.toInt())
            VectorRenderer().draw(Canvas(bitmap), ShapeOutlines.paintSpec(o, false)!!, false, ColorMode.RGB)
            shapeData = ShapeCodec.encode(o)
            locked = true
        }
        layer("Hidden").apply {
            Canvas(bitmap).drawCircle(50f, 160f, 20f, Paint().apply { color = 0xFF00AA00.toInt() })
            visible = false
        }
        layer("Base").apply {
            Canvas(bitmap).drawRect(150f, 20f, 280f, 60f, Paint().apply { color = 0xFF0000FF.toInt() })
            opacity = 0.9f
        }
        layer("Clipped").apply {
            Canvas(bitmap).drawRect(100f, 30f, 200f, 50f, Paint().apply { color = 0xFFFF00FF.toInt() })
            clipping = true
        }
        doc.activeLayerIndex = doc.layers.indexOfFirst { it.name == "Vector" }
        return doc
    }

    fun controller(doc: Document): EditorController = Smoke.controller(app, doc)
}
