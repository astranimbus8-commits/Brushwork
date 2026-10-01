package com.brushwork.paint.masks

import com.brushwork.paint.core.PackedPoints
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** v1.5 F2: the reference mask renderer (§4.3b raw values and combination; JVM). */
class MaskSpecRendererTest {
    private val eps = 1e-5f

    @Test
    fun smoothstepIsExactAndAStepWhenTheEdgesMeet() {
        assertEquals(0f, MaskSpecRenderer.smoothstep(0f, 1f, -1f), 0f)
        assertEquals(0.5f, MaskSpecRenderer.smoothstep(0f, 1f, 0.5f), eps)
        assertEquals(0.15625f, MaskSpecRenderer.smoothstep(0f, 1f, 0.25f), eps)
        assertEquals(1f, MaskSpecRenderer.smoothstep(0f, 1f, 3f), 0f)
        assertEquals(0f, MaskSpecRenderer.smoothstep(1f, 1f, 0.999f), 0f)
        assertEquals(1f, MaskSpecRenderer.smoothstep(1f, 1f, 1f), 0f)
    }

    @Test
    fun linearRawIsFullAtP0AndEmptyFromP1On() {
        val c = LinearMask(1, x0 = 0f, y0 = 0f, x1 = 100f, y1 = 0f)
        assertEquals(1f, MaskSpecRenderer.linearRaw(c, -10f, 33f), 0f)
        assertEquals(1f, MaskSpecRenderer.linearRaw(c, 0f, 0f), 0f)
        assertEquals(0.84375f, MaskSpecRenderer.linearRaw(c, 25f, -40f), eps)
        assertEquals(0.5f, MaskSpecRenderer.linearRaw(c, 50f, 7f), eps)
        assertEquals(0f, MaskSpecRenderer.linearRaw(c, 100f, 0f), 0f)
        assertEquals(0f, MaskSpecRenderer.linearRaw(c, 400f, 0f), 0f)
        // Perpendicular lines through p0 / p1 are iso-lines, whatever the direction.
        val diag = LinearMask(2, x0 = 10f, y0 = 10f, x1 = 20f, y1 = 20f)
        assertEquals(MaskSpecRenderer.linearRaw(diag, 15f, 15f), MaskSpecRenderer.linearRaw(diag, 10f, 20f), eps)
        assertEquals(0.5f, MaskSpecRenderer.linearRaw(diag, 10f, 20f), eps)
        // No direction: everything is on the full side.
        assertEquals(1f, MaskSpecRenderer.linearRaw(LinearMask(3, x0 = 5f, y0 = 5f, x1 = 5f, y1 = 5f), 90f, 90f), 0f)
    }

    @Test
    fun radialRawFollowsTheFeatheredRotatedEllipse() {
        val c = RadialMask(1, cx = 100f, cy = 100f, rx = 50f, ry = 25f, feather = 0.5f)
        assertEquals(1f, MaskSpecRenderer.radialRaw(c, 100f, 100f), 0f)
        assertEquals(1f, MaskSpecRenderer.radialRaw(c, 125f, 100f), 0f) // d = 0.5: inside the feather
        assertEquals(0.5f, MaskSpecRenderer.radialRaw(c, 137.5f, 100f), eps) // d = 0.75
        assertEquals(0f, MaskSpecRenderer.radialRaw(c, 150f, 100f), 0f) // d = 1: the ellipse
        assertEquals(0.5f, MaskSpecRenderer.radialRaw(c, 100f, 118.75f), eps) // ry axis, d = 0.75
        // Rotated 90° (clockwise on screen): the long axis points down.
        val r = c.copy(rotationDeg = 90f)
        assertEquals(0.5f, MaskSpecRenderer.radialRaw(r, 100f, 137.5f), 1e-4f)
        assertEquals(0f, MaskSpecRenderer.radialRaw(r, 125f, 100f), 1e-4f)
        // No feather: a hard edge; no size: nothing.
        val hard = c.copy(feather = 0f)
        assertEquals(1f, MaskSpecRenderer.radialRaw(hard, 149f, 100f), 0f)
        assertEquals(0f, MaskSpecRenderer.radialRaw(hard, 151f, 100f), 0f)
        assertEquals(0f, MaskSpecRenderer.radialRaw(c.copy(rx = 0f), 100f, 100f), 0f)
        // Full feather: a smooth fall from the centre.
        val soft = c.copy(feather = 1f)
        assertEquals(0.5f, MaskSpecRenderer.radialRaw(soft, 125f, 100f), eps)
    }

    @Test
    fun combinationTable() {
        // ADD = max, SUBTRACT multiplies by (1 - a r), INTERSECT by (1 - a + a r).
        val m = 0.5f
        assertEquals(0.5f, MaskSpecRenderer.combine(m, MaskMode.ADD, 0.5f, 0.8f), eps)
        assertEquals(0.9f, MaskSpecRenderer.combine(m, MaskMode.ADD, 1f, 0.9f), eps)
        assertEquals(0.3f, MaskSpecRenderer.combine(m, MaskMode.SUBTRACT, 0.5f, 0.8f), eps)
        assertEquals(0f, MaskSpecRenderer.combine(m, MaskMode.SUBTRACT, 1f, 1f), eps)
        assertEquals(0.45f, MaskSpecRenderer.combine(m, MaskMode.INTERSECT, 0.5f, 0.8f), eps)
        assertEquals(0f, MaskSpecRenderer.combine(m, MaskMode.INTERSECT, 1f, 0f), eps)
        assertEquals(m, MaskSpecRenderer.combine(m, MaskMode.INTERSECT, 0f, 0f), eps)
        // Amount is clamped.
        assertEquals(1f, MaskSpecRenderer.combine(0f, MaskMode.ADD, 7f, 1f), eps)
        // Final: invert, then density.
        assertEquals(0.375f, MaskSpecRenderer.finish(MaskSpec(invert = true, density = 0.5f), 0.25f), eps)
        assertEquals(0.25f, MaskSpecRenderer.finish(MaskSpec(), 0.25f), eps)
    }

    @Test
    fun specsCombineTheirComponentsInOrder() {
        val left = LinearMask(1, x0 = 0f, y0 = 0f, x1 = 100f, y1 = 0f)
        // Start full, subtract the left ramp: hidden at the left, visible at the right.
        val sub = MaskSpec(startFull = true, components = listOf(left.copy(mode = MaskMode.SUBTRACT)))
        assertEquals(0f, MaskSpecRenderer.valueAt(sub, 0f, 0f), eps)
        assertEquals(0.5f, MaskSpecRenderer.valueAt(sub, 50f, 0f), eps)
        assertEquals(1f, MaskSpecRenderer.valueAt(sub, 150f, 0f), eps)
        // An inverted component counts 1 - raw.
        val inv = MaskSpec(components = listOf(left.copy(invert = true)))
        assertEquals(1f, MaskSpecRenderer.valueAt(inv, 150f, 0f), eps)
        assertEquals(0f, MaskSpecRenderer.valueAt(inv, -5f, 0f), eps)
        // Radial intersected with the linear ramp; a hidden component is skipped.
        val radial = RadialMask(2, cx = 0f, cy = 0f, rx = 200f, ry = 200f, feather = 0f)
        val both = MaskSpec(components = listOf(radial, left.copy(id = 3, mode = MaskMode.INTERSECT), radial.copy(id = 4, visible = false, mode = MaskMode.SUBTRACT)))
        assertEquals(0.5f, MaskSpecRenderer.valueAt(both, 50f, 0f), eps)
        assertEquals(0f, MaskSpecRenderer.valueAt(both, 150f, 0f), eps)
        // Brush components are not drawn by the reference: an INTERSECT with one keeps 1 - amount.
        val brush = BrushMask(5, mode = MaskMode.INTERSECT, amount = 0.25f, strokes = listOf(MaskStroke(false, 10f, 0.5f, 1f, PackedPoints(floatArrayOf(1f), floatArrayOf(1f), floatArrayOf(1f)))))
        assertEquals(0.75f, MaskSpecRenderer.valueAt(MaskSpec(startFull = true, components = listOf(brush)), 1f, 1f), eps)
    }

    @Test
    fun renderWritesTheRegionAtItsOffsetAsOpaqueGray() {
        val spec = MaskSpec(components = listOf(
            LinearMask(1, x0 = 10f, y0 = 0f, x1 = 70f, y1 = 30f),
            RadialMask(2, mode = MaskMode.SUBTRACT, amount = 0.6f, cx = 40f, cy = 25f, rx = 18f, ry = 9f, rotationDeg = 30f),
        ), density = 0.8f)
        val left = 13; val top = 7; val width = 41; val height = 23; val stride = 50
        val out = IntArray(stride * height)
        MaskSpecRenderer.render(spec, 100, 60, left, top, width, height, out, stride)
        for (row in 0 until height) for (col in 0 until width) {
            val expect = MaskSpecRenderer.gray(MaskSpecRenderer.valueAt(spec, left + col + 0.5f, top + row + 0.5f))
            assertEquals("($col, $row)", expect, out[row * stride + col])
            val c = out[row * stride + col]
            assertEquals(0xFF, c ushr 24)
            assertTrue((c shr 16 and 0xFF) == (c and 0xFF) && (c shr 8 and 0xFF) == (c and 0xFF))
        }
        // Padding past the width is untouched.
        assertEquals(0, out[stride - 1])
        // A large region (parallel rows) equals the same pixels rendered in small pieces.
        val bw = 400; val bh = 300
        val big = IntArray(bw * bh)
        MaskSpecRenderer.render(spec, bw, bh, 0, 0, bw, bh, big, bw)
        val piece = IntArray(50 * 10)
        MaskSpecRenderer.render(spec, bw, bh, 200, 150, 50, 10, piece, 50)
        for (row in 0 until 10) assertArrayEquals(big.copyOfRange((150 + row) * bw + 200, (150 + row) * bw + 250), piece.copyOfRange(row * 50, row * 50 + 50))
        // No components: the base value everywhere.
        val flat = IntArray(4)
        MaskSpecRenderer.render(MaskSpec(startFull = true, density = 0.5f), 10, 10, 0, 0, 2, 2, flat, 2)
        assertTrue(flat.all { it == MaskSpecRenderer.gray(0.5f) })
    }

    @Test
    fun coverageBoundsHoldEveryNonZeroPixel() {
        val w = 120; val h = 80
        assertNull(MaskSpecRenderer.coverageBounds(MaskSpec(), w, h))
        assertNull(MaskSpecRenderer.coverageBounds(MaskSpec(startFull = true, density = 0f), w, h))
        assertArrayEquals(intArrayOf(0, 0, w, h), MaskSpecRenderer.coverageBounds(MaskSpec(startFull = true), w, h))
        assertArrayEquals(intArrayOf(0, 0, w, h), MaskSpecRenderer.coverageBounds(MaskSpec(invert = true), w, h))
        // Only lowering components from an empty start: nothing.
        assertNull(MaskSpecRenderer.coverageBounds(MaskSpec(components = listOf(LinearMask(1, mode = MaskMode.SUBTRACT, x0 = 0f, y0 = 0f, x1 = 10f, y1 = 0f))), w, h))
        // A ramp ending at x = 60 covers only the left part.
        val lin = MaskSpecRenderer.coverageBounds(MaskSpec(components = listOf(LinearMask(1, x0 = 50f, y0 = 0f, x1 = 60f, y1 = 0f))), w, h)
        assertNotNull(lin)
        assertTrue(lin!![2] in 60..62 && lin[0] == 0 && lin[1] == 0 && lin[3] == h)
        val rad = MaskSpecRenderer.coverageBounds(MaskSpec(components = listOf(RadialMask(1, cx = 30f, cy = 40f, rx = 10f, ry = 5f))), w, h)!!
        assertTrue(rad[0] in 18..20 && rad[2] in 40..42 && rad[1] in 33..35 && rad[3] in 45..47)
        // Random specs: every pixel outside the bounds renders black.
        val rnd = Random(4)
        repeat(40) {
            val comps = List(rnd.nextInt(1, 4)) { i ->
                val mode = MaskMode.entries[rnd.nextInt(3)]
                if (rnd.nextBoolean()) LinearMask(i.toLong(), mode = mode, invert = rnd.nextInt(5) == 0, amount = rnd.nextFloat(),
                    x0 = rnd.nextFloat() * w, y0 = rnd.nextFloat() * h, x1 = rnd.nextFloat() * w, y1 = rnd.nextFloat() * h)
                else RadialMask(i.toLong(), mode = mode, invert = rnd.nextInt(5) == 0, amount = rnd.nextFloat(),
                    cx = rnd.nextFloat() * w, cy = rnd.nextFloat() * h, rx = 1f + rnd.nextFloat() * 40f, ry = 1f + rnd.nextFloat() * 40f,
                    rotationDeg = rnd.nextFloat() * 360f, feather = rnd.nextFloat())
            }
            val spec = MaskSpec(startFull = rnd.nextInt(6) == 0, components = comps, invert = rnd.nextInt(6) == 0, density = 0.2f + rnd.nextFloat() * 0.8f)
            val out = IntArray(w * h)
            MaskSpecRenderer.render(spec, w, h, 0, 0, w, h, out, w)
            val b = MaskSpecRenderer.coverageBounds(spec, w, h)
            for (y in 0 until h) for (x in 0 until w) {
                val inside = b != null && x >= b[0] && x < b[2] && y >= b[1] && y < b[3]
                if (!inside) assertEquals("spec $spec at ($x, $y)", 0, out[y * w + x] and 0xFF)
            }
        }
    }
}
