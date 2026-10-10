package com.brushwork.paint.vector

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * v1.8 F1 (item 6, I16): the [CurveGenerator] model. [CurveGenerator.sanitized] clamps every
 * value and refuses an unusable placement; [CurveGenerator.mapped] follows similarities only
 * (a reflection mirrors the angle and flips the direction) and refuses every other map, so the
 * caller drops the generator. JVM.
 */
class CurveGeneratorModelTest {
    private val g = CurveGenerator(
        kind = CurveGenKind.LOGARITHMIC, cx = 120f, cy = -40f, size = 150f, angleDeg = 30f, turns = 4.5f, inner = 0.2f,
        growth = 3f, clockwise = false, amplitude = 25f, waves = 6f, petalsN = 7, petalsD = 3, density = 12,
    )

    private fun rotation(deg: Double): FloatArray {
        val r = Math.toRadians(deg)
        val c = cos(r).toFloat(); val s = sin(r).toFloat()
        return floatArrayOf(c, -s, 0f, s, c, 0f, 0f, 0f, 1f)
    }

    @Test
    fun aUsableGeneratorIsKeptAsItIs() {
        assertSame(g, g.sanitized())
        val d = CurveGenerator()
        assertSame(d, d.sanitized())
        for (k in CurveGenKind.entries) {
            val x = g.copy(kind = k)
            assertSame(k.name, x, x.sanitized())
        }
    }

    @Test
    fun sanitizedClampsEveryValue() {
        val s = g.copy(
            cx = 2e5f, cy = -3e5f, size = 9e5f, angleDeg = 725f, turns = 0f, inner = 1f, growth = 1f, amplitude = -2e5f,
            waves = 99f, petalsN = 0, petalsD = 20, density = -3,
        ).sanitized()!!
        assertEquals(CurveGenerator.MAX_SIZE, s.cx, 0f)
        assertEquals(-CurveGenerator.MAX_SIZE, s.cy, 0f)
        assertEquals(CurveGenerator.MAX_SIZE, s.size, 0f)
        assertEquals(5f, s.angleDeg, 1e-3f)
        assertEquals(0.25f, s.turns, 0f)
        assertEquals(0.9f, s.inner, 0f)
        assertEquals(1.05f, s.growth, 0f)
        assertEquals(-CurveGenerator.MAX_SIZE, s.amplitude, 0f)
        assertEquals(50f, s.waves, 0f)
        assertEquals(1, s.petalsN)
        assertEquals(12, s.petalsD)
        assertEquals(0, s.density)
        val t = g.copy(turns = 99f, growth = 50f, waves = 0.1f, petalsN = 30, petalsD = 0, density = 100).sanitized()!!
        assertEquals(CurveGenerator.MAX_TURNS, t.turns, 0f)
        assertEquals(10f, t.growth, 0f)
        assertEquals(0.5f, t.waves, 0f)
        assertEquals(24, t.petalsN)
        assertEquals(1, t.petalsD)
        assertEquals(CurveGenerator.MAX_DENSITY, t.density)
        assertSame("idempotent", s, s.sanitized())
        assertSame(t, t.sanitized())
    }

    @Test
    fun nonFiniteValuesTakeTheirDefaultsAndAnUnusablePlacementIsRefused() {
        val d = CurveGenerator()
        val s = g.copy(angleDeg = Float.POSITIVE_INFINITY, turns = Float.NaN, inner = Float.NaN, growth = Float.NEGATIVE_INFINITY, amplitude = Float.NaN, waves = Float.NaN).sanitized()!!
        assertEquals(d.angleDeg, s.angleDeg, 0f)
        assertEquals(d.turns, s.turns, 0f)
        assertEquals(d.inner, s.inner, 0f)
        assertEquals(d.growth, s.growth, 0f)
        assertEquals(d.amplitude, s.amplitude, 0f)
        assertEquals(d.waves, s.waves, 0f)
        assertEquals("the rest is kept", g.cx, s.cx, 0f)
        assertNull(g.copy(cx = Float.NaN).sanitized())
        assertNull(g.copy(cy = Float.POSITIVE_INFINITY).sanitized())
        assertNull(g.copy(size = 0f).sanitized())
        assertNull(g.copy(size = -5f).sanitized())
        assertNull(g.copy(size = Float.NaN).sanitized())
    }

    @Test
    fun mappedFollowsMovesTurnsAndUniformScales() {
        assertEquals("the identity keeps it exactly", g, g.mapped(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)))
        assertEquals("a homogeneous scale is the identity", g, g.mapped(floatArrayOf(2f, 0f, 0f, 0f, 2f, 0f, 0f, 0f, 2f)))
        val moved = g.mapped(floatArrayOf(1f, 0f, 10f, 0f, 1f, -5f, 0f, 0f, 1f))!!
        assertEquals(g.copy(cx = 130f, cy = -45f), moved)
        val scaled = g.mapped(floatArrayOf(2f, 0f, 0f, 0f, 2f, 0f, 0f, 0f, 1f))!!
        assertEquals(g.copy(cx = 240f, cy = -80f, size = 300f, amplitude = 50f), scaled)
        // A quarter turn (clockwise on screen): the centre turns and the angle grows by 90.
        val turned = g.mapped(rotation(90.0))!!
        assertEquals(40f, turned.cx, 1e-3f)
        assertEquals(120f, turned.cy, 1e-3f)
        assertEquals(120f, turned.angleDeg, 1e-3f)
        assertEquals(150f, turned.size, 1e-3f)
        assertFalse(turned.clockwise)
        // Turned and scaled together.
        val m = rotation(-30.0).map { it * 0.5f }.toFloatArray().also { it[8] = 1f }
        val both = g.mapped(m)!!
        assertEquals(0f, both.angleDeg, 1e-3f)
        assertEquals(75f, both.size, 1e-3f)
        assertEquals(12.5f, both.amplitude, 1e-3f)
    }

    @Test
    fun aReflectionMirrorsTheAngleAndFlipsTheDirection() {
        // Mirror in the vertical line x = 100: a start direction of 30 degrees becomes 150.
        val mx = g.mapped(floatArrayOf(-1f, 0f, 200f, 0f, 1f, 0f, 0f, 0f, 1f))!!
        assertEquals(80f, mx.cx, 1e-3f)
        assertEquals(-40f, mx.cy, 1e-3f)
        assertEquals(150f, mx.angleDeg, 1e-3f)
        assertTrue(mx.clockwise)
        assertEquals(g.size, mx.size, 0f)
        // Mirror in the x axis: 30 becomes -30.
        val my = g.mapped(floatArrayOf(1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 1f))!!
        assertEquals(-30f, my.angleDeg, 1e-3f)
        assertEquals(40f, my.cy, 1e-3f)
        assertTrue(my.clockwise)
        // Twice is the original.
        val back = mx.mapped(floatArrayOf(-1f, 0f, 200f, 0f, 1f, 0f, 0f, 0f, 1f))!!
        assertEquals(g.cx, back.cx, 1e-3f)
        assertEquals(g.angleDeg, back.angleDeg, 1e-3f)
        assertFalse(back.clockwise)
    }

    @Test
    fun anythingButASimilarityIsRefused() {
        assertNull("a non-uniform scale", g.mapped(floatArrayOf(2f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)))
        assertNull("a shear", g.mapped(floatArrayOf(1f, 0.5f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)))
        assertNull("a homography", g.mapped(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0.001f, 0f, 1f)))
        assertNull("a singular map", g.mapped(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f)))
        assertNull("a NaN", g.mapped(floatArrayOf(1f, 0f, Float.NaN, 0f, 1f, 0f, 0f, 0f, 1f)))
        assertNull("too short", g.mapped(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)))
        // Float rounding of a turn is still a similarity.
        assertNotNull(g.mapped(rotation(37.0)))
        // A result out of range is clamped, as on read.
        val far = g.mapped(floatArrayOf(1000f, 0f, 0f, 0f, 1000f, 0f, 0f, 0f, 1f))!!
        assertEquals(CurveGenerator.MAX_SIZE, far.size, 0f)
        assertEquals(CurveGenerator.MAX_SIZE, far.cx, 0f)
    }

    @Test
    fun theJsonRoundTrips() {
        val json = Json { encodeDefaults = true }
        for (k in CurveGenKind.entries) {
            val x = g.copy(kind = k, clockwise = k.ordinal % 2 == 0)
            assertEquals(x, json.decodeFromString(CurveGenerator.serializer(), json.encodeToString(CurveGenerator.serializer(), x)))
        }
    }
}
