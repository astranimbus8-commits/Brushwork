package com.brushwork.paint.filters.pixelate

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterValues
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.hypot

class PixelateFiltersTest {
    private val ctx = FilterContext()

    private fun randomImage(w: Int, h: Int, seed: Long, withAlpha: Boolean = true): PixelBuffer {
        val rnd = Random(seed)
        val b = PixelBuffer(w, h)
        for (i in 0 until b.size) {
            val a = if (!withAlpha) 255 else when (rnd.nextInt(4)) { 0 -> 0; 1 -> rnd.nextInt(256); else -> 255 }
            b.pixels[i] = ColorUtils.argb(a, rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
        }
        return b
    }

    private fun lattices(w: Int, h: Int): List<Pair<String, CellLattice>> {
        val list = ArrayList<Pair<String, CellLattice>>()
        for (angle in floatArrayOf(0f, 7f, 30f, 45f, 60f, 90f, 137.5f, 222f, 359f)) {
            for (size in floatArrayOf(0.3f, 1f, 1.7f, 3f, 8f, 25f, 300f)) {
                list += "square $size@$angle" to SquareLattice(w, h, size * 2f, angle)
                list += "hex $size@$angle" to HexLattice(w, h, size, angle)
                list += "triangle $size@$angle" to TriangleLattice(w, h, size, angle)
            }
        }
        for (size in floatArrayOf(0.5f, 1f, 3f, 7.5f, 40f)) for (irr in floatArrayOf(0f, 0.5f, 1f)) {
            list += "voronoi $size/$irr" to VoronoiLattice(w, h, size, irr, 7)
        }
        return list
    }

    private fun rowOfCells(lattice: CellLattice): IntArray {
        val rows = IntArray(lattice.cellCount)
        for (r in 0 until lattice.rowCount) for (c in lattice.rowStart(r) until lattice.rowStart(r + 1)) rows[c] = r
        return rows
    }

    private fun values(filter: com.brushwork.paint.filters.Filter, vararg kv: Pair<String, Any>): FilterValues {
        val v = filter.defaultValues()
        for ((k, value) in kv) v.set(k, value)
        return v
    }

    // ------------------------------------------------------------------ lattices

    @Test
    fun everyPixelLiesInsideItsCellAndCentersAgree() {
        for ((w, h) in listOf(57 to 41, 1 to 1, 3 to 64, 64 to 2)) {
            for ((name, lat) in lattices(w, h)) {
                val rows = rowOfCells(lat)
                val c = FloatArray(2); val c2 = FloatArray(2)
                for (y in 0 until h) for (x in 0 until w) {
                    val px = x + 0.5f; val py = y + 0.5f
                    val cell = lat.cellAt(px, py, c)
                    assertTrue("$name ${w}x$h: cell $cell out of range", cell in 0 until lat.cellCount)
                    assertEquals("$name: labels must not depend on the center query", cell, lat.cellAt(px, py, null))
                    val d = hypot(c[0] - px, c[1] - py)
                    assertTrue("$name ${w}x$h: ($x,$y) is $d from its center > ${lat.boundRadius}", d <= lat.boundRadius + 1e-3f)
                    lat.centerOf(rows[cell], cell, c2)
                    assertEquals("$name center x", c[0], c2[0], 1e-3f)
                    assertEquals("$name center y", c[1], c2[1], 1e-3f)
                }
            }
        }
    }

    @Test
    fun rotatedLatticesIndexOnlyCellsNearTheImage() {
        // Compact row indexing: even rotated by 45 degrees the cell table stays close to the
        // number of cells that actually touch the image (no bounding-box blow-up).
        val w = 400; val h = 300
        val hex = HexLattice(w, h, 2f, 45f)
        val hexArea = 1.5f * SQRT3 * 4f
        assertTrue("hex cells ${hex.cellCount}", hex.cellCount < w * h / hexArea * 1.3f)
        val sq = SquareLattice(w, h, 3f, 45f)
        assertTrue("square cells ${sq.cellCount}", sq.cellCount < w * h / 9f * 1.3f)
    }

    @Test
    fun mosaicEqualsBruteForceCellAverage() {
        val w = 61; val h = 47
        val src = randomImage(w, h, 3)
        for ((name, lat) in lattices(w, h)) {
            val out = CellMosaic.pixelate(src, lat, ctx)
            val n = IntArray(lat.cellCount); val sa = LongArray(lat.cellCount)
            val sr = LongArray(lat.cellCount); val sg = LongArray(lat.cellCount); val sb = LongArray(lat.cellCount)
            for (y in 0 until h) for (x in 0 until w) {
                val cell = lat.cellAt(x + 0.5f, y + 0.5f, null)
                val p = src[x, y]; val a = p ushr 24
                n[cell]++; sa[cell] += a.toLong()
                sr[cell] += ColorUtils.red(p).toLong() * a; sg[cell] += ColorUtils.green(p).toLong() * a; sb[cell] += ColorUtils.blue(p).toLong() * a
            }
            for (y in 0 until h) for (x in 0 until w) {
                val cell = lat.cellAt(x + 0.5f, y + 0.5f, null)
                val expected = CellMosaic.averageColor(n[cell], sa[cell], sr[cell], sg[cell], sb[cell])
                assertEquals("$name at ($x,$y)", expected, out[x, y])
            }
        }
    }

    @Test
    fun averageIsAlphaWeighted() {
        // 1 opaque red + 1 transparent pixel: color stays pure red, alpha halves.
        assertEquals(ColorUtils.argb(128, 255, 0, 0), CellMosaic.averageColor(2, 255, 255L * 255, 0, 0))
        // Opaque red + opaque blue.
        assertEquals(ColorUtils.argb(255, 128, 0, 128), CellMosaic.averageColor(2, 510, 255L * 255, 0, 255L * 255))
        assertEquals(0, CellMosaic.averageColor(5, 0, 0, 0, 0))
        assertEquals(0, CellMosaic.averageColor(0, 0, 0, 0, 0))
    }

    // ------------------------------------------------------------------ mosaic filters

    @Test
    fun squarePixelateAveragesBlocksCenteredOnTheCanvas() {
        // 12x12 with side 4 (radius 2): a cell is centered at (6,6) so blocks are [0,4), [4,8), [8,12).
        val src = PixelBuffer(12, 12)
        val red = 0xFFFF0000.toInt(); val blue = 0xFF0000FF.toInt()
        for (y in 0 until 12) for (x in 0 until 12) {
            src[x, y] = when {
                y < 4 -> if (x % 4 < 2) red else blue // top blocks: half red, half blue
                y < 8 -> if (x % 4 < 2) red else 0 // middle blocks: half red, half transparent
                else -> 0xFF204060.toInt()
            }
        }
        val f = SquarePixelateFilter()
        val out = f.apply(src, values(f, "radius" to 2f, "angle" to 0f), ctx)
        for (y in 0 until 12) for (x in 0 until 12) {
            val expected = when {
                y < 4 -> ColorUtils.argb(255, 128, 0, 128)
                y < 8 -> ColorUtils.argb(128, 255, 0, 0)
                else -> 0xFF204060.toInt()
            }
            assertEquals("($x,$y)", expected, out[x, y])
        }
    }

    @Test
    fun mosaicFiltersKeepFlatColorsAndTransparency() {
        val filters = listOf(CrystallizeFilter(), SquarePixelateFilter(), HexagonalPixelateFilter(), TriangularPixelateFilter())
        for (color in intArrayOf(0xFF3366CC.toInt(), 0x80FF8000.toInt(), 0)) {
            val src = PixelBuffer.filled(45, 38, color)
            for (f in filters) for (angle in floatArrayOf(0f, 33f)) {
                val v = f.defaultValues().set("angle", angle)
                val out = f.apply(src, v, ctx)
                assertArrayEquals("${f.id} on ${ColorUtils.toHex(color)}", src.pixels, out.pixels)
            }
        }
    }

    @Test
    fun squareGridIsSymmetricUnderQuarterTurns() {
        val src = randomImage(40, 28, 11)
        val f = SquarePixelateFilter()
        val a0 = f.apply(src, values(f, "radius" to 2f, "angle" to 0f), ctx)
        val a90 = f.apply(src, values(f, "radius" to 2f, "angle" to 90f), ctx)
        assertArrayEquals(a0.pixels, a90.pixels)
    }

    @Test
    fun hexGridIsSymmetricUnderSixthTurns() {
        val src = randomImage(80, 60, 12, withAlpha = false)
        val f = HexagonalPixelateFilter()
        val a0 = f.apply(src, values(f, "radius" to 6f, "angle" to 0f), ctx)
        val a360 = f.apply(src, values(f, "radius" to 6f, "angle" to 360f), ctx)
        assertArrayEquals(a0.pixels, a360.pixels)
        val a60 = f.apply(src, values(f, "radius" to 6f, "angle" to 60f), ctx)
        val same = a0.pixels.indices.count { a0.pixels[it] == a60.pixels[it] }
        assertTrue("only $same of ${a0.size} equal", same > a0.size * 0.97)
        // A rotation that is not a symmetry changes the result.
        val a30 = f.apply(src, values(f, "radius" to 6f, "angle" to 30f), ctx)
        assertFalse(a0.pixels.contentEquals(a30.pixels))
    }

    @Test
    fun hexAndTriangleCellsHaveExpectedCounts() {
        // Distinct colors ~ number of cells: hexagon area 2.598 R^2, triangle area 1.299 R^2.
        // Red = x, green = y, so each cell's average is its centroid and differs between cells.
        val w = 240; val h = 180
        val src = PixelBuffer(w, h)
        for (y in 0 until h) for (x in 0 until w) src[x, y] = ColorUtils.argb(255, x, y, 0)
        fun distinct(p: PixelBuffer) = p.pixels.toSet().size
        val hex = HexagonalPixelateFilter()
        val nh = distinct(hex.apply(src, values(hex, "radius" to 10f), ctx))
        val expectedHex = w * h / (2.598f * 100f)
        assertTrue("hex cells $nh vs $expectedHex", nh > expectedHex * 0.9f && nh < expectedHex * 1.35f)
        val tri = TriangularPixelateFilter()
        val nt = distinct(tri.apply(src, values(tri, "radius" to 10f), ctx))
        val expectedTri = w * h / (1.299f * 100f)
        assertTrue("triangle cells $nt vs $expectedTri", nt > expectedTri * 0.9f && nt < expectedTri * 1.35f)
    }

    @Test
    fun crystallizeWithoutIrregularityIsAGridOfBlocks() {
        val w = 30; val h = 20; val s = 5
        val src = randomImage(w, h, 5)
        val f = CrystallizeFilter()
        val out = f.apply(src, values(f, "size" to s.toFloat(), "irregularity" to 0f), ctx)
        for (by in 0 until h / s) for (bx in 0 until w / s) {
            var n = 0; var sa = 0L; var sr = 0L; var sg = 0L; var sb = 0L
            for (y in by * s until by * s + s) for (x in bx * s until bx * s + s) {
                val p = src[x, y]; val a = (p ushr 24).toLong()
                n++; sa += a; sr += ColorUtils.red(p) * a; sg += ColorUtils.green(p) * a; sb += ColorUtils.blue(p) * a
            }
            val expected = CellMosaic.averageColor(n, sa, sr, sg, sb)
            for (y in by * s until by * s + s) for (x in bx * s until bx * s + s) assertEquals("($x,$y)", expected, out[x, y])
        }
    }

    @Test
    fun crystallizeIsDeterministicPerSeedAndIrregular() {
        val src = randomImage(90, 70, 6, withAlpha = false)
        val f = CrystallizeFilter()
        val a = f.apply(src, values(f, "size" to 9f, "seed" to 1), ctx)
        val b = f.apply(src, values(f, "size" to 9f, "seed" to 1), ctx)
        val c = f.apply(src, values(f, "size" to 9f, "seed" to 2), ctx)
        assertArrayEquals(a.pixels, b.pixels)
        assertFalse(a.pixels.contentEquals(c.pixels))
        // Cells are regions of constant color; with jitter they are not aligned to the 9 px grid.
        val grid = f.apply(src, values(f, "size" to 9f, "irregularity" to 0f), ctx)
        assertFalse(a.pixels.contentEquals(grid.pixels))
    }

    @Test
    fun crystalSeedsDoNotDependOnGridWidth() {
        // 100 px -> 10 grid columns, 105 px -> 11: the shared columns must keep their seeds so a
        // downscaled preview (whose grid may round differently) shows the same crystals.
        val a = VoronoiLattice(100, 60, 10f, 1f, 3)
        val b = VoronoiLattice(105, 60, 10f, 1f, 3)
        val ca = FloatArray(2); val cb = FloatArray(2)
        for (y in 0 until 60 step 3) for (x in 0 until 85 step 3) {
            a.cellAt(x + 0.5f, y + 0.5f, ca); b.cellAt(x + 0.5f, y + 0.5f, cb)
            assertEquals(ca[0], cb[0], 1e-4f); assertEquals(ca[1], cb[1], 1e-4f)
        }
    }

    @Test
    fun previewScaleShrinksCellsProportionally() {
        // The same relative layout at 1/4 scale: distinct colors (= cells) stay about the same.
        val full = randomImage(200, 160, 8, withAlpha = false)
        val small = randomImage(50, 40, 9, withAlpha = false)
        for (f in listOf(SquarePixelateFilter(), HexagonalPixelateFilter(), TriangularPixelateFilter(), CrystallizeFilter())) {
            val v = f.defaultValues()
            val nFull = f.apply(full, v, FilterContext()).pixels.toSet().size
            val nSmall = f.apply(small, v, FilterContext(scale = 0.25f)).pixels.toSet().size
            assertTrue("${f.id}: $nFull vs $nSmall", abs(nFull - nSmall) <= nFull * 0.25f + 3)
        }
    }

    // ------------------------------------------------------------------ dots

    @Test
    fun uniformDotsAtFullDensityEqualTheMosaic() {
        val src = randomImage(70, 55, 21)
        for (hex in listOf(true, false)) for (angle in floatArrayOf(0f, 33f)) {
            val f = if (hex) DotsFilter.hexagonal() else DotsFilter.square()
            val out = f.apply(src, values(f, "size" to 9f, "density" to 100f, "angle" to angle), ctx)
            val lattice = f.lattice(70, 55, 9f, angle)
            val mosaic = CellMosaic.pixelate(src, lattice, ctx)
            assertArrayEquals("${f.id} @ $angle", mosaic.pixels, out.pixels)
        }
    }

    @Test
    fun uniformDotsAreRoundWithGapsBetween() {
        val src = PixelBuffer.filled(64, 64, 0xFF2080C0.toInt())
        for (f in listOf(DotsFilter.hexagonal(), DotsFilter.square())) {
            val out = f.apply(src, values(f, "size" to 16f, "density" to 50f, "angle" to 0f), ctx)
            val lattice = f.lattice(64, 64, 16f, 0f)
            val radius = (lattice.boundRadius + 0.5f) * 0.5f
            val c = FloatArray(2)
            var gaps = 0
            for (y in 0 until 64) for (x in 0 until 64) {
                lattice.cellAt(x + 0.5f, y + 0.5f, c)
                val d = hypot(x + 0.5f - c[0], y + 0.5f - c[1])
                val p = out[x, y]
                if (d <= radius - 0.5f) assertEquals("${f.id} inside ($x,$y)", 0xFF2080C0.toInt(), p)
                if (d >= radius + 0.5f) { assertEquals("${f.id} gap ($x,$y)", 0, p); gaps++ }
            }
            assertTrue(gaps > 64 * 64 / 4)
        }
    }

    @Test
    fun dotsAtZeroDensityLeaveOnlyTheBackground() {
        val src = randomImage(30, 30, 4)
        for (f in listOf(DotsFilter.hexagonal(), DotsFilter.square())) {
            val empty = f.apply(src, values(f, "density" to 0f), ctx)
            assertTrue(empty.pixels.all { it == 0 })
            val bg = f.apply(src, values(f, "density" to 0f, "background" to true, "bgColor" to 0xFFFFEE00.toInt()), ctx)
            assertTrue(bg.pixels.all { it == 0xFFFFEE00.toInt() })
        }
    }

    @Test
    fun halftoneDotAreaFollowsDarkness() {
        for (f in listOf(DotsFilter.hexagonal(), DotsFilter.square())) {
            for (level in intArrayOf(0, 32, 64, 128, 191, 230, 255)) {
                val src = PixelBuffer.filled(240, 240, ColorUtils.gray(level))
                val out = f.apply(src, values(f, "size" to 20f, "density" to 100f, "sizing" to 1, "dotColor" to 1, "color" to 0xFF000000.toInt()), ctx)
                var sum = 0.0; var n = 0
                for (y in 40 until 200) for (x in 40 until 200) { sum += (out[x, y] ushr 24) / 255.0; n++ }
                val coverage = sum / n
                val ink = 1.0 - level / 255.0
                assertEquals("${f.id} gray $level", ink, coverage, 0.04)
                // The ink is the custom color.
                assertTrue(out.pixels.all { it == 0 || (it and 0xFFFFFF) == 0 })
            }
        }
    }

    @Test
    fun halftoneCountsTransparencyAsNoInkAndSolidInkFillsCells() {
        for (f in listOf(DotsFilter.hexagonal(), DotsFilter.square())) {
            val clear = f.apply(PixelBuffer(50, 50), values(f, "sizing" to 1, "density" to 100f), ctx)
            assertTrue(clear.pixels.all { it == 0 })
            val black = f.apply(PixelBuffer.filled(50, 50, 0xFF000000.toInt()), values(f, "sizing" to 1, "density" to 100f, "angle" to 17f), ctx)
            assertTrue("${f.id}: solid black must stay solid", black.pixels.all { it == 0xFF000000.toInt() })
            // Cell color in halftone mode is opaque; custom color with a background gives an opaque layer.
            val red = f.apply(PixelBuffer.filled(50, 50, 0xFF800000.toInt()), values(f, "sizing" to 1, "background" to true, "bgColor" to -1), ctx)
            assertTrue(red.pixels.all { (it ushr 24) == 255 })
            assertTrue(red.pixels.any { it == -1 })
            assertTrue(red.pixels.any { it == 0xFF800000.toInt() })
        }
    }

    @Test
    fun inkLutInvertsCoverage() {
        val sq = DotsFilter.inkRadiusLut(4)
        assertEquals(0f, sq[0], 1e-6f)
        assertEquals(0.70710677f, sq[256], 1e-4f) // circumradius: covers the whole square
        assertEquals(0.5f, DotsFilter.halftoneRadius((Math.PI / 4).toFloat(), 1f, sq), 2e-3f) // inscribed circle
        val hex = DotsFilter.inkRadiusLut(6)
        assertEquals(0.57735f, hex[256], 1e-4f)
        for (i in 1..256) { assertTrue(sq[i] > sq[i - 1]); assertTrue(hex[i] > hex[i - 1]) }
    }

    // ------------------------------------------------------------------ pointillize

    @Test
    fun pointillizeBackgroundsAndZeroDensity() {
        val src = randomImage(40, 30, 13)
        val f = PointillizeFilter()
        val original = f.apply(src, values(f, "density" to 0f, "background" to 2), ctx)
        assertArrayEquals(src.pixels, original.pixels)
        val clear = f.apply(src, values(f, "density" to 0f, "background" to 1), ctx)
        assertTrue(clear.pixels.all { it == 0 })
        val colored = f.apply(src, values(f, "density" to 0f, "background" to 0, "bgColor" to 0xFF102030.toInt()), ctx)
        assertTrue(colored.pixels.all { it == 0xFF102030.toInt() })
        // Nothing to sample on a transparent layer: only the background remains.
        val empty = f.apply(PixelBuffer(40, 30), values(f, "density" to 100f, "background" to 0), ctx)
        assertTrue(empty.pixels.all { it == -1 })
    }

    @Test
    fun pointillizeDotsTakeTheImageColor() {
        val color = 0xFF3A7BD5.toInt()
        val src = PixelBuffer.filled(80, 60, color)
        val f = PointillizeFilter()
        val out = f.apply(src, values(f, "variation" to 0f, "background" to 2, "density" to 100f), ctx)
        assertArrayEquals(src.pixels, out.pixels)
        val onWhite = f.apply(src, values(f, "variation" to 0f, "background" to 0, "density" to 100f, "size" to 10f), ctx)
        val dotted = onWhite.pixels.count { it == color }
        assertTrue("dots should cover most pixels: $dotted", dotted > onWhite.size * 0.6)
        val varied = f.apply(src, values(f, "variation" to 100f, "background" to 1, "density" to 100f, "size" to 6f), ctx)
        assertTrue("variation should produce many colors", varied.pixels.toSet().size > 20)
    }

    @Test
    fun pointillizeCoverageFollowsDensity() {
        val src = PixelBuffer.filled(240, 180, 0xFF000000.toInt())
        val f = PointillizeFilter()
        fun coverage(density: Float): Double {
            val out = f.apply(src, values(f, "density" to density, "background" to 1, "size" to 8f), ctx)
            return out.pixels.sumOf { (it ushr 24).toDouble() } / (255.0 * out.size)
        }
        val low = coverage(25f); val mid = coverage(50f); val high = coverage(100f)
        // Poisson coverage 1 - e^(-2 * density): ~0.39, ~0.63, ~0.86.
        assertEquals(0.39, low, 0.08)
        assertEquals(0.63, mid, 0.08)
        assertEquals(0.86, high, 0.08)
    }

    @Test
    fun pointillizeIsDeterministicAndMatchesAtPreviewScale() {
        val src = randomImage(120, 90, 17, withAlpha = false)
        val f = PointillizeFilter()
        val a = f.apply(src, values(f, "seed" to 5), ctx)
        val b = f.apply(src, values(f, "seed" to 5), ctx)
        val c = f.apply(src, values(f, "seed" to 6), ctx)
        assertArrayEquals(a.pixels, b.pixels)
        assertFalse(a.pixels.contentEquals(c.pixels))
        // Same dot layout at preview scale: the covered share is similar.
        fun coverage(p: PixelBuffer) = p.pixels.sumOf { (it ushr 24).toDouble() } / (255.0 * p.size)
        val full = f.apply(PixelBuffer.filled(200, 160, 0xFF000000.toInt()), values(f, "seed" to 3, "background" to 1), ctx)
        val small = f.apply(PixelBuffer.filled(50, 40, 0xFF000000.toInt()), values(f, "seed" to 3, "background" to 1), FilterContext(scale = 0.25f))
        assertEquals(coverage(full), coverage(small), 0.06)
    }

    // ------------------------------------------------------------------ registry

    @Test
    fun registryListsAllSevenPixelateFilters() {
        val ids = pixelateFilters.map { it.id }
        assertEquals(
            listOf(
                "pixelate.crystallize", "pixelate.hexagonal_pixelate", "pixelate.square_pixelate",
                "pixelate.triangular_pixelate", "pixelate.pointillize", "pixelate.dots_hexagonal", "pixelate.dots_square",
            ),
            ids,
        )
        assertTrue(pixelateFilters.all { it.category == FilterCategory.PIXELATE })
    }
}
