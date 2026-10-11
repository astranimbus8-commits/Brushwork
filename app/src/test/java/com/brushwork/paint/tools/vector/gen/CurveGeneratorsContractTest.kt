package com.brushwork.paint.tools.vector.gen

import com.brushwork.paint.tools.vector.CurveKind
import com.brushwork.paint.vector.CurveGenKind
import com.brushwork.paint.vector.CurveGenerator
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.8 F3 (design §4.4): the contract of [CurveGenerators.build], which every caller relies on
 * from the foundation on (area E gives it its real geometry): for every generator kind and curve
 * kind, a usable open subpath of at least two finite anchors; a spline for the Path tool only;
 * damaged generators still build.
 */
class CurveGeneratorsContractTest {
    private fun assertUsable(b: CurveBuild, kind: CurveKind, what: String) {
        val a = b.subpath.anchors
        assertTrue("$what: at least two anchors (${a.size})", a.size >= 2)
        assertTrue("$what: finite anchors", a.all { it.x.isFinite() && it.y.isFinite() })
        if (kind == CurveKind.PATH) {
            assertNotNull("$what: a spline for the Path tool", b.spline)
            val s = b.spline!!
            assertTrue("$what: at least two control points", s.points.size >= 2)
            assertTrue("$what: finite control points", s.points.all { it.x.isFinite() && it.y.isFinite() })
        } else {
            assertNull("$what: no spline outside the Path tool", b.spline)
        }
    }

    @Test
    fun everyKindBuildsAUsableCurve() {
        for (g in CurveGenKind.entries) for (k in CurveKind.entries) {
            val gen = CurveGenerator(kind = g, cx = 200f, cy = 150f, size = 120f)
            assertUsable(CurveGenerators.build(gen, k), k, "$g / $k")
        }
    }

    @Test
    fun extremeAndDamagedGeneratorsStillBuild() {
        val odd = listOf(
            CurveGenerator(turns = CurveGenerator.MAX_TURNS, density = CurveGenerator.MAX_DENSITY),
            CurveGenerator(turns = 0f, size = 1f),
            CurveGenerator(size = -5f),
            CurveGenerator(cx = Float.NaN),
            CurveGenerator(angleDeg = Float.POSITIVE_INFINITY, clockwise = true),
        )
        for ((i, g) in odd.withIndex()) for (k in CurveKind.entries) assertUsable(CurveGenerators.build(g, k), k, "case $i / $k")
    }
}
