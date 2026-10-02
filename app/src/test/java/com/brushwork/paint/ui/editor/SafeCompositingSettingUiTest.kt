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
 * it on or off saves it, switches the compositor and redraws the canvas.
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
        val prefs = EditorPrefs(settings)
        assertTrue(prefs.safeCompositing)
        var redraws = 0
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
    }
}
