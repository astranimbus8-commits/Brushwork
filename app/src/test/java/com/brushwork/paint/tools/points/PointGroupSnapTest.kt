package com.brushwork.paint.tools.points

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.transform.DocBox
import com.brushwork.paint.tools.transform.GridSnap
import com.brushwork.paint.tools.transform.PeriodicLines
import com.brushwork.paint.tools.transform.SnapGuides
import com.brushwork.paint.tools.transform.SnapLine
import com.brushwork.paint.tools.transform.SnapTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v1.10 (A5, R8): the pure rule of `PointGroupSnap`. Per axis the moving box's min, centre and
 * max lines, then every moving point (the grabbed one first) are the candidates; the smallest
 * correction within reach moves the group; ties go to the earlier candidate; an axis no target
 * placed follows the grid (the grabbed point, else the box's min corner), else the Length step.
 * The snap distance is 8 doc px here; targets are the 200 x 200 canvas (0, 100, 200) plus extra
 * point lines.
 */
class PointGroupSnapTest {

    /** What the session would answer, from the canvas and [extra], plus the guides it would show. */
    private class Session(extra: List<SnapLine> = emptyList()) {
        val targets = SnapTargets.build(200f, 200f, emptyList(), extra = extra)
        var guideBox: DocBox? = null
        var guidesHidden = false
        fun snap(grid: GridSnap? = null, step: Float? = null) = PointGroupSnap(
            objects = { v, axis -> SnapGuides.snapValue(v, axis, targets, 8f) },
            showGuides = { b -> guideBox = b; guidesHidden = b == null },
            grid = grid,
            step = step,
        )
    }

    /** Two points (30, 40) and (70, 60): the box 30..70 x 40..60, its centre (50, 50). */
    private val two = floatArrayOf(30f, 40f, 70f, 60f)
    private val twoBox = DocBox(30f, 40f, 70f, 60f)

    /** A (30, 30), B (60, 30), C (20, 60), D (80, 60): the box 20..80 x 30..60, A and B inside it on x. */
    private val four = floatArrayOf(30f, 30f, 60f, 30f, 20f, 60f, 80f, 60f)
    private val fourBox = DocBox(20f, 30f, 80f, 60f)

    private fun assertVec(expected: Vec2, actual: Vec2) {
        assertEquals("x of $actual", expected.x, actual.x, 1e-4f)
        assertEquals("y of $actual", expected.y, actual.y, 1e-4f)
    }

    @Test
    fun theBoxCentreSnapsWhenItIsTheClosest() {
        val s = Session()
        // Centre x 50 + 47 = 97: 3 px from the canvas centre; the points (77, 117) are 23 / 17 off.
        val d = s.snap().snap(two, twoBox, -1, Vec2(47f, 0f))
        assertVec(Vec2(50f, 0f), d)
    }

    @Test
    fun aMovingPointThatIsNotGrabbedSnapsTheGroup() {
        val s = Session(SnapLine.point(Vec2(150f, 170f), "Point"))
        // Grabbed: point 0 at 30 + 76 = 106 (6 px from 100); point 1 at 70 + 76 = 146: 4 px from 150.
        val d = s.snap().snap(two, twoBox, 0, Vec2(76f, 0f))
        assertVec(Vec2(80f, 0f), d)
    }

    @Test
    fun onATieTheBoxLineWinsOverAPoint() {
        // The box's max line and point 1 are the same x: the box's line (earlier) is the one used,
        // so the guide shows the moved box rather than a zero-width point box.
        val s = Session()
        val d = s.snap().snap(two, twoBox, 0, Vec2(127f, 0f))
        assertVec(Vec2(130f, 0f), d)
        assertEquals(DocBox(160f, 40f, 200f, 60f), s.guideBox)
    }

    @Test
    fun onATieBetweenPointsTheGrabbedOneWins() {
        // Moved by (5, 0): A at 35 is 3 px from the line x 38, B at 65 is 3 px from x 62, the box
        // centre 55 is 7 px from x 62 (farther): both corrections are 3 px, in opposite directions.
        val s = Session(SnapLine.point(Vec2(38f, 190f), "Point") + SnapLine.point(Vec2(62f, 190f), "Point"))
        assertVec(Vec2(2f, 0f), s.snap().snap(four, fourBox, 1, Vec2(5f, 0f)))
        assertVec(Vec2(8f, 0f), s.snap().snap(four, fourBox, 0, Vec2(5f, 0f)))
        // Grabbed C (nothing near it): the points in order, A first.
        assertVec(Vec2(8f, 0f), s.snap().snap(four, fourBox, 2, Vec2(5f, 0f)))
    }

    @Test
    fun theSmallestCorrectionWinsWhateverTheCandidate() {
        // The box min (point 0) at 105 is 5 px from the canvas centre; the box centre at 125 is 2 px from a point line.
        val s = Session(SnapLine.point(Vec2(127f, 180f), "Point"))
        val d = s.snap().snap(two, twoBox, 0, Vec2(75f, 0f))
        // The centre's 2 px win over the box min's (and point 0's) 5 px.
        assertVec(Vec2(77f, 0f), d)
    }

    @Test
    fun nothingWithinReachLeavesTheDeltaAndHidesTheGuides() {
        val s = Session()
        val d = s.snap().snap(two, twoBox, 0, Vec2(15f, 15f))
        assertVec(Vec2(15f, 15f), d)
        assertNull(s.guideBox)
        assertEquals(true, s.guidesHidden)
    }

    @Test
    fun aSnappedPointShowsItsOwnGuideAndAnUnsnappedAxisTheMovedBox() {
        val s = Session(SnapLine.point(Vec2(67f, 190f), "Point"))
        // X: B at 65 lands on 67 (a zero-width box there); Y did not snap: the moved box's lines.
        assertVec(Vec2(7f, 15f), s.snap().snap(four, fourBox, 0, Vec2(5f, 15f)))
        assertEquals(DocBox(67f, 45f, 67f, 75f), s.guideBox)
    }

    @Test
    fun anAxisNoTargetPlacedFollowsTheGridFromTheGrabbedPoint() {
        val s = Session()
        val grid = GridSnap(PeriodicLines(25f, 0f), PeriodicLines(25f, 0f))
        // Grabbed point 1 (70, 60) + (12, 11) = (82, 71): the nearest grid point is (75, 75).
        assertVec(Vec2(5f, 15f), s.snap(grid).snap(two, twoBox, 1, Vec2(12f, 11f)))
    }

    @Test
    fun theGizmoFollowsTheGridFromTheBoxMinCorner() {
        val s = Session()
        val grid = GridSnap(PeriodicLines(25f, 0f), PeriodicLines(25f, 0f))
        // Box min corner (30, 40) + (12, 11) = (42, 51): the nearest grid point is (50, 50).
        assertVec(Vec2(20f, 10f), s.snap(grid).snap(two, twoBox, -1, Vec2(12f, 11f)))
    }

    @Test
    fun anObjectBeatsTheGridOnItsAxis() {
        val s = Session()
        val grid = GridSnap(PeriodicLines(25f, 0f), PeriodicLines(25f, 0f))
        // X: centre 50 + 47 = 97 snaps to the canvas centre (+3); Y follows the grid from point 1: 60 + 11 = 71 -> 75.
        assertVec(Vec2(50f, 15f), s.snap(grid).snap(two, twoBox, 1, Vec2(47f, 11f)))
    }

    @Test
    fun withoutTheGridAFreeAxisMovesByTheLengthStep() {
        val s = Session()
        // X snaps (the canvas centre); Y moves by multiples of 10: 14 -> 10.
        assertVec(Vec2(50f, 10f), s.snap(step = 10f).snap(two, twoBox, 1, Vec2(47f, 14f)))
    }

    @Test
    fun aBadInputMovesByTheRawDeltaAndShowsNoGuide() {
        val s = Session()
        assertVec(Vec2(3f, 4f), s.snap().snap(FloatArray(0), twoBox, -1, Vec2(3f, 4f)))
        assertEquals(true, s.guidesHidden)
        val nan = s.snap().snap(two, twoBox, -1, Vec2(Float.NaN, 1f))
        assertEquals(true, nan.x.isNaN())
        // A grabbed index past the points counts as none.
        assertVec(Vec2(50f, 0f), s.snap().snap(two, twoBox, 7, Vec2(47f, 0f)))
    }

    @Test
    fun pairsAndBoundsSkipNonFinitePoints() {
        val p = PointGroupSnap.pairs(listOf(Vec2(5f, 9f), Vec2(Float.NaN, 1f), Vec2(-3f, 20f)))
        assertEquals(6, p.size)
        assertEquals(DocBox(-3f, 9f, 5f, 20f), PointGroupSnap.bounds(p))
        assertNull(PointGroupSnap.bounds(FloatArray(0)))
    }

    @Test
    fun theGridIsTheSquareSnappingGridOnly() {
        val on = com.brushwork.paint.model.GridSettings(enabled = true, snap = true, spacingPx = 20f, offsetXPx = 3f, offsetYPx = 4f)
        assertEquals(GridSnap(PeriodicLines(20f, 3f), PeriodicLines(20f, 4f)), PointGroupSnap.gridOf(on))
        assertNull(PointGroupSnap.gridOf(on.copy(snap = false)))
        assertNull(PointGroupSnap.gridOf(on.copy(enabled = false)))
        assertNull(PointGroupSnap.gridOf(on.copy(type = com.brushwork.paint.model.GridType.ISOMETRIC)))
    }

    @Test
    fun snappingAxesAreIndependent() {
        // Y: point 0 at 40 + 57 = 97 (3 from the canvas centre); X free.
        val s = Session()
        assertVec(Vec2(12f, 60f), s.snap().snap(two, twoBox, 0, Vec2(12f, 57f)))
    }
}
