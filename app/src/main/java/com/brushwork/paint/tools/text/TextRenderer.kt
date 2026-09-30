package com.brushwork.paint.tools.text

import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
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
) {
    /** True when there is nothing to draw (empty text); the box still has a placeholder size. */
    val isEmpty: Boolean get() = drawGlyphs == null

    /** Distance from the box edge to the text area. */
    val inset: Float get() = spec.box.inset

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

    private fun drawBox(canvas: Canvas) {
        val box = spec.box
        if (!box.hasFrame) return
        val r = box.roundness.coerceIn(0f, 1f) * min(width, height) / 2f
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
) {
    val onPath: Boolean get() = block == null

    /** True when nothing would be drawn. */
    val isEmpty: Boolean get() = block?.isEmpty ?: (text.isEmpty() || pathBounds == null || pathBounds.isEmpty)

    /** False when this uses an imported font and fonts were imported or deleted since. */
    internal val fontsCurrent: Boolean get() = spec.fontId == null || fontGeneration == FontStore.generation

    /** Whether this can draw [item] (position and rotation of straight text don't matter). */
    fun matches(item: TextItem): Boolean {
        if (item.text != text || item.spec != spec || !fontsCurrent) return false
        return if (block != null) !item.path.isActive else item.path == path
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
        return p.measureText(text.replace('\n', ' '))
    }

    /**
     * Prepares [item] for drawing, reusing [reuse] (or its layout / paints) when the text and
     * look didn't change. Text on a path is measured by [TextOnPath.bounds].
     */
    fun prepare(item: TextItem, reuse: PreparedText? = null): PreparedText {
        if (reuse != null && reuse.matches(item)) return reuse
        val sameLook = reuse != null && reuse.spec == item.spec && reuse.fontsCurrent
        return if (item.path.isActive) {
            val paints = reuse?.paints?.takeIf { sameLook } ?: pathPaints(item.spec)
            val b = if (item.text.isEmpty()) RectF() else RectF(TextOnPath.bounds(item.text, paints.fill, paints.stroke, item.path))
            PreparedText(item.text, item.spec, item.path, null, paints, b)
        } else {
            val block = reuse?.block?.takeIf { sameLook && reuse.text == item.text } ?: layout(item.text, item.spec)
            PreparedText(item.text, item.spec, item.path, block, null, null)
        }
    }

    /** Lays out [text] with [spec] as straight (horizontal or vertical) text in its box. */
    fun layout(text: String, spec: TextSpec): TextBlock =
        if (spec.vertical) layoutVertical(text, spec) else layoutHorizontal(text, spec)

    private fun inkPad(spec: TextSpec) = spec.strokeWidthPx + spec.sizePx * (if (spec.italic || spec.font == TextFont.CURSIVE) 0.5f else 0.3f) + 2f

    /**
     * [overflow]: how far measured ink reaches outside the text area (imported fonts only: script
     * and display fonts from dafont often have swashes far beyond their letter cells, which the
     * fixed allowance of the built-in fonts would cut off when the text is committed).
     */
    private fun block(spec: TextSpec, cw: Float, ch: Float, lines: Int, paint: TextPaint, overflow: Float = 0f, glyphs: ((Canvas, TextPaint) -> Unit)?): TextBlock {
        val inset = spec.box.inset
        val pad = max(inkPad(spec), spec.strokeWidthPx + overflow + 2f)
        return TextBlock(cw + 2f * inset, ch + 2f * inset, cw, ch, pad, lines, spec, paint, glyphs)
    }

    private fun layoutHorizontal(text: String, spec: TextSpec): TextBlock {
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
        val overflow = if (spec.fontId != null) horizontalOverflow(layout, text, paint, width, height) else 0f
        return block(spec, width, height, layout.lineCount, paint, overflow) { c, _ -> layout.draw(c) }
    }

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

    private fun layoutVertical(text: String, spec: TextSpec): TextBlock {
        val paint = newPaint(spec).apply { textAlign = Paint.Align.CENTER }
        val em = spec.sizePx
        val res = VerticalTextLayout.layout(
            text, em, spec.letterSpacing, spec.lineSpacing, spec.align,
            rotatedAdvance = { paint.measureText(it) },
            style = spec.verticalStyle,
            wrapLength = spec.box.height,
            leftToRight = spec.columnsLeftToRight,
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
        val overflow = if (spec.fontId != null) verticalOverflow(glyphs, paint, baseline, tcyScale, punct, small, shift, width, height) else 0f
        return block(spec, width, height, res.columns, paint, overflow) { c, p ->
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
            if (paints != null) TextOnPath.draw(canvas, item.text, paints.fill, paints.stroke, item.path)
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
