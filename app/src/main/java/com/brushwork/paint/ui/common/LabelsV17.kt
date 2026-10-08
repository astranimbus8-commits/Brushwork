package com.brushwork.paint.ui.common

/**
 * v1.7 (I10, design §4.8): the labels used across areas or by foundation-written code, in ONE
 * frozen file. Labels that only one screen uses live with that screen, as in v1.6. Several are
 * longer than the bare word because the bare word already names something else on the same
 * screen ("Select all", "Roundness", "Keep proportions", "Smooth", "Columns", "Scale"...).
 */
object FolderLabels {
    const val NEW = "New folder"; const val PUT_IN_NEW = "Put in new folder"
    const val MOVE_IN = "Move into folder above"; const val MOVE_OUT = "Move out of folder"
    const val RENAME = "Rename folder"; const val DUPLICATE = "Duplicate folder"; const val MERGE = "Merge folder"
    const val FROM_FOLDER = "Layer from folder"; const val UNGROUP = "Ungroup folder"; const val PASS_THROUGH = "Pass through"
    fun open(name: String) = "Open $name"; fun close(name: String) = "Close $name"
    fun deleteAsk(n: Int) = "Delete folder and its $n layers?"; const val DELETE_ALL = "Delete all"; const val FOLDER_ONLY = "Folder only"
    const val PAINT_REFUSAL = "Choose a layer inside the folder to paint"
    const val MERGE_INTO_REFUSAL = "Can't merge into a folder: use Merge folder first"
    const val MERGE_BLEND_WARNING = "Blending with layers below the folder will change"
    const val DEPTH_LIMIT = "Folders can be nested 8 deep"; const val COUNT_LIMIT = "A picture can have up to 64 folders"
    const val NO_MASK = "Folders have no mask yet"; const val NO_ALPHA_LOCK = "Alpha lock works on layers"
    const val REPAIRED = "The folder structure was repaired"
    fun locked(name: String) = "Folder “$name” is locked"; fun hidden(name: String) = "Folder “$name” is hidden"
}

object ArrayLabels {
    const val BUTTON = "Array"; const val FROM_SELECTION = "Array from selection"; const val FROM_OBJECTS = "Array from objects"
    const val OPEN = "Array…"; const val EDIT = "Edit array"; const val APPLY = "Apply array"; const val REMOVE = "Remove array"
    const val APPLIED = "Array applied"; const val RENDERING = "Rendering array…"; const val DEFORM_REFUSAL = "Apply the array to deform"
    const val LINE = "Array in a line"; const val CIRCLE = "Array in a circle"; const val CURVE = "Array along a curve"; const val TRANSFORM = "Array by transform"
    const val COPY_SPACING = "Copy spacing"; const val SCALE_PER_COPY = "Scale per copy"
    const val APPLY_TEXT_ASK = "Apply turns the text into pixels"; const val TOO_MANY = "Too many copies to apply: lower the count"
    const val LINKED_REFUSAL = "Linked text frames can't be arrayed"; const val PLAIN_REFUSAL = "Select some pixels, or pick a text, shape or vector layer"
    const val FOLDER_REFUSAL = "Folders can't be arrayed"
    const val EDIT_SOURCE = "Edit source pixels"; const val FINISH_SOURCE = "Finish source edit"; const val EDITING_BADGE = "Array: editing source"
    fun damaged(name: String) = "The array of layer “$name” could not be read; its copies are kept as pixels"
}

object PointLabels {
    const val SELECT_SEVERAL = "Select several"; const val SELECT_ALL = "Select all points"; const val DESELECT_ALL = "Deselect all points"
    const val SEVERAL_HINT = "Tap points to add or remove them. Drag on empty canvas to box-select."
    const val MIXED = "Mixed"; const val ROUNDNESS = "Point roundness"; const val RESET_ROUNDNESS = "Reset point roundness"
    const val ROUND_REFUSAL = "Only corners between straight sides can be rounded"
    const val TO_PATH = "Turn into path"; const val ARROW_REFUSAL = "Arrows can't become paths"; const val ENDS_SHARP = "Ends are always sharp"
    // "Sharp corner" / "Smooth" / "Deselect point" are the existing CurveToolOptions labels, reused.
}

object PillLabels {
    const val SCALE_X = "Scale X"; const val SCALE_Y = "Scale Y"; const val KEEP_PROPORTIONS = "Keep scale proportions"
    const val SCALE_INCREMENTS = "Scale increments"; const val DELETE_POINTS = "Delete selected points"
    /** [kind]: curve, polyline, path, shape, text. */
    fun deleteObject(kind: String) = "Delete $kind"
}

object CurveLabels17 {
    const val STROKE_ONLY = "Stroke only"; const val FILL_ONLY = "Fill only"; const val BOTH = "Stroke and fill"
    const val STROKE_KIND = "Stroke kind"; const val FILL_NEEDS_3 = "Fill needs 3 points"
}

object SavedSelectionLabels {
    const val ADD_LAYER = "Add selection layer"; const val SAVE_BUTTON = "Save"; const val SAVE = "Save selection"
    const val LOAD = "Load selection"; const val ADD = "Add to selection"; const val SUBTRACT = "Subtract from selection"
    const val INTERSECT = "Intersect with selection"; const val UPDATE = "Update from selection"; const val RENAME = "Rename selection"
    const val DELETE = "Delete saved selection"; const val LIMIT = "You can keep up to 32 saved selections"
    const val FULL = "Saved selections are full: delete one first"
}

object TransformLabels17 {
    const val FREE_DEFORM = "Free deform"; const val COLUMNS = "Mesh columns"; const val ROWS = "Mesh rows"
    const val SMOOTH = "Smooth mesh"; const val RESET = "Reset mesh"
    const val RASTERIZE_TO_DEFORM = "Rasterize to deform"; const val RASTERIZE_AND_DEFORM = "Rasterize and deform"
    const val RASTERIZE_TO_FREE_DEFORM = "Rasterize to free deform"; const val ONE_LAYER = "Free deform works on one layer"
}

/** The type chips are `SymmetryType.label`. */
object SymmetryLabels {
    const val TOOL = "Symmetry"; const val DIVISIONS = "Divisions"; const val RESET = "Reset symmetry"
    const val ERASER_NOTE = "Symmetry doesn't apply to erasing vector objects"
    const val OUT_OF_MEMORY = "Not enough memory for symmetry with a brush this large"
}

object PathfinderLabels {
    const val SELECT_ALL = "Select all objects"; const val HINT = "Tap shapes or paths to combine them"
    /** Visible text "Outline"; the content descriptions of the other nine are in design §3.20. */
    const val OUTLINE = "Outline shapes"
    const val STROKES_SKIPPED = "Brush strokes are skipped"; const val ARRAY_SKIPPED = "Arrayed layers are skipped"
    const val TOO_MANY = "Too many pieces: select fewer objects"; const val TOO_MANY_OPERANDS = "Select up to 12 objects"
    fun resultLayer(n: Int) = "Pathfinder $n"
    const val WORKING = "Working…"; const val NOTHING_LEFT = "Nothing is left: the shapes don't overlap"
    const val FAILED = "Pathfinder couldn't combine these shapes"; const val CHANGED = "The objects changed: try again"
    const val NO_MEMORY = "Not enough memory for Pathfinder"
    fun picked(n: Int) = if (n == 1) "1 object" else "$n objects"
    /** Content descriptions of the operations (the visible names are `HistoryLabels.PATHFINDER_OPS`). */
    const val UNITE = "Unite shapes"; const val MINUS_FRONT = "Minus front shape"; const val MINUS_BACK = "Minus back shape"
    const val INTERSECT = "Intersect shapes"; const val EXCLUDE = "Exclude overlap"; const val DIVIDE = "Divide shapes"
    const val TRIM = "Trim shapes"; const val MERGE = "Merge shapes"; const val CROP = "Crop shapes"
}

object KerningLabels {
    const val KERNING = "Kerning"; const val FONT_KERNING = "Font kerning"
    const val VERTICAL_REFUSAL = "Kerning works on horizontal text"
    const val LINE_REFUSAL = "Kerning works between two letters of a line"
    const val SCRIPT_REFUSAL = "Kerning works on left-to-right text with separate letters"
    fun between(a: String, b: String) = "Between “$a” and “$b”"
}

object ExpressionLabels {
    const val DIV_ZERO = "Can't divide by 0"; const val INVALID = "Check the expression"
    const val PLUS = "Plus"; const val MINUS = "Minus"; const val TIMES = "Times"; const val DIVIDED = "Divided by"
    const val OPEN = "Open parenthesis"; const val CLOSE = "Close parenthesis"
}

object V17Tags {
    const val POINT_GIZMO = "pointGizmo"; const val PILL_SCALE_ROW = "pillScaleRow"; const val PILL_TRASH = "pillTrash"
    const val OPERATOR_KEYS = "operatorKeys"; const val ARRAY_SHEET = "arraySheet"; const val MESH = "freeDeformMesh"
    fun folderRow(id: Long) = "folderRow_$id"; fun savedSelectionRow(id: Long) = "savedSelectionRow_$id"
}
