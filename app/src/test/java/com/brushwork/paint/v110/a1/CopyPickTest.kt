package com.brushwork.paint.v110.a1

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.vector.CopyMaps
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.copies.CopyPick
import com.brushwork.paint.vector.select.ObjectTestKit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.cos
import kotlin.math.sin

/**
 * v1.10 (R1 (c), A1 step 1): [CopyPick.hitCopy] names the copy of a mirrored path or shape under
 * the finger (the top-most one where copies overlap) for every live mirror kind (Mirror,
 * Kaleidoscope, Rotation, Array), with the tolerance measured on screen whatever the map's scale;
 * [CopyPick.side] and [CopyPick.boundsOf] are copy k alone. `VectorOps.hit` stays v1.9's answer.
 */
@RunWith(RobolectricTestRunner::class)
class CopyPickTest {
    private val kit = ObjectTestKit()

    @After
    fun tearDown() = kit.close()

    private val cx = 256f
    private val cy = 192f

    private fun identity() = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private fun mirrorX() = floatArrayOf(-1f, 0f, 2f * cx, 0f, 1f, 0f, 0f, 0f, 1f)
    private fun mirrorY() = floatArrayOf(1f, 0f, 0f, 0f, -1f, 2f * cy, 0f, 0f, 1f)
    private fun translate(dx: Float, dy: Float) = floatArrayOf(1f, 0f, dx, 0f, 1f, dy, 0f, 0f, 1f)

    /** A turn by [deg] about the document centre. */
    private fun turn(deg: Double): FloatArray {
        val r = Math.toRadians(deg)
        val c = cos(r).toFloat(); val s = sin(r).toFloat()
        return floatArrayOf(c, -s, cx - c * cx + s * cy, s, c, cy - s * cx - c * cy, 0f, 0f, 1f)
    }

    private fun mul(a: FloatArray, b: FloatArray): FloatArray = FloatArray(9) { i ->
        val r = i / 3; val col = i % 3
        a[r * 3] * b[col] + a[r * 3 + 1] * b[3 + col] + a[r * 3 + 2] * b[6 + col]
    }

    private fun apply(m: FloatArray, p: Vec2) = Vec2(m[0] * p.x + m[1] * p.y + m[2], m[3] * p.x + m[4] * p.y + m[5])

    private fun withMaps(o: VObject, maps: List<FloatArray>): VObject = ObjectCopies.withCopies(o, CopyMaps.of(maps))

    /** Each copy's centre picks that copy, the empty middle picks nothing, and `hit` agrees. */
    private fun assertEachCopyPicked(kind: String, o: VObject, centre: Vec2) {
        val maps = ObjectCopies.of(o).maps
        assertTrue("$kind has copies", maps.size >= 2)
        for (k in maps.indices) {
            val p = apply(maps[k], centre)
            assertEquals("$kind: copy $k picked at its centre", k, VectorOps.hitCopy(o, p, 2f))
            assertEquals("$kind: CopyPick agrees", k, CopyPick.hitCopy(o, p, 2f))
            assertTrue("$kind: v1.9 hit", VectorOps.hit(o, p, 2f))
        }
        assertEquals("$kind: nothing at the centre of the document", -1, VectorOps.hitCopy(o, Vec2(cx, cy), 2f))
        assertTrue("$kind: v1.9 miss", !VectorOps.hit(o, Vec2(cx, cy), 2f))
    }

    @Test
    fun everyMirrorKindPicksTheCopyUnderTheFinger() {
        val path = kit.box(60f, 60f, 110f, 100f).copy(id = 1)
        val shape = kit.ellipse(85f, 80f, 50f, 40f).copy(id = 2)
        val centre = Vec2(85f, 80f)
        for (o in listOf<VObject>(path, shape)) {
            val name = if (o is VPath) "path" else "shape"
            assertEachCopyPicked("$name Mirror", withMaps(o, listOf(identity(), mirrorX())), centre)
            assertEachCopyPicked("$name Kaleidoscope", withMaps(o, listOf(identity(), mirrorX(), mirrorY(), mul(mirrorX(), mirrorY()))), centre)
            assertEachCopyPicked("$name Rotation", withMaps(o, listOf(identity(), turn(120.0), turn(240.0))), centre)
            assertEachCopyPicked("$name Array", withMaps(o, listOf(identity(), translate(120f, 0f), translate(0f, 160f), translate(300f, 200f))), centre)
        }
    }

    @Test
    fun overlappingCopiesPickTheTopMostOne() {
        // An array whose copies overlap: copy 2 is drawn last, so it is on top where all three are.
        val o = withMaps(kit.box(100f, 100f, 160f, 140f).copy(id = 1), listOf(identity(), translate(20f, 0f), translate(40f, 0f)))
        assertEquals(2, VectorOps.hitCopy(o, Vec2(150f, 120f), 1f))
        assertEquals("only the source and copy 1 there", 1, VectorOps.hitCopy(o, Vec2(130f, 120f), 1f))
        assertEquals("only the source there", 0, VectorOps.hitCopy(o, Vec2(105f, 120f), 1f))
        assertEquals("only copy 2 there", 2, VectorOps.hitCopy(o, Vec2(195f, 120f), 1f))
        // A skipped copy (a lifted side) is not hit: the one below it is.
        assertEquals(1, VectorOps.hitCopy(o, Vec2(150f, 120f), 1f, skip = 2))
        assertEquals(-1, VectorOps.hitCopy(o, Vec2(195f, 120f), 1f, skip = 2))
    }

    @Test
    fun theToleranceIsMeasuredWhereTheCopyIsDrawn() {
        // Copy 1 is drawn twice as large: a tap 5 px beside its drawn edge is within 6 px, 7 px is not.
        val src = kit.box(40f, 40f, 80f, 80f).copy(id = 1, stroke = null)
        val big = floatArrayOf(2f, 0f, 200f, 0f, 2f, 100f, 0f, 0f, 1f) // edge x = 360 at the right side
        val o = withMaps(src, listOf(identity(), big))
        val y = 2f * 60f + 100f
        assertEquals(1, VectorOps.hitCopy(o, Vec2(365f, y), 6f))
        assertEquals(-1, VectorOps.hitCopy(o, Vec2(367f, y), 6f))
        // The source keeps the tolerance as it is.
        assertEquals(0, VectorOps.hitCopy(o, Vec2(85f, 60f), 6f))
        assertEquals(-1, VectorOps.hitCopy(o, Vec2(87f, 60f), 6f))
    }

    @Test
    fun sideIsCopyKAloneAndItsBoundsAreInsideTheObjects() {
        val src = kit.ellipse(85f, 80f, 50f, 40f).copy(id = 5)
        val o = withMaps(src, listOf(identity(), mirrorX(), turn(90.0)))
        val s0 = CopyPick.side(o, 0)!!
        assertTrue("k = 0 is the source", ObjectCopies.of(s0).isEmpty)
        assertEquals(5L, s0.id)
        val s1 = CopyPick.side(o, 1) as VShape
        assertEquals(2f * cx - 85f, s1.shape.cx, 1e-3f)
        assertEquals(80f, s1.shape.cy, 1e-3f)
        assertTrue(ObjectCopies.of(s1).isEmpty)
        assertNull("no copy 3", CopyPick.side(o, 3))
        assertNull(CopyPick.side(o, -1))
        val all = VectorOps.bounds(o)
        for (k in 0..2) {
            val b = CopyPick.boundsOf(o, k)
            assertTrue("copy $k has a box", !b.isEmpty)
            assertTrue("copy $k inside the object's box", all.left <= b.left + 0.01f && all.top <= b.top + 0.01f && all.right >= b.right - 0.01f && all.bottom >= b.bottom - 0.01f)
        }
        assertEquals(VectorOps.bounds(s1), CopyPick.boundsOf(o, 1))
        assertTrue(CopyPick.boundsOf(o, 7).isEmpty)
        // Without copies: the object itself, as k = 0 only.
        assertSame(src, CopyPick.side(src, 0))
        assertNull(CopyPick.side(src, 1))
        assertEquals(0, VectorOps.hitCopy(src, Vec2(85f, 80f), 1f))
        assertEquals(-1, VectorOps.hitCopy(src, Vec2(300f, 300f), 1f))
    }
}
