package com.brushwork.paint.v110.a6

import com.brushwork.paint.ui.vector.ObjectBarLayout
import com.brushwork.paint.ui.vector.ObjectBarSlot
import com.brushwork.paint.ui.vector.ObjectBarSlot.ARRAY
import com.brushwork.paint.ui.vector.ObjectBarSlot.BACK
import com.brushwork.paint.ui.vector.ObjectBarSlot.BACKWARD
import com.brushwork.paint.ui.vector.ObjectBarSlot.BEHIND
import com.brushwork.paint.ui.vector.ObjectBarSlot.DELETE
import com.brushwork.paint.ui.vector.ObjectBarSlot.DESELECT
import com.brushwork.paint.ui.vector.ObjectBarSlot.DETACH
import com.brushwork.paint.ui.vector.ObjectBarSlot.DUPLICATE
import com.brushwork.paint.ui.vector.ObjectBarSlot.EDIT
import com.brushwork.paint.ui.vector.ObjectBarSlot.FORWARD
import com.brushwork.paint.ui.vector.ObjectBarSlot.FRONT
import com.brushwork.paint.ui.vector.ObjectBarSlot.RECOLOR
import com.brushwork.paint.ui.vector.ObjectBarSlot.TO_SHAPE
import com.brushwork.paint.ui.vector.ObjectBarSlot.TRANSFORM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.10 A6 (R10, J26): the object bar's ranks and row 1 on the user's 392 dp phone (368 dp inside
 * the bar's padding, 324 dp beside the 44 dp More toggle). With Edit, row 1 holds 8 buttons at
 * 40.5 dp: Delete, Duplicate, Front, Back, Recolor, Transform, Edit, Deselect; Forward, Backward,
 * Array, Behind, Detach and "Turn into shape" wait on one More line, in that order.
 */
class ObjectBarLayout8At392Test {
    private val inner392 = 368f

    private fun row1(s: ObjectBarLayout.Split, slots: List<ObjectBarSlot>) = s.row1.map { slots[it] }

    private fun more(s: ObjectBarLayout.Split, slots: List<ObjectBarSlot>) = s.more.map { line -> line.map { slots[it] } }

    @Test
    fun theRanksAreJ26s() {
        val ranks = ObjectBarSlot.entries.associateWith { it.rank }
        assertEquals(
            mapOf(
                ARRAY to 1, BEHIND to 1, DETACH to 2, TO_SHAPE to 2, FORWARD to 3, BACKWARD to 3, FRONT to 4, BACK to 4,
                DUPLICATE to 5, RECOLOR to 6, TRANSFORM to 7, EDIT to 8, DESELECT to 9, DELETE to 10,
            ),
            ranks,
        )
        assertEquals("Detach and Turn into shape are 2 units", setOf(DETACH, TO_SHAPE), ObjectBarSlot.entries.filter { it.units == 2 }.toSet())
        assertTrue("every other button is 1 unit", ObjectBarSlot.entries.all { it.units == 1 || it in setOf(DETACH, TO_SHAPE) })
        assertEquals(
            "row order: Edit right after Transform, Behind right after Array, Turn into shape after Detach",
            listOf(DELETE, DUPLICATE, FORWARD, BACKWARD, FRONT, BACK, RECOLOR, TRANSFORM, EDIT, DESELECT, ARRAY, BEHIND, DETACH, TO_SHAPE),
            ObjectBarSlot.shown(edit = true, behind = true, detach = true, toShape = true),
        )
    }

    @Test
    fun onePathSelectedKeepsEightAt40AndAHalf() {
        val slots = ObjectBarSlot.shown(edit = true, behind = false, detach = false, toShape = true)
        val s = ObjectBarSlot.split(slots, inner392)
        assertEquals(listOf(DELETE, DUPLICATE, FRONT, BACK, RECOLOR, TRANSFORM, EDIT, DESELECT), row1(s, slots))
        assertEquals("324 / 8", 40.5f, s.itemDp, 1e-3f)
        assertTrue(s.toggle)
        assertEquals("8 × 40.5 + 44", 368f, s.rowDp, 1e-3f)
        assertEquals("one More line", listOf(listOf(FORWARD, BACKWARD, ARRAY, TO_SHAPE)), more(s, slots))
    }

    @Test
    fun everyMoreItemStillLeavesTheSameEight() {
        val slots = ObjectBarSlot.shown(edit = true, behind = true, detach = true, toShape = true)
        val s = ObjectBarSlot.split(slots, inner392)
        assertEquals(listOf(DELETE, DUPLICATE, FRONT, BACK, RECOLOR, TRANSFORM, EDIT, DESELECT), row1(s, slots))
        assertEquals(40.5f, s.itemDp, 1e-3f)
        assertEquals(
            "Forward, Backward, Array, Behind, Detach (2), Turn into shape (2): 8 units on one line",
            listOf(listOf(FORWARD, BACKWARD, ARRAY, BEHIND, DETACH, TO_SHAPE)),
            more(s, slots),
        )
    }

    @Test
    fun oneShapeSelectedHasEditButNoTurnIntoShape() {
        val slots = ObjectBarSlot.shown(edit = true, behind = false, detach = false, toShape = false)
        val s = ObjectBarSlot.split(slots, inner392)
        assertEquals(listOf(DELETE, DUPLICATE, FRONT, BACK, RECOLOR, TRANSFORM, EDIT, DESELECT), row1(s, slots))
        assertEquals(40.5f, s.itemDp, 1e-3f)
        assertEquals(listOf(listOf(FORWARD, BACKWARD, ARRAY)), more(s, slots))
    }

    @Test
    fun withoutEditTheBarIsV19s() {
        // Several objects or a brush stroke: v1.9's seven at 46 dp.
        val slots = ObjectBarSlot.shown(edit = false, behind = false, detach = false, toShape = false)
        val s = ObjectBarSlot.split(slots, inner392)
        assertEquals(listOf(DELETE, DUPLICATE, FRONT, BACK, RECOLOR, TRANSFORM, DESELECT), row1(s, slots))
        assertEquals(46f, s.itemDp, 1e-3f)
        assertEquals(listOf(listOf(FORWARD, BACKWARD, ARRAY)), more(s, slots))
    }
}
