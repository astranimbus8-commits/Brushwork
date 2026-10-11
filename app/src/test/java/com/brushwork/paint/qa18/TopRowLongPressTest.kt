package com.brushwork.paint.qa18

import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.IbisDocs
import com.brushwork.paint.qa16.Walk
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.time.Duration

/**
 * v1.8 (design §3.9 item 9): a long press on a top-row circle or a selection-bar item shows its
 * label in a bubble (tag [V18Tags.LONG_PRESS_BUBBLE]) and does nothing else: no undo, no vector
 * mode, no panel, no menu, no copy. A tap still does what it did in v1.7. On the user's phone
 * (392 × 873 dp), with real touch events: down, held past the long-press time, up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.longpresssandbox"])
class TopRowLongPressTest {

    /** The circles' labels, left to right (I10, unchanged since v1.6). */
    private val circles = listOf("Undo", "Redo", "Vector", "Selection", "Stabilizer", "Grid", "Ruler", "More options")

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** Holds for [ms] of REAL time while running the looper (Compose times a long press on the wall clock). */
    private fun holdRealTime(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            Thread.sleep(10)
            idle(10)
        }
    }

    private fun send(v: View, downTime: Long, action: Int, x: Float, y: Float) {
        val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        v.dispatchTouchEvent(e)
        e.recycle()
    }

    /** The bubble's text, or null when none shows. */
    private fun bubble(): String? = StripTestKit.tagged(V18Tags.LONG_PRESS_BUBBLE)?.let { b ->
        generateSequence(listOf(b)) { level -> level.flatMap { it.children }.ifEmpty { null } }.flatten()
            .firstNotNullOfOrNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text }
    }

    /** A finger held still on the control labelled [label] for 800 ms; [held] runs before it lifts. */
    private fun longPress(s: ChromeScreen, label: String, held: () -> Unit) {
        val r = Finger.control(s, label) ?: throw AssertionError("no \"$label\" on screen")
        val (x, y) = Finger.px(s, r.center.x, r.center.y)
        val v = s.activity.window.decorView
        val t0 = SystemClock.uptimeMillis()
        send(v, t0, MotionEvent.ACTION_DOWN, x, y)
        holdRealTime(800)
        held()
        send(v, t0, MotionEvent.ACTION_UP, x, y)
        Smoke.pump(300); SmokeUi.settle()
    }

    /** What a stray action would change. */
    private data class Snapshot(val undo: Boolean, val redo: Boolean, val vector: Boolean, val windows: Int, val sheets: List<String>, val pills: List<String>, val clip: Any?)

    private fun snapshot(s: ChromeScreen) = Snapshot(
        s.c.canUndo, s.c.canRedo, s.c.isVectorMode, SmokeUi.windows().size, SmokeUi.sheetTitles(), SmokeUi.pillTitles(), s.c.clipboard,
    )

    @Test
    fun aLongPressShowsTheLabelAndATapStillActs() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = ChromeHarness()
        h.section("top row") { topRow(h) }
        h.section("selection bar") { selectionBar(h) }
        dog.interrupt()
        h.finish()
    }

    private fun topRow(h: ChromeHarness) {
        val s = h.editor(IbisDocs.page())
        Walk.stroke(s)
        assertTrue("a stroke to undo", s.c.canUndo)
        for (label in circles) {
            val before = snapshot(s)
            longPress(s, label) { assertEquals("$label: the bubble shows while held", label, bubble()) }
            assertEquals("$label: the bubble stays after the finger lifts", label, bubble())
            assertEquals("$label: a long press does nothing else", before, snapshot(s))
        }
        Smoke.assertQuiet(s.c, "top row long presses")

        // A tap still acts, as in v1.7 (the bubble of the last long press may still show).
        Finger.tap(s, "Undo")
        assertFalse("Undo undid the stroke", s.c.canUndo)
        Finger.tap(s, "Redo")
        assertTrue("Redo redid it", s.c.canUndo)
        Finger.tap(s, "Vector")
        assertTrue("Vector turns vector mode on", s.c.isVectorMode)
        Finger.tap(s, "Vector")
        assertFalse("…and off", s.c.isVectorMode)
        for (panel in listOf("Selection", "Stabilizer", "Grid", "Ruler")) {
            Finger.tap(s, panel)
            Walk.panelThenBack(panel)
        }
        Finger.tap(s, "More options")
        Smoke.pump(300); SmokeUi.settle()
        assertEquals("More options drops the menu", 2, SmokeUi.windows().size)
        Finger.back()
        assertEquals(1, SmokeUi.windows().size)
        // The bubble has gone by itself after 1.5 s.
        holdRealTime(1800)
        SmokeUi.settle()
        assertNull("the bubble went", bubble())
        Smoke.assertQuiet(s.c, "top row taps")
    }

    private fun selectionBar(h: ChromeHarness) {
        val s = h.editor(IbisDocs.page()) { c -> c.selectAll() }
        assertNotNull("the selection bar shows", s.tagged(com.brushwork.paint.ui.editor.chrome.ChromeTags.SELECTION_BAR))
        assertNull("nothing copied yet", s.c.clipboard)
        // The bubble says what the item does (its click label), as a screen reader does.
        for (label in listOf("Copy selection", "Invert the selection", "Selection menu")) {
            val before = snapshot(s)
            val selection = s.c.selection
            longPress(s, label) { assertEquals("$label: the bubble shows while held", label, bubble()) }
            assertEquals("$label: a long press does nothing else", before, snapshot(s))
            assertTrue("$label: the selection is untouched", s.c.selection === selection)
        }
        Smoke.assertQuiet(s.c, "selection bar long presses")
        Finger.tap(s, "Copy selection")
        assertNotNull("a tap on Copy copies", s.c.clipboard)
        Finger.tap(s, "Selection menu")
        Walk.panelThenBack("Selection")
        Smoke.assertQuiet(s.c, "selection bar taps")
    }
}
