package com.brushwork.paint.vector

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
 * v1.8 (I16, design §3.7): `VectorOps.transformed` maps a path's curve generator by
 * [CurveGenerator.mapped] under a similarity (a rotation, a uniform scale, a move, a mirror) and
 * drops it under any other map. Under a skew or a non-uniform scale the spline is kept (NURBS are
 * affine-invariant) and the generator is not; a homography drops both. A path without a
 * generator transforms exactly as before, and a path with live copies maps its source's
 * generator while its copies are conjugated. (Area E's `GeneratorTransformTest` checks the
 * mapped generator still builds the mapped geometry once the real `CurveGenerators` lands.)
 */
@RunWith(RobolectricTestRunner::class)
class VectorOpsGeneratorTest {
    private val g = CurveGenerator(kind = CurveGenKind.ARCHIMEDEAN, cx = 100f, cy = 80f, size = 60f, angleDeg = 15f, turns = 2f)

    private val path = VPath(
        1,
        subpaths = listOf(VSubpath(listOf(VAnchor(40f, 80f, sharp = true), VAnchor(100f, 20f, sharp = true), VAnchor(160f, 80f, sharp = true)))),
        stroke = VStrokeStyle(color = 0xFF000000.toInt(), width = 4f),
        spline = VSpline(listOf(VSplinePoint(40f, 80f), VSplinePoint(100f, 20f), VSplinePoint(160f, 80f)), order = 3),
        generator = g,
    )

    /** Rotation by 30°, scale 2, then a move by (10, -5). */
    private val similarity: FloatArray = run {
        val r = Math.toRadians(30.0)
        val c = (2 * cos(r)).toFloat(); val s = (2 * sin(r)).toFloat()
        floatArrayOf(c, -s, 10f, s, c, -5f, 0f, 0f, 1f)
    }
    private val mirror = floatArrayOf(-1f, 0f, 512f, 0f, 1f, 0f, 0f, 0f, 1f)
    private val skew = floatArrayOf(1f, 0.5f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private val stretch = floatArrayOf(2f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private val homography = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0.001f, 0f, 1f)

    private fun mapped(o: VObject, m: FloatArray): VPath = VectorOps.transformed(o, m) as VPath

    @Test
    fun aSimilarityMapsTheGenerator() {
        val out = mapped(path, similarity)
        val expected = g.mapped(similarity)
        assertNotNull(expected)
        assertEquals(expected, out.generator)
        val gen = out.generator ?: throw AssertionError("the generator was dropped")
        assertEquals("the centre follows the map", 2 * 100f * cos(Math.toRadians(30.0)).toFloat() - 2 * 80f * sin(Math.toRadians(30.0)).toFloat() + 10f, gen.cx, 1e-3f)
        assertEquals("the size scales", 120f, gen.size, 1e-3f)
        assertEquals("the angle turns", 45f, gen.angleDeg, 1e-3f)
        assertNotNull("the spline is kept too", out.spline)
    }

    @Test
    fun aMirrorKeepsTheGeneratorAndFlipsItsDirection() {
        val out = mapped(path, mirror)
        assertEquals(g.mapped(mirror), out.generator)
        val gen = out.generator ?: throw AssertionError("the generator was dropped")
        assertEquals(412f, gen.cx, 1e-3f)
        assertTrue("clockwise flips", gen.clockwise != g.clockwise)
    }

    @Test
    fun aSkewOrAStretchDropsTheGeneratorAndKeepsTheSpline() {
        for ((name, m) in listOf("skew" to skew, "stretch" to stretch)) {
            val out = mapped(path, m)
            assertNull("$name: the curve is no longer the generator's", out.generator)
            assertNotNull("$name: the spline maps (affine)", out.spline)
        }
    }

    @Test
    fun aHomographyDropsTheGeneratorAndTheSpline() {
        val out = mapped(path, homography)
        assertNull(out.generator)
        assertNull(out.spline)
    }

    @Test
    fun theGeometryDoesNotDependOnTheGenerator() {
        val plain = path.copy(generator = null)
        for (m in listOf(similarity, mirror, skew, stretch, homography)) {
            val without = mapped(plain, m)
            assertNull("no generator stays none", without.generator)
            assertEquals(without, mapped(path, m).copy(generator = null))
        }
    }

    @Test
    fun aPathWithCopiesMapsItsSourcesGenerator() {
        val copies = CopyMaps.of(listOf(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), mirror))
        val linked = path.copy(copies = copies)
        val out = mapped(linked, similarity)
        assertEquals(g.mapped(similarity), out.generator)
        assertEquals(copies.conjugated(similarity), out.copies)
        // A homography expands the copies first (ObjectCopies.transformedAll): no piece keeps a generator.
        val pieces = ObjectCopies.transformedAll(linked, homography)
        assertEquals(2, pieces.size)
        for (p in pieces) assertNull((p as VPath).generator)
    }
}
