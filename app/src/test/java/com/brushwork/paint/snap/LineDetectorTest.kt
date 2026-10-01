package com.brushwork.paint.snap

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.distort.TableCountFilter
import com.brushwork.paint.filters.distort.TableSizeFilter
import com.brushwork.paint.tools.transform.SnapAxis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * [LineDetector] against the real Table (Count) / Table (Size) filters on transparent, white and
 * noisy backgrounds (Space = 0 and > 0, thin to 100 px lines, with and without margin, both
 * alignments), plus boxes, blank / uniform layers, noise, the line cap and performance.
 */
class LineDetectorTest {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    private fun detect(buf: PixelBuffer, cancelled: () -> Boolean = { false }): List<DetectedLine> =
        LineDetector.detect(buf.width, buf.height, { y0, rows, out ->
            System.arraycopy(buf.pixels, y0 * buf.width, out, 0, rows * buf.width)
        }, cancelled)

    private fun List<DetectedLine>.on(axis: SnapAxis) = filter { it.axis == axis }

    private enum class Bg { TRANSPARENT, WHITE, NOISY }

    private fun background(bg: Bg, w: Int, h: Int, seed: Int = 1): PixelBuffer = when (bg) {
        Bg.TRANSPARENT -> PixelBuffer(w, h)
        Bg.WHITE -> PixelBuffer.filled(w, h, white)
        Bg.NOISY -> {
            // A diagonal gradient with per-channel grain, like a photo.
            val rnd = Random(seed)
            val b = PixelBuffer(w, h)
            for (y in 0 until h) for (x in 0 until w) {
                val g = 90 + 130 * (x + y) / (w + h)
                fun ch(base: Int) = (base + rnd.nextInt(-12, 13)).coerceIn(0, 255)
                b.pixels[y * w + x] = (0xFF shl 24) or (ch(g) shl 16) or (ch(g - 20) shl 8) or ch(g + 10)
            }
            b
        }
    }

    /** Where the Table filters put their lines (same math as TableFilters.kt). */
    private class Layout(val x0: Float, val y0: Float, val cols: Int, val rows: Int, val cw: Float, val ch: Float, val space: Float) {
        fun lines(axis: SnapAxis): List<Float> {
            val start = if (axis == SnapAxis.X) x0 else y0
            val n = if (axis == SnapAxis.X) cols else rows
            val cell = if (axis == SnapAxis.X) cw else ch
            return if (space == 0f) (0..n).map { start + it * cell }
            else (0 until n).flatMap { listOf(start + it * (cell + space), start + it * (cell + space) + cell) }
        }

        /** The table's extent along lines of [axis] (vertical lines run along y). */
        fun extent(axis: SnapAxis): Pair<Float, Float> =
            if (axis == SnapAxis.X) y0 to y0 + rows * ch + (rows - 1) * space
            else x0 to x0 + cols * cw + (cols - 1) * space

        override fun toString() = "layout(x0=$x0, y0=$y0, ${cols}x$rows, cell ${cw}x$ch, space $space)"
    }

    private fun countLayout(w: Int, h: Int, cols: Int, rows: Int, margin: Float, space: Float): Layout? {
        val cw = (w - 2f * margin - (cols - 1) * space) / cols
        val ch = (h - 2f * margin - (rows - 1) * space) / rows
        return if (cw > 0f && ch > 0f) Layout(margin, margin, cols, rows, cw, ch, space) else null
    }

    private fun sizeLayout(w: Int, h: Int, cw: Float, ch: Float, margin: Float, space: Float, centered: Boolean): Layout? {
        val iw = w - 2f * margin
        val ih = h - 2f * margin
        val cols = if (iw <= 0f) 0 else floor((iw + space) / (cw + space)).toInt()
        val rows = if (ih <= 0f) 0 else floor((ih + space) / (ch + space)).toInt()
        if (cols <= 0 || rows <= 0) return null
        val usedW = cols * cw + (cols - 1) * space
        val usedH = rows * ch + (rows - 1) * space
        return Layout(margin + if (centered) (iw - usedW) / 2f else 0f, margin + if (centered) (ih - usedH) / 2f else 0f, cols, rows, cw, ch, space)
    }

    private fun tableCount(src: PixelBuffer, cols: Int, rows: Int, margin: Float, space: Float, t: Float, color: Int = black): PixelBuffer {
        val f = TableCountFilter()
        val v = f.defaultValues().set("cols", cols.toFloat()).set("rows", rows.toFloat()).set("margin", margin)
            .set("space", space).set("thickness", t).set("color", color)
        return f.apply(src, v, FilterContext())
    }

    private fun tableSize(src: PixelBuffer, cw: Float, ch: Float, margin: Float, space: Float, t: Float, centered: Boolean, color: Int = black): PixelBuffer {
        val f = TableSizeFilter()
        val v = f.defaultValues().set("cellW", cw).set("cellH", ch).set("margin", margin).set("space", space)
            .set("thickness", t).set("color", color).set("align", if (centered) 0f else 1f)
        return f.apply(src, v, FilterContext())
    }

    /** Cells clearly wider than their lines (else the "table" is a solid fill with holes). */
    private fun Layout.isTable(t: Float) = min(cw, ch) >= 2.5f * t + 4f && (space == 0f || space >= 2f * t + 4f)

    /**
     * Every line of [layout] drawn [t] px thick on a [w] x [h] image is in [found] (within 0.5
     * px, spanning most of the table), and nothing else is far from them (sides of thick lines
     * are fine). Lines cut by the image border with their middle inside the image can't be
     * measured and are skipped; lines centered on the border are found on it.
     */
    private fun assertTableLines(what: String, found: List<DetectedLine>, layout: Layout, t: Float, w: Int, h: Int) {
        for (axis in SnapAxis.entries) {
            val size = if (axis == SnapAxis.X) w.toFloat() else h.toFloat()
            val expected = layout.lines(axis)
            val mine = found.on(axis)
            val (e0, e1) = layout.extent(axis)
            for (pos in expected) {
                val clipped = pos - t / 2f < -0.01f || pos + t / 2f > size + 0.01f
                val onBorder = abs(pos) < 0.01f || abs(pos - size) < 0.01f
                if (clipped && !onBorder) continue
                val hit = mine.filter { abs(it.pos - pos) <= 0.5f }
                if (hit.isEmpty()) fail("$what $layout: no $axis line at $pos; found ${mine.map { it.pos }}")
                val l = hit.maxBy { it.end - it.start }
                val cover = min(l.end, e1) - max(l.start, e0)
                assertTrue("$what $layout: $axis line at $pos spans ${l.start}..${l.end}, table $e0..$e1", cover >= 0.8f * (e1 - e0) - t)
            }
            for (l in mine) {
                val near = expected.any { abs(it - l.pos) <= t / 2f + 1.5f } || l.pos <= 0.5f || l.pos >= size - 0.5f
                assertTrue("$what $layout: spurious $axis line at ${l.pos} (${l.start}..${l.end}); expected $expected", near)
            }
        }
    }

    // ------------------------------------------------------------------ Table filters

    private val gridSizes = listOf(1 to 1, 2 to 10, 5 to 3, 7 to 7, 10 to 4)

    @Test
    fun tableCountLinesAreFoundOnEveryBackground() {
        val w = 600
        val h = 700
        var checked = 0
        for (bg in Bg.entries) {
            val base = background(bg, w, h)
            for (t in listOf(1f, 4f, 20f)) for (space in listOf(0f, 2f * t + 10f)) for (margin in listOf(0f, 40f)) {
                for ((cols, rows) in gridSizes) {
                    val layout = countLayout(w, h, cols, rows, margin, space) ?: continue
                    if (!layout.isTable(t)) continue
                    val out = tableCount(base, cols, rows, margin, space, t)
                    assertTableLines("count $bg t=$t", detect(out), layout, t, w, h)
                    checked++
                }
            }
        }
        assertTrue("cases checked: $checked", checked >= 120)
    }

    @Test
    fun tableSizeLinesAreFoundWithBothAlignments() {
        val w = 600
        val h = 700
        var checked = 0
        for (bg in Bg.entries) {
            val base = background(bg, w, h, seed = 2)
            for (t in listOf(1f, 4f, 20f)) for (space in listOf(0f, 2f * t + 10f)) for (margin in listOf(0f, 40f)) {
                for (centered in listOf(true, false)) for ((cw, ch) in listOf(100f to 80f, 73f to 91f)) {
                    val layout = sizeLayout(w, h, cw, ch, margin, space, centered) ?: continue
                    if (!layout.isTable(t)) continue
                    val out = tableSize(base, cw, ch, margin, space, t, centered)
                    assertTableLines("size $bg t=$t centered=$centered", detect(out), layout, t, w, h)
                    checked++
                }
            }
        }
        assertTrue("cases checked: $checked", checked >= 120)
    }

    @Test
    fun hundredPixelLinesCrossingAndApart() {
        val w = 1500
        val h = 1600
        val t = 100f
        var checked = 0
        for (bg in Bg.entries) {
            val base = background(bg, w, h, seed = 3)
            for (space in listOf(0f, 210f)) for (margin in listOf(0f, 40f)) for ((cols, rows) in listOf(1 to 1, 3 to 2)) {
                val layout = countLayout(w, h, cols, rows, margin, space) ?: continue
                if (!layout.isTable(t)) continue
                assertTableLines("count $bg t=$t", detect(tableCount(base, cols, rows, margin, space, t)), layout, t, w, h)
                checked++
            }
            // Table (Size), centered, 400 x 350 cells.
            val layout = sizeLayout(w, h, 400f, 350f, 40f, 0f, true)!!
            assertTableLines("size $bg t=$t", detect(tableSize(base, 400f, 350f, 40f, 0f, t, true)), layout, t, w, h)
            checked++
        }
        assertEquals(27, checked)
    }

    @Test
    fun largeCanvasTablesWithHundredPixelCells() {
        val w = 2048
        val h = 2048
        for (bg in listOf(Bg.TRANSPARENT, Bg.WHITE)) {
            val base = background(bg, w, h)
            for (t in listOf(4f, 20f)) for (space in listOf(0f, 50f)) {
                val layout = sizeLayout(w, h, 100f, 100f, 40f, space, true)!!
                assertTableLines("size 2048 $bg t=$t", detect(tableSize(base, 100f, 100f, 40f, space, t, true)), layout, t, w, h)
            }
            val layout = countLayout(w, h, 5, 5, 40f, 0f)!!
            assertTableLines("count 2048 $bg t=100", detect(tableCount(base, 5, 5, 40f, 0f, 100f)), layout, 100f, w, h)
        }
    }

    @Test
    fun coloredAndTranslucentLines() {
        val w = 500
        val h = 400
        // Red lines on an opaque blue layer.
        val blue = PixelBuffer.filled(w, h, 0xFF2040C0.toInt())
        val l1 = countLayout(w, h, 4, 3, 30f, 0f)!!
        assertTableLines("red on blue", detect(tableCount(blue, 4, 3, 30f, 0f, 3f, 0xFFE02020.toInt())), l1, 3f, w, h)
        // Half-transparent black lines on a transparent layer.
        val l2 = countLayout(w, h, 3, 3, 25f, 0f)!!
        assertTableLines("translucent", detect(tableCount(PixelBuffer(w, h), 3, 3, 25f, 0f, 2f, 0x80000000.toInt())), l2, 2f, w, h)
    }

    @Test
    fun thinLinesGiveOneLineThickOnesAlsoTheirSides() {
        val w = 400
        val h = 300
        // One cell, no margin... a 1 x 1 table with margin 100: a box outline.
        val thin = detect(tableCount(PixelBuffer(w, h), 1, 1, 100f, 0f, 4f))
        assertEquals(listOf(100f, 300f), thin.on(SnapAxis.X).map { it.pos })
        assertEquals(listOf(100f, 200f), thin.on(SnapAxis.Y).map { it.pos })
        val thick = detect(tableCount(PixelBuffer(w, h), 1, 1, 100f, 0f, 30f))
        assertEquals(listOf(85f, 100f, 115f, 285f, 300f, 315f), thick.on(SnapAxis.X).map { it.pos })
        assertEquals(30f, thick.on(SnapAxis.X)[1].thickness, 0.01f)
    }

    // ------------------------------------------------------------------ other content

    private fun fillRect(b: PixelBuffer, l: Int, t: Int, r: Int, btm: Int, color: Int) {
        for (y in t until btm) for (x in l until r) b.pixels[y * b.width + x] = color
    }

    @Test
    fun aFilledRectangleGivesItsFourSides() {
        for (bg in listOf(PixelBuffer(600, 500), PixelBuffer.filled(600, 500, white))) {
            fillRect(bg, 100, 150, 400, 350, 0xFF3366AA.toInt())
            val lines = detect(bg)
            assertEquals(listOf(100f, 400f), lines.on(SnapAxis.X).map { it.pos })
            assertEquals(listOf(150f, 350f), lines.on(SnapAxis.Y).map { it.pos })
            val top = lines.on(SnapAxis.Y)[0]
            assertEquals(100f, top.start, 0f)
            assertEquals(400f, top.end, 0f)
        }
        // A small box is also a thick band: its middle is found too, across its longer side (a
        // band shorter than thick is a blob, not a line).
        val small = PixelBuffer(300, 300).also { fillRect(it, 50, 60, 110, 100, black) }
        assertEquals(listOf(50f, 110f), detect(small).on(SnapAxis.X).map { it.pos })
        assertEquals(listOf(60f, 80f, 100f), detect(small).on(SnapAxis.Y).map { it.pos })
    }

    @Test
    fun blankAndUniformLayersGiveNoLines() {
        assertTrue(detect(PixelBuffer(300, 200)).isEmpty())
        assertTrue(detect(PixelBuffer.filled(300, 200, 0xFFCC3344.toInt())).isEmpty())
        assertTrue(detect(PixelBuffer.filled(300, 200, 0x40112233)).isEmpty())
        // A smooth gradient has no edges either.
        val grad = PixelBuffer(400, 300)
        for (y in 0 until 300) for (x in 0 until 400) grad.pixels[y * 400 + x] = (0xFF shl 24) or ((x * 255 / 399) shl 16) or ((y * 255 / 299) shl 8)
        assertTrue(detect(grad).isEmpty())
        assertTrue(detect(PixelBuffer(1, 1)).isEmpty())
        assertTrue(LineDetector.detect(0, 0, { _, _, _ -> }).isEmpty())
    }

    @Test
    fun shortLinesAndNoiseAreIgnored() {
        // Short dashes far apart (shorter than 4% of the canvas) are not lines; a long one is.
        val b = PixelBuffer.filled(1000, 1000, white)
        for (i in 0 until 8) fillRect(b, 50 + i * 120, 100, 50 + i * 120 + 30, 103, black)
        fillRect(b, 50, 500, 950, 503, black)
        val lines = detect(b)
        assertEquals(listOf(501.5f), lines.on(SnapAxis.Y).map { it.pos })
        assertTrue(lines.on(SnapAxis.X).isEmpty())
        // Grain: nothing.
        assertTrue(detect(background(Bg.NOISY, 800, 600)).isEmpty())
        // Pure random pixels: nothing (or next to nothing).
        val rnd = Random(7)
        val noise = PixelBuffer(800, 600)
        for (i in noise.pixels.indices) noise.pixels[i] = rnd.nextInt() or (0xFF shl 24)
        assertTrue(detect(noise).size <= 2)
    }

    @Test
    fun aBusyImageIsCappedToTheLongestLines() {
        // Thousands of random boxes: many straight edges, at most MAX_LINES_PER_AXIS per axis.
        val w = 2048
        val h = 2048
        val b = PixelBuffer.filled(w, h, white)
        val rnd = Random(11)
        repeat(3000) {
            val x = rnd.nextInt(w - 200)
            val y = rnd.nextInt(h - 200)
            fillRect(b, x, y, x + 60 + rnd.nextInt(140), y + 60 + rnd.nextInt(140), rnd.nextInt() or (0xFF shl 24))
        }
        val lines = detect(b)
        for (axis in SnapAxis.entries) {
            val n = lines.on(axis).size
            assertTrue("$axis: $n lines", n in 50..LineDetector.MAX_LINES_PER_AXIS)
        }
    }

    @Test
    fun cancellingStopsEarly() {
        val buf = tableCount(PixelBuffer.filled(800, 800, white), 4, 4, 40f, 0f, 4f)
        var polls = 0
        assertTrue(detect(buf) { ++polls > 2 }.isEmpty())
        assertTrue(detect(buf).isNotEmpty())
    }

    // ------------------------------------------------------------------ performance

    private fun timeMs(block: () -> Unit): Double {
        var best = Double.MAX_VALUE
        repeat(4) {
            val t0 = System.nanoTime()
            block()
            best = min(best, (System.nanoTime() - t0) / 1e6)
        }
        return best
    }

    @Test
    fun phoneSizedLayersAreFast() {
        val w = 1080
        val h = 2408
        val white = tableCount(PixelBuffer.filled(w, h, white), 4, 9, 40f, 0f, 4f)
        val photo = tableCount(background(Bg.NOISY, w, h), 4, 9, 40f, 0f, 4f)
        val clear = tableCount(PixelBuffer(w, h), 4, 9, 40f, 12f, 6f)
        var found = 0
        val msWhite = timeMs { found = detect(white).size }
        assertEquals(10 + 5, found)
        val msPhoto = timeMs { detect(photo) }
        val msClear = timeMs { detect(clear) }
        println("LineDetector 1080x2408: table on white ${"%.1f".format(msWhite)} ms, on grain ${"%.1f".format(msPhoto)} ms, transparent ${"%.1f".format(msClear)} ms")
        // Loose bound: shared build machines are noisy; the target is well under 150 ms.
        assertTrue("$msWhite / $msPhoto / $msClear ms", max(msWhite, max(msPhoto, msClear)) < 600.0)
    }

    /** A photo-like image: smooth random shapes at several scales, hard edges and grain. */
    private fun photoLike(w: Int, h: Int, seed: Int): PixelBuffer {
        val rnd = Random(seed)
        val out = PixelBuffer(w, h)
        val layers = listOf(256, 64, 16, 4).map { cell -> cell to FloatArray((w / cell + 2) * (h / cell + 2) * 3) { rnd.nextFloat() } }
        for (y in 0 until h) for (x in 0 until w) {
            var r = 0f; var g = 0f; var b = 0f; var amp = 0.5f
            for ((cell, grid) in layers) {
                val gw = w / cell + 2
                val fx = x.toFloat() / cell
                val fy = y.toFloat() / cell
                val ix = fx.toInt(); val iy = fy.toInt()
                val tx = fx - ix; val ty = fy - iy
                fun at(c: Int, dx: Int, dy: Int) = grid[((iy + dy) * gw + ix + dx) * 3 + c]
                fun lerp(c: Int) = (at(c, 0, 0) * (1 - tx) + at(c, 1, 0) * tx) * (1 - ty) + (at(c, 0, 1) * (1 - tx) + at(c, 1, 1) * tx) * ty
                r += amp * lerp(0); g += amp * lerp(1); b += amp * lerp(2)
                amp *= 0.5f
            }
            // Posterized a little (hard edges between regions), plus grain.
            fun ch(v: Float) = ((floor(v * 6f) / 6f) * 255f + rnd.nextInt(-10, 11)).toInt().coerceIn(0, 255)
            out.pixels[y * w + x] = (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
        }
        return out
    }

    @Test
    fun photoLikeImagesStayFastAndCapped() {
        val photo = photoLike(2048, 2048, 5)
        var lines: List<DetectedLine> = emptyList()
        val ms = timeMs { lines = detect(photo) }
        println("LineDetector 2048x2048 photo-like: ${"%.1f".format(ms)} ms, ${lines.size} lines")
        for (axis in SnapAxis.entries) assertTrue(lines.on(axis).size <= LineDetector.MAX_LINES_PER_AXIS)
        assertTrue("$ms ms", ms < 2000.0)
        // A table drawn over it is still found.
        val layout = countLayout(2048, 2048, 6, 6, 40f, 0f)!!
        val table = detect(tableCount(photo, 6, 6, 40f, 0f, 6f))
        for (axis in SnapAxis.entries) for (pos in layout.lines(axis)) {
            assertTrue("$axis line at $pos: ${table.on(axis).map { it.pos }}", table.on(axis).any { abs(it.pos - pos) <= 0.5f })
        }
    }

    @Test
    fun largestCanvasIsReadInStrips() {
        // 4000 x 5000 (the largest canvas), made row by row: no full-size buffer anywhere.
        val w = 4000
        val h = 5000
        var maxRows = 0
        val t0 = System.nanoTime()
        val lines = LineDetector.detect(w, h, { y0, rows, out ->
            maxRows = max(maxRows, rows)
            for (r in 0 until rows) {
                val y = y0 + r
                val onH = (y % 500) in 248..253
                for (x in 0 until w) out[r * w + x] = if (onH || (x % 400) in 198..203) black else white
            }
        })
        val ms = (System.nanoTime() - t0) / 1e6
        println("LineDetector 4000x5000 procedural: ${"%.0f".format(ms)} ms, ${lines.size} lines, strips of $maxRows rows")
        assertTrue("strips of $maxRows rows", maxRows.toLong() * w <= 1L shl 16)
        assertEquals((0 until 10).map { it * 400 + 201f }, lines.on(SnapAxis.X).map { it.pos })
        assertEquals((0 until 10).map { it * 500 + 251f }, lines.on(SnapAxis.Y).map { it.pos })
    }
}
