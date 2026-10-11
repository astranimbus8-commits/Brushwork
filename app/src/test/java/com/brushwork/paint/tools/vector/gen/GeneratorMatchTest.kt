package com.brushwork.paint.tools.vector.gen

import com.brushwork.paint.tools.vector.CurveKind
import com.brushwork.paint.vector.CurveGenKind
import com.brushwork.paint.vector.CurveGenerator
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.8 (I16, design §3.7b): [CurveGenerators.matches] is true exactly while a path's geometry is
 * its generator's within 0.01 px. A built curve matches (every kind, Curve and Path); a moved
 * anchor, a moved handle, an added or deleted anchor, a sharp corner, a weight, another generator
 * or a changed closed flag do not; per-point thickness never counts; a Polyline never matches.
 */
class GeneratorMatchTest {
    private val generators = CurveGenKind.entries.map { k ->
        CurveGenerator(kind = k, cx = 220f, cy = 180f, size = 140f, angleDeg = 25f, turns = 2.5f, petalsN = 4, petalsD = 3, waves = 2.5f, amplitude = 35f)
    }

    private fun path(g: CurveGenerator, kind: CurveKind): VPath {
        val b = CurveGenerators.build(g, kind)
        return VPath(7, subpaths = listOf(b.subpath), stroke = VStrokeStyle(color = 0xFF000000.toInt(), width = 3f), spline = b.spline, polyline = kind == CurveKind.POLYLINE, generator = g)
    }

    private fun VPath.withAnchors(f: (VSubpath) -> VSubpath): VPath = copy(subpaths = listOf(f(subpaths[0])))

    @Test
    fun aBuiltCurveMatchesItsGenerator() {
        for (g in generators) for (k in listOf(CurveKind.CURVE, CurveKind.PATH)) assertTrue("$g / $k", CurveGenerators.matches(path(g, k)))
    }

    /**
     * The most turns: a logarithmic spiral's innermost turns are far below a pixel (2^-50 of its
     * size), where the anchors' float rounding is larger than the curve's movement; the match
     * must not lose its place there (it did: a full Newton step threw it to the curve's end).
     */
    @Test
    fun theMostTurnsAndPointsStillMatch() {
        for (kind in CurveGenKind.entries) for (density in listOf(0, CurveGenerator.MAX_DENSITY)) {
            val g = CurveGenerator(
                kind = kind, cx = 200f, cy = 150f, size = 140f, angleDeg = 10f, turns = CurveGenerator.MAX_TURNS, inner = 0.1f,
                waves = 50f, amplitude = 28f, petalsN = 23, petalsD = 12, density = density,
            )
            for (k in listOf(CurveKind.CURVE, CurveKind.PATH)) assertTrue("$kind / $k, density $density", CurveGenerators.matches(path(g, k)))
        }
    }

    @Test
    fun withoutAGeneratorOrAsAPolylineNothingMatches() {
        for (g in generators) {
            assertFalse(CurveGenerators.matches(path(g, CurveKind.CURVE).copy(generator = null)))
            assertFalse("Polyline has no generators", CurveGenerators.matches(path(g, CurveKind.POLYLINE)))
        }
    }

    @Test
    fun aHandEditedBezierCurveNoLongerMatches() {
        for (g in generators) {
            val p = path(g, CurveKind.CURVE)
            val n = p.subpaths[0].anchors.size
            val mid = n / 2
            val edits = mapOf(
                "a point moved 0.05 px" to p.withAnchors { s -> s.copy(anchors = s.anchors.mapIndexed { i, a -> if (i == mid) a.copy(x = a.x + 0.05f) else a }) },
                "the last point moved" to p.withAnchors { s -> s.copy(anchors = s.anchors.mapIndexed { i, a -> if (i == n - 1) a.copy(y = a.y - 1f) else a }) },
                "a handle longer" to p.withAnchors { s -> s.copy(anchors = s.anchors.mapIndexed { i, a -> if (i == mid) a.copy(outX = a.outX!! * 1.1f, outY = a.outY!! * 1.1f) else a }) },
                "a point deleted" to p.withAnchors { s -> s.copy(anchors = s.anchors.filterIndexed { i, _ -> i != mid }) },
                "a point added" to p.withAnchors { s -> s.copy(anchors = s.anchors.take(mid) + s.anchors[mid].copy(x = s.anchors[mid].x + 3f) + s.anchors.drop(mid)) },
                "a sharp corner" to p.withAnchors { s -> s.copy(anchors = s.anchors.mapIndexed { i, a -> if (i == mid) a.copy(sharp = true) else a }) },
                "the automatic tangent" to p.withAnchors { s -> s.copy(anchors = s.anchors.mapIndexed { i, a -> if (i == mid) a.copy(inX = null, inY = null, outX = null, outY = null) else a }) },
                "closed toggled" to p.withAnchors { s -> s.copy(closed = !s.closed) },
                "another generator" to p.copy(generator = g.copy(size = g.size + 0.5f)),
                "two subpaths" to p.copy(subpaths = p.subpaths + p.subpaths),
            )
            for ((what, edited) in edits) assertFalse("${g.kind}: $what", CurveGenerators.matches(edited))
        }
    }

    @Test
    fun pointThicknessIsNotGeometry() {
        for (g in generators) {
            val p = path(g, CurveKind.CURVE)
            assertTrue(CurveGenerators.matches(p.withAnchors { s -> s.copy(anchors = s.anchors.mapIndexed { i, a -> a.copy(width = if (i % 2 == 0) 0.3f else 2f) }) }))
            val q = path(g, CurveKind.PATH)
            val sp = q.spline!!
            assertTrue(CurveGenerators.matches(q.copy(spline = sp.copy(points = sp.points.map { it.copy(width = 2f) }))))
        }
    }

    @Test
    fun aHandEditedPathNoLongerMatches() {
        for (g in generators) {
            val p = path(g, CurveKind.PATH)
            val sp = p.spline!!
            val mid = sp.points.size / 2
            val edits = mapOf(
                "a control point moved" to p.copy(spline = sp.copy(points = sp.points.mapIndexed { i, q -> if (i == mid) q.copy(x = q.x + 0.05f) else q })),
                "a weight" to p.copy(spline = sp.copy(points = sp.points.mapIndexed { i, q -> if (i == mid) q.copy(weight = 2f) else q })),
                "a sharp point" to p.copy(spline = sp.copy(points = sp.points.mapIndexed { i, q -> if (i == mid) q.copy(sharp = true) else q })),
                "a point deleted" to p.copy(spline = sp.copy(points = sp.points.filterIndexed { i, _ -> i != mid })),
                "the order" to p.copy(spline = sp.copy(order = 3)),
                "cyclic toggled" to p.copy(spline = sp.copy(cyclic = !sp.cyclic)),
                "the Bézier form edited" to p.withAnchors { s -> s.copy(anchors = s.anchors.mapIndexed { i, a -> if (i == 1) a.copy(x = a.x + 2f) else a }) },
            )
            for ((what, edited) in edits) assertFalse("${g.kind}: $what", CurveGenerators.matches(edited))
        }
    }
}
