package com.brushwork.paint.tools.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wrapped line breaking (v1.5 §4.1e, JVM): a fake monospace font (every character 10 px wide,
 * cells 10 px tall) so every position is exact.
 */
class WrapLayoutTest {

    /** 10 px per character; the low half of a surrogate pair has no advance of its own. */
    private val mono = WrapMeasurer { text, start, end, out ->
        for (i in start until end) out[i - start] = if (Character.isLowSurrogate(text[i])) 0f else 10f
    }

    private val metrics = WrapMetrics(ascent = 8f, descent = 2f)

    private fun layout(
        text: String,
        width: Float,
        sides: WrapSides = WrapSides.LARGEST,
        align: TextAlign = TextAlign.START,
        minRun: Float = 0f,
        m: WrapMetrics = metrics,
        blocked: (Float, Float) -> List<ClosedFloatingPointRange<Float>> = { _, _ -> emptyList() },
    ): WrapResult = WrapLayout.layout(WrapLayout.measure(text, mono), width, m, blocked, sides, align, minRun)

    private fun WrapResult.texts(src: String) = lines.map { src.substring(it.start, it.end) }

    /** The picture covers [x0]..[x1] in the bands that start above [untilY]. */
    private fun obstacle(x0: Float, x1: Float, untilY: Float = 30f): (Float, Float) -> List<ClosedFloatingPointRange<Float>> =
        { top, _ -> if (top < untilY) listOf(x0..x1) else emptyList() }

    @Test
    fun withoutAPictureLinesBreakGreedilyAtSpaces() {
        val s = "aaa bbb ccc ddd"
        val r = layout(s, 75f)
        assertEquals(listOf("aaa bbb", "ccc ddd"), r.texts(s))
        assertEquals(listOf(0, 8), r.lines.map { it.start })
        assertEquals(listOf(8f, 18f), r.lines.map { it.baseline })
        assertEquals(20f, r.height, 0f)
        assertEquals(70f, r.lines[0].width, 0f)
    }

    @Test
    fun trailingSpacesHangPastTheEdge() {
        val s = "aaaa    bbbb"
        val r = layout(s, 40f)
        assertEquals(listOf("aaaa", "bbbb"), r.texts(s))
        assertEquals("the next line starts after the spaces", 8, r.lines[1].start)
    }

    @Test
    fun largestSidePicksTheWiderRunAndBothSidesUseBoth() {
        val s = "aa bb cc dd ee ff gg hh"
        // 200 wide, the picture covers 60..100 for the first three bands: 60 px left, 100 px right.
        val largest = layout(s, 200f, blocked = obstacle(60f, 100f))
        val first = largest.lines.first()
        assertEquals("the wider (right) run is used", 100f, first.x, 0f)
        assertEquals("aa bb cc", s.substring(first.start, first.end))
        assertTrue("no line enters the picture", largest.lines.filter { it.baseline < 30f }.all { it.x >= 100f || it.x + it.width <= 60f })

        val both = layout(s, 200f, sides = WrapSides.BOTH, blocked = obstacle(60f, 100f))
        val band0 = both.lines.filter { it.baseline == 8f }
        assertEquals("the left run, then the right one", 2, band0.size)
        assertEquals(listOf(0f, 100f), band0.map { it.x })
        assertEquals("aa bb", s.substring(band0[0].start, band0[0].end))
        assertEquals("cc dd ee", s.substring(band0[1].start, band0[1].end))
        // Left / right only.
        val left = layout(s, 200f, sides = WrapSides.LEFT, blocked = obstacle(60f, 100f))
        assertTrue(left.lines.filter { it.baseline < 30f }.all { it.x + it.width <= 60f })
        val right = layout(s, 200f, sides = WrapSides.RIGHT, blocked = obstacle(110f, 150f))
        assertTrue("right only, even when the left is wider", right.lines.filter { it.baseline < 30f }.all { it.x >= 150f })
    }

    @Test
    fun aFullyBlockedBandIsSkipped() {
        val s = "aaa bbb ccc"
        // The second band (top 10) is covered from edge to edge.
        val r = layout(s, 80f) { top, _ -> if (top in 9f..11f) listOf(-5f..85f) else emptyList() }
        assertEquals(listOf("aaa bbb", "ccc"), r.texts(s))
        assertEquals(listOf(8f, 28f), r.lines.map { it.baseline })
        assertEquals(1, r.skippedBands)
        assertEquals(30f, r.height, 0f)
    }

    @Test
    fun runsNarrowerThanTheShortestRunStayEmpty() {
        val s = "aa bb cc dd"
        // A 15 px run on the left, 145 px on the right: with both sides, only the right one is used.
        val r = layout(s, 200f, sides = WrapSides.BOTH, minRun = 20f, blocked = obstacle(15f, 55f))
        assertTrue(r.lines.filter { it.baseline < 30f }.all { it.x >= 55f })
        // Every run too narrow: the band is skipped.
        val skip = layout(s, 100f, minRun = 50f, blocked = obstacle(30f, 70f, untilY = 10f))
        assertEquals(1, skip.skippedBands)
        assertEquals(18f, skip.lines.first().baseline, 0f)
    }

    @Test
    fun aLongWordIsBrokenBetweenCharacters() {
        val s = "abcdefghijklmno"
        val r = layout(s, 60f)
        assertEquals(listOf("abcdef", "ghijkl", "mno"), r.texts(s))
        // A surrogate pair (one emoji) is never split.
        val e = "aaaaa😀bb"
        val er = layout(e, 60f)
        assertTrue(er.lines.all { it.end == e.length || !Character.isLowSurrogate(e[it.end]) })
    }

    @Test
    fun aWordThatFitsTheFullWidthWaitsForAWideEnoughRun() {
        val s = "aa abcdefgh cc"
        // Beside the picture only 50 px are free; "abcdefgh" (80 px) goes below it, unbroken.
        val r = layout(s, 100f, blocked = obstacle(50f, 100f, untilY = 20f))
        val texts = r.texts(s)
        assertEquals("aa", texts[0])
        assertTrue("the word is not broken: $texts", texts.contains("abcdefgh cc") || texts.contains("abcdefgh"))
        val word = r.lines.first { s.substring(it.start, it.end).startsWith("abcdefgh") }
        assertTrue("placed below the picture", word.baseline > 20f)
    }

    @Test
    fun alignmentIsAppliedInsideTheRun() {
        val s = "abc"
        val center = layout(s, 200f, align = TextAlign.CENTER, blocked = obstacle(0f, 100f))
        assertEquals(135f, center.lines[0].x, 0f)
        val end = layout(s, 200f, align = TextAlign.END, blocked = obstacle(0f, 100f))
        assertEquals(170f, end.lines[0].x, 0f)
        val noPicture = layout(s, 200f, align = TextAlign.CENTER)
        assertEquals(85f, noPicture.lines[0].x, 0f)
    }

    @Test
    fun emptyTextAndEmptyLines() {
        val r = layout("", 100f)
        assertEquals(1, r.lines.size)
        assertEquals(10f, r.height, 0f)
        val s = "a\n\nb"
        val lines = layout(s, 100f, m = WrapMetrics(8f, 2f, extra = 2f))
        assertEquals(3, lines.lines.size)
        assertEquals(listOf(8f, 20f, 32f), lines.lines.map { it.baseline })
        assertEquals("no spacing after the last line", 34f, lines.height, 0f)
    }

    @Test
    fun spacingFollowsStaticLayoutAtTheEnd() {
        // "a\n": the first line reaches the text end (its line break included): no extra after it.
        val r = layout("a\n", 100f, m = WrapMetrics(8f, 2f, extra = 4f))
        assertEquals(2, r.lines.size)
        assertEquals(listOf(8f, 18f), r.lines.map { it.baseline })
        assertEquals(20f, r.height, 0f)
        val two = layout("a\nb", 100f, m = WrapMetrics(8f, 2f, extra = 4f))
        assertEquals(listOf(8f, 22f), two.lines.map { it.baseline })
        assertEquals(24f, two.height, 0f)
        // The StaticLayout rule for the extra spacing.
        assertEquals(WrapMetrics(56f, 15f, 14f), WrapMetrics.staticLayout(-56, 15, 1.2f))
        assertEquals(WrapMetrics(56f, 15f, -36f), WrapMetrics.staticLayout(-56, 15, 0.5f))
        assertEquals(0f, WrapMetrics.staticLayout(-56, 15, 1f).extra, 0f)
    }

    @Test
    fun theDesignSignatureLaysOutTheSame() {
        val s = "aaa bbb ccc ddd"
        val r = WrapLayout.layout(s, mono, 75f, 10f, 8f, { _, _ -> emptyList() }, WrapSides.LARGEST, TextAlign.START, 0f)
        assertEquals(listOf("aaa bbb", "ccc ddd"), r.texts(s))
        assertEquals(listOf(8f, 18f), r.lines.map { it.baseline })
    }

    // ------------------------------------------------------------------ obstacle

    private fun square(x0: Float, y0: Float, x1: Float, y1: Float) = WrapPolygon(listOf(x0, x1, x1, x0), listOf(y0, y0, y1, y1))

    @Test
    fun obstacleExtentsAreExactPerBand() {
        // A triangle with its apex up: (0, 0) - (100, 100) - (-100, 100), item at the origin.
        val tri = WrapPolygon(listOf(0f, 100f, -100f), listOf(0f, 100f, 100f))
        val o = WrapObstacle(listOf(tri), 0f, 0f, 0f)
        val m = WrapObstacle.CONTOUR_MARGIN
        val band = o.blocked(40f, 60f, 0f).single()
        // Widest inside the band at its bottom (y = 60 + margin): x = ±(60 + margin).
        assertEquals(-(60f + m) - m, band.start, 1e-3f)
        assertEquals(60f + m + m, band.endInclusive, 1e-3f)
        assertTrue("above the apex nothing is covered", o.blocked(-20f, -5f, 0f).isEmpty())
        // The distance widens the band and the interval alike (a square dilation).
        val gap = o.blocked(40f, 60f, 10f).single()
        assertEquals(-(70f + m) - (10f + m), gap.start, 1e-3f)
        // The area frame: text area whose top-left corner is at (-100, -50) in the block frame.
        val area = o.forArea(-100f, -50f, 0f)(90f, 110f).single()
        assertEquals(band.start + 100f, area.start, 1e-3f)
    }

    @Test
    fun aHugeGapPushesEveryLineBelowThePicture() {
        val o = WrapObstacle(listOf(square(-20f, -20f, 20f, 20f)), 0f, 0f, 0f)
        val s = "aa bb cc dd"
        // Text area 100 wide, its top-left corner at (-50, -40) in the block frame.
        val r = WrapLayout.layout(WrapLayout.measure(s, mono), 100f, metrics, o.forArea(-50f, -40f, 1000f), WrapSides.LARGEST, TextAlign.START, 0f)
        val firstTop = r.lines.first().baseline - 8f
        assertTrue("first line below the picture + gap: $firstTop", firstTop - 40f >= 20f + 1000f)
        assertTrue(r.skippedBands > 50)
    }

    @Test
    fun aTurnedTextSeesThePictureInItsOwnFrame() {
        // A picture right of the text item (doc x 100..140, y -10..10); the text turned 90°
        // clockwise: in its frame the picture is "above" it (negative y).
        val o = WrapObstacle(listOf(square(100f, -10f, 140f, 10f)), 0f, 0f, 90f)
        assertTrue(o.blocked(-5f, 5f, 0f).isEmpty())
        val above = o.blocked(-120f, -110f, 0f).single()
        val m = WrapObstacle.CONTOUR_MARGIN
        assertEquals(-10f - m, above.start, 1e-3f)
        assertEquals(10f + m, above.endInclusive, 1e-3f)
        assertTrue(o.blocked(-150f, -145f, 0f).isEmpty())
        assertFalse(o.isEmpty)
        assertTrue(WrapObstacle(emptyList(), 0f, 0f, 0f).isEmpty)
    }
}
