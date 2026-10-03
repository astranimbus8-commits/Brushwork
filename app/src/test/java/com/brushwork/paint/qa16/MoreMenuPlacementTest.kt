package com.brushwork.paint.qa16

import android.view.WindowManager
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The More menu drops UNDER the top row (§3.7.6: "a dark dropdown, top-left under the top row,
 * 280 dp wide, scrolling") and stops above the bottom bar: its ~17 entries are taller than the
 * room there, so it scrolls instead of growing over the circles (and over the bottom bar).
 * Before the fix the dropdown was as tall as the window and Material moved it up to the top
 * edge: it covered the top row (the More circle that opened it included) and the bottom bar.
 */
internal object MorePlacement {
    fun run(name: String) = Walk.run(name) { h ->
        val s = h.editor(IbisDocs.page())
        val top = s.tagged(ChromeTags.TOP_ROW) ?: throw AssertionError("no top row")
        val bar = s.tagged(ChromeTags.BOTTOM_BAR) ?: throw AssertionError("no bottom bar")
        Finger.tap(s, "More options")
        Smoke.pump(300); SmokeUi.settle()
        assertEquals("the More menu drops", 2, SmokeUi.windows().size)
        val (menuTop, menuBottom) = popupDp(s)
        assertTrue("$name: the More menu starts under the top row (menu $menuTop–$menuBottom dp, top row ends at ${top.bottom})", menuTop >= top.bottom - 0.5f)
        assertTrue("$name: the More menu ends above the bottom bar (menu $menuTop–$menuBottom dp, bar starts at ${bar.top})", menuBottom <= bar.top + 0.5f)
        // Its header and first entry show; the rest scroll.
        assertTrue("$name: the header shows", SmokeUi.has("Smoke · 300 × 430 px", exact = true))
        assertTrue("$name: the first entry shows", SmokeUi.has("Copy layer", exact = true))
        Finger.back()
        assertEquals(1, SmokeUi.windows().size)
        Smoke.assertQuiet(s.c, name)
    }

    /**
     * The menu's scrolling body (the dropdown's card, less its 8 dp padding): top and bottom, dp
     * from the editor's top. The popup's window may be the whole screen with the card placed
     * inside it, so the card is found by its scroll semantics, not by the window's size.
     */
    fun popupDp(s: ChromeScreen): Pair<Float, Float> {
        val popup = SmokeUi.windows().last()
        val lp = popup.layoutParams as WindowManager.LayoutParams
        val body = com.brushwork.paint.ui.color.RobolectricUi.elements().firstOrNull { e ->
            e.window === popup && e.node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange) != null
        } ?: throw AssertionError("the More menu has no scrolling body")
        val loc = IntArray(2)
        s.activity.window.decorView.getLocationOnScreen(loc)
        val offset = lp.y - loc[1] - s.root.top
        return ((body.bounds.top + offset) / s.density) to ((body.bounds.bottom + offset) / s.density)
    }

    /** The open menu's scrolling body. */
    private fun body(): com.brushwork.paint.ui.color.RobolectricUi.Element {
        val popup = SmokeUi.windows().last()
        return com.brushwork.paint.ui.color.RobolectricUi.elements().firstOrNull { e ->
            e.window === popup && e.node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange) != null
        } ?: throw AssertionError("the More menu has no scrolling body")
    }

    /** How far the open menu is scrolled, and how far it can be (px). */
    fun scroll(): Pair<Float, Float> {
        val r = body().node.config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
        return r.value() to r.maxValue()
    }

    /** A finger pushes the open menu's list up by half its height (a slow drag on its body). */
    fun pushUp() {
        val b = body().bounds
        val x = b.left + b.width / 2f
        com.brushwork.paint.ui.color.RobolectricUi.drag(body().window, x to b.top + b.height * 0.8f, x to b.top + b.height * 0.55f, x to b.top + b.height * 0.3f)
        Smoke.pump(300); SmokeUi.settle()
    }

    /** What shows (window px; empty when scrolled away) of the open menu's text [label]. */
    fun shown(label: String): androidx.compose.ui.geometry.Rect {
        val popup = SmokeUi.windows().last()
        return com.brushwork.paint.ui.color.RobolectricUi.elements().firstOrNull { e ->
            e.window === popup && e.node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.any { it.text == label } == true
        }?.bounds ?: throw AssertionError("no \"$label\" in the More menu")
    }
}

/**
 * Every opening of the More menu starts at its top: the document's name and size, then "Copy
 * layer" (ibisPaint's menus open at their first entry). Before the fix Material's dropdown kept the
 * scroll of the last opening: after an entry at the menu's end ("Settings", "Save now") the next
 * opening showed the end again, the header scrolled away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.moretopsandbox"])
class MoreMenuReopensAtTopTest {
    @Test
    fun everyOpeningStartsAtTheHeader() = Walk.run("More reopens at its top") { h ->
        val s = h.editor(IbisDocs.page())
        MoreWalk.open(s)
        assertEquals("the first opening is at the top", 0f, MorePlacement.scroll().first, 0.5f)
        var tries = 0
        while (MorePlacement.scroll().let { (v, max) -> v < max - 0.5f } && tries++ < 12) MorePlacement.pushUp()
        val (end, max) = MorePlacement.scroll()
        assertTrue("a finger scrolled the menu to its end: $end of $max px", max > 0f && end >= max - 0.5f)
        assertTrue("the header scrolled away", MorePlacement.shown("Smoke · 300 × 430 px").height < 1f)
        Finger.back()
        assertEquals("Back closed the menu", 1, SmokeUi.windows().size)

        MoreWalk.open(s)
        assertEquals("the menu reopens at its top", 0f, MorePlacement.scroll().first, 0.5f)
        val header = MorePlacement.shown("Smoke · 300 × 430 px")
        assertTrue("the header shows: $header", header.height >= 10f * s.density)
        assertTrue("\"Copy layer\" shows", MorePlacement.shown("Copy layer").height >= 10f * s.density)
        Finger.back()
        assertEquals(1, SmokeUi.windows().size)
        Smoke.assertQuiet(s.c, "More reopens at its top")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.moreplace392sandbox"])
class MoreMenuPlacementTest {
    @Test
    fun theMoreMenuDropsUnderTheTopRow() = MorePlacement.run("392 dp")
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.moreplace360sandbox"])
class MoreMenuPlacementNarrowTest {
    @Test
    fun theMoreMenuDropsUnderTheTopRowAt360dp() = MorePlacement.run("360 dp")
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w873dp-h392dp-land-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.moreplacelandsandbox"])
class MoreMenuPlacementLandscapeTest {
    @Test
    fun theMoreMenuDropsUnderTheTopRowInLandscape() = MorePlacement.run("landscape")
}
