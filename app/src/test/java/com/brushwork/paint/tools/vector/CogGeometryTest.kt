package com.brushwork.paint.tools.vector

import com.brushwork.paint.core.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * v1.8 F (design §3.8 (c)): the cog's geometry ([CogGeometry]): the vertex counts, the root arcs'
 * ≤ 20° rule, the tips on the box and the roots at the depth, the hole's 48 vertices going the
 * other way round, and `ShapeGeometry.vertices(COG)` / `outline(COG)` built from it.
 */
class CogGeometryTest {

    private fun angleOf(v: Vec2) = atan2(v.y.toDouble(), v.x.toDouble())

    /** [b] - [a] in (-π, π]. */
    private fun turn(a: Double, b: Double): Double {
        var d = b - a
        while (d <= -PI) d += 2 * PI
        while (d > PI) d -= 2 * PI
        return d
    }

    /** Twice the signed area (shoelace); its sign is the winding. */
    private fun signedArea(p: List<Vec2>): Double {
        var s = 0.0
        for (i in p.indices) {
            val a = p[i]; val b = p[(i + 1) % p.size]
            s += a.x.toDouble() * b.y - b.x.toDouble() * a.y
        }
        return s
    }

    private val widths = floatArrayOf(0.2f, 0.35f, 0.5f, 0.65f, 0.8f)

    @Test
    fun vertexCountsFollowTheTeethAndTheRootSteps() {
        for (n in CogParams.MIN_TEETH..CogParams.MAX_TEETH) for (tw in widths) {
            val c = CogParams(teeth = n, toothWidth = tw)
            val gapDeg = 360.0 / n * (1.0 - tw)
            val m = maxOf(1, ceil(gapDeg / CogGeometry.MAX_ROOT_STEP_DEG - 1e-9).toInt())
            val pts = CogGeometry.unitOuter(c)
            assertEquals("n=$n tw=$tw", n * (3 + m), pts.size)
            assertEquals(pts.size, CogGeometry.vertexCount(c))
            assertTrue("n=$n tw=$tw: ${pts.size} <= ${CogGeometry.MAX_VERTICES}", pts.size <= CogGeometry.MAX_VERTICES)
        }
        // The extremes: 3 wide gaps (96° each, 5 steps) and 64 narrow ones (one step).
        assertEquals(3 * 8, CogGeometry.unitOuter(CogParams(teeth = 3, toothWidth = 0.2f)).size)
        assertEquals(64 * 4, CogGeometry.unitOuter(CogParams(teeth = 64)).size)
        // The defaults: 12 teeth, a 15° gap, no root vertex in between.
        assertEquals(12 * 4, CogGeometry.unitOuter(CogParams.DEFAULT).size)
    }

    @Test
    fun rootArcsStepAtMostTwentyDegreesAndTipsAndRootsSitOnTheirCircles() {
        for (n in intArrayOf(3, 4, 5, 7, 12, 20, 33, 64)) for (tw in widths) for (depth in floatArrayOf(0.05f, 0.2f, 0.5f)) for (taper in floatArrayOf(0f, 0.3f, 0.9f)) {
            val c = CogParams(teeth = n, depth = depth, toothWidth = tw, taper = taper)
            val pts = CogGeometry.unitOuter(c)
            val per = pts.size / n
            val root = 1f - depth
            val where = "n=$n tw=$tw depth=$depth taper=$taper"
            for (t in 0 until n) {
                val k = t * per
                // Root start, tip start, tip end, root end, then the root arc.
                assertEquals(where, root.toDouble(), hypot(pts[k].x.toDouble(), pts[k].y.toDouble()), 1e-5)
                assertEquals(where, 1.0, hypot(pts[k + 1].x.toDouble(), pts[k + 1].y.toDouble()), 1e-5)
                assertEquals(where, 1.0, hypot(pts[k + 2].x.toDouble(), pts[k + 2].y.toDouble()), 1e-5)
                for (j in 3 until per) assertEquals(where, root.toDouble(), hypot(pts[k + j].x.toDouble(), pts[k + j].y.toDouble()), 1e-5)
                // The tooth is centred on -90° + t·pitch; its tip is (1 - taper) of its base.
                val centre = -PI / 2 + t * 2 * PI / n
                val base = turn(angleOf(pts[k]), angleOf(pts[k + 3]))
                val tip = turn(angleOf(pts[k + 1]), angleOf(pts[k + 2]))
                assertEquals(where, tw * 2 * PI / n, base, 1e-4)
                assertEquals(where, base * (1 - taper), tip, 1e-4)
                assertEquals(where, 0.0, turn(centre, (angleOf(pts[k + 1]) + turn(angleOf(pts[k + 1]), angleOf(pts[k + 2])) / 2)), 1e-4)
                // The root arc from this tooth's root end to the next tooth's root start: steps of at most 20°.
                val arc = (3 until per).map { pts[k + it] } + pts[(k + per) % pts.size]
                for (i in 1 until arc.size) {
                    val step = Math.toDegrees(turn(angleOf(arc[i - 1]), angleOf(arc[i])))
                    assertTrue("$where: a root step of $step°", step > 0.0 && step <= CogGeometry.MAX_ROOT_STEP_DEG + 1e-4)
                }
            }
            // Clockwise on the screen (y down): every vertex further round than the one before.
            assertTrue(where, signedArea(pts) > 0.0)
        }
    }

    @Test
    fun theHoleHasFortyEightVerticesGoingTheOtherWayRound() {
        assertTrue(CogGeometry.unitHole(CogParams.DEFAULT).isEmpty())
        assertTrue(!CogGeometry.hasHole(CogParams.DEFAULT))
        for (hole in floatArrayOf(0.1f, 0.45f, 0.8f)) for (depth in floatArrayOf(0.05f, 0.3f)) {
            val c = CogParams(teeth = 9, depth = depth, hole = hole)
            val h = CogGeometry.unitHole(c)
            assertEquals(CogGeometry.HOLE_VERTICES, h.size)
            for (v in h) assertEquals((hole * (1f - depth)).toDouble(), hypot(v.x.toDouble(), v.y.toDouble()), 1e-5)
            val outer = signedArea(CogGeometry.unitOuter(c))
            val inner = signedArea(h)
            assertTrue("the hole winds against the teeth: $outer, $inner", outer > 0.0 && inner < 0.0)
            // The hole lies inside the root circle: it never touches the teeth.
            assertTrue(h.all { hypot(it.x.toDouble(), it.y.toDouble()) < 1.0 - depth })
        }
    }

    @Test
    fun theTeethFillTheBoxAndVerticesScaleWithIt() {
        for ((w, h) in listOf(200f to 200f, 240f to 90f, 1f to 1f, 37.5f to 120f)) {
            val c = CogParams(teeth = 11, depth = 0.3f, toothWidth = 0.6f, taper = 0.5f, hole = 0.4f)
            val p = OutlineParams(cog = c)
            val v = ShapeGeometry.vertices(ShapeType.COG, w, h, p)
            val unit = ShapeGeometry.vertices(ShapeType.COG, 1f, 1f, p)
            assertEquals(CogGeometry.outer(w, h, c), v)
            assertEquals(unit.size, v.size)
            for (i in v.indices) {
                assertEquals(unit[i].x * w, v[i].x, 1e-3f)
                assertEquals(unit[i].y * h, v[i].y, 1e-3f)
                assertTrue(abs(v[i].x) <= w / 2f + 1e-3f && abs(v[i].y) <= h / 2f + 1e-3f)
            }
            // The first tooth's tip is on the outer radius, at the top of the box (half its tip angle off).
            val halfTip = (0.6 * 2 * PI / 11 * (1 - 0.5) / 2)
            assertEquals(-h / 2f * kotlin.math.cos(halfTip).toFloat(), v[1].y, 1e-3f * h)
            assertEquals(v[1].y, v[2].y, 1e-3f * h)
        }
    }

    @Test
    fun theOutlineIsTheTeethThenTheHoleInDocumentSpace() {
        val box = ShapeBox(120f, 90f, 160f, 140f, 25f)
        val c = CogParams(teeth = 7, depth = 0.25f, toothWidth = 0.4f, taper = 0.2f, hole = 0.5f)
        val sharp = ShapeGeometry.outline(ShapeType.COG, box, OutlineParams(cog = c))
        val moves = sharp.ops.withIndex().filter { it.value is PathOp.MoveTo }.map { it.index }
        assertEquals("two contours", 2, moves.size)
        assertEquals("both closed", 2, sharp.ops.count { it == PathOp.Close })
        val outerPts = sharp.ops.subList(0, moves[1]).mapNotNull { (it as? PathOp.MoveTo)?.p ?: (it as? PathOp.LineTo)?.p }
        val expected = CogGeometry.outer(box.w, box.h, c).map { box.toDoc(it) }
        assertEquals(expected.size, outerPts.size)
        for (i in expected.indices) {
            assertEquals(expected[i].x, outerPts[i].x, 1e-3f)
            assertEquals(expected[i].y, outerPts[i].y, 1e-3f)
        }
        val holePts = sharp.ops.subList(moves[1], sharp.ops.size).mapNotNull { (it as? PathOp.MoveTo)?.p ?: (it as? PathOp.LineTo)?.p }
        assertEquals(CogGeometry.HOLE_VERTICES, holePts.size)
        // Without a hole: one contour. Rounded corners round the teeth, never the hole.
        assertEquals(1, ShapeGeometry.outline(ShapeType.COG, box, OutlineParams(cog = c.copy(hole = 0f))).ops.count { it is PathOp.MoveTo })
        val round = ShapeGeometry.outline(ShapeType.COG, box, OutlineParams(corner = CornerStyle.ROUND, cornerRadius = 4f, cog = c))
        assertTrue(round.ops.any { it is PathOp.CubicTo })
        val roundHole = round.ops.subList(round.ops.indexOfLast { it is PathOp.MoveTo }, round.ops.size)
        assertTrue("the hole has no corners", roundHole.none { it is PathOp.CubicTo })
        assertEquals(CogGeometry.contours(box, OutlineParams(corner = CornerStyle.ROUND, cornerRadius = 4f, cog = c)).ops, round.ops)
    }

    @Test
    fun outOfRangeParametersAreClamped() {
        val wild = CogParams(teeth = 500, depth = 9f, toothWidth = -1f, taper = Float.NaN, hole = 3f)
        val sane = CogParams(teeth = 64, depth = 0.5f, toothWidth = 0.2f, taper = CogParams.DEFAULT.taper, hole = 0.8f)
        assertEquals(sane, CogGeometry.clamped(wild))
        assertEquals(CogGeometry.unitOuter(sane), CogGeometry.unitOuter(wild))
        assertEquals(CogGeometry.unitHole(sane), CogGeometry.unitHole(wild))
        assertEquals(3 * 8, CogGeometry.unitOuter(CogParams(teeth = 0, toothWidth = 0.2f)).size)
        // In range: the same instance.
        assertTrue(CogGeometry.clamped(CogParams.DEFAULT) === CogParams.DEFAULT)
    }

    @Test
    fun theShapeToolOffersTheCog() {
        assertEquals(ShapeType.entries, ShapeGeometry.pickerTypes)
        assertEquals(ShapeType.COG, ShapeGeometry.pickerTypes.last())
        assertEquals(1f, ShapeGeometry.naturalAspect(ShapeType.COG, OutlineParams()), 0f)
    }
}
