package com.brushwork.paint.ui.common

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.snap.Increments
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.6 §3.4 (G): the Increments sheet (More › Increments…, `EditorPanel.INCREMENTS`) and so the
 * Settings › Increments section it holds. Titled "Increment steps" (its minimized pill must not
 * read "Increments", the X / Y pill's "#" cell, I10): the switch "Use increments", the five steps
 * typed (persisted app-wide: a new editor reads them), and "Clear custom steps" for the
 * controls' own steps (disabled when there are none). Own sandbox, one test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.common.incrementssheetsandbox"])
class IncrementsSheetUiRobolectricTest {

    @Test
    fun theIncrementsSheet() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c = Smoke.controller(activity, Smoke.document(200, 150, layers = 1))
        c.increments.update { it.withCustom("Exposure|EV", 0.25f) }
        var open = true
        activity.setContent {
            BrushworkTheme {
                CompositionLocalProvider(LocalIncrements provides c.increments) {
                    if (open) IncrementsSheet(c, onDismiss = { open = false })
                }
            }
        }
        SmokeUi.settle(20, 50)
        assertTrue("titled: ${SmokeUi.shown().take(40)}", SmokeUi.has(INCREMENTS_SHEET_TITLE, exact = true))
        assertEquals("Increment steps", INCREMENTS_SHEET_TITLE)
        assertFalse("never \"Increments\" (the pill's # cell)", SmokeUi.has("Increments", exact = true))
        for (label in listOf("Length step", "Size step", "Scale step", "Angle step", "Percent step")) {
            assertTrue("\"$label\"", SmokeUi.has(label, exact = true))
        }

        // The switch.
        assertFalse(c.increments.enabled)
        SmokeUi.click("Use increments", exact = true)
        assertTrue(c.increments.enabled)

        // Steps are typed exactly and persisted.
        SmokeUi.typeAndDone("Length step", "25")
        SmokeUi.typeAndDone("Angle step", "7.5")
        SmokeUi.typeAndDone("Scale step", "12.5")
        assertEquals(25f, c.increments.state.lengthPx, 0f)
        assertEquals(7.5f, c.increments.state.angleDeg, 0f)
        assertEquals(12.5f, c.increments.state.scalePercent, 0f)
        val again = Increments(AppSettings(activity))
        assertEquals("persisted app-wide", 25f, again.state.lengthPx, 0f)
        assertTrue(again.state.enabled)

        // The controls' own steps: one, cleared.
        assertTrue(SmokeUi.has("1 control has a step of its own", exact = true))
        assertTrue(SmokeUi.isEnabled("Clear custom steps"))
        SmokeUi.click("Clear custom steps", exact = true)
        assertTrue(c.increments.state.custom.isEmpty())
        assertFalse(SmokeUi.isEnabled("Clear custom steps"))

        SmokeUi.click("Use increments", exact = true)
        assertFalse(c.increments.enabled)
        c.dispose()
    }
}
