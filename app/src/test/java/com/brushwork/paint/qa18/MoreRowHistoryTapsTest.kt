package com.brushwork.paint.qa18

import android.graphics.Matrix
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Layer
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.common.StripLabels18
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.select.ObjectTestKit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * v1.8 integration (a lead rule over part of D25): history taps through an open More row, on the
 * user's phone (392 × 873 dp), with real multi-finger touch events through the editor's window.
 * With the options strip's More row open, or the vector object bar's:
 * - a two-finger tap on the canvas closes the row AND undoes, exactly once;
 * - a three-finger tap closes it AND redoes, exactly once;
 * - one finger (a tap or a drag) only closes it: no dot, no stroke, no step, the selection kept;
 * - a pinch or a two-finger pan only closes it: no zoom, no pan, no paint, no step;
 * - with the two-finger undo setting off, a two-finger tap only closes it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.morerowhistorytapssandbox"])
class MoreRowHistoryTapsTest {

    @Test
    fun historyTapsCloseAnOpenMoreRowAndActOnceOneFingerOnlyCloses() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = ChromeHarness()
        h.section("the strip's More row") { stripRow() }
        val kit = ObjectTestKit(400, 300)
        try {
            h.section("the object bar's More row") { objectRow(kit) }
        } finally {
            kit.close()
        }
        dog.interrupt()
        h.finish()
    }

    // ------------------------------------------------------------------ the rows

    /** One of the two More rows: its toggle and how to tell it is open. */
    private class Row(val toggle: String)

    private val strip = Row(StripLabels18.MORE_TOGGLE)
    private val objects = Row(StripLabels18.MORE_OBJECT)

    private fun expanded(s: ChromeScreen, row: Row): Boolean = when (Fingers18.state(s, row.toggle)) {
        StripLabels18.EXPANDED -> true
        StripLabels18.COLLAPSED -> false
        else -> throw AssertionError("no \"${row.toggle}\" with a state")
    }

    private fun open(s: ChromeScreen, row: Row) {
        assertFalse("${row.toggle}: closed before", expanded(s, row))
        Finger.tap(s, row.toggle)
        Smoke.pump(300); SmokeUi.settle()
        assertTrue("${row.toggle} opens its More row", expanded(s, row))
        assertNotNull(s.tagged(V18Tags.STRIP_MORE_ROW))
    }

    private fun assertClosed(s: ChromeScreen, row: Row, why: String) {
        Smoke.pump(300); SmokeUi.settle()
        assertNull("$why: no More row", s.tagged(V18Tags.STRIP_MORE_ROW))
        assertFalse("$why: collapsed", expanded(s, row))
    }

    /** The canvas once every vector render is done. */
    private fun state(s: ChromeScreen): Fingers18.CanvasState {
        Smoke.pumpUntil(10_000) { !s.c.vectors.isRendering }
        SmokeUi.settle()
        return Fingers18.canvasState(s)
    }

    /**
     * The touches every row is put through, with the canvas at [two] (two steps to undo, the
     * second one's state [one] below it): each closes the row; only the history taps act.
     */
    private fun touches(s: ChromeScreen, row: Row, one: Fingers18.CanvasState, two: Fingers18.CanvasState) {
        val c = s.c
        val view = Matrix(c.viewTransform.matrix)
        assertEquals("${row.toggle}: two steps apart", one.undo + 1, two.undo)
        assertFalse(two.redo)

        // One finger: a tap, then a drag. Only closes.
        open(s, row)
        Fingers18.tap(s, 300f, 150f)
        assertClosed(s, row, "a one-finger tap")
        assertEquals("a one-finger tap: no dot, no step", two, state(s))
        open(s, row)
        Fingers18.stroke(s, 250f, 160f, 380f, 190f)
        assertClosed(s, row, "a one-finger drag")
        assertEquals("a one-finger drag: no stroke, no step", two, state(s))

        // Two fingers moving: a pinch, then a pan. Only closes.
        open(s, row)
        Fingers18.pinch(s, 200f, 150f)
        assertClosed(s, row, "a pinch")
        assertEquals("a pinch: no zoom, no paint, no step", two, state(s))
        assertEquals("a pinch: the view never moved", view, c.viewTransform.matrix)
        open(s, row)
        Fingers18.pan(s, 200f, 150f)
        assertClosed(s, row, "a two-finger pan")
        assertEquals("a two-finger pan: no paint, no step", two, state(s))
        assertEquals("a two-finger pan: no pan", view, c.viewTransform.matrix)

        // A two-finger tap: closes and undoes once.
        open(s, row)
        Fingers18.twoFingerTap(s, 200f, 150f)
        assertClosed(s, row, "a two-finger tap")
        assertEquals("a two-finger tap: exactly one undo", one.copy(redo = true), state(s))

        // A three-finger tap: closes and redoes once.
        open(s, row)
        Fingers18.threeFingerTap(s, 200f, 150f)
        assertClosed(s, row, "a three-finger tap")
        assertEquals("a three-finger tap: exactly one redo", two, state(s))

        // The two-finger undo off: a two-finger tap only closes.
        c.settings.twoFingerUndo = false
        try {
            open(s, row)
            Fingers18.twoFingerTap(s, 200f, 150f)
            assertClosed(s, row, "a two-finger tap with the undo off")
            assertEquals("the two-finger undo off: no undo", two, state(s))
        } finally {
            c.settings.twoFingerUndo = true
        }
        assertEquals("the view never moved", view, c.viewTransform.matrix)
    }

    private fun stripRow() = FakeStripEditor.open(Smoke.document(400, 300, layers = 2, whiteBottom = true), FakeStrips.spec(2)).use { e ->
        val s = e.screen
        val c = s.c
        assertEquals(ToolId.BRUSH, c.activeToolId)
        assertTrue(c.settings.twoFingerUndo && c.settings.threeFingerRedo)
        val blank = state(s)
        Fingers18.stroke(s, 60f, 100f, 340f, 120f)
        val one = state(s)
        assertNotEquals("the first stroke paints", blank.pixels, one.pixels)
        Fingers18.stroke(s, 60f, 200f, 340f, 220f)
        val two = state(s)
        assertNotEquals("the second stroke paints", one.pixels, two.pixels)
        touches(s, strip, one, two)
        Smoke.assertQuiet(c, "the strip's More row")
    }

    private fun objectRow(kit: ObjectTestKit) {
        val doc = Smoke.document(400, 300, layers = 1, whiteBottom = true).also { d ->
            d.layers += Layer(d.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(400, 300)).also { it.vector = VectorContent.EMPTY }
            d.activeLayerIndex = 1
        }
        FakeStripEditor.open(doc, FakeStrips.spec(1)) { c ->
            val layer = c.doc.layers[1]
            c.vectors.addObjects(layer, listOf(kit.box(40f, 40f, 120f, 110f), kit.stroke(60f, 200f, 200f, 230f)), "Add")
            c.vectors.setSelection(layer, setOf(1L))
        }.use { e ->
            val s = e.screen
            val c = s.c
            val one = state(s)
            assertEquals("one object selected", setOf(1L), one.objects)
            assertFalse(expanded(s, objects))
            // A step that keeps the selection: recolor it with another main color.
            c.color = RECOLOR
            Smoke.pump(100); SmokeUi.settle()
            Finger.tap(s, "Recolor with the main color")
            val two = state(s)
            assertNotEquals("the recolor shows", one.pixels, two.pixels)
            assertEquals("the selection stays", one.objects, two.objects)
            touches(s, objects, one, two)
            Smoke.assertQuiet(c, "the object bar's More row")
        }
    }

    private companion object {
        const val RECOLOR = 0xFF0000FF.toInt()
    }
}
