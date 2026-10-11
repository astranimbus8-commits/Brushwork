package com.brushwork.paint.tools.vector.gen

import com.brushwork.paint.tools.vector.CurveKind
import com.brushwork.paint.vector.CurveGenKind
import com.brushwork.paint.vector.CurveGenerator
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VectorOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.cos
import kotlin.math.sin

/**
 * v1.8 (I16, design §3.7b): a generated path transformed by a similarity (a move, a turn, a
 * uniform scale, a mirror; the Transform tool's `VectorOps.transformed`) keeps its generator,
 * mapped, and the mapped generator still builds the mapped geometry: the path still matches
 * ([CurveGenerators.matches]) and its spline equals the mapped generator's build within 0.01 px.
 * A skew or a stretch drops the generator (the curve is no longer the generator's).
 */
@RunWith(RobolectricTestRunner::class)
class GeneratorTransformTest {
    private fun rotateScaleMove(deg: Double, s: Double, tx: Float, ty: Float): FloatArray {
        val r = Math.toRadians(deg)
        val c = (s * cos(r)).toFloat(); val n = (s * sin(r)).toFloat()
        return floatArrayOf(c, -n, tx, n, c, ty, 0f, 0f, 1f)
    }

    private val similarities = mapOf(
        "a move" to floatArrayOf(1f, 0f, 37.5f, 0f, 1f, -12.25f, 0f, 0f, 1f),
        "a turn, a scale and a move" to rotateScaleMove(30.0, 2.0, 10f, -5f),
        "a shrink and a turn" to rotateScaleMove(-115.0, 0.4, 300f, 20f),
        "a mirror" to floatArrayOf(-1f, 0f, 512f, 0f, 1f, 0f, 0f, 0f, 1f),
        "a mirror and a turn" to floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f),
        "three times bigger" to floatArrayOf(3f, 0f, 0f, 0f, 3f, 0f, 0f, 0f, 1f),
    )

    private val others = mapOf(
        "a skew" to floatArrayOf(1f, 0.5f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
        "a stretch" to floatArrayOf(2f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
    )

    private val generators = CurveGenKind.entries.map { k ->
        CurveGenerator(kind = k, cx = 200f, cy = 160f, size = 120f, angleDeg = 15f, turns = 2f, waves = 2f, amplitude = 30f, petalsN = 3, petalsD = 2, clockwise = k == CurveGenKind.FERMAT)
    }

    private fun path(g: CurveGenerator, kind: CurveKind): VPath {
        val b = CurveGenerators.build(g, kind)
        return VPath(3, subpaths = listOf(b.subpath), stroke = VStrokeStyle(color = 0xFF000000.toInt(), width = 4f), spline = b.spline, generator = g)
    }

    @Test
    fun aSimilarityKeepsTheGeneratorAndItStillBuildsTheGeometry() {
        for (g in generators) for (kind in listOf(CurveKind.CURVE, CurveKind.PATH)) for ((name, m) in similarities) {
            val out = VectorOps.transformed(path(g, kind), m) as VPath
            val mg = out.generator
            assertNotNull("${g.kind} / $kind, $name: the generator is kept", mg)
            assertEquals(g.mapped(m), mg)
            assertTrue("${g.kind} / $kind, $name: the mapped path still matches", CurveGenerators.matches(out))
            if (kind == CurveKind.PATH) {
                val built = CurveGenerators.build(mg!!, kind).spline!!
                val stored = out.spline!!
                assertEquals(built.points.size, stored.points.size)
                for (i in built.points.indices) {
                    assertEquals("${g.kind}, $name: point $i x", built.points[i].x, stored.points[i].x, 0.01f)
                    assertEquals("${g.kind}, $name: point $i y", built.points[i].y, stored.points[i].y, 0.01f)
                }
            }
        }
    }

    @Test
    fun aSkewOrAStretchDropsTheGenerator() {
        for (g in generators) for (kind in listOf(CurveKind.CURVE, CurveKind.PATH)) for ((name, m) in others) {
            val out = VectorOps.transformed(path(g, kind), m) as VPath
            assertNull("${g.kind} / $kind, $name", out.generator)
        }
    }

    @Test
    fun aMovedHandleNoLongerMatchesAfterAMap() {
        for (g in generators) {
            val out = VectorOps.transformed(path(g, CurveKind.CURVE), similarities.getValue("a turn, a scale and a move")) as VPath
            val s = out.subpaths[0]
            val edited = out.copy(subpaths = listOf(s.copy(anchors = s.anchors.mapIndexed { i, a -> if (i == 1) a.copy(outX = a.outX!! + 0.5f) else a })))
            assertTrue(!CurveGenerators.matches(edited))
        }
    }
}
