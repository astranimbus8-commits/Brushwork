package com.brushwork.paint.tools.text

import android.graphics.Bitmap
import android.graphics.Rect
import com.brushwork.paint.core.Parallel
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.select.MarchingSquares
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The outline text wraps around (v1.5 §4.1c): a layer's opaque pixels (alpha × mask), as a few
 * hundred points of polygon in DOCUMENT pixels.
 *
 * 1. [scan]: alpha × mask is read in strips (several threads, like `ContentBounds`) into a grid of
 *    at most [MAX_GRID] cells on the long side; a cell is "covered" when ANY of its pixels reaches
 *    [ALPHA_THRESHOLD] (a max filter: thin lines survive the downsampling).
 * 2. [outline]: the grid is traced with [MarchingSquares]; holes are dropped (text never flows
 *    into a hole of the picture); every contour is simplified (Douglas-Peucker, [RDP_EPSILON_PX],
 *    coarser while the total is above [TextWrapSpec.MAX_POINTS]; the smallest contours become
 *    their bounding boxes, then are dropped, if that is not enough).
 *
 * The distance kept from the picture is NOT baked in: [WrapObstacle] dilates the stored outline
 * while laying out, so the distance can change without the picture (it may be deleted).
 */
object WrapContourBuilder {
    /** Longest side of the coverage grid. */
    const val MAX_GRID = 1024

    /** Opacity (alpha × mask, 0..255) from which a pixel blocks text. */
    const val ALPHA_THRESHOLD = 32

    /** Simplification tolerance of the outline (document px). */
    const val RDP_EPSILON_PX = 0.75f

    /** Coarsest simplification tried before contours are replaced by boxes (grid cells). */
    private const val MAX_EPSILON_CELLS = 8f

    /**
     * A coverage grid of a [docW] × [docH] layer: cell (gx, gy) covers document pixels
     * [gx × factor, (gx + 1) × factor) × [gy × factor, (gy + 1) × factor); 255 = covered.
     */
    class Grid(val cells: ByteArray, val gw: Int, val gh: Int, val factor: Int, val docW: Int, val docH: Int) {
        init { require(cells.size >= gw * gh) }

        /** Document bounds of the covered cells (left, top, right, bottom; exclusive), or null when none. */
        fun bounds(): IntArray? {
            var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = -1; var b = -1
            for (gy in 0 until gh) {
                val row = gy * gw
                for (gx in 0 until gw) {
                    if (cells[row + gx].toInt() == 0) continue
                    if (gx < l) l = gx
                    if (gx > r) r = gx
                    if (gy < t) t = gy
                    b = gy
                }
            }
            if (r < 0) return null
            return intArrayOf(l * factor, t * factor, min(docW, (r + 1) * factor), min(docH, (b + 1) * factor))
        }
    }

    /** What a layer's pixels give: their [bounds] (null = nothing opaque) and both kinds of outline. */
    class Outline(val bounds: Rect?, val shape: List<WrapPolygon>, val box: List<WrapPolygon>) {
        fun polygons(contour: WrapContour): List<WrapPolygon> = if (contour == WrapContour.BOX) box else shape

        companion object {
            val EMPTY = Outline(null, emptyList(), emptyList())
        }
    }

    /** Cell size (document px) of the grid for a [w] × [h] layer. */
    fun factorFor(w: Int, h: Int): Int = max(1, ceil(max(w, h).toDouble() / MAX_GRID).toInt())

    /**
     * The coverage grid of [bitmap] (premultiplied ARGB) times [mask] (grayscale, white =
     * visible; null = none). Reads strips on several threads; never copies the whole layer.
     */
    fun scan(bitmap: Bitmap, mask: Bitmap?): Grid {
        val w = bitmap.width
        val h = bitmap.height
        val f = factorFor(w, h)
        val gw = (w + f - 1) / f
        val gh = (h + f - 1) / f
        val cells = ByteArray(gw * gh)
        val m = mask?.takeIf { it.width == w && it.height == h && !it.isRecycled }
        // With a mask a pixel needs alpha × luminance ≥ threshold × 255; without, alpha ≥ threshold.
        val need = ALPHA_THRESHOLD * 255
        Parallel.forRange(gh, 1) { g0, g1 ->
            val buf = IntArray(w * f)
            val mbuf = if (m != null) IntArray(w * f) else null
            for (gy in g0 until g1) {
                val y0 = gy * f
                val rows = min(f, h - y0)
                bitmap.getPixels(buf, 0, w, 0, y0, w, rows)
                if (mbuf != null) m!!.getPixels(mbuf, 0, w, 0, y0, w, rows)
                val out = gy * gw
                for (gx in 0 until gw) {
                    val x0 = gx * f
                    val x1 = min(w, x0 + f)
                    var hit = false
                    var r = 0
                    while (r < rows && !hit) {
                        val base = r * w
                        var x = x0
                        while (x < x1) {
                            val a = buf[base + x] ushr 24
                            if (a >= ALPHA_THRESHOLD) {
                                if (mbuf == null) { hit = true; break }
                                val p = mbuf[base + x]
                                val luma = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
                                val ma = p ushr 24
                                if (a * (luma * ma / 255) >= need) { hit = true; break }
                            }
                            x++
                        }
                        r++
                    }
                    if (hit) cells[out + gx] = -1
                }
            }
        }
        return Grid(cells, gw, gh, f, w, h)
    }

    /** Both outlines of [grid] (see the class comment). */
    fun outline(grid: Grid, maxPoints: Int = TextWrapSpec.MAX_POINTS): Outline {
        val b = grid.bounds() ?: return Outline.EMPTY
        val rect = Rect(b[0], b[1], b[2], b[3])
        val box = listOf(
            WrapPolygon(
                listOf(rect.left.toFloat(), rect.right.toFloat(), rect.right.toFloat(), rect.left.toFloat()),
                listOf(rect.top.toFloat(), rect.top.toFloat(), rect.bottom.toFloat(), rect.bottom.toFloat()),
            )
        )
        return Outline(rect, polygons(grid.cells, grid.gw, grid.gh, grid.factor, maxPoints), box)
    }

    /**
     * Outline polygons (document px) of the covered cells of a [gw] × [gh] grid whose cells are
     * [factor] px: holes dropped, simplified to at most [maxPoints] points in total.
     */
    fun polygons(cells: ByteArray, gw: Int, gh: Int, factor: Int, maxPoints: Int = TextWrapSpec.MAX_POINTS): List<WrapPolygon> {
        // Traced only where something is: the covered cells' bounds (a picture usually fills a
        // small part of the canvas; outside them the grid is empty and has no contour).
        var l = gw; var t = gh; var r = -1; var b = -1
        for (gy in 0 until gh) {
            val row = gy * gw
            var first = -1
            var last = -1
            for (gx in 0 until gw) {
                if (cells[row + gx].toInt() != 0) {
                    if (first < 0) first = gx
                    last = gx
                }
            }
            if (first < 0) continue
            if (first < l) l = first
            if (last > r) r = last
            if (gy < t) t = gy
            b = gy
        }
        if (r < 0) return emptyList()
        val cw = r - l + 1
        val ch = b - t + 1
        val crop = if (cw == gw && ch == gh) cells else ByteArray(cw * ch).also { out ->
            for (gy in 0 until ch) System.arraycopy(cells, (t + gy) * gw + l, out, gy * cw, cw)
        }
        val traced = (MarchingSquares.contours(crop, cw, ch) ?: return emptyList()).onEach { pts ->
            if (l != 0 || t != 0) for (i in 0 until pts.size / 2) {
                pts[2 * i] += l.toFloat()
                pts[2 * i + 1] += t.toFloat()
            }
        }
        if (traced.isEmpty()) return emptyList()
        val areas = FloatArray(traced.size) { signedArea(traced[it]) }
        // The biggest contour is an outer one (a hole lies inside a bigger outline): its
        // orientation tells outlines from holes.
        var biggest = 0
        for (i in areas.indices) if (abs(areas[i]) > abs(areas[biggest])) biggest = i
        val outerPositive = areas[biggest] > 0f
        val outers = traced.indices.filter { areas[it] != 0f && (areas[it] > 0f) == outerPositive }.map { traced[it] }
        if (outers.isEmpty()) return emptyList()
        val budget = max(4, maxPoints)
        var eps = max(RDP_EPSILON_PX / factor, if (factor > 1) 0.6f else RDP_EPSILON_PX)
        // A thin sliver can simplify to a segment: it still blocks text, as its box.
        fun simplify(e: Float) = outers.map { o -> MarchingSquares.simplifyClosed(o, e).let { if (it.size < 6) boxOf(o) else it } }
        var simplified = simplify(eps)
        while (MarchingSquares.pointCount(simplified) > budget && eps < MAX_EPSILON_CELLS) {
            eps *= 1.6f
            simplified = simplify(eps)
        }
        if (MarchingSquares.pointCount(simplified) > budget) simplified = fitBudget(simplified, budget)
        val f = factor.toFloat()
        return simplified.filter { it.size >= 6 }.map { pts ->
            val n = pts.size / 2
            // Sample (i, j) is the center of cell (i, j): document ((i + 0.5) × factor, ...).
            WrapPolygon(List(n) { (pts[2 * it] + 0.5f) * f }, List(n) { (pts[2 * it + 1] + 0.5f) * f })
        }
    }

    /** Biggest contours kept whole while the budget lasts, then as boxes; the smallest are dropped. */
    private fun fitBudget(contours: List<FloatArray>, budget: Int): List<FloatArray> {
        val order = contours.indices.sortedByDescending { abs(signedArea(contours[it])) }
        val out = ArrayList<FloatArray>()
        var left = budget
        for (i in order) {
            val pts = contours[i]
            val n = pts.size / 2
            when {
                n <= left -> { out += pts; left -= n }
                left >= 4 -> { out += boxOf(pts); left -= 4 }
                else -> break
            }
        }
        return out
    }

    private fun boxOf(pts: FloatArray): FloatArray {
        var l = Float.POSITIVE_INFINITY; var t = Float.POSITIVE_INFINITY
        var r = Float.NEGATIVE_INFINITY; var b = Float.NEGATIVE_INFINITY
        for (i in 0 until pts.size / 2) {
            l = min(l, pts[2 * i]); r = max(r, pts[2 * i])
            t = min(t, pts[2 * i + 1]); b = max(b, pts[2 * i + 1])
        }
        return floatArrayOf(l, t, r, t, r, b, l, b)
    }

    /** Signed area (shoelace) of a closed polyline. */
    fun signedArea(pts: FloatArray): Float {
        val n = pts.size / 2
        var a = 0.0
        for (k in 0 until n) {
            val j = if (k + 1 == n) 0 else k + 1
            a += pts[2 * k].toDouble() * pts[2 * j + 1] - pts[2 * j].toDouble() * pts[2 * k + 1]
        }
        return (a / 2.0).toFloat()
    }

    /** Area enclosed by [polygons] (each counted once; for tests and diagnostics). */
    fun area(polygons: List<WrapPolygon>): Float = polygons.sumOf { p ->
        val pts = FloatArray(p.size * 2) { if (it % 2 == 0) p.xs[it / 2] else p.ys[it / 2] }
        abs(signedArea(pts)).toDouble()
    }.toFloat()
}

/**
 * The outlines of layers, cached by layer content (main thread): the text tool and the re-flow
 * listener share it, so one edit of a picture is traced once however many texts wrap around it
 * (and the second event of the same edit finds it ready).
 */
class WrapContours {
    private class Entry(val version: Long, val bitmapId: Int, val maskId: Int, val outline: WrapContourBuilder.Outline)

    private val entries = object : LinkedHashMap<Long, Entry>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Entry>?): Boolean = size > CAPACITY
    }

    /** Number of layers traced (not served from the cache) so far; for tests. */
    var traces: Int = 0
        private set

    private fun maskOf(layer: Layer): Bitmap? = layer.mask?.takeIf { layer.maskEnabled }

    /** The outline of [layer]'s current pixels, or null when there is not enough memory to trace it. */
    fun outline(layer: Layer): WrapContourBuilder.Outline? {
        val mask = maskOf(layer)
        val bitmapId = System.identityHashCode(layer.bitmap)
        val maskId = mask?.let { System.identityHashCode(it) } ?: 0
        entries[layer.id]?.let { e ->
            if (e.version == layer.contentVersion && e.bitmapId == bitmapId && e.maskId == maskId) return e.outline
        }
        if (layer.bitmap.isRecycled) return null
        val outline = try {
            WrapContourBuilder.outline(WrapContourBuilder.scan(layer.bitmap, mask))
        } catch (e: OutOfMemoryError) {
            return null
        }
        traces++
        entries[layer.id] = Entry(layer.contentVersion, bitmapId, maskId, outline)
        return outline
    }

    /** The [contour] polygons of [layer] (see [outline]). */
    fun polygons(layer: Layer, contour: WrapContour): List<WrapPolygon>? = outline(layer)?.polygons(contour)

    fun clear() = entries.clear()

    private companion object {
        const val CAPACITY = 8
    }
}
