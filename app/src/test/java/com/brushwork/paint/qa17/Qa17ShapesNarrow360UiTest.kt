package com.brushwork.paint.qa17

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.qa16.QaCurves
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.pathfinder.PathfinderTool
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.PathfinderLabels
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.vector.pathfinder.PathfinderOp
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.7 final QA (items 1, 2, 6, 13 and 20 for shapes) on a narrower phone (360 × 760 dp): every
 * Shape Points control is reached by sliding the strip at its full size ("Select several" and
 * "Select all points" 44 dp, "Point roundness" and "Turn into path" 40 dp), the pill's trash
 * cell is wholly on screen, and so are "Select all objects" and the ten Pathfinder operations
 * (40 dp); the last one used is ONE step.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa17.shapesnarrow360sandbox"])
class Qa17ShapesNarrow360UiTest {

    private fun press(s: ChromeScreen, label: String, minDp: Float) {
        Qa16Ui(s).reach(label, minDp)
        settle()
        Finger.tap(s, label)
    }

    @Test
    fun shapeAndPathfinderControlsAt360dp() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 120_000)
        val h = ChromeHarness()
        h.section("Shape Points and Pathfinder at 360 dp") { narrow(h.editor(Smoke.document(400, 300, layers = 1, whiteBottom = true))) }
        dog.interrupt()
        h.finish()
    }

    private fun narrow(s: ChromeScreen) {
        val c = s.c
        assertEquals(360f, s.widthDp, 1f)
        val ui = Qa16Ui(s)
        QaCurves.tool(s, "Shape")
        val tool = c.currentTool as ShapeTool
        QaCurves.snapOff(c)
        QaCurves.drag(s, Vec2(60f, 70f), Vec2(170f, 150f))
        assertNotNull("a pending rectangle", tool.box)
        press(s, "Points", 32f)
        assertTrue(tool.pointsMode)
        press(s, PointLabels.SELECT_SEVERAL, 44f)
        assertTrue(tool.selectSeveral)
        press(s, PointLabels.SELECT_ALL, 44f)
        assertEquals(4, tool.pointSelection.count)
        ui.reach(PointLabels.DESELECT_ALL, 44f)
        ui.reach("Type ${PointLabels.ROUNDNESS}", 40f)
        ui.reach(PointLabels.TO_PATH, 40f)
        val trash = requireNotNull(s.tagged(V17Tags.PILL_TRASH)) { "the trash cell shows" }
        assertTrue("the trash cell is on screen: $trash", trash.left >= 0f && trash.right <= s.widthDp && trash.top >= 0f)
        assertTrue("a finger's size: $trash", trash.width >= 40f && trash.height >= 40f)
        click("Apply shape edit")
        // A second shape over the first: two operands.
        click("Shape type", exact = true)
        click(ShapeType.ELLIPSE.label, exact = true)
        QaCurves.drag(s, Vec2(150f, 100f), Vec2(170f, 150f))
        click("Apply shape edit")
        assertEquals(3, c.doc.layers.size)

        QaCurves.tool(s, "Pathfinder")
        assertEquals(ToolId.PATHFINDER, c.activeToolId)
        val pf = c.currentTool as PathfinderTool
        pf.computeDispatcher = Dispatchers.Unconfined
        press(s, PathfinderLabels.SELECT_ALL, 32f)
        assertEquals(2, pf.count)
        for (op in PathfinderOp.entries) ui.reach(op.description, 40f)
        val before = c.undoManager.undoCount
        press(s, PathfinderOp.EXCLUDE.description, 40f)
        assertTrue("done", Smoke.pumpUntil(20_000) { settle(1); !pf.busy && !c.vectors.isRendering })
        settle()
        assertEquals("ONE step", before + 1, c.undoManager.undoCount)
        assertEquals(PathfinderOp.EXCLUDE.historyLabel, c.undoManager.undoLabel)
        Qa17Shots.screen(s, "shapes-narrow360-pf")
        Smoke.assertQuiet(c, "shapes at 360 dp")
    }
}
