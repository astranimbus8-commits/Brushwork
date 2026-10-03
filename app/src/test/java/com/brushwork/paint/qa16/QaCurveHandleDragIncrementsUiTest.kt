package com.brushwork.paint.qa16

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.6 final QA (increments everywhere): a Bézier tangent handle dragged with the Curve tool on
 * the real editor. ARCHITECTURE ("Curve / Path points and handles" step) and the Shape tool's
 * handles say a handle's end moves by multiples of the Length step from where it was grabbed
 * while increments are on, an object guide winning on its axis, the move in the info chip; with
 * increments off the drag is free, exactly v1.5 (I8).
 *
 * (Before the fix the Curve tool's handle drag ignored the increments: +37, +12 px moved the end
 * +37, +12 with a 25 px step on.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.curvehandledragsandbox"])
class QaCurveHandleDragIncrementsUiTest {

    private val pts = listOf(Vec2(80f, 200f), Vec2(200f, 80f), Vec2(320f, 200f))

    /** Curve tool, three tapped points, the middle one tapped (selected): its handles show. */
    private fun curveWithSelectedMiddle(s: ChromeScreen): CurveTool {
        QaCurves.tool(s, "Curve")
        assertEquals(ToolId.CURVE, s.c.activeToolId)
        val tool = s.c.currentTool as CurveTool
        for (p in pts) QaCurves.tap(s, p)
        assertEquals(3, tool.anchors.size)
        QaCurves.tap(s, pts[1])
        assertEquals("the tap selected the middle point", 1, tool.selected)
        return tool
    }

    /** Drags the selected middle point's out-handle end by [by]; returns (end before, end after, readout while held). */
    private fun dragOutHandle(s: ChromeScreen, tool: CurveTool, by: Vec2): Triple<Vec2, Vec2, String?> {
        val a = tool.anchors[1].pos
        val start = a + tool.handlesOf(1).second
        var held: String? = null
        QaCurves.drag(s, start, by) { held = s.c.increments.readout; if (held != null) assertTrue("the chip shows \"$held\": ${SmokeUi.shown()}", has(held!!, exact = true)) }
        return Triple(start, tool.anchors[1].pos + tool.handlesOf(1).second, held)
    }

    @Test
    fun aDraggedTangentHandleStepsByTheLengthIncrement() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 60_000)
        val h = ChromeHarness()
        h.section("increments off: the handle follows the finger (v1.5)") {
            val s = h.editor()
            val tool = curveWithSelectedMiddle(s)
            QaCurves.snapOff(s.c)
            val (start, end, held) = dragOutHandle(s, tool, Vec2(37f, 12f))
            assertEquals(start.x + 37f, end.x, 0.05f)
            assertEquals(start.y + 12f, end.y, 0.05f)
            assertNull("no readout without increments", held)
            Smoke.assertQuiet(s.c, "off")
        }
        h.section("increments on (Length 25): the handle's end moves by whole steps") {
            val s = h.editor()
            QaCurves.incrementsOn(length = "25")
            assertEquals(25f, s.c.increments.step(com.brushwork.paint.model.IncrementKind.LENGTH)!!, 0f)
            val tool = curveWithSelectedMiddle(s)
            QaCurves.snapOff(s.c)
            val (start, end, held) = dragOutHandle(s, tool, Vec2(37f, 12f))
            assertEquals("x: +37 → one 25 px step", start.x + 25f, end.x, 0.01f)
            assertEquals("y: +12 → no step", start.y, end.y, 0.01f)
            assertEquals("the chip showed the move", "+25, 0 px", held)
            assertNull("the readout goes when the finger lifts", s.c.increments.readout)
            // The in handle stays opposite (a smooth point).
            val (hIn, hOut) = tool.handlesOf(1)
            assertEquals(0f, hIn.x * hOut.y - hIn.y * hOut.x, 1e-2f)
            // One in-tool step: undo puts the handle back, redo moves it again.
            click("Undo last point")
            assertEquals(start.x, (tool.anchors[1].pos + tool.handlesOf(1).second).x, 0.01f)
            click("Redo point")
            assertEquals(end.x, (tool.anchors[1].pos + tool.handlesOf(1).second).x, 0.01f)
            // −63, +40: whole steps from the grab on both axes.
            val (s2, e2, _) = dragOutHandle(s, tool, Vec2(-63f, 40f))
            assertTrue("x on a step from the grab: ${e2.x - s2.x}", QaCurves.onStep(e2.x - s2.x, 25f))
            assertTrue("y on a step from the grab: ${e2.y - s2.y}", QaCurves.onStep(e2.y - s2.y, 25f))
            Smoke.assertQuiet(s.c, "on")
        }
        h.section("increments on, Snap to objects on: a guide wins on its axis") {
            val s = h.editor()
            QaCurves.incrementsOn(length = "25")
            val tool = curveWithSelectedMiddle(s)
            assertTrue("snapping is on by default", s.c.snapping.enabled)
            val a = tool.anchors[1].pos
            val start = a + tool.handlesOf(1).second
            // To x = 320 + 1 (point 3's vertical line, within reach): x snaps to 320, not to a step.
            val by = Vec2(321f - start.x, 37f)
            val (_, end, _) = dragOutHandle(s, tool, by)
            assertEquals("the guide placed x", 320f, end.x, 0.01f)
            assertEquals("y still steps (+37 → +25)", start.y + 25f, end.y, 0.01f)
            Smoke.assertQuiet(s.c, "guide")
        }
        dog.interrupt()
        h.finish()
    }
}
