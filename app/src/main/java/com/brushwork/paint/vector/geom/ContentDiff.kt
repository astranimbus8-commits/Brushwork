package com.brushwork.paint.vector.geom

import android.graphics.RectF
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VectorContent

/**
 * What differs between two versions of a vector layer's content (v1.5 §4.9c, A1), for dirty
 * tile sets, and the three-way merge that re-bases an edit computed from an older content.
 * Objects are compared by id: same instance or equal value = unchanged.
 */
object ContentDiff {

    /**
     * The objects whose areas must be repainted to turn [before]'s rendering into [after]'s:
     * objects only in one of them, both versions of every replaced object, and the objects whose
     * stacking order among the common ones changed (only those outside a longest run that kept
     * its order: moving one object to the back repaints that object's area, not every other's).
     */
    fun changedObjects(before: VectorContent, after: VectorContent): List<VObject> {
        if (before === after) return emptyList()
        val out = ArrayList<VObject>()
        val oldById = HashMap<Long, VObject>(before.objects.size * 2)
        for (o in before.objects) oldById[o.id] = o
        val newById = HashMap<Long, VObject>(after.objects.size * 2)
        for (o in after.objects) newById[o.id] = o
        for (o in before.objects) {
            val n = newById[o.id]
            if (n == null) out += o else if (n !== o && n != o) { out += o; out += n }
        }
        for (o in after.objects) if (o.id !in oldById) out += o
        // Stacking order of the common objects: those off a longest increasing run moved.
        val oldRank = HashMap<Long, Int>(before.objects.size * 2)
        var k = 0
        for (o in before.objects) if (o.id in newById) oldRank[o.id] = k++
        val seq = ArrayList<Long>(oldRank.size)
        for (o in after.objects) if (o.id in oldRank) seq += o.id
        val ranks = IntArray(seq.size) { oldRank.getValue(seq[it]) }
        val keep = longestIncreasing(ranks)
        for (i in seq.indices) {
            if (keep[i]) continue
            val id = seq[i]
            oldById[id]?.let { out += it }
            newById[id]?.let { if (it !== oldById[id]) out += it }
        }
        return out
    }

    /** The tiles of the document grid the [changedObjects] can paint ([index] measures bounds). */
    fun changedTiles(before: VectorContent, after: VectorContent, docW: Int, docH: Int, tile: Int): TileSet {
        val set = TileSet(docW, docH, tile)
        val bi = ObjectIndex.of(before)
        val ai = ObjectIndex.of(after)
        for (o in changedObjects(before, after)) {
            val b = if (ai.indexOfId(o.id).let { it >= 0 && after.objects[it] === o }) ai.boundsOf(o) else bi.boundsOf(o)
            set.addObject(o, b)
        }
        return set
    }

    /** Union of the bounds of the [changedObjects]. */
    fun changedBounds(before: VectorContent, after: VectorContent): RectF {
        val bi = ObjectIndex.of(before)
        val ai = ObjectIndex.of(after)
        val r = RectF()
        for (o in changedObjects(before, after)) {
            val b = if (ai.indexOfId(o.id).let { it >= 0 && after.objects[it] === o }) ai.boundsOf(o) else bi.boundsOf(o)
            if (!b.isEmpty) r.union(b)
        }
        return r
    }

    /** Marks the members of one longest strictly increasing subsequence of [a] (O(n log n)). */
    internal fun longestIncreasing(a: IntArray): BooleanArray {
        val n = a.size
        val keep = BooleanArray(n)
        if (n == 0) return keep
        val tails = IntArray(n)     // index into a of the smallest tail of each length
        val prev = IntArray(n) { -1 }
        var len = 0
        for (i in 0 until n) {
            var lo = 0
            var hi = len
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (a[tails[mid]] < a[i]) lo = mid + 1 else hi = mid
            }
            if (lo > 0) prev[i] = tails[lo - 1]
            tails[lo] = i
            if (lo == len) len++
        }
        var i = tails[len - 1]
        while (i >= 0) { keep[i] = true; i = prev[i] }
        return keep
    }

    /**
     * [ours] (an edit computed from [base]) re-based onto [theirs] (what [base] became meanwhile):
     * - objects [ours] changed take ours' version; objects only [theirs] changed take theirs';
     * - objects deleted on either side stay deleted (a concurrent delete wins over an edit);
     * - objects [ours] added keep their ids unless [theirs] uses them (then they get new ones);
     * - objects [theirs] added stay, right above the object they followed in [theirs];
     * - stacking order follows [ours].
     * Equal to [ours] when [theirs] is [base], and to [theirs] when [ours] is [base].
     */
    fun merge3(base: VectorContent, ours: VectorContent, theirs: VectorContent): VectorContent {
        if (theirs === base || theirs == base) return ours
        if (ours === base || ours == base) return theirs
        val baseById = HashMap<Long, VObject>(base.objects.size * 2)
        for (o in base.objects) baseById[o.id] = o
        val theirsById = HashMap<Long, VObject>(theirs.objects.size * 2)
        for (o in theirs.objects) theirsById[o.id] = o
        val oursIds = HashSet<Long>(ours.objects.size * 2)
        for (o in ours.objects) oursIds += o.id
        var next = maxOf(ours.nextId, theirs.nextId)
        for (o in ours.objects) if (o.id >= next) next = o.id + 1
        for (o in theirs.objects) if (o.id >= next) next = o.id + 1
        val out = ArrayList<VObject>(ours.objects.size + theirs.objects.size)
        val used = HashSet<Long>()
        for (o in ours.objects) {
            val b = baseById[o.id]
            if (b != null) {
                val t = theirsById[o.id] ?: continue // deleted meanwhile
                val oursChanged = o !== b && o != b
                val v = if (oursChanged) o else t
                if (used.add(v.id)) out += v
            } else {
                // Added by ours: keep the id unless theirs (or ours, twice) already has it.
                val v = if (o.id in theirsById || o.id in used) o.withId(next++) else o
                used += v.id
                out += v
            }
        }
        // Theirs' additions, each right above the object it followed in theirs.
        val added = theirs.objects.indices.filter { theirs.objects[it].id !in baseById && theirs.objects[it].id !in oursIds }
        for (i in added) {
            val o = theirs.objects[i]
            if (o.id in used) continue
            var at = 0
            for (j in i - 1 downTo 0) {
                val pid = theirs.objects[j].id
                val pos = out.indexOfFirst { it.id == pid }
                if (pos >= 0) { at = pos + 1; break }
            }
            out.add(at, o)
            used += o.id
        }
        return VectorContent(version = maxOf(ours.version, theirs.version), objects = out, nextId = next)
    }
}
