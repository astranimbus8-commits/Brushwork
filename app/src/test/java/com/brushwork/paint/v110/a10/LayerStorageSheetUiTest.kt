package com.brushwork.paint.v110.a10

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.LayerStorageMode
import com.brushwork.paint.engine.check.CheckStore
import com.brushwork.paint.qa19.Qa19Harness
import com.brushwork.paint.qa19.Qa19Ui
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.storage.engine.CheckReasons
import com.brushwork.paint.storage.engine.EngineStateStore
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.MemoryPreset
import com.brushwork.paint.ui.common.EngineLabels110
import com.brushwork.paint.ui.common.V110Tags
import com.brushwork.paint.ui.editor.EditorPrefs
import com.brushwork.paint.ui.editor.EditorSettingsDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.10 A10 (design 2.11, step 2; I27): Settings > Layer storage, in the real editor settings
 * dialog at 392 and 360 dp and at each font scale. The row (tagged [V110Tags.LAYER_STORAGE]) shows
 * [EngineLabels110.LAYER_STORAGE], the stored check's effective result and
 * [EngineLabels110.FOR_THIS_PHONE]; a tap opens the sheet over the dialog with the three radios,
 * [EngineLabels110.APPLIES_NEXT], [EngineLabels110.CHECK_AGAIN] and [EngineLabels110.COPY_DETAILS],
 * every text whole. A radio writes `AppSettings.layerStorageMode` at once (the row and the sheet
 * read it as chosen); Copy details puts the check's numbers on the clipboard; Check again writes
 * `engineCheckPending` to `engine-state.json`, the row reads [EngineLabels110.CHECK_PENDING] and the
 * button turns off.
 *
 * One UI test (the frame clock serves the first test of a sandbox); its cases are sections.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392, instrumentedPackages = ["com.brushwork.paint.v110.a10.layerstoragesheetsandbox"])
class LayerStorageSheetUiTest {
    @get:Rule
    val engine = EngineRule(memory = FakeMemoryProbe(MemoryPreset.T606))

    private val app get() = RuntimeEnvironment.getApplication() as BrushworkApp
    private val clipboard get() = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    @Test
    fun theRowAndTheSheet() = Qa19Ui.uiTest { h ->
        try {
            for (width in listOf(Qa19Ui.Q392, Qa19Ui.Q360)) for (scale in listOf(1f) + Qa19Ui.FONT_SCALES) {
                val where = "at ${width.substringBefore('-')} and font scale $scale"
                h.section(where) {
                    RuntimeEnvironment.setQualifiers(width)
                    rowAndSheet(h, scale, if (width == Qa19Ui.Q360) 360 else 392, where)
                }
            }
        } finally {
            RuntimeEnvironment.setQualifiers(Qa19Ui.Q392)
        }
    }

    /** The radio row labelled [label] is selected (the row merges its text and its state). */
    private fun selected(label: String): Boolean {
        var n: SemanticsNode? = Qa19Ui.find(label, exact = true)?.node ?: throw AssertionError("no \"$label\"; shown ${Qa19Ui.shown()}")
        while (n != null) {
            n.config.getOrNull(SemanticsProperties.Selected)?.let { return it }
            n = n.parent
        }
        throw AssertionError("\"$label\" is not a radio row")
    }

    private fun waitFor(where: String, label: String) =
        assertTrue("$where: \"$label\" shows; shown ${Qa19Ui.shown()}", A10Kit.pumpUntil(10_000) { Qa19Ui.settle(1); Qa19Ui.has(label, exact = true) })

    private fun rowAndSheet(h: Qa19Harness, scale: Float, widthDp: Int, where: String) {
        CheckStore.file(app).delete()
        EngineStateStore.file(app).delete()
        app.settings.layerStorageMode = LayerStorageMode.AUTOMATIC
        clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        CheckStore.write(app, A10Kit.result(EngineLevel.CLASSIC, CheckReasons.LOW_MEMORY))
        val classic = String.format(EngineLabels110.SUB_CLASSIC, CheckReasons.LOW_MEMORY)

        val a = h.show(scale) { EditorSettingsDialog(EditorPrefs(app.settings), onDismiss = {}) }
        assertEquals(where, widthDp, a.resources.configuration.screenWidthDp)
        waitFor(where, classic)
        assertTrue("$where: the row's title", Qa19Ui.has(EngineLabels110.LAYER_STORAGE, exact = true))
        assertTrue("$where: the second line", Qa19Ui.has(EngineLabels110.FOR_THIS_PHONE, exact = true))
        Qa19Ui.assertNoTextOverflow("$where, the settings with the row")

        // The sheet, over the dialog.
        val row = Qa19Ui.tagged(V110Tags.LAYER_STORAGE) ?: throw AssertionError("$where: no row tagged ${V110Tags.LAYER_STORAGE}")
        requireNotNull(row.config.getOrNull(SemanticsActions.OnClick)?.action) { "$where: the row taps" }.invoke()
        Qa19Ui.settle()
        waitFor(where, EngineLabels110.MODE_AUTOMATIC)
        for (label in listOf(EngineLabels110.MODE_FULL, EngineLabels110.MODE_CLASSIC, EngineLabels110.APPLIES_NEXT, EngineLabels110.CHECK_AGAIN, EngineLabels110.COPY_DETAILS)) {
            assertTrue("$where: \"$label\" in the sheet; shown ${Qa19Ui.shown()}", Qa19Ui.has(label, exact = true))
        }
        assertTrue("$where: Automatic is selected", selected(EngineLabels110.MODE_AUTOMATIC))
        assertFalse(where, selected(EngineLabels110.MODE_FULL))
        Qa19Ui.assertNoTextOverflow("$where, the sheet")

        // A radio writes the setting at once.
        Qa19Ui.click(EngineLabels110.MODE_FULL, exact = true)
        assertEquals("$where: Full chosen", LayerStorageMode.FULL, app.settings.layerStorageMode)
        waitFor(where, EngineLabels110.SUB_FULL_CHOSEN)
        assertTrue("$where: Full is selected", selected(EngineLabels110.MODE_FULL))
        Qa19Ui.click(EngineLabels110.MODE_CLASSIC, exact = true)
        assertEquals("$where: Classic chosen", LayerStorageMode.CLASSIC, app.settings.layerStorageMode)
        waitFor(where, EngineLabels110.SUB_CLASSIC_CHOSEN)
        Qa19Ui.click(EngineLabels110.MODE_AUTOMATIC, exact = true)
        assertEquals("$where: Automatic again", LayerStorageMode.AUTOMATIC, app.settings.layerStorageMode)
        waitFor(where, classic)

        // Copy details: the check's numbers as text.
        Qa19Ui.click(EngineLabels110.COPY_DETAILS, exact = true)
        val copied = { clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty() }
        assertTrue("$where: copied", A10Kit.pumpUntil(10_000) { Qa19Ui.settle(1); copied().isNotEmpty() })
        val text = copied()
        for (part in listOf("Setting: automatic", "Check: CLASSIC (low memory)", "Native probe: 256 MiB (REACHED)", "Storage: write 120 MB/s, read 250 MB/s")) {
            assertTrue("$where: the details hold \"$part\":\n$text", text.contains(part))
        }

        // Check again.
        assertTrue("$where: Check again is on", SmokeUi.isEnabled(EngineLabels110.CHECK_AGAIN))
        Qa19Ui.click(EngineLabels110.CHECK_AGAIN, exact = true)
        assertTrue("$where: engineCheckPending written", A10Kit.pumpUntil(10_000) { EngineStateStore.read(app).engineCheckPending })
        waitFor(where, EngineLabels110.CHECK_PENDING)
        assertFalse("$where: Check again turns off while a check is pending", SmokeUi.isEnabled(EngineLabels110.CHECK_AGAIN))
        assertEquals("$where: the choice is untouched", LayerStorageMode.AUTOMATIC, app.settings.layerStorageMode)
        Qa19Ui.assertNoTextOverflow("$where, after Check again")
    }
}
