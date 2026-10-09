package com.brushwork.paint.qa16

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.model.IncrementKind
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.ui.common.CurveLabels17
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.abs
import kotlin.math.ln

/**
 * v1.6 final QA (increments everywhere): the plain line's "Line width" in the Curve / Path
 * settings sheet on the real editor. A line width is a size (the Shape tool's "Stroke width" steps
 * by the Size increment, field and slider; px sliders are SIZE), so with increments on its − / +
 * go to the next multiple of the Size step and its logarithmic slider lands on them; a typed
 * width is exact; with increments off it is v1.5 (± 1 px, a free slider).
 *
 * (Before the fix the field stepped by the Length increment — 8 → 25 px — and the slider
 * ignored the increments — 50.6 px.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.curvelinewidthsandbox"])
class QaCurveLineWidthIncrementsUiTest {

    /** The slider right under the "Line width" field (the sheet's next slider down). */
    private fun lineWidthSlider(): RobolectricUi.Element {
        val f = SmokeUi.field("Line width").bounds
        return RobolectricUi.elements()
            .filter { it.node.layoutInfo.isPlaced && it.node.config.getOrNull(SemanticsActions.SetProgress) != null && it.bounds.top >= f.bottom - 2f }
            .minByOrNull { it.bounds.top } ?: throw AssertionError("no slider under \"Line width\"")
    }

    /** Where 0.5 – 500 px (logarithmic) puts [px]. */
    private fun pos(px: Float): Float = (ln(px / 0.5f) / ln(1000f))

    @Test
    fun theCurvesLineWidthStepsByTheSizeIncrement() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 60_000)
        val h = ChromeHarness()
        h.section("increments on (Length 25, Size 3)") {
            val s = h.editor()
            QaCurves.incrementsOn(length = "25", size = "3")
            QaCurves.tool(s, "Curve")
            assertEquals(ToolId.CURVE, s.c.activeToolId)
            val tool = s.c.currentTool as CurveTool
            click(CurveLabels17.STROKE_KIND, exact = true)
            click(CurveStroke.PLAIN.label, exact = true)
            click("Curve settings")
            val w0 = tool.lineWidth
            assertEquals("the brush size (8 px)", 8f, w0, 1e-3f)
            click("Increase Line width", exact = true)
            assertEquals("+: the next multiple of the Size step", 9f, tool.lineWidth, 1e-3f)
            click("Decrease Line width", exact = true)
            assertEquals("−: 6", 6f, tool.lineWidth, 1e-3f)
            requireNotNull(lineWidthSlider().node.config.getOrNull(SemanticsActions.SetProgress)?.action).invoke(pos(50.6f))
            settle(4)
            val w = tool.lineWidth
            assertTrue("the slider lands on a 3 px multiple: $w", QaCurves.onStep(w, 3f))
            assertTrue("the nearest one to 50.6: $w", abs(w - 50.6f) <= 1.5f + 1e-3f)
            SmokeUi.typeAndDone("Line width", "12.4")
            assertEquals("typed: exact", 12.4f, tool.lineWidth, 1e-3f)
            Smoke.assertQuiet(s.c, "on")
        }
        h.section("increments off: v1.5") {
            val s = h.editor()
            QaCurves.incrementsOff()
            QaCurves.tool(s, "Curve")
            val tool = s.c.currentTool as CurveTool
            if (tool.settings.stroke != CurveStroke.PLAIN) { click(CurveLabels17.STROKE_KIND, exact = true); click(CurveStroke.PLAIN.label, exact = true) }
            click("Curve settings")
            val w0 = tool.lineWidth
            click("Increase Line width", exact = true)
            assertEquals("+ 1 px", w0 + 1f, tool.lineWidth, 1e-3f)
            requireNotNull(lineWidthSlider().node.config.getOrNull(SemanticsActions.SetProgress)?.action).invoke(pos(50.6f))
            settle(4)
            assertEquals("free", 50.6f, tool.lineWidth, 0.05f)
            Smoke.assertQuiet(s.c, "off")
        }
        dog.interrupt()
        h.finish()
    }
}
