package com.brushwork.paint.masks

import com.brushwork.paint.core.PackedPoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** v1.5 A5: affine maps of mask components (exact for linear and radial), pins, ids. JVM. */
class MaskGeometryTest {
    private val rnd = Random(3)

    private fun randomAffine(): MaskGeometry.Affine {
        while (true) {
            val m = MaskGeometry.Affine(
                rnd.nextFloat() * 4f - 2f, rnd.nextFloat() * 4f - 2f, rnd.nextFloat() * 200f - 100f,
                rnd.nextFloat() * 4f - 2f, rnd.nextFloat() * 4f - 2f, rnd.nextFloat() * 200f - 100f,
            )
            if (kotlin.math.abs(m.det) > 0.2f) return m
        }
    }

    @Test
    fun linearAndRadialComponentsMapExactlyUnderAnyAffineMap() {
        repeat(200) {
            val m = randomAffine()
            val lin = LinearMask(1, x0 = rnd.nextFloat() * 100f, y0 = rnd.nextFloat() * 100f, x1 = rnd.nextFloat() * 100f + 1f, y1 = rnd.nextFloat() * 100f)
            val rad = RadialMask(2, cx = rnd.nextFloat() * 100f, cy = rnd.nextFloat() * 100f, rx = 5f + rnd.nextFloat() * 50f, ry = 5f + rnd.nextFloat() * 50f,
                rotationDeg = rnd.nextFloat() * 360f - 180f, feather = rnd.nextFloat())
            val lin2 = MaskGeometry.transformed(lin, m) as LinearMask
            val rad2 = MaskGeometry.transformed(rad, m) as RadialMask
            repeat(20) {
                val x = rnd.nextFloat() * 200f - 50f; val y = rnd.nextFloat() * 200f - 50f
                val mx = m.mapX(x, y); val my = m.mapY(x, y)
                assertEquals("linear $lin by $m", MaskSpecRenderer.linearRaw(lin, x, y), MaskSpecRenderer.linearRaw(lin2, mx, my), 2e-3f)
                assertEquals("radial $rad by $m", MaskSpecRenderer.radialRaw(rad, x, y), MaskSpecRenderer.radialRaw(rad2, mx, my), 2e-3f)
            }
        }
    }

    @Test
    fun flipsAndQuarterTurnsAreExactOnPixelCentres() {
        val w = 120; val h = 80
        val spec = MaskSpec(components = listOf(
            LinearMask(1, x0 = 10f, y0 = 5f, x1 = 90f, y1 = 60f),
            RadialMask(2, mode = MaskMode.SUBTRACT, cx = 60f, cy = 40f, rx = 30f, ry = 12f, rotationDeg = 25f),
        ), nextId = 3)
        val flipH = MaskGeometry.Affine(-1f, 0f, w.toFloat(), 0f, 1f, 0f)
        val flipped = MaskGeometry.transformed(spec, flipH)!!
        val a = IntArray(w * h); val b = IntArray(w * h)
        MaskSpecRenderer.render(spec, w, h, 0, 0, w, h, a, w)
        MaskSpecRenderer.render(flipped, w, h, 0, 0, w, h, b, w)
        var off = 0
        for (y in 0 until h) for (x in 0 until w) {
            val d = kotlin.math.abs((a[y * w + x] and 0xFF) - (b[y * w + (w - 1 - x)] and 0xFF))
            assertTrue("($x, $y) differs by $d", d <= 1)
            if (d != 0) off++
        }
        assertTrue("rounding only, rarely: $off", off < w * h / 100)
    }

    @Test
    fun brushStrokesMapTheirPointsAndScaleTheirSize() {
        val s = MaskStroke(false, 10f, 0.5f, 1f, PackedPoints(floatArrayOf(1f, 2f), floatArrayOf(3f, 4f), floatArrayOf(1f, 0.5f)))
        val c = BrushMask(1, strokes = listOf(s))
        val m = MaskGeometry.Affine.similarity(0f, 0f, 2f, 90f, 10f, 0f)
        val c2 = MaskGeometry.transformed(c, m) as BrushMask
        assertEquals(20f, c2.strokes[0].size, 1e-4f)
        assertEquals(m.mapX(1f, 3f), c2.strokes[0].points.x[0], 1e-4f)
        assertEquals(m.mapY(2f, 4f), c2.strokes[0].points.y[1], 1e-4f)
        assertEquals(0.5f, c2.strokes[0].points.p[1], 0f)
        // Degenerate and non-finite maps are refused.
        assertNull(MaskGeometry.transformed(c, MaskGeometry.Affine(0f, 0f, 0f, 0f, 0f, 0f)))
        assertNull(MaskGeometry.transformed(c, MaskGeometry.Affine(Float.NaN, 0f, 0f, 0f, 1f, 0f)))
    }

    @Test
    fun pinsMoveComponentsAndNamesCountPerKind() {
        val lin = LinearMask(1, x0 = 0f, y0 = 0f, x1 = 10f, y1 = 20f)
        assertEquals(5f to 10f, MaskGeometry.pin(lin))
        val moved = MaskGeometry.movedTo(lin, 50f, 60f) as LinearMask
        assertEquals(45f, moved.x0, 1e-4f); assertEquals(50f, moved.y0, 1e-4f)
        assertEquals(55f, moved.x1, 1e-4f); assertEquals(70f, moved.y1, 1e-4f)
        val rad = RadialMask(2, cx = 3f, cy = 4f, rx = 5f, ry = 6f)
        assertEquals(3f to 4f, MaskGeometry.pin(rad))
        assertNull(MaskGeometry.pin(BrushMask(3)))
        val spec = MaskSpec(components = listOf(lin, rad, lin.copy(id = 4)), nextId = 2)
        assertEquals("Linear 2", MaskGeometry.displayName(spec, spec.components[2]))
        assertEquals("Radial 1", MaskGeometry.displayName(spec, rad))
        // Ids never repeat, even when nextId lags behind.
        assertEquals(5L, MaskGeometry.nextId(spec))
        val added = MaskGeometry.added(spec, BrushMask(0))
        assertEquals(5L, added.components.last().id)
        assertEquals(6L, added.nextId)
        assertNotNull(MaskGeometry.boxCorners(rad))
        assertEquals(4, MaskGeometry.boxCorners(lin)!!.size)
        assertNull(MaskGeometry.boxCorners(BrushMask(9)))
    }

    @Test
    fun similarityTurnsClockwiseAroundItsPivot() {
        val m = MaskGeometry.Affine.similarity(10f, 10f, 1f, 90f)
        // (20, 10) turns a quarter clockwise on screen (y down) around (10, 10): (10, 20).
        assertEquals(10f, m.mapX(20f, 10f), 1e-4f)
        assertEquals(20f, m.mapY(20f, 10f), 1e-4f)
        val inv = m.inverse()!!
        assertEquals(20f, inv.mapX(10f, 20f), 1e-4f)
        assertEquals(10f, inv.mapY(10f, 20f), 1e-4f)
        assertEquals(90f, MaskGeometry.normalizeDegrees(450f), 0f)
        assertEquals(180f, MaskGeometry.normalizeDegrees(-180f), 0f)
    }
}
