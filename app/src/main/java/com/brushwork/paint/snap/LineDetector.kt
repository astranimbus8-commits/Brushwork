package com.brushwork.paint.snap

import android.graphics.Bitmap
import android.graphics.Rect
import com.brushwork.paint.tools.transform.SnapAxis

/**
 * A straight horizontal or vertical line found in a layer's pixels (document px), e.g. one of
 * the lines of the Table (Count) / Table (Size) filters, a frame border or the side of a box.
 */
data class DetectedLine(
    /** [SnapAxis.Y] = a horizontal line at y = [pos]; [SnapAxis.X] = a vertical line at x = [pos]. */
    val axis: SnapAxis,
    /**
     * Middle of the line across its thickness, in pixel-edge coordinates: a line covering rows
     * 38..41 (y from 38.0 to 42.0) is at 40.0, where the Table filter centered it.
     */
    val pos: Float,
    /** Where the line starts / ends along its length (pixel edges, start < end). */
    val start: Float,
    val end: Float,
    /** Thickness across the line (px). */
    val thickness: Float,
)

/**
 * Finds the long straight horizontal and vertical lines in a layer, so tools can snap to them
 * ("snap to objects"): table lines, frame borders, the sides of drawn boxes. Works on
 * transparent layers AND on opaque ones (a table drawn on a white background) because it looks
 * at color differences between neighbouring rows / columns, not at alpha. Pure computation,
 * safe off the main thread.
 *
 * Contract (see LineDetectorTest):
 *  - every line of a Table (Count) / Table (Size) filter result is found, with [DetectedLine.pos]
 *    within 0.5 px of the layout edge it was drawn on, for line thicknesses 1..100 px, with
 *    Space = 0 (lines cross) and Space > 0, on transparent and opaque backgrounds;
 *  - thin lines give ONE line at their middle (not one per side); thick bands may also give
 *    their two sides;
 *  - short runs (text, noise) are ignored: a line must be long relative to the canvas;
 *  - at most [MAX_LINES_PER_AXIS] lines per axis (the longest);
 *  - a blank or uniformly filled layer gives no lines.
 */
object LineDetector {
    /** At most this many lines per axis are returned (the longest ones). */
    const val MAX_LINES_PER_AXIS = 256

    /**
     * Lines in a [width] x [height] image whose rows [readRows] reads: it fills `out` with
     * `rows * width` ARGB colors (non-premultiplied, row-major, like Bitmap.getPixels) of rows
     * `y0 until y0 + rows`. Coordinates of the result are relative to that image. [cancelled]
     * is polled now and then (an empty or partial result is fine once it returns true).
     */
    fun detect(
        width: Int,
        height: Int,
        readRows: (y0: Int, rows: Int, out: IntArray) -> Unit,
        cancelled: () -> Boolean = { false },
    ): List<DetectedLine> {
        // Implemented by the snap-lines work (v1.4).
        return emptyList()
    }

    /**
     * Lines in [bitmap] (document px), looking only inside [region] (e.g. the layer's content
     * bounds; null = everything).
     */
    fun detect(bitmap: Bitmap, region: Rect? = null, cancelled: () -> Boolean = { false }): List<DetectedLine> {
        val area = Rect(0, 0, bitmap.width, bitmap.height)
        if (region != null && !area.intersect(region)) return emptyList()
        if (area.isEmpty) return emptyList()
        val lines = detect(area.width(), area.height(), { y0, rows, out ->
            bitmap.getPixels(out, 0, area.width(), area.left, area.top + y0, area.width(), rows)
        }, cancelled)
        if (area.left == 0 && area.top == 0) return lines
        return lines.map { l ->
            if (l.axis == SnapAxis.Y) l.copy(pos = l.pos + area.top, start = l.start + area.left, end = l.end + area.left)
            else l.copy(pos = l.pos + area.left, start = l.start + area.top, end = l.end + area.top)
        }
    }
}
