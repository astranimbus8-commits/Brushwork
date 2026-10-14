package com.brushwork.paint.qa19

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.common.PointLabels19
import com.brushwork.paint.ui.common.V19Tags
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.vector.ShapeStripItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.9 A9 (R18, I27): "Bevel points" in the real editor, on the user's 392 dp phone, at 360 dp,
 * and at 360 dp at each of [Qa19Ui.FONT_SCALES]: a rectangle in Points mode, its top left corner
 * selected by a tap, "Bevel points" from the More row bevels it at once and the two-line popup
 * shows inside the screen, above the ✓ / ✕ and the bottom bar, with none of its texts cut (nor
 * any on screen up to font scale 1.3: at 2.0 v1.8's fixed Shape cells and the pill's readouts
 * are, which the lead's `StripFontScaleTest` covers with the areas merged); "More segments"
 * re-applies the bevel; "Done" closes it; a second "Bevel points" reopens it on the same step; a
 * tap on the canvas closes it and is consumed (the shape stays open, unchanged); one undo removes
 * the bevel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392, instrumentedPackages = ["com.brushwork.paint.qa19.shapebevelsandbox"])
class Qa19ShapeBevelUiTest {

    @Test
    fun bevelPointsAndItsPopup() = Qa19Ui.uiTest { h ->
        h.section("392 dp") { bevel(h, Qa19Ui.Q392, 392f, 1f) }
        for (scale in listOf(1f) + Qa19Ui.FONT_SCALES) {
            h.section("360 dp at font scale $scale") { bevel(h, Qa19Ui.Q360, 360f, scale) }
        }
    }

    private fun bevel(h: Qa19Harness, qualifiers: String, widthDp: Float, scale: Float) {
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
            // A rectangle (document px 100, 80 to 300, 220), then Points mode.
            val a = s.screen(100f, 80f)
            val b = s.screen(300f, 220f)
            s.touch.stroke(a, ((a.first + b.first) / 2f) to ((a.second + b.second) / 2f), b)
            settle()
            assertNotNull("$where: the drag drew a shape", tool.box)
            Qa19Ui.click(ShapeStripItems.POINTS_LABEL, exact = true)
            assertTrue("$where: Points mode", tool.pointsMode)
            assertEquals(4, tool.pointCount)
            // The top left corner, by a tap.
            val corner = tool.docAnchors()!!.indexOfFirst { it.pos.distanceTo(Vec2(100f, 80f)) < 2f }
            assertTrue("$where: a point at the top left", corner >= 0)
            tap(s, 100f, 80f)
            assertEquals("$where: the tap selected the corner", listOf(corner), tool.pointSelection.indices)

            Qa19Ui.click(PointLabels19.BEVEL, exact = true)
            assertEquals("$where: bevelled at once (a chamfer)", 5, tool.pointCount)
            assertEquals("$where: the new points are selected", 2, tool.pointSelection.count)
            val popup = s.tagged(V19Tags.BEVEL_POPUP) ?: throw AssertionError("$where: no bevel popup")
            assertNotNull("$where: the item is tagged", s.tagged(V19Tags.BEVEL))
            val bar = s.tagged(ChromeTags.BOTTOM_BAR) ?: throw AssertionError("$where: no bottom bar")
            assertTrue("$where: inside the screen: $popup", popup.left >= -0.5f && popup.right <= s.widthDp + 0.5f && popup.top >= 0f)
            assertTrue("$where: above the bottom bar: $popup, bar $bar", popup.bottom <= bar.top + 0.5f)
            s.tagged(ChromeTags.PENDING_BAR)?.let { pending ->
                assertTrue("$where: above the ✓ / ✕: $popup, $pending", popup.bottom <= pending.top + 0.5f)
            }
            // Two lines: Amount above the segments and Done.
            val amount = SmokeUi.find(PointLabels19.AMOUNT, exact = true)?.let { s.dp(it.bounds) } ?: throw AssertionError("$where: no Amount")
            val done = s.clickable("Done") ?: throw AssertionError("$where: no Done")
            val more = s.clickable(PointLabels19.MORE_SEGMENTS) ?: throw AssertionError("$where: no More segments")
            assertTrue("$where: Done on the second line: $done, Amount $amount", done.center.y > amount.bottom && done.center.y < popup.bottom)
            assertTrue("$where: every control at least 40 dp", listOf(done, more).all { it.width >= 39.5f && it.height >= 39.5f })
            assertTrue("$where: two lines: $popup", popup.height <= 160f)
            // The popup's own texts at every scale; the whole screen up to 1.3 (at 2.0 v1.8's
            // fixed cells and the pill's readouts are cut: the lead's StripFontScaleTest, I27).
            val popupWindow = s.placed().last { it.node.config.getOrNull(SemanticsProperties.TestTag) == V19Tags.BEVEL_POPUP }.window
            val cut = Qa19Ui.textOverflows { it.window === popupWindow }
            assertTrue("texts of the bevel popup overflow their box, $where:\n" + cut.joinToString("\n"), cut.isEmpty())
            if (scale < 2f) Qa19Ui.assertNoTextOverflow("with the bevel popup open, $where") { it == ShapeStripItems.ANGLE_LABEL }

            // "More segments" re-applies the bevel on its step.
            Qa19Ui.click(PointLabels19.MORE_SEGMENTS, exact = true)
            assertEquals("$where: two segments", 6, tool.pointCount)
            Qa19Ui.click("Done", exact = true)
            assertNull("$where: Done closes the popup", s.tagged(V19Tags.BEVEL_POPUP))
            // A second tap reopens it on the same step; a tap on the canvas closes it, consumed.
            Qa19Ui.click(PointLabels19.BEVEL, exact = true)
            assertNotNull("$where: reopened", s.tagged(V19Tags.BEVEL_POPUP))
            assertEquals("$where: not bevelled again", 6, tool.pointCount)
            val shape = tool.points
            val layers = s.c.doc.layers.size
            tap(s, 360f, 150f)
            assertNull("$where: the tap closes the popup", s.tagged(V19Tags.BEVEL_POPUP))
            assertTrue("$where: the shape stays open", tool.hasPendingWork)
            assertEquals("$where: unchanged", shape, tool.points)
            assertEquals("$where: no layer", layers, s.c.doc.layers.size)
            // One undo removes the bevel.
            Qa19Ui.click("Undo", exact = true)
            assertEquals("$where: one undo removes the bevel", 4, tool.pointCount)
            assertFalse(tool.bevelPopupOpen)
            Smoke.assertQuiet(s.c, where)
        } finally {
            RuntimeEnvironment.setQualifiers(Qa19Ui.Q392)
        }
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
