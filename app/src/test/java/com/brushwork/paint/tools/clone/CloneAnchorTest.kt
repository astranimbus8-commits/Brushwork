package com.brushwork.paint.tools.clone

import com.brushwork.paint.core.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** v1.5 §4.2: the clone stamp's source and offset rules (Photoshop's Aligned). */
class CloneAnchorTest {

    @Test
    fun noSourceMeansNoOffset() {
        val a = CloneAnchor()
        assertNull(a.offsetFor(Vec2(10f, 10f), aligned = true))
        assertNull(a.offsetFor(Vec2(10f, 10f), aligned = false))
        a.strokeCompleted(CloneOffset(1, 1), aligned = true, end = Vec2(5f, 5f))
        assertNull("nothing is fixed without a source", a.fixed)
        assertNull(a.source)
    }

    @Test
    fun alignedKeepsTheOffsetOfTheFirstStrokeAndTheSourceTravels() {
        val a = CloneAnchor()
        a.set(Vec2(20f, 50f))
        val first = a.offsetFor(Vec2(100f, 50f), aligned = true)
        assertEquals(CloneOffset(80, 0), first)
        a.strokeCompleted(first!!, aligned = true, end = Vec2(130f, 60f))
        assertEquals(first, a.fixed)
        assertEquals("the source moved along with the stroke", Vec2(50f, 60f), a.source)
        // The next strokes keep the offset, wherever they start.
        assertEquals(CloneOffset(80, 0), a.offsetFor(Vec2(100f, 120f), aligned = true))
        assertEquals(CloneOffset(80, 0), a.offsetFor(Vec2(3f, 7f), aligned = true))
        assertEquals(Vec2(20f, 120f), CloneOffset(80, 0).sourceOf(Vec2(100f, 120f)))
        a.strokeCompleted(CloneOffset(80, 0), aligned = true, end = Vec2(90f, 10f))
        assertEquals("the first stroke's offset stays", first, a.fixed)
        assertEquals(Vec2(10f, 10f), a.source)
    }

    @Test
    fun nonAlignedStartsEveryStrokeAtTheSource() {
        val a = CloneAnchor()
        a.set(Vec2(20f, 50f))
        val first = a.offsetFor(Vec2(100f, 50f), aligned = false)!!
        a.strokeCompleted(first, aligned = false, end = Vec2(150f, 70f))
        assertNull(a.fixed)
        assertEquals("the source stays put", Vec2(20f, 50f), a.source)
        assertEquals(CloneOffset(80, 70), a.offsetFor(Vec2(100f, 120f), aligned = false))
        assertEquals(Vec2(20f, 50f), CloneOffset(80, 70).sourceOf(Vec2(100f, 120f)))
    }

    @Test
    fun resettingTheSourceOrAlignedStartsOver() {
        val a = CloneAnchor()
        a.set(Vec2(20f, 50f))
        a.strokeCompleted(a.offsetFor(Vec2(100f, 50f), aligned = true)!!, aligned = true, end = Vec2(100f, 50f))
        // A new source: the next stroke fixes a new offset.
        a.set(Vec2(30f, 30f))
        assertNull(a.fixed)
        assertEquals(CloneOffset(10, 10), a.offsetFor(Vec2(40f, 40f), aligned = true))
        a.strokeCompleted(CloneOffset(10, 10), aligned = true, end = Vec2(40f, 40f))
        // Switching Aligned starts over from the (travelled) source point.
        a.resetAlignment()
        assertEquals(Vec2(30f, 30f), a.source)
        assertEquals(CloneOffset(70, 0), a.offsetFor(Vec2(100f, 30f), aligned = true))
        // A cancelled gesture puts the saved state back.
        a.restore(Vec2(1f, 2f), CloneOffset(5, 5))
        assertEquals(Vec2(1f, 2f), a.source)
        assertEquals(CloneOffset(5, 5), a.offsetFor(Vec2(0f, 0f), aligned = true))
        a.clear()
        assertNull(a.source)
        assertNull(a.fixed)
    }

    @Test
    fun aCancelledFirstStrokeFixesNothing() {
        val a = CloneAnchor()
        a.set(Vec2(20f, 50f))
        a.offsetFor(Vec2(100f, 50f), aligned = true) // stroke started, then cancelled: no strokeCompleted
        assertNull(a.fixed)
        assertEquals(CloneOffset(10, 0), a.offsetFor(Vec2(30f, 50f), aligned = true))
    }

    @Test
    fun nonFiniteSourcesAreIgnoredAndHugeOnesClamped() {
        val a = CloneAnchor()
        a.set(Vec2(Float.NaN, 1f))
        a.set(Vec2(1f, Float.NEGATIVE_INFINITY))
        assertNull(a.source)
        a.set(Vec2(4f, 5f))
        a.set(Vec2(Float.POSITIVE_INFINITY, 0f))
        assertEquals("a bad point leaves the source as it was", Vec2(4f, 5f), a.source)
        val m = CloneAnchor.MAX_COORD
        a.set(Vec2(3e9f, -3e9f))
        assertEquals(Vec2(m, -m), a.source)
        // The offset math stays in range (no NaN rounding, no Int overflow).
        assertEquals(CloneOffset(-1_000_000, 1_000_000), a.offsetFor(Vec2(0f, 0f), aligned = false))
        a.strokeCompleted(CloneOffset(-2_000_000, 0), aligned = true, end = Vec2(0f, 0f))
        assertEquals("a source carried far by a stroke stays in range", Vec2(m, 0f), a.source)
    }

    @Test
    fun offsetsAreWholePixels() {
        val a = CloneAnchor()
        a.set(Vec2(20.2f, 50f))
        assertEquals(CloneOffset(80, 0), a.offsetFor(Vec2(100.6f, 50.4f), aligned = false))
        assertEquals(CloneOffset(81, 1), a.offsetFor(Vec2(100.8f, 50.6f), aligned = false))
        assertEquals(CloneOffset(-20, -50), a.offsetFor(Vec2(0f, 0f), aligned = false))
    }
}
