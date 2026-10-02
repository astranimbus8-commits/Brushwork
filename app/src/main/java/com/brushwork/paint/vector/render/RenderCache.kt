package com.brushwork.paint.vector.render

import android.graphics.Canvas
import com.brushwork.paint.brush.BrushPreset
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.vector.VObject

/**
 * Prepared drawing of vector objects, kept between renders by ONE owner thread (v1.5 A1: "cache
 * per-object geometry"): a path's Android paths and paints (fill, plain outline, varying-width
 * outline), a shape's paint spec, and the samples a brush outline is replayed along. Objects are
 * immutable, so an entry stays valid as long as its object instance lives; entries are keyed by
 * identity and the least recently used ones are dropped beyond [maxEntries] / [maxPoints].
 *
 * Not thread-safe: Android `Path` objects must not be drawn from two threads at once, so every
 * thread that renders (the main thread, the background render worker) owns its own cache.
 */
class RenderCache(private val maxEntries: Int = 512, private val maxPoints: Long = 2_000_000L) {

    /** A brush replay along an outline: what `StrokeRaster.render` gets. */
    internal class BrushReplay(val preset: BrushPreset, val color: Int, val seed: Long, val points: PackedPoints)

    /** What an object draws besides strokes: [plain] (fills, plain lines, shape specs) and its brush replays. */
    internal class Prepared(val plain: ((Canvas) -> Unit)?, val brushes: List<BrushReplay>) {
        val points: Long = brushes.sumOf { it.points.size.toLong() } + 64L
    }

    private class Key(val o: VObject) {
        override fun hashCode(): Int = System.identityHashCode(o)
        override fun equals(other: Any?): Boolean = other is Key && other.o === o
    }

    private val map = LinkedHashMap<Key, Prepared>(64, 0.75f, true)
    private var points = 0L

    /** Hits and misses since creation (tests, tuning). */
    var hits = 0L
        private set
    var misses = 0L
        private set

    internal fun get(o: VObject, build: () -> Prepared): Prepared {
        val k = Key(o)
        map[k]?.let { hits++; return it }
        misses++
        val p = build()
        map[k] = p
        points += p.points
        if (map.size > maxEntries || points > maxPoints) {
            val it = map.entries.iterator()
            while ((map.size > maxEntries || points > maxPoints) && it.hasNext()) {
                val e = it.next()
                if (e.key.o === o) continue
                points -= e.value.points
                it.remove()
            }
        }
        return p
    }

    val size: Int get() = map.size

    fun clear() {
        map.clear()
        points = 0
    }
}
