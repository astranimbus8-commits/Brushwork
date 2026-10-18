package com.brushwork.paint.v19.a3

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.text.TextPathGeometry
import com.brushwork.paint.tools.text.TextPathGuide
import com.brushwork.paint.tools.text.TextPathNodes
import com.brushwork.paint.tools.text.TextPathSide
import com.brushwork.paint.tools.text.TextPathSpec
import com.brushwork.paint.tools.text.TextPathType
import com.brushwork.paint.vector.VSpline
import com.brushwork.paint.vector.VSplinePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * v1.9 A3 step 9 (R12): a text path's nodes. The v1.8 guides turn into nodes that follow them
 * (a cubic exactly, a circle within half a pixel, a rectangle's corners and rounded corners
 * exactly) with the text where it was; the nodes are then the guide (layout, handles, moves);
 * TextLabels19.ADD_POINTS doubles the segments, up to [TextPathSpec.MAX_NODES].
 */
class TextPathNodesTest {

    private val line = TextPathSpec(type = TextPathType.LINE, x1 = 20f, y1 = 200f, x2 = 380f, y2 = 140f)
    private val curve = TextPathSpec(type = TextPathType.CURVE, x1 = 20f, y1 = 300f, cx1 = 120f, cy1 = 40f, cx2 = 280f, cy2 = 460f, x2 = 400f, y2 = 200f)
    private val circle = TextPathSpec(type = TextPathType.CIRCLE, cx = 250f, cy = 240f, radius = 150f, startAngleDeg = -90f)
    private val rect = TextPathSpec(type = TextPathType.RECT, cx = 250f, cy = 240f, width = 300f, height = 180f, startAngleDeg = -90f)

    /** Largest distance from a point of [a] (sampled every [step] px of arc length) to the polyline of [b], both ways. */
    private fun distance(a: TextPathGuide, b: TextPathGuide, step: Double = 0.5): Double = max(oneWay(a, b, step), oneWay(b, a, step))

    private fun oneWay(a: TextPathGuide, b: TextPathGuide, step: Double): Double {
        var worst = 0.0
        val e = DoubleArray(4)
        var s = 0.0
        while (s <= a.length) {
            a.eval(s, e)
            worst = max(worst, toPolyline(b, e[0], e[1]))
            s += step
        }
        return worst
    }

    private fun toPolyline(g: TextPathGuide, x: Double, y: Double): Double {
        var best = Double.MAX_VALUE
        for (i in 0 until g.vertexCount - 1) {
            val a = g.vertex(i)
            val b = g.vertex(i + 1)
            val ex = (b.x - a.x).toDouble()
            val ey = (b.y - a.y).toDouble()
            val l2 = ex * ex + ey * ey
            val t = if (l2 > 0.0) (((x - a.x) * ex + (y - a.y) * ey) / l2).coerceIn(0.0, 1.0) else 0.0
            best = min(best, hypot(a.x + ex * t - x, a.y + ey * t - y))
        }
        return best
    }

    private fun guideOf(spec: TextPathSpec): TextPathGuide = TextPathGeometry.guide(spec)!!

    /** The spec with [nodes] as its guide. */
    private fun noded(spec: TextPathSpec, nodes: VSpline): TextPathSpec = TextPathNodes.withNodes(spec, nodes)

    /** Where the text sits on a closed guide (its anchor) and which way it reads there. */
    private fun assertSameTextPosition(what: String, a: TextPathGuide, b: TextPathGuide, tol: Float) {
        val pa = a.posAt(a.anchor.toFloat())
        val pb = b.posAt(b.anchor.toFloat())
        assertTrue("$what: text position $pa vs $pb", pa.distanceTo(pb) <= tol)
        val ta = a.tangentAt(a.anchor.toFloat() + 1f)
        val tb = b.tangentAt(b.anchor.toFloat() + 1f)
        assertTrue("$what: direction $ta vs $tb", ta.dot(tb) > 0.99f)
    }

    @Test
    fun aLineGivesItsTwoEndsAndACurveItsFourControlPointsExactly() {
        val l = TextPathNodes.asPath(line)!!
        assertEquals(listOf(Vec2(20f, 200f), Vec2(380f, 140f)), l.points.map { Vec2(it.x, it.y) })
        assertEquals(4, l.order)
        assertFalse(l.cyclic)
        assertTrue(distance(guideOf(line), guideOf(noded(line, l))) < 1e-3)

        val c = TextPathNodes.asPath(curve)!!
        assertEquals(listOf(Vec2(20f, 300f), Vec2(120f, 40f), Vec2(280f, 460f), Vec2(400f, 200f)), c.points.map { Vec2(it.x, it.y) })
        val d = distance(guideOf(curve), guideOf(noded(curve, c)))
        assertTrue("the cubic is the curve (I9): $d px", d < 0.1)
        assertEquals(guideOf(curve).length, guideOf(noded(curve, c)).length, 0.1)
    }

    @Test
    fun aCircleGivesEightPathNodesWithinHalfAPixelWithTheTextWhereItWas() {
        for (cw in listOf(true, false)) for (r in listOf(40f, 150f, 600f)) for (at in listOf(-90f, 0f, 135f)) {
            val spec = circle.copy(radius = r, clockwise = cw, startAngleDeg = at)
            val n = TextPathNodes.asPath(spec)!!
            assertEquals(TextPathNodes.CIRCLE_PATH_NODES, n.points.size)
            assertTrue(n.cyclic)
            assertEquals(4, n.order)
            assertTrue(n.points.none { it.sharp })
            val nodes = noded(spec, n)
            assertEquals(TextPathType.CURVE, nodes.type)
            val d = distance(guideOf(spec), guideOf(nodes))
            assertTrue("r $r: $d px", d <= 0.5)
            assertSameTextPosition("circle r $r at $at cw $cw", guideOf(spec), guideOf(nodes), 0.5f)
            assertEquals(cw, TextPathNodes.isClockwise(n))
        }
    }

    @Test
    fun aRectangleGivesItsCornersSharpAndItsRoundedCornersAsExactArcs() {
        // The text at the middle of the top edge: the corners plus a node there.
        val sharp = TextPathNodes.asPath(rect)!!
        assertEquals(5, sharp.points.size)
        assertEquals(listOf(false, true, true, true, true), sharp.points.map { it.sharp })
        assertEquals(Vec2(250f, 150f), Vec2(sharp.points[0].x, sharp.points[0].y))
        assertTrue(distance(guideOf(rect), guideOf(noded(rect, sharp))) < 0.01)
        assertSameTextPosition("square corners", guideOf(rect), guideOf(noded(rect, sharp)), 0.01f)
        // The text at a corner: the 4 corners alone, that one first.
        val atCorner = rect.copy(width = 200f, height = 200f, startAngleDeg = 45f)
        val four = TextPathNodes.asPath(atCorner)!!
        assertEquals(4, four.points.size)
        assertTrue(four.points.all { it.sharp })
        assertEquals(Vec2(350f, 340f), Vec2(four.points[0].x, four.points[0].y))

        // Rounded, turned, either way round, the text on an edge or on a corner.
        for (cw in listOf(true, false)) for (at in listOf(-90f, 0f, 35f)) {
            val spec = rect.copy(cornerRadius = 40f, rotationDeg = 30f, clockwise = cw, startAngleDeg = at)
            val n = TextPathNodes.asPath(spec)!!
            val onCorner = at == 35f
            // Each corner: a sharp node at either end and a weighted one between; the text position
            // splits its corner's arc in two, or adds a smooth node on its edge.
            assertEquals("cw $cw at $at", if (onCorner) 14 else 13, n.points.size)
            assertEquals(if (onCorner) 5 else 4, n.points.count { it.weight < 0.999f })
            val d = distance(guideOf(spec), guideOf(noded(spec, n)))
            assertTrue("rounded: $d px", d < 0.05)
            assertSameTextPosition("rounded cw $cw at $at", guideOf(spec), guideOf(noded(spec, n)), 0.05f)
        }
    }

    @Test
    fun polylinesSampleTheShapes() {
        assertEquals(2, TextPathNodes.asPolyline(line)!!.points.size)
        val c = TextPathNodes.asPolyline(curve)!!
        assertEquals(2, c.order)
        assertEquals(TextPathNodes.CUBIC_POLYLINE_SEGMENTS + 1, c.points.size)
        assertEquals(Vec2(400f, 200f), Vec2(c.points.last().x, c.points.last().y))
        val circ = TextPathNodes.asPolyline(circle)!!
        assertEquals(TextPathNodes.CIRCLE_POLYLINE_VERTICES, circ.points.size)
        assertTrue(circ.cyclic)
        for (p in circ.points) assertEquals(150.0, hypot(p.x - 250.0, p.y - 240.0), 1e-3)
        assertEquals("the first vertex at the text position", Vec2(250f, 90f).distanceTo(Vec2(circ.points[0].x, circ.points[0].y)), 0f, 1e-3f)
        // A rounded rectangle: each quarter arc in 4 segments; the text on the top edge.
        val r = TextPathNodes.asPolyline(rect.copy(cornerRadius = 30f))!!
        assertEquals(1 + 4 * 5, r.points.size)
        // Path nodes sampled: 8 segments per span (a closed cubic of 8 nodes has 8 spans).
        val fromPath = TextPathNodes.asPolyline(noded(circle, TextPathNodes.asPath(circle)!!))!!
        assertEquals(8 * TextPathNodes.CUBIC_POLYLINE_SEGMENTS, fromPath.points.size)
        assertTrue(distance(guideOf(circle), guideOf(noded(circle, fromPath))) < 1.0)
        // Never past the cap.
        val long = VSpline(List(150) { VSplinePoint(it * 4f, if (it % 2 == 0) 0f else 30f) }, order = 4)
        assertTrue(TextPathNodes.asPolyline(noded(line, long))!!.points.size <= TextPathSpec.MAX_NODES)
        // A polyline as a path keeps its shape: its vertices become corners.
        val back = TextPathNodes.asPath(noded(curve, c))!!
        assertEquals(4, back.order)
        assertTrue(back.points.drop(1).dropLast(1).all { it.sharp })
        assertTrue(distance(guideOf(noded(curve, c)), guideOf(noded(curve, back))) < 1e-3)
    }

    @Test
    fun addPointsDoublesTheSegmentsWithinTheCap() {
        val open = VSpline(List(5) { VSplinePoint(it * 50f, (it % 2) * 40f) }, order = 4)
        val more = TextPathNodes.withAddedPoints(open)
        assertEquals(9, more.points.size)
        assertEquals("the nodes stay", open.points, more.points.filterIndexed { i, _ -> i % 2 == 0 })
        assertEquals(VSplinePoint(25f, 20f), more.points[1])
        val closed = TextPathNodes.asPath(circle)!!
        val closedMore = TextPathNodes.withAddedPoints(closed)
        assertEquals("the closing segment too", 16, closedMore.points.size)
        val last = closedMore.points.last()
        assertEquals((closed.points.last().x + closed.points[0].x) / 2f, last.x, 1e-3f)
        // Split in order until the cap.
        val big = VSpline(List(150) { VSplinePoint(it * 4f, (it % 3) * 10f) }, order = 2)
        val capped = TextPathNodes.withAddedPoints(big)
        assertEquals(TextPathSpec.MAX_NODES, capped.points.size)
        assertEquals(VSplinePoint(2f, 5f), capped.points[1])
        assertEquals(big.points.subList(50, 150), capped.points.subList(100, 200))
        assertSame("full: nothing to add", capped, TextPathNodes.withAddedPoints(capped))
        // A polyline keeps its shape exactly.
        val poly = TextPathNodes.asPolyline(curve)!!
        assertTrue(distance(guideOf(noded(curve, poly)), guideOf(noded(curve, TextPathNodes.withAddedPoints(poly)))) < 1e-3)
    }

    @Test
    fun theNodeBarsEditsKeepAGuide() {
        val n = VSpline(List(4) { VSplinePoint(it * 100f, 0f) }, order = 4)
        val (after, at) = TextPathNodes.withPointAfter(n, 1)!!
        assertEquals(2, at)
        assertEquals(VSplinePoint(150f, 0f), after.points[2])
        val (end, atEnd) = TextPathNodes.withPointAfter(n, -1)!!
        assertEquals("none selected: before the end", 3, atEnd)
        assertEquals(VSplinePoint(250f, 0f), end.points[3])
        assertEquals(VSplinePoint(300f, 0f), end.points.last())
        val full = VSpline(List(TextPathSpec.MAX_NODES) { VSplinePoint(it.toFloat(), 0f) }, order = 2)
        assertNull("the 201st point is refused", TextPathNodes.withPointAfter(full, 3))
        assertNull("at least 2 stay", TextPathNodes.withoutPoint(VSpline(n.points.take(2), order = 4), 0))
        assertEquals(3, TextPathNodes.withoutPoint(n, 1)!!.points.size)
        assertFalse("an open end is no corner", TextPathNodes.canBeSharp(n, 0))
        assertTrue(TextPathNodes.withSharpToggled(n, 1)!!.points[1].sharp)
        assertNull("a polyline's vertices are corners already", TextPathNodes.withSharpToggled(n.copy(order = 2), 1))
        assertEquals(2, TextPathNodes.nearest(n, Vec2(205f, 10f), 24f))
        assertEquals(-1, TextPathNodes.nearest(n, Vec2(250f, 60f), 24f))
    }

    @Test
    fun theNodesAreTheGuide() {
        val nodes = VSpline(listOf(VSplinePoint(0f, 0f), VSplinePoint(100f, -80f), VSplinePoint(200f, 80f), VSplinePoint(300f, 0f), VSplinePoint(400f, 50f)), order = 4)
        val spec = noded(curve, nodes)
        assertEquals(TextPathType.CURVE, spec.type)
        assertSame(TextPathNodes.guide(nodes), TextPathGeometry.guide(spec))
        assertSame("cached", TextPathNodes.guide(nodes), TextPathNodes.guide(nodes.copy()))
        assertEquals(nodes.points.map { Vec2(it.x, it.y) }, TextPathGeometry.handles(spec))
        // Straight text keeps its nodes (for later) and has no guide.
        val straight = spec.copy(type = TextPathType.NONE)
        assertNull(TextPathGeometry.guide(straight))
        assertNull(TextPathNodes.of(straight))
        assertNotNull(straight.withNodeFallback().nodes)
        // A handle drag moves its node.
        val moved = TextPathGeometry.moveHandle(spec, 2, Vec2(210f, 120f))
        assertEquals(VSplinePoint(210f, 120f), moved.nodes!!.points[2])
        assertEquals(nodes.points[1], moved.nodes.points[1])
        // Moved, scaled and turned with the text: the nodes too.
        val t = TextPathGeometry.transformed(spec, Vec2(10f, 20f), 2f, 90f, Vec2(0f, 0f))
        val p = t.nodes!!.points[1]
        assertEquals(10f + 160f, p.x, 1e-3f)
        assertEquals(20f + 200f, p.y, 1e-3f)
        assertEquals(TextPathGeometry.pathLength(spec) * 2f, TextPathGeometry.pathLength(t), 0.05f)
        // A 200-node guide: an arc-length table of about 2048 vertices.
        val many = VSpline(List(TextPathSpec.MAX_NODES) { VSplinePoint(it * 5f, 40f * sin(it * 0.3f)) }, order = 4)
        val g = TextPathNodes.guide(many)
        assertTrue("${g.vertexCount} vertices", g.vertexCount in TextPathNodes.GUIDE_SAMPLES..2 * TextPathNodes.GUIDE_SAMPLES)
    }

    @Test
    fun aClosedGuideHasSidesLikeACircle() {
        for (cw in listOf(true, false)) for (side in TextPathSide.entries) {
            val spec = circle.copy(clockwise = cw, side = side)
            val nodes = noded(spec, TextPathNodes.asPath(spec)!!)
            assertEquals("cw $cw $side", TextPathGeometry.isStanding(spec), TextPathGeometry.isStanding(nodes))
        }
        // An open guide: the letters always stand on it.
        assertTrue(TextPathGeometry.isStanding(noded(line.copy(side = TextPathSide.INSIDE), TextPathNodes.asPath(line)!!)))
        // A closed guide's text position is where it comes closest to its first node.
        val square = VSpline(
            listOf(VSplinePoint(100f, 0f), VSplinePoint(200f, 0f, sharp = true), VSplinePoint(200f, 100f, sharp = true), VSplinePoint(0f, 100f, sharp = true), VSplinePoint(0f, 0f, sharp = true)),
            order = 4, cyclic = true,
        )
        val g = TextPathNodes.guide(square)
        assertTrue(g.closed)
        assertTrue(g.posAt(g.anchor.toFloat()).distanceTo(Vec2(100f, 0f)) < 1e-3f)
        assertEquals(600.0, g.length, 0.01)
        // Its corners turn within a negligible length (letters fan out across them).
        val e = DoubleArray(4)
        g.eval(g.anchor + 100.0 + 0.01, e)
        assertEquals(0.0, e[2], 1e-6)
        assertEquals(1.0, e[3], 1e-6)
    }
}
