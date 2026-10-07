package com.brushwork.paint.vector.pathfinder

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.vector.PathOp
import com.brushwork.paint.tools.vector.VectorPath
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VectorOps
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Pathfinder's Outline (v1.7 item 20, §3.20; Illustrator's "divides the artwork into its
 * edges"): Divide's pieces, each piece's contour split at every junction, every resulting
 * stretch an OPEN path with no fill and a plain 1 px stroke in its piece's fill colour. A
 * stretch two pieces share appears once, in the colour of the upper piece (the higher source;
 * the later piece on a tie). A contour without junctions (a piece inside a hole, an island)
 * stays one closed path.
 *
 * Junctions are found on the segments Skia gives (lines and cubics, kept as they are): the
 * pieces' vertices are merged when closer than [SNAP] px, a line is split where another piece's
 * vertex lies on it (a T), and a vertex is a junction where three or more distinct edges meet.
 * Two stretches are the same edge when they pass through the same vertices and their middles
 * are within [SAME_MIDDLE] px. (A curved edge whose two copies Skia computed differently enough
 * stays twice.)
 */
object PathfinderOutline {
    /** Vertices closer than this (px) are one vertex. */
    private const val SNAP = 0.02f

    /** Two stretches over the same vertices are one edge when their middles are this close (px). */
    private const val SAME_MIDDLE = 0.1f

    /** The grid (px) edges are told apart by, at their middle point. */
    private const val EDGE_GRID = 0.1f

    /** Buckets (px) of the vertex lookup for T-junctions. */
    private const val BUCKET = 16f

    private class Stretch(val piece: Int, val source: Int, val segs: List<PathConvert.Seg>, val ids: List<Int>, val loop: Boolean) {
        val key: String = if (loop) "L" + ids.sorted().joinToString(",") else canonical(ids)
        /** A loop has no start: its vertices' centroid stands for it. */
        val middle: Vec2 = if (loop) centroid(segs) else middleOf(segs)
    }

    /** The edges of [pieces] (bottom first by source), styled from [styles] (one per operand). */
    fun edges(pieces: List<PathfinderOps.Piece>, styles: List<PathfinderStyle>): List<VPath> {
        val snapper = Snapper()
        // Every piece's contours, with the vertex id at the start of each segment.
        val contours = pieces.map { p -> PathConvert.contours(p.path).filter { it.closed && it.segs.isNotEmpty() }.map { it.segs.toMutableList() } }
        for (cs in contours) for (segs in cs) for (s in segs) { snapper.id(s.p0); snapper.id(s.p1) }

        splitAtTees(contours, snapper)

        // Degree of every vertex: distinct edges meeting there, over all pieces.
        val edgesAt = HashMap<Int, MutableSet<Long>>()
        for (cs in contours) for (segs in cs) for (s in segs) {
            val a = snapper.id(s.p0); val b = snapper.id(s.p1)
            if (a == b) continue
            val key = edgeKey(a, b, s.at(0.5f))
            edgesAt.getOrPut(a) { HashSet() } += key
            edgesAt.getOrPut(b) { HashSet() } += key
        }
        fun junction(id: Int) = (edgesAt[id]?.size ?: 0) >= 3

        val stretches = ArrayList<Stretch>()
        for ((k, cs) in contours.withIndex()) {
            for (segs in cs) {
                val ids = segs.map { snapper.id(it.p0) }
                val cuts = ids.indices.filter { junction(ids[it]) }
                if (cuts.isEmpty()) {
                    stretches += Stretch(k, pieces[k].source, segs, ids, loop = true)
                    continue
                }
                for ((n, from) in cuts.withIndex()) {
                    val to = if (n + 1 < cuts.size) cuts[n + 1] else cuts[0] + segs.size
                    val part = (from until to).map { segs[it % segs.size] }
                    val partIds = (from..to).map { ids[it % segs.size] }
                    stretches += Stretch(k, pieces[k].source, part, partIds, loop = false)
                }
            }
        }

        // One copy of each edge: the upper piece's.
        val groups = LinkedHashMap<String, MutableList<MutableList<Stretch>>>()
        for (s in stretches) {
            val sameKey = groups.getOrPut(s.key) { ArrayList() }
            val same = sameKey.firstOrNull { it[0].middle.distanceTo(s.middle) <= SAME_MIDDLE }
            if (same != null) same += s else sameKey += mutableListOf(s)
        }
        val kept = groups.values.flatten().map { copies -> copies.maxWith(compareBy<Stretch>({ it.source }, { it.piece })) }
        return kept.sortedWith(compareBy({ it.source }, { it.piece })).mapNotNull { s -> objectOf(s, styles[s.source]) }
    }

    private fun objectOf(s: Stretch, style: PathfinderStyle): VPath? {
        val ops = ArrayList<PathOp>(s.segs.size + 2)
        ops += PathOp.MoveTo(s.segs[0].p0)
        for (g in s.segs) ops += if (g.c1 != null && g.c2 != null) PathOp.CubicTo(g.c1, g.c2, g.p1) else PathOp.LineTo(g.p1)
        if (s.loop) ops += PathOp.Close
        val subpaths = VectorOps.subpathsOf(VectorPath(ops))
        if (subpaths.isEmpty()) return null
        return VPath(0, style.opacity, subpaths, fill = null, stroke = PathfinderStyles.edgeStroke(style))
    }

    /** Splits every line where another piece's vertex lies on it (inside, not at its ends). */
    private fun splitAtTees(contours: List<List<MutableList<PathConvert.Seg>>>, snapper: Snapper) {
        val buckets = HashMap<Long, MutableList<Int>>()
        for (id in 0 until snapper.size) {
            val p = snapper.point(id)
            buckets.getOrPut(bucketKey(floor(p.x / BUCKET).toInt(), floor(p.y / BUCKET).toInt())) { ArrayList() } += id
        }
        for (cs in contours) for (segs in cs) {
            var i = 0
            while (i < segs.size) {
                val s = segs[i]
                if (s.isCubic) { i++; continue }
                val a = snapper.id(s.p0); val b = snapper.id(s.p1)
                val d = s.p1 - s.p0
                val len2 = d.lengthSq
                if (len2 <= SNAP * SNAP) { i++; continue }
                val hits = ArrayList<Pair<Float, Int>>()
                val x0 = floor((minOf(s.p0.x, s.p1.x) - SNAP) / BUCKET).toInt(); val x1 = floor((maxOf(s.p0.x, s.p1.x) + SNAP) / BUCKET).toInt()
                val y0 = floor((minOf(s.p0.y, s.p1.y) - SNAP) / BUCKET).toInt(); val y1 = floor((maxOf(s.p0.y, s.p1.y) + SNAP) / BUCKET).toInt()
                for (bx in x0..x1) for (by in y0..y1) {
                    val ids = buckets[bucketKey(bx, by)] ?: continue
                    for (id in ids) {
                        if (id == a || id == b) continue
                        val p = snapper.point(id)
                        val t = (p - s.p0).dot(d) / len2
                        if (t <= 0f || t >= 1f) continue
                        val q = s.p0 + d * t
                        if (q.distanceTo(p) <= SNAP && p.distanceTo(s.p0) > SNAP && p.distanceTo(s.p1) > SNAP) hits += t to id
                    }
                }
                if (hits.isEmpty()) { i++; continue }
                hits.sortBy { it.first }
                val parts = ArrayList<PathConvert.Seg>(hits.size + 1)
                var from = s.p0
                for ((_, id) in hits) {
                    val p = snapper.point(id)
                    parts += PathConvert.Seg(from, null, null, p)
                    from = p
                }
                parts += PathConvert.Seg(from, null, null, s.p1)
                segs.removeAt(i)
                segs.addAll(i, parts)
                i += parts.size
            }
        }
    }

    /** Merges points closer than [SNAP]: a stable id per vertex. */
    private class Snapper {
        private val grid = HashMap<Long, MutableList<Int>>()
        private val points = ArrayList<Vec2>()
        val size: Int get() = points.size

        fun point(id: Int): Vec2 = points[id]

        fun id(p: Vec2): Int {
            val gx = floor(p.x / SNAP).toInt(); val gy = floor(p.y / SNAP).toInt()
            for (dx in -1..1) for (dy in -1..1) {
                val ids = grid[bucketKey(gx + dx, gy + dy)] ?: continue
                for (id in ids) if (points[id].distanceTo(p) <= SNAP) return id
            }
            points += p
            grid.getOrPut(bucketKey(gx, gy)) { ArrayList() } += points.lastIndex
            return points.lastIndex
        }
    }

    private fun bucketKey(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)

    /** An edge between vertices [a] and [b] told apart from others between them by its middle. */
    private fun edgeKey(a: Int, b: Int, middle: Vec2): Long {
        val lo = minOf(a, b).toLong(); val hi = maxOf(a, b).toLong()
        val mx = (middle.x / EDGE_GRID).roundToInt().toLong(); val my = (middle.y / EDGE_GRID).roundToInt().toLong()
        var h = lo * 1_000_003L + hi
        h = h * 31L + mx
        h = h * 31L + my
        return h
    }

    /** The vertex sequence read in the direction that sorts first, so both copies of an edge agree. */
    private fun canonical(ids: List<Int>): String {
        val rev = ids.asReversed()
        for (i in ids.indices) {
            if (ids[i] != rev[i]) return (if (ids[i] < rev[i]) ids else rev).joinToString(",")
        }
        return ids.joinToString(",")
    }

    /** The point halfway along [segs] by count: the same whichever way they are read. */
    private fun middleOf(segs: List<PathConvert.Seg>): Vec2 =
        if (segs.size % 2 == 1) segs[segs.size / 2].at(0.5f) else segs[segs.size / 2].p0

    private fun centroid(segs: List<PathConvert.Seg>): Vec2 {
        var x = 0.0; var y = 0.0
        for (s in segs) { x += s.p0.x; y += s.p0.y }
        return Vec2((x / segs.size).toFloat(), (y / segs.size).toFloat())
    }
}
