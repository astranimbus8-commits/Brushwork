package com.brushwork.paint.snap

import android.graphics.Bitmap
import android.graphics.Rect
import com.brushwork.paint.tools.transform.SnapAxis
import kotlin.math.max
import kotlin.math.min

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
 * safe off the main thread; reads the image in strips (no full-size copy). See [LineScanner]
 * for how.
 *
 * Contract (see LineDetectorTest):
 *  - every line of a Table (Count) / Table (Size) filter result is found, with [DetectedLine.pos]
 *    within 0.5 px of the layout edge it was drawn on, for line thicknesses 1..100 px, with
 *    Space = 0 (lines cross) and Space > 0, on transparent and opaque backgrounds (a line cut by
 *    the image border is found on that border);
 *  - thin lines give ONE line at their middle (not one per side); thick bands may also give
 *    their two sides;
 *  - short runs (text, noise) are ignored: a line must be long relative to the canvas
 *    ([minLength]);
 *  - at most [MAX_LINES_PER_AXIS] lines per axis (the longest);
 *  - a blank or uniformly filled layer gives no lines.
 */
object LineDetector {
    /** At most this many lines per axis are returned (the longest ones). */
    const val MAX_LINES_PER_AXIS = 256

    /**
     * Shortest line found in a [width] x [height] image (px): 4% of its shorter side, at least
     * 24 px (a 2048 px canvas: 82 px, the phone's 1080 x 2408 screen size: 43 px). Lines cut
     * by crossing lines count their pieces together.
     */
    fun minLength(width: Int, height: Int): Float = max(MIN_LENGTH_PX, MIN_LENGTH_FRACTION * min(width, height))

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
        if (width <= 0 || height <= 0) return emptyList()
        return LineScanner(width, height, minLength(width, height)).run(readRows, cancelled)
    }

    /**
     * Lines in [bitmap] (document px), looking only inside [region] (e.g. the layer's content
     * bounds; null = everything). Line lengths are judged against the whole bitmap.
     */
    fun detect(bitmap: Bitmap, region: Rect? = null, cancelled: () -> Boolean = { false }): List<DetectedLine> {
        val full = Rect(0, 0, bitmap.width, bitmap.height)
        val area = Rect(full)
        if (region != null && !area.intersect(region)) return emptyList()
        if (area.isEmpty) return emptyList()
        // One more row / column on each side where the bitmap has one: an edge right on the
        // region's border (e.g. a table's outer line at the content bounds) is then seen as such.
        val read = Rect(area.left - 1, area.top - 1, area.right + 1, area.bottom + 1)
        read.intersect(full)
        var borders = 0
        if (read.top == 0) borders = borders or LineScanner.BORDER_TOP
        if (read.bottom == full.bottom) borders = borders or LineScanner.BORDER_BOTTOM
        if (read.left == 0) borders = borders or LineScanner.BORDER_LEFT
        if (read.right == full.right) borders = borders or LineScanner.BORDER_RIGHT
        val w = read.width()
        val lines = LineScanner(w, read.height(), minLength(bitmap.width, bitmap.height), borders).run({ y0, rows, out ->
            bitmap.getPixels(out, 0, w, read.left, read.top + y0, w, rows)
        }, cancelled)
        if (read.left == 0 && read.top == 0) return lines
        return lines.map { l ->
            if (l.axis == SnapAxis.Y) l.copy(pos = l.pos + read.top, start = l.start + read.left, end = l.end + read.left)
            else l.copy(pos = l.pos + read.left, start = l.start + read.top, end = l.end + read.top)
        }
    }

    private const val MIN_LENGTH_PX = 24f
    private const val MIN_LENGTH_FRACTION = 0.04f
}
