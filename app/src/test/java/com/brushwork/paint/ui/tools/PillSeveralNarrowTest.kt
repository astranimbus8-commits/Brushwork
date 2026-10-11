package com.brushwork.paint.ui.tools

import androidx.compose.ui.geometry.Rect
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.QaCurves
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.testing.PillTestKit
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 (F4, design §3.2) on the editor at 392 dp (the user's phone) and at 360 dp (the other
 * phones' QA width): row 1 of the X / Y pill at its fullest with the new cell, a Path's two
 * points picked and a long "#" step, `[✥][X][Y][# 1,234.5][🗑][sel]`: every cell whole inside
 * the screen's 8 dp margins and inside the row, side by side in that order, the "Select several"
 * cell 40 × 40 dp (also while on), X and Y a finger's width at least (they give way first), the
 * row at most 372 dp; folded, `[✥][🗑][sel]` the same. One UI test per class (Compose's frame
 * clock), each its own sandbox.
 */
internal object PillSeveralNarrow {

    private const val EPS = 0.75f

    /** The cell's touch, `IbisDims.PillIconTouch` × `IbisDims.PillCellTouch`. */
    private const val CELL_DP = 40f

    private val five = listOf(Vec2(60f, 80f), Vec2(140f, 50f), Vec2(220f, 120f), Vec2(300f, 70f), Vec2(360f, 200f))

    private fun element(s: ChromeScreen, label: String): Rect =
        requireNotNull(Finger.element(s, label)) { "no \"$label\"; shown: ${SmokeUi.shown().take(80)}" }

    private fun tagged(s: ChromeScreen, tag: String): Rect = requireNotNull(s.tagged(tag)) { "no $tag" }

    /** [cells] lie in [row], inside the screen's margins, whole, left to right without overlap. */
    private fun assertRow(s: ChromeScreen, what: String, row: Rect, cells: List<Pair<String, Rect>>) {
        val w = s.widthDp
        assertTrue("$what inside the 8 dp margins: $row of $w", row.left >= 8f - EPS && row.right <= w - 8f + EPS)
        assertTrue("$what at most 372 dp: ${row.width}", row.width <= 372f + EPS)
        val sorted = cells.sortedBy { it.second.left }
        assertEquals("$what: the cells in their order", cells.map { it.first }, sorted.map { it.first })
        for ((name, r) in cells) {
            assertTrue("$what: $name ($r) in the row ($row)", r.left >= row.left - EPS && r.right <= row.right + EPS)
            assertTrue("$what: $name ($r) whole on screen", r.left >= -EPS && r.right <= w + EPS && r.width > 0f)
        }
        for (i in 1 until sorted.size) {
            assertTrue("$what: ${sorted[i - 1].first} and ${sorted[i].first} side by side", sorted[i - 1].second.right <= sorted[i].second.left + EPS)
        }
    }

    private fun assertCell(what: String, r: Rect) {
        assertEquals("$what: the cell is 40 dp wide ($r)", CELL_DP, r.width, EPS)
        assertTrue("$what: the cell is 40 dp tall ($r)", r.height >= CELL_DP - EPS)
    }

    private fun relayout(s: ChromeScreen) = repeat(2) {
        s.placed()
        Smoke.pump(100)
        settle()
    }

    fun run(name: String) {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = ChromeHarness()
        h.section(name) {
            val s = h.editor()
            val c = s.c
            QaCurves.tool(s, "Path")
            val tool = c.currentTool as CurveTool
            for (p in five) assertTrue(tool.addAnchor(p))
            tool.selectPoints(PointSelection.of(tool.pointCount, 1, 3))
            c.increments.update { it.copy(lengthPx = 1234.5f) }
            relayout(s)
            val label = requireNotNull(coordinateSourceOf(tool)).target.label
            val row = element(s, label)

            for (on in listOf(false, true)) {
                val what = "row 1${if (on) ", Select several on" else ""}"
                val cells = listOf(
                    "fold" to element(s, "Fold the X / Y strip"),
                    "X" to element(s, "X slider"),
                    "Y" to element(s, "Y slider"),
                    "#" to element(s, INCREMENTS_LABEL),
                    "trash" to tagged(s, V17Tags.PILL_TRASH),
                    "sel" to tagged(s, V18Tags.PILL_SEVERAL),
                )
                assertRow(s, what, row, cells)
                val cell = cells.toMap()
                assertCell(what, cell.getValue("sel"))
                assertEquals("$what: the trash cell", CELL_DP, cell.getValue("trash").width, EPS)
                assertTrue("$what: the # cell whole: ${cell.getValue("#")}", cell.getValue("#").width >= 40f - EPS)
                for (axis in listOf("X", "Y")) {
                    assertTrue("$what: $axis a finger's width: ${cell.getValue(axis)}", cell.getValue(axis).width >= 40f - EPS)
                }
                if (!on) {
                    PillTestKit.toggleSeveral()
                    assertTrue(tool.selectSeveral)
                    relayout(s)
                }
            }
            PillTestKit.toggleSeveral()
            relayout(s)

            // Folded: [✥][🗑][sel].
            SmokeUi.click("Fold the X / Y strip", exact = true)
            relayout(s)
            assertNull("no X folded", Finger.element(s, "X slider"))
            val folded = listOf(
                "fold" to element(s, "Unfold the X / Y strip"),
                "trash" to tagged(s, V17Tags.PILL_TRASH),
                "sel" to tagged(s, V18Tags.PILL_SEVERAL),
            )
            assertRow(s, "folded", element(s, label), folded)
            assertCell("folded", folded.toMap().getValue("sel"))
            SmokeUi.click("Unfold the X / Y strip", exact = true)
            relayout(s)
            tool.discard()
            settle()
            Smoke.assertQuiet(c, name)
        }
        dog.interrupt()
        h.finish()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.tools.pillseveral392sandbox"])
class PillSeveralNarrowTest {
    @Test
    fun theSelectSeveralCellFitsRowOneAt392dp() = PillSeveralNarrow.run("the pill with Select several at 392 dp")
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.tools.pillseveral360sandbox"])
class PillSeveralNarrow360Test {
    @Test
    fun theSelectSeveralCellFitsRowOneAt360dp() = PillSeveralNarrow.run("the pill with Select several at 360 dp")
}
