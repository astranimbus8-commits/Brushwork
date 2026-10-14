package com.brushwork.paint.tools.text

import java.lang.Character.UnicodeScript

/**
 * v1.9 (R5) "Metrics" pair kerning (Illustrator's "Metrics - Roman Only"): the text split into
 * script runs, the font's own kerning pairs kept only inside runs of Latin, Greek and Cyrillic
 * letters. Pure Kotlin (no android imports).
 *
 * A character's script is its Unicode script; characters every script shares (spaces, digits,
 * punctuation, the Japanese long vowel mark) and combining marks belong to the run they stand in:
 * the script of the letter before them in their paragraph, or, at a paragraph's start, of the
 * first letter after them. A paragraph without a letter of any script (digits and punctuation
 * only) is set as Latin, so "1.1" keeps the font's pairs.
 */
internal object ScriptRuns {
    /** Scripts whose runs keep the font's kerning pairs under "Metrics". */
    private val ROMAN = intArrayOf(UnicodeScript.LATIN.ordinal, UnicodeScript.GREEK.ordinal, UnicodeScript.CYRILLIC.ordinal)

    /** Marks a character whose run is not known yet (shared or inherited, before any letter). */
    private const val PENDING = -1

    /**
     * Per character of [text] (both halves of a surrogate pair alike): the [UnicodeScript]
     * ordinal of the run it stands in (see the object comment). One pass per paragraph.
     */
    fun scripts(text: String): IntArray {
        val n = text.length
        val out = IntArray(n)
        var ps = 0
        while (ps <= n) {
            var pe = text.indexOf('\n', ps)
            if (pe < 0) pe = n
            resolve(text, ps, pe, out)
            if (pe < n) out[pe] = UnicodeScript.COMMON.ordinal
            ps = pe + 1
        }
        return out
    }

    /** Resolves `text[ps, pe)` (one paragraph) into [out]. */
    private fun resolve(text: String, ps: Int, pe: Int, out: IntArray) {
        var current = PENDING
        var i = ps
        while (i < pe) {
            val cp = text.codePointAt(i)
            val count = Character.charCount(cp).coerceAtMost(pe - i)
            val s = UnicodeScript.of(cp)
            val script = if (s == UnicodeScript.COMMON || s == UnicodeScript.INHERITED || s == UnicodeScript.UNKNOWN) current else s.ordinal.also { current = it }
            for (k in 0 until count) out[i + k] = script
            i += count
        }
        // The shared characters that open the paragraph join its first letter's run (Latin without one).
        var first = UnicodeScript.LATIN.ordinal
        for (k in ps until pe) if (out[k] != PENDING) { first = out[k]; break }
        for (k in ps until pe) {
            if (out[k] != PENDING) break
            out[k] = first
        }
    }

    /** Whether a run of [script] (a [UnicodeScript] ordinal) keeps the font's kerning pairs under "Metrics". */
    fun isRoman(script: Int): Boolean = script == ROMAN[0] || script == ROMAN[1] || script == ROMAN[2]

    /**
     * Whether the font's kerning of the pair after character [i] stays under "Metrics" ([scripts]
     * of the text): both characters in one run of Latin, Greek or Cyrillic.
     */
    fun keepsKerning(scripts: IntArray, i: Int): Boolean =
        i + 1 < scripts.size && scripts[i] == scripts[i + 1] && isRoman(scripts[i])

    /**
     * Whether every pair of letters of [text] keeps the font's kerning under "Metrics" (Latin,
     * Greek or Cyrillic runs only: nothing to change). Pairs with a space or a line break don't
     * count: the font's pairs are between letters, and a shaper never kerns across two scripts.
     */
    fun allRoman(text: String): Boolean {
        var plain = true
        for (c in text) if (c.code >= 0x250) { plain = false; break }
        if (plain) return true
        val s = scripts(text)
        for (i in 0 until s.size - 1) if (!text[i].isWhitespace() && !text[i + 1].isWhitespace() && !keepsKerning(s, i)) return false
        return true
    }
}
