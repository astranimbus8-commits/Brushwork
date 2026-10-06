package com.brushwork.paint.probes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.7 F0 probe for Free deform (item 16, §3.16): `Canvas.drawBitmapMesh` draws under
 * Robolectric's NATIVE graphics, so the mesh renderer can be tested on the JVM. A 1 × 1 mesh
 * places the picture on its quad, a moved vertex warps it, and a 2 × 2 identity mesh reproduces
 * the picture.
 */
@RunWith(RobolectricTestRunner::class)
class MeshDrawProbeTest {
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    private fun source(): Bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
        // Left half red, right half blue.
        for (y in 0 until 32) for (x in 0 until 32) setPixel(x, y, if (x < 16) red else blue)
    }

    private fun target(): Bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)

    @Test
    fun aOneCellMeshPlacesThePictureOnItsQuad() {
        val out = target()
        val verts = floatArrayOf(10f, 10f, 70f, 10f, 10f, 70f, 70f, 70f)
        Canvas(out).drawBitmapMesh(source(), 1, 1, verts, 0, null, 0, Paint())
        assertEquals("left half", red, out.getPixel(20, 40))
        assertEquals("right half", blue, out.getPixel(60, 40))
        assertEquals("outside the quad", 0, out.getPixel(5, 5))
        assertEquals("outside the quad", 0, out.getPixel(75, 75))
    }

    @Test
    fun aMovedVertexWarpsThePicture() {
        val out = target()
        // The bottom-right vertex pulled in: the lower right corner of the target stays empty.
        val verts = floatArrayOf(10f, 10f, 70f, 10f, 10f, 70f, 40f, 40f)
        Canvas(out).drawBitmapMesh(source(), 1, 1, verts, 0, null, 0, Paint())
        assertEquals("near the top-left vertex", red, out.getPixel(14, 14))
        assertEquals("beyond the pulled vertex", 0, out.getPixel(65, 65))
        var painted = 0
        for (y in 0 until 80) for (x in 0 until 80) if (out.getPixel(x, y) != 0) painted++
        assertTrue("the warped picture covers less than the square ($painted)", painted in 900 until 3600)
    }

    @Test
    fun anIdentityMeshReproducesThePicture() {
        val src = source()
        val out = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val verts = FloatArray(18)
        for (j in 0..2) for (i in 0..2) { verts[(j * 3 + i) * 2] = i * 16f; verts[(j * 3 + i) * 2 + 1] = j * 16f }
        Canvas(out).drawBitmapMesh(src, 2, 2, verts, 0, null, 0, Paint())
        var same = 0
        for (y in 0 until 32) for (x in 0 until 32) if (out.getPixel(x, y) == src.getPixel(x, y)) same++
        assertTrue("identity mesh ($same of 1024 pixels equal)", same >= 1024 - 64)
    }
}
