package com.brushwork.paint.tools.transform

import com.brushwork.paint.core.Affine2
import com.brushwork.paint.core.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * v1.7 (item 11, design §3.11 b): Free deform's mesh math on the JVM. An untouched mesh is the
 * identity (bilinear and smooth); a mesh that is an affine map of its rectangle is exactly that
 * map everywhere, so a 1 x 1 bilinear mesh is the Free / Distort quad of an affine transform and
 * meets a homography's quad at its corners; changing the number of cells keeps the deformation
 * exactly (the image never jumps), back and forth, up to MeshDeform.MAX_DEPTH changes in a row;
 * the smooth surface passes through every vertex and is C1 across cells where bilinear cells are
 * not; vertices move one by one or as a group.
 */
class MeshDeformTest {
    private val left = 10f
    private val top = 20f
    private val w = 120f
    private val h = 90f

    private fun assertNear(msg: String, a: Vec2, b: Vec2, tol: Float) {
        assertTrue("$msg: $a vs $b", hypot(a.x - b.x, a.y - b.y) <= tol)
    }

    private fun source(s: Float, t: Float) = Vec2(left + w * s, top + h * t)

    private fun samples(): List<Pair<Float, Float>> = (0..10).flatMap { a -> (0..10).map { b -> a / 10f to b / 10f } }

    /** A 3 x 3 mesh with its two middle rows pushed about (a typical warp). */
    private fun warped(): MeshDeform {
        val m = MeshDeform.identity(left, top, w, h, 3, 3)
        return m.moved(listOf(m.index(1, 1)), 9f, -6f).moved(listOf(m.index(2, 2)), -7f, 8f).moved(listOf(m.index(3, 1)), 5f, 4f)
    }

    @Test
    fun anUntouchedMeshIsTheIdentity() {
        for (smooth in listOf(false, true)) {
            val m = MeshDeform.identity(left, top, w, h, 4, 3)
            assertTrue(m.isIdentity())
            assertEquals(20, m.vertexCount)
            for ((s, t) in samples()) assertNear("smooth=$smooth at $s,$t", source(s, t), m.at(s, t, smooth), 1e-3f)
        }
    }

    @Test
    fun anAffineMeshIsThatMapEverywhereAndA1x1BilinearMeshIsTheQuad() {
        val a = 0.4f
        val k = 1.3f
        val m = floatArrayOf(k * cos(a), -k * sin(a), 25f, k * sin(a), k * cos(a), -8f, 0f, 0f, 1f)
        fun map(p: Vec2) = Vec2(m[0] * p.x + m[1] * p.y + m[2], m[3] * p.x + m[4] * p.y + m[5])
        for (smooth in listOf(false, true)) for (cells in listOf(1, 3)) {
            val mesh = MeshDeform.fromMap(left, top, w, h, cells, cells, m)
            for ((s, t) in samples()) assertNear("smooth=$smooth $cells cells at $s,$t", map(source(s, t)), mesh.at(s, t, smooth), 2e-3f)
        }
        // A distorted quad (a homography): the 1 x 1 mesh has its four corners.
        val hm = floatArrayOf(1.1f, 0.2f, 3f, -0.1f, 0.9f, 5f, 0.001f, 0.0005f, 1f)
        fun proj(p: Vec2): Vec2 {
            val z = hm[6] * p.x + hm[7] * p.y + hm[8]
            return Vec2((hm[0] * p.x + hm[1] * p.y + hm[2]) / z, (hm[3] * p.x + hm[4] * p.y + hm[5]) / z)
        }
        val quad = MeshDeform.fromMap(left, top, w, h, 1, 1, hm)
        for ((s, t) in listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f)) assertNear("corner $s,$t", proj(source(s, t)), quad.at(s, t, false), 1e-3f)
    }

    @Test
    fun theSmoothSurfacePassesThroughEveryVertexAndIsC1() {
        val m = warped()
        for (j in 0..3) for (i in 0..3) {
            val v = m.vertex(m.index(i, j))
            assertNear("bilinear vertex $i,$j", v, m.at(i / 3f, j / 3f, false), 1e-3f)
            assertNear("smooth vertex $i,$j", v, m.at(i / 3f, j / 3f, true), 1e-3f)
        }
        // The slope across the cell border s = 1/3 (through the pushed vertex 1,1): equal on both
        // sides for the smooth surface, a kink for the bilinear cells.
        val e = 1e-3f
        val s = 1f / 3f
        val t = 1f / 3f
        fun slope(smooth: Boolean, from: Float, to: Float): Vec2 {
            val a = m.at(from, t, smooth)
            val b = m.at(to, t, smooth)
            return Vec2((b.x - a.x) / (to - from), (b.y - a.y) / (to - from))
        }
        val smoothL = slope(true, s - e, s)
        val smoothR = slope(true, s, s + e)
        assertNear("smooth: C1", smoothL, smoothR, 1.5f)
        val linL = slope(false, s - e, s)
        val linR = slope(false, s, s + e)
        assertFalse("bilinear: a kink (the test has teeth)", hypot(linL.x - linR.x, linL.y - linR.y) <= 1.5f)
    }

    @Test
    fun changingTheCellsKeepsTheDeformation() {
        val m = warped()
        // Any new number of cells, smooth or bilinear: the same surface (the image never jumps),
        // and the new vertices lie on it.
        for (smooth in listOf(false, true)) for ((nc, nr) in listOf(6 to 6, 4 to 4, 5 to 2, 2 to 7, 12 to 12)) {
            val r = m.resampled(nc, nr, smooth)
            assertEquals(nc, r.cols)
            assertEquals((nc + 1) * (nr + 1), r.vertexCount)
            for ((s, t) in samples()) assertNear("smooth=$smooth 3 -> $nc x $nr at $s,$t", m.at(s, t, smooth), r.at(s, t, smooth), 0.01f)
            for (j in 0..nr) for (i in 0..nc) {
                assertNear("vertex $i,$j on the old surface", m.at(i.toFloat() / nc, j.toFloat() / nr, smooth), r.vertex(r.index(i, j)), 0.01f)
            }
            // Moving a new vertex deforms from there: the surface goes through it.
            val k = r.index(1, 1)
            val moved = r.moved(listOf(k), 4f, 3f)
            assertNear("through the moved vertex", moved.vertex(k), moved.at(1f / nc, 1f / nr, smooth), 0.01f)
        }
        // Back and forth on a changed mesh keeps the surface too.
        val there = m.resampled(5, 5, smooth = true).moved(listOf(7), 3f, -2f)
        val back = there.resampled(3, 3, smooth = true)
        for ((s, t) in samples()) assertNear("5 -> 3 at $s,$t", there.at(s, t, true), back.at(s, t, true), 0.01f)
        // Past MAX_DEPTH changes the old surface is approximated by the new vertices (no deeper chain).
        var chain = m
        repeat(MeshDeform.MAX_DEPTH + 2) { chain = chain.resampled(if (it % 2 == 0) 4 else 3, 3, smooth = true) }
        assertTrue(chain.depth <= MeshDeform.MAX_DEPTH)
        // Back and forth keeps the untouched mesh untouched.
        assertTrue(MeshDeform.identity(left, top, w, h, 3, 3).resampled(7, 2, smooth = true).isIdentity())
        // 1..12 cells.
        val clamped = m.resampled(40, 0, smooth = false)
        assertEquals(MeshDeform.MAX_CELLS, clamped.cols)
        assertEquals(1, clamped.rows)
        assertTrue("same size: the same mesh", m.resampled(3, 3, smooth = true) === m)
    }

    @Test
    fun verticesMoveAloneOrAsAGroup() {
        val m = MeshDeform.identity(left, top, w, h, 2, 2)
        val centre = m.index(1, 1)
        val one = m.moved(listOf(centre), 5f, -3f)
        assertEquals(Vec2(left + w / 2f + 5f, top + h / 2f - 3f), one.vertex(centre))
        assertFalse(one.isIdentity())
        for (k in 0 until m.vertexCount) if (k != centre) assertEquals(m.vertex(k), one.vertex(k))
        // Three vertices turned about their middle one together.
        val group = listOf(m.index(0, 1), centre, m.index(2, 1))
        val turned = m.mapped(group, Affine2.rotateAbout(m.vertex(centre), 90f))
        assertNear("left end", Vec2(left + w / 2f, top + h / 2f - w / 2f), turned.vertex(group[0]), 1e-3f)
        assertNear("right end", Vec2(left + w / 2f, top + h / 2f + w / 2f), turned.vertex(group[2]), 1e-3f)
        assertEquals(m.vertex(centre), turned.vertex(centre))
        assertEquals(m.vertex(m.index(0, 0)), turned.vertex(m.index(0, 0)))
        // Nearest vertex within reach.
        assertEquals(centre, m.nearest(left + w / 2f + 3f, top + h / 2f - 2f, 10f))
        assertEquals(-1, m.nearest(left + w / 4f, top + h / 4f, 5f))
        // withVertices round-trips; equality is by value.
        assertEquals(one, m.withVertices(one.vertices()))
        assertEquals((2 * 8 + 1) * (2 * 8 + 1) * 2, m.dense(8, smooth = true).size)
        val b = one.bounds(8, smooth = false)
        assertEquals(left, b[0], 1e-3f)
        assertEquals(top + h, b[3], 1e-3f)
    }
}
