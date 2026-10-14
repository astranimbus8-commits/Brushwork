package com.brushwork.paint.tools.text

import kotlin.math.min

/*
 * v1.9 justified text (R4): the lines [WrapLayout] broke, spread over their width. Pure Kotlin
 * (no android imports): only the measured advances ([WrapText]) and the lines are needed.
 *
 * Justification never changes a line break: it only moves glyphs to the right inside their line,
 * so measuring, the fixed-point passes of text wrapped around a picture and the overset of linked
 * frames are untouched. The rules (Illustrator's seven modes are `align` x [TextJustify]):
 * - [TextJustify.ON] spreads every line but the last of its paragraph (before a line break or at
 *   the end of the text; a frame's last line whose paragraph goes on in the next frame is not the
 *   last); the last line keeps `align`. [TextJustify.ALL] spreads the last line too.
 * - The free space goes into the line's word gaps: every space after its first letter (spaces
 *   that open a paragraph stay as they are, as an indent). A line without a space spreads between
 *   its grapheme clusters when it holds Chinese, Japanese or Korean letters (no word spaces), so
 *   an accent never leaves its letter. A one-word Latin line keeps `align`, except under
 *   [TextJustify.ALL], where its letters spread. There is no cap on a gap (D9).
 * - A line is spread to its run of the band (the text area, or its free part beside a picture);
 *   text without a fixed box (point text) to its widest line.
 * - Right-to-left paragraphs and scripts shaped as a whole (Arabic, the Indic scripts, Thai...)
 *   keep `align`: their glyphs can't be moved one piece at a time (as [LetterRamp.supports]).
 */

/**
 * Lines of a text after [Justify.apply]: [lines] with every justified line moved to the start of
 * its run, and per index i of the measured text (and its length) the extra advance [offsets]`[i]`
 * before character i on its line (0 at a line's start, then the stretched gaps before i; 0 on a
 * line that isn't justified), null when no line is. [spreadsClusters]: some line spreads between
 * grapheme clusters (no word spaces), which a run of text in an export can't carry.
 */
internal class Justified(val lines: List<WrapLine>, val offsets: FloatArray?, val spreadsClusters: Boolean) {
    /** What a block keeps of this for [TextExport] (null when no line is justified), [measured] being the text it was justified on. */
    fun forBlock(measured: WrapText): JustifiedLines? = offsets?.let { JustifiedLines(measured, it, spreadsClusters) }
}

/**
 * The justified lines of a [TextBlock] (v1.9, R4) as [TextExport] needs them: the [measured]
 * text (its advances; a frame's measured story tail, whose indices are the frame's own text's)
 * and the [offsets] of [Justified]. [spreadsClusters]: see [Justified].
 */
internal class JustifiedLines(val measured: WrapText, val offsets: FloatArray, val spreadsClusters: Boolean)

internal object Justify {
    /** Slack below this (px) leaves a line as it is (float sums of advances). */
    private const val EPS = 1e-3f

    /**
     * [lines] of [wt] justified as [justify] asks (see the file comment). [toWidest]: the text has
     * no fixed width (point text), so its lines spread to the widest one (and never past their
     * run). [TextJustify.OFF] returns [lines] as they are, with no offsets.
     *
     * One pass over the lines' characters; allocates the offsets (4 bytes per character) and a
     * copy of the line list only when a line is justified.
     */
    fun apply(wt: WrapText, lines: List<WrapLine>, justify: TextJustify, toWidest: Boolean): Justified {
        if (justify == TextJustify.OFF || lines.isEmpty()) return Justified(lines, null, false)
        val text = wt.text
        val n = text.length
        var widest = 0f
        if (toWidest) for (l in lines) if (l.width > widest) widest = l.width
        var offsets: FloatArray? = null
        var out: ArrayList<WrapLine>? = null
        var spreads = false
        // The paragraph of the last line looked at: [ps, pe), whether it can be spread, and its
        // grapheme cluster starts (found when a line of it spreads between clusters).
        var ps = 0
        var pe = -1
        var supported = false
        var starts: BooleanArray? = null
        var startsKnown = false
        for (k in lines.indices) {
            val l = lines[k]
            if (l.end <= l.start || l.end > n) continue
            if (justify == TextJustify.ON && l.lastOfParagraph) continue
            if (l.start < ps || l.start >= pe) {
                ps = text.lastIndexOf('\n', l.start - 1) + 1
                pe = text.indexOf('\n', l.start).let { if (it < 0) n else it }
                supported = supports(text, ps, pe)
                starts = null
                startsKnown = false
            }
            if (!supported) continue
            val target = if (toWidest) min(l.runWidth, widest) else l.runWidth
            val slack = target - l.width
            if (!(slack > EPS) || !slack.isFinite()) {
                // Already as wide as its target (point text's widest line, which `align` may have
                // put half a pixel in): flush with the spread lines at the run's start.
                if (toWidest && slack >= -EPS && l.x != l.runX) (out ?: ArrayList(lines).also { out = it })[k] = l.copy(x = l.runX)
                continue
            }
            var first = l.start
            while (first < l.end && isGap(text[first])) first++
            var gaps = 0
            for (i in first until l.end) if (isGap(text[i])) gaps++
            val byClusters = gaps == 0
            if (byClusters) {
                if (justify != TextJustify.ALL && !hasCjk(text, first, l.end)) continue
                if (!startsKnown) {
                    if (needsClusters(text, ps, pe)) starts = clusterStarts(text, ps, pe)
                    startsKnown = true
                }
                for (p in first + 1 until l.end) if (isBoundary(text, p, ps, starts)) gaps++
                if (gaps == 0) continue
            }
            val extra = slack / gaps
            val o = offsets ?: FloatArray(n + 1).also { offsets = it }
            var add = 0f
            for (i in l.start until l.end) {
                o[i] = add
                val gapAfter = if (byClusters) i + 1 > first && i + 1 < l.end && isBoundary(text, i + 1, ps, starts) else i >= first && isGap(text[i])
                if (gapAfter) add += extra
            }
            if (byClusters) spreads = true
            val list = out ?: ArrayList(lines).also { out = it }
            list[k] = l.copy(x = l.runX)
        }
        return Justified(out ?: lines, offsets, spreads)
    }

    /** [offsets]`[i]`, or 0 without offsets (no boxing: called per piece while drawing). */
    fun at(offsets: FloatArray?, i: Int): Float = if (offsets == null) 0f else offsets[i]

    /**
     * Whether the extra space of a justified line grows after character [i]: a piece boundary for
     * drawing (`offsets[i + 1] != offsets[i]`). False without offsets.
     */
    fun gapAfter(offsets: FloatArray?, i: Int): Boolean = offsets != null && offsets[i + 1] != offsets[i]

    /**
     * The pieces of line [l] of [wt] split after its stretched gaps ([offsets], see [Justified]):
     * `text[a, b)` drawn with its left end at x (text area coordinates). A line that isn't
     * justified is one piece. Nothing for an empty line. Allocates nothing.
     */
    inline fun forEachPiece(l: WrapLine, wt: WrapText, offsets: FloatArray?, piece: (Int, Int, Float) -> Unit) {
        if (l.end <= l.start) return
        var a = l.start
        if (offsets != null) {
            for (j in l.start until l.end - 1) {
                if (offsets[j + 1] != offsets[j]) {
                    piece(a, j + 1, l.x + wt.width(l.start, a) + offsets[a])
                    a = j + 1
                }
            }
        }
        piece(a, l.end, l.x + wt.width(l.start, a) + at(offsets, a))
    }

    /** A word gap: a space that may hang past a line's end, or a no-break space. */
    internal fun isGap(c: Char): Boolean = WrapLayout.isLineEndSpace(c) || c == ' '

    /**
     * Whether `text[a, b)` holds a Chinese, Japanese or Korean letter or a full-width form (text
     * written without word spaces): CJK radicals, symbols and punctuation, kana, bopomofo, Hangul,
     * the unified and compatibility ideographs (also beyond the BMP) and the half- and full-width
     * forms.
     */
    internal fun hasCjk(text: String, a: Int, b: Int): Boolean {
        var i = a
        while (i < b) {
            val cp = text.codePointAt(i)
            if (cp in 0x2E80..0x9FFF || cp in 0xAC00..0xD7AF || cp in 0xF900..0xFAFF || cp in 0xFF00..0xFFEF || cp in 0x20000..0x3FFFF) return true
            i += Character.charCount(cp)
        }
        return false
    }

    /** [LetterRamp.supports] of `text[ps, pe)` (plain ASCII without a copy). */
    private fun supports(text: String, ps: Int, pe: Int): Boolean {
        for (i in ps until pe) if (text[i].code >= 0x80) return LetterRamp.supports(text.substring(ps, pe))
        return true
    }

    /** Whether some boundary of `text[ps, pe)` needs the cluster rules (a character from U+0300 on). */
    private fun needsClusters(text: String, ps: Int, pe: Int): Boolean {
        for (i in ps until pe) if (text[i].code >= 0x300) return true
        return false
    }

    /** Per index of `text[ps, pe]` (relative to [ps]): whether a grapheme cluster starts there. */
    private fun clusterStarts(text: String, ps: Int, pe: Int): BooleanArray {
        val out = BooleanArray(pe - ps + 1)
        for (p in LetterRamp.clusterBounds(text.substring(ps, pe))) out[p] = true
        return out
    }

    /**
     * Whether a grapheme cluster starts at [p] of the paragraph starting at [ps] ([starts]: its
     * cluster starts, null when every character below U+0300 is a cluster of its own).
     */
    private fun isBoundary(text: String, p: Int, ps: Int, starts: BooleanArray?): Boolean {
        if (starts == null) return true
        val a = text[p - 1]
        val b = text[p]
        if (a.code < 0x300 && b.code < 0x300) return true
        return starts[p - ps]
    }
}
