package com.brushwork.paint.v110.a5

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.points.PointGroupSnap
import com.brushwork.paint.tools.transform.SnapAxis
import com.brushwork.paint.tools.transform.SnapGuides
import com.brushwork.paint.tools.transform.SnapTargets
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.10 (A5, R8): above [PointGroupSnap.MAX_POINT_CANDIDATES] (256) moving points only the moving
 * box's lines (its nine handles: min, centre and max on each axis) are candidates, which keeps a
 * move within its budget however many points move (`GroupSnapPerfProbeTest`).
 *
 * The targets are the 200 x 200 canvas (x and y 0, 100, 200) at a snap distance of 8 px. The
 * moving points sit on the box's left and right sides (x 20 and 80, y 40..60) except one inner
 * point at x 61: moved 37 px right, the inner point is 2 px from the canvas centre x 100 while the
 * box's lines (57, 87, 117) and the side points are 13 px or more from any target, and on y
 * (40..60, not moved) nothing is within reach.
 */
class GroupSnapCandidateCapTest {
    private val targets = SnapTargets.build(200f, 200f, emptyList())
    private val asked = ArrayList<Float>()

    private fun snap() = PointGroupSnap(
        objects = { v, axis -> if (axis == SnapAxis.X) asked += v; SnapGuides.snapValue(v, axis, targets, 8f) },
        showGuides = { },
        grid = null,
        step = null,
    )

    /** [n] moving points: the inner point (61, 50) first, then the side points. */
    private fun points(n: Int): FloatArray {
        val out = FloatArray(n * 2)
        out[0] = 61f
        out[1] = 50f
        for (i in 1 until n) {
            out[i * 2] = if (i % 2 == 0) 20f else 80f
            out[i * 2 + 1] = 40f + (i % 21)
        }
        return out
    }

    private val move = Vec2(37f, 0f)

    @Test
    fun upTo256MovingPointsEveryPointIsACandidate() {
        val p = points(PointGroupSnap.MAX_POINT_CANDIDATES)
        val box = PointGroupSnap.bounds(p)!!
        assertEquals(256, PointGroupSnap.MAX_POINT_CANDIDATES)
        // The inner point lands on x 100: the group moves 39 px.
        assertEquals(39f, snap().snap(p, box, -1, move).x, 1e-4f)
        assertEquals(39f, snap().snap(p, box, 0, move).x, 1e-4f)
    }

    @Test
    fun above256MovingPointsOnlyTheBoxLinesAreCandidates() {
        val p = points(PointGroupSnap.MAX_POINT_CANDIDATES + 1)
        val box = PointGroupSnap.bounds(p)!!
        asked.clear()
        // The inner point is not asked: nothing on the box is within reach, the raw move stays.
        assertEquals(37f, snap().snap(p, box, -1, move).x, 1e-4f)
        assertEquals("only the box's min, centre and max on x", listOf(57f, 87f, 117f), asked)
        // Grabbing the inner point changes nothing: it is a point, not a box line.
        asked.clear()
        assertEquals(37f, snap().snap(p, box, 0, move).x, 1e-4f)
        assertEquals(listOf(57f, 87f, 117f), asked)
    }

    @Test
    fun above256TheBoxStillSnaps() {
        val p = points(PointGroupSnap.MAX_POINT_CANDIDATES + 1)
        val box = PointGroupSnap.bounds(p)!!
        // The box's right side 80 + 117 = 197 lands on the canvas edge 200.
        assertEquals(120f, snap().snap(p, box, 0, Vec2(117f, 0f)).x, 1e-4f)
    }
}
