package com.brushwork.paint.tools.text

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import kotlin.math.max
import kotlin.math.min

/**
 * Text layers for SVG / PDF export (v1.5 §4.10; owned by A7, used by A8): the laid-out lines of
 * horizontal text (to write real, selectable text), the glyph outlines of any text (vertical, on a
 * path...) and every painted part with its color ([outlineParts]: box, outline stroke, letters),
 * all exactly where the text layer's pixels are.
 */
object TextExport {
    /**
     * The laid-out lines of horizontal straight text [item] (wrapped around a picture or not),
     * in the block's local coordinates: x and baseline include the box inset, and
     * [TextRunPaint.matrix] maps them to document px (position and rotation of the item).
     * Draw each run with [TextRunPaint.spec]'s font, size, letter spacing and colors (the outline,
     * [TextSpec.strokeWidthPx], is centered on the glyph edges and drawn under the fill).
     *
     * The lines are the letters only: a text whose box draws something ([TextBoxSpec.hasFrame]:
     * background, border) gets that box from [outlineParts] (its [TextOutlinePart.Kind.BOX_FILL] and
     * [TextOutlinePart.Kind.BOX_BORDER] parts, drawn under the lines).
     *
     * Null for vertical text and text on a path (use [outlines] / [outlineParts]). Empty for an
     * empty text.
     *
     * v1.6: null (the FIRST check, before anything is laid out) for text with letter scaling
     * on: its letters have their own sizes and places, which a run of one font size can't
     * carry, so exporters use [outlineParts] and the look stays exact.
     *
     * v1.7 (item 17): null too, before anything is laid out, for text with a manual kern that
     * applies to its own characters ([kerned]) and for text with "Font kerning" off
     * ([TextSpec.fontKerning]): a run of text can carry neither (an SVG viewer or PDF reader would
     * set the letters with the font's kerning), so they export as outlines.
     */
    fun lines(item: TextItem): List<TextLineRun>? {
        if (item.spec.letterScale.isOn) return null
        if (!item.spec.fontKerning || kerned(item)) return null
        if (item.spec.vertical || item.path.isActive) return null
        val prep = TextRenderer.prepare(item)
        val block = prep.block ?: return null
        val paint = TextRunPaint(item.spec, affine(TextRenderer.matrix(item, block)))
        if (block.isEmpty) return emptyList()
        val inset = block.inset
        val text = item.text
        block.wrapLines?.let { lines ->
            return lines.filter { it.end > it.start }.map { TextLineRun(text.substring(it.start, it.end), it.x + inset, it.baseline + inset, paint) }
        }
        val layout = block.staticLayout ?: return null
        val out = ArrayList<TextLineRun>(layout.lineCount)
        for (i in 0 until layout.lineCount) {
            val s = layout.getLineStart(i)
            val e = layout.getLineVisibleEnd(i)
            if (e <= s) continue
            out += TextLineRun(text.substring(s, e), TextRenderer.staticLineX(layout, i) + inset, layout.getLineBaseline(i) + inset, paint)
        }
        return out
    }

    /**
     * v1.7: whether a manual kern of [item] moves letters of its own characters (the renderer's
     * rule, [TextKerns.advancesPx]: a frame of a linked story looks at its slice of the story).
     * Kerns that don't apply (vertical text, a right-to-left paragraph, beside a line break) leave
     * the text exportable as runs.
     */
    fun kerned(item: TextItem): Boolean {
        if (item.kerns.isEmpty() || item.spec.vertical) return false
        val th = item.thread
        val source = if (th.isOn) th.story else item.text
        val from = if (th.isOn) th.start.coerceIn(0, source.length) else 0
        val px = TextKerns.advancesPx(source, item.kerns, from, item.spec.sizePx) ?: return false
        val n = if (th.isOn) (th.end - from).coerceIn(0, px.size) else px.size
        for (i in 0 until n) if (px[i] != 0f) return true
        return false
    }

    /**
     * The glyph outlines of text layer [layer] in document px (any text: horizontal, wrapped,
     * vertical, on a path), to fill with the text's color ([TextSpec.color]); null when [layer] is
     * not a text layer or draws no letters. The letters only: the box (background, border) and the
     * text's outline stroke have other colors and come from [outlineParts], which gives every part
     * of the exact look with its color.
     */
    fun outlines(doc: Document, layer: Layer): Path? {
        val item = TextCodec.decode(layer.textData) ?: return null
        return outlineParts(item)?.lastOrNull { it.kind == TextOutlinePart.Kind.TEXT }?.path
    }

    /**
     * Everything [item] paints as ONE outline in document px (box, outline stroke and letters
     * united), e.g. for a clip or a hit area; null when nothing is drawn. Its parts have different
     * colors: to draw the text, use [outlineParts].
     */
    fun coverage(item: TextItem): Path? {
        val parts = outlineParts(item) ?: return null
        if (parts.size == 1) return Path(parts[0].path)
        var union: Path? = null
        for (part in parts) {
            val u = union
            union = if (u == null) Path(part.path) else Path().also { if (!it.op(u, part.path, Path.Op.UNION)) { it.set(u); it.addPath(part.path) } }
        }
        return union
    }

    /**
     * What [item] paints as filled outlines in document px, in painting order (each filled with
     * its [TextOutlinePart.color], non-zero winding): the box's background, its border, the text
     * outline (stroke) and the letters. Null when nothing is drawn. Color emoji have no outline.
     */
    fun outlineParts(item: TextItem): List<TextOutlinePart>? {
        if (item.text.isEmpty()) return null
        val spec = item.spec
        val prep = TextRenderer.prepare(item)
        val out = ArrayList<TextOutlinePart>(4)
        val block = prep.block
        if (block == null) {
            val paints = prep.paints ?: TextRenderer.pathPaints(spec)
            val (glyphs, stroke) = TextOnPathEngine.outlines(item.text, paints.fill, paints.stroke, item.path, TextRenderer.pathLetters(item), item.kerns) ?: return null
            if (stroke != null) out += TextOutlinePart(stroke, spec.strokeColor, TextOutlinePart.Kind.TEXT_OUTLINE)
            out += TextOutlinePart(glyphs, spec.color, TextOutlinePart.Kind.TEXT)
            return out
        }
        if (block.isEmpty) return null
        val m = TextRenderer.matrix(item, block)
        val box = spec.box
        if (box.fill) {
            val r = block.boxRadius
            val p = Path().apply { addRoundRect(RectF(0f, 0f, block.width, block.height), r, r, Path.Direction.CW) }
            p.transform(m)
            out += TextOutlinePart(p, box.fillColor, TextOutlinePart.Kind.BOX_FILL)
        }
        if (box.borderWidth > 0f) {
            // As TextBlock draws it: a stroke inside the box edge.
            val r = block.boxRadius
            val h = min(box.borderWidth, min(block.width, block.height)) / 2f
            val ri = max(0f, r - h)
            val edge = Path().apply { addRoundRect(RectF(h, h, block.width - h, block.height - h), ri, ri, Path.Direction.CW) }
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = h * 2f
                strokeJoin = Paint.Join.MITER
            }
            val p = Path()
            stroke.getFillPath(edge, p)
            p.transform(m)
            out += TextOutlinePart(p, box.borderColor, TextOutlinePart.Kind.BOX_BORDER)
        }
        val glyphs = block.glyphOutline() ?: return out.ifEmpty { null }
        if (spec.strokeWidthPx > 0f) {
            val stroke = Paint(block.textPaint).apply {
                style = Paint.Style.STROKE
                strokeWidth = spec.strokeWidthPx * 2f
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }
            val p = Path()
            stroke.getFillPath(glyphs, p)
            p.transform(m)
            out += TextOutlinePart(p, spec.strokeColor, TextOutlinePart.Kind.TEXT_OUTLINE)
        }
        glyphs.transform(m)
        out += TextOutlinePart(glyphs, spec.color, TextOutlinePart.Kind.TEXT)
        return out
    }

    /** The 6 affine values (a, b, c, d, e, f as in SVG's `matrix()`) of [m]. */
    private fun affine(m: Matrix): List<Float> {
        val v = FloatArray(9)
        m.getValues(v)
        return listOf(v[Matrix.MSCALE_X], v[Matrix.MSKEW_Y], v[Matrix.MSKEW_X], v[Matrix.MSCALE_Y], v[Matrix.MTRANS_X], v[Matrix.MTRANS_Y])
    }
}

/** One laid-out line: [text] drawn with its left end at ([x], [baseline]) in the block's local coordinates. */
data class TextLineRun(val text: String, val x: Float, val baseline: Float, val paintSpec: TextRunPaint)

/**
 * How a [TextLineRun] is painted: the text object's [spec] (font, size, colors, outline...) and
 * the affine [matrix] (6 values a, b, c, d, e, f as in SVG's `matrix()`) mapping the block's
 * local coordinates to document px (position and rotation of the text item).
 */
data class TextRunPaint(val spec: TextSpec, val matrix: List<Float>)

/** One filled outline of a text (document px, non-zero winding) and its [color]; see [TextExport.outlineParts]. */
class TextOutlinePart(val path: Path, val color: Int, val kind: Kind) {
    enum class Kind { BOX_FILL, BOX_BORDER, TEXT_OUTLINE, TEXT }
}
