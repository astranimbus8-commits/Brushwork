package com.brushwork.paint.tools

import com.brushwork.paint.core.Vec2

/*
 * The X / Y coordinate strip under the tool options (v1.5, §4.6) reads and moves "the thing
 * being placed" through these interfaces (frozen). Tools that existed before v1.5 are adapted
 * from outside (ui/tools/CoordinateSources.kt); new tools implement [PositionedTool] themselves.
 */

/** The position the X / Y strip shows and edits. */
interface ObjectPosition {
    /** Document px; null = nothing to place (the strip is hidden). Backed by Compose state. */
    val position: Vec2?

    /** What the position is: "Center", "Top left", "Point 3", "Source", "Radial 1". */
    val label: String

    /** Moves the object so its position is ([x], [y]) (document px; null = that axis unchanged). */
    fun setPosition(x: Float?, y: Float?)

    /** A slider drag or a typed value is complete (one undo step per edit, per the tool's model). */
    fun endPositionEdit() {}
}

/** A tool with an [ObjectPosition] (null while there is nothing to place). */
interface PositionedTool {
    val objectPosition: ObjectPosition?
}
