package com.brushwork.paint.ui.tools.strip

/**
 * The strip's geometry (v1.8, design §3.9 and §4.6), in dp, as pure functions (I18): which items
 * row 1 keeps, which go to the More row, how the More row wraps and how tall it is. Frozen from
 * F3 on (§4.5).
 */
object StripLayout {
    /** The More-row toggle at row 1's end (`StripLabels18.MORE_TOGGLE`). */
    const val TOGGLE_DP = 44f

    /** Between neighbouring items, and between the More row's lines. */
    const val GAP_DP = 4f

    /** One line of the More row. */
    const val LINE_DP = 44f

    /** The VECTOR icon cell (D30); row 1 reserves VECTOR_CHIP_DP + GAP_DP = 44 dp for it. */
    const val VECTOR_CHIP_DP = 40f

    /** D33, a lead deviation the user may veto: the veto sets 1, and that line scrolls sideways. */
    const val MAX_MORE_LINES = 3

    /** Row 1's items, the More row's items (in order), and whether the toggle shows. */
    class Split(val row1: List<StripItem>, val more: List<StripItem>, val toggle: Boolean)

    /** The width of [items] side by side: their widths plus a [GAP_DP] between neighbours. */
    fun rowWidth(items: List<StripItem>): Float =
        if (items.isEmpty()) 0f else items.sumOf { it.width.value.toDouble() }.toFloat() + GAP_DP * (items.size - 1)

    /**
     * Pure (I18). [availableDp] = the row's content width (364 at 392 dp, 332 at 360 dp), minus
     * VECTOR_CHIP_DP + GAP_DP while the chip shows. The toggle (TOGGLE_DP + GAP_DP) is reserved
     * when any item or the hint goes to the More row. AUTO items leave row 1 by rank until it
     * fits. The budgets this gives are §3.9's table (284 / 240 / 332 / 288 at 360 dp).
     *
     * AUTO items of equal rank leave from the end of the row first. The items that leave go to
     * the front of the More row in their row-1 order, before the spec's own More items. A row
     * whose non-AUTO items alone exceed the budget keeps them all (the host clips it; the
     * inventory test catches it first).
     */
    fun split(spec: StripSpec, availableDp: Float): Split {
        val row1 = spec.items.filter { it.row == StripRow.ROW1 }
        val more = spec.items.filter { it.row == StripRow.MORE }
        var toggle = more.isNotEmpty() || spec.hint != null
        val kept = row1.toMutableList()
        val left = HashSet<StripItem>()
        val candidates = row1.withIndex()
            .filter { it.value.overflowRank > 0 }
            .sortedWith(compareByDescending<IndexedValue<StripItem>> { it.value.overflowRank }.thenByDescending { it.index })
            .map { it.value }
        for (c in candidates) {
            val budget = availableDp - if (toggle) TOGGLE_DP + GAP_DP else 0f
            if (rowWidth(kept) <= budget + EPSILON) break
            kept.remove(c)
            left += c
            toggle = true
        }
        val moved = row1.filter { it in left }
        return Split(kept, moved + more, toggle)
    }

    /** v1.8 integration: the most lines the hint takes; a longer hint ends in an ellipsis. */
    const val MAX_HINT_LINES = 2

    /**
     * The More row's lines: the hint's first, then the items wrapped in order at [availableDp].
     * The hint takes [hintLines] lines (as the host measures it at [availableDp]), empty ones,
     * counted like any other line: see [hintLineCount]. Items share the hint's line only when
     * [MAX_MORE_LINES] leaves no other. At most [MAX_MORE_LINES] lines in all: the items that do
     * not fit go on the last line, which then scrolls sideways. No hint and no items: no lines.
     */
    fun moreLines(split: Split, hint: String?, availableDp: Float, hintLines: Int = 1): List<List<StripItem>> {
        val lines = ArrayList<MutableList<StripItem>>()
        if (hint != null) repeat(hintLineCount(split, hintLines)) { lines += mutableListOf<StripItem>() }
        var line: MutableList<StripItem>? = null
        var width = 0f
        for (item in split.more) {
            val w = item.width.value
            val cur = line
            if (cur == null || width + GAP_DP + w > availableDp + EPSILON) {
                if (lines.size >= MAX_MORE_LINES) {
                    // Out of lines: the rest joins the last line (it scrolls sideways).
                    val last = lines.last()
                    last += item
                    line = last
                    width = rowWidth(last)
                    continue
                }
                val next = mutableListOf(item)
                lines += next
                line = next
                width = w
            } else {
                cur += item
                width += GAP_DP + w
            }
        }
        return lines
    }

    /**
     * The lines the hint takes in [moreLines]: [hintLines] kept within 1 .. [MAX_HINT_LINES], and
     * within [MAX_MORE_LINES] less one while [split] sends items to the More row, so they keep a
     * line of their own (with 3, a two-line hint leaves the items one line).
     */
    fun hintLineCount(split: Split, hintLines: Int): Int {
        val room = if (split.more.isEmpty()) MAX_MORE_LINES else MAX_MORE_LINES - 1
        return hintLines.coerceAtMost(MAX_HINT_LINES).coerceAtMost(room).coerceAtLeast(1)
    }

    /** lines·44 + (lines-1)·4 dp; 0 for no lines. The pill and bars move by this + 4 dp. */
    fun moreRowDp(lines: Int): Float = if (lines <= 0) 0f else lines * LINE_DP + (lines - 1) * GAP_DP

    /** Float sums of dp widths: a row that fits exactly must not overflow by rounding. */
    private const val EPSILON = 0.01f
}
