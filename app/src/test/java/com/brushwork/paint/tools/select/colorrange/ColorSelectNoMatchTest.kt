package com.brushwork.paint.tools.select.colorrange

import android.graphics.Canvas
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.common.ColorSelectLabels18
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 integration (v18I): "Select colors" whose preset (or samples) match no pixel of the layer
 * shows [ColorSelectLabels18.NO_MATCH] and changes nothing: no selection step, the selection
 * (none, or the one there) stays, in every mode. A preset that matches still selects, as one step.
 */
@RunWith(RobolectricTestRunner::class)
class ColorSelectNoMatchTest {

    @Test
    fun aPresetThatMatchesNoPixelSaysSoAndAddsNoStep() {
        ShadowLog.stream = null
        val doc = Smoke.document(320, 240)
        // Red on the left half, transparent on the right (a transparent pixel is never selected).
        Canvas(doc.layers[0].bitmap).drawRect(0f, 0f, 160f, 240f, Paint().apply { color = 0xFFE01010.toInt() })
        doc.activeLayerIndex = 0
        val c = Smoke.controller(ApplicationProvider.getApplicationContext(), doc)
        c.selectTool(ToolId.COLOR_SELECT)
        val t = c.currentTool as ColorSelectTool
        assertTrue(Smoke.pumpUntil { t.proxyReady && t.idle })
        val steps = c.undoManager.undoCount

        // Blues on a red and transparent layer: nothing matches, with no selection and with one.
        t.updateSettings(t.settings.copy(preset = ColorPreset.BLUES))
        assertTrue(t.canSelect)
        t.mode = SelectionMode.REPLACE
        c.message = null
        t.selectColors()
        assertTrue(Smoke.pumpUntil { t.idle })
        assertEquals("no match: the toast", ColorSelectLabels18.NO_MATCH, c.message)
        assertNull("no match: still nothing selected", c.selection)
        assertEquals("no match: no step", steps, c.undoManager.undoCount)

        c.selectAll()
        val all = c.selection
        assertNotNull(all)
        val withAll = c.undoManager.undoCount
        for (m in listOf(SelectionMode.REPLACE, SelectionMode.ADD, SelectionMode.SUBTRACT, SelectionMode.INTERSECT)) {
            t.mode = m
            c.message = null
            t.selectColors()
            assertTrue(Smoke.pumpUntil { t.idle })
            assertEquals("$m: the toast", ColorSelectLabels18.NO_MATCH, c.message)
            assertSame("$m: the selection stays", all, c.selection)
            assertEquals("$m: no step", withAll, c.undoManager.undoCount)
        }

        // Reds inverted: the red half is out and the transparent half never counts, so nothing matches.
        t.updateSettings(t.settings.copy(preset = ColorPreset.REDS, invert = true))
        t.mode = SelectionMode.REPLACE
        c.message = null
        t.selectColors()
        assertTrue(Smoke.pumpUntil { t.idle })
        assertEquals("inverted, no match: the toast", ColorSelectLabels18.NO_MATCH, c.message)
        assertSame(all, c.selection)
        assertEquals(withAll, c.undoManager.undoCount)

        // Reds: the red half is selected, one step, no toast.
        t.updateSettings(t.settings.copy(preset = ColorPreset.REDS, invert = false))
        c.message = null
        t.selectColors()
        assertTrue(Smoke.pumpUntil { t.idle })
        assertNull("a match: no toast", c.message)
        assertEquals(withAll + 1, c.undoManager.undoCount)
        assertEquals(ToolId.COLOR_SELECT.label, c.undoManager.undoLabel)
        val sel = c.selection!!
        assertEquals(255, sel.alphaAt(40, 100))
        assertEquals(0, sel.alphaAt(280, 100))
        Smoke.assertQuiet(c, "color select with no match")
    }
}
