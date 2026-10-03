package com.brushwork.paint.ui.editor.chrome

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.ShapeTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * The user's phone turned sideways (873 × 392 dp): the short-screen rules of §3.7.2 / §3.7.7 —
 * the top row left-aligned at the 48 dp pitch, the brush sliders side by side in ONE 40 dp row
 * (wide screens, v1.5's rule: the canvas keeps twice the height it would have under two rows),
 * the bottom bar's 7 slots of 56 × 50, the tool menu under the top row and 16 dp above the bar,
 * the v1.5 side window instead of ibisPaint's 382 × 520 one (the screen is under 480 dp tall) with
 * the ✓ / ✕ stacked at the free left edge, and every chrome target finger-sized and uniquely named.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w873dp-h392dp-land-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.editor.chrome.ibislayoutlandscapesandbox"])
class IbisLayoutMetricsLandscapeTest {

    private fun near(what: String, expected: Float, actual: Float, tol: Float = 1f) =
        assertEquals("$what: expected $expected dp, got $actual", expected, actual, tol)

    @Test
    fun aPhoneInLandscapeKeepsTheChromeUsable() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = ChromeHarness()
        h.section("873 × 392 dp") { landscape(h) }
        dog.interrupt()
        h.finish()
    }

    private fun landscape(h: ChromeHarness) {
        val s = h.editor()
        val (st, nav) = s.insetsDp()
        val w = s.widthDp
        val hh = s.heightDp
        near("screen width", 873f, w)
        near("screen height", 392f, hh)

        // ---- top row: all 8 circles, left-aligned at the 48 dp pitch, the 8 dp gap after Redo (v16 polish).
        listOf("Undo", "Redo", "Vector", "Selection", "Stabilizer", "Grid", "Ruler", "More options").forEachIndexed { i, label ->
            val b = s.clickable(label) ?: throw AssertionError("no \"$label\"")
            near("$label centre x", 24f + 48f * i + (if (i >= 2) 8f else 0f), b.center.x)
            near("$label touch width", 48f, b.width)
            near("$label top", st, b.top)
        }

        // ---- one slider row: size and opacity side by side, 40 dp tall, on the bottom bar.
        assertEquals(1, ChromeLayout.sliderRowCount(w))
        val rowTop = hh - nav - 50f - 40f
        val rows = s.tagged(ChromeTags.SLIDER_ROWS) ?: throw AssertionError("no slider rows")
        near("slider row top", rowTop, rows.top)
        near("one 40 dp row", 40f, rows.height)
        val size = slider(s, "Brush size")
        val opacity = slider(s, "Brush opacity")
        near("size track on the row", rowTop, size.top)
        near("opacity track on the same row", rowTop, opacity.top)
        assertTrue("size left of opacity: $size / $opacity", size.right < opacity.left)
        near("size track starts as on a phone", 92f, size.left)
        val half = (w - 12f) / 2f
        near("the opacity half starts after the gap", half + 12f + 92f, opacity.left)
        near("the opacity track ends 37 dp from the edge", w - 37f, opacity.right)
        for (label in listOf("Smaller brush", "Bigger brush", "Less opacity", "More opacity", "Type brush size", "Type brush opacity")) {
            val b = s.clickable(label) ?: throw AssertionError("no \"$label\"")
            near("$label on the row", rowTop, b.top)
        }

        // ---- the bottom bar: 7 slots of 56 × 50 in ibisPaint's order, spread over the width.
        var lastRight = -1f
        listOf("Switch to eraser", "Tools (current: Brush)", "Open brush settings", "Open color picker", "Hide interface", "Open layers (active layer 2)", "Back to gallery").forEach { label ->
            val b = s.clickable(label) ?: throw AssertionError("no \"$label\"")
            near("$label width", 56f, b.width)
            near("$label height", 50f, b.height)
            near("$label top", hh - nav - 50f, b.top)
            assertTrue("$label after the previous slot", b.left >= lastRight - 0.5f)
            lastRight = b.right
        }

        // ---- the canvas fit: between the options strip (137 from the top) and the one slider row.
        val fitTop = st + 104f
        val fitBottom = hh - (nav + 90f)
        val docTop = (s.screen(200f, 0f).second - s.root.top) / s.density
        val docBottom = (s.screen(200f, 300f).second - s.root.top) / s.density
        assertTrue("the canvas fits between the bands: $docTop..$docBottom in $fitTop..$fitBottom", docTop >= fitTop - 1f && docBottom <= fitBottom + 1f)
        // Taller than the whole area two rows would leave (the fit keeps a small margin).
        assertTrue("it uses the height a single row leaves: ${docBottom - docTop} in ${fitBottom - fitTop}", docBottom - docTop > (fitBottom - fitTop) - 40f)

        // ---- every chrome target finger-sized, every label once.
        val main = Clickables.onScreen(s)
        val strip = s.tagged(ChromeTags.OPTIONS_STRIP)
        val small = main.filter { strip == null || !strip.contains(it.bounds.center) }.filter { it.width < 39.5f || it.height < 39.5f }
        assertTrue("clickables under 40 dp: ${small.map { "${it.labels} ${it.width} × ${it.height}" }}", small.isEmpty())
        val dups = Clickables.duplicates(main).filterKeys { !it.matches(Regex("""^[-+]?[\d.,\s]+(%|px|°| px| %)?$""")) }
        assertTrue("labels shared by several clickables: $dups", dups.isEmpty())

        // ---- the tool menu: 150 wide at x 6, 16 above the bar, never over the top row (it scrolls).
        click("Tools (current: Brush)")
        val menu = s.tagged(ChromeTags.TOOL_MENU) ?: throw AssertionError("no tool menu")
        near("menu left", 6f, menu.left)
        near("menu width", 150f, menu.width)
        near("menu bottom", hh - nav - 50f - 16f, menu.bottom)
        assertTrue("the menu stays under the top row: $menu", menu.top >= st + 48f - 0.5f)
        val wand = s.clickable("Magic wand")!!
        near("two columns", 81f, wand.left)
        click("Tools (current: Brush)")
        assertNull(s.tagged(ChromeTags.TOOL_MENU))

        // ---- the layer window: the v1.5 side window (the screen is under 480 dp tall), inside the
        // screen between the top row and the slider row, with the ✓ / ✕ stacked at the left edge.
        assertTrue(ChromeLayout.layerWindow(w, hh, st, nav).short)
        s.c.selectTool(ToolId.SHAPE)
        Smoke.pump(60)
        assertTrue((s.c.currentTool as ShapeTool).ensurePending())
        settle()
        click("Open layers")
        val lw = s.tagged(ChromeTags.LAYER_WINDOW) ?: throw AssertionError("no layer window")
        assertTrue("inside the screen: $lw", lw.left >= 0f && lw.right <= w + 0.5f)
        near("at the right edge", w - 8f, lw.right)
        assertTrue("under the top row: $lw", lw.top >= st + 48f - 0.5f)
        assertTrue("above the slider row: $lw", lw.bottom <= rowTop + 0.5f)
        val bar = s.tagged(ChromeTags.PENDING_BAR) ?: throw AssertionError("no ✓ / ✕")
        assertTrue("✓ / ✕ stacked: $bar", bar.height > bar.width)
        assertTrue("✓ / ✕ at the left edge, beside the window: $bar", bar.left < 20f && bar.right < lw.left)
        assertTrue("✓ / ✕ on screen, under the top row: $bar", bar.top >= st + 48f - 0.5f && bar.bottom <= rowTop)
        click("Close layers", exact = true)
        assertNull(s.tagged(ChromeTags.LAYER_WINDOW))
        click("Discard shape edit")
        Smoke.assertQuiet(s.c, "landscape")
    }

    private fun slider(s: ChromeScreen, name: String) = s.placed().last { e ->
        e.node.config.contains(SemanticsActions.SetProgress) &&
            e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(name) == true
    }.let { s.dp(it.bounds) }
}
