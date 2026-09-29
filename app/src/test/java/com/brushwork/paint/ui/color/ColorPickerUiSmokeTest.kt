package com.brushwork.paint.ui.color

import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.ui.theme.BrushworkTheme
import kotlinx.coroutines.MainScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Hosts the real panel and dialog in an activity (phone-sized, hdpi) and lets them compose,
 * measure and animate in: catches crashes such as intrinsic-measurement or layout errors inside
 * the sheet/dialog windows, and checks the dialog's mode tabs react to a real tap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-hdpi")
class ColorPickerUiSmokeTest {

    private fun settle() = repeat(20) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }

    /** All window root views (activity + sheet/dialog windows). */
    @Suppress("UNCHECKED_CAST")
    private fun rootViews(): List<View> {
        val wmg = Class.forName("android.view.WindowManagerGlobal")
        val inst = wmg.getMethod("getInstance").invoke(null)
        val f = wmg.getDeclaredField("mViews").apply { isAccessible = true }
        return (f.get(inst) as List<View>).toList()
    }

    private fun assertWindowsLaidOut() {
        val roots = rootViews()
        assertTrue("expected the activity plus a sheet/dialog window, got ${roots.size}", roots.size >= 2)
        assertTrue(roots.all { it.width > 0 && it.height > 0 })
    }

    private fun controller(activity: ComponentActivity, mode: ColorMode = ColorMode.RGB): EditorController {
        val doc = Document("t", "t", 64, 64)
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(64, 64))
        doc.colorMode = mode
        return EditorController(activity.applicationContext, doc, MainScope(), AppSettings(activity))
    }

    private fun tap(v: View, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        v.dispatchTouchEvent(down)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        val up = MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        v.dispatchTouchEvent(up)
        settle()
    }

    /** One finger gesture through [points] (with interpolated moves in between). */
    private fun drag(v: View, vararg points: Pair<Float, Float>) {
        val t0 = SystemClock.uptimeMillis()
        var t = t0
        fun send(action: Int, x: Float, y: Float) {
            val e = MotionEvent.obtain(t0, t, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            v.dispatchTouchEvent(e)
            e.recycle()
        }
        send(MotionEvent.ACTION_DOWN, points[0].first, points[0].second)
        for (i in 1 until points.size) {
            val (ax, ay) = points[i - 1]
            val (bx, by) = points[i]
            for (s in 1..8) {
                t += 8
                send(MotionEvent.ACTION_MOVE, ax + (bx - ax) * s / 8f, ay + (by - ay) * s / 8f)
            }
        }
        t += 8
        send(MotionEvent.ACTION_UP, points.last().first, points.last().second)
        settle()
    }

    // Dialog geometry at w360dp-h760dp-hdpi (1.5 px/dp): the window is as tall as the dialog;
    // the wheel is 240 dp (360 px) centered at x = 270, center y = 474 px from the window top.
    private val wheelCx = 270f
    private val wheelCy = 474f
    private fun okButton(root: View) = Pair(root.width - 80f, root.height - 72f)
    private fun cancelButton(root: View) = Pair(root.width - 187f, root.height - 72f)

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

    @Test
    fun dialogComposesWithAlphaAndTabsRespondToTaps() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val store = PaletteStore.get(activity)
        store.setPickerMode(PickerMode.WHEEL)
        activity.setContent { BrushworkTheme { ColorPickerDialog(0x8033AA55.toInt(), {}, {}, showAlpha = true) } }
        settle()
        assertWindowsLaidOut()
        // "RGB" tab: middle third of the tab row, 249 px from the dialog window top (hdpi, 360 dp).
        tap(rootViews().last(), 269f, 249f)
        assertEquals(PickerMode.RGB.ordinal, store.data.pickerMode)
        assertWindowsLaidOut()
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
            BrushworkTheme { ColorPickerDialog(0xFF3366CC.toInt(), onPick = { picked = it }, onDismiss = { dismissed = true }) }
        }
        settle()
        val root = rootViews().last()
        // Tap the top of the hue ring (hue 0), then drag in the square from the center past the
        // bottom-left corner (black) and on past the top-right corner (full saturation/brightness).
        tap(root, wheelCx, wheelCy - 152f)
        drag(root, wheelCx to wheelCy, wheelCx - 150f to wheelCy + 220f, wheelCx + 150f to wheelCy - 130f)
        val (okX, okY) = okButton(root)
        tap(root, okX, okY)

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
            BrushworkTheme { ColorPickerDialog(0xFF3366CC.toInt(), onPick = { picked = it }, onDismiss = { dismissed = true }) }
        }
        settle()
        val root = rootViews().last()
        tap(root, wheelCx, wheelCy - 152f)
        val (x, y) = cancelButton(root)
        tap(root, x, y)
        assertTrue(dismissed)
        assertEquals(null, picked)
    }
}
