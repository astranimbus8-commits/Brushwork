package com.brushwork.paint.ui.editor

import androidx.compose.ui.geometry.Rect
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.Walk
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.common.PillLabels
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.tools.FakePillTool
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
 * v1.7 item 10 (design §3.10; area I), the review's end-to-end checks on the user's phone
 * (392 dp), with real multi-touch events:
 * - the UI finger first, then a canvas finger: one undo, the canvas finger draws nothing, and the
 *   canvas' own two-finger tap still undoes afterwards;
 * - the settings (§3.10 "Both respect the twoFingerUndo and threeFingerRedo settings"): with the
 *   two-finger undo off, two fingers on the UI do not undo and the button under the first finger
 *   still fires, while three fingers still redo (no button fires); with the three-finger redo
 *   off, three fingers neither redo nor undo, and no button fires;
 * - the first finger on a pill cell (X, Scale X): one undo, and the cell's tap does not open its
 *   typing dialog when the fingers lift (the cell gives the touch up once the hub claims it).
 *   Last, because [FakePillTool] has no options: the options strip goes once it is active.
 * One test (Compose's frame clock serves the first test of a sandbox only), own sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.editor.historytapspillsandbox"])
class HistoryTapsPillAndSettingsRobolectricTest {

    private lateinit var s: ChromeScreen

    private fun px(p: Pair<Float, Float>): Pair<Float, Float> = Finger.px(s, p.first, p.second)

    private fun Rect.at(fx: Float, fy: Float = 0.5f): Pair<Float, Float> = (left + width * fx) to (top + height * fy)

    private fun region(tag: String): Rect = requireNotNull(s.tagged(tag)) { "no $tag on screen; windows ${SmokeUi.windows().size}, shown: ${SmokeUi.shown().take(60)}" }

    private fun control(label: String): Rect = requireNotNull(Finger.control(s, label)) { "no \"$label\"; shown: ${SmokeUi.shown().take(80)}" }

    private fun twoFingers(a: Pair<Float, Float>, b: Pair<Float, Float>) {
        s.touch.idle(200)
        s.touch.twoFingerTap(px(a), px(b))
        settle()
    }

    private fun threeFingers(a: Pair<Float, Float>, b: Pair<Float, Float>, d: Pair<Float, Float>) {
        s.touch.idle(200)
        s.touch.threeFingerTap(px(a), px(b), px(d))
        settle()
    }

    private fun stroke(y: Float) {
        Finger.slowDrag(s, Walk.docDp(s, 80f, y), Walk.docDp(s, 320f, y + 20f))
        Smoke.pump(200)
        settle()
    }

    private val steps: Int get() = s.c.undoManager.undoCount

    private fun nothingOpen(): Boolean = SmokeUi.sheetTitles().isEmpty() && SmokeUi.pillTitles().isEmpty() && SmokeUi.windows().size <= 1

    @Test
    fun pillCellsUiThenCanvasAndTheSettings() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = ChromeHarness()
        h.section("history taps: UI then canvas, the settings, the pill") {
            s = h.editor(Smoke.document(400, 300, layers = 2, whiteBottom = true))
            val c = s.c
            for (y in listOf(30f, 70f, 110f, 150f, 190f, 230f)) stroke(y)
            assertEquals("six strokes", 6, steps)
            val windows = SmokeUi.windows().size

            // ------------------------------------------------ the UI finger first, then a canvas finger
            twoFingers(region(ChromeTags.OPTIONS_STRIP).at(0.5f), Walk.docDp(s, 200f, 150f))
            assertEquals("exactly one undo", 5, steps)
            assertFalse("the canvas finger drew nothing", c.isInteracting)
            assertTrue(nothingOpen())
            // The canvas' own two-finger tap afterwards: still one undo.
            twoFingers(Walk.docDp(s, 150f, 150f), Walk.docDp(s, 250f, 150f))
            assertEquals("the canvas' own tap", 4, steps)
            assertFalse(c.isInteracting)

            // ------------------------------------------------ the two-finger undo off
            c.settings.twoFingerUndo = false
            assertTrue(c.settings.threeFingerRedo)
            twoFingers(control("Open brush settings").at(0.5f), region(ChromeTags.TOP_ROW).at(0.5f, 0.9f))
            assertEquals("no undo", 4, steps)
            assertTrue("the button under the first finger fired: ${SmokeUi.shown().take(40)}", SmokeUi.sheetTitles().isNotEmpty() || SmokeUi.pillTitles().isNotEmpty())
            Finger.back()
            assertTrue(nothingOpen())
            // Three fingers still redo, and no button under them fires.
            val bar = region(ChromeTags.BOTTOM_BAR)
            threeFingers(bar.at(0.2f), bar.at(0.5f), bar.at(0.8f))
            assertEquals("one redo", 5, steps)
            assertEquals("no button fired", ToolId.BRUSH, c.activeToolId)
            assertTrue(nothingOpen())
            assertNull("no tool menu", s.tagged(ChromeTags.TOOL_MENU))
            assertNull("no layer window", s.tagged(ChromeTags.LAYER_WINDOW))

            // ------------------------------------------------ the three-finger redo off
            c.settings.twoFingerUndo = true
            c.settings.threeFingerRedo = false
            assertTrue(c.canRedo)
            threeFingers(bar.at(0.2f), bar.at(0.5f), bar.at(0.8f))
            assertEquals("neither redo nor undo", 5, steps)
            assertEquals("no button fired", ToolId.BRUSH, c.activeToolId)
            assertTrue(nothingOpen())
            assertNull(s.tagged(ChromeTags.TOOL_MENU))
            assertNull(s.tagged(ChromeTags.LAYER_WINDOW))
            // Two fingers still undo.
            twoFingers(region(ChromeTags.OPTIONS_STRIP).at(0.3f), region(ChromeTags.OPTIONS_STRIP).at(0.7f))
            assertEquals(4, steps)
            c.settings.threeFingerRedo = true

            // ------------------------------------------------ the first finger on a pill cell
            val fake = FakePillTool(c)
            @Suppress("UNCHECKED_CAST")
            (c.tools as MutableMap<ToolId, Tool>)[ToolId.CURVE] = fake
            c.selectTool(ToolId.CURVE)
            c.snapping.enabled = false
            settle()
            val points = fake.points
            twoFingers(control("X slider").at(0.5f), region(ChromeTags.TOP_ROW).at(0.5f, 0.9f))
            assertEquals("one undo", 3, steps)
            assertFalse("no \"X position\" dialog: ${SmokeUi.shown().take(40)}", SmokeUi.has("X position", exact = true))
            assertEquals("no window opened", windows, SmokeUi.windows().size)
            assertEquals("the X cell moved nothing", points, fake.points)
            assertEquals("its edits are paired", fake.beginPositionCalls, fake.endPositionCalls)

            twoFingers(control(PillLabels.SCALE_X).at(0.5f), region(ChromeTags.TOP_ROW).at(0.5f, 0.9f))
            assertEquals("one undo", 2, steps)
            assertFalse("no Scale X dialog", SmokeUi.has("% of the size it was selected at"))
            assertEquals(windows, SmokeUi.windows().size)
            assertEquals(points, fake.points)
            assertEquals(fake.beginScaleCalls, fake.endScaleCalls)

            // A one-finger tap on the same cell still types (the cell is not left blocked).
            Finger.tap(s, "X slider")
            assertTrue("a plain tap types X", SmokeUi.has("X position", exact = true))
            Finger.back()
            assertFalse(SmokeUi.has("X position", exact = true))
            Smoke.assertQuiet(c, "history taps: UI then canvas, the settings, the pill")
        }
        dog.interrupt()
        h.finish()
    }
}
