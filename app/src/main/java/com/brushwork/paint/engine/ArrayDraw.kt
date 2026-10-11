package com.brushwork.paint.engine

import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import com.brushwork.paint.model.ArrayInstance
import com.brushwork.paint.model.ArrayInstances
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.ArrayTint
import com.brushwork.paint.model.CopyLook
import com.brushwork.paint.model.LayerArray
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.geom.ObjectIndex
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * v1.7 (item 3, I14): the ONE seam through which a layer's cache shows its live array. Every
 * cache writer draws through it: `EditorController.updateLayerData` (and so `updateTextLayer` /
 * `updateShapeLayer`) and `addLayerWithContent` wrap their draw lambda in [drawWithArray];
 * `VectorLayers` diffs and renders [effectiveVector] of the before and after data; canvas
 * operations re-render a vector layer from [effectiveVector] of its mapped data. A raster
 * array's cache is [drawPixels].
 *
 * Placement is `ArrayLayout` (instance 0 is the exact identity), measured from [sourceBounds]:
 * the source's CURRENT bounds, so relative offsets follow edits of the source. Copies are drawn
 * from k = N − 1 down to 0, so the source is on top. A layer without an array draws exactly as in
 * v1.6: [drawWithArray] calls its lambda once, untouched, and [effectiveVector] is the layer's own
 * content (the same instance).
 *
 * v1.8 (item 2, I19): every instance comes from [ArrayInstances] (its matrix and its
 * [CopyLook]), so an Array look shows in every cache: a pixel copy draws through its own paint
 * (alpha and the tint's `ColorMatrixColorFilter`), a text or shape copy inside a `saveLayer` with
 * that paint, and a vector copy's objects are [restyled] (colors tinted per paint, opacity
 * multiplied: design R12). The source (k = 0) never has a look, and a spec without a look
 * (`look == null`) draws, bounds and copies exactly as in v1.7: no layer, no paint change.
 *
 * Thread-safe: canvas operations call [effectiveVector] and [sourceBounds] from their worker, and
 * `ArrayRenders` draws [drawPixels] and [drawWithArray] on its worker.
 */
object ArrayDraw {
    /** Copy k ≥ 1 of object `id` has the id `id + (k shl COPY_ID_SHIFT)` (real ids lie within ±2^52, `VectorCodec`'s MAX_ID). */
    const val COPY_ID_SHIFT = 53

    /** Memoized results kept (the before and after data of an edit, and a little slack). */
    private const val MEMO = 4

    private val lock = Any()

    /**
     * Draws [drawSource] (the source alone, at identity, in document px) once per instance of
     * [array] measured from [sourceBounds] ([ArrayInstances]): copies k = N − 1 down to 1 under
     * their matrix, then the source itself with no matrix at all (the source is on top and its
     * pixels are exactly what [drawSource] alone draws). Without an array, with an empty or
     * non-finite [sourceBounds], or in "Edit source pixels" mode ([ArraySpec.editingSource])
     * [drawSource] runs once, untouched. A copy whose matrix is not finite is skipped.
     *
     * v1.8: a copy whose look changes its pixels ([changesPixels]: opacity below 1 or a tint) is
     * drawn inside a `saveLayer` over [sourceBounds] grown by [layerMargin], through [paintOf] its
     * look; a copy with opacity 0 is skipped. Copies without one draw exactly as in v1.7.
     */
    fun drawWithArray(canvas: Canvas, array: LayerArray?, sourceBounds: RectF, drawSource: (Canvas) -> Unit) {
        val instances = if (array == null) null else instancesOf(array, sourceBounds)
        if (instances == null || instances.size <= 1) {
            drawSource(canvas)
            return
        }
        val m = Matrix()
        var layer: RectF? = null
        for (inst in instances) {
            if (inst.k == 0) continue
            val v = inst.matrix
            if (v.any { !it.isFinite() }) continue
            val look = inst.look
            if (look != null && look.opacity <= 0f) continue
            m.setValues(v)
            canvas.save()
            canvas.concat(m)
            if (look != null && changesPixels(look)) {
                val r = layer ?: RectF(sourceBounds).apply { val g = layerMargin(sourceBounds); inset(-g, -g) }.also { layer = it }
                canvas.saveLayer(r, paintOf(look, null))
                drawSource(canvas)
                canvas.restore()
            } else {
                drawSource(canvas)
            }
            canvas.restore()
        }
        drawSource(canvas)
    }

    /**
     * A raster array's cache: its [LayerArray.pixels] drawn once per instance (copies bilinear,
     * the source itself at its place unfiltered, so its pixels are exact); only the source while
     * editing it ([ArraySpec.editingSource]). Nothing without pixels. v1.8: a copy whose look
     * changes its pixels draws through [paintOf] its look (alpha and tint, still bilinear); one
     * with opacity 0 is skipped.
     *
     * [keepGoing] is asked before each copy (a background render that was superseded stops
     * early: `ArrayRenders`); when it says false the drawing stops there, unfinished.
     */
    fun drawPixels(canvas: Canvas, array: LayerArray, keepGoing: (() -> Boolean)? = null) {
        val px = array.pixels ?: return
        val bmp = px.bitmap
        if (bmp.isRecycled) return
        val src = RectF(px.left.toFloat(), px.top.toFloat(), (px.left + bmp.width).toFloat(), (px.top + bmp.height).toFloat())
        val instances = instancesOf(array, src)
        val bilinear = Paint(Paint.FILTER_BITMAP_FLAG)
        val m = Matrix()
        for (inst in instances) {
            if (inst.k == 0) continue
            if (keepGoing != null && !keepGoing()) return
            val v = inst.matrix
            if (v.any { !it.isFinite() }) continue
            val look = inst.look
            if (look != null && look.opacity <= 0f) continue
            m.setValues(v)
            canvas.save()
            canvas.concat(m)
            canvas.drawBitmap(bmp, src.left, src.top, if (look != null && changesPixels(look)) paintOf(look, bilinear) else bilinear)
            canvas.restore()
        }
        canvas.drawBitmap(bmp, src.left, src.top, null)
    }

    /**
     * What a vector layer's cache shows, from DATA (before or after an edit): the objects of
     * [LayerData.vector] repeated per instance, bottom first: copy N − 1's objects, …, copy 1's,
     * then the source objects themselves (on top; the same instances). Copy k ≥ 1 of object
     * `id` is `VectorOps.transformed(o, matrix k)`, [restyled] by copy k's look, with the id
     * `id + (k shl 53)` ([COPY_ID_SHIFT]): disjoint from real ids and from every other copy's;
     * copy ids are never encoded or sanitized (the layer's DATA stays the source objects). At
     * most [ArraySpec.MAX_INSTANCES] objects: the last copies are left out beyond that. A copy
     * whose look has opacity 0 is left out too (it draws nothing, as in [drawWithArray]).
     *
     * Without an array (or with one instance, or no objects): [LayerData.vector] itself, the same
     * instance. Results are memoized by the content's identity and the spec, and copies of
     * unchanged source objects are reused (the same instances) while the matrices and the looks
     * stay the same, so `ContentDiff` and `ObjectIndex` see unchanged copies as unchanged.
     */
    fun effectiveVector(data: LayerData): VectorContent? {
        val v = data.vector ?: return null
        val a = data.array ?: return v
        if (v.objects.isEmpty()) return v
        synchronized(lock) {
            for (e in memo) if (e.source === v && e.spec == a.spec) return e.result
        }
        val instances = instancesOf(a, ObjectIndex.of(v).unionBounds())
        if (instances.size <= 1) return v
        val total = instances.size
        val byK = arrayOfNulls<ArrayInstance>(total)
        for (inst in instances) byK[inst.k] = inst
        val matrices = byK.map { it?.matrix ?: IDENTITY }
        val looks = byK.map { it?.look }
        val n = v.objects.size
        val copies = minOf(total, maxOf(1, ArraySpec.MAX_INSTANCES / n))
        if (copies <= 1) return v
        // Copies of source objects already mapped by the same matrices and looks (a previous result).
        val reuse: IdentityHashMap<VObject, Array<VObject?>>? = synchronized(lock) {
            memo.firstOrNull { e -> sameMatrices(e.matrices, matrices) && e.looks == looks }?.copies
        }
        val made = IdentityHashMap<VObject, Array<VObject?>>(n * 2)
        val out = ArrayList<VObject>(n * copies)
        for (k in copies - 1 downTo 1) {
            val mk = matrices[k]
            if (mk.any { !it.isFinite() }) continue
            val look = looks[k]
            if (look != null && look.opacity <= 0f) continue
            val shift = k.toLong() shl COPY_ID_SHIFT
            for (o in v.objects) {
                val slots = made.getOrPut(o) { reuse?.get(o)?.copyOf(total) ?: arrayOfNulls(total) }
                val c = slots[k]?.takeIf { it.id == o.id + shift }
                    ?: restyled(VectorOps.transformed(o, mk), look).withId(o.id + shift).also { slots[k] = it }
                out += c
            }
        }
        out.addAll(v.objects)
        val result = v.copy(objects = out)
        synchronized(lock) {
            memo.addFirst(Memo(v, a.spec, matrices, looks, made, result))
            while (memo.size > MEMO) memo.removeLast()
        }
        return result
    }

    /**
     * v1.8 (item 2, design R12): [o] as a copy with [look] draws it: every paint color tinted by
     * [ArrayTint.apply] (a stroke's color; a path's fill, its gradient stops and its outline's
     * color; a shape's stroke and fill colors) and the object's opacity multiplied by the look's.
     * Geometry, ids and live symmetry copies are kept. [o] itself (the same instance) without a
     * look or with one that changes nothing (opacity 1, no tint).
     */
    fun restyled(o: VObject, look: CopyLook?): VObject {
        if (look == null || !changesPixels(look)) return o
        val opacity = o.opacity * look.opacity.coerceIn(0f, 1f)
        return when (o) {
            is VStroke -> o.copy(color = tinted(o.color, look), opacity = opacity)
            is VPath -> o.copy(fill = o.fill?.let { tinted(it, look) }, stroke = o.stroke?.let { it.copy(color = tinted(it.color, look)) }, opacity = opacity)
            is VShape -> o.copy(shape = o.shape.copy(strokeColor = tinted(o.shape.strokeColor, look), fillColor = tinted(o.shape.fillColor, look)), opacity = opacity)
        }
    }

    /** [argb] as a copy with [look] paints it ([ArrayTint.apply]; alpha kept): [argb] itself without a tint. */
    fun tinted(argb: Int, look: CopyLook): Int = if (look.tintStrength == 0f) argb else ArrayTint.apply(argb, look.tintColor, look.tintStrength)

    /** [paint] with every color [tinted] by [look] ([paint] itself without a tint). */
    fun tinted(paint: VPaint, look: CopyLook): VPaint {
        if (look.tintStrength == 0f) return paint
        return when (paint) {
            is VPaint.Solid -> VPaint.Solid(tinted(paint.color, look))
            is VPaint.Linear -> paint.copy(stops = paint.stops.map { it.copy(color = tinted(it.color, look)) })
            is VPaint.Radial -> paint.copy(stops = paint.stops.map { it.copy(color = tinted(it.color, look)) })
        }
    }

    /** True when a copy with [look] draws differently from the plain copy: opacity below 1, or a tint. */
    fun changesPixels(look: CopyLook): Boolean = look.opacity < 1f || look.tintStrength > 0f

    /**
     * The paint a copy with [look] draws through (a new one, based on [base] when given): its
     * opacity as alpha, and its tint as a `ColorMatrixColorFilter` ([CopyLook.colorMatrix]).
     */
    fun paintOf(look: CopyLook, base: Paint?): Paint {
        val p = if (base != null) Paint(base) else Paint()
        p.alpha = (look.opacity.coerceIn(0f, 1f) * 255f).roundToInt()
        look.colorMatrix()?.let { p.colorFilter = ColorMatrixColorFilter(it) }
        return p
    }

    /**
     * The margin a text or shape copy's layer gets around [source] (document px): glyphs and
     * strokes may reach a little past the measured bounds (italics, accents), as the Array tool's
     * proxy allows for.
     */
    fun layerMargin(source: RectF): Float = max(4f, 0.1f * max(source.width(), source.height()))

    /**
     * The bounds the array is measured from, from DATA (document px, a new RectF): the raster
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
     * Everything the cache of [data] covers: `ArrayInstances.bounds(spec, sourceBounds(data))`
     * (document px, a new RectF; the copies' paint bounds lie within it, each copy's look size
     * and turn included; `ArrayLayout.bounds` itself when the spec has no look). Null without an
     * array or a source.
     */
    fun cacheBounds(data: LayerData): RectF? {
        val a = data.array ?: return null
        val s = sourceBounds(data) ?: return null
        return ArrayInstances.bounds(a.spec, s)
    }

    // ------------------------------------------------------------------ internals

    /**
     * [array]'s instances for [source] in draw order ([ArrayInstances.of]); only the source
     * (identity, no look) while editing the source, or for an unusable source rectangle.
     */
    internal fun instancesOf(array: LayerArray, source: RectF): List<ArrayInstance> {
        val ok = source.left.isFinite() && source.top.isFinite() && source.right.isFinite() && source.bottom.isFinite() && !source.isEmpty
        if (array.spec.editingSource || !ok) return listOf(ArrayInstance(0, IDENTITY.copyOf(), null))
        return ArrayInstances.of(array.spec, source)
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
        val looks: List<CopyLook?>,
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
