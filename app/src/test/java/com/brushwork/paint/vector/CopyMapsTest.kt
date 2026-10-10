package com.brushwork.paint.vector

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.8 F1 (I15): [CopyMaps] is always sanitized: affine maps only, the identity first, 2..256 maps
 * or none; compared by content; [CopyMaps.conjugated] follows an affine transform of the object
 * and leaves the copies alone for anything else. JVM.
 */
class CopyMapsTest {
    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    /** Mirror in the vertical line x = 100. */
    private val mirror = floatArrayOf(-1f, 0f, 200f, 0f, 1f, 0f, 0f, 0f, 1f)
    /** Half turn about (100, 50). */
    private val halfTurn = floatArrayOf(-1f, 0f, 200f, 0f, -1f, 100f, 0f, 0f, 1f)
    private val perspective = floatArrayOf(1.1f, 0.05f, 3f, 0.02f, 0.9f, -4f, 0.0004f, 0.0002f, 1f)

    private fun translate(dx: Float, dy: Float) = floatArrayOf(1f, 0f, dx, 0f, 1f, dy, 0f, 0f, 1f)

    @Test
    fun noneIsEmptyAndDrawsTheObjectOnce() {
        val none = CopyMaps.NONE
        assertTrue(none.isEmpty)
        assertEquals(1, none.count)
        assertEquals(1, none.orIdentity().size)
        assertArrayEquals(identity, none.orIdentity()[0], 0f)
        assertSame(none, CopyMaps.of(emptyList()))
        // The identity alone (or twice) is no copy at all.
        assertSame(none, CopyMaps.of(listOf(identity)))
        assertSame(none, CopyMaps.of(listOf(identity, identity.copyOf())))
    }

    @Test
    fun theIdentityComesFirstAndOnlyOnce() {
        val a = CopyMaps.of(listOf(mirror))
        assertEquals(2, a.maps.size)
        assertEquals(2, a.count)
        assertArrayEquals("the identity is put in front", identity, a.maps[0], 0f)
        assertArrayEquals(mirror, a.maps[1], 0f)
        val b = CopyMaps.of(listOf(mirror, identity))
        assertEquals("the identity moves to the front", a, b)
        val c = CopyMaps.of(listOf(identity, mirror, identity, halfTurn))
        assertEquals(3, c.maps.size)
        assertArrayEquals(identity, c.maps[0], 0f)
        assertArrayEquals(mirror, c.maps[1], 0f)
        assertArrayEquals("the other maps keep their order", halfTurn, c.maps[2], 0f)
        assertSame(c.maps, c.orIdentity())
    }

    @Test
    fun damagedAndNonAffineMapsAreDropped() {
        val nan = mirror.copyOf().also { it[2] = Float.NaN }
        val inf = mirror.copyOf().also { it[0] = Float.POSITIVE_INFINITY }
        val huge = mirror.copyOf().also { it[2] = 3e7f }
        val singular = floatArrayOf(1f, 2f, 0f, 2f, 4f, 0f, 0f, 0f, 1f)
        val short = floatArrayOf(-1f, 0f, 200f, 0f, 1f, 0f)
        val scaledW = floatArrayOf(-2f, 0f, 400f, 0f, 2f, 0f, 0f, 0f, 2f)
        val c = CopyMaps.of(listOf(identity, perspective, nan, inf, huge, singular, short, scaledW, mirror))
        assertEquals("only the usable affine map is a copy", CopyMaps.of(listOf(mirror)), c)
        assertSame("nothing usable: no copies", CopyMaps.NONE, CopyMaps.of(listOf(perspective, nan, singular, short)))
    }

    @Test
    fun atMost256Maps() {
        val many = List(400) { translate(10f * (it + 1), 0f) }
        val c = CopyMaps.of(many)
        assertEquals(StrokeCopies.MAX, c.maps.size)
        assertArrayEquals(identity, c.maps[0], 0f)
        assertArrayEquals("the first ones are kept", many[0], c.maps[1], 0f)
        assertArrayEquals(many[StrokeCopies.MAX - 2], c.maps.last(), 0f)
    }

    @Test
    fun ofIsIdempotentAndCopiesTheArrays() {
        val input = mirror.copyOf()
        val c = CopyMaps.of(listOf(input, halfTurn))
        input[2] = 999f
        assertEquals("a later change of the caller's array changes nothing", 200f, c.maps[1][2], 0f)
        val again = CopyMaps.of(c.maps)
        assertEquals(c, again)
        assertEquals(c.hashCode(), again.hashCode())
        assertEquals(c.maps.map { it.toList() }, again.maps.map { it.toList() })
    }

    @Test
    fun equalByContent() {
        val a = CopyMaps.of(listOf(mirror))
        val b = CopyMaps.of(listOf(mirror.copyOf()))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, CopyMaps.of(listOf(halfTurn)))
        assertNotEquals(a, CopyMaps.of(listOf(mirror, halfTurn)))
        assertNotEquals(a, CopyMaps.NONE)
        assertEquals(CopyMaps.NONE, CopyMaps.of(listOf(identity)))
    }

    @Test
    fun conjugatedFollowsAnAffineTransformOfTheObject() {
        val c = CopyMaps.of(listOf(mirror))
        // Moved 50 px right, the mirror line moves with it: x = 150.
        val moved = c.conjugated(translate(50f, 0f))
        assertEquals(2, moved.maps.size)
        assertArrayEquals("the identity stays exact", identity, moved.maps[0], 0f)
        assertArrayEquals(floatArrayOf(-1f, 0f, 300f, 0f, 1f, 0f, 0f, 0f, 1f), moved.maps[1], 1e-4f)
        // Scaled 2x about the origin: the line x = 100 becomes x = 200.
        val scaled = c.conjugated(floatArrayOf(2f, 0f, 0f, 0f, 2f, 0f, 0f, 0f, 1f))
        assertArrayEquals(floatArrayOf(-1f, 0f, 400f, 0f, 1f, 0f, 0f, 0f, 1f), scaled.maps[1], 1e-4f)
        // The identity map changes nothing.
        assertEquals(c, c.conjugated(identity))
        // No copies, a homography or a singular map: unchanged (the caller bakes the copies).
        assertSame(CopyMaps.NONE, CopyMaps.NONE.conjugated(translate(5f, 5f)))
        assertSame(c, c.conjugated(perspective))
        assertSame(c, c.conjugated(floatArrayOf(1f, 2f, 0f, 2f, 4f, 0f, 0f, 0f, 1f)))
        assertSame(c, c.conjugated(floatArrayOf(1f, 0f, Float.NaN, 0f, 1f, 0f, 0f, 0f, 1f)))
    }
}
