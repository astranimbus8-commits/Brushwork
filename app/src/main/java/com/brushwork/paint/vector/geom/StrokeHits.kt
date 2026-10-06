package com.brushwork.paint.vector.geom

import com.brushwork.paint.brush.StrokeRaster
import com.brushwork.paint.vector.StrokeCopies
import com.brushwork.paint.vector.VStroke
import kotlin.math.hypot

/**
 * Exact hit geometry of freehand strokes (v1.5 A1, "exact stroke hit-testing with dab radii"):
 * the stroke's replayed dabs ([StrokeRaster.dabCircles]: the same sampler, pressure, tapers and
 * scatter as the pixels) as a chain of capsules whose radius blends from one dab to the next. A
 * tap beside a tapered tip misses it; a tap on a pressure swell hits.
 *
 * The dab chains of recently tested strokes are kept (by object identity; objects are immutable)
 * in a small bounded cache, so repeated taps and eraser moves don't replay the stroke again.
 * Thread-safe.
 */
object StrokeHits {
    /** Strokes whose dabs are kept. */
    private const val KEPT = 64

    /** Most floats kept over all strokes (3 per dab). */
    private const val MAX_FLOATS = 3_000_000L

    private class Key(val o: VStroke) {
        override fun hashCode(): Int = System.identityHashCode(o)
        override fun equals(other: Any?): Boolean = other is Key && other.o === o
    }

    private val cache = object : LinkedHashMap<Key, FloatArray>(KEPT * 2, 0.75f, true) {}
    private var floats = 0L

    /** (x, y, radius) triples of [s]'s dabs in stamping order (cached). */
    fun dabs(s: VStroke): FloatArray {
        val key = Key(s)
        synchronized(cache) { cache[key]?.let { return it } }
        val d = StrokeRaster.dabCircles(s.preset, s.sizeScale, s.stylus, s.seed, s.points, s.taperIn, s.taperOut)
        synchronized(cache) {
            cache[key]?.let { return it }
            cache[key] = d
            floats += d.size
            val it = cache.entries.iterator()
            while ((cache.size > KEPT || floats > MAX_FLOATS) && it.hasNext()) {
                val e = it.next()
                if (e.key.o === s) continue
                floats -= e.value.size
                it.remove()
            }
        }
        return d
    }

    /**
     * Distance from ([x], [y]) to the painted area of [s] (≤ 0 inside): to the nearest capsule
     * between consecutive dabs (radius blended along it), or to the only dab.
     * [Float.POSITIVE_INFINITY] without dabs. v1.7: the nearest over the stroke's symmetry
     * copies ([StrokeCopies.dabChains]).
     */
    fun distance(s: VStroke, x: Float, y: Float): Float {
        val d = dabs(s)
        if (s.copies.isEmpty()) return distance(d, x, y)
        var best = Float.POSITIVE_INFINITY
        for (m in s.copies) best = minOf(best, distance(StrokeCopies.mappedDabs(d, m), x, y))
        return best
    }

    /** [distance] over a dab chain (x, y, r triples). */
    fun distance(d: FloatArray, x: Float, y: Float): Float {
        val n = d.size / 3
        if (n == 0) return Float.POSITIVE_INFINITY
        if (n == 1) return hypot(x - d[0], y - d[1]) - d[2]
        var best = Float.POSITIVE_INFINITY
        for (i in 1 until n) {
            val ax = d[3 * i - 3]; val ay = d[3 * i - 2]; val ar = d[3 * i - 1]
            val bx = d[3 * i]; val by = d[3 * i + 1]; val br = d[3 * i + 2]
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            var t = if (len2 > 0f) ((x - ax) * dx + (y - ay) * dy) / len2 else 0f
            if (t < 0f) t = 0f else if (t > 1f) t = 1f
            val px = ax + dx * t; val py = ay + dy * t
            val dist = hypot(x - px, y - py) - (ar + (br - ar) * t)
            if (dist < best) best = dist
        }
        return best
    }

    /** True when ([x], [y]) lies within [tol] of what [s] paints. */
    fun hits(s: VStroke, x: Float, y: Float, tol: Float): Boolean = distance(s, x, y) <= tol

    /** Forgets every cached chain. */
    fun clear() = synchronized(cache) { cache.clear(); floats = 0 }
}
