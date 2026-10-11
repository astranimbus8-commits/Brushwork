package com.brushwork.paint.ui.remove

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.qa16.QaCurves
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.remove.CafSource
import com.brushwork.paint.tools.remove.RemoveTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.placement.PlacementStripKit
import com.brushwork.paint.ui.placement.PlacementStripKit.assertSplits
import com.brushwork.paint.ui.placement.PlacementStripKit.back
import com.brushwork.paint.ui.placement.PlacementStripKit.clippedTexts
import com.brushwork.paint.ui.placement.PlacementStripKit.inspect
import com.brushwork.paint.ui.placement.PlacementStripKit.key
import com.brushwork.paint.ui.placement.PlacementStripKit.overlayWindowShown
import com.brushwork.paint.ui.placement.PlacementStripKit.row1Handles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 G (design §3.9 "Remove"): the Remove tool's converted strip at 360 and 392 dp, on the raster
 * and the vector layer (the VECTOR cell):
 * - row 1 only, every configuration: "Remove brush size" (the size readout), "Sample: Current
 *   layer", "Color adaptation"; no More row, no toggle;
 * - every v1.7 handle reachable (the inventory), no label cut by its item's width;
 * - the readout's popup holds v1.7's slider (its description and state unchanged) and moves the
 *   size; the sample dropdown and the color adaptation toggle work.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h780dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.remove.removestripsandbox"])
class RemoveStripTest {

    private val row1 = listOf(RemoveStripItems.SIZE, RemoveStripItems.SOURCE, RemoveStripItems.ADAPT)

    @Test
    fun theRemoveStripKeepsItsControlsAtBothWidths() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        PlacementStripKit.atEachWidth { rig, dp ->
            for (vector in listOf(false, true)) {
                val k = key(ToolId.REMOVE, vector)
                rig.select(ToolId.REMOVE, vector)
                inspect(rig, k)
                assertNull("$k: no hint", rig.spec.hint)
                assertSplits(k, rig.spec) { _, _ -> row1 }
                assertEquals(
                    "$k at $dp dp: row 1",
                    listOf(RemoveStripItems.SIZE_LABEL, RemoveStripItems.sourceLabel(CafSource.LAYER), RemoveStripItems.ADAPT_LABEL),
                    row1Handles(),
                )
                assertNull("$k: no toggle", StripTestKit.toggle())
                assertEquals("$k at $dp dp: row 1 texts fit", emptyList<String>(), clippedTexts())
            }

            rig.select(ToolId.REMOVE)
            val tool = rig.c.currentTool as RemoveTool

            // The size readout opens a popup with v1.7's slider; the readout leaves the semantics meanwhile.
            assertFalse(QaCurvesProbe.sliderShown())
            SmokeUi.click(RemoveStripItems.SIZE_LABEL, exact = true)
            rig.settle()
            assertTrue("$dp dp: the size popup", overlayWindowShown(rig) && QaCurvesProbe.sliderShown())
            assertEquals("one \"Remove brush size\" while the popup shows", 1, QaCurvesProbe.count(RemoveStripItems.SIZE_LABEL))
            QaCurves.setSlider(RemoveStripItems.SIZE_LABEL, RemoveSizeScale.toPosition(50f))
            assertEquals(50f, tool.size, 0f)
            assertTrue("the readout follows", SmokeUi.has("50 px", exact = true))
            back(rig)
            assertFalse(QaCurvesProbe.sliderShown())
            assertTrue(SmokeUi.has(RemoveStripItems.SIZE_LABEL, exact = true))

            // The sample dropdown.
            SmokeUi.click(RemoveStripItems.sourceLabel(CafSource.LAYER), exact = true)
            rig.settle()
            SmokeUi.click(CafSource.ALL_LAYERS.label, exact = true)
            rig.settle()
            assertEquals(CafSource.ALL_LAYERS, tool.settings.source)
            assertTrue(SmokeUi.has(RemoveStripItems.sourceLabel(CafSource.ALL_LAYERS), exact = true))
            assertEquals("$dp dp: texts fit", emptyList<String>(), clippedTexts())
            SmokeUi.click(RemoveStripItems.sourceLabel(CafSource.ALL_LAYERS), exact = true)
            SmokeUi.click(CafSource.LAYER.label, exact = true)
            rig.settle()
            assertEquals(CafSource.LAYER, tool.settings.source)

            // Color adaptation toggles.
            val adapt = tool.settings.colorAdaptation
            SmokeUi.click(RemoveStripItems.ADAPT_LABEL, exact = true)
            rig.settle()
            assertNotEquals(adapt, tool.settings.colorAdaptation)
            SmokeUi.click(RemoveStripItems.ADAPT_LABEL, exact = true)
            rig.settle()
            assertEquals(adapt, tool.settings.colorAdaptation)
        }
    }
}

/** Slider lookups by description (QaCurves finds a slider; these count it). */
private object QaCurvesProbe {
    fun sliderShown(): Boolean = runCatching { QaCurves.slider(RemoveStripItems.SIZE_LABEL) }.isSuccess

    fun count(label: String): Int = RobolectricUi.elements().count { e ->
        e.node.layoutInfo.isPlaced &&
            e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
    }
}
