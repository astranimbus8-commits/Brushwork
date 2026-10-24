package com.brushwork.paint.v110.a5

import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.ui.tools.PillCell
import com.brushwork.paint.ui.tools.pillRow1
import com.brushwork.paint.ui.tools.pillRow2
import com.brushwork.paint.v110.a5.GroupSnapFixtures.Companion.CURVE_KINDS
import com.brushwork.paint.v110.a5.SharpMixedMakesSharpTest.Companion.FIVE
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 (A5, R12, J28): the Sharp / Smooth cell (SHARP) is never directly under the trash (a
 * delete with no confirmation).
 * - Row 2 (pure, [pillRow2]): SHARP ends it while row 1 ends with "Select several" (SEVERAL),
 *   so it is under SEVERAL; otherwise it comes right after COLOR (or first), before the Scale row.
 * - Every tool with the cell shows "Select several" whenever it shows SHARP: a selected point
 *   makes both available, so on the real tools SHARP is always under SEVERAL.
 */
@RunWith(RobolectricTestRunner::class)
class SharpNeverUnderTrashTest {
    private val fx = GroupSnapFixtures()

    @After
    fun tearDown() = fx.release()

    @Test
    fun row2PutsSharpAfterColorWhenRow1DoesNotEndWithSelectSeveral() {
        val flags = listOf(false, true)
        for (scale in flags) for (color in flags) {
            val what = "scale $scale, color $color"
            val row2 = requireNotNull(pillRow2(true, false, scale, color, sharp = true, row1LastIsSeveral = false)) { what }
            assertEquals("$what: SHARP right after COLOR (or first)", if (color) 1 else 0, row2.indexOf(PillCell.SHARP))
            if (scale) assertTrue("$what: before the Scale row", row2.indexOf(PillCell.SHARP) < row2.indexOf(PillCell.SCALE))
            val under = pillRow2(true, false, scale, color, sharp = true, row1LastIsSeveral = true)!!
            assertEquals("$what: under Select several, SHARP ends row 2", PillCell.SHARP, under.last())
            // Folded or without a position there is no row 2 (SHARP ends the folded row 1).
            assertNull(pillRow2(true, true, scale, color, sharp = true, row1LastIsSeveral = false))
            assertNull(pillRow2(false, false, scale, color, sharp = true, row1LastIsSeveral = false))
        }
        // Whatever row 1 holds, SHARP is never under the trash: under SEVERAL when the rows span the
        // pill (row 1 ends with SEVERAL), else near the start of row 2 (both rows start at the
        // pill's left; the trash follows the move icon, X, Y and "#").
        for (trash in flags) for (several in flags) for (color in flags) for (scale in flags) {
            val row1 = pillRow1(true, false, trash, several, color, sharp = true)
            val row2 = pillRow2(true, false, scale, color, sharp = true, row1LastIsSeveral = row1.last() == PillCell.SEVERAL)!!
            if (!trash) continue
            val sharpFromEnd = row2.size - 1 - row2.indexOf(PillCell.SHARP)
            val what = "trash, several $several, color $color, scale $scale: $row1 over $row2"
            if (row1.last() == PillCell.SEVERAL) {
                assertEquals("$what: SHARP under SEVERAL", 0, sharpFromEnd)
            } else {
                assertTrue("$what: SHARP (cell ${row2.indexOf(PillCell.SHARP)}) left of the trash (cell ${row1.indexOf(PillCell.TRASH)})", row2.indexOf(PillCell.SHARP) < row1.indexOf(PillCell.TRASH))
            }
        }
        // Folded: [fold][color][trash][sel][sharp]: SHARP after "Select several", never beside the trash alone.
        assertEquals(
            listOf(PillCell.FOLD, PillCell.COLOR, PillCell.TRASH, PillCell.SEVERAL, PillCell.SHARP),
            pillRow1(true, true, trash = true, several = true, color = true, sharp = true),
        )
    }

    @Test
    fun everyToolWithTheCellShowsSelectSeveralToo() {
        for (id in CURVE_KINDS) {
            val (_, tool) = fx.curve(id, FIVE)
            val e = requireNotNull(tool.pointCorners) { "$id has a corner editor" }
            for (sel in listOf(PointSelection.of(5, 0), PointSelection.of(5, 1, 3), PointSelection.all(5))) {
                tool.selectPoints(sel)
                assertNotNull("$id $sel: the cell shows", e.corner)
                assertTrue("$id $sel: so does Select several", tool.selectSeveralAvailable)
            }
            tool.discard()
            assertNull("$id discarded: no cell", e.corner)
        }
        val (_, shape) = fx.shapeGroup()
        val e = shape.pointCorners!!
        assertNotNull("Shape points: the cell shows", e.corner)
        assertTrue("Shape points: so does Select several", shape.selectSeveralAvailable)
        assertEquals(ToolId.SHAPE, shape.id)
    }
}
