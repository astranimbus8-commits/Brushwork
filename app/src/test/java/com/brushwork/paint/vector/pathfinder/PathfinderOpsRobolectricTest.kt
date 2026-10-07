package com.brushwork.paint.vector.pathfinder

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.tools.vector.PathOp
import com.brushwork.paint.tools.vector.VectorPath
import com.brushwork.paint.tools.vector.toAndroidPath
import com.brushwork.paint.vector.VFillRule
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStrokeKind
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VectorOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * v1.7 (§3.20, area G): Pathfinder's geometry on real Skia PathOps (NATIVE graphics), with the
 * design's squares A = (0, 0, 100, 100) and B = (50, 0, 150, 100), B on top: Unite 15 000,
 * Intersect 5 000, Exclude 10 000 in 2 pieces, Minus front 5 000 (A's style), Minus back 5 000
 * (B's), Divide 3 pieces, Crop 5 000, Trim A − B and B, Merge (same fill: one object), Outline 6
 * open unfilled paths of total length 700, the styles each keeps, the piece cap, and the budgets.
 * Areas are counted on a raster of the result objects, independent of the code's own area.
 */
@RunWith(RobolectricTestRunner::class)
class PathfinderOpsRobolectricTest {
    private val red = 0xFFDD2211.toInt()
    private val blue = 0xFF2244CC.toInt()
    private val styleA = PathfinderStyle(1f, VPaint.Solid(red), VStrokeStyle(kind = VStrokeKind.PLAIN, color = 0xFF000000.toInt(), width = 3f))
    private val styleB = PathfinderStyle(0.8f, VPaint.Solid(blue), null)

    private fun rect(l: Float, t: Float, r: Float, b: Float) = Path().apply { addRect(l, t, r, b, Path.Direction.CW) }

    private fun squares(a: PathfinderStyle = styleA, b: PathfinderStyle = styleB) =
        listOf(PathfinderOperand(rect(0f, 0f, 100f, 100f), a), PathfinderOperand(rect(50f, 0f, 150f, 100f), b))

    private fun run(op: PathfinderOp, operands: List<PathfinderOperand> = squares()): List<VPath> {
        val r = PathfinderOps.run(op, operands)
        assertTrue("$op: $r", r is PathfinderResult.Done)
        return (r as PathfinderResult.Done).objects
    }

    /** Pixels [o] fills (no anti-aliasing: whole-pixel squares count exactly). */
    private fun area(o: VPath): Int {
        val b = Bitmap.createBitmap(200, 140, Bitmap.Config.ARGB_8888)
        val p = VectorOps.toVectorPath(o).toAndroidPath(Path())
        p.fillType = if (o.fillRule == VFillRule.EVENODD) Path.FillType.EVEN_ODD else Path.FillType.WINDING
        Canvas(b).drawPath(p, Paint().apply { color = 0xFF000000.toInt(); isAntiAlias = false })
        val px = IntArray(200 * 140).also { b.getPixels(it, 0, 200, 0, 0, 200, 140) }
        return px.count { it != 0 }
    }

    private fun length(o: VPath): Float = VectorOps.toVectorPath(o).flatten(0.05f).sumOf { pl ->
        pl.points.zipWithNext().sumOf { (a, b) -> a.distanceTo(b).toDouble() } + (if (pl.closed) pl.points.last().distanceTo(pl.points.first()).toDouble() else 0.0)
    }.toFloat()

    private fun assertStyle(what: String, s: PathfinderStyle, o: VPath) {
        assertEquals("$what: fill", s.fill, o.fill)
        assertEquals("$what: stroke", s.stroke, o.stroke)
        assertEquals("$what: opacity", s.opacity, o.opacity, 0f)
        assertNull("$what: no spline", o.spline)
    }

    @Test
    fun theShapeModes() {
        val unite = run(PathfinderOp.UNITE).single()
        assertEquals(15_000, area(unite))
        assertStyle("Unite takes the top's", styleB, unite)
        assertEquals("one outline", 1, unite.subpaths.size)

        val intersect = run(PathfinderOp.INTERSECT).single()
        assertEquals(5_000, area(intersect))
        assertStyle("Intersect takes the top's", styleB, intersect)

        val exclude = run(PathfinderOp.EXCLUDE).single()
        assertEquals(10_000, area(exclude))
        assertEquals("Exclude: 2 pieces", 2, exclude.subpaths.size)
        assertStyle("Exclude takes the top's", styleB, exclude)

        val minusFront = run(PathfinderOp.MINUS_FRONT).single()
        assertEquals(5_000, area(minusFront))
        assertStyle("Minus front keeps the back's", styleA, minusFront)
        assertTrue("it is A − B", minusFront.subpaths.single().anchors.all { it.x <= 50f })

        val minusBack = run(PathfinderOp.MINUS_BACK).single()
        assertEquals(5_000, area(minusBack))
        assertStyle("Minus back keeps the front's", styleB, minusBack)
        assertTrue("it is B − A", minusBack.subpaths.single().anchors.all { it.x >= 100f })

        // Results are closed Bézier paths with Skia's fill rule.
        for (o in listOf(unite, intersect, exclude, minusFront, minusBack)) {
            assertEquals(VFillRule.EVENODD, o.fillRule)
            assertTrue(o.subpaths.all { it.closed })
        }
        // Disjoint shapes: nothing to intersect.
        val apart = listOf(PathfinderOperand(rect(0f, 0f, 10f, 10f), styleA), PathfinderOperand(rect(20f, 0f, 30f, 10f), styleB))
        assertSame(PathfinderResult.Empty, PathfinderOps.run(PathfinderOp.INTERSECT, apart))
    }

    @Test
    fun divideTrimMergeAndCrop() {
        val divide = run(PathfinderOp.DIVIDE)
        assertEquals("Divide: 3 pieces", 3, divide.size)
        assertEquals(listOf(5_000, 5_000, 5_000), divide.map { area(it) })
        // A − B keeps A's style; the overlap and B − A take B's (the frontmost covering them).
        assertStyle("A − B", styleA, divide[0])
        assertStyle("A ∩ B", styleB, divide[1])
        assertStyle("B − A", styleB, divide[2])

        val trim = run(PathfinderOp.TRIM)
        assertEquals(2, trim.size)
        assertEquals(5_000, area(trim[0]))
        assertEquals(10_000, area(trim[1]))
        assertStyle("Trim: A − B", styleA, trim[0])
        assertStyle("Trim: B", styleB, trim[1])

        // Different fills: Merge is Trim. The same fill: one object.
        assertEquals(listOf(5_000, 10_000), run(PathfinderOp.MERGE).map { area(it) })
        val sameFill = styleB.copy(stroke = styleA.stroke)
        val merged = run(PathfinderOp.MERGE, squares(a = sameFill, b = styleB)).single()
        assertEquals(15_000, area(merged))
        assertStyle("the topmost member's style", styleB, merged)

        val crop = run(PathfinderOp.CROP).single()
        assertEquals("Crop keeps 5 000", 5_000, area(crop))
        assertStyle("inside the top object, A's own style", styleA, crop)
    }

    @Test
    fun outlineGivesTheEdges() {
        val edges = run(PathfinderOp.OUTLINE)
        assertEquals("6 open paths", 6, edges.size)
        for (e in edges) {
            assertNull("no fill", e.fill)
            val s = e.stroke!!
            assertEquals(VStrokeKind.PLAIN, s.kind)
            assertEquals(1f, s.width, 0f)
            assertEquals(1, e.subpaths.size)
            assertTrue("open", !e.subpaths[0].closed)
        }
        val total = edges.sumOf { length(it).toDouble() }
        assertEquals("total length 700 ± 1 %", 700.0, total, 7.0)
        val lengths = edges.map { length(it).roundToInt() }.sorted()
        assertEquals("two U-shapes, two verticals, the middle's top and bottom", listOf(50, 50, 100, 100, 200, 200), lengths)
        // A's U is A's fill colour; everything else borders B's pieces (the upper ones).
        val uA = edges.single { e -> length(e).roundToInt() == 200 && e.subpaths[0].anchors.all { it.x <= 50f } }
        assertEquals(red, uA.stroke!!.color)
        for (e in edges - uA) assertEquals(blue, e.stroke!!.color)

        // A square inside a square: two closed outlines, no junction.
        val nested = listOf(PathfinderOperand(rect(0f, 0f, 100f, 100f), styleA), PathfinderOperand(rect(25f, 25f, 75f, 75f), styleB))
        val loops = run(PathfinderOp.OUTLINE, nested)
        assertEquals(2, loops.size)
        assertEquals(listOf(200, 400), loops.map { length(it).roundToInt() }.sorted())
    }

    @Test
    fun tooManyPiecesAreRefused() {
        // 100 small squares in each of two operands, each pair overlapping: 300 pieces.
        fun grid(dx: Float) = Path().apply {
            for (i in 0 until 10) for (j in 0 until 10) addRect(i * 10f + dx, j * 10f, i * 10f + dx + 6f, j * 10f + 6f, Path.Direction.CW)
        }
        val operands = listOf(PathfinderOperand(grid(0f), styleA), PathfinderOperand(grid(3f), styleB))
        assertSame(PathfinderResult.TooMany, PathfinderOps.run(PathfinderOp.DIVIDE, operands))
        assertSame(PathfinderResult.TooMany, PathfinderOps.run(PathfinderOp.OUTLINE, operands))
        // Shape modes have no piece cap.
        assertTrue(PathfinderOps.run(PathfinderOp.UNITE, operands) is PathfinderResult.Done)
    }

    @Test
    fun slivers() {
        // B overlaps A by 0.004 px: the overlap is a sliver, Divide gives 2 pieces.
        val operands = listOf(PathfinderOperand(rect(0f, 0f, 100f, 100f), styleA), PathfinderOperand(rect(99.996f, 0f, 200f, 100f), styleB))
        assertEquals(2, run(PathfinderOp.DIVIDE, operands).size)
    }

    /** A closed wavy loop of [n] cubics around ([cx], [cy]). */
    private fun wavy(cx: Float, cy: Float, r: Float, n: Int, phase: Float = 0f): Path {
        fun pt(t: Double): Vec2 {
            val a = 2 * PI * t
            val rr = r * (1 + 0.15 * sin(7 * a + phase))
            return Vec2((cx + rr * cos(a)).toFloat(), (cy + rr * sin(a)).toFloat())
        }
        val ops = ArrayList<PathOp>()
        ops += PathOp.MoveTo(pt(0.0))
        val h = 1e-4
        for (i in 0 until n) {
            val t0 = i.toDouble() / n; val t1 = (i + 1).toDouble() / n
            val p0 = pt(t0); val p1 = pt(t1)
            val d0 = (pt(t0 + h) - pt(t0 - h)) * (1f / (2 * h * n * 3).toFloat())
            val d1 = (pt(t1 + h) - pt(t1 - h)) * (1f / (2 * h * n * 3).toFloat())
            ops += PathOp.CubicTo(p0 + d0, p1 - d1, p1)
        }
        ops += PathOp.Close
        return VectorPath(ops).toAndroidPath(Path())
    }

    private fun medianMs(runs: Int = 5, block: () -> Unit): Double {
        repeat(2) { block() }
        val times = List(runs) { val t0 = System.nanoTime(); block(); (System.nanoTime() - t0) / 1e6 }
        return times.sorted()[runs / 2]
    }

    @Test
    fun budgets() {
        val two = listOf(PathfinderOperand(wavy(200f, 200f, 120f, 200), styleA), PathfinderOperand(wavy(280f, 200f, 120f, 200, 1f), styleB))
        val twoMs = medianMs { assertTrue(PathfinderOps.run(PathfinderOp.UNITE, two) is PathfinderResult.Done) }
        assertTrue("two operands of 200 cubics: $twoMs ms", twoMs <= PerfBudget.ms(20.0))

        val ten = List(10) { i -> PathfinderOperand(wavy(200f + 60f * cos(i * 0.6f), 200f + 60f * sin(i * 0.6f), 100f, 200, i.toFloat()), styleA) }
        val tenMs = medianMs(3) { assertTrue(PathfinderOps.run(PathfinderOp.UNITE, ten) is PathfinderResult.Done) }
        assertTrue("Unite of 10 shapes × 200 segments: $tenMs ms", tenMs <= PerfBudget.ms(150.0))

        val twelve = List(12) { i ->
            val a = i * 2 * PI / 12
            PathfinderOperand(wavy((200 + 70 * cos(a)).toFloat(), (200 + 70 * sin(a)).toFloat(), 60f, 24, i.toFloat()), if (i % 2 == 0) styleA else styleB)
        }
        var pieces = 0
        val divideMs = medianMs(3) { pieces = (PathfinderOps.run(PathfinderOp.DIVIDE, twelve) as PathfinderResult.Done).objects.size }
        assertTrue("Divide makes many pieces ($pieces)", pieces > 24)
        assertTrue("Divide of 12 operands: $divideMs ms", divideMs <= PerfBudget.ms(300.0))
    }
}
