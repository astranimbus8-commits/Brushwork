package com.brushwork.paint.ui.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.8 (design §3.9): how the vector object bar's buttons share its row ([ObjectBarLayout]).
 * The bar is the screen minus 16 dp, its buttons inside 4 dp of padding on each side: 368 dp on
 * the user's 392 dp phone, 336 dp at 360 dp. Ten buttons (the nine v1.6 ones and Array), eleven
 * units with the double-width detach button. When row 1 is short of room the least used leave it
 * first, a whole rank at a time: Array, the detach button, Forward and Backward, Front and Back...
 * v1.10 (A6, J26): the ranks are [ObjectBarSlot]'s (Deselect 9, Delete 10; Edit, 8, is covered by
 * 110/a6/ObjectBarLayout8At392Test and ObjectBarLayout6At360Test); without Edit the rows are v1.9's.
 */
class ObjectBarLayoutTest {
    // Delete, Duplicate, Forward, Backward, Front, Back, Recolor, Transform, Deselect, Array (+ Detach),
    // with the bar's ranks (higher stays longer).
    private val ten = List(10) { 1 }
    private val tenRanks = listOf(10, 5, 3, 3, 4, 4, 6, 7, 9, 1)
    private val withDetach = ten + 2
    private val withDetachRanks = tenRanks + 2

    private fun assertFits(name: String, units: List<Int>, ranks: List<Int>, inner: Float, s: ObjectBarLayout.Split) {
        assertTrue("$name: buttons finger-sized (${s.itemDp})", s.itemDp >= ObjectBarLayout.MIN_ITEM_DP - 1e-3f)
        assertTrue("$name: buttons at most the selection bar's (${s.itemDp})", s.itemDp <= ObjectBarLayout.MAX_ITEM_DP + 1e-3f)
        assertTrue("$name: row 1 (${s.rowDp} dp) fits $inner dp", s.rowDp <= inner + 0.5f)
        val used = s.row1.sumOf { units[it] }
        assertEquals("$name: row 1's width", used * s.itemDp + if (s.toggle) ObjectBarLayout.TOGGLE_DP else 0f, s.rowDp, 1e-3f)
        // Every button once; each row keeps the bar's order.
        assertEquals("$name: every button once", units.indices.toList(), (s.row1 + s.more.flatten()).sorted())
        assertEquals("$name: row 1 in order", s.row1.sorted(), s.row1)
        assertEquals("$name: the More row in order", s.more.flatten().sorted(), s.more.flatten())
        for (line in s.more) assertTrue("$name: a More line fits", line.sumOf { units[it] } * s.itemDp <= inner + 0.5f || line.size == 1)
        assertEquals("$name: a toggle exactly when something waits on the More row", s.more.isNotEmpty(), s.toggle)
        // The most used stay: every button left on row 1 ranks above every one that went.
        val waiting = s.more.flatten()
        if (waiting.isNotEmpty() && s.row1.isNotEmpty()) {
            assertTrue("$name: the least used leave first", waiting.maxOf { ranks[it] } < s.row1.minOf { ranks[it] })
        }
        // A pair (Forward and Backward, Front and Back) is never split between the rows.
        for ((a, b) in listOf(2 to 3, 4 to 5)) assertEquals("$name: $a and $b together", a in s.row1, b in s.row1)
    }

    @Test
    fun theRanksAreTheBars() {
        val slots = ObjectBarSlot.shown(edit = false, behind = false, detach = true, toShape = false)
        assertEquals(withDetach, slots.map { it.units })
        assertEquals(withDetachRanks, slots.map { it.rank })
    }

    @Test
    fun theUsersPhoneKeepsTheMostUsedSevenAndTheToggle() {
        val s = ObjectBarLayout.split(ten, tenRanks, 368f)
        assertFits("392 dp", ten, tenRanks, 368f, s)
        assertEquals("Delete, Duplicate, Front, Back, Recolor, Transform, Deselect", listOf(0, 1, 4, 5, 6, 7, 8), s.row1)
        assertTrue(s.toggle)
        assertEquals("46 dp, the selection bar's", 46f, s.itemDp, 1e-3f)
        assertEquals("Forward, Backward and Array on one More line", listOf(listOf(2, 3, 9)), s.more)
        assertEquals("7 × 46 + 44", 366f, s.rowDp, 1e-3f)

        val d = ObjectBarLayout.split(withDetach, withDetachRanks, 368f)
        assertFits("392 dp with copies", withDetach, withDetachRanks, 368f, d)
        assertEquals(listOf(0, 1, 4, 5, 6, 7, 8), d.row1)
        assertEquals("Forward, Backward, Array and Detach on one More line", listOf(listOf(2, 3, 9, 10)), d.more)
    }

    @Test
    fun at360TheSameSevenStay() {
        val s = ObjectBarLayout.split(ten, tenRanks, 336f)
        assertFits("360 dp", ten, tenRanks, 336f, s)
        assertEquals(listOf(0, 1, 4, 5, 6, 7, 8), s.row1)
        assertEquals("(336 − 44) / 7", 292f / 7f, s.itemDp, 1e-3f)
        assertEquals(listOf(listOf(2, 3, 9)), s.more)
        val d = ObjectBarLayout.split(withDetach, withDetachRanks, 336f)
        assertFits("360 dp with copies", withDetach, withDetachRanks, 336f, d)
        assertEquals(listOf(listOf(2, 3, 9, 10)), d.more)
    }

    @Test
    fun aWideScreenShowsEverythingOnOneRow() {
        for (inner in listOf(400f, 460f, 600f, 1200f)) {
            val s = ObjectBarLayout.split(ten, tenRanks, inner)
            assertFits("$inner dp", ten, tenRanks, inner, s)
            assertFalse("$inner dp: no toggle", s.toggle)
            assertEquals(ten.indices.toList(), s.row1)
            assertEquals("$inner dp: at most 46 dp each", (inner / 10f).coerceAtMost(46f), s.itemDp, 1e-3f)
        }
        val unbounded = ObjectBarLayout.split(withDetach, withDetachRanks, Float.POSITIVE_INFINITY)
        assertFalse(unbounded.toggle)
        assertEquals(46f, unbounded.itemDp, 0f)
        assertEquals(withDetach.indices.toList(), unbounded.row1)
    }

    @Test
    fun narrowScreensWrapTheMoreRow() {
        for (inner in listOf(84f, 120f, 160f, 200f, 240f, 280f, 300f, 336f, 368f, 399f)) {
            assertFits("$inner dp, ten buttons", ten, tenRanks, inner, ObjectBarLayout.split(ten, tenRanks, inner))
            assertFits("$inner dp, with Detach", withDetach, withDetachRanks, inner, ObjectBarLayout.split(withDetach, withDetachRanks, inner))
        }
        val tiny = ObjectBarLayout.split(ten, tenRanks, 120f)
        assertEquals("Delete beside the toggle", listOf(0), tiny.row1)
        assertTrue("the rest on several lines", tiny.more.size > 1)
    }
}
