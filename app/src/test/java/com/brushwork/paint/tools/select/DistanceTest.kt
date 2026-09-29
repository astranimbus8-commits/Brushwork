package com.brushwork.paint.tools.select

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class DistanceTest {

    private fun bruteForce(src: BooleanArray, w: Int, h: Int, x: Int, y: Int, r: Int): Int {
        var best = Long.MAX_VALUE
        for (yy in 0 until h) for (xx in 0 until w) {
            if (!src[yy * w + xx]) continue
            val d = (xx - x).toLong() * (xx - x) + (yy - y).toLong() * (yy - y)
            if (d < best) best = d
        }
        return if (best > r.toLong() * r) Distance.FAR else best.toInt()
    }

    @Test
    fun matchesBruteForceOnRandomMasksAndWindows() {
        val rnd = Random(1234)
        for (trial in 0 until 40) {
            val w = 5 + rnd.nextInt(40)
            val h = 5 + rnd.nextInt(30)
            val density = listOf(0.002, 0.02, 0.1, 0.5)[trial % 4]
            val src = BooleanArray(w * h) { rnd.nextDouble() < density }
            val r = listOf(1, 2, 3, 7, 20, Distance.MAX_RADIUS)[trial % 6]
            val x0 = rnd.nextInt(w); val y0 = rnd.nextInt(h)
            val x1 = x0 + 1 + rnd.nextInt(w - x0); val y1 = y0 + 1 + rnd.nextInt(h - y0)
            val seen = BooleanArray(w * h)
            Distance.bounded(w, h, x0, y0, x1, y1, r, { src[it] }, { y, d2 ->
                for (k in 0 until x1 - x0) {
                    val x = x0 + k
                    assertEquals("trial $trial r=$r at ($x,$y) in ${w}x$h", bruteForce(src, w, h, x, y, r), d2[k])
                    synchronized(seen) { seen[y * w + x] = true }
                }
            })
            for (y in y0 until y1) for (x in x0 until x1) assertEquals(true, seen[y * w + x])
        }
    }

    @Test
    fun noSourcesMeansFar() {
        Distance.bounded(10, 10, 0, 0, 10, 10, 5, { false }, { _, d2 -> d2.forEach { assertEquals(Distance.FAR, it) } })
    }
}
