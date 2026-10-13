package com.brushwork.paint.vector.select

import android.graphics.RectF
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** v1.9 (R19, design 2.4.11): [ArrangeOrder.step] (fields only: RectF's methods are stubs in plain JVM tests). */
class ArrangeOrderTest {

    private fun r(l: Float, t: Float, rr: Float, b: Float) = RectF().also { it.left = l; it.top = t; it.right = rr; it.bottom = b }

    /** A 10 x 10 square at column [x] of row 0. */
    private fun sq(x: Float) = r(x, 0f, x + 10f, 10f)

    private val far = 1000f

    private fun <T> List<T>.permuted(perm: IntArray): List<T> = perm.map { this[it] }

    @Test
    fun forwardGoesPastTheFirstOverlappingObjectAboveSkippingTheOthers() {
        // a, b (apart from a), c (over a): a goes right above c.
        val bounds = listOf(sq(0f), sq(far), sq(5f))
        val perm = ArrangeOrder.step(bounds, setOf(0), 1)!!
        assertArrayEquals(intArrayOf(1, 2, 0), perm)
        assertEquals(listOf("b", "c", "a"), listOf("a", "b", "c").permuted(perm))
    }

    @Test
    fun withNothingOverlappingItTakesOnePlainStep() {
        val bounds = listOf(sq(0f), sq(far), sq(2 * far))
        assertArrayEquals(intArrayOf(1, 0, 2), ArrangeOrder.step(bounds, setOf(0), 1))
        assertArrayEquals(intArrayOf(0, 2, 1), ArrangeOrder.step(bounds, setOf(2), -1))
    }

    @Test
    fun backwardMirrorsForward() {
        // c (under a), b (apart), a: a goes right below c.
        val bounds = listOf(sq(5f), sq(far), sq(0f))
        assertArrayEquals(intArrayOf(2, 0, 1), ArrangeOrder.step(bounds, setOf(2), -1))
    }

    @Test
    fun nothingMovesAtTheEndsOrWithoutAValidSelectionOrDirection() {
        val bounds = listOf(sq(0f), sq(5f), sq(8f))
        assertNull(ArrangeOrder.step(bounds, setOf(2), 1))
        assertNull(ArrangeOrder.step(bounds, setOf(0), -1))
        assertNull(ArrangeOrder.step(bounds, emptySet(), 1))
        assertNull(ArrangeOrder.step(bounds, setOf(0, 1, 2), 1))
        assertNull(ArrangeOrder.step(bounds, setOf(1), 0))
        assertNull(ArrangeOrder.step(bounds, setOf(1), 2))
        assertNull(ArrangeOrder.step(bounds, setOf(7, -1), 1))
        assertNull(ArrangeOrder.step(emptyList(), setOf(0), 1))
        // Indices outside the list are ignored.
        assertArrayEquals(intArrayOf(0, 2, 1), ArrangeOrder.step(bounds, setOf(1, 9), 1))
    }

    @Test
    fun selectedObjectsNeverPassEachOther() {
        // s1, x, s2, y, z: s1 overlaps only z (above s2), s2 overlaps nothing.
        val s1 = r(0f, 0f, 10f, 10f)
        val z = r(5f, 5f, 15f, 15f)
        val bounds = listOf(s1, sq(far), sq(2 * far), sq(3 * far), z)
        val perm = ArrangeOrder.step(bounds, setOf(0, 2), 1)!!
        // s2 takes one plain step past y; s1 one plain step past x (z lies beyond s2).
        assertEquals(listOf("x", "s1", "y", "s2", "z"), listOf("s1", "x", "s2", "y", "z").permuted(perm))
        // Backward: the same from the top down.
        val back = ArrangeOrder.step(listOf(z, sq(far), sq(2 * far), sq(3 * far), s1), setOf(2, 4), -1)!!
        assertEquals(listOf("z", "s2", "y", "s1", "x"), listOf("z", "y", "s2", "x", "s1").permuted(back))
    }

    @Test
    fun eachSelectedObjectLooksAtItsOwnOverlaps() {
        // s1, a (over s1), s2, b (over s2): each goes past its own.
        val bounds = listOf(sq(0f), sq(5f), sq(100f), sq(105f))
        val perm = ArrangeOrder.step(bounds, setOf(0, 2), 1)!!
        assertEquals(listOf("a", "s1", "b", "s2"), listOf("s1", "a", "s2", "b").permuted(perm))
    }

    @Test
    fun touchingEmptyAndNanBoundsDoNotOverlap() {
        // a, b touching a's right edge, c over a: a goes past c (b does not count).
        val touching = listOf(sq(0f), sq(10f), sq(5f))
        assertArrayEquals(intArrayOf(1, 2, 0), ArrangeOrder.step(touching, setOf(0), 1))
        val empty = listOf(sq(0f), r(2f, 2f, 2f, 8f), sq(5f))
        assertArrayEquals(intArrayOf(1, 2, 0), ArrangeOrder.step(empty, setOf(0), 1))
        val nan = listOf(sq(0f), r(Float.NaN, 0f, 10f, 10f), sq(5f))
        assertArrayEquals(intArrayOf(1, 2, 0), ArrangeOrder.step(nan, setOf(0), 1))
        // A selected object with empty bounds takes a plain step.
        assertArrayEquals(intArrayOf(1, 0, 2), ArrangeOrder.step(listOf(r(0f, 0f, 0f, 0f), sq(0f), sq(0f)), setOf(0), 1))
    }
}
