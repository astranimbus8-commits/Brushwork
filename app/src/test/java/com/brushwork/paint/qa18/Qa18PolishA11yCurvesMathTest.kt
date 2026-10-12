package com.brushwork.paint.qa18

import com.brushwork.paint.tools.vector.CurveKind
import com.brushwork.paint.tools.vector.gen.CurveGenerators
import com.brushwork.paint.ui.common.CurveGenLabels18
import com.brushwork.paint.vector.CurveGenKind
import com.brushwork.paint.vector.CurveGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * v1.8 polish (item 3): what the Curves sheet's two new lines say is what the curve does.
 *
 * - "Draws N petals" under "Petals": [CurveGenerators.rosePetals] is the lobes the rose's own
 *   curve has (counted on it), so an even n·d (n / d reduced) says twice the "Petals" value, an odd
 *   one the value itself; the label agrees in number.
 * - "At the 400-point limit: more add nothing" under "Points per turn":
 *   [CurveGenerators.atAnchorLimit] is true exactly from the first points per turn whose curve
 *   every higher value builds the same (Curve, Polyline and Path; open and closed), and there the
 *   curve has its [CurveGenerators.MAX_ANCHORS] points; one value lower still builds another curve.
 */
class Qa18PolishA11yCurvesMathTest {

    private fun rose(n: Int, d: Int) = CurveGenerator(CurveGenKind.ROSE, cx = 200f, cy = 200f, size = 150f, petalsN = n, petalsD = d)

    /** The lobes of [g]'s rose counted on its own curve: the peaks of its distance from the centre. */
    private fun lobes(g: CurveGenerator): Int {
        val a = CurveGenerators.Analytic(g.sanitized()!!)
        val samples = 24_000
        val v = DoubleArray(4)
        val r = DoubleArray(samples) { i ->
            a.eval(a.start + (a.end - a.start) * i / samples, v)
            hypot(v[0] - g.cx, v[1] - g.cy)
        }
        var peaks = 0
        for (i in 0 until samples) {
            val prev = r[(i + samples - 1) % samples]
            val next = r[(i + 1) % samples]
            if (r[i] > 0.9 * g.size && r[i] > prev && r[i] >= next) peaks++
        }
        return peaks
    }

    @Test
    fun theRoseDrawsThePetalsTheLineSays() {
        // The cases the user meets: n alone, even n (twice), a divisor making n·d even, a reducible n / d.
        val known = mapOf((5 to 1) to 5, (4 to 1) to 8, (1 to 1) to 1, (2 to 1) to 4, (3 to 2) to 6, (3 to 4) to 6, (5 to 3) to 5, (4 to 2) to 4, (6 to 4) to 6, (24 to 11) to 48)
        for ((nd, petals) in known) {
            val g = rose(nd.first, nd.second)
            assertEquals("Petals ${nd.first}, divisor ${nd.second}", petals, CurveGenerators.rosePetals(g))
            assertEquals("Petals ${nd.first}, divisor ${nd.second}: counted on the curve", petals, lobes(g))
        }
        // Every value the sheet offers (Petals 1..24, divisor 1..12): what the curve has.
        for (n in 1..24) for (d in 1..12) {
            val g = rose(n, d)
            assertEquals("Petals $n, divisor $d", lobes(g), CurveGenerators.rosePetals(g))
        }
        // Any kind is read as its rose (the sheet asks only for a rose).
        assertEquals(8, CurveGenerators.rosePetals(rose(4, 1).copy(kind = CurveGenKind.SINE)))
        assertEquals("Draws 8 petals", CurveGenLabels18.petalsDrawn(8))
        assertEquals("Draws 1 petal", CurveGenLabels18.petalsDrawn(1))
    }

    /** Curves whose points per turn reach the limit inside 1..64, and ones that never do; open and closed. */
    private val cases = listOf(
        rose(24, 11),
        rose(9, 2),
        rose(5, 1),
        CurveGenerator(CurveGenKind.ARCHIMEDEAN, cx = 200f, cy = 200f, size = 180f, turns = 50f),
        CurveGenerator(CurveGenKind.ARCHIMEDEAN, cx = 200f, cy = 200f, size = 180f, turns = 3f),
        CurveGenerator(CurveGenKind.FERMAT, cx = 200f, cy = 200f, size = 180f, turns = 12f),
        CurveGenerator(CurveGenKind.SINE, cx = 200f, cy = 100f, size = 380f, amplitude = 30f, waves = 50f),
        CurveGenerator(CurveGenKind.SINE, cx = 200f, cy = 100f, size = 380f, amplitude = 30f, waves = 7.5f),
    )

    private fun same(g: CurveGenerator, kind: CurveKind, d1: Int, d2: Int): Boolean {
        val a = CurveGenerators.build(g.copy(density = d1), kind)
        val b = CurveGenerators.build(g.copy(density = d2), kind)
        return a.subpath == b.subpath && a.spline == b.spline
    }

    /** The points the curve is made of: the spline's samples (Path, without its two end tangent points) or the anchors. */
    private fun points(g: CurveGenerator, kind: CurveKind): Int {
        val b = CurveGenerators.build(g, kind)
        val s = b.spline ?: return b.subpath.anchors.size
        return if (s.cyclic) s.points.size else s.points.size - 2
    }

    @Test
    fun theLimitLineShowsExactlyWhenMorePointsPerTurnAddNothing() {
        var reached = 0
        for (g0 in cases) for (kind in CurveKind.entries) {
            val what = "${g0.kind} (${g0.turns} turns, ${g0.waves} waves, ${g0.petalsN}/${g0.petalsD}), $kind"
            val at = (1..CurveGenerator.MAX_DENSITY).map { d -> CurveGenerators.atAnchorLimit(g0.copy(density = d), kind) }
            val first = at.indexOf(true) + 1
            if (first == 0) {
                // Never at the limit: the line keeps saying the limit alone; more points per turn still count.
                assertTrue("$what: under the limit at 64", points(g0.copy(density = CurveGenerator.MAX_DENSITY), kind) <= CurveGenerators.MAX_ANCHORS)
                assertFalse("$what: 63 and 64 differ", same(g0, kind, CurveGenerator.MAX_DENSITY - 1, CurveGenerator.MAX_DENSITY))
                continue
            }
            reached++
            assertTrue("$what: once at the limit, at every higher value", at.drop(first - 1).all { it })
            assertEquals("$what: at the limit, the curve has its ${CurveGenerators.MAX_ANCHORS} points", CurveGenerators.MAX_ANCHORS, points(g0.copy(density = first), kind))
            for (d in first until CurveGenerator.MAX_DENSITY) assertTrue("$what: $d and ${d + 1} build the same curve", same(g0, kind, d, d + 1))
            if (first > 2) assertFalse("$what: one lower (${first - 1}) still builds another curve", same(g0, kind, first - 1, first))
        }
        assertTrue("the cases reach the limit ($reached)", reached >= 12)
        // The default (0) is the kind's own default: a Path rose of 48 petals at 8 per turn is 384 samples, under it.
        assertFalse(CurveGenerators.atAnchorLimit(rose(24, 11), CurveKind.PATH))
        assertTrue(CurveGenerators.atAnchorLimit(rose(24, 11).copy(density = 9), CurveKind.PATH))
        assertNotEquals(CurveGenLabels18.pointsLimit(CurveGenerators.MAX_ANCHORS), CurveGenLabels18.pointsAtLimit(CurveGenerators.MAX_ANCHORS))
    }
}
