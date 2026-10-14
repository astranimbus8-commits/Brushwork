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
     *
     * v1.6: [letters] scales the letters progressively (per-cluster size and advance; Center and
     * Top move the smaller letters along the path's normal). The caller checks the script can be
     * drawn per cluster (`LetterRamp.supports`); null = plain letters.
     *
     * v1.7: [kerns] ([TextItem.kerns], indices of [text]) move the letters after their gaps
     * along the path, by `value / 1000` em (scaled with the letter before the gap). They apply
     * on a left-to-right line drawn one cluster at a time (as scaled letters are, so without
     * ligatures); a text holding right-to-left or shaped script ignores them.
     *
     * v1.9 (R5): [mode] (“Pair kerning” Metrics or Optical, [KerningModes]; null for Auto and Off)
     * moves the letters after their gaps along the path as kerns do, on the same lines.
     */
    fun draw(
        canvas: Canvas,
        text: String,
        fill: Paint,
        stroke: Paint?,
        spec: TextPathSpec,
        letters: LetterScaleSpec? = null,
        kerns: List<TextKern> = emptyList(),
        mode: TextKerningMode? = null,
    ): RectF = TextOnPathEngine.draw(canvas, text, fill, stroke, spec, letters, kerns, mode)

    /** Bounds [draw] would cover (for hit testing and invalidation). */
    fun bounds(text: String, fill: Paint, stroke: Paint?, spec: TextPathSpec, letters: LetterScaleSpec? = null, kerns: List<TextKern> = emptyList(), mode: TextKerningMode? = null): RectF =
        TextOnPathEngine.bounds(text, fill, stroke, spec, letters, kerns, mode)

    /**
     * The guide path (thin line shown while editing): the shape itself, plus the control arms of
     * a curve and the stem of a rectangle's rotation handle. v1.9 (R12): with nodes, the curve
     * through them ([TextPathNodes.guide]) and, for path nodes, the lines between the nodes.
     */
    fun guide(spec: TextPathSpec): Path {
        val s = TextPathGeometry.sanitized(spec)
        val p = Path()
        val nodes = if (s.type == TextPathType.NONE) null else TextPathNodes.of(s)
        if (nodes != null) {
            val g = TextPathNodes.guide(nodes)
            for (i in 0 until g.vertexCount) {
                val v = g.vertex(i)
                if (i == 0) p.moveTo(v.x, v.y) else p.lineTo(v.x, v.y)
            }
            if (g.closed) p.close()
            if (!TextPathNodes.isPolyline(nodes)) {
                val pts = nodes.points
                p.moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) p.lineTo(pts[i].x, pts[i].y)
                if (TextPathNodes.isClosed(nodes)) p.close()
            }
            return p
        }
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
