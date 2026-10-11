package com.brushwork.paint.tools.select.magnetic

import kotlin.math.max
import kotlin.math.min

/**
 * The live wire (v1.8, design §3.4 c): the cheapest 8-connected path between two proxy pixels of
 * an [EdgeMap], by Dijkstra with Dial's bucket queue. A step to pixel q costs (10 straight | 14
 * diagonal) × (1 + ((255 − g(q)) shr 3)), where g below the contrast floor counts as 0: a flat
 * region costs the same everywhere (the path runs straight there), an edge is up to 32 times
 * cheaper to follow.
 *
 * The search keeps to the box of the two ends plus [MARGIN] proxy px. A window over [MAX_NODES]
 * runs on the map's half-resolution copy and the path is scaled back up.
 *
 * One instance per worker: its arrays are sized to the largest window searched and reused, so a
 * drag allocates nothing. Not thread safe.
 */
class LiveWire {
    private var dist = IntArray(0)
    /** Per node: 0 unseen, 1..8 reached from direction (value − 1), +16 once settled. */
    private var from = ByteArray(0)
    private val head = IntArray(BUCKETS)
    private var poolNode = IntArray(1024)
    private var poolNext = IntArray(1024)
    private var poolFree = -1
    private var poolUsed = 0

    /** Nodes in the last search's window (for tests and budgets). */
    var lastNodes = 0
        private set

    /** True when the last search ran at half resolution. */
    var lastHalf = false
        private set

    /** The bytes the search's buffers hold now (they grow to the largest window searched, [MAX_NODES] at most). */
    internal val bytes: Long
        get() = dist.size * 4L + from.size + (poolNode.size + poolNext.size + head.size) * 4L

    /**
     * The path from ([ax], [ay]) to ([bx], [by]) (proxy px of [map], clamped into it) as x, y
     * pairs, both ends included, straight runs reduced to their ends. [floor] 0..255: the
     * contrast floor. Null when [cancelled].
     */
    fun path(map: EdgeMap, ax: Int, ay: Int, bx: Int, by: Int, floor: Int, cancelled: () -> Boolean = { false }): IntArray? {
        val x0 = ax.coerceIn(0, map.width - 1); val y0 = ay.coerceIn(0, map.height - 1)
        val x1 = bx.coerceIn(0, map.width - 1); val y1 = by.coerceIn(0, map.height - 1)
        val ww = min(map.width - 1, max(x0, x1) + MARGIN) - max(0, min(x0, x1) - MARGIN) + 1
        val wh = min(map.height - 1, max(y0, y1) + MARGIN) - max(0, min(y0, y1) - MARGIN) + 1
        if (ww.toLong() * wh <= MAX_NODES) {
            lastHalf = false
            return search(map, x0, y0, x1, y1, floor, cancelled)
        }
        val half = map.half
        val sx = half.width.toFloat() / map.width
        val sy = half.height.toFloat() / map.height
        fun hx(x: Int) = (x * sx).toInt().coerceIn(0, half.width - 1)
        fun hy(y: Int) = (y * sy).toInt().coerceIn(0, half.height - 1)
        val p = search(half, hx(x0), hy(y0), hx(x1), hy(y1), floor, cancelled) ?: return null
        lastHalf = true
        // Back to full resolution: each point at its pixel's centre, the exact ends kept.
        val out = IntArray(p.size)
        for (i in p.indices step 2) {
            out[i] = ((p[i] + 0.5f) / sx).toInt().coerceIn(0, map.width - 1)
            out[i + 1] = ((p[i + 1] + 0.5f) / sy).toInt().coerceIn(0, map.height - 1)
        }
        out[0] = x0; out[1] = y0
        out[out.size - 2] = x1; out[out.size - 1] = y1
        return out
    }

    private fun search(map: EdgeMap, ax: Int, ay: Int, bx: Int, by: Int, floor: Int, cancelled: () -> Boolean): IntArray? {
        if (ax == bx && ay == by) return intArrayOf(ax, ay)
        val left = max(0, min(ax, bx) - MARGIN); val top = max(0, min(ay, by) - MARGIN)
        val right = min(map.width - 1, max(ax, bx) + MARGIN); val bottom = min(map.height - 1, max(ay, by) + MARGIN)
        val ww = right - left + 1
        val wh = bottom - top + 1
        val n = ww * wh
        lastNodes = n
        if (dist.size < n) {
            dist = IntArray(n)
            from = ByteArray(n)
        }
        java.util.Arrays.fill(dist, 0, n, Int.MAX_VALUE)
        java.util.Arrays.fill(from, 0, n, 0)
        head.fill(-1)
        poolFree = -1
        poolUsed = 0

        val start = (ay - top) * ww + (ax - left)
        val target = (by - top) * ww + (bx - left)
        dist[start] = 0
        push(start, 0)
        var live = 1
        var d = 0
        val grad = map.grad
        val mw = map.width
        var steps = 0
        while (live > 0) {
            val b = d % BUCKETS
            val e = head[b]
            if (e < 0) { d++; continue }
            head[b] = poolNext[e]
            val node = poolNode[e]
            poolNext[e] = poolFree; poolFree = e
            live--
            if (from[node].toInt() and SETTLED != 0 || dist[node] != d) continue
            from[node] = (from[node].toInt() or SETTLED).toByte()
            if (node == target) break
            if (++steps and 0x3FFF == 0 && cancelled()) return null
            val nx = node % ww
            val ny = node / ww
            for (k in 0 until 8) {
                val qx = nx + DX[k]; val qy = ny + DY[k]
                if (qx < 0 || qy < 0 || qx >= ww || qy >= wh) continue
                val q = qy * ww + qx
                if (from[q].toInt() and SETTLED != 0) continue
                var g = grad[(qy + top) * mw + qx + left].toInt() and 0xFF
                if (g < floor) g = 0
                val nd = d + STEP[k] * (1 + ((255 - g) shr 3))
                if (nd < dist[q]) {
                    dist[q] = nd
                    from[q] = (k + 1).toByte()
                    push(q, nd % BUCKETS)
                    live++
                }
            }
        }
        if (from[target].toInt() and SETTLED == 0) return null
        // Back from the target; keep only the points where the direction turns.
        val rev = ArrayList<Int>()
        var node = target
        var lastDir = -1
        while (node != start) {
            val k = (from[node].toInt() and 0x0F) - 1
            if (k != lastDir) { rev += node; lastDir = k }
            node -= DY[k] * ww + DX[k]
        }
        rev += start
        val out = IntArray(rev.size * 2)
        for (i in rev.indices) {
            val v = rev[rev.size - 1 - i]
            out[2 * i] = v % ww + left
            out[2 * i + 1] = v / ww + top
        }
        return out
    }

    private fun push(node: Int, bucket: Int) {
        val e: Int
        if (poolFree >= 0) {
            e = poolFree
            poolFree = poolNext[e]
        } else {
            if (poolUsed == poolNode.size) {
                poolNode = poolNode.copyOf(poolUsed * 2)
                poolNext = poolNext.copyOf(poolUsed * 2)
            }
            e = poolUsed++
        }
        poolNode[e] = node
        poolNext[e] = head[bucket]
        head[bucket] = e
    }

    companion object {
        /** Proxy px around the box of the two ends that the search may use. */
        const val MARGIN = 48

        /** Above this many nodes the search runs at half resolution. */
        const val MAX_NODES = 1 shl 20

        /** The dearest step (14 × 32) plus one: Dial's circular bucket count. */
        private const val BUCKETS = 14 * 32 + 1

        private const val SETTLED = 16
        private val DX = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1)
        private val DY = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)
        private val STEP = intArrayOf(10, 10, 10, 10, 14, 14, 14, 14)
    }
}
