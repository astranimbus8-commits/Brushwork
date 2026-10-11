package com.brushwork.paint.tools.select.colorrange

import android.graphics.Canvas
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 (C, review; design §3.5): with nothing that counts ("Sampled colors", no sample, not
 * inverted) "Select colors" has nothing to select. It used to replace (or intersect) the
 * selection with an empty one, as a step. Now [ColorSelectTool.canSelect] is false (the strip's
 * button is off), and a confirm then (the pending bar's ✓ after "Clear samples", a layer
 * operation landing the steps) only ends the samples' steps: the selection and the history stay.
 */
@RunWith(RobolectricTestRunner::class)
class ColorSelectNothingToSelectTest {

    @Test
    fun noSampleSelectsNothingAndKeepsTheSelection() {
        ShadowLog.stream = null
        val doc = Smoke.document(320, 240)
        Canvas(doc.layers[0].bitmap).drawRect(0f, 0f, 160f, 240f, Paint().apply { color = 0xFFE01010.toInt() })
        doc.activeLayerIndex = 0
        val c = Smoke.controller(ApplicationProvider.getApplicationContext(), doc)
        c.selectTool(ToolId.COLOR_SELECT)
        val t = c.currentTool as ColorSelectTool
        assertTrue(Smoke.pumpUntil { t.proxyReady && t.idle })
        assertEquals(ColorPreset.SAMPLED, t.settings.preset)
        assertFalse("no sample: nothing to select", t.canSelect)

        c.selectAll()
        val all = c.selection
        assertNotNull(all)
        val steps = c.undoManager.undoCount
        for (m in listOf(SelectionMode.REPLACE, SelectionMode.INTERSECT)) {
            t.mode = m
            t.selectColors()
            assertTrue(Smoke.pumpUntil { t.idle })
            assertSame("$m: the selection stays", all, c.selection)
            assertEquals("$m: no step", steps, c.undoManager.undoCount)
        }

        // A sample, then "Clear samples": pending steps, nothing that counts; ✓ ends the steps only.
        c.pointerDown(ToolPoint(40f, 100f)); c.pointerUp(ToolPoint(40f, 100f))
        assertTrue(t.canSelect)
        t.clearSamples()
        assertFalse(t.canSelect)
        assertTrue(t.hasPendingWork)
        t.commit()
        assertTrue(Smoke.pumpUntil { t.idle })
        assertFalse("the steps ended", t.hasPendingWork || t.canUndoStep || t.canRedoStep)
        assertSame(all, c.selection)
        assertEquals(steps, c.undoManager.undoCount)

        // Inverted, "nothing" is everything: there is something to select again.
        t.updateSettings(t.settings.copy(invert = true))
        assertTrue(t.canSelect)
        // A preset counts without samples.
        t.updateSettings(t.settings.copy(invert = false, preset = ColorPreset.REDS))
        assertTrue(t.canSelect)
        t.mode = SelectionMode.REPLACE
        t.selectColors()
        assertTrue(Smoke.pumpUntil { t.idle })
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(ToolId.COLOR_SELECT.label, c.undoManager.undoLabel)
        val sel = c.selection!!
        assertEquals(255, sel.alphaAt(40, 100))
        assertEquals(0, sel.alphaAt(280, 100))
        Smoke.assertQuiet(c, "color select with nothing to select")
    }
}
