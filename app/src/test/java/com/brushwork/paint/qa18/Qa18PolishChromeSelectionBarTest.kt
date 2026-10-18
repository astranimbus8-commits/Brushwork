package com.brushwork.paint.qa18

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.remove.ContentAwareFillJob
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.LayerLabels19
import com.brushwork.paint.ui.common.SavedSelectionLabels
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.editor.chrome.Clickables
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
import kotlin.math.pow

/**
 * v1.8 polish (request items 8 and 9, QA: the selection bar's ten items, eleven in v1.9, scroll sideways with no
 * cue). On the user's phone (392 dp) and on a 360 dp phone, over a dark and a white picture
 * zoomed under the bar, with a selection made by a finger: a chevron cue shows at the edge with
 * items past it (the end at first, the start once a finger slid the bar to its end, the end
 * again once slid back), and only there; a slide reaches all eleven items; the cue lies clear of
 * every whole item's label; its chevron stands out from the rest of the cue on both pictures
 * (at least 4.5:1); a finger on it reaches the item under it. A bar whose items fit (Paste,
 * More, Hide) shows no cue. Rendered: `polish-chrome-selection-bar-<width>-<dark|white>[-end].png`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.polishchromeselectionbarsandbox"])
class Qa18PolishChromeSelectionBarTest {

    @Test
    fun theSelectionBarShowsWhereItScrolls() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 600_000)
        val h = ChromeHarness()
        Qa18PolishChromeKit.atEachWidth(h, "the selection bar") { dp ->
            for (dark in listOf(true, false)) bar(h, dp, dark)
        }
        dog.interrupt()
        h.finish()
    }

    private fun cue(s: ChromeScreen, end: Boolean): Rect? =
        s.tagged(if (end) ChromeTags.SELECTION_BAR_CUE_END else ChromeTags.SELECTION_BAR_CUE_START)

    private fun bar(h: ChromeHarness, dp: Int, dark: Boolean) {
        val s = h.editor(Qa18MenusShots.doc(vector = false, dark = dark))
        assertEquals("$dp dp wide", dp.toFloat(), s.widthDp, 1f)
        val tone = if (dark) "dark" else "white"
        val what = "the selection bar at $dp dp over a $tone picture"
        Qa18MenusShots.pick(s, ToolId.MARQUEE)
        // v1.9 (CI flake, shard 0, first editor at 360 dp): the tool menu could still be closing
        // when the finger came down, and the stroke went to it. Wait for it to go; retry once.
        menuGone(s)
        Fingers18.stroke(s, 100f, 80f, 260f, 210f)
        if (s.c.selection == null) {
            System.err.println("[polish-chrome] $what: no selection after the first stroke (tool ${s.c.activeToolId}, windows ${SmokeUi.windows().size}, tool menu ${s.tagged(ChromeTags.TOOL_MENU)}); again")
            // A tool menu still open (a second tap on its button opened it again) closes by Back.
            if (s.tagged(ChromeTags.TOOL_MENU) != null) {
                Finger.back()
                Smoke.pump(300); SmokeUi.settle()
            }
            menuGone(s)
            // The stroke may have gone to the menu and picked another tool: the marquee again.
            if (s.c.activeToolId != ToolId.MARQUEE) {
                Qa18MenusShots.pick(s, ToolId.MARQUEE)
                menuGone(s)
            }
            Fingers18.stroke(s, 100f, 80f, 260f, 210f)
        }
        assertNotNull("$what: a selection", s.c.selection)
        // Zoomed in by two fingers: the picture lies under the bar.
        Fingers18.pinch(s, 200f, 150f)
        Fingers18.pinch(s, 200f, 150f)
        assertNotNull("$what: the selection kept by the pinches", s.c.selection)
        val bar = s.tagged(ChromeTags.SELECTION_BAR) ?: throw AssertionError("$what: no bar")
        val (px, py) = Finger.px(s, bar.right - 7f, bar.center.y)
        val (dx, dy) = s.doc(px, py)
        assertTrue("$what: the picture under the bar's end ($dx, $dy)", dx in 0f..400f && dy in 0f..300f)
        val seen = labels(s, bar)

        // At first: the end cue, no start cue.
        val end = cue(s, end = true) ?: throw AssertionError("$what: no cue at the end")
        assertNull("$what: no cue at the start before a slide", cue(s, end = false))
        assertEquals("$what: the cue at the bar's end", bar.right, end.right, 1f)
        assertEquals("$what: the cue as tall as the bar", bar.height, end.height, 1f)
        clearOfWholeItems(s, bar, end, what)
        legible(s, end, what)
        Qa18PolishChromeKit.shot(s, "selection-bar-$dp-$tone", heightDp = 200f)

        // A finger on the cue reaches the item under it (the bar's "More": the selection menu).
        Finger.tapAt(s, end.center.x, end.center.y)
        Smoke.pump(300); SmokeUi.settle()
        assertTrue("$what: a tap on the cue opens the selection menu under it: ${SmokeUi.sheetTitles()}", "Selection" in SmokeUi.sheetTitles())
        Finger.back()
        Smoke.pump(300); SmokeUi.settle()
        assertFalse("$what: Back closes the menu", "Selection" in SmokeUi.sheetTitles())

        // Slid to its end by a finger: the start cue, no end cue.
        Finger.slowDrag(s, (bar.right - 24f) to bar.center.y, (bar.left + 24f) to bar.center.y)
        Smoke.pump(300); SmokeUi.settle()
        val start = cue(s, end = false) ?: throw AssertionError("$what: no cue at the start after a slide")
        assertNull("$what: no cue at the end once slid to it", cue(s, end = true))
        assertEquals("$what: the cue at the bar's start", bar.left, start.left, 1f)
        clearOfWholeItems(s, bar, start, "$what, slid")
        legible(s, start, "$what, slid")
        Qa18PolishChromeKit.shot(s, "selection-bar-$dp-$tone-end", heightDp = 200f)
        seen += labels(s, bar)
        val missing = ITEMS.filter { it !in seen }
        assertTrue("$what: all eleven items reached by a slide; missing $missing", missing.isEmpty())

        // Slid back: the end cue again, no start cue.
        Finger.slowDrag(s, (bar.left + 24f) to bar.center.y, (bar.right - 24f) to bar.center.y)
        Smoke.pump(300); SmokeUi.settle()
        assertNotNull("$what: the end cue again once slid back", cue(s, end = true))
        assertNull("$what: no start cue once slid back", cue(s, end = false))

        // Copied, then deselected: Paste, More and Hide fit; no cue.
        Finger.tap(s, "Copy selection")
        Finger.tap(s, "Clear the selection")
        Smoke.pump(300); SmokeUi.settle()
        assertNull("$what: deselected", s.c.selection)
        // The cue follows the bar's width a frame after the bar is measured. Under Robolectric the
        // shrunk bar is measured only when asked (reading its bounds); then one more frame.
        s.tagged(ChromeTags.SELECTION_BAR)
        Smoke.pump(50); SmokeUi.settle()
        val paste = s.tagged(ChromeTags.SELECTION_BAR) ?: throw AssertionError("$what: no paste bar")
        val shown = Clickables.onScreen(s, inside = paste).map { "${it.own.firstOrNull()} ${it.bounds}" }
        assertEquals("$what: the paste bar's three items: $shown", 3, shown.size)
        assertNull("$what: the paste bar $paste fits $shown: no end cue", cue(s, end = true))
        assertNull("$what: the paste bar $paste fits $shown: no start cue", cue(s, end = false))
        Qa18PolishChromeKit.shot(s, "selection-bar-$dp-$tone-paste", heightDp = 200f)
        Smoke.assertQuiet(s.c, what)
    }

    /** Until the tool menu (its window too) has gone, at most 2 s. */
    private fun menuGone(s: ChromeScreen) {
        repeat(20) {
            if (SmokeUi.windows().size <= 1 && s.tagged(ChromeTags.TOOL_MENU) == null) return
            Smoke.pump(100); SmokeUi.settle()
        }
    }

    /** Everything the bar's items on screen are known by. */
    private fun labels(s: ChromeScreen, bar: Rect): MutableSet<String> =
        Clickables.onScreen(s, inside = bar).flatMap { it.labels }.toMutableSet()

    /** No label of an item wholly in view lies under [cue] (its icon is narrower and centered above it). */
    private fun clearOfWholeItems(s: ChromeScreen, bar: Rect, cue: Rect, what: String) {
        val whole = Clickables.onScreen(s, inside = bar).filter { it.bounds.width >= it.width - 0.5f }
        assertTrue("$what: whole items", whole.size >= 6)
        for (item in whole) {
            for (part in parts(item.node)) {
                val r = s.dp(part.boundsInWindow)
                val overlap = minOf(r.right, cue.right) - maxOf(r.left, cue.left)
                System.err.println("[polish-chrome] $what: \"${part.config.getOrNull(SemanticsProperties.Text)?.joinToString()}\" at ${"%.1f".format(r.left)}..${"%.1f".format(r.right)}, the cue ${"%.1f".format(cue.left)}..${"%.1f".format(cue.right)}")
                assertTrue("$what: the cue $cue covers \"${item.own.firstOrNull()}\" ($r)", overlap <= 0.5f)
            }
        }
    }

    /** The text nodes under [n]. */
    private fun parts(n: SemanticsNode): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(x: SemanticsNode) {
            if (x.config.getOrNull(SemanticsProperties.Text) != null) out += x
            x.children.forEach(::walk)
        }
        walk(n)
        return out
    }

    /**
     * The chevron (the brightest pixel of the cue's middle 12 dp, where the glyph is, away from
     * the bar's rounded corners) against what most of the cue shows (its median pixel: the fade
     * over the bar): at least 4.5:1.
     */
    private fun legible(s: ChromeScreen, cue: Rect, what: String) {
        val shot: Bitmap = Qa18MenusShots.capture()
        val (l, t) = Finger.px(s, cue.left, cue.top)
        val (r, b) = Finger.px(s, cue.right, cue.bottom)
        val (_, gt) = Finger.px(s, cue.left, cue.center.y - 6f)
        val (_, gb) = Finger.px(s, cue.left, cue.center.y + 6f)
        val lum = ArrayList<Double>()
        var glyph = 0.0
        for (y in t.toInt() until b.toInt().coerceAtMost(shot.height)) {
            for (x in l.toInt() until r.toInt().coerceAtMost(shot.width)) {
                val v = luminance(shot.getPixel(x, y))
                lum += v
                if (y >= gt.toInt() && y < gb.toInt()) glyph = maxOf(glyph, v)
            }
        }
        lum.sort()
        val around = lum[lum.size / 2]
        val ratio = (glyph + 0.05) / (around + 0.05)
        System.err.println("[polish-chrome] $what: the cue's chevron ${"%.3f".format(glyph)} on ${"%.3f".format(around)}: ${"%.1f".format(ratio)}:1")
        assertTrue("$what: the chevron stands out (${"%.1f".format(ratio)}:1)", ratio >= 4.5)
    }

    private fun luminance(c: Int): Double {
        fun ch(v: Int): Double = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch((c shr 16) and 0xFF) + 0.7152 * ch((c shr 8) and 0xFF) + 0.0722 * ch(c and 0xFF)
    }

    internal companion object {
        /** What the bar's eleven items do (their click labels; v1.9 adds [LayerLabels19.DUPLICATE_DESC]). */
        val ITEMS = listOf(
            "Copy selection", "Cut selection", "Paste as a new layer", "Clear the selection", "Delete the selected pixels",
            ContentAwareFillJob.FILL_LABEL, "Invert the selection", "Selection menu", ArrayLabels.FROM_SELECTION, SavedSelectionLabels.SAVE,
            LayerLabels19.DUPLICATE_DESC,
        )
    }
}
