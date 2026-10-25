package com.brushwork.paint.v110.a1

import android.graphics.Color
import android.graphics.RectF
import com.brushwork.paint.v110.a1.A1Kit.kinds
import com.brushwork.paint.v110.a1.A1Kit.mul
import com.brushwork.paint.v110.a1.A1Kit.translate
import com.brushwork.paint.v110.a1.A1Kit.turnAbout
import com.brushwork.paint.v110.a1.A1Kit.withMaps
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.copies.CopyEdit
import com.brushwork.paint.vector.copies.CopyPick
import com.brushwork.paint.vector.select.ObjectTestKit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs

/**
 * v1.10 (R1 (a), S2): `CopyEdit.offset` moves side k by an isometry M and keeps it linked, for
 * every map kind (Mirror, Kaleidoscope, Rotation, Array grid), on a path and on a shape. k ≥ 1:
 * `C_k' = M·C_k`; k = 0: the source is mapped and `C_j' = C_j·M⁻¹`. Side k ends where M puts it,
 * and the unmoved sides are drawn exactly where they were (goldens: the object without side k,
 * rendered before and after).
 */
@RunWith(RobolectricTestRunner::class)
class CopyOffsetMathTest {
    private val kit = ObjectTestKit()

    @After
    fun tearDown() = kit.close()

    private fun objects(): List<Pair<String, VObject>> = listOf(
        "path" to kit.box(60f, 100f, 120f, 140f),
        "shape" to kit.rectangle(90f, 120f, 60f, 40f),
    )

    private fun assertBox(what: String, expected: RectF, actual: RectF, tol: Float = 0.5f) {
        val ok = abs(expected.left - actual.left) <= tol && abs(expected.top - actual.top) <= tol &&
            abs(expected.right - actual.right) <= tol && abs(expected.bottom - actual.bottom) <= tol
        assertTrue("$what: expected $expected, got $actual", ok)
    }

    /**
     * Pixels whose premultiplied channels differ by more than 32 between [a] and [b]: a shift of an
     * edge by a pixel differs by far more along the whole edge, while a 1e-5 px float difference in
     * a rebased anchor changes a round join's antialiasing by up to about 24 (and a pixel at alpha
     * 1/255 is no difference).
     */
    private fun differing(a: IntArray, b: IntArray): Int {
        fun pm(p: Int, ch: Int) = ch * Color.alpha(p) / 255
        var n = 0
        for (i in a.indices) {
            val x = a[i]
            val y = b[i]
            if (abs(Color.alpha(x) - Color.alpha(y)) > 32 || abs(pm(x, Color.red(x)) - pm(y, Color.red(y))) > 32 ||
                abs(pm(x, Color.green(x)) - pm(y, Color.green(y))) > 32 || abs(pm(x, Color.blue(x)) - pm(y, Color.blue(y))) > 32
            ) n++
        }
        return n
    }

    @Test
    fun aMovedSideGoesWhereTheIsometryPutsItAndTheOtherSidesStay() {
        for ((name, base) in objects()) for ((kind, maps) in kinds()) for (k in listOf(0, maps.lastIndex)) {
            val what = "$name, $kind, side $k"
            val old = withMaps(base, maps).withId(5)
            val box = CopyPick.boundsOf(old, k)
            // A turn by 30° about the side's centre, then a move: an isometry.
            val m = mul(translate(17f, -9f), turnAbout(box.centerX(), box.centerY(), 30.0))
            assertTrue(CopyEdit.isIsometry(m))
            val moved = CopyEdit.offset(old, k, m)
            assertNotNull(what, moved)
            moved!!
            assertEquals("$what: the same object", 5L, moved.id)
            assertEquals("$what: still linked, every side kept", maps.size, ObjectCopies.of(moved).maps.size)
            for (j in maps.indices) {
                val now = VectorOps.bounds(CopyPick.side(moved, j)!!)
                if (j == k) assertBox("$what: side $j moved by M", VectorOps.bounds(VectorOps.transformed(CopyPick.side(old, k)!!, m)), now)
                else assertBox("$what: side $j stays", VectorOps.bounds(CopyPick.side(old, j)!!), now)
            }
            // Goldens of the unmoved sides: the object without side k, before and after.
            val before = kit.render(VectorContent(objects = listOf(CopyEdit.dropCopy(old, k)!!)))
            val after = kit.render(VectorContent(objects = listOf(CopyEdit.dropCopy(moved, k)!!)))
            val diff = differing(before, after)
            assertTrue("$what: the unmoved sides are drawn where they were ($diff pixels differ)", diff <= 8)
        }
    }

    @Test
    fun theMapsFollowTheRule() {
        val maps = kinds().first { it.first == "Kaleidoscope" }.second
        val old = withMaps(kit.box(60f, 100f, 120f, 140f), maps)
        val m = mul(translate(5f, 7f), turnAbout(90f, 120f, 90.0))
        // k ≥ 1: only C_k changes, to M·C_k.
        val a = ObjectCopies.of(CopyEdit.offset(old, 2, m)!!).maps
        for (j in maps.indices) {
            val want = if (j == 2) mul(m, maps[j]) else maps[j]
            for (i in 0 until 9) assertEquals("k=2, map $j[$i]", want[i], a[j][i], 1e-3f)
        }
        // k = 0: C_0 stays the identity and C_j' = C_j·M⁻¹ (so C_j'·(M·src) = C_j·src).
        val moved = CopyEdit.offset(old, 0, m)!!
        val b = ObjectCopies.of(moved).maps
        for (i in 0 until 9) assertEquals("k=0, map 0[$i]", maps[0][i], b[0][i], 1e-6f)
        for (j in 1 until maps.size) {
            val back = mul(b[j], m)
            for (i in 0 until 9) assertEquals("k=0, map $j·M[$i]", maps[j][i], back[i], 1e-3f)
        }
        assertBox("k=0: the source moved by M", VectorOps.bounds(VectorOps.transformed(ObjectCopies.source(old), m)), VectorOps.bounds(ObjectCopies.source(moved)))
    }
}
