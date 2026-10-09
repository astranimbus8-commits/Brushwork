package com.brushwork.paint.ui.editor

import android.graphics.Matrix
import com.brushwork.paint.AppSettings
import com.brushwork.paint.DeferredStep
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.7 item 10 (design §3.10; area I): the two parts of [EditorHistoryTaps]' mark.
 * - The first down takes the light mark, which records no step: a pending live edit (the Adjust
 *   sheet's, a vector render still running) stays pending through a one-finger tap. The claim
 *   takes the full `UiMark`, which records it (it belongs before the mark). A history tap puts
 *   back what the first finger changed before the claim: a setting, the colour, a brush preset.
 * - The touch-down audit's filter rows A1, A4, A5, A6: a filter panel changes the open filter's
 *   parameters on the first finger's DOWN, and a `UiMark` does not hold an open filter. The light
 *   mark keeps the parameters the filter had: a three-finger redo leaves the filter open as it was
 *   before the gesture; a two-finger undo then cancels it, as the hotbar's Undo does.
 */
@RunWith(RobolectricTestRunner::class)
class HistoryTapMarkRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private fun controller(): EditorController {
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", 64, 48)
        repeat(2) { i -> doc.layers += Layer(doc.newLayerId(), "Layer ${i + 1}", BitmapUtils.createLayerBitmap(64, 48)) }
        doc.activeLayerIndex = 1
        return EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
    }

    @Test
    fun theFirstDownRecordsNoStepTheClaimDoes() {
        val c = controller()
        val taps = EditorHistoryTaps(c, 36f)
        var flushes = 0
        // A pending live edit: it records its step when it is flushed.
        val live = object : DeferredStep {
            override fun flush() {
                flushes++
                c.removeDeferredStep(this)
            }
        }
        c.addDeferredStep(live)

        // ------------------------------------------------ one finger: a tap on a control
        taps.openMark()
        taps.releaseMark()
        assertEquals("a first down records no pending step", 0, flushes)

        // ------------------------------------------------ two fingers: undo
        val aligned = c.settings.cloneAligned
        val color = c.color
        val brush = c.brush
        taps.openMark()
        // What the first finger's DOWN changed before the claim.
        c.settings.cloneAligned = !aligned
        c.color = 0xFF336699.toInt()
        c.updatePreset(ToolId.BRUSH, brush.copy(size = brush.size + 7f))
        assertEquals(0, flushes)
        taps.claimMark() // the second finger lands
        assertEquals("the claim records the pending step (it belongs before the mark)", 1, flushes)
        taps.historyTap(redo = false)
        taps.releaseMark()
        assertEquals("the setting put back", aligned, c.settings.cloneAligned)
        assertEquals("the colour put back", color, c.color)
        assertEquals("the brush put back", brush, c.brush)

        // ------------------------------------------------ released: later changes stay
        c.color = 0xFF112233.toInt()
        taps.openMark()
        taps.releaseMark()
        assertEquals(0xFF112233.toInt(), c.color)
    }

    @Test
    fun aHistoryTapPutsBackWhatTheFirstFingerDidToAnOpenFilter() {
        val c = controller()
        val taps = EditorHistoryTaps(c, 36f)
        var feedback: String? = null
        taps.onFeedback = { feedback = it }

        c.startFilter(FilterRegistry.byId("adjust.brightness_contrast")!!)
        val session = requireNotNull(c.filterSession) { "the filter opened" }
        session.update("brightness", 20f) // set before the gesture: it stays

        // ------------------------------------------------ three fingers: redo
        taps.openMark()
        session.update("contrast", 50f) // the first finger's DOWN (a − / + step)
        taps.claimMark() // the second finger lands
        taps.historyTap(redo = true)
        taps.releaseMark()
        assertSame("a redo leaves the filter open", session, c.filterSession)
        assertEquals("what was set before the gesture stays", 20f, session.values.float("brightness"), 0f)
        assertEquals("the first finger's change put back", 0f, session.values.float("contrast"), 0f)
        assertEquals("the hotbar's feedback", "Finish the filter first", feedback)

        // ------------------------------------------------ released: a later change is not undone
        session.update("contrast", 10f)
        assertEquals(10f, session.values.float("contrast"), 0f)

        // ------------------------------------------------ two fingers: undo cancels the filter
        taps.openMark()
        session.update("contrast", 70f)
        taps.claimMark()
        taps.historyTap(redo = false)
        taps.releaseMark()
        assertEquals("Filter cancelled", feedback)
        assertNull("the filter closed", c.filterSession)
        assertEquals("closed with the values it had before the gesture", 10f, session.values.float("contrast"), 0f)
    }
}
