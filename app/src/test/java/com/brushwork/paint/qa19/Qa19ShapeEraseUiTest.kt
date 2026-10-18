package com.brushwork.paint.qa19

import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.common.EraseLabels19
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.common.V19Tags
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.vector.ShapeStripItems
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.draw.VectorEraseMode
import com.brushwork.paint.vector.draw.VectorEraserModes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.9 A9 (R11, I27): erasing with shapes in the real editor, on the user's 392 dp phone and at
 * 360 dp, each at font scale 1 and at each of [Qa19Ui.FONT_SCALES]. On a vector layer a rectangle is drawn,
 * then "Stroke with"'s last row [EraseLabels19.INSTEAD_OF_PAINT] turns erase mode on (the
 * rectangle is placed first, a shape object) and bottom-bar slot 1 says how to switch back
 * ([EraseLabels19.ERASING_SHAPE]); with the More row closed the Eraser's mode cell shows in row 1,
 * and its menu picks Object; a rectangle drawn across the first one erases it live, and slot 1
 * (painting again) lands the erase as ONE step [EraseLabels19.STEP_SHAPE]. No text on screen is
 * cut, the two menus open included (the coordinate pill's readouts left out, as in the other
 * Shape UI tests: the lead's CoordinatePill).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392, instrumentedPackages = ["com.brushwork.paint.qa19.shapeerasesandbox"])
class Qa19ShapeEraseUiTest {

    @Test
    fun eraseWithShapes() = Qa19Ui.uiTest { h ->
        // (I27: both widths at each font scale.)
        for (scale in listOf(1f) + Qa19Ui.FONT_SCALES) {
            h.section("392 dp at font scale $scale") { erase(h, Qa19Ui.Q392, 392f, scale) }
            h.section("360 dp at font scale $scale") { erase(h, Qa19Ui.Q360, 360f, scale) }
        }
    }

    private fun erase(h: Qa19Harness, qualifiers: String, widthDp: Float, scale: Float) {
        val where = "${widthDp.toInt()} dp at font scale $scale"
        RuntimeEnvironment.setQualifiers(qualifiers)
        try {
            val s = h.editor(Smoke.document(400, 300, layers = 2, whiteBottom = true), fontScale = scale) { c ->
                c.snapping.enabled = false
                c.addVectorLayer()
                c.selectTool(ToolId.SHAPE)
            }
            settle()
            assertEquals("the phone is ${widthDp.toInt()} dp wide", widthDp, s.widthDp, 1f)
            val tool = s.c.tools.getValue(ToolId.SHAPE) as ShapeTool
            val vec = s.c.doc.activeLayer
            assertTrue("$where: a vector layer", vec.isVectorLayer)
            // A rectangle (document px 100, 80 to 300, 220), painted.
            drag(s, 100f, 80f, 300f, 220f)
            assertNotNull("$where: the drag drew a shape", tool.box)
            assertFalse(tool.eraseOn)

            // "Stroke with" ends with "Erase instead of paint".
            Qa19Ui.click(ShapeStripItems.STROKE_WITH_LABEL, exact = true)
            assertNotNull("$where: the menu's last row", SmokeUi.find(EraseLabels19.INSTEAD_OF_PAINT, exact = true))
            assertNoCut("with the Stroke with menu open, $where")
            Qa19Ui.click(EraseLabels19.INSTEAD_OF_PAINT, exact = true)
            assertTrue("$where: erase mode on", tool.eraseOn)
            assertFalse("$where: the rectangle was placed first", tool.hasPendingWork)
            val placed = vec.vector!!.objects.single()
            assertTrue("$where: a shape object", placed is VShape)
            assertTrue("$where: slot 1 switches back to painting", Qa19Ui.has(EraseLabels19.ERASING_SHAPE, exact = true))

            // The mode cell in row 1, the More row closed.
            Qa19Ui.closeMoreRow()
            assertFalse(StripTestKit.moreRowShown())
            val cell = Qa19Ui.tagged(V19Tags.ERASE_MODE_CELL) ?: throw AssertionError("$where: no mode cell")
            assertTrue("$where: the mode cell is in row 1", StripTestKit.under(cell, V18Tags.STRIP_ROW1))
            Qa19Ui.click(EraseLabels19.MODE_CELL.format(VectorEraserModes.mode(s.c).label), exact = true)
            assertNoCut("with the mode cell's menu open, $where")
            Qa19Ui.click(VectorEraseMode.OBJECT.label, exact = true)
            assertEquals("$where: the Eraser's mode", VectorEraseMode.OBJECT, VectorEraserModes.mode(s.c))
            assertNotNull("$where: the cell stays", Qa19Ui.tagged(V19Tags.ERASE_MODE_CELL))

            // A rectangle across the first one erases it live; slot 1 lands it as one step.
            val steps = s.c.undoManager.undoCount
            drag(s, 60f, 60f, 140f, 250f)
            assertNotNull("$where: erasing live", tool.eraseSession)
            assertEquals("$where: nothing changes before the commit", listOf(placed), vec.vector!!.objects)
            assertNoCut("while erasing, $where")
            Qa19Ui.click(EraseLabels19.ERASING_SHAPE, exact = true)
            assertFalse("$where: painting again", tool.eraseOn)
            assertEquals("$where: one step", steps + 1, s.c.undoManager.undoCount)
            assertEquals(EraseLabels19.STEP_SHAPE, s.c.undoManager.undoLabel)
            assertTrue("$where: the rectangle is erased", vec.vector!!.objects.isEmpty())
            assertTrue("$where: back to the paint label", Qa19Ui.has(EraseLabels19.WITH_SHAPE, exact = true))
            Smoke.assertQuiet(s.c, where)
        } finally {
            RuntimeEnvironment.setQualifiers(Qa19Ui.Q392)
        }
    }

    /** No text on screen is cut (I27), the coordinate pill's readouts left out. */
    private fun assertNoCut(where: String) {
        val cut = Qa19Ui.textOverflows { e -> !StripTestKit.under(e.node, ChromeTags.PILL_SLOT) }
        assertTrue("texts overflow their box $where:\n" + cut.joinToString("\n"), cut.isEmpty())
    }

    private fun drag(s: ChromeScreen, x0: Float, y0: Float, x1: Float, y1: Float) {
        val a = s.screen(x0, y0)
        val b = s.screen(x1, y1)
        s.touch.stroke(a, ((a.first + b.first) / 2f) to ((a.second + b.second) / 2f), b)
        settle()
    }

    private fun settle() {
        Smoke.pump(300)
        SmokeUi.settle()
    }
}
