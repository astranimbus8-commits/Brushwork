package com.brushwork.paint.ui.editor

import com.brushwork.paint.tools.ToolId

/** One cell of the ibisPaint tool menu (v1.6 §3.7.6): a tool, or one of the three panel cells. */
sealed interface ToolMenuEntry {
    data class Tool(val id: ToolId) : ToolMenuEntry

    /** Opens the filter browser (label "Filters": the only "Filters" clickable, V11). */
    data object Filters : ToolMenuEntry

    /** Opens the Canvas panel (moved here from the v1.5 top bar). */
    data object Canvas : ToolMenuEntry

    /** Opens Settings. */
    data object Settings : ToolMenuEntry
}

/**
 * The ibisPaint tool menu's cells (v1.6 §3.7.6; drawn by `chrome/ToolMenuPanel`, which replaced
 * the v1.5 Tools sheet and its `ToolGrid`): two columns, row-major, ibisPaint's order first
 * and Brushwork's extra tools after it — 28 cells in 14 rows (v1.7), every [ToolId] exactly once,
 * plus Filters, Canvas and Settings once each, Ruler last. New tools go before Ruler (every
 * earlier cell keeps its index; Canvas stays at [14]).
 */
object ToolMenu {
    private fun t(id: ToolId) = ToolMenuEntry.Tool(id)

    val entries: List<ToolMenuEntry> = listOf(
        t(ToolId.TRANSFORM), t(ToolId.MAGIC_WAND),
        t(ToolId.LASSO), ToolMenuEntry.Filters,
        t(ToolId.BRUSH), t(ToolId.ERASER),
        t(ToolId.SMUDGE), t(ToolId.BLUR),
        t(ToolId.CLONE), t(ToolId.FILL),
        t(ToolId.CURVE), t(ToolId.TEXT),
        t(ToolId.FRAME_DIVIDER), t(ToolId.EYEDROPPER),
        ToolMenuEntry.Canvas, ToolMenuEntry.Settings,
        t(ToolId.PATH), t(ToolId.TEXT_FRAMES),
        t(ToolId.POLYLINE), t(ToolId.SHAPE),
        t(ToolId.MARQUEE), t(ToolId.OBJECT_SELECT),
        t(ToolId.MASK), t(ToolId.REMOVE),
        // v1.7: Array, Pathfinder and Symmetry (beside Ruler, its fellow drawing aid) before Ruler.
        t(ToolId.ARRAY), t(ToolId.PATHFINDER),
        t(ToolId.SYMMETRY), t(ToolId.RULER),
    )

    /** Cells per row. */
    const val COLUMNS = 2

    /** Every tool in menu order. */
    val tools: List<ToolId> get() = entries.mapNotNull { (it as? ToolMenuEntry.Tool)?.id }
}
