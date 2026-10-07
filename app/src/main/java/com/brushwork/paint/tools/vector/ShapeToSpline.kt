package com.brushwork.paint.tools.vector

import com.brushwork.paint.vector.VPath

/**
 * v1.7 (item 6, §3.6; area C): what [ShapeToSpline.convert] gives: the shape as a Path-tool
 * path ([path], with its spline) and the indices of its control points that stand for the
 * shape's selected corners ([selectedSplineIndices]), for `CurveTool.openPath` to select.
 */
class ConvertResult(val path: VPath, val selectedSplineIndices: IntArray)

/**
 * v1.7 (item 6, §3.6; area C): a shape turned into an editable Path-tool path, exactly: its
 * corners become sharp control points, its curved segments clamped pieces, its rounded corners
 * rational arcs, and its selected corners editable NURBS arcs; its look is carried over.
 *
 * Foundation stub: always null, so no shape converts until area C implements it.
 */
object ShapeToSpline {
    /**
     * [shape] as a path whose corners [selected] (indices into its points) became editable
     * curves, or null when it can't be converted (an arrow).
     */
    @Suppress("UNUSED_PARAMETER")
    fun convert(shape: ShapeObject, selected: IntArray): ConvertResult? = null
}
