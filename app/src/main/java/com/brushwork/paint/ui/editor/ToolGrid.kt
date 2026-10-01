package com.brushwork.paint.ui.editor

import com.brushwork.paint.tools.ToolId

/** One tile of the tools grid: a tool, or the Filters browser (v1.5). */
sealed interface ToolGridEntry {
    data class Tool(val id: ToolId) : ToolGridEntry
    data object Filters : ToolGridEntry
}

/** A titled group of tiles in the tools grid. */
data class ToolGridSection(val title: String, val entries: List<ToolGridEntry>)

/**
 * The tools grid of the Tools sheet (v1.5 §4.9): explicit sections in menu order, 4 tiles per
 * row (the order of [ToolId] does not matter). Every tool appears exactly once.
 */
object ToolGrid {
    private fun tools(vararg ids: ToolId): List<ToolGridEntry> = ids.map { ToolGridEntry.Tool(it) }

    val sections: List<ToolGridSection> = listOf(
        ToolGridSection("Paint", tools(ToolId.BRUSH, ToolId.ERASER, ToolId.SMUDGE, ToolId.BLUR, ToolId.CLONE)),
        ToolGridSection("Color", tools(ToolId.FILL, ToolId.EYEDROPPER)),
        ToolGridSection("Select", tools(ToolId.LASSO, ToolId.MARQUEE, ToolId.MAGIC_WAND, ToolId.OBJECT_SELECT)),
        ToolGridSection("Edit", tools(ToolId.TRANSFORM, ToolId.REMOVE, ToolId.MASK) + ToolGridEntry.Filters),
        ToolGridSection("Create", tools(ToolId.TEXT, ToolId.SHAPE, ToolId.CURVE, ToolId.POLYLINE, ToolId.FRAME_DIVIDER, ToolId.RULER)),
    )

    /** Every tool in grid order. */
    val tools: List<ToolId> get() = sections.flatMap { s -> s.entries.mapNotNull { (it as? ToolGridEntry.Tool)?.id } }
}
