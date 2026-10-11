package com.brushwork.paint.ui.selection

import android.graphics.Canvas
import android.graphics.Paint
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.select.colorrange.ColorPreset
import com.brushwork.paint.tools.select.colorrange.ColorSelectTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.ColorSelectLabels18
import com.brushwork.paint.ui.tools.strip.StripRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.abs

/**
 * v1.8 (C, design §3.5 b and §3.9): Color select's popups on the 392 dp phone. The preset chip says
 * its preset as its state and opens the thirteen presets as chips, the current one on; a tap on
 * the reds picks them, closes the popup and brings a new preview. The fuzziness (on row 1 at 392 dp)
 * opens a one-line slider panel 44 dp tall right under the strip's row, as wide as the screen; its
 * slider sets the value (the chip's state follows) and Back closes it. The color picker's add
 * opens the picker, whose OK adds its color as a sample (one step).
 *
 * One test: Compose's frame clock only serves the first test of a Robolectric sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.selection.colorchipssandbox"])
class ColorSelectPresetChipsUiTest {

    /** The control known by [label] (its description; the texts inside it are nodes of their own). */
    private fun button(label: String): RobolectricUi.Element? = RobolectricUi.elements().lastOrNull {
        it.node.layoutInfo.isPlaced && it.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
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
    fun thePresetChipsTheFuzzinessPanelAndThePicker() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val rig = StripRig(panel = false)
        // The picture layer: a red left half, a blue right half.
        val canvas = Canvas(rig.doc.layers[0].bitmap)
        val paint = Paint()
        paint.color = 0xFFE01010.toInt(); canvas.drawRect(0f, 0f, 200f, 300f, paint)
        paint.color = 0xFF1030D0.toInt(); canvas.drawRect(200f, 0f, 400f, 300f, paint)
        rig.show()
        try {
            val c = rig.c
            val width = rig.activity.resources.displayMetrics.widthPixels
            val density = rig.activity.resources.displayMetrics.density
            rig.select(ToolId.COLOR_SELECT)
            val tool = c.currentTool as ColorSelectTool
            assertTrue(Smoke.pumpUntil { tool.proxyReady && tool.idle && tool.preview != null })
            rig.settle()

            // The preset chip: 92 dp, its preset as its state.
            val range = requireNotNull(button(ColorSelectLabels18.COLOR_RANGE)) { "no preset chip" }
            assertTrue("the preset chip is ${range.bounds.width / density} dp wide", abs(range.bounds.width - ColorSelectStrip.RANGE_DP * density) <= 1.5f)
            assertEquals(ColorSelectLabels18.SAMPLED, state(ColorSelectLabels18.COLOR_RANGE))

            // Its popup: the thirteen presets as chips, the current one on.
            val before = SmokeUi.windows().size
            click(ColorSelectLabels18.COLOR_RANGE, exact = true)
            assertTrue("the presets popup", SmokeUi.windows().size > before)
            val popup = RobolectricUi.elements().filter { it.window === SmokeUi.windows().last() && it.node.layoutInfo.isPlaced }
            val chips = popup.flatMap { e -> e.node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
            for (p in ColorPreset.entries) assertTrue("the chip \"${p.label}\" in $chips", p.label in chips)
            val on = popup.filter { it.node.config.getOrNull(SemanticsProperties.Selected) == true }
                .mapNotNull { e -> e.node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } ?: e.node.children.firstNotNullOfOrNull { ch -> ch.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } } }
            assertTrue("only the current preset is on: $on", on.size == 1 && on[0].contains(ColorSelectLabels18.SAMPLED))

            // The reds: picked, the popup closed, a new preview.
            val version = tool.preview!!.version
            click(ColorSelectLabels18.REDS, exact = true)
            assertEquals(ColorPreset.REDS, tool.settings.preset)
            assertEquals("the popup closed", before, SmokeUi.windows().size)
            assertTrue("a new preview", Smoke.pumpUntil { (tool.preview?.version ?: 0) > version && tool.idle })
            rig.settle()
            assertEquals(ColorSelectLabels18.REDS, state(ColorSelectLabels18.COLOR_RANGE))
            assertFalse(StripTestKit.moreRowShown())

            // The fuzziness: on row 1 at 392 dp, its value as its state.
            val chip = requireNotNull(button(ColorSelectLabels18.FUZZINESS)) { "no fuzziness chip on row 1" }
            assertFalse("the fuzziness chip is on row 1", StripTestKit.moreRowShown())
            assertEquals("40", state(ColorSelectLabels18.FUZZINESS))
            click(ColorSelectLabels18.FUZZINESS, exact = true)
            assertTrue("the fuzziness panel", SmokeUi.windows().size > before)
            val panel = requireNotNull(RobolectricUi.elements().lastOrNull { it.node.config.getOrNull(SemanticsProperties.TestTag) == ColorSelectStrip.PANEL_TAG }) { "no panel" }
            assertTrue("the panel is 44 dp tall: ${panel.bounds.height / density}", abs(panel.bounds.height - ColorSelectStrip.PANEL_DP * density) <= 1.5f)
            assertTrue("the panel is the screen's width: ${panel.bounds.width} of $width", abs(panel.bounds.width - width) <= 1.5f)
            // Its window sits at the screen's left edge, right under the strip's row.
            val params = SmokeUi.windows().last().layoutParams as WindowManager.LayoutParams
            val rowBottom = chip.bounds.bottom
            assertEquals("the panel at the left edge", 0, params.x)
            assertTrue("the panel (y ${params.y}) is under the row (bottom $rowBottom)", params.y >= rowBottom - 1f && params.y <= rowBottom + 8 * density)
            // The slider sets the value; the chip says it.
            val slider = requireNotNull(RobolectricUi.elements().lastOrNull { e ->
                e.node.layoutInfo.isPlaced && e.node.config.contains(SemanticsActions.SetProgress) &&
                    e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(ColorSelectLabels18.FUZZINESS) == true
            })
            val v2 = tool.preview!!.version
            requireNotNull(slider.node.config.getOrNull(SemanticsActions.SetProgress)?.action).invoke(120f)
            rig.settle()
            assertEquals(120, tool.settings.fuzziness)
            assertTrue("a new preview", Smoke.pumpUntil { (tool.preview?.version ?: 0) > v2 && tool.idle })
            assertTrue("the panel shows the value", has("120", exact = true))
            back(rig)
            assertEquals("Back closed the panel", before, SmokeUi.windows().size)
            rig.settle()
            assertEquals("120", state(ColorSelectLabels18.FUZZINESS))

            // The color picker's add: its OK adds a sample, one step.
            c.color = 0xFF20A040.toInt()
            click(ColorSelectLabels18.FROM_PICKER, exact = true)
            assertTrue("the picker", has("OK", exact = true))
            click("OK", exact = true)
            rig.settle()
            assertEquals(listOf(0xFF20A040.toInt()), tool.sampleColors)
            assertTrue(tool.canUndoStep)
            c.undo()
            assertEquals(emptyList<Int>(), tool.sampleColors)
            assertFalse("the picker closed", has("OK", exact = true))

            tool.updateSettings(tool.settings.copy(preset = ColorPreset.SAMPLED, fuzziness = 40))
            rig.settle()
            Smoke.assertQuiet(c, "color select popups")
        } finally {
            rig.close()
        }
    }
}
