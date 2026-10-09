package com.brushwork.paint.tools.vector

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.qa16.QaCurves
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.vector.PATH_WIDTH_CAPTION
import com.brushwork.paint.ui.vector.POINT_THICKNESS_LABEL
import com.brushwork.paint.ui.vector.POINT_WEIGHT_LABEL
import com.brushwork.paint.ui.vector.SHARP_MIXED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.7 (items 1 and 4, design §3.1a and §3.4a) on the real editor at the user's phone width: the
 * points bar of the Curve and Path tools. "Select several" (and its hint), "Select all points" /
 * "Deselect all points", the group's "Point thickness" (typed: one step for all), the three-state
 * sharp chip, "Delete point" on the group; a Path's "Sharp corner" pair for a middle point (one
 * in-tool step), disabled at an open end, and the thickness caption of a smooth middle point.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi")
class CurvePointsStripUiRobolectricTest {

    private val five = listOf(Vec2(40f, 60f), Vec2(100f, 40f), Vec2(160f, 80f), Vec2(220f, 50f), Vec2(280f, 90f))

    private fun open(s: ChromeScreen, label: String): CurveTool {
        QaCurves.tool(s, label)
        val tool = s.c.currentTool as CurveTool
        for (p in five) assertTrue(tool.addAnchor(p))
        tool.deselect()
        settle()
        return tool
    }

    private fun steps(t: CurveTool): Int {
        var n = 0
        while (t.undoStep()) n++
        repeat(n) { t.redoStep() }
        return n
    }

    @Test
    fun thePointsBar() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 120_000)
        val h = ChromeHarness()
        h.section("Curve: Select several, Select all, the group's thickness, sharp and delete") {
            val s = h.editor()
            val tool = open(s, "Curve")
            assertTrue(SmokeUi.shown().toString(), has(PointLabels.SELECT_SEVERAL, exact = true))
            assertFalse(has(PointLabels.SEVERAL_HINT, exact = true))
            click(PointLabels.SELECT_SEVERAL, exact = true)
            assertTrue(tool.selectSeveral)
            assertTrue("the hint under the bar", has(PointLabels.SEVERAL_HINT, exact = true))
            click(PointLabels.SELECT_SEVERAL, exact = true)
            assertFalse(tool.selectSeveral)
            assertFalse(has(PointLabels.SEVERAL_HINT, exact = true))

            click(PointLabels.SELECT_ALL, exact = true)
            assertEquals(5, tool.pointSelection.count)
            assertTrue(has(PointLabels.DESELECT_ALL, exact = true))
            assertFalse("no single-point slider", has("Point thickness slider"))
            assertTrue(has(POINT_THICKNESS_LABEL, exact = true))
            click(PointLabels.DESELECT_ALL, exact = true)
            assertTrue(tool.pointSelection.isEmpty)

            // Three of five: typed 50 % sets exactly those, one in-tool step.
            tool.selectPoints(PointSelection.of(5, 1, 2, 3))
            settle()
            val n = steps(tool)
            click("Type $POINT_THICKNESS_LABEL", exact = true)
            SmokeUi.typeAndDone(POINT_THICKNESS_LABEL, "50")
            assertEquals(listOf(1f, 0.5f, 0.5f, 0.5f, 1f), tool.anchors.map { it.width })
            assertEquals("one step", n + 1, steps(tool))

            // The sharp chip: all smooth, then all sharp, then mixed (a tap makes all sharp).
            click("Sharp corner", exact = true)
            assertEquals(listOf(false, true, true, true, false), tool.anchors.map { it.sharp })
            assertTrue(has("Smooth", exact = true))
            assertTrue(tool.undoStep())
            tool.setSharp(2, true)
            settle()
            assertTrue(SmokeUi.shown().toString(), has(SHARP_MIXED, exact = true))
            click(SHARP_MIXED, exact = true)
            assertEquals(listOf(false, true, true, true, false), tool.anchors.map { it.sharp })

            // Delete point: the three go (one step), two stay.
            val before = steps(tool)
            click("Delete point", exact = true)
            assertEquals(listOf(five[0], five[4]), tool.anchors.map { it.pos })
            assertEquals(before + 1, steps(tool))
            assertTrue(tool.pointSelection.isEmpty)
        }
        h.section("Path: Sharp corner on a middle point, disabled at the ends, the caption, the group's weight") {
            val s = h.editor()
            val tool = open(s, "Path")
            tool.select(2)
            settle()
            assertTrue("caption: ${SmokeUi.shown()}", has(PATH_WIDTH_CAPTION, exact = true))
            val n = steps(tool)
            click("Sharp corner", exact = true)
            assertTrue(tool.spline!!.points[2].sharp)
            assertEquals("one in-tool step", n + 1, steps(tool))
            // (Undoing everything empties the path, and so its selection: select it again.)
            tool.select(2)
            settle()
            assertTrue(has("Smooth", exact = true))
            assertFalse("a corner is reached: no caption", has(PATH_WIDTH_CAPTION, exact = true))
            // The open path's ends are always reached.
            tool.select(0)
            settle()
            assertTrue(has(PointLabels.ENDS_SHARP, exact = true))
            assertFalse(SmokeUi.isEnabled(PointLabels.ENDS_SHARP))
            assertFalse(has(PATH_WIDTH_CAPTION, exact = true))
            // Several: weight and thickness fields, the sharp chip counts the middle points only.
            tool.selectPoints(PointSelection.of(5, 0, 1, 2))
            settle()
            assertTrue(has(POINT_WEIGHT_LABEL, exact = true))
            assertTrue(has(POINT_THICKNESS_LABEL, exact = true))
            assertTrue("1 smooth, 2 sharp: ${SmokeUi.shown()}", has(SHARP_MIXED, exact = true))
            click(SHARP_MIXED, exact = true)
            assertEquals(listOf(false, true, true, false, false), tool.spline!!.points.map { it.sharp })
            click("Type $POINT_WEIGHT_LABEL", exact = true)
            SmokeUi.typeAndDone(POINT_WEIGHT_LABEL, "*2")
            assertEquals(listOf(2f, 2f, 2f, 1f, 1f), tool.spline!!.points.map { it.weight })
        }
        dog.interrupt()
        h.finish()
    }
}
