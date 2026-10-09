package com.brushwork.paint.ui.tools

import androidx.compose.ui.geometry.Rect
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.common.PillLabels
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.7 item 9 (design §3.9; area I), `Qa17PillNarrowUiTest`: the pill at its widest in the editor
 * on a narrow phone, against [FakePillTool] (an object 9.9 billion px out, so X and Y read
 * "−9,876,540,416"; a Scale of 100,000,000,000 %; long steps on both "#" cells):
 * - row 1 `[✥][X][Y][# step][🗑]` and row 2 `[⛓][Scale X][Scale Y][# step]` stay inside the
 *   screen's 8 dp margins, their cells whole and side by side; at 392 dp the X, Y and Scale cells
 *   are at their 110 dp maximum, row 1 at most 372 dp and row 2 at most 328 dp;
 * - the selection bar sits 6 dp under row 2 (209 dp on the reference phone, 74 dp under row 1's
 *   top) without overlapping it, and back at 173 (38 dp under) with the pill folded, where the
 *   trash cell stays.
 * At 360 dp (the other phones' QA width) the X and Y cells give way first, so the "#" and trash
 * cells stay whole. One UI test per class (Compose's frame clock), each its own sandbox.
 */
internal object PillNarrow {

    private const val EPS = 0.75f

    private fun element(s: ChromeScreen, label: String): Rect =
        requireNotNull(Finger.element(s, label)) { "no \"$label\"; shown: ${SmokeUi.shown().take(80)}" }

    private fun tagged(s: ChromeScreen, tag: String): Rect = requireNotNull(s.tagged(tag)) { "no $tag" }

    /** [cells] (named) lie in [row], inside the screen's margins, left to right without overlap. */
    private fun assertRow(s: ChromeScreen, what: String, row: Rect, cells: List<Pair<String, Rect>>) {
        val w = s.widthDp
        assertTrue("$what inside the 8 dp margins: $row of $w", row.left >= 8f - EPS && row.right <= w - 8f + EPS)
        val sorted = cells.sortedBy { it.second.left }
        assertEquals("$what: the cells in their order", cells.map { it.first }, sorted.map { it.first })
        for ((name, r) in cells) {
            assertTrue("$what: $name ($r) in the row ($row)", r.left >= row.left - EPS && r.right <= row.right + EPS)
        }
        for (i in 1 until sorted.size) {
            assertTrue("$what: ${sorted[i - 1].first} and ${sorted[i].first} side by side", sorted[i - 1].second.right <= sorted[i].second.left + EPS)
        }
    }

    /**
     * Lets the screen settle after a change. Under Robolectric a Compose layout can wait for the
     * test's next query; the pill's height (`onSizeChanged`) then reaches the selection bar's
     * place one frame later. So: lay out, run frames, twice.
     */
    private fun relayout(s: ChromeScreen) = repeat(2) {
        s.placed()
        Smoke.pump(100)
        settle()
    }

    private fun assertWidth(what: String, r: Rect, min: Float, max: Float) =
        assertTrue("$what ${r.width} dp in $min–$max", r.width >= min - EPS && r.width <= max + EPS)

    fun run(name: String, reference: Boolean) {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = ChromeHarness()
        h.section(name) {
            val s = h.editor(Smoke.document(400, 300, layers = 2, whiteBottom = true))
            val c = s.c
            // Long enough to reach the 110 dp cap: "−9,876,540,416" and "100,000,000,000 %".
            val far = -9.87654e9f
            val size = 40_960f
            val fake = FakePillTool(c, listOf(Vec2(far, far), Vec2(far + size, far), Vec2(far + size, far + size), Vec2(far, far + size)))
            @Suppress("UNCHECKED_CAST")
            (c.tools as MutableMap<ToolId, Tool>)[ToolId.CURVE] = fake
            c.selectTool(ToolId.CURVE)
            c.increments.update { it.copy(lengthPx = 1234.5f, scalePercent = 999.5f) }
            fake.objectScale!!.setScale(1e11f, 1e11f)
            c.selectAll()
            relayout(s)

            // ------------------------------------------------ row 1
            val row1Cells = listOf(
                "fold" to element(s, "Fold the X / Y strip"),
                "X" to element(s, "X slider"),
                "Y" to element(s, "Y slider"),
                "#" to element(s, INCREMENTS_LABEL),
                "trash" to tagged(s, V17Tags.PILL_TRASH),
            )
            val row1 = element(s, "Center")
            assertRow(s, "row 1", row1, row1Cells)
            val cell = row1Cells.toMap()
            assertWidth("#", cell.getValue("#"), 40f, 56f)
            assertWidth("trash", cell.getValue("trash"), 40f, 40f)
            if (reference) {
                assertWidth("X at its maximum", cell.getValue("X"), 110f, 110f)
                assertWidth("Y at its maximum", cell.getValue("Y"), 110f, 110f)
                assertTrue("row 1 at most 372 dp: ${row1.width}", row1.width <= 372f + EPS)
            }

            // ------------------------------------------------ row 2
            val row2 = tagged(s, V17Tags.PILL_SCALE_ROW)
            val row2Cells = listOf(
                "keep" to element(s, PillLabels.KEEP_PROPORTIONS),
                "Scale X" to element(s, PillLabels.SCALE_X),
                "Scale Y" to element(s, PillLabels.SCALE_Y),
                "#" to element(s, PillLabels.SCALE_INCREMENTS),
            )
            assertRow(s, "row 2", row2, row2Cells)
            val cell2 = row2Cells.toMap()
            assertWidth("Scale #", cell2.getValue("#"), 40f, 56f)
            assertWidth("Scale X at its maximum", cell2.getValue("Scale X"), 110f, 110f)
            assertWidth("Scale Y at its maximum", cell2.getValue("Scale Y"), 110f, 110f)
            assertTrue("row 2 at most 328 dp: ${row2.width}", row2.width <= 328f + EPS)
            assertEquals("row 2 4 dp under row 1", row1.bottom + 4f, row2.top, EPS)

            // ------------------------------------------------ the selection bar under it
            val bar = tagged(s, ChromeTags.SELECTION_BAR)
            assertTrue("no overlap: bar $bar, row 2 $row2", bar.top >= row2.bottom - EPS)
            assertEquals("6 dp under row 2 (209 dp on the reference phone)", row2.bottom + 6f, bar.top, EPS)
            assertEquals("209 − 135", 74f, bar.top - row1.top, EPS)

            // ------------------------------------------------ folded: [✥][🗑], the bar back at 173
            SmokeUi.click("Fold the X / Y strip", exact = true)
            relayout(s)
            assertNull("no row 2 folded", s.tagged(V17Tags.PILL_SCALE_ROW))
            val trash = tagged(s, V17Tags.PILL_TRASH)
            assertWidth("the trash cell folded", trash, 40f, 40f)
            val folded = tagged(s, ChromeTags.SELECTION_BAR)
            assertEquals("173 − 135 (pill slot ${s.tagged(ChromeTags.PILL_SLOT)}, bar $folded)", 38f, folded.top - row1.top, EPS)
            SmokeUi.click("Unfold the X / Y strip", exact = true)
            relayout(s)
            assertEquals(74f, tagged(s, ChromeTags.SELECTION_BAR).top - row1.top, EPS)
            Smoke.assertQuiet(c, name)
        }
        dog.interrupt()
        h.finish()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.tools.pillnarrow392sandbox"])
class Qa17PillNarrowUiTest {
    @Test
    fun bothRowsAtTheirWidestFitTheReferencePhone() = PillNarrow.run("the pill at 392 dp", reference = true)
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.tools.pillnarrow360sandbox"])
class Qa17PillNarrow360UiTest {
    @Test
    fun bothRowsAtTheirWidestFitA360dpPhone() = PillNarrow.run("the pill at 360 dp", reference = false)
}
