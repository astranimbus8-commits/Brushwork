package com.brushwork.paint.qa18

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.tools.vector.gen.CurveGenerators
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.CogLabels18
import com.brushwork.paint.ui.common.CurveGenLabels18
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.vector.CurveGenKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 polish, verifier (items 2 and 3) on the user's phone (392 dp), the flows the fixer's tests
 * leave out:
 *
 * 1. Path tool, an Archimedean spiral (an OPEN spline, not the closed rose): "Turns" 40 at the
 *    default 8 per turn is under the limit ("At most 400 points in all"); "Points per turn" 10
 *    reaches it ("At the 400-point limit: more add nothing"; 400 samples on the curve, plus the
 *    clamped spline's two end-tangent points). The top row's Undo takes the value back and the
 *    line with it, Redo brings both again, the sheet keeps its height; the slider's state is
 *    the value shown throughout.
 * 2. The Shape tool's cog: "Tooth depth" (a LabeledSlider in another tool's sheet) is ONE node
 *    "Tooth depth slider" whose state is the value shown, and a finger drag moves both.
 * A render of the spiral's sheet after the Redo.
 *
 * One UI test, own sandbox (Compose frames stall in later tests of a sandbox).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.polisha11yverifycurvessandbox"])
class Qa18PolishA11yVerifyCurvesUndoUiTest {

    @Test
    fun theLimitLineFollowsUndoAndRedoAndAShapeSliderIsNamed() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 300_000)
        val h = ChromeHarness()
        val q = Qa18CurvesUi(h, 392f)
        h.section("an open Path spiral's limit line through Undo and Redo") { spiral(q) }
        h.section("the cog's Tooth depth slider named for a screen reader") { cog(q) }
        dog.interrupt()
        h.finish()
    }

    /** The node named "[label] slider" (one only), with its value text as its state. */
    private fun namedSlider(q: Qa18CurvesUi, label: String): RobolectricUi.Element {
        val named = RobolectricUi.elements().filter { e ->
            e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("$label slider")
        }
        assertEquals("one \"$label slider\"", 1, named.size)
        val e = named.single()
        assertTrue("\"$label slider\" sets a progress", e.node.config.getOrNull(SemanticsActions.SetProgress) != null)
        return e
    }

    /** [namedSlider], scrolled wholly into view, right under its label's text. */
    private fun shownSlider(q: Qa18CurvesUi, label: String): RobolectricUi.Element {
        val e = q.visible { namedSlider(q, label) }
        val text = RobolectricUi.elements().last { t ->
            t.node.layoutInfo.isPlaced && t.node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
        }
        assertTrue("\"$label slider\" (${e.bounds}) under its label (${text.bounds})", e.bounds.top >= text.bounds.bottom - 0.5f && e.bounds.top - text.bounds.bottom < 48f * q.s.density)
        return e
    }

    private fun state(e: RobolectricUi.Element): String? = e.node.config.getOrNull(SemanticsProperties.StateDescription)

    private fun spiral(q: Qa18CurvesUi) {
        val k = Qa18PolishA11yCurvesSheet
        q.editor(Qa18CurvesUi.vectorDoc())
        q.tool("Path")
        val tool = q.c.currentTool as CurveTool
        q.tap(CurveGenLabels18.CURVES_DESC)
        q.tap(CurveGenLabels18.ARCHIMEDEAN)
        assertEquals(CurveGenKind.ARCHIMEDEAN, tool.generator?.kind)
        StripTestKit.closeMoreRow()
        q.tap(CurveGenLabels18.CHANGE)
        assertTrue("the sheet", has(CurveGenLabels18.POINTS_PER_TURN, exact = true))
        assertFalse("no petal line for a spiral", SmokeUi.shown().any { it.startsWith("Draws ") })

        k.type(q, CurveGenLabels18.TURNS, "40")
        assertEquals(40f, tool.generator!!.turns, 0f)
        assertEquals("8 per turn (the Path default) shows", "8", state(shownSlider(q, CurveGenLabels18.POINTS_PER_TURN)))
        val under = k.assertUnder(q, k.LIMIT, CurveGenLabels18.POINTS_PER_TURN, null)
        val top = q.sheetTop()
        assertTrue("under the limit: 321 samples (${tool.spline!!.points.size} points)", tool.spline!!.points.size == 40 * 8 + 1 + 2)

        // (An in-tool step: the document's history gets the curve at ✓, as one step.)
        val steps = q.steps()
        k.type(q, CurveGenLabels18.POINTS_PER_TURN, "10")
        assertEquals(10, tool.generator!!.density)
        k.assertUnder(q, k.AT_LIMIT, CurveGenLabels18.POINTS_PER_TURN, null)
        assertEquals("\"10\" as the state", "10", state(namedSlider(q, CurveGenLabels18.POINTS_PER_TURN)))
        val atTen = tool.spline!!
        assertFalse("an open spline", atTen.cyclic)
        assertEquals("400 samples, plus the two end-tangent points", CurveGenerators.MAX_ANCHORS + 2, atTen.points.size)

        // Undo by the top row: the value and the first line come back, the sheet does not jump.
        q.undo()
        assertEquals("Undo: the default again", 8, com.brushwork.paint.ui.vector.densityShown(tool.generator!!, tool.kind))
        val back = k.assertUnder(q, k.LIMIT, CurveGenLabels18.POINTS_PER_TURN, null)
        assertFalse(has(k.AT_LIMIT, exact = true))
        assertEquals("\"8\" as the state again", "8", state(namedSlider(q, CurveGenLabels18.POINTS_PER_TURN)))
        assertEquals("the same line height", under, back, 0.5f)
        assertEquals("the sheet keeps its height", top, q.sheetTop(), 0.5f)
        assertNotEquals("another spline", atTen, tool.spline)

        // Redo: the limit again, the very same spline.
        q.redo()
        assertEquals(10, tool.generator!!.density)
        k.assertUnder(q, k.AT_LIMIT, CurveGenLabels18.POINTS_PER_TURN, null)
        assertEquals("\"10\" as the state again", "10", state(namedSlider(q, CurveGenLabels18.POINTS_PER_TURN)))
        assertEquals("the same spline", atTen, tool.spline)
        assertEquals("the sheet keeps its height", top, q.sheetTop(), 0.5f)
        Qa18CurvesShots.save(q.capture(), "polish-a11y-verify-spiral-redo.png")

        Finger.back()
        q.applyEdit("Apply path edit")
        assertEquals("the ✓ is one step", steps + 1, q.steps())
        Smoke.assertQuiet(q.c, "the spiral's limit line")
    }

    private fun cog(q: Qa18CurvesUi) {
        q.editor(Qa18CurvesUi.vectorDoc())
        q.tool("Shape")
        val tool = q.c.currentTool as ShapeTool
        q.tap("Shape type")
        q.tap(ShapeType.COG.label)
        assertEquals(ShapeType.COG, tool.settings.type)
        q.tap("Shape settings")
        val e0 = shownSlider(q, CogLabels18.TOOTH_DEPTH)
        val s0 = state(namedSlider(q, CogLabels18.TOOTH_DEPTH))
        assertTrue("its state is the value shown (\"$s0\")", s0 != null && SmokeUi.has(s0, exact = true))
        assertTrue("\"Tooth depth\" still shown as is", SmokeUi.has(CogLabels18.TOOTH_DEPTH, exact = true))
        val depth0 = tool.settings.cogDepth
        q.dragSlider(CogLabels18.TOOTH_DEPTH, 0.2f)
        assertNotEquals("the drag moved the depth", depth0, tool.settings.cogDepth)
        val s1 = state(namedSlider(q, CogLabels18.TOOTH_DEPTH))
        assertNotEquals("the state follows", s0, s1)
        assertTrue("the new state is the value shown (\"$s1\")", s1 != null && SmokeUi.has(s1, exact = true))
        assertTrue("on screen: ${e0.bounds}", e0.bounds.top >= 0f)
        Finger.back()
        Smoke.assertQuiet(q.c, "the cog's slider")
    }
}
