package com.brushwork.paint.probes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.7 F0 probe for Pathfinder (item 20, §3.20, V28, R15): `Path.op` is real Skia PathOps under
 * Robolectric's NATIVE graphics, and its result reads back segment by segment and rebuilds into
 * the same path: two overlapping squares, united and intersected.
 *
 * What the probe found: androidx's `androidx.graphics.path.PathIterator` (graphics-path 1.0.1, a
 * direct dependency since v1.7) loads its JNI library `androidx.graphics.path` in a static
 * initializer, even on API 34+ where it delegates to the platform, and that library is an Android
 * `.so` that a JVM test cannot load ([theAndroidxIteratorCannotLoadOnTheJvm]). The platform
 * `android.graphics.PathIterator` (API 34, the SDK Robolectric runs) works, so a reader that takes
 * the platform iterator on API 34+ and androidx's below it is testable on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
class PathOpProbeTest {
    private fun square(l: Float, t: Float, size: Float) = Path().apply { addRect(l, t, l + size, t + size, Path.Direction.CW) }

    /** What [roundTrip] read: the segment verbs, the distinct on-curve points and the rebuilt path. */
    private class Read(val verbs: List<Int>, val corners: Set<Pair<Float, Float>>, val path: Path)

    /** The segments of [p], read with the platform `PathIterator`, and the path rebuilt from them. */
    private fun roundTrip(p: Path): Read {
        val out = Path()
        val verbs = ArrayList<Int>()
        val corners = LinkedHashSet<Pair<Float, Float>>()
        val it = p.pathIterator
        val pts = FloatArray(8)
        while (it.hasNext()) {
            val verb = it.next(pts, 0)
            verbs += verb
            when (verb) {
                android.graphics.PathIterator.VERB_MOVE -> { out.moveTo(pts[0], pts[1]); corners += pts[0] to pts[1] }
                android.graphics.PathIterator.VERB_LINE -> { out.lineTo(pts[2], pts[3]); corners += pts[2] to pts[3] }
                android.graphics.PathIterator.VERB_QUAD -> { out.quadTo(pts[2], pts[3], pts[4], pts[5]); corners += pts[4] to pts[5] }
                android.graphics.PathIterator.VERB_CUBIC -> { out.cubicTo(pts[2], pts[3], pts[4], pts[5], pts[6], pts[7]); corners += pts[6] to pts[7] }
                android.graphics.PathIterator.VERB_CLOSE -> out.close()
                else -> Unit
            }
        }
        return Read(verbs, corners, out)
    }

    private fun bounds(p: Path) = RectF().also { @Suppress("DEPRECATION") p.computeBounds(it, true) }

    private fun pixels(p: Path): IntArray {
        val b = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        Canvas(b).drawPath(p, Paint().apply { color = 0xFF000000.toInt() })
        return IntArray(160 * 160).also { b.getPixels(it, 0, 160, 0, 0, 160, 160) }
    }

    @Test
    fun aUnionOfTwoSquaresRoundTrips() {
        val u = Path()
        assertTrue(u.op(square(0f, 0f, 100f), square(50f, 50f, 100f), Path.Op.UNION))
        val read = roundTrip(u)
        assertEquals("one outline", 1, read.verbs.count { it == android.graphics.PathIterator.VERB_MOVE })
        assertTrue("straight edges only", read.verbs.none { it == android.graphics.PathIterator.VERB_QUAD || it == android.graphics.PathIterator.VERB_CUBIC })
        val expected = setOf(0f to 0f, 100f to 0f, 100f to 50f, 150f to 50f, 150f to 150f, 50f to 150f, 50f to 100f, 0f to 100f)
        assertEquals("the eight corners of the union", expected, read.corners)
        assertEquals(RectF(0f, 0f, 150f, 150f), bounds(read.path))
        assertArrayEquals("the rebuilt path draws the same pixels", pixels(u), pixels(read.path))
    }

    @Test
    fun anIntersectionOfTwoSquaresRoundTrips() {
        val i = Path()
        assertTrue(i.op(square(0f, 0f, 100f), square(50f, 50f, 100f), Path.Op.INTERSECT))
        val read = roundTrip(i)
        assertEquals(1, read.verbs.count { it == android.graphics.PathIterator.VERB_MOVE })
        assertEquals(setOf(50f to 50f, 100f to 50f, 100f to 100f, 50f to 100f), read.corners)
        assertEquals(RectF(50f, 50f, 100f, 100f), bounds(read.path))
        assertArrayEquals(pixels(i), pixels(read.path))
        var inside = 0
        for (v in pixels(read.path)) if (v != 0) inside++
        assertEquals("a 50 x 50 square", 2500, inside)
    }

    @Test
    fun theAndroidxIteratorCannotLoadOnTheJvm() {
        try {
            androidx.graphics.path.PathIterator(square(0f, 0f, 10f)).hasNext()
            fail("androidx's PathIterator loaded on the JVM: Pathfinder's reader may use it in tests too (update the class KDoc)")
        } catch (e: LinkageError) {
            // UnsatisfiedLinkError on first use, NoClassDefFoundError after a failed initializer.
        }
    }
}
