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
 * v1.10 A6 (R10, finding 35): row 1 at 360 dp (336 dp inside the bar's padding, 292 dp beside the
 * toggle). With Edit, Front and Back join the More row and 6 buttons show at 46 dp; a brush stroke
 * has no Edit, so 7 fit and Front and Back stay on row 1.
 */
class ObjectBarLayout6At360Test {
    private val inner360 = 336f

    private fun row1(s: ObjectBarLayout.Split, slots: List<ObjectBarSlot>) = s.row1.map { slots[it] }

    private fun more(s: ObjectBarLayout.Split, slots: List<ObjectBarSlot>) = s.more.map { line -> line.map { slots[it] } }

    @Test
    fun onePathSelectedKeepsSixAt46() {
        val slots = ObjectBarSlot.shown(edit = true, behind = false, detach = false, toShape = true)
        val s = ObjectBarSlot.split(slots, inner360)
        assertEquals(listOf(DELETE, DUPLICATE, RECOLOR, TRANSFORM, EDIT, DESELECT), row1(s, slots))
        assertEquals(46f, s.itemDp, 1e-3f)
        assertTrue(s.toggle)
        assertTrue("row 1 fits", s.rowDp <= inner360 + 0.5f)
        assertEquals(
            "Forward, Backward, Front, Back, Array, Turn into shape (2): 7 units on one line",
            listOf(listOf(FORWARD, BACKWARD, FRONT, BACK, ARRAY, TO_SHAPE)),
            more(s, slots),
        )
    }

    @Test
    fun aShapeWithEveryMoreItemKeepsTheSameSix() {
        val slots = ObjectBarSlot.shown(edit = true, behind = true, detach = true, toShape = false)
        val s = ObjectBarSlot.split(slots, inner360)
        assertEquals(listOf(DELETE, DUPLICATE, RECOLOR, TRANSFORM, EDIT, DESELECT), row1(s, slots))
        assertEquals(46f, s.itemDp, 1e-3f)
        // 7 units fit a line (336 / 46): Detach's 2 units start the second.
        assertEquals(listOf(listOf(FORWARD, BACKWARD, FRONT, BACK, ARRAY, BEHIND), listOf(DETACH)), more(s, slots))
    }

    @Test
    fun aStrokeKeepsFrontAndBackOnRow1() {
        // A brush stroke: no Edit, no Turn into shape.
        val slots = ObjectBarSlot.shown(edit = false, behind = false, detach = false, toShape = false)
        val s = ObjectBarSlot.split(slots, inner360)
        assertEquals(listOf(DELETE, DUPLICATE, FRONT, BACK, RECOLOR, TRANSFORM, DESELECT), row1(s, slots))
        assertEquals("(336 − 44) / 7", 292f / 7f, s.itemDp, 1e-3f)
        assertEquals(listOf(listOf(FORWARD, BACKWARD, ARRAY)), more(s, slots))
        // With mirror copies too (Detach): still seven.
        val withCopies = ObjectBarSlot.shown(edit = false, behind = false, detach = true, toShape = false)
        val d = ObjectBarSlot.split(withCopies, inner360)
        assertEquals(listOf(DELETE, DUPLICATE, FRONT, BACK, RECOLOR, TRANSFORM, DESELECT), row1(d, withCopies))
    }
}
