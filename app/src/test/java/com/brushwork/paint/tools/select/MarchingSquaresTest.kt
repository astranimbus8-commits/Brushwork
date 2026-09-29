package com.brushwork.paint.tools.select

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot

class MarchingSquaresTest {

    @Test
    fun squareOutlineLiesOnPixelBorders() {
        val w = 20; val h = 20
        val g = ByteArray(w * h) { i -> if (i % w in 5 until 15 && i / w in 5 until 15) -1 else 0 }
        val polys = MarchingSquares.contours(g, w, h)!!
        assertEquals(1, polys.size)
        val p = polys[0]
        val xs = p.filterIndexed { i, _ -> i % 2 == 0 }
        val ys = p.filterIndexed { i, _ -> i % 2 == 1 }
        // Pixel 5 spans [4.5, 5.5] in sample coordinates.
        assertEquals(4.5f, xs.min(), 0.01f); assertEquals(14.5f, xs.max(), 0.01f)
        assertEquals(4.5f, ys.min(), 0.01f); assertEquals(14.5f, ys.max(), 0.01f)
        assertEquals(99.5f, MarchingSquares.area(p), 0.05f) // corners are cut by 1/8 px each
        val simple = MarchingSquares.simplifyClosed(p, 0.3f)
        assertTrue("simplified to ${simple.size / 2} points", simple.size / 2 in 4..8)
        assertEquals(99.5f, MarchingSquares.area(simple), 0.6f)
    }

    @Test
    fun hardCircleOutlineFollowsPixelBoundary() {
        val w = 40; val h = 40
        val g = ByteArray(w * h) { i -> if (hypot(i % w - 20.0, i / w - 20.0) <= 12.0) -1 else 0 }
        val polys = MarchingSquares.contours(g, w, h)!!
        assertEquals(1, polys.size)
        val p = polys[0]
        for (k in 0 until p.size / 2) {
            val d = hypot(p[2 * k] - 20.0, p[2 * k + 1] - 20.0)
            assertTrue("point $k at distance $d", d in 11.4..13.0)
        }
        val inside = g.count { it.toInt() != 0 }
        assertEquals(inside.toFloat(), MarchingSquares.area(p), 0.05f * inside)
    }

    @Test
    fun antiAliasedCircleOutlineHasExactRadius() {
        val w = 40; val h = 40
        val r = 12.5
        // Coverage falls linearly from 1 to 0 across the edge, 50% exactly at radius r.
        val g = ByteArray(w * h) { i ->
            val d = hypot(i % w - 20.0, i / w - 20.0)
            ((r + 0.5 - d).coerceIn(0.0, 1.0) * 255 + 0.5).toInt().toByte()
        }
        val p = MarchingSquares.contours(g, w, h)!!.single()
        for (k in 0 until p.size / 2) {
            val d = hypot(p[2 * k] - 20.0, p[2 * k + 1] - 20.0)
            assertTrue("point $k at distance $d", abs(d - r) < 0.2)
        }
        assertEquals((PI * r * r).toFloat(), MarchingSquares.area(p), 0.02f * (PI * r * r).toFloat())
    }

    @Test
    fun ringProducesOuterAndInnerContours() {
        val w = 30; val h = 30
        val g = ByteArray(w * h) { i -> val d = hypot(i % w - 15.0, i / w - 15.0); if (d in 5.0..10.0) -1 else 0 }
        val polys = MarchingSquares.contours(g, w, h)!!
        assertEquals(2, polys.size)
    }

    @Test
    fun checkerboardSaddlesAllCloseAndVisitEveryCrossing() {
        val w = 9; val h = 7
        val g = ByteArray(w * h) { i -> if ((i % w + i / w) % 2 == 0) -1 else 0 }
        val polys = MarchingSquares.contours(g, w, h)
        assertNotNull(polys)
        // Count crossing edges of the zero-padded grid: every one must appear exactly once.
        fun inside(x: Int, y: Int) = x in 0 until w && y in 0 until h && g[y * w + x].toInt() != 0
        var crossings = 0
        for (y in -1..h) for (x in -1..w) {
            if (x < w && inside(x, y) != inside(x + 1, y)) crossings++
            if (y < h && inside(x, y) != inside(x, y + 1)) crossings++
        }
        assertEquals(crossings, MarchingSquares.pointCount(polys!!))
        assertTrue(polys.all { it.size >= 6 })
    }

    @Test
    fun softEdgesInterpolate() {
        val w = 10; val h = 1
        val g = ByteArray(w * h) { if (it < 5) -1 else 0 }
        g[5] = 64 // partial pixel moves the edge right by 1 - (127.5 - 64) / (0 - 64)... toward pixel 5
        val p = MarchingSquares.contours(g, w, h)!![0]
        val maxX = p.filterIndexed { i, _ -> i % 2 == 0 }.max()
        // Between sample 4 (255) and 5 (64): t = (127.5 - 255) / (64 - 255) = 0.667 -> x = 4.667.
        assertEquals(4.667f, maxX, 0.01f)
    }

    @Test
    fun downsampleMaxKeepsThinLines() {
        val w = 64; val h = 64
        val g = ByteArray(w * h) { i -> if (i % w == 33) -1 else 0 }
        val d = MarchingSquares.downsampleMax(g, w, h, 4)
        assertEquals(16 * 16, d.size)
        val polys = MarchingSquares.contours(d, 16, 16)!!
        assertEquals(1, polys.size)
    }
}
