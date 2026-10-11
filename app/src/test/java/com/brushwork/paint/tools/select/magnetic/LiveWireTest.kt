package com.brushwork.paint.tools.select.magnetic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.8 (C, design §3.4 c): the live wire follows the strongest edge. On a dark disc on white a
 * segment between two rim points stays within 1.5 proxy px of the rim (where the straight chord
 * runs 35 px inside it); on flat paper it runs straight; its ends are exact; a cancelled search
 * gives up.
 */
class LiveWireTest {

    private val disc: EdgeMap by lazy {
        requireNotNull(EdgeMap.fromLuminance(MagneticTestPictures.disc(400, 400, 200f, 200f, 120f), 400, 400, 1f))
    }

    @Test
    fun aSegmentBetweenTwoRimPointsFollowsTheRim() {
        val wire = LiveWire()
        val path = requireNotNull(wire.path(disc, 80, 200, 200, 80, floor = 0))
        assertEquals(80, path[0]); assertEquals(200, path[1])
        assertEquals(200, path[path.size - 2]); assertEquals(80, path[path.size - 1])
        val off = MagneticTestPictures.offRim(path, 200f, 200f, 120f)
        assertTrue("the wire leaves the rim by $off px", off <= 1.5f)
        // The upper-left quarter, not the long way round.
        assertTrue(MagneticTestPictures.pixels(path).all { (x, y) -> x <= 201 && y <= 201 })
        assertFalse(wire.lastHalf)
        // Back again: the same rim.
        val back = requireNotNull(wire.path(disc, 200, 80, 80, 200, floor = 0))
        assertTrue(MagneticTestPictures.offRim(back, 200f, 200f, 120f) <= 1.5f)
    }

    @Test
    fun aSegmentAlongTheLowerRimToo() {
        // From the right to the bottom: the lower-right quarter.
        val path = requireNotNull(LiveWire().path(disc, 320, 200, 200, 320, floor = 25))
        val off = MagneticTestPictures.offRim(path, 200f, 200f, 120f)
        assertTrue("the wire leaves the rim by $off px", off <= 1.5f)
    }

    @Test
    fun flatPaperGivesAStraightLine() {
        val flat = EdgeMap(300, 200, 1f, ByteArray(300 * 200))
        val wire = LiveWire()
        assertTrue(wire.path(flat, 10, 20, 210, 20, 0).contentEquals(intArrayOf(10, 20, 210, 20)))
        assertTrue(wire.path(flat, 10, 20, 110, 120, 0).contentEquals(intArrayOf(10, 20, 110, 120)))
        // An anchor on itself.
        assertTrue(wire.path(flat, 5, 5, 5, 5, 0).contentEquals(intArrayOf(5, 5)))
    }

    @Test
    fun endsOutsideTheMapAreClamped() {
        val flat = EdgeMap(100, 100, 1f, ByteArray(100 * 100))
        val p = requireNotNull(LiveWire().path(flat, -20, 50, 150, 50, 0))
        assertTrue(p.contentToString(), p.contentEquals(intArrayOf(0, 50, 99, 50)))
    }

    @Test
    fun theWindowIsTheBoxPlus48() {
        val wire = LiveWire()
        wire.path(disc, 100, 100, 150, 130, 0)
        assertEquals((50 + 2 * LiveWire.MARGIN + 1) * (30 + 2 * LiveWire.MARGIN + 1), wire.lastNodes)
        // Clamped at the picture's edge.
        wire.path(disc, 0, 0, 10, 10, 0)
        assertEquals((10 + LiveWire.MARGIN + 1) * (10 + LiveWire.MARGIN + 1), wire.lastNodes)
    }

    @Test
    fun aCancelledSearchGivesUp() {
        val flat = EdgeMap(400, 400, 1f, ByteArray(400 * 400))
        assertNull(LiveWire().path(flat, 0, 0, 399, 399, 0) { true })
        // The same instance searches on afterwards.
        val wire = LiveWire()
        assertNull(wire.path(flat, 0, 0, 399, 399, 0) { true })
        assertTrue(wire.path(flat, 0, 0, 399, 399, 0).contentEquals(intArrayOf(0, 0, 399, 399)))
    }
}
