package com.brushwork.paint.tools.text

import android.graphics.Paint

/**
 * v1.9 (R5) the "Pair kerning" modes as extra advances after characters, on top of the font's
 * own kerning the text is measured and drawn with: the same mechanism as the v1.7 manual kerns
 * ([TextKerns.advancesPx]), so every route that draws kerned text (lines in pieces, wrapped text,
 * frames, scaled letters, text on a path) draws the modes too, and text whose mode moves nothing
 * keeps its v1.8 route and pixels.
 *
 * - Auto (`kerningMode == null`): the font's pairs everywhere (v1.7); nothing to add.
 * - Off (`fontKerning == false`): the paint turns the font's pairs off ([TextRenderer.NO_FONT_KERNING]);
 *   nothing to add.
 * - Metrics ([TextKerningMode.METRICS_LATIN]): the font's kerning of a pair is taken back where
 *   the pair is not inside one run of Latin, Greek or Cyrillic letters ([ScriptRuns]): the extra
 *   advance is the difference between the pair's advance without and with the font's kerning.
 * - Optical ([TextKerningMode.OPTICAL]): each pair set from the letters' shapes, see [OpticalKerning].
 *
 * A mode moves letters only where a manual kern could ([TextKerns.applies]: no line break on
 * either side, between grapheme clusters, in a paragraph drawn one cluster at a time); a
 * right-to-left or shaped paragraph keeps the font's shaping (its pairs as Auto). Vertical text
 * keeps the font's pairs (v1.9 kerns horizontal text and text on a path).
 */
internal object KerningModes {

    /**
     * The mode [spec] adds to the font's kerning: null for Auto, Off and straight vertical text.
     * Text [onPath] follows its shape whatever its "Vertical" says, so its mode applies.
     */
    fun modeOf(spec: TextSpec, onPath: Boolean = false): TextKerningMode? =
        if (spec.fontKerning && (onPath || !spec.vertical)) spec.kerningMode else null

    /**
     * The extra advance after each character of `source[from, length)` (index 0 = `source[from]`)
     * [mode] adds, or null when it adds none. [paint] measures as the text is measured (typeface,
     * size, letter spacing and font features, the font's kerning on); [factors] (scaled letters,
     * per source character, else null) scale each advance with its letter.
     */
    fun advancesPx(mode: TextKerningMode?, source: String, from: Int, paint: Paint, factors: FloatArray? = null): FloatArray? = when (mode) {
        null -> null
        TextKerningMode.METRICS_LATIN -> metrics(source, from, paint, factors)
        TextKerningMode.OPTICAL -> OpticalKerning.advancesPx(source, from, paint, factors)
    }

    /** [a] plus [b] (each the extra advances of one source range, or null). */
    fun sum(a: FloatArray?, b: FloatArray?): FloatArray? {
        if (a == null) return b
        if (b == null) return a
        val out = a.copyOf()
        for (i in out.indices) out[i] += b[i]
        return out
    }

    /** Whether [px] moves a letter: a value other than 0 among its first [n]. */
    fun moves(px: FloatArray?, n: Int = px?.size ?: 0): Boolean {
        if (px == null) return false
        for (i in 0 until minOf(n, px.size)) if (px[i] != 0f) return true
        return false
    }

    /**
     * Metrics: per paragraph of `source[from, length)` that has a pair outside a Latin, Greek or
     * Cyrillic run, the advances with and without the font's kerning; the difference of each such
     * pair (summed over its cluster) is the extra advance after it.
     */
    private fun metrics(source: String, from: Int, paint: Paint, factors: FloatArray?): FloatArray? {
        val n = source.length
        if (from >= n - 1 || ScriptRuns.allRoman(source.substring(from))) return null
        val scripts = ScriptRuns.scripts(source)
        val applies = TextKerns.applyRule(source)
        val plain = Paint(paint).apply { fontFeatureSettings = withoutKerning(paint.fontFeatureSettings) }
        var out: FloatArray? = null
        var on = FloatArray(0)
        var off = FloatArray(0)
        var ps = from
        while (ps < n) {
            var pe = source.indexOf('\n', ps)
            if (pe < 0) pe = n
            if (pe - ps >= 2 && hasForeignPair(scripts, source, ps, pe)) {
                val len = pe - ps
                if (on.size < len) { on = FloatArray(len); off = FloatArray(len) }
                paint.getTextWidths(source, ps, pe, on)
                plain.getTextWidths(source, ps, pe, off)
                var acc = 0f
                for (i in ps until pe - 1) {
                    val d = off[i - ps] - on[i - ps]
                    if (d.isFinite()) acc += d * (factors?.getOrNull(i) ?: 1f)
                    if (!applies(i)) continue
                    // A cluster boundary: the font's kerning of this pair is in acc.
                    if (acc != 0f && !ScriptRuns.keepsKerning(scripts, i)) (out ?: FloatArray(n - from).also { out = it })[i - from] = acc
                    acc = 0f
                }
            }
            ps = pe + 1
        }
        return out
    }

    /** Whether `source[ps, pe)` has a pair whose kerning "Metrics" takes back. */
    private fun hasForeignPair(scripts: IntArray, source: String, ps: Int, pe: Int): Boolean {
        for (i in ps until pe - 1) if (!ScriptRuns.keepsKerning(scripts, i) && !source[i].isWhitespace() && !source[i + 1].isWhitespace()) return true
        return false
    }

    /** [features] with the font's kerning turned off ([TextRenderer.NO_FONT_KERNING] added). */
    fun withoutKerning(features: String?): String =
        if (features.isNullOrEmpty()) TextRenderer.NO_FONT_KERNING else "$features, ${TextRenderer.NO_FONT_KERNING}"
}
