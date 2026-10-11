package com.brushwork.paint.ui.tools

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.qa16.QaCurves
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.testing.PillTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.points.PointEditor
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.common.TransformLabels17
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.abs

/**
 * v1.8 (F4, design §3.2) on the narrow phone (360 dp), every step by a finger: in Curve,
 * Polyline, Path, Shape's Points mode and Free deform (every tool whose strip had the "Select
 * several" chip in v1.7) a tap on the X / Y pill's cell (tag [V18Tags.PILL_SEVERAL]) turns it on,
 * taps on the canvas pick several points (selecting moves nothing), one drag from one of them moves
 * them together while the others stay, the top row's Undo takes the whole drag back as ONE step
 * and its Redo brings it back, and a second tap on the cell turns it off. In the curve tools a
 * second Undo then takes back the last placed point: the picking taps were no steps.
 *
 * One test: Compose's frame clock only serves the first test of a Robolectric sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.tools.pillseveralfingersandbox"])
class PillSeveralFingerTest {

    private val five = listOf(Vec2(80f, 200f), Vec2(140f, 110f), Vec2(200f, 190f), Vec2(260f, 110f), Vec2(320f, 200f))
    private val by = Vec2(10f, 20f)

    private fun points(t: PointEditor): List<Vec2> = (0 until t.pointCount).map { t.pointAt(it) }

    private fun assertNear(what: String, a: Vec2, b: Vec2, tol: Float) =
        assertTrue("$what: $a vs $b", abs(a.x - b.x) <= tol && abs(a.y - b.y) <= tol)

    private fun assertAll(what: String, expected: List<Vec2>, t: PointEditor, tol: Float) {
        val now = points(t)
        assertEquals("$what: point count", expected.size, now.size)
        for (i in expected.indices) assertNear("$what: point $i", expected[i], now[i], tol)
    }

    /** A finger on the cell (the centre of its bounds, on screen and 40 dp); the flag and the state follow. */
    private fun tapCell(s: ChromeScreen, t: PointEditor, on: Boolean, what: String) {
        val r = requireNotNull(s.tagged(V18Tags.PILL_SEVERAL)) { "$what: the cell shows; shown: ${SmokeUi.shown().take(80)}" }
        assertTrue("$what: the cell lies on screen: $r", r.left >= 0f && r.right <= s.widthDp + 0.5f && r.top >= 0f && r.bottom <= s.heightDp + 0.5f)
        assertTrue("$what: a finger's size: $r", r.width >= 40f - 0.5f && r.height >= 40f - 0.5f)
        Finger.tapAt(s, r.center.x, r.center.y)
        assertEquals("$what: Select several", on, t.selectSeveral)
        assertEquals("$what: the cell's state", if (on) "On" else "Off", PillTestKit.severalState())
    }

    /**
     * The cell on; [picked] tapped; the group dragged from point [grab] by [by]: every picked point
     * moves by the grabbed point's move (the tool's own increments may round it), the others stay;
     * the top row's Undo takes the drag back whole ([undone] then runs), its Redo brings it back;
     * the cell off. Returns the points as dragged.
     */
    private fun groupDrag(
        s: ChromeScreen,
        t: PointEditor,
        what: String,
        picked: List<Int>,
        grab: Int,
        tol: Float,
        undone: (start: List<Vec2>) -> Unit = {},
    ): List<Vec2> {
        tapCell(s, t, on = true, what = "$what, on")
        val start = points(t)
        for (i in picked) QaCurves.tap(s, start[i])
        assertEquals("$what: the taps picked", picked, t.pointSelection.indices)
        assertAll("$what: picking moved nothing", start, t, tol)
        QaCurves.drag(s, start[grab], by)
        val d = t.pointAt(grab) - start[grab]
        assertTrue("$what: the grabbed point moved: $d", d.length > 5f)
        val moved = start.mapIndexed { i, p -> if (i in picked) p + d else p }
        assertAll("$what: dragged together", moved, t, tol)
        Finger.tap(s, "Undo")
        assertAll("$what: ONE Undo takes the drag back", start, t, tol)
        assertEquals("$what: with the selection", picked, t.pointSelection.indices)
        undone(start)
        Finger.tap(s, "Redo")
        assertAll("$what: Redo brings it back", moved, t, tol)
        tapCell(s, t, on = false, what = "$what, off")
        return moved
    }

    @Test
    fun severalPointsPickedDraggedUndoneAndRedoneByFingers() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 240_000)
        val h = ChromeHarness()

        for (label in listOf("Curve", "Polyline", "Path")) {
            h.section("$label: several points by fingers") {
                val s = h.editor()
                assertEquals(360f, s.widthDp, 1f)
                QaCurves.tool(s, label)
                val tool = s.c.currentTool as CurveTool
                QaCurves.snapOff(s.c)
                assertNull("$label: no cell before a point", s.tagged(V18Tags.PILL_SEVERAL))
                for (p in five) QaCurves.tap(s, p)
                assertEquals("$label: five points by five taps", 5, tool.pointCount)
                // The lowest of the three sits ON the gizmo's bottom handle: the point wins the finger.
                // The picking taps were no steps: the Undo after the drag's takes back the fifth point.
                groupDrag(s, tool, label, picked = listOf(1, 2, 3), grab = 2, tol = 0.05f) { start ->
                    Finger.tap(s, "Undo")
                    assertEquals("$label: the next Undo takes back the last point placed", 4, tool.pointCount)
                    Finger.tap(s, "Redo")
                    assertAll("$label: the point is back", start, tool, 0.05f)
                }
                tool.discard()
                settle()
                assertNull("$label: discarded, no cell", s.tagged(V18Tags.PILL_SEVERAL))
                Smoke.assertQuiet(s.c, label)
            }
        }

        h.section("Shape: several corners in Points mode by fingers") {
            val s = h.editor()
            val ui = Qa16Ui(s)
            QaCurves.tool(s, "Shape")
            val tool = s.c.currentTool as ShapeTool
            QaCurves.snapOff(s.c)
            QaCurves.drag(s, Vec2(60f, 70f), Vec2(170f, 150f))
            assertNotNull("a pending rectangle", tool.box)
            ui.reach("Points", 32f)
            settle()
            Finger.tap(s, "Points")
            assertTrue(tool.pointsMode)
            assertEquals(4, tool.pointCount)
            groupDrag(s, tool, "Shape", picked = listOf(0, 1, 2), grab = 0, tol = 0.05f)
            assertTrue("still in Points mode", tool.pointsMode)
            assertNotNull("the shape still pending", tool.box)
            tool.discard()
            settle()
            Smoke.assertQuiet(s.c, "Shape")
        }

        h.section("Free deform: several vertices by fingers") {
            val s = h.editor()
            val ui = Qa16Ui(s)
            s.c.selectLayer(0)
            settle()
            QaCurves.tool(s, "Transform")
            val tool = s.c.currentTool as TransformTool
            assertTrue(Smoke.pumpUntil { settle(1); tool.transformState != null })
            settle()
            ui.reach(TransformLabels17.FREE_DEFORM, 32f)
            settle()
            Finger.tap(s, TransformLabels17.FREE_DEFORM)
            assertTrue(Smoke.pumpUntil { settle(1); tool.isMeshShown })
            settle()
            assertTrue("a mesh of at least 2 × 2 cells", tool.meshColumns >= 2 && tool.meshRows >= 2)
            val stride = tool.meshColumns + 1
            groupDrag(s, tool, "Free deform", picked = listOf(stride + 1, stride + 2, 2 * stride + 1), grab = stride + 1, tol = 0.5f)
            assertTrue("still free deforming", tool.isMeshShown)
            tool.discard()
            s.c.selectTool(ToolId.BRUSH)
            settle()
            Smoke.assertQuiet(s.c, "Free deform")
        }

        dog.interrupt()
        h.finish()
    }
}
