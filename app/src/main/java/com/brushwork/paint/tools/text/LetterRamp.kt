package com.brushwork.paint.tools.text

import java.text.BreakIterator
import java.text.Bidi

/*
 * Progressive letter scaling (v1.6 §3.5): which size every character of a text is drawn at.
 * Pure Kotlin (no android imports), unit-tested on the JVM.
 *
 * The letters are the non-whitespace grapheme clusters of the text (a letter with its accents,
 * an emoji sequence...), numbered k = 0 … n−1 within the scope (the whole text, or each paragraph
 * on its own); letter k gets `LetterScaleSpec.factor(k, n)`. Whitespace takes the factor of the
 * letter before it (the first letter's when it leads) and uses up no step, so the space between
 * "ELTON" and "JOHN" is no smaller letter of its own (V2).
 */

/**
 * The size factors of one text: [factors] per character (every character of a cluster has its
 * cluster's factor), [bounds] the grapheme cluster boundaries (0 first, the text's length last)
 * and [blank] per cluster (whitespace: nothing to draw).
 */
class LetterRampResult internal constructor(
    val text: String,
    val spec: LetterScaleSpec,
    val factors: FloatArray,
    val bounds: IntArray,
    val blank: BooleanArray,
) {
    /** Number of letters (non-whitespace clusters) of the whole text. */
    val letterCount: Int get() = blank.count { !it }

    /** Index of the first cluster whose start is ≥ [pos] (clusters.size when none). */
    fun clusterAtOrAfter(pos: Int): Int {
        var lo = 0
        var hi = bounds.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (bounds[mid] < pos) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

object LetterRamp {

    /**
     * Whether [text]'s letters can be drawn one cluster at a time: false for right-to-left text
     * (`Bidi.requiresBidi`) and for scripts whose letters are shaped together (Arabic, Hebrew,
     * the Indic scripts, Thai, Lao, Tibetan, Myanmar, Khmer, Mongolian and other complex scripts):
     * drawing them per cluster would break their joining, so such text is drawn unscaled.
     */
    fun supports(text: String): Boolean {
        if (text.isEmpty()) return true
        var i = 0
        var ascii = true
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (cp >= 0x80) ascii = false
            if (isComplex(cp)) return false
            i += Character.charCount(cp)
        }
        if (ascii) return true
        val chars = text.toCharArray()
        return !Bidi.requiresBidi(chars, 0, chars.size)
    }

    /** Code points of scripts whose letters are shaped together (or written right to left). */
    internal fun isComplex(cp: Int): Boolean = when (cp) {
        in 0x0590..0x08FF, // Hebrew, Arabic, Syriac, Thaana, NKo, Samaritan, Mandaic, Arabic extended
        in 0x0900..0x0DFF, // Devanagari … Sinhala
        in 0x0E00..0x0FFF, // Thai, Lao, Tibetan
        in 0x1000..0x109F, // Myanmar
        in 0x1700..0x177F, // Tagalog, Hanunoo, Buhid, Tagbanwa
        in 0x1780..0x18AF, // Khmer, Mongolian
        in 0x1900..0x1AAF, // Limbu, Tai Le, New Tai Lue, Khmer symbols, Buginese, Tai Tham
        in 0x1B00..0x1C4F, // Balinese, Sundanese, Batak, Lepcha
        in 0xA800..0xA82F, // Syloti Nagri
        in 0xA840..0xA8DF, // Phags-pa, Saurashtra
        in 0xA8E0..0xA8FF, // Devanagari extended
        in 0xA900..0xA9FF, // Kayah Li, Rejang, Javanese, Myanmar extended-B
        in 0xAA00..0xAAFF, // Cham, Myanmar extended-A, Tai Viet, Meetei Mayek extensions
        in 0xABC0..0xABFF, // Meetei Mayek
        in 0xFB1D..0xFDFF, // Hebrew and Arabic presentation forms A
        in 0xFE70..0xFEFF, // Arabic presentation forms B
        in 0x10800..0x10FFF, // right-to-left historic scripts
        in 0x11000..0x11FFF, // Brahmi, Kaithi, Chakma, Sharada, Takri and other Brahmic scripts
        in 0x1E800..0x1EFFF, // Mende Kikakui, Adlam, Arabic mathematical symbols
        -> true
        else -> false
    }

    /**
     * Grapheme cluster boundaries of [text] (0 first, the length last). `java.text.BreakIterator`
     * plus the emoji rules of extended grapheme clusters that older JDKs miss (the platform's ICU
     * iterator already follows them, so on a device the fix-up changes nothing): ZWJ sequences,
     * variation selectors, skin-tone modifiers, keycaps, tag sequences and flag pairs stay one
     * cluster.
     */
    fun clusterBounds(text: String): IntArray {
        val n = text.length
        if (n == 0) return intArrayOf(0)
        val bi = BreakIterator.getCharacterInstance()
        bi.setText(text)
        val raw = ArrayList<Int>()
        var b = bi.first()
        while (b != BreakIterator.DONE) {
            raw += b
            b = bi.next()
        }
        if (raw.isEmpty() || raw.first() != 0) raw.add(0, 0)
        if (raw.last() != n) raw += n
        val out = IntArray(raw.size)
        var m = 0
        for ((idx, p) in raw.withIndex()) {
            if (idx == 0 || p == n) { out[m++] = p; continue }
            if (joins(text, p)) continue
            out[m++] = p
        }
        // Regional indicator pairs (flags): re-done on the merged boundaries.
        return pairFlags(text, out.copyOf(m))
    }

    private fun isVariationSelector(cp: Int) = cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF
    private fun isModifier(cp: Int) = cp in 0x1F3FB..0x1F3FF
    private fun isTag(cp: Int) = cp in 0xE0020..0xE007F
    private fun isRegional(cp: Int) = cp in 0x1F1E6..0x1F1FF
    private fun isPictographic(cp: Int) =
        cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2190..0x21FF || cp in 0x2B00..0x2BFF || cp in 0x2300..0x23FF || cp == 0x00A9 || cp == 0x00AE

    /** Whether the boundary at [p] lies inside an emoji sequence (it is no boundary). */
    private fun joins(text: String, p: Int): Boolean {
        if (text[p] == '\n' || text[p - 1] == '\n') return false
        val next = text.codePointAt(p)
        val prev = text.codePointBefore(p)
        if (next == 0x200D || isVariationSelector(next) || isModifier(next) || next == 0x20E3 || isTag(next)) return true
        val type = Character.getType(next)
        if (type == Character.NON_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt()) return true
        if (prev == 0x200D && isPictographic(next)) return true
        return false
    }

    /** Merges consecutive single regional indicators into pairs (one flag each). */
    private fun pairFlags(text: String, bounds: IntArray): IntArray {
        var any = false
        for (i in 0 until bounds.size - 1) if (isRegional(text.codePointAt(bounds[i]))) { any = true; break }
        if (!any) return bounds
        val out = ArrayList<Int>(bounds.size)
        var i = 0
        while (i < bounds.size - 1) {
            out += bounds[i]
            val s = bounds[i]
            val e = bounds[i + 1]
            val single = isRegional(text.codePointAt(s)) && e - s == 2
            if (single && i + 2 < bounds.size) {
                val s2 = bounds[i + 1]
                val e2 = bounds[i + 2]
                if (isRegional(text.codePointAt(s2)) && e2 - s2 == 2) {
                    i += 2
                    continue
                }
            }
            i++
        }
        out += bounds.last()
        return out.toIntArray()
    }

    /** Whitespace (and invisible format characters) only: no letter of its own. */
    private fun isBlank(text: String, s: Int, e: Int): Boolean {
        var i = s
        while (i < e) {
            val c = text[i]
            if (!(Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.getType(c) == Character.FORMAT.toInt())) return false
            i++
        }
        return true
    }

    /**
     * The factors of [text] for [spec] (see the file comment). With [spec] off every factor is 1.
     * Linear in the text length.
     */
    fun compute(text: String, spec: LetterScaleSpec): LetterRampResult {
        val n = text.length
        val factors = FloatArray(n) { 1f }
        val bounds = clusterBounds(text)
        val clusters = bounds.size - 1
        val blank = BooleanArray(clusters) { isBlank(text, bounds[it], bounds[it + 1]) }
        if (!spec.isOn || n == 0) return LetterRampResult(text, spec, factors, bounds, blank)
        val perParagraph = spec.scope == LetterScaleScope.EACH_PARAGRAPH
        var c0 = 0
        while (c0 < clusters) {
            // The scope: up to and including the cluster that holds a line break (each paragraph),
            // or every cluster (the whole text).
            var c1 = c0
            var letters = 0
            while (c1 < clusters) {
                if (!blank[c1]) letters++
                val breaks = perParagraph && text.indexOf('\n', bounds[c1]).let { it >= 0 && it < bounds[c1 + 1] }
                c1++
                if (breaks) break
            }
            var k = 0
            var f = spec.factor(0, letters)
            for (c in c0 until c1) {
                if (!blank[c]) f = spec.factor(k++, letters)
                java.util.Arrays.fill(factors, bounds[c], bounds[c + 1], f)
            }
            c0 = c1
        }
        return LetterRampResult(text, spec, factors, bounds, blank)
    }

    private class Key(val text: String, val spec: LetterScaleSpec) {
        override fun equals(other: Any?): Boolean = other is Key && spec == other.spec && (text === other.text || text == other.text)
        override fun hashCode(): Int = text.hashCode() * 31 + spec.hashCode()
    }

    private val cache = object : LinkedHashMap<Key, LetterRampResult>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, LetterRampResult>?): Boolean = size > CACHE_SIZE
    }

    private const val CACHE_SIZE = 8

    /**
     * [compute] for [text] and [spec], cached for the last few texts (a linked story's frames all
     * use the story's ramp; a drag re-uses it). Thread-safe (placeholder fitting runs in the
     * background).
     */
    @Synchronized
    fun of(text: String, spec: LetterScaleSpec): LetterRampResult {
        val key = Key(text, spec)
        cache[key]?.let { return it }
        return compute(text, spec).also { cache[key] = it }
    }
}
