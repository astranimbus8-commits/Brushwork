package com.brushwork.paint.ui.selection

import android.graphics.Canvas
import android.graphics.Paint
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
import com.brushwork.paint.tools.select.SampleSource
import com.brushwork.paint.tools.select.colorrange.ColorSelectTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.ColorSelectLabels18
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
 * v1.8 (C, design §3.5 and §3.9): Color select's strip on a 360 dp phone, on a picture layer and
 * on a vector layer (the VECTOR chip). Row 1: the mode dropdown, the preset chip, the color
 * picker's add and the check, on the screen; the fuzziness leaves row 1 for the More row's front.
 * A tap on the canvas adds the color under it (on the vector layer, with Refer: Canvas). The More
 * row: the fuzziness (its value as its state, its panel opens and the row stays), the brush
 * color's add, clearing the samples (one step), invert, Refer and anti-alias, on the screen. Each
 * item's content fits its declared width. The check selects the colors: one step "Color select".
 *
 * One test: Compose's frame clock only serves the first test of a Robolectric sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h780dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.selection.colornarrowsandbox"])
class ColorSelectStripNarrowTest {

    private val red = 0xFFE01010.toInt()
    private val blue = 0xFF1030D0.toInt()

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
    fun theColorSelectStripFitsA360dpPhone() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val rig = StripRig(panel = false)
        val canvas = Canvas(rig.doc.layers[0].bitmap)
        val paint = Paint()
        paint.color = red; canvas.drawRect(0f, 0f, 200f, 300f, paint)
        paint.color = blue; canvas.drawRect(200f, 0f, 400f, 300f, paint)
        rig.show()
        try {
            val c = rig.c
            val width = rig.activity.resources.displayMetrics.widthPixels
            val density = rig.activity.resources.displayMetrics.density
            for (vector in listOf(false, true)) {
                val where = if (vector) "vector layer" else "picture layer"
                rig.select(ToolId.COLOR_SELECT, vector)
                val tool = c.currentTool as ColorSelectTool
                if (vector) tool.updateSettings(tool.settings.copy(source = SampleSource.CANVAS))
                assertTrue(Smoke.pumpUntil { tool.proxyReady && tool.idle })
                rig.settle()

                // Row 1, on the screen; the fuzziness is not on it at 360 dp.
                for (label in listOf(MagneticLabels18.SELECTION_MODE, ColorSelectLabels18.COLOR_RANGE, ColorSelectLabels18.FROM_PICKER, ColorSelectLabels18.SELECT_COLORS)) {
                    assertOnScreen(where, label, width)
                }
                assertFalse(StripTestKit.moreRowShown())
                assertFalse("$where: the fuzziness left row 1", has(ColorSelectLabels18.FUZZINESS, exact = true))

                // A tap adds the color under the finger.
                c.pointerDown(ToolPoint(50f, 150f))
                c.pointerUp(ToolPoint(50f, 150f))
                rig.settle()
                assertEquals("$where: the tapped color", listOf(red), tool.sampleColors)

                // The More row: every item on the screen.
                assertTrue(StripTestKit.openMoreRow())
                val refer = "Refer: " + (if (vector) SampleSource.CANVAS else SampleSource.LAYER).label
                for (label in listOf(ColorSelectLabels18.FUZZINESS, ColorSelectLabels18.USE_CURRENT, ColorSelectLabels18.CLEAR_SAMPLES, ColorSelectLabels18.INVERT_RANGE, refer, "Anti-alias")) {
                    assertOnScreen(where, label, width)
                }
                assertEquals("40", state(ColorSelectLabels18.FUZZINESS))
                // The brush color: one more sample.
                c.color = 0xFF20A040.toInt()
                click(ColorSelectLabels18.USE_CURRENT, exact = true)
                assertEquals(listOf(red, 0xFF20A040.toInt()), tool.sampleColors)
                // Clearing: one step.
                click(ColorSelectLabels18.CLEAR_SAMPLES, exact = true)
                assertEquals(emptyList<Int>(), tool.sampleColors)
                assertFalse("$where: nothing to clear", SmokeUi.isEnabled(ColorSelectLabels18.CLEAR_SAMPLES))
                c.undo()
                rig.settle()
                assertEquals(listOf(red, 0xFF20A040.toInt()), tool.sampleColors)
                // Invert on and off.
                click(ColorSelectLabels18.INVERT_RANGE, exact = true)
                assertTrue(tool.settings.invert)
                click(ColorSelectLabels18.INVERT_RANGE, exact = true)
                assertFalse(tool.settings.invert)
                // The fuzziness's panel, from the More row: it opens and the row stays.
                val before = SmokeUi.windows().size
                click(ColorSelectLabels18.FUZZINESS, exact = true)
                assertTrue("$where: the fuzziness panel", SmokeUi.windows().size > before)
                assertTrue(StripTestKit.moreRowShown())
                back(rig)
                assertEquals(before, SmokeUi.windows().size)
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

                // The check: one selection step.
                click(ColorSelectLabels18.SELECT_COLORS, exact = true)
                assertTrue(Smoke.pumpUntil { tool.idle })
                rig.settle()
                assertFalse(tool.hasPendingWork)
                assertEquals(listOf(red, 0xFF20A040.toInt()), tool.sampleColors)
                if (!vector) {
                    assertEquals(ToolId.COLOR_SELECT.label, c.undoManager.undoLabel)
                    assertEquals(255, c.selection!!.alphaAt(50, 150))
                    assertEquals(0, c.selection!!.alphaAt(300, 150))
                }
                c.deselect()
                tool.clearSamples()
                tool.samples.resetHistory() // no step left for the layer change to confirm
                tool.updateSettings(tool.settings.copy(source = SampleSource.LAYER))
                rig.settle()
            }
            Smoke.assertQuiet(c, "color select strip")
        } finally {
            rig.close()
        }
    }
}
