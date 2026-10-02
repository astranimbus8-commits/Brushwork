package com.brushwork.paint.ui.editor

import com.brushwork.paint.tools.LayerToolRules
import com.brushwork.paint.tools.ToolId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.6 §3.7.6: the ibisPaint tool menu's cells (JVM). It replaced the v1.5 tools grid
 * (`ToolGridTest`): every [ToolId] exactly once, Filters, Canvas and Settings once each, and new
 * tool ids stay appended.
 */
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
        // Pixel-only tools are all in the menu (they carry a "px" badge in vector mode).
        assertTrue(ToolMenu.tools.containsAll(LayerToolRules.PIXEL_ONLY))
    }

    @Test
    fun ibisPaintsOrderComesFirst() {
        val e = ToolMenu.entries
        // ibisPaint's own rows: Transform | Magic wand, Lasso | Filter, Brush | Eraser, Smudge | Blur,
        // Special Pen (Clone stamp) | Bucket, Vector (Curve) | Text, Frame divider | Eyedropper, Canvas.
        val ibis = listOf(
            ToolMenuEntry.Tool(ToolId.TRANSFORM), ToolMenuEntry.Tool(ToolId.MAGIC_WAND),
            ToolMenuEntry.Tool(ToolId.LASSO), ToolMenuEntry.Filters,
            ToolMenuEntry.Tool(ToolId.BRUSH), ToolMenuEntry.Tool(ToolId.ERASER),
            ToolMenuEntry.Tool(ToolId.SMUDGE), ToolMenuEntry.Tool(ToolId.BLUR),
            ToolMenuEntry.Tool(ToolId.CLONE), ToolMenuEntry.Tool(ToolId.FILL),
            ToolMenuEntry.Tool(ToolId.CURVE), ToolMenuEntry.Tool(ToolId.TEXT),
            ToolMenuEntry.Tool(ToolId.FRAME_DIVIDER), ToolMenuEntry.Tool(ToolId.EYEDROPPER),
            ToolMenuEntry.Canvas,
        )
        assertEquals(ibis, e.take(ibis.size))
        assertEquals(ToolMenuEntry.Settings, e[15])
        // Path beside Text frames (row 9), then Brushwork's other tools; Ruler last.
        assertEquals(ToolMenuEntry.Tool(ToolId.PATH), e[16])
        assertEquals(ToolMenuEntry.Tool(ToolId.TEXT_FRAMES), e[17])
        assertEquals(ToolMenuEntry.Tool(ToolId.RULER), e.last())
    }

    @Test
    fun newToolIdsAreAppended() {
        // Ordinals of older ids are unchanged (persisted tool names / ordinals stay valid).
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
    fun everyToolHasItsOwnGlyph() {
        // The v1.6 tools got their final glyphs (no longer the Curve / Frame divider placeholders).
        assertTrue(EditorIcons.tool(ToolId.PATH) !== EditorIcons.tool(ToolId.CURVE))
        assertTrue(EditorIcons.tool(ToolId.TEXT_FRAMES) !== EditorIcons.tool(ToolId.FRAME_DIVIDER))
        assertEquals("PathTool", EditorIcons.Path.name)
        assertEquals("TextFramesTool", EditorIcons.TextFrames.name)
    }
}
