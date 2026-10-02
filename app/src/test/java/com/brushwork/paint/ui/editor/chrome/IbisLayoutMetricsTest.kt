package com.brushwork.paint.ui.editor.chrome

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.ShapeTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.6 §3.7.2–3.7.9 on the user's phone (392 × 873 dp, xxhdpi): every band of the ibisPaint main
 * screen within ±1 dp — top row (8 circles, 48 dp pitch), options strip (85 / 44 tall from 6 to
 * 386), slider rows (701 / 741, value 0–58, − at 73, track 92–355, + at 375), bottom bar (7 slots
 * of 56 × 50), the canvas fit insets (137 / 172), the tool menu (150 wide, ≤ 434, 16 above the
 * bar), the layer window (382 × 520 at x 5, its bottom on the bar), ✓ / ✕ placement and "hide
 * interface". Positions are relative to the system bars the test window reports (the design's
 * phone has a 33 dp status bar and a 42 dp navigation bar; Robolectric reports its own).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.editor.chrome.ibislayoutsandbox"])
class IbisLayoutMetricsTest {

    private fun near(what: String, expected: Float, actual: Float, tol: Float = 1f) =
        assertEquals("$what: expected $expected dp, got $actual", expected, actual, tol)

    @Test
    fun theReferencePhoneBands() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = ChromeHarness()
        h.section("top row, options strip, slider rows, bottom bar, fit") { bands(h) }
        h.section("tool menu") { toolMenu(h) }
        h.section("layer window") { layerWindow(h) }
        h.section("✓ / ✕ placement and hide interface") { pendingAndHide(h) }
        dog.interrupt()
        h.finish()
    }

    private fun bands(h: ChromeHarness) {
        val s = h.editor()
        val (st, nav) = s.insetsDp()
        val w = s.widthDp
        val hh = s.heightDp
        near("screen width", 392f, w)

        // ---- top row: 8 circles, touch 48 × 48 at a 48 dp pitch, centres 24 + 48·i
        val top = s.tagged(ChromeTags.TOP_ROW) ?: throw AssertionError("no top row")
        near("top row top", st, top.top)
        near("top row height", 48f, top.height)
        val labels = listOf("Undo", "Redo", "Vector", "Selection", "Stabilizer", "Grid", "Ruler", "More options")
        labels.forEachIndexed { i, label ->
            val b = s.clickable(label) ?: throw AssertionError("no \"$label\"")
            near("$label centre x", 24f + 48f * i, b.center.x)
            near("$label touch width", 48f, b.width)
            near("$label touch height", 48f, b.height)
            near("$label top", st, b.top)
        }
        assertEquals("the visual circles are 40 dp", 40f, ChromeLayout.topRow(w).circle, 0.01f)

        // ---- options strip: a floating panel at y 85, 44 tall, x 6..386
        val strip = s.tagged(ChromeTags.OPTIONS_STRIP) ?: throw AssertionError("no options strip")
        near("strip top", st + 52f, strip.top)
        near("strip height", 44f, strip.height)
        near("strip left", 6f, strip.left)
        near("strip right", w - 6f, strip.right)

        // ---- slider rows: 701 and 741 on the reference phone (two 40 dp rows on the bar)
        val rowsTop = hh - nav - 50f - 80f
        val rows = s.tagged(ChromeTags.SLIDER_ROWS) ?: throw AssertionError("no slider rows")
        near("slider rows top", rowsTop, rows.top)
        near("slider rows height", 80f, rows.height)
        val size = slider(s, "Brush size")
        near("size track left", 92f, size.left)
        near("size track right", w - 37f, size.right)
        near("size row top", rowsTop, size.top)
        near("size row height", 40f, size.height)
        val opacity = slider(s, "Brush opacity")
        near("opacity row top", rowsTop + 40f, opacity.top)
        val value = s.clickable("Type brush size")!!
        near("value left", 0f, value.left)
        near("value width", 58f, value.width)
        val minus = s.clickable("Smaller brush")!!
        near("− centre", 73f, minus.center.x)
        near("− touch", 40f, minus.width)
        near("− touch height", 40f, minus.height)
        val plus = s.clickable("Bigger brush")!!
        assertTrue("+ (centre 375) inside its touch box $plus", 375f in plus.left..plus.right)
        near("+ touch", 40f, plus.width)
        near("+ right edge", w, plus.right)
        assertNotNull(s.clickable("Less opacity"))
        assertNotNull(s.clickable("More opacity"))
        assertNotNull(s.clickable("Type brush opacity"))
        assertTrue("ibisPaint's size readout", has("10.0", exact = true) || has(com.brushwork.paint.ui.editor.SliderMath.formatSizeFixed(s.c.brush.size), exact = true))

        // ---- bottom bar: 7 slots of 56 × 50 from y 781
        val bar = s.tagged(ChromeTags.BOTTOM_BAR) ?: throw AssertionError("no bottom bar")
        near("bar top", hh - nav - 50f, bar.top)
        near("bar height", 50f, bar.height)
        val slots = listOf("Switch to eraser", "Tools (current: Brush)", "Open brush settings", "Open color picker", "Hide interface", "Open layers (active layer 2)", "Back to gallery")
        slots.forEachIndexed { i, label ->
            val b = s.clickable(label) ?: throw AssertionError("no \"$label\"; shown ${SmokeUi.shown().take(80)}")
            near("$label left", 56f * i, b.left)
            near("$label width", 56f, b.width)
            near("$label height", 50f, b.height)
            near("$label top", hh - nav - 50f, b.top)
        }

        // ---- the canvas fit: centred between the options strip (137) and the slider rows (701)
        val fitTop = st + 104f
        val fitBottom = hh - (nav + 130f)
        val centre = s.screen(200f, 150f)
        val cy = (centre.second - s.root.top) / s.density
        near("fitted canvas centre", (fitTop + fitBottom) / 2f, cy, 1.5f)
        val docTop = (s.screen(200f, 0f).second - s.root.top) / s.density
        val docBottom = (s.screen(200f, 300f).second - s.root.top) / s.density
        assertTrue("the canvas fits between the bands: $docTop..$docBottom in $fitTop..$fitBottom", docTop >= fitTop - 1f && docBottom <= fitBottom + 1f)
        Smoke.assertQuiet(s.c, "bands")
    }

    private fun slider(s: ChromeScreen, name: String): Rect = s.placed().last { e ->
        e.node.config.contains(SemanticsActions.SetProgress) &&
            e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(name) == true
    }.let { s.dp(it.bounds) }

    private fun toolMenu(h: ChromeHarness) {
        val s = h.editor()
        val (_, nav) = s.insetsDp()
        val hh = s.heightDp
        click("Tools (current: Brush)")
        SmokeUi.assertPanelShown("Tools")
        val menu = s.tagged(ChromeTags.TOOL_MENU) ?: throw AssertionError("no tool menu")
        near("menu left", 6f, menu.left)
        near("menu width", 150f, menu.width)
        near("menu height (25 cells scroll in 434)", 434f, menu.height)
        near("menu bottom: 16 above the bar", hh - nav - 50f - 16f, menu.bottom)
        val transform = s.clickable("Transform")!!
        near("cell width", 75f, transform.width)
        near("cell height", 52f, transform.height)
        near("first cell left", 6f, transform.left)
        near("first cell top", menu.top, transform.top)
        val wand = s.clickable("Magic wand")!!
        near("second column", 81f, wand.left)
        // The tools button shows the open state and closes it again.
        click("Tools (current: Brush)")
        assertNull("closed", s.tagged(ChromeTags.TOOL_MENU))
    }

    private fun layerWindow(h: ChromeHarness) {
        val s = h.editor()
        val (_, nav) = s.insetsDp()
        val hh = s.heightDp
        click("Open layers")
        val lw = s.tagged(ChromeTags.LAYER_WINDOW) ?: throw AssertionError("no layer window")
        near("window left", 5f, lw.left)
        near("window width", 382f, lw.width)
        near("window height", 520f, lw.height)
        near("window bottom on the bar", hh - nav - 50f, lw.bottom)
        assertTrue("it covers the slider rows", lw.top < hh - nav - 130f)
        assertTrue(has("Close layers", exact = true))
        assertTrue("the layers slot shows the open state", has("Close layers (active layer 2)"))
        click("Close layers", exact = true)
        assertNull(s.tagged(ChromeTags.LAYER_WINDOW))
    }

    private fun pendingAndHide(h: ChromeHarness) {
        val s = h.editor()
        val (st, nav) = s.insetsDp()
        val w = s.widthDp
        val hh = s.heightDp
        s.c.selectTool(ToolId.SHAPE)
        Smoke.pump(60)
        assertTrue((s.c.currentTool as ShapeTool).ensurePending())
        settle()
        // Centred, 8 dp above the slider rows.
        var bar = s.tagged(ChromeTags.PENDING_BAR) ?: throw AssertionError("no ✓ / ✕")
        near("✓ / ✕ centred", w / 2f, bar.center.x)
        near("✓ / ✕ above the slider rows", hh - nav - 130f - 8f, bar.bottom)
        near("44 dp buttons", 44f, bar.height)
        // Right-aligned while the tool menu is open.
        click("Tools (current: Shape)")
        bar = s.tagged(ChromeTags.PENDING_BAR)!!
        near("✓ / ✕ at the right with the menu", w - 8f, bar.right)
        val menu = s.tagged(ChromeTags.TOOL_MENU)!!
        assertTrue("not over the menu", bar.left > menu.right)
        click("Tools (current: Shape)")
        // Above the layer window's top-right corner (y ≈ 209–253 on the reference phone).
        click("Open layers")
        val lw = s.tagged(ChromeTags.LAYER_WINDOW)!!
        bar = s.tagged(ChromeTags.PENDING_BAR)!!
        near("✓ / ✕ right edge on the window's", lw.right, bar.right)
        near("✓ / ✕ 8 dp above the window", lw.top - 8f, bar.bottom)
        click("Close layers", exact = true)

        // Hide interface: the top row, strip, pill and slider rows fade; the bar and ✓ / ✕ stay.
        val centre0 = s.screen(200f, 150f)
        click("Hide interface")
        Smoke.pump(300)
        settle()
        assertNull("top row hidden", s.tagged(ChromeTags.TOP_ROW))
        assertNull("options strip hidden", s.tagged(ChromeTags.OPTIONS_STRIP))
        assertNull("slider rows hidden", s.tagged(ChromeTags.SLIDER_ROWS))
        assertNotNull("bottom bar stays", s.tagged(ChromeTags.BOTTOM_BAR))
        assertTrue("Show interface", has("Show interface", exact = true))
        assertFalse(has("Undo", exact = true))
        bar = s.tagged(ChromeTags.PENDING_BAR) ?: throw AssertionError("✓ / ✕ stay")
        near("✓ / ✕ above the bottom bar", hh - nav - 50f - 8f, bar.bottom)
        assertEquals("the canvas does not refit", centre0, s.screen(200f, 150f))
        click("Show interface")
        Smoke.pump(300)
        settle()
        assertNotNull(s.tagged(ChromeTags.TOP_ROW))
        assertNotNull(s.tagged(ChromeTags.SLIDER_ROWS))
        near("top row back", st, s.tagged(ChromeTags.TOP_ROW)!!.top)
        click("Apply shape edit")
        Smoke.assertQuiet(s.c, "pending and hide")
    }
}
