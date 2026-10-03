package com.brushwork.paint.ui.vector

import android.view.MotionEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.Smoke.P
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.spline.SplineEditing
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.6 §3.4 on the real editor with G's increments service (`controller.increments`), at the
 * user's phone size, by touch where a finger would act:
 * - a Path control point dragged through the canvas view with increments on (switched on with the
 *   X / Y pill's "#" cell) moves by multiples of the Length step from where it was, the top info
 *   chip shows the move while the finger is down and goes when it lifts, and the drag is one
 *   in-tool step; with increments off the same drag is free (I8);
 * - the Curve tool's Handle scale slider lands on the Scale step's multiples (one step per
 *   drag), and a press of › steps one Scale increment with "110 %" in the info chip while held.
 */
// Own sandbox (the test recomposer policy and paused Choreographer are global); the user's phone size.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.vector.vectorincrementssandbox"])
class VectorIncrementsEditorUiRobolectricTest {

    private val points = listOf(Vec2(60f, 200f), Vec2(150f, 80f), Vec2(260f, 210f), Vec2(350f, 90f))

    /** One finger from document point [from] by [by] (document px), checking [held] before it lifts. */
    private fun drag(s: ChromeScreen, from: Vec2, by: Vec2, held: () -> Unit) {
        val a = s.screen(from.x, from.y)
        val b = s.screen(from.x + by.x, from.y + by.y)
        s.touch.send(MotionEvent.ACTION_DOWN, P(0, a.first, a.second))
        for (i in 1..8) {
            s.touch.idle(16)
            s.touch.send(MotionEvent.ACTION_MOVE, P(0, a.first + (b.first - a.first) * i / 8f, a.second + (b.second - a.second) * i / 8f))
        }
        s.touch.idle(16)
        settle(2)
        held()
        s.touch.send(MotionEvent.ACTION_UP, P(0, b.first, b.second))
        s.touch.idle(50)
        settle()
    }

    private fun node(description: String): SemanticsNode = RobolectricUi.elements().last { e ->
        e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true
    }.node

    /** Scrolls the options strip until the node described [description] shows whole. */
    private fun scrollStripTo(s: ChromeScreen, description: String) {
        fun whole() = node(description).let { it.boundsInWindow.width >= it.size.width - 1f && it.boundsInWindow.height >= it.size.height - 1f }
        if (whole()) return
        var p: SemanticsNode? = node(description)
        while (p != null && p.config.getOrNull(SemanticsActions.ScrollBy) == null) p = p.parent
        val scroll = requireNotNull(p?.config?.getOrNull(SemanticsActions.ScrollBy)?.action) { "\"$description\" is clipped and does not scroll" }
        repeat(40) {
            if (whole()) return
            scroll.invoke(120f * s.density, 0f)
            settle(8)
        }
        throw AssertionError("\"$description\" never scrolled into view")
    }

    @Test
    fun steppedPathPointsAndHandlesOnTheEditor() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = ChromeHarness()
        h.section("a Path point dragged by touch") {
            val s = h.editor()
            s.c.selectTool(ToolId.PATH)
            settle()
            val tool = s.c.tools.getValue(ToolId.PATH) as CurveTool
            for (p in points) tool.addAnchor(p)
            tool.select(1)
            settle()
            // The X / Y pill's "#" cell switches increments on (Length 10 px by default).
            assertFalse(s.c.increments.enabled)
            click("Increments", exact = true)
            assertTrue("the # cell switched increments on", s.c.increments.enabled)
            assertEquals(10f, s.c.increments.step(com.brushwork.paint.model.IncrementKind.LENGTH))
            val undo0 = s.c.undoManager.undoCount

            // (Away from the other points' x and y: an object guide would take the axis first.)
            drag(s, points[1], Vec2(23.4f, -27.2f)) {
                assertEquals("the move so far, in Length steps", "+20, −30 px", s.c.increments.readout)
                assertTrue("the info chip shows it: ${SmokeUi.shown()}", SmokeUi.has("+20, −30 px", exact = true))
                assertEquals(Vec2(170f, 50f), SplineEditing.pos(tool.spline!!.points[1]))
            }
            assertNull("the readout goes when the finger lifts", s.c.increments.readout)
            assertEquals("on multiples of 10 px from where it was", Vec2(170f, 50f), SplineEditing.pos(tool.spline!!.points[1]))
            assertEquals("an in-tool step, not a document one", undo0, s.c.undoManager.undoCount)
            assertTrue(tool.undoStep())
            assertEquals("the drag was one step", points[1], SplineEditing.pos(tool.spline!!.points[1]))

            // Off again (the same cell): the same drag is free, with no readout (I8).
            tool.select(2)
            settle()
            click("Increments", exact = true)
            assertFalse(s.c.increments.enabled)
            drag(s, points[2], Vec2(-23.4f, -47.3f)) {
                assertNull("no readout while increments are off", s.c.increments.readout)
            }
            val free = SplineEditing.pos(tool.spline!!.points[2])
            assertEquals(236.6f, free.x, 0.05f)
            assertEquals(162.7f, free.y, 0.05f)
            tool.discard()
            settle()
            Smoke.assertQuiet(s.c, "path drag")
        }
        h.section("the Curve tool's Handle scale slider and › with increments on") {
            val s = h.editor()
            s.c.selectTool(ToolId.CURVE)
            settle()
            val tool = s.c.tools.getValue(ToolId.CURVE) as CurveTool
            for (p in points) tool.addAnchor(p)
            tool.select(1)
            settle()
            s.c.increments.update { it.copy(enabled = true) }
            settle()
            val out0 = tool.handlesOf(1).second.length

            // The slider at 127 %: 130 % (Scale 10 %), one step, back to 100 % at rest.
            val slider = RobolectricUi.elements().last { e ->
                e.node.config.contains(SemanticsActions.SetProgress) &&
                    e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Handle scale") == true
            }
            requireNotNull(slider.node.config.getOrNull(SemanticsActions.SetProgress)?.action).invoke(handleScaleToFraction(1.27f))
            settle(4)
            assertEquals("130 %", out0 * 1.3f, tool.handlesOf(1).second.length, 1e-2f * out0)
            assertEquals(1f, tool.handleScale, 0f)
            assertNull(s.c.increments.readout)
            assertTrue(tool.undoStep())
            assertEquals(out0, tool.handlesOf(1).second.length, 1e-3f * out0)

            // › pressed (not held long enough to repeat): one Scale step, shown while held.
            scrollStripTo(s, "Longer handles")
            val r = node("Longer handles").boundsInWindow
            val x = (r.left + r.right) / 2f
            val y = (r.top + r.bottom) / 2f
            s.touch.send(MotionEvent.ACTION_DOWN, P(0, x, y))
            s.touch.idle(100)
            settle(2)
            assertEquals(1.1f, tool.handleScale, 1e-4f)
            assertEquals("110 %", s.c.increments.readout)
            assertTrue("the info chip shows it: ${SmokeUi.shown()}", SmokeUi.has("110 %", exact = true))
            s.touch.send(MotionEvent.ACTION_UP, P(0, x, y))
            s.touch.idle(50)
            settle()
            assertNull("the readout goes on release", s.c.increments.readout)
            assertEquals("back to 100 % at rest", 1f, tool.handleScale, 0f)
            assertEquals(out0 * 1.1f, tool.handlesOf(1).second.length, 1e-3f * out0)
            assertTrue(tool.undoStep())
            assertEquals("one step", out0, tool.handlesOf(1).second.length, 1e-3f * out0)
            tool.discard()
            settle()
            Smoke.assertQuiet(s.c, "handles")
        }
        h.section("a Path point's weight with a step of its own") {
            val s = h.editor()
            s.c.selectTool(ToolId.PATH)
            settle()
            val tool = s.c.tools.getValue(ToolId.PATH) as CurveTool
            for (p in points) tool.addAnchor(p)
            tool.select(1)
            settle()
            s.c.increments.update { it.copy(enabled = true) }
            settle()
            // A long-press on the weight opens its Step popup ("path.weight" → "Step for Weight").
            val hold = RobolectricUi.elements().last { e -> e.node.config.getOrNull(SemanticsActions.OnLongClick)?.label == "Step for Weight" }
            requireNotNull(hold.node.config[SemanticsActions.OnLongClick].action).invoke()
            settle()
            assertTrue("the Step popup: ${SmokeUi.shown()}", SmokeUi.has("Step for Weight", exact = true))
            SmokeUi.typeAndDone("Weight step", "0.5")
            assertEquals(0.5f, s.c.increments.customStep(PATH_WEIGHT_KEY))
            // The slider lands on multiples of 0.5 (one step per drag).
            val slider = RobolectricUi.elements().last { e ->
                e.node.config.contains(SemanticsActions.SetProgress) &&
                    e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Point weight slider") == true
            }
            requireNotNull(slider.node.config.getOrNull(SemanticsActions.SetProgress)?.action).invoke(SplineEditing.weightToFraction(2.3f))
            settle(4)
            assertEquals(2.5f, tool.spline!!.points[1].weight, 0f)
            assertTrue(tool.undoStep())
            assertEquals(1f, tool.spline!!.points[1].weight, 0f)
            tool.discard()
            settle()
            Smoke.assertQuiet(s.c, "weight step")
        }
        dog.interrupt()
        h.finish()
    }
}
