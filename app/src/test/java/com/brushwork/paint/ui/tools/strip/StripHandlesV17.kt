package com.brushwork.paint.ui.tools.strip

/**
 * v1.8 inventory rule 5 (design §3.9; recorded by the lead at F3, frozen): for every tool state of
 * [StripInventoryTest], the handles of the controls the v1.7 (legacy) strip shows: each control's
 * description, else its visible text. A converted strip must still offer each of them on row 1,
 * on the More row or inside an item's dropdown or popup (declared in `StripSpec.movedInto`),
 * except the duplicates §3.9 removes ([REMOVED]) and the one name §3.9 lets a tool keep for two
 * ([SAME]).
 *
 * A state is "<ToolId name>" on the raster layer, "<ToolId name>@vector" on the vector layer, each
 * with the tool as it opens on an empty layer of a 400 × 300 document.
 */
internal object StripHandlesV17 {

    /** §3.2 and §3.9: the strips' point and corner Undo / Redo and the "Select several" chip (F4). */
    val REMOVED: Set<String> = setOf(
        "Undo last point", "Redo point", "Undo last corner", "Redo corner", "Undo point edit", "Redo point edit",
        "Select several",
    )

    /** §3.9: "Snap" versus "Snap to objects", one name per tool (the longer one). */
    val SAME: Map<String, String> = mapOf("Snap" to "Snap to objects")

    val HANDLES: Map<String, Set<String>> = mapOf(
        "BRUSH" to setOf("Brush size", "Choose brush", "Opacity", "Pressure changes size: on"),
        "ERASER" to setOf("Brush size", "Choose brush", "Opacity", "Pressure changes size: off"),
        "SMUDGE" to setOf("Brush size", "Choose brush", "Pressure changes size: off", "Strength"),
        "BLUR" to setOf("Brush size", "Choose brush", "Pressure changes size: off", "Strength"),
        "FILL" to setOf("Anti-alias", "Close gaps 0 px", "Expand 0 px", "Refer: Layer", "Tolerance 32"),
        "EYEDROPPER" to setOf("Back to brush", "Sample: Canvas", "Size: 1×1"),
        "MAGIC_WAND" to setOf("Add to selection", "Anti-alias", "Contiguous", "Deselect", "Intersect with selection", "New selection", "Refer: Layer", "Selection menu", "Subtract from selection", "Tolerance 32"),
        "LASSO" to setOf("Add to selection", "Anti-alias", "Curve", "Deselect", "Freehand", "Intersect with selection", "New selection", "Polygon", "Selection menu", "Subtract from selection"),
        "MARQUEE" to setOf("1:1", "Add to selection", "Deselect", "Ellipse selection", "From center", "Intersect with selection", "New selection", "Rectangle selection", "Selection menu", "Snap", "Subtract from selection"),
        "TRANSFORM" to setOf(),
        "TEXT" to setOf("Horizontal text (tap for vertical)", "Letter scaling", "Snap", "Wrap around picture"),
        "SHAPE" to setOf("15°", "60 px", "Center", "Editable", "Numbers", "Proportional", "Shape settings", "Shape style", "Shape type", "Snap", "Stroke with"),
        "CURVE" to setOf("Closed", "Curve settings", "Fill only", "Numbers", "Redo point", "Snap", "Stroke and fill", "Stroke kind", "Stroke only", "Undo last point"),
        "POLYLINE" to setOf("Closed", "Fill only", "Numbers", "Polyline settings", "Redo point", "Snap", "Stroke and fill", "Stroke kind", "Stroke only", "Undo last point"),
        "FRAME_DIVIDER" to setOf("New frame layer"),
        "RULER" to setOf("Circular ruler", "Done", "Elliptical ruler", "Numbers", "Radial ruler", "Snap", "Straight ruler"),
        "OBJECT_SELECT" to setOf("Add to selection", "Deselect", "Intersect with selection", "New selection", "Refer: Canvas", "Refine edges", "Selection menu", "Subtract from selection"),
        "REMOVE" to setOf("Color adaptation", "Remove brush size", "Sample: Current layer"),
        "CLONE" to setOf("Aligned", "Brush size", "Choose brush", "Long-press where to copy from", "Opacity", "Pressure changes size: on", "Sample from: This layer", "Set source", "Show source"),
        "MASK" to setOf("+ Brush", "+ Linear", "+ Radial", "Add", "Adjust…", "Choose what the mask is for", "Components (0)", "Intersect", "Invert", "Mask overlay: after edits", "Subtract"),
        "PATH" to setOf("Cyclic", "Endpoint", "Fill only", "Higher order", "Lower order", "Numbers", "Path settings", "Path shapes", "Redo point", "Snap", "Stroke and fill", "Stroke kind", "Stroke only", "Undo last point"),
        "TEXT_FRAMES" to setOf("Snap", "Threads"),
        "ARRAY" to setOf(),
        "SYMMETRY" to setOf("Angle 0°", "Array ruler", "Done", "Kaleidoscope ruler", "Mirror ruler", "Off", "Perspective array ruler", "Reset symmetry", "Rotation ruler"),
        "PATHFINDER" to setOf("Crop shapes", "Divide shapes", "Exclude overlap", "Intersect shapes", "Merge shapes", "Minus back shape", "Minus front shape", "Outline shapes", "Select all objects", "Trim shapes", "Unite shapes"),
        "BRUSH@vector" to setOf("Brush size", "Choose brush", "Opacity", "Pressure changes size: on"),
        "ERASER@vector" to setOf("Brush size", "Choose brush", "Object", "Opacity", "Partial", "Pressure changes size: off", "To intersection"),
        "SMUDGE@vector" to setOf("Brush size", "Choose brush", "Pressure changes size: off", "Strength"),
        "BLUR@vector" to setOf("Brush size", "Choose brush", "Pressure changes size: off", "Strength"),
        "FILL@vector" to setOf("Anti-alias", "Close gaps 0 px", "Expand 0 px", "Refer: Layer", "Tolerance 32"),
        "EYEDROPPER@vector" to setOf("Back to brush", "Sample: Canvas", "Size: 1×1"),
        "MAGIC_WAND@vector" to setOf("Add to selection", "Anti-alias", "Contiguous", "Deselect", "Intersect with selection", "New selection", "Refer: Layer", "Selection menu", "Subtract from selection", "Tolerance 32"),
        "LASSO@vector" to setOf("Add to selection", "Anti-alias", "Curve", "Deselect", "Freehand", "Intersect with selection", "New selection", "Polygon", "Selection menu", "Subtract from selection"),
        "MARQUEE@vector" to setOf("1:1", "Add to selection", "Deselect", "Ellipse selection", "From center", "Intersect with selection", "New selection", "Rectangle selection", "Selection menu", "Snap", "Subtract from selection"),
        "TRANSFORM@vector" to setOf(),
        "TEXT@vector" to setOf("Horizontal text (tap for vertical)", "Letter scaling", "Snap", "Wrap around picture"),
        "SHAPE@vector" to setOf("15°", "60 px", "Center", "Numbers", "Proportional", "Shape settings", "Shape style", "Shape type", "Snap", "Stroke with"),
        "CURVE@vector" to setOf("Closed", "Curve settings", "Fill only", "Numbers", "Redo point", "Snap", "Stroke and fill", "Stroke kind", "Stroke only", "Undo last point"),
        "POLYLINE@vector" to setOf("Closed", "Fill only", "Numbers", "Polyline settings", "Redo point", "Snap", "Stroke and fill", "Stroke kind", "Stroke only", "Undo last point"),
        "FRAME_DIVIDER@vector" to setOf("New frame layer"),
        "RULER@vector" to setOf("Circular ruler", "Done", "Elliptical ruler", "Numbers", "Radial ruler", "Snap", "Straight ruler"),
        "OBJECT_SELECT@vector" to setOf("Add to selection", "Deselect", "Intersect with selection", "New selection", "Refer: Canvas", "Refine edges", "Selection menu", "Subtract from selection"),
        "REMOVE@vector" to setOf("Color adaptation", "Remove brush size", "Sample: Current layer"),
        "CLONE@vector" to setOf("Aligned", "Brush size", "Choose brush", "Long-press where to copy from", "Opacity", "Pressure changes size: on", "Sample from: This layer", "Set source", "Show source"),
        "MASK@vector" to setOf("+ Brush", "+ Linear", "+ Radial", "Add", "Adjust…", "Choose what the mask is for", "Components (0)", "Intersect", "Invert", "Mask overlay: after edits", "Subtract"),
        "PATH@vector" to setOf("Cyclic", "Endpoint", "Fill only", "Higher order", "Lower order", "Numbers", "Path settings", "Path shapes", "Redo point", "Snap", "Stroke and fill", "Stroke kind", "Stroke only", "Undo last point"),
        "TEXT_FRAMES@vector" to setOf("Snap", "Threads"),
        "ARRAY@vector" to setOf(),
        "SYMMETRY@vector" to setOf("Angle 0°", "Array ruler", "Done", "Kaleidoscope ruler", "Mirror ruler", "Off", "Perspective array ruler", "Reset symmetry", "Rotation ruler"),
        "PATHFINDER@vector" to setOf("Crop shapes", "Divide shapes", "Exclude overlap", "Intersect shapes", "Merge shapes", "Minus back shape", "Minus front shape", "Outline shapes", "Select all objects", "Trim shapes", "Unite shapes"),
    )
}
