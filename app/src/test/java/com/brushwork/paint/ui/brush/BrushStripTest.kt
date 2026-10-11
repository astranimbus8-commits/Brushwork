package com.brushwork.paint.ui.brush

import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.brush.MirrorsStripKit.assertSplits
import com.brushwork.paint.ui.brush.MirrorsStripKit.back
import com.brushwork.paint.ui.brush.MirrorsStripKit.inspect
import com.brushwork.paint.ui.brush.MirrorsStripKit.key
import com.brushwork.paint.ui.brush.MirrorsStripKit.row1Handles
import com.brushwork.paint.ui.brush.MirrorsStripKit.staysOpen
import com.brushwork.paint.ui.vector.VectorEraserStrip
import com.brushwork.paint.vector.draw.VectorEraseMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 D (design §3.9 "Brush", "Eraser", "Smudge, Blur"): the brush tools' converted strip at 360
 * and 392 dp, on the raster layer and on the vector layer (the VECTOR cell):
 * - Brush, raster Eraser, Smudge, Blur: row 1 only, "Choose brush", "Brush size", "Opacity" (or
 *   "Strength"), "Pressure changes size", no toggle, everywhere; "All brush settings" stays in
 *   the size and opacity popups and opens the brush panel.
 * - The vector Eraser: the three modes, "Brush size", "Opacity" (AUTO 1: on the More row's front
 *   at 360 dp with the VECTOR cell); the More row "Choose brush", "Pressure changes size"; the
 *   brush panel opened from the More row outlives it.
 * Measured widths (rule 4, rounded up to 4 dp) differ from the planning table: "Brush size" 76
 * (for "1000 px"), "Opacity" 60 (for "100%"); the sums stay within rule 1 (276 of 288, and 196 of
 * 240 for the vector eraser), so no item became AUTO.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h780dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.brush.brushstripsandbox"])
class BrushStripTest {

    private val plain = listOf(BrushStripItems.CHOOSE, BrushStripItems.SIZE, BrushStripItems.OPACITY, BrushStripItems.PRESSURE)

    @Test
    fun theBrushToolsStripsKeepTheirRowsAndLabelsAtBothWidths() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        MirrorsStripKit.atEachWidth { rig, dp ->
            for (vector in listOf(false, true)) for (id in listOf(ToolId.BRUSH, ToolId.ERASER, ToolId.SMUDGE, ToolId.BLUR)) {
                val k = key(id, vector)
                rig.select(id, vector)
                assertEquals(k, id, rig.c.activeToolId)
                val shown = inspect(rig, k)
                val spec = rig.spec
                val modes = id == ToolId.ERASER && vector
                if (modes) {
                    assertSplits(k, spec) { w, chip ->
                        val r1 = listOf(VectorEraserStrip.KEY, BrushStripItems.SIZE)
                        if (w == 360 && chip) r1 else r1 + BrushStripItems.OPACITY
                    }
                    // The modes first, unchanged; the brush's rarer controls on the More row.
                    val row1 = row1Handles()
                    val want = VectorEraseMode.entries.map { it.label } + "Brush size" + if (dp == 392) listOf("Opacity") else emptyList()
                    assertEquals("$k at $dp dp: row 1", want, row1)
                    assertTrue("$k: the toggle", StripTestKit.toggle() != null)
                    assertTrue(shown.containsAll(listOf("Choose brush", "Pressure changes size: off", "Opacity")))
                } else {
                    assertSplits(k, spec) { _, _ -> plain }
                    val strength = id == ToolId.SMUDGE || id == ToolId.BLUR
                    val pressure = if (id == ToolId.BRUSH) "Pressure changes size: on" else "Pressure changes size: off"
                    assertEquals("$k at $dp dp: row 1", listOf("Choose brush", "Brush size", if (strength) "Strength" else "Opacity", pressure), row1Handles())
                    assertNull("$k: no More row, no toggle", StripTestKit.toggle())
                }
            }

            // "All brush settings" stays inside the size popup and opens the brush panel.
            rig.select(ToolId.BRUSH)
            SmokeUi.click("Brush size", exact = true)
            assertTrue("the size popup", SmokeUi.has(BrushStripItems.ALL_SETTINGS, exact = true))
            SmokeUi.click(BrushStripItems.ALL_SETTINGS, exact = true)
            rig.settle()
            assertTrue("the brush panel: ${SmokeUi.shown()}", SmokeUi.has("PRESETS", exact = true))
            back(rig)
            assertFalse(SmokeUi.has("PRESETS", exact = true))
            // A strip control still works: the pressure toggle.
            SmokeUi.click("Pressure changes size: on", exact = true)
            assertTrue(SmokeUi.has("Pressure changes size: off", exact = true))
            SmokeUi.click("Pressure changes size: off", exact = true)

            // The vector eraser: a mode on row 1, and the brush panel opened from the More row
            // stays open after the row closes.
            rig.select(ToolId.ERASER, vector = true)
            SmokeUi.click(VectorEraseMode.PARTIAL.label, exact = true)
            assertEquals(VectorEraseMode.PARTIAL, com.brushwork.paint.vector.draw.VectorEraserModes.mode(rig.c))
            SmokeUi.click(VectorEraseMode.OBJECT.label, exact = true)
            staysOpen(rig, "Choose brush", "$dp dp, the vector eraser's brush panel") { SmokeUi.has("PRESETS", exact = true) }
            back(rig)
            assertFalse(SmokeUi.has("PRESETS", exact = true))
            rig.select(ToolId.BRUSH)
        }
    }
}
