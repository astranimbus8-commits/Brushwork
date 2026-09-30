package com.brushwork.paint.inpaint

import com.brushwork.paint.core.Parallel

/** Mask helpers of the fill engine (binary masks are ByteArrays with 0 / 1). */
internal object HoleOps {

    /** Chamfer 3-4 unit per pixel step (so a distance in pixels is roughly d / 3). */
    const val CHAMFER_STEP = 3
    private const val CHAMFER_DIAG = 4
    const val FAR = Int.MAX_VALUE / 4

    /**
     * Chamfer 3-4 distance (units of 1/3 px) from every pixel to the nearest pixel where
     * [seed] is non-zero; [FAR] when there is none.
     */
    fun chamferDistance(seed: ByteArray, w: Int, h: Int): IntArray {
        val d = IntArray(w * h)
        for (i in d.indices) d[i] = if (seed[i].toInt() != 0) 0 else FAR
        // Forward pass.
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                var v = d[i]
                if (v == 0) continue
                if (x > 0) v = minOf(v, d[i - 1] + CHAMFER_STEP)
                if (y > 0) {
                    val up = i - w
                    v = minOf(v, d[up] + CHAMFER_STEP)
                    if (x > 0) v = minOf(v, d[up - 1] + CHAMFER_DIAG)
                    if (x < w - 1) v = minOf(v, d[up + 1] + CHAMFER_DIAG)
                }
                d[i] = v
            }
        }
        // Backward pass.
        for (y in h - 1 downTo 0) {
            val row = y * w
            for (x in w - 1 downTo 0) {
                val i = row + x
                var v = d[i]
                if (v == 0) continue
                if (x < w - 1) v = minOf(v, d[i + 1] + CHAMFER_STEP)
                if (y < h - 1) {
                    val dn = i + w
                    v = minOf(v, d[dn] + CHAMFER_STEP)
                    if (x > 0) v = minOf(v, d[dn - 1] + CHAMFER_DIAG)
                    if (x < w - 1) v = minOf(v, d[dn + 1] + CHAMFER_DIAG)
                }
                d[i] = v
            }
        }
        return d
    }

    /**
     * Largest chessboard distance from a hole pixel to the nearest known pixel, i.e. the number
     * of 3x3 erosions that empty the hole. [hole] covers [rect] of an image of [imgW] x [imgH];
     * pixels outside [rect] but inside the image are known, pixels outside the image are not
     * (a hole along the image edge can only be reached from one side).
     */
    fun holeRadius(hole: ByteArray, rect: IRect, imgW: Int, imgH: Int): Int {
        val w = rect.width; val h = rect.height
        val far = FAR
        val d = IntArray(w * h)
        for (i in d.indices) d[i] = if (hole[i].toInt() != 0) far else 0
        // Neighbour outside the rect: known (0) inside the image, unknown (FAR) outside it.
        fun outside(x: Int, y: Int): Int {
            val ix = rect.left + x; val iy = rect.top + y
            return if (ix < 0 || iy < 0 || ix >= imgW || iy >= imgH) far else 0
        }
        fun at(x: Int, y: Int): Int = if (x < 0 || y < 0 || x >= w || y >= h) outside(x, y) else d[y * w + x]
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (d[i] == 0) continue
            var v = d[i]
            v = minOf(v, at(x - 1, y) + 1, at(x - 1, y - 1) + 1)
            v = minOf(v, at(x, y - 1) + 1, at(x + 1, y - 1) + 1)
            d[i] = v
        }
        var best = 0
        for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
            val i = y * w + x
            if (d[i] == 0) continue
            var v = d[i]
            v = minOf(v, at(x + 1, y) + 1, at(x + 1, y + 1) + 1)
            v = minOf(v, at(x, y + 1) + 1, at(x - 1, y + 1) + 1)
            d[i] = v
            if (v > best) best = v
        }
        // A hole covering the whole image has no known pixel at all.
        return if (best >= far / 2) Int.MAX_VALUE else best
    }

    /**
     * Binary dilation with a square of radius [radius] (chessboard): out = 1 where [src] has a
     * non-zero pixel within [radius] in x and y. Separable sliding-window counts, O(w * h).
     */
    fun dilateSquare(src: ByteArray, w: Int, h: Int, radius: Int): ByteArray {
        if (radius <= 0) return ByteArray(src.size) { if (src[it].toInt() != 0) 1 else 0 }
        val tmp = ByteArray(w * h)
        Parallel.forRows(h) { y0, y1 ->
            for (y in y0 until y1) {
                val row = y * w
                var count = 0
                // Window [x - radius, x + radius]; prime with [0, radius - 1].
                for (x in 0 until minOf(radius, w)) if (src[row + x].toInt() != 0) count++
                for (x in 0 until w) {
                    val add = x + radius
                    if (add < w && src[row + add].toInt() != 0) count++
                    val drop = x - radius - 1
                    if (drop >= 0 && src[row + drop].toInt() != 0) count--
                    tmp[row + x] = if (count > 0) 1 else 0
                }
            }
        }
        val out = ByteArray(w * h)
        Parallel.forRange(w, 32) { x0, x1 ->
            val counts = IntArray(x1 - x0)
            for (y in 0 until minOf(radius, h)) {
                val row = y * w
                for (x in x0 until x1) if (tmp[row + x].toInt() != 0) counts[x - x0]++
            }
            for (y in 0 until h) {
                val add = y + radius
                val drop = y - radius - 1
                val addRow = add * w
                val dropRow = drop * w
                val row = y * w
                for (x in x0 until x1) {
                    val j = x - x0
                    if (add < h && tmp[addRow + x].toInt() != 0) counts[j]++
                    if (drop >= 0 && tmp[dropRow + x].toInt() != 0) counts[j]--
                    out[row + x] = if (counts[j] > 0) 1 else 0
                }
            }
        }
        return out
    }

    /** Bounding box of the non-zero pixels of [mask] ([w] x [h]), or null if there are none. */
    fun bounds(mask: ByteArray, w: Int, h: Int): IRect? {
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) {
            val row = y * w
            var any = false
            for (x in 0 until w) {
                if (mask[row + x].toInt() != 0) {
                    any = true
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                }
            }
            if (any) { if (y < minY) minY = y; maxY = y }
        }
        return if (maxX < 0) null else IRect(minX, minY, maxX + 1, maxY + 1)
    }

    /** Copies the [r] part of [src] ([srcW] wide, positioned at [srcRect]) into a new [r]-sized array. */
    fun crop(src: ByteArray, srcRect: IRect, r: IRect): ByteArray {
        val out = ByteArray(r.width * r.height)
        val sw = srcRect.width
        val inter = srcRect.intersect(r)
        if (inter.isEmpty) return out
        for (y in inter.top until inter.bottom) {
            System.arraycopy(src, (y - srcRect.top) * sw + (inter.left - srcRect.left), out, (y - r.top) * r.width + (inter.left - r.left), inter.width)
        }
        return out
    }
}
