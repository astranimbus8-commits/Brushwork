package com.brushwork.paint.tools.text

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The picture a text wraps around, seen from the text block (pure Kotlin): the outline polygons
 * (document px) turned into the block's own frame, centered on the item's position ([cx], [cy])
 * and unrotated by [rotationDeg], so a turned text still flows along its own lines.
 *
 * [blocked] answers "which x stretches of this band does the picture cover?": per polygon, the
 * exact x extent of the polygon inside the band (vertices inside it and edge crossings at its top
 * and bottom), widened by the distance on every side. That is the outline dilated by a square of
 * the distance (what a max filter of that radius gives) — computed from the stored outline, so
 * the distance can change while the picture is gone. Concave parts facing a line count as covered
 * (text never runs into a bay of the picture); holes are inside their outline and change nothing.
 */
class WrapObstacle(polygons: List<WrapPolygon>, cx: Float, cy: Float, rotationDeg: Float) {
    private val xs: Array<FloatArray>
    private val ys: Array<FloatArray>
    private val minY: FloatArray
    private val maxY: FloatArray

    init {
        val r = Math.toRadians(rotationDeg.toDouble())
        val c = if (rotationDeg == 0f) 1.0 else cos(r)
        val s = if (rotationDeg == 0f) 0.0 else sin(r)
        val usable = polygons.filter { it.xs.size >= 3 && it.xs.size == it.ys.size }
        xs = Array(usable.size) { FloatArray(usable[it].xs.size) }
        ys = Array(usable.size) { FloatArray(usable[it].xs.size) }
        minY = FloatArray(usable.size)
        maxY = FloatArray(usable.size)
        for ((k, p) in usable.withIndex()) {
            var lo = Float.POSITIVE_INFINITY
            var hi = Float.NEGATIVE_INFINITY
            for (i in p.xs.indices) {
                val dx = (p.xs[i] - cx).toDouble()
                val dy = (p.ys[i] - cy).toDouble()
                // Inverse of TextItem.localToDoc's rotation.
                val x = (dx * c + dy * s).toFloat()
                val y = (-dx * s + dy * c).toFloat()
                xs[k][i] = x
                ys[k][i] = y
                lo = min(lo, y)
                hi = max(hi, y)
            }
            minY[k] = lo
            maxY[k] = hi
        }
    }

    /** True when there is no outline at all (the text lays out as if unobstructed). */
    val isEmpty: Boolean get() = xs.isEmpty()

    /**
     * The x intervals (block-centered frame) the picture covers in the band [top]..[bottom]
     * (block-centered frame, y down), kept [gap] away from the outline. One interval per polygon
     * that reaches the band; they may overlap.
     */
    fun blocked(top: Float, bottom: Float, gap: Float): List<ClosedFloatingPointRange<Float>> {
        if (xs.isEmpty()) return emptyList()
        val g = (if (gap.isFinite()) max(0f, gap) else 0f) + CONTOUR_MARGIN
        val y0 = top - g
        val y1 = bottom + g
        var out: ArrayList<ClosedFloatingPointRange<Float>>? = null
        for (k in xs.indices) {
            if (maxY[k] < y0 || minY[k] > y1) continue
            val px = xs[k]
            val py = ys[k]
            val n = px.size
            var lo = Float.POSITIVE_INFINITY
            var hi = Float.NEGATIVE_INFINITY
            for (i in 0 until n) {
                val ax = px[i]; val ay = py[i]
                val j = if (i + 1 == n) 0 else i + 1
                val bx = px[j]; val by = py[j]
                if (ay >= y0 && ay <= y1) {
                    if (ax < lo) lo = ax
                    if (ax > hi) hi = ax
                }
                // Where the edge crosses the band's top and bottom lines.
                if ((ay < y0) != (by < y0) && ay != by) {
                    val x = ax + (y0 - ay) * (bx - ax) / (by - ay)
                    if (x < lo) lo = x
                    if (x > hi) hi = x
                }
                if ((ay < y1) != (by < y1) && ay != by) {
                    val x = ax + (y1 - ay) * (bx - ax) / (by - ay)
                    if (x < lo) lo = x
                    if (x > hi) hi = x
                }
            }
            if (lo > hi) continue
            val list = out ?: ArrayList<ClosedFloatingPointRange<Float>>(2).also { out = it }
            list += (lo - g)..(hi + g)
        }
        return out ?: emptyList()
    }

    /**
     * [blocked] for a text area whose top-left corner is at ([areaLeft], [areaTop]) in the
     * block-centered frame: the callback [WrapLayout.layout] takes (text area coordinates).
     */
    fun forArea(areaLeft: Float, areaTop: Float, gap: Float): (Float, Float) -> List<ClosedFloatingPointRange<Float>> = { t, b ->
        val list = blocked(t + areaTop, b + areaTop, gap)
        if (list.isEmpty() || areaLeft == 0f) list else list.map { (it.start - areaLeft)..(it.endInclusive - areaLeft) }
    }

    companion object {
        /**
         * Extra distance (px) always kept from the outline: the traced outline is simplified by up
         * to 0.75 px and anti-aliased glyph edges reach a little past their advance.
         */
        const val CONTOUR_MARGIN = 1f
    }
}
