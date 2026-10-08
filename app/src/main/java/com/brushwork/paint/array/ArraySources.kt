package com.brushwork.paint.array

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.CanvasOps
import com.brushwork.paint.model.ArrayPixels
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeOutlines
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.VectorRenderer
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlin.math.max

/**
 * v1.7 (item 3, area E): what the array operations share: the kind of an array's source, how a
 * text or shape source is drawn (exactly as its own tool draws it, so `ArrayDraw.drawWithArray`
 * repeats the same pixels per copy), the memory rule (§3.3 b: an array's source pixels count
 * toward `effectiveLayerCount`), the "Array N" names, and the pixel helpers.
 */
internal object ArraySources {
    /** The source of an array, by where it lives (§3.3 b). */
    enum class Kind { PIXELS, VECTOR, TEXT, SHAPE }

    /** The kind of [d]'s array source, or null without an array (or without a usable source). */
    fun kindOf(d: LayerData): Kind? {
        val a = d.array ?: return null
        return when {
            a.pixels != null -> Kind.PIXELS
            d.vector != null -> Kind.VECTOR
            d.text != null -> Kind.TEXT
            d.shape != null -> Kind.SHAPE
            else -> null
        }
    }

    /**
     * Draws a text or shape source ALONE at identity (document px), the way its tool draws it:
     * the Text tool's `TextRenderer.drawItem` of the prepared item, the Shape tool's
     * `VectorRenderer.draw` of `ShapeOutlines.paintSpec` (and a brush-stroked outline replayed
     * by the vector renderer). Null when [d] holds neither, or it can't be read.
     */
    fun sourceDraw(d: LayerData, colorMode: ColorMode, docW: Int, docH: Int): ((Canvas) -> Unit)? {
        d.text?.let { t ->
            val item = TextCodec.decode(t) ?: return null
            val prep = TextRenderer.prepare(item)
            return { cv -> TextRenderer.drawItem(cv, item, prep, null) }
        }
        d.shape?.let { s ->
            val o = ShapeCodec.decode(s) ?: return null
            return shapeDraw(o, colorMode, docW, docH)
        }
        return null
    }

    /** [sourceDraw] of the shape [o]. */
    fun shapeDraw(o: ShapeObject, colorMode: ColorMode, docW: Int, docH: Int): (Canvas) -> Unit {
        val brush = o.paintsWithBrush
        val spec = ShapeOutlines.paintSpec(o, brush)
        val renderer = VectorRenderer()
        // The brush outline alone (no fill): the vector renderer's replay of the shape's brush.
        val outline = if (brush) VectorContent(objects = listOf(VShape(0L, shape = o.copy(style = ShapeStyle.STROKE)))) else null
        val region = Rect().also { r -> VectorOps.bounds(VShape(0L, shape = o)).roundOut(r); r.inset(-2, -2) }
        val document = Rect(0, 0, docW, docH)
        return { cv ->
            spec?.let { renderer.draw(cv, it, false, colorMode) }
            if (outline != null && !region.isEmpty) {
                val tips = TipCache(8L shl 20)
                try {
                    VectorLayerRenderer.render(cv, outline, region, tips = tips, document = document)
                } finally {
                    tips.clear()
                }
            }
        }
    }

    /** [r] rounded out to whole pixels (a new Rect; empty for an empty or non-finite [r]). */
    fun rectOf(r: RectF?): Rect {
        if (r == null || r.isEmpty || !r.left.isFinite() || !r.top.isFinite() || !r.right.isFinite() || !r.bottom.isFinite()) return Rect()
        return Rect().also { r.roundOut(it) }
    }

    /** The source's own rectangle in [d] (document px, grown by 2 px), empty when unknown. */
    fun sourceRect(d: LayerData): Rect = rectOf(ArrayDraw.sourceBounds(d)).also { if (!it.isEmpty) it.inset(-2, -2) }

    /**
     * True when the document still has room after adding [newLayers] pixel layers and
     * [addBytes] of array source pixels while [freedBytes] are let go (§3.3 b, the rule of
     * `EditorController.effectiveLayerCount`): `pixelLayerCount + newLayers + ⌈array bytes /
     * layerBytes⌉ ≤ maxLayers`, which is `canAddLayer` for one new layer without pixels.
     */
    fun hasRoom(c: EditorController, newLayers: Int, addBytes: Long, freedBytes: Long = 0L): Boolean {
        val doc = c.doc
        val per = max(1L, doc.layerBytes)
        val bytes = max(0L, doc.layers.sumOf { it.array?.pixels?.bytes ?: 0L } - freedBytes + addBytes)
        val count = doc.pixelLayerCount.toLong() + newLayers + (bytes + per - 1) / per
        return count <= c.maxLayers
    }

    /** "Array N" with the smallest N ≥ 1 that no layer uses yet. */
    fun newName(c: EditorController): String {
        val names = c.doc.layers.mapTo(HashSet()) { it.name }
        var n = 1
        while ("Array $n" in names) n++
        return "Array $n"
    }

    /**
     * The pixels of [src] × the coverage of [sel] (as the selection bar's Cut takes them),
     * cropped to the non-transparent ones, as array source pixels; null when the selection
     * covers no painted pixel.
     */
    fun selectedPixels(src: Bitmap, sel: Selection): ArrayPixels? {
        val b = Rect(sel.bounds)
        if (!b.intersect(0, 0, src.width, src.height)) return null
        val crop = BitmapUtils.createLayerBitmap(b.width(), b.height())
        Canvas(crop).apply {
            translate(-b.left.toFloat(), -b.top.toFloat())
            // Skia draws an ALPHA_8 bitmap as coverage: the source, as a shader, through the mask.
            drawBitmap(sel.mask, 0f, 0f, Paint().apply { shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) })
        }
        val o = CanvasOps.opaqueBounds(crop)
        if (o == null) { crop.recycle(); return null }
        if (o.left == 0 && o.top == 0 && o.width() == crop.width && o.height() == crop.height) return ArrayPixels(crop, b.left, b.top)
        val out = try { cropped(crop, o) } finally { crop.recycle() }
        return ArrayPixels(out, b.left + o.left, b.top + o.top)
    }

    /** The non-transparent pixels of [bmp] (a layer's content) as array source pixels; null when it has none. */
    fun layerPixels(bmp: Bitmap): ArrayPixels? {
        val o = CanvasOps.opaqueBounds(bmp) ?: return null
        return ArrayPixels(cropped(bmp, o), o.left, o.top)
    }

    /** A new mutable ARGB_8888 copy of [r] of [bmp]. */
    fun cropped(bmp: Bitmap, r: Rect): Bitmap {
        val out = BitmapUtils.createLayerBitmap(r.width(), r.height())
        Canvas(out).drawBitmap(bmp, -r.left.toFloat(), -r.top.toFloat(), null)
        return out
    }

    /** True for a text layer that is a frame of linked text frames (refused, §3.3 a). */
    fun isLinkedText(layer: Layer): Boolean = layer.textData?.let { TextCodec.decode(it)?.threaded } == true
}
