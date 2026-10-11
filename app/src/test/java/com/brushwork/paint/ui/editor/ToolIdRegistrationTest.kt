package com.brushwork.paint.ui.editor

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.EditorController
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.LayerToolRules
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.select.colorrange.ColorSelectTool
import com.brushwork.paint.tools.select.magnetic.MagneticLassoTool
import com.brushwork.paint.tools.text.frames.TextFrameTool
import com.brushwork.paint.tools.vector.CurveKind
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.ui.tools.ToolOptionsBar
import com.brushwork.paint.ui.tools.coordinateSourceOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.6 foundation (§4.1, §4.9): the new tools are registered everywhere a tool must be — a
 * factory entry, an icon, a tool-menu cell (v1.6: the menu replaced the tools grid), a history label, an options
 * strip, a coordinate source — and selecting and touching them is safe while they are stubs
 * (Path behaves as Curve; Text frames does nothing). v1.8 F3: Magnetic lasso and Color select
 * are registered the same way; a touch pushes no document step, and whatever in-tool work it
 * leaves (Magnetic lasso's first anchor, v1.8 area C) is dropped by discard().
 */
@RunWith(RobolectricTestRunner::class)
@Config(instrumentedPackages = ["com.brushwork.paint.ui.editor.toolidregistrationsandbox"])
class ToolIdRegistrationTest {

    @Test
    fun newToolsAreRegisteredEverywhere() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c: EditorController = Smoke.controller(activity)
        val newIds = listOf(ToolId.PATH, ToolId.TEXT_FRAMES)

        // Factory: one instance per id, of the right kind.
        assertEquals(ToolId.entries.toSet(), c.tools.keys)
        val path = c.tools.getValue(ToolId.PATH) as CurveTool
        assertEquals(CurveKind.PATH, path.kind)
        assertFalse(path.polyline)
        assertEquals(ToolId.PATH, path.id)
        assertEquals(CurveKind.CURVE, (c.tools.getValue(ToolId.CURVE) as CurveTool).kind)
        assertTrue((c.tools.getValue(ToolId.POLYLINE) as CurveTool).polyline)
        assertTrue(c.tools.getValue(ToolId.TEXT_FRAMES) is TextFrameTool)
        // v1.8 F3: the two selection tools (area C).
        val v18Ids = listOf(ToolId.MAGNETIC_LASSO, ToolId.COLOR_SELECT)
        assertTrue(c.tools.getValue(ToolId.MAGNETIC_LASSO) is MagneticLassoTool)
        assertTrue(c.tools.getValue(ToolId.COLOR_SELECT) is ColorSelectTool)
        for (id in v18Ids) assertEquals(id, c.tools.getValue(id).id)
        // v1.6 area B: Path's X / Y target is its own stable control-point position (nothing selected yet).
        assertNull(requireNotNull(path.splinePointPosition).position)

        for (id in newIds + v18Ids) {
            assertNotNull(EditorIcons.tool(id))
            assertTrue("$id in the tool menu", id in ToolMenu.tools)
            assertTrue(HistoryLabels.stepName(id).isNotBlank())
            assertNull("$id starts on a raster layer", LayerToolRules.refusal(id, c.activeLayer))
        }
        assertEquals("last point", HistoryLabels.stepName(ToolId.PATH))
        assertTrue(ToolId.PATH in EditorController.HOLD_PICK_TOOLS)
        assertFalse("Text frames drags never turn into picks", ToolId.TEXT_FRAMES in EditorController.HOLD_PICK_TOOLS)

        // Coordinate sources: Path uses its control-point position (area B); Text frames has none yet.
        assertTrue(coordinateSourceOf(path)!!.target === path.splinePointPosition)
        // v1.7 area B: Curve is a PillPositionTool, so its X / Y target is the tool's own pill position.
        val curveTool = c.tools.getValue(ToolId.CURVE) as CurveTool
        assertTrue(coordinateSourceOf(curveTool)!!.target === curveTool.pillPosition)
        assertNull(coordinateSourceOf(c.tools.getValue(ToolId.TEXT_FRAMES))!!.target.position)

        // Options strips compose for both tools; selecting and touching them is safe.
        var shown by mutableStateOf(0)
        activity.setContent { BrushworkTheme { shown; ToolOptionsBar(c) } }
        SmokeUi.settle()
        for (id in newIds + v18Ids) {
            val steps0 = c.undoManager.undoCount
            c.selectTool(id)
            shown++
            SmokeUi.settle()
            assertEquals(id, c.activeToolId)
            c.pointerDown(ToolPoint(100f, 100f))
            c.pointerMove(ToolPoint(140f, 120f))
            c.pointerUp(ToolPoint(140f, 120f))
            SmokeUi.settle()
            Smoke.assertQuiet(c, "$id")
            if (id in v18Ids) {
                // v1.8 area C: Magnetic lasso's tap places an anchor, pending in-tool work by design (§5.1 C).
                c.currentTool.discard()
                assertFalse("$id: discard drops its in-tool steps", c.currentTool.hasPendingWork)
                assertEquals("$id pushes no document step", steps0, c.undoManager.undoCount)
            }
        }
        // Text frames (a stub) left nothing pending.
        assertFalse(c.tools.getValue(ToolId.TEXT_FRAMES).hasPendingWork)
        c.selectTool(ToolId.PATH)
        path.discard()
        c.selectTool(ToolId.BRUSH)
        SmokeUi.settle()
        Smoke.assertQuiet(c, "back to the brush")
        assertTrue(Smoke.errorLogs().isEmpty())
    }
}
