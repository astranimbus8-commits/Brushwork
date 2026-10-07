package com.brushwork.paint.brush

import android.graphics.Bitmap
import android.graphics.Canvas
import com.brushwork.paint.assist.SymmetryMaps
import com.brushwork.paint.vector.StrokeCopies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs

/**
 * v1.7 (item 18, §3.18): [DabMapping] places a dab's copies — centre mapped, size × √|det J|
 * (as `StrokeCopies.scaleAt`), tip turned, textured tips mirrored when det J < 0 — so a mirrored
 * dab is the mirror image of the dab.
 */
@RunWith(RobolectricTestRunner::class)
class DabMappingRobolectricTest {
    private val size = 64

    private fun dab(x: Float, y: Float, d: Float, rotation: Float, variant: Int = 0) =
        Dab(x, y, 1f, 0f, 0f, 0f, rotation, variant, 0.5f).also { it.diameter = d; it.alpha = 1f }

    private fun alpha(b: Bitmap): IntArray = IntArray(size * size) { (b.getPixel(it % size, it / size) ushr 24) and 0xFF }

    /** The mirror about the vertical line x = 32 (the middle of the 64 px canvas). */
    private val mirror = SymmetryMaps.reflection(32f, 32f, 90.0)

    @Test
    fun aCopyIsPlacedTurnedAndScaled() {
        val stamper = DabStamper(TipCache())
        val m = DabMapping(listOf(SymmetryMaps.identity(), mirror, floatArrayOf(2f, 0f, 5f, 0f, 2f, 7f, 0f, 0f, 1f)), stamper)
        assertEquals(2, m.copies)
        val pen = BrushLibrary.defaultBrush
        val c = m.place(0, pen, dab(10f, 20f, 8f, 30f))!!
        assertEquals(54f, c.cx, 1e-4f)
        assertEquals(20f, c.cy, 1e-4f)
        assertEquals(8f, c.diameter, 1e-6f)
        assertEquals("the mirror turns 30° to 150°", 150f, c.rotation, 1e-3f)
        assertTrue("measured", c.hasBounds)
        val s = m.place(1, pen, dab(10f, 20f, 8f, 0f))!!
        assertEquals(25f, s.cx, 1e-4f)
        assertEquals(47f, s.cy, 1e-4f)
        assertEquals("√|det J| = 2", 16f, s.diameter, 1e-5f)
        assertEquals(0f, s.rotation, 0f)
        // Below 1 px a copy is drawn at 1 px with less alpha (as StrokeDynamics.resolve does).
        val tiny = DabMapping(listOf(SymmetryMaps.identity(), floatArrayOf(0.25f, 0f, 0f, 0f, 0.25f, 0f, 0f, 0f, 1f)), stamper)
        val t = tiny.place(0, pen, dab(10f, 20f, 2f, 0f))!!
        assertEquals(1f, t.diameter, 0f)
        assertEquals(0.5f, t.alpha, 1e-6f)
        // The scale is StrokeCopies' at the dab, also for a homography.
        val h = floatArrayOf(1.2f, 0.1f, 3f, -0.05f, 0.9f, 4f, 0.0008f, 0.0004f, 1f)
        val ph = DabMapping(listOf(SymmetryMaps.identity(), h), stamper).place(0, pen, dab(30f, 40f, 10f, 0f))!!
        assertEquals(10f * StrokeCopies.scaleAt(h, 30f, 40f), ph.diameter, 1e-5f)
        // Behind the horizon nothing is placed.
        val behind = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, -0.1f, 1f)
        assertNull(DabMapping(listOf(SymmetryMaps.identity(), behind), stamper).place(0, pen, dab(5f, 20f, 4f, 0f)))
        assertNull(DabMapping.of(listOf(SymmetryMaps.identity()), stamper))
    }

    @Test
    fun aMirroredTexturedDabIsTheMirrorImage() {
        for (id in listOf("chalk", "pencil", "spray")) {
            val preset = BrushLibrary.byId(id)!!
            assertTrue(TipShapes.isTextured(preset.tip) && preset.antiAlias)
            val stamper = DabStamper(TipCache())
            val base = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
            val copy = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
            val d = dab(20.3f, 30.7f, 26f, 37f, variant = 1)
            stamper.stamp(Canvas(base), preset, d)
            val m = DabMapping(listOf(SymmetryMaps.identity(), mirror), stamper)
            m.stamp(Canvas(copy), 0, m.place(0, preset, d)!!)
            val a = alpha(base)
            val b = alpha(copy)
            var painted = 0
            var off = 0
            var turnedOnly = 0
            for (y in 0 until size) for (x in 0 until size) {
                val pa = a[y * size + x]
                val pb = b[y * size + (size - 1 - x)]
                if (pa == 0 && pb == 0) continue
                painted++
                if (abs(pa - pb) > 2) off++
            }
            // Turning the tip without flipping it would not be the mirror image of a texture.
            val turned = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
            stamper.stamp(Canvas(turned), preset, dab(43.7f, 30.7f, 26f, 143f, variant = 1))
            val c = alpha(turned)
            for (y in 0 until size) for (x in 0 until size) if (abs(c[y * size + x] - b[y * size + x]) > 2) turnedOnly++
            assertTrue("$id: $painted pixels painted", painted > 100)
            assertTrue("$id: $off of $painted pixels differ from the mirror image", off <= painted / 100)
            assertTrue("$id: the texture is flipped, not just turned ($turnedOnly)", turnedOnly > painted / 20)
        }
    }

    @Test
    fun aMirroredPixelTipIsTheBrushTurned() {
        // Pixel tips bake the brush angle in: the copy is drawn with the brush turned (30° to 150°).
        val preset = BrushLibrary.byId("calligraphy")!!.copy(antiAlias = false, angle = 30f, size = 20f)
        val stamper = DabStamper(TipCache())
        val copy = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val m = DabMapping(listOf(SymmetryMaps.identity(), mirror), stamper)
        m.stamp(Canvas(copy), 0, m.place(0, preset, dab(20f, 30f, 20f, 30f))!!)
        val expected = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        stamper.stamp(Canvas(expected), preset.copy(angle = 150f), dab(44f, 30f, 20f, 150f))
        assertTrue(alpha(expected).any { it > 0 })
        assertTrue("the turned pixel tip", alpha(expected).contentEquals(alpha(copy)))
    }
}
