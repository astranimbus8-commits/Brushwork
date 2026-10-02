package com.brushwork.paint.tools.text

import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.fonts.FontStore
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Selection
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Laid-out straight text in block-local coordinates: the box spans (0, 0) - ([width], [height])
 * (text area + padding + border); the text area starts at ([inset], [inset]). Ink may reach
 * [inkPad] beyond the box (outline, italic overhang, accents). Not thread-safe: draw on the
 * thread that created it.
 */
class TextBlock internal constructor(
    val width: Float,
    val height: Float,
    /** Size of the text area (the box without padding and border). */
    val contentWidth: Float,
    val contentHeight: Float,
    val inkPad: Float,
    /** Lines of horizontal text, columns of vertical text (0 when empty). */
    val lineCount: Int,
    private val spec: TextSpec,
    private val paint: TextPaint,
    private val drawGlyphs: ((Canvas, TextPaint) -> Unit)?,
    /** Unwrapped horizontal text: its layout (for [TextExport]). */
    internal val staticLayout: StaticLayout? = null,
    /** Text wrapped around a picture, or a frame of a linked story (v1.6): its lines, in text area coordinates. */
    internal val wrapLines: List<WrapLine>? = null,
    /** Adds the glyph outlines (text area coordinates) to a path, as [drawGlyphs] draws them. */
    private val outlineGlyphs: ((Path, TextPaint) -> Unit)? = null,
) {
    /** True when there is nothing to draw (empty text); the box still has a placeholder size. */
    val isEmpty: Boolean get() = drawGlyphs == null

    /** True for text laid out by [WrapLayout] ([wrapLines]): around a picture, or a frame of a linked story (v1.6). */
    val isWrapped: Boolean get() = wrapLines != null

    /** Distance from the box edge to the text area. */
    val inset: Float get() = spec.box.inset

    /** The paint the glyphs are drawn with (fill color, font, size, letter spacing); do not change it. */
    internal val textPaint: TextPaint get() = paint

    /**
     * The glyph outlines in block coordinates (the box's top-left corner at 0, 0), or null when
     * they are not available (empty text).
     */
    internal fun glyphOutline(): Path? {
        val add = outlineGlyphs ?: return null
        if (drawGlyphs == null) return null
        val p = Path()
        add(p, paint)
        val i = inset
        if (i != 0f) p.offset(i, i)
        return p
    }

    private val boxPaint by lazy { Paint().apply { isAntiAlias = spec.antiAlias } }

    /** Draws the box (background, border), then the outline (if any) and the fill. */
    fun draw(canvas: Canvas) {
        val glyphs = drawGlyphs ?: return
        drawBox(canvas)
        val s = canvas.save()
        canvas.translate(inset, inset)
        if (spec.strokeWidthPx > 0f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = spec.strokeWidthPx * 2f // centered on the glyph edge; the fill covers the inner half
            paint.color = spec.strokeColor
            glyphs(canvas, paint)
            paint.style = Paint.Style.FILL
            paint.color = spec.color
        }
        glyphs(canvas, paint)
        canvas.restoreToCount(s)
    }

    /** Corner radius of the box (block coordinates). */
    internal val boxRadius: Float get() = spec.box.roundness.coerceIn(0f, 1f) * min(width, height) / 2f

    private fun drawBox(canvas: Canvas) {
        val box = spec.box
        if (!box.hasFrame) return
        val r = boxRadius
        if (box.fill) {
            boxPaint.style = Paint.Style.FILL
            boxPaint.color = box.fillColor
            canvas.drawRoundRect(0f, 0f, width, height, r, r, boxPaint)
        }
        val b = box.borderWidth
        if (b > 0f) {
            // Inside the box edge, so the box keeps its size.
            val h = min(b, min(width, height)) / 2f
            val ri = max(0f, r - h)
            boxPaint.style = Paint.Style.STROKE
            boxPaint.strokeWidth = h * 2f
            boxPaint.strokeJoin = Paint.Join.MITER
            boxPaint.color = box.borderColor
            canvas.drawRoundRect(h, h, width - h, height - h, ri, ri, boxPaint)
        }
    }
}

/** Fill and (optional) outline paints for text on a path; see [TextOnPath.draw]. */
class PathPaints internal constructor(val fill: TextPaint, val stroke: TextPaint?)

/**
 * A text object ready to draw: the straight layout ([block]) or, for text on a path, its paints
 * and document bounds. Build with [TextRenderer.prepare], passing the previous one to reuse what
 * did not change (moving text never re-lays it out).
 */
class PreparedText internal constructor(
    val text: String,
    val spec: TextSpec,
    val path: TextPathSpec,
    /** Straight text layout; null when the text follows a path. */
    val block: TextBlock?,
    internal val paints: PathPaints?,
    private val pathBounds: RectF?,
    /** [FontStore.generation] when this was laid out (imported fonts may come and go). */
    internal val fontGeneration: Int = FontStore.generation,
    /** Text wrapped around a picture: the placement and wrap it was laid out for (else null). */
    internal val wrapKey: WrapKey? = null,
    /**
     * Text wrapped around a picture: its measured characters (reused while only the placement
     * changes). A frame of a linked story (v1.6): the measured story from the frame's start.
     */
    internal val wrapText: WrapText? = null,
    /** v1.6: the linked-story frame this was laid out for (the default spec = not threaded). */
    val thread: TextThreadSpec = TextThreadSpec(),
) {
    val onPath: Boolean get() = block == null

    /** True when nothing would be drawn. */
    val isEmpty: Boolean get() = block?.isEmpty ?: (text.isEmpty() || pathBounds == null || pathBounds.isEmpty)

    /** False when this uses an imported font and fonts were imported or deleted since. */
    internal val fontsCurrent: Boolean get() = spec.fontId == null || fontGeneration == FontStore.generation

    /**
     * Whether this can draw [item]: position and rotation of straight text don't matter, unless
     * the text wraps around a picture (then its lines depend on where the picture is). v1.6: a
     * frame of a linked story also needs the same [TextItem.thread] (identity first, then
     * equality: the story copy can be long).
     */
    fun matches(item: TextItem): Boolean {
        if (item.text != text || item.spec != spec || !fontsCurrent) return false
        if (block == null) return item.path == path
        if (item.path.isActive) return false
        if (item.thread !== thread && item.thread != thread) return false
        return if (item.wrapActive) wrapKey == WrapKey.of(item) else wrapKey == null
    }

    /** Document bounds of everything [item] paints (copy). */
    fun docBounds(item: TextItem): RectF = if (block != null) TextRenderer.docBounds(item, block) else RectF(pathBounds ?: RectF())

    /** This text on a path moved by ([dx], [dy]) to [item] (bounds offset, nothing re-measured). */
    internal fun translatedTo(item: TextItem, dx: Float, dy: Float): PreparedText {
        if (block != null) return this
        val b = RectF(pathBounds ?: RectF()).apply { if (!isEmpty) offset(dx, dy) }
        return PreparedText(item.text, item.spec, item.path, null, paints, b, fontGeneration)
    }

    /** Whether the document point [p] is on [item] (its box, or its path bounds), with [tolerance] px. */
    fun contains(item: TextItem, p: Vec2, tolerance: Float): Boolean {
        val b = block
        if (b == null) {
            val r = pathBounds ?: return false
            if (r.isEmpty) return false
            return p.x >= r.left - tolerance && p.x <= r.right + tolerance && p.y >= r.top - tolerance && p.y <= r.bottom + tolerance
        }
        val l = item.docToLocal(p, b.width, b.height)
        return l.x >= -tolerance && l.y >= -tolerance && l.x <= b.width + tolerance && l.y <= b.height + tolerance
    }
}

/**
 * One frame of a linked story laid out (v1.6, [TextRenderer.frameLayout]): [lines] in the text
 * area's coordinates, their indices relative to [start] (indices into the frame's
 * [TextItem.text]); [start] and [end] are absolute story indices (the next frame starts at
 * [end]); [height] is the height of the laid-out lines; [width] × [contentHeight] is the text
 * area (the frame's fixed box, without padding and border).
 */
class TextFrameLayout internal constructor(
    val lines: List<WrapLine>,
    val start: Int,
    val end: Int,
    val height: Float,
    val width: Float,
    val contentHeight: Float,
    /** The story measured from [start] (reusable for the same story and look). */
    internal val measured: WrapText,
    /** v1.6 scaled letters: the ramp over the whole story (null when the letters aren't scaled). */
    internal val ramp: LetterRampResult? = null,
) {
    /** Characters of the story this frame shows. */
    val length: Int get() = end - start
}

/** What the lines of a wrapped text depend on besides its words and look: where it is and its picture. */
internal data class WrapKey(val cx: Float, val cy: Float, val rotationDeg: Float, val wrap: TextWrapSpec) {
    companion object {
        fun of(item: TextItem) = WrapKey(item.cx, item.cy, item.rotationDeg, item.wrap)
    }
}

/** Builds [TextBlock]s (StaticLayout for horizontal text, manual glyph placement for vertical). */
object TextRenderer {

    /** Typeface of [spec]: its font (imported or built-in) in its bold / italic style. */
    fun typeface(spec: TextSpec): Typeface {
        val style = when {
            spec.bold && spec.italic -> Typeface.BOLD_ITALIC
            spec.bold -> Typeface.BOLD
            spec.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return Typeface.create(baseTypeface(spec), style)
    }

    /**
     * Plain typeface of [spec]'s font: the imported font [TextSpec.fontId], or the built-in
     * family [TextSpec.font] (also while the imported font is missing).
     */
    fun baseTypeface(spec: TextSpec): Typeface =
        spec.fontId?.let { FontStore.typefaceFor(it) } ?: FontStore.builtIn(spec.font)

    /** True when [spec] asks for an imported font that isn't on this device (drawn with [TextSpec.font]). */
    fun isFontMissing(spec: TextSpec): Boolean {
        val id = spec.fontId ?: return false
        return FontStore.typefaceFor(id) == null
    }

    private fun newPaint(spec: TextSpec): TextPaint = TextPaint().apply {
        isAntiAlias = spec.antiAlias
        isSubpixelText = spec.antiAlias
        textSize = spec.sizePx
        typeface = typeface(spec)
        color = spec.color
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    /**
     * The letter scaling of [item] along its path (v1.6), or null when its letters are drawn
     * plain (scaling off, or a script that can't be drawn per cluster: see [scalesLetters]).
     */
    internal fun pathLetters(item: TextItem): LetterScaleSpec? = item.spec.letterScale.takeIf { scalesLetters(item.spec, item.text) }

    /** Paints for [TextOnPath]: typeface, size, letter spacing and colors of [spec]. */
    fun pathPaints(spec: TextSpec): PathPaints {
        val fill = newPaint(spec).apply {
            letterSpacing = spec.letterSpacing
            style = Paint.Style.FILL
        }
        val stroke = if (spec.strokeWidthPx > 0f) {
            newPaint(spec).apply {
                letterSpacing = spec.letterSpacing
                style = Paint.Style.STROKE
                strokeWidth = spec.strokeWidthPx * 2f // like straight text: the fill covers the inner half
                color = spec.strokeColor
            }
        } else null
        return PathPaints(fill, stroke)
    }

    /** Width of [text] on one line (newlines as spaces) with [spec]'s font, size and letter spacing. */
    fun lineWidth(text: String, spec: TextSpec): Float {
        val p = newPaint(spec).apply { letterSpacing = spec.letterSpacing }
        val line = text.replace('\n', ' ')
        // v1.6: scaled letters take their scaled advances.
        val ramp = rampFor(spec, line) ?: return p.measureText(line)
        val adv = FloatArray(line.length)
        p.getTextWidths(line, 0, line.length, adv)
        var w = 0.0
        for (i in adv.indices) if (adv[i].isFinite() && adv[i] > 0f) w += adv[i] * ramp.factors[i]
        return w.toFloat()
    }

    /**
     * Prepares [item] for drawing, reusing [reuse] (or its layout / paints) when the text and
     * look didn't change. Text on a path is measured by [TextOnPath.bounds].
     */
    fun prepare(item: TextItem, reuse: PreparedText? = null): PreparedText {
        if (reuse != null && reuse.matches(item)) return reuse
        val sameLook = reuse != null && reuse.spec == item.spec && reuse.fontsCurrent
        return when {
            item.path.isActive -> {
                val paints = reuse?.paints?.takeIf { sameLook } ?: pathPaints(item.spec)
                val b = if (item.text.isEmpty()) RectF() else RectF(TextOnPath.bounds(item.text, paints.fill, paints.stroke, item.path, pathLetters(item)))
                PreparedText(item.text, item.spec, item.path, null, paints, b)
            }
            item.thread.isOn -> {
                // v1.6: a frame of a linked story is laid out from the story at its start (the
                // measured story is reused while the look is the same: a frame drag re-breaks
                // nothing it doesn't need to).
                val same = reuse?.takeIf { sameLook && it.thread.isOn && it.wrapText != null }
                val (block, measured) = layoutThreaded(item, same?.wrapText)
                PreparedText(
                    item.text, item.spec, item.path, block, null, null,
                    wrapKey = if (item.wrapActive) WrapKey.of(item) else null, wrapText = measured, thread = item.thread,
                )
            }
            item.wrapActive -> {
                // Same words and look (the text or its picture moved): the measured characters are
                // reused, so a drag re-breaks lines without measuring again. The layout itself
                // never depends on an earlier one: the same item always gives the same lines (I1:
                // the committed pixels equal a fresh rendering of the stored item).
                val same = reuse?.takeIf { sameLook && it.text == item.text && it.wrapText != null && !it.thread.isOn }
                val (block, measured) = layoutWrapped(item, same?.wrapText)
                PreparedText(item.text, item.spec, item.path, block, null, null, wrapKey = WrapKey.of(item), wrapText = measured)
            }
            else -> {
                val block = reuse?.block?.takeIf { sameLook && reuse.text == item.text && reuse.wrapKey == null && !reuse.thread.isOn } ?: layout(item.text, item.spec)
                PreparedText(item.text, item.spec, item.path, block, null, null)
            }
        }
    }

    /**
     * Lays out [text] with [spec] as straight (horizontal or vertical) text in its box.
     * [measureInk] = false skips measuring how far an imported font's glyphs reach outside the
     * text area (only the drawing margin depends on it: for fitting text, not for drawing).
     */
    fun layout(text: String, spec: TextSpec, measureInk: Boolean = true): TextBlock = when {
        spec.vertical -> layoutVertical(text, spec, measureInk)
        // v1.6: scaled letters go through WrapLayout (StaticLayout can't size letters one by one).
        scalesLetters(spec, text) -> layoutScaled(text, spec, measureInk)
        else -> layoutHorizontal(text, spec, measureInk)
    }

    /**
     * Whether text with [spec] whose letters come from [source] (its text, or a linked story)
     * is drawn with scaled letters (v1.6 §3.5): scaling on, and a script that can be drawn one
     * cluster at a time ([LetterRamp.supports]; right-to-left and shaping scripts are drawn
     * unscaled). Unscaled text keeps the v1.5 paths bit for bit.
     */
    internal fun scalesLetters(spec: TextSpec, source: String): Boolean = spec.letterScale.isOn && LetterRamp.supports(source)

    /** The ramp of [source] when [spec] scales its letters (see [scalesLetters]), else null. */
    private fun rampFor(spec: TextSpec, source: String): LetterRampResult? =
        if (scalesLetters(spec, source)) LetterRamp.of(source, spec.letterScale) else null

    /**
     * [text] measured for [WrapLayout] with [paint]: plain advances, or (with a [ramp]) each
     * character's advance times its letter's factor, [text] being the ramp's source from
     * [offset] on ([key]: see [WrapText.scaleKey]). Letter spacing is in em, so it scales too.
     */
    private fun measureFor(text: String, paint: TextPaint, ramp: LetterRampResult?, offset: Int, key: ScaleKey?): WrapText {
        if (ramp == null) return WrapLayout.measure(text) { s, a, b, out -> paint.getTextWidths(s, a, b, out) }
        val f = ramp.factors
        return WrapLayout.measure(text, WrapMeasurer { s, a, b, out ->
            paint.getTextWidths(s, a, b, out)
            for (i in 0 until b - a) out[i] *= f[offset + a + i]
        }, key)
    }

    /** Text area width of scaled letters: the fixed box (whole pixels, as StaticLayout), else the widest paragraph. */
    private fun scaledWidth(wrap: Float, wt: WrapText): Float =
        (if (wrap > 0f) ceil(wrap).toInt() else ceil(WrapLayout.widestParagraph(wt)).toInt() + 1).coerceAtLeast(1).toFloat()

    /**
     * Horizontal text with scaled letters (v1.6 §3.5c): the advances are scaled per letter and
     * broken into lines by [WrapLayout] (a fixed box breaks on the scaled advances); the lines
     * keep the full-size pitch, so the box doesn't jump while the slider moves.
     */
    private fun layoutScaled(text: String, spec: TextSpec, measureInk: Boolean): TextBlock {
        if (text.isEmpty()) return layoutHorizontal(text, spec, measureInk)
        val paint = newPaint(spec).apply { letterSpacing = spec.letterSpacing }
        val fmi = paint.fontMetricsInt
        val metrics = WrapMetrics.staticLayout(fmi.ascent, fmi.descent, spec.lineSpacing)
        val ramp = LetterRamp.of(text, spec.letterScale)
        val wt = measureFor(text, paint, ramp, 0, ScaleKey(spec.letterScale, text, 0))
        val wrap = spec.box.width
        val width = scaledWidth(wrap, wt)
        val minHeight = if (wrap > 0f) spec.box.minHeight else 0f
        val res = WrapLayout.layout(wt, width, metrics, NOTHING_BLOCKED, WrapSides.LARGEST, spec.align, 0f)
        val height = max(max(1f, res.height), minHeight)
        return scaledBlock(spec, text, ramp, 0, paint, wt, res.lines, width, height, measureInk)
    }

    /** The block of scaled [lines] of [text] ([ramp] from [offset], see [ScaledLetters]). */
    private fun scaledBlock(
        spec: TextSpec, text: String, ramp: LetterRampResult, offset: Int, paint: TextPaint, wt: WrapText,
        lines: List<WrapLine>, width: Float, contentH: Float, measureInk: Boolean,
    ): TextBlock {
        val letters = ScaledLetters(text, ramp, offset, spec.letterScale.align, ScaledLetters.capHeight(paint))
        val overflow = if (measureInk && spec.fontId != null) letters.overflow(lines, wt, paint, width, contentH) else 0f
        val outline: (Path, TextPaint) -> Unit = { out, p -> letters.outline(out, lines, wt, p) }
        return block(spec, width, contentH, lines.size, paint, overflow, wrapLines = lines, outline = outline) { c, p -> letters.draw(c, lines, wt, p) }
    }

    private fun inkPad(spec: TextSpec) = spec.strokeWidthPx + spec.sizePx * (if (spec.italic || spec.font == TextFont.CURSIVE) 0.5f else 0.3f) + 2f

    /**
     * [overflow]: how far measured ink reaches outside the text area (imported fonts only: script
     * and display fonts from dafont often have swashes far beyond their letter cells, which the
     * fixed allowance of the built-in fonts would cut off when the text is committed).
     */
    private fun block(
        spec: TextSpec, cw: Float, ch: Float, lines: Int, paint: TextPaint, overflow: Float = 0f,
        staticLayout: StaticLayout? = null, wrapLines: List<WrapLine>? = null, outline: ((Path, TextPaint) -> Unit)? = null,
        glyphs: ((Canvas, TextPaint) -> Unit)?,
    ): TextBlock {
        val inset = spec.box.inset
        val pad = max(inkPad(spec), spec.strokeWidthPx + overflow + 2f)
        return TextBlock(cw + 2f * inset, ch + 2f * inset, cw, ch, pad, lines, spec, paint, glyphs, staticLayout, wrapLines, outline)
    }

    private fun layoutHorizontal(text: String, spec: TextSpec, measureInk: Boolean): TextBlock {
        val paint = newPaint(spec).apply { letterSpacing = spec.letterSpacing }
        val fm = paint.fontMetrics
        val wrap = spec.box.width
        // A fixed-size text box (width x height): the area stays at least this tall.
        val minHeight = if (wrap > 0f) spec.box.minHeight else 0f
        if (text.isEmpty()) {
            val w = if (wrap > 0f) wrap else spec.sizePx * 0.6f
            return block(spec, w, max((fm.descent - fm.ascent) * max(1f, spec.lineSpacing), minHeight), 0, paint, glyphs = null)
        }
        val layout = staticLayout(text, spec, paint)
        val width = layout.width.toFloat()
        val height = max(max(1f, layout.height.toFloat()), minHeight)
        val overflow = if (measureInk && spec.fontId != null) horizontalOverflow(layout, text, paint, width, height) else 0f
        val outline: (Path, TextPaint) -> Unit = { out, p ->
            val tmp = Path()
            for (i in 0 until layout.lineCount) {
                val s = layout.getLineStart(i)
                val e = layout.getLineVisibleEnd(i)
                if (e <= s) continue
                tmp.rewind()
                p.getTextPath(text, s, e, staticLineX(layout, i), layout.getLineBaseline(i).toFloat(), tmp)
                out.addPath(tmp)
            }
        }
        return block(spec, width, height, layout.lineCount, paint, overflow, staticLayout = layout, outline = outline) { c, _ -> layout.draw(c) }
    }

    /**
     * Where [layout] starts drawing line [i] (the left end of its text): the integer positions
     * `Layout.drawText` uses for left-to-right lines, `getLineLeft` for right-to-left ones.
     */
    internal fun staticLineX(layout: StaticLayout, i: Int): Float {
        if (layout.getParagraphDirection(i) != Layout.DIR_LEFT_TO_RIGHT) return layout.getLineLeft(i)
        val right = layout.width
        val max = layout.getLineMax(i).toInt()
        return when (layout.getParagraphAlignment(i)) {
            Layout.Alignment.ALIGN_OPPOSITE -> (right - max).toFloat()
            Layout.Alignment.ALIGN_CENTER -> ((right - (max and 1.inv())) shr 1).toFloat()
            else -> 0f
        }
    }

    // ------------------------------------------------------------------ text wrapped around a picture

    /**
     * Lays out [item] flowing around its picture ([TextItem.wrap], horizontal straight text):
     * [WrapLayout] lines with StaticLayout's metrics, the picture seen through [WrapObstacle].
     * The block is centered on the item's position, so where the picture falls on the lines
     * depends on the block's height: up to 3 passes, starting from the height of the text
     * without the picture, find a height that agrees with its lines; if they don't agree, the
     * tallest height seen is laid out (grown until the lines fit) and kept (the box gets a little
     * room below). The lines are always laid out for the height the block gets, so they keep
     * clear of the picture. The result depends only on [item] (the same item always gives the
     * same lines). [measured] is reused when the text and look are unchanged.
     */
    internal fun layoutWrapped(item: TextItem, measured: WrapText? = null, measureInk: Boolean = true): Pair<TextBlock, WrapText?> {
        val spec = item.spec
        val text = item.text
        if (text.isEmpty()) return layoutHorizontal(text, spec, measureInk) to null
        val paint = newPaint(spec).apply { letterSpacing = spec.letterSpacing }
        val fmi = paint.fontMetricsInt
        val metrics = WrapMetrics.staticLayout(fmi.ascent, fmi.descent, spec.lineSpacing)
        val wrap = spec.box.width
        // v1.6: scaled letters measure scaled advances (and only reuse a measurement of the same ramp).
        val ramp = rampFor(spec, text)
        val key = ramp?.let { ScaleKey(spec.letterScale, text, 0) }
        val wt = measured?.takeIf { it.text == text && it.scaleKey == key } ?: measureFor(text, paint, ramp, 0, key)
        // StaticLayout's width (whole pixels); without a fixed box, the text's own width.
        val width = if (ramp != null) scaledWidth(wrap, wt)
        else (if (wrap > 0f) ceil(wrap).toInt() else ceil(Layout.getDesiredWidth(text, paint)).toInt() + 1).coerceAtLeast(1).toFloat()
        val minHeight = if (wrap > 0f) spec.box.minHeight else 0f
        val obstacle = WrapObstacle(item.wrap.polygons, item.cx, item.cy, item.rotationDeg)
        val inset = spec.box.inset
        val blockW = width + 2f * inset
        val gap = item.wrap.gapPx
        val minRun = item.wrap.minRunEm * spec.sizePx
        fun blockHeight(contentH: Float) = max(max(1f, contentH), minHeight) + 2f * inset
        fun run(blockH: Float): WrapResult {
            val blocked = if (obstacle.isEmpty) NOTHING_BLOCKED else obstacle.forArea(inset - blockW / 2f, inset - blockH / 2f, gap)
            return WrapLayout.layout(wt, width, metrics, blocked, item.wrap.sides, spec.align, minRun)
        }
        var bh = blockHeight(WrapLayout.layout(wt, width, metrics, NOTHING_BLOCKED, item.wrap.sides, spec.align, minRun).height)
        var res = run(bh)
        var next = blockHeight(res.height)
        var tallest = max(bh, next)
        var passes = 1
        while (next != bh && passes < WRAP_PASSES) {
            bh = next
            res = run(bh)
            next = blockHeight(res.height)
            tallest = max(tallest, next)
            passes++
        }
        if (next != bh) {
            bh = tallest
            res = run(bh)
            next = blockHeight(res.height)
            // The lines must fit the block they were laid out for (else they would sit lower than
            // where the picture was looked at): grow it until they do.
            var grow = 0
            while (next > bh && grow < WRAP_GROW_PASSES) {
                bh = next
                res = run(bh)
                next = blockHeight(res.height)
                grow++
            }
        }
        val finalH = max(next, bh)
        val contentH = finalH - 2f * inset
        val lines = res.lines
        if (ramp != null) return scaledBlock(spec, text, ramp, 0, paint, wt, lines, width, contentH, measureInk) to wt
        val overflow = if (measureInk && spec.fontId != null) wrappedOverflow(lines, text, paint, width, contentH) else 0f
        val outline: (Path, TextPaint) -> Unit = { out, p ->
            val tmp = Path()
            for (l in lines) {
                if (l.end <= l.start) continue
                tmp.rewind()
                p.getTextPath(text, l.start, l.end, l.x, l.baseline, tmp)
                out.addPath(tmp)
            }
        }
        val block = block(spec, width, contentH, lines.size, paint, overflow, wrapLines = lines, outline = outline) { c, p ->
            for (l in lines) if (l.end > l.start) c.drawText(text, l.start, l.end, l.x, l.baseline, p)
        }
        return block to wt
    }

    /**
     * Height of the text area [text] needs in [item]'s wrapped layout when the block is
     * [blockHeight] tall (no fixed-point search: for fitting text into a box of that height).
     */
    internal fun wrappedTextHeight(item: TextItem, text: String, blockHeight: Float): Float {
        val spec = item.spec
        if (text.isEmpty()) return 0f
        val paint = newPaint(spec).apply { letterSpacing = spec.letterSpacing }
        val fmi = paint.fontMetricsInt
        val metrics = WrapMetrics.staticLayout(fmi.ascent, fmi.descent, spec.lineSpacing)
        val wrap = spec.box.width
        val ramp = rampFor(spec, text)
        val wt = measureFor(text, paint, ramp, 0, ramp?.let { ScaleKey(spec.letterScale, text, 0) })
        val width = if (ramp != null) scaledWidth(wrap, wt)
        else (if (wrap > 0f) ceil(wrap).toInt() else ceil(Layout.getDesiredWidth(text, paint)).toInt() + 1).coerceAtLeast(1).toFloat()
        val obstacle = WrapObstacle(item.wrap.polygons, item.cx, item.cy, item.rotationDeg)
        val inset = spec.box.inset
        val blocked = if (obstacle.isEmpty) NOTHING_BLOCKED else obstacle.forArea(inset - (width + 2f * inset) / 2f, inset - blockHeight / 2f, item.wrap.gapPx)
        return WrapLayout.layout(wt, width, metrics, blocked, item.wrap.sides, spec.align, item.wrap.minRunEm * spec.sizePx).height
    }

    // ------------------------------------------------------------------ v1.6: frames of a linked story (§3.6, §4.4)

    /**
     * Lays out frame [item] of a linked story (v1.6, foundation): `story.substring(thread.start)`
     * with [WrapLayout.layoutFrame] in the frame's text area — `box.width` (whole pixels, as
     * StaticLayout) × `box.minHeight` — honouring the item's wrap outline ([TextItem.wrapActive];
     * the block height is fixed, so no fixed-point passes). Rendering a frame ([prepare] →
     * [layoutThreaded]) and computing its end ([frameEnd]) are this one call, so a frame's pixels
     * equal its slice of the chain by construction (I1).
     *
     * Any straight horizontal item works: an unthreaded one is a story of its own text starting
     * at 0, in its fixed box (no `minHeight`, or no fixed width: unlimited height, and then the
     * wrap outline is ignored). [measured] (the story measured from the start) is reused when it
     * is that text.
     *
     * v1.6 letter scaling (area C): the measurer's advances are scaled by the ramp over the WHOLE
     * story (a frame's letters continue the ramp of the frames before it: its letter offset is
     * computed from `story[0, start)`, never stored), so the flow and the rendering keep agreeing.
     * A measurement is reused only for the same story, scale and start ([WrapText.scaleKey]).
     */
    fun frameLayout(item: TextItem, measured: WrapText? = null): TextFrameLayout {
        val spec = item.spec
        val th = item.thread
        val story = if (th.isOn) th.story else item.text
        val start = if (th.isOn) th.start.coerceIn(0, story.length) else 0
        val paint = newPaint(spec).apply { letterSpacing = spec.letterSpacing }
        val fmi = paint.fontMetricsInt
        val metrics = WrapMetrics.staticLayout(fmi.ascent, fmi.descent, spec.lineSpacing)
        val wrap = spec.box.width
        val rest = story.length - start
        val ramp = rampFor(spec, story)
        val key = ramp?.let { ScaleKey(spec.letterScale, story, start) }
        val wt = measured?.takeIf { it.scaleKey == key && it.text.length == rest && story.regionMatches(start, it.text, 0, rest) }
            ?: measureFor(story.substring(start), paint, ramp, start, key)
        val width = if (ramp != null) scaledWidth(wrap, wt)
        else (if (wrap > 0f) ceil(wrap).toInt() else ceil(Layout.getDesiredWidth(wt.text, paint)).toInt() + 1).coerceAtLeast(1).toFloat()
        val height = if (wrap > 0f && spec.box.minHeight > 0f) spec.box.minHeight else Float.POSITIVE_INFINITY
        val inset = spec.box.inset
        val obstacle = WrapObstacle(item.wrap.polygons, item.cx, item.cy, item.rotationDeg)
        val blocked = if (!item.wrapActive || obstacle.isEmpty || !height.isFinite()) NOTHING_BLOCKED
        else obstacle.forArea(inset - (width + 2f * inset) / 2f, inset - (height + 2f * inset) / 2f, item.wrap.gapPx)
        val res = WrapLayout.layoutFrame(wt, width, height, metrics, blocked, item.wrap.sides, spec.align, item.wrap.minRunEm * spec.sizePx)
        return TextFrameLayout(res.lines, start, start + res.end, res.height, width, if (height.isFinite()) height else res.height, wt, ramp)
    }

    /**
     * Where frame [item]'s slice of its story ends (v1.6): the absolute story index the next frame
     * starts at (see [frameLayout]; `thread.start` when not even one line fits). For an
     * unthreaded item, the end of what fits its fixed box in its own text.
     */
    fun frameEnd(item: TextItem): Int = frameLayout(item).end

    /**
     * The block of frame [item] (v1.6): [frameLayout]'s lines in the frame's fixed box, drawn
     * from the item's own text (`story[start, end)`, I9; lines past it are left out, so a frame
     * never shows its neighbour's characters). Its `wrapLines` index into [TextItem.text].
     */
    internal fun layoutThreaded(item: TextItem, measured: WrapText? = null, measureInk: Boolean = true): Pair<TextBlock, WrapText> {
        val spec = item.spec
        val fl = frameLayout(item, measured)
        val text = item.text
        val n = text.length
        val lines = fl.lines.mapNotNull { l ->
            when {
                l.end <= n -> l
                l.start >= n -> null
                else -> l.copy(end = n)
            }
        }
        val paint = newPaint(spec).apply { letterSpacing = spec.letterSpacing }
        val contentH = fl.contentHeight
        if (text.isEmpty()) return block(spec, fl.width, max(1f, contentH), 0, paint, wrapLines = emptyList(), glyphs = null) to fl.measured
        // v1.6: scaled letters continue the story's ramp from this frame's start.
        val ramp = fl.ramp
        if (ramp != null) return scaledBlock(spec, text, ramp, fl.start, paint, fl.measured, lines, fl.width, max(1f, contentH), measureInk) to fl.measured
        val overflow = if (measureInk && spec.fontId != null) wrappedOverflow(lines, text, paint, fl.width, contentH) else 0f
        val outline: (Path, TextPaint) -> Unit = { out, p ->
            val tmp = Path()
            for (l in lines) {
                if (l.end <= l.start) continue
                tmp.rewind()
                p.getTextPath(text, l.start, l.end, l.x, l.baseline, tmp)
                out.addPath(tmp)
            }
        }
        val block = block(spec, fl.width, max(1f, contentH), lines.size, paint, overflow, wrapLines = lines, outline = outline) { c, p ->
            for (l in lines) if (l.end > l.start) c.drawText(text, l.start, l.end, l.x, l.baseline, p)
        }
        return block to fl.measured
    }

    /** Like [horizontalOverflow] for wrapped [lines]. */
    private fun wrappedOverflow(lines: List<WrapLine>, text: String, paint: TextPaint, w: Float, h: Float): Float {
        val r = Rect()
        var over = 0f
        for (l in lines) {
            if (l.end <= l.start) continue
            paint.getTextBounds(text, l.start, l.end, r)
            if (r.isEmpty) continue
            over = max(over, max(max(-(l.x + r.left), l.x + r.right - w), max(-(l.baseline + r.top), l.baseline + r.bottom - h)))
        }
        return max(0f, over)
    }

    private val NOTHING_BLOCKED: (Float, Float) -> List<ClosedFloatingPointRange<Float>> = { _, _ -> emptyList() }

    /** Layout passes looking for a block height that agrees with its wrapped lines. */
    private const val WRAP_PASSES = 3

    /** Extra passes growing a block that didn't settle until its lines fit it. */
    private const val WRAP_GROW_PASSES = 4

    private fun staticLayout(text: String, spec: TextSpec, paint: TextPaint): StaticLayout {
        val wrap = spec.box.width
        val width = if (wrap > 0f) ceil(wrap).toInt() else ceil(Layout.getDesiredWidth(text, paint)).toInt() + 1
        val align = when (spec.align) {
            TextAlign.START -> Layout.Alignment.ALIGN_NORMAL
            TextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
            TextAlign.END -> Layout.Alignment.ALIGN_OPPOSITE
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(align)
            .setLineSpacing(0f, spec.lineSpacing)
            .setIncludePad(false)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
    }

    /** How far the glyphs of [layout] reach outside (0, 0) - ([w], [h]) (0 when they don't). */
    private fun horizontalOverflow(layout: StaticLayout, text: String, paint: TextPaint, w: Float, h: Float): Float {
        val r = Rect()
        var over = 0f
        for (i in 0 until layout.lineCount) {
            val s = layout.getLineStart(i)
            val e = layout.getLineEnd(i)
            if (e <= s) continue
            paint.getTextBounds(text, s, e, r)
            if (r.isEmpty) continue
            val x = layout.getLineLeft(i)
            val y = layout.getLineBaseline(i).toFloat()
            over = max(over, max(max(-(x + r.left), x + r.right - w), max(-(y + r.top), y + r.bottom - h)))
        }
        return max(0f, over)
    }

    private fun layoutVertical(text: String, spec: TextSpec, measureInk: Boolean): TextBlock {
        val paint = newPaint(spec).apply { textAlign = Paint.Align.CENTER }
        val em = spec.sizePx
        // v1.6: scaled letters shrink along the column and stay centred on its axis (§3.5a).
        val ramp = rampFor(spec, text)
        val res = VerticalTextLayout.layout(
            text, em, spec.letterSpacing, spec.lineSpacing, spec.align,
            rotatedAdvance = { paint.measureText(it) },
            style = spec.verticalStyle,
            wrapLength = spec.box.height,
            leftToRight = spec.columnsLeftToRight,
            factors = ramp?.factors,
        )
        val height = if (res.height > 0f) res.height else em
        // A fixed-size text box: at least this wide; columns start at its right (or left) edge.
        val width = max(res.width, if (spec.box.height > 0f) spec.box.minWidth else 0f)
        val shift = if (spec.columnsLeftToRight) 0f else width - res.width
        if (res.glyphs.isEmpty()) return block(spec, width, height, 0, paint, glyphs = null)
        val fm = paint.fontMetrics
        val baseline = -(fm.ascent + fm.descent) / 2f
        val glyphs = res.glyphs
        // Horizontal squeeze of tate-chu-yoko cells so they fit in one em.
        val tcyScale = FloatArray(glyphs.size) { i ->
            val g = glyphs[i]
            if (g.kind == VerticalGlyphKind.TATE_CHU_YOKO) min(1f, em * 0.95f / max(1e-3f, paint.measureText(g.text))) else 1f
        }
        val punct = VerticalTextLayout.PUNCTUATION_SHIFT * em
        val small = VerticalTextLayout.SMALL_KANA_SHIFT * em
        val overflow = if (measureInk && spec.fontId != null) verticalOverflow(glyphs, paint, baseline, tcyScale, punct, small, shift, width, height) else 0f
        if (ramp != null) return scaledVerticalBlock(spec, glyphs, paint, baseline, tcyScale, punct, small, shift, width, height, res.columns, overflow)
        // The same placement as the drawing below, as outlines (for export).
        val outline: (Path, TextPaint) -> Unit = { out, centered ->
            // Glyph paths of left-aligned text moved by half their advance: the drawing centers them.
            val p = TextPaint(centered).apply { textAlign = Paint.Align.LEFT }
            val tmp = Path()
            val m = Matrix()
            for (i in glyphs.indices) {
                val g = glyphs[i]
                val half = p.measureText(g.text) / 2f
                tmp.rewind()
                when (g.kind) {
                    VerticalGlyphKind.UPRIGHT -> p.getTextPath(g.text, 0, g.text.length, g.cx + shift - half, g.cy + baseline, tmp)
                    VerticalGlyphKind.PUNCTUATION -> p.getTextPath(g.text, 0, g.text.length, g.cx + punct + shift - half, g.cy + baseline - punct, tmp)
                    VerticalGlyphKind.SMALL_KANA -> p.getTextPath(g.text, 0, g.text.length, g.cx + small + shift - half, g.cy + baseline - small, tmp)
                    VerticalGlyphKind.ROTATED -> {
                        p.getTextPath(g.text, 0, g.text.length, -half, baseline, tmp)
                        m.setRotate(90f)
                        m.postTranslate(g.cx + shift, g.cy)
                        tmp.transform(m)
                    }
                    VerticalGlyphKind.TATE_CHU_YOKO -> {
                        p.getTextPath(g.text, 0, g.text.length, -half, 0f, tmp)
                        m.setScale(tcyScale[i], 1f)
                        m.postTranslate(g.cx + shift, g.cy + baseline)
                        tmp.transform(m)
                    }
                }
                out.addPath(tmp)
            }
        }
        return block(spec, width, height, res.columns, paint, overflow, outline = outline) { c, p ->
            val s0 = c.save()
            if (shift != 0f) c.translate(shift, 0f)
            for (i in glyphs.indices) {
                val g = glyphs[i]
                when (g.kind) {
                    VerticalGlyphKind.UPRIGHT -> c.drawText(g.text, g.cx, g.cy + baseline, p)
                    VerticalGlyphKind.PUNCTUATION -> c.drawText(g.text, g.cx + punct, g.cy + baseline - punct, p)
                    VerticalGlyphKind.SMALL_KANA -> c.drawText(g.text, g.cx + small, g.cy + baseline - small, p)
                    VerticalGlyphKind.ROTATED -> {
                        val s = c.save()
                        c.translate(g.cx, g.cy)
                        c.rotate(90f)
                        c.drawText(g.text, 0f, baseline, p)
                        c.restoreToCount(s)
                    }
                    VerticalGlyphKind.TATE_CHU_YOKO -> {
                        val s = c.save()
                        c.translate(g.cx, g.cy + baseline)
                        c.scale(tcyScale[i], 1f)
                        c.drawText(g.text, 0f, 0f, p)
                        c.restoreToCount(s)
                    }
                }
            }
            c.restoreToCount(s0)
        }
    }

    /**
     * Vertical text with scaled letters (v1.6 §3.5a): every cell is drawn at `sizePx · scale`,
     * centred on its column like full-size cells (its baseline offset, punctuation and small-kana
     * nudges scale with it), placed where [VerticalTextLayout] put it on the scaled advances.
     * Drawing and outlines place the cells identically.
     */
    private fun scaledVerticalBlock(
        spec: TextSpec, glyphs: List<VerticalGlyph>, paint: TextPaint, baseline: Float, tcyScale: FloatArray,
        punct: Float, small: Float, shift: Float, width: Float, height: Float, columns: Int, overflow: Float,
    ): TextBlock {
        val outline: (Path, TextPaint) -> Unit = { out, centered ->
            val p = TextPaint(centered).apply { textAlign = Paint.Align.LEFT; style = Paint.Style.FILL }
            val base = p.textSize
            val tmp = Path()
            val m = Matrix()
            for (i in glyphs.indices) {
                val g = glyphs[i]
                val s = g.scale
                p.textSize = base * s
                val bl = baseline * s
                val pu = punct * s
                val sm = small * s
                val half = p.measureText(g.text) / 2f
                tmp.rewind()
                when (g.kind) {
                    VerticalGlyphKind.UPRIGHT -> p.getTextPath(g.text, 0, g.text.length, g.cx + shift - half, g.cy + bl, tmp)
                    VerticalGlyphKind.PUNCTUATION -> p.getTextPath(g.text, 0, g.text.length, g.cx + pu + shift - half, g.cy + bl - pu, tmp)
                    VerticalGlyphKind.SMALL_KANA -> p.getTextPath(g.text, 0, g.text.length, g.cx + sm + shift - half, g.cy + bl - sm, tmp)
                    VerticalGlyphKind.ROTATED -> {
                        p.getTextPath(g.text, 0, g.text.length, -half, bl, tmp)
                        m.setRotate(90f)
                        m.postTranslate(g.cx + shift, g.cy)
                        tmp.transform(m)
                    }
                    VerticalGlyphKind.TATE_CHU_YOKO -> {
                        p.getTextPath(g.text, 0, g.text.length, -half, 0f, tmp)
                        m.setScale(tcyScale[i], 1f)
                        m.postTranslate(g.cx + shift, g.cy + bl)
                        tmp.transform(m)
                    }
                }
                out.addPath(tmp)
            }
        }
        return block(spec, width, height, columns, paint, overflow, outline = outline) { c, p ->
            val base = p.textSize
            val s0 = c.save()
            try {
                if (shift != 0f) c.translate(shift, 0f)
                for (i in glyphs.indices) {
                    val g = glyphs[i]
                    val s = g.scale
                    p.textSize = base * s
                    val bl = baseline * s
                    val pu = punct * s
                    val sm = small * s
                    when (g.kind) {
                        VerticalGlyphKind.UPRIGHT -> c.drawText(g.text, g.cx, g.cy + bl, p)
                        VerticalGlyphKind.PUNCTUATION -> c.drawText(g.text, g.cx + pu, g.cy + bl - pu, p)
                        VerticalGlyphKind.SMALL_KANA -> c.drawText(g.text, g.cx + sm, g.cy + bl - sm, p)
                        VerticalGlyphKind.ROTATED -> {
                            val r = c.save()
                            c.translate(g.cx, g.cy)
                            c.rotate(90f)
                            c.drawText(g.text, 0f, bl, p)
                            c.restoreToCount(r)
                        }
                        VerticalGlyphKind.TATE_CHU_YOKO -> {
                            val r = c.save()
                            c.translate(g.cx, g.cy + bl)
                            c.scale(tcyScale[i], 1f)
                            c.drawText(g.text, 0f, 0f, p)
                            c.restoreToCount(r)
                        }
                    }
                }
            } finally {
                p.textSize = base
                c.restoreToCount(s0)
            }
        }
    }

    /** Like [horizontalOverflow] for the cells of vertical text (drawn as in [layoutVertical]). */
    private fun verticalOverflow(
        glyphs: List<VerticalGlyph>, paint: TextPaint, baseline: Float, tcyScale: FloatArray,
        punct: Float, small: Float, shift: Float, w: Float, h: Float,
    ): Float {
        val r = Rect()
        var over = 0f
        fun extend(l: Float, t: Float, rt: Float, b: Float) {
            over = max(over, max(max(-(l + shift), rt + shift - w), max(-t, b - h)))
        }
        for (i in glyphs.indices) {
            val g = glyphs[i]
            paint.getTextBounds(g.text, 0, g.text.length, r)
            if (r.isEmpty) continue
            // Centered text: the ink relative to the drawing point.
            val half = paint.measureText(g.text) / 2f
            val l = r.left - half; val rt = r.right - half
            when (g.kind) {
                VerticalGlyphKind.UPRIGHT -> extend(g.cx + l, g.cy + baseline + r.top, g.cx + rt, g.cy + baseline + r.bottom)
                VerticalGlyphKind.PUNCTUATION -> extend(g.cx + punct + l, g.cy + baseline - punct + r.top, g.cx + punct + rt, g.cy + baseline - punct + r.bottom)
                VerticalGlyphKind.SMALL_KANA -> extend(g.cx + small + l, g.cy + baseline - small + r.top, g.cx + small + rt, g.cy + baseline - small + r.bottom)
                // Turned 90° clockwise about the cell center: (x, y) -> (-y, x).
                VerticalGlyphKind.ROTATED -> extend(g.cx - (baseline + r.bottom), g.cy + l, g.cx - (baseline + r.top), g.cy + rt)
                VerticalGlyphKind.TATE_CHU_YOKO -> extend(g.cx + l * tcyScale[i], g.cy + baseline + r.top, g.cx + rt * tcyScale[i], g.cy + baseline + r.bottom)
            }
        }
        return max(0f, over)
    }

    /** Block-local -> document matrix of [item] (rotation around the block center). */
    fun matrix(item: TextItem, block: TextBlock, out: Matrix = Matrix()): Matrix = out.apply {
        setTranslate(-block.width / 2f, -block.height / 2f)
        postRotate(item.rotationDeg)
        postTranslate(item.cx, item.cy)
    }

    /** Document-space bounds of everything the (straight) item may paint. */
    fun docBounds(item: TextItem, block: TextBlock): RectF {
        val r = RectF(-block.inkPad, -block.inkPad, block.width + block.inkPad, block.height + block.inkPad)
        matrix(item, block).mapRect(r)
        return r
    }

    /** Straight-text convenience for [drawItem]. */
    fun drawItem(canvas: Canvas, item: TextItem, block: TextBlock, selection: Selection?, previewMode: ColorMode = ColorMode.RGB) =
        drawItem(canvas, item, PreparedText(item.text, item.spec, item.path, block, null, null), selection, previewMode)

    /**
     * Draws [item] (prepared as [prepared]) into a canvas in DOCUMENT coordinates, clipped to
     * [selection] (soft mask) when given. [previewMode] adds a color filter approximating the
     * document color mode (previews only; committed pixels are constrained exactly by ColorModeOps).
     */
    fun drawItem(canvas: Canvas, item: TextItem, prepared: PreparedText, selection: Selection?, previewMode: ColorMode = ColorMode.RGB) {
        if (prepared.isEmpty) return
        val bounds = prepared.docBounds(item)
        if (bounds.isEmpty) return
        val filter = colorModePaint(previewMode)
        val save = if (selection != null || filter != null) canvas.saveLayer(bounds, filter) else canvas.save()
        val block = prepared.block
        if (block != null) {
            canvas.save()
            canvas.concat(matrix(item, block))
            block.draw(canvas)
            canvas.restore()
        } else {
            val paints = prepared.paints
            if (paints != null) TextOnPath.draw(canvas, item.text, paints.fill, paints.stroke, item.path, pathLetters(item))
        }
        if (selection != null) {
            canvas.clipRect(bounds)
            com.brushwork.paint.engine.BitmapUtils.maskWith(canvas, selection.mask)
        }
        canvas.restoreToCount(save)
    }

    private val grayPaint by lazy { Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) } }

    private val monoPaint by lazy {
        // Luma pushed to black/white and alpha thresholded at 50 %, like ColorModeOps.MONOCHROME.
        val k = 64f
        val o = -k * 127.5f + 127.5f
        val m = ColorMatrix(
            floatArrayOf(
                0.299f * k, 0.587f * k, 0.114f * k, 0f, o,
                0.299f * k, 0.587f * k, 0.114f * k, 0f, o,
                0.299f * k, 0.587f * k, 0.114f * k, 0f, o,
                0f, 0f, 0f, k, o,
            )
        )
        Paint().apply { colorFilter = ColorMatrixColorFilter(m) }
    }

    private fun colorModePaint(mode: ColorMode): Paint? = when (mode) {
        ColorMode.RGB -> null
        ColorMode.GRAYSCALE -> grayPaint
        ColorMode.MONOCHROME -> monoPaint
    }
}
