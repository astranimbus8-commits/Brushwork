package com.brushwork.paint.qa18

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.gen.CurveGenerators
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.CurveGenLabels18
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.vector.CurveGenKind
import com.brushwork.paint.vector.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/** The Curves sheet's two v1.8 polish lines, as a finger and a screen reader meet them (shared by the 392 and 360 dp tests). */
internal object Qa18PolishA11yCurvesSheet {
    val LIMIT = CurveGenLabels18.pointsLimit(CurveGenerators.MAX_ANCHORS)
    val AT_LIMIT = CurveGenLabels18.pointsAtLimit(CurveGenerators.MAX_ANCHORS)

    /** The line showing exactly [text], scrolled wholly into view. */
    fun line(q: Qa18CurvesUi, text: String): RobolectricUi.Element =
        q.visible { SmokeUi.find(text, exact = true) ?: throw AssertionError("no \"$text\"; shown: ${SmokeUi.shown().take(60)}") }

    /** The lines [e]'s text is laid out on. */
    fun lineCount(e: RobolectricUi.Element): Int {
        val out = ArrayList<TextLayoutResult>()
        requireNotNull(e.node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action) { "not a text" }.invoke(out)
        return out.single().lineCount
    }

    /**
     * A finger on the value of the slider [label] (its "Type a value for" box), [text] typed and
     * the keyboard's Done: the value as the user types it.
     */
    fun type(q: Qa18CurvesUi, label: String, text: String) {
        val box = q.visible {
            RobolectricUi.elements().lastOrNull { e ->
                e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsActions.OnClick)?.label == "Type a value for $label"
            } ?: throw AssertionError("no value box for \"$label\"")
        }
        box.tap()
        settle(4)
        val field = RobolectricUi.textFields().lastOrNull { it.focused } ?: throw AssertionError("\"$label\": no field in focus")
        field.type(text)
        settle(2)
        requireNotNull(field.node.config.getOrNull(SemanticsActions.OnImeAction)?.action) { "\"$label\": no Done" }.invoke()
        settle(4)
    }

    /**
     * [text] shows exactly once, on one line, wholly on screen and right under the slider [label]
     * (above [nextLabel]'s text when given); returns its height (window px).
     */
    fun assertUnder(q: Qa18CurvesUi, text: String, label: String, nextLabel: String?): Float {
        val e = line(q, text)
        assertEquals("\"$text\" once", 1, RobolectricUi.elements().count { t -> t.node.layoutInfo.isPlaced && t.node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true })
        assertEquals("\"$text\" on one line", 1, lineCount(e))
        val b = e.bounds
        assertTrue("\"$text\" across the phone: $b of ${e.window.width} px", b.left >= 0f && b.right <= e.window.width + 0.5f)
        assertTrue("\"$text\" on screen: $b of ${e.window.height} px", b.top >= 0f && b.bottom <= e.window.height + 0.5f)
        val slider = q.sliderUnder(label).bounds
        assertTrue("\"$text\" right under the \"$label\" slider ($slider): $b", b.top >= slider.bottom - 0.5f && b.top - slider.bottom < 12f * q.s.density)
        if (nextLabel != null) {
            val next = requireNotNull(SmokeUi.find(nextLabel, exact = true)) { "no \"$nextLabel\"" }.bounds
            assertTrue("\"$text\" above \"$nextLabel\" ($next): $b", b.bottom <= next.top + 0.5f)
        }
        return b.height
    }
}

/**
 * v1.8 polish (item 3) on the user's phone (392 dp), by finger: the Curves sheet says what two
 * of its sliders do.
 *
 * - Under "Petals", "Draws N petals": the rose's own count, twice the value when n·d is even ("4"
 *   draws 8), the value when odd ("5" draws 5; "3" over divisor "2" draws 6); it follows a drag.
 * - Under "Points per turn", "At most 400 points in all" while the value still counts; from the
 *   value where turns × points per turn reach 400 (a 48-petal rose at 9), "At the 400-point limit:
 *   more add nothing": the curve has its 400 points, a higher value (20) leaves them exactly, and
 *   a drag back under the limit brings the first line and another curve.
 * - Both lines are one line in either state and right under their slider; the sheet keeps its
 *   height when the line switches. The ✓ stores the rose with its settings (one step).
 * - A render of the sheet at the limit for the lead.
 *
 * One UI test, own sandbox (Compose frames stall in later tests of a sandbox).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.polisha11ycurvessandbox"])
class Qa18PolishA11yCurvesUiTest {

    @Test
    fun theCurvesSheetSaysThePetalsDrawnAndThePointLimit() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 300_000)
        val h = ChromeHarness()
        val q = Qa18CurvesUi(h, 392f)
        h.section("the Curves sheet's petal and point lines at 392 dp") { flow(q) }
        dog.interrupt()
        h.finish()
    }

    private fun flow(q: Qa18CurvesUi) {
        val k = Qa18PolishA11yCurvesSheet
        q.editor(Qa18CurvesUi.vectorDoc())
        q.tool("Curve")
        val tool = q.c.currentTool as CurveTool
        q.tap(CurveGenLabels18.CURVES_DESC)
        q.tap(CurveGenLabels18.ROSE)
        val start = tool.generator ?: throw AssertionError("no rose placed")
        assertEquals(CurveGenKind.ROSE, start.kind)
        StripTestKit.closeMoreRow()
        val stepsBefore = q.steps()
        q.tap(CurveGenLabels18.CHANGE)
        assertTrue("the sheet; shown: ${SmokeUi.shown().take(60)}", has(CurveGenLabels18.POINTS_PER_TURN, exact = true))

        // The rose as placed: its petals, under the limit.
        assertEquals("the rose as placed: 5 petals, divisor 1", 5 to 1, start.petalsN to start.petalsD)
        k.assertUnder(q, "Draws 5 petals", CurveGenLabels18.PETALS, CurveGenLabels18.PETAL_DIVISOR)
        k.assertUnder(q, k.LIMIT, CurveGenLabels18.POINTS_PER_TURN, null)
        assertFalse(has(k.AT_LIMIT, exact = true))

        // An even value draws twice its petals; a divisor can make an odd value even.
        k.type(q, CurveGenLabels18.PETALS, "4")
        assertEquals(4, tool.generator!!.petalsN)
        k.assertUnder(q, "Draws 8 petals", CurveGenLabels18.PETALS, CurveGenLabels18.PETAL_DIVISOR)
        assertFalse("the old count is gone", has("Draws 5 petals", exact = true))
        k.type(q, CurveGenLabels18.PETALS, "3")
        k.type(q, CurveGenLabels18.PETAL_DIVISOR, "2")
        assertEquals(3 to 2, tool.generator!!.petalsN to tool.generator!!.petalsD)
        k.assertUnder(q, "Draws 6 petals", CurveGenLabels18.PETALS, CurveGenLabels18.PETAL_DIVISOR)

        // A drag: the line follows the rose (whatever value the finger lands on).
        k.type(q, CurveGenLabels18.PETAL_DIVISOR, "1")
        val beforeDrag = tool.generator!!
        q.dragSlider(CurveGenLabels18.PETALS, 0.3f, downPast = 0.5f)
        val dragged = tool.generator!!
        assertNotEquals("the drag changed Petals", beforeDrag.petalsN, dragged.petalsN)
        val n = dragged.petalsN
        k.assertUnder(q, "Draws ${if (n % 2 == 1) n else 2 * n} petals", CurveGenLabels18.PETALS, CurveGenLabels18.PETAL_DIVISOR)

        // 48 petals: 4 points per turn (the Curve's default) are 192 in all, under the limit.
        k.type(q, CurveGenLabels18.PETALS, "24")
        k.type(q, CurveGenLabels18.PETAL_DIVISOR, "11")
        k.assertUnder(q, "Draws 48 petals", CurveGenLabels18.PETALS, CurveGenLabels18.PETAL_DIVISOR)
        assertEquals("4 per turn shows", "4", q.sliderUnder(CurveGenLabels18.POINTS_PER_TURN).node.config.getOrNull(SemanticsProperties.StateDescription))
        val under = k.assertUnder(q, k.LIMIT, CurveGenLabels18.POINTS_PER_TURN, null)
        val top = q.sheetTop()
        assertTrue("under the limit: fewer than 400 points (${tool.anchors.size})", tool.anchors.size < CurveGenerators.MAX_ANCHORS)

        // 9 per turn: 432, at the limit; the curve has its 400 points.
        k.type(q, CurveGenLabels18.POINTS_PER_TURN, "9")
        assertEquals(9, tool.generator!!.density)
        val at = k.assertUnder(q, k.AT_LIMIT, CurveGenLabels18.POINTS_PER_TURN, null)
        assertFalse("the first line is gone", has(k.LIMIT, exact = true))
        assertEquals("the same height in both states", under, at, 0.5f)
        assertEquals("the sheet keeps its height", top, q.sheetTop(), 0.5f)
        assertEquals("at the limit: 400 points", CurveGenerators.MAX_ANCHORS, tool.anchors.size)
        val atNine = tool.anchors
        Qa18CurvesShots.save(q.capture(), "polish-a11y-curves-limit.png")

        // More add nothing: 20 per turn keeps the very same points (the value itself is kept).
        k.type(q, CurveGenLabels18.POINTS_PER_TURN, "20")
        assertEquals(20, tool.generator!!.density)
        assertEquals("20 per turn: the same curve", atNine, tool.anchors)
        k.assertUnder(q, k.AT_LIMIT, CurveGenLabels18.POINTS_PER_TURN, null)

        // A finger drags it back under the limit: the first line again, and another curve.
        q.dragSlider(CurveGenLabels18.POINTS_PER_TURN, 0.25f, downPast = 0.2f)
        val d = tool.generator!!.density
        assertTrue("dragged under 9 ($d)", d in 1..8)
        k.assertUnder(q, k.LIMIT, CurveGenLabels18.POINTS_PER_TURN, null)
        assertFalse(has(k.AT_LIMIT, exact = true))
        assertNotEquals("another curve", atNine, tool.anchors)
        assertEquals("the sheet keeps its height", top, q.sheetTop(), 0.5f)
        assertFalse("no hint that the settings are gone", has(CurveGenLabels18.EDITED_HINT, exact = true))

        val final = tool.generator!!
        Finger.back()
        assertFalse("Back closed the sheet", has(CurveGenLabels18.POINTS_PER_TURN, exact = true))
        q.applyEdit("Apply curve edit")
        assertEquals("the ✓ is one step", stepsBefore + 1, q.steps())
        val stored = q.c.activeLayer.vector!!.objects.last() as VPath
        assertEquals("stored with its settings", final, stored.generator)
        Smoke.assertQuiet(q.c, "the Curves sheet's lines")
    }
}
