package com.brushwork.paint.qa19

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.engine.live.StrokePreview
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.common.StabilityLabels19
import com.brushwork.paint.ui.editor.DrawingPreviewSetting
import com.brushwork.paint.ui.mask.FAST_ADJUST_PREVIEW_LABEL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * v1.9 A6 step 8 (P4, LA2; I27): the cases of the "Drawing preview" tests ([Qa19DrawPreviewUiTest]
 * at 392 dp, [Qa19DrawPreviewNarrowUiTest] at 360 dp), one section each:
 * - As shipped ([StrokePreview.SHIPPED]; LA2 cut P4 on its proxy numbers): the editor's Settings
 *   (More options › Settings) shows "Drawing preview" only when P4 ships; the Display section keeps
 *   "Fast adjustment preview".
 * - With the setting available (as the lead would ship it), at font scale 1 and at each of
 *   [Qa19Ui.FONT_SCALES]: the label, the chips "Faster" and "Exact pixels" and the note under them,
 *   "Faster" chosen by default; a tap on a chip saves `AppSettings.drawPreview`; no text overflows.
 * - In the editor's Settings: it sits between "Fast adjustment preview" and "Safe compositing",
 *   shows the saved choice when Settings opens again, and the next stroke reads it
 *   ([StrokePreview.faster]).
 */
internal object Qa19DrawPreviewChecks {
    private val app: Context get() = ApplicationProvider.getApplicationContext()

    private val LABEL = StabilityLabels19.DRAWING_PREVIEW
    private val FASTER = StabilityLabels19.FASTER
    private val EXACT = StabilityLabels19.EXACT_PIXELS
    private val NOTE = StabilityLabels19.DRAWING_PREVIEW_NOTE

    fun run(h: Qa19Harness, widthDp: Float) {
        val wasAvailable = StrokePreview.available
        try {
            h.section("as shipped, $widthDp dp") { asShipped(h, widthDp) }
            for (scale in listOf(1f) + Qa19Ui.FONT_SCALES) h.section("the setting at font scale $scale, $widthDp dp") {
                StrokePreview.available = true
                try {
                    setting(h, scale, "$widthDp dp at font scale $scale")
                } finally {
                    StrokePreview.available = wasAvailable
                }
            }
            h.section("in the editor's Settings, $widthDp dp") {
                StrokePreview.available = true
                try {
                    inSettings(h, widthDp)
                } finally {
                    StrokePreview.available = wasAvailable
                }
            }
        } finally {
            StrokePreview.available = wasAvailable
        }
    }

    private fun asShipped(h: Qa19Harness, widthDp: Float) {
        assertEquals("the setting is available as LA2 decided", StrokePreview.SHIPPED, StrokePreview.available)
        AppSettings(app).prefs.edit().clear().commit()
        val s = h.editor()
        assertEquals("the phone is $widthDp dp wide", widthDp, s.widthDp, 1f)
        openSettings()
        assertTrue("the Display section: ${Qa19Ui.shown()}", Qa19Ui.has(FAST_ADJUST_PREVIEW_LABEL, exact = true))
        for (t in listOf(LABEL, FASTER, EXACT)) {
            assertEquals("\"$t\" shown only when P4 ships", StrokePreview.SHIPPED, Qa19Ui.has(t, exact = true))
        }
        closeSettings()
    }

    private fun setting(h: Qa19Harness, scale: Float, where: String) {
        AppSettings(app).prefs.edit().clear().commit()
        h.show(fontScale = scale) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { DrawingPreviewSetting() }
        }
        for (t in listOf(LABEL, FASTER, EXACT, NOTE)) assertTrue("\"$t\" at $where: ${Qa19Ui.shown()}", Qa19Ui.has(t, exact = true))
        val label = Qa19Ui.find(LABEL, exact = true)!!.bounds
        val chip = Qa19Ui.find(FASTER, exact = true)!!.bounds
        val note = Qa19Ui.find(NOTE, exact = true)!!.bounds
        assertTrue("the label, then the chips, then the note: $label $chip $note", label.bottom <= chip.top + 1f && chip.bottom <= note.top + 1f)
        assertTrue("\"Faster\" by default", selected(FASTER))
        assertFalse(selected(EXACT))
        Qa19Ui.assertNoTextOverflow("the setting at $where")

        Qa19Ui.click(EXACT, exact = true)
        assertEquals("saved", AppSettings.DRAW_PREVIEW_EXACT, AppSettings(app).drawPreview)
        assertTrue(selected(EXACT))
        assertFalse(selected(FASTER))
        Qa19Ui.assertNoTextOverflow("\"Exact pixels\" chosen at $where")

        Qa19Ui.click(FASTER, exact = true)
        assertEquals("saved", AppSettings.DRAW_PREVIEW_FASTER, AppSettings(app).drawPreview)
        assertTrue(selected(FASTER))
    }

    private fun inSettings(h: Qa19Harness, widthDp: Float) {
        AppSettings(app).prefs.edit().clear().commit()
        val s = h.editor { c -> c.selectTool(ToolId.BRUSH) }
        val c = s.c
        assertEquals("the phone is $widthDp dp wide", widthDp, s.widthDp, 1f)
        assertTrue("the next stroke previews Faster", StrokePreview.faster(c.settings))
        openSettings()
        val fast = Qa19Ui.find(FAST_ADJUST_PREVIEW_LABEL, exact = true)!!.bounds
        val drawing = Qa19Ui.find(LABEL, exact = true)!!.bounds
        val safe = Qa19Ui.find("Safe compositing", exact = true)!!.bounds
        assertTrue("under \"Fast adjustment preview\", above \"Safe compositing\": $fast $drawing $safe", fast.bottom <= drawing.top && drawing.bottom <= safe.top)
        assertTrue("\"Faster\" by default", selected(FASTER))
        Qa19Ui.click(EXACT, exact = true)
        assertEquals("saved", AppSettings.DRAW_PREVIEW_EXACT, c.settings.drawPreview)
        assertFalse("the next stroke previews exactly", StrokePreview.faster(c.settings))
        Qa19Ui.assertNoTextOverflow("in Settings, $widthDp dp", except = { it !in listOf(LABEL, FASTER, EXACT, NOTE) })
        closeSettings()

        openSettings()
        assertTrue("Settings shows the saved choice", selected(EXACT))
        assertFalse(selected(FASTER))
        Qa19Ui.click(FASTER, exact = true)
        assertTrue("the next stroke previews Faster", StrokePreview.faster(c.settings))
        closeSettings()
    }

    private fun openSettings() {
        Qa19Ui.click("More options")
        Qa19Ui.click("Settings", exact = true)
        assertTrue("the Settings dialog", Qa19Ui.has("Editor settings", exact = true))
    }

    private fun closeSettings() {
        Qa19Ui.click("Close", exact = true)
        assertFalse("Settings closed", Qa19Ui.has("Editor settings", exact = true))
    }

    /** Whether the chip [label] is the chosen one (its node or the nearest one with a state). */
    private fun selected(label: String): Boolean {
        var n: SemanticsNode? = Qa19Ui.find(label, exact = true)?.node ?: throw AssertionError("no \"$label\": ${Qa19Ui.shown()}")
        while (n != null) {
            n.config.getOrNull(SemanticsProperties.Selected)?.let { return it }
            n.config.getOrNull(SemanticsProperties.ToggleableState)?.let { return it == ToggleableState.On }
            n = n.parent
        }
        throw AssertionError("\"$label\" has no selected state")
    }
}
