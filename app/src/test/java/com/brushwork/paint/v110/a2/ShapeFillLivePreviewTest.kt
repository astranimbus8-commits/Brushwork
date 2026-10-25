package com.brushwork.paint.v110.a2

import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.ObjectColorEditor
import com.brushwork.paint.tools.ObjectColorTarget
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.BLUE
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.GREEN
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.RED
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.composite
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.drag
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.frame
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.hex
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.tap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 (R7 (e); design 4.4 A2 step 3): a color change of the open shape is shown on the canvas
 * live. The canvas's pixels follow each preview of the fill and of the stroke, of a new shape and
 * of a reopened one; Cancel puts back the pixels the sheet opened with; ✓ keeps the previewed
 * colors in the shape's ONE step (no step of their own); erasing hides the editor.
 */
@RunWith(RobolectricTestRunner::class)
class ShapeFillLivePreviewTest {

    private val rig = A2ToolRig()

    @After
    fun release() = rig.release()

    private fun shapeTool(c: EditorController): ShapeTool {
        c.selectTool(ToolId.SHAPE)
        return c.tools.getValue(ToolId.SHAPE) as ShapeTool
    }

    private fun editor(tool: ShapeTool): ObjectColorEditor = tool.objectColors ?: throw AssertionError("an open shape has colors")

    /** The canvas at the rectangle's inside (60, 60) and on its outline (30, 60). */
    private fun inside(c: EditorController) = c.composite().getPixel(60, 60)
    private fun outline(c: EditorController) = c.composite().getPixel(30, 60)

    private fun assertColor(what: String, expected: Int, actual: Int) = assertEquals(what, hex(expected), hex(actual))

    @Test
    fun aNewShapeShowsEachPreviewAndCancelPutsItBack() {
        val c = rig.controller()
        val tool = shapeTool(c)
        tool.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE_FILL, fillColor = null, useBrushSize = false, strokeWidth = 6f) }
        c.drag(30f to 30f, 80f to 60f, 110f to 90f)
        assertColor("the fill as drawn", RED, inside(c))
        val ed = editor(tool)
        ed.begin()
        ed.preview(ObjectColorTarget.FILL, BLUE)
        assertColor("the fill preview is on the canvas", BLUE, inside(c))
        assertColor("the outline keeps its color", RED, outline(c))
        ed.preview(ObjectColorTarget.STROKE, GREEN)
        assertColor("the stroke preview is on the canvas", GREEN, outline(c))
        assertColor("the fill stays", BLUE, inside(c))
        ed.cancel()
        assertColor("Cancel: the fill is back", RED, inside(c))
        assertColor("Cancel: the outline is back", RED, outline(c))

        // ✓ keeps the previewed colors in the shape's one step.
        val steps = c.undoManager.undoCount
        ed.begin()
        ed.preview(ObjectColorTarget.FILL, GREEN)
        ed.commit()
        assertEquals("no step of its own", steps, c.undoManager.undoCount)
        tool.commit()
        assertEquals("the shape's one step", steps + 1, c.undoManager.undoCount)
        frame()
        assertColor("placed with the previewed fill", GREEN, inside(c))
        assertColor("the outline as drawn", RED, outline(c))
        c.undo()
        assertColor("one undo takes the shape back", 0, inside(c))
    }

    @Test
    fun aReopenedShapeShowsThePreviewAndTheCheckIsOneStep() {
        val c = rig.controller()
        val tool = shapeTool(c)
        tool.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE_FILL, fillColor = null, useBrushSize = false, strokeWidth = 6f) }
        c.drag(30f to 30f, 80f to 60f, 110f to 90f)
        tool.commit()
        val layer = c.activeLayer
        c.tap(60f, 60f)
        assertSame("reopened", layer, tool.editingLayer)
        assertColor("as placed", RED, inside(c))
        val ed = editor(tool)
        ed.begin()
        ed.preview(ObjectColorTarget.FILL, BLUE)
        assertColor("the fill preview is on the canvas", BLUE, inside(c))
        ed.preview(ObjectColorTarget.FILL, GREEN)
        assertColor("each preview", GREEN, inside(c))
        ed.cancel()
        assertColor("Cancel: back", RED, inside(c))
        ed.begin()
        ed.preview(ObjectColorTarget.STROKE, BLUE)
        assertColor("the stroke preview is on the canvas", BLUE, outline(c))
        assertColor("the fill is pinned", RED, inside(c))
        ed.commit()
        val steps = c.undoManager.undoCount
        tool.commit()
        assertEquals("the edit's one step", steps + 1, c.undoManager.undoCount)
        assertEquals(BLUE, ShapeCodec.decode(layer.shapeData)!!.strokeColor)
        assertColor("kept", BLUE, outline(c))
        c.undo()
        assertColor("one undo takes the color back", RED, outline(c))
        assertEquals(RED, ShapeCodec.decode(layer.shapeData)!!.strokeColor)

        // Erasing: no editor (the pill's cell hides).
        c.tap(60f, 60f)
        assertSame(layer, tool.editingLayer)
        tool.setErase(true)
        assertNull(tool.objectColors)
        tool.setErase(false)
    }
}
