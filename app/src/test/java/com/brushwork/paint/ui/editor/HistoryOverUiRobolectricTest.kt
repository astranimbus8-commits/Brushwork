package com.brushwork.paint.ui.editor

import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.ui.geometry.Rect
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.Walk
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.Smoke.P
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowViewConfiguration

/**
 * v1.7 item 10 (design §3.10; area I): "undo and redo also works when using the 2 and 3 fingers
 * over a UI", with real multi-touch events on the user's phone (392 dp):
 * - two fingers on the options bar undo once; three on the bottom bar redo once; the buttons
 *   under the fingers do nothing (no panel, no tool change);
 * - over a panel (the Brush sheet) the same, and what its sliders did under the fingers is put
 *   back; a "Bigger brush" step under the first finger is put back too, and so is the brush
 *   size slider the first finger dragged;
 * - a finger on the canvas and one on the UI: exactly one undo, the canvas finger draws nothing;
 *   the canvas alone keeps its own taps (one undo, not two);
 * - with [FakePillTool], a Scale X drag under the first finger (an in-tool step) is rolled back,
 *   then one undo.
 * One test (Compose's frame clock serves the first test of a sandbox only), own sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.editor.historyoveruisandbox"])
class HistoryOverUiRobolectricTest {

    private lateinit var s: ChromeScreen

    /** Window px of editor dp [p]. */
    private fun px(p: Pair<Float, Float>): Pair<Float, Float> = Finger.px(s, p.first, p.second)

    private fun Rect.at(fx: Float, fy: Float = 0.5f): Pair<Float, Float> = (left + width * fx) to (top + height * fy)

    private fun region(tag: String): Rect = requireNotNull(s.tagged(tag)) { "no $tag on screen" }

    private fun control(label: String): Rect = requireNotNull(Finger.control(s, label)) { "no \"$label\"; shown: ${SmokeUi.shown().take(80)}" }

    private fun twoFingers(a: Pair<Float, Float>, b: Pair<Float, Float>) {
        s.touch.twoFingerTap(px(a), px(b))
        settle()
    }

    private fun threeFingers(a: Pair<Float, Float>, b: Pair<Float, Float>, d: Pair<Float, Float>) {
        s.touch.threeFingerTap(px(a), px(b), px(d))
        settle()
    }

    private fun stroke(y: Float) {
        Finger.slowDrag(s, Walk.docDp(s, 80f, y), Walk.docDp(s, 320f, y + 20f))
        Smoke.pump(200)
        settle()
    }

    /** Sets the activity's touch slop to a phone's 8 dp (Robolectric's shadow keeps 16 dp). */
    private fun phoneTouchSlop(s: ChromeScreen) {
        val shadow = Shadow.extract<ShadowViewConfiguration>(ViewConfiguration.get(s.activity))
        ShadowViewConfiguration::class.java.getDeclaredField("touchSlop").apply { isAccessible = true }.setInt(shadow, (8 * s.density).toInt())
        assertEquals((8 * s.density).toInt(), ViewConfiguration.get(s.activity).scaledTouchSlop)
    }

    /**
     * The first finger goes down at [first] (editor dp) and drags 10 dp to the right (under the
     * 12 dp tap slop), [whileDragging] checks what the drag did, then a second finger lands at
     * [other] and both lift: a two-finger tap.
     */
    private fun dragThenSecondFinger(first: Pair<Float, Float>, other: Pair<Float, Float>, whileDragging: () -> Unit) {
        val a = px(first)
        val b = px(other)
        val t = s.touch
        t.send(MotionEvent.ACTION_DOWN, P(0, a.first, a.second))
        for (i in 1..5) {
            t.idle(16)
            t.send(MotionEvent.ACTION_MOVE, P(0, a.first + 2f * i * s.density, a.second))
        }
        settle(2, 10)
        whileDragging()
        val dragged = a.first + 10f * s.density
        t.send(MotionEvent.ACTION_POINTER_DOWN, P(0, dragged, a.second), P(1, b.first, b.second), index = 1)
        t.idle(40)
        t.send(MotionEvent.ACTION_POINTER_UP, P(0, dragged, a.second), P(1, b.first, b.second), index = 0)
        t.idle(20)
        t.send(MotionEvent.ACTION_UP, P(1, b.first, b.second))
        settle()
    }

    private val steps: Int get() = s.c.undoManager.undoCount

    private fun feedback(): String? = SmokeUi.shown().lastOrNull { it.startsWith("Undo: ") || it.startsWith("Redo: ") }

    @Test
    fun historyTapsOverTheUi() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = ChromeHarness()
        h.section("history taps over the UI") {
            s = h.editor(Smoke.document(400, 300, layers = 2, whiteBottom = true))
            val c = s.c
            assertTrue("both taps are on by default", c.settings.twoFingerUndo && c.settings.threeFingerRedo)
            for (y in listOf(40f, 80f, 120f, 160f, 200f)) stroke(y)
            assertEquals("five strokes", 5, steps)

            // ------------------------------------------------ options bar: two fingers undo
            val strip = region(ChromeTags.OPTIONS_STRIP)
            twoFingers(strip.at(0.3f), strip.at(0.7f))
            assertEquals("one undo", 4, steps)
            assertTrue(c.canRedo)
            assertTrue("the feedback: ${SmokeUi.shown().take(40)}", feedback()?.startsWith("Undo: ") == true)
            assertTrue("nothing opened", SmokeUi.sheetTitles().isEmpty() && SmokeUi.pillTitles().isEmpty())

            // ------------------------------------------------ bottom bar: three fingers redo
            val bar = region(ChromeTags.BOTTOM_BAR)
            threeFingers(bar.at(0.2f), bar.at(0.5f), bar.at(0.8f))
            assertEquals("one redo", 5, steps)
            assertEquals("no button fired", ToolId.BRUSH, c.activeToolId)
            assertTrue(SmokeUi.sheetTitles().isEmpty() && SmokeUi.pillTitles().isEmpty())
            assertNull("no tool menu", s.tagged(ChromeTags.TOOL_MENU))
            assertNull("no layer window", s.tagged(ChromeTags.LAYER_WINDOW))

            // ------------------------------------------------ the buttons under the fingers
            twoFingers(control("Open brush settings").at(0.5f), control("Open color picker").at(0.5f))
            assertEquals(4, steps)
            assertTrue("the brush settings did not open", SmokeUi.sheetTitles().isEmpty() && SmokeUi.pillTitles().isEmpty())

            // ------------------------------------------------ a step under the first finger is put back
            val size = c.brush.size
            twoFingers(control("Bigger brush").at(0.5f), region(ChromeTags.TOP_ROW).at(0.5f, 0.9f))
            assertEquals("the size the brush had", size, c.brush.size, 1e-4f)
            assertEquals(3, steps)

            // ------------------------------------------------ a slider moved by the first finger is put back
            // Robolectric's touch slop is 16 dp, a phone's 8 dp: a drag of 10 dp (under the 12 dp
            // tap slop) is a drag on the phone only. From here on the test has the phone's slop.
            phoneTouchSlop(s)
            val sizeTrack = requireNotNull(Finger.element(s, "Brush size")) { "no size slider" }
            dragThenSecondFinger(sizeTrack.at(0.5f), region(ChromeTags.TOP_ROW).at(0.5f, 0.9f)) {
                assertNotEquals("the drag moved the size slider", size, c.brush.size)
            }
            assertEquals("the size slider put back", size, c.brush.size, 1e-4f)
            assertEquals("one undo", 2, steps)

            // ------------------------------------------------ over a panel
            SmokeUi.click("Open brush settings", exact = true)
            SmokeUi.assertPanelShown()
            val brush = c.brush
            val panel = s.dp(requireNotNull(SmokeUi.sheetPanel()).bounds)
            twoFingers(panel.at(0.3f, 0.6f), panel.at(0.7f, 0.6f))
            assertEquals("one undo over the sheet", 1, steps)
            assertEquals("its sliders put back", brush, c.brush)
            SmokeUi.assertPanelShown()
            threeFingers(panel.at(0.2f, 0.5f), panel.at(0.5f, 0.5f), panel.at(0.8f, 0.5f))
            assertEquals("one redo over the sheet", 2, steps)
            assertEquals(brush, c.brush)
            Finger.back()
            assertTrue(SmokeUi.sheetTitles().isEmpty() && SmokeUi.pillTitles().isEmpty())

            // ------------------------------------------------ a canvas finger and a UI finger
            twoFingers(Walk.docDp(s, 200f, 150f), region(ChromeTags.OPTIONS_STRIP).at(0.5f))
            assertEquals("exactly one undo", 1, steps)
            assertFalse("the canvas finger left no stroke behind", c.isInteracting)
            assertTrue(c.canRedo)

            // ------------------------------------------------ the canvas alone: its own tap, once
            twoFingers(Walk.docDp(s, 150f, 150f), Walk.docDp(s, 250f, 150f))
            assertEquals("one undo, not two", 0, steps)

            // ------------------------------------------------ an in-tool step under the first finger
            stroke(120f)
            assertEquals(1, steps)
            val fake = FakePillTool(c)
            @Suppress("UNCHECKED_CAST")
            (c.tools as MutableMap<ToolId, Tool>)[ToolId.CURVE] = fake
            c.selectTool(ToolId.CURVE)
            c.snapping.enabled = false
            settle()
            val before = fake.points
            dragThenSecondFinger(control(PillLabels.SCALE_X).at(0.5f), region(ChromeTags.TOP_ROW).at(0.5f, 0.9f)) {
                assertNotEquals("the drag scaled the object (an in-tool step)", before, fake.points)
                assertTrue(fake.steps > 0)
            }
            assertEquals("the drag rolled back", before, fake.points)
            assertEquals(0, fake.steps)
            assertEquals("then one undo", 0, steps)
            assertEquals(Vec2(100f, 100f), fake.objectScale!!.scalePercent)
            Smoke.assertQuiet(c, "history taps over the UI")
        }
        dog.interrupt()
        h.finish()
    }
}
