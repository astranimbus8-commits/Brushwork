package com.brushwork.paint.tools.text

import java.text.BreakIterator
import kotlin.math.max

/** How one character cell is drawn in vertical text. */
enum class VerticalGlyphKind {
    /** Drawn upright, centered in a 1 em cell (CJK ideographs, kana, full-width forms, emoji). */
    UPRIGHT,
    /** Rotated 90° clockwise, advancing by its horizontal width (Latin, long vowel mark, dashes, brackets). */
    ROTATED,
    /** Ideographic comma / full stop: upright but moved to the top-right of the cell. */
    PUNCTUATION,
    /** Small kana: upright, nudged slightly up and right. */
    SMALL_KANA,
    /** Tate-chu-yoko: a short horizontal run ("12", "!?") squeezed into one upright cell. */
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
 * Manga-style vertical layout: each line of the text becomes a column, characters run top to
 * bottom and columns run right to left. Pure Kotlin; glyph widths come from a measuring lambda so
 * the math is testable without Android.
 */
object VerticalTextLayout {

    /** Offsets (in em) applied to [VerticalGlyphKind.PUNCTUATION] and [VerticalGlyphKind.SMALL_KANA] cells. */
    const val PUNCTUATION_SHIFT = 0.55f
    const val SMALL_KANA_SHIFT = 0.1f

    private val PUNCTUATION = intArrayOf(0x3001, 0x3002, 0xFF0C, 0xFF0E, 0xFF61, 0xFF64).toSet()

    private val SMALL_KANA = (
        "ぁぃぅぇぉっゃゅょゎゕゖ" + "ァィゥェォッャュョヮヵヶ" + "ｧｨｩｪｫｬｭｮｯ"
        ).codePoints().toArray().toSet() + (0x31F0..0x31FF)

    /** Characters that are rotated even though they live in otherwise-upright blocks. */
    private val FORCE_ROTATE: Set<Int> = buildSet {
        addAll(listOf(0x30FC, 0xFF70, 0x301C, 0xFF5E, 0x3030, 0x2026, 0x2025, 0x2014, 0x2015, 0x2013, 0x2010))
        addAll(0x3008..0x3011) // 〈〉《》「」『』【】
        addAll(0x3014..0x301B) // 〔〕〖〗〘〙〚〛
        addAll(listOf(0xFF08, 0xFF09, 0xFF3B, 0xFF3D, 0xFF5B, 0xFF5D, 0xFF5F, 0xFF60)) // （）［］｛｝｟｠
        addAll(listOf(0xFF1A, 0xFF1B, 0xFF1C, 0xFF1D, 0xFF1E, 0xFF0D, 0xFF3F)) // ：；＜＝＞－＿
    }

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

    /** Orientation of a single grapheme cluster (tate-chu-yoko is decided by [clusters]). */
    fun kindOf(cluster: String): VerticalGlyphKind {
        if (cluster.isEmpty()) return VerticalGlyphKind.UPRIGHT
        val cp = cluster.codePointAt(0)
        return when {
            cp in PUNCTUATION -> VerticalGlyphKind.PUNCTUATION
            cp in SMALL_KANA -> VerticalGlyphKind.SMALL_KANA
            cp in FORCE_ROTATE -> VerticalGlyphKind.ROTATED
            isUprightBlock(cp) -> VerticalGlyphKind.UPRIGHT
            else -> VerticalGlyphKind.ROTATED
        }
    }

    /** A grapheme cluster with its orientation. */
    data class Cluster(val text: String, val kind: VerticalGlyphKind)

    private fun isAsciiDigit(s: String) = s.length == 1 && s[0] in '0'..'9'
    private fun isBang(s: String) = s == "!" || s == "?"

    /**
     * Splits one line into grapheme clusters and orientations. Runs of one or two ASCII digits and
     * pairs like "!!" / "!?" become a single tate-chu-yoko cell.
     */
    fun clusters(line: String): List<Cluster> {
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
                    else -> for (k in i until j) out += Cluster(raw[k], kindOf(raw[k]))
                }
                i = j
            } else {
                out += Cluster(s, kindOf(s))
                i++
            }
        }
        return out
    }

    /**
     * Lays out [text] vertically. [em] is the font size, [letterSpacingEm] extra space between
     * cells (in em), [lineSpacing] the column pitch in em. [rotatedAdvance] returns the horizontal
     * width of a cluster (the vertical advance of a rotated cell).
     */
    fun layout(
        text: String,
        em: Float,
        letterSpacingEm: Float,
        lineSpacing: Float,
        align: TextAlign,
        rotatedAdvance: (String) -> Float,
    ): VerticalLayoutResult {
        val lines = text.split('\n')
        val pitch = em * lineSpacing
        val spacing = letterSpacingEm * em
        val columns = lines.map { line ->
            val cs = clusters(line)
            val adv = FloatArray(cs.size) { i -> if (cs[i].kind == VerticalGlyphKind.ROTATED) max(0f, rotatedAdvance(cs[i].text)) else em }
            var len = 0f
            for (i in adv.indices) len += adv[i] + (if (i > 0) spacing else 0f)
            Triple(cs, adv, max(0f, len))
        }
        val n = columns.size
        val width = em + (n - 1) * pitch
        val height = columns.maxOf { it.third }
        val glyphs = ArrayList<VerticalGlyph>()
        columns.forEachIndexed { col, (cs, adv, len) ->
            val cx = width - em / 2f - col * pitch
            var y = when (align) {
                TextAlign.START -> 0f
                TextAlign.CENTER -> (height - len) / 2f
                TextAlign.END -> height - len
            }
            for (i in cs.indices) {
                if (i > 0) y += spacing
                glyphs += VerticalGlyph(cs[i].text, cs[i].kind, cx, y + adv[i] / 2f, adv[i], col)
                y += adv[i]
            }
        }
        return VerticalLayoutResult(glyphs, width, height, n)
    }
}
