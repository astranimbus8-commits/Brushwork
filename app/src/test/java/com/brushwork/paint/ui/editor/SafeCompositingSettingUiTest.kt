package com.brushwork.paint.ui.editor

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.engine.AdjustmentStage
import com.brushwork.paint.smoke.SmokeUi
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
 * v1.5 integration (lead): "Safe compositing" (adjustment layers drawn without their effect on the
 * canvas, I5's kill switch) is also in the editor settings: it shows the live switch, and turning
 * it on or off saves it, switches the compositor and redraws the canvas. v1.9 Gate 2: so does the
 * switch saved by a settings restore with the editor open ([EditorPrefs]' redraw, which
 * `EditorScreen` points at its canvas).
 * One test: Compose's frame clock only serves the first test of a Robolectric sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.editor.safecompositingsandbox"])
class SafeCompositingSettingUiTest {

    @After
    fun reset() {
        AdjustmentStage.safeCompositing = false
    }

    @Test
    fun theEditorSettingsSwitchSafeCompositing() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val settings = AppSettings(ctx).also { it.prefs.edit().clear().commit() }
        // Turned on earlier (e.g. from the Masks tool's sheet): the dialog shows it on.
        settings.safeCompositing = true
        AdjustmentStage.safeCompositing = true
        var redraws = 0
        // (As the editor makes it: a followed switch redraws its canvas.)
        val prefs = EditorPrefs(settings) { redraws++ }
        assertTrue(prefs.safeCompositing)
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { BrushworkTheme { EditorSettingsDialog(prefs, onDismiss = {}, onCanvasChanged = { redraws++ }) } }
        SmokeUi.settle()
        assertTrue("shown: ${SmokeUi.shown()}", SmokeUi.has("Safe compositing", exact = true))
        SmokeUi.click("Safe compositing", exact = true)
        assertFalse(settings.safeCompositing)
        assertFalse(AdjustmentStage.safeCompositing)
        assertFalse(prefs.safeCompositing)
        assertEquals("the canvas is redrawn", 1, redraws)
        SmokeUi.click("Safe compositing", exact = true)
        assertTrue(settings.safeCompositing)
        assertTrue(AdjustmentStage.safeCompositing)
        assertEquals(2, redraws)

        // v1.9 Gate 2 (A8's settings restore with the editor open): the Backup section's restore
        // saves the backup's settings on another thread (the dialog may be closed by then). The
        // switch and the compositor follow the restored "off", and the canvas is redrawn for it,
        // once. The switch's own taps above did not count as followed (no extra redraw).
        Thread { AppSettings(ctx).safeCompositing = false }.apply { start(); join() }
        SmokeUi.settle()
        assertFalse(AdjustmentStage.safeCompositing)
        assertFalse(prefs.safeCompositing)
        assertEquals("the canvas is redrawn for the restored switch", 3, redraws)
        // The switch's own taps still redraw once each.
        SmokeUi.click("Safe compositing", exact = true)
        assertTrue(settings.safeCompositing)
        assertTrue(AdjustmentStage.safeCompositing)
        SmokeUi.settle()
        assertEquals(4, redraws)
    }
}
