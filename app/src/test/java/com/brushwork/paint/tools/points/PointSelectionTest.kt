package com.brushwork.paint.tools.points

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.7 (item 1, design §3.1, I12): the immutable selection of one object's points. */
class PointSelectionTest {

    @Test
    fun buildsAndQueries() {
        val none = PointSelection.none(5)
        assertTrue(none.isEmpty)
        assertEquals(0, none.count)
        assertEquals(-1, none.primary)
        assertEquals(5, none.size)

        val s = PointSelection.of(5, 3, 1, 9, -1)
        assertEquals(listOf(1, 3), s.indices)
        assertEquals(1, s.primary)
        assertTrue(1 in s)
        assertFalse(2 in s)
        assertFalse(s.isSingle)

        val all = PointSelection.all(4)
        assertEquals(listOf(0, 1, 2, 3), all.indices)
        assertEquals(3, all.primary)
        assertTrue(PointSelection.all(0).isEmpty)

        val one = none.only(2)
        assertTrue(one.isSingle)
        assertEquals(2, one.primary)
        assertEquals(none, none.only(7))
    }

    @Test
    fun addRemoveAndToggleKeepThePrimaryAMember() {
        var s = PointSelection.none(6).plus(4).plus(1).plus(5)
        assertEquals(listOf(1, 4, 5), s.indices)
        assertEquals(5, s.primary)
        // Adding a selected point again only moves the primary.
        s = s.plus(1)
        assertEquals(listOf(1, 4, 5), s.indices)
        assertEquals(1, s.primary)
        // Removing the primary: the largest left becomes it.
        s = s.minus(1)
        assertEquals(listOf(4, 5), s.indices)
        assertEquals(5, s.primary)
        s = s.minus(3)
        assertEquals(listOf(4, 5), s.indices)
        s = s.toggled(4).toggled(0)
        assertEquals(listOf(0, 5), s.indices)
        assertEquals(0, s.primary)
        s = s.toggled(0).toggled(5)
        assertTrue(s.isEmpty)
        assertEquals(-1, s.primary)
        val many = PointSelection.none(10).plusAll(listOf(7, 2, 9, 2))
        assertEquals(listOf(2, 7, 9), many.indices)
        assertEquals(2, many.primary)
        // Out of range: unchanged.
        val same = many.plus(10)
        assertSame(many, same)
    }

    @Test
    fun insertsAndRemovesShiftIndices() {
        val s = PointSelection.of(6, 1, 4, 2)
        val ins = s.afterInsert(2)
        assertEquals(7, ins.size)
        assertEquals(listOf(1, 3, 5), ins.indices)
        assertEquals(3, ins.primary)
        val end = s.afterInsert(6)
        assertEquals(listOf(1, 2, 4), end.indices)
        assertEquals(7, end.size)

        // Remove 0 and 2 (before the removal): 2 leaves, 1 -> 0, 4 -> 2; the primary (2) was removed.
        val rem = s.afterRemove(listOf(2, 0))
        assertEquals(4, rem.size)
        assertEquals(listOf(0, 2), rem.indices)
        assertEquals(2, rem.primary)
        // The primary follows its point.
        val rem2 = PointSelection.of(6, 1, 4).afterRemove(listOf(0, 2))
        assertEquals(listOf(0, 2), rem2.indices)
        assertEquals(2, rem2.primary)
        assertSame(s, s.afterRemove(emptyList()))

        val small = PointSelection.of(6, 5, 1, 3).resized(4)
        assertEquals(4, small.size)
        assertEquals(listOf(1, 3), small.indices)
        assertEquals(3, small.primary)
        assertEquals(PointSelection.none(0), PointSelection.all(3).resized(0))
    }

    @Test
    fun equalityIsByContents() {
        assertEquals(PointSelection.of(5, 1, 2), PointSelection.of(5, 1, 2))
        assertEquals(PointSelection.of(5, 1, 2).hashCode(), PointSelection.of(5, 1, 2).hashCode())
        assertNotEquals(PointSelection.of(5, 1, 2), PointSelection.of(5, 2, 1))
        assertNotEquals(PointSelection.of(5, 1, 2), PointSelection.of(6, 1, 2))
        assertNotEquals(PointSelection.of(5, 1), PointSelection.of(5, 1, 2))
    }
}
