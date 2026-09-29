package com.brushwork.paint.tools.text

import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Selection
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Laid-out text in block-local coordinates: the box spans (0, 0) - ([width], [height]); ink may
 * reach [inkPad] beyond it (outline, italic overhang, accents). Not thread-safe: draw on the
 * thread that created it.
 */
class TextBlock internal constructor(
    val width: Float,
    val height: Float,
    val inkPad: Float,
    private val spec: TextSpec,
    private val paint: TextPaint,
    private val drawGlyphs: ((Canvas, TextPaint) -> Unit)?,
) {
    /** True when there is nothing to draw (empty text); the box still has a placeholder size. */
    val isEmpty: Boolean get() = drawGlyphs == null

    /** Draws the outline (if any) and then the fill at block-local coordinates. */
    fun draw(canvas: Canvas) {
        val glyphs = drawGlyphs ?: return
        if (spec.strokeWidthPx > 0f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = spec.strokeWidthPx * 2f // centered on the glyph edge; the fill covers the inner half
            paint.color = spec.strokeColor
            glyphs(canvas, paint)
            paint.style = Paint.Style.FILL
            paint.color = spec.color
        }
        glyphs(canvas, paint)
    }
}

/** Builds [TextBlock]s (StaticLayout for horizontal text, manual glyph placement for vertical). */
object TextRenderer {

    fun typeface(spec: TextSpec): Typeface {
        val style = when {
            spec.bold && spec.italic -> Typeface.BOLD_ITALIC
            spec.bold -> Typeface.BOLD
            spec.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return Typeface.create(Typeface.create(spec.font.family, Typeface.NORMAL), style)
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

    /** Lays out [text] with [spec]. */
    fun layout(text: String, spec: TextSpec): TextBlock =
        if (spec.vertical) layoutVertical(text, spec) else layoutHorizontal(text, spec)

    private fun inkPad(spec: TextSpec) = spec.strokeWidthPx + spec.sizePx * (if (spec.italic || spec.font == TextFont.CURSIVE) 0.5f else 0.3f) + 2f

    private fun layoutHorizontal(text: String, spec: TextSpec): TextBlock {
        val paint = newPaint(spec).apply { letterSpacing = spec.letterSpacing }
        val fm = paint.fontMetrics
        if (text.isEmpty()) {
            return TextBlock(spec.sizePx * 0.6f, (fm.descent - fm.ascent) * max(1f, spec.lineSpacing), inkPad(spec), spec, paint, null)
        }
        val width = ceil(Layout.getDesiredWidth(text, paint)).toInt() + 1
        val align = when (spec.align) {
            TextAlign.START -> Layout.Alignment.ALIGN_NORMAL
            TextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
            TextAlign.END -> Layout.Alignment.ALIGN_OPPOSITE
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(align)
            .setLineSpacing(0f, spec.lineSpacing)
            .setIncludePad(false)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
        return TextBlock(width.toFloat(), max(1f, layout.height.toFloat()), inkPad(spec), spec, paint) { c, _ -> layout.draw(c) }
    }

    private fun layoutVertical(text: String, spec: TextSpec): TextBlock {
        val paint = newPaint(spec).apply { textAlign = Paint.Align.CENTER }
        val em = spec.sizePx
        val res = VerticalTextLayout.layout(text, em, spec.letterSpacing, spec.lineSpacing, spec.align) { paint.measureText(it) }
        val height = if (res.height > 0f) res.height else em
        if (res.glyphs.isEmpty()) return TextBlock(res.width, height, inkPad(spec), spec, paint, null)
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
        return TextBlock(res.width, height, inkPad(spec), spec, paint) { c, p ->
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
        }
    }

    /** Block-local -> document matrix of [item] (rotation around the block center). */
    fun matrix(item: TextItem, block: TextBlock, out: Matrix = Matrix()): Matrix = out.apply {
        setTranslate(-block.width / 2f, -block.height / 2f)
        postRotate(item.rotationDeg)
        postTranslate(item.cx, item.cy)
    }

    /** Document-space bounds of everything the item may paint. */
    fun docBounds(item: TextItem, block: TextBlock): RectF {
        val r = RectF(-block.inkPad, -block.inkPad, block.width + block.inkPad, block.height + block.inkPad)
        matrix(item, block).mapRect(r)
        return r
    }

    private val dstIn = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }

    /**
     * Draws [item] into a canvas in DOCUMENT coordinates, clipped to [selection] (soft mask) when
     * given. [previewMode] adds a color filter approximating the document color mode (previews
     * only; committed pixels are constrained exactly by ColorModeOps).
     */
    fun drawItem(canvas: Canvas, item: TextItem, block: TextBlock, selection: Selection?, previewMode: ColorMode = ColorMode.RGB) {
        if (block.isEmpty) return
        val filter = colorModePaint(previewMode)
        val bounds = docBounds(item, block)
        val save = if (selection != null || filter != null) canvas.saveLayer(bounds, filter) else canvas.save()
        canvas.save()
        canvas.concat(matrix(item, block))
        block.draw(canvas)
        canvas.restore()
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
