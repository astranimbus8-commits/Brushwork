package com.brushwork.paint.tools.text

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.brushwork.paint.core.Vec2

// STUB — replaced by the text-path engine. Keep every public signature.
/**
 * Text on a path (line, circle, rectangle, Bezier curve): layout, rendering, bounds and the
 * on-canvas handles that shape the path. Everything is in DOCUMENT coordinates.
 */
object TextOnPath {
    /**
     * Draws [text] (one line; newlines are treated as spaces) along [spec]'s path. [fill] carries
     * typeface, text size, letter spacing and fill color; [stroke] (optional) is an outline drawn
     * behind the fill with the same typeface/size. Returns the document-space bounds of what was
     * drawn (empty when nothing was).
     */
    fun draw(canvas: Canvas, text: String, fill: Paint, stroke: Paint?, spec: TextPathSpec): RectF = RectF()

    /** Bounds [draw] would cover (for hit testing and invalidation). */
    fun bounds(text: String, fill: Paint, stroke: Paint?, spec: TextPathSpec): RectF = RectF()

    /** The guide path (thin line shown while editing). */
    fun guide(spec: TextPathSpec): Path = Path()

    /** Draggable handles that shape the path, in a stable order for [moveHandle]. */
    fun handles(spec: TextPathSpec): List<Vec2> = emptyList()

    /** [spec] with handle [index] (see [handles]) moved to [pos]. */
    fun moveHandle(spec: TextPathSpec, index: Int, pos: Vec2): TextPathSpec = spec

    /** [spec] moved by [translation], scaled by [scale] and rotated by [rotationDeg] around [pivot]. */
    fun transformed(spec: TextPathSpec, translation: Vec2, scale: Float, rotationDeg: Float, pivot: Vec2): TextPathSpec = spec

    /**
     * A sensible path of [type] for text whose plain (straight) layout is centered at [center]
     * and [textWidth] wide with [fontSize] (used when the user switches the path type).
     */
    fun defaultFor(type: TextPathType, center: Vec2, textWidth: Float, fontSize: Float, current: TextPathSpec): TextPathSpec =
        current.copy(type = type, cx = center.x, cy = center.y)
}
