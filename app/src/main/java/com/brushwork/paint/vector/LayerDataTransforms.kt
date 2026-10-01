package com.brushwork.paint.vector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.masks.MaskSpecs
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.vector.render.RenderCache
import com.brushwork.paint.vector.render.VectorLayerRenderer

/**
 * How a layer's editable data follows a canvas geometry change (resize image, canvas size, crop,
 * trim, rotate, flip, color mode; v1.5 §5.4 and §4.9's table; API frozen, owned by A1). Called by
 * CanvasOps for every layer whose pixels changed; pure and thread-safe (immutable data in, out).
 *
 * - Vector content is mapped object by object ([VectorOps.transformed]: strokes scale their
 *   `sizeScale` by sqrt|det|, shapes stay shapes under similarities and symmetric mirrors).
 * - A mask spec is mapped by [MaskSpecs.transformed] (dropped when that can't).
 * - Text and shape layers become raster layers (as in v1.4: their pixels are resampled).
 * - An identity map (color-mode changes) keeps everything: in a grayscale or 1-bit document a
 *   vector layer's cache is the constrained rendering of its objects.
 * - Adjustments are kept.
 */
object LayerDataTransforms {
    /** Data after a canvas geometry change [m] (old -> new document px); vector content mapped (sizeScale), maskSpec via MaskSpecs.transformed. */
    fun transformed(d: LayerData, m: Matrix, newW: Int, newH: Int): LayerData {
        if (m.isIdentity) return d.copy(text = null, shape = null)
        val values = FloatArray(9).also { m.getValues(it) }
        val vector = d.vector?.let { mapped(it, values) }
        val mask = d.maskSpec?.let { MaskSpecs.transformed(it, m) }
        return d.copy(text = null, shape = null, vector = vector, maskSpec = mask)
    }

    /** [content] with every object mapped by [m] (3x3 row-major); ids and order kept. */
    fun mapped(content: VectorContent, m: FloatArray): VectorContent =
        content.copy(objects = content.objects.map { VectorOps.transformed(it, m) })

    /**
     * Background-thread re-render of a vector layer for scaling ops (null = keep CanvasOps'
     * resampled pixels): [content] (in the NEW document's coordinates, i.e. already mapped) drawn
     * on a new [newW] x [newH] bitmap, crisp at any scale. Null when [cancelled] (checked between
     * objects) or out of memory.
     */
    fun renderScaled(content: VectorContent, newW: Int, newH: Int, cancelled: () -> Boolean): Bitmap? =
        try {
            renderScaled(content, newW, newH, cancelled) {}
        } catch (e: OutOfMemoryError) {
            null
        }

    /**
     * [renderScaled] reporting [progress] (0..1, per object; it may throw to abandon the render,
     * e.g. CanvasOps' Stop) and letting out-of-memory errors through to the caller.
     */
    internal fun renderScaled(content: VectorContent, newW: Int, newH: Int, cancelled: () -> Boolean, progress: (Float) -> Unit): Bitmap? {
        if (newW < 1 || newH < 1) return null
        val out = BitmapUtils.createLayerBitmap(newW, newH)
        val tips = TipCache(8L shl 20)
        try {
            val doc = Rect(0, 0, newW, newH)
            val finished = VectorLayerRenderer.renderWith(Canvas(out), content, doc, emptySet(), tips, doc, RenderCache()) { done, n ->
                progress(done.toFloat() / n.coerceAtLeast(1))
                !cancelled()
            }
            if (!finished) {
                out.recycle()
                return null
            }
        } catch (t: Throwable) {
            out.recycle()
            throw t
        } finally {
            tips.clear()
        }
        return out
    }
}
