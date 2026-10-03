package com.brushwork.paint.tools.text

import java.text.BreakIterator

/*
 * Line breaking of text that flows around a picture (v1.5 §4.1). Pure Kotlin (no android
 * imports): the per-character advances come from a [WrapMeasurer] (the text paint on Android, a
 * fake monospace font in tests) and the room on every line from a `blocked` callback (the
 * picture's outline, see [WrapObstacle]).
 *
 * The rules follow StaticLayout (simple greedy breaking, no hyphenation, includePad = false), so
 * a wrapped text whose picture is elsewhere breaks and spaces its lines like unwrapped text:
 * - break opportunities come from `java.text.BreakIterator.getLineInstance()` (CJK included);
 *   trailing spaces hang past the edge and don't count;
 * - a word wider than the whole width is broken between characters (grapheme clusters);
 * - line pitch = cell (descent - ascent) + extra (the line spacing); the height ends at the last
 *   line's cell (StaticLayout adds no spacing below its last line, even an empty one after a
 *   final line break).
 *
 * Wrapping: every line slot ("band") is the glyph cell of one line. The picture blocks parts of
 * it; the free parts ("runs") narrower than the shortest run are left empty, and a band with no
 * usable run is skipped (the text continues below). [WrapSides] picks the run(s) of a band.
 * Alignment is applied inside the chosen run. A word that doesn't fit beside the picture but
 * would fit the full width moves on to the next run (like a word processor); only a word wider
 * than the whole text width is broken between characters.
 */

/** Measures the advance of every character of a paragraph (no line breaks in it). */
fun interface WrapMeasurer {
    /**
     * Advances of `text[start, end)` into `out[0, end - start)`, shaped as one run (the second
     * half of a surrogate pair and other characters inside a cluster get 0).
     */
    fun advances(text: String, start: Int, end: Int, out: FloatArray)
}

/**
 * Vertical metrics of wrapped lines: [ascent] (baseline below the line top) and [descent] (cell
 * below the baseline) make the glyph cell; [extra] is added between lines (line spacing).
 */
data class WrapMetrics(val ascent: Float, val descent: Float, val extra: Float = 0f) {
    /** Height of one line's glyph cell (what the picture is tested against). */
    val cell: Float get() = ascent + descent

    /** Distance between the baselines of consecutive lines. */
    val pitch: Float get() = cell + extra

    companion object {
        /**
         * The metrics StaticLayout uses for a font whose integer metrics are [ascentInt] (negative,
         * above the baseline) and [descentInt], with the line spacing multiplier [lineSpacing]
         * (`setLineSpacing(0, lineSpacing)`): extra = round(cell × (lineSpacing − 1)).
         */
        fun staticLayout(ascentInt: Int, descentInt: Int, lineSpacing: Float): WrapMetrics {
            val cell = descentInt - ascentInt
            // StaticLayout.out(): float arithmetic, rounded half away from zero.
            val ex: Float = cell * (lineSpacing - 1f) + 0f
            val extra = if (ex >= 0f) (ex + 0.5f).toInt() else -((-ex + 0.5f).toInt())
            return WrapMetrics((-ascentInt).toFloat(), descentInt.toFloat(), extra.toFloat())
        }
    }
}

/**
 * One laid-out line: the characters [start]..[end] (trailing spaces and the line break left
 * out), drawn with their left end at ([x], [baseline]) in the text area's coordinates; [width]
 * is their advance width.
 */
data class WrapLine(val start: Int, val end: Int, val x: Float, val baseline: Float, val width: Float)

/**
 * Lines of a wrapped text and the text's [height] (from the first line's top to the last line's
 * glyph cell bottom, skipped bands included). [skippedBands] counts bands left empty because the
 * picture left no usable room.
 */
class WrapResult(val lines: List<WrapLine>, val height: Float, val bands: Int, val skippedBands: Int)

/**
 * A text laid out in a frame of limited height (v1.6, [WrapLayout.layoutFrame]): the [lines] that
 * fit, [end] = the start index (into the laid-out text) of the first line that does not fit, or
 * the text's length when everything fits, and the [height] of what was laid out (as
 * [WrapResult.height]).
 */
class FrameResult(val lines: List<WrapLine>, val end: Int, val height: Float)

/**
 * A text measured for [WrapLayout]: character advances and line break opportunities, computed
 * once and reused by every layout pass (only the picture's position changes between passes).
 */
class WrapText internal constructor(
    val text: String,
    /** prefix[i] = advance of text[0, i) (line breaks count 0). */
    internal val prefix: DoubleArray,
    /** Paragraphs: start, end (exclusive, the line break or the text end) pairs. */
    internal val paragraphs: IntArray,
    /** Per paragraph: positions where a line may end (absolute, increasing, the paragraph end last). */
    internal val breaks: Array<IntArray>,
    /**
     * v1.6 letter scaling: what the advances were scaled for besides [text] (a text's own scale
     * spec and text: [ScaleKey]; a linked story's tail: its characters' factors, [TailScaleKey]),
     * null when unscaled. A measurement is reused only for the same key: a frame's letters are
     * smaller or larger depending on the letters before it.
     */
    internal val scaleKey: Any? = null,
) {
    val paragraphCount: Int get() = paragraphs.size / 2

    /** Advance width of text[a, b). */
    fun width(a: Int, b: Int): Float = (prefix[b] - prefix[a]).toFloat()
}

object WrapLayout {
    /** Widths are compared with this slack (float sums of advances). */
    private const val EPS = 1e-3f

    /** Bands laid out beyond this many (a picture covering everything) ignore the picture. */
    const val MAX_BANDS = 4000

    /** Measures [text] once (advances per paragraph, break opportunities). */
    fun measure(text: String, measurer: WrapMeasurer): WrapText = measure(text, measurer, null)

    /** [measure] of advances scaled for [scaleKey] (v1.6 letter scaling; see [WrapText.scaleKey]). */
    internal fun measure(text: String, measurer: WrapMeasurer, scaleKey: Any?): WrapText {
        val n = text.length
        val prefix = DoubleArray(n + 1)
        val paras = ArrayList<Int>()
        val breaks = ArrayList<IntArray>()
        var buf = FloatArray(64)
        var ps = 0
        while (true) {
            var pe = text.indexOf('\n', ps)
            if (pe < 0) pe = n
            val len = pe - ps
            if (len > 0) {
                if (buf.size < len) buf = FloatArray(maxOf(len, buf.size * 2))
                java.util.Arrays.fill(buf, 0, len, 0f)
                measurer.advances(text, ps, pe, buf)
                for (i in 0 until len) {
                    val a = buf[i]
                    prefix[ps + i + 1] = prefix[ps + i] + if (a.isFinite() && a > 0f) a.toDouble() else 0.0
                }
            }
            // The line break itself has no width.
            if (pe < n) prefix[pe + 1] = prefix[pe]
            paras += ps
            paras += pe
            breaks += lineBreaks(text, ps, pe)
            if (pe >= n) break
            ps = pe + 1
        }
        return WrapText(text, prefix, paras.toIntArray(), breaks.toTypedArray(), scaleKey)
    }

    /**
     * Widest paragraph of [t] (its trailing spaces left out, as lines end): the width a text
     * without a fixed box needs to keep every paragraph on one line (v1.6 scaled letters, whose
     * advances StaticLayout doesn't know).
     */
    fun widestParagraph(t: WrapText): Float {
        var w = 0f
        for (p in 0 until t.paragraphCount) {
            val ps = t.paragraphs[2 * p]
            var pe = t.paragraphs[2 * p + 1]
            while (pe > ps && isLineEndSpace(t.text[pe - 1])) pe--
            w = maxOf(w, t.width(ps, pe))
        }
        return w
    }

    /** Positions in (ps, pe] where a line may end (pe last); empty for an empty paragraph. */
    private fun lineBreaks(text: String, ps: Int, pe: Int): IntArray {
        if (pe <= ps) return IntArray(0)
        val bi = BreakIterator.getLineInstance()
        bi.setText(text.substring(ps, pe))
        val out = ArrayList<Int>()
        var b = bi.first()
        while (b != BreakIterator.DONE) {
            if (b > 0) out += ps + b
            b = bi.next()
        }
        if (out.isEmpty() || out.last() != pe) out += pe
        return out.toIntArray()
    }

    /**
     * Design signature: lays out [text] in [width] with lines [lineHeight] apart whose first
     * baseline is [firstBaseline] below the top (no extra spacing rule).
     */
    fun layout(
        text: String,
        measurer: WrapMeasurer,
        width: Float,
        lineHeight: Float,
        firstBaseline: Float,
        blocked: (Float, Float) -> List<ClosedFloatingPointRange<Float>>,
        sides: WrapSides,
        align: TextAlign,
        minRun: Float,
    ): WrapResult = layout(measure(text, measurer), width, WrapMetrics(firstBaseline, lineHeight - firstBaseline), blocked, sides, align, minRun)

    /**
     * Lays out [t] in a text area [width] wide. [blocked] gives, for the band from `top` to
     * `bottom` (text area coordinates, y down), the x intervals the picture covers (any order,
     * may overlap or reach outside 0..width). Runs narrower than [minRun] stay empty.
     */
    fun layout(
        t: WrapText,
        width: Float,
        metrics: WrapMetrics,
        blocked: (Float, Float) -> List<ClosedFloatingPointRange<Float>>,
        sides: WrapSides,
        align: TextAlign,
        minRun: Float,
        maxBands: Int = MAX_BANDS,
    ): WrapResult {
        val r = bandLoop(t, width, metrics, blocked, sides, align, minRun, maxBands, Double.POSITIVE_INFINITY)
        return WrapResult(r.lines, r.height, r.bands, r.skipped)
    }

    /**
     * v1.6 linked text frames (§4.4): [layout] in a frame [height] tall (the text area; +∞ = no
     * limit). The band loop is [layout]'s, with a height stop: a band whose glyph cell would end
     * below [height] is not laid out (a band with several runs fits or stops as a whole), and
     * [FrameResult.end] is the start index of the first line that doesn't fit (the text's length
     * when everything fits; 0 when not even the first line does). From 0 with an infinite height
     * this equals [layout] exactly (same loop). Chaining frames — the next one laid out from
     * `text.substring(end)` — covers the text contiguously.
     */
    fun layoutFrame(
        t: WrapText,
        width: Float,
        height: Float,
        metrics: WrapMetrics,
        blocked: (Float, Float) -> List<ClosedFloatingPointRange<Float>>,
        sides: WrapSides,
        align: TextAlign,
        minRun: Float,
    ): FrameResult {
        val limit = if (height.isNaN()) 0.0 else height.toDouble()
        val r = bandLoop(t, width, metrics, blocked, sides, align, minRun, MAX_BANDS, limit)
        return FrameResult(r.lines, r.end, r.height)
    }

    /** What [bandLoop] laid out ([end]: see [FrameResult.end]). */
    private class Bands(val lines: List<WrapLine>, val height: Float, val bands: Int, val skipped: Int, val end: Int)

    /** The band loop of [layout] and [layoutFrame]: bands whose cell ends below [maxHeight] (+ slack) stop it. */
    private fun bandLoop(
        t: WrapText,
        width: Float,
        metrics: WrapMetrics,
        blocked: (Float, Float) -> List<ClosedFloatingPointRange<Float>>,
        sides: WrapSides,
        align: TextAlign,
        minRun: Float,
        maxBands: Int,
        maxHeight: Double,
    ): Bands {
        val text = t.text
        val n = text.length
        val full = if (width.isFinite() && width > 0f) width else 0f
        val cell = metrics.cell
        val extra = metrics.extra
        val lines = ArrayList<WrapLine>()
        var top = 0.0
        var lastTop = 0.0
        var bands = 0
        var skipped = 0
        var end = n
        val stop = maxHeight + EPS
        val fullRun = listOf(Run(0f, full))
        paragraphs@ for (p in 0 until t.paragraphCount) {
            val ps = t.paragraphs[2 * p]
            val pe = t.paragraphs[2 * p + 1]
            if (ps == pe) {
                if (top + cell > stop) { end = ps; break@paragraphs }
                // An empty line takes a band, wherever the picture is (it draws nothing).
                lines += WrapLine(ps, ps, aligned(fullRun[0], 0f, align), (top + metrics.ascent).toFloat(), 0f)
                lastTop = top
                bands++
                top += cell + extra
                continue
            }
            val brk = t.breaks[p]
            var pos = ps
            while (pos < pe) {
                if (top + cell > stop) { end = pos; break@paragraphs }
                val runs = if (bands >= maxBands) fullRun else runsOf(blocked(top.toFloat(), (top + cell).toFloat()), full, sides, minRun)
                val baseline = (top + metrics.ascent).toFloat()
                var placed = false
                for (r in runs) {
                    if (pos >= pe) break
                    val end = fit(t, brk, pos, pe, r.width, full, r.width >= full - EPS || bands >= maxBands)
                    if (end < 0) continue
                    val vis = trimEnd(text, pos, end)
                    val w = t.width(pos, vis)
                    lines += WrapLine(pos, vis, aligned(r, w, align), baseline, w)
                    pos = end
                    placed = true
                }
                if (!placed) skipped++
                lastTop = top
                bands++
                top += cell + extra
            }
        }
        // The spacing is added between lines: none below the last one.
        val height = if (bands == 0) 0f else (lastTop + cell).toFloat()
        return Bands(lines, height, bands, skipped, end)
    }

    /** A free stretch of a band, [x0]..[x1]. */
    private class Run(val x0: Float, val x1: Float) {
        val width: Float get() = x1 - x0
    }

    /** The run(s) of a band whose [blocked] parts are given, as [sides] picks them. */
    private fun runsOf(blocked: List<ClosedFloatingPointRange<Float>>, full: Float, sides: WrapSides, minRun: Float): List<Run> {
        // Covered parts inside the text area, sorted and merged.
        val cover = ArrayList<FloatArray>(blocked.size)
        for (b in blocked) {
            val a = b.start.coerceAtLeast(0f)
            val e = b.endInclusive.coerceAtMost(full)
            if (!(e > a)) continue
            cover += floatArrayOf(a, e)
        }
        if (cover.isEmpty()) return listOf(Run(0f, full))
        cover.sortBy { it[0] }
        val merged = ArrayList<FloatArray>()
        for (c in cover) {
            val last = merged.lastOrNull()
            if (last != null && c[0] <= last[1]) last[1] = maxOf(last[1], c[1]) else merged += c
        }
        val free = ArrayList<Run>()
        var x = 0f
        for (c in merged) {
            if (c[0] > x) free += Run(x, c[0])
            x = maxOf(x, c[1])
        }
        if (full > x) free += Run(x, full)
        val usable = free.filter { it.width >= minRun - EPS }
        return when (sides) {
            WrapSides.LARGEST -> usable.maxByOrNull { it.width }?.let { listOf(it) } ?: emptyList()
            WrapSides.BOTH -> usable
            WrapSides.LEFT -> {
                val left = Run(0f, merged.first()[0])
                if (left.width > 0f && left.width >= minRun - EPS) listOf(left) else emptyList()
            }
            WrapSides.RIGHT -> {
                val right = Run(merged.last()[1], full)
                if (right.width > 0f && right.width >= minRun - EPS) listOf(right) else emptyList()
            }
        }
    }

    /**
     * Where the line starting at [pos] ends in a run [maxW] wide (the next line's start, trailing
     * spaces included), or -1 when its first word must go to a wider run ([isFullWidth] false and
     * the word fits the full width [full]).
     */
    private fun fit(t: WrapText, brk: IntArray, pos: Int, pe: Int, maxW: Float, full: Float, isFullWidth: Boolean): Int {
        var i = firstAfter(brk, pos)
        var best = -1
        while (i < brk.size) {
            val b = brk[i]
            if (t.width(pos, trimEnd(t.text, pos, b)) <= maxW + EPS) {
                best = b
                i++
            } else {
                break
            }
        }
        if (best > pos) return best
        // Not even the first word fits.
        val first = if (firstAfter(brk, pos) < brk.size) brk[firstAfter(brk, pos)] else pe
        val wordW = t.width(pos, trimEnd(t.text, pos, first))
        if (!isFullWidth && wordW <= full + EPS) return -1
        return breakWord(t, pos, first, maxW)
    }

    /** Index of the first break position greater than [pos]. */
    private fun firstAfter(brk: IntArray, pos: Int): Int {
        var lo = 0
        var hi = brk.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (brk[mid] <= pos) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** A word too wide for any run, broken between characters: as many as fit (at least one). */
    private fun breakWord(t: WrapText, pos: Int, wordEnd: Int, maxW: Float): Int {
        val bi = BreakIterator.getCharacterInstance()
        bi.setText(t.text.substring(pos, wordEnd))
        var best = -1
        var b = bi.next()
        while (b != BreakIterator.DONE) {
            val e = pos + b
            if (best < 0 || t.width(pos, e) <= maxW + EPS) best = e else break
            b = bi.next()
        }
        return if (best > pos) best else minOf(wordEnd, pos + 1)
    }

    /** [end] moved back over the spaces that hang past the line's end. */
    private fun trimEnd(text: String, start: Int, end: Int): Int {
        var e = end
        while (e > start && isLineEndSpace(text[e - 1])) e--
        return e
    }

    /** Spaces that may hang past a line's end (minikin's rule). */
    fun isLineEndSpace(c: Char): Boolean {
        val u = c.code
        return u == 0x20 || u == 0x09 || u == 0x1680 || (u in 0x2000..0x200A && u != 0x2007) || u == 0x205F || u == 0x3000
    }

    private fun aligned(r: Run, w: Float, align: TextAlign): Float = when (align) {
        TextAlign.START -> r.x0
        TextAlign.CENTER -> r.x0 + (r.width - w) / 2f
        TextAlign.END -> r.x1 - w
    }
}
