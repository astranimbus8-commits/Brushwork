package com.brushwork.paint.ui.mask

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.6 §3.1a review fix: the Masks tool's "Fast adjustment preview" switch shows the SAVED setting.
 * When Settings turned it off, the live adjustment's cached copy only catches up at the next drag;
 * the sheet must still show "off", and one tap must turn it on (not off again). Own sandbox: this
 * harness runs one Compose screen per test class.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.mask.fasttogglesyncsandbox"])
class FastAdjustPreviewToggleSyncUiRobolectricTest {

    private fun state(): ToggleableState? {
        var n: SemanticsNode? = SmokeUi.find(FAST_ADJUST_PREVIEW_LABEL, exact = true)?.node
        while (n != null) {
            n.config.getOrNull(SemanticsProperties.ToggleableState)?.let { return it }
            n = n.parent
        }
        return null
    }

    @Test
    fun theMasksSheetSwitchShowsTheSettingSavedInSettings() {
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c = Smoke.controller(activity, Smoke.document(400, 300, layers = 2))
        assertTrue(c.liveAdjust.fastPreview)
        // Settings' copy of the switch saved "off"; no drag has happened since.
        c.settings.fastAdjustPreview = false
        assertTrue("the live adjustment's cached copy is still on", c.liveAdjust.fastPreview)

        activity.setContent { BrushworkTheme { FastAdjustPreviewToggle(c) } }
        SmokeUi.settle()
        assertEquals("the sheet shows the saved setting", ToggleableState.Off, state())

        SmokeUi.click(FAST_ADJUST_PREVIEW_LABEL, exact = true)
        assertEquals(ToggleableState.On, state())
        assertTrue("one tap turns it on", c.settings.fastAdjustPreview)
        assertTrue(c.liveAdjust.fastPreview)
        Smoke.assertQuiet(c, "fast preview switch after Settings")
    }
}
