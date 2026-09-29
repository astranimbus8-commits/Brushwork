package com.brushwork.paint.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToLong

class CanvasLimitsTest {
    private val mb = 1L shl 20

    private fun scaled(w: Int, h: Int, side: Int): Pair<Int, Int> {
        val long = maxOf(w, h)
        val s = side.toDouble() / long
        val sw = maxOf(1L, (w * s).roundToLong()).toInt()
        val sh = maxOf(1L, (h * s).roundToLong()).toInt()
        return sw to sh
    }

    @Test
    fun rawMaxLayersMatchesTheEditorFormula() {
        val heap = 512 * mb
        assertEquals((heap * 0.55).toLong() / (2048L * 2048 * 4) - 3, CanvasLimits.rawMaxLayers(2048, 2048, heap))
        assertEquals(2048L * 2048 * 4, CanvasLimits.layerBytes(2048, 2048))
        // 10000 x 10000 x 4 doesn't overflow.
        assertEquals(400_000_000L, CanvasLimits.layerBytes(10_000, 10_000))
    }

    @Test
    fun importedPhotosAreScaledDownOnlyWhenMemoryRequires() {
        // A 12 MP phone photo on a 512 MB heap: 48.8 MB per layer leaves only 2 layers, so it
        // is scaled down to the largest size that still leaves room for 5.
        val heap = 512 * mb
        val cap = CanvasLimits.importMaxSide(4032, 3024, heap)
        assertTrue("cap $cap should be below 4032", cap < 4032)
        val (w, h) = scaled(4032, 3024, cap)
        assertTrue(CanvasLimits.rawMaxLayers(w, h, heap) >= CanvasLimits.IMPORT_MIN_LAYERS)
        val (w1, h1) = scaled(4032, 3024, cap + 1)
        assertTrue("cap must be the largest that fits", CanvasLimits.rawMaxLayers(w1, h1, heap) < CanvasLimits.IMPORT_MIN_LAYERS)
        // Portrait pictures get the same limit.
        assertEquals(cap, CanvasLimits.importMaxSide(3024, 4032, heap))

        // Plenty of memory: the picture keeps its size, but never more than 4096 px.
        assertEquals(4032, CanvasLimits.importMaxSide(4032, 3024, 4096 * mb))
        assertEquals(4096, CanvasLimits.importMaxSide(8000, 6000, 8192 * mb))
        assertEquals(640, CanvasLimits.importMaxSide(640, 480, heap))
        // Extreme shapes and tiny heaps still give a usable, positive size.
        val thin = CanvasLimits.importMaxSide(1, 50_000, 64 * mb)
        assertTrue(thin in 1..4096)
        val small = CanvasLimits.importMaxSide(6000, 6000, 32 * mb)
        assertTrue(small in 1 until 6000)
        val (sw, sh) = scaled(6000, 6000, small)
        assertTrue(CanvasLimits.rawMaxLayers(sw, sh, 32 * mb) >= CanvasLimits.IMPORT_MIN_LAYERS)
        assertEquals(4096, CanvasLimits.importMaxSide(0, 100, heap))
    }
}
