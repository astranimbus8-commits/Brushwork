package com.brushwork.paint.qa16

import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.remove.RemoveTool
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.remove.RemoveSizeScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.6 final QA (increments everywhere: "brush size sliders"): the Remove tool's own brush size
 * slider in its options strip on the real editor. A px slider steps by the Size increment
 * (ARCHITECTURE §Increments: px → SIZE, sliders too), as the bottom brush size row does: with
 * increments on (Size 3) it lands on multiples of 3 px (the range ends stay reachable); with
 * increments off it is v1.5 (whole pixels).
 *
 * (Before the fix the slider ignored the increments: 50 px with a 3 px step on.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.removesizesandbox"])
class QaRemoveSizeIncrementsUiTest {

    @Test
    fun theRemoveBrushSizeStepsByTheSizeIncrement() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 60_000)
        val h = ChromeHarness()
        h.section("increments off: whole pixels (v1.5)") {
            val s = h.editor()
            assertFalse(s.c.increments.enabled)
            QaCurves.tool(s, "Remove")
            assertEquals(ToolId.REMOVE, s.c.activeToolId)
            val tool = s.c.currentTool as RemoveTool
            QaCurves.setSlider("Remove brush size", RemoveSizeScale.toPosition(50f))
            assertEquals(50f, tool.size, 0f)
            Smoke.assertQuiet(s.c, "off")
        }
        h.section("increments on (Size 3): multiples of 3 px, the ends reachable") {
            val s = h.editor()
            QaCurves.incrementsOn(size = "3")
            QaCurves.tool(s, "Remove")
            val tool = s.c.currentTool as RemoveTool
            QaCurves.setSlider("Remove brush size", RemoveSizeScale.toPosition(50f))
            assertEquals("the nearest multiple of 3", 51f, tool.size, 0f)
            QaCurves.setSlider("Remove brush size", RemoveSizeScale.toPosition(122f))
            assertEquals(123f, tool.size, 0f)
            QaCurves.setSlider("Remove brush size", 1f)
            assertEquals("the top end", 600f, tool.size, 0f)
            QaCurves.setSlider("Remove brush size", 0f)
            assertEquals("the bottom end", 2f, tool.size, 0f)
            Smoke.assertQuiet(s.c, "on")
        }
        dog.interrupt()
        h.finish()
    }
}
