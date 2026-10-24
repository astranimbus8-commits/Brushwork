package com.brushwork.paint.v110.a2

import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.tools.ObjectColorTarget
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.ObjectColorLabels110
import com.brushwork.paint.v110.a2.A2SheetKit.assertClosed
import com.brushwork.paint.v110.a2.A2SheetKit.openSheet
import com.brushwork.paint.v110.a2.A2SheetKit.settle
import com.brushwork.paint.v110.a2.A2SheetKit.tab
import com.brushwork.paint.v110.a2.A2SheetKit.tapIn
import com.brushwork.paint.v110.a2.A2SheetKit.tapTag
import com.brushwork.paint.v110.a2.A2SheetKit.typeIn
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.BLACK
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.BLUE
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.GREEN
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.RED
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.drag
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.hex
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.tap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.10 (R7, J21, C13; design 4.4 A2 step 7): the Object colors sheet's Cancel and Back give the
 * object back the colors the sheet opened with, and a session ended under the sheet takes its
 * live preview with it. On a reopened shape, driven as the user does (the pill's cell, a tab, the
 * hex typed: a live preview):
 * - Cancel: the sheet closes, the stroke and the drawing color (a reopened shape's own) are back;
 * - Back (the editor's: it closes the top sheet as its ✕ would): the same, for the fill;
 * - C13: a filter started while the sheet is open deactivates the tool (its ✓, keeping the
 *   preview as ✓ does), the sheet closes, the user's color comes back, and the editor the sheet
 *   held previews and cancels nothing any more: the placed shape stays as it is.
 *
 * One test in its own sandbox: Compose's frame clock only serves the first test of a sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.v110.a2.sheetcancelsandbox"])
class ObjectColorsSheetCancelTest {

    private val rig = A2ToolRig()

    @After
    fun release() = rig.release()

    private fun assertColor(what: String, expected: Int, actual: Int) = assertEquals(what, hex(expected), hex(actual))

    @Test
    fun cancelAndBackRestoreAndAnEndedSessionTakesThePreviewAway() {
        val c = rig.controller()
        c.snapping.enabled = false
        val activity = A2SheetKit.show(c)
        c.selectTool(ToolId.SHAPE)
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        tool.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE_FILL, fillColor = null, useBrushSize = false, strokeWidth = 6f) }
        c.drag(30f to 30f, 80f to 60f, 110f to 90f)
        tool.commit()
        settle()
        val layer = c.activeLayer
        val placed = requireNotNull(ShapeCodec.decode(layer.shapeData))
        c.color = GREEN
        c.tap(60f, 60f)
        settle()
        assertSame("reopened", layer, tool.editingLayer)
        assertColor("reopened: its color is the drawing color", RED, c.color)
        fun pending() = requireNotNull(tool.pendingObject()) { "the shape is open" }

        // ---- Cancel: the stroke previewed live, then given back.
        openSheet("Cancel")
        tapTag(tab(ObjectColorTarget.STROKE))
        typeIn(ObjectColorLabels110.CELL, "0000FF")
        settle()
        assertColor("Cancel: the stroke previewed", BLUE, pending().strokeColor)
        tapIn(ObjectColorLabels110.CELL, "Cancel")
        settle()
        assertClosed("Cancel")
        assertColor("Cancel: the stroke back", RED, pending().strokeColor)
        assertColor("Cancel: the drawing color back", RED, c.color)
        assertEquals("Cancel: the shape as it opened", placed, pending())

        // ---- Back: the fill previewed live, then given back.
        openSheet("Back")
        tapTag(tab(ObjectColorTarget.FILL))
        typeIn(ObjectColorLabels110.CELL, "00FF00")
        settle()
        assertColor("Back: the fill previewed", GREEN, pending().fillColor)
        activity.onBackPressedDispatcher.onBackPressed()
        settle()
        assertClosed("Back")
        assertColor("Back: the fill back", RED, pending().fillColor)
        assertEquals("Back: the shape as it opened", placed, pending())

        // ---- C13: a filter starts while the sheet previews; the tool's session ends under it.
        val editor = requireNotNull(tool.objectColors)
        openSheet("a filter")
        tapTag(tab(ObjectColorTarget.STROKE))
        typeIn(ObjectColorLabels110.CELL, "000000")
        settle()
        assertColor("a filter: the stroke previewed", BLACK, pending().strokeColor)
        c.startFilter(FilterRegistry.all.first())
        settle()
        assertFalse("the filter deactivated the tool", tool.hasPendingWork)
        assertNull("no shape open", tool.editingLayer)
        assertClosed("a filter")
        val kept = requireNotNull(ShapeCodec.decode(layer.shapeData))
        assertColor("the tool's ✓ kept the preview", BLACK, kept.strokeColor)
        assertColor("the user's color back", GREEN, c.color)
        editor.begin()
        editor.preview(ObjectColorTarget.STROKE, BLUE)
        editor.preview(ObjectColorTarget.FILL, BLUE)
        editor.cancel()
        settle()
        assertEquals("a late preview does nothing", kept, requireNotNull(ShapeCodec.decode(layer.shapeData)))
        assertColor("nor to the drawing color", GREEN, c.color)
        assertClosed("still closed")
        c.filterSession?.cancel()
        settle()
        assertEquals("the filter cancelled: the shape as kept", kept, requireNotNull(ShapeCodec.decode(layer.shapeData)))
    }
}
