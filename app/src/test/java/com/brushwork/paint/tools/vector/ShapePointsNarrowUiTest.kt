package com.brushwork.paint.tools.vector

import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.points.Mixed
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.vector.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.7 items 1, 2 and 6 for shapes (design §3.1, §3.2, §3.6; area C) through the editor screen
 * at 392 dp, the phone's width: in Points mode the strip reaches "Select several" (its hint as
 * the editor's message), "Select all points", "Point roundness" (typed, then "Reset point
 * roundness") and "Turn into path", each wholly on screen (chips at least 32 dp, the field and
 * the button 40 dp), and each does what it says. Its own sandbox: one test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.tools.vector.shapepointsnarrowsandbox"])
class ShapePointsNarrowUiTest {

    private lateinit var h: ChromeHarness

    @Test
    fun shapePointsAt392Dp() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        h = ChromeHarness()
        h.section("the Points strip of a shape at 392 dp") { strip() }
        dog.interrupt()
        h.finish()
    }

    private fun strip() {
        val s = h.editor(Smoke.document(480, 320, layers = 2, whiteBottom = true)) { it.snapping.enabled = false }
        assertEquals("the phone is 392 dp wide", 392f, s.widthDp, 1f)
        val ui = Qa16Ui(s)
        val c = s.c
        c.selectTool(ToolId.SHAPE)
        SmokeUi.settle()
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        tool.update {
            it.copy(
                type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE_FILL, useBrushSize = false, strokeWidth = 5f,
                fillColor = 0xFF40B060.toInt(), corner = CornerStyle.SHARP, keepProportions = false, fromCenter = false,
            )
        }
        ui.stroke(120f to 80f, 200f to 140f, 300f to 220f)
        assertTrue("a pending shape", tool.hasPendingWork)

        ui.reach("Points", CHIP_DP)
        click("Points", exact = true)
        assertTrue(tool.pointsMode)
        assertEquals(4, tool.pointCount)

        // "Select several": on, with its one-line hint.
        ui.reach(PointLabels.SELECT_SEVERAL, CHIP_DP)
        click(PointLabels.SELECT_SEVERAL, exact = true)
        assertTrue(tool.selectSeveral)
        assertTrue("the hint on screen", has(PointLabels.SEVERAL_HINT, exact = true))

        // "Select all points": the four corners; the chip then reads "Deselect all".
        ui.reach(PointLabels.SELECT_ALL, CHIP_DP)
        click(PointLabels.SELECT_ALL, exact = true)
        assertEquals(4, tool.pointSelection.count)
        assertTrue(has(PointLabels.DESELECT_ALL, exact = true))

        // "Point roundness": typed, one in-tool step; "Reset point roundness" gives the shape's own back.
        ui.reach(PointLabels.ROUNDNESS)
        click("Type ${PointLabels.ROUNDNESS}", exact = true)
        SmokeUi.typeAndDone(PointLabels.ROUNDNESS, "20")
        assertEquals(Mixed.Same(20f), tool.pointRoundness)
        assertTrue(tool.docAnchors()!!.all { it.radius == 20f })
        ui.reach(PointLabels.RESET_ROUNDNESS, CHIP_DP)
        assertTrue(SmokeUi.isEnabled(PointLabels.RESET_ROUNDNESS))
        click(PointLabels.RESET_ROUNDNESS, exact = true)
        assertTrue(tool.docAnchors()!!.all { it.radius == null })
        assertFalse(tool.canResetPointRoundness)

        // "Turn into path": a vector layer with one path, open in the Path tool.
        ui.reach(PointLabels.TO_PATH)
        click(PointLabels.TO_PATH, exact = true)
        assertTrue(Smoke.pumpUntil(10_000) { !c.vectors.isRendering })
        SmokeUi.settle()
        assertEquals(ToolId.PATH, c.activeToolId)
        assertEquals(HistoryLabels.TURN_INTO_PATH, c.undoManager.undoLabel)
        val layer = c.doc.activeLayer
        assertTrue(layer.isVectorLayer)
        assertTrue((layer.vector!!.objects.single() as VPath).spline != null)
        Smoke.assertQuiet(c, "end")
    }

    private companion object {
        /** The strip's chips are 32 dp high (their touch target is 48 dp); buttons and fields are 40 dp. */
        const val CHIP_DP = 32f
    }
}
