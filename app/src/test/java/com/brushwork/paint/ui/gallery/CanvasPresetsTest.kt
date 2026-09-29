package com.brushwork.paint.ui.gallery

import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasPresetsTest {
    private fun paper(name: String) = CanvasPresets.paper.first { it.name == name }

    @Test
    fun paperSizesAtPrintResolutions() {
        assertEquals(2480 to 3508, paper("A4").pixels(300.0))
        assertEquals(3508 to 2480, paper("A4").pixels(300.0, landscape = true))
        assertEquals(3300 to 5100, paper("Tabloid").pixels(300.0))
        assertEquals(2550 to 3300, paper("US Letter").pixels(300.0))
        assertEquals(2550 to 4200, paper("US Legal").pixels(300.0))
        assertEquals(3508 to 4961, paper("A3").pixels(300.0))
        assertEquals(1748 to 2480, paper("A5").pixels(300.0))
        assertEquals(2894 to 4093, paper("A4").pixels(350.0))
        assertEquals(1181 to 1748, paper("Postcard").pixels(300.0))
        assertEquals(paper("B4 (JIS)").pixels(600.0), paper("Manga manuscript (B4)").pixels(600.0))
        assertEquals(595 to 842, paper("A4").pixels(72.0))
    }

    @Test
    fun unitConversions() {
        assertEquals(300, CanvasPresets.toPixels(1.0, LengthUnit.IN, 300.0))
        assertEquals(300, CanvasPresets.toPixels(2.54, LengthUnit.CM, 300.0))
        assertEquals(300, CanvasPresets.toPixels(25.4, LengthUnit.MM, 300.0))
        assertEquals(300, CanvasPresets.toPixels(72.0, LengthUnit.PT, 300.0))
        assertEquals(1234, CanvasPresets.toPixels(1234.0, LengthUnit.PX, 72.0))
        assertEquals(1, CanvasPresets.toPixels(0.0001, LengthUnit.MM, 72.0))
        // cm <-> in <-> mm through the shared converter
        assertEquals(1.0, Units.convert(2.54, LengthUnit.CM, LengthUnit.IN, 350.0), 1e-9)
        assertEquals(25.4, Units.convert(1.0, LengthUnit.IN, LengthUnit.MM, 350.0), 1e-9)
        assertEquals(21.0, Units.convert(210.0, LengthUnit.MM, LengthUnit.CM, 300.0), 1e-9)
        assertEquals(72.0, Units.convert(1.0, LengthUnit.IN, LengthUnit.PT, 123.0), 1e-9)
    }

    @Test
    fun screenPresetsAndAspectLabels() {
        assertEquals(9, CanvasPresets.screen.size)
        assertEquals("1:1", CanvasPresets.aspectLabel(2048, 2048))
        assertEquals("16:9", CanvasPresets.aspectLabel(1920, 1080))
        assertEquals("9:16", CanvasPresets.aspectLabel(1080, 1920))
        assertEquals("3:4", CanvasPresets.aspectLabel(1536, 2048))
        assertEquals("4:3", CanvasPresets.aspectLabel(2048, 1536))
        assertEquals("2:3", CanvasPresets.aspectLabel(1200, 1800))
        assertEquals("≈9:20", CanvasPresets.aspectLabel(1080, 2408))
        assertEquals("1:1", PixelPreset(500, 500).aspect)
        assertEquals("≈1:10", CanvasPresets.aspectLabel(1000, 9973))
        assertEquals("1:7.92", CanvasPresets.aspectLabel(1000, 7919))
        assertEquals("", CanvasPresets.aspectLabel(0, 10))
    }

    @Test
    fun memoryAndLayerLimits() {
        val heap = 512L shl 20
        assertEquals(2048L * 2048 * 4, CanvasPresets.layerBytes(2048, 2048))
        // Same formula as EditorController.maxLayers: 0.55 * heap / layerBytes - 3.
        val expected = ((heap * 0.55).toLong() / (2048L * 2048 * 4) - 3).toInt()
        assertEquals(expected, CanvasPresets.maxLayers(2048, 2048, heap))
        assertEquals(100, CanvasPresets.maxLayers(100, 100, heap))
        assertEquals(2, CanvasPresets.maxLayers(8000, 8000, heap))
        assertTrue(CanvasPresets.check(2048, 2048, heap).ok)
        assertFalse(CanvasPresets.check(10001, 100, heap).ok)
        assertFalse(CanvasPresets.check(0, 100, heap).ok)
        // 7000 x 7000 x 4 = 196 MB per layer: only ~-2 layers fit in 282 MB -> rejected.
        val big = CanvasPresets.check(7000, 7000, heap)
        assertFalse(big.ok)
        assertTrue(big.message!!.contains("memory"))
        assertTrue(CanvasPresets.check(10000, 10000, 64L shl 30).ok)
    }

    @Test
    fun formatting() {
        assertEquals("16 MB", CanvasPresets.formatBytes(2048L * 2048 * 4))
        assertEquals("33.2 MB", CanvasPresets.formatBytes(2480L * 3508 * 4))
        assertEquals("977 KB", CanvasPresets.formatBytes(500L * 500 * 4))
        assertEquals("1.5 GB", CanvasPresets.formatBytes(3L shl 29))
        assertEquals("210 × 297 mm", paper("A4").sizeLabel)
        assertEquals("8.5 × 11 in", paper("US Letter").sizeLabel)
        assertEquals("21 × 29.7 cm", CanvasPresets.physicalLabel(2480, 3508, 300.0, LengthUnit.CM))
        assertEquals("8.5 × 11 in", CanvasPresets.physicalLabel(2550, 3300, 300.0, LengthUnit.IN))
    }
}
