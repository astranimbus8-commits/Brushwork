package com.brushwork.paint.ui.selection

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.find
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.select.magnetic.MagneticLassoTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.MagneticLabels18
import com.brushwork.paint.ui.tools.strip.StripRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 (C, design §3.4 and §3.9): the Magnetic lasso's strip on a 360 dp phone, on a picture layer
 * and on a vector layer (the VECTOR chip). Row 1: the mode dropdown, then while an outline is open
 * "Delete point", the discard and the close with its point count, all on the screen (their room is
 * kept while nothing is placed, so nothing jumps). Each is one tap: delete and discard are in-tool
 * steps Undo takes back, the close commits. The More row: the snap's reach and the edges' contrast
 * (known by their names, their values as state), snapping, Refer and anti-alias, on the screen;
 * each item's content fits its declared width.
 *
 * One test: Compose's frame clock only serves the first test of a Robolectric sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h780dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.selection.magneticnarrowsandbox"])
class MagneticStripNarrowTest {

    private fun assertOnScreen(where: String, label: String, width: Int) {
        val e = find(label, exact = true) ?: throw AssertionError("$where: \"$label\" is not shown; shown: ${SmokeUi.shown()}")
        val b = e.bounds
        assertTrue("$where: \"$label\" at $b fits the ${width}px wide screen", b.width > 0f && b.left >= 0f && b.right <= width)
    }

    private fun state(label: String): String? =
        RobolectricUi.elements().lastOrNull { it.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true }
            ?.node?.config?.getOrNull(SemanticsProperties.StateDescription)

    private fun back(rig: StripRig) {
        val w = SmokeUi.windows().last()
        w.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        w.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        rig.settle()
    }

    @Test
    fun theMagneticStripFitsA360dpPhone() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val rig = StripRig(panel = false).show()
        try {
            val c = rig.c
            val width = rig.activity.resources.displayMetrics.widthPixels
            val density = rig.activity.resources.displayMetrics.density
            for (vector in listOf(false, true)) {
                val where = if (vector) "vector layer" else "picture layer"
                rig.select(ToolId.MAGNETIC_LASSO, vector)
                val tool = c.currentTool as MagneticLassoTool
                assertTrue(Smoke.pumpUntil { tool.edgeMapReady && tool.idle })
                rig.settle()

                // Nothing placed: the mode only (the other three keep their room, drawn empty).
                assertOnScreen(where, MagneticLabels18.SELECTION_MODE, width)
                for (label in listOf("Delete point", MagneticLabels18.DISCARD, MagneticLabels18.CLOSE)) {
                    assertFalse("$where: \"$label\" with no outline", has(label, exact = true))
                }

                for ((x, y) in listOf(80f to 50f, 320f to 50f, 320f to 190f)) {
                    c.pointerDown(ToolPoint(x, y))
                    c.pointerUp(ToolPoint(x, y))
                }
                rig.settle()
                assertEquals(3, tool.anchorCount)
                for (label in listOf(MagneticLabels18.SELECTION_MODE, "Delete point", MagneticLabels18.DISCARD, MagneticLabels18.CLOSE)) {
                    assertOnScreen(where, label, width)
                }
                assertTrue("$where: the point count under the close", has("3 pt", exact = true))
                assertFalse(StripTestKit.moreRowShown())

                // "Delete point": the active (last) anchor, one step.
                click("Delete point", exact = true)
                assertEquals(2, tool.anchorCount)
                c.undo()
                rig.settle()
                assertEquals(3, tool.anchorCount)
                // The discard: one step, Undo brings the outline back.
                click(MagneticLabels18.DISCARD, exact = true)
                assertEquals(0, tool.anchorCount)
                assertFalse(has(MagneticLabels18.CLOSE, exact = true))
                c.undo()
                rig.settle()
                assertEquals(3, tool.anchorCount)

                // The More row, every item on the screen; the two values are the chips' states.
                assertTrue(StripTestKit.openMoreRow())
                for (label in listOf(MagneticLabels18.EDGE_REACH, MagneticLabels18.EDGE_CONTRAST, MagneticLabels18.SNAP_TO_EDGE, "Refer: Layer", "Anti-alias")) {
                    assertOnScreen(where, label, width)
                }
                assertEquals("24 dp", state(MagneticLabels18.EDGE_REACH))
                assertEquals("10 %", state(MagneticLabels18.EDGE_CONTRAST))
                click(MagneticLabels18.SNAP_TO_EDGE, exact = true)
                assertFalse(tool.settings.snapToEdge)
                click(MagneticLabels18.SNAP_TO_EDGE, exact = true)
                assertTrue(tool.settings.snapToEdge)
                // The reach opens its slider in a popup anchored in the item; the row stays.
                val before = SmokeUi.windows().size
                click(MagneticLabels18.EDGE_REACH, exact = true)
                assertTrue("$where: the reach's popup", SmokeUi.windows().size > before)
                assertTrue(StripTestKit.moreRowShown())
                back(rig)
                tool.updateSettings(tool.settings.copy(edgeContrast = 20))
                rig.settle()
                assertEquals("20 %", state(MagneticLabels18.EDGE_CONTRAST))
                tool.updateSettings(tool.settings.copy(edgeContrast = 10))
                StripTestKit.closeMoreRow()

                // Each item's content fits its declared width.
                rig.measuring = true
                rig.settle()
                for (item in rig.spec.items) {
                    val px = rig.measured[item.key] ?: continue
                    assertTrue("$where: \"${item.key}\" measures ${px / density} dp, declared ${item.width}", px <= item.width.value * density + 1f)
                }
                rig.measuring = false
                rig.settle()

                // The close: one selection step, the outline gone.
                click(MagneticLabels18.CLOSE, exact = true)
                assertTrue(Smoke.pumpUntil { tool.idle })
                rig.settle()
                assertEquals(0, tool.anchorCount)
                assertFalse(tool.hasPendingWork)
                if (!vector) assertEquals(ToolId.MAGNETIC_LASSO.label, c.undoManager.undoLabel)
                c.deselect()
                rig.settle()
            }
            Smoke.assertQuiet(c, "magnetic strip")
        } finally {
            rig.close()
        }
    }
}
