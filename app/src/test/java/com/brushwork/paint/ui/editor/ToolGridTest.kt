package com.brushwork.paint.ui.editor

import com.brushwork.paint.tools.LayerToolRules
import com.brushwork.paint.tools.ToolId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.5 foundation (§5.10 item 11): the sectioned tools grid (JVM). */
class ToolGridTest {
    @Test
    fun everyToolAppearsExactlyOnceAndFiltersOnce() {
        val tools = ToolGrid.tools
        assertEquals(ToolId.entries.size, tools.size)
        assertEquals(ToolId.entries.toSet(), tools.toSet())
        val filters = ToolGrid.sections.flatMap { it.entries }.count { it == ToolGridEntry.Filters }
        assertEquals(1, filters)
    }

    @Test
    fun sectionsFollowTheDesignOrder() {
        assertEquals(listOf("Paint", "Color", "Select", "Edit", "Create"), ToolGrid.sections.map { it.title })
        val paint = ToolGrid.sections[0].entries
        assertEquals(ToolGridEntry.Tool(ToolId.CLONE), paint.last())
        val edit = ToolGrid.sections[3].entries
        assertEquals(listOf(ToolGridEntry.Tool(ToolId.TRANSFORM), ToolGridEntry.Tool(ToolId.REMOVE), ToolGridEntry.Tool(ToolId.MASK), ToolGridEntry.Filters), edit)
        // Pixel-only tools are all in the grid (they carry a "px" badge in vector mode).
        assertTrue(tools().containsAll(LayerToolRules.PIXEL_ONLY))
    }

    private fun tools() = ToolGrid.tools

    @Test
    fun newToolIdsAreAppended() {
        // Ordinals of v1.4 ids are unchanged (persisted tool names / ordinals stay valid).
        assertEquals(ToolId.REMOVE.ordinal + 1, ToolId.CLONE.ordinal)
        assertEquals(ToolId.CLONE.ordinal + 1, ToolId.MASK.ordinal)
        assertEquals("Clone stamp", ToolId.CLONE.label)
        assertEquals("Masks", ToolId.MASK.label)
        // v1.6: Path and Text frames follow Masks.
        assertEquals(ToolId.MASK.ordinal + 1, ToolId.PATH.ordinal)
        assertEquals(ToolId.PATH.ordinal + 1, ToolId.TEXT_FRAMES.ordinal)
        assertEquals(ToolId.TEXT_FRAMES, ToolId.entries.last())
        assertEquals("Path", ToolId.PATH.label)
        assertEquals("Text frames", ToolId.TEXT_FRAMES.label)
    }

    @Test
    fun createSectionHoldsTheV16Tools() {
        val create = ToolGrid.sections.single { it.title == "Create" }.entries
        assertTrue(ToolGridEntry.Tool(ToolId.PATH) in create)
        assertTrue(ToolGridEntry.Tool(ToolId.TEXT_FRAMES) in create)
        // Still two rows of four tiles.
        assertEquals(8, create.size)
    }
}
