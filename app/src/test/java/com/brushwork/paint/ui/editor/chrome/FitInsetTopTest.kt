package com.brushwork.paint.ui.editor.chrome

import com.brushwork.paint.ui.tools.strip.StripLayout
import com.brushwork.paint.ui.theme.IbisDims
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.9 F3 (R3 (a); JVM): [ChromeLayout.fitInsetTop]. With no More row it is v1.8's inset (137 dp
 * under a 33 dp status bar on the reference phone); an explicit fit with the row open leaves its
 * height and its 4 dp gap as well, so the artwork's top keeps the fit gap under the row's bottom,
 * for each of the row's 1 to [StripLayout.MAX_MORE_LINES] lines (3 lines: 140 dp).
 */
class FitInsetTopTest {
    private val status = 33f

    /** The More row's bottom (dp) when it is [rowDp] tall: under row 1, [ChromeLayout.MORE_ROW_GAP] apart. */
    private fun rowBottom(statusDp: Float, rowDp: Float): Float =
        ChromeLayout.optionsStripTop(statusDp) + IbisDims.OptionsStripHeight.value + ChromeLayout.MORE_ROW_GAP + rowDp

    @Test
    fun withoutARowItIsTheV18Inset() {
        assertEquals(137f, ChromeLayout.fitInsetTop(status), 0.01f)
        assertEquals(ChromeLayout.fitInsetTop(status), ChromeLayout.fitInsetTop(status, 0f), 0f)
        for (s in listOf(0f, 24f, 33f, 48f)) {
            assertEquals("status $s", s + IbisDims.FitInsetTop.value - IbisDims.StatusBar.value, ChromeLayout.fitInsetTop(s), 0.01f)
        }
    }

    @Test
    fun anOpenRowAddsItsHeightAndGap() {
        assertEquals("3 lines cover 140 dp", 140f, StripLayout.moreRowDp(3), 0.01f)
        for (lines in 1..StripLayout.MAX_MORE_LINES) {
            val row = StripLayout.moreRowDp(lines)
            val inset = ChromeLayout.fitInsetTop(status, row)
            assertEquals("$lines lines", ChromeLayout.fitInsetTop(status) + row + ChromeLayout.MORE_ROW_GAP, inset, 0.01f)
            assertEquals("$lines lines: the fit gap under the row", ChromeLayout.fitGap, inset - rowBottom(status, row), 0.01f)
            assertTrue("$lines lines: the row's bottom is above the artwork's top", rowBottom(status, row) < inset)
        }
        assertEquals("the reference phone, one line: 137 + 44 + 4", 185f, ChromeLayout.fitInsetTop(status, StripLayout.moreRowDp(1)), 0.01f)
    }
}
