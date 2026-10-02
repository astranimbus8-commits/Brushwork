package com.brushwork.paint.tools.text.frames

import android.graphics.RectF
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.text.TextBoxSpec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextSpec
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Where a linked text frame is (v1.6, §3.6; area D). A frame's text area is its
 * `spec.box.width` (whole pixels, as `TextRenderer.frameLayout` lays it out) by its
 * `spec.box.minHeight`; its OUTER box adds the padding and border ([TextBoxSpec.inset]) on every
 * side and is centred on (`cx`, `cy`). Frames are never rotated (§7: frame rotation is deferred),
 * so every box here is axis-aligned, in document px.
 */
object FrameGeometry {

    /** Width of [item]'s text area as it is laid out: `ceil(box.width)`, at least 1 px. */
    fun contentWidth(item: TextItem): Float = max(1f, ceil(item.spec.box.width))

    /** Height of [item]'s text area: `box.minHeight`, at least 1 px. */
    fun contentHeight(item: TextItem): Float = max(1f, item.spec.box.minHeight)

    /**
     * The outer box of frame [item] (text area + padding + border), into [out]: exactly the box
     * `TextRenderer` draws the frame's block in (its width is `ceil(box.width) + 2 × inset`, its
     * height `max(1, minHeight) + 2 × inset`, centred on the item).
     */
    fun outerRect(item: TextItem, out: RectF = RectF()): RectF {
        val inset = item.spec.box.inset
        val w = contentWidth(item) + 2f * inset
        val h = contentHeight(item) + 2f * inset
        out.set(item.cx - w / 2f, item.cy - h / 2f, item.cx + w / 2f, item.cy + h / 2f)
        return out
    }

    /**
     * [template] placed so its OUTER box is the doc rect ([left], [top]) - ([right], [bottom]) as
     * closely as whole-pixel widths allow: the text area becomes `round(width − 2·inset)` × the
     * height minus `2·inset` (each at least [minContent] px), and the edges named by [keepRight] /
     * [keepBottom] (else the left / top edge) stay exactly where they are. The frame is unrotated.
     */
    fun placed(
        template: TextItem,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        keepRight: Boolean = false,
        keepBottom: Boolean = false,
        minContent: Float = 1f,
    ): TextItem {
        val inset = template.spec.box.inset
        val l = minOf(left, right); val r = maxOf(left, right)
        val t = minOf(top, bottom); val b = maxOf(top, bottom)
        val cw = max(max(1f, ceil(minContent)), ((r - l) - 2f * inset).roundToInt().toFloat())
        val ch = max(max(1f, minContent), (b - t) - 2f * inset)
        val w = cw + 2f * inset
        val h = ch + 2f * inset
        val cx = if (keepRight) r - w / 2f else l + w / 2f
        val cy = if (keepBottom) b - h / 2f else t + h / 2f
        val box = template.spec.box.copy(width = cw, minHeight = ch, height = 0f, minWidth = 0f)
        return template.copy(spec = template.spec.copy(box = box, vertical = false), cx = cx, cy = cy, rotationDeg = 0f)
    }

    /** [spec] with the frame size of [box] (text area width and height); everything else from [spec]. */
    fun withFrameBox(spec: TextSpec, box: TextBoxSpec): TextSpec =
        spec.copy(box = spec.box.copy(width = box.width, minHeight = box.minHeight, height = 0f, minWidth = 0f), vertical = false)

    /**
     * The look a story shares across its frames: [spec] without the per-frame text area size (and
     * never vertical: frames are horizontal).
     */
    fun storyLook(spec: TextSpec): TextSpec =
        spec.copy(box = spec.box.copy(width = 0f, minHeight = 0f, height = 0f, minWidth = 0f), vertical = false)

    /** One of the 8 resize handles of a frame: [dx] / [dy] are −1 (left / top), 0 (middle) or 1 (right / bottom). */
    enum class Handle(val dx: Int, val dy: Int) {
        TOP_LEFT(-1, -1), TOP(0, -1), TOP_RIGHT(1, -1), RIGHT(1, 0),
        BOTTOM_RIGHT(1, 1), BOTTOM(0, 1), BOTTOM_LEFT(-1, 1), LEFT(-1, 0);

        /** Where this handle is on [r] (document px). */
        fun at(r: RectF): Vec2 = Vec2(
            when (dx) { -1 -> r.left; 1 -> r.right; else -> r.centerX() },
            when (dy) { -1 -> r.top; 1 -> r.bottom; else -> r.centerY() },
        )
    }

    /**
     * [r] with the edges [h] moves dragged to [to] (document px), the other edges kept; a dragged
     * edge never crosses its opposite edge closer than [minW] / [minH].
     */
    fun resized(r: RectF, h: Handle, to: Vec2, minW: Float, minH: Float): RectF {
        val out = RectF(r)
        when (h.dx) {
            -1 -> out.left = minOf(to.x, r.right - minW)
            1 -> out.right = maxOf(to.x, r.left + minW)
        }
        when (h.dy) {
            -1 -> out.top = minOf(to.y, r.bottom - minH)
            1 -> out.bottom = maxOf(to.y, r.top + minH)
        }
        return out
    }

    /** True when [a] and [b] are the same box within [eps] px. */
    fun sameRect(a: RectF, b: RectF, eps: Float = 1e-3f): Boolean =
        abs(a.left - b.left) <= eps && abs(a.top - b.top) <= eps && abs(a.right - b.right) <= eps && abs(a.bottom - b.bottom) <= eps
}
