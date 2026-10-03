package com.brushwork.paint.tools.text

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.text.TextPaint
import kotlin.math.max
import kotlin.math.min

/**
 * What a scaled measurement of letters depends on besides its own text (v1.6, see
 * [WrapText.scaleKey]): the scale [spec], the whole [source] the ramp runs over (a text, or a
 * linked story) and the [offset] the measured text starts at in it.
 */
internal data class ScaleKey(val spec: LetterScaleSpec, val source: String, val offset: Int)

/**
 * The letters of a horizontal text drawn one grapheme cluster at a time, each at its own size
 * (v1.6 §3.5c): letter k at `sizePx · f(k)`, at the x its scaled advances give (the lines come
 * from [WrapLayout] on those advances) and on its line's baseline shifted by [align]:
 * - Center: `baseline − capH·(1 − f)/2` (every letter's cap centre on one line, as in the
 *   user's example);
 * - Top: `baseline − capH·(1 − f)` (every cap top on one line);
 * - Baseline: the baseline.
 *
 * [capHeight] is the cap height at full size. [text] is what is drawn (an item's text); its
 * character i is character [offset] + i of the ramp's source (a frame of a linked story starts
 * at its slice's start). Whitespace clusters draw nothing. The outline stroke keeps the text's
 * width for every letter (drawn and exported alike).
 */
internal class ScaledLetters(
    private val text: String,
    private val ramp: LetterRampResult,
    private val offset: Int,
    private val align: LetterScaleAlign,
    private val capHeight: Float,
    /**
     * Lines farther than this (in ems, plus the stroke) outside the canvas clip are not drawn, as
     * StaticLayout skips the lines outside it: a display tile redraws only the lines it shows.
     * 0 = draw every line (imported fonts, whose swashes may reach anywhere).
     */
    private val cullEms: Float = 0f,
) {
    /** How far letter [f]'s baseline moves (negative = up) for [align]. */
    fun shift(f: Float): Float = when (align) {
        LetterScaleAlign.CENTER -> -capHeight * (1f - f) / 2f
        LetterScaleAlign.TOP -> -capHeight * (1f - f)
        LetterScaleAlign.BASELINE -> 0f
    }

    /** Draws the letters of [lines] ([wt]: their measured text, indices as [text]'s) with [paint] (any style). */
    fun draw(canvas: Canvas, lines: List<WrapLine>, wt: WrapText, paint: TextPaint) {
        val base = paint.textSize
        val clip = Rect()
        val shown = if (cullEms > 0f && lines.size > 1 && canvas.getClipBounds(clip)) {
            // Every letter's ink lies within [margin] of its line's box (Center / Top move the
            // smaller letters up by less than a cap height; accents and descenders stay within an em).
            val m = cullEms * base + paint.strokeWidth
            lines.filter { l ->
                l.baseline + m >= clip.top && l.baseline - m <= clip.bottom && l.x + l.width + m >= clip.left && l.x - m <= clip.right
            }
        } else lines
        try {
            forEachLetter(shown, wt) { s, e, x, y, f ->
                paint.textSize = base * f
                canvas.drawText(text, s, e, x, y, paint)
            }
        } finally {
            paint.textSize = base
        }
    }

    /** Adds the letters' outlines, placed as [draw] draws them, to [out]. */
    fun outline(out: Path, lines: List<WrapLine>, wt: WrapText, paint: TextPaint) {
        val p = TextPaint(paint).apply { style = Paint.Style.FILL }
        val base = p.textSize
        val tmp = Path()
        forEachLetter(lines, wt) { s, e, x, y, f ->
            p.textSize = base * f
            tmp.rewind()
            p.getTextPath(text, s, e, x, y, tmp)
            out.addPath(tmp)
        }
    }

    /** Ink bounds of every letter (text area coordinates), for imported fonts' overflow. */
    fun overflow(lines: List<WrapLine>, wt: WrapText, paint: TextPaint, w: Float, h: Float): Float {
        val p = TextPaint(paint)
        val base = p.textSize
        val r = Rect()
        var over = 0f
        forEachLetter(lines, wt) { s, e, x, y, f ->
            p.textSize = base * f
            p.getTextBounds(text, s, e, r)
            if (!r.isEmpty) over = maxOf(over, maxOf(maxOf(-(x + r.left), x + r.right - w), maxOf(-(y + r.top), y + r.bottom - h)))
        }
        return maxOf(0f, over)
    }

    /**
     * Every non-blank cluster of [lines]: its characters, where it is drawn and its factor. A line
     * that starts inside a cluster (a word too wide for the box is broken between characters, which
     * on some platforms split what the ramp keeps as one cluster, such as an emoji sequence; or a
     * frame of a story starting there) draws that cluster's part on it too, so no character is lost.
     */
    private inline fun forEachLetter(lines: List<WrapLine>, wt: WrapText, block: (Int, Int, Float, Float, Float) -> Unit) {
        val n = text.length
        val bounds = ramp.bounds
        val last = bounds.size - 1
        for (l in lines) {
            if (l.end <= l.start) continue
            val end = min(l.end, n)
            val pos = offset + l.start
            var c = ramp.clusterAtOrAfter(pos)
            if (c > 0 && bounds[c] > pos) c--
            while (c < last) {
                val s = max(bounds[c] - offset, l.start)
                if (s >= end) break
                if (!ramp.blank[c]) {
                    val e = min(bounds[c + 1] - offset, end)
                    val f = ramp.factors[offset + s]
                    block(s, e, l.x + wt.width(l.start, s), l.baseline + shift(f), f)
                }
                c++
            }
        }
    }

    companion object {
        /** The clip margin of built-in fonts' lines, in ems (see [cullEms]). */
        const val CULL_EMS = 2f

        /** Cap height of [paint]'s font at its size (the "H"), or 0.7 em when the font has none. */
        fun capHeight(paint: Paint): Float {
            val r = Rect()
            paint.getTextBounds("H", 0, 1, r)
            return if (r.height() > 0) -r.top.toFloat() else 0.7f * paint.textSize
        }
    }
}
