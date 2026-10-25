package com.brushwork.paint.qa110

import androidx.compose.ui.geometry.Rect
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.QaCurves
import com.brushwork.paint.qa19.Qa19Harness
import com.brushwork.paint.qa19.Qa19Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ColoredObjectTool
import com.brushwork.paint.tools.ObjectColorEditor
import com.brushwork.paint.tools.ObjectColorFace
import com.brushwork.paint.tools.ObjectColorTarget
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.vector.CornerStyle
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.ObjectColorLabels110
import com.brushwork.paint.ui.common.PaintMode
import com.brushwork.paint.ui.common.V110Tags
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.theme.IbisDims
import com.brushwork.paint.v110.a2.A2SheetKit
import com.brushwork.paint.vector.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.10 final QA of R2 and R7 (the Object colors sheet from the pill) on the real editor at 392
 * and 360 dp and font scale 1.3 and 2.0, every tap a finger's (design 4.4 A2 step 8; device check
 * FC2):
 * - a reopened shape: the pill's cell (tag [V110Tags.PILL_COLOR]) whole inside the pill's 8 dp
 *   margins; the sheet's two tabs, three paint-mode glyphs, "No fill", Cancel and OK whole on
 *   screen; a fill and a stroke color typed preview on the shape; OK, then ✓: one step with both
 *   colors, the drawing color as it was; one Undo gives the old colors back; reopened again, a
 *   color typed and Back: the shape's color comes back;
 * - a new Polyline: "No fill" makes its paint mode Stroke (the sheet's Stroke glyph selected) and
 *   Cancel gives the fill back; a stroke color, OK and ✓ place the path in that color, the drawing
 *   color as it was;
 * - a new text: the Text and Outline tabs, no paint mode; a text color previews; "No outline" on
 *   screen; Cancel gives the text color back;
 * and no text overflows its box with the sheet open, but two cuts at font scale 2.0 that are not
 * the sheet's own ([assertNoCut]).
 *
 * One test: Compose's frame clock only serves the first test of a Robolectric sandbox. The 360 dp
 * phone comes from `RuntimeEnvironment.setQualifiers` (as `Qa110SharpCellUiTest`). The hex is
 * typed into the sheet's own text field ([A2SheetKit.typeIn]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392, instrumentedPackages = ["com.brushwork.paint.qa110.objectcolorssandbox"])
class Qa110ObjectColorsUiTest {

    @Test
    fun objectColorsByFingerAt392And360() = Qa19Ui.uiTest { h ->
        try {
            for (width in listOf(392, 360)) {
                if (width == 360) RuntimeEnvironment.setQualifiers(Qa19Ui.Q360)
                for (scale in Qa19Ui.FONT_SCALES) {
                    val at = "$width dp, font scale $scale"
                    h.section("$at: Shape") { shape(h, width, scale, at) }
                    h.section("$at: Polyline") { polyline(h, width, scale, at) }
                    h.section("$at: Text") { text(h, width, scale, at) }
                }
            }
        } finally {
            RuntimeEnvironment.setQualifiers(Qa19Ui.Q392)
        }
    }

    private fun open(h: Qa19Harness, id: ToolId, width: Int, scale: Float): ChromeScreen {
        val s = h.editor(fontScale = scale)
        assertEquals("the phone", width.toFloat(), s.widthDp, 1f)
        s.c.snapping.enabled = false
        s.c.selectTool(id)
        Qa19Ui.settle()
        return s
    }

    /** [r] (dp) is shown whole inside the window; [r]. */
    private fun onScreen(s: ChromeScreen, what: String, r: Rect?): Rect {
        val b = r ?: throw AssertionError("$what is not shown; shown: ${Qa19Ui.shown().take(80)}")
        assertTrue(
            "$what whole on screen: $b in ${s.widthDp} x ${s.heightDp} dp",
            b.width > 0f && b.height > 0f && b.left >= -EPS && b.top >= -EPS && b.right <= s.widthDp + EPS && b.bottom <= s.heightDp + EPS,
        )
        return b
    }

    private fun tapTag(s: ChromeScreen, tag: String) {
        val r = onScreen(s, tag, s.tagged(tag))
        Finger.tapAt(s, r.center.x, r.center.y)
    }

    private fun tapLabel(s: ChromeScreen, label: String) {
        val r = onScreen(s, "\"$label\"", Finger.control(s, label))
        Finger.tapAt(s, r.center.x, r.center.y)
    }

    private fun tab(t: ObjectColorTarget) = "${V110Tags.COLORS_SHEET_TAB}.${t.name}"

    private fun mode(m: PaintMode) = "${V110Tags.COLORS_SHEET_MODE}.${m.name}"

    /** The pill's cell whole inside the pill's 8 dp margins; a finger's tap opens the sheet. */
    private fun openSheet(s: ChromeScreen, what: String) {
        val cell = s.tagged(V110Tags.PILL_COLOR) ?: throw AssertionError("$what: the pill's cell; shown: ${Qa19Ui.shown().take(80)}")
        val margin = IbisDims.PillStart.value
        assertTrue("$what: the cell whole inside the 8 dp margins: $cell", cell.left >= margin - EPS && cell.right <= s.widthDp - margin + EPS && cell.width > 0f)
        Finger.tapAt(s, cell.center.x, cell.center.y)
        assertEquals("$what: the sheet opened", listOf(ObjectColorLabels110.CELL), SmokeUi.sheetTitles())
    }

    /** The sheet's controls whole on screen ([modes]: its paint-mode glyphs), no text cut. */
    private fun assertSheet(s: ChromeScreen, what: String, scale: Float, tabs: List<ObjectColorTarget>, modes: Boolean) {
        for (t in tabs) onScreen(s, "$what: the ${t.name} tab", s.tagged(tab(t)))
        for (m in PaintMode.entries) {
            if (modes) onScreen(s, "$what: the ${m.name} glyph", s.tagged(mode(m))) else assertNull("$what: no paint mode", s.tagged(mode(m)))
        }
        onScreen(s, "$what: Cancel", Finger.control(s, CANCEL))
        onScreen(s, "$what: OK", Finger.control(s, OK))
        assertNoCut("$what, the sheet open", scale)
    }

    /**
     * No text overflows its box ([Qa19Ui.assertNoTextOverflow]), except at font scale 2.0 two
     * cuts that are not the sheet's own: the color body's "Previous" and "Current" (one line
     * each in `CompareSwatch`, ui/color/ColorControls.kt; v1.9's own color picker cuts them the
     * same at 392 dp and font scale 2.0) and the sheet's title, which `BwSheet`'s header
     * (ui/common/Components.kt) ellipsizes on one line beside the picker's Cancel and OK. Both are
     * lead requests (ARCHITECTURE A2).
     */
    private fun assertNoCut(what: String, scale: Float) =
        Qa19Ui.assertNoTextOverflow(what) { scale >= 2f && it in NOT_THE_SHEETS_CUTS }

    private fun assertClosed(what: String) = assertTrue(
        "$what: the sheet closed: ${SmokeUi.sheetTitles()} ${SmokeUi.pillTitles()}",
        ObjectColorLabels110.CELL !in SmokeUi.sheetTitles() && ObjectColorLabels110.CELL !in SmokeUi.pillTitles(),
    )

    private fun type(hex: String) {
        A2SheetKit.typeIn(ObjectColorLabels110.CELL, hex)
        Qa19Ui.settle()
    }

    /** The pending-work bar's ✓ and ✕ (EditorChrome's `PendingButtons` names them so). */
    private fun apply(tool: Tool) = "Apply ${tool.id.label.lowercase()} edit"

    private fun discard(tool: Tool) = "Discard ${tool.id.label.lowercase()} edit"

    private fun editor(tool: Tool, what: String): ObjectColorEditor =
        (tool as? ColoredObjectTool)?.objectColors ?: throw AssertionError("$what: no open object's colors")

    private fun shape(h: Qa19Harness, width: Int, scale: Float, at: String) {
        val s = open(h, ToolId.SHAPE, width, scale)
        val c = s.c
        val drawing = c.color
        val tool = c.currentTool as ShapeTool
        tool.update {
            it.copy(
                type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE_FILL, fillColor = ORANGE, useBrushSize = false, strokeWidth = 6f,
                corner = CornerStyle.SHARP, keepProportions = false, fromCenter = false, snapAngle = false,
            )
        }
        QaCurves.drag(s, Vec2(60f, 70f), Vec2(110f, 80f))
        tapLabel(s, apply(tool))
        val layer = c.activeLayer
        val before = requireNotNull(ShapeCodec.decode(layer.shapeData)) { "$at: a shape layer" }
        assertEquals("$at: placed in its fill", ORANGE, before.fillColor)

        // Reopened by a finger: both colors typed, OK, then ✓.
        QaCurves.tap(s, Vec2(115f, 110f))
        assertSame("$at: reopened", layer, tool.editingLayer)
        val ed = editor(tool, at)
        openSheet(s, "$at, Shape")
        tapTag(s, tab(ObjectColorTarget.FILL))
        onScreen(s, "$at: \"No fill\"", Finger.control(s, ObjectColorLabels110.NO_FILL))
        assertSheet(s, "$at, Shape", scale, listOf(ObjectColorTarget.FILL, ObjectColorTarget.STROKE), modes = true)
        type("00FF00")
        assertEquals("$at: the fill, live", ObjectColorFace.Solid(GREEN), ed.face(ObjectColorTarget.FILL))
        tapTag(s, tab(ObjectColorTarget.STROKE))
        type("0000FF")
        assertEquals("$at: the stroke, live", ObjectColorFace.Solid(BLUE), ed.face(ObjectColorTarget.STROKE))
        tapLabel(s, OK)
        assertClosed("$at: OK")
        val steps = c.undoManager.undoCount
        tapLabel(s, apply(tool))
        assertNull("$at: ✓ closed the shape", tool.editingLayer)
        assertEquals("$at: ✓ is one step", steps + 1, c.undoManager.undoCount)
        val after = requireNotNull(ShapeCodec.decode(layer.shapeData))
        assertEquals("$at: ✓ keeps the fill", GREEN, after.fillColor)
        assertEquals("$at: ✓ keeps the stroke", BLUE, after.strokeColor)
        assertEquals("$at: the drawing color as it was", drawing, c.color)
        tapLabel(s, UNDO)
        val undone = requireNotNull(ShapeCodec.decode(layer.shapeData))
        assertEquals("$at: one Undo, the old fill", before.fillColor, undone.fillColor)
        assertEquals("$at: one Undo, the old stroke", before.strokeColor, undone.strokeColor)

        // Reopened again: a color typed, then Back gives the shape's color back.
        QaCurves.tap(s, Vec2(115f, 110f))
        assertSame("$at: reopened again", layer, tool.editingLayer)
        val again = editor(tool, at)
        openSheet(s, "$at, Shape again")
        tapTag(s, tab(ObjectColorTarget.FILL))
        type("00FF00")
        assertEquals(ObjectColorFace.Solid(GREEN), again.face(ObjectColorTarget.FILL))
        Finger.back()
        assertClosed("$at: Back")
        assertEquals("$at: Back gives the fill back", ObjectColorFace.Solid(before.fillColor), again.face(ObjectColorTarget.FILL))
        tapLabel(s, discard(tool))
        Qa19Ui.settle()
        assertEquals("$at: the drawing color as it was", drawing, c.color)
        Smoke.assertQuiet(c, "Shape at $at")
    }

    private fun polyline(h: Qa19Harness, width: Int, scale: Float, at: String) {
        val s = open(h, ToolId.POLYLINE, width, scale)
        val c = s.c
        val drawing = c.color
        val tool = c.currentTool as CurveTool
        tool.update { it.copy(stroke = CurveStroke.PLAIN, plainWidth = 6f, useBrushSize = false, fill = true, fillColor = ORANGE, closed = true, ownLayer = true) }
        for (p in listOf(Vec2(60f, 80f), Vec2(170f, 80f), Vec2(170f, 160f), Vec2(60f, 160f))) assertTrue("$at: a point at $p", tool.addAnchor(p))
        tool.deselect()
        Qa19Ui.settle()
        val ed = editor(tool, at)

        // "No fill" is paint mode Stroke; Cancel gives the fill back.
        openSheet(s, "$at, Polyline")
        tapTag(s, tab(ObjectColorTarget.FILL))
        assertSheet(s, "$at, Polyline", scale, listOf(ObjectColorTarget.FILL, ObjectColorTarget.STROKE), modes = true)
        tapLabel(s, ObjectColorLabels110.NO_FILL)
        assertEquals("$at: No fill is paint mode Stroke", PaintMode.STROKE, ed.paintMode)
        assertEquals("$at: the sheet's Stroke glyph is selected", true, A2SheetKit.selectedTag(mode(PaintMode.STROKE)))
        tapLabel(s, CANCEL)
        assertClosed("$at: Cancel")
        assertEquals("$at: Cancel gives the fill back", PaintMode.BOTH, ed.paintMode)
        assertEquals("$at: and its color", ObjectColorFace.Solid(ORANGE), ed.face(ObjectColorTarget.FILL))

        // A stroke color, OK, then ✓.
        openSheet(s, "$at, Polyline stroke")
        tapTag(s, tab(ObjectColorTarget.STROKE))
        type("0000FF")
        assertEquals("$at: the stroke, live", ObjectColorFace.Solid(BLUE), ed.face(ObjectColorTarget.STROKE))
        tapLabel(s, OK)
        assertClosed("$at: OK")
        tapLabel(s, apply(tool))
        val layer = c.doc.activeLayer
        assertTrue("$at: a vector layer of its own", layer.isVectorLayer)
        val path = layer.vector!!.objects.single() as VPath
        assertEquals("$at: ✓ keeps the stroke", BLUE, path.stroke?.color)
        assertEquals("$at: the drawing color as it was", drawing, c.color)
        Smoke.assertQuiet(c, "Polyline at $at")
    }

    private fun text(h: Qa19Harness, width: Int, scale: Float, at: String) {
        val s = open(h, ToolId.TEXT, width, scale)
        val c = s.c
        val drawing = c.color
        val tool = c.currentTool as TextTool
        QaCurves.tap(s, Vec2(80f, 150f))
        assertTrue("$at: the editor opened for a new text", tool.editorOpen && tool.editingNew)
        tool.setText("HH")
        tool.updateSpec { it.copy(sizePx = 40f, color = BLACK, strokeWidthPx = 0f) }
        tool.confirmEditor()
        Qa19Ui.settle()
        val ed = editor(tool, at)

        openSheet(s, "$at, Text")
        tapTag(s, tab(ObjectColorTarget.TEXT))
        assertSheet(s, "$at, Text", scale, listOf(ObjectColorTarget.TEXT, ObjectColorTarget.OUTLINE), modes = false)
        type("0000FF")
        assertEquals("$at: the text color, live", BLUE, tool.item!!.spec.color)
        tapTag(s, tab(ObjectColorTarget.OUTLINE))
        onScreen(s, "$at: \"No outline\"", Finger.control(s, ObjectColorLabels110.NO_OUTLINE))
        assertNoCut("$at, the Outline tab", scale)
        tapLabel(s, CANCEL)
        assertClosed("$at: Cancel")
        assertEquals("$at: Cancel gives the text color back", BLACK, tool.item!!.spec.color)
        assertEquals("$at: and its face", ObjectColorFace.Solid(BLACK), ed.face(ObjectColorTarget.TEXT))
        tapLabel(s, discard(tool))
        Qa19Ui.settle()
        assertEquals("$at: the drawing color as it was", drawing, c.color)
        Smoke.assertQuiet(c, "Text at $at")
    }

    private companion object {
        const val EPS = 0.5f
        const val ORANGE = 0xFFFF8000.toInt()
        const val GREEN = 0xFF00FF00.toInt()
        const val BLUE = 0xFF0000FF.toInt()
        const val BLACK = 0xFF000000.toInt()

        /** The color picker's buttons and the editor's Undo, as the v1.9 QA tests find them. */
        const val OK = "OK"
        const val CANCEL = "Cancel"
        const val UNDO = "Undo"

        /** See [assertNoCut]: CompareSwatch's two literals and the sheet's title. */
        val NOT_THE_SHEETS_CUTS = setOf("Previous", "Current", ObjectColorLabels110.CELL)
    }
}
