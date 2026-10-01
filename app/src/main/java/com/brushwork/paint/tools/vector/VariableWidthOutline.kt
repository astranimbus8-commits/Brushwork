package com.brushwork.paint.tools.vector

/**
 * The filled outline of a line whose width varies along it (per-point thickness, §4.5; v1.5,
 * owned by A4, API frozen). F2 writes a reference; A4 the production version (round joins, caps,
 * closed paths). Foundation (F1): no outline.
 */
object VariableWidthOutline {
    /**
     * Outline of the polyline [xs]/[ys] (first [n] points, document px) with full widths
     * [widths] per point; [closed] joins the ends. Flattening [tolerance] in px.
     */
    fun build(xs: FloatArray, ys: FloatArray, widths: FloatArray, n: Int, closed: Boolean = false, tolerance: Float = 0.25f): VectorPath =
        VectorPath.EMPTY
}
