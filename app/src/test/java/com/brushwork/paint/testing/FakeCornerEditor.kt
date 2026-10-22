package com.brushwork.paint.testing

import com.brushwork.paint.tools.points.CornerState
import com.brushwork.paint.tools.points.PointCornerEditor

/**
 * v1.10 F1 (design 2.16, R12 and J28; lead, frozen kit): a [PointCornerEditor] with settable state
 * that records every call, for the pill's SHARP cell tests of A5 without a point tool. [corner]
 * (null hides the cell) and [cornerLocked] are settable; [setSelectedSharp] follows the editor's
 * rules: it changes nothing and returns false while [cornerLocked], for [CornerState.ENDS_ONLY]
 * and [CornerState.ALWAYS_SHARP] (always sharp), with no selection (null), or when every selected
 * point already is what was asked; otherwise it sets [corner] to SHARP or SMOOTH, counts one
 * in-tool step in [steps] and returns true. [calls] lists the calls (`"setSelectedSharp true"`)
 * in order. Main thread.
 */
class FakeCornerEditor(
    override var corner: CornerState? = CornerState.SMOOTH,
    override var cornerLocked: Boolean = false,
) : PointCornerEditor {
    /** Every call, as text, in order. */
    val calls: MutableList<String> = mutableListOf()

    /** The in-tool steps [setSelectedSharp] made. */
    var steps = 0
        private set

    override fun setSelectedSharp(sharp: Boolean): Boolean {
        calls += "setSelectedSharp $sharp"
        val now = corner ?: return false
        if (cornerLocked) return false
        if (now == CornerState.ENDS_ONLY || now == CornerState.ALWAYS_SHARP) return false
        val wanted = if (sharp) CornerState.SHARP else CornerState.SMOOTH
        if (now == wanted) return false
        corner = wanted
        steps++
        return true
    }
}
