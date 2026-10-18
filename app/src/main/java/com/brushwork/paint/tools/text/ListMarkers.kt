package com.brushwork.paint.tools.text

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.text.TextPaint
import kotlin.math.max

/**
 * v1.9 (R7) bullets and numbering of horizontal text ([TextSpec.list], one style per text):
 *
 * - Every paragraph with something to read (not empty, not only spaces) gets a marker: the bullet,
 *   or its number in the chosen style ([format]). Numbers count those paragraphs from
 *   [TextListSpec.start], per text: a threaded story counts over all its frames, and two texts of
 *   one layer never continue each other (C22).
 * - The markers stand in a column at the text area's left: every line of the text keeps an
 *   [ListPlan.indent] of the widest marker plus the gap ([TextListSpec.gapEm], in em of the text
 *   size), so wrapped lines align with the text above them; each marker ends the gap before its
 *   paragraph's first line, on its baseline (with "Left" or justified text, right-aligned in the
 *   column; centred or right-aligned text keeps the column too, its marker just before its
 *   first line's text).
 * - A marker is [TextListSpec.markerScale] of the paragraph's first letter's size, in the text's
 *   colour (and outline), in the text's font or its own ([TextListSpec.markerFont]);
 *   [TextListSpec.markerWeight] thickens it by up to an outline of [OUTLINE_EM] of its size.
 *
 * Vertical text and text on a path have no list ([applies]; `TextItem.sanitized` drops it).
 * Markers are measured once per layout ([plan], one string per paragraph) and placed with the
 * lines ([ListPlan.place]); drawing them allocates nothing.
 */
internal object ListMarkers {
    /** The outline a marker of thickness 1 gets, in em of the marker. */
    const val OUTLINE_EM = 0.08f

    /** The highest Roman number; past it, numbers fall back to "1." ([TextNumbering.DECIMAL_DOT]). */
    const val MAX_ROMAN = 3999

    /** The highest circled number (㊿); past it, numbers fall back to "1.". */
    const val MAX_CIRCLED = 50

    /** Whether text with [spec] draws a list: one is chosen and the text is horizontal. */
    fun applies(spec: TextSpec): Boolean = spec.list.isOn && !spec.vertical

    /**
     * The marker of number [n] in [style]: "1." "1)" "(1)", "a." "A." (a … z, then aa, ab …),
     * "i." "I." (1 to [MAX_ROMAN]) or ① (1 to [MAX_CIRCLED]); a number a style can't write (0 in
     * letters, Roman or circled; above their range) is written "n." instead.
     */
    fun format(style: TextNumbering, n: Int): String = when (style) {
        TextNumbering.DECIMAL_DOT -> "$n."
        TextNumbering.DECIMAL_PAREN -> "$n)"
        TextNumbering.DECIMAL_PARENS -> "($n)"
        TextNumbering.LOWER_ALPHA -> alpha(n, upper = false)?.let { "$it." }
        TextNumbering.UPPER_ALPHA -> alpha(n, upper = true)?.let { "$it." }
        TextNumbering.LOWER_ROMAN -> roman(n)?.let { "${it.lowercase()}." }
        TextNumbering.UPPER_ROMAN -> roman(n)?.let { "$it." }
        TextNumbering.CIRCLED -> circled(n)
    } ?: "$n."

    /** [n] in letters (bijective base 26: a … z, aa … az, ba …), null below 1. */
    fun alpha(n: Int, upper: Boolean): String? {
        if (n < 1) return null
        val base = if (upper) 'A' else 'a'
        val sb = StringBuilder()
        var v = n
        while (v > 0) {
            v--
            sb.append(base + v % 26)
            v /= 26
        }
        return sb.reverse().toString()
    }

    private val ROMAN_VALUES = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
    private val ROMAN_DIGITS = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")

    /** [n] in upper-case Roman numerals, null outside 1..[MAX_ROMAN]. */
    fun roman(n: Int): String? {
        if (n < 1 || n > MAX_ROMAN) return null
        val sb = StringBuilder()
        var v = n
        for (k in ROMAN_VALUES.indices) {
            while (v >= ROMAN_VALUES[k]) {
                sb.append(ROMAN_DIGITS[k])
                v -= ROMAN_VALUES[k]
            }
        }
        return sb.toString()
    }

    /** [n] circled (① … ⑳, ㉑ … ㉟, ㊱ … ㊿), null outside 1..[MAX_CIRCLED]. */
    fun circled(n: Int): String? = when (n) {
        in 1..20 -> String(Character.toChars(0x2460 + n - 1))
        in 21..35 -> String(Character.toChars(0x3251 + n - 21))
        in 36..MAX_CIRCLED -> String(Character.toChars(0x32B1 + n - 36))
        else -> null
    }

    /**
     * Per paragraph of [source] (split at line breaks, in order): its marker, or null for a
     * paragraph with nothing to read (empty or only spaces), which is neither marked nor counted.
     * Null array entries everywhere when [list] is off.
     */
    fun texts(list: TextListSpec, source: String): Array<String?> {
        val starts = paragraphStarts(source)
        val out = arrayOfNulls<String>(starts.size)
        if (!list.isOn) return out
        var n = list.start
        for (p in starts.indices) {
            val ps = starts[p]
            val pe = if (p + 1 < starts.size) starts[p + 1] - 1 else source.length
            if (isBlank(source, ps, pe)) continue
            out[p] = if (list.type == TextListType.BULLETS) list.bullet else format(list.numbering, n++)
        }
        return out
    }

    /** The start of each paragraph of [source] (0 first; one more after each line break). */
    fun paragraphStarts(source: String): IntArray {
        var count = 1
        for (c in source) if (c == '\n') count++
        val out = IntArray(count)
        var k = 1
        for (i in source.indices) if (source[i] == '\n') out[k++] = i + 1
        return out
    }

    private fun isBlank(s: String, a: Int, b: Int): Boolean {
        for (i in a until b) if (!s[i].isWhitespace()) return false
        return true
    }

    /**
     * [spec]'s list measured for [source] (the text, or a threaded story), or null when it has
     * none ([applies]) or no paragraph is marked. [ramp] (scaled letters over [source]) sizes each
     * marker with its paragraph's first letter.
     */
    fun plan(spec: TextSpec, source: String, ramp: LetterRampResult?): ListPlan? {
        if (!applies(spec) || source.isEmpty()) return null
        val list = spec.list
        val texts = texts(list, source)
        if (texts.all { it == null }) return null
        val starts = paragraphStarts(source)
        val face = if (list.sameFontAsText) TextRenderer.typeface(spec) else TextRenderer.typeface(markerFontSpec(spec))
        val paint = TextPaint().apply {
            isAntiAlias = spec.antiAlias
            isSubpixelText = spec.antiAlias
            typeface = face
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        val sizes = FloatArray(texts.size)
        val widths = FloatArray(texts.size)
        var widest = 0f
        for (p in texts.indices) {
            val t = texts[p] ?: continue
            val f = ramp?.factors?.getOrNull(starts[p])?.takeIf { it.isFinite() && it > 0f } ?: 1f
            sizes[p] = list.markerScale * spec.sizePx * f
            paint.textSize = sizes[p]
            widths[p] = paint.measureText(t)
            widest = max(widest, widths[p])
        }
        val gap = list.gapEm * spec.sizePx
        return ListPlan(source, starts, texts, sizes, widths, widest + gap, gap, list.markerWeight, paint)
    }

    /** [spec] with the list's marker font (its family, imported font and name) for the letters. */
    fun markerFontSpec(spec: TextSpec): TextSpec {
        val l = spec.list
        if (l.sameFontAsText) return spec
        return spec.copy(font = l.markerFont ?: spec.font, fontId = l.markerFontId, fontName = l.markerFontName)
    }
}

/**
 * A list measured for a text ([ListMarkers.plan]): per paragraph of its [source] its marker, size
 * and width, and the [indent] every line keeps (the widest marker plus the [gap]).
 */
internal class ListPlan internal constructor(
    private val source: String,
    private val starts: IntArray,
    private val texts: Array<String?>,
    private val sizes: FloatArray,
    private val widths: FloatArray,
    val indent: Float,
    val gap: Float,
    private val weight: Float,
    private val paint: TextPaint,
) {
    /**
     * The markers of [lines] (text area coordinates) whose characters are `source[offset + start,
     * offset + end)` (a frame's lines index its slice of the story): one before each line that
     * opens a marked paragraph (a frame whose first line goes on with a paragraph has none there),
     * ending [gap] before the line's text, on its baseline. Null when no line opens one.
     */
    fun place(lines: List<WrapLine>, offset: Int): ListMarkerLayout? {
        var out: ArrayList<ListMarkerLayout.Marker>? = null
        for (l in lines) {
            val s = offset + l.start
            if (s < 0 || s > source.length || (s > 0 && source[s - 1] != '\n')) continue
            val p = paragraphOf(s)
            val t = texts[p] ?: continue
            val m = ListMarkerLayout.Marker(t, l.x - gap - widths[p], l.baseline, sizes[p])
            (out ?: ArrayList<ListMarkerLayout.Marker>().also { out = it }) += m
        }
        return out?.let { ListMarkerLayout(it, weight, TextPaint(paint)) }
    }

    /** The paragraph of [source] that starts at or before [s] (the last one). */
    private fun paragraphOf(s: Int): Int {
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= s) lo = mid else hi = mid - 1
        }
        return lo
    }
}

/**
 * A text's markers placed with its lines (text area coordinates): drawn before the letters with
 * the paint the letters get (its colour, its outline pass and fill pass), each at its own size in
 * the markers' font, thickened by the list's weight ([ListMarkers.OUTLINE_EM]). Not thread-safe:
 * draw on the thread that made it (as [TextBlock]).
 */
internal class ListMarkerLayout internal constructor(
    val markers: List<Marker>,
    private val weight: Float,
    private val paint: TextPaint,
) {
    /** One marker: [text] with its left end at ([x], [baseline]), [sizePx] tall (its font size). */
    class Marker(val text: String, val x: Float, val baseline: Float, val sizePx: Float)

    /** The markers' typeface. */
    internal val typeface get() = paint.typeface

    /** The outline thickness 1 adds to a marker of [size], as a stroke width (both sides of the edge). */
    private fun thickening(size: Float): Float = 2f * ListMarkers.OUTLINE_EM * weight * size

    /**
     * Draws the markers with [p]'s colour and style: its outline pass (the text's outline, widened
     * by the thickening) and its fill pass (filled and, with a weight, stroked in the same colour).
     */
    fun draw(c: Canvas, p: TextPaint) {
        val m = paint
        m.color = p.color
        m.colorFilter = p.colorFilter
        m.isAntiAlias = p.isAntiAlias
        for (k in markers) {
            m.textSize = k.sizePx
            val thick = if (weight > 0f) thickening(k.sizePx) else 0f
            if (p.style == Paint.Style.STROKE) {
                m.style = Paint.Style.STROKE
                m.strokeWidth = p.strokeWidth + thick
            } else if (thick > 0f) {
                m.style = Paint.Style.FILL_AND_STROKE
                m.strokeWidth = thick
            } else {
                m.style = Paint.Style.FILL
            }
            c.drawText(k.text, k.x, k.baseline, m)
        }
        m.style = Paint.Style.FILL
    }

    /** Adds the markers' filled outlines (thickened as drawn) to [out]. */
    fun outline(out: Path) {
        val m = TextPaint(paint).apply { style = Paint.Style.FILL }
        val tmp = Path()
        for (k in markers) {
            m.textSize = k.sizePx
            tmp.rewind()
            m.getTextPath(k.text, 0, k.text.length, k.x, k.baseline, tmp)
            val thick = if (weight > 0f) thickening(k.sizePx) else 0f
            if (thick > 0f) {
                val s = Paint(m).apply {
                    style = Paint.Style.FILL_AND_STROKE
                    strokeWidth = thick
                }
                val thickPath = Path()
                s.getFillPath(tmp, thickPath)
                out.addPath(thickPath)
            } else {
                out.addPath(tmp)
            }
        }
    }

    /** How far the markers' ink reaches outside (0, 0) - ([w], [h]) (0 when it doesn't). */
    fun overflow(w: Float, h: Float): Float {
        val m = TextPaint(paint)
        val r = Rect()
        var over = 0f
        for (k in markers) {
            m.textSize = k.sizePx
            m.getTextBounds(k.text, 0, k.text.length, r)
            if (r.isEmpty) continue
            val t = thickening(k.sizePx) / 2f
            over = max(over, max(max(-(k.x + r.left - t), k.x + r.right + t - w), max(-(k.baseline + r.top - t), k.baseline + r.bottom + t - h)))
        }
        return max(0f, over)
    }
}
