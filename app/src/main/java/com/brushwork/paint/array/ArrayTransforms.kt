package com.brushwork.paint.array

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.CanvasOps
import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.model.ArrayPixels
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.tools.text.TextTransforms
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.vector.LayerDataTransforms
import com.brushwork.paint.vector.geom.ShapeAffine
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * v1.7 (item 11, §3.11; area E): the Transform tool's data lift of an arrayed layer. Move,
 * rotate, scale and flip map the array's spec AND its source, so the layer stays a live array:
 *
 * - a vector source: its objects (`LayerDataTransforms.mapped`: shapes stay shapes, strokes
 *   scale their width by sqrt|det|);
 * - a shape source: `ShapeAffine` (exact for any affine map; a skewed regular shape becomes a
 *   point shape, still a shape);
 * - a text source: `TextTransforms` (area D; null on a branch without it, which refuses);
 * - raster source pixels: resampled once (bilinear), or moved without resampling by a whole-pixel
 *   move.
 *
 * The spec follows the copies, not only the source ([mappedSpec]): unless the map only moves, a
 * CIRCLE centre or TRANSFORM pivot that follows the source is fixed first (as
 * `LayerDataTransforms.mappedArray` does for canvas operations), and a LINE's relative offset
 * becomes a constant one under maps that do not keep the axes (a turn by 30°), where a fraction
 * of the source's new bounding box would no longer point where the old copies map to. Exact for
 * similarities (moves, flips, turns, uniform scales) of a source whose bounds map onto the new
 * source's bounds. Pure: the caller (the Transform tool) records the step and re-renders the
 * cache, e.g. with [cacheDraw].
 */
object ArrayTransforms {
    /** Source pixels larger than this after a scale are refused (the Transform tool then refuses the array). */
    private const val MAX_SOURCE_PIXELS = 16L shl 20

    /**
     * [layer]'s data with its array (spec and source) mapped by [m] (a row-major 3 × 3 affine
     * matrix in document px, in `android.graphics.Matrix` value order), or null when it can't be:
     * no array, a projective or singular map, a text the Text tool's transforms can't map, a
     * raster source in "Edit source pixels" mode (the layer's pixels are the source being
     * painted: they transform as pixels, without baking, I14), or source pixels too large.
     */
    fun mapped(layer: Layer, m: FloatArray): LayerData? {
        if (!isAffine(m)) return null
        val d = layer.dataSnapshot()
        val a = d.array ?: return null
        val source = ArrayDraw.sourceBounds(d) ?: return null
        return when (ArraySources.kindOf(d)) {
            ArraySources.Kind.VECTOR -> {
                val v = d.vector ?: return null
                d.copy(vector = LayerDataTransforms.mapped(v, m), array = a.copy(spec = mappedSpec(a.spec, source, m)))
            }
            ArraySources.Kind.SHAPE -> {
                val o = d.shape?.let { ShapeCodec.decode(it) } ?: return null
                val mo = ShapeAffine.mapped(o, m) ?: return null
                d.copy(shape = ShapeCodec.encode(mo), array = a.copy(spec = mappedSpec(a.spec, source, m)))
            }
            ArraySources.Kind.TEXT -> {
                val t = d.text ?: return null
                if (!TextTransforms.canMap(m)) return null
                val mt = TextTransforms.mapped(t, m) ?: return null
                d.copy(text = mt, array = a.copy(spec = mappedSpec(a.spec, source, m)))
            }
            ArraySources.Kind.PIXELS -> {
                if (a.spec.editingSource) return null
                val px = mappedPixels(a.pixels ?: return null, m) ?: return null
                d.copy(array = a.copy(spec = mappedSpec(a.spec, source, m), pixels = px))
            }
            null -> null
        }
    }

    /**
     * What the cache of [data] (an arrayed layer's data, e.g. a [mapped] result) is drawn with
     * through `EditorController.updateLayerData`, per its contract: a raster source's
     * `ArrayDraw.drawPixels`, a text or shape source alone (the controller repeats it per copy),
     * a vector source's expanded objects within whole tiles of the document. Null without a
     * source.
     */
    fun cacheDraw(data: LayerData, colorMode: ColorMode, docW: Int, docH: Int): ((Canvas) -> Unit)? {
        val a = data.array ?: return null
        val document = Rect(0, 0, docW, docH)
        return when (ArraySources.kindOf(data)) {
            ArraySources.Kind.PIXELS -> fun(cv: Canvas) { ArrayDraw.drawPixels(cv, a) }
            ArraySources.Kind.TEXT, ArraySources.Kind.SHAPE -> ArraySources.sourceDraw(data, colorMode, docW, docH)
            ArraySources.Kind.VECTOR -> {
                val view = ArrayDraw.effectiveVector(data) ?: return null
                fun(cv: Canvas) { ArrayOps.renderTiles(cv, view, document, document) }
            }
            null -> null
        }
    }

    /**
     * [spec] of a source whose current bounds are [source], under [m]: where the copies of the
     * mapped source go is where the old copies map to (see the class docs).
     */
    internal fun mappedSpec(spec: ArraySpec, source: RectF, m: FloatArray): ArraySpec {
        if (!isAffine(m)) return spec
        var s = spec
        if (!moveOnly(m)) {
            val circle = s.mode == ArrayMode.CIRCLE && (s.centerX == null || s.centerY == null)
            val turn = s.mode == ArrayMode.TRANSFORM && (s.pivotX == null || s.pivotY == null)
            if ((circle || turn) && !source.isEmpty) {
                val r = s.resolved(source)
                s = if (circle) s.copy(centerX = r.centerX, centerY = r.centerY) else s.copy(pivotX = r.pivotX, pivotY = r.pivotY)
            }
            if (s.mode == ArrayMode.LINE && !keepsAxes(m) && (s.relativeX != 0f || s.relativeY != 0f)) {
                s = s.copy(
                    relativeX = 0f, relativeY = 0f,
                    constantX = s.constantX + s.relativeX * source.width(),
                    constantY = s.constantY + s.relativeY * source.height(),
                )
            }
        }
        return s.mapped(m).sanitized()
    }

    /** [px] under [m]: moved by a whole-pixel move (the same pixels), else resampled once and cropped; null when too large. */
    private fun mappedPixels(px: ArrayPixels, m: FloatArray): ArrayPixels? {
        val bmp = px.bitmap
        if (bmp.isRecycled) return null
        if (moveOnly(m)) {
            val dx = m[2] / m[8]
            val dy = m[5] / m[8]
            if (dx == dx.roundToInt().toFloat() && dy == dy.roundToInt().toFloat()) {
                // Immutable once published: the moved source shares them.
                return ArrayPixels(bmp, px.left + dx.roundToInt(), px.top + dy.roundToInt())
            }
        }
        val matrix = Matrix().apply { setValues(m) }
        val dst = RectF()
        matrix.mapRect(dst, RectF(px.left.toFloat(), px.top.toFloat(), (px.left + bmp.width).toFloat(), (px.top + bmp.height).toFloat()))
        val r = Rect().also { dst.roundOut(it) }
        if (r.isEmpty || r.width().toLong() * r.height() > MAX_SOURCE_PIXELS) return null
        return try {
            val out = BitmapUtils.createLayerBitmap(r.width(), r.height())
            Canvas(out).apply {
                translate(-r.left.toFloat(), -r.top.toFloat())
                concat(matrix)
                drawBitmap(bmp, px.left.toFloat(), px.top.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
            }
            val o = CanvasOps.opaqueBounds(out)
            when {
                o == null -> { out.recycle(); null }
                o.left == 0 && o.top == 0 && o.width() == out.width && o.height() == out.height -> ArrayPixels(out, r.left, r.top)
                else -> try { ArrayPixels(ArraySources.cropped(out, o), r.left + o.left, r.top + o.top) } finally { out.recycle() }
            }
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    private fun isAffine(m: FloatArray): Boolean {
        if (m.size < 9 || m.any { !it.isFinite() } || m[6] != 0f || m[7] != 0f || m[8] == 0f) return false
        val det = m[0] * m[4] - m[1] * m[3]
        return det != 0f && det.isFinite()
    }

    private fun moveOnly(m: FloatArray): Boolean = m[0] == m[8] && m[1] == 0f && m[3] == 0f && m[4] == m[8]

    /** The map takes the x and y axes onto the axes (scales, flips, quarter turns). */
    private fun keepsAxes(m: FloatArray): Boolean = (m[1] == 0f && m[3] == 0f) || (m[0] == 0f && m[4] == 0f) ||
        (abs(m[1]) < 1e-6f * abs(m[0]) && abs(m[3]) < 1e-6f * abs(m[4]))
}
