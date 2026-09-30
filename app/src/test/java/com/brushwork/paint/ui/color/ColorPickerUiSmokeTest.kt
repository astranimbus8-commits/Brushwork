package com.brushwork.paint.ui.color

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.ui.color.RobolectricUi.byDescription
import com.brushwork.paint.ui.color.RobolectricUi.byText
import com.brushwork.paint.ui.color.RobolectricUi.drag
import com.brushwork.paint.ui.color.RobolectricUi.settle
import com.brushwork.paint.ui.color.RobolectricUi.tap
import com.brushwork.paint.ui.color.RobolectricUi.windowRoots
import com.brushwork.paint.ui.theme.BrushworkTheme
import kotlinx.coroutines.MainScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Hosts the real panel and dialog in an activity (phone-sized, hdpi) and lets them compose,
 * measure and animate in: catches crashes such as intrinsic-measurement or layout errors inside
 * the sheet/dialog windows, and drives the dialog with real touch events. Elements are located
 * through the semantics tree (see [RobolectricUi]), not fixed pixel positions.
 *
 * Compose keeps a process-static frame clock bound to the first test's Choreographer, so frames
 * (animations) stall in later tests of the same Robolectric sandbox. These tests only rely on
 * composition, layout and synchronous input dispatch, and the placeholder instrumented package
 * gives this class its own sandbox so it can't affect other Compose tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-hdpi", instrumentedPackages = ["com.brushwork.paint.ui.color.uitestsandbox"])
class ColorPickerUiSmokeTest {

    private fun assertWindowsLaidOut() {
        val roots = windowRoots()
        assertTrue("expected the activity plus a sheet/dialog window, got ${roots.size}", roots.size >= 2)
        assertTrue(roots.all { it.width > 0 && it.height > 0 })
    }

    private fun controller(activity: ComponentActivity, mode: ColorMode = ColorMode.RGB): EditorController {
        val doc = Document("t", "t", 64, 64)
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(64, 64))
        doc.colorMode = mode
        return EditorController(activity.applicationContext, doc, MainScope(), AppSettings(activity))
    }

    private fun wheel(activity: ComponentActivity) =
        RobolectricUi.WheelPoints(byDescription("Color wheel"), activity.resources.displayMetrics.density)

    @Test
    fun panelComposesInEveryMode() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c = controller(activity)
        c.color = 0xFF3366CC.toInt()
        val store = PaletteStore.get(activity)
        activity.setContent { BrushworkTheme { ColorPickerPanel(c) {} } }
        settle()
        for (m in PickerMode.entries) {
            store.setPickerMode(m)
            settle()
            assertWindowsLaidOut()
        }
        // Composing alone must not change the drawing color.
        assertEquals(0xFF3366CC.toInt(), c.color)
    }

    @Test
    fun panelComposesForGrayscaleAndMonochromeDocuments() {
        for (mode in listOf(ColorMode.GRAYSCALE, ColorMode.MONOCHROME)) {
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
            val c = controller(activity, mode)
            c.color = 0xFFCC3366.toInt()
            activity.setContent { BrushworkTheme { ColorPickerPanel(c) {} } }
            settle()
            assertWindowsLaidOut()
            assertEquals(0xFFCC3366.toInt(), c.color)
        }
    }

    /**
     * The dialog's title-row actions and body composed straight into the activity. The real
     * dialog is a bottom sheet, which only animates into view in the first test of a sandbox
     * (see the class comment), so touch tests drive the same pieces without the sheet.
     */
    @Composable
    private fun DialogWithoutSheet(initial: Int, showAlpha: Boolean, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
        val model = rememberColorDialogModel(initial, showAlpha)
        Surface {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row { ColorDialogActions(model, onPick, onDismiss) }
                ColorDialogBody(model)
            }
        }
    }

    @Test
    fun dialogComposesWithAlphaAndTabsRespondToTaps() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val store = PaletteStore.get(activity)
        store.setPickerMode(PickerMode.WHEEL)
        var sheet by mutableStateOf(true)
        activity.setContent {
            BrushworkTheme {
                if (sheet) ColorPickerDialog(0x8033AA55.toInt(), {}, {}, showAlpha = true)
                else DialogWithoutSheet(0x8033AA55.toInt(), showAlpha = true, onPick = {}, onDismiss = {})
            }
        }
        settle()
        assertWindowsLaidOut()
        for (m in PickerMode.entries) {
            store.setPickerMode(m)
            settle()
            assertWindowsLaidOut()
        }
        store.setPickerMode(PickerMode.WHEEL)
        sheet = false
        settle()
        byText("RGB").tap()
        assertEquals(PickerMode.RGB.ordinal, store.data.pickerMode)
    }

    @Test
    fun dialogWheelGesturesPickAColor() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val store = PaletteStore.get(activity)
        store.setPickerMode(PickerMode.WHEEL)
        store.clearRecent()
        var picked: Int? = null
        var dismissed = false
        activity.setContent {
            BrushworkTheme { DialogWithoutSheet(0xFF3366CC.toInt(), showAlpha = false, onPick = { picked = it }, onDismiss = { dismissed = true }) }
        }
        settle()
        val w = wheel(activity)
        val window = byDescription("Color wheel").window
        // Tap the top of the hue ring (hue 0), then drag in the square from the center past the
        // bottom-left corner (black) and on past the top-right corner (full saturation/brightness).
        tap(window, w.ringTop.first, w.ringTop.second)
        drag(window, w.center, w.square(-1.7f, 2.5f), w.square(1.7f, -1.5f))
        byText("OK").tap()

        assertTrue(dismissed)
        val c = requireNotNull(picked) { "OK did not deliver a color" }
        val hsb = Hsb.fromColor(c)
        assertEquals(255, c ushr 24)
        assertEquals(1f, hsb.s, 0.01f)
        assertEquals(1f, hsb.b, 0.01f)
        assertTrue("hue ${hsb.h} should be near 0", hsb.h < 5f || hsb.h > 355f)
        // A changed color picked with OK is recorded in the shared recents.
        assertEquals(c, store.data.recent.first())
    }

    @Test
    fun dialogCancelDoesNotPick() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        PaletteStore.get(activity).setPickerMode(PickerMode.WHEEL)
        var picked: Int? = null
        var dismissed = false
        activity.setContent {
            BrushworkTheme { DialogWithoutSheet(0xFF3366CC.toInt(), showAlpha = false, onPick = { picked = it }, onDismiss = { dismissed = true }) }
        }
        settle()
        val w = wheel(activity)
        tap(byDescription("Color wheel").window, w.ringTop.first, w.ringTop.second)
        byText("Cancel").tap()
        assertTrue(dismissed)
        assertEquals(null, picked)
    }
}
