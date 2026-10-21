package com.brushwork.paint.ui.common

/**
 * v1.10 (design 3.2, F0): the test tags of the new controls. A tag is never a label
 * (LabelsV110Test, rule 3); texts found by tag are left out of the substring check (rule 4).
 */
object V110Tags {
    const val PILL_COLOR = "v110.pill.color";            const val PILL_SHARP = "v110.pill.sharp"
    const val OBJECTS_EDIT = "v110.objects.edit";        const val OBJECTS_TO_SHAPE = "v110.objects.toShape"
    const val SEL_ARRAY_BEHIND = "v110.sel.arrayBehind"; const val SEL_EDIT = "v110.sel.edit"
    const val ARRAY_COLOR_MODE = "v110.array.colorMode"; const val LAYER_STORAGE = "v110.settings.layerStorage"
    /** Prefix of the Object colors sheet's paint-mode segments (found by tag, rule 4). */
    const val COLORS_SHEET_MODE = "v110.colors.mode"
    /** "$PAINT_MODE.<shape|curve|shapeStrip|curveStrip>.<MODE>". */
    const val PAINT_MODE = "v110.paintMode"
    /** "$COLORS_SHEET_TAB.<FILL|STROKE|TEXT|OUTLINE>". */
    const val COLORS_SHEET_TAB = "v110.colors.tab"
}
