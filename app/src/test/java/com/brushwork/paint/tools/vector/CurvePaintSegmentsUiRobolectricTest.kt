package com.brushwork.paint.tools.vector

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.qa16.QaCurves
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.ui.common.CurveLabels17
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.7 (item 7, design §3.7a) on the real editor at the user's phone width: the curve tools'
 * "Stroke", "Fill", "Both" segments (known as "Stroke only", "Fill only", "Stroke and fill"),
 * "Stroke kind" with the brush and the plain line only, shown for a line; Fill and Both greyed
 * with 1 or 2 points (a tap says why), and Fill then Both bringing the brush back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi")
class CurvePaintSegmentsUiRobolectricTest {

    private fun selected(label: String): Boolean =
        SmokeUi.find(label, exact = true)?.node?.config?.getOrNull(SemanticsProperties.Selected) == true

    @Test
    fun theSegments() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 120_000)
        val h = ChromeHarness()
        h.section("Curve: Stroke, Fill, Both and the stroke kind") {
            val s = h.editor()
            QaCurves.tool(s, "Curve")
            val tool = s.c.currentTool as CurveTool
            for (l in listOf(CurveLabels17.STROKE_ONLY, CurveLabels17.FILL_ONLY, CurveLabels17.BOTH)) {
                assertTrue("$l: ${SmokeUi.shown()}", has(l, exact = true))
                assertTrue("$l with no points (for the next curve)", SmokeUi.isEnabled(l))
            }
            assertTrue(selected(CurveLabels17.STROKE_ONLY))
            assertFalse(selected(CurveLabels17.FILL_ONLY))
            // The stroke kind: the brush or a plain line, "No stroke" is the Fill segment now.
            click(CurveLabels17.STROKE_KIND, exact = true)
            assertTrue(has(CurveStroke.PLAIN.label, exact = true))
            assertFalse(has(CurveStroke.NONE.label, exact = true))
            click(CurveStroke.PLAIN.label, exact = true)
            assertEquals(CurveStroke.PLAIN, tool.settings.stroke)
            // Fill: no stroke kind to pick; Both brings the plain line back.
            click(CurveLabels17.FILL_ONLY, exact = true)
            assertEquals(CurveStroke.NONE, tool.settings.stroke)
            assertTrue(tool.settings.fill)
            assertTrue(selected(CurveLabels17.FILL_ONLY))
            assertFalse("a fill has no stroke kind", has(CurveLabels17.STROKE_KIND, exact = true))
            click(CurveLabels17.BOTH, exact = true)
            assertEquals(CurveStroke.PLAIN, tool.settings.stroke)
            assertTrue(tool.settings.fill)
            assertTrue(selected(CurveLabels17.BOTH))
            // Fill then Both restores the brush.
            click(CurveLabels17.STROKE_KIND, exact = true)
            click(CurveStroke.BRUSH.label, exact = true)
            click(CurveLabels17.FILL_ONLY, exact = true)
            click(CurveLabels17.BOTH, exact = true)
            assertEquals(CurveStroke.BRUSH, tool.settings.stroke)
            click(CurveLabels17.STROKE_ONLY, exact = true)
            assertFalse(tool.settings.fill)
            assertEquals(CurveStroke.BRUSH, tool.settings.stroke)

            // Two points: Fill and Both are greyed; a tap says why and changes nothing.
            assertTrue(tool.addAnchor(Vec2(60f, 60f)))
            assertTrue(tool.addAnchor(Vec2(200f, 80f)))
            settle()
            assertFalse(SmokeUi.isEnabled(CurveLabels17.FILL_ONLY))
            assertFalse(SmokeUi.isEnabled(CurveLabels17.BOTH))
            assertTrue(SmokeUi.isEnabled(CurveLabels17.STROKE_ONLY))
            // (Read before the screen settles: the editor hands the message to its snackbar.)
            click(CurveLabels17.FILL_ONLY, exact = true, settleAfter = false)
            assertEquals(CurveLabels17.FILL_NEEDS_3, s.c.message)
            settle()
            assertFalse(tool.settings.fill)
            // A third point: Fill is there.
            assertTrue(tool.addAnchor(Vec2(140f, 200f)))
            settle()
            assertTrue(SmokeUi.isEnabled(CurveLabels17.FILL_ONLY))
            click(CurveLabels17.FILL_ONLY, exact = true)
            assertTrue(tool.settings.fill)
            assertEquals(CurveStroke.NONE, tool.settings.stroke)
        }
        dog.interrupt()
        h.finish()
    }
}
