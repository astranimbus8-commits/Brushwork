package com.brushwork.paint.qa18

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.vector.CurveSettings
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.gen.CurveGenerators
import com.brushwork.paint.ui.common.CurveGenLabels18
import com.brushwork.paint.ui.common.CurveLabels17
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.vector.CurveGenerator
import com.brushwork.paint.vector.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 final QA (item 6, design §3.7, I16): a generated curve stays editable until a point is
 * moved by hand, on the phone (392 dp), by finger:
 * - a committed spiral reopens by a tap on its line with its settings and its chip;
 * - a point dragged by hand (the sheet open) drops the settings: the hint (once per session),
 *   the chip and the sheet go; Undo brings them back; ✓ stores the curve without settings;
 * - the canvas handles move and scale a rose, one Undo each; all points selected and moved
 *   carry the settings along;
 * - the stroke kind, Use brush size, the line width and Taper ends leave the settings alone and
 *   stay the tool's after ✓, on a spiral and on a rose alike (only a rose's Closed is its own),
 *   also when the rose was undone away and redone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.curveseditsandbox"])
class Qa18CurvesEditUiTest {

    private fun chipShown(q: Qa18CurvesUi): Boolean =
        Finger.control(q.s, CurveGenLabels18.CHANGE) != null || StripTestKit.reach { SmokeUi.find(CurveGenLabels18.CHANGE, exact = true) } != null

    /** A finger drag on the canvas from [a] by [d] (document px), at a finger's pace. */
    private fun drag(q: Qa18CurvesUi, a: Vec2, d: Vec2) = q.strokeDoc(a, a + d * 0.25f, a + d * 0.5f, a + d * 0.75f, a + d)

    /**
     * A middle point of the open curve the finger can reach: shown above the sheet (when one is
     * open) and clear of the generator's canvas handles and of the other points.
     */
    private fun reachablePoint(q: Qa18CurvesUi, tool: CurveTool): Int {
        val top = q.sheetTop()
        val h = tool.generatorHandles
        val pts = tool.anchors.map { it.pos }
        return (1 until pts.size - 1).filter { i ->
            val p = pts[i]
            val (_, sy) = q.s.screen(p.x, p.y)
            sy < top - 60f && (h == null || (p.distanceTo(h.centre) > 40f && p.distanceTo(h.size) > 40f)) &&
                pts.indices.none { j -> j != i && pts[j].distanceTo(p) < 16f }
        }.maxByOrNull { i -> pts[i].distanceTo(Vec2(tool.generator?.cx ?: 0f, tool.generator?.cy ?: 0f)) }
            ?: throw AssertionError("no point to drag: ${pts.size} points, the sheet at $top")
    }

    /** Reopen by a tap, a hand edit with the sheet open, Undo, the hint once, ✓ without settings. */
    private fun handEdit(q: Qa18CurvesUi, tool: CurveTool) {
        val c = q.c
        q.tap(CurveGenLabels18.CURVES_DESC)
        q.tap(CurveGenLabels18.ARCHIMEDEAN)
        q.applyEdit("Apply curve edit")
        val layer = c.activeLayer
        val stored = layer.vector!!.objects.last() as VPath
        val g = requireNotNull(stored.generator) { "stored with its settings" }
        val count = layer.vector!!.objects.size
        val steps = q.steps()

        q.tapDoc(q.onLine(stored, 0.85f))
        assertEquals("a tap on its line reopens it with its settings", g, tool.generator)
        assertTrue("its chip", chipShown(q))
        StripTestKit.closeMoreRow()
        q.tap(CurveGenLabels18.CHANGE)
        assertTrue("the sheet", has(CurveGenLabels18.POINTS_PER_TURN, exact = true))

        c.message = null
        val i = reachablePoint(q, tool)
        val a = tool.anchors[i].pos
        val before = tool.anchors
        drag(q, a, Vec2(14f, 10f))
        assertNull("a point moved by hand drops the settings", tool.generator)
        assertEquals("moved", a.x + 14f, tool.anchors[i].pos.x, 1.5f)
        assertTrue("the hint, when the finger lifts", has(CurveGenLabels18.EDITED_HINT, exact = true))
        assertFalse("the sheet went with the settings", has(CurveGenLabels18.POINTS_PER_TURN, exact = true))
        assertFalse("the chip went", chipShown(q))
        StripTestKit.closeMoreRow()

        q.undo()
        assertEquals("Undo brings the settings back", g, tool.generator)
        assertEquals("and the points", before, tool.anchors)
        assertTrue("and the chip", chipShown(q))
        StripTestKit.closeMoreRow()

        // The first hint's snackbar goes; the same edit again shows none.
        assertTrue("the hint goes", Smoke.pumpUntil(Qa18CurvesUi.WAIT_MS) { Smoke.pump(200); !has(CurveGenLabels18.EDITED_HINT, exact = true) })
        drag(q, a, Vec2(14f, 10f))
        assertNull(tool.generator)
        assertFalse("the hint once per session", has(CurveGenLabels18.EDITED_HINT, exact = true))
        q.applyEdit("Apply curve edit")
        assertEquals("the ✓ is one step", steps + 1, q.steps())
        assertEquals("the edited curve replaced the old one", count, layer.vector!!.objects.size)
        val edited = layer.vector!!.objects.last() as VPath
        assertNull("stored without settings", edited.generator)
        Smoke.assertQuiet(c, "hand edit")
    }

    /** The canvas handles of a rose: the centre moves it, the size handle scales it; one Undo each. */
    private fun handles(q: Qa18CurvesUi, tool: CurveTool) {
        q.tap(CurveGenLabels18.CURVES_DESC)
        q.tap(CurveGenLabels18.ROSE)
        StripTestKit.closeMoreRow()
        val g0 = tool.generator!!
        val h0 = tool.generatorHandles!!
        drag(q, h0.centre, Vec2(24f, 12f))
        val g1 = tool.generator ?: throw AssertionError("the centre handle dropped the settings")
        assertEquals("the centre handle moves it", g0.copy(cx = g1.cx, cy = g1.cy), g1)
        assertEquals(g0.cx + 24f, g1.cx, 1.5f)
        assertEquals(g0.cy + 12f, g1.cy, 1.5f)
        assertTrue(CurveGenerators.matches(tool.buildVPath()!!))

        val h1 = tool.generatorHandles!!
        val out = (h1.size - h1.centre).normalized()
        drag(q, h1.size, out * 30f)
        val g2 = tool.generator ?: throw AssertionError("the size handle dropped the settings")
        assertEquals("the size handle scales it", g1.size + 30f, g2.size, 2f)
        assertEquals(g1.cx, g2.cx, 0.01f)
        assertTrue(CurveGenerators.matches(tool.buildVPath()!!))

        q.undo()
        assertEquals("one Undo takes the scale back", g1, tool.generator)
        q.undo()
        assertEquals("one Undo takes the move back", g0, tool.generator)
        q.redo(); q.redo()
        assertEquals(g2, tool.generator)

        // Every point selected and moved together: the settings follow.
        q.tap(PointLabels.SELECT_ALL)
        StripTestKit.closeMoreRow()
        val i = reachablePoint(q, tool)
        drag(q, tool.anchors[i].pos, Vec2(-16f, 10f))
        val g3 = tool.generator ?: throw AssertionError("moving every point dropped the settings")
        assertEquals("all points moved: the curve moved", g2.cx - 16f, g3.cx, 1.5f)
        assertEquals(g2.size, g3.size, 0.01f)
        assertTrue(CurveGenerators.matches(tool.buildVPath()!!))
        q.tap(PointLabels.DESELECT_ALL)
        StripTestKit.closeMoreRow()
    }

    /** The strip's look controls (stroke kind, Use brush size, the line width, Taper ends) leave the curve's settings alone. */
    private fun look(q: Qa18CurvesUi, tool: CurveTool, g: CurveGenerator) {
        q.tap(CurveLabels17.STROKE_KIND)
        q.tap(CurveStroke.PLAIN.label)
        assertEquals("Plain line", CurveStroke.PLAIN, tool.settings.stroke)
        assertEquals("the stroke kind is no edit of the curve", g, tool.generator)
        val linked = tool.settings.useBrushSize
        q.tap("Use brush size")
        assertEquals(!linked, tool.settings.useBrushSize)
        assertEquals("Use brush size is no edit of the curve", g, tool.generator)
        StripTestKit.closeMoreRow()
        if (tool.settings.useBrushSize) { q.tap("Use brush size"); StripTestKit.closeMoreRow() }
        q.tap("Curve settings")
        val width = tool.lineWidth
        q.dragSlider("Line width", 0.15f, downPast = 0.5f)
        assertNotEquals("the line width changed", width, tool.lineWidth, 0.01f)
        assertEquals("the line width is no edit of the curve", g, tool.generator)
        Finger.back()
        q.tap(CurveLabels17.STROKE_KIND)
        q.tap(CurveStroke.BRUSH.label)
        assertEquals(CurveStroke.BRUSH, tool.settings.stroke)
        val tapered = tool.settings.taper
        q.tap("Taper ends")
        assertEquals(!tapered, tool.settings.taper)
        assertEquals("Taper ends is no edit of the curve", g, tool.generator)
        StripTestKit.closeMoreRow()
        assertTrue(CurveGenerators.matches(tool.buildVPath()!!))
    }


    @Test
    fun aGeneratedCurveStaysEditableUntilAPointIsMovedByHand() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 300_000)
        val h = ChromeHarness()
        val q = Qa18CurvesUi(h, 392f)
        h.section("reopen, a hand edit, Undo, the hint once, ✓") {
            q.editor(Qa18CurvesUi.vectorDoc())
            q.tool("Curve")
            handEdit(q, q.c.currentTool as CurveTool)
        }
        h.section("a rose's canvas handles, every point moved, the look") {
            q.editor(Qa18CurvesUi.vectorDoc())
            q.tool("Curve")
            val tool = q.c.currentTool as CurveTool
            val own = tool.settings
            assertFalse("the user's curves are open", own.closed)
            handles(q, tool)
            assertTrue("a rose shows Closed", tool.settings.closed)
            look(q, tool, tool.generator!!)
            val shown = tool.settings
            q.applyEdit("Apply curve edit")
            keptLook(q, tool, shown, "a rose")
        }
        h.section("the look on a spiral, and on a rose undone and redone") {
            q.editor(Qa18CurvesUi.vectorDoc())
            q.tool("Curve")
            val tool = q.c.currentTool as CurveTool
            q.tap(CurveGenLabels18.CURVES_DESC)
            q.tap(CurveGenLabels18.GOLDEN)
            StripTestKit.closeMoreRow()
            q.tap("Taper ends")
            StripTestKit.closeMoreRow()
            val spiral = tool.settings
            q.applyEdit("Apply curve edit")
            keptLook(q, tool, spiral, "a spiral")

            // A rose: Taper ends; Undo takes the rose away (the curve's Closed goes with it, the
            // look stays); Redo brings it back closed; Taper ends again; ✓.
            q.tap(CurveGenLabels18.CURVES_DESC)
            q.tap(CurveGenLabels18.ROSE)
            StripTestKit.closeMoreRow()
            q.tap("Taper ends")
            StripTestKit.closeMoreRow()
            val tapered = tool.settings.taper
            q.undo()
            assertEquals("Undo took the rose away", 0, tool.pointCount)
            assertFalse("the user's Closed is back", tool.settings.closed)
            assertEquals("Taper ends stays as set", tapered, tool.settings.taper)
            q.redo()
            assertNotNull("Redo brought the rose back", tool.generator)
            assertTrue(tool.settings.closed)
            q.tap("Taper ends")
            StripTestKit.closeMoreRow()
            val redone = tool.settings
            q.applyEdit("Apply curve edit")
            keptLook(q, tool, redone, "a redone rose")
        }
        dog.interrupt()
        h.finish()
    }

    /**
     * After the ✓ of a generated curve whose look was set up as [shown]: the next curve looks the
     * same (only a rose's Closed was its own), in the tool and in its saved settings.
     */
    private fun keptLook(q: Qa18CurvesUi, tool: CurveTool, shown: CurveSettings, what: String) {
        assertEquals("$what: the look set on it stays the tool's", shown.copy(closed = false), tool.settings)
        assertEquals("$what: and is saved", tool.settings, q.c.settings.getObject("vec.curve", CurveSettings.serializer()))
    }
}
