package com.brushwork.paint.tools

/**
 * v1.9 (R11, design 2.4.12; frozen): a drawing tool that can erase with its own outline.
 * CurveTool (A1) and ShapeTool (A9).
 *
 * The bottom bar's slot 1 (A5; F1b: v1.8) stays one slot: when the current tool is an
 * [ErasableTool] it reads [eraseLabel] or [erasingLabel], draws the slot open while [eraseOn] and
 * calls `setErase(!eraseOn)`; otherwise it is v1.8's.
 */
interface ErasableTool {
    /** Transient: never saved; off when the tool is chosen again. */
    val eraseOn: Boolean

    fun setErase(on: Boolean)

    /** Slot 1 while erase mode is off: EraseLabels19.WITH_CURVE, WITH_POLYLINE, WITH_PATH or WITH_SHAPE for the current kind. */
    val eraseLabel: String

    /** Slot 1 while erase mode is on: EraseLabels19.ERASING_CURVE, ERASING_POLYLINE, ERASING_PATH or ERASING_SHAPE. */
    val erasingLabel: String
}
