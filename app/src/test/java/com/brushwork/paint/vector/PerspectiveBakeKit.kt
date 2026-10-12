package com.brushwork.paint.vector

import android.graphics.RectF

/**
 * v1.8 polish (mirrors follow-ups, item 3): what a Perspective array bake keeps on a vector layer.
 * Since the polish, [ObjectCopies.baked] with the document's rect drops the copies whose bounds
 * (all they can paint) lie wholly outside the canvas; the source always stays. The tests that
 * counted one baked path per map count these instead.
 */
internal object PerspectiveBakeKit {
    fun canvas(w: Int, h: Int) = RectF(0f, 0f, w.toFloat(), h.toFloat())

    /** The bake of [source] by [maps] without the cut: the source, then one plain object per map other than the identity. */
    fun uncut(source: VObject, maps: List<FloatArray>): List<VObject> = ObjectCopies.baked(source, maps)

    /** Of [uncut] (source first), what lies on a [w] × [h] canvas: the source, and each copy whose bounds meet the canvas, in order. */
    fun onCanvas(uncut: List<VObject>, w: Int, h: Int): List<VObject> =
        uncut.filterIndexed { k, o -> k == 0 || RectF.intersects(VectorOps.bounds(o), canvas(w, h)) }

    /** True when [o] paints nowhere on a [w] × [h] canvas (its bounds wholly outside). */
    fun whollyOff(o: VObject, w: Int, h: Int): Boolean = !RectF.intersects(VectorOps.bounds(o), canvas(w, h))
}
