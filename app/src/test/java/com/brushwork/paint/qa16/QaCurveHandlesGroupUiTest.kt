package com.brushwork.paint.qa16

import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.HandleSide
import com.brushwork.paint.tools.vector.docLength
import com.brushwork.paint.tools.vector.spline.SplineEditing
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.vector.handleScaleToFraction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.6 final QA (§3.3 Bézier handle scaling) on the real editor at the user's phone size: the
 * Curve tool's Handles group — ‹ ›, the slider, the typed value and its dialog's − / + and slider,
 * In and out / In / Out, All points — with increments off and with a custom Scale step; a pinch
 * near the selected point (stepped too) and far from it (the view zooms); "Handle size" making
 * points easier to grab; and the Path point Weight dialog with a step of its own.
 *
 * (Before the fix the typed-value dialogs of Handle scale and of a Path point's Weight ignored
 * the increments: − / + went × 0.9 / × 1.1 and their sliders landed anywhere, while the strip's
 * ‹ › and slider stepped.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.curvehandlesgroupsandbox"])
class QaCurveHandlesGroupUiTest {

    private val pts = listOf(Vec2(60f, 200f), Vec2(150f, 80f), Vec2(260f, 210f), Vec2(350f, 90f))

    private fun curve(s: ChromeScreen, select: Int = 1): CurveTool {
        QaCurves.tool(s, "Curve")
        assertEquals(ToolId.CURVE, s.c.activeToolId)
        val tool = s.c.currentTool as CurveTool
        QaCurves.snapOff(s.c)
        for (p in pts) QaCurves.tap(s, p)
        assertEquals(4, tool.anchors.size)
        if (select >= 0) {
            QaCurves.tap(s, pts[select])
            assertEquals("the tap selected point ${select + 1}", select, tool.selected)
        }
        return tool
    }

    private fun CurveTool.outLen(i: Int = 1) = handlesOf(i).second.length
    private fun CurveTool.inLen(i: Int = 1) = handlesOf(i).first.length

    @Test
    fun theHandlesGroupPinchAndHandleSize() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 60_000)
        val h = ChromeHarness()
        h.section("increments off: ‹ › × 0.9 / × 1.1, slider, typed, In / Out, All points") {
            val s = h.editor()
            val tool = curve(s)
            for (label in listOf("Handles", "Shorter handles", "Longer handles", "Type handle scale", "Handles: In and out", "Handles: In", "Handles: Out", "Handles: All points")) {
                assertTrue("\"$label\": ${SmokeUi.shown()}", has(label, exact = true))
            }
            val out0 = tool.outLen()
            val in0 = tool.inLen()
            click("Longer handles")
            assertEquals("› × 1.1", out0 * 1.1f, tool.outLen(), 1e-3f)
            assertEquals("both sides", in0 * 1.1f, tool.inLen(), 1e-3f)
            assertTrue("back to 100 % at rest", has("100 %", exact = true))
            click("Shorter handles")
            assertEquals("‹ × 0.9", out0 * 1.1f * 0.9f, tool.outLen(), 1e-3f)
            click("Undo last point")
            click("Undo last point")
            assertEquals(out0, tool.outLen(), 1e-3f)
            // The slider: 200 % in one drag (one step).
            QaCurves.setSlider("Handle scale", handleScaleToFraction(2f))
            assertEquals(out0 * 2f, tool.outLen(), 1e-2f)
            click("Undo last point")
            assertEquals(out0, tool.outLen(), 1e-3f)
            // Typed: exact.
            click("Type handle scale")
            SmokeUi.typeAndDone("Handles", "137")
            assertEquals(out0 * 1.37f, tool.outLen(), 1e-3f)
            // The dialog's + (no increments): × 1.1.
            val out1 = tool.outLen()
            click("Type handle scale")
            click("Increase Handles")
            click("OK", exact = true)
            assertEquals(out1 * 1.1f, tool.outLen(), 1e-3f)
            // Out only.
            click("Handles: Out", exact = true)
            assertEquals(HandleSide.OUT, tool.handleSide)
            val o2 = tool.outLen()
            val i2 = tool.inLen()
            click("Longer handles")
            assertEquals(o2 * 1.1f, tool.outLen(), 1e-3f)
            assertEquals("In untouched", i2, tool.inLen(), 1e-3f)
            click("Handles: In", exact = true)
            click("Shorter handles")
            assertEquals(i2 * 0.9f, tool.inLen(), 1e-3f)
            click("Handles: In and out", exact = true)
            // All points: point 3's handles too.
            click("Handles: All points", exact = true)
            assertTrue(tool.handleAllPoints)
            val other = tool.outLen(2)
            click("Longer handles")
            assertEquals(other * 1.1f, tool.outLen(2), 1e-3f)
            Smoke.assertQuiet(s.c, "off")
        }
        h.section("increments on (Scale 25): ‹ ›, slider, typed exact, the dialog's − / + and slider") {
            val s = h.editor()
            QaCurves.incrementsOn(scale = "25")
            val tool = curve(s)
            val out0 = tool.outLen()
            click("Longer handles")
            assertEquals("› = 125 %", out0 * 1.25f, tool.outLen(), 1e-3f)
            click("Undo last point")
            // The slider lands on 25 % multiples (160 % → 150 %).
            QaCurves.setSlider("Handle scale", handleScaleToFraction(1.6f))
            assertEquals(out0 * 1.5f, tool.outLen(), 1e-2f)
            click("Undo last point")
            // Typed: exact.
            click("Type handle scale")
            SmokeUi.typeAndDone("Handles", "137")
            assertEquals("typed stays exact", out0 * 1.37f, tool.outLen(), 1e-3f)
            click("Undo last point")
            assertEquals(out0, tool.outLen(), 1e-3f)
            // The dialog: + goes to the next Scale multiple (125), − to the one below (75).
            click("Type handle scale")
            click("Increase Handles")
            assertTrue("+ → 125: ${SmokeUi.shown()}", SmokeUi.field("Handles").node.config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text == "125")
            click("Decrease Handles")
            click("Decrease Handles")
            assertTrue("− − → 75", SmokeUi.field("Handles").node.config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text == "75")
            // Its slider lands on multiples too (160 → 150).
            QaCurves.setSlider("Handles slider", handleScaleToFraction(1.6f))
            assertEquals("150", SmokeUi.field("Handles").node.config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
            click("OK", exact = true)
            assertEquals(out0 * 1.5f, tool.outLen(), 1e-3f)
            Smoke.assertQuiet(s.c, "on")
        }
        h.section("a pinch near the point scales its handles (stepped with increments); far away it zooms") {
            val s = h.editor()
            val tool = curve(s)
            val out0 = tool.outLen()
            val a0 = s.screen(pts[1].x, pts[1].y)
            val b0 = a0.first + 150f to a0.second + 40f
            val steps = s.c.undoManager.undoCount
            s.touch.idle(400)
            s.touch.pinch(a0, b0, a0, a0.first + 300f to a0.second + 80f)
            settle()
            assertEquals("× 2", out0 * 2f, tool.outLen(), out0 * 0.02f)
            assertEquals("in-tool, not a document step", steps, s.c.undoManager.undoCount)
            click("Undo last point")
            assertEquals("one in-tool step", out0, tool.outLen(), 1e-3f)
            // With a Scale step of 25 %: × 1.6 → 150 %.
            QaCurves.incrementsOn(scale = "25")
            val a1 = s.screen(pts[1].x, pts[1].y)
            val b1 = a1.first + 150f to a1.second
            s.touch.idle(400)
            s.touch.pinch(a1, b1, a1, a1.first + 240f to a1.second)
            settle()
            assertEquals("× 1.6 → 150 %", out0 * 1.5f, tool.outLen(), 1e-3f)
            click("Undo last point")
            assertEquals(out0, tool.outLen(), 1e-3f)
            // Far from the point: the view zooms (never stepped), the handles stay.
            val z0 = s.canvas.zoom
            val f0 = s.screen(330f, 260f)
            val g0 = f0.first - 60f to f0.second
            s.touch.idle(400)
            s.touch.pinch(f0, g0, f0.first + 60f to f0.second, g0.first - 60f to g0.second)
            settle()
            assertTrue("the view zoomed: $z0 → ${s.canvas.zoom}", s.canvas.zoom > z0 * 1.2f)
            assertEquals(out0, tool.outLen(), 1e-3f)
            Smoke.assertQuiet(s.c, "pinch")
        }
        h.section("Handle size 150 %: a point grabs from farther") {
            val s = h.editor()
            val tool = curve(s, select = -1)
            val off = s.c.docLength(30f)
            val near = Vec2(pts[0].x, pts[0].y + off)
            // 100 %: 30 dp off is outside the 24 dp grab: a new point.
            QaCurves.tap(s, near)
            assertEquals(5, tool.anchors.size)
            click("Undo last point")
            assertEquals(4, tool.anchors.size)
            // Curve settings › Handle size 150 %.
            click("Curve settings")
            assertTrue("the sheet: ${SmokeUi.shown()}", has("Handle size", exact = true))
            click("Type a value for Handle size")
            SmokeUi.typeAndDone("Handle size", "150")
            assertEquals(1.5f, s.c.settings.curveHandleScale, 1e-4f)
            click("Close", exact = true)
            QaCurves.tap(s, near)
            assertEquals("no new point", 4, tool.anchors.size)
            assertEquals("the point is grabbed (selected)", 0, tool.selected)
            Smoke.assertQuiet(s.c, "handle size")
        }
        h.section("Path point Weight: a step of its own; the dialog's + and slider follow it, typed stays exact") {
            val s = h.editor()
            QaCurves.tool(s, "Path")
            val tool = s.c.currentTool as CurveTool
            QaCurves.snapOff(s.c)
            for (p in pts) QaCurves.tap(s, p)
            QaCurves.tap(s, pts[1])
            assertEquals(1, tool.selectedPoint)
            // Long-press the weight: the Step popup ("Step for Weight"), increments on, 0.25.
            val hold = com.brushwork.paint.ui.color.RobolectricUi.elements().last { e ->
                e.node.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)?.label == "Step for Weight"
            }
            requireNotNull(hold.node.config[androidx.compose.ui.semantics.SemanticsActions.OnLongClick].action).invoke()
            settle()
            assertTrue(has("Step for Weight", exact = true))
            click("Use increments", exact = true)
            SmokeUi.typeAndDone("Weight step", "0.25")
            if (SmokeUi.has("OK", exact = true)) click("OK", exact = true)
            assertEquals(0.25f, s.c.increments.customStep("path.weight")!!, 0f)
            click("Type the point weight")
            click("Increase Weight")
            assertEquals("+ → 1.25", "1.25", SmokeUi.field("Weight").node.config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
            QaCurves.setSlider("Weight slider", SplineEditing.weightToFraction(1.6f))
            assertEquals("its slider lands on 0.25 multiples (1.6 → 1.5)", "1.5", SmokeUi.field("Weight").node.config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
            click("OK", exact = true)
            assertEquals(1.5f, tool.spline!!.points[1].weight, 1e-6f)
            click("Type the point weight")
            SmokeUi.typeAndDone("Weight", "1.37")
            assertEquals("typed: exact", 1.37f, tool.spline!!.points[1].weight, 0f)
            Smoke.assertQuiet(s.c, "weight")
        }
        dog.interrupt()
        h.finish()
    }
}
