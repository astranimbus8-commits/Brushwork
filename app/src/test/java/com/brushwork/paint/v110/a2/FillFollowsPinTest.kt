package com.brushwork.paint.v110.a2

import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.ObjectColorEditor
import com.brushwork.paint.tools.ObjectColorFace
import com.brushwork.paint.tools.ObjectColorTarget
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.PaintMode
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.BLACK
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.BLUE
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.GREEN
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.RED
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.drag
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.frame
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.tap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 (R2, C4; design 4.4 A2 step 3): the Shape tool's Object colors editor pins the fill. A
 * stroke color keeps the fill at its current color, and the fill follows the stroke (the main
 * color) only while the two are equal; a fill color equal to the stroke follows it again. The
 * placed shape carries the colors. The edits are the object's only: when a new shape's session
 * ends the user's drawing color and fill come back, and a reopened shape gives back the user's
 * main color as v1.9 does. A fill color while the mode is Stroke turns it to Both (and a stroke
 * color while it is Fill); "No fill" is the Stroke mode; a line edits its stroke only.
 */
@RunWith(RobolectricTestRunner::class)
class FillFollowsPinTest {

    private val rig = A2ToolRig()

    @After
    fun release() = rig.release()

    private fun shapeTool(c: EditorController): ShapeTool {
        c.selectTool(ToolId.SHAPE)
        return c.tools.getValue(ToolId.SHAPE) as ShapeTool
    }

    private fun drawRect(c: EditorController, tool: ShapeTool, style: ShapeStyle = ShapeStyle.STROKE_FILL) {
        tool.update { it.copy(type = ShapeType.RECTANGLE, style = style, fillColor = null, useBrushSize = false, strokeWidth = 4f) }
        c.drag(30f to 30f, 80f to 60f, 110f to 90f)
    }

    private fun editor(tool: ShapeTool): ObjectColorEditor = tool.objectColors ?: throw AssertionError("an open shape has colors")

    @Test
    fun aStrokeColorPinsTheFillOfANewShape() {
        val c = rig.controller()
        val tool = shapeTool(c)
        assertNull("no open shape: no colors", tool.objectColors)
        drawRect(c, tool)
        val ed = editor(tool)
        assertEquals(listOf(ObjectColorTarget.FILL, ObjectColorTarget.STROKE), ed.targets)
        assertEquals(PaintMode.BOTH, ed.paintMode)
        assertEquals(ObjectColorFace.Solid(RED), ed.face(ObjectColorTarget.FILL))
        assertTrue("the fill follows the main color", tool.pendingObject()!!.fillFollowsColor)
        ed.begin()

        ed.preview(ObjectColorTarget.STROKE, BLUE)
        var o = tool.pendingObject()!!
        assertEquals(BLUE, o.strokeColor)
        assertEquals("the fill is pinned to its color", RED, o.fillColor)
        assertFalse(o.fillFollowsColor)

        ed.preview(ObjectColorTarget.FILL, BLUE)
        o = tool.pendingObject()!!
        assertEquals(BLUE, o.fillColor)
        assertTrue("a fill equal to the stroke follows it", o.fillFollowsColor)

        ed.preview(ObjectColorTarget.FILL, GREEN)
        o = tool.pendingObject()!!
        assertEquals(GREEN, o.fillColor)
        assertFalse(o.fillFollowsColor)

        ed.preview(ObjectColorTarget.STROKE, GREEN)
        assertTrue("equal again: follows", tool.pendingObject()!!.fillFollowsColor)
        ed.preview(ObjectColorTarget.STROKE, BLACK)
        o = tool.pendingObject()!!
        assertEquals(BLACK, o.strokeColor)
        assertEquals("pinned again", GREEN, o.fillColor)
        assertFalse(o.fillFollowsColor)
        assertEquals(ObjectColorFace.Solid(GREEN), ed.face(ObjectColorTarget.FILL))
        assertEquals(ObjectColorFace.Solid(BLACK), ed.face(ObjectColorTarget.STROKE))
        ed.commit()
        // A strip edit meanwhile saves the options the shape shows (its fill too).
        tool.update { it.copy(strokeWidth = 9f) }

        // ✓: the placed shape has the colors; the user's drawing color and fill come back.
        tool.commit()
        val placed = ShapeCodec.decode(c.activeLayer.shapeData)!!
        assertEquals(BLACK, placed.strokeColor)
        assertEquals(GREEN, placed.fillColor)
        assertFalse(placed.fillFollowsColor)
        frame()
        assertEquals("the user's drawing color is back", RED, c.color)
        assertNull("the user's fill is back (it follows)", tool.settings.fillColor)
        assertEquals(ShapeStyle.STROKE_FILL, tool.settings.style)
        val saved = ShapeTool(c).settings
        assertNull("saved as the user's again", saved.fillColor)
        assertEquals("the strip edit stays", 9f, saved.strokeWidth, 0f)
    }

    @Test
    fun aReopenedShapeIsPinnedAndGivesBackTheUserColor() {
        val c = rig.controller()
        val tool = shapeTool(c)
        drawRect(c, tool, ShapeStyle.STROKE)
        tool.commit()
        val layer = c.activeLayer
        c.color = BLUE
        c.tap(30f, 60f)
        assertSame("reopened", layer, tool.editingLayer)
        assertEquals("the shape's color", RED, c.color)
        val ed = editor(tool)
        assertEquals("no fill: Stroke", ObjectColorFace.None, ed.face(ObjectColorTarget.FILL))
        ed.begin()
        ed.preview(ObjectColorTarget.STROKE, GREEN)
        val o = tool.pendingObject()!!
        assertEquals(GREEN, o.strokeColor)
        assertEquals("the fill keeps its color though it is not painted", RED, o.fillColor)
        assertFalse(o.fillFollowsColor)
        assertEquals("a stroke color keeps the Stroke mode", PaintMode.STROKE, ed.paintMode)
        ed.commit()
        tool.commit()
        val placed = ShapeCodec.decode(layer.shapeData)!!
        assertEquals(GREEN, placed.strokeColor)
        assertEquals(RED, placed.fillColor)
        assertFalse(placed.fillFollowsColor)
        assertEquals("the user's main color is back", BLUE, c.color)

        // A color the user picks meanwhile is the one that comes back.
        c.tap(30f, 60f)
        assertEquals(GREEN, c.color)
        c.color = BLACK
        val ed2 = editor(tool)
        ed2.begin()
        ed2.preview(ObjectColorTarget.STROKE, RED)
        tool.commit()
        assertEquals(RED, ShapeCodec.decode(layer.shapeData)!!.strokeColor)
        assertEquals("the color picked while it was open", BLACK, c.color)
    }

    @Test
    fun theColorsSetThePaintMode() {
        val c = rig.controller()
        val tool = shapeTool(c)
        drawRect(c, tool, ShapeStyle.STROKE)
        val ed = editor(tool)
        ed.begin()
        assertEquals(PaintMode.STROKE, ed.paintMode)
        ed.preview(ObjectColorTarget.FILL, BLUE)
        assertEquals("a fill color while Stroke: Both", PaintMode.BOTH, ed.paintMode)
        assertEquals(ShapeStyle.STROKE_FILL, tool.settings.style)
        ed.setNone(ObjectColorTarget.FILL)
        assertEquals("No fill: Stroke", PaintMode.STROKE, ed.paintMode)
        assertEquals(ObjectColorFace.None, ed.face(ObjectColorTarget.FILL))
        assertEquals("the fill keeps its color", BLUE, tool.pendingObject()!!.fillColor)
        ed.setPaintMode(PaintMode.FILL)
        assertEquals(ShapeStyle.FILL, tool.settings.style)
        ed.preview(ObjectColorTarget.STROKE, GREEN)
        assertEquals("a stroke color while Fill: Both", PaintMode.BOTH, ed.paintMode)
        // Cancel: back to how the sheet opened it.
        ed.cancel()
        assertEquals(ShapeStyle.STROKE, tool.settings.style)
        assertNull(tool.settings.fillColor)
        assertEquals(RED, c.color)
        // ✕ (discard) with edits: the user's values stay.
        ed.begin()
        ed.preview(ObjectColorTarget.STROKE, GREEN)
        ed.preview(ObjectColorTarget.FILL, BLUE)
        tool.discard()
        frame()
        assertNull(tool.objectColors)
        assertEquals(RED, c.color)
        assertNull(tool.settings.fillColor)
        assertEquals(ShapeStyle.STROKE, tool.settings.style)
        // Nothing open: the editor does nothing (C13).
        ed.preview(ObjectColorTarget.STROKE, BLUE)
        ed.cancel()
        assertEquals(RED, c.color)
    }

    @Test
    fun aLineEditsItsStrokeOnly() {
        val c = rig.controller()
        val tool = shapeTool(c)
        tool.update { it.copy(type = ShapeType.LINE, useBrushSize = false, strokeWidth = 4f) }
        c.drag(30f to 30f, 80f to 60f, 110f to 90f)
        val ed = editor(tool)
        assertEquals(listOf(ObjectColorTarget.STROKE), ed.targets)
        ed.begin()
        val fill = tool.settings.fillColor
        ed.preview(ObjectColorTarget.FILL, BLUE)
        assertEquals(fill, tool.settings.fillColor)
        ed.preview(ObjectColorTarget.STROKE, BLUE)
        assertEquals(BLUE, tool.pendingObject()!!.strokeColor)
        assertEquals("a line's fill is not pinned", fill, tool.settings.fillColor)
        tool.commit()
        frame()
        assertEquals(RED, c.color)
    }
}
