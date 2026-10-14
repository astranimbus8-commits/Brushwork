package com.brushwork.paint.qa19

import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.AppSettings
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.PointLabels19
import com.brushwork.paint.ui.common.V19Tags
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.9 R18 (design 1 R18 (a), (e); I27): Polyline's "Bevel points" on the editor at 392 and
 * 360 dp and at font scales 1, 1.3 and 2. With two points selected the More row shows the item
 * (tag [V19Tags.BEVEL]) on screen; a tap bevels at once and opens the popup ([V19Tags.BEVEL_POPUP]):
 * its own window, inside the screen's width, at the bottom above the ✓ / ✕ bar and the bottom
 * bar, two lines with every control at least 40 dp and no text past its box. "More segments"
 * re-applies the step; the canvas tap that closes the popup adds no point; a re-tap of the item
 * reopens it; Back and "Done" close it; one Undo removes the bevel. One class: the widths and font
 * scales are sections.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392, instrumentedPackages = ["com.brushwork.paint.qa19.polylinebevelsandbox"])
class Qa19PolylineBevelUiTest {

    @Test
    fun bevelPointsAndItsPopupAtEveryWidthAndFontScale() = Qa19Ui.uiTest { h ->
        try {
            for (q in listOf(Qa19Ui.Q392, Qa19Ui.Q360)) for (scale in listOf(1f) + Qa19Ui.FONT_SCALES) {
                val where = "at ${q.substringBefore('-')}, font scale $scale"
                h.section(where) {
                    RuntimeEnvironment.setQualifiers(q)
                    checkTheBevel(h, scale, where)
                }
            }
        } finally {
            RuntimeEnvironment.setQualifiers(Qa19Ui.Q392)
        }
    }

    private fun checkTheBevel(h: Qa19Harness, scale: Float, where: String) {
        val s = h.editor(fontScale = scale)
        s.c.settings.curveBevelAmount = AppSettings.DEFAULT_BEVEL_AMOUNT
        s.c.settings.curveBevelSegments = AppSettings.DEFAULT_BEVEL_SEGMENTS
        s.c.selectTool(ToolId.POLYLINE)
        val t = s.c.tools.getValue(ToolId.POLYLINE) as CurveTool
        for (p in listOf(Vec2(60f, 200f), Vec2(130f, 90f), Vec2(200f, 160f), Vec2(270f, 80f))) assertTrue(t.addAnchor(p))
        t.selectPoints(PointSelection.of(t.pointCount, 1, 2))
        val before = t.anchors
        Qa19Ui.settle()
        Qa19Ui.openMoreRow()
        val d = s.density
        val item = requireNotNull(Qa19Ui.tagged(V19Tags.BEVEL)) { "$where: the Bevel points item; shown ${Qa19Ui.shown().take(60)}" }
        assertTrue("$where: the item is on screen", item.positionInWindow.x >= 0f && item.positionInWindow.x + item.size.width <= s.root.width + 0.5f)
        assertTrue("$where: the item is 40 dp", item.size.width / d >= 39.5f && item.size.height / d >= 39.5f)

        Qa19Ui.click(PointLabels19.BEVEL, exact = true)
        assertEquals("$where: two corners, two points each", 6, t.pointCount)
        assertTrue("$where: the popup opens", t.bevelPopupShown)
        checkThePopup(s, where)

        Qa19Ui.click(PointLabels19.MORE_SEGMENTS, exact = true)
        assertEquals("$where: More segments", 2, t.bevelSession?.segments)
        assertEquals("$where: three points per corner", 8, t.pointCount)

        // The tap on the canvas that closes the popup adds no point (the More row closed first: it covers the top).
        Qa19Ui.closeMoreRow()
        val (x, y) = s.screen(200f, 30f)
        Finger.tapAt(s, (x - s.root.left) / d, (y - s.root.top) / d)
        assertFalse("$where: the canvas tap closes the popup", t.bevelPopupShown)
        assertEquals("$where: and adds no point", 8, t.pointCount)

        // A re-tap of the item reopens it on the same step; Done closes it.
        Qa19Ui.openMoreRow()
        Qa19Ui.click(PointLabels19.BEVEL, exact = true)
        assertTrue("$where: reopened", t.bevelPopupShown)
        assertEquals("$where: not bevelled again", 8, t.pointCount)
        assertEquals(2, t.bevelSession?.segments)
        // Back closes it (the editor's window gets the key: the popup is not focusable), the bevel stays.
        s.activity.onBackPressedDispatcher.onBackPressed()
        Qa19Ui.settle()
        assertFalse("$where: Back closes it", t.bevelPopupShown)
        assertEquals("$where: Back keeps the bevel", 8, t.pointCount)
        assertTrue("$where: and the polyline", t.hasPendingWork)
        Qa19Ui.click(PointLabels19.BEVEL, exact = true)
        assertTrue("$where: reopened after Back", t.bevelPopupShown)
        val done =requireNotNull(popupNodes().firstOrNull { it.config.getOrNull(SemanticsActions.OnClick) != null && labels(it).contains(DONE) }) { "$where: Done" }
        requireNotNull(done.config.getOrNull(SemanticsActions.OnClick)?.action).invoke()
        Qa19Ui.settle()
        assertFalse("$where: Done closes it", t.bevelPopupShown)
        assertTrue("$where: no popup window left", Qa19Ui.tagged(V19Tags.BEVEL_POPUP) == null)

        // One undo removes the bevel.
        Qa19Ui.click(UNDO, exact = true)
        assertEquals("$where: one undo", before, t.anchors)
    }

    /** The popup: inside the screen's width, at the bottom above the ✓ / ✕ bar, two lines, controls of 40 dp, no text past its box. */
    private fun checkThePopup(s: ChromeScreen, where: String) {
        val d = s.density
        val popup = requireNotNull(RobolectricUi.elements().lastOrNull { e -> e.node.layoutInfo.isPlaced && StripTestKit.under(e.node, V19Tags.BEVEL_POPUP) && e.node.parent?.let { StripTestKit.under(it, V19Tags.BEVEL_POPUP) } != true }) { "$where: the popup" }
        val params = popup.window.layoutParams as WindowManager.LayoutParams
        val w = popup.node.size.width
        val hgt = popup.node.size.height
        val screen = s.root
        assertTrue("$where: the popup is inside the screen's width (x ${params.x}, ${w / d} dp of ${screen.width / d})", params.x >= screen.left - 0.5f && params.x + w <= screen.right + 0.5f)
        val pending = requireNotNull(s.tagged(ChromeTags.PENDING_BAR)) { "$where: the ✓ / ✕ bar" }
        val bottomBar = requireNotNull(s.tagged(ChromeTags.BOTTOM_BAR)) { "$where: the bottom bar" }
        val bottomDp = (params.y + hgt - screen.top) / d
        assertTrue("$where: the popup ($bottomDp dp) sits above the ✓ / ✕ bar (${pending.top})", bottomDp <= pending.top + 0.5f)
        assertTrue("$where: and above the bottom bar (${bottomBar.top})", bottomDp <= bottomBar.top + 0.5f)
        assertTrue("$where: at the bottom: its bottom within 72 dp of the ✓ / ✕ bar", pending.top - bottomDp <= 72f)

        val controls = popupNodes().filter { n ->
            n.config.getOrNull(SemanticsActions.OnClick) != null || n.config.getOrNull(SemanticsActions.SetProgress) != null
        }
        assertTrue("$where: the popup's controls (${controls.size})", controls.size >= 4)
        for (n in controls) {
            assertTrue("$where: ${labels(n)} is ${n.size.width / d} × ${n.size.height / d} dp", n.size.width / d >= 39.5f && n.size.height / d >= 39.5f)
        }
        val slider = controls.first { it.config.getOrNull(SemanticsActions.SetProgress) != null }
        val doneNode = controls.first { labels(it).contains(DONE) }
        assertTrue("$where: two lines (the Amount slider above Done)", doneNode.positionInWindow.y >= slider.positionInWindow.y + slider.size.height - 10f * d)

        val bad = Qa19Ui.textOverflows { e -> StripTestKit.under(e.node, V19Tags.BEVEL_POPUP) }
        if (bad.isNotEmpty()) throw AssertionError("texts of the bevel popup overflow their box $where:\n" + bad.joinToString("\n"))
    }

    /** The placed nodes under the popup's tag. */
    private fun popupNodes(): List<SemanticsNode> =
        RobolectricUi.elements().filter { e -> e.node.layoutInfo.isPlaced && StripTestKit.under(e.node, V19Tags.BEVEL_POPUP) }.map { it.node }

    /** [n]'s description and text. */
    private fun labels(n: SemanticsNode): List<String> =
        n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            n.children.flatMap { c -> c.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    private companion object {
        /** BevelPopup's button (a private constant of the frozen popup). */
        const val DONE = "Done"
        const val UNDO = "Undo"
    }
}
