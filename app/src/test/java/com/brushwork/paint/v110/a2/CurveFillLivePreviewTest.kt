package com.brushwork.paint.v110.a2

import android.os.Looper
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.ObjectColorEditor
import com.brushwork.paint.tools.ObjectColorFace
import com.brushwork.paint.tools.ObjectColorTarget
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurvePaint
import com.brushwork.paint.tools.vector.CurveSettings
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.common.CurveLabels17
import com.brushwork.paint.ui.common.ObjectColorLabels110
import com.brushwork.paint.ui.common.PaintMode
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.BLACK
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.BLUE
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.GREEN
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.RED
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.composite
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.frame
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.hex
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStop
import com.brushwork.paint.vector.VSubpath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * v1.10 (R7 (e); design 4.4 A2 step 4): a color change of the path open in the Curve, Polyline
 * or Path tool is shown on the canvas live. The canvas's pixels follow each preview of the fill and
 * of the line, for a new path of each kind and for a reopened path object; Cancel puts back the
 * pixels the sheet opened with; ✓ keeps the previewed colors in the path's ONE step (no step of
 * their own) and the user's drawing color and options come back. Fill needs 3 points, and a line
 * color while the mode is Fill turns it to Both.
 */
@RunWith(RobolectricTestRunner::class)
class CurveFillLivePreviewTest {

    private val rig = A2ToolRig()

    @After
    fun release() = rig.release()

    private val square = arrayOf(Vec2(40f, 40f), Vec2(160f, 40f), Vec2(160f, 160f), Vec2(40f, 160f))

    private fun look(own: Boolean = false): (CurveSettings) -> CurveSettings =
        { it.copy(stroke = CurveStroke.PLAIN, plainWidth = 8f, useBrushSize = false, fill = true, fillColor = null, closed = true, ownLayer = own) }

    private fun settle() {
        frame()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Tool [id] set up by [look] with the points [pts] (none selected). */
    private fun EditorController.drawing(id: ToolId, pts: Array<Vec2>, look: (CurveSettings) -> CurveSettings = look()): CurveTool {
        selectTool(id)
        val t = tools.getValue(id) as CurveTool
        t.update(look)
        for (p in pts) assertTrue("$id adds $p", t.addAnchor(p))
        t.deselect()
        settle()
        return t
    }

    private fun editor(tool: CurveTool): ObjectColorEditor = tool.objectColors ?: throw AssertionError("an open path has colors")

    private fun inside(c: EditorController) = c.composite().getPixel(100, 100)
    private fun outline(c: EditorController) = c.composite().getPixel(100, 40)
    private fun assertColor(what: String, expected: Int, actual: Int) = assertEquals(what, hex(expected), hex(actual))

    @Test
    fun aNewPathOfEachKindShowsEachPreviewAndCancelPutsItBack() {
        for (id in listOf(ToolId.POLYLINE, ToolId.CURVE, ToolId.PATH)) {
            val c = rig.controller()
            c.snapping.enabled = false
            val tool = c.drawing(id, square)
            assertColor("$id: the fill as drawn", RED, inside(c))
            val ed = editor(tool)
            assertEquals(listOf(ObjectColorTarget.FILL, ObjectColorTarget.STROKE), ed.targets)
            assertEquals(PaintMode.BOTH, ed.paintMode)
            ed.begin()
            ed.preview(ObjectColorTarget.FILL, BLUE)
            assertColor("$id: the fill preview is on the canvas", BLUE, inside(c))
            ed.preview(ObjectColorTarget.STROKE, GREEN)
            assertColor("$id: the fill is pinned", BLUE, inside(c))
            if (id == ToolId.POLYLINE) assertColor("the line preview is on the canvas", GREEN, outline(c))
            ed.cancel()
            assertColor("$id: Cancel: the fill is back", RED, inside(c))
            if (id == ToolId.POLYLINE) assertColor("Cancel: the line is back", RED, outline(c))
            assertEquals(RED, c.color)

            // ✓ keeps the previewed colors in the path's one step; the user's color and options come back.
            val steps = c.undoManager.undoCount
            ed.begin()
            ed.preview(ObjectColorTarget.FILL, GREEN)
            ed.preview(ObjectColorTarget.STROKE, BLACK)
            ed.commit()
            assertEquals("$id: no step of its own", steps, c.undoManager.undoCount)
            tool.commit()
            settle()
            assertEquals("$id: the path's one step", steps + 1, c.undoManager.undoCount)
            assertColor("$id: painted with the previewed fill", GREEN, inside(c))
            if (id == ToolId.POLYLINE) assertColor("painted with the previewed line", BLACK, outline(c))
            assertNull("$id: no path open", tool.objectColors)
            assertEquals("$id: the user's drawing color is back", hex(RED), hex(c.color))
            assertNull("$id: the user's fill is back", tool.settings.fillColor)
            c.undo()
            assertColor("$id: one undo takes the path back", 0, inside(c))
        }
    }

    @Test
    fun fillNeedsThreePointsAndALineColorBringsTheLineBack() {
        val c = rig.controller()
        c.snapping.enabled = false
        val tool = c.drawing(ToolId.CURVE, arrayOf(Vec2(40f, 40f), Vec2(160f, 40f)))
        val ed = editor(tool)
        ed.begin()
        assertEquals(CurveLabels17.FILL_NEEDS_3, ed.unavailable(ObjectColorTarget.FILL))
        assertNull(ed.unavailable(ObjectColorTarget.STROKE))
        val before = tool.settings
        ed.preview(ObjectColorTarget.FILL, BLUE)
        ed.setPaintMode(PaintMode.FILL)
        assertEquals("a refused fill changes nothing", before, tool.settings)
        assertTrue(tool.addAnchor(Vec2(160f, 160f)))
        assertNull("3 points: fill possible", ed.unavailable(ObjectColorTarget.FILL))
        ed.setPaintMode(PaintMode.FILL)
        assertEquals(CurvePaint.FILL, tool.paintMode)
        assertEquals(CurveStroke.NONE, tool.settings.stroke)
        ed.preview(ObjectColorTarget.STROKE, GREEN)
        assertEquals("a line color while Fill: Both", CurvePaint.BOTH, tool.paintMode)
        assertEquals("with the kind it had", CurveStroke.PLAIN, tool.settings.stroke)
        ed.setNone(ObjectColorTarget.FILL)
        assertEquals("No fill: Stroke", CurvePaint.STROKE, tool.paintMode)
        assertEquals(ObjectColorFace.None, ed.face(ObjectColorTarget.FILL))
        ed.preview(ObjectColorTarget.FILL, BLUE)
        assertEquals("a fill color while Stroke: Both", CurvePaint.BOTH, tool.paintMode)
        ed.cancel()
        assertEquals(before.fill, tool.settings.fill)
        assertEquals(before.stroke, tool.settings.stroke)
        assertEquals(before.fillColor, tool.settings.fillColor)
        assertEquals(RED, c.color)
        // ✕: the user's options and color stay theirs.
        tool.discard()
        settle()
        assertEquals(RED, c.color)
        assertEquals(before.fillColor, tool.settings.fillColor)
        assertEquals(CurveStroke.PLAIN, tool.settings.stroke)
    }

    @Test
    fun aReopenedPathShowsThePreviewAndGivesBackTheUserColor() {
        val c = rig.controller()
        c.snapping.enabled = false
        val tool = c.drawing(ToolId.POLYLINE, square, look(own = true))
        tool.commit()
        settle()
        val layer = c.doc.activeLayer
        assertTrue("a vector layer of its own", layer.isVectorLayer)
        val path = layer.vector!!.objects.single() as VPath
        c.color = BLUE
        assertTrue(tool.reopen(path))
        settle()
        assertEquals("the path's line color", hex(RED), hex(c.color))
        assertColor("as placed", RED, inside(c))
        val ed = editor(tool)
        ed.begin()
        ed.preview(ObjectColorTarget.FILL, GREEN)
        assertColor("the fill preview is on the canvas", GREEN, inside(c))
        ed.cancel()
        assertColor("Cancel: back", RED, inside(c))
        ed.begin()
        ed.preview(ObjectColorTarget.STROKE, BLACK)
        assertColor("the line preview is on the canvas", BLACK, outline(c))
        assertColor("the fill is pinned", RED, inside(c))
        ed.commit()
        val steps = c.undoManager.undoCount
        tool.commit()
        settle()
        assertEquals("the edit's one step", steps + 1, c.undoManager.undoCount)
        val placed = layer.vector!!.objects.single() as VPath
        assertEquals(hex(BLACK), hex(placed.stroke!!.color))
        assertEquals(VPaint.Solid(RED), placed.fill)
        assertEquals("the user's main color is back", hex(BLUE), hex(c.color))
        c.undo()
        settle()
        assertEquals("one undo takes the color back", hex(RED), hex((layer.vector!!.objects.single() as VPath).stroke!!.color))
    }

    /**
     * Review addition (R2: gradients are kept until touched): a reopened path with a gradient fill
     * shows it as the Fill face; the first fill color replaces it, says
     * [ObjectColorLabels110.GRADIENT_REPLACED] once per opening of the sheet, and Cancel brings
     * the gradient back. A fill-only edit kept by ✓ stores the solid fill in the edit's one step;
     * one undo brings the gradient back.
     */
    @Test
    fun aReopenedGradientFillIsKeptUntilAFillColorIsPicked() {
        val c = rig.controller(400, 300)
        c.snapping.enabled = false
        val layer = c.addVectorLayer() ?: throw AssertionError("a vector layer")
        val gradient = VPaint.Linear(100f, 0f, 300f, 0f, listOf(VStop(0f, RED), VStop(1f, BLUE)))
        val blob = VPath(
            0,
            subpaths = listOf(VSubpath(listOf(VAnchor(100f, 100f), VAnchor(300f, 100f), VAnchor(300f, 220f), VAnchor(100f, 220f)), closed = true)),
            fill = gradient,
        )
        c.vectors.addObjects(layer, listOf(blob), "Import")
        c.settleVectorWork()
        c.selectTool(ToolId.CURVE)
        val tool = c.tools.getValue(ToolId.CURVE) as CurveTool
        settle()
        assertTrue(tool.reopen(layer.vector!!.objects.single() as VPath))
        c.settleVectorWork()
        settle()
        assertTrue("reopened", tool.isReopened)
        val ed = editor(tool)
        assertEquals("the gradient is the Fill face", ObjectColorFace.Gradient(listOf(RED, BLUE)), ed.face(ObjectColorTarget.FILL))

        ed.begin()
        c.message = null
        ed.preview(ObjectColorTarget.FILL, GREEN)
        assertEquals("the first fill color says so", ObjectColorLabels110.GRADIENT_REPLACED, c.message)
        assertEquals(ObjectColorFace.Solid(GREEN), ed.face(ObjectColorTarget.FILL))
        c.message = null
        ed.preview(ObjectColorTarget.FILL, BLACK)
        assertNull("said once per opening", c.message)
        ed.cancel()
        assertEquals("Cancel brings the gradient back", ObjectColorFace.Gradient(listOf(RED, BLUE)), ed.face(ObjectColorTarget.FILL))

        ed.begin()
        ed.preview(ObjectColorTarget.FILL, GREEN)
        assertEquals("said again in a new opening", ObjectColorLabels110.GRADIENT_REPLACED, c.message)
        ed.commit()
        val steps = c.undoManager.undoCount
        tool.commit()
        c.settleVectorWork()
        settle()
        assertEquals("the edit's one step", steps + 1, c.undoManager.undoCount)
        assertEquals("stored with the solid fill", VPaint.Solid(GREEN), (layer.vector!!.objects.single() as VPath).fill)
        c.undo()
        c.settleVectorWork()
        settle()
        assertEquals("one undo brings the gradient back", gradient, (layer.vector!!.objects.single() as VPath).fill)
    }
}
