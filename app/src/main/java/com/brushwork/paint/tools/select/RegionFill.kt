package com.brushwork.paint.tools.select

import com.brushwork.paint.core.Parallel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A computed region: soft [coverage] (0..255) for the window [x0, x1) x [y0, y1), row-major. */
class Region(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val coverage: ByteArray) {
    val width: Int get() = x1 - x0
    val height: Int get() = y1 - y0

    /** Coverage at document pixel (x, y), 0 outside the window. */
    fun at(x: Int, y: Int): Int =
        if (x < x0 || y < y0 || x >= x1 || y >= y1) 0 else coverage[(y - y0) * width + (x - x0)].toInt() and 0xFF
}

/** Parameters shared by the bucket fill and the magic wand. */
data class RegionParams(
    /** 0..255, see [RegionFill.colorDistance]. */
    val tolerance: Int,
    /** Only the area connected to the seed (4-connectivity) instead of every similar pixel. */
    val contiguous: Boolean = true,
    /** Treat gaps up to about 2x this many px wide as closed (contiguous mode only), 0..10. */
    val gapClose: Int = 0,
    /** Grow the result by this many px (e.g. to fill under line-art edges), 0..8. */
    val expand: Int = 0,
    /** Soft 1 px edge instead of a hard one. */
    val antiAlias: Boolean = true,
)

/**
 * Region finding for the bucket fill and the magic wand. Pure Kotlin: works on a packed
 * row-major "map" of flags so a 4000x5000 canvas needs ~1 byte per pixel of working memory.
 */
object RegionFill {
    /** Map flag: the pixel is similar to the seed (and inside the selection). */
    const val PASSABLE = 1
    private const val ERODED = 2
    private const val SEEDED = 4
    private const val REGION = 8
    private const val CONNECTED = 16

    /**
     * Color difference used by the tolerance: the maximum absolute difference over the A, R, G, B
     * channels, computed on PREMULTIPLIED values so faint pixels count as nearly transparent
     * (their unpremultiplied RGB is noise). For opaque colors this is the plain per-channel max.
     */
    fun colorDistance(c1: Int, c2: Int): Int {
        val a1 = c1 ushr 24; val a2 = c2 ushr 24
        var d = kotlin.math.abs(a1 - a2)
        d = max(d, kotlin.math.abs(premul((c1 shr 16) and 0xFF, a1) - premul((c2 shr 16) and 0xFF, a2)))
        d = max(d, kotlin.math.abs(premul((c1 shr 8) and 0xFF, a1) - premul((c2 shr 8) and 0xFF, a2)))
        d = max(d, kotlin.math.abs(premul(c1 and 0xFF, a1) - premul(c2 and 0xFF, a2)))
        return d
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun premul(v: Int, a: Int): Int = if (a == 255) v else (v * a + 127) / 255

    /**
     * Sets [PASSABLE] in `out[outOffset + i]` for each of [count] NON-premultiplied pixels
     * `src[srcOffset + i]` within [tolerance] of [seed] (and clears it otherwise). When [clip] is
     * given, pixels whose clip value is 0 are never passable (`clip[clipOffset + i]`).
     */
    fun markSimilar(
        src: IntArray, srcOffset: Int, count: Int, seed: Int, tolerance: Int,
        out: ByteArray, outOffset: Int,
        clip: ByteArray? = null, clipOffset: Int = 0,
    ) {
        val sa = seed ushr 24
        val sr = premul((seed shr 16) and 0xFF, sa)
        val sg = premul((seed shr 8) and 0xFF, sa)
        val sb = premul(seed and 0xFF, sa)
        val tol = tolerance.coerceIn(0, 255)
        for (i in 0 until count) {
            var ok: Boolean
            val c = src[srcOffset + i]
            if (c == seed) {
                ok = true
            } else {
                val a = c ushr 24
                ok = kotlin.math.abs(a - sa) <= tol &&
                    kotlin.math.abs(premul((c shr 16) and 0xFF, a) - sr) <= tol &&
                    kotlin.math.abs(premul((c shr 8) and 0xFF, a) - sg) <= tol &&
                    kotlin.math.abs(premul(c and 0xFF, a) - sb) <= tol
            }
            if (ok && clip != null && clip[clipOffset + i].toInt() == 0) ok = false
            out[outOffset + i] = if (ok) PASSABLE.toByte() else 0
        }
    }

    /** Convenience for tests / small images: builds the map for a whole pixel array. */
    fun buildMap(pixels: IntArray, width: Int, height: Int, seedX: Int, seedY: Int, tolerance: Int, clip: ByteArray? = null): ByteArray {
        val map = ByteArray(width * height)
        markSimilar(pixels, 0, width * height, pixels[seedY * width + seedX], tolerance, map, 0, clip, 0)
        return map
    }

    /**
     * Computes the region for a tap at ([seedX], [seedY]) from [map] (only [PASSABLE] may be set
     * on entry; the array is used as scratch and modified). [clip], if given, is a full-size soft
     * mask (e.g. the selection) multiplied into the result. Returns null when the seed is not
     * passable or nothing was found (or when [cancelled]).
     */
    fun compute(
        map: ByteArray, width: Int, height: Int, seedX: Int, seedY: Int,
        params: RegionParams,
        clip: ByteArray? = null,
        cancelled: () -> Boolean = { false },
    ): Region? {
        if (seedX !in 0 until width || seedY !in 0 until height) return null
        val seedIdx = seedY * width + seedX
        if (map[seedIdx].toInt() and PASSABLE == 0) return null

        val bounds: IntArray
        val regionBit: Int
        if (!params.contiguous) {
            bounds = boundsOf(map, width, height, PASSABLE) ?: return null
            regionBit = PASSABLE
        } else {
            val gap = params.gapClose.coerceIn(0, Distance.MAX_RADIUS - 1)
            var closed: IntArray? = null
            if (gap > 0) closed = floodWithGapClosing(map, width, height, seedX, seedY, gap, cancelled)
            if (cancelled()) return null
            if (closed != null) {
                bounds = closed
                regionBit = CONNECTED
            } else {
                bounds = flood(map, width, height, seedX, seedY, PASSABLE, SEEDED) ?: return null
                regionBit = SEEDED
            }
        }
        if (cancelled()) return null
        return rasterize(map, width, height, bounds, regionBit, params, clip, cancelled)
    }

    /**
     * Gap closing: erode the passable set by [r] (pixels within r of a barrier are removed), flood
     * from the seed, then grow the result back by r + 1 inside the passable set, keeping only what
     * stays 4-connected to it (so the grow never jumps across thin lines). Returns the bounds of
     * the [CONNECTED]-flagged result, or null to fall back to a plain fill (when no eroded pixel is
     * reachable near the seed, e.g. inside a very thin area).
     */
    private fun floodWithGapClosing(map: ByteArray, w: Int, h: Int, sx: Int, sy: Int, r: Int, cancelled: () -> Boolean): IntArray? {
        val r2 = r * r
        Distance.bounded(w, h, 0, 0, w, h, r, { i -> map[i].toInt() and PASSABLE == 0 }, { y, d2 ->
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                val m = map[i].toInt()
                if (m and PASSABLE != 0 && d2[x] > r2) map[i] = (m or ERODED).toByte()
            }
        }, cancelled)
        if (cancelled()) return null
        // Taps close to a line land in the eroded band: start from the nearest eroded pixel that
        // is reachable from the tap without crossing a barrier.
        val path = pathToFlag(map, w, h, sx, sy, r + 1, ERODED) ?: return null
        val start = path[path.size - 1]
        val seeded = flood(map, w, h, start % w, start / w, ERODED, SEEDED) ?: return null
        if (cancelled()) return null
        val grow = r + 1
        val g2 = grow * grow
        val bx0 = max(0, seeded[0] - grow); val by0 = max(0, seeded[1] - grow)
        val bx1 = min(w, seeded[2] + grow); val by1 = min(h, seeded[3] + grow)
        Distance.bounded(w, h, bx0, by0, bx1, by1, grow, { i -> map[i].toInt() and SEEDED != 0 }, { y, d2 ->
            val row = y * w
            for (k in d2.indices) {
                val i = row + bx0 + k
                val m = map[i].toInt()
                if (m and PASSABLE != 0 && d2[k] <= g2) map[i] = (m or REGION).toByte()
            }
        }, cancelled)
        if (cancelled()) return null
        // The tapped pixel always belongs to the fill, joined to the core along the path found.
        for (i in path) map[i] = (map[i].toInt() or REGION).toByte()
        return flood(map, w, h, start % w, start / w, REGION, CONNECTED)
    }

    /**
     * Breadth-first search from (sx, sy) through [PASSABLE] pixels (4-connected) within the
     * square of [radius] around it, for the closest pixel having [flag] within [radius]. Returns
     * the pixel indices of the path from the seed to that pixel (seed first), or null.
     */
    private fun pathToFlag(map: ByteArray, w: Int, h: Int, sx: Int, sy: Int, radius: Int, flag: Int): IntArray? {
        val x0 = max(0, sx - radius); val y0 = max(0, sy - radius)
        val x1 = min(w - 1, sx + radius); val y1 = min(h - 1, sy + radius)
        val ww = x1 - x0 + 1
        val n = ww * (y1 - y0 + 1)
        val parent = IntArray(n) { -2 } // -2 = unvisited, -1 = the seed
        val queue = IntArray(n)
        var head = 0; var tail = 0
        val seed = (sy - y0) * ww + (sx - x0)
        parent[seed] = -1
        queue[tail++] = seed
        val r2 = radius * radius
        while (head < tail) {
            val q = queue[head++]
            val lx = q % ww; val ly = q / ww
            val x = x0 + lx; val y = y0 + ly
            val dx = x - sx; val dy = y - sy
            if (dx * dx + dy * dy <= r2 && map[y * w + x].toInt() and flag != 0) {
                var len = 0
                var c = q
                while (c >= 0) { len++; c = parent[c] }
                val out = IntArray(len)
                c = q
                for (k in len - 1 downTo 0) { out[k] = (y0 + c / ww) * w + x0 + c % ww; c = parent[c] }
                return out
            }
            for (dir in 0..3) {
                val nx = lx + (if (dir == 0) -1 else if (dir == 1) 1 else 0)
                val ny = ly + (if (dir == 2) -1 else if (dir == 3) 1 else 0)
                if (nx < 0 || ny < 0 || nx >= ww || ny > y1 - y0) continue
                val nq = ny * ww + nx
                if (parent[nq] != -2) continue
                if (map[(y0 + ny) * w + x0 + nx].toInt() and PASSABLE == 0) continue
                parent[nq] = q
                queue[tail++] = nq
            }
        }
        return null
    }

    /**
     * Scanline flood fill (4-connected) over pixels having [passFlag], setting [markFlag].
     * Returns the bounds [x0, y0, x1, y1) of the marked area, or null if the seed isn't passable.
     */
    fun flood(map: ByteArray, w: Int, h: Int, sx: Int, sy: Int, passFlag: Int, markFlag: Int): IntArray? {
        if (!open(map, sy * w + sx, passFlag, markFlag)) return null
        val stack = IntStack()
        var minX = sx; var maxX = sx; var minY = sy; var maxY = sy
        stack.push(sx, sy)
        while (stack.isNotEmpty) {
            val y = stack.pop()
            val x = stack.pop()
            val row = y * w
            if (!open(map, row + x, passFlag, markFlag)) continue
            var xl = x
            while (xl > 0 && open(map, row + xl - 1, passFlag, markFlag)) xl--
            var xr = x
            while (xr < w - 1 && open(map, row + xr + 1, passFlag, markFlag)) xr++
            for (i in row + xl..row + xr) map[i] = (map[i].toInt() or markFlag).toByte()
            if (xl < minX) minX = xl
            if (xr > maxX) maxX = xr
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            var ny = y - 1
            while (ny <= y + 1) {
                if (ny in 0 until h) {
                    val nrow = ny * w
                    var inRun = false
                    for (xi in xl..xr) {
                        if (open(map, nrow + xi, passFlag, markFlag)) {
                            if (!inRun) { stack.push(xi, ny); inRun = true }
                        } else {
                            inRun = false
                        }
                    }
                }
                ny += 2
            }
        }
        return intArrayOf(minX, minY, maxX + 1, maxY + 1)
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun open(map: ByteArray, i: Int, passFlag: Int, markFlag: Int): Boolean {
        val m = map[i].toInt()
        return m and passFlag != 0 && m and markFlag == 0
    }

    /** Growable int stack of (x, y) pairs for the flood fill. */
    private class IntStack {
        private var data = IntArray(4096)
        private var size = 0
        val isNotEmpty: Boolean get() = size > 0
        fun push(x: Int, y: Int) {
            if (size + 2 > data.size) data = data.copyOf(data.size * 2)
            data[size++] = x
            data[size++] = y
        }
        fun pop(): Int = data[--size]
    }

    private fun boundsOf(map: ByteArray, w: Int, h: Int, flag: Int): IntArray? {
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) {
            val row = y * w
            var any = false
            for (x in 0 until w) {
                if (map[row + x].toInt() and flag != 0) {
                    any = true
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                }
            }
            if (any) { if (y < minY) minY = y; maxY = y }
        }
        return if (maxX < 0) null else intArrayOf(minX, minY, maxX + 1, maxY + 1)
    }

    /**
     * [region] cropped to its non-zero coverage, after zeroing the coverage wherever [keep]
     * (optional, same window layout as the coverage) is 0 — e.g. transparent pixels of an
     * alpha-locked layer. Modifies [region]'s coverage in place; null when nothing is left.
     */
    fun trim(region: Region, keep: ByteArray? = null): Region? {
        val w = region.width; val h = region.height
        val cov = region.coverage
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                if (cov[i].toInt() == 0) continue
                if (keep != null && keep[i].toInt() == 0) { cov[i] = 0; continue }
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                maxY = y
            }
        }
        if (maxX < 0) return null
        if (minX == 0 && minY == 0 && maxX == w - 1 && maxY == h - 1) return region
        val nw = maxX - minX + 1; val nh = maxY - minY + 1
        val out = ByteArray(nw * nh)
        for (y in 0 until nh) System.arraycopy(cov, (minY + y) * w + minX, out, y * nw, nw)
        return Region(region.x0 + minX, region.y0 + minY, region.x0 + maxX + 1, region.y0 + maxY + 1, out)
    }

    /** Turns the flagged pixels into soft coverage with expand / anti-alias / clip applied. */
    private fun rasterize(
        map: ByteArray, w: Int, h: Int, b: IntArray, bit: Int, params: RegionParams,
        clip: ByteArray?, cancelled: () -> Boolean,
    ): Region? {
        val expand = params.expand.coerceIn(0, Distance.MAX_RADIUS - 1)
        val aa = params.antiAlias
        val margin = expand + if (aa) 1 else 0
        val x0 = max(0, b[0] - margin); val y0 = max(0, b[1] - margin)
        val x1 = min(w, b[2] + margin); val y1 = min(h, b[3] + margin)
        val ww = x1 - x0
        val cov = ByteArray(ww * (y1 - y0))
        if (expand > 0) {
            val reach = if (aa) expand + 1 else expand
            Distance.bounded(w, h, x0, y0, x1, y1, reach, { i -> map[i].toInt() and bit != 0 }, { y, d2 ->
                val out = (y - y0) * ww
                for (k in 0 until ww) {
                    val dd = d2[k]
                    if (dd == Distance.FAR) continue
                    val v = if (!aa) 255 else {
                        val c = (expand + 1 - sqrt(dd.toDouble())) * 255.0
                        if (c >= 255.0) 255 else if (c <= 0.0) 0 else (c + 0.5).toInt()
                    }
                    cov[out + k] = v.toByte()
                }
            }, cancelled)
        } else {
            Parallel.forRows(y1 - y0) { ra, rb ->
                for (ry in ra until rb) {
                    val y = y0 + ry
                    val out = ry * ww
                    for (k in 0 until ww) {
                        val x = x0 + k
                        val i = y * w + x
                        if (map[i].toInt() and bit != 0) { cov[out + k] = -1; continue }
                        if (!aa) continue
                        var n = 0
                        for (dy in -1..1) {
                            val yy = y + dy
                            if (yy < 0 || yy >= h) continue
                            for (dx in -1..1) {
                                if (dx == 0 && dy == 0) continue
                                val xx = x + dx
                                if (xx < 0 || xx >= w) continue
                                if (map[yy * w + xx].toInt() and bit != 0) n++
                            }
                        }
                        if (n > 0) cov[out + k] = (n * 255 / 8).toByte()
                    }
                }
            }
        }
        if (cancelled()) return null
        if (clip != null) {
            for (ry in 0 until y1 - y0) {
                val src = (y0 + ry) * w + x0
                val out = ry * ww
                for (k in 0 until ww) {
                    val c = cov[out + k].toInt() and 0xFF
                    if (c == 0) continue
                    val s = clip[src + k].toInt() and 0xFF
                    if (s != 255) cov[out + k] = ((c * s + 127) / 255).toByte()
                }
            }
        }
        return Region(x0, y0, x1, y1, cov)
    }
}
