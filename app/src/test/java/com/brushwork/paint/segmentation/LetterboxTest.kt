package com.brushwork.paint.segmentation

import com.brushwork.paint.core.PixelBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LetterboxTest {

    @Test
    fun landscapeIsCenteredVertically() {
        val lb = Letterbox(1280, 960)
        assertEquals(512, lb.contentWidth)
        assertEquals(384, lb.contentHeight)
        assertEquals(0, lb.offsetX)
        assertEquals(64, lb.offsetY)
        assertTrue(lb.isContent(0, 64))
        assertFalse(lb.isContent(0, 63))
        assertFalse(lb.isContent(511, 448))
    }

    @Test
    fun portraitIsCenteredHorizontally() {
        val lb = Letterbox(300, 1000)
        assertEquals(154, lb.contentWidth) // 153.6 rounded
        assertEquals(512, lb.contentHeight)
        assertEquals(179, lb.offsetX)
        assertEquals(0, lb.offsetY)
    }

    @Test
    fun smallImagesAreEnlargedToFit() {
        val lb = Letterbox(7, 5)
        assertEquals(512, lb.contentWidth)
        assertEquals(366, lb.contentHeight)
        val strip = Letterbox(5000, 3)
        assertEquals(1, strip.contentHeight)
    }

    @Test
    fun coordinateRoundTrip() {
        for (lb in listOf(Letterbox(1280, 960), Letterbox(300, 1000), Letterbox(4000, 3000), Letterbox(7, 5))) {
            for (fx in listOf(0f, 0.5f, 0.25f, 0.999f, 1f)) for (fy in listOf(0f, 0.5f, 0.75f, 1f)) {
                val x = fx * lb.srcWidth; val y = fy * lb.srcHeight
                assertEquals("$lb x", x, lb.toSourceX(lb.toModelX(x)), 1e-2f * maxOf(1f, x))
                assertEquals("$lb y", y, lb.toSourceY(lb.toModelY(y)), 1e-2f * maxOf(1f, y))
            }
            // The source rectangle maps exactly onto the content rectangle.
            assertEquals(lb.offsetX.toFloat(), lb.toModelX(0f), 1e-4f)
            assertEquals((lb.offsetX + lb.contentWidth).toFloat(), lb.toModelX(lb.srcWidth.toFloat()), 1e-3f)
            assertEquals(lb.offsetY.toFloat(), lb.toModelY(0f), 1e-4f)
            assertEquals((lb.offsetY + lb.contentHeight).toFloat(), lb.toModelY(lb.srcHeight.toFloat()), 1e-3f)
        }
    }

    @Test
    fun autosegQuantizationIsPixelMinus128() {
        // Autoseg-EdgeTPU input: int8, scale 0.007843138 (1/127.5), zero point -1.
        val lut = Letterbox.quantLut(0.007843138f, -1, signed = true)
        for (v in 0..255) assertEquals("v=$v", (v - 128).toByte(), lut[v])
        val unquantized = Letterbox.quantLut(0f, 0, signed = true)
        assertEquals((-128).toByte(), unquantized[0])
        assertEquals(127.toByte(), unquantized[255])
    }
}
