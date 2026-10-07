package com.brushwork.paint.tools

import com.brushwork.paint.core.LengthUnit
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

    /**
     * A slider drag (or an arrow press, a typed value) starts: everything until [endPositionEdit]
     * is one edit, however slowly the finger moves (a tool that coalesces edits by time must not
     * split it, nor fold it into the edit before).
     */
    fun beginPositionEdit() {}

    /** A slider drag or a typed value is complete (one undo step per edit, per the tool's model). */
    fun endPositionEdit() {}
}

/** A tool with an [ObjectPosition] (null while there is nothing to place). */
interface PositionedTool {
    val objectPosition: ObjectPosition?
}

/*
 * v1.7 (design §4.6, frozen after F5): the pill's sources. `coordinateSourceOf` routes
 * [PillPositionTool] generically, before the v1.6 adapters; the pill's Scale row and trash cell
 * read `(tool as? ScaledTool)?.objectScale` and `(tool as? DeletingTool)?.objectDeletion`, never a
 * concrete tool class. Implementers: Curve, Polyline and Path [B]; Shape [C]; Text [D]; Transform
 * [F] (PillPositionTool and ScaledTool only). On `main` before they merge, no tool implements them.
 */

/**
 * Items 1, 9, 12, 13: the ONE stable pill source of a v1.7 point editor (Curve, Polyline, Path,
 * Shape, Text, Transform). [pillPosition] is the same object for the tool's lifetime; its position
 * falls back from the single selected point ("Point 3") to the selection's box centre ("Selected
 * points": setPosition moves the group) to the open object's centre ("Center"); position is null
 * only when no object is open (the pill hides).
 */
interface PillPositionTool {
    val pillPosition: ObjectPosition
    val pillUnit: LengthUnit
}

/** Item 9: the pill's Scale row. Compose-state backed. */
interface ObjectScale {
    /** Percent (x, y) of the reference box captured when the object or point selection was taken; null hides row 2. */
    val scalePercent: Vec2?

    /** Text: Scale Y hidden, "Keep scale proportions" forced on. */
    val uniformOnly: Boolean get() = false

    fun beginScaleEdit() {}

    /** About the box centre; null keeps that axis. */
    fun setScale(xPercent: Float?, yPercent: Float?)

    fun endScaleEdit() {}
}

/** A tool with an [ObjectScale] (null while there is nothing to scale). */
interface ScaledTool {
    val objectScale: ObjectScale?
}

/** Item 13: the pill's trash cell. */
interface ObjectDeletion {
    /**
     * "Delete selected points" (some but not all points selected); otherwise, ALSO with every
     * point selected, "Delete curve", "Delete polyline", "Delete path", "Delete shape" or
     * "Delete text". Null hides the cell (no object open).
     */
    val deleteLabel: String?

    fun delete()
}

/** A tool with an [ObjectDeletion] (null while nothing is open). */
interface DeletingTool {
    val objectDeletion: ObjectDeletion?
}
