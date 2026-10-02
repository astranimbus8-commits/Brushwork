package com.brushwork.paint.ui.editor

import com.brushwork.paint.tools.ToolId
import org.junit.Assert.assertEquals
import org.junit.Test

/** v1.6 foundation (§3.7.6): the ibisPaint tool menu's cells (JVM; area E builds the menu from them). */
class ToolMenuTest {
    @Test
    fun everyToolOnceAndThePanelCellsOnce() {
        val tools = ToolMenu.tools
        assertEquals(ToolId.entries.size, tools.size)
        assertEquals(ToolId.entries.toSet(), tools.toSet())
        for (cell in listOf(ToolMenuEntry.Filters, ToolMenuEntry.Canvas, ToolMenuEntry.Settings)) {
            assertEquals("$cell once", 1, ToolMenu.entries.count { it == cell })
        }
        assertEquals("25 cells", 25, ToolMenu.entries.size)
        assertEquals("13 rows of two", 13, (ToolMenu.entries.size + ToolMenu.COLUMNS - 1) / ToolMenu.COLUMNS)
    }

    @Test
    fun ibisPaintsOrderComesFirst() {
        val e = ToolMenu.entries
        assertEquals(ToolMenuEntry.Tool(ToolId.TRANSFORM), e[0])
        assertEquals(ToolMenuEntry.Tool(ToolId.MAGIC_WAND), e[1])
        assertEquals(ToolMenuEntry.Filters, e[3])
        assertEquals(ToolMenuEntry.Canvas, e[14])
        assertEquals(ToolMenuEntry.Settings, e[15])
        assertEquals(ToolMenuEntry.Tool(ToolId.PATH), e[16])
        assertEquals(ToolMenuEntry.Tool(ToolId.TEXT_FRAMES), e[17])
        assertEquals(ToolMenuEntry.Tool(ToolId.RULER), e.last())
    }
}
