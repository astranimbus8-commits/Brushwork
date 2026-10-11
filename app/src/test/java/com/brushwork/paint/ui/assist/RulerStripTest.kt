package com.brushwork.paint.ui.assist

import com.brushwork.paint.model.RulerType
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.brush.MirrorsStripKit
import com.brushwork.paint.ui.brush.MirrorsStripKit.assertSplits
import com.brushwork.paint.ui.brush.MirrorsStripKit.back
import com.brushwork.paint.ui.brush.MirrorsStripKit.clippedTexts
import com.brushwork.paint.ui.brush.MirrorsStripKit.inspect
import com.brushwork.paint.ui.brush.MirrorsStripKit.key
import com.brushwork.paint.ui.brush.MirrorsStripKit.moreHandles
import com.brushwork.paint.ui.brush.MirrorsStripKit.row1Handles
import com.brushwork.paint.ui.brush.MirrorsStripKit.staysOpen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 D (design §3.9 "Ruler"): the ruler tool's converted strip at 360 and 392 dp, on the raster
 * and the vector layer (the VECTOR cell):
 * - row 1 the four ruler-type buttons and "Done" (240 dp, every configuration);
 * - the More row "Numbers" and "Snap to objects" (v1.7's "Snap": the longer name, §3.9);
 * - every v1.7 handle reachable (the inventory), no label cut by its item's width, the controls
 *   work, and the Ruler sheet opened from the More row outlives the row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h780dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.assist.rulerstripsandbox"])
class RulerStripTest {

    private val types = RulerType.entries.map { RulerStripItems.typeLabel(it) }

    @Test
    fun theRulerStripKeepsItsControlsAtBothWidths() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        MirrorsStripKit.atEachWidth { rig, dp ->
            for (vector in listOf(false, true)) {
                val k = key(ToolId.RULER, vector)
                rig.select(ToolId.RULER, vector)
                inspect(rig, k)
                assertSplits(k, rig.spec) { _, _ -> listOf(RulerStripItems.TYPES, RulerStripItems.DONE) }
                assertEquals("$k at $dp dp: row 1", types + "Done", row1Handles())
                assertTrue("$k: the toggle", StripTestKit.toggle() != null)
                assertEquals("$k at $dp dp: row 1 texts fit", emptyList<String>(), clippedTexts())
                assertTrue(StripTestKit.openMoreRow())
                assertEquals("$k at $dp dp: the More row", listOf("Numbers", "Snap to objects"), moreHandles())
                assertEquals("$k at $dp dp: More row texts fit", emptyList<String>(), clippedTexts())
                StripTestKit.closeMoreRow()
            }

            rig.select(ToolId.BRUSH)
            rig.select(ToolId.RULER)
            val c = rig.c
            // A type button picks the type and turns the ruler on.
            SmokeUi.click(RulerStripItems.typeLabel(RulerType.CIRCLE), exact = true)
            rig.settle()
            assertEquals(RulerType.CIRCLE, c.ruler.type)
            assertTrue(c.ruler.enabled)
            SmokeUi.click(RulerStripItems.typeLabel(RulerType.STRAIGHT), exact = true)
            assertEquals(RulerType.STRAIGHT, c.ruler.type)

            // "Snap to objects" is a toggle: the More row stays open.
            assertTrue(StripTestKit.openMoreRow())
            val snap = c.snapping.enabled
            SmokeUi.click("Snap to objects", exact = true)
            rig.settle()
            assertNotEquals(snap, c.snapping.enabled)
            assertTrue("a toggle keeps the More row", StripTestKit.moreRowShown())
            SmokeUi.click("Snap to objects", exact = true)
            assertEquals(snap, c.snapping.enabled)
            StripTestKit.closeMoreRow()

            // "Numbers": the Ruler sheet outlives the More row.
            staysOpen(rig, "Numbers", "$dp dp, the Ruler sheet") { SmokeUi.has("Use ruler", exact = true) }
            back(rig)
            assertFalse(SmokeUi.has("Use ruler", exact = true))
            assertEquals(ToolId.RULER, c.activeToolId)

            // "Done": back to the last painting tool.
            val paint = c.lastPaintTool
            SmokeUi.click("Done", exact = true)
            rig.settle()
            assertEquals(paint, c.activeToolId)
        }
    }
}
