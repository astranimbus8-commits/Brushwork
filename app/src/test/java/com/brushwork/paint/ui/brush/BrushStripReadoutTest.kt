package com.brushwork.paint.ui.brush

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.brushwork.paint.brush.BrushPresetStore
import com.brushwork.paint.brush.BrushTool
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.common.FieldUi
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * The brush size and opacity numbers in the brush tool's options strip, on the user's phone
 * size: tapping one opens a small editor where the number can be typed or dragged, the change
 * reaches the brush and is saved, and the full brush panel is one tap away.
 *
 * One test: Compose's frame clock only serves the first test of a Robolectric sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.brush.stripreadoutsandbox"])
class BrushStripReadoutTest {

    private fun pressBack(window: android.view.View) {
        window.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        window.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        SmokeUi.settle()
    }

    @Test
    fun sizeAndOpacityReadoutsCanBeTypedAndDragged() {
        ShadowLog.stream = null
        // The editor is a DropdownMenu popup: park its endless anchor polling.
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c = Smoke.controller(activity, Smoke.document(400, 300))
        c.selectTool(ToolId.BRUSH)
        val tool = c.tools.getValue(ToolId.BRUSH) as BrushTool
        val store = BrushPresetStore.get(activity)
        val id = requireNotNull(c.presetFor(ToolId.BRUSH)).id
        activity.setContent {
            BrushworkTheme {
                Surface {
                    // Like the options bar under the top bar: a horizontally scrolling strip.
                    Row(Modifier.horizontalScroll(rememberScrollState())) { BrushToolOptions(tool) }
                }
            }
        }
        SmokeUi.settle()

        // Size: tap the number, type a new one, Done.
        SmokeUi.click("Edit Brush size")
        SmokeUi.assertWindowsLaidOut(2)
        SmokeUi.typeAndDone("Brush size", "37")
        assertEquals(37f, requireNotNull(c.presetFor(ToolId.BRUSH)).size, 0f)
        assertEquals("the typed size is saved", 37f, store.edited(id)?.size)
        // Its slider (logarithmic) sits with it and drags the size too.
        FieldUi.setProgress("Brush size", 1f)
        assertEquals(1000f, requireNotNull(c.presetFor(ToolId.BRUSH)).size, 0f)
        FieldUi.setProgress("Brush size", 0f)
        assertEquals(0.5f, requireNotNull(c.presetFor(ToolId.BRUSH)).size, 0f)
        SmokeUi.typeAndDone("Brush size", "37")

        // The full brush panel is one tap away and shows the same number.
        SmokeUi.click("All brush settings")
        assertTrue("the brush panel opened", SmokeUi.has("Presets", exact = false) || SmokeUi.has("PRESETS", exact = false))
        assertEquals(com.brushwork.paint.core.Units.formatNumber(37.0, 1), SmokeUi.field("Size").text)
        SmokeUi.click("Close", exact = true)
        Smoke.pump(500)
        assertEquals("only the activity is left", 1, SmokeUi.windows().size)
        assertTrue("the strip shows the new size", SmokeUi.has("37 px", exact = true))

        // Opacity: tap the percentage, drag its slider; closing the editor keeps and saves it.
        SmokeUi.click("Edit Opacity")
        SmokeUi.assertWindowsLaidOut(2)
        FieldUi.setProgress("Opacity", 0.25f)
        assertEquals(0.25f, requireNotNull(c.presetFor(ToolId.BRUSH)).opacity, 1e-6f)
        assertEquals("25", SmokeUi.field("Opacity").text)
        SmokeUi.typeAndDone("Opacity", "60")
        assertEquals(0.6f, requireNotNull(c.presetFor(ToolId.BRUSH)).opacity, 1e-6f)
        pressBack(SmokeUi.windows().last())
        assertEquals("the editor closed", 1, SmokeUi.windows().size)
        assertEquals("the typed opacity is saved", 0.6f, requireNotNull(store.edited(id)).opacity, 1e-6f)
        assertTrue("the strip shows the new opacity", SmokeUi.has("60%", exact = true))
        Smoke.assertQuiet(c, "brush strip")
    }
}
