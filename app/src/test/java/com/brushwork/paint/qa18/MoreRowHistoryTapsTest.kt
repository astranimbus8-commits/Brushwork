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
 * v1.8 integration (a lead rule over part of D25), v1.9 (R3, design 2.6): history taps through an
 * open More row, on the user's phone (392 × 873 dp), with real multi-finger touch events through
 * the editor's window. v1.9: a canvas touch never closes the row and is never consumed. With the
 * options strip's More row open, or the vector object bar's, the row stays open throughout, and:
 * - a two-finger tap on the canvas undoes, exactly once; a three-finger tap redoes, exactly once;
 * - with the two-finger undo setting off, a two-finger tap does nothing; with the three-finger
 *   redo off, a three-finger tap does nothing;
 * - a canvas finger first, then a finger on a control (HistoryTapHub's tap, the canvas yields its
 *   own): a two-finger tap undoes exactly once, two canvas fingers and a UI finger redo exactly
 *   once (two steps to undo and redo, so a double shows), and the control under the UI finger
 *   does not fire;
 * - a pinch zooms and a two-finger pan pans: no paint, no step;
 * - with the strip's row, one finger uses the brush: a drag paints a stroke, one step.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.morerowhistorytapssandbox"])
class MoreRowHistoryTapsTest {

    @Test
    fun historyTapsThroughAnOpenMoreRowActOnceAndTheRowStays() {
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

    private fun assertOpen(s: ChromeScreen, row: Row, why: String) {
        Smoke.pump(300); SmokeUi.settle()
        assertNotNull("$why: the More row stays", s.tagged(V18Tags.STRIP_MORE_ROW))
        assertTrue("$why: expanded", expanded(s, row))
    }

    /** Only the toggle closes the row (v1.9); the next section starts with nothing remembered. */
    private fun closeByToggle(s: ChromeScreen, row: Row) {
        Finger.tap(s, row.toggle)
        Smoke.pump(300); SmokeUi.settle()
        assertNull("the toggle closes the row", s.tagged(V18Tags.STRIP_MORE_ROW))
        assertFalse(expanded(s, row))
    }

    /** The canvas once every vector render is done. */
    private fun state(s: ChromeScreen): Fingers18.CanvasState {
        Smoke.pumpUntil(10_000) { !s.c.vectors.isRendering }
        SmokeUi.settle()
        return Fingers18.canvasState(s)
    }

    /** Window px of the middle of the fake strip's row-1 item: a control, so a finger there is on the UI. */
    private fun uiFinger(s: ChromeScreen): Pair<Float, Float> {
        val r = Finger.control(s, FakeStrips.ROW1) ?: throw AssertionError("no \"${FakeStrips.ROW1}\" on row 1")
        return Finger.px(s, r.center.x, r.center.y)
    }

    /**
     * The touches every row is put through, with the canvas at [two] (two steps to undo: [one]
     * below it, [zero] below that, the row's toggle on screen at each): the row, opened once,
     * stays open through all of them; the history taps act once each, two fingers moving change
     * only the view. The canvas ends at [two], zoomed and panned.
     */
    private fun touches(s: ChromeScreen, row: Row, zero: Fingers18.CanvasState, one: Fingers18.CanvasState, two: Fingers18.CanvasState) {
        val c = s.c
        val view = Matrix(c.viewTransform.matrix)
        assertEquals("${row.toggle}: two steps apart", one.undo + 1, two.undo)
        assertEquals("${row.toggle}: three states", zero.undo + 1, one.undo)
        assertFalse(two.redo)
        open(s, row)

        // A two-finger tap: undoes once.
        Fingers18.twoFingerTap(s, 200f, 150f)
        assertOpen(s, row, "a two-finger tap")
        assertEquals("a two-finger tap: exactly one undo", one.copy(redo = true), state(s))

        // The three-finger redo off: a three-finger tap does nothing.
        c.settings.threeFingerRedo = false
        try {
            Fingers18.threeFingerTap(s, 200f, 150f)
            assertOpen(s, row, "a three-finger tap with the redo off")
            assertEquals("the three-finger redo off: no redo", one.copy(redo = true), state(s))
        } finally {
            c.settings.threeFingerRedo = true
        }

        // A three-finger tap: redoes once.
        Fingers18.threeFingerTap(s, 200f, 150f)
        assertOpen(s, row, "a three-finger tap")
        assertEquals("a three-finger tap: exactly one redo", two, state(s))

        // The two-finger undo off: a two-finger tap does nothing.
        c.settings.twoFingerUndo = false
        try {
            Fingers18.twoFingerTap(s, 200f, 150f)
            assertOpen(s, row, "a two-finger tap with the undo off")
            assertEquals("the two-finger undo off: no undo", two, state(s))
        } finally {
            c.settings.twoFingerUndo = true
        }

        // A canvas finger, then one on a control: HistoryTapHub's two-finger tap. The hub undoes
        // once (a canvas tap as well would undo twice, to [zero]); the control under the UI finger
        // does not fire.
        FakeStrips.taps.clear()
        s.touch.twoFingerTap(s.screen(200f, 150f), uiFinger(s))
        assertOpen(s, row, "a canvas finger and a UI finger")
        assertEquals("a canvas finger and a UI finger: exactly one undo", one.copy(redo = true), state(s))
        assertNull("the control under the UI finger did not fire", FakeStrips.taps[FakeStrips.ROW1])

        // Down to [zero], so two redos are there to take.
        Fingers18.twoFingerTap(s, 200f, 150f)
        assertOpen(s, row, "a two-finger tap at one")
        assertEquals("a two-finger tap at one: one undo", zero.copy(redo = true), state(s))

        // Two canvas fingers, then one on a control: HistoryTapHub's three-finger tap. It redoes
        // once (a canvas tap as well would undo that redo, or redo a second time to [two]); the
        // control does not fire.
        val (cx, cy) = s.screen(200f, 150f)
        val d = 40f * s.density
        s.touch.threeFingerTap((cx - d) to cy, (cx + d) to cy, uiFinger(s))
        assertOpen(s, row, "two canvas fingers and a UI finger")
        assertEquals("two canvas fingers and a UI finger: exactly one redo", one.copy(redo = true), state(s))
        assertNull("the control under the UI finger did not fire", FakeStrips.taps[FakeStrips.ROW1])

        // Back to [two] with the canvas' own three-finger tap.
        Fingers18.threeFingerTap(s, 200f, 150f)
        assertOpen(s, row, "a three-finger tap at one")
        assertEquals("a three-finger tap at one: one redo", two, state(s))
        assertEquals("the history taps never moved the view", view, c.viewTransform.matrix)

        // Two fingers moving: a pinch zooms, a pan pans; no paint, no step.
        Fingers18.pinch(s, 200f, 150f)
        assertOpen(s, row, "a pinch")
        val zoomed = state(s)
        assertNotEquals("a pinch zooms", two.zoom, zoomed.zoom)
        assertEquals("a pinch: no paint, no step", two.copy(zoom = zoomed.zoom), zoomed)
        val beforePan = Matrix(c.viewTransform.matrix)
        Fingers18.pan(s, 200f, 150f)
        assertOpen(s, row, "a two-finger pan")
        assertNotEquals("a two-finger pan pans", beforePan, c.viewTransform.matrix)
        assertEquals("a two-finger pan: no paint, no step", zoomed, state(s))
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
        touches(s, strip, blank, one, two)

        // One finger uses the brush with the row open, and the row stays: a drag paints a stroke,
        // one step (the view zoomed and panned: points at the middle).
        val viewed = state(s)
        Fingers18.stroke(s, 185f, 140f, 215f, 160f)
        assertOpen(s, strip, "a one-finger drag")
        val stroked = state(s)
        assertNotEquals("a one-finger drag: a stroke", viewed.pixels, stroked.pixels)
        assertEquals("…one step", viewed.undo + 1, stroked.undo)
        closeByToggle(s, strip)
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
            val zero = state(s)
            assertEquals("one object selected", setOf(1L), zero.objects)
            assertFalse(expanded(s, objects))
            // Two steps that keep the selection: recolor it with one main color, then another.
            c.color = RECOLOR
            Smoke.pump(100); SmokeUi.settle()
            Finger.tap(s, "Recolor with the main color")
            val one = state(s)
            assertNotEquals("the recolor shows", zero.pixels, one.pixels)
            assertEquals("the selection stays", zero.objects, one.objects)
            c.color = RECOLOR_AGAIN
            Smoke.pump(100); SmokeUi.settle()
            Finger.tap(s, "Recolor with the main color")
            val two = state(s)
            assertNotEquals("the second recolor shows", one.pixels, two.pixels)
            assertEquals("the selection stays", one.objects, two.objects)
            touches(s, objects, zero, one, two)
            closeByToggle(s, objects)
            Smoke.assertQuiet(c, "the object bar's More row")
        }
    }

    private companion object {
        const val RECOLOR = 0xFF0000FF.toInt()
        const val RECOLOR_AGAIN = 0xFF00AA00.toInt()
    }
}
