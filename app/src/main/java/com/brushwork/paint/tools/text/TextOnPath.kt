package com.brushwork.paint.tools.text

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.brushwork.paint.core.Vec2
import kotlin.math.max

/**
 * Text on a path (line, circle, rectangle, Bezier curve): layout, rendering, bounds and the
 * on-canvas handles that shape the path. Everything is in DOCUMENT coordinates.
 *
 * The text is one line (line breaks become spaces), laid out with the fill paint's typeface, size
 * and letter spacing. [TextPathMode.BEND] bends the letter outlines along the path;
 * [TextPathMode.ROTATE] keeps every letter's shape and turns it to the path. Color emoji have no
 * outline and are always placed like rotated letters. Which way letters face and which side of a
 * closed shape they stand on is described in `TextPathGeometry.kt`.
 *
 * Layouts and bent outlines are cached for the last two inputs, so redrawing an unchanged text is
 * cheap and dragging a handle only re-bends the outline. Call from one thread at a time per paint.
 */
object TextOnPath {
    /**
     * Draws [text] (one line; newlines are treated as spaces) along [spec]'s path. [fill] carries
     * typeface, text size, letter spacing and fill color; [stroke] (optional) is an outline drawn
     * behind the fill with the same typeface/size. Returns the document-space bounds of what was
     * drawn (empty when nothing was).
     *
     * [stroke] is used as given (set its style to STROKE and its width). Neither paint is changed
     * (their text alignment is set to LEFT while drawing and restored).
     */
    fun draw(canvas: Canvas, text: String, fill: Paint, stroke: Paint?, spec: TextPathSpec): RectF =
        TextOnPathEngine.draw(canvas, text, fill, stroke, spec)

    /** Bounds [draw] would cover (for hit testing and invalidation). */
    fun bounds(text: String, fill: Paint, stroke: Paint?, spec: TextPathSpec): RectF =
        TextOnPathEngine.bounds(text, fill, stroke, spec)

    /**
     * The guide path (thin line shown while editing): the shape itself, plus the control arms of
     * a curve and the stem of a rectangle's rotation handle.
     */
    fun guide(spec: TextPathSpec): Path {
        val s = TextPathGeometry.sanitized(spec)
        val p = Path()
        when (s.type) {
            TextPathType.NONE -> {}
            TextPathType.LINE -> {
                p.moveTo(s.x1, s.y1)
                p.lineTo(s.x2, s.y2)
            }
            TextPathType.CIRCLE -> p.addCircle(s.cx, s.cy, max(s.radius, TextPathGeometry.MIN_EXTENT), Path.Direction.CW)
            TextPathType.RECT -> {
                val hw = max(s.width, TextPathGeometry.MIN_EXTENT) / 2f
                val hh = max(s.height, TextPathGeometry.MIN_EXTENT) / 2f
                val r = s.cornerRadius.coerceIn(0f, minOf(hw, hh))
                p.addRoundRect(RectF(-hw, -hh, hw, hh), r, r, Path.Direction.CW)
                p.moveTo(hw, 0f)
                p.lineTo(hw + TextPathGeometry.rotationStem(s), 0f)
                p.transform(Matrix().apply { setRotate(s.rotationDeg); postTranslate(s.cx, s.cy) })
            }
            TextPathType.CURVE -> {
                p.moveTo(s.x1, s.y1)
                p.cubicTo(s.cx1, s.cy1, s.cx2, s.cy2, s.x2, s.y2)
                p.moveTo(s.x1, s.y1)
                p.lineTo(s.cx1, s.cy1)
                p.moveTo(s.x2, s.y2)
                p.lineTo(s.cx2, s.cy2)
            }
        }
        return p
    }

    /**
     * Draggable handles that shape the path, in a stable order for [moveHandle]:
     * - LINE: 0 start, 1 end, 2 middle (moves the line);
     * - CIRCLE: 0 center (moves it), 1 radius (on the circle opposite the text), 2 text position
     *   (on the circle, where the text is anchored);
     * - RECT: 0 center (moves it), 1 size (bottom-right corner, resizes around the center; keeps
     *   it square with [TextPathSpec.keepSquare]), 2 rotation (beyond the right edge), 3 corner
     *   radius (inside the top-left corner), 4 text position (on the outline);
     * - CURVE: 0 start, 1 first control point, 2 second control point, 3 end, 4 middle (moves it).
     */
    fun handles(spec: TextPathSpec): List<Vec2> = TextPathGeometry.handles(spec)

    /** [spec] with handle [index] (see [handles]) moved to [pos]. */
    fun moveHandle(spec: TextPathSpec, index: Int, pos: Vec2): TextPathSpec = TextPathGeometry.moveHandle(spec, index, pos)

    /** [spec] moved by [translation], scaled by [scale] and rotated by [rotationDeg] around [pivot]. */
    fun transformed(spec: TextPathSpec, translation: Vec2, scale: Float, rotationDeg: Float, pivot: Vec2): TextPathSpec =
        TextPathGeometry.transformed(spec, translation, scale, rotationDeg, pivot)

    /**
     * A sensible path of [type] for text whose plain (straight) layout is centered at [center]
     * and [textWidth] wide with [fontSize] (used when the user switches the path type).
     */
    fun defaultFor(type: TextPathType, center: Vec2, textWidth: Float, fontSize: Float, current: TextPathSpec): TextPathSpec =
        TextPathGeometry.defaultFor(type, center, textWidth, fontSize, current)
}
