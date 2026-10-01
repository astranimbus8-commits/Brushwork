package com.brushwork.paint.tools.vector

import com.brushwork.paint.brush.PathStrokeInput
import com.brushwork.paint.core.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * v1.5 F2 (§4.5, §5.8): the per-sample [WidthProfile] of a brush along a path only scales the
 * pressures (sample points bit-identical), and [brushStrokeSamples] is the bit-identical,
 * thread-safe twin of [brushStrokeInput].
 */
class BrushStrokeSamplesTest {
    private val curve = CurveGeometry.toPath(
        listOf(
            CurveAnchor(40f, 200f), CurveAnchor(120f, 60f), CurveAnchor(230f, 180f, sharp = true, handleIn = Vec2(-30f, -40f)),
            CurveAnchor(300f, 70f),
        ),
        closed = false, tension = 0.2f, polyline = false,
    )
    private val loop = CurveGeometry.toPath(
        listOf(CurveAnchor(60f, 60f), CurveAnchor(200f, 50f), CurveAnchor(220f, 190f), CurveAnchor(70f, 170f)),
        closed = true, tension = 0f, polyline = false,
    )

    private fun copy(i: PathStrokeInput) = Triple(i.x.copyOf(i.size), i.y.copyOf(i.size), i.pressure.copyOf(i.size))

    private fun assertBits(what: String, a: FloatArray, b: FloatArray) {
        assertEquals("$what size", a.size, b.size)
        for (k in a.indices) assertEquals("$what [$k]", a[k].toRawBits(), b[k].toRawBits())
    }

    @Test
    fun theTwinGivesTheSameSamplesBitForBit() {
        for (path in listOf(curve, loop)) {
            for (taper in listOf(0f, 0.15f, 0.5f, 0.9f)) {
                val main = copy(brushStrokeInput(path, taper))
                val twin = copy(brushStrokeSamples(path, taper, null, PathStrokeInput()))
                assertBits("x", main.first, twin.first)
                assertBits("y", main.second, twin.second)
                assertBits("p", main.third, twin.third)
                // The samples are CurveGeometry.sample's, 0.75 px apart.
                val ref = CurveGeometry.sample(path, BRUSH_SAMPLE_SPACING)
                assertEquals(ref.size, main.first.size)
                for (k in ref.indices) {
                    assertEquals(ref[k].x.toRawBits(), main.first[k].toRawBits())
                    assertEquals(ref[k].y.toRawBits(), main.second[k].toRawBits())
                }
            }
        }
    }

    @Test
    fun theTwinRunsOnSeveralThreadsAtOnce() {
        val expected = copy(brushStrokeInput(curve, 0.2f))
        val pool = Executors.newFixedThreadPool(4)
        try {
            val jobs = List(16) { pool.submit(Callable { copy(brushStrokeSamples(if (it % 2 == 0) curve else loop, 0.2f, null, PathStrokeInput())) }) }
            jobs.forEachIndexed { i, f ->
                if (i % 2 == 0) {
                    val r = f.get()
                    assertBits("x", expected.first, r.first)
                    assertBits("p", expected.third, r.third)
                } else {
                    f.get()
                }
            }
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun aWidthProfileScalesOnlyThePressures() {
        val plain = copy(brushStrokeInput(curve, 0.2f))
        val n = plain.first.size
        val widths = FloatArray(n) { 0.5f + 2.5f * it / (n - 1) } // 0.5 .. 3
        val wMax = widths.max()
        for (profiled in listOf(
            copy(brushStrokeInput(curve, 0.2f, PathStrokeInput(), WidthProfile(widths))),
            copy(brushStrokeSamples(curve, 0.2f, WidthProfile(widths), PathStrokeInput())),
        )) {
            assertBits("x", plain.first, profiled.first)
            assertBits("y", plain.second, profiled.second)
            for (k in 0 until n) assertEquals("p[$k]", plain.third[k] * widths[k] / wMax, profiled.third[k], 1e-6f)
        }
        // Uniform widths change nothing; a profile of the wrong length is ignored.
        assertBits("uniform", plain.third, copy(brushStrokeInput(curve, 0.2f, PathStrokeInput(), WidthProfile(FloatArray(n) { 2f }))).third)
        assertBits("short", plain.third, copy(brushStrokeInput(curve, 0.2f, PathStrokeInput(), WidthProfile(FloatArray(n - 1) { 0.5f }))).third)
        // All zero: nothing to scale by (pressures stay).
        assertBits("zero", plain.third, copy(brushStrokeInput(curve, 0.2f, PathStrokeInput(), WidthProfile(FloatArray(n)))).third)
        assertTrue(WidthProfile(floatArrayOf(1f, 2f)) == WidthProfile(floatArrayOf(1f, 2f)))
    }
}
