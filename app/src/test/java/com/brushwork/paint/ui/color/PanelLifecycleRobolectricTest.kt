package com.brushwork.paint.ui.color

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.ui.color.RobolectricUi.byDescription
import com.brushwork.paint.ui.color.RobolectricUi.drag
import com.brushwork.paint.ui.color.RobolectricUi.hasText
import com.brushwork.paint.ui.color.RobolectricUi.settle
import com.brushwork.paint.ui.color.RobolectricUi.tap
import com.brushwork.paint.ui.theme.BrushworkTheme
import kotlinx.coroutines.MainScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The real panel (bottom sheet) opened and closed in an activity: wheel gestures inside the sheet
 * change the drawing color live without dragging the sheet, the grayscale hint follows a document
 * color-mode change, and recent colors are recorded on close only when the color changed.
 *
 * Own sandbox (see [ColorPickerUiSmokeTest]): later tests in a shared sandbox get no frames.
 * Element bounds are read after forcing pending Compose layout (see [RobolectricUi.elements]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-hdpi", instrumentedPackages = ["com.brushwork.paint.ui.color.panellifecyclesandbox"])
class PanelLifecycleRobolectricTest {

    @Test
    fun wheelHintAndRecents() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val doc = Document("t", "t", 64, 64)
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(64, 64))
        val c = EditorController(activity.applicationContext, doc, MainScope(), AppSettings(activity))
        c.color = 0xFF3366CC.toInt()
        val store = PaletteStore.get(activity)
        store.clearRecent()
        store.setPickerMode(PickerMode.WHEEL)
        var show by mutableStateOf(true)
        activity.setContent { BrushworkTheme { if (show) ColorPickerPanel(c) { show = false } } }
        settle()
        assertTrue("panel is showing", hasText("Previous"))
        assertFalse(hasText("This canvas is grayscale"))

        // Wheel: ring top (hue 0), then a drag in the square that goes down past the bottom-left
        // corner (it would pull the sheet down if the wheel didn't consume it) and ends past the
        // top-right corner.
        val wheel = byDescription("Color wheel")
        val before = wheel.bounds
        assertTrue("wheel laid out", before.width > 0f)
        val w = RobolectricUi.WheelPoints(wheel, activity.resources.displayMetrics.density)
        tap(wheel.window, w.ringTop.first, w.ringTop.second)
        drag(wheel.window, w.center, w.square(-1.7f, 2.5f), w.square(1.7f, -1.5f))
        assertTrue("the sheet is still open", hasText("Previous"))
        assertEquals("the sheet did not move or scroll", before, byDescription("Color wheel").bounds)
        val hsb = Hsb.fromColor(c.color)
        assertEquals("drawing color follows the wheel live and stays opaque", 255, c.color ushr 24)
        assertEquals(1f, hsb.s, 0.01f)
        assertEquals(1f, hsb.b, 0.01f)
        assertTrue("hue ${hsb.h} should be near 0", hsb.h < 5f || hsb.h > 355f)
        val picked = c.color
        assertTrue("nothing recorded while the panel is open", store.data.recent.isEmpty())

        // The document switches to grayscale while the panel is open: the hint appears.
        doc.colorMode = ColorMode.GRAYSCALE
        c.onDocumentGeometryChanged()
        settle()
        assertTrue("grayscale hint follows the document", hasText("This canvas is grayscale"))

        // Closing records the changed color once.
        show = false
        settle()
        assertEquals(listOf(picked), store.data.recent)
        assertEquals(picked, c.color)

        // Opening and closing again without a change records nothing.
        store.clearRecent()
        show = true
        settle()
        show = false
        settle()
        assertTrue(store.data.recent.isEmpty())
        assertEquals(picked, c.color)
    }
}
