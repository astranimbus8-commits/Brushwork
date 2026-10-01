package com.brushwork.paint.vector.geom

import android.graphics.RectF
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorOps
import java.util.IdentityHashMap
import kotlin.math.floor
import kotlin.math.max

/**
 * Spatial index of one [VectorContent] (v1.5 §4.9c, A1): every object's paint bounds
 * ([VectorOps.bounds]) and a grid of [CELL] px document squares listing the objects whose bounds
 * reach each square, so hit tests, dirty regions, cost estimates and renders look only at the
 * objects near a point or region (2000 objects: a hit test is a few cell lookups).
 *
 * Immutable and thread-safe once built. [of] keeps the indices of the last contents it was asked
 * for (by identity): an edit replaces the content but shares every unchanged object instance, so
 * a new index takes those objects' bounds from the previous one instead of measuring them again.
 *
 * Objects whose bounds cover more than [MAX_CELLS] squares (or are not finite) are kept in one
 * list that every query checks, so a huge object never fills thousands of cells.
 */
class ObjectIndex private constructor(
    val content: VectorContent,
    private val l: FloatArray,
    private val t: FloatArray,
    private val r: FloatArray,
    private val b: FloatArray,
    private val cells: HashMap<Long, IntArray>,
    private val big: IntArray,
    private val identity: IdentityHashMap<VObject, Int>,
) {
    private val ids: HashMap<Long, Int> by lazy {
        val m = HashMap<Long, Int>(content.objects.size * 2)
        content.objects.forEachIndexed { i, o -> if (o.id !in m) m[o.id] = i }
        m
    }

    /** Number of objects. */
    val size: Int get() = l.size

    /** Paint bounds of the object at z-index [i] (a new RectF; empty when it paints nothing). */
    fun bounds(i: Int): RectF = if (isEmpty(i)) RectF() else RectF(l[i], t[i], r[i], b[i])

    /** Paint bounds of [o] when it is one of this content's objects (by identity), else measured. */
    fun boundsOf(o: VObject): RectF = identity[o]?.let { bounds(it) } ?: VectorOps.bounds(o)

    /** Z-index of the object with [id], or -1. */
    fun indexOfId(id: Long): Int = ids[id] ?: -1

    private fun isEmpty(i: Int): Boolean = !(l[i] < r[i] && t[i] < b[i])

    /** True when the bounds of object [i] intersect the rect (strictly, as RectF.intersects). */
    fun intersects(i: Int, left: Float, top: Float, right: Float, bottom: Float): Boolean =
        !isEmpty(i) && l[i] < right && left < r[i] && t[i] < bottom && top < b[i]

    /** Union of every object's bounds (empty without objects). */
    fun unionBounds(): RectF {
        val out = RectF()
        for (i in 0 until size) if (!isEmpty(i)) out.union(l[i], t[i], r[i], b[i])
        return out
    }

    /**
     * Z-indices (ascending: bottom first) of the objects whose bounds intersect the rect
     * ([left], [top], [right], [bottom], document px).
     */
    fun query(left: Float, top: Float, right: Float, bottom: Float): IntArray {
        val n = size
        if (n == 0 || !(left < right && top < bottom)) return EMPTY_INTS
        val c0 = cellOf(left); val c1 = cellOf(right)
        val r0 = cellOf(top); val r1 = cellOf(bottom)
        val span = (c1.toLong() - c0 + 1) * (r1.toLong() - r0 + 1)
        val hit = BooleanArray(n)
        var count = 0
        fun take(i: Int) {
            if (!hit[i] && intersects(i, left, top, right, bottom)) { hit[i] = true; count++ }
        }
        if (span > max(64L, n.toLong())) {
            // A query wider than the content: one pass over every object is cheaper.
            for (i in 0 until n) take(i)
        } else {
            for (row in r0..r1) for (col in c0..c1) cells[key(col, row)]?.let { list -> for (i in list) take(i) }
            for (i in big) take(i)
        }
        if (count == 0) return EMPTY_INTS
        val out = IntArray(count)
        var k = 0
        for (i in 0 until n) if (hit[i]) out[k++] = i
        return out
    }

    fun query(rect: RectF): IntArray = query(rect.left, rect.top, rect.right, rect.bottom)

    /** Z-indices of the objects whose bounds come within [tol] of ([x], [y]), bottom first. */
    fun queryPoint(x: Float, y: Float, tol: Float): IntArray {
        val e = if (tol.isFinite()) max(0f, tol) else 0f
        // Closed at the point: a zero-size query box still finds bounds that contain the point.
        return query(x - e - 1e-3f, y - e - 1e-3f, x + e + 1e-3f, y + e + 1e-3f)
    }

    companion object {
        /** Side of the grid squares (document px). */
        const val CELL = 128

        /** Objects covering more squares than this are checked by every query instead. */
        private const val MAX_CELLS = 256

        /** Indices kept for reuse ([of]). */
        private const val KEPT = 12

        private val EMPTY_INTS = IntArray(0)

        private val recent = ArrayDeque<ObjectIndex>()

        private fun cellOf(v: Float): Int {
            val c = floor(v / CELL)
            return if (c.isNaN()) 0 else c.coerceIn(-1e9f, 1e9f).toInt()
        }

        private fun key(col: Int, row: Int): Long = (col.toLong() shl 32) or (row.toLong() and 0xFFFFFFFFL)

        /**
         * The index of [content] (the same instance is indexed once; the last [KEPT] are kept).
         * Thread-safe; building happens outside the lock.
         */
        fun of(content: VectorContent): ObjectIndex {
            val donors: List<ObjectIndex>
            synchronized(recent) {
                val hit = recent.firstOrNull { it.content === content }
                if (hit != null) {
                    if (recent.first() !== hit) { recent.remove(hit); recent.addFirst(hit) }
                    return hit
                }
                donors = recent.toList()
            }
            val built = build(content, donors)
            synchronized(recent) {
                recent.firstOrNull { it.content === content }?.let { return it }
                recent.addFirst(built)
                while (recent.size > KEPT) recent.removeLast()
            }
            return built
        }

        /** Forgets every kept index (tests, memory pressure). */
        fun clearCache() = synchronized(recent) { recent.clear() }

        private fun build(content: VectorContent, donors: List<ObjectIndex>): ObjectIndex {
            val objs = content.objects
            val n = objs.size
            val l = FloatArray(n); val t = FloatArray(n); val r = FloatArray(n); val b = FloatArray(n)
            val identity = IdentityHashMap<VObject, Int>(n * 2)
            val lists = HashMap<Long, IntList>()
            val big = IntList()
            for (i in 0 until n) {
                val o = objs[i]
                identity[o] = i
                var found = false
                for (d in donors) {
                    val j = d.identity[o] ?: continue
                    l[i] = d.l[j]; t[i] = d.t[j]; r[i] = d.r[j]; b[i] = d.b[j]
                    found = true
                    break
                }
                if (!found) {
                    val bb = VectorOps.bounds(o)
                    l[i] = bb.left; t[i] = bb.top; r[i] = bb.right; b[i] = bb.bottom
                }
                if (!(l[i] < r[i] && t[i] < b[i])) continue
                if (!(l[i].isFinite() && t[i].isFinite() && r[i].isFinite() && b[i].isFinite())) { big.add(i); continue }
                val c0 = cellOf(l[i]); val c1 = cellOf(r[i])
                val r0 = cellOf(t[i]); val r1 = cellOf(b[i])
                val span = (c1.toLong() - c0 + 1) * (r1.toLong() - r0 + 1)
                if (span > MAX_CELLS) { big.add(i); continue }
                for (row in r0..r1) for (col in c0..c1) lists.getOrPut(key(col, row)) { IntList() }.add(i)
            }
            val cells = HashMap<Long, IntArray>(lists.size * 2)
            for ((k, v) in lists) cells[k] = v.toArray()
            return ObjectIndex(content, l, t, r, b, cells, big.toArray(), identity)
        }
    }

    /** A growable int list (no boxing). */
    private class IntList {
        private var a = IntArray(4)
        private var n = 0
        fun add(v: Int) {
            if (n == a.size) a = a.copyOf(a.size * 2)
            a[n++] = v
        }
        fun toArray(): IntArray = a.copyOf(n)
    }

    /** Union of the bounds of the objects [indices] (empty when none paints anything). */
    fun unionOf(indices: IntArray): RectF {
        val out = RectF()
        for (i in indices) if (!isEmpty(i)) out.union(l[i], t[i], r[i], b[i])
        return out
    }
}
