package com.brushwork.paint.engine

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import com.brushwork.paint.model.ArrayLayout
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.LayerArray
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.geom.ObjectIndex
import java.util.IdentityHashMap

/**
 * v1.7 (item 3, I14): the ONE seam through which a layer's cache shows its live array. Every
 * cache writer draws through it: `EditorController.updateLayerData` (and so `updateTextLayer` /
 * `updateShapeLayer`) and `addLayerWithContent` wrap their draw lambda in [drawWithArray];
 * `VectorLayers` diffs and renders [effectiveVector] of the before and after data; canvas
 * operations re-render a vector layer from [effectiveVector] of its mapped data. A raster
 * array's cache is [drawPixels].
 *
 * Placement is [ArrayLayout] (instance 0 is the exact identity), measured from [sourceBounds]:
 * the source's CURRENT bounds, so relative offsets follow edits of the source. Copies are drawn
 * from k = N − 1 down to 0, so the source is on top. A layer without an array draws exactly as in
 * v1.6: [drawWithArray] calls its lambda once, untouched, and [effectiveVector] is the layer's own
 * content (the same instance).
 *
 * Thread-safe: canvas operations call [effectiveVector] and [sourceBounds] from their worker.
 */
object ArrayDraw {
    /** Copy k ≥ 1 of object `id` has the id `id + (k shl COPY_ID_SHIFT)` (real ids lie within ±2^52, `VectorCodec`'s MAX_ID). */
    const val COPY_ID_SHIFT = 53

    /** Memoized results kept (the before and after data of an edit, and a little slack). */
    private const val MEMO = 4

    private val lock = Any()

    /**
     * Draws [drawSource] (the source alone, at identity, in document px) once per [ArrayLayout]
     * matrix of [array] measured from [sourceBounds]: copies k = N − 1 down to 1 under their
     * matrix, then the source itself with no matrix at all (the source is on top and its pixels
     * are exactly what [drawSource] alone draws). Without an array, with an empty or non-finite
     * [sourceBounds], or in "Edit source pixels" mode ([ArraySpec.editingSource]) [drawSource]
     * runs once, untouched. A copy whose matrix is not finite is skipped.
     */
    fun drawWithArray(canvas: Canvas, array: LayerArray?, sourceBounds: RectF, drawSource: (Canvas) -> Unit) {
        val ms = if (array == null) null else matricesOf(array, sourceBounds)
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

    /**
     * A raster array's cache: its [LayerArray.pixels] drawn once per matrix (copies bilinear,
     * the source itself at its place unfiltered, so its pixels are exact); only the source while
     * editing it ([ArraySpec.editingSource]). Nothing without pixels.
     */
    fun drawPixels(canvas: Canvas, array: LayerArray) {
        val px = array.pixels ?: return
        val bmp = px.bitmap
        if (bmp.isRecycled) return
        val src = RectF(px.left.toFloat(), px.top.toFloat(), (px.left + bmp.width).toFloat(), (px.top + bmp.height).toFloat())
        val ms = matricesOf(array, src)
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

    /**
     * What a vector layer's cache shows, from DATA (before or after an edit): the objects of
     * [LayerData.vector] repeated per matrix, bottom first: copy N − 1's objects, …, copy 1's,
     * then the source objects themselves (on top; the same instances). Copy k ≥ 1 of object
     * `id` is `VectorOps.transformed(o, matrix k)` with the id `id + (k shl 53)` ([COPY_ID_SHIFT]):
     * disjoint from real ids and from every other copy's; copy ids are never encoded or
     * sanitized (the layer's DATA stays the source objects). At most [ArraySpec.MAX_INSTANCES]
     * objects: the last copies are left out beyond that.
     *
     * Without an array (or with one instance, or no objects): [LayerData.vector] itself, the same
     * instance. Results are memoized by the content's identity and the spec, and copies of
     * unchanged source objects are reused (the same instances) while the matrices stay the same,
     * so `ContentDiff` and `ObjectIndex` see unchanged copies as unchanged.
     */
    fun effectiveVector(data: LayerData): VectorContent? {
        val v = data.vector ?: return null
        val a = data.array ?: return v
        if (v.objects.isEmpty()) return v
        synchronized(lock) {
            for (e in memo) if (e.source === v && e.spec == a.spec) return e.result
        }
        val ms = matricesOf(a, ObjectIndex.of(v).unionBounds())
        if (ms.size <= 1) return v
        val n = v.objects.size
        val copies = minOf(ms.size, maxOf(1, ArraySpec.MAX_INSTANCES / n))
        if (copies <= 1) return v
        // Copies of source objects already mapped by the same matrices (a previous result).
        val reuse: IdentityHashMap<VObject, Array<VObject?>>? = synchronized(lock) {
            memo.firstOrNull { e -> sameMatrices(e.matrices, ms) }?.copies
        }
        val made = IdentityHashMap<VObject, Array<VObject?>>(n * 2)
        val out = ArrayList<VObject>(n * copies)
        for (k in copies - 1 downTo 1) {
            val mk = ms[k]
            if (mk.any { !it.isFinite() }) continue
            val shift = k.toLong() shl COPY_ID_SHIFT
            for (o in v.objects) {
                val slots = made.getOrPut(o) { reuse?.get(o)?.copyOf(ms.size) ?: arrayOfNulls(ms.size) }
                val c = slots[k]?.takeIf { it.id == o.id + shift } ?: VectorOps.transformed(o, mk).withId(o.id + shift).also { slots[k] = it }
                out += c
            }
        }
        out.addAll(v.objects)
        val result = v.copy(objects = out)
        synchronized(lock) {
            memo.addFirst(Memo(v, a.spec, ms, made, result))
            while (memo.size > MEMO) memo.removeLast()
        }
        return result
    }

    /**
     * The bounds [ArrayLayout] measures from, from DATA (document px, a new RectF): the raster
     * source's [com.brushwork.paint.model.ArrayPixels] rectangle, else the vector content's paint
     * bounds (`ObjectIndex.unionBounds`, what `LayerDataTransforms` maps arrays by), else the
     * shape's paint bounds (`VectorOps.bounds` of it as a `VShape`: a shape array applied or
     * converted to vector objects keeps its placement), else the text's bounds
     * (`TextRenderer.prepare(item).docBounds(item)`, as the Text tool measures it). Null when
     * the data has no source (or it can't be read); empty when the source paints nothing.
     */
    fun sourceBounds(data: LayerData): RectF? {
        data.array?.pixels?.let { px ->
            return RectF(px.left.toFloat(), px.top.toFloat(), (px.left + px.bitmap.width).toFloat(), (px.top + px.bitmap.height).toFloat())
        }
        data.vector?.let { return ObjectIndex.of(it).unionBounds() }
        data.shape?.let { s ->
            val o = ShapeCodec.decode(s) ?: return null
            return VectorOps.bounds(VShape(0L, shape = o))
        }
        data.text?.let { t -> return textBounds(t) }
        return null
    }

    /**
     * Everything the cache of [data] covers: `ArrayLayout.bounds(spec, sourceBounds(data))`
     * (document px, a new RectF; the copies' paint bounds lie within it). Null without an array
     * or a source.
     */
    fun cacheBounds(data: LayerData): RectF? {
        val a = data.array ?: return null
        val s = sourceBounds(data) ?: return null
        return ArrayLayout.bounds(a.spec, s)
    }

    // ------------------------------------------------------------------ internals

    /** [array]'s matrices for [source]; only the identity while editing the source, or for an unusable source rectangle. */
    private fun matricesOf(array: LayerArray, source: RectF): List<FloatArray> {
        val ok = source.left.isFinite() && source.top.isFinite() && source.right.isFinite() && source.bottom.isFinite() && !source.isEmpty
        if (array.spec.editingSource || !ok) return listOf(IDENTITY)
        return ArrayLayout.matrices(array.spec, source)
    }

    private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    private fun sameMatrices(a: List<FloatArray>, b: List<FloatArray>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) if (!a[i].contentEquals(b[i])) return false
        return true
    }

    private class Memo(
        val source: VectorContent,
        val spec: ArraySpec,
        val matrices: List<FloatArray>,
        val copies: IdentityHashMap<VObject, Array<VObject?>>,
        val result: VectorContent,
    )

    private val memo = ArrayDeque<Memo>()

    /** The text bounds of the last few text data strings (layout is not cheap). */
    private val textMemo = ArrayDeque<Pair<String, RectF>>()

    private fun textBounds(data: String): RectF? {
        synchronized(lock) {
            for ((d, r) in textMemo) if (d == data) return RectF(r)
        }
        val item = TextCodec.decode(data) ?: return null
        val prep = TextRenderer.prepare(item)
        val r = if (prep.isEmpty) RectF() else prep.docBounds(item)
        synchronized(lock) {
            textMemo.addFirst(data to RectF(r))
            while (textMemo.size > MEMO) textMemo.removeLast()
        }
        return r
    }

    /** Forgets every memoized result (tests; the editor's `dispose` through `ObjectIndex.clearCache`'s callers). */
    internal fun clearCaches() {
        synchronized(lock) {
            memo.clear()
            textMemo.clear()
        }
    }
}
