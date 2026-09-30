package com.brushwork.paint.inpaint

import com.brushwork.paint.core.Parallel
import kotlin.math.max
import kotlin.math.min

/**
 * One scale of the image being completed: premultiplied packed ARGB pixels ([img]; hole pixels
 * hold the current estimate), the hole, the pixels excluded as sources, optional texture
 * features ([tex], packed Tx | Ty shl 8) and the valid source patch centers.
 */
internal class Level(val w: Int, val h: Int, val img: IntArray, val hole: ByteArray, val excluded: ByteArray?) {
    var tex: IntArray? = null

    /** Hole bounding box (right / bottom exclusive) and pixel count. */
    var hx0 = 0; var hy0 = 0; var hx1 = 0; var hy1 = 0
    var holeCount = 0
        private set

    /** 1 = a patch centered here is a valid source (fully known, away from the hole). */
    var valid = ByteArray(0)
        private set
    var validList = IntArray(0)
        private set

    init { computeHoleBox() }

    private fun computeHoleBox() {
        var minX = w; var minY = h; var maxX = -1; var maxY = -1; var n = 0
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if (hole[row + x].toInt() != 0) {
                    n++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    maxY = y
                }
            }
        }
        holeCount = n
        if (n > 0) { hx0 = minX; hy0 = minY; hx1 = maxX + 1; hy1 = maxY + 1 }
    }

    /**
     * Valid source centers for patches of radius [r]: the patch lies inside the level, no hole
     * pixel is within [r] + [guard] (so neither the hole nor its halo is ever copied) and no
     * excluded pixel is inside the patch.
     */
    fun computeValid(r: Int, guard: Int) {
        val v = ByteArray(w * h)
        if (w > 2 * r && h > 2 * r) {
            val nearHole = HoleOps.dilateSquare(hole, w, h, r + guard)
            val nearExcl = excluded?.let { HoleOps.dilateSquare(it, w, h, r) }
            for (y in r until h - r) {
                val row = y * w
                for (x in r until w - r) {
                    val i = row + x
                    if (nearHole[i].toInt() == 0 && (nearExcl == null || nearExcl[i].toInt() == 0)) v[i] = 1
                }
            }
        }
        var n = 0
        for (b in v) if (b.toInt() != 0) n++
        val list = IntArray(n)
        var k = 0
        for (i in v.indices) if (v[i].toInt() != 0) list[k++] = i
        valid = v
        validList = list
    }

    companion object {
        private val KERNEL = intArrayOf(1, 3, 3, 1)

        /**
         * Half-size level: a coarse pixel is a hole if ANY of its children is (hole content never
         * leaks into known pixels), known pixels are a [1 3 3 1] gaussian average of the known
         * fine pixels around them; excluded if any child is excluded.
         */
        fun downsample(src: Level): Level {
            val w = src.w; val h = src.h
            val cw = (w + 1) / 2; val ch = (h + 1) / 2
            val img = IntArray(cw * ch)
            val hole = ByteArray(cw * ch)
            val excl = src.excluded?.let { ByteArray(cw * ch) }
            val sImg = src.img; val sHole = src.hole; val sEx = src.excluded
            Parallel.forRows(ch) { y0, y1 ->
                for (cy in y0 until y1) {
                    val fy = cy * 2
                    for (cx in 0 until cw) {
                        val fx = cx * 2
                        val ci = cy * cw + cx
                        var anyHole = false
                        var anyEx = false
                        for (yy in fy until min(h, fy + 2)) for (xx in fx until min(w, fx + 2)) {
                            val i = yy * w + xx
                            if (sHole[i].toInt() != 0) anyHole = true
                            if (sEx != null && sEx[i].toInt() != 0) anyEx = true
                        }
                        if (anyEx) excl!![ci] = 1
                        if (anyHole) { hole[ci] = 1; continue }
                        var sa = 0; var sr = 0; var sg = 0; var sb = 0; var sw = 0
                        for (ky in 0 until 4) {
                            val yy = fy - 1 + ky
                            if (yy < 0 || yy >= h) continue
                            val wy = KERNEL[ky]
                            for (kx in 0 until 4) {
                                val xx = fx - 1 + kx
                                if (xx < 0 || xx >= w) continue
                                val i = yy * w + xx
                                if (sHole[i].toInt() != 0) continue
                                val wt = wy * KERNEL[kx]
                                val c = sImg[i]
                                sa += (c ushr 24) * wt; sr += ((c shr 16) and 0xFF) * wt
                                sg += ((c shr 8) and 0xFF) * wt; sb += (c and 0xFF) * wt
                                sw += wt
                            }
                        }
                        img[ci] = packAvg(sa, sr, sg, sb, sw)
                    }
                }
            }
            return Level(cw, ch, img, hole, excl)
        }

        /** Level downscaled 2^[k] times by averaging the known children (same hole rules). */
        fun downscaleBox(src: Level, k: Int): Level {
            val s = 1 shl k
            val w = src.w; val h = src.h
            val cw = (w + s - 1) shr k; val ch = (h + s - 1) shr k
            val img = IntArray(cw * ch)
            val hole = ByteArray(cw * ch)
            val excl = src.excluded?.let { ByteArray(cw * ch) }
            val sImg = src.img; val sHole = src.hole; val sEx = src.excluded
            Parallel.forRows(ch) { y0, y1 ->
                for (cy in y0 until y1) {
                    for (cx in 0 until cw) {
                        val ci = cy * cw + cx
                        var anyHole = false
                        var anyEx = false
                        var sa = 0L; var sr = 0L; var sg = 0L; var sb = 0L; var n = 0
                        for (yy in cy * s until min(h, cy * s + s)) {
                            val row = yy * w
                            for (xx in cx * s until min(w, cx * s + s)) {
                                val i = row + xx
                                if (sHole[i].toInt() != 0) { anyHole = true; continue }
                                if (sEx != null && sEx[i].toInt() != 0) anyEx = true
                                val c = sImg[i]
                                sa += c ushr 24; sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
                                n++
                            }
                        }
                        if (anyEx) excl!![ci] = 1
                        if (anyHole || n == 0) { hole[ci] = 1; continue }
                        img[ci] = packAvg(sa.toInt(), sr.toInt(), sg.toInt(), sb.toInt(), n)
                    }
                }
            }
            return Level(cw, ch, img, hole, excl)
        }

        /** Rounded premultiplied average (rgb kept <= alpha). */
        fun packAvg(sa: Int, sr: Int, sg: Int, sb: Int, sw: Int): Int {
            if (sw <= 0) return 0
            val half = sw / 2
            val a = min(255, (sa + half) / sw)
            val r = min(a, (sr + half) / sw)
            val g = min(a, (sg + half) / sw)
            val b = min(a, (sb + half) / sw)
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        /**
         * Texture features (Newson et al.): box means of |dY/dx| and |dY/dy| over a [window]
         * square, from gradients between two KNOWN pixels only; 0 in the hole (reconstructed
         * later). Quantized x4 into 0..255, packed Tx | Ty shl 8.
         */
        fun texture(l: Level, window: Int): IntArray {
            val w = l.w; val h = l.h
            val lum = IntArray(w * h)
            Parallel.forRange(lum.size, 4096) { a, b ->
                for (i in a until b) {
                    if (l.hole[i].toInt() != 0) { lum[i] = -1; continue }
                    val c = l.img[i]
                    lum[i] = (77 * ((c shr 16) and 0xFF) + 150 * ((c shr 8) and 0xFF) + 29 * (c and 0xFF) + 128) shr 8
                }
            }
            val out = IntArray(w * h)
            // Horizontal window sums of the gradient magnitudes and of how many were valid
            // (packed sum shl 8 | count: a window holds at most 64 x 255), then vertical sums.
            val rowSums = IntArray(w * h)
            val half = window / 2
            for (axis in 0..1) {
                Parallel.forRows(h) { y0, y1 ->
                    val g = IntArray(w)
                    for (y in y0 until y1) {
                        val row = y * w
                        for (x in 0 until w) {
                            val i = row + x
                            val a = lum[i]
                            val j = if (axis == 0) (if (x + 1 < w) i + 1 else -1) else (if (y + 1 < h) i + w else -1)
                            g[x] = if (a >= 0 && j >= 0 && lum[j] >= 0) {
                                val b = lum[j]
                                ((if (a > b) a - b else b - a) shl 8) or 1
                            } else 0
                        }
                        // Window [x - half, x - half + window).
                        var s = 0
                        for (x in 0 until min(w, window - half - 1)) s += g[x]
                        for (x in 0 until w) {
                            val add = x - half + window - 1
                            if (add < w) s += g[add]
                            val drop = x - half - 1
                            if (drop >= 0) s -= g[drop]
                            rowSums[row + x] = s
                        }
                    }
                }
                val shift = axis * 8
                Parallel.forRange(w, 32) { x0, x1 ->
                    val n = x1 - x0
                    val sums = LongArray(n)
                    val counts = IntArray(n)
                    for (y in 0 until min(h, window - half - 1)) {
                        val row = y * w
                        for (x in x0 until x1) { val v = rowSums[row + x]; sums[x - x0] += (v ushr 8).toLong(); counts[x - x0] += v and 0xFF }
                    }
                    for (y in 0 until h) {
                        val add = y - half + window - 1
                        if (add < h) {
                            val row = add * w
                            for (x in x0 until x1) { val v = rowSums[row + x]; sums[x - x0] += (v ushr 8).toLong(); counts[x - x0] += v and 0xFF }
                        }
                        val drop = y - half - 1
                        if (drop >= 0) {
                            val row = drop * w
                            for (x in x0 until x1) { val v = rowSums[row + x]; sums[x - x0] -= (v ushr 8).toLong(); counts[x - x0] -= v and 0xFF }
                        }
                        val row = y * w
                        for (x in x0 until x1) {
                            val i = row + x
                            if (lum[i] < 0) continue
                            val c = counts[x - x0]
                            if (c > 0) {
                                val t = min(255L, (sums[x - x0] * 4 + c / 2) / c).toInt()
                                out[i] = out[i] or (t shl shift)
                            }
                        }
                    }
                }
            }
            return out
        }

        /** Features of the finest level [fine] subsampled (nearest, not blurred) to [coarse], [shift] levels down. */
        fun subsampleTexture(fine: Level, fineTex: IntArray, coarse: Level, shift: Int): IntArray {
            val out = IntArray(coarse.w * coarse.h)
            for (y in 0 until coarse.h) {
                val fy = min(fine.h - 1, y shl shift)
                for (x in 0 until coarse.w) {
                    val i = y * coarse.w + x
                    if (coarse.hole[i].toInt() != 0) continue
                    out[i] = fineTex[fy * fine.w + min(fine.w - 1, x shl shift)]
                }
            }
            return out
        }
    }
}

/**
 * Nearest-neighbour field of a level: for each target pixel (whose patch overlaps the hole) the
 * index of the source patch center it matches ([f], -1 for non-targets) and the patch distance
 * ([d]). Stored over the hole's bounding box grown by the patch radius.
 */
internal class Nnf(level: Level, val r: Int) {
    val x0 = max(0, level.hx0 - r)
    val y0 = max(0, level.hy0 - r)
    val bw = max(0, min(level.w, level.hx1 + r) - x0)
    val bh = max(0, min(level.h, level.hy1 + r) - y0)
    val f = IntArray(bw * bh)
    val d = IntArray(bw * bh)
    val isTarget: ByteArray
    val targetCount: Int

    init {
        val crop = ByteArray(bw * bh)
        for (y in 0 until bh) System.arraycopy(level.hole, (y0 + y) * level.w + x0, crop, y * bw, bw)
        isTarget = HoleOps.dilateSquare(crop, bw, bh, r)
        var n = 0
        for (i in f.indices) {
            f[i] = -1
            if (isTarget[i].toInt() != 0) n++
        }
        targetCount = n
    }
}

/** Small fast deterministic PRNG (xorshift64*). */
internal class Rng(seed: Long) {
    private var s = mix64(seed).let { if (it == 0L) -0x61c8864680b583ebL else it }

    fun nextLong(): Long {
        var x = s
        x = x xor (x shl 13)
        x = x xor (x ushr 7)
        x = x xor (x shl 17)
        s = x
        return x * 0x2545F4914F6CDD1DL
    }

    /** Uniform-ish in [0, bound). */
    fun nextInt(bound: Int): Int = ((nextLong() ushr 33) % bound).toInt()

    companion object {
        fun mix64(z0: Long): Long {
            var z = z0 + -0x61c8864680b583ebL
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }

        /** Seed derived from several integers (deterministic, order-sensitive). */
        fun hash(a: Long, b: Long, c: Long = 0, d: Long = 0): Long =
            mix64(mix64(mix64(mix64(a) + b) + c) + d)
    }
}
