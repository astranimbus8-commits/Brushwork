package com.brushwork.paint.qa19

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.engine.PerfTrace
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.common.StabilityLabels19
import com.brushwork.paint.ui.common.V19Tags
import com.brushwork.paint.ui.diagnostics.DiagnosticsSection
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.9 F3 (design 2.6 step 8; R9; I27): the "Show frame times" switch and the frame-times overlay.
 * - The Diagnostics section ([DiagnosticsSection]) shows its header and the switch
 *   ([StabilityLabels19.SHOW_FRAME_TIMES]), off by default, whole at font scale 1 and at each of
 *   [Qa19Ui.FONT_SCALES]. Turning it on saves `AppSettings.showFrameTimes`, forgets the old
 *   samples and records ([PerfTrace.enabled]); turning it off stops both.
 * - In the editor, through More options › Settings: while the switch is off the canvas draws no
 *   overlay and no node is tagged [V19Tags.FRAME_TIMES]. On, a frame draws it at the free area's
 *   top left, and the tagged node over it reads "frame p50 … p90 … max … ms" and "last lift -";
 *   after a brush stroke, "last lift … ms". Off again, the overlay and the node go.
 * The canvas is drawn into a bitmap for each frame (as `LiveQa` does): Robolectric runs no vsync
 * draw of the view.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392, instrumentedPackages = ["com.brushwork.paint.qa19.frametimessandbox"])
class Qa19FrameTimesUiTest {
    private val app: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun theShowFrameTimesSwitchAndTheOverlay() = Qa19Ui.uiTest { h ->
        try {
            for (scale in listOf(1f) + Qa19Ui.FONT_SCALES) h.section("the Diagnostics section at font scale $scale") {
                AppSettings(app).prefs.edit().clear().commit()
                PerfTrace.enabled = false
                PerfTrace.clear()
                h.show(fontScale = scale) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { DiagnosticsSection() }
                }
                assertTrue("the header: ${Qa19Ui.shown()}", Qa19Ui.has(StabilityLabels19.DIAGNOSTICS.uppercase(), exact = true))
                assertTrue("the switch: ${Qa19Ui.shown()}", Qa19Ui.has(StabilityLabels19.SHOW_FRAME_TIMES, exact = true))
                assertEquals("off by default", ToggleableState.Off, switchState())
                Qa19Ui.assertNoTextOverflow("the Diagnostics section at font scale $scale")

                // An old sample, recorded before the switch: turning it on forgets it.
                PerfTrace.enabled = true
                PerfTrace.record(PerfTrace.FRAME, 5_000_000L)
                PerfTrace.enabled = false
                Qa19Ui.click(StabilityLabels19.SHOW_FRAME_TIMES, exact = true)
                assertEquals(ToggleableState.On, switchState())
                assertTrue("saved on", AppSettings(app).showFrameTimes)
                assertTrue("recording", PerfTrace.enabled)
                assertEquals("the old samples forgotten", 0, PerfTrace.stats(PerfTrace.FRAME).count)
                Qa19Ui.assertNoTextOverflow("the switch on, at font scale $scale")

                Qa19Ui.click(StabilityLabels19.SHOW_FRAME_TIMES, exact = true)
                assertEquals(ToggleableState.Off, switchState())
                assertFalse("saved off", AppSettings(app).showFrameTimes)
                assertFalse("not recording", PerfTrace.enabled)
            }

            h.section("the overlay over the editor") {
                val s = h.editor { c -> c.selectTool(ToolId.BRUSH) }
                PerfTrace.enabled = false
                PerfTrace.clear()
                val c = s.c

                // Off: no overlay is drawn, no node.
                assertFalse(c.settings.showFrameTimes)
                val off = frame(s)
                assertNull("off: no frame-times node", overlayText())

                // On, through More options › Settings: the next frame draws it, the node reads it.
                settingsSwitch(on = true)
                assertTrue("saved on", c.settings.showFrameTimes)
                assertTrue("recording", PerfTrace.enabled)
                val on = frame(s)
                val text = overlayText() ?: throw AssertionError("on: no node tagged ${V19Tags.FRAME_TIMES}; shown: ${Qa19Ui.shown().take(40)}")
                assertTrue("the overlay's text: \"$text\"", OVERLAY.matches(text))
                assertTrue("…no lift yet: \"$text\"", text.endsWith("\n" + LIFT_NONE))
                val node = s.tagged(V19Tags.FRAME_TIMES)!!
                assertEquals("the node sits 8 dp into the canvas' fit area (its left band is 0)", 8f, node.left, 0.5f)
                // Under the node's top left (in the box's top padding, beside no text), the box is
                // drawn: dark over the surround; without the overlay that pixel is the surround.
                val (px, py) = canvasPx(s, node.left + 10f, node.top + 3f)
                assertTrue(
                    "the overlay drawn under its node: ${Integer.toHexString(on.getPixel(px, py))} over ${Integer.toHexString(off.getPixel(px, py))}",
                    luma(on.getPixel(px, py)) < luma(off.getPixel(px, py)) - 120,
                )
                // Left of the node, nothing is drawn.
                val (lx, ly) = canvasPx(s, node.left - 4f, node.top + 3f)
                assertEquals("nothing drawn left of the node", off.getPixel(lx, ly), on.getPixel(lx, ly))

                // A brush stroke: its lift shows (the overlay reports at most every 500 ms).
                s.touch.stroke(s.screen(100f, 150f), s.screen(200f, 160f), s.screen(300f, 150f))
                Smoke.pump(300)
                SmokeUi.settle()
                assertNotNull("the lift was timed", PerfTrace.lastMs(PerfTrace.LIFT))
                Smoke.pump(600)
                frame(s)
                val lifted = overlayText() ?: throw AssertionError("no frame-times node after the stroke")
                assertTrue("after a stroke: \"$lifted\"", OVERLAY.matches(lifted) && LIFT.matches(lifted.substringAfter('\n')))
                assertTrue("…frames counted", PerfTrace.stats(PerfTrace.FRAME).count >= 2)

                // Off again: the node goes at once, and no frame draws the overlay.
                settingsSwitch(on = false)
                assertFalse("saved off", c.settings.showFrameTimes)
                assertFalse("not recording", PerfTrace.enabled)
                assertNull("off: the node went", overlayText())
                Smoke.pump(600)
                val offAgain = frame(s)
                assertNull("off: no node after a frame", overlayText())
                assertEquals("off: the overlay is not drawn", off.getPixel(px, py), offAgain.getPixel(px, py))
                Smoke.assertQuiet(c, "the overlay over the editor")
            }
        } finally {
            PerfTrace.enabled = false
            PerfTrace.clear()
        }
    }

    /** The switch's state (the toggleable row around its label). */
    private fun switchState(): ToggleableState? {
        var n: SemanticsNode? = Qa19Ui.find(StabilityLabels19.SHOW_FRAME_TIMES, exact = true)?.node
        while (n != null && n.config.getOrNull(SemanticsProperties.ToggleableState) == null) n = n.parent
        return n?.config?.getOrNull(SemanticsProperties.ToggleableState)
    }

    /** More options › Settings, the switch set to [on] (a click when it is not), and Close. */
    private fun settingsSwitch(on: Boolean) {
        Qa19Ui.click("More options")
        Qa19Ui.click("Settings", exact = true)
        assertTrue("the Settings dialog", Qa19Ui.has("Editor settings", exact = true))
        assertTrue("Diagnostics in Settings", Qa19Ui.has(StabilityLabels19.DIAGNOSTICS.uppercase(), exact = true))
        val want = if (on) ToggleableState.On else ToggleableState.Off
        if (switchState() != want) Qa19Ui.click(StabilityLabels19.SHOW_FRAME_TIMES, exact = true)
        assertEquals(want, switchState())
        Qa19Ui.click("Close", exact = true)
        assertFalse("Settings closed", Qa19Ui.has("Editor settings", exact = true))
        Smoke.pump(100)
        SmokeUi.settle()
    }

    /** One frame of the editor's canvas, drawn into a bitmap (its overlay reported to the node). */
    private fun frame(s: ChromeScreen): Bitmap {
        val v = s.canvas
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        SmokeUi.settle()
        return b
    }

    /** The canvas view's pixel under editor point ([x], [y]) (dp). */
    private fun canvasPx(s: ChromeScreen, x: Float, y: Float): Pair<Int, Int> {
        val loc = IntArray(2)
        s.canvas.getLocationInWindow(loc)
        return (s.root.left + x * s.density - loc[0]).toInt() to (s.root.top + y * s.density - loc[1]).toInt()
    }

    /** The text of the node tagged [V19Tags.FRAME_TIMES], or null when there is none. */
    private fun overlayText(): String? =
        Qa19Ui.tagged(V19Tags.FRAME_TIMES)?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

    private fun luma(c: Int): Int = Color.red(c) + Color.green(c) + Color.blue(c)

    private companion object {
        /** Both lines of the overlay (`CanvasView.FRAME_TIMES_FORMAT`, then the lift). */
        val OVERLAY = Regex("""frame p50 \d+\.\d {2}p90 \d+\.\d {2}max \d+\.\d ms\nlast lift (-|\d+\.\d ms)""")
        val LIFT = Regex("""last lift \d+\.\d ms""")
        const val LIFT_NONE = "last lift -"
    }
}
