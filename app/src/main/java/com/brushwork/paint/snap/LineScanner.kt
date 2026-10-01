package com.brushwork.paint.snap

import com.brushwork.paint.tools.transform.SnapAxis
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * How [LineDetector] finds straight horizontal / vertical lines (pure Kotlin, no Android):
 *
 *  1. One streaming pass over the rows (read in strips, bounded memory): every pixel is compared
 *     with the one above it (for horizontal lines) and the one on its left (vertical lines) as
 *     premultiplied RGBA, so transparent vs any color and color vs color both count. Runs of
 *     "edge" pixels along a row / column boundary become pieces, with their summed difference
 *     and the mean colors on both sides.
 *  2. Pieces on neighbouring boundaries that overlap make one edge (anti-aliasing spreads a step
 *     over two boundaries, a thin line over three). Its position is the difference-weighted
 *     centroid of its boundaries: blending is linear in coverage, so this is exact to a fraction
 *     of a pixel for anti-aliased steps (the edge) and thin lines (their middle). An edge is a
 *     STEP (one color to another) or a PULSE (a thin line: the same color on both sides).
 *     Thick stacks of edges (texture, noise, soft blur) are not lines.
 *  3. Collinear steps of the same colors merge; two facing steps with the same color between
 *     them pair into a band (a thick line), thinnest first (line-colored insides before ones of
 *     the page's background color: gaps between things): one line at its middle when the band is
 *     longer than thick, plus its two sides when it is thicker than [THICK_SIDES]. A step left
 *     alone is a line at the edge; one that is close to a border of the image, with line color
 *     between, is a line clipped by the image (e.g. a table border drawn on the canvas edge): a
 *     line on that border too.
 *  4. Collinear results merge and only long ones stay: pieces chained across short gaps must
 *     cover three quarters of their extent and [minLength] in all, where a gap that a line of the
 *     other axis, about as thick as the gap, crosses counts as covered (a table line is cut
 *     wherever a crossing line's color meets it; the gaps between letters of a text are crossed
 *     by nothing); otherwise a piece must be [minLength] long by itself, or be one of several
 *     collinear sides of boxes (lines of the other axis turn from both its ends to the same side
 *     and a parallel line closes the box: the cells of a Table with a Space between them, even
 *     small ones far apart). A line that the other axis' edges end on every few pixels is the top
 *     or bottom of a row of letters, not a line. The longest [LineDetector.MAX_LINES_PER_AXIS]
 *     per axis are kept.
 *
 * [borders] (bits [BORDER_TOP]...) are the sides of the scanned area that are real borders of
 * the image (where a line may be clipped); the others had a neighbouring row / column read along
 * (so an edge there is a real edge).
 */
internal class LineScanner(
    private val width: Int,
    private val height: Int,
    /** Shortest line kept (px); see [LineDetector.minLength]. */
    private val minLength: Float,
    private val borders: Int = ALL_BORDERS,
) {
    /** Shortest run of edge pixels that counts at all (shorter ones are text, noise...). */
    private val minSeg = max(MIN_SEG_FLOOR, (minLength / 4f).toInt())

    fun run(readRows: (y0: Int, rows: Int, out: IntArray) -> Unit, cancelled: () -> Boolean): List<DetectedLine> {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return emptyList()
        val stripRows = max(1, min(h, STRIP_PIXELS / w))
        val buf = IntArray(stripRows * w)
        var prev = IntArray(w)
        var cur = IntArray(w)
        val longPiece = kotlin.math.ceil(minLength).toInt()
        val hp = Pieces(longPiece)
        val vp = Pieces(longPiece)
        val runs = ColumnRuns(w, minSeg)
        val hist = IntArray(HIST_SIZE)
        var y0 = 0
        while (y0 < h) {
            if (cancelled()) return emptyList()
            val rows = min(stripRows, h - y0)
            readRows(y0, rows, buf)
            for (r in 0 until rows) {
                val y = y0 + r
                val base = r * w
                for (x in 0 until w) cur[x] = premultiply(buf[base + x])
                if (y and 7 == 0) sample(cur, hist)
                if (y > 0) rowBoundary(prev, cur, y, hp)
                runs.row(cur, y, vp)
                val t = prev
                prev = cur
                cur = t
            }
            y0 += rows
        }
        runs.closeAll(vp)
        if (cancelled()) return emptyList()
        val bg = background(hist)
        val horizontal = candidates(hp, h, bg, BORDER_TOP, BORDER_BOTTOM, cancelled)
        if (cancelled()) return emptyList()
        val vertical = candidates(vp, w, bg, BORDER_LEFT, BORDER_RIGHT, cancelled)
        if (cancelled()) return emptyList()
        // Each axis' lines may be cut where the other axis' lines cross them, and are not lines
        // where the other axis' edges end on them all along (text) - except where the other
        // axis' lines meet them (the border of a fine grid).
        val indexH = LineIndex(horizontal)
        val indexV = LineIndex(vertical)
        val foundH = collect(horizontal, Crossings(vertical), indexV, indexH)
        val foundV = collect(vertical, Crossings(horizontal), indexH, indexV)
        if (cancelled()) return emptyList()
        val out = ArrayList<DetectedLine>()
        out += finish(foundH, SnapAxis.Y, Ends(vp), Meeting(foundV))
        out += finish(foundV, SnapAxis.X, Ends(hp), Meeting(foundH))
        return if (cancelled()) emptyList() else out
    }

    // ------------------------------------------------------------------ pass 1: pieces

    /** Edge pieces along the boundary [b] between rows [above] (b - 1) and [below] (b). */
    private fun rowBoundary(above: IntArray, below: IntArray, b: Int, out: Pieces) {
        val w = width
        var open = false
        var start = 0
        var last = 0
        var weight = 0
        var count = 0
        var a0 = 0; var a1 = 0; var a2 = 0; var a3 = 0
        var b0 = 0; var b1 = 0; var b2 = 0; var b3 = 0
        for (x in 0 until w) {
            val p = above[x]
            val q = below[x]
            if (p == q) continue
            val d = diff(p, q)
            if (d < EDGE) continue
            if (open && x - last - 1 > GAP) {
                if (last + 1 - start >= minSeg) out.add(b, start, last + 1, weight, count, a0, a1, a2, a3, b0, b1, b2, b3)
                open = false
            }
            if (!open) {
                open = true
                start = x
                weight = 0; count = 0
                a0 = 0; a1 = 0; a2 = 0; a3 = 0
                b0 = 0; b1 = 0; b2 = 0; b3 = 0
            }
            last = x
            weight += d
            count++
            a0 += p ushr 24; a1 += (p shr 16) and 255; a2 += (p shr 8) and 255; a3 += p and 255
            b0 += q ushr 24; b1 += (q shr 16) and 255; b2 += (q shr 8) and 255; b3 += q and 255
        }
        if (open && last + 1 - start >= minSeg) out.add(b, start, last + 1, weight, count, a0, a1, a2, a3, b0, b1, b2, b3)
    }

    /** Vertical runs of edge pixels per column boundary x (between columns x - 1 and x). */
    private class ColumnRuns(private val w: Int, private val minSeg: Int) {
        private val start = IntArray(w) { -1 }
        private val last = IntArray(w)
        private val weight = IntArray(w)
        private val count = IntArray(w)
        private val left = Array(4) { IntArray(w) }
        private val right = Array(4) { IntArray(w) }

        fun row(cur: IntArray, y: Int, out: Pieces) {
            val l0 = left[0]; val l1 = left[1]; val l2 = left[2]; val l3 = left[3]
            val r0 = right[0]; val r1 = right[1]; val r2 = right[2]; val r3 = right[3]
            var p = cur[0]
            for (x in 1 until w) {
                val q = cur[x]
                if (p == q) continue
                val d = diff(p, q)
                if (d >= EDGE) {
                    if (start[x] >= 0 && y - last[x] - 1 > GAP) close(x, out)
                    if (start[x] < 0) {
                        start[x] = y
                        weight[x] = 0; count[x] = 0
                        l0[x] = 0; l1[x] = 0; l2[x] = 0; l3[x] = 0
                        r0[x] = 0; r1[x] = 0; r2[x] = 0; r3[x] = 0
                    }
                    last[x] = y
                    weight[x] += d
                    count[x]++
                    l0[x] += p ushr 24; l1[x] += (p shr 16) and 255; l2[x] += (p shr 8) and 255; l3[x] += p and 255
                    r0[x] += q ushr 24; r1[x] += (q shr 16) and 255; r2[x] += (q shr 8) and 255; r3[x] += q and 255
                }
                p = q
            }
        }

        private fun close(x: Int, out: Pieces) {
            val s = start[x]
            start[x] = -1
            if (last[x] + 1 - s < minSeg) return
            out.add(
                x, s, last[x] + 1, weight[x], count[x],
                left[0][x], left[1][x], left[2][x], left[3][x],
                right[0][x], right[1][x], right[2][x], right[3][x],
            )
        }

        fun closeAll(out: Pieces) {
            for (x in 1 until w) if (start[x] >= 0) close(x, out)
        }
    }

    /**
     * Pieces found on one axis, as packed ints: boundary, start, end (exclusive), summed
     * difference, number of edge pixels, mean color on the low side (above / left), mean color
     * on the high side. Memory is bounded: past half of [MAX_PIECES] (a very busy image) only
     * pieces at least [longPiece] long are kept, so the rest of the image still gets its long
     * lines; past [MAX_PIECES], nothing more.
     */
    private class Pieces(private val longPiece: Int) {
        var size = 0
            private set
        private var data = IntArray(FIELDS * 64)

        /**
         * Adds a run of [count] edge pixels, given the sums of their channels on both sides,
         * unless it is mostly holes (scattered noise, not an edge).
         */
        fun add(
            boundary: Int, start: Int, end: Int, weight: Int, count: Int,
            la: Int, lr: Int, lg: Int, lb: Int,
            ha: Int, hr: Int, hg: Int, hb: Int,
        ) {
            if (size >= MAX_PIECES || count <= 0 || count * 4 < (end - start) * 3) return
            if (size >= MAX_PIECES / 2 && end - start < longPiece) return
            val i = size * FIELDS
            if (i + FIELDS > data.size) data = data.copyOf(data.size * 2)
            data[i] = boundary
            data[i + 1] = start
            data[i + 2] = end
            data[i + 3] = weight
            data[i + 4] = count
            data[i + 5] = mean(la, lr, lg, lb, count)
            data[i + 6] = mean(ha, hr, hg, hb, count)
            size++
        }

        fun boundary(k: Int) = data[k * FIELDS]
        fun start(k: Int) = data[k * FIELDS + 1]
        fun end(k: Int) = data[k * FIELDS + 2]
        fun weight(k: Int) = data[k * FIELDS + 3]
        fun count(k: Int) = data[k * FIELDS + 4]
        fun low(k: Int) = data[k * FIELDS + 5]
        fun high(k: Int) = data[k * FIELDS + 6]
        fun length(k: Int) = end(k) - start(k)

        companion object {
            const val FIELDS = 7
        }
    }

    // ------------------------------------------------------------------ pass 2: edges

    /**
     * A straight edge on one axis: at [pos] (pixel edges), covering [segs] along the line.
     * [low] / [high] are the mean premultiplied colors before / after it (above / below, left /
     * right). [step]: one color to another; otherwise a thin line (pulse).
     */
    private class Edge(val pos: Float, val segs: IntArray, val low: Int, val high: Int, val step: Boolean, val thickness: Float) {
        val length: Int = Segs.length(segs)
    }

    /** Groups [p]'s pieces into edges (see the class comment, step 2). */
    private fun edges(p: Pieces, cancelled: () -> Boolean): List<Edge> {
        val n = p.size
        if (n == 0) return emptyList()
        // Pieces by boundary, then start.
        val keys = LongArray(n) { i -> (p.boundary(i).toLong() shl 42) or (p.start(i).toLong() shl 21) or i.toLong() }
        keys.sort()
        val order = IntArray(n) { (keys[it] and INDEX_MASK).toInt() }
        val parent = IntArray(n) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }
        // Link pieces of neighbouring boundaries when the shorter one lies mostly along the
        // other (both ranges sorted by start): the boundaries of one anti-aliased edge, even
        // when one of them is cut into pieces. Specks of noise linked to a long edge this way
        // stay out of its core (below) and so don't move it.
        var prevS = -1
        var prevE = -1
        var i = 0
        while (i < n) {
            val b = p.boundary(order[i])
            var j = i
            while (j < n && p.boundary(order[j]) == b) j++
            if (prevS >= 0 && p.boundary(order[prevS]) == b - 1) {
                var a = prevS
                var c = i
                while (a < prevE && c < j) {
                    val pa = order[a]
                    val pc = order[c]
                    val ov = min(p.end(pa), p.end(pc)) - max(p.start(pa), p.start(pc))
                    if (ov > 0 && ov * 2 >= min(p.length(pa), p.length(pc))) {
                        val ra = find(pa)
                        val rc = find(pc)
                        if (ra != rc) parent[max(ra, rc)] = min(ra, rc)
                    }
                    if (p.end(pa) < p.end(pc)) a++ else c++
                }
            }
            prevS = i
            prevE = j
            i = j
        }
        if (cancelled()) return emptyList()
        // Members of each group, in (boundary, start) order.
        val rootOf = IntArray(n)
        val count = IntArray(n + 1)
        for (k in 0 until n) {
            val r = find(order[k])
            rootOf[k] = r
            count[r + 1]++
        }
        for (k in 0 until n) count[k + 1] += count[k]
        val members = IntArray(n)
        val fill = count.copyOf(n)
        for (k in 0 until n) members[fill[rootOf[k]]++] = order[k]

        val out = ArrayList<Edge>()
        // Per-boundary sums of the group being looked at.
        var bs = IntArray(16)
        var ls = IntArray(16)
        var cs = IntArray(16)
        var ws = LongArray(16)
        var firstMember = IntArray(16)
        for (r in 0 until n) {
            val from = count[r]
            val to = count[r + 1]
            if (to <= from) continue
            // Distinct boundaries of the group.
            var nb = 0
            var k = from
            while (k < to) {
                val b = p.boundary(members[k])
                if (nb == bs.size) {
                    bs = bs.copyOf(nb * 2); ls = ls.copyOf(nb * 2); cs = cs.copyOf(nb * 2); ws = ws.copyOf(nb * 2)
                    firstMember = firstMember.copyOf(nb * 2)
                }
                bs[nb] = b
                ls[nb] = 0
                cs[nb] = 0
                ws[nb] = 0
                firstMember[nb] = k
                while (k < to && p.boundary(members[k]) == b) {
                    ls[nb] += p.length(members[k])
                    cs[nb] += p.count(members[k])
                    ws[nb] += p.weight(members[k]).toLong()
                    k++
                }
                nb++
            }
            // The core: the main boundary and its neighbours covering at least half as much.
            var main = 0
            for (q in 1 until nb) if (ls[q] > ls[main]) main = q
            var c0 = main
            var c1 = main
            while (c0 > 0 && bs[c0 - 1] == bs[c0] - 1 && ls[c0 - 1] * 2 >= ls[main]) c0--
            while (c1 < nb - 1 && bs[c1 + 1] == bs[c1] + 1 && ls[c1 + 1] * 2 >= ls[main]) c1++
            val coreHeight = c1 - c0 + 1
            if (coreHeight > MAX_CORE) continue
            var wSum = 0.0
            var bwSum = 0.0
            var perPixel = 0.0
            var consistent = 0.0
            for (q in c0..c1) {
                wSum += ws[q]
                bwSum += ws[q].toDouble() * bs[q]
                if (cs[q] > 0) perPixel += ws[q].toDouble() / cs[q]
                // How much the mean colors on both sides of this boundary differ, against how
                // much its pixels do: about as much for a real edge, little for noise (whose
                // differences go every which way and average out).
                val end = if (q + 1 < nb) firstMember[q + 1] else to
                val d = diff(meanColor(p, members, firstMember[q], end, high = false), meanColor(p, members, firstMember[q], end, high = true))
                consistent += d.toDouble() * cs[q]
            }
            if (wSum <= 0.0 || consistent < CONSISTENCY * wSum) continue
            val pos = (bwSum / wSum).toFloat()
            val low = meanColor(p, members, firstMember[c0], end = if (c0 + 1 < nb) firstMember[c0 + 1] else to, high = false)
            val high = meanColor(p, members, firstMember[c1], end = if (c1 + 1 < nb) firstMember[c1 + 1] else to, high = true)
            // A step changes color across the edge by about as much as its pixels differ; a thin
            // line has the same color on both sides.
            val change = diff(low, high)
            val step = change >= EDGE && change >= STEP_RATIO * perPixel
            if (!step && coreHeight < 2) continue
            val mainEnd = if (main + 1 < nb) firstMember[main + 1] else to
            val segs = IntArray((mainEnd - firstMember[main]) * 2)
            var s = 0
            for (m in firstMember[main] until mainEnd) {
                segs[s++] = p.start(members[m])
                segs[s++] = p.end(members[m])
            }
            out += Edge(pos, segs, low, high, step, if (step) 0f else (coreHeight - 1).toFloat())
        }
        return out
    }

    /** Length-weighted mean color on one side of pieces members[start until end]. */
    private fun meanColor(p: Pieces, members: IntArray, start: Int, end: Int, high: Boolean): Int {
        var a = 0L; var r = 0L; var g = 0L; var b = 0L; var n = 0L
        for (m in start until end) {
            val k = members[m]
            val c = if (high) p.high(k) else p.low(k)
            val l = p.length(k).toLong()
            a += (c ushr 24) * l; r += ((c shr 16) and 255) * l; g += ((c shr 8) and 255) * l; b += (c and 255) * l
            n += l
        }
        if (n <= 0L) return 0
        return pack((a / n).toInt(), (r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    // ------------------------------------------------------------------ pass 3: lines

    /** Line found before the final merge: at [pos], over [segs], [thickness] across. */
    private class Candidate(val pos: Float, val segs: IntArray, val thickness: Float)

    /** Lines (before the final merge) from the pieces [p] of one axis; [extent] = the image size across them. */
    private fun candidates(p: Pieces, extent: Int, bg: Int?, lowBorder: Int, highBorder: Int, cancelled: () -> Boolean): List<Candidate> {
        val raw = edges(p, cancelled)
        if (raw.isEmpty() || cancelled()) return emptyList()
        val edges = mergeCollinear(raw)
        val steps = edges.filter { it.step }
        val cands = ArrayList<Candidate>()
        for (e in edges) if (!e.step) cands += Candidate(e.pos, e.segs, e.thickness)

        // Facing steps with the same color between them: bands, thinnest first (line-colored
        // insides before background-colored ones: those are gaps between things).
        val pairs = ArrayList<LongArray>()
        for (i in steps.indices) {
            val a = steps[i]
            var found = 0
            for (j in i + 1 until min(steps.size, i + 1 + MAX_SCANNED)) {
                val b = steps[j]
                val d = b.pos - a.pos
                if (d > MAX_BAND) break
                if (d < MIN_BAND) continue
                val inside = a.high
                if (diff(inside, b.low) > SIMILAR) continue
                if ((inside ushr 24) < MIN_INSIDE_ALPHA || (b.low ushr 24) < MIN_INSIDE_ALPHA) continue
                if (!sideBySide(a, b)) continue
                val gap = if (bg != null && diff(inside, bg) <= SIMILAR) 1L else 0L
                // Sort key: gap flag, distance (1/64 px), then indices.
                pairs += longArrayOf((gap shl 40) or ((d * 64f).toLong() shl 20), i.toLong(), j.toLong())
                if (++found >= MAX_PARTNERS) break
            }
        }
        pairs.sortBy { it[0] }
        val partner = IntArray(steps.size) { -1 }
        for (pr in pairs) {
            val i = pr[1].toInt()
            val j = pr[2].toInt()
            if (partner[i] >= 0 || partner[j] >= 0) continue
            partner[i] = j
            partner[j] = i
        }
        for (i in steps.indices) {
            val j = partner[i]
            if (j <= i) continue
            val a = steps[i]
            val b = steps[j]
            val t = b.pos - a.pos
            val segs = Segs.union(a.segs, b.segs)
            // Its middle is a line when it is longer than thick (else a blob, e.g. the space
            // between two letters).
            if (Segs.length(segs) >= t) cands += Candidate((a.pos + b.pos) / 2f, segs, t)
            if (t > THICK_SIDES) {
                cands += Candidate(a.pos, a.segs, 0f)
                cands += Candidate(b.pos, b.segs, 0f)
            }
        }
        for (i in steps.indices) {
            if (partner[i] >= 0) continue
            val e = steps[i]
            // A lone step is a line at the edge; at a border with line color in between, it is
            // the inner side of a line cut by the image, whose middle is on that border (the
            // side only counts for thick lines, as for bands).
            val clipped = clippedAtBorder(e, edges, extent, bg, lowBorder, highBorder)
            if (clipped != null) cands += clipped
            if (clipped == null || clipped.thickness > THICK_SIDES) cands += Candidate(e.pos, e.segs, 0f)
        }
        return if (cancelled()) emptyList() else cands
    }

    /**
     * Collinear edges merged: pulses at the same place, and steps at the same place with the
     * same color on at least one side (the pieces of one edge cut by crossing lines).
     */
    private fun mergeCollinear(list: List<Edge>): List<Edge> {
        val sorted = list.sortedBy { it.pos }
        val out = ArrayList<Edge>(sorted.size)
        val open = ArrayList<MutableList<Edge>>()
        fun flush(g: List<Edge>) {
            if (g.size == 1) { out += g[0]; return }
            var total = 0.0
            var posSum = 0.0
            var thick = 0f
            var segs = IntArray(0)
            for (e in g) {
                total += e.length
                posSum += e.pos.toDouble() * e.length
                thick = max(thick, e.thickness)
                segs = Segs.union(segs, e.segs)
            }
            val pos = if (total > 0.0) (posSum / total).toFloat() else g[0].pos
            out += Edge(pos, segs, weightedColor(g, high = false), weightedColor(g, high = true), g[0].step, thick)
        }
        for (e in sorted) {
            // Groups that can no longer take anything are done.
            val it = open.iterator()
            while (it.hasNext()) {
                val g = it.next()
                if (e.pos - g.last().pos > COLLINEAR) { flush(g); it.remove() }
            }
            val g = open.firstOrNull { g ->
                val f = g[0]
                f.step == e.step && (!e.step || diff(f.low, e.low) <= SIMILAR || diff(f.high, e.high) <= SIMILAR)
            }
            if (g != null) g += e else open += mutableListOf(e)
        }
        for (g in open) flush(g)
        return out.sortedBy { it.pos }
    }

    private fun weightedColor(g: List<Edge>, high: Boolean): Int {
        var a = 0.0; var r = 0.0; var gg = 0.0; var b = 0.0; var n = 0.0
        for (e in g) {
            val c = if (high) e.high else e.low
            val l = e.length.toDouble()
            a += (c ushr 24) * l; r += ((c shr 16) and 255) * l; gg += ((c shr 8) and 255) * l; b += (c and 255) * l
            n += l
        }
        if (n <= 0.0) return if (high) g[0].high else g[0].low
        return pack((a / n).toInt(), (r / n).toInt(), (gg / n).toInt(), (b / n).toInt())
    }

    /**
     * A lone step close to a border of the image with line color (not the background, not
     * transparent) between it and the border, and nothing else in between: the visible part of
     * a line clipped by the image (e.g. a table drawn with no margin), whose middle is on the
     * border.
     */
    private fun clippedAtBorder(e: Edge, edges: List<Edge>, extent: Int, bg: Int?, lowBorder: Int, highBorder: Int): Candidate? {
        val toLow = e.pos <= MAX_BAND / 2f && (borders and lowBorder) != 0
        val toHigh = extent - e.pos <= MAX_BAND / 2f && (borders and highBorder) != 0
        val border = when {
            toLow && (!toHigh || e.pos <= extent - e.pos) -> 0f
            toHigh -> extent.toFloat()
            else -> return null
        }
        if (abs(e.pos - border) < MIN_BAND) return null
        val between = if (border == 0f) e.low else e.high
        if ((between ushr 24) < MIN_INSIDE_ALPHA || (bg != null && diff(between, bg) <= SIMILAR)) return null
        val lo = min(border, e.pos)
        val hi = max(border, e.pos)
        for (o in edges) {
            if (o === e || o.pos <= lo || o.pos >= hi) continue
            if (sideBySide(o, e)) return null
        }
        return Candidate(border, e.segs, 2f * abs(e.pos - border))
    }

    /**
     * Whether [a] and [b] run side by side along most of the shorter one and a good part of the
     * longer one (a speck of noise next to a long edge is not its other side).
     */
    private fun sideBySide(a: Edge, b: Edge): Boolean {
        val ov = Segs.intersection(a.segs, b.segs)
        return ov * 2 >= min(a.length, b.length) && ov * 4 >= max(a.length, b.length)
    }

    /**
     * The lines of one axis (before the final merge), for telling where the other axis' lines are
     * crossed by them: sorted by position, long ones only (and clearly longer than thick).
     */
    private inner class Crossings(cands: List<Candidate>) {
        private val list = cands.filter { Segs.length(it.segs) >= max(minLength, 2f * it.thickness) }.sortedBy { it.pos }
        private val pos = FloatArray(list.size) { list[it].pos }

        /**
         * Whether a line at [at], [thickness] thick (on the other axis), is crossed inside its
         * gap [g0, g1): one of these lines lies inside the gap (not on its ends: those are the
         * sides of whatever stops there, e.g. letters), is about as thick as the gap is wide
         * (it is what cuts the line there) and one of its pieces reaches the line (it runs
         * through it, or stops on it: a T junction).
         */
        fun crosses(g0: Int, g1: Int, at: Float, thickness: Float): Boolean {
            val reach = thickness / 2f + CROSS_REACH
            var i = lowerBound(g0 + 1f)
            while (i < list.size && pos[i] <= g1 - 1f) {
                if (g1 - g0 > list[i].thickness + CROSS_SLACK) { i++; continue }
                val s = list[i].segs
                var k = 0
                while (k < s.size) {
                    if (s[k] <= at + reach && s[k + 1] >= at - reach) return true
                    k += 2
                }
                i++
            }
            return false
        }

        private fun lowerBound(v: Float): Int {
            var lo = 0
            var hi = pos.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (pos[mid] < v) lo = mid + 1 else hi = mid
            }
            return lo
        }
    }

    /**
     * All the candidate lines of one axis, short ones too, sorted by position: for telling
     * whether a piece of a line of the other axis is a side of a box ([corner]) and whether a
     * box has its opposite side ([covers]).
     */
    private inner class LineIndex(cands: List<Candidate>) {
        private val list = cands.sortedBy { it.pos }
        private val pos = FloatArray(list.size) { list[it].pos }

        private fun lowerBound(v: Float): Int {
            var lo = 0
            var hi = pos.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (pos[mid] < v) lo = mid + 1 else hi = mid
            }
            return lo
        }

        /**
         * Where the corner at [end] (an end of a piece of the line at [at], [thickness] thick,
         * of the other axis) leads: one of these lines lies within the line's thickness inside the
         * piece from [end] ([isStart]: [end] is where the piece starts) and one of its pieces
         * starts or ends on that line, going away from it on one side only (a corner, not a
         * crossing) for at least [minSeg] px. Returns where that piece ends far from the line,
         * or NaN when there is no such corner.
         */
        fun corner(end: Int, at: Float, thickness: Float, isStart: Boolean): Float {
            val t = max(thickness, 1f)
            val from = if (isStart) end - CORNER_SLACK else end - t - CORNER_SLACK
            val to = if (isStart) end + t + CORNER_SLACK else end + CORNER_SLACK
            val reach = t / 2f + CROSS_REACH + CORNER_SLACK
            var i = lowerBound(from)
            while (i < list.size && pos[i] <= to) {
                val s = list[i].segs
                var k = 0
                while (k < s.size) {
                    val below = at - s[k]
                    val above = s[k + 1] - at
                    if (below <= reach && above >= minSeg) return s[k + 1].toFloat()
                    if (above <= reach && below >= minSeg) return s[k].toFloat()
                    k += 2
                }
                i++
            }
            return Float.NaN
        }

        /** Whether one of these lines between [from] and [to] runs along [s0, s1) for at least [part] of it. */
        fun covers(from: Float, to: Float, s0: Int, s1: Int, part: Float): Boolean {
            val need = part * (s1 - s0)
            var i = lowerBound(from)
            while (i < list.size && pos[i] <= to) {
                val s = list[i].segs
                var n = 0
                var k = 0
                while (k < s.size) {
                    n += max(0, min(s[k + 1], s1) - max(s[k], s0))
                    k += 2
                }
                if (n >= need) return true
                i++
            }
            return false
        }
    }

    /**
     * Whether the piece [s0, s1) of the line at [pos] ([thickness] thick) is a side of a box:
     * lines of the other axis ([across]) turn from both its ends to the same side, as far, and
     * a line of this axis ([along]) runs there across most of it (the opposite side). The
     * cells of a Table with a Space between them are such boxes; letters (an "n": no bottom)
     * are not.
     */
    private fun boxSide(s0: Int, s1: Int, pos: Float, thickness: Float, across: LineIndex, along: LineIndex): Boolean {
        val f0 = across.corner(s0, pos, thickness, isStart = true)
        if (f0.isNaN()) return false
        val f1 = across.corner(s1, pos, thickness, isStart = false)
        if (f1.isNaN() || (f0 > pos) != (f1 > pos)) return false
        val t = max(thickness, 1f)
        if (abs(f0 - f1) > t + 2f * CORNER_SLACK) return false
        val far = (f0 + f1) / 2f
        return along.covers(far - t - CORNER_SLACK, far + t + CORNER_SLACK, s0, s1, BOX_OPPOSITE)
    }

    /**
     * Where the pieces of the other axis end ([p]): along a row of letters, their strokes end
     * on its top and bottom every few pixels; a ruled line only meets the lines that cross it.
     */
    private class Ends(p: Pieces) {
        /** (position along the piece) shl 32 or (its boundary), sorted. */
        private val keys: LongArray

        init {
            val n = p.size
            keys = LongArray(n * 2)
            for (k in 0 until n) {
                val b = p.boundary(k).toLong()
                keys[2 * k] = (p.start(k).toLong() shl 32) or b
                keys[2 * k + 1] = (p.end(k).toLong() shl 32) or b
            }
            keys.sort()
        }

        /**
         * How many places (ends closer than [JUNCTION_MERGE] px make one) inside [segs] (not
         * within [JUNCTION_MERGE] px of their ends: crossings and corners) pieces of the other
         * axis end within [reach] of [pos], leaving out where a line of [meeting] ends on it.
         */
        fun junctions(pos: Float, reach: Float, segs: IntArray, meeting: Meeting): Int {
            val lo = ceilInt(pos - reach)
            val hi = floorInt(pos + reach)
            if (hi < lo) return 0
            val xs = ArrayList<Int>()
            var i = lowerBound(lo.toLong() shl 32)
            while (i < keys.size && (keys[i] ushr 32).toInt() <= hi) {
                val x = (keys[i] and 0xFFFFFFFFL).toInt()
                if (insideSegs(segs, x) && !meeting.meets(x, pos, reach)) xs += x
                i++
            }
            if (xs.isEmpty()) return 0
            xs.sort()
            var sites = 1
            for (k in 1 until xs.size) if (xs[k] - xs[k - 1] > JUNCTION_MERGE) sites++
            return sites
        }

        private fun insideSegs(segs: IntArray, x: Int): Boolean {
            var k = 0
            while (k < segs.size) {
                if (x >= segs[k] + JUNCTION_MERGE && x <= segs[k + 1] - JUNCTION_MERGE) return true
                k += 2
            }
            return false
        }

        private fun lowerBound(v: Long): Int {
            var lo = 0
            var hi = keys.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (keys[mid] < v) lo = mid + 1 else hi = mid
            }
            return lo
        }

        private fun ceilInt(v: Float) = kotlin.math.ceil(v).toInt()
        private fun floorInt(v: Float) = kotlin.math.floor(v).toInt()
    }

    /** A line of one axis that passed [collect]: at [pos], over [segs], [thickness] thick. */
    private class Found(val pos: Float, val segs: IntArray, val thickness: Float) {
        val length: Int = Segs.length(segs)
    }

    /** The [Found] lines of one axis, for telling where they meet a line of the other axis. */
    private class Meeting(found: List<Found>) {
        private val list = found.sortedBy { it.pos }
        private val pos = FloatArray(list.size) { list[it].pos }

        /**
         * Whether one of these lines covers [x] (across it) and one of its pieces reaches [at]
         * within [reach]: it crosses or ends on the line at [at] there.
         */
        fun meets(x: Int, at: Float, reach: Float): Boolean {
            var lo = 0
            var hi = pos.size
            val from = x - MEETING_SPAN
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (pos[mid] < from) lo = mid + 1 else hi = mid
            }
            var i = lo
            while (i < list.size && pos[i] <= x + MEETING_SPAN) {
                val l = list[i]
                if (abs(l.pos - x) <= l.thickness / 2f + JUNCTION_MERGE) {
                    val s = l.segs
                    var k = 0
                    while (k < s.size) {
                        if (s[k] <= at + reach + 1f && s[k + 1] >= at - reach - 1f) return true
                        k += 2
                    }
                }
                i++
            }
            return false
        }
    }

    /**
     * Collinear candidates join; chains of pieces across short gaps stay when they are long and
     * dense enough (gaps where [crossings] cross them count as covered), other pieces only when
     * long by themselves or when they are sides of boxes in a row ([boxSide] with the lines
     * [across] them and [along] them: all the candidates of the other axis and of this one).
     */
    private fun collect(cands: List<Candidate>, crossings: Crossings, across: LineIndex, along: LineIndex): List<Found> {
        if (cands.isEmpty()) return emptyList()
        val sorted = cands.sortedBy { it.pos }
        val lines = ArrayList<Found>()
        var i = 0
        while (i < sorted.size) {
            var j = i + 1
            while (j < sorted.size && sorted[j].pos - sorted[j - 1].pos <= COLLINEAR && sorted[j].pos - sorted[i].pos <= 2f * COLLINEAR) j++
            var segs = IntArray(0)
            var posSum = 0.0
            var wSum = 0.0
            var thick = 0f
            for (k in i until j) {
                segs = Segs.union(segs, sorted[k].segs)
                val l = Segs.length(sorted[k].segs).toDouble()
                posSum += sorted[k].pos * l
                wSum += l
                thick = max(thick, sorted[k].thickness)
            }
            val pos = if (wSum > 0.0) (posSum / wSum).toFloat() else sorted[i].pos
            val kept = accepted(segs, pos, thick, crossings, across, along)
            if (kept != null) lines += Found(pos, kept, thick)
            i = j
        }
        return lines
    }

    /**
     * The lines of [found] on [axis], without those that pieces of the other axis end on all
     * along ([ends]: text; where a line of [meeting] ends on them does not count); longest first,
     * at most [LineDetector.MAX_LINES_PER_AXIS].
     */
    private fun finish(found: List<Found>, axis: SnapAxis, ends: Ends, meeting: Meeting): List<DetectedLine> {
        val lines = found.filter { !endedOnAllAlong(it, ends, meeting) }
        val top = if (lines.size > LineDetector.MAX_LINES_PER_AXIS) {
            lines.sortedByDescending { it.length }.take(LineDetector.MAX_LINES_PER_AXIS)
        } else lines
        return top.sortedBy { it.pos }.map { DetectedLine(axis, it.pos, it.segs[0].toFloat(), it.segs[it.segs.size - 1].toFloat(), it.thickness) }
    }

    /**
     * Whether pieces of the other axis end on [line] at more than [MAX_JUNCTIONS] places (not
     * counting where lines of [meeting] meet it), closer together than [JUNCTION_SPACING] px on
     * average: the top or bottom of a row of letters, not a line.
     */
    private fun endedOnAllAlong(line: Found, ends: Ends, meeting: Meeting): Boolean {
        val n = ends.junctions(line.pos, line.thickness / 2f + CROSS_REACH, line.segs, meeting)
        return n > MAX_JUNCTIONS && n * JUNCTION_SPACING > line.length
    }

    /**
     * The parts of [segs] (sorted, disjoint; of a line at [pos], [thickness] thick) that make a line, or null when
     * none: chains across gaps up to [MAX_GAP] that are [minLength] long and covered for at least
     * [CHAIN_COVERAGE] of their extent (gaps where [crossings] cross count as covered: the cells
     * of a table), else single pieces [minLength] long; plus the sides of boxes in a row
     * ([boxSide], at least [MIN_BOX_SIDES] of them).
     */
    private fun accepted(segs: IntArray, pos: Float, thickness: Float, crossings: Crossings, across: LineIndex, along: LineIndex): IntArray? {
        val n = segs.size / 2
        val keep = BooleanArray(n)
        var i = 0
        while (i < n) {
            var j = i + 1
            var covered = segs[2 * i + 1] - segs[2 * i]
            while (j < n && segs[2 * j] - segs[2 * j - 1] <= MAX_GAP) {
                val g0 = segs[2 * j - 1]
                val g1 = segs[2 * j]
                if (crossings.crosses(g0, g1, pos, thickness)) covered += g1 - g0
                covered += segs[2 * j + 1] - segs[2 * j]
                j++
            }
            val span = segs[2 * j - 1] - segs[2 * i]
            if (covered >= minLength && covered >= CHAIN_COVERAGE * span) {
                for (k in i until j) keep[k] = true
            } else {
                for (k in i until j) if (segs[2 * k + 1] - segs[2 * k] >= minLength) keep[k] = true
            }
            i = j
        }
        // Sides of boxes side by side (the cells of a Table with a Space between them, smaller
        // than [minLength]) when there are several of them, [minLength] long together, however
        // far apart.
        var boxed = 0
        var boxedLength = 0
        val side = BooleanArray(n)
        for (k in 0 until n) {
            if (keep[k]) continue
            val s0 = segs[2 * k]
            val s1 = segs[2 * k + 1]
            if (s1 - s0 < max(MIN_BOX_SIDE * minLength, 2f * thickness)) continue
            if (boxSide(s0, s1, pos, thickness, across, along)) {
                side[k] = true
                boxed++
                boxedLength += s1 - s0
            }
        }
        if (boxed >= MIN_BOX_SIDES && boxedLength >= minLength) for (k in 0 until n) if (side[k]) keep[k] = true
        val out = ArrayList<Int>()
        for (k in 0 until n) if (keep[k]) { out += segs[2 * k]; out += segs[2 * k + 1] }
        return if (out.isEmpty()) null else out.toIntArray()
    }

    // ------------------------------------------------------------------ helpers

    /** Sorted, disjoint [start, end) intervals packed as [s0, e0, s1, e1...]. */
    private object Segs {
        fun length(s: IntArray): Int {
            var n = 0
            var i = 0
            while (i < s.size) { n += s[i + 1] - s[i]; i += 2 }
            return n
        }

        fun union(a: IntArray, b: IntArray): IntArray {
            if (a.isEmpty()) return b
            if (b.isEmpty()) return a
            val out = IntArray(a.size + b.size)
            var n = 0
            var i = 0
            var j = 0
            while (i < a.size || j < b.size) {
                val takeA = j >= b.size || (i < a.size && a[i] <= b[j])
                val s: Int
                val e: Int
                if (takeA) { s = a[i]; e = a[i + 1]; i += 2 } else { s = b[j]; e = b[j + 1]; j += 2 }
                if (n > 0 && s <= out[n - 1]) {
                    if (e > out[n - 1]) out[n - 1] = e
                } else {
                    out[n++] = s
                    out[n++] = e
                }
            }
            return if (n == out.size) out else out.copyOf(n)
        }

        fun intersection(a: IntArray, b: IntArray): Int {
            var n = 0
            var i = 0
            var j = 0
            while (i < a.size && j < b.size) {
                val lo = max(a[i], b[j])
                val hi = min(a[i + 1], b[j + 1])
                if (hi > lo) n += hi - lo
                if (a[i + 1] < b[j + 1]) i += 2 else j += 2
            }
            return n
        }
    }

    companion object {
        const val BORDER_TOP = 1
        const val BORDER_BOTTOM = 2
        const val BORDER_LEFT = 4
        const val BORDER_RIGHT = 8
        const val ALL_BORDERS = 15

        /** Pixels read per strip. */
        private const val STRIP_PIXELS = 1 shl 16

        /** Neighbouring pixels differing this much (premultiplied, any channel, 0..255) are an edge. */
        private const val EDGE = 20

        /** Edge pixels this many apart (anti-aliasing dropouts) still make one run. */
        private const val GAP = 2


        /** Shortest run of edge pixels considered (px), whatever the image size. */
        private const val MIN_SEG_FLOOR = 8

        /** An edge spread over more boundaries than this is texture or blur, not a line. */
        private const val MAX_CORE = 4

        /** Thickest band paired into one line (px): Table lines are up to 100 px. */
        const val MAX_BAND = 128f

        /** Thinnest band (two separate edges). */
        private const val MIN_BAND = 0.75f

        /** Bands thicker than this also give their two sides. */
        const val THICK_SIDES = 12f

        /** Pieces of one line may be this far apart (where other lines cross it). */
        private const val MAX_GAP = 128

        /** A chain of pieces must cover this much of its extent (crossed gaps count). */
        private const val CHAIN_COVERAGE = 0.75f

        /** Sides of boxes in a row make a line when there are at least this many. */
        private const val MIN_BOX_SIDES = 2

        /** Shortest box side counted, as a fraction of the shortest line. */
        private const val MIN_BOX_SIDE = 0.5f

        /** The opposite side of a box runs along at least this much of the side. */
        private const val BOX_OPPOSITE = 0.6f

        /** A corner's line may lie this much (px) beyond the end of a box side. */
        private const val CORNER_SLACK = 2f

        /** A crossing line's pieces stop this close (px) to the sides of the line they cross. */
        private const val CROSS_REACH = 2f

        /** The gap a crossing line cuts is at most this much (px) wider than the line. */
        private const val CROSS_SLACK = 4f

        /** Ends of pieces this close (px) are one junction (the anti-aliased sides of one stroke). */
        private const val JUNCTION_MERGE = 3

        /** A line may have this many junctions inside it whatever its length (crossing lines). */
        private const val MAX_JUNCTIONS = 3

        /** More junctions than one per this many px along a line: text, not a line. */
        private const val JUNCTION_SPACING = 12

        /** Lines are looked for this far (px) around a junction (half the thickest band, and some). */
        private const val MEETING_SPAN = 70

        /** Mean colors this close (any channel) are the same color. */
        private const val SIMILAR = 40

        /** Between two edges, at least this alpha is something (not a transparent gap). */
        private const val MIN_INSIDE_ALPHA = 24

        /** A step's color change compared with its pixels' difference (else a thin line). */
        private const val STEP_RATIO = 0.35

        /** Each boundary of an edge: mean colors differing at least this much of its pixels' difference. */
        private const val CONSISTENCY = 0.5


        /** Positions this close (px) are one line. */
        private const val COLLINEAR = 0.5f

        /** Pairing candidates kept per step. */
        private const val MAX_PARTNERS = 64

        /** Steps looked at after each step for pairing (busy images have thousands nearby). */
        private const val MAX_SCANNED = 512

        /** Pieces kept per axis (bounded memory on pathological images). */
        private const val MAX_PIECES = 1 shl 18

        private const val INDEX_MASK = (1L shl 21) - 1

        /** Background estimate: 3 bits per channel. */
        private const val HIST_SIZE = 1 shl 12

        /** Premultiplied ARGB of a non-premultiplied ARGB color. */
        fun premultiply(c: Int): Int {
            val a = c ushr 24
            if (a == 255) return c
            if (a == 0) return 0
            val r = (((c shr 16) and 255) * a + 127) / 255
            val g = (((c shr 8) and 255) * a + 127) / 255
            val b = ((c and 255) * a + 127) / 255
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        /** Largest channel difference of two packed colors. */
        fun diff(p: Int, q: Int): Int {
            if (p == q) return 0
            val da = abs((p ushr 24) - (q ushr 24))
            val dr = abs(((p shr 16) and 255) - ((q shr 16) and 255))
            val dg = abs(((p shr 8) and 255) - ((q shr 8) and 255))
            val db = abs((p and 255) - (q and 255))
            return max(max(da, dr), max(dg, db))
        }

        private fun pack(a: Int, r: Int, g: Int, b: Int): Int =
            (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

        private fun mean(a: Int, r: Int, g: Int, b: Int, n: Int): Int =
            if (n <= 0) 0 else pack(a / n, r / n, g / n, b / n)

        private fun sample(row: IntArray, hist: IntArray) {
            var x = 0
            while (x < row.size) {
                val c = row[x]
                hist[((c ushr 29) shl 9) or (((c shr 21) and 7) shl 6) or (((c shr 13) and 7) shl 3) or ((c shr 5) and 7)]++
                x += 8
            }
        }

        /**
         * The background: the color (premultiplied, bucket center) of at least two thirds of the
         * samples, e.g. transparent or the white of a page; null when no color clearly dominates
         * (a photo, a gradient). Not just a half: thick lines over a photo (a Table with 40 px
         * lines and 150 px cells) can cover half of it, and taking their color for the page's
         * would pair the cells between them as lines.
         */
        private fun background(hist: IntArray): Int? {
            var best = 0
            var total = 0L
            for (k in hist.indices) {
                total += hist[k]
                if (hist[k] > hist[best]) best = k
            }
            if (total == 0L || hist[best] * 3L < total * 2L) return null
            if (best == 0) return 0
            fun ch(q: Int) = (q shl 5) + 16
            return pack(ch(best shr 9), ch((best shr 6) and 7), ch((best shr 3) and 7), ch(best and 7))
        }
    }
}
