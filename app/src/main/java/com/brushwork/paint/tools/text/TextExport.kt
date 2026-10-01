package com.brushwork.paint.tools.text

import android.graphics.Path
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer

/**
 * Text layers for SVG / PDF export (v1.5 §4.10; owned by A7, API used by A8). Foundation (F1):
 * nothing is laid out yet ([lines] and [outlines] return null: the exporter falls back to the
 * layer's pixels).
 */
object TextExport {
    /**
     * The laid-out lines of horizontal straight text [item] (wrapped or not), in the block's
     * local coordinates (see [TextRunPaint.matrix]); null for vertical text, text on a path, or
     * when it can't be laid out.
     */
    fun lines(item: TextItem): List<TextLineRun>? = null

    /** The glyph outlines of text layer [layer] in document px (any text: vertical, on a path...); null when unavailable. */
    fun outlines(doc: Document, layer: Layer): Path? = null
}

/** One laid-out line: [text] drawn with its left end at ([x], [baseline]) in the block's local coordinates. */
data class TextLineRun(val text: String, val x: Float, val baseline: Float, val paintSpec: TextRunPaint)

/**
 * How a [TextLineRun] is painted: the text object's [spec] (font, size, colors, outline...) and
 * the affine [matrix] (6 values a, b, c, d, e, f as in SVG's `matrix()`) mapping the block's
 * local coordinates to document px (position and rotation of the text item).
 */
data class TextRunPaint(val spec: TextSpec, val matrix: List<Float>)
