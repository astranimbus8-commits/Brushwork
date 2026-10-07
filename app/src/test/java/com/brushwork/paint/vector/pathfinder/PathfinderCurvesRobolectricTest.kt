package com.brushwork.paint.vector.pathfinder

import android.graphics.Path
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VectorOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.PI

/**
 * v1.7 (§3.20, area G): Pathfinder on curves, on real Skia PathOps (NATIVE graphics).
 * - Skia's conics (`Path.addCircle`) read back from their own start: one closed contour of
 *   cubics starting at the move (the platform `PathIterator`'s `peek()` answers one segment
 *   ahead after `hasNext()`, so the reader never uses it).
 * - Two circles of radius 60 whose centres are 60 apart (120° inner arcs): Unite is the two
 *   240° arcs (502.7 px), Divide 3 pieces, Outline 4 paths of 251 + 126 + 126 + 251 px (each
 *   shared arc once), whether the circles are conics or cubic Béziers (how vector objects and
 *   shapes reach `Path.op`).
 * - Outline draws every edge of the Divide pieces once: its length is half of Unite's plus all
 *   the pieces' (the outer edges belong to one piece, the inner ones to two), for circles, a
 *   circle and a square, three circles and a circle inside a square.
 */
@RunWith(RobolectricTestRunner::class)
class PathfinderCurvesRobolectricTest {
    private val red = PathfinderStyle(1f, VPaint.Solid(0xFFDD2211.toInt()), null)
    private val blue = PathfinderStyle(1f, VPaint.Solid(0xFF2244CC.toInt()), null)

    /** A circle as Skia's 4 conics. */
    private fun conicCircle(cx: Float, cy: Float, r: Float) = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }

    /** A circle as 4 cubic Béziers. */
    private fun bezierCircle(cx: Float, cy: Float, r: Float) = Path().apply {
        val k = 0.5522848f * r
        moveTo(cx + r, cy)
        cubicTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r)
        cubicTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy)
        cubicTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r)
        cubicTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy)
        close()
    }

    private fun rect(l: Float, t: Float, r: Float, b: Float) = Path().apply { addRect(l, t, r, b, Path.Direction.CW) }

    private fun run(op: PathfinderOp, operands: List<PathfinderOperand>): List<VPath> {
        val r = PathfinderOps.run(op, operands)
        assertTrue("$op: $r", r is PathfinderResult.Done)
        return (r as PathfinderResult.Done).objects
    }

    private fun length(o: VPath): Double = VectorOps.toVectorPath(o).flatten(0.05f).sumOf { pl ->
        pl.points.zipWithNext().sumOf { (a, b) -> a.distanceTo(b).toDouble() } + (if (pl.closed) pl.points.last().distanceTo(pl.points.first()).toDouble() else 0.0)
    }

    private fun total(op: PathfinderOp, operands: List<PathfinderOperand>): Double = run(op, operands).sumOf(::length)

    @Test
    fun conicsReadBackFromTheirMove() {
        val cs = PathConvert.contours(conicCircle(100f, 100f, 60f))
        assertEquals("one contour", 1, cs.size)
        val c = cs.single()
        assertTrue("closed", c.closed)
        assertEquals("it starts at the move", Vec2(160f, 100f), c.segs.first().p0)
        assertTrue("cubics", c.segs.all { it.isCubic })
        for ((i, s) in c.segs.withIndex()) {
            for (t in listOf(0f, 0.5f, 1f)) {
                val d = s.at(t).distanceTo(Vec2(100f, 100f))
                assertEquals("segment $i at $t on the circle", 60f, d, PathConvert.CONIC_TOLERANCE + 0.05f)
            }
        }
        assertEquals("the disc's area", PI * 3600, PathConvert.area(conicCircle(100f, 100f, 60f)).toDouble(), PI * 3600 * 0.01)
    }

    @Test
    fun twoCirclesConicsOrBeziers() {
        val arc120 = 2 * PI * 60 / 3 // 125.66
        for ((kind, circle) in listOf<Pair<String, (Float, Float, Float) -> Path>>("conics" to ::conicCircle, "Béziers" to ::bezierCircle)) {
            val ops = listOf(PathfinderOperand(circle(100f, 100f, 60f), red), PathfinderOperand(circle(160f, 100f, 60f), blue))
            assertEquals("$kind: Unite", 4 * arc120, total(PathfinderOp.UNITE, ops), 1.0)
            assertEquals("$kind: Divide pieces", 3, run(PathfinderOp.DIVIDE, ops).size)
            val outline = run(PathfinderOp.OUTLINE, ops).map(::length).sorted()
            assertEquals("$kind: Outline paths $outline", 4, outline.size)
            assertEquals("$kind: inner arc 1", arc120, outline[0], 1.0)
            assertEquals("$kind: inner arc 2", arc120, outline[1], 1.0)
            assertEquals("$kind: outer arc 1", 2 * arc120, outline[2], 1.5)
            assertEquals("$kind: outer arc 2", 2 * arc120, outline[3], 1.5)
        }
    }

    @Test
    fun outlineDrawsEveryEdgeOnce() {
        for (circle in listOf<(Float, Float, Float) -> Path>(::conicCircle, ::bezierCircle)) {
            val cases = listOf(
                "circle and square" to listOf(PathfinderOperand(circle(100f, 100f, 60f), red), PathfinderOperand(rect(100f, 60f, 220f, 140f), blue)),
                "three circles" to listOf(
                    PathfinderOperand(circle(100f, 100f, 60f), red),
                    PathfinderOperand(circle(160f, 100f, 60f), blue),
                    PathfinderOperand(circle(130f, 150f, 60f), red),
                ),
                "circle in a square" to listOf(PathfinderOperand(rect(0f, 0f, 200f, 200f), red), PathfinderOperand(circle(100f, 100f, 60f), blue)),
            )
            for ((name, ops) in cases) {
                val unite = total(PathfinderOp.UNITE, ops)
                val divide = total(PathfinderOp.DIVIDE, ops)
                val outline = total(PathfinderOp.OUTLINE, ops)
                assertEquals("$name: Outline = (Unite + Divide) / 2", (unite + divide) / 2, outline, 0.005 * outline + 0.5)
            }
            val inSquare = run(PathfinderOp.OUTLINE, cases[2].second).map(::length).sorted()
            assertEquals("circle in a square: two paths $inSquare", 2, inSquare.size)
            assertEquals("the circle", 2 * PI * 60, inSquare[0], 1.5)
            assertEquals("the square", 800.0, inSquare[1], 0.5)
        }
    }
}
