package com.brushwork.paint.ui.tools.strip

import androidx.compose.ui.unit.dp
import com.brushwork.paint.ui.editor.chrome.ChromeLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.8 I18 (design §3.9, §4.6): where the strip's items go is a pure function of their declared
 * widths, rows and ranks. Row 1's content width is the screen less the panel's 2 × 6 dp margins
 * and its 2 × 8 dp padding (364 dp at 392, 332 dp at 360); the toggle takes 44 + 4 dp, the
 * VECTOR cell 40 + 4 dp, which gives §3.9's budgets.
 */
class StripLayoutTest {

    private fun item(key: String, w: Float, row: StripRow = StripRow.ROW1, rank: Int = 0) =
        StripItem(key = key, label = key, width = w.dp, row = row, overflowRank = rank) {}

    private fun more(key: String, w: Float = 40f) = item(key, w, StripRow.MORE)

    private fun keys(items: List<StripItem>) = items.map { it.key }

    /** Row 1's content width on a screen [screenDp] wide. */
    private fun content(screenDp: Float) = screenDp - 2 * 6f - 2 * 8f

    private val chip = StripLayout.VECTOR_CHIP_DP + StripLayout.GAP_DP

    @Test
    fun contentWidthsAndTheFourBudgets() {
        assertEquals(332f, content(360f), 0f)
        assertEquals(364f, content(392f), 0f)
        assertEquals(48f, StripLayout.TOGGLE_DP + StripLayout.GAP_DP, 0f)
        assertEquals(44f, chip, 0f)
        val toggle = StripLayout.TOGGLE_DP + StripLayout.GAP_DP
        // §3.9: toggle without chip, toggle with chip, neither, chip only.
        assertEquals(listOf(284f, 240f, 332f, 288f), listOf(content(360f) - toggle, content(360f) - chip - toggle, content(360f), content(360f) - chip))
        assertEquals(listOf(316f, 272f, 364f, 320f), listOf(content(392f) - toggle, content(392f) - chip - toggle, content(392f), content(392f) - chip))
    }

    @Test
    fun rowWidthAddsTheGaps() {
        assertEquals(0f, StripLayout.rowWidth(emptyList()), 0f)
        assertEquals(40f, StripLayout.rowWidth(listOf(item("a", 40f))), 0f)
        assertEquals(40f + 4f + 56f + 4f + 64f, StripLayout.rowWidth(listOf(item("a", 40f), item("b", 56f), item("c", 64f))), 0f)
    }

    @Test
    fun aRowThatFitsKeepsEverythingAndShowsNoToggle() {
        val spec = StripSpec(listOf(item("a", 96f), item("b", 64f), item("c", 56f), item("d", 40f)))
        val s = StripLayout.split(spec, content(360f))
        assertEquals(listOf("a", "b", "c", "d"), keys(s.row1))
        assertTrue(s.more.isEmpty())
        assertFalse(s.toggle)
        // Exactly the width: still fits (no rounding overflow).
        val exact = StripSpec(listOf(item("a", 332f)))
        assertEquals(listOf("a"), keys(StripLayout.split(exact, 332f).row1))
        assertFalse(StripLayout.split(exact, 332f).toggle)
    }

    @Test
    fun aMoreItemOrAHintReservesTheToggle() {
        val withMore = StripSpec(listOf(item("a", 100f), more("m")))
        val s = StripLayout.split(withMore, content(360f))
        assertTrue(s.toggle)
        assertEquals(listOf("a"), keys(s.row1))
        assertEquals(listOf("m"), keys(s.more))
        val withHint = StripSpec(listOf(item("a", 100f)), hint = "Tap corners")
        val h = StripLayout.split(withHint, content(360f))
        assertTrue(h.toggle)
        assertTrue(h.more.isEmpty())
    }

    @Test
    fun eachBudgetHoldsExactlyItsWidth() {
        // A fixed item plus an AUTO item, together exactly the budget: both stay; 1 dp more: the AUTO one leaves.
        for (screen in listOf(360f, 392f)) for (withChip in listOf(false, true)) {
            val available = content(screen) - if (withChip) chip else 0f
            val budget = available - (StripLayout.TOGGLE_DP + StripLayout.GAP_DP)
            val fits = StripSpec(listOf(item("fixed", budget - 44f), item("auto", 40f, rank = 1), more("m")))
            val s = StripLayout.split(fits, available)
            assertEquals("$screen dp, chip $withChip", listOf("fixed", "auto"), keys(s.row1))
            val over = StripSpec(listOf(item("fixed", budget - 43f), item("auto", 40f, rank = 1), more("m")))
            val o = StripLayout.split(over, available)
            assertEquals("$screen dp, chip $withChip", listOf("fixed"), keys(o.row1))
            assertEquals(listOf("auto", "m"), keys(o.more))
        }
    }

    @Test
    fun autoItemsLeaveByRankThenFromTheEnd() {
        val a = item("a", 60f, rank = 1)
        val b = item("b", 60f, rank = 2)
        val c = item("c", 60f, rank = 1)
        val d = item("d", 60f)
        val spec = StripSpec(listOf(a, b, c, d, more("m")))
        // All four: 60·4 + 12 = 252. Budget = available − 48.
        assertEquals(listOf("a", "b", "c", "d"), keys(StripLayout.split(spec, 252f + 48f).row1))
        // One must leave: the highest rank (b).
        val one = StripLayout.split(spec, 251f + 48f)
        assertEquals(listOf("a", "c", "d"), keys(one.row1))
        assertEquals(listOf("b", "m"), keys(one.more))
        // Two: then of the equal ranks the later one (c), in row-1 order at the More row's front.
        val two = StripLayout.split(spec, 187f + 48f)
        assertEquals(listOf("a", "d"), keys(two.row1))
        assertEquals(listOf("b", "c", "m"), keys(two.more))
        // Three: a goes too; the non-AUTO d always stays.
        val three = StripLayout.split(spec, 100f + 48f)
        assertEquals(listOf("d"), keys(three.row1))
        assertEquals(listOf("a", "b", "c", "m"), keys(three.more))
        assertEquals(listOf("d"), keys(StripLayout.split(spec, 10f).row1))
    }

    @Test
    fun movingAnAutoItemReservesTheToggleToo() {
        // No More items and no hint: 252 + 4 + 40 + 4 + 40 = 340 > 332. Without auto2 it is 296,
        // which no longer fits once the toggle is reserved (284): auto1 leaves as well.
        val spec = StripSpec(listOf(item("fixed", 252f), item("auto1", 40f, rank = 1), item("auto2", 40f, rank = 2)))
        val s = StripLayout.split(spec, content(360f))
        assertTrue(s.toggle)
        assertEquals(listOf("fixed"), keys(s.row1))
        assertEquals(listOf("auto1", "auto2"), keys(s.more))
        // At 392 dp everything fits without a toggle.
        val wide = StripLayout.split(spec, content(392f))
        assertFalse(wide.toggle)
        assertEquals(3, wide.row1.size)
    }

    @Test
    fun nonAutoItemsNeverLeaveRow1() {
        val spec = StripSpec(listOf(item("a", 200f), item("b", 200f)))
        val s = StripLayout.split(spec, content(360f))
        assertEquals(listOf("a", "b"), keys(s.row1))
        assertFalse(s.toggle)
        assertTrue(StripLayout.rowWidth(s.row1) > content(360f))
    }

    @Test
    fun specsWithoutItemsSplitToNothing() {
        // EMPTY, and a spec with overlays alone (its sheets, nothing on the rows).
        for (spec in listOf(StripSpec.EMPTY, StripSpec(emptyList(), overlays = {}))) {
            val s = StripLayout.split(spec, content(360f))
            assertTrue(s.row1.isEmpty() && s.more.isEmpty())
            assertFalse(s.toggle)
            assertTrue(StripLayout.moreLines(s, spec.hint, content(360f)).isEmpty())
        }
    }

    @Test
    fun moreLinesWrapInOrderWithTheHintFirst() {
        fun lines(spec: StripSpec, w: Float) = StripLayout.moreLines(StripLayout.split(spec, w), spec.hint, w).map { keys(it) }
        // Nothing for the More row: no lines.
        assertEquals(emptyList<List<String>>(), lines(StripSpec(listOf(item("a", 40f))), 332f))
        // A hint alone: its line.
        assertEquals(listOf(emptyList<String>()), lines(StripSpec(listOf(item("a", 40f)), hint = "h"), 332f))
        // 100 dp items at 332: three a line (308), in order.
        val seven = (1..7).map { more("m$it", 100f) }
        assertEquals(listOf(listOf("m1", "m2", "m3"), listOf("m4", "m5", "m6"), listOf("m7")), lines(StripSpec(seven), 332f))
        // With the hint, its line comes first.
        assertEquals(
            listOf(emptyList(), listOf("m1", "m2", "m3"), listOf("m4", "m5", "m6", "m7")),
            lines(StripSpec(seven, hint = "h"), 332f),
        )
        // An exact fit stays on its line.
        assertEquals(listOf(listOf("x", "y")), lines(StripSpec(listOf(more("x", 164f), more("y", 164f))), 332f))
    }

    @Test
    fun moreLinesStopAtTheMaximumAndTheLastLineTakesTheRest() {
        assertEquals(3, StripLayout.MAX_MORE_LINES)
        val twelve = (1..12).map { more("m$it", 100f) }
        val spec = StripSpec(twelve)
        val lines = StripLayout.moreLines(StripLayout.split(spec, 332f), null, 332f)
        assertEquals(StripLayout.MAX_MORE_LINES, lines.size)
        assertEquals(listOf(3, 3, 6), lines.map { it.size })
        assertEquals(twelve.map { it.key }, lines.flatten().map { it.key })
    }

    @Test
    fun moreRowHeightAndThePillOffset() {
        assertEquals(0f, StripLayout.moreRowDp(0), 0f)
        assertEquals(44f, StripLayout.moreRowDp(1), 0f)
        assertEquals(92f, StripLayout.moreRowDp(2), 0f)
        assertEquals(140f, StripLayout.moreRowDp(3), 0f)
        // §3.9: one line moves the X / Y pill from 135 to 183 on the reference phone (status bar 33).
        assertEquals(135f, ChromeLayout.pillTop(33f), 0.01f)
        assertEquals(183f, ChromeLayout.pillTop(33f, StripLayout.moreRowDp(1)), 0.01f)
        assertEquals(0f, ChromeLayout.moreRowShift(0f), 0f)
    }
}
