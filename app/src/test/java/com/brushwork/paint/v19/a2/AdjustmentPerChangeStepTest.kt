package com.brushwork.paint.v19.a2

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.mask.AdjustmentEdit
import com.brushwork.paint.tools.mask.MaskTool
import com.brushwork.paint.ui.common.FilterLabels19
import com.brushwork.paint.ui.common.PointLabels19
import com.brushwork.paint.ui.filters.ParamHost
import com.brushwork.paint.ui.mask.adjustmentParamHost
import com.brushwork.paint.ui.mask.resetAmount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.9 (R8, A2 accept): in an adjustment layer's Adjust sheet every finished change is one
 * main-history step "Change <label>"; a run of − / + presses is one step, ended by another
 * control; a control's reset is one step "Reset <label>" and its icon shows only off the default;
 * Amount (the layer's opacity) takes part like the effect's own controls. Driven through the
 * sheet's [adjustmentParamHost], with no UI.
 */
@RunWith(RobolectricTestRunner::class)
class AdjustmentPerChangeStepTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var c: EditorController
    private val tone = FilterRegistry.byId("adjust.tone")!!

    @After
    fun tearDown() {
        if (::c.isInitialized) c.dispose()
        scope.cancel()
    }

    private class Sheet(val layer: Layer, val edit: AdjustmentEdit, val host: ParamHost)

    private fun open(): Sheet {
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("n", "n", 48, 32)
        doc.layers += Layer(doc.newLayerId(), "Photo", BitmapUtils.createLayerBitmap(48, 32)).also {
            Canvas(it.bitmap).drawRect(4f, 4f, 40f, 28f, Paint().apply { color = 0xFF3366AA.toInt() })
        }
        doc.activeLayerIndex = 0
        c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        c.selectTool(ToolId.MASK)
        val adj = c.addAdjustmentLayer(AdjustmentEffects.defaultSpec(tone), null)!!
        val edit = (c.tools.getValue(ToolId.MASK) as MaskTool).adjustmentEdit(adj)
        return Sheet(adj, edit, adjustmentParamHost(c, adj, tone, edit))
    }

    private fun Sheet.value(key: String) = host.values.float(key)

    private fun change(label: String) = String.format(FilterLabels19.CHANGE_STEP, label)

    private fun reset(label: String) = String.format(FilterLabels19.RESET_ONE, label)

    @Test
    fun aFinishedChangeIsOneStepNamedAfterItsControl() {
        val s = open()
        val before = c.undoManager.undoCount
        // A slider dragged through several values: live, no step under the finger.
        for (v in listOf(0.2f, 0.5f, 0.8f)) s.host.update("exposure", v)
        assertEquals("live: no step yet", before, c.undoManager.undoCount)
        assertTrue(s.edit.isPending)
        s.host.onChangeFinished("exposure")
        assertEquals(before + 1, c.undoManager.undoCount)
        assertEquals(change("Exposure"), c.undoManager.undoLabel)
        assertFalse(s.edit.isPending)

        // The same control again, and another one: a step each.
        s.host.update("exposure", 1.2f)
        s.host.onChangeFinished("exposure")
        s.host.update("contrast", 30f)
        s.host.onChangeFinished("contrast")
        assertEquals(before + 3, c.undoManager.undoCount)
        assertEquals(change("Contrast"), c.undoManager.undoLabel)

        c.undo()
        assertEquals(0f, s.value("contrast"))
        assertEquals(1.2f, s.value("exposure"), 1e-6f)
        c.undo()
        assertEquals(0.8f, s.value("exposure"), 1e-6f)
        c.redo()
        assertEquals(1.2f, s.value("exposure"), 1e-6f)

        // A release that changed nothing is no step.
        s.host.onChangeFinished("exposure")
        assertEquals(before + 2, c.undoManager.undoCount)
    }

    @Test
    fun aRunOfMinusPlusPressesIsOneStepEndedByAnotherControl() {
        val s = open()
        val before = c.undoManager.undoCount
        repeat(3) {
            s.host.update("contrast", s.value("contrast") + 1f)
            s.host.onNudgeFinished("contrast")
        }
        assertEquals("the run is still open", before, c.undoManager.undoCount)
        // Another control's change records the run first, as its own step.
        s.host.update("shadows", 12f)
        assertEquals(before + 1, c.undoManager.undoCount)
        assertEquals(change("Contrast"), c.undoManager.undoLabel)
        s.host.onChangeFinished("shadows")
        assertEquals(change("Shadows"), c.undoManager.undoLabel)
        c.undo()
        c.undo()
        assertEquals("the whole run goes back at once", 0f, s.value("contrast"))

        // A run the sheet's close ends is one step under its control's name too.
        s.host.update("whites", 5f)
        s.host.onNudgeFinished("whites")
        s.edit.flush()
        assertEquals(change("Whites"), c.undoManager.undoLabel)
    }

    @Test
    fun aResetIsOneStepAndItsIconShowsOnlyOffTheDefault() {
        val s = open()
        val exposure = tone.params.first { it.key == "exposure" }
        assertTrue("at the default: no reset icon", s.host.isDefault(exposure))
        s.host.update("exposure", 0.7f)
        s.host.onChangeFinished("exposure")
        assertFalse("off the default: the reset icon shows", s.host.isDefault(exposure))
        val before = c.undoManager.undoCount
        s.host.resetParam("exposure")
        assertEquals(0f, s.value("exposure"))
        assertTrue(s.host.isDefault(exposure))
        assertEquals(before + 1, c.undoManager.undoCount)
        assertEquals(reset("Exposure"), c.undoManager.undoLabel)
        c.undo()
        assertEquals(0.7f, s.value("exposure"), 1e-6f)

        // A reset first records another control's change still pending.
        s.host.update("blacks", -4f)
        s.host.onNudgeFinished("blacks")
        s.host.resetParam("exposure")
        c.undo()
        assertEquals(-4f, s.value("blacks"))
        assertEquals(change("Blacks"), c.undoManager.undoLabel)

        // A run of − / + presses of a control, then that control's reset: the run is its own
        // step first, so Undo of the reset goes back to where the run ended (as in a panel).
        val n = c.undoManager.undoCount
        repeat(2) {
            s.host.update("contrast", s.value("contrast") + 1f)
            s.host.onNudgeFinished("contrast")
        }
        s.host.resetParam("contrast")
        assertEquals(0f, s.value("contrast"))
        assertEquals("the run and the reset, a step each", n + 2, c.undoManager.undoCount)
        assertEquals(reset("Contrast"), c.undoManager.undoLabel)
        c.undo()
        assertEquals(2f, s.value("contrast"))
        assertEquals(change("Contrast"), c.undoManager.undoLabel)
    }

    @Test
    fun amountTakesPartLikeTheEffectsControls() {
        val s = open()
        val before = c.undoManager.undoCount
        // The Amount slider: changing, its samples, then its release.
        for (o in listOf(0.8f, 0.6f, 0.5f)) {
            s.edit.changing(PointLabels19.AMOUNT)
            s.edit.preview(s.layer.adjustment, o, s.layer.name)
        }
        s.edit.finish(PointLabels19.AMOUNT)
        assertEquals(before + 1, c.undoManager.undoCount)
        assertEquals(change(PointLabels19.AMOUNT), c.undoManager.undoLabel)
        resetAmount(s.layer, s.edit)
        assertEquals(1f, s.layer.opacity)
        assertEquals(reset(PointLabels19.AMOUNT), c.undoManager.undoLabel)
        c.undo()
        assertEquals(0.5f, s.layer.opacity)
        // A reset at 100 % is no step.
        c.undo()
        resetAmount(s.layer, s.edit)
        assertEquals(before, c.undoManager.undoCount)
        // An edit with no control named (v1.8's callers) keeps v1.8's step name.
        s.edit.preview(s.layer.adjustment, 0.3f, s.layer.name)
        s.edit.flush()
        assertEquals(AdjustmentEdit.LABEL, c.undoManager.undoLabel)
    }
}
