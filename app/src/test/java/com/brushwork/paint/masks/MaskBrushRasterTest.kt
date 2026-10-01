package com.brushwork.paint.masks

import com.brushwork.paint.core.PackedPoints
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** v1.5 A5: brush components of editable masks (accumulation, erase, determinism over grids, the cache). JVM. */
class MaskBrushRasterTest {
    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, n: Int = 12): PackedPoints {
        val xs = FloatArray(n) { x0 + (x1 - x0) * it / (n - 1) }
        val ys = FloatArray(n) { y0 + (y1 - y0) * it / (n - 1) }
        return PackedPoints(xs, ys, FloatArray(n) { 1f })
    }

    private fun stroke(erase: Boolean = false, size: Float = 20f, hardness: Float = 0.5f, flow: Float = 1f, pts: PackedPoints) =
        MaskStroke(erase, size, hardness, flow, pts)

    private fun at(cov: ByteArray, grid: SampleGrid, x: Int, y: Int): Int = cov[y * grid.cols + x].toInt() and 0xFF

    @Test
    fun aFullFlowStrokeCoversItsCentreLineAndFallsOffToTheRadius() {
        val grid = SampleGrid.pixels(0, 0, 100, 40)
        val cov = MaskBrushRaster.rasterize(listOf(stroke(pts = line(10f, 20.5f, 90f, 20.5f))), grid)
        assertEquals("centre line", 255, at(cov, grid, 50, 20))
        assertEquals("beyond the radius", 0, at(cov, grid, 50, 5))
        // Hardness 0.5: full inside half the radius, then a smooth fall.
        assertEquals(255, at(cov, grid, 50, 25))
        val edge = at(cov, grid, 50, 28)
        assertTrue("soft edge $edge", edge in 1..254)
        // The ends are round (dab discs): 9.5 px from the first dab is the very edge.
        assertTrue(at(cov, grid, 0, 20) < 16)
        assertEquals(0, at(cov, grid, 0, 12))
    }

    @Test
    fun flowIsAboutWhatOnePassReachesAndStrokesBuildUp() {
        val grid = SampleGrid.pixels(0, 0, 120, 40)
        val pts = line(10f, 20.5f, 110f, 20.5f)
        for (flow in listOf(0.2f, 0.5f, 0.8f)) {
            val once = MaskBrushRaster.rasterize(listOf(stroke(flow = flow, hardness = 0.8f, size = 24f, pts = pts)), grid)
            val v = at(once, grid, 60, 20) / 255f
            assertEquals("one pass at flow $flow", flow, v, 0.12f)
            val twice = MaskBrushRaster.rasterize(listOf(stroke(flow = flow, hardness = 0.8f, size = 24f, pts = pts), stroke(flow = flow, hardness = 0.8f, size = 24f, pts = pts)), grid)
            assertTrue("a second pass adds more", at(twice, grid, 60, 20) > at(once, grid, 60, 20))
        }
    }

    @Test
    fun eraseTakesCoverageAwayAndDoesNothingOnEmptyCoverage() {
        val grid = SampleGrid.pixels(0, 0, 100, 40)
        val paint = stroke(pts = line(10f, 20.5f, 90f, 20.5f))
        val erase = stroke(erase = true, size = 10f, hardness = 1f, pts = line(50.5f, 0f, 50.5f, 40f, 6))
        val cov = MaskBrushRaster.rasterize(listOf(paint, erase), grid)
        assertEquals("erased", 0, at(cov, grid, 50, 20))
        assertEquals("kept", 255, at(cov, grid, 20, 20))
        val only = MaskBrushRaster.rasterize(listOf(erase), grid)
        assertTrue(only.all { it.toInt() == 0 })
        // A soft erase at partial flow only lowers.
        val soft = MaskBrushRaster.rasterize(listOf(paint, erase.copy(flow = 0.5f, hardness = 0.2f, size = 30f)), grid)
        val v = at(soft, grid, 50, 20)
        assertTrue("partly erased $v", v in 1..254)
    }

    @Test
    fun anyRegionOfAnyGridGivesTheFullRenderBytes() {
        val rnd = Random(7)
        val w = 300; val h = 260
        repeat(6) {
            val strokes = List(rnd.nextInt(1, 6)) {
                val n = rnd.nextInt(1, 30)
                val xs = FloatArray(n) { rnd.nextFloat() * w }
                val ys = FloatArray(n) { rnd.nextFloat() * h }
                stroke(erase = rnd.nextInt(4) == 0, size = 2f + rnd.nextFloat() * 80f, hardness = rnd.nextFloat(), flow = 0.1f + rnd.nextFloat() * 0.9f,
                    pts = PackedPoints(xs, ys, FloatArray(n) { 1f }))
            }
            val full = MaskBrushRaster.rasterize(strokes, SampleGrid.pixels(0, 0, w, h))
            val serial = MaskBrushRaster.rasterize(strokes, SampleGrid.pixels(0, 0, w, h), parallel = false)
            assertArrayEquals("parallel = serial", serial, full)
            repeat(5) {
                val l = rnd.nextInt(w - 10); val t = rnd.nextInt(h - 10)
                val rw = rnd.nextInt(1, w - l); val rh = rnd.nextInt(1, h - t)
                val part = MaskBrushRaster.rasterize(strokes, SampleGrid.pixels(l, t, rw, rh))
                for (y in 0 until rh) for (x in 0 until rw) {
                    assertEquals("($l+$x, $t+$y)", full[(t + y) * w + l + x], part[y * rw + x])
                }
            }
        }
    }

    @Test
    fun theCacheAddsStrokesIncrementallyLikeAFullRender() {
        val w = 160; val h = 120
        val s1 = stroke(pts = line(10f, 10f, 150f, 100f))
        val s2 = stroke(flow = 0.4f, size = 40f, pts = line(20f, 100f, 140f, 20f))
        val s3 = stroke(erase = true, flow = 0.7f, pts = line(80f, 0f, 80f, 120f))
        val cache = MaskBrushCache(w, h)
        val one = BrushMask(3, strokes = listOf(s1))
        cache.full(one)
        assertTrue(cache.holds(one))
        val three = one.copy(strokes = one.strokes + s2 + s3)
        assertTrue("an extension is served", cache.serves(three))
        val inc = cache.full(three)!!
        assertArrayEquals(MaskBrushRaster.rasterize(three.strokes, SampleGrid.pixels(0, 0, w, h)), inc)
        // A region of the cache equals the region raster.
        val region = cache.coverage(three, SampleGrid.pixels(30, 40, 50, 20))!!
        assertArrayEquals(MaskBrushRaster.rasterize(three.strokes, SampleGrid.pixels(30, 40, 50, 20)), region)
        // A different component (or fewer strokes) is not served: it isn't rebuilt implicitly.
        assertNull(cache.coverage(BrushMask(4, strokes = listOf(s1)), SampleGrid.pixels(0, 0, 4, 4)))
        assertNull(cache.coverage(one, SampleGrid.pixels(0, 0, 4, 4)))
        // Preview grids are never served from the cache.
        assertNull(cache.coverage(three, SampleGrid(0f, 0f, 2f, 10, 10)))
    }

    @Test
    fun specsWithBrushComponentsRenderTheSameInPiecesAndThroughACache() {
        val w = 200; val h = 150
        val brush = BrushMask(2, mode = MaskMode.ADD, strokes = listOf(stroke(size = 30f, pts = line(20f, 20f, 180f, 130f))))
        val spec = MaskSpec(
            components = listOf(
                RadialMask(1, cx = 100f, cy = 75f, rx = 60f, ry = 40f, rotationDeg = 20f),
                brush,
                LinearMask(3, mode = MaskMode.INTERSECT, amount = 0.6f, x0 = 0f, y0 = 0f, x1 = 200f, y1 = 0f),
                BrushMask(4, mode = MaskMode.SUBTRACT, strokes = listOf(stroke(size = 16f, flow = 0.5f, pts = line(100f, 0f, 100f, 150f)))),
            ),
            nextId = 5,
        )
        val full = IntArray(w * h)
        MaskSpecRenderer.render(spec, w, h, 0, 0, w, h, full, w)
        val part = IntArray(40 * 30)
        MaskSpecRenderer.render(spec, w, h, 80, 60, 40, 30, part, 40)
        for (y in 0 until 30) for (x in 0 until 40) assertEquals(full[(60 + y) * w + 80 + x], part[y * 40 + x])
        val cache = MaskBrushCache(w, h).also { it.full(brush) }
        val cached = IntArray(w * h)
        MaskSpecRenderer.render(spec, w, h, 0, 0, w, h, cached, w, cache)
        assertArrayEquals(full, cached)
        // valueAt agrees with the render.
        for ((x, y) in listOf(100 to 75, 30 to 30, 150 to 110, 100 to 10)) {
            assertEquals(full[y * w + x], MaskSpecRenderer.gray(MaskSpecRenderer.valueAt(spec, x + 0.5f, y + 0.5f)))
        }
    }

    @Test
    fun dabsFollowThePolylineAtTheirSpacing() {
        val d = MaskBrushRaster.dabsOf(stroke(size = 20f, pts = line(0f, 0f, 100f, 0f, 3)))
        // Spacing 2 px over 100 px: 51 dabs (0, 2, ... 100).
        assertEquals(51, d.count)
        for (k in 0 until d.count) assertEquals(k * 2f, d.x[k], 1e-3f)
        val single = MaskBrushRaster.dabsOf(stroke(pts = PackedPoints(floatArrayOf(5f), floatArrayOf(6f), floatArrayOf(1f))))
        assertEquals(1, single.count)
        val none = MaskBrushRaster.dabsOf(stroke(pts = PackedPoints(floatArrayOf(Float.NaN), floatArrayOf(1f), floatArrayOf(1f))))
        assertTrue(none.isEmpty)
        assertNull(MaskBrushRaster.bounds(stroke(pts = PackedPoints(FloatArray(0), FloatArray(0), FloatArray(0)))))
        assertNotNull(MaskBrushRaster.bounds(stroke(pts = line(0f, 0f, 1f, 1f))))
    }

    @Test
    fun coverageBoundsAndChangedRegionsHoldEveryDifference() {
        val w = 160; val h = 120
        val rnd = Random(11)
        fun randomComp(id: Long): MaskComponent {
            val mode = MaskMode.entries[rnd.nextInt(3)]
            val inv = rnd.nextInt(5) == 0
            return when (rnd.nextInt(3)) {
                0 -> LinearMask(id, mode = mode, invert = inv, amount = rnd.nextFloat(), x0 = rnd.nextFloat() * w, y0 = rnd.nextFloat() * h, x1 = rnd.nextFloat() * w, y1 = rnd.nextFloat() * h)
                1 -> RadialMask(id, mode = mode, invert = inv, amount = rnd.nextFloat(), cx = rnd.nextFloat() * w, cy = rnd.nextFloat() * h,
                    rx = 2f + rnd.nextFloat() * 50f, ry = 2f + rnd.nextFloat() * 50f, rotationDeg = rnd.nextFloat() * 360f, feather = rnd.nextFloat())
                else -> BrushMask(id, mode = mode, invert = inv, amount = rnd.nextFloat(), strokes = listOf(stroke(size = 4f + rnd.nextFloat() * 30f, flow = rnd.nextFloat(),
                    pts = line(rnd.nextFloat() * w, rnd.nextFloat() * h, rnd.nextFloat() * w, rnd.nextFloat() * h, 5))))
            }
        }
        repeat(30) {
            val comps = List(rnd.nextInt(1, 4)) { randomComp(it.toLong() + 1) }
            val before = MaskSpec(startFull = rnd.nextInt(4) == 0, components = comps, nextId = 10)
            // One component edited, added or removed.
            val after = when (rnd.nextInt(3)) {
                0 -> before.copy(components = before.components.mapIndexed { i, c -> if (i == 0) MaskGeometry.withId(randomComp(c.id), c.id) else c })
                1 -> before.copy(components = before.components + randomComp(10))
                else -> before.copy(components = before.components.drop(1))
            }
            val a = IntArray(w * h); val b = IntArray(w * h)
            MaskSpecRenderer.render(before, w, h, 0, 0, w, h, a, w)
            MaskSpecRenderer.render(after, w, h, 0, 0, w, h, b, w)
            val r = MaskSpecRenderer.changedRegion(before, after, w, h)
            for (y in 0 until h) for (x in 0 until w) {
                val inside = r != null && x >= r[0] && x < r[2] && y >= r[1] && y < r[3]
                if (!inside) assertEquals("$before -> $after at ($x, $y)", a[y * w + x], b[y * w + x])
            }
            val cb = MaskSpecRenderer.coverageBounds(after, w, h)
            for (y in 0 until h) for (x in 0 until w) {
                val inside = cb != null && x >= cb[0] && x < cb[2] && y >= cb[1] && y < cb[3]
                if (!inside) assertEquals(0, b[y * w + x] and 0xFF)
            }
        }
        assertNull(MaskSpecRenderer.changedRegion(MaskSpec(), MaskSpec(), w, h))
    }

    @Test
    fun theEstimateGrowsWithAreaAndBrushWork() {
        val small = MaskSpecRenderer.estimateMillis(MaskSpec(components = listOf(RadialMask(1, cx = 0f, cy = 0f, rx = 5f, ry = 5f))), 0, 0, 100, 100)
        val big = MaskSpecRenderer.estimateMillis(MaskSpec(components = listOf(RadialMask(1, cx = 0f, cy = 0f, rx = 5f, ry = 5f))), 0, 0, 1000, 1000)
        assertTrue(big > small * 50)
        val heavy = MaskSpec(components = listOf(BrushMask(1, strokes = List(60) { stroke(size = 200f, pts = line(0f, it * 10f, 2000f, it * 10f, 40)) })))
        assertTrue("a brush-heavy spec is over the tile threshold", MaskSpecRenderer.estimateMillis(heavy, 0, 0, 2000, 600) > MaskEdits.TILE_THRESHOLD_MS)
        assertEquals(0.0, MaskSpecRenderer.estimateMillis(heavy, 0, 0, 0, 0), 0.0)
    }
}
