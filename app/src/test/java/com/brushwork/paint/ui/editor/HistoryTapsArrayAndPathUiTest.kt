package com.brushwork.paint.ui.editor

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.Selection
import com.brushwork.paint.qa16.QaCurves
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.array.ArrayTool
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.editor.HistoryTapFingers.Companion.SEED
import com.brushwork.paint.ui.editor.HistoryTapFingers.Companion.seed
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeTags
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
 * v1.7 item 10 (design §3.10, §6.2) on the user's 392 dp phone, with real multi-touch events, over
 * the v1.7 controls that change something while a finger is still down:
 * - the open Array sheet (hosted: both fingers inside it): the first finger on the Count slider
 *   (it jumps to the finger), then on "Increase Count" (it steps on touch-down), then on "Apply
 *   array": each time exactly one "Edit array" is undone, what the finger moved is put back and
 *   the sheet stays open; three fingers over the sheet redo one;
 * - I's merge-gate check: the Path tool on a path tapped again (nothing changed yet), a point
 *   selected, one finger drags its width slider (a live in-tool step), a second finger lands:
 *   the width goes back, then exactly ONE document step ("Seed") is undone, and the path is as
 *   it was committed; three fingers redo that step.
 * One test (Compose's frame clock serves the first test of a sandbox only), own sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.editor.historytapsarraypathsandbox"])
class HistoryTapsArrayAndPathUiTest {

    @Test
    fun historyTapsOverTheArraySheetAndThePathWidthSlider() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 60_000)
        val h = ChromeHarness()
        h.section("the open Array sheet: its Count slider, its stepper, Apply") { arraySheet(h) }
        h.section("the Path tool's width slider dragged, then a second finger") { pathWidth(h) }
        dog.interrupt()
        h.finish()
    }

    private fun arraySheet(h: ChromeHarness) {
        val s = h.editor(Smoke.document(400, 300, layers = 2, whiteBottom = true))
        val c = s.c
        val f = HistoryTapFingers(s)
        val ui = Qa16Ui(s)
        val src = c.doc.layers[1]
        Canvas(src.bitmap).drawRect(60f, 60f, 100f, 100f, Paint().apply { color = 0xFFDD2211.toInt() })
        src.markChanged()
        c.setSelection(Selection.fromPath(Path().apply { addRect(50f, 50f, 110f, 110f, Path.Direction.CW) }, c.doc.width, c.doc.height, antiAlias = false), recordUndo = false)
        settle(4)
        ui.reach(ArrayLabels.FROM_SELECTION)
        click(ArrayLabels.FROM_SELECTION, exact = true)
        c.setSelection(null, recordUndo = false)
        settle(4)
        val layer = c.activeLayer
        val tool = c.tools.getValue(ToolId.ARRAY) as ArrayTool
        assertNotNull(layer.array)
        assertEquals(ToolId.ARRAY, c.activeToolId)
        fun count() = layer.array!!.spec.count
        // (The Count slider runs 0..1 over 1..200 copies.)
        fun fraction(n: Int) = (n - 1) / 199f
        // Three array edits: Count 3 → 4 → 5 → 6.
        ui.reach("Increase Count")
        repeat(3) { click("Increase Count", exact = true) }
        assertEquals(6, count())
        assertEquals(ArrayLabels.EDIT, c.undoManager.undoLabel)
        val steps = f.steps
        fun sheet() = requireNotNull(s.tagged(V17Tags.ARRAY_SHEET)) { "the Array sheet is not open; shown: ${SmokeUi.shown().take(60)}" }
        // The second finger on the sheet's title: a touch outside a hosted sheet minimizes it.
        fun title() = with(f) { textIn("Array", sheet()).at(0.5f) }
        with(f) {
            // ------------------------------------------------ the first finger on the Count slider
            reachSlider("Count")
            assertEquals(fraction(6), sliderValue("Count"), 1e-3f)
            twoFingers(sliderBox("Count").at(0.12f), title())
            assertEquals("one array edit undone", 5, count())
            assertEquals(steps - 1, f.steps)
            assertTrue("the feedback: ${SmokeUi.shown().take(60)}", saw("Undo: ${ArrayLabels.EDIT}"))
            assertNull("no preview left", tool.previewSpec)
            assertEquals("the slider shows the count", fraction(5), sliderValue("Count"), 1e-3f)
            sheet()

            // ------------------------------------------------ the first finger on "Increase Count" (it steps on touch-down)
            ui.reach("Increase Count")
            twoFingers(control("Increase Count").at(0.5f), title())
            assertEquals("the stepper's change put back, then one undo", 4, count())
            assertEquals(steps - 2, f.steps)
            assertNull(tool.previewSpec)
            assertEquals(fraction(4), sliderValue("Count"), 1e-3f)
            sheet()

            // ------------------------------------------------ the first finger on "Apply array"
            twoFingers(control(ArrayLabels.APPLY).at(0.5f), title())
            assertNotNull("not applied", layer.array)
            assertEquals("the last array edit undone", 3, count())
            assertEquals(steps - 3, f.steps)
            assertEquals("the array itself is left", ArrayLabels.BUTTON, c.undoManager.undoLabel)
            sheet()

            // ------------------------------------------------ three fingers over the sheet redo one
            val panel = sheet()
            threeFingers(title(), panel.at(0.2f, 0.6f), panel.at(0.8f, 0.6f))
            assertEquals("one redo", 4, count())
            assertEquals(steps - 2, f.steps)
            assertTrue(saw("Redo: ${ArrayLabels.EDIT}"))
            assertNotNull(layer.array)
            sheet()
        }
        Smoke.assertQuiet(c, "array sheet")
    }

    private fun pathWidth(h: ChromeHarness) {
        val s = h.editor(Smoke.document(400, 300, layers = 2, whiteBottom = true))
        val c = s.c
        val f = HistoryTapFingers(s)
        val layer = c.addVectorLayer()!!
        c.selectTool(ToolId.PATH)
        val tool = c.tools.getValue(ToolId.PATH) as CurveTool
        for (p in listOf(Vec2(60f, 150f), Vec2(200f, 150f), Vec2(340f, 150f))) assertTrue(tool.addAnchor(p))
        tool.commit()
        c.settleVectorWork()
        settle()
        val committed = layer.vector!!.objects.single()
        // The step the tap must undo (on the bottom layer; the path stays).
        seed(c)
        val steps = f.steps

        // The path tapped again: nothing changed yet; its middle point selected.
        c.pointerDown(ToolPoint(200f, 150f))
        c.pointerUp(ToolPoint(200f, 150f))
        assertTrue("reopened", Smoke.pumpUntil { tool.isReopened })
        settle()
        tool.select(1)
        settle()
        assertFalse("nothing changed yet", tool.hasUserChanges)
        assertEquals("reopening records nothing", steps, f.steps)
        assertEquals(SEED, c.undoManager.undoLabel)
        val width = tool.widthOf(1)
        assertEquals(1f, width, 1e-4f)

        // One finger drags the width slider (an in-tool step), then the second finger lands.
        QaCurves.scrollStripTo(s, "Point thickness slider")
        f.phoneTouchSlop()
        with(f) {
            val track = sliderBox("Point thickness slider")
            dragThenSecondFinger(track.at(0.75f), region(ChromeTags.TOP_ROW).at(0.5f, 0.9f)) {
                assertNotEquals("the finger moved the width (an in-tool step)", width, tool.widthOf(1))
                assertTrue(tool.hasUserChanges)
            }
        }
        assertFalse("the width rolled back: the untouched path is let go", tool.hasPendingWork)
        assertEquals("then exactly one document step undone", steps - 1, f.steps)
        assertTrue("the feedback: ${SmokeUi.shown().take(60)}", f.saw("Undo: $SEED"))
        assertEquals("the path as it was committed", committed, layer.vector!!.objects.single())

        // Three fingers over the options strip redo that step.
        with(f) {
            val strip = region(ChromeTags.OPTIONS_STRIP)
            threeFingers(strip.at(0.2f), strip.at(0.5f), strip.at(0.8f))
        }
        assertEquals("one redo", steps, f.steps)
        assertTrue(f.saw("Redo: $SEED"))
        assertEquals(committed, layer.vector!!.objects.single())
        assertEquals(ToolId.PATH, c.activeToolId)
        Smoke.assertQuiet(c, "path width")
    }
}
