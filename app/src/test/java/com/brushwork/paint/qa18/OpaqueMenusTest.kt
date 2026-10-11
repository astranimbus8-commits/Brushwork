package com.brushwork.paint.qa18

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.MenuLabels18
import com.brushwork.paint.ui.common.SheetBackground
import com.brushwork.paint.ui.common.StripLabels18
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.theme.IbisColors
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.select.ObjectTestKit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 (D27, design §3.9): the menus are opaque ([IbisColors.Menu], #1E1E1E) and legible over any
 * canvas, while sheets stay translucent. Pixels of real renders (Skia) on the user's phone
 * (392 × 873 dp), sampled inside each surface away from its content and its 1 dp edge:
 * - the More menu's card ("More options"): its own window, every pixel sampled is #1E1E1E with
 *   full alpha;
 * - the options strip's More row and the vector object bar's More row, over the white page:
 *   #1E1E1E, nothing of the page shows through; the object bar's own row (a sheet,
 *   [SheetBackground]) lets the page through.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.opaquemenussandbox"])
class OpaqueMenusTest {

    private val menu = IbisColors.Menu.toArgb()

    @Test
    fun menusAreOpaqueAndSheetsTranslucent() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = ChromeHarness()
        h.section("the More menu's card") { moreMenu(h) }
        h.section("the strip's More row") { stripMoreRow() }
        h.section("the object bar's More row") { objectMoreRow(h) }
        dog.interrupt()
        h.finish()
    }

    /**
     * [v] drawn into a bitmap. Under Robolectric a popup's content waits for an input event to be
     * laid out, and its compose view draws nothing into a bitmap with its default (hardware)
     * layer: it is laid out first ([RobolectricUi.elements]), then drawn through a software layer,
     * as on the phone.
     */
    private fun render(v: View, software: Boolean = false): Bitmap {
        RobolectricUi.elements()
        val views = if (software) allViews(v) - v else emptyList()
        views.forEach { it.setLayerType(View.LAYER_TYPE_SOFTWARE, null); it.invalidate() }
        v.invalidate()
        Smoke.pump(300); SmokeUi.settle()
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        views.forEach { it.setLayerType(View.LAYER_TYPE_NONE, null) }
        return b
    }

    private fun allViews(v: View): List<View> =
        if (v is ViewGroup) (0 until v.childCount).flatMap { allViews(v.getChildAt(it)) } + v else listOf(v)

    private fun hex(c: Int) = "#%08X".format(c)

    private fun assertMenuColor(what: String, pixel: Int) {
        assertEquals("$what: opaque ${hex(pixel)}", 255, pixel ushr 24)
        for (shift in listOf(16, 8, 0)) {
            assertEquals("$what: #1E1E1E, got ${hex(pixel)}", ((menu shr shift) and 0xFF).toFloat(), ((pixel shr shift) and 0xFF).toFloat(), 1f)
        }
    }

    /** The pixel of [b] (the main window, px) at editor point ([x], [y]) dp. */
    private fun at(s: ChromeScreen, b: Bitmap, x: Float, y: Float): Int {
        val (px, py) = Finger.px(s, x, y)
        return b.getPixel(px.toInt().coerceIn(0, b.width - 1), py.toInt().coerceIn(0, b.height - 1))
    }

    /** The view (document px × 3) puts the white page under the chrome's top half (400 × 300 dp). */
    private fun pageUnderTheChrome(s: ChromeScreen) {
        s.c.viewTransform.set(Matrix().apply { setScale(3f, 3f) })
        Smoke.pump(200); SmokeUi.settle()
    }

    private fun moreMenu(h: ChromeHarness) {
        val s = h.editor()
        pageUnderTheChrome(s)
        Finger.tap(s, "More options")
        Smoke.pump(500); SmokeUi.settle()
        val windows = SmokeUi.windows()
        assertEquals("the More menu drops", 2, windows.size)
        val popup = windows.last()
        val shot = render(popup, software = true)
        // Inside every section header that shows, right of its name (a header is the card's own
        // background: no item, no ripple).
        val headers = RobolectricUi.elements().filter { e ->
            e.window === popup && e.node.layoutInfo.isPlaced &&
                e.node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(V18Tags.MENU_SECTION_PREFIX) == true
        }
        assertNotNull(
            "the first section shows",
            headers.firstOrNull { it.node.config.getOrNull(SemanticsProperties.TestTag) == V18Tags.MENU_SECTION_PREFIX + MenuLabels18.CLIPBOARD },
        )
        val d = s.density
        var sampled = 0
        for (e in headers) {
            val r: Rect = e.bounds
            if (r.bottom > shot.height || r.top < 0f || r.height < 20f * d) continue
            val name = e.node.config.getOrNull(SemanticsProperties.TestTag)!!.removePrefix(V18Tags.MENU_SECTION_PREFIX)
            for (x in listOf(r.right - 12f * d, r.right - 40f * d, r.center.x + 30f * d)) {
                assertMenuColor("\"$name\" header at x ${x.toInt()} px", shot.getPixel(x.toInt(), r.center.y.toInt()))
                sampled++
            }
        }
        assertTrue("sampled the card ($sampled)", sampled >= 6)
        Finger.back()
        assertEquals(1, SmokeUi.windows().size)
    }

    private fun stripMoreRow() = FakeStripEditor.open(Smoke.document(400, 300, layers = 2, whiteBottom = true), FakeStrips.spec(1)).use { e ->
        val s = e.screen
        pageUnderTheChrome(s)
        Finger.tap(s, StripLabels18.MORE_TOGGLE)
        Smoke.pump(300); SmokeUi.settle()
        val more = s.tagged(V18Tags.STRIP_MORE_ROW) ?: throw AssertionError("no More row")
        val item = Finger.control(s, FakeStrips.more(1))!!
        val shot = render(s.activity.window.decorView)
        val white = at(s, shot, more.center.x, more.bottom + 3f)
        assertEquals("the page under the row is white: ${hex(white)}", 0xFFFFFFFF.toInt(), white)
        // Right of the 300 dp item, inside the 1 dp edge.
        for (x in listOf((item.right + more.right) / 2f, more.right - 6f)) {
            for (y in listOf(more.top + 6f, more.center.y, more.bottom - 6f)) assertMenuColor("the strip's More row at ($x, $y) dp", at(s, shot, x, y))
        }
    }

    private fun objectMoreRow(h: ChromeHarness) {
        val kit = ObjectTestKit(400, 300)
        val doc: Document = Smoke.document(400, 300, layers = 1, whiteBottom = true).also { d ->
            d.layers += Layer(d.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(400, 300)).also { it.vector = VectorContent.EMPTY }
            d.activeLayerIndex = 1
        }
        val s = h.editor(doc) { c ->
            val layer = c.doc.layers[1]
            // Low and to the right: nothing of it is drawn under the bar.
            c.vectors.addObjects(layer, listOf(kit.box(330f, 250f, 390f, 290f)), "Add")
            c.vectors.setSelection(layer, setOf(1L))
        }
        Smoke.pumpUntil(10_000) { !s.c.vectors.isRendering }
        pageUnderTheChrome(s)
        Finger.tap(s, StripLabels18.MORE_OBJECT)
        Smoke.pump(300); SmokeUi.settle()
        val more = s.tagged(V18Tags.STRIP_MORE_ROW) ?: throw AssertionError("no object More row")
        val array = Finger.control(s, ArrayLabels.FROM_OBJECTS)!!
        val shot = render(s.activity.window.decorView)
        val white = at(s, shot, more.center.x, more.bottom + 3f)
        assertEquals("the page under the row is white: ${hex(white)}", 0xFFFFFFFF.toInt(), white)
        for (x in listOf((array.right + more.right) / 2f, more.right - 8f)) {
            for (y in listOf(more.top + 8f, more.center.y, more.bottom - 8f)) assertMenuColor("the object More row at ($x, $y) dp", at(s, shot, x, y))
        }
        // The bar's own row is a sheet: over the white page it is lighter than the menus.
        val bar = s.tagged(ChromeTags.SELECTION_BAR)!!
        val sheet = at(s, shot, bar.center.x, bar.top + 1.5f)
        val expected = 255 - (255 * SheetBackground.alpha).toInt()
        assertEquals("the bar's row lets the page through: ${hex(sheet)}", expected.toFloat(), (sheet and 0xFF).toFloat(), 3f)
        assertTrue("…lighter than a menu", (sheet and 0xFF) > (menu and 0xFF) + 10)
        kit.close()
    }
}
