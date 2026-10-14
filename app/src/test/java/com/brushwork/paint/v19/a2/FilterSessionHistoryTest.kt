package com.brushwork.paint.v19.a2

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterSession
import com.brushwork.paint.filters.FilterValues
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.ui.common.FilterLabels19
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.ui.filters.paramHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * v1.9 (R8, A2 accept): a filter session's own steps ([FilterSession.history]). Each finished
 * change is one step named after its control; Undo and Redo walk them ("Undo: <label>"); a new
 * change drops the redo steps; at most 100 steps; "Reset to defaults" and a control's reset are
 * one step each; the opening state shows the start toast and the next Undo cancels the panel as
 * in v1.8; ✓ drops the steps and records one document step. No UI: the session's own calls.
 */
@RunWith(RobolectricTestRunner::class)
class FilterSessionHistoryTest {

    /** Two sliders and a text; the output is a copy of the source. */
    private class TwoSliders : Filter("v19a2_two", "Two sliders", FilterCategory.ADJUST) {
        override val params = listOf(
            FilterParam.Slider("amount", "Amount", 0f, 100f, 50f, step = 1f),
            FilterParam.Slider("radius", "Radius", 0f, 20f, 4f, step = 1f),
            FilterParam.Text("words", "Words", "Hi"),
        )
        override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext) = src.copy()
    }

    private lateinit var context: Context
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun open(): Pair<EditorController, FilterSession> {
        val doc = Document("t", "t", 24, 16)
        val layer = Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(24, 16))
        layer.bitmap.eraseColor(0xFF2040C0.toInt())
        doc.layers += layer
        val c = EditorController(context, doc, scope, AppSettings(context))
        c.startFilter(TwoSliders())
        val s = c.filterSession
        assertNotNull(s)
        s!!.debounceMs = 0
        shadowOf(Looper.getMainLooper()).idle()
        return c to s
    }

    /** One finished change of [key]: what a slider released does. */
    private fun FilterSession.change(key: String, value: Float) {
        update(key, value)
        commitStep(key)
    }

    @Test
    fun eachFinishedChangeIsOneStepNamedAfterItsControl() {
        val (c, s) = open()
        assertSame("the open session owns Undo and Redo", s, c.inToolHistory)
        assertFalse(s.canUndo)
        assertEquals("Filter cancelled", HistoryLabels.undo(c))

        // A slider dragged through several values and released: one step.
        for (v in listOf(51f, 55f, 60f)) s.update("amount", v)
        s.commitStep("amount")
        s.change("radius", 9f)
        assertEquals(2, s.history.depth)
        assertEquals("Undo: Radius", HistoryLabels.undo(c))

        c.undo()
        assertEquals(4f, s.values.float("radius"))
        assertEquals(60f, s.values.float("amount"))
        assertEquals("Undo: Amount", HistoryLabels.undo(c))
        assertEquals("Redo: Radius", HistoryLabels.redo(c))
        c.redo()
        assertEquals(9f, s.values.float("radius"))
        assertFalse(s.canRedo)
        assertFalse("the session stays open", s.isClosed)

        // A release that changed nothing is no step.
        s.commitStep("radius")
        assertEquals(2, s.history.depth)
    }

    @Test
    fun aNewChangeDropsTheRedoSteps() {
        val (c, s) = open()
        s.change("amount", 10f)
        s.change("amount", 20f)
        c.undo()
        assertTrue(s.canRedo)
        s.change("radius", 2f)
        assertFalse("a new change drops the redo steps", s.canRedo)
        assertEquals("Finish the filter first", HistoryLabels.redo(c))
        c.undo()
        c.undo()
        assertEquals(50f, s.values.float("amount"))
        assertEquals(4f, s.values.float("radius"))
        assertEquals(0, s.history.depth)
    }

    @Test
    fun aChangeNotFinishedYetIsTakenBackFirst() {
        val (c, s) = open()
        s.change("amount", 70f)
        // Text typed without leaving the field: live, no step yet; Undo names and takes it back.
        s.update("words", "Hello")
        assertEquals(1, s.history.depth)
        assertTrue(s.canUndo)
        assertFalse("a pending change is new: nothing to redo", s.canRedo)
        assertEquals("Undo: Words", HistoryLabels.undo(c))
        c.undo()
        assertEquals("Hi", s.values.text("words"))
        assertEquals(70f, s.values.float("amount"))
        assertEquals("Redo: Words", HistoryLabels.redo(c))

        // Another control's change makes the typed text its own step first.
        s.update("words", "Hey")
        s.change("radius", 12f)
        assertEquals(3, s.history.depth)
        c.undo()
        assertEquals("Hey", s.values.text("words"))
        assertEquals(4f, s.values.float("radius"))
    }

    @Test
    fun aRunOfMinusPlusTapsIsOneStep() {
        val (c, s) = open()
        val host = s.paramHost()
        repeat(3) {
            host.update("radius", s.values.float("radius") + 1f)
            host.onNudgeFinished("radius")
        }
        assertEquals("the run is still open", 0, s.history.depth)
        assertEquals("Undo: Radius", HistoryLabels.undo(c))
        // The next control ends the run.
        host.update("amount", 40f)
        host.onChangeFinished("amount")
        assertEquals(2, s.history.depth)
        c.undo()
        c.undo()
        assertEquals("the whole run goes back at once", 4f, s.values.float("radius"))
    }

    @Test
    fun aRunThenItsOwnResetAreAStepEach() {
        val (c, s) = open()
        val host = s.paramHost()
        repeat(2) {
            host.update("radius", s.values.float("radius") + 1f)
            host.onNudgeFinished("radius")
        }
        host.resetParam("radius")
        assertEquals(4f, s.values.float("radius"))
        assertEquals("the run and the reset", 2, s.history.depth)
        c.undo()
        assertEquals("Undo of the reset goes back to where the run ended", 6f, s.values.float("radius"))
        assertEquals("Undo: Radius", HistoryLabels.undo(c))
    }

    @Test
    fun aHistoryTapsHoldRecordsNothing() {
        val (c, s) = open()
        s.change("amount", 30f)
        // The first finger moved a slider, the second claimed the tap: the slider's end is no
        // step, and the tap puts the values back before it undoes.
        s.update("radius", 15f)
        s.heldByTap = true
        s.commitStep("radius")
        assertEquals(1, s.history.depth)
        s.update("radius", 4f)
        c.undo()
        assertFalse("the undo ends the hold", s.heldByTap)
        assertEquals(50f, s.values.float("amount"))
        assertEquals(4f, s.values.float("radius"))
        assertFalse(s.isClosed)
    }

    @Test
    fun theResetsAreOneStepEach() {
        val (c, s) = open()
        s.change("amount", 10f)
        s.change("radius", 10f)
        s.resetParam("amount")
        assertEquals(50f, s.values.float("amount"))
        assertEquals("Undo: " + String.format(FilterLabels19.RESET_ONE, "Amount"), HistoryLabels.undo(c))
        s.reset()
        assertEquals(4f, s.values.float("radius"))
        assertEquals("Undo: " + FilterSession.RESET_ALL, HistoryLabels.undo(c))
        assertEquals(4, s.history.depth)
        c.undo()
        assertEquals(10f, s.values.float("radius"))
        assertEquals(50f, s.values.float("amount"))
        c.undo()
        assertEquals(10f, s.values.float("amount"))
        // A reset of a value already at its default is no step.
        s.reset()
        s.reset()
        assertEquals(3, s.history.depth)
    }

    @Test
    fun atMostOneHundredStepsAndTheirLabelsStayInStep() {
        val (_, s) = open()
        for (i in 1..105) s.change(if (i % 2 == 0) "amount" else "radius", (i % 20).toFloat() + if (i % 2 == 0) 30f else 0f)
        assertEquals(100, s.history.depth)
        var undone = 0
        while (s.canUndo) {
            val label = s.undoLabel
            assertNotNull("a label for every step ($undone undone)", label)
            assertTrue(label, label == "Amount" || label == "Radius")
            assertTrue(s.undoStep())
            undone++
        }
        assertEquals(100, undone)
        assertNull(s.undoLabel)
        // The oldest five steps were dropped: the bottom is the sixth state, not the opening one.
        assertEquals(5f, s.values.float("radius"))
    }

    @Test
    fun theOpeningStateShowsTheToastOnceAndTheNextUndoCancels() {
        val (c, s) = open()
        s.change("amount", 80f)
        c.message = null
        c.undo()
        assertEquals(FilterLabels19.SESSION_AT_START, c.message)
        assertFalse(s.isClosed)
        assertEquals("Filter cancelled", HistoryLabels.undo(c))
        c.message = null
        c.undo()
        assertTrue("Undo at the bottom cancels the panel, as in v1.8", s.isClosed)
        assertNull(c.filterSession)
        assertNull(c.inToolHistory)
        assertNull("no second toast", c.message)
        assertFalse(c.canUndo)
    }

    @Test
    fun whileApplyingUndoStopsTheFilterAndTheLabelsSaySo() {
        val (c, s) = open()
        s.change("amount", 20f)
        s.change("radius", 6f)
        c.undo()
        assertTrue(s.canUndo && s.canRedo)
        s.apply()
        // ✓ runs (its coroutine not started yet): the steps wait, and Undo is v1.8's cancel.
        assertTrue(s.isApplying)
        assertFalse(s.canUndo)
        assertFalse(s.canRedo)
        assertNull(s.undoLabel)
        assertNull(s.redoLabel)
        assertEquals("Filter cancelled", HistoryLabels.undo(c))
        assertEquals("Finish the filter first", HistoryLabels.redo(c))
        c.undo()
        assertTrue("Undo while applying cancels the panel, as in v1.8", s.isClosed)
        assertNull(c.filterSession)
        assertNull(c.inToolHistory)
        val looper = shadowOf(Looper.getMainLooper())
        repeat(20) { looper.idleFor(java.time.Duration.ofMillis(10)) }
        assertFalse("nothing applied", c.canUndo)
    }

    @Test
    fun applyRecordsOneDocumentStepAndDropsThePanelSteps() {
        val (c, s) = open()
        s.change("amount", 20f)
        s.change("radius", 6f)
        s.apply()
        val looper = shadowOf(Looper.getMainLooper())
        val end = System.currentTimeMillis() + 15_000
        while (c.filterSession != null && System.currentTimeMillis() < end) {
            looper.idleFor(java.time.Duration.ofMillis(10))
            Thread.sleep(1)
        }
        assertTrue(s.isClosed)
        assertNull("the panel's steps go with it", c.inToolHistory)
        // The copy filter changes nothing: no document step, as in v1.8.
        assertFalse(c.canUndo)
    }
}
