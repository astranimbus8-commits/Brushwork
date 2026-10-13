package com.brushwork.paint.qa18

import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.geometry.Rect
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.select.colorrange.ColorSelectTool
import com.brushwork.paint.tools.select.magnetic.MagneticLassoTool
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.ui.common.MagneticLabels18
import com.brushwork.paint.ui.common.SavedSelectionLabels
import com.brushwork.paint.ui.common.StripLabels18
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.ToolMenu
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.editor.chrome.Clickables
import com.brushwork.paint.ui.placement.SNAP_LABEL
import com.brushwork.paint.ui.placement.TextStripItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 polish (top-menu clarity), the verifier's flows around each change that the fixer's
 * classes do not drive:
 * - the selection bar's cue (item 5) slid part way (both cues at once), deselected while slid,
 *   then Undo / Redo by the top row while slid to its end: each cue shows exactly while its first
 *   / last item is not whole, on a dark picture at 392 dp and a white one at 360 dp; a finger held
 *   on a cue shows the bubble of the item under it and fires nothing;
 * - the long-press bubbles (item 1) on every tool's strip at 360 dp (the strips lay out
 *   differently there), and on the Magnetic lasso with three points and Color select with a
 *   sample (icons shown only then); the lasso's ✕ still drops the points after the census;
 * - the Text strip's More row (item 4) kept open while the open text is applied by the top row's
 *   ✓, then Undo / Redo: "Numbers" goes with the text, nothing blank is left after Snap, and Snap
 *   is back where it was each time. v1.9 (R3): the row's lines are end-aligned under the toggle,
 *   so while a text is open "Numbers" ends Snap's line and Snap sits that much further left on it.
 * Rendered: `polish-chrome-verify-selection-bar-middle-360.png`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.polishchromeverifysandbox"])
class Qa18PolishChromeVerifyTest {

    private val narrow = Qa18PolishChromeKit.WIDTHS.last()

    @Test
    fun theFixesHoldAroundTheirFlows() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 1_200_000)
        val h = ChromeHarness()
        h.section("the selection bar slid, deselected, undone and redone at 392 dp (dark)") { selectionBar(h, 392, dark = true) }
        at(narrow.second) { h.section("the selection bar slid, deselected, undone and redone at 360 dp (white)") { selectionBar(h, 360, dark = false) } }
        at(narrow.second) { h.section("every tool's bubbles at 360 dp") { everyTool(h) } }
        h.section("the Magnetic lasso with three points") { magnetic(h) }
        h.section("Color select with a sample") { colorSelect(h) }
        at(narrow.second) { h.section("the Text More row through apply, undo and redo at 360 dp") { text(h) } }
        dog.interrupt()
        h.finish()
    }

    private fun at(qualifiers: String, block: () -> Unit) {
        try {
            RuntimeEnvironment.setQualifiers(qualifiers)
            block()
        } finally {
            RuntimeEnvironment.setQualifiers(Qa18PolishChromeKit.WIDTHS.first().second)
        }
    }

    // ------------------------------------------------------------------ item 5: the selection bar's cue

    private fun cue(s: ChromeScreen, end: Boolean): Rect? =
        s.tagged(if (end) ChromeTags.SELECTION_BAR_CUE_END else ChromeTags.SELECTION_BAR_CUE_START)

    /** The bar, measured, and one more frame (the cue follows the bar's width a frame later under Robolectric). */
    private fun bar(s: ChromeScreen): Rect? {
        s.tagged(ChromeTags.SELECTION_BAR)
        Smoke.pump(50); SmokeUi.settle()
        return s.tagged(ChromeTags.SELECTION_BAR)?.takeIf { it.width > 0f && it.height > 0f }
    }

    private fun whole(item: Clickables.Item): Boolean = item.bounds.width >= item.width - 0.5f

    /** Each cue shows exactly while its end's item is not whole. Returns the bar's items. */
    private fun cuesMatch(s: ChromeScreen, what: String, first: String, last: String): List<Clickables.Item> {
        val bar = bar(s) ?: throw AssertionError("$what: no bar")
        val items = Clickables.onScreen(s, inside = bar)
        val head = items.firstOrNull { first in it.labels }
        val tail = items.firstOrNull { last in it.labels }
        val startHidden = head == null || !whole(head)
        val endHidden = tail == null || !whole(tail)
        val shown = items.map { "${it.labels.firstOrNull()} ${it.bounds.left.toInt()}..${it.bounds.right.toInt()}" }
        assertEquals("$what: a start cue exactly while \"$first\" is not whole: $shown", startHidden, cue(s, end = false) != null)
        assertEquals("$what: an end cue exactly while \"$last\" is not whole: $shown", endHidden, cue(s, end = true) != null)
        return items
    }

    private fun selectionBar(h: ChromeHarness, dp: Int, dark: Boolean) {
        val s = h.editor(Qa18MenusShots.doc(vector = false, dark = dark))
        assertEquals("$dp dp wide", dp.toFloat(), s.widthDp, 1f)
        val what = "the selection bar at $dp dp over a ${if (dark) "dark" else "white"} picture"
        Qa18MenusShots.pick(s, ToolId.MARQUEE)
        Fingers18.stroke(s, 100f, 80f, 260f, 210f)
        val made = s.c.selection ?: throw AssertionError("$what: no selection")
        val madeBounds = made.bounds
        Finger.tap(s, "Copy selection")
        Smoke.pump(300); SmokeUi.settle()
        assertNotNull("$what: copied", s.c.clipboard)
        val steps0 = s.c.undoManager.undoCount
        cuesMatch(s, "$what, at first", FIRST, LAST)
        assertNull("$what: no start cue at first", cue(s, end = false))

        // Slid part way by a finger (the touch slop, then half of what shows past the end): both
        // cues; Deselect still whole.
        val bar = bar(s)!!
        val past = Clickables.onScreen(s, inside = bar).maxOf { it.bounds.left + it.width } - bar.right
        assertTrue("$what: items past the end ($past dp)", past > 8f)
        val half = (SLOP_DP + past / 2f) / 2f
        Finger.slowDrag(s, (bar.center.x + half) to bar.center.y, (bar.center.x - half) to bar.center.y)
        Smoke.pump(300); SmokeUi.settle()
        val middle = cuesMatch(s, "$what, slid part way", FIRST, LAST)
        assertNotNull("$what, slid part way: a start cue", cue(s, end = false))
        assertNotNull("$what, slid part way: an end cue", cue(s, end = true))
        if (dp == 360) Qa18PolishChromeKit.shot(s, "verify-selection-bar-middle-$dp", heightDp = 200f)
        val deselect = middle.firstOrNull { DESELECT in it.labels && whole(it) }
            ?: throw AssertionError("$what, slid part way: \"$DESELECT\" not whole: ${middle.map { "${it.labels.firstOrNull()} ${it.bounds}" }}")

        // A finger held on each cue: the bubble of the item under it, nothing fired.
        for (end in listOf(false, true)) {
            val at = cue(s, end) ?: throw AssertionError("$what: no cue (end $end)")
            val under = Clickables.onScreen(s, inside = bar).firstOrNull { it.bounds.contains(at.center) }
                ?: throw AssertionError("$what: no item under the cue (end $end)")
            val before = Qa18PolishChromeBubbles.snap(s)
            var held: String? = null
            Fingers18.longPressAt(s, at) { held = Fingers18.bubble() }
            assertTrue("$what: a finger held on the cue (end $end) shows ${under.labels}'s bubble: \"$held\"", held != null && held in under.labels)
            val after = Qa18PolishChromeBubbles.snap(s)
            assertEquals("$what: the held cue (end $end) changed ${Qa18PolishChromeBubbles.diff(before, after)}", before, after)
        }

        // Deselected while slid: the paste bar (Paste, More, Hide), no cue.
        Finger.tapAt(s, deselect.bounds.center.x, deselect.bounds.center.y)
        Smoke.pump(300); SmokeUi.settle()
        assertNull("$what: deselected", s.c.selection)
        assertEquals("$what: Deselect is one step", steps0 + 1, s.c.undoManager.undoCount)
        val paste = bar(s) ?: throw AssertionError("$what: no paste bar")
        assertEquals("$what: the paste bar's three items", 3, Clickables.onScreen(s, inside = paste).size)
        assertNull("$what: the paste bar has no end cue", cue(s, end = true))
        assertNull("$what: the paste bar has no start cue", cue(s, end = false))

        // Undo by the top row: the selection back, exactly; the cues match the bar.
        Finger.tap(s, "Undo")
        Smoke.pump(300); SmokeUi.settle()
        assertEquals("$what: Undo brings the selection back", madeBounds, s.c.selection?.bounds)
        assertEquals("$what: one step back", steps0, s.c.undoManager.undoCount)
        cuesMatch(s, "$what, Deselect undone", FIRST, LAST)

        // Slid to its end, then Undo takes the selection itself back: the paste bar, no cue.
        val full = bar(s)!!
        Finger.slowDrag(s, (full.right - 24f) to full.center.y, (full.left + 24f) to full.center.y)
        Smoke.pump(300); SmokeUi.settle()
        cuesMatch(s, "$what, slid to its end", FIRST, LAST)
        assertNotNull("$what, slid to its end: a start cue", cue(s, end = false))
        assertNull("$what, slid to its end: no end cue", cue(s, end = true))
        Finger.tap(s, "Undo")
        Smoke.pump(300); SmokeUi.settle()
        assertNull("$what: Undo takes the selection back", s.c.selection)
        assertNotNull("$what: the paste bar", bar(s))
        assertNull("$what: the paste bar, after Undo, has no end cue", cue(s, end = true))
        assertNull("$what: the paste bar, after Undo, has no start cue", cue(s, end = false))

        // Redo twice: the selection, the cues matching; then deselected again, no cue.
        Finger.tap(s, "Redo")
        Smoke.pump(300); SmokeUi.settle()
        assertEquals("$what: Redo brings the selection", madeBounds, s.c.selection?.bounds)
        cuesMatch(s, "$what, redone", FIRST, LAST)
        Finger.tap(s, "Redo")
        Smoke.pump(300); SmokeUi.settle()
        assertNull("$what: Redo deselects again", s.c.selection)
        assertNotNull("$what: the paste bar again", bar(s))
        assertNull("$what: the paste bar, redone, has no end cue", cue(s, end = true))
        assertNull("$what: the paste bar, redone, has no start cue", cue(s, end = false))
        Smoke.assertQuiet(s.c, what)
    }

    // ------------------------------------------------------------------ item 1: the long-press bubbles

    private fun problems(held: List<Qa18PolishChromeBubbles.Held>, what: String) {
        val p = held.flatMap { it.problems }
        p.forEach { System.err.println("[polish-chrome] PROBLEM $it") }
        assertTrue("$what: every icon-only control shows its name and does nothing else:\n" + p.joinToString("\n"), p.isEmpty())
    }

    private fun everyTool(h: ChromeHarness) {
        val s = h.editor(Qa18MenusShots.doc(vector = false))
        assertEquals("360 dp wide", 360f, s.widthDp, 1f)
        val seen = linkedSetOf<String>()
        val held = mutableListOf<Qa18PolishChromeBubbles.Held>()
        for (id in ToolMenu.tools) {
            s.c.selectTool(id)
            Smoke.pump(300); SmokeUi.settle()
            repeat(3) { if (SmokeUi.windows().size > 1) Finger.back() }
            if (s.c.activeToolId != id) continue
            held += Qa18PolishChromeBubbles.census(s, "${id.label} at 360 dp", seen)
        }
        System.err.println("[polish-chrome] verify: held ${held.size} icon-only controls at 360 dp")
        assertTrue("controls held at 360 dp: ${held.size}", held.size >= 40)
        problems(held, "every tool at 360 dp")
        Smoke.assertQuiet(s.c, "every tool at 360 dp")
    }

    private fun magnetic(h: ChromeHarness) {
        val s = h.editor(Qa18MenusShots.doc(vector = false))
        Qa18MenusShots.pick(s, ToolId.MAGNETIC_LASSO)
        val lasso = s.c.currentTool as MagneticLassoTool
        assertTrue("the edges are found", Smoke.pumpUntil(30_000) { SmokeUi.settle(1); lasso.edgeMapReady && lasso.idle })
        for ((x, y) in listOf(100f to 80f, 300f to 90f, 250f to 220f)) {
            Fingers18.tap(s, x, y)
            assertTrue(Smoke.pumpUntil(30_000) { SmokeUi.settle(1); lasso.idle })
        }
        assertEquals("three points by three taps", 3, lasso.anchorCount)
        val steps = s.c.undoManager.undoCount
        val held = Qa18PolishChromeBubbles.census(s, "Magnetic lasso, three points", linkedSetOf())
        val names = held.map { it.name }
        assertTrue("the outline's ✕ and ✓ held: $names", MagneticLabels18.DISCARD in names && MagneticLabels18.CLOSE in names)
        problems(held, "Magnetic lasso")
        assertEquals("the points kept", 3, lasso.anchorCount)
        // The ✕ still acts: it drops the points, recording nothing.
        Finger.tap(s, MagneticLabels18.DISCARD)
        assertTrue(Smoke.pumpUntil(30_000) { SmokeUi.settle(1); lasso.idle })
        assertEquals("✕ drops the points", 0, lasso.anchorCount)
        assertEquals("and records nothing", steps, s.c.undoManager.undoCount)
        Smoke.assertQuiet(s.c, "Magnetic lasso")
    }

    private fun colorSelect(h: ChromeHarness) {
        // A red block on the active layer (Color select samples the active layer), over a dark picture.
        val s = h.editor(Qa18MenusShots.doc(vector = false, dark = true)) { c ->
            check(c.editWholeLayer(c.doc.layers[1], "Seed") { b -> Canvas(b).drawRect(20f, 20f, 140f, 120f, Paint().apply { color = 0xFFCC2222.toInt() }) })
        }
        Qa18MenusShots.pick(s, ToolId.COLOR_SELECT)
        val range = s.c.currentTool as ColorSelectTool
        assertTrue("the preview is ready", Smoke.pumpUntil(30_000) { SmokeUi.settle(1); range.proxyReady && range.idle && range.preview != null })
        val v0 = range.preview!!.version
        Fingers18.tap(s, 60f, 60f)
        assertTrue("the tap samples", Smoke.pumpUntil(30_000) { SmokeUi.settle(1); range.idle && range.preview!!.version > v0 })
        val samples = range.sampleColors
        assertEquals("one sample", 1, samples.size)
        val held = Qa18PolishChromeBubbles.census(s, "Color select, one sample", linkedSetOf())
        assertTrue("controls held: ${held.map { it.name }}", held.isNotEmpty())
        problems(held, "Color select")
        assertEquals("the sample kept", samples, range.sampleColors)
        Smoke.assertQuiet(s.c, "Color select")
    }

    // ------------------------------------------------------------------ item 4: the Text strip's More row

    /** The More row's controls (name, bounds) left to right, line by line; opened by the toggle when closed. */
    private fun moreRow(s: ChromeScreen): List<Pair<String, Rect>> {
        if (Fingers18.state(s, StripLabels18.MORE_TOGGLE) != StripLabels18.EXPANDED) {
            Finger.tap(s, StripLabels18.MORE_TOGGLE)
            Smoke.pump(300); SmokeUi.settle()
        }
        val more = s.tagged(V18Tags.STRIP_MORE_ROW) ?: throw AssertionError("no More row")
        return Clickables.onScreen(s, inside = more)
            .sortedWith(compareBy({ (it.bounds.top / 8f).toInt() }, { it.bounds.left }))
            .map { (it.own.firstOrNull() ?: it.labels.firstOrNull() ?: "?") to it.bounds }
    }

    /**
     * No text open: Snap ends the row, at [snap] (when known), and its line ends at the row's end
     * (v1.9, R3: each line is as wide as its items, end-aligned under the toggle).
     */
    private fun closedRow(s: ChromeScreen, what: String, snap: Rect?): Rect {
        val row = moreRow(s)
        val names = row.map { it.first }
        assertEquals("$what: Snap ends the More row: $names", SNAP_LABEL, names.last())
        assertFalse("$what: no Numbers: $names", TextStripItems.NUMBERS_LABEL in names)
        val at = row.last().second
        if (snap != null) assertEquals("$what: Snap has not moved", snap, at)
        val more = s.tagged(V18Tags.STRIP_MORE_ROW)!!
        assertEquals("$what: Snap's line ends at the row's end", more.right - 8f, at.right, 1.5f)
        return at
    }

    private fun text(h: ChromeHarness) {
        val s = h.editor(Qa18MenusShots.doc(vector = false, dark = true))
        assertEquals("360 dp wide", 360f, s.widthDp, 1f)
        Qa18MenusShots.pick(s, ToolId.TEXT)
        val t = s.c.currentTool as TextTool
        val snap = closedRow(s, "no text open", null)

        t.startTextAt(200f, 150f)
        t.setText("Strip")
        t.confirmEditor()
        Smoke.pump(300); SmokeUi.settle()
        assertNotNull("an open text", t.item)
        val open = moreRow(s)
        val names = open.map { it.first }
        val i = names.indexOf(SNAP_LABEL)
        assertTrue("a text open: Snap, then Numbers: $names", i >= 0 && names.getOrNull(i + 1) == TextStripItems.NUMBERS_LABEL)
        // v1.9 (R3): Numbers ends the end-aligned line; Snap stays on it, Numbers' width and the gap further left.
        val numbers = open[i + 1].second
        assertEquals("a text open: Snap on its line", snap.top, open[i].second.top, 0.5f)
        assertEquals("a text open: Snap moved left by Numbers and the gap", snap.left - numbers.width - 4f, open[i].second.left, 0.5f)
        assertEquals("a text open: Numbers ends the line", snap.right, numbers.right, 0.5f)

        // Applied by the top row's ✓ with the More row open: Numbers goes, nothing blank after Snap.
        val steps = s.c.undoManager.undoCount
        val layers = s.c.doc.layers.size
        Finger.tap(s, "Apply text edit")
        Smoke.pump(300); SmokeUi.settle()
        assertNull("the text applied", t.item)
        assertEquals("one step", steps + 1, s.c.undoManager.undoCount)
        assertEquals("a text layer", layers + 1, s.c.doc.layers.size)
        closedRow(s, "the text applied", snap)

        // Undo, then Redo, by the top row: the row as it was each time.
        Finger.tap(s, "Undo")
        Smoke.pump(300); SmokeUi.settle()
        assertEquals("Undo takes the text layer back", layers, s.c.doc.layers.size)
        assertEquals("one step back", steps, s.c.undoManager.undoCount)
        closedRow(s, "the text undone", snap)
        Finger.tap(s, "Redo")
        Smoke.pump(300); SmokeUi.settle()
        assertEquals("Redo brings the text layer", layers + 1, s.c.doc.layers.size)
        assertNull("no text open after Redo", t.item)
        closedRow(s, "the text redone", snap)
        Smoke.assertQuiet(s.c, "Text")
    }

    private companion object {
        const val FIRST = "Copy selection"
        const val LAST = SavedSelectionLabels.SAVE
        const val DESELECT = "Clear the selection"

        /** How far a finger moves before a drag scrolls (Android's touch slop, dp). */
        const val SLOP_DP = 8f
    }
}
