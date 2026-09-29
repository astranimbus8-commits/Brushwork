package com.brushwork.paint.ui.color

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.ui.theme.BrushworkTheme
import kotlinx.coroutines.MainScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The real panel opened and closed in an activity: recent colors are recorded on close only when
 * the color changed, and the grayscale hint follows a document color-mode change.
 *
 * Own sandbox (see [ColorPickerUiSmokeTest]): later tests in a shared sandbox get no frames.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-hdpi", instrumentedPackages = ["com.brushwork.paint.ui.color.panellifecyclesandbox"])
class PanelLifecycleRobolectricTest {

    private fun settle() = repeat(20) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }

    /** Semantics nodes of every Compose window (activity + sheet). */
    @Suppress("UNCHECKED_CAST")
    private fun allNodes(): List<SemanticsNode> {
        val wmg = Class.forName("android.view.WindowManagerGlobal")
        val inst = wmg.getMethod("getInstance").invoke(null)
        val views = (wmg.getDeclaredField("mViews").apply { isAccessible = true }.get(inst) as List<View>).toList()
        fun roots(v: View): List<ViewRootForTest> = when (v) {
            is ViewRootForTest -> listOf(v)
            is ViewGroup -> (0 until v.childCount).flatMap { roots(v.getChildAt(it)) }
            else -> emptyList()
        }
        val out = mutableListOf<SemanticsNode>()
        fun walk(n: SemanticsNode) { out += n; n.children.forEach(::walk) }
        views.flatMap(::roots).forEach { walk(it.semanticsOwner.unmergedRootSemanticsNode) }
        return out
    }

    private fun textShown(fragment: String) = allNodes().any { n ->
        n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains(fragment) } == true
    }

    @Test
    fun recentsOnCloseAndLiveColorModeHint() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val doc = Document("t", "t", 64, 64)
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(64, 64))
        val c = EditorController(activity.applicationContext, doc, MainScope(), AppSettings(activity))
        c.color = 0xFF3366CC.toInt()
        val store = PaletteStore.get(activity)
        store.clearRecent()
        var show by mutableStateOf(true)
        activity.setContent { BrushworkTheme { if (show) ColorPickerPanel(c) { show = false } } }
        settle()
        assertTrue("panel is showing", textShown("Previous"))

        // Closing without a change records nothing.
        show = false
        settle()
        assertTrue(store.data.recent.isEmpty())

        // Reopen, change the color (as the wheel would), close: recorded once, opaque.
        show = true
        settle()
        assertFalse(textShown("This canvas is grayscale"))
        c.color = 0xFF112233.toInt()
        settle()
        // The document switches to grayscale while the panel is open: the hint appears.
        doc.colorMode = ColorMode.GRAYSCALE
        c.onDocumentGeometryChanged()
        settle()
        assertTrue("grayscale hint follows the document", textShown("This canvas is grayscale"))
        show = false
        settle()
        assertEquals(listOf(0xFF112233.toInt()), store.data.recent)
        assertEquals(0xFF112233.toInt(), c.color)
    }
}
