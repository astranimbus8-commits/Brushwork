package com.brushwork.paint.ui.clone

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.clone.CloneTool
import com.brushwork.paint.ui.brush.BrushStripItems
import com.brushwork.paint.ui.brush.MirrorsStripKit
import com.brushwork.paint.ui.brush.MirrorsStripKit.assertSplits
import com.brushwork.paint.ui.brush.MirrorsStripKit.back
import com.brushwork.paint.ui.brush.MirrorsStripKit.inspect
import com.brushwork.paint.ui.brush.MirrorsStripKit.key
import com.brushwork.paint.ui.brush.MirrorsStripKit.moreHandles
import com.brushwork.paint.ui.brush.MirrorsStripKit.row1Handles
import com.brushwork.paint.ui.brush.MirrorsStripKit.staysOpen
import com.brushwork.paint.ui.tools.strip.StripHandlesV17
import com.brushwork.paint.ui.tools.strip.StripLayout
import com.brushwork.paint.ui.tools.strip.StripRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 D (design §3.9 "Clone stamp"): the clone stamp's converted strip at 360 and 392 dp, on the
 * raster and the vector layer (the VECTOR cell):
 * - row 1 "Set source" ("Tap the source" while armed), "Aligned", "Sample from: This layer", the
 *   same at every configuration (236 dp fits 240);
 * - the More row: the hint "Long-press where to copy from" (no source, not armed; it arms Set
 *   source and closes the row), "Show source", the brush's four controls (three lines at 360 dp
 *   with the hint, two without);
 * - every v1.7 handle stays reachable (the inventory), the controls drive the tool, and the brush
 *   panel opened from the More row outlives the row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h780dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.clone.clonestripsandbox"])
class CloneStripTest {

    private val row1 = listOf(CloneStripItems.SET_SOURCE, CloneStripItems.ALIGNED, CloneStripItems.SAMPLE)
    private val brushMore = listOf("Choose brush", "Brush size", "Opacity", "Pressure changes size: on")

    @Test
    fun theCloneStripKeepsItsControlsAtBothWidths() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        MirrorsStripKit.atEachWidth { rig, dp ->
            for (vector in listOf(false, true)) {
                val k = key(ToolId.CLONE, vector)
                rig.select(ToolId.CLONE, vector)
                val tool = rig.c.tools.getValue(ToolId.CLONE) as CloneTool
                assertEquals(null, tool.anchor.source)
                inspect(rig, k)
                val spec = rig.spec
                assertSplits(k, spec) { _, _ -> row1 }
                // Three More lines at 360 dp (the hint, then "Show source" and the brush), two at 392.
                val split = StripLayout.split(spec, StripInventoryWidths.row1(dp, vector))
                val lines = StripLayout.moreLines(split, spec.hint, StripInventoryWidths.content(dp))
                assertEquals("$k at $dp dp: More lines", if (dp == 360) 3 else 2, lines.size)
                assertEquals("$k at $dp dp: row 1", listOf("Set source", "Aligned", "Sample from: This layer"), row1Handles())
                assertTrue("$k: the toggle", StripTestKit.toggle() != null)
                assertTrue(StripTestKit.openMoreRow())
                assertEquals("$k at $dp dp: the More row", listOf(CloneTool.HINT, "Show source") + brushMore, moreHandles())
                StripTestKit.closeMoreRow()
            }

            // The hint arms Set source and closes the More row (the next canvas tap sets the source).
            rig.select(ToolId.CLONE)
            val tool = rig.c.tools.getValue(ToolId.CLONE) as CloneTool
            assertTrue(StripTestKit.openMoreRow())
            SmokeUi.click(CloneTool.HINT, exact = true)
            rig.settle()
            assertTrue("$dp dp: the hint arms Set source", tool.armed)
            assertFalse("$dp dp: the hint closes the More row", StripTestKit.moreRowShown())
            assertEquals(listOf("Tap the source", "Aligned", "Sample from: This layer"), row1Handles())
            assertFalse("armed: no hint", rig.spec.items.any { it.key == CloneStripItems.HINT })
            assertEquals(row1, rig.spec.items.filter { it.row == StripRow.ROW1 }.map { it.key })
            SmokeUi.click("Tap the source", exact = true)
            assertFalse(tool.armed)
            SmokeUi.click("Set source", exact = true)
            assertTrue(tool.armed)
            SmokeUi.click("Tap the source", exact = true)
            assertFalse(tool.armed)

            // With a source: no hint; the rest of the record stays.
            tool.setSource(Vec2(50f, 50f))
            rig.settle()
            assertNotNull(tool.anchor.source)
            inspect(rig, "CLONE", StripHandlesV17.HANDLES.getValue("CLONE") - CloneTool.HINT)
            assertTrue(StripTestKit.openMoreRow())
            assertEquals("$dp dp, a source: the More row", listOf("Show source") + brushMore, moreHandles())
            // "Show source" is a toggle: the row stays open.
            SmokeUi.click("Show source", exact = true)
            rig.settle()
            assertFalse(tool.showSource)
            assertFalse(rig.c.settings.cloneShowSource)
            assertTrue("a toggle keeps the More row", StripTestKit.moreRowShown())
            SmokeUi.click("Show source", exact = true)
            assertTrue(tool.showSource)
            StripTestKit.closeMoreRow()

            // Aligned: an icon toggle, the tool and the setting follow.
            assertTrue(tool.aligned)
            SmokeUi.click("Aligned", exact = true)
            assertFalse(tool.aligned)
            assertFalse(rig.c.settings.cloneAligned)
            SmokeUi.click("Aligned", exact = true)
            assertTrue(rig.c.settings.cloneAligned)

            // Sample from: a dropdown with This layer / All layers.
            SmokeUi.click("Sample from: This layer", exact = true)
            rig.settle()
            SmokeUi.click(CloneStripItems.ALL_LAYERS, exact = true)
            rig.settle()
            assertTrue(tool.sampleAllLayers)
            assertTrue(rig.c.settings.cloneSampleAllLayers)
            assertTrue(SmokeUi.has("Sample from: All layers", exact = true))
            SmokeUi.click("Sample from: All layers", exact = true)
            rig.settle()
            SmokeUi.click(CloneStripItems.THIS_LAYER, exact = true)
            rig.settle()
            assertFalse(tool.sampleAllLayers)

            // The brush panel opened from the More row outlives the row.
            staysOpen(rig, BrushStripItems.CHOOSE_BRUSH, "$dp dp, the clone brush's panel") { SmokeUi.has("PRESETS", exact = true) }
            back(rig)
            assertFalse(SmokeUi.has("PRESETS", exact = true))
            assertTrue(SmokeUi.has("Set source", exact = true))
            rig.select(ToolId.BRUSH)
        }
    }

    /** Row 1's space and the More row's width of the inventory (StripInventory.content less the VECTOR cell and the toggle). */
    private object StripInventoryWidths {
        fun content(screenDp: Int) = com.brushwork.paint.ui.tools.strip.StripInventory.content(screenDp.toFloat())
        fun row1(screenDp: Int, chip: Boolean) = content(screenDp) - if (chip) StripLayout.VECTOR_CHIP_DP + StripLayout.GAP_DP else 0f
    }
}
