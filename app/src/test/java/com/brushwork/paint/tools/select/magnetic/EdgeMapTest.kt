package com.brushwork.paint.tools.select.magnetic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.8 (C, design §3.4 c): the magnetic lasso's edge map. Rec. 709 luminance over white, the
 * proxy size, the 99th percentile normalization (and its line-art fallback), the half-resolution
 * copy, and the contrast floor the live wire applies to the map.
 */
class EdgeMapTest {

    private fun map(lum: ByteArray, w: Int, h: Int): EdgeMap = requireNotNull(EdgeMap.fromLuminance(lum, w, h, 1f))

    private fun stripes(w: Int, h: Int, period: Int): ByteArray =
        ByteArray(w * h) { i -> if ((i % w) / period % 2 == 0) 0 else 255.toByte() }

    @Test
    fun luminanceIsRec709OverWhite() {
        assertEquals(255, EdgeMap.luminance(0xFFFFFFFF.toInt()))
        assertEquals(0, EdgeMap.luminance(0xFF000000.toInt()))
        assertEquals(54, EdgeMap.luminance(0xFFFF0000.toInt()))
        assertEquals(182, EdgeMap.luminance(0xFF00FF00.toInt()))
        assertEquals(19, EdgeMap.luminance(0xFF0000FF.toInt()))
        // Transparent paint is the paper: white.
        assertEquals(255, EdgeMap.luminance(0x00000000))
        assertEquals(127, EdgeMap.luminance(0x80000000.toInt()))
    }

    @Test
    fun theProxyKeepsTheLongSideAt2048() {
        assertEquals(100 to 50, EdgeMap.proxySize(100, 50))
        assertEquals(2048 to 2048, EdgeMap.proxySize(2048, 2048))
        assertEquals(1638 to 2048, EdgeMap.proxySize(4000, 5000))
        assertEquals(2048 to 1, EdgeMap.proxySize(10000, 3))
    }

    @Test
    fun theNinetyNinthPercentileMapsTo255() {
        // 98 % flat, 1.5 % in bin 100, 0.5 % in bin 200: the 99th percentile lies in bin 100.
        val hist = IntArray(256)
        hist[0] = 9800; hist[100] = 150; hist[200] = 50
        val r = EdgeMap.reference(hist, 10000)
        assertEquals(565, r)
        // 565 is bin 100's smallest magnitude (bin = magnitude × 256 / 1444): the whole bin maps to 255.
        assertEquals(99, 564 * 256 / (EdgeMap.MAX_MAGNITUDE + 1))
        assertEquals(100, 565 * 256 / (EdgeMap.MAX_MAGNITUDE + 1))
    }

    @Test
    fun lineArtUsesThePercentileAboveNoise() {
        // Edges cover 0.5 %: the 99th percentile is flat, so the pixels above noise decide.
        val hist = IntArray(256)
        hist[0] = 9950; hist[50] = 50
        assertEquals(283, EdgeMap.reference(hist, 10000))
        // A flat picture: nothing above noise, the floor.
        val flat = IntArray(256).also { it[0] = 10000 }
        assertEquals(EdgeMap.MIN_REFERENCE, EdgeMap.reference(flat, 10000))
    }

    @Test
    fun stripesSaturateTheirEdgesAndLeaveTheFlatsAtZero() {
        val w = 64; val h = 32
        val m = map(stripes(w, h, 8), w, h)
        var max = 0; var at255 = 0
        for (y in 0 until h) for (x in 0 until w) {
            val g = m.at(x, y)
            max = maxOf(max, g)
            if (g == 255) at255++
        }
        assertEquals(255, max)
        assertTrue("$at255 px at 255", at255 >= w * h / 100)
        // A stripe's middle (3 px from both its edges) is flat.
        for (y in 0 until h) {
            assertEquals(0, m.at(3, y)); assertEquals(0, m.at(12, y)); assertEquals(0, m.at(20, y))
        }
        // An edge (between x = 7 and 8) is strong on both sides.
        assertTrue(m.at(7, 10) >= 200 && m.at(8, 10) >= 200)
    }

    @Test
    fun aFaintLineOnWhiteStillReaches255() {
        // One grey line, 10 % darker than the paper, covering under 1 % of the picture.
        val w = 200; val h = 500
        val lum = ByteArray(w * h) { 255.toByte() }
        for (x in 0 until w) lum[250 * w + x] = 230.toByte()
        val m = map(lum, w, h)
        assertTrue("the line is the strongest edge", (0 until w).all { m.at(it, 249) >= 200 && m.at(it, 251) >= 200 })
        assertEquals(0, m.at(50, 20))
    }

    @Test
    fun aFlatPictureHasNoEdges() {
        val m = map(ByteArray(40 * 30) { 77 }, 40, 30)
        for (y in 0 until 30) for (x in 0 until 40) assertEquals(0, m.at(x, y))
    }

    @Test
    fun halfKeepsTheStrongestOfEach2x2() {
        val grad = ByteArray(5 * 3)
        grad[0 * 5 + 1] = 200.toByte()
        grad[2 * 5 + 4] = 90
        val m = EdgeMap(5, 3, 0.5f, grad)
        val half = m.half
        assertEquals(3, half.width); assertEquals(2, half.height)
        assertEquals(200, half.at(0, 0))
        assertEquals(0, half.at(1, 0))
        assertEquals(90, half.at(2, 1))
        assertEquals(0.3f, half.scale, 1e-6f)
        assertTrue("built once", m.half === half)
    }

    @Test
    fun theContrastFloorIgnoresWeakerEdges() {
        // An L-shaped edge of strength 128 from (10, 10) right to (80, 10), then down to (80, 80).
        val w = 100; val h = 100
        val grad = ByteArray(w * h)
        for (x in 10..80) grad[10 * w + x] = 128.toByte()
        for (y in 10..80) grad[y * w + 80] = 128.toByte()
        val m = EdgeMap(w, h, 1f, grad)
        val wire = LiveWire()
        val followed = requireNotNull(wire.path(m, 10, 10, 80, 80, floor = 0))
        for ((x, y) in MagneticTestPictures.pixels(followed)) assertTrue("($x, $y) off the L", y == 10 || x == 80)
        // Under a floor above 128 the L is flat paper: the wire runs straight.
        val ignored = requireNotNull(wire.path(m, 10, 10, 80, 80, floor = 129))
        assertTrue(ignored.contentToString(), ignored.contentEquals(intArrayOf(10, 10, 80, 80)))
    }
}
