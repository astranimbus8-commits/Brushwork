package com.brushwork.paint.tools.select.magnetic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.8 (C, design §3.4 c): over the node cap ([LiveWire.MAX_NODES], 1 M) the live wire searches
 * the edge map's half-resolution copy and scales the path back up: on a 2048 px proxy a quarter of
 * a disc's rim (a window of about 1.1 M nodes) still stays within 3 px of the rim, its ends exact.
 */
class LiveWireCapTest {

    @Test
    fun overTheCapTheHalfResolutionPathStaysWithin3Px() {
        val n = EdgeMap.MAX_SIDE
        val map = requireNotNull(EdgeMap.fromLuminance(MagneticTestPictures.disc(n, n, 1024f, 1024f, 950f), n, n, 1f))
        val wire = LiveWire()
        val path = requireNotNull(wire.path(map, 74, 1024, 1024, 74, floor = 25))
        assertTrue("searched at half resolution (${wire.lastNodes} nodes)", wire.lastHalf)
        assertTrue(wire.lastNodes <= LiveWire.MAX_NODES)
        assertEquals(74, path[0]); assertEquals(1024, path[1])
        assertEquals(1024, path[path.size - 2]); assertEquals(74, path[path.size - 1])
        val off = MagneticTestPictures.offRim(path, 1024f, 1024f, 950f)
        assertTrue("the wire leaves the rim by $off px", off <= 3f)

        // Under the cap the same map is searched at full resolution.
        val short = requireNotNull(wire.path(map, 74, 1024, 100, 800, floor = 25))
        assertFalse(wire.lastHalf)
        assertTrue(MagneticTestPictures.offRim(short, 1024f, 1024f, 950f) <= 1.5f)
    }
}
