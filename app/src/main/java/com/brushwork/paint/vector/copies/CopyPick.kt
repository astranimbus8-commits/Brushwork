package com.brushwork.paint.vector.copies

import android.graphics.RectF
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.vector.CopyMaps
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VectorOps

/**
 * v1.10 (R1, design 1 R1 (c)): which side (copy k) of a mirrored object a tap is on, and copy k on
 * its own. Copy k of an object is its source drawn under C_k ([ObjectCopies]); k = 0 is the source.
 *
 * - [hitCopy]: the top-most copy hit at a point (the highest k whose drawing is within the
 *   tolerance: copies are drawn in the order of their maps, so the last one hit is on top). It costs
 *   one 2×3 inverse plus the object's own hit test per copy, as v1.9's [ObjectCopies.hits] did.
 * - [side], [boundsOf]: copy k as a plain object, and everything it paints.
 *
 * An object without copies (and a `VStroke`, whose v1.7 copies are not linked objects: a tap picks
 * it whole, R1) has one entry, k = 0. Pure geometry; thread-safe.
 */
object CopyPick {

    /**
     * The highest k for which [test] (at a point and a tolerance in the source's own space) hits
     * copy k of [copies] at [p] within [tol], or -1 when none does. The point is mapped by C_k⁻¹
     * and the tolerance divided by the map's smallest stretch, so a copy is hit wherever it is
     * drawn within [tol]. Without copies: 0 when [test] hits at [p], else -1. [skip] leaves one
     * copy out (a side that was lifted away: its place shows the hole).
     */
    fun hitCopy(copies: CopyMaps, p: Vec2, tol: Float, skip: Int = -1, test: (Vec2, Float) -> Boolean): Int {
        if (copies.isEmpty) return if (skip != 0 && test(p, tol)) 0 else -1
        val maps = copies.maps
        for (k in maps.indices.reversed()) {
            if (k == skip) continue
            if (k == 0) return if (test(p, tol)) 0 else -1
            val m = maps[k]
            val inv = ObjectCopies.inverse(m) ?: continue
            val q = Vec2(inv[0] * p.x + inv[1] * p.y + inv[2], inv[3] * p.x + inv[4] * p.y + inv[5])
            if (!(q.x.isFinite() && q.y.isFinite())) continue
            val smin = ObjectCopies.stretch(m).first
            val t = if (smin > 0f && tol.isFinite()) tol / smin else tol
            if (test(q, t)) return k
        }
        return -1
    }

    /**
     * The copy of [o] hit at [p] within [tol] (see [hitCopy]; [VectorOps.hitCopy] tests the
     * drawing): -1 when [o] is missed, 0 for an object without copies and for a stroke.
     */
    fun hitCopy(o: VObject, p: Vec2, tol: Float, skip: Int = -1): Int = VectorOps.hitCopy(o, p, tol, skip)

    /**
     * Copy [k] of [o] as a plain object with [o]'s id: the source for k = 0, else the source
     * mapped by C_k ([VectorOps.transformed]: a shape stays a shape under the rulers' isometries).
     * [o] itself without copies and k = 0; null for a k [o] does not have.
     */
    fun side(o: VObject, k: Int): VObject? {
        val copies = ObjectCopies.of(o)
        if (copies.isEmpty) return o.takeIf { k == 0 }
        if (k !in copies.maps.indices) return null
        val src = ObjectCopies.source(o)
        return if (k == 0) src else VectorOps.transformed(src, copies.maps[k])
    }

    /** Everything copy [k] of [o] paints (document px, the stroke's reach included); empty for a k [o] does not have. */
    fun boundsOf(o: VObject, k: Int): RectF = side(o, k)?.let { VectorOps.bounds(it) } ?: RectF()
}
