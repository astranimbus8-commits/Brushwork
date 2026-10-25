package com.brushwork.paint.v110.a2

import android.os.Looper
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ObjectColorEditor
import com.brushwork.paint.tools.ObjectColorFace
import com.brushwork.paint.tools.ObjectColorTarget
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.ui.vector.ObjectColorExtras
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.BLACK
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.BLUE
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.GREEN
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.RED
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.drag
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.frame
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.hex
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.tap
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * v1.10 (R2 (e), R7, J21; design 4.4 A2 step 7): the Object colors editor and Undo.
 * - The colors make no step of their own: the object's ✓ is ONE step carrying them (Undo takes
 *   the object back as it was, colors and all; Redo brings it back with them).
 * - An in-tool Undo (an edit of the open object: a nudge) takes back that edit only; the colors
 *   picked stay on the object.
 * - Undo at the bottom of the object's steps closes it without the colors (a reopened object as
 *   it was placed, a new one gone), the user's drawing color back, and no document step undone.
 * - Undo while the Object colors sheet is open (or minimized) first closes it with Cancel, then
 *   undoes as usual; a sheet closed by OK, or by the end of its object's session, leaves Undo
 *   to the app (the controller's `inToolHistory` given back).
 * - Review fix (8/8): when the previewed colors were the object's only change, that Undo closes
 *   the object as it was opened and undoes no document step (before, the Cancel left an untouched
 *   object, whose usual Undo also took back the document's step before it: the shape's placing);
 *   with nothing previewed, Undo stays v1.9's (an untouched object does not swallow it).
 *
 * For a reopened shape, a new shape and a reopened path (the Polyline tool). The editor is driven
 * as the sheet drives it (`begin`, `preview`, OK's `commit`); the sheet's own tests drive the UI.
 */
@RunWith(RobolectricTestRunner::class)
class ObjectColorsUndoTest {

    private val rig = A2ToolRig()

    @After
    fun release() = rig.release()

    private fun settle() {
        frame()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun ObjectColorEditor?.open(): ObjectColorEditor = this ?: throw AssertionError("an open object has colors")

    private fun shapeOf(layer: Layer): ShapeObject = requireNotNull(ShapeCodec.decode(layer.shapeData)) { "no shape in ${layer.name}" }

    /** The sheet's way: its start values, two colors previewed, OK. */
    private fun ObjectColorEditor.pick(stroke: Int, fill: Int) {
        begin()
        preview(ObjectColorTarget.STROKE, stroke)
        preview(ObjectColorTarget.FILL, fill)
        commit()
    }

    private fun shapeTool(c: com.brushwork.paint.EditorController): ShapeTool {
        c.selectTool(ToolId.SHAPE)
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        tool.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE_FILL, fillColor = null, useBrushSize = false, strokeWidth = 6f) }
        return tool
    }

    @Test
    fun aReopenedShapeKeepsItsColorsThroughAnInToolUndoAndClosesWithoutThemAtTheBottom() {
        val c = rig.controller()
        val tool = shapeTool(c)
        c.drag(30f to 30f, 80f to 60f, 110f to 90f)
        tool.commit()
        settle()
        val layer = c.activeLayer
        val placed = shapeOf(layer)
        c.color = GREEN
        c.tap(60f, 60f)
        settle()
        assertSame("reopened", layer, tool.editingLayer)
        val opened = requireNotNull(tool.pendingObject())
        tool.nudge(1, 0)
        assertNotEquals("nudged", opened.cx, requireNotNull(tool.pendingObject()).cx)
        tool.objectColors.open().pick(stroke = BLUE, fill = BLACK)
        val steps = c.undoManager.undoCount

        // In-tool Undo: the nudge goes, the colors stay.
        c.undo()
        settle()
        assertSame("Undo of the nudge: still open", layer, tool.editingLayer)
        val back = requireNotNull(tool.pendingObject())
        assertEquals("the nudge is taken back", opened.cx, back.cx, 1e-3f)
        assertEquals("the stroke picked stays", hex(BLUE), hex(back.strokeColor))
        assertEquals("the fill picked stays", hex(BLACK), hex(back.fillColor))
        assertEquals("no document step", steps, c.undoManager.undoCount)

        // At the bottom of its steps: closed without the colors, the user's green back.
        c.undo()
        settle()
        assertFalse("Undo at the bottom closes the shape", tool.hasPendingWork)
        assertNull("no shape open", tool.editingLayer)
        assertEquals("the shape as it was placed", placed, shapeOf(layer))
        assertEquals("the user's drawing color", hex(GREEN), hex(c.color))
        assertEquals("no document step undone", steps, c.undoManager.undoCount)
        assertNull("no colors to edit", tool.objectColors)
    }

    @Test
    fun undoWhileTheSheetIsOpenFirstClosesItWithCancel() {
        val c = rig.controller()
        val tool = shapeTool(c)
        c.drag(30f to 30f, 80f to 60f, 110f to 90f)
        tool.commit()
        settle()
        val layer = c.activeLayer
        c.color = GREEN
        c.tap(60f, 60f)
        settle()
        assertSame("reopened", layer, tool.editingLayer)
        val opened = requireNotNull(tool.pendingObject())
        tool.nudge(1, 0)
        val ed = tool.objectColors.open()
        val sheet = (ed as ObjectColorExtras).sheet
        // The pill's cell opens the sheet; the sheet takes its start values and previews (open or
        // minimized alike: the state is the editor's).
        sheet.open()
        assertTrue(sheet.isOpen)
        ed.begin()
        ed.preview(ObjectColorTarget.STROKE, BLUE)
        assertEquals(hex(BLUE), hex(requireNotNull(tool.pendingObject()).strokeColor))
        val steps = c.undoManager.undoCount

        c.undo()
        settle()
        assertFalse("Undo closed the sheet", sheet.isOpen)
        assertNull("and gave Undo back", c.inToolHistory)
        val back = requireNotNull(tool.pendingObject())
        assertEquals("with Cancel: the stroke as it opened", hex(RED), hex(back.strokeColor))
        assertEquals("then undid as usual: the nudge", opened.cx, back.cx, 1e-3f)
        assertSame("still open", layer, tool.editingLayer)
        assertEquals("no document step", steps, c.undoManager.undoCount)

        // Closed by its OK, the sheet leaves Undo alone: an in-tool Undo keeps the colors kept.
        tool.nudge(0, 1)
        sheet.open()
        ed.begin()
        ed.preview(ObjectColorTarget.FILL, BLACK)
        ed.commit()
        sheet.close()
        assertNull("OK gave Undo back", c.inToolHistory)
        c.undo()
        settle()
        assertEquals("the fill kept", hex(BLACK), hex(requireNotNull(tool.pendingObject()).fillColor))
        assertEquals("the nudge taken back", opened.cy, requireNotNull(tool.pendingObject()).cy, 1e-3f)

        // The object's session ending under the open sheet (✓) takes the hook with it.
        sheet.open()
        tool.commit()
        settle()
        assertFalse(sheet.isOpen)
        assertNull("✓ gave Undo back", c.inToolHistory)
        assertEquals("the user's drawing color", hex(GREEN), hex(c.color))
    }

    @Test
    fun undoWithTheSheetOpenOnAnOtherwiseUntouchedShapeClosesItAndUndoesNoDocumentStep() {
        val c = rig.controller()
        val tool = shapeTool(c)
        c.drag(30f to 30f, 80f to 60f, 110f to 90f)
        tool.commit()
        settle()
        val layer = c.activeLayer
        val placed = shapeOf(layer)
        val layers = c.doc.layers.size
        c.color = GREEN
        c.tap(60f, 60f)
        settle()
        assertSame("reopened", layer, tool.editingLayer)
        val ed = tool.objectColors.open()
        val sheet = (ed as ObjectColorExtras).sheet
        sheet.open()
        ed.begin()
        ed.preview(ObjectColorTarget.STROKE, BLUE)
        ed.preview(ObjectColorTarget.FILL, BLACK)
        assertEquals(hex(BLUE), hex(requireNotNull(tool.pendingObject()).strokeColor))
        val steps = c.undoManager.undoCount

        c.undo()
        settle()
        assertFalse("Undo closed the sheet", sheet.isOpen)
        assertNull("and gave Undo back", c.inToolHistory)
        assertNull("the shape closed", tool.editingLayer)
        assertFalse(tool.hasPendingWork)
        assertEquals("as it was placed: no colors", placed, shapeOf(layer))
        assertEquals("no document step undone (the shape's placing stays)", steps, c.undoManager.undoCount)
        assertEquals("the shape's layer stays", layers, c.doc.layers.size)
        assertTrue(c.doc.layers.contains(layer))
        assertEquals("the user's drawing color", hex(GREEN), hex(c.color))

        // With nothing previewed the sheet changes nothing about Undo (v1.9 R17): an untouched
        // reopened shape closes and the document's step before it is undone, in one Undo.
        c.tap(60f, 60f)
        settle()
        assertSame("reopened again", layer, tool.editingLayer)
        val ed2 = tool.objectColors.open()
        val sheet2 = (ed2 as ObjectColorExtras).sheet
        sheet2.open()
        ed2.begin()
        c.undo()
        settle()
        assertFalse(sheet2.isOpen)
        assertNull(c.inToolHistory)
        assertNull("the shape closed", tool.editingLayer)
        assertEquals("then undid as usual: the shape's placing", steps - 1, c.undoManager.undoCount)
    }

    @Test
    fun undoWithTheSheetOpenOnAnOtherwiseUntouchedTextClosesItAndUndoesNoDocumentStep() {
        val c = rig.controller(400, 300)
        c.viewTransform.set(android.graphics.Matrix())
        c.snapping.enabled = false
        c.selectTool(ToolId.TEXT)
        val tool = c.tools.getValue(ToolId.TEXT) as TextTool
        c.tap(200f, 150f)
        assertTrue("the editor opened for a new text", tool.editorOpen && tool.editingNew)
        tool.setText("HH")
        tool.updateSpec { it.copy(sizePx = 80f, color = BLACK, strokeWidthPx = 0f) }
        tool.confirmEditor()
        assertTrue(tool.commitItem())
        settle()
        val layer = c.activeLayer
        assertTrue(layer.isTextLayer)
        val placed = TextCodec.decodeAll(layer.textData).single().spec
        val layers = c.doc.layers.size
        c.tap(200f, 150f)
        settle()
        assertNotNull("the tap opens the text", tool.item)
        val ed = tool.objectColors.open()
        val sheet = (ed as ObjectColorExtras).sheet
        sheet.open()
        assertTrue(sheet.isOpen)
        ed.begin()
        ed.preview(ObjectColorTarget.TEXT, GREEN)
        ed.preview(ObjectColorTarget.OUTLINE, BLUE)
        val steps = c.undoManager.undoCount

        c.undo()
        settle()
        assertFalse("Undo closed the sheet", sheet.isOpen)
        assertNull("and gave Undo back", c.inToolHistory)
        assertNull("the text closed", tool.item)
        assertEquals("as it was placed: no colors", placed, TextCodec.decodeAll(layer.textData).single().spec)
        assertEquals("no document step undone (the text's placing stays)", steps, c.undoManager.undoCount)
        assertEquals("the text's layer stays", layers, c.doc.layers.size)
        assertEquals("the drawing color never changed", hex(RED), hex(c.color))
    }

    @Test
    fun undoWithTheSheetOpenOnAnOtherwiseUntouchedPathClosesItAndUndoesNoDocumentStep() {
        val c = rig.controller()
        c.snapping.enabled = false
        c.selectTool(ToolId.POLYLINE)
        val tool = c.tools.getValue(ToolId.POLYLINE) as CurveTool
        tool.update { it.copy(stroke = CurveStroke.PLAIN, plainWidth = 8f, useBrushSize = false, fill = true, fillColor = null, closed = true, ownLayer = true) }
        for (p in listOf(Vec2(40f, 40f), Vec2(160f, 40f), Vec2(160f, 160f), Vec2(40f, 160f))) assertTrue(tool.addAnchor(p))
        tool.deselect()
        tool.commit()
        settle()
        val layer = c.doc.activeLayer
        assertTrue("a vector layer of its own", layer.isVectorLayer)
        fun placed() = layer.vector!!.objects.single() as VPath
        val before = placed()
        val layers = c.doc.layers.size
        c.color = GREEN
        assertTrue(tool.reopen(before))
        settle()
        tool.deselect()
        val ed = tool.objectColors.open()
        val sheet = (ed as ObjectColorExtras).sheet
        sheet.open()
        ed.begin()
        ed.preview(ObjectColorTarget.STROKE, BLUE)
        ed.preview(ObjectColorTarget.FILL, BLACK)
        settle()
        val steps = c.undoManager.undoCount

        c.undo()
        settle()
        assertFalse("Undo closed the sheet", sheet.isOpen)
        assertNull("and gave Undo back", c.inToolHistory)
        assertFalse("the path closed", tool.isReopened)
        assertEquals("as it was placed: no colors", before, placed())
        assertEquals("no document step undone (the path's placing stays)", steps, c.undoManager.undoCount)
        assertEquals("the path's layer stays", layers, c.doc.layers.size)
        assertEquals("the user's drawing color", hex(GREEN), hex(c.color))
    }

    @Test
    fun aShapesCheckIsOneStepCarryingTheColors() {
        val c = rig.controller()
        val tool = shapeTool(c)
        c.drag(30f to 30f, 80f to 60f, 110f to 90f)
        tool.commit()
        settle()
        val layer = c.activeLayer
        val placed = shapeOf(layer)
        c.tap(60f, 60f)
        settle()
        tool.nudge(0, 1)
        tool.objectColors.open().pick(stroke = BLUE, fill = GREEN)
        val steps = c.undoManager.undoCount
        tool.commit()
        settle()
        assertEquals("✓ is one step", steps + 1, c.undoManager.undoCount)
        val edited = shapeOf(layer)
        assertEquals(hex(BLUE), hex(edited.strokeColor))
        assertEquals(hex(GREEN), hex(edited.fillColor))

        c.undo()
        settle()
        assertEquals("one Undo: the shape as it was, colors and place", placed, shapeOf(layer))
        assertEquals(steps, c.undoManager.undoCount)
        c.redo()
        settle()
        assertEquals("Redo: the edit with its colors", edited, shapeOf(layer))
        assertEquals("the user's drawing color", hex(RED), hex(c.color))
    }

    @Test
    fun aNewShapeKeepsItsColorsThroughAnInToolUndoAndIsDiscardedAtTheBottom() {
        val c = rig.controller()
        val tool = shapeTool(c)
        val steps = c.undoManager.undoCount
        val layers = c.doc.layers.size
        c.drag(30f to 30f, 80f to 60f, 110f to 90f)
        settle()
        assertTrue("a new shape", tool.hasPendingWork)
        val drawn = requireNotNull(tool.pendingObject())
        tool.objectColors.open().pick(stroke = BLUE, fill = BLACK)
        tool.nudge(1, 0)

        c.undo()
        settle()
        assertTrue("Undo of the nudge: still open", tool.hasPendingWork)
        val back = requireNotNull(tool.pendingObject())
        assertEquals("the nudge is taken back", drawn.cx, back.cx, 1e-3f)
        assertEquals("the stroke picked stays", hex(BLUE), hex(back.strokeColor))
        assertEquals("the fill picked stays", hex(BLACK), hex(back.fillColor))

        c.undo()
        settle()
        assertFalse("Undo at the bottom discards the new shape", tool.hasPendingWork)
        assertEquals("nothing placed", layers, c.doc.layers.size)
        assertEquals("no document step", steps, c.undoManager.undoCount)
        assertEquals("the user's drawing color", hex(RED), hex(c.color))
        assertEquals("the user's fill: the main color again", null, tool.settings.fillColor)
    }

    @Test
    fun aReopenedPathKeepsItsColorsThroughAnInToolUndoAndClosesWithoutThemAtTheBottom() {
        val c = rig.controller()
        c.snapping.enabled = false
        c.selectTool(ToolId.POLYLINE)
        val tool = c.tools.getValue(ToolId.POLYLINE) as CurveTool
        tool.update { it.copy(stroke = CurveStroke.PLAIN, plainWidth = 8f, useBrushSize = false, fill = true, fillColor = null, closed = true, ownLayer = true) }
        for (p in listOf(Vec2(40f, 40f), Vec2(160f, 40f), Vec2(160f, 160f), Vec2(40f, 160f))) assertTrue(tool.addAnchor(p))
        tool.deselect()
        tool.commit()
        settle()
        val layer = c.doc.activeLayer
        assertTrue("a vector layer of its own", layer.isVectorLayer)
        fun placed() = layer.vector!!.objects.single() as VPath
        val before = placed()
        c.color = GREEN
        assertTrue(tool.reopen(before))
        settle()
        tool.deselect()
        val opened = tool.anchors.map { it.pos }
        tool.nudge(1, 0)
        assertNotEquals("nudged", opened, tool.anchors.map { it.pos })
        val ed = tool.objectColors.open()
        ed.pick(stroke = BLUE, fill = BLACK)
        val steps = c.undoManager.undoCount

        c.undo()
        settle()
        assertTrue("Undo of the nudge: still open", tool.isReopened)
        assertEquals("the nudge is taken back", opened, tool.anchors.map { it.pos })
        assertEquals("the line picked stays", ObjectColorFace.Solid(BLUE), ed.face(ObjectColorTarget.STROKE))
        assertEquals("the fill picked stays", ObjectColorFace.Solid(BLACK), ed.face(ObjectColorTarget.FILL))
        assertEquals("no document step", steps, c.undoManager.undoCount)

        c.undo()
        settle()
        assertFalse("Undo at the bottom closes the path", tool.isReopened)
        assertEquals("the path as it was placed", before, placed())
        assertEquals("its line color", hex(RED), hex(placed().stroke!!.color))
        assertEquals("its fill", VPaint.Solid(RED), placed().fill)
        assertEquals("the user's drawing color", hex(GREEN), hex(c.color))
        assertEquals("no document step undone", steps, c.undoManager.undoCount)
    }
}
