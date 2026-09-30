package com.brushwork.paint.inpaint

import com.brushwork.paint.core.Parallel
import com.brushwork.paint.core.PixelBuffer
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Multi-scale PatchMatch image completion of one [InpaintPlan] (see ContentAwareFill):
 *
 * 1. Premultiply the region, pick a working scale (big holes / regions are searched downscaled)
 *    and build a pyramid whose coarsest level shrinks the hole to about two patch widths.
 * 2. Coarsest level: onion-peel initialization (the hole is filled ring by ring from the best
 *    matching known patch), then EM: PatchMatch nearest-neighbour search (propagation + random
 *    search, alternating scan order) followed by gaussian-weighted patch voting.
 * 3. Finer levels start from the upsampled nearest-neighbour field (never the upsampled image).
 * 4. Finest level: every hole pixel copies the pixel of its best-matching patch (no averaging
 *    blur); after a downscaled search the field is refined once more at full resolution so the
 *    copied pixels are real full-resolution detail.
 * 5. Optional color adaptation: the mismatch along the hole's border is spread smoothly into the
 *    fill (membrane interpolation), removing brightness / color seams.
 *
 * Deterministic for a seed: randomness is seeded per level / iteration / band / pixel, bands
 * are fixed by geometry (not by the number of CPU cores) and never read each other's rows.
 */
internal class InpaintEngine(
    private val plan: InpaintPlan,
    private val params: InpaintParams,
    private val monitor: InpaintMonitor,
) {
    private var p = 7
    private var r = 3
    private var texW = 0
    private val seed = params.seed.toLong()

    private var progressDone = 0.0
    private var progressTotal = 1.0

    private fun advance(units: Double) {
        progressDone += units
        monitor.progress((progressDone / progressTotal).toFloat())
    }

    fun run(pixels: PixelBuffer): InpaintResult {
        val roi = plan.roi
        val rw = roi.width; val rh = roi.height
        require(pixels.width == rw && pixels.height == rh) { "pixels ${pixels.width}x${pixels.height} != roi ${rw}x$rh" }
        monitor.checkCancelled()

        // Premultiplied copy; hole pixels are unknown (never read as sources).
        val full = IntArray(rw * rh)
        val src = pixels.pixels
        val holeMask = plan.hole
        Parallel.forRange(full.size, 4096) { a, b ->
            for (i in a until b) full[i] = if (holeMask[i].toInt() != 0) 0 else premul(src[i])
        }
        val fullLevel = Level(rw, rh, full, holeMask, plan.excluded)

        // Working scale: search downscaled when the hole or the sampling region is big.
        var k = 0
        val holeN = plan.holePixels.toLong()
        val roiN = rw.toLong() * rh
        while (k < MAX_WORK_SHIFT && ((holeN shr (2 * k)) > params.maxWorkingHolePixels || (roiN shr (2 * k)) > params.maxWorkingRoiPixels)) k++
        val base = if (k == 0) fullLevel else Level.downscaleBox(fullLevel, k)
        monitor.checkCancelled()

        val radiusW = max(1, plan.holeRadius shr k)
        p = if (radiusW <= THIN_RADIUS) 5 else 7
        r = p / 2
        base.computeValid(r, if (k == 0) SOURCE_GUARD else 1)
        while (base.validList.isEmpty() && r > 1) {
            r--; p = 2 * r + 1
            base.computeValid(r, if (k == 0) SOURCE_GUARD else 1)
        }
        if (base.validList.isEmpty()) throw InpaintException(NO_SOURCE)

        // Pyramid: about two patch widths of hole at the coarsest level (Newson eq. 12).
        var levelCount = if (radiusW <= THIN_RADIUS) 0 else ceil(ln(2.0 * radiusW / p) / ln(2.0)).toInt().coerceIn(0, MAX_LEVELS)
        while (levelCount > 0 && (min(base.w, base.h) shr levelCount) < 4 * p) levelCount--
        val levels = ArrayList<Level>()
        levels += base
        for (l in 1..levelCount) {
            val next = Level.downsample(levels.last())
            next.computeValid(r, 1)
            if (next.validList.size < MIN_VALID || next.holeCount == 0) break
            levels += next
            monitor.checkCancelled()
        }
        val top = levels.size - 1

        // Texture features at the working level, subsampled to the coarser ones.
        texW = if (params.textureWeight > 0f) max(1, (params.textureWeight * p * p / 9f / 16f).roundToInt()) else 0
        if (texW > 0) {
            val t0 = Level.texture(base, max(3, 1 shl top))
            base.tex = t0
            for (l in 1..top) levels[l].tex = Level.subsampleTexture(base, t0, levels[l], l)
        }

        // Progress: EM iterations weighted by the hole size of their level.
        var total = 0.0
        for (l in 0..top) total += emIterations(l, top) * levels[l].holeCount.toDouble()
        total += levels[top].holeCount * 2.0
        val fullPass = k > 0
        if (fullPass) total += fullLevel.holeCount * FULL_PASS_UNITS
        progressTotal = max(1.0, total)
        progressDone = 0.0

        // Coarse to fine.
        var prev: Nnf? = null
        var prevLevel: Level? = null
        for (l in top downTo 0) {
            val lvl = levels[l]
            val n = Nnf(lvl, r)
            if (prev == null) {
                val nn = onionPeel(lvl, l)
                initFromOnion(lvl, n, nn, l)
                advance(lvl.holeCount * 2.0)
            } else {
                upsampleNnf(prev, prevLevel!!, n, lvl, 1, l)
                vote(lvl, n, weighted = false)
            }
            val nEm = emIterations(l, top)
            for (it in 0 until nEm) {
                refreshDistances(lvl, n)
                val pmIters = when {
                    l == top && it == 0 -> 4
                    l == 0 -> 1
                    else -> 2
                }
                patchMatch(lvl, n, pmIters, max(lvl.w, lvl.h), l, it * 8)
                val change = vote(lvl, n, weighted = true)
                advance(lvl.holeCount.toDouble())
                if (it > 0 && change < CONVERGED) {
                    advance(lvl.holeCount.toDouble() * (nEm - it - 1))
                    break
                }
            }
            if (prevLevel != null && prevLevel !== base) prevLevel.tex = null
            prev = n
            prevLevel = lvl
        }
        val workNnf = prev!!

        val sources = if (params.recordSources) IntArray(rw * rh) { -1 } else null
        if (!fullPass) {
            refreshDistances(base, workNnf)
            bestPatch(base, workNnf, sources)
            if (params.colorAdaptation) colorAdapt(base) { x, y -> bestSource(base, workNnf, x, y) }
        } else {
            fullResolutionPass(fullLevel, base, workNnf, k, sources)
        }
        monitor.checkCancelled()
        monitor.progress(1f)
        return buildResult(fullLevel, sources)
    }

    /** EM iterations of level [l] (0 = finest) with [top] the coarsest: 12 down to 2 (Barnes). */
    private fun emIterations(l: Int, top: Int): Int = if (top == 0) 4 else (2 + 10.0 * l / top).roundToInt()

    // ------------------------------------------------------------------ full-resolution pass

    /**
     * After a downscaled search: upsample the field 2^[k] times, refine it with PatchMatch whose
     * random search stays within a few working pixels, and copy real full-resolution pixels.
     * Very large holes skip the search and copy through the upsampled field directly.
     */
    private fun fullResolutionPass(full: Level, work: Level, workNnf: Nnf, k: Int, sources: IntArray?) {
        texW = 0
        full.computeValid(r, SOURCE_GUARD)
        if (full.validList.isEmpty()) throw InpaintException(NO_SOURCE)
        val boxArea = (min(full.w, full.hx1 + r) - max(0, full.hx0 - r)).toLong() * (min(full.h, full.hy1 + r) - max(0, full.hy0 - r))
        if (full.holeCount > FULL_RES_SEARCH_LIMIT || boxArea > FULL_RES_BOX_LIMIT) {
            directCopy(full, work, workNnf, k, sources)
            return
        }
        val n = Nnf(full, r)
        upsampleNnf(workNnf, work, n, full, k, FULL_LEVEL_TAG)
        vote(full, n, weighted = false)
        advance(full.holeCount.toDouble())
        val radius = 2 shl k
        refreshDistances(full, n)
        patchMatch(full, n, 1, radius, FULL_LEVEL_TAG, 0)
        vote(full, n, weighted = true)
        advance(full.holeCount.toDouble())
        refreshDistances(full, n)
        patchMatch(full, n, 1, radius, FULL_LEVEL_TAG, 1)
        bestPatch(full, n, sources)
        advance(full.holeCount.toDouble())
        if (params.colorAdaptation) colorAdapt(full) { x, y -> bestSource(full, n, x, y) }
    }

    /** Source pixel of full-resolution pixel ([x], [y]) through the upsampled working field, or -1. */
    private fun upsampledSource(full: Level, work: Level, workNnf: Nnf, k: Int, x: Int, y: Int): Int {
        val px = (x shr k) - workNnf.x0
        val py = (y shr k) - workNnf.y0
        if (px < 0 || py < 0 || px >= workNnf.bw || py >= workNnf.bh) return -1
        val s = workNnf.f[py * workNnf.bw + px]
        if (s < 0) return -1
        val sy = s / work.w; val sx = s - sy * work.w
        val mask = (1 shl k) - 1
        val cx = ((sx shl k) + (x and mask)).coerceIn(0, full.w - 1)
        val cy = ((sy shl k) + (y and mask)).coerceIn(0, full.h - 1)
        val c = cy * full.w + cx
        return if (full.hole[c].toInt() == 0) c else -1
    }

    private fun directCopy(full: Level, work: Level, workNnf: Nnf, k: Int, sources: IntArray?) {
        val w = full.w
        Parallel.forRows(full.hy1 - full.hy0) { a, b ->
            for (ry in a until b) {
                if ((ry and 15) == 0) monitor.checkCancelled()
                val y = full.hy0 + ry
                for (x in full.hx0 until full.hx1) {
                    val i = y * w + x
                    if (full.hole[i].toInt() == 0) continue
                    var s = upsampledSource(full, work, workNnf, k, x, y)
                    if (s < 0) s = full.validList[Rng(Rng.hash(seed, DIRECT_TAG, i.toLong())).nextInt(full.validList.size)]
                    full.img[i] = full.img[s]
                    sources?.set(i, s)
                }
            }
        }
        advance(full.holeCount * FULL_PASS_UNITS)
        if (params.colorAdaptation) colorAdapt(full) { x, y -> upsampledSource(full, work, workNnf, k, x, y) }
    }

    // ------------------------------------------------------------------ patch distance

    /**
     * Sum of squared premultiplied channel differences between the patch at ([tx], [ty]) and the
     * source patch centered at index [s], plus the texture term on a 3x3 sub-grid. Stops early
     * once the sum reaches [limit] (the result is then >= [limit]). A target patch cut by the
     * level border is compared on its inside part and scaled to a full patch.
     */
    private fun dist(l: Level, tx: Int, ty: Int, s: Int, limit: Int): Int {
        val w = l.w; val h = l.h; val img = l.img; val r = r; val p = p
        val sy = s / w; val sx = s - sy * w
        var sum = 0
        val tex = l.tex
        val tw = texW
        if (tex != null && tw > 0) {
            var gy = -1
            while (gy <= 1) {
                val oy = gy * r
                val yy = ty + oy
                if (yy in 0 until h) {
                    val trow = yy * w
                    val srow = (sy + oy) * w + sx
                    var gx = -1
                    while (gx <= 1) {
                        val ox = gx * r
                        val xx = tx + ox
                        if (xx in 0 until w) {
                            val a = tex[trow + xx]; val b = tex[srow + ox]
                            val d0 = (a and 0xFF) - (b and 0xFF)
                            val d1 = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
                            sum += (d0 * d0 + d1 * d1) * tw
                        }
                        gx++
                    }
                }
                gy++
            }
            if (sum >= limit) return sum
        }
        if (tx >= r && ty >= r && tx + r < w && ty + r < h) {
            var ti = (ty - r) * w + (tx - r)
            var si = (sy - r) * w + (sx - r)
            var row = 0
            while (row < p) {
                var c = 0
                while (c < p) {
                    val a = img[ti + c]; val b = img[si + c]
                    val d0 = (a and 0xFF) - (b and 0xFF)
                    val d1 = ((a ushr 8) and 0xFF) - ((b ushr 8) and 0xFF)
                    val d2 = ((a ushr 16) and 0xFF) - ((b ushr 16) and 0xFF)
                    val d3 = (a ushr 24) - (b ushr 24)
                    sum += d0 * d0 + d1 * d1 + d2 * d2 + d3 * d3
                    c++
                }
                if (sum >= limit) return sum
                ti += w; si += w
                row++
            }
            return sum
        }
        var part = 0L
        var cnt = 0
        for (dy in -r..r) {
            val yy = ty + dy
            if (yy < 0 || yy >= h) continue
            val trow = yy * w
            val srow = (sy + dy) * w + sx
            for (dx in -r..r) {
                val xx = tx + dx
                if (xx < 0 || xx >= w) continue
                val a = img[trow + xx]; val b = img[srow + dx]
                val d0 = (a and 0xFF) - (b and 0xFF)
                val d1 = ((a ushr 8) and 0xFF) - ((b ushr 8) and 0xFF)
                val d2 = ((a ushr 16) and 0xFF) - ((b ushr 16) and 0xFF)
                val d3 = (a ushr 24) - (b ushr 24)
                part += (d0 * d0 + d1 * d1 + d2 * d2 + d3 * d3).toLong()
                cnt++
            }
        }
        if (cnt == 0) return sum
        val scaled = sum + part * (p * p) / cnt
        return if (scaled > MAX_DIST) MAX_DIST else scaled.toInt()
    }

    private fun refreshDistances(l: Level, n: Nnf) {
        Parallel.forRange(n.bh, 2) { a, b ->
            for (row in a until b) {
                if ((row and 7) == 0) monitor.checkCancelled()
                val y = n.y0 + row
                val base = row * n.bw
                for (col in 0 until n.bw) {
                    val i = base + col
                    val s = n.f[i]
                    if (s >= 0) n.d[i] = dist(l, n.x0 + col, y, s, Int.MAX_VALUE)
                }
            }
        }
    }

    // ------------------------------------------------------------------ PatchMatch

    /**
     * [iters] PatchMatch iterations (Barnes et al. 2009). Rows of the field are split into bands
     * processed in parallel; each band scans its own rows (forward on even, backward on odd
     * iterations, with the band edges shifted by half a band on odd ones) and propagates only
     * from rows of the same band, so the result doesn't depend on thread timing.
     */
    private fun patchMatch(l: Level, n: Nnf, iters: Int, radius0: Int, levelTag: Int, iterTag: Int) {
        if (n.bh == 0 || l.validList.isEmpty()) return
        for (it in 0 until iters) {
            val parity = (iterTag + it) and 1
            val edges = bandEdges(n.bh, shifted = parity == 1)
            val bands = edges.size - 1
            Parallel.forRange(bands, 1) { b0, b1 ->
                for (b in b0 until b1) {
                    val rng = Rng(Rng.hash(seed, levelTag.toLong(), (iterTag + it).toLong(), b.toLong()))
                    pmBand(l, n, edges[b], edges[b + 1], parity == 0, radius0, rng)
                }
            }
            monitor.checkCancelled()
        }
    }

    private fun bandEdges(rows: Int, shifted: Boolean): IntArray {
        val k = (rows / BAND_ROWS).coerceIn(1, MAX_BANDS)
        val size = (rows + k - 1) / k
        val out = ArrayList<Int>(k + 2)
        out += 0
        var e = if (shifted && k > 1) size / 2 else size
        while (e < rows) { out += e; e += size }
        out += rows
        return out.toIntArray()
    }

    private fun pmBand(l: Level, n: Nnf, ys: Int, ye: Int, forward: Boolean, radius0: Int, rng: Rng) {
        val w = l.w; val h = l.h; val valid = l.valid
        val f = n.f; val d = n.d; val bw = n.bw
        val r = r
        val dir = if (forward) 1 else -1
        val xMax = w - 1 - r; val yMax = h - 1 - r
        var row = if (forward) ys else ye - 1
        var rows = 0
        while (row in ys until ye) {
            if ((rows++ and 7) == 0) monitor.checkCancelled()
            val y = n.y0 + row
            val base = row * bw
            var col = if (forward) 0 else bw - 1
            while (col in 0 until bw) {
                val ti = base + col
                var best = f[ti]
                if (best >= 0) {
                    val x = n.x0 + col
                    var bestD = d[ti]
                    // Propagation: the neighbour already visited in this row...
                    val pc = col - dir
                    if (pc in 0 until bw) {
                        val nf = f[base + pc]
                        if (nf >= 0) {
                            // Source columns are >= r and <= w-1-r, so +-1 stays in the row;
                            // invalid border columns are rejected by valid[].
                            val cand = nf + dir
                            if (cand != best && valid[cand].toInt() != 0) {
                                val dd = dist(l, x, y, cand, bestD)
                                if (dd < bestD) { bestD = dd; best = cand }
                            }
                        }
                    }
                    // ... and in the previous row of this band.
                    val pr = row - dir
                    if (pr in ys until ye) {
                        val nf = f[pr * bw + col]
                        if (nf >= 0) {
                            val cand = nf + dir * w
                            if (cand >= 0 && cand < valid.size && cand != best && valid[cand].toInt() != 0) {
                                val dd = dist(l, x, y, cand, bestD)
                                if (dd < bestD) { bestD = dd; best = cand }
                            }
                        }
                    }
                    // Random search around the current match in exponentially shrinking windows.
                    var rad = radius0
                    val by = best / w
                    val bx = best - by * w
                    while (rad >= 1) {
                        val cx = (bx + rng.nextInt(2 * rad + 1) - rad).coerceIn(r, xMax)
                        val cy = (by + rng.nextInt(2 * rad + 1) - rad).coerceIn(r, yMax)
                        val cand = cy * w + cx
                        if (cand != best && valid[cand].toInt() != 0) {
                            val dd = dist(l, x, y, cand, bestD)
                            if (dd < bestD) { bestD = dd; best = cand }
                        }
                        rad = rad shr 1
                    }
                    f[ti] = best
                    d[ti] = bestD
                }
                col += dir
            }
            row += dir
        }
    }

    // ------------------------------------------------------------------ voting

    /**
     * EM M-step: every hole pixel becomes the (gaussian-weighted) mean of the pixels that the
     * matches of all patches covering it propose (Wexler et al.; weights exp(-d / 2 sigma^2)
     * with sigma^2 the 75th percentile of the distances). Texture features are voted alike.
     * Sources are never hole pixels, so writing in place is order-independent. Returns the mean
     * absolute change per channel.
     */
    private fun vote(l: Level, n: Nnf, weighted: Boolean): Double {
        if (l.holeCount == 0) return 0.0
        val w = l.w; val img = l.img; val tex = l.tex; val hole = l.hole
        val f = n.f; val d = n.d; val bw = n.bw; val bh = n.bh; val nx0 = n.x0; val ny0 = n.y0
        val r = r
        val lutScale = if (weighted) (LUT_PER_UNIT / sigma2(n)).toFloat() else 0f
        val lut = WEIGHT_LUT
        val rows = l.hy1 - l.hy0
        val change = LongArray(rows)
        Parallel.forRange(rows, 1) { a, b ->
            for (ry in a until b) {
                if ((ry and 7) == 0) monitor.checkCancelled()
                val y = l.hy0 + ry
                var rowChange = 0L
                for (x in l.hx0 until l.hx1) {
                    val i = y * w + x
                    if (hole[i].toInt() == 0) continue
                    var sa = 0f; var sr = 0f; var sg = 0f; var sb = 0f; var t0 = 0f; var t1 = 0f; var sw = 0f
                    for (dy in -r..r) {
                        val qy = y - dy - ny0
                        if (qy < 0 || qy >= bh) continue
                        val qrow = qy * bw
                        val soff = dy * w
                        for (dx in -r..r) {
                            val qx = x - dx - nx0
                            if (qx < 0 || qx >= bw) continue
                            val qi = qrow + qx
                            val s = f[qi]
                            if (s < 0) continue
                            val wt = if (weighted) {
                                val li = (d[qi] * lutScale).toInt()
                                if (li in 0 until LUT_SIZE) lut[li] else lut[LUT_SIZE - 1]
                            } else 1f
                            val si = s + soff + dx
                            val c = img[si]
                            sa += (c ushr 24) * wt; sr += ((c shr 16) and 0xFF) * wt
                            sg += ((c shr 8) and 0xFF) * wt; sb += (c and 0xFF) * wt
                            if (tex != null) {
                                val t = tex[si]
                                t0 += (t and 0xFF) * wt; t1 += ((t shr 8) and 0xFF) * wt
                            }
                            sw += wt
                        }
                    }
                    if (sw <= 0f) continue
                    val inv = 1f / sw
                    val na = min(255, (sa * inv + 0.5f).toInt())
                    val nr = min(na, (sr * inv + 0.5f).toInt())
                    val ng = min(na, (sg * inv + 0.5f).toInt())
                    val nb = min(na, (sb * inv + 0.5f).toInt())
                    val old = img[i]
                    rowChange += abs(na - (old ushr 24)) + abs(nr - ((old shr 16) and 0xFF)) +
                        abs(ng - ((old shr 8) and 0xFF)) + abs(nb - (old and 0xFF))
                    img[i] = (na shl 24) or (nr shl 16) or (ng shl 8) or nb
                    if (tex != null) tex[i] = min(255, (t0 * inv + 0.5f).toInt()) or (min(255, (t1 * inv + 0.5f).toInt()) shl 8)
                }
                change[ry] = rowChange
            }
        }
        var total = 0L
        for (c in change) total += c
        return total.toDouble() / (l.holeCount * 4.0)
    }

    private fun abs(v: Int) = if (v < 0) -v else v

    /** 75th percentile of the target distances (sampled deterministically), at least 1. */
    private fun sigma2(n: Nnf): Double {
        val total = n.targetCount
        if (total == 0) return 1.0
        val step = max(1, total / SIGMA_SAMPLES)
        val samples = IntArray(total / step + 1)
        var k = 0
        var seen = 0
        for (i in n.f.indices) {
            if (n.f[i] < 0) continue
            if (seen % step == 0 && k < samples.size) samples[k++] = n.d[i]
            seen++
        }
        if (k == 0) return 1.0
        samples.sort(0, k)
        return max(1.0, samples[min(k - 1, k * 3 / 4)].toDouble())
    }

    // ------------------------------------------------------------------ best patch (final step)

    /** Source pixel proposed for ([x], [y]) by the best-matching patch that covers it, or -1. */
    private fun bestSource(l: Level, n: Nnf, x: Int, y: Int): Int {
        val w = l.w
        var bestD = Int.MAX_VALUE
        var bestS = -1
        for (dy in -r..r) {
            val qy = y - dy - n.y0
            if (qy < 0 || qy >= n.bh) continue
            val qrow = qy * n.bw
            for (dx in -r..r) {
                val qx = x - dx - n.x0
                if (qx < 0 || qx >= n.bw) continue
                val qi = qrow + qx
                val s = n.f[qi]
                if (s < 0) continue
                val dd = n.d[qi]
                if (dd < bestD) { bestD = dd; bestS = s + dy * w + dx }
            }
        }
        return bestS
    }

    /**
     * Newson's final step: instead of averaging (which blurs), every hole pixel copies the pixel
     * proposed by the best-matching patch covering it.
     */
    private fun bestPatch(l: Level, n: Nnf, sources: IntArray?) {
        val w = l.w
        Parallel.forRange(l.hy1 - l.hy0, 1) { a, b ->
            for (ry in a until b) {
                if ((ry and 7) == 0) monitor.checkCancelled()
                val y = l.hy0 + ry
                for (x in l.hx0 until l.hx1) {
                    val i = y * w + x
                    if (l.hole[i].toInt() == 0) continue
                    val s = bestSource(l, n, x, y)
                    if (s < 0) continue
                    l.img[i] = l.img[s]
                    sources?.set(i, s)
                }
            }
        }
    }

    // ------------------------------------------------------------------ initialization

    /**
     * Onion peel (Newson et al. 3.4): the hole is filled ring by ring from its border inwards;
     * each pixel of a ring takes the center of the known patch that best matches its already
     * known surroundings. Candidates: the matches of filled neighbours (shifted) and either
     * every valid source or a random subset of them, then a local refinement. Returns the match
     * of each filled pixel (-1 elsewhere).
     */
    private fun onionPeel(l: Level, levelTag: Int): IntArray {
        val w = l.w; val h = l.h; val img = l.img; val tex = l.tex
        val known = ByteArray(w * h)
        for (i in known.indices) known[i] = if (l.hole[i].toInt() == 0) 1 else 0
        val nn = IntArray(w * h) { -1 }
        val validList = l.validList
        val nValid = validList.size
        val valid = l.valid
        val perPixel = max(1L, l.holeCount.toLong() * p * p)
        val budget = (ONION_BUDGET / perPixel).toInt()
        val exhaustive = budget >= nValid
        val m = if (exhaustive) nValid else max(ONION_MIN_CANDIDATES, budget)
        var remaining = l.holeCount
        var ring = 0
        var layer = IntArray(64)
        while (remaining > 0) {
            monitor.checkCancelled()
            var count = 0
            for (y in l.hy0 until l.hy1) {
                for (x in l.hx0 until l.hx1) {
                    val i = y * w + x
                    if (known[i].toInt() != 0) continue
                    var border = false
                    for (oy in -1..1) {
                        val yy = y + oy
                        if (yy < 0 || yy >= h) continue
                        for (ox in -1..1) {
                            val xx = x + ox
                            if (xx < 0 || xx >= w) continue
                            if (known[yy * w + xx].toInt() != 0) { border = true; break }
                        }
                        if (border) break
                    }
                    if (!border) continue
                    if (count == layer.size) layer = layer.copyOf(layer.size * 2)
                    layer[count++] = i
                }
            }
            if (count == 0) {
                // No known pixel touches what's left (can't normally happen): random sources.
                for (y in l.hy0 until l.hy1) for (x in l.hx0 until l.hx1) {
                    val i = y * w + x
                    if (known[i].toInt() != 0) continue
                    val s = validList[Rng(Rng.hash(seed, ONION_TAG, levelTag.toLong(), i.toLong())).nextInt(nValid)]
                    img[i] = img[s]; if (tex != null) tex[i] = tex[s]
                    nn[i] = s; known[i] = 1
                }
                break
            }
            val ringPixels = layer
            val res = IntArray(count)
            val ringNo = ring
            Parallel.forRange(count, 4) { a, b ->
                val best = OnionBest()
                for (j in a until b) {
                    if ((j and 31) == 0) monitor.checkCancelled()
                    val i = ringPixels[j]
                    val y = i / w
                    val x = i - y * w
                    best.reset()
                    // Filled neighbours' matches, shifted to this pixel.
                    for (oy in -1..1) {
                        val yy = y + oy
                        if (yy < 0 || yy >= h) continue
                        for (ox in -1..1) {
                            val xx = x + ox
                            if ((ox == 0 && oy == 0) || xx < 0 || xx >= w) continue
                            val s0 = nn[yy * w + xx]
                            if (s0 < 0) continue
                            val cand = s0 - ox - oy * w
                            if (cand < 0 || cand >= valid.size || valid[cand].toInt() == 0) continue
                            consider(l, known, x, y, cand, best)
                        }
                    }
                    if (exhaustive) {
                        for (cand in validList) consider(l, known, x, y, cand, best)
                    } else {
                        val rng = Rng(Rng.hash(seed, ONION_TAG + 1, levelTag.toLong() * 4096 + ringNo, i.toLong()))
                        repeat(m) { consider(l, known, x, y, validList[rng.nextInt(nValid)], best) }
                    }
                    // Local refinement around the best match.
                    if (best.s >= 0) {
                        val b0 = best.s
                        for (oy in -1..1) for (ox in -1..1) {
                            if (ox == 0 && oy == 0) continue
                            val cand = b0 + ox + oy * w
                            if (cand < 0 || cand >= valid.size || valid[cand].toInt() == 0) continue
                            consider(l, known, x, y, cand, best)
                        }
                    }
                    res[j] = if (best.s >= 0) best.s else validList[Rng(Rng.hash(seed, ONION_TAG + 2, i.toLong())).nextInt(nValid)]
                }
            }
            for (j in 0 until count) {
                val i = ringPixels[j]
                val s = res[j]
                img[i] = img[s]
                if (tex != null) tex[i] = tex[s]
                nn[i] = s
                known[i] = 1
            }
            remaining -= count
            ring++
        }
        return nn
    }

    /** Best onion-peel candidate so far of one pixel. */
    private class OnionBest {
        var s = -1
        var d = Int.MAX_VALUE
        var sq = Int.MAX_VALUE
        fun reset() { s = -1; d = Int.MAX_VALUE; sq = Int.MAX_VALUE }
    }

    /**
     * Keeps [cand] if its partial distance is lower, or equal and the source is nearer. The
     * known part of a border pixel's patch often matches many sources equally (a flat area next
     * to an edge matches the edge's flat side too); the nearest source is the likely one, where
     * scan order would pick an arbitrary edge and start a false structure.
     */
    private fun consider(l: Level, known: ByteArray, x: Int, y: Int, cand: Int, best: OnionBest) {
        if (cand == best.s) return
        val limit = if (best.d == Int.MAX_VALUE) Int.MAX_VALUE else best.d + 1
        val dd = partialDist(l, known, x, y, cand, limit)
        if (dd > best.d) return
        val cy = cand / l.w
        val cx = cand - cy * l.w
        val sq = (cx - x) * (cx - x) + (cy - y) * (cy - y)
        if (dd < best.d || sq < best.sq) {
            best.s = cand; best.d = dd; best.sq = sq
        }
    }

    /** Distance over the KNOWN pixels of the patch at ([x], [y]) only (early exit at [limit]). */
    private fun partialDist(l: Level, known: ByteArray, x: Int, y: Int, s: Int, limit: Int): Int {
        val w = l.w; val h = l.h; val img = l.img; val r = r
        val sy = s / w; val sx = s - sy * w
        var sum = 0
        for (dy in -r..r) {
            val yy = y + dy
            if (yy < 0 || yy >= h) continue
            val trow = yy * w
            val srow = (sy + dy) * w + sx
            for (dx in -r..r) {
                val xx = x + dx
                if (xx < 0 || xx >= w) continue
                val ti = trow + xx
                if (known[ti].toInt() == 0) continue
                val a = img[ti]; val b = img[srow + dx]
                val d0 = (a and 0xFF) - (b and 0xFF)
                val d1 = ((a ushr 8) and 0xFF) - ((b ushr 8) and 0xFF)
                val d2 = ((a ushr 16) and 0xFF) - ((b ushr 16) and 0xFF)
                val d3 = (a ushr 24) - (b ushr 24)
                sum += d0 * d0 + d1 * d1 + d2 * d2 + d3 * d3
            }
            if (sum >= limit) return sum
        }
        return sum
    }

    /** Field of the coarsest level: onion-peel matches in the hole, random valid sources elsewhere. */
    private fun initFromOnion(l: Level, n: Nnf, nn: IntArray, levelTag: Int) {
        val nValid = l.validList.size
        for (row in 0 until n.bh) {
            val y = n.y0 + row
            for (col in 0 until n.bw) {
                val ti = row * n.bw + col
                if (n.isTarget[ti].toInt() == 0) continue
                val i = y * l.w + n.x0 + col
                val s = nn[i]
                n.f[ti] = if (s >= 0 && l.valid[s].toInt() != 0) s
                else l.validList[Rng(Rng.hash(seed, INIT_TAG, levelTag.toLong(), i.toLong())).nextInt(nValid)]
            }
        }
    }

    /**
     * Initial field of level [fine] from [coarse] ([shift] = log2 of the scale step): every
     * target takes its parent's match scaled up plus its own sub-pixel offset; invalid results
     * fall back to the aligned position, then to a random valid source.
     */
    private fun upsampleNnf(coarse: Nnf, cl: Level, fine: Nnf, fl: Level, shift: Int, levelTag: Int) {
        val mask = (1 shl shift) - 1
        val fw = fl.w; val fh = fl.h
        val valid = fl.valid
        val validList = fl.validList
        val nValid = validList.size
        val r = r
        Parallel.forRange(fine.bh, 2) { a, b ->
            for (row in a until b) {
                val y = fine.y0 + row
                val prow = (y shr shift) - coarse.y0
                for (col in 0 until fine.bw) {
                    val ti = row * fine.bw + col
                    if (fine.isTarget[ti].toInt() == 0) continue
                    val x = fine.x0 + col
                    val pcol = (x shr shift) - coarse.x0
                    var s = -1
                    if (prow in 0 until coarse.bh && pcol in 0 until coarse.bw) {
                        val ps = coarse.f[prow * coarse.bw + pcol]
                        if (ps >= 0) {
                            val psy = ps / cl.w; val psx = ps - psy * cl.w
                            val cx = (psx shl shift) + (x and mask)
                            val cy = (psy shl shift) + (y and mask)
                            if (cx in 0 until fw && cy in 0 until fh && valid[cy * fw + cx].toInt() != 0) {
                                s = cy * fw + cx
                            } else if (fw > 2 * r && fh > 2 * r) {
                                val ax = ((psx shl shift) + (mask shr 1)).coerceIn(r, fw - 1 - r)
                                val ay = ((psy shl shift) + (mask shr 1)).coerceIn(r, fh - 1 - r)
                                if (valid[ay * fw + ax].toInt() != 0) s = ay * fw + ax
                            }
                        }
                    }
                    if (s < 0) s = validList[Rng(Rng.hash(seed, UPSAMPLE_TAG, levelTag.toLong(), (y.toLong() shl 32) + x)).nextInt(nValid)]
                    fine.f[ti] = s
                }
            }
        }
    }

    // ------------------------------------------------------------------ color adaptation

    /**
     * Membrane color adaptation: along a thin ring just outside the hole, the difference between
     * the original pixels and what the fill proposes there ([proposal]) is interpolated smoothly
     * into the hole (pull-push + a few relaxation sweeps) and added to the fill. Removes low
     * frequency brightness / color mismatches (gradients, vignetting) that patch copying can't.
     */
    private fun colorAdapt(l: Level, proposal: (x: Int, y: Int) -> Int) {
        if (l.holeCount == 0) return
        val w = l.w; val h = l.h
        val rx0 = max(0, l.hx0 - RING); val ry0 = max(0, l.hy0 - RING)
        val rx1 = min(w, l.hx1 + RING); val ry1 = min(h, l.hy1 + RING)
        val bw = rx1 - rx0; val bh = ry1 - ry0
        val size = bw * bh
        val holeCrop = ByteArray(size)
        for (y in 0 until bh) System.arraycopy(l.hole, (ry0 + y) * w + rx0, holeCrop, y * bw, bw)
        val near = HoleOps.dilateSquare(holeCrop, bw, bh, RING)
        val delta = Array(4) { FloatArray(size) }
        val wgt = FloatArray(size)
        var ringCount = 0
        for (y in 0 until bh) {
            if ((y and 15) == 0) monitor.checkCancelled()
            for (x in 0 until bw) {
                val j = y * bw + x
                if (holeCrop[j].toInt() != 0 || near[j].toInt() == 0) continue
                val s = proposal(rx0 + x, ry0 + y)
                if (s < 0) continue
                val o = l.img[(ry0 + y) * w + rx0 + x]
                val fv = l.img[s]
                for (c in 0 until 4) {
                    val sh = c * 8
                    delta[c][j] = (((o ushr sh) and 0xFF) - ((fv ushr sh) and 0xFF)).toFloat().coerceIn(-MAX_DELTA, MAX_DELTA)
                }
                wgt[j] = 1f
                ringCount++
            }
        }
        if (ringCount == 0) return
        pullPush(delta, wgt, bw, bh)
        // Relaxation (Gauss-Seidel, sequential so it stays deterministic) with the ring fixed.
        val sweeps = (RELAX_BUDGET / max(1, l.holeCount)).coerceIn(2, 30)
        repeat(sweeps) {
            monitor.checkCancelled()
            for (y in 0 until bh) for (x in 0 until bw) {
                val j = y * bw + x
                if (holeCrop[j].toInt() == 0) continue
                var n = 0
                var s0 = 0f; var s1 = 0f; var s2 = 0f; var s3 = 0f
                if (x > 0) { val q = j - 1; s0 += delta[0][q]; s1 += delta[1][q]; s2 += delta[2][q]; s3 += delta[3][q]; n++ }
                if (x < bw - 1) { val q = j + 1; s0 += delta[0][q]; s1 += delta[1][q]; s2 += delta[2][q]; s3 += delta[3][q]; n++ }
                if (y > 0) { val q = j - bw; s0 += delta[0][q]; s1 += delta[1][q]; s2 += delta[2][q]; s3 += delta[3][q]; n++ }
                if (y < bh - 1) { val q = j + bw; s0 += delta[0][q]; s1 += delta[1][q]; s2 += delta[2][q]; s3 += delta[3][q]; n++ }
                if (n == 0) continue
                val inv = 1f / n
                delta[0][j] = s0 * inv; delta[1][j] = s1 * inv; delta[2][j] = s2 * inv; delta[3][j] = s3 * inv
            }
        }
        // Apply inside the hole (premultiplied: 0 <= rgb <= alpha).
        for (y in 0 until bh) for (x in 0 until bw) {
            val j = y * bw + x
            if (holeCrop[j].toInt() == 0) continue
            val i = (ry0 + y) * w + rx0 + x
            val c = l.img[i]
            val b = ((c and 0xFF) + delta[0][j]).roundToInt()
            val g = (((c shr 8) and 0xFF) + delta[1][j]).roundToInt()
            val rr = (((c shr 16) and 0xFF) + delta[2][j]).roundToInt()
            val a = ((c ushr 24) + delta[3][j]).roundToInt().coerceIn(0, 255)
            l.img[i] = (a shl 24) or (rr.coerceIn(0, a) shl 16) or (g.coerceIn(0, a) shl 8) or b.coerceIn(0, a)
        }
    }

    /**
     * Pull-push interpolation: values with weight 1 stay, the rest is filled from successively
     * coarser averages (bilinear push-down), giving a smooth membrane over the unknown pixels.
     */
    private fun pullPush(values: Array<FloatArray>, weight: FloatArray, w: Int, h: Int) {
        val vs = ArrayList<Array<FloatArray>>()
        val ws = ArrayList<FloatArray>()
        val dims = ArrayList<IntArray>()
        vs += values; ws += weight; dims += intArrayOf(w, h)
        var cw = w; var ch = h
        while (cw > 1 || ch > 1) {
            val nw = (cw + 1) / 2; val nh = (ch + 1) / 2
            val pv = vs.last(); val pw = ws.last()
            val nv = Array(4) { FloatArray(nw * nh) }
            val nwt = FloatArray(nw * nh)
            for (y in 0 until nh) for (x in 0 until nw) {
                var sw = 0f
                var a0 = 0f; var a1 = 0f; var a2 = 0f; var a3 = 0f
                for (yy in 2 * y until min(ch, 2 * y + 2)) for (xx in 2 * x until min(cw, 2 * x + 2)) {
                    val q = yy * cw + xx
                    val wt = pw[q]
                    if (wt <= 0f) continue
                    sw += wt
                    a0 += pv[0][q] * wt; a1 += pv[1][q] * wt; a2 += pv[2][q] * wt; a3 += pv[3][q] * wt
                }
                val j = y * nw + x
                if (sw > 0f) {
                    nv[0][j] = a0 / sw; nv[1][j] = a1 / sw; nv[2][j] = a2 / sw; nv[3][j] = a3 / sw
                    nwt[j] = min(1f, sw)
                }
            }
            vs += nv; ws += nwt; dims += intArrayOf(nw, nh)
            cw = nw; ch = nh
        }
        for (lv in vs.size - 2 downTo 0) {
            val fv = vs[lv]; val fwt = ws[lv]
            val fw = dims[lv][0]; val fh = dims[lv][1]
            val cv = vs[lv + 1]
            val cw2 = dims[lv + 1][0]; val ch2 = dims[lv + 1][1]
            for (y in 0 until fh) for (x in 0 until fw) {
                val j = y * fw + x
                val wt = fwt[j]
                if (wt >= 1f) continue
                val gx = ((x + 0.5f) / 2f - 0.5f).coerceIn(0f, (cw2 - 1).toFloat())
                val gy = ((y + 0.5f) / 2f - 0.5f).coerceIn(0f, (ch2 - 1).toFloat())
                val x0 = gx.toInt(); val y0 = gy.toInt()
                val x1 = min(cw2 - 1, x0 + 1); val y1 = min(ch2 - 1, y0 + 1)
                val tx = gx - x0; val ty = gy - y0
                val i00 = y0 * cw2 + x0; val i10 = y0 * cw2 + x1; val i01 = y1 * cw2 + x0; val i11 = y1 * cw2 + x1
                for (c in 0 until 4) {
                    val cc = cv[c]
                    val up = (cc[i00] * (1 - tx) + cc[i10] * tx) * (1 - ty) + (cc[i01] * (1 - tx) + cc[i11] * tx) * ty
                    fv[c][j] = fv[c][j] * wt + up * (1 - wt)
                }
                fwt[j] = 1f
            }
        }
    }

    // ------------------------------------------------------------------ result

    private fun buildResult(full: Level, sources: IntArray?): InpaintResult {
        val roi = plan.roi
        val ch = plan.changed
        val cw = ch.width; val chh = ch.height
        val fill = IntArray(cw * chh)
        val src = if (sources != null) IntArray(cw * chh) { -1 } else null
        val ox = ch.left - roi.left; val oy = ch.top - roi.top
        for (y in 0 until chh) {
            for (x in 0 until cw) {
                val i = (oy + y) * full.w + ox + x
                if (full.hole[i].toInt() == 0) continue
                val j = y * cw + x
                fill[j] = unpremul(full.img[i])
                if (src != null && sources!![i] >= 0) {
                    val s = sources[i]
                    val sy = s / full.w; val sx = s - sy * full.w
                    src[j] = (sy + roi.top) * plan.imageWidth + sx + roi.left
                }
            }
        }
        return InpaintResult(ch, fill, plan.weight, params.seed, src)
    }

    companion object {
        /** Holes this thin (erosions to empty) use one level and 5x5 patches. */
        const val THIN_RADIUS = 3
        /** Known pixels this close to the hole are never copied (halo of a removed object). */
        const val SOURCE_GUARD = 2
        const val MAX_LEVELS = 7
        const val MAX_WORK_SHIFT = 5
        const val MIN_VALID = 24
        const val CONVERGED = 0.1
        const val BAND_ROWS = 12
        const val MAX_BANDS = 24
        const val SIGMA_SAMPLES = 4096
        const val ONION_BUDGET = 60_000_000L
        const val ONION_MIN_CANDIDATES = 48
        const val FULL_RES_SEARCH_LIMIT = 3_000_000
        const val FULL_RES_BOX_LIMIT = 5_000_000L
        const val FULL_PASS_UNITS = 3.0
        const val RING = 2
        const val MAX_DELTA = 96f
        const val RELAX_BUDGET = 4_000_000
        const val MAX_DIST = Int.MAX_VALUE / 2

        const val FULL_LEVEL_TAG = 100
        const val ONION_TAG = 1_000L
        const val INIT_TAG = 2_000L
        const val UPSAMPLE_TAG = 3_000L
        const val DIRECT_TAG = 4_000L

        const val NO_SOURCE = "There isn't enough picture around the selection to fill it from. Select a smaller area or use a larger sampling area."

        private const val LUT_SIZE = 4096
        private const val LUT_PER_UNIT = 128.0
        /** exp(-v / 2) for v = index / LUT_PER_UNIT (weights of the vote). */
        private val WEIGHT_LUT = FloatArray(LUT_SIZE) { i -> max(1e-30, exp(-(i / LUT_PER_UNIT) / 2.0)).toFloat() }

        fun premul(c: Int): Int {
            val a = c ushr 24
            if (a == 255) return c
            if (a == 0) return 0
            val r = (((c shr 16) and 0xFF) * a + 127) / 255
            val g = (((c shr 8) and 0xFF) * a + 127) / 255
            val b = ((c and 0xFF) * a + 127) / 255
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        fun unpremul(c: Int): Int {
            val a = c ushr 24
            if (a == 255) return c
            if (a == 0) return 0
            val half = a / 2
            val r = min(255, (((c shr 16) and 0xFF) * 255 + half) / a)
            val g = min(255, (((c shr 8) and 0xFF) * 255 + half) / a)
            val b = min(255, ((c and 0xFF) * 255 + half) / a)
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
