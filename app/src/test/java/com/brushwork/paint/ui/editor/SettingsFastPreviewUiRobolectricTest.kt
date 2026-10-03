package com.brushwork.paint.ui.editor

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.engine.AdjustmentStage
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.mask.FAST_ADJUST_PREVIEW_DESCRIPTION
import com.brushwork.paint.ui.mask.FAST_ADJUST_PREVIEW_LABEL
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.6 integration (A ↔ E): the editor's Settings has ONE "Fast adjustment preview" switch, area
 * A's [com.brushwork.paint.ui.mask.FastAdjustPreviewToggle] (the Masks tool's switch: same label,
 * same description, same saved setting), right above "Safe compositing" under Display. It shows
 * the setting saved elsewhere (the Masks tool's sheet) when the dialog opens, and each tap saves
 * `AppSettings.fastAdjustPreview` (the live adjustment takes it over at the next drag) without a
 * redraw: between drags the canvas shows the exact image either way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.editor.settingsfastpreviewsandbox"])
class SettingsFastPreviewUiRobolectricTest {

    @After
    fun reset() {
        AdjustmentStage.safeCompositing = false
    }

    /** The placed switch rows (toggleable nodes) of the dialog, top to bottom. */
    private fun switches(): List<RobolectricUi.Element> = RobolectricUi.elements()
        .filter { it.node.layoutInfo.isPlaced && it.node.config.getOrNull(SemanticsProperties.ToggleableState) != null }
        .sortedBy { it.bounds.top }

    private fun SemanticsNode.texts(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + children.flatMap { it.texts() }

    private fun state(): ToggleableState? =
        switches().single { FAST_ADJUST_PREVIEW_LABEL in it.node.texts() }.node.config.getOrNull(SemanticsProperties.ToggleableState)

    @Test
    fun settingsHasAreaASwitchOnceAboveSafeCompositing() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val settings = AppSettings(ctx).also { it.prefs.edit().clear().commit() }
        assertTrue("default on (v1.6 §3.1a)", settings.fastAdjustPreview)
        // Turned off earlier in the Masks tool's sheet: Settings opens showing "off".
        settings.fastAdjustPreview = false
        val prefs = EditorPrefs(settings)
        var redraws = 0
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { BrushworkTheme { EditorSettingsDialog(prefs, onDismiss = {}, onCanvasChanged = { redraws++ }) } }
        SmokeUi.settle()

        val rows = switches().map { it.node.texts() }
        val fast = rows.indexOfFirst { FAST_ADJUST_PREVIEW_LABEL in it }
        assertEquals("one \"$FAST_ADJUST_PREVIEW_LABEL\" switch: $rows", 1, rows.count { FAST_ADJUST_PREVIEW_LABEL in it })
        assertEquals("area A's description under it", listOf(FAST_ADJUST_PREVIEW_LABEL, FAST_ADJUST_PREVIEW_DESCRIPTION), rows[fast])
        assertEquals("right above \"Safe compositing\": $rows", "Safe compositing", rows.getOrNull(fast + 1)?.firstOrNull())
        assertEquals("the label is on one element", 1, RobolectricUi.elements().count { e ->
            e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == FAST_ADJUST_PREVIEW_LABEL } == true
        })
        assertEquals("the saved setting", ToggleableState.Off, state())

        SmokeUi.click(FAST_ADJUST_PREVIEW_LABEL, exact = true)
        assertTrue("saved on", settings.fastAdjustPreview)
        assertEquals(ToggleableState.On, state())
        SmokeUi.click(FAST_ADJUST_PREVIEW_LABEL, exact = true)
        assertFalse("saved off", settings.fastAdjustPreview)
        assertEquals(ToggleableState.Off, state())
        assertEquals("no redraw (only the next drag uses it)", 0, redraws)
        assertFalse("Safe compositing untouched", settings.safeCompositing)
    }
}
