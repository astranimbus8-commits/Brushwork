package com.brushwork.paint.tools.text

import java.text.BreakIterator
import kotlin.math.max

/** How one character cell is drawn in vertical text. */
enum class VerticalGlyphKind {
    /** Drawn upright, centered in a 1 em cell (CJK ideographs, kana, full-width forms, emoji; everything in upright style). */
    UPRIGHT,
    /** Rotated 90° clockwise, advancing by its horizontal width (Latin in mixed style, long vowel mark, dashes, brackets). */
    ROTATED,
    /** Ideographic comma / full stop: upright but moved to the top-right of the cell. */
    PUNCTUATION,
    /** Small kana: upright, nudged slightly up and right. */
    SMALL_KANA,
    /** Tate-chu-yoko: a short horizontal run ("12", "!?") squeezed into one upright cell (mixed style). */
    TATE_CHU_YOKO,
}

/** One positioned cell of vertical text; ([cx], [cy]) is the cell center in block coordinates. */
data class VerticalGlyph(
    val text: String,
    val kind: VerticalGlyphKind,
    val cx: Float,
    val cy: Float,
    /** Vertical extent of the cell. */
    val advance: Float,
    val column: Int,
)

/** Result of [VerticalTextLayout.layout]; the block spans (0, 0) - ([width], [height]). */
class VerticalLayoutResult(val glyphs: List<VerticalGlyph>, val width: Float, val height: Float, val columns: Int)

/**
 * Vertical layout: each line of the text becomes a column (more when it wraps at a fixed
 * height), characters run top to bottom and columns run right to left (or left to right).
 * Pure Kotlin; glyph widths come from a measuring lambda so the math is testable without Android.
 */
object VerticalTextLayout {

    /** Offsets (in em) applied to [VerticalGlyphKind.PUNCTUATION] and [VerticalGlyphKind.SMALL_KANA] cells. */
    const val PUNCTUATION_SHIFT = 0.55f
    const val SMALL_KANA_SHIFT = 0.1f

    private val PUNCTUATION = intArrayOf(0x3001, 0x3002, 0xFF0C, 0xFF0E, 0xFF61, 0xFF64).toSet()

    private val SMALL_KANA = (
        "ぁぃぅぇぉっゃゅょゎゕゖ" + "ァィゥェォッャュョヮヵヶ" + "ｧｨｩｪｫｬｭｮｯ"
        ).codePoints().toArray().toSet() + (0x31F0..0x31FF)

    /**
     * Japanese vertical forms: characters that are turned in vertical text in BOTH styles
     * because they have no upright meaning (a long vowel mark or a bracket drawn upright would
     * be wrong in a vertical column).
     */
    private val VERTICAL_FORMS: Set<Int> = buildSet {
        addAll(listOf(0x30FC, 0xFF70, 0x301C, 0xFF5E, 0x3030, 0x2026, 0x2025, 0x2014, 0x2015))
        addAll(0x3008..0x3011) // 〈〉《》「」『』【】
        addAll(0x3014..0x301B) // 〔〕〖〗〘〙〚〛
        addAll(listOf(0xFF08, 0xFF09, 0xFF3B, 0xFF3D, 0xFF5B, 0xFF5D, 0xFF5F, 0xFF60)) // （）［］｛｝｟｠
        addAll(listOf(0xFF1A, 0xFF1B, 0xFF1C, 0xFF1D, 0xFF1E, 0xFF0D, 0xFF3F)) // ：；＜＝＞－＿
    }

    /** Latin dashes that mixed style turns sideways too (upright style keeps them upright). */
    private val LATIN_DASHES = setOf(0x2013, 0x2010)

    private fun isUprightBlock(cp: Int): Boolean = when (cp) {
        '!'.code, '?'.code -> true // manga lettering keeps ! and ? upright
        in 0x1100..0x11FF, // Hangul Jamo
        in 0x2E80..0x2FDF, // CJK radicals
        in 0x2FF0..0x303F, // ideographic description, CJK symbols & punctuation
        in 0x3040..0x30FF, // hiragana, katakana
        in 0x3100..0x31FF, // bopomofo, Hangul compatibility, kanbun, katakana ext.
        in 0x3200..0x33FF, // enclosed CJK, CJK compatibility
        in 0x3400..0x4DBF, // CJK ext A
        in 0x4E00..0x9FFF, // CJK unified ideographs
        in 0xA960..0xA97F,
        in 0xAC00..0xD7FF, // Hangul syllables
        in 0xF900..0xFAFF, // CJK compatibility ideographs
        in 0xFE10..0xFE1F, // vertical forms
        in 0xFE30..0xFE4F, // CJK compatibility forms
        in 0xFF01..0xFF60, // full-width forms
        in 0xFF61..0xFFDC, // half-width katakana / Hangul
        in 0xFFE0..0xFFE6, // full-width signs
        in 0x2600..0x27BF, // symbols, dingbats (☆♪♡✓)
        in 0x2B00..0x2BFF,
        in 0x1F000..0x1FAFF, // emoji and pictographs
        in 0x20000..0x3FFFF -> true // CJK ext B+
        else -> false
    }

    /** Orientation of a single grapheme cluster in [style] (tate-chu-yoko is decided by [clusters]). */
    fun kindOf(cluster: String, style: VerticalStyle = VerticalStyle.MIXED): VerticalGlyphKind {
        if (cluster.isEmpty()) return VerticalGlyphKind.UPRIGHT
        val cp = cluster.codePointAt(0)
        return when {
            cp in PUNCTUATION -> VerticalGlyphKind.PUNCTUATION
            cp in SMALL_KANA -> VerticalGlyphKind.SMALL_KANA
            cp in VERTICAL_FORMS -> VerticalGlyphKind.ROTATED
            style == VerticalStyle.UPRIGHT -> VerticalGlyphKind.UPRIGHT
            cp in LATIN_DASHES -> VerticalGlyphKind.ROTATED
            isUprightBlock(cp) -> VerticalGlyphKind.UPRIGHT
            else -> VerticalGlyphKind.ROTATED
        }
    }

    /** A grapheme cluster with its orientation. */
    data class Cluster(val text: String, val kind: VerticalGlyphKind)

    private fun isAsciiDigit(s: String) = s.length == 1 && s[0] in '0'..'9'
    private fun isBang(s: String) = s == "!" || s == "?"
    private fun isSpace(s: String) = s.isNotEmpty() && s.all { it.isWhitespace() }

    private fun graphemes(line: String): List<String> {
        val raw = ArrayList<String>()
        val it = BreakIterator.getCharacterInstance()
        it.setText(line)
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            raw += line.substring(start, end)
            start = end
            end = it.next()
        }
        return raw
    }

    /**
     * Splits one line into grapheme clusters and orientations. In mixed style, runs of one or
     * two ASCII digits and pairs like "!!" / "!?" become a single tate-chu-yoko cell; in upright
     * style every cluster is its own upright cell.
     */
    fun clusters(line: String, style: VerticalStyle = VerticalStyle.MIXED): List<Cluster> {
        val raw = graphemes(line)
        if (style == VerticalStyle.UPRIGHT) return raw.map { Cluster(it, kindOf(it, style)) }
        val out = ArrayList<Cluster>(raw.size)
        var i = 0
        while (i < raw.size) {
            val s = raw[i]
            if (isAsciiDigit(s) || isBang(s)) {
                val digits = isAsciiDigit(s)
                var j = i
                while (j < raw.size && (if (digits) isAsciiDigit(raw[j]) else isBang(raw[j]))) j++
                val run = j - i
                when {
                    digits && run <= 2 -> out += Cluster(raw.subList(i, j).joinToString(""), VerticalGlyphKind.TATE_CHU_YOKO)
                    !digits && run == 2 -> out += Cluster(raw[i] + raw[i + 1], VerticalGlyphKind.TATE_CHU_YOKO)
                    else -> for (k in i until j) out += Cluster(raw[k], kindOf(raw[k], style))
                }
                i = j
            } else {
                out += Cluster(s, kindOf(s, style))
                i++
            }
        }
        return out
    }

    /** One laid-out column: clusters [from] until [to] of a line. */
    private class Column(val cs: List<Cluster>, val adv: FloatArray, val from: Int, val to: Int, val length: Float)

    private fun lengthOf(adv: FloatArray, from: Int, to: Int, spacing: Float): Float {
        var len = 0f
        for (i in from until to) len += adv[i] + (if (i > from) spacing else 0f)
        return max(0f, len)
    }

    /**
     * Splits one line into columns no longer than [wrap] (0 = no wrapping). Breaks after the last
     * space of a column when the next cell starts a word (word wrap), else between any two cells;
     * spaces at a wrap point are dropped. Every column holds at least one cell.
     */
    private fun columnsOf(cs: List<Cluster>, adv: FloatArray, spacing: Float, wrap: Float): List<Column> {
        val n = cs.size
        if (wrap <= 0f || n == 0) return listOf(Column(cs, adv, 0, n, lengthOf(adv, 0, n, spacing)))
        val out = ArrayList<Column>()
        var start = 0
        while (start < n) {
            // A wrapped column never starts with the spaces at the break.
            if (start > 0 && out.isNotEmpty() && isSpace(cs[start].text)) { start++; continue }
            var len = 0f
            var i = start
            var lastSpace = -1
            while (i < n) {
                val add = adv[i] + (if (i > start) spacing else 0f)
                if (i > start && len + add > wrap + 1e-3f) break
                len += add
                if (isSpace(cs[i].text)) lastSpace = i
                i++
            }
            var end = i
            if (end < n && lastSpace > start && !isSpace(cs[end].text)) end = lastSpace + 1
            // Trailing spaces of a wrapped column are invisible: don't let them shift the alignment.
            var visibleEnd = end
            if (end < n) while (visibleEnd > start + 1 && isSpace(cs[visibleEnd - 1].text)) visibleEnd--
            out += Column(cs, adv, start, visibleEnd, lengthOf(adv, start, visibleEnd, spacing))
            start = end
        }
        return out
    }

    /**
     * Lays out [text] vertically. [em] is the font size, [letterSpacingEm] extra space between
     * cells (in em), [lineSpacing] the column pitch in em. [rotatedAdvance] returns the horizontal
     * width of a cluster (the vertical advance of a rotated cell). [style] picks the character
     * orientation, [wrapLength] (> 0) the height at which columns wrap, [leftToRight] makes
     * columns run left to right. Alignment is within the block height (the fixed wrap height, or
     * the tallest column).
     */
    fun layout(
        text: String,
        em: Float,
        letterSpacingEm: Float,
        lineSpacing: Float,
        align: TextAlign,
        rotatedAdvance: (String) -> Float,
        style: VerticalStyle = VerticalStyle.MIXED,
        wrapLength: Float = 0f,
        leftToRight: Boolean = false,
    ): VerticalLayoutResult {
        val lines = text.split('\n')
        val pitch = em * lineSpacing
        val spacing = letterSpacingEm * em
        val wrap = if (wrapLength.isFinite() && wrapLength > 0f) wrapLength else 0f
        val columns = ArrayList<Column>()
        for (line in lines) {
            val cs = clusters(line.trimEnd('\r'), style)
            val adv = FloatArray(cs.size) { i -> if (cs[i].kind == VerticalGlyphKind.ROTATED) max(0f, rotatedAdvance(cs[i].text)) else em }
            columns += columnsOf(cs, adv, spacing, wrap)
        }
        val n = columns.size
        val width = em + (n - 1) * pitch
        val tallest = columns.maxOf { it.length }
        val height = if (wrap > 0f) max(wrap, tallest) else tallest
        val glyphs = ArrayList<VerticalGlyph>()
        columns.forEachIndexed { col, c ->
            val cx = if (leftToRight) em / 2f + col * pitch else width - em / 2f - col * pitch
            var y = when (align) {
                TextAlign.START -> 0f
                TextAlign.CENTER -> (height - c.length) / 2f
                TextAlign.END -> height - c.length
            }
            for (i in c.from until c.to) {
                if (i > c.from) y += spacing
                glyphs += VerticalGlyph(c.cs[i].text, c.cs[i].kind, cx, y + c.adv[i] / 2f, c.adv[i], col)
                y += c.adv[i]
            }
        }
        return VerticalLayoutResult(glyphs, width, height, n)
    }
}
