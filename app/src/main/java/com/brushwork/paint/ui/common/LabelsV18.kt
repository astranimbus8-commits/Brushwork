package com.brushwork.paint.ui.common

/**
 * v1.8 (I10, design §4.8): every NEW v1.8 label, in ONE file written by the foundation (F1) and
 * frozen afterwards (rule L: the single source). Labels reused from v1.7 stay where they are
 * ("Delete point", "Refer", "Anti-alias", the selection modes); the tool names "Magnetic lasso"
 * and "Color select" are `ToolId` labels and "Cog" is `ShapeType.COG`'s. Several are longer than
 * the bare word because the bare word already names something else on the same screen.
 */
object LabelsV18 {
    /**
     * The v1.8 labels that equal a v1.7 text on purpose. Each shows on a different screen from its
     * v1.7 twin, except "RGB", a visible text only (the color picker's mode tab over the filter
     * panel; tests reach the fringe chip by its tag, [V18Tags.FRINGE_PREFIX] + "RGB").
     */
    val SHARED: Set<String> = setOf(
        ColorSelectLabels18.HIGHLIGHTS, ColorSelectLabels18.MIDTONES, ColorSelectLabels18.SHADOWS, ColorSelectLabels18.SKIN,
        FringeLabels18.RGB, CurveGenLabels18.CLOCKWISE, MenuLabels18.VIEW,
    )
}

/** Mirror copies on objects (item 1). */
object MirrorLabels18 {
    const val CLONE_TOO_MANY = "Clone stamp mirrors up to 32 copies"
    const val PERSPECTIVE_BAKED = "Perspective copies become separate paths"
    const val PERSPECTIVE_PIXELS = "Perspective copies are drawn as pixels"
    const val DETACH = "Detach mirror copies"; const val REMOVE = "Remove mirror copies"
}

/** The Array sheet's Placement and Look tabs (item 2). */
object ArrayLabels18 {
    const val PLACEMENT = "Placement"; const val LOOK = "Look"
    const val LOOK_CAPTION = "From the first copy to the last. The source keeps its look."
    const val START_OPACITY = "Start opacity"; const val END_OPACITY = "End opacity"
    const val START_SIZE = "Start size"; const val END_SIZE = "End size"
    const val START_TURN = "Start turn"; const val END_TURN = "End turn"
    /** The easing dropdown ([com.brushwork.paint.model.ArrayEasing]) and its four choices. */
    const val PACE = "Pace"; const val EVEN = "Even"; const val SPEEDS_UP = "Speeds up"; const val SLOWS_DOWN = "Slows down"; const val S_CURVE = "S-curve"
    const val TINT_COLORS = "Tint colors"; const val TINT_STRENGTH = "Tint strength"
    const val RESET_LOOK = "Reset look"
    /** The history entry of a look change. */
    const val HISTORY_LOOK = "Array look"
}

/** The Magnetic lasso strip (item 3). [SELECTION_MODE] is every selection strip's mode dropdown. */
object MagneticLabels18 {
    const val DISCARD = "Discard outline"; const val CLOSE = "Close outline"
    const val EDGE_REACH = "Edge reach"; const val EDGE_CONTRAST = "Edge contrast"; const val SNAP_TO_EDGE = "Snap to edge"
    const val SELECTION_MODE = "Selection mode"
    /** v1.8 integration: the toast, said once, when the edge map has no memory (the segments stay straight). */
    const val OUT_OF_MEMORY = "Not enough memory to find edges: the outline stays straight"
}

/** The Color select strip and its presets popup (item 3). */
object ColorSelectLabels18 {
    const val COLOR_RANGE = "Color range"; const val SAMPLED = "Sampled colors"
    const val REDS = "Reds"; const val YELLOWS = "Yellows"; const val GREENS = "Greens"
    const val CYANS = "Cyans"; const val BLUES = "Blues"; const val MAGENTAS = "Magentas"
    const val HIGHLIGHTS = "Highlights"; const val MIDTONES = "Midtones"; const val SHADOWS = "Shadows"
    const val VIVID = "Vivid colors"; const val MUTED = "Muted colors"; const val SKIN = "Skin tones"
    const val FUZZINESS = "Fuzziness"; const val SELECT_COLORS = "Select colors"
    const val USE_CURRENT = "Use the current color"; const val FROM_PICKER = "Add from the color picker"
    const val CLEAR_SAMPLES = "Clear samples"; const val INVERT_RANGE = "Invert range"
    /** v1.8 integration: the toast when the samples or the preset match no pixel (no selection step). */
    const val NO_MATCH = "No pixels match these colors"
    /** v1.8 final QA: the "Color range" chip's value for [SAMPLED] with no sample (with samples it shows their swatches). */
    const val NO_SAMPLES = "None yet"
}

/** The fringe colors of Chromatic aberration and Glitch (item 5). The preset chips' names are [RGB] .. [BLUE_YELLOW]. */
object FringeLabels18 {
    const val FRINGE_COLORS = "Fringe colors"
    const val FIRST = "First fringe color"; const val MIDDLE = "Middle fringe color"; const val LAST = "Last fringe color"
    const val NO_COLOR = "No color"
    const val RGB = "RGB"; const val RED_CYAN = "Red/Cyan"; const val MAGENTA_GREEN = "Magenta/Green"; const val BLUE_YELLOW = "Blue/Yellow"
    const val STATIC_COLOR = "Color of the static"
}

/** The generated curves of the Curve and Path tools (item 6). */
object CurveGenLabels18 {
    const val CURVES = "Curves"; const val CURVES_DESC = "Spirals and waves"
    const val ARCHIMEDEAN = "Archimedean spiral"; const val GOLDEN = "Golden spiral"; const val LOGARITHMIC = "Logarithmic spiral"
    const val FERMAT = "Fermat spiral"; const val SINE = "Sine wave"; const val ROSE = "Rose"
    const val CHANGE = "Change the generated curve"
    const val TURNS = "Turns"; const val INNER = "Inner size"; const val GROWTH = "Growth per turn"; const val CLOCKWISE = "Clockwise"
    const val WAVES = "Waves"; const val WAVE_HEIGHT = "Wave height"
    const val PETALS = "Petals"; const val PETAL_DIVISOR = "Petal divisor"
    const val POINTS_PER_TURN = "Points per turn"
    const val EDITED_HINT = "Edited by hand: the curve settings are gone"
}

/** The cog's Shape settings (item 4); [TEETH] reads like v1.7's "Number of sides" / "Number of points". */
object CogLabels18 {
    const val TEETH = "Number of teeth"; const val TOOTH_DEPTH = "Tooth depth"; const val TOOTH_WIDTH = "Tooth width"
    const val TOOTH_TAPER = "Tooth taper"; const val HOLE_SIZE = "Hole size"
    const val HOLE_REFUSAL = "Set the hole size to 0 to edit points"
    const val COMPOUND_HINT = "The hole makes a compound path"
}

object TransformLabels18 {
    /** Free deform's mesh chip, whose visible text is the size ("3 × 3"). */
    const val MESH_SIZE = "Mesh size"
    /**
     * v1.8 integration: the More-row hint while a box-mode transform (Free, Distort) is active.
     * A drag off the handles moves the content; a corner or side handle scales it (Free) or moves
     * that corner or side (Distort); the round handle turns it.
     */
    const val BOX_HINT = "Drag to move; drag a handle to reshape or turn"
    /**
     * v1.8 polish: the caption over the current interpolation on "Choose interpolation" (the
     * More row of Transform), so the button says what it chooses.
     */
    const val INTERPOLATION_CAPTION = "Interpolation"
}

/** The two-row tool strip (item 7). */
object StripLabels18 {
    const val MORE_TOGGLE = "Show more tool options"; const val EXPANDED = "Expanded"; const val COLLAPSED = "Collapsed"
    const val MORE_OBJECT = "Show more object actions"
}

/** The More menu's sections (item 8). */
object MenuLabels18 {
    const val CLIPBOARD = "Clipboard"; const val BRING_IN = "Bring in"; const val SEND_OUT = "Send out"
    const val VIEW = "View"; const val THIS_ARTWORK = "This artwork"
}

/** v1.8 test tags (semantics handles that are not labels). */
object V18Tags {
    const val STRIP_ROW1 = "v18.strip.row1"; const val STRIP_MORE_ROW = "v18.strip.more"; const val STRIP_TOGGLE = "v18.strip.toggle"
    const val PILL_SEVERAL = "v18.pill.several"; const val ARRAY_LOOK = "v18.array.look"
    /** + a fringe preset's name ([FringeLabels18.RGB] ...). */
    const val FRINGE_PREFIX = "v18.fringe."
    /** + a More menu section's label. */
    const val MENU_SECTION_PREFIX = "v18.menu.section."
    const val LONG_PRESS_BUBBLE = "v18.longpress"
}
