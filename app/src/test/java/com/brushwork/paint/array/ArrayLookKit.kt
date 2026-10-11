package com.brushwork.paint.array

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.model.ArrayLayout
import com.brushwork.paint.model.ArrayLook
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerArray
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.model.Selection
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStop
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.geom.ObjectIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.abs

/**
 * v1.8 (area B, item 2): shared fixtures of the Array look tests: layers of every source kind on
 * a small controller, the looks they use, and the v1.7 draw, pixel and vector algorithms written
 * out (ArrayLayout's matrices, no look), the reference I19 is checked against: there are no array
 * golden files, so "bit-identical to v1.7" means bit-identical to these.
 */
internal object ArrayLookKit {
    const val GRAY = 0xFF808080.toInt()
    const val RED = 0xFFFF0000.toInt()
    const val BLUE = 0xFF0000FF.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()

    /** A tint from red (first copy) to blue (last copy). */
    val RED_TO_BLUE: List<VStop> = listOf(VStop(0f, RED), VStop(1f, BLUE))

    /** Copies fade from opaque to half, tinted red to blue at full strength. */
    val FADE_AND_TINT = ArrayLook(opacityStart = 1f, opacityEnd = 0.5f, tint = RED_TO_BLUE, tintStrength = 1f)

    fun controller(app: Context, w: Int = 400, h: Int = 300): EditorController =
        Smoke.controller(app, Smoke.document(w, h)).also { it.viewTransform.set(Matrix()) }

    /** Lets background renders (vector caches, array renders) land. */
    fun settle(c: EditorController) {
        Smoke.pumpUntil { !c.vectors.isRendering && c.doc.layers.none { c.arrayRenders.isPendingOn(it) } }
        Smoke.pump(40)
    }

    fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    fun rectSelection(c: EditorController, l: Float, t: Float, r: Float, b: Float): Selection =
        Selection.fromPath(Path().apply { addRect(l, t, r, b, Path.Direction.CW) }, c.doc.width, c.doc.height, antiAlias = false)

    /** A raster array "Array 1" from a [color] square [l, t, l + size, t + size] on layer 1 (count 3, relative X 100 %). */
    fun pixelArray(c: EditorController, l: Int = 20, t: Int = 30, size: Int = 40, color: Int = GRAY): Layer {
        val src = c.doc.layers[1]
        Canvas(src.bitmap).drawRect(l.toFloat(), t.toFloat(), (l + size).toFloat(), (t + size).toFloat(), Paint().apply { this.color = color })
        c.selectLayer(src)
        c.setSelection(rectSelection(c, l.toFloat(), t.toFloat(), (l + size).toFloat(), (t + size).toFloat()), recordUndo = false)
        assertTrue(c.arrayFromSelection())
        settle(c)
        return c.activeLayer
    }

    fun textDraw(item: TextItem): (Canvas) -> Unit = { cv -> TextRenderer.drawItem(cv, item, TextRenderer.prepare(item), null) }

    /** A text layer showing [item], arrayed whole (count 3). */
    fun textArray(c: EditorController, item: TextItem): Layer {
        val layer = c.addLayerWithContent("Text", "Add text", textData = TextCodec.encode(item), draw = textDraw(item))!!
        assertTrue(c.arrayWholeLayer(layer))
        settle(c)
        return layer
    }

    /** A shape layer showing [o] (drawn as the Shape tool draws it), arrayed whole (count 3). */
    fun shapeArray(c: EditorController, o: ShapeObject): Layer {
        val draw = ArraySources.shapeDraw(o, c.doc.colorMode, c.doc.width, c.doc.height)
        val layer = c.addLayerWithContent("Shape", "Add shape", shapeData = ShapeCodec.encode(o), draw = draw)!!
        assertTrue(c.arrayWholeLayer(layer))
        settle(c)
        return layer
    }

    /** A vector layer holding [objects], arrayed whole (count 3). */
    fun vectorArray(c: EditorController, objects: List<VObject>): Layer {
        val v = c.addVectorLayer()!!
        c.vectors.update(v, VectorContent.EMPTY.plus(objects).first, "Add")
        settle(c)
        assertTrue(c.arrayWholeLayer(v))
        settle(c)
        return v
    }

    fun box(l: Float, t: Float, r: Float, b: Float, fill: Int = GRAY, stroke: Int? = null, id: Long = 0) = VPath(
        id,
        subpaths = listOf(VSubpath(listOf(VAnchor(l, t, true), VAnchor(r, t, true), VAnchor(r, b, true), VAnchor(l, b, true)), closed = true)),
        fill = VPaint.Solid(fill),
        stroke = stroke?.let { VStrokeStyle(color = it, width = 4f) },
    )

    /** [layer]'s array edited to [spec] (one step), rendered. */
    fun edit(c: EditorController, layer: Layer, spec: ArraySpec) {
        assertTrue(ArrayOps.edit(c, layer, spec))
        settle(c)
    }

    // ------------------------------------------------------------------ the v1.7 reference

    /** v1.7's `ArrayDraw.drawWithArray`: the source under each ArrayLayout matrix k = N − 1 .. 1, then at identity. */
    fun v17Draw(canvas: Canvas, array: LayerArray?, source: RectF, drawSource: (Canvas) -> Unit) {
        val ms = if (array == null) null else v17Matrices(array, source)
        if (ms == null || ms.size <= 1) {
            drawSource(canvas)
            return
        }
        val m = Matrix()
        for (k in ms.size - 1 downTo 1) {
            val v = ms[k]
            if (v.any { !it.isFinite() }) continue
            m.setValues(v)
            canvas.save()
            canvas.concat(m)
            drawSource(canvas)
            canvas.restore()
        }
        drawSource(canvas)
    }

    /** v1.7's `ArrayDraw.drawPixels`. */
    fun v17Pixels(canvas: Canvas, array: LayerArray) {
        val px = array.pixels ?: return
        val bmp = px.bitmap
        val src = RectF(px.left.toFloat(), px.top.toFloat(), (px.left + bmp.width).toFloat(), (px.top + bmp.height).toFloat())
        val ms = v17Matrices(array, src)
        val bilinear = Paint(Paint.FILTER_BITMAP_FLAG)
        val m = Matrix()
        for (k in ms.size - 1 downTo 1) {
            val v = ms[k]
            if (v.any { !it.isFinite() }) continue
            m.setValues(v)
            canvas.save()
            canvas.concat(m)
            canvas.drawBitmap(bmp, src.left, src.top, bilinear)
            canvas.restore()
        }
        canvas.drawBitmap(bmp, src.left, src.top, null)
    }

    /** v1.7's `ArrayDraw.effectiveVector` objects (no memo): copies k = N − 1 .. 1, then the source objects. */
    fun v17Vector(data: LayerData): List<VObject> {
        val v = data.vector!!
        val a = data.array ?: return v.objects
        val ms = v17Matrices(a, ObjectIndex.of(v).unionBounds())
        val copies = minOf(ms.size, maxOf(1, ArraySpec.MAX_INSTANCES / v.objects.size))
        if (copies <= 1) return v.objects
        val out = ArrayList<VObject>()
        for (k in copies - 1 downTo 1) {
            if (ms[k].any { !it.isFinite() }) continue
            for (o in v.objects) out += VectorOps.transformed(o, ms[k]).withId(o.id + (k.toLong() shl ArrayDraw.COPY_ID_SHIFT))
        }
        out.addAll(v.objects)
        return out
    }

    private fun v17Matrices(array: LayerArray, source: RectF): List<FloatArray> {
        val ok = source.left.isFinite() && source.top.isFinite() && source.right.isFinite() && source.bottom.isFinite() && !source.isEmpty
        if (array.spec.editingSource || !ok) return listOf(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f))
        return ArrayLayout.matrices(array.spec, source)
    }

    // ------------------------------------------------------------------ colors

    fun a(c: Int) = (c ushr 24) and 0xFF
    fun r(c: Int) = (c shr 16) and 0xFF
    fun g(c: Int) = (c shr 8) and 0xFF
    fun b(c: Int) = c and 0xFF

    /** [actual] is [expected] within [tol] per channel (getPixel unpremultiplies: a half-transparent channel may be off by one or two). */
    fun assertColor(message: String, expected: Int, actual: Int, tol: Int = 2) {
        val ok = abs(a(expected) - a(actual)) <= tol && abs(r(expected) - r(actual)) <= tol &&
            abs(g(expected) - g(actual)) <= tol && abs(b(expected) - b(actual)) <= tol
        if (!ok) assertEquals(message, Integer.toHexString(expected), Integer.toHexString(actual))
    }

    /** [argb] with alpha [alpha] (0..255). */
    fun withAlpha(argb: Int, alpha: Int): Int = (argb and 0x00FFFFFF) or (alpha shl 24)
}
