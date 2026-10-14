package com.brushwork.paint.qa19

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.vector.ShapeStripItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.9 A9 (R17): undo while a shape is edited takes back its last edit, not the whole edit, in
 * the real editor on the user's 392 dp phone, at 360 dp, and at 360 dp at each of
 * [Qa19Ui.FONT_SCALES]: a rectangle placed in a shape layer of its own and opened again by a tap
 * on its outline is moved by a drag, then in Points mode its top left point is dragged; the first
 * "Undo" takes back the point drag (the moved shape stays, in Points mode), the second the move
 * (the shape as it was opened); the shape stays open and its layer untouched meanwhile. No text
 * on screen is cut at any font scale (I27), the Shape cells included; the coordinate pill's
 * readouts are left out (at 2.0 they are taller than the pill's cells: the lead's CoordinatePill).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392, instrumentedPackages = ["com.brushwork.paint.qa19.shapeundosandbox"])
class Qa19ShapeUndoUiTest {

    @Test
    fun undoTakesBackTheLastEditOfTheOpenShape() = Qa19Ui.uiTest { h ->
        h.section("392 dp") { undo(h, Qa19Ui.Q392, 392f, 1f) }
        for (scale in listOf(1f) + Qa19Ui.FONT_SCALES) {
            h.section("360 dp at font scale $scale") { undo(h, Qa19Ui.Q360, 360f, scale) }
        }
    }

    private fun undo(h: Qa19Harness, qualifiers: String, widthDp: Float, scale: Float) {
        val where = "${widthDp.toInt()} dp at font scale $scale"
        RuntimeEnvironment.setQualifiers(qualifiers)
        try {
            val s = h.editor(Smoke.document(400, 300, layers = 2, whiteBottom = true), fontScale = scale) { c ->
                c.snapping.enabled = false
                c.selectTool(ToolId.SHAPE)
            }
            settle()
            assertEquals("the phone is ${widthDp.toInt()} dp wide", widthDp, s.widthDp, 1f)
            val tool = s.c.tools.getValue(ToolId.SHAPE) as ShapeTool
            // A rectangle (document px 100, 80 to 300, 220), placed by a tap beside it.
            val layers = s.c.doc.layers.size
            drag(s, 100f to 80f, 300f to 220f)
            assertNotNull("$where: the drag drew a shape", tool.box)
            tap(s, 370f, 280f)
            assertNull("$where: the tap placed the shape", tool.box)
            assertEquals("$where: in a layer of its own", layers + 1, s.c.doc.layers.size)
            val layer = s.c.doc.activeLayer
            assertTrue("$where: a shape layer", layer.isShapeLayer)
            val data = layer.shapeData
            val docSteps = s.c.undoManager.undoCount

            // Opened again by a tap on its left side.
            tap(s, 100f, 150f)
            assertSame("$where: the tap opened the shape", layer, tool.editingLayer)
            val opened = tool.box!!
            assertNull("$where: a regular rectangle", tool.points)

            // Moved by a drag inside it.
            drag(s, 200f to 150f, 230f to 170f)
            val moved = tool.box!!
            assertEquals("$where: moved right", opened.cx + 30f, moved.cx, 2f)
            assertEquals("$where: moved down", opened.cy + 20f, moved.cy, 2f)

            // Points mode, then the top left point dragged up and left.
            Qa19Ui.click(ShapeStripItems.POINTS_LABEL, exact = true)
            assertTrue("$where: Points mode", tool.pointsMode)
            val before = tool.points!!
            val corner = Vec2(130f, 100f)
            val index = tool.docAnchors()!!.indexOfFirst { it.pos.distanceTo(corner) < 3f }
            assertTrue("$where: a point at the moved top left corner: ${tool.docAnchors()}", index >= 0)
            drag(s, 130f to 100f, 110f to 70f)
            assertTrue("$where: the point moved", tool.docAnchors()!![index].pos.distanceTo(Vec2(110f, 70f)) < 3f)

            // The first undo takes back the point drag only.
            Qa19Ui.click("Undo", exact = true)
            assertSame("$where: still open", layer, tool.editingLayer)
            assertEquals("$where: the point is back", before, tool.points)
            assertEquals("$where: the move stays", moved, tool.box)
            assertTrue("$where: still in Points mode", tool.pointsMode)
            assertNoTextCut("after one undo, $where")

            // The second takes back the move: the shape as it was opened.
            Qa19Ui.click("Undo", exact = true)
            assertSame("$where: still open", layer, tool.editingLayer)
            assertEquals("$where: back where it was opened", opened, tool.box)
            assertNull("$where: the regular rectangle again", tool.points)
            assertFalse("$where: Points mode ends with its points", tool.pointsMode)
            assertFalse("$where: nothing left to commit", tool.hasUserChanges)
            assertEquals("$where: the layer is untouched meanwhile", data, layer.shapeData)
            assertEquals("$where: no document step meanwhile", docSteps, s.c.undoManager.undoCount)
            assertNoTextCut("after two undos, $where")
            Smoke.assertQuiet(s.c, where)
        } finally {
            RuntimeEnvironment.setQualifiers(Qa19Ui.Q392)
        }
    }

    /** I27: no text on screen is cut, but the coordinate pill's readouts (see the class). */
    private fun assertNoTextCut(where: String) {
        val cut = Qa19Ui.textOverflows { e -> !StripTestKit.under(e.node, ChromeTags.PILL_SLOT) }
        assertTrue("texts overflow their box $where:\n" + cut.joinToString("\n"), cut.isEmpty())
    }

    /** A one-finger drag between document points [a] and [b] (through their middle). */
    private fun drag(s: ChromeScreen, a: Pair<Float, Float>, b: Pair<Float, Float>) {
        val p = s.screen(a.first, a.second)
        val q = s.screen(b.first, b.second)
        s.touch.stroke(p, ((p.first + q.first) / 2f) to ((p.second + q.second) / 2f), q)
        settle()
    }

    private fun tap(s: ChromeScreen, x: Float, y: Float) {
        val p = s.screen(x, y)
        s.touch.tap(p.first, p.second)
        settle()
    }

    private fun settle() {
        Smoke.pump(300)
        SmokeUi.settle()
    }
}
