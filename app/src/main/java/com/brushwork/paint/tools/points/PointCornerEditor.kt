package com.brushwork.paint.tools.points

/**
 * v1.10 (R12, J28): the corner type of the selected points, as the pill's SHARP cell shows it.
 * [SHARP]: every selected point is sharp; [SMOOTH]: every one is smooth; [MIXED]: some of each;
 * [ENDS_ONLY]: only the ends of an open path are selected (always sharp); [ALWAYS_SHARP]: a
 * Polyline point (the greyed cell, UQ18).
 */
enum class CornerState { SHARP, SMOOTH, MIXED, ENDS_ONLY, ALWAYS_SHARP }

/** v1.10 (R12, J28): what a point editor gives the pill's SHARP cell. Main thread. */
interface PointCornerEditor {
    /** null hides the SHARP cell; derivedStateOf-backed. */
    val corner: CornerState?

    /** True while a group edit runs (C10). */
    val cornerLocked: Boolean

    /** One in-tool step; false when nothing changed (locked, ends only). */
    fun setSelectedSharp(sharp: Boolean): Boolean
}

/** v1.10 (R12): a tool whose points can be made sharp or smooth from the pill; null hides the cell. */
interface CornerTool { val pointCorners: PointCornerEditor? }
