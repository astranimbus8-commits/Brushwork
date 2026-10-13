package com.brushwork.paint.tools.points

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.vector.CornerStyle
import com.brushwork.paint.tools.vector.ShapeAnchor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** v1.9 (R7, design 2.4.10): [PointBevel]'s geometry, its origin row and its predicates. */
class PointBevelTest {

    private fun p(x: Float, y: Float, w: Float = 1f) = BevelPoint(x, y, w)
    private val all: (Int) -> Boolean = { true }
    private val corner = listOf(p(0f, 0f), p(10f, 0f), p(10f, 10f))
    private val square = listOf(p(0f, 0f), p(10f, 0f), p(10f, 10f), p(0f, 10f))

    private fun ok(r: PointBevel.Result): PointBevel.Result.Ok {
        assertTrue("expected Ok, got $r", r is PointBevel.Result.Ok)
        return r as PointBevel.Result.Ok
    }

    private fun assertPoint(expected: BevelPoint, actual: BevelPoint) {
        assertEquals("x of $actual", expected.x, actual.x, 1e-4f)
        assertEquals("y of $actual", expected.y, actual.y, 1e-4f)
        assertEquals("width of $actual", expected.width, actual.width, 1e-4f)
    }

    @Test
    fun oneSegmentIsAChamferAtTheAmountOfTheShorterEdge() {
        val r = ok(PointBevel.bevel(corner, closed = false, sharp = all, selected = setOf(1), amount = 0.5f, segments = 1, maxPoints = 100))
        assertEquals(4, r.points.size)
        assertPoint(p(0f, 0f), r.points[0])
        assertPoint(p(5f, 0f), r.points[1])
        assertPoint(p(10f, 5f), r.points[2])
        assertPoint(p(10f, 10f), r.points[3])
        assertEquals(setOf(1, 2), r.selected)
        assertEquals(1, r.bevelled)
        // The shorter edge decides: 0.25 of min(10, 4) = 1.
        val uneven = listOf(p(0f, 0f), p(10f, 0f), p(10f, 4f))
        val u = ok(PointBevel.bevel(uneven, false, all, setOf(1), 0.25f, 1, 100))
        assertPoint(p(9f, 0f), u.points[1])
        assertPoint(p(10f, 1f), u.points[2])
    }

    @Test
    fun originMapsUntouchedPointsToTheirSourceAndMadePointsToMinusOne() {
        val line = listOf(p(0f, 0f), p(10f, 0f), p(10f, 10f), p(20f, 10f), p(20f, 20f))
        val r = ok(PointBevel.bevel(line, false, all, setOf(1, 3), 0.2f, 2, 100))
        assertArrayEquals(intArrayOf(0, -1, -1, -1, 2, -1, -1, -1, 4), r.origin)
        assertEquals(r.points.size, r.origin.size)
        for ((i, o) in r.origin.withIndex()) {
            if (o >= 0) assertEquals(line[o], r.points[i]) else assertTrue(i in r.selected)
        }
        // Output 0 is source point 0 when it was not bevelled.
        assertEquals(0, r.origin[0])
        assertEquals(setOf(1, 2, 3, 5, 6, 7), r.selected)
        assertEquals(2, r.bevelled)
    }

    @Test
    fun outputZeroIsTheFirstBevelPointWhenPointZeroWasBevelledOnAClosedLine() {
        val r = ok(PointBevel.bevel(square, closed = true, sharp = all, selected = setOf(0), amount = 0.5f, segments = 1, maxPoints = 100))
        assertArrayEquals(intArrayOf(-1, -1, 1, 2, 3), r.origin)
        // From the incoming edge (from point 3) to the outgoing edge (to point 1).
        assertPoint(p(0f, 5f), r.points[0])
        assertPoint(p(5f, 0f), r.points[1])
        assertEquals(setOf(0, 1), r.selected)
    }

    @Test
    fun moreSegmentsLieOnTheArcTangentToBothEdges() {
        val r = ok(PointBevel.bevel(corner, false, all, setOf(1), 0.5f, 4, 100))
        assertEquals(3 + 4, r.points.size)
        // d = 5 at a right angle: the centre is (5, 5) and the radius d tan(45°) = 5.
        val made = (1..5).map { r.points[it] }
        assertPoint(p(5f, 0f), made.first())
        assertPoint(p(10f, 5f), made.last())
        for (m in made) assertEquals(5f, hypot(m.x - 5f, m.y - 5f), 1e-3f)
        // The arc faces the corner: every made point lies between the chord and the corner (10, 0).
        for (m in made.subList(1, 4)) assertTrue("$m bulges toward the corner", m.x - m.y > 5f)
        // Evenly spaced along the arc.
        val steps = made.zipWithNext { a, b -> hypot(a.x - b.x, a.y - b.y) }
        for (s in steps) assertEquals(steps[0], s, 1e-3f)
    }

    @Test
    fun anAlmostStraightCornerGetsItsPointsOnTheChord() {
        val line = listOf(p(0f, 0f), p(10f, 0f), p(20f, 0f))
        val r = ok(PointBevel.bevel(line, false, all, setOf(1), 0.5f, 3, 100))
        assertEquals(6, r.points.size)
        for (i in 1..4) assertEquals(0f, r.points[i].y, 1e-4f)
        assertPoint(p(5f, 0f), r.points[1])
        assertPoint(p(15f, 0f), r.points[4])
    }

    @Test
    fun widthsAreInterpolatedAlongTheNewPoints() {
        val line = listOf(p(0f, 0f, 1f), p(10f, 0f, 3f), p(10f, 10f, 5f))
        val r = ok(PointBevel.bevel(line, false, all, setOf(1), 0.5f, 2, 100))
        assertEquals(2f, r.points[1].width, 1e-4f)
        assertEquals(3f, r.points[2].width, 1e-4f)
        assertEquals(4f, r.points[3].width, 1e-4f)
    }

    @Test
    fun endsOfAnOpenLineUnselectedAndNotSharpPointsAreSkipped() {
        assertEquals(PointBevel.Result.NoSharpCorner, PointBevel.bevel(corner, false, all, setOf(0, 2), 0.2f, 1, 100))
        assertEquals(PointBevel.Result.NoSharpCorner, PointBevel.bevel(corner, false, all, emptySet(), 0.2f, 1, 100))
        assertEquals(PointBevel.Result.NoSharpCorner, PointBevel.bevel(corner, false, { false }, setOf(1), 0.2f, 1, 100))
        // A zero-length edge has no direction.
        val doubled = listOf(p(0f, 0f), p(10f, 0f), p(10f, 0f), p(10f, 10f))
        assertEquals(PointBevel.Result.NoSharpCorner, PointBevel.bevel(doubled, false, all, setOf(1, 2), 0.2f, 1, 100))
        // A closed line of two points has no corner.
        assertEquals(PointBevel.Result.NoSharpCorner, PointBevel.bevel(corner.take(2), true, all, setOf(0, 1), 0.2f, 1, 100))
    }

    @Test
    fun tooManyWhenTheResultWouldPassTheLimit() {
        // 4 points + 4 corners x 3 segments = 16.
        assertEquals(PointBevel.Result.TooMany(15), PointBevel.bevel(square, true, all, setOf(0, 1, 2, 3), 0.2f, 3, 15))
        assertEquals(16, ok(PointBevel.bevel(square, true, all, setOf(0, 1, 2, 3), 0.2f, 3, 16)).points.size)
    }

    @Test
    fun amountAndSegmentsAreClamped() {
        assertEquals(0.5f, PointBevel.clampAmount(2f))
        assertEquals(0.01f, PointBevel.clampAmount(0f))
        assertEquals(0.01f, PointBevel.clampAmount(Float.NaN))
        assertEquals(1, PointBevel.clampSegments(0))
        assertEquals(PointBevel.MAX_SEGMENTS, PointBevel.clampSegments(20))
        val r = ok(PointBevel.bevel(corner, false, all, setOf(1), 9f, 20, 100))
        assertEquals(3 + PointBevel.MAX_SEGMENTS, r.points.size)
        assertPoint(p(5f, 0f), r.points[1])
    }

    @Test
    fun neighbouringBevelsAtHalfMeetWithoutCrossing() {
        val r = ok(PointBevel.bevel(square, true, all, setOf(0, 1, 2, 3), 0.5f, 1, 100))
        assertEquals(8, r.points.size)
        assertEquals(4, r.bevelled)
        // The cut of corner 0 on edge 0-1 and that of corner 1 on the same edge meet at its middle.
        assertPoint(r.points[1], r.points[2])
    }

    @Test
    fun resultsCompareByContent() {
        val a = PointBevel.bevel(corner, false, all, setOf(1), 0.3f, 2, 100)
        val b = PointBevel.bevel(corner, false, all, setOf(1), 0.3f, 2, 100)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun polylinePredicateIsEveryInnerPointOrEveryPointOfAClosedLine() {
        val open = PointBevel.polylineSharp(4, closed = false)
        assertEquals(listOf(false, true, true, false), (0 until 4).map(open))
        val closed = PointBevel.polylineSharp(4, closed = true)
        assertEquals(listOf(true, true, true, true), (0 until 4).map(closed))
        assertEquals(false, open(4))
    }

    @Test
    fun shapePredicateNeedsAPlainUnroundedCornerBetweenStraightSegments() {
        fun a(x: Float, y: Float, smooth: Boolean = false, hIn: Vec2? = null, hOut: Vec2? = null, radius: Float? = null) =
            ShapeAnchor(Vec2(x, y), smooth, hIn, hOut, radius)
        val plain = listOf(a(0f, 0f), a(10f, 0f), a(10f, 10f), a(0f, 10f))
        val sharp = PointBevel.shapeSharp(plain, closed = true, corner = CornerStyle.SHARP, radius = 8f)
        assertEquals(listOf(true, true, true, true), (0 until 4).map(sharp))
        // The shape's own rounding takes every corner without a radius of its own.
        val round = PointBevel.shapeSharp(plain, true, CornerStyle.ROUND, 8f)
        assertEquals(listOf(false, false, false, false), (0 until 4).map(round))
        assertEquals(true, PointBevel.shapeSharp(plain, true, CornerStyle.ROUND, 0f)(1))
        // A point's own radius: 0 keeps it sharp under a round shape, above 0 rounds it under a sharp one.
        val own = listOf(a(0f, 0f), a(10f, 0f, radius = 0f), a(10f, 10f, radius = 4f), a(0f, 10f))
        assertEquals(true, PointBevel.shapeSharp(own, true, CornerStyle.ROUND, 8f)(1))
        assertEquals(false, PointBevel.shapeSharp(own, true, CornerStyle.SHARP, 8f)(2))
        // Smooth, with handles, or next to a curved segment: not sharp.
        val curved = listOf(a(0f, 0f), a(10f, 0f, smooth = true), a(10f, 10f, hOut = Vec2(1f, 1f)), a(0f, 10f))
        val c = PointBevel.shapeSharp(curved, true, CornerStyle.SHARP, 0f)
        assertEquals(listOf(false, false, false, false), (0 until 4).map(c))
        // The ends of an open outline are skipped; open outlines are never rounded.
        val open = PointBevel.shapeSharp(plain, closed = false, corner = CornerStyle.ROUND, radius = 8f)
        assertEquals(listOf(false, true, true, false), (0 until 4).map(open))
    }
}
