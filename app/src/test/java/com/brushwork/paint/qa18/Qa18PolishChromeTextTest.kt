package com.brushwork.paint.qa18

import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.Clickables
import com.brushwork.paint.ui.placement.SNAP_LABEL
import com.brushwork.paint.ui.placement.TextStripItems
import com.brushwork.paint.ui.tools.strip.StripLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 polish (request items 8 and 9, QA: the Text strip's More row kept a blank 72 dp slot for
 * "Numbers" while no text was open). Text picked by a finger on a raster and on a vector layer, at
 * 392 and 360 dp, its More row opened by the toggle: with no text open, "Snap to objects" is its
 * last control with nothing after it (no slot); with a text open, "Numbers" follows Snap 4 dp
 * after it, whole on one line, and Snap has not moved. "Numbers" still opens its sheet (the row
 * closes first, D25).
 * v1.9 (R3): the row's lines are content-width, end-aligned under the toggle, so Snap's line ends
 * at the row's end, and with a text open "Numbers" ends it: Snap stays on its line, moved left by
 * Numbers' width and the gap. The sheet covers the row (hidden, remembered) instead of closing it.
 * Rendered: `polish-chrome-text-<width>[-vector]` and `...-open`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.polishchrometextsandbox"])
class Qa18PolishChromeTextTest {

    @Test
    fun theTextStripsMoreRowKeepsNoBlankSlot() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 600_000)
        val h = ChromeHarness()
        Qa18PolishChromeKit.atEachWidth(h, "Text") { dp ->
            for (vector in listOf(false, true)) text(h, dp, vector)
        }
        dog.interrupt()
        h.finish()
    }

    /** The More row's controls, left to right, line by line, with their bounds (dp). */
    private fun moreControls(s: ChromeScreen, what: String): List<Pair<String, androidx.compose.ui.geometry.Rect>> {
        val (items, _) = Qa18MenusShots.openMore(s, what)
        return items.sortedWith(compareBy({ (it.bounds.top / 8f).toInt() }, { it.bounds.left })).map { (it.own.firstOrNull() ?: it.labels.firstOrNull() ?: "?") to it.bounds }
    }

    private fun text(h: ChromeHarness, dp: Int, vector: Boolean) {
        val s = h.editor(Qa18MenusShots.doc(vector))
        assertEquals("$dp dp wide", dp.toFloat(), s.widthDp, 1f)
        Qa18MenusShots.pick(s, ToolId.TEXT)
        val tag = "$dp${if (vector) "-vector" else ""}"
        val idle = "Text, no text open, at $tag"

        // No text open: Snap ends the row, nothing after it.
        val closed = moreControls(s, idle)
        val names = closed.map { it.first }
        assertEquals("$idle: Snap ends the More row: $names", SNAP_LABEL, names.last())
        assertFalse("$idle: no Numbers without a text: $names", TextStripItems.NUMBERS_LABEL in names)
        val more = s.tagged(V18Tags.STRIP_MORE_ROW)!!
        val snap = closed.last().second
        // v1.9 (R3): each line is as wide as its items, end-aligned under the toggle: Snap's line
        // ends at Snap plus the line's own padding, at the row's end.
        assertEquals("$idle: Snap's line ends at the row's end", more.right, snap.right + SIDE_PADDING_DP, 1.5f)
        Qa18PolishChromeKit.shot(s, "text-$tag", heightDp = 220f)
        Qa18MenusShots.closeMore(s, idle)

        // A text open: Numbers right after Snap, Snap where it was.
        val t = s.c.currentTool as TextTool
        t.startTextAt(200f, 150f)
        t.setText("Strip")
        t.confirmEditor()
        Smoke.pump(300); SmokeUi.settle()
        assertNotNull("an open text", t.item)
        val open = "Text, a text open, at $tag"
        val opened = moreControls(s, open)
        val openNames = opened.map { it.first }
        val i = openNames.indexOf(SNAP_LABEL)
        assertTrue("$open: Snap, then Numbers: $openNames", i >= 0 && openNames.getOrNull(i + 1) == TextStripItems.NUMBERS_LABEL && i + 2 == openNames.size)
        val snapNow = opened[i].second
        val numbers = opened[i + 1].second
        // v1.9 (R3): Numbers ends the end-aligned line; Snap stays on it, Numbers' width and the gap further left.
        assertEquals("$open: Numbers ends the line, at the row's end", more.right, numbers.right + SIDE_PADDING_DP, 1.5f)
        assertEquals("$open: Snap moved left by Numbers and the gap", snap.left - numbers.width - StripLayout.GAP_DP, snapNow.left, 0.5f)
        assertEquals("$open: Snap has not moved (top)", snap.top, snapNow.top, 0.5f)
        assertEquals("$open: Numbers 4 dp after Snap", snapNow.right + StripLayout.GAP_DP, numbers.left, 0.5f)
        // Its word whole on one line (at 72 dp it broke as "Number" / "s").
        val word = Qa18PolishChromeKit.textNode(s, TextStripItems.NUMBERS_LABEL, inside = numbers) ?: throw AssertionError("$open: no \"Numbers\" text")
        val wordLines = Qa18PolishChromeKit.lines(Qa18PolishChromeKit.layout(word) ?: throw AssertionError("$open: no layout"))
        assertEquals("$open: \"Numbers\" on one line: $wordLines", listOf(TextStripItems.NUMBERS_LABEL), wordLines)
        Qa18PolishChromeKit.shot(s, "text-$tag-open", heightDp = 220f)

        // "Numbers" opens its sheet; the sheet covers the row (v1.9, R3: hidden, remembered). Back closes the sheet.
        val before = s.c.undoManager.undoCount
        Finger.tap(s, TextStripItems.NUMBERS_LABEL)
        Smoke.pump(300); SmokeUi.settle()
        assertTrue("$open: the Numbers sheet", t.numbersOpen)
        assertEquals("$open: the row hides under the sheet", null, s.tagged(V18Tags.STRIP_MORE_ROW))
        Finger.back()
        Smoke.pump(300); SmokeUi.settle()
        assertFalse("$open: Back closes the sheet", t.numbersOpen)
        assertEquals("$open: no step", before, s.c.undoManager.undoCount)
        Smoke.assertQuiet(s.c, open)
    }

    private companion object {
        /** A More row line's side padding (StripHost's ROW_PADDING). */
        const val SIDE_PADDING_DP = 8f
    }
}
