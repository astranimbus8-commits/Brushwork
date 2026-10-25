package com.brushwork.paint.ui.common

/*
 * v1.10 (design 3.2, F0; I10, rule L): every new visible text of 1.10, one constant per value. A
 * label is used through its constant, never retyped (LabelsV110Test, rule 2); the texts are frozen
 * for the areas (a new label is the lead's). Texts already in LabelsV17-V19 are reused from there,
 * not declared again.
 */

object LabelsV110 {
    /**
     * Equal to an existing literal of the main sources on purpose (rule 2): the v1.9 shape fill
     * row (`VectorWidgets.kt`), the canvas fill picker (`ResizeTabs.kt`) and the text editor's color
     * rows (`TextEditorDialog.kt`) already show these texts on other screens. The Object colors
     * sheet's tabs reuse them with the same meaning.
     */
    val SHARED: Set<String> = setOf(ObjectColorLabels110.FILL, ObjectColorLabels110.TEXT, ObjectColorLabels110.OUTLINE)
}

object MirrorLabels110 {    // R1; LA4
    /** The first 3 picks of one side, counted in UiPrefs `hint.sidePicks`. */
    const val SIDE_HINT = "One side picked. Tap it again for all sides."
    /** LA4: the name of the disabled scale controls while one side is picked; a toast once per pick. */
    const val SIDE_RESIZE_BLOCKED = "Resize all sides together: tap again for all sides"
    const val SIDE_REFUSED = "This side cannot move there"
    /** The trash cell's long-press name while a side is picked, and the undo step. */
    const val DELETE_SIDE = "Delete one side"
    const val PERSPECTIVE_SEPARATE = "Perspective array copies are separate objects: each moves on its own"
}

object ObjectColorLabels110 {   // R2
    /** The pill cell that opens the Object colors sheet. */
    const val CELL = "Object colors"
    /** Tabs of the sheet; FILL, TEXT and OUTLINE are in [LabelsV110.SHARED]. */
    const val FILL = "Fill color"; const val STROKE = "Stroke color"; const val TEXT = "Text color"; const val OUTLINE = "Outline color"
    const val NO_FILL = "No fill"; const val NO_OUTLINE = "No outline"
    const val GRADIENT_REPLACED = "Picking a color replaces the gradient"
}

object ArrayLabels110 {     // R4, R5
    const val BEHIND = "Behind"
    const val BEHIND_DESC = "Array on a new layer behind"
    const val ARRAY_BEHIND_STEP = "Array behind layer"
    const val LAYER_MENU = "Array behind this layer…"
    const val BEHIND_HINT = "Copies show in front while you adjust and go behind when you let go."
    /** Replaces ArrayLabels.REMOVE on a copies-only layer (UQ11). */
    const val REMOVE_WITH_LAYER = "Remove array and its layer"
    const val TINTED = "Tinted"; const val RECOLORED = "Recolored"; const val RECOLOR_STRENGTH = "Recolor strength"
}

object CdcLabels110 {       // R6
    const val OBJECTS = "Changes the colors of the objects; they stay editable"
    const val SELECTION_WHOLE = "Shapes touching the selection change whole"
}

object PointLabels110 {     // R12
    const val MIXED_MAKES_SHARP = "Mixed: tap for sharp corners"
    const val POLYLINE_ALWAYS_SHARP = "Polyline points are always sharp"
}

object ObjectBarLabels110 { // R10, R11
    const val EDIT = "Edit"; const val EDIT_DESC = "Edit the selected object in its tool"
    const val NO_EDITOR_PARTS = "This path has several parts. Edit it with Transform"
    const val NO_EDITOR_SMALL = "This path is too small to edit"
    const val TO_SHAPE = "Turn into shape"
    const val NOTE_GRADIENT = "Gradient fill became its first color"
    const val NOTE_WIDTH = "Line thickness became even"
    const val NOTE_GENERATED = "The generator was removed; the outline is kept"
    const val NOTE_OPEN_FILL = "The fill was removed because the path is open"
    const val REFUSE_PIECES = "Paths of several separate pieces cannot become a shape"
    const val REFUSE_SMALL = "This path is too small to become a shape"
    const val REFUSE_POINTS = "This path has too many points for the Shape tool"
}

object LayersLabels110 {    // R13
    fun count(n: Int): String = if (n == 1) "1 layer" else "$n layers"
    const val GUARD = "An artwork can have at most 10,000 layers"
    const val PAYLOAD_TOO_MANY = "The share format holds at most 1000 layers. Export as PSD, or merge some layers first."
    fun canvasTooBig(w: Int, h: Int): String = "This canvas is too big for this phone's memory. The largest is about $w × $h."
    /** The new-canvas dialog's row at FULL. */
    const val NO_FIXED_LIMIT = "No fixed limit"
}

object EngineLabels110 {    // 2.11, A10
    const val LAYER_STORAGE = "Layer storage"
    const val MODE_AUTOMATIC = "Automatic (recommended)"; const val MODE_FULL = "Full: unlimited layers"
    const val MODE_CLASSIC = "Classic: as in version 1.9"
    const val SUB_FULL = "Automatic: Full"; const val SUB_TILES = "Automatic: Full, without storage swap"
    const val SUB_CLASSIC = "Automatic: Classic (%s)"; const val SUB_NO_CHECK = "Automatic: Classic until the phone check runs"
    const val SUB_FULL_CHOSEN = "Full (chosen)"; const val SUB_CLASSIC_CHOSEN = "Classic (chosen)"
    const val APPLIES_NEXT = "Applies the next time you open an artwork"
    const val CHECK_AGAIN = "Check again"; const val CHECK_PENDING = "Will check when you return to the gallery"
    const val DETAILS = "Details"; const val COPY_DETAILS = "Copy details"
    const val PREPARING_LAYER = "Preparing the layer"
    const val OPENING_LAYERS = "Opening layer %1\$d of %2\$d"
    const val CANVAS_OP_TOO_BIG = "Not enough memory to change the canvas with this many layers"
    const val STORAGE_FULL = "Not enough free storage for more layers"
    const val LOW_STORAGE = "Storage is almost full. Big projects need free space to stay fast."
    const val SWAP_READ_FAILED = "Part of a layer could not be read back and was restored from the last save"
    const val NEEDS_FULL_STORAGE = "This artwork has more layers than Classic layer storage can open."
    const val NEEDS_FULL_NO_MEMORY = "This phone does not have enough memory for it."
    const val OPEN_WITH_FULL = "Open with Full storage"
    const val FOR_THIS_PHONE = "For this phone, all artworks"
    /** The one path every notice, the README and the device checks name. */
    const val SETTINGS_PATH = "open an artwork, then Settings > Layer storage"
    const val PAINT_REFUSED = "Not enough memory to paint on this layer. Merge or delete layers"
    const val CHECK_CLASSIC_MEMORY = "This phone has little free memory, so Brushwork uses Classic layer storage (as in 1.9). To change it, $SETTINGS_PATH."
    const val CHECK_CLASSIC_SLOW = "Layers drew too slowly on this phone, so Brushwork uses Classic layer storage (as in 1.9). To change it, $SETTINGS_PATH."
    const val CHECK_TILES = "Free storage is low or slow, so Brushwork keeps all layers in memory. Free some space to allow more layers. To change it, $SETTINGS_PATH."
    const val LMK_NOTICE = "Brushwork was closed by the phone because memory ran out. Layer storage is now Classic to keep your work safe. To change it, $SETTINGS_PATH."
}

object FilterLabels110 {    // R6 (A4's lead request): the Filters sheet's rasterize banner
    const val RASTERIZES_TEXT = "Applying rasterizes this text layer"
    const val RASTERIZES_SHAPE = "Applying rasterizes this shape layer"
    const val RASTERIZES_ARRAY = "Applying rasterizes this array layer"
    /** Appended to the banner on a layer with objects; `%s` names the filters with a `colorMapper`. */
    const val KEEPS_EDITABLE = "%s keeps it editable"
}
