package com.brushwork.paint.tools.points

import com.brushwork.paint.core.Affine2
import com.brushwork.paint.core.Vec2

/**
 * v1.7 (item 1, design §3.1 and §4.5, I12): what a point editor (Curve, Polyline, Path, Shape
 * Points, the Free deform mesh) gives the gizmo, the X / Y pill and the property rows. Main
 * thread; Compose-state backed.
 *
 * A group gesture is ONE in-tool step: [beginGroupEdit] captures the selected points (and their
 * tangent handles), each event calls [setGroupTransform] with the whole gesture's map so far, and
 * [endGroupEdit] closes the step. With exactly one point selected and [selectSeveral] off, the
 * tool behaves as in v1.6.
 */
interface PointEditor {
    /** The number of points of the edited object (0 when none is open). */
    val pointCount: Int

    /** The selected points of the edited object. */
    val pointSelection: PointSelection

    /** Replaces the selection (not an in-tool step of its own). */
    fun selectPoints(s: PointSelection)

    /** "Select several" (a tool-session toggle; off when the tool closes or the object changes). */
    var selectSeveral: Boolean

    /** Document px of point i (Path: the control point; Free deform: the mesh vertex). */
    fun pointAt(i: Int): Vec2

    /** Starts ONE group edit: one in-tool step until endGroupEdit; positions and tangent handles are captured now. */
    fun beginGroupEdit(label: String)

    /** Maps the CAPTURED selected points (and their handles, as vectors) by [m]; replaces the previous call's map. */
    fun setGroupTransform(m: Affine2)

    /** Ends the group edit started by [beginGroupEdit]. */
    fun endGroupEdit()

    /** Deletes the selected points as one in-tool step; false when nothing changed (the minimum-points toast is the tool's). */
    fun deleteSelectedPoints(): Boolean

    /** Path, Curve, Polyline: 2; closed Shape: 3; mesh: Int.MAX_VALUE (vertices are not deletable). */
    val minPoints: Int
}
