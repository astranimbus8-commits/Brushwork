package com.brushwork.paint.qa18

import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.common.StripLabels18
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 final QA (items 8 and 9, D25) on the user's phone (392 × 873 dp), with REAL strips picked
 * from the tool menu (`MoreRowHistoryTapsTest` drives a fake one): a long press on "Show more
 * tool options" shows its name and opens nothing. v1.9 (R3, design 2.6): with the More row open,
 * a canvas touch is never consumed and the row stays open: one finger acts at once (Bucket fills,
 * Curve adds a point), a two-finger tap undoes once, three fingers redo once. Only the toggle
 * closes the row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.menushistorytapssandbox"])
class Qa18MenusHistoryTapsTest {

    @Test
    fun anOpenMoreRowLetsEveryTouchActAndHistoryTapsActOnce() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = ChromeHarness()
        h.section("Bucket") { bucket(h) }
        h.section("Curve") { curve(h) }
        dog.interrupt()
        h.finish()
    }

    private fun expanded(s: ChromeScreen): Boolean = Fingers18.state(s, StripLabels18.MORE_TOGGLE) == StripLabels18.EXPANDED

    private fun open(s: ChromeScreen) {
        assertTrue("closed before", !expanded(s) && s.tagged(V18Tags.STRIP_MORE_ROW) == null)
        Finger.tap(s, StripLabels18.MORE_TOGGLE)
        assertTrue("the toggle opens the More row", expanded(s) && s.tagged(V18Tags.STRIP_MORE_ROW) != null)
    }

    private fun assertOpen(s: ChromeScreen, why: String) {
        Smoke.pump(300); SmokeUi.settle()
        assertTrue("$why: the toggle says Expanded", expanded(s))
        assertNotNull("$why: the More row stays", s.tagged(V18Tags.STRIP_MORE_ROW))
    }

    private fun assertClosed(s: ChromeScreen, why: String) {
        assertTrue("$why: the toggle says Collapsed", !expanded(s))
        assertNull("$why: no More row", s.tagged(V18Tags.STRIP_MORE_ROW))
    }

    /** Only the toggle closes the row (v1.9); the next section starts with nothing remembered. */
    private fun closeByToggle(s: ChromeScreen) {
        Finger.tap(s, StripLabels18.MORE_TOGGLE)
        Smoke.pump(300); SmokeUi.settle()
        assertClosed(s, "the toggle")
    }

    /** The active layer's pixel under the taps (document 200, 150). */
    private fun at(s: ChromeScreen): Int = s.c.activeLayer.bitmap.getPixel(200, 150)

    private fun bucket(h: ChromeHarness) {
        val s = h.editor(Qa18MenusShots.doc(vector = false))
        val c = s.c
        assertTrue("history taps are on by default", c.settings.twoFingerUndo && c.settings.threeFingerRedo)
        Qa18MenusShots.pick(s, ToolId.FILL)

        // A long press on the toggle names it and opens nothing.
        var named: String? = null
        Fingers18.longPress(s, StripLabels18.MORE_TOGGLE) { named = Fingers18.bubble() }
        assertEquals("the long press names the toggle", StripLabels18.MORE_TOGGLE, named)
        assertClosed(s, "a long press")

        // One finger on the canvas fills at once, one step; the row stays open.
        // (A colour that is not black: a whole 400 × 300 layer of opaque black hashes as an empty one.)
        val red = 0xFFCC3322.toInt()
        c.color = red
        val blank = Fingers18.canvasState(s)
        assertEquals("an empty layer", 0, at(s))
        open(s)
        Fingers18.tap(s, 200f, 150f)
        assertTrue("one finger: the Bucket fills", Smoke.pumpUntil(5_000) { SmokeUi.settle(); at(s) == red })
        val filled = Fingers18.canvasState(s)
        assertNotEquals("…the layer changes", blank.pixels, filled.pixels)
        assertEquals("…one step", blank.undo + 1, filled.undo)
        assertOpen(s, "one finger")

        // Two fingers undo once; three fingers redo once; the row stays open.
        Fingers18.twoFingerTap(s, 200f, 150f)
        val undone = Fingers18.canvasState(s)
        assertEquals("two fingers: the fill undone, once", blank.pixels, undone.pixels)
        assertEquals("…the layer empty again", 0, at(s))
        assertEquals(blank.undo, undone.undo)
        assertTrue("…to redo", undone.redo)
        assertOpen(s, "a two-finger tap")
        Fingers18.threeFingerTap(s, 200f, 150f)
        assertEquals("three fingers: the fill redone, once", filled, Fingers18.canvasState(s))
        assertEquals("…filled again", red, at(s))
        assertOpen(s, "a three-finger tap")
        closeByToggle(s)
        Smoke.assertQuiet(c, "Bucket")
    }

    private fun curve(h: ChromeHarness) {
        val s = h.editor(Qa18MenusShots.doc(vector = false))
        Qa18MenusShots.pick(s, ToolId.CURVE)
        val t = s.c.currentTool as CurveTool
        Fingers18.tap(s, 60f, 70f)
        Fingers18.tap(s, 110f, 100f)
        assertEquals("two points by two taps", 2, t.pointCount)
        open(s)
        Fingers18.tap(s, 150f, 80f)
        assertEquals("one finger with the row open adds a point", 3, t.pointCount)
        assertOpen(s, "one finger")
        // A two-finger tap through the open row steps the last point back (the top row's Undo does).
        Fingers18.twoFingerTap(s, 200f, 150f)
        assertEquals("two fingers: one point back", 2, t.pointCount)
        assertOpen(s, "a two-finger tap")
        Fingers18.threeFingerTap(s, 200f, 150f)
        assertEquals("three fingers: the point again", 3, t.pointCount)
        assertOpen(s, "a three-finger tap")
        closeByToggle(s)
        Smoke.assertQuiet(s.c, "Curve")
    }
}