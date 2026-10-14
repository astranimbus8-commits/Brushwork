package com.brushwork.paint.vector.draw

import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.geom.ObjectIndex
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.min

/** The vector eraser's modes (persisted in `AppSettings.vectorEraserMode` by name). */
enum class VectorEraseMode(val label: String, val description: String) {
    /** Every touched object goes. */
    OBJECT("Object", "Erases every object it touches"),
    /**
     * Strokes and open lines are cut where the eraser passes; v1.9 (R11 b): shapes and filled paths
     * are cut by the eraser's region at the lift ([EraseCut]); other closed lines go whole.
     */
    PARTIAL("Partial", "Cuts strokes and lines where it passes; closed shapes and fills go whole"),
    /** The touched piece of a stroke or line goes, up to where it crosses other lines. */
    TO_INTERSECTION("To intersection", "Erases the touched piece of a line up to where it crosses other lines");

    companion object {
        fun parse(name: String?): VectorEraseMode = entries.firstOrNull { it.name == name } ?: OBJECT
    }
}

/**
 * One gesture of the vector eraser over [content] (pure Kotlin): every eraser point grows what
 * will go ([add]), [result] is the content without it. Main thread (it reads nothing shared but
 * [targets], the per-editor cache of object geometry).
 *
 * v1.8 (§3.1): the object eraser erases a path or shape with live copies whole, all copies with it
 * (its target is every copy: [EraseTarget.parts]). The partial and "to intersection" erasers
 * expand the copies first: the session works on each copy as a plain object of its own
 * ([ObjectCopies.expanded]), and [result] replaces an object whose copies were touched by all of
 * its copies' pieces, in the same step. Without copies the session is v1.7's.
 *
 * v1.9 (R11 b, A5): the partial eraser only marks the shapes and filled paths it touches
 * ([cutTargets]); their boolean cut by the swept region ([EraseCut.cutAll], at the lift, in the
 * background) is handed to [result].
 */
internal class EraseSession(
    val content: VectorContent,
    val mode: VectorEraseMode,
    private val targets: TargetCache = TargetCache(),
    /** False: every object is tested for every capsule (v1.8's pass; the reference of the index's tests). */
    private val indexed: Boolean = true,
) {
    /** Per object of [objects] when copies were expanded: the index of the object of [content] it comes from; null otherwise. */
    private val origin: IntArray?
    private val objects: List<VObject>

    init {
        if (mode != VectorEraseMode.OBJECT && content.objects.any { ObjectCopies.hasCopies(it) }) {
            val list = ArrayList<VObject>(content.objects.size + 8)
            val from = ArrayList<Int>(content.objects.size + 8)
            content.objects.forEachIndexed { j, o -> for (piece in ObjectCopies.expanded(o)) { list += piece; from += j } }
            objects = list
            origin = from.toIntArray()
        } else {
            objects = content.objects
            origin = null
        }
    }

    private val n = objects.size

    init {
        targets.trimTo(objects)
    }

    /**
     * v1.9 (A5, design 2.4.4): the objects near each eraser capsule come from [content]'s
     * [ObjectIndex] (usually built already: the layer's renders use it), not from a pass over
     * every object per point.
     */
    private val index: ObjectIndex by lazy(LazyThreadSafetyMode.NONE) { ObjectIndex.of(content) }

    /** When copies were expanded: per object of [content], its first index in [objects] (and [n] last). */
    private val firstOf: IntArray? = origin?.let { from ->
        val out = IntArray(content.objects.size + 1) { n }
        for (i in n - 1 downTo 0) out[from[i]] = i
        // (Every object expands to at least itself: the ranges are contiguous.)
        for (j in content.objects.size - 1 downTo 0) if (out[j] == n) out[j] = out[j + 1]
        out
    }

    /**
     * Calls [block] with the index in [objects] of every object whose box may reach the rect
     * (document px; bottom first, then the objects the index can't place, see
     * [ObjectIndex.unbounded]). The callers test each object's own box next.
     */
    private inline fun near(l: Float, t: Float, r: Float, b: Float, block: (Int) -> Unit) {
        if (!indexed) {
            for (i in 0 until n) block(i)
            return
        }
        val ix = index
        val hits = ix.query(l - QUERY_PAD, t - QUERY_PAD, r + QUERY_PAD, b + QUERY_PAD)
        val starts = firstOf
        for (pass in 0..1) {
            val list = if (pass == 0) hits else ix.unbounded
            for (j in list) {
                if (starts == null) block(j) else for (i in starts[j] until starts[j + 1]) block(i)
            }
        }
    }

    /** Per object: erased whole. */
    private val whole = BooleanArray(n)
    /** Per object: the removed parameters of its centerline (cut kinds). */
    private val removed = arrayOfNulls<Intervals>(n)
    /** Per object ("to intersection"): where its centerline is touched (within the eraser radius). */
    private val touched = arrayOfNulls<Intervals>(n)
    /** Per object ("to intersection"): parameters where it crosses other objects. */
    private val crossings = arrayOfNulls<List<Float>>(n)
    /** Per object: bumped whenever what goes of it changes (the preview rebuilds those). */
    val versions = IntArray(n)

    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var lastR = 0f

    /** Objects (indices) whose doomed parts changed since the last [takeChanged]. */
    private val changed = LinkedHashSet<Int>()

    /** True once something will go. */
    var hasEffect = false
        private set

    /** Per object (partial eraser): a shape or filled path the region cut works on ([EraseCut.cutsByRegion]); null in the other modes. */
    private val byRegion: BooleanArray? = if (mode == VectorEraseMode.PARTIAL) BooleanArray(n) { EraseCut.cutsByRegion(objects[it]) } else null

    /** Per object: [byRegion] and touched (its boolean cut decides what goes, at the lift). */
    private val cut = BooleanArray(n)

    /** The last capsule [add] applied: x0, y0, x1, y1, radius. */
    val capsule = FloatArray(5)

    /** The capsule being applied touched an object of [byRegion]. */
    private var regionHit = false

    /** The last [result] baked an object's live copies into pieces of their own. */
    var bakedCopies = false
        private set

    fun target(i: Int): EraseTarget = targets.of(objects[i])

    fun objectAt(i: Int): VObject = objects[i]

    val size: Int get() = n

    fun isWhole(i: Int): Boolean = whole[i]

    /** The removed parameters of object [i]'s centerline (cut kinds), or null. */
    fun removedOf(i: Int): Intervals? = removed[i]

    /** Indices whose doomed parts changed since the last call. */
    fun takeChanged(): List<Int> {
        if (changed.isEmpty()) return emptyList()
        val out = changed.toList()
        changed.clear()
        return out
    }

    /**
     * The eraser reaches ([x], [y]) with radius [r] (document px): the capsule from the last
     * point (or a disc for the first one) is applied to every object it can reach. True when it
     * touched a shape or filled path of the partial eraser's region cut (the preview takes the
     * capsule, [capsule], out of the layer).
     */
    fun add(x: Float, y: Float, r: Float): Boolean {
        if (!x.isFinite() || !y.isFinite()) return false
        val radius = if (r.isFinite()) max(0.5f, r) else 0.5f
        val cx = if (lastX.isNaN()) x else lastX
        val cy = if (lastY.isNaN()) y else lastY
        val rr = if (lastX.isNaN()) radius else max(radius, lastR)
        lastX = x; lastY = y; lastR = radius
        capsule[0] = cx; capsule[1] = cy; capsule[2] = x; capsule[3] = y; capsule[4] = rr
        regionHit = false
        apply(cx, cy, x, y, rr)
        return regionHit
    }

    /**
     * v1.9 (A5): the eraser lifts; the next [add] starts a new run of capsules (with a disc). A
     * tool's outline ([ToolEraseSession]) is several runs: its contours and its fill's rows.
     */
    fun lift() {
        lastX = Float.NaN
        lastY = Float.NaN
    }

    private fun apply(cx: Float, cy: Float, dx: Float, dy: Float, r: Float) {
        val l = min(cx, dx) - r; val t = min(cy, dy) - r
        val rt = max(cx, dx) + r; val b = max(cy, dy) + r
        near(l, t, rt, b) { i -> applyTo(i, cx, cy, dx, dy, r, l, t, rt, b) }
    }

    /** The capsule ([cx], [cy]) to ([dx], [dy]) of radius [r] (box [l], [t], [rt], [b]) over object [i]. */
    private fun applyTo(i: Int, cx: Float, cy: Float, dx: Float, dy: Float, r: Float, l: Float, t: Float, rt: Float, b: Float) {
        if (whole[i]) return
        // Widest reach of any object is unknown: test each object's box grown by its own reach.
        if (!targets.boundsOf(objects[i]).let { it[0] <= rt && it[2] >= l && it[1] <= b && it[3] >= t }) return
        val tg = target(i)
        if (!tg.intersects(l, t, rt, b)) return
        when (mode) {
            VectorEraseMode.OBJECT -> if (tg.touchedBy(cx, cy, dx, dy, r)) doom(i)
            VectorEraseMode.PARTIAL -> {
                if (byRegion?.get(i) == true) {
                    // v1.9: cut by the region at the lift; every capsule over it is punched.
                    if (tg.touchedBy(cx, cy, dx, dy, r)) {
                        regionHit = true
                        if (!cut[i]) { cut[i] = true; hasEffect = true }
                    }
                } else if (tg.cut == CutKind.WHOLE) {
                    if (tg.touchedBy(cx, cy, dx, dy, r)) doom(i)
                } else {
                    val set = removed[i] ?: Intervals().also { removed[i] = it }
                    val size0 = set.size
                    val sum0 = total(set)
                    EraseMath.capsuleIntervals(tg.lines[0], cx, cy, dx, dy, r + tg.cutReach, set)
                    if (set.size != size0 || total(set) != sum0) mark(i)
                }
            }
            VectorEraseMode.TO_INTERSECTION -> {
                // Closed or filled objects and shapes are only boundaries here.
                if (!tg.isLine) return
                if (tg.cut == CutKind.WHOLE) {
                    // A dot: the touched piece is all of it.
                    if (tg.touchedBy(cx, cy, dx, dy, r)) doom(i)
                    return
                }
                val line = tg.lines[0]
                // Touched where the eraser meets the line's paint (as the other modes), not
                // only its centerline; the spill over a crossing is measured the same way.
                val reach = r + tg.cutReach
                val set = touched[i] ?: Intervals().also { touched[i] = it }
                val sum0 = total(set)
                val size0 = set.size
                EraseMath.capsuleIntervals(line, cx, cy, dx, dy, reach, set)
                if (set.isEmpty || (set.size == size0 && total(set) == sum0)) return
                val cr = crossings[i] ?: crossingsOf(i).also { crossings[i] = it }
                val out = Intervals()
                EraseMath.piecesToIntersection(line, cr, set, reach, out)
                val old = removed[i]
                if (old == null || total(old) != total(out) || old.size != out.size) {
                    removed[i] = out
                    if (!out.isEmpty) mark(i)
                }
            }
        }
    }

    private fun total(s: Intervals): Float {
        var sum = 0f
        for (k in 0 until s.size) sum += s.end(k) - s.start(k) + 1f
        return sum
    }

    private fun doom(i: Int) {
        whole[i] = true
        mark(i)
    }

    private fun mark(i: Int) {
        versions[i]++
        changed += i
        hasEffect = true
    }

    /** Sorted parameters where object [i]'s centerline crosses the centerlines of the other objects. */
    private fun crossingsOf(i: Int): List<Float> {
        val tg = target(i)
        val line = tg.lines[0]
        val out = ArrayList<Float>()
        var grid: SegmentGrid? = null
        near(tg.left, tg.top, tg.right, tg.bottom) { k ->
            val bb = targets.boundsOf(objects[k])
            if (k != i && !(bb[0] > tg.right || bb[2] < tg.left || bb[1] > tg.bottom || bb[3] < tg.top)) {
                for (other in target(k).lines) {
                    if (other.n < 2 || other.right < line.left || other.left > line.right || other.bottom < line.top || other.top > line.bottom) continue
                    val g = grid ?: SegmentGrid.of(line).also { grid = it }
                    EraseMath.crossings(line, other, out, g)
                }
            }
        }
        out.sort()
        return out
    }

    /**
     * The objects of [content] the partial eraser's region cut works on (v1.9): each shape or
     * filled path it touched (an object with live copies once, whichever copies were touched), in
     * the layer's order.
     */
    fun cutTargets(): List<VObject> {
        val out = ArrayList<VObject>()
        var last = -1
        for (i in 0 until n) {
            if (!cut[i]) continue
            val j = origin?.get(i) ?: i
            if (j == last) continue
            last = j
            out += content.objects[j]
        }
        return out
    }

    /**
     * The content once the erased objects and parts are gone (a cut object's first piece keeps
     * its id and place, further pieces get new ids right after it), or null when nothing goes.
     * v1.9: [cuts] holds the region cut's pieces of [cutTargets] by id ([EraseCut.Cuts.pieces]);
     * a target without an entry stays as it is.
     */
    fun result(cuts: Map<Long, List<VObject>> = emptyMap()): VectorContent? {
        bakedCopies = false
        if (!hasEffect) return null
        if (origin != null) return expandedResult(origin, cuts)
        val map = HashMap<Long, List<VObject>>()
        for (i in 0 until n) {
            val o = objects[i]
            if (cut[i]) { cuts[o.id]?.let { map[o.id] = it }; continue }
            if (whole[i]) { map[o.id] = emptyList(); continue }
            val set = removed[i] ?: continue
            if (set.isEmpty) continue
            val pieces = ErasePieces.pieces(target(i), set) ?: continue
            map[o.id] = pieces
        }
        if (map.isEmpty()) return null
        return content.replaced(map)
    }

    /** What is left of erased objects (pieces in place) when [objects] are the expanded copies ([from]: their object in [content]). */
    private fun expandedResult(from: IntArray, cuts: Map<Long, List<VObject>>): VectorContent? {
        val map = HashMap<Long, List<VObject>>()
        var i = 0
        while (i < n) {
            val j = from[i]
            var end = i
            while (end < n && from[end] == j) end++
            val id = content.objects[j].id
            // v1.9: the region cut baked every copy of a cut target itself.
            val cutHere = cuts[id]
            if (cutHere != null) {
                map[id] = cutHere
                if (end - i > 1) bakedCopies = true
                i = end
                continue
            }
            // The pieces of every copy of content object j, when one of them was erased.
            val out = ArrayList<VObject>(end - i)
            var changedHere = false
            for (k in i until end) {
                // (A cut target the region cut left: it stays, with its copies unless another one changed.)
                if (cut[k]) { out += objects[k]; continue }
                if (whole[k]) { changedHere = true; continue }
                val set = removed[k]
                val pieces = if (set == null || set.isEmpty) null else ErasePieces.pieces(target(k), set)
                if (pieces == null) out += objects[k] else { changedHere = true; out += pieces }
            }
            if (changedHere) {
                map[id] = out
                if (end - i > 1) bakedCopies = true
            }
            i = end
        }
        if (map.isEmpty()) return null
        return content.replaced(map)
    }

    private companion object {
        /**
         * Added around an index query (document px): the index holds paint bounds, the eraser's
         * own boxes ([TargetCache.boundsOf]) can stick out of them by a rounding.
         */
        const val QUERY_PAD = 2f
    }
}

/**
 * Geometry of objects for the eraser and the bucket, cached by object identity (objects are
 * immutable, and most of them survive an edit). Bounded: cleared when it grows too large.
 */
internal class TargetCache {
    private val targets = IdentityHashMap<VObject, EraseTarget>()
    private val bounds = IdentityHashMap<VObject, FloatArray>()

    fun of(o: VObject): EraseTarget {
        targets[o]?.let { return it }
        if (targets.size >= MAX) targets.clear()
        val t = EraseTarget.of(o)
        targets[o] = t
        return t
    }

    /** A box around everything [o] can paint (left, top, right, bottom); cheap (no flattening for strokes). */
    fun boundsOf(o: VObject): FloatArray {
        bounds[o]?.let { return it }
        if (bounds.size >= MAX) bounds.clear()
        val b = when (o) {
            // v1.7: a stroke with symmetry copies: the box around every copy.
            is VStroke -> if (o.copies.isNotEmpty()) of(o).let { tg -> floatArrayOf(tg.left, tg.top, tg.right, tg.bottom) } else {
                val p = o.points
                var l = Float.POSITIVE_INFINITY; var t = Float.POSITIVE_INFINITY
                var r = Float.NEGATIVE_INFINITY; var bt = Float.NEGATIVE_INFINITY
                for (k in 0 until p.size) {
                    val x = p.x[k]; val y = p.y[k]
                    if (!x.isFinite() || !y.isFinite()) continue
                    if (x < l) l = x
                    if (x > r) r = x
                    if (y < t) t = y
                    if (y > bt) bt = y
                }
                val e = EraseTarget.strokeRadius(o)
                floatArrayOf(l - e, t - e, r + e, bt + e)
            }
            // v1.8: a path with live copies: the box around every copy.
            is VPath -> if (ObjectCopies.hasCopies(o)) of(o).let { tg -> floatArrayOf(tg.left, tg.top, tg.right, tg.bottom) } else {
                // The control box (it holds the curves) plus the paint's reach: no flattening,
                // so a layer of many imported paths is not flattened whole at the first touch,
                // only the paths near the finger are.
                var l = Float.POSITIVE_INFINITY; var t = Float.POSITIVE_INFINITY
                var r = Float.NEGATIVE_INFINITY; var bt = Float.NEGATIVE_INFINITY
                for (s in o.subpaths) {
                    val cb = EraseTarget.subpathGeometry(o, s).controlBounds() ?: continue
                    if (!(cb.left.isFinite() && cb.top.isFinite() && cb.right.isFinite() && cb.bottom.isFinite())) continue
                    l = min(l, cb.left); t = min(t, cb.top); r = max(r, cb.right); bt = max(bt, cb.bottom)
                }
                // (EraseTarget's reach, and a pixel to spare.)
                val e = max(EraseTarget.halfWidth(o), 1f) + 1f
                floatArrayOf(l - e, t - e, r + e, bt + e)
            }
            is VShape -> {
                val tg = of(o)
                floatArrayOf(tg.left, tg.top, tg.right, tg.bottom)
            }
        }
        bounds[o] = b
        return b
    }

    fun clear() {
        targets.clear()
        bounds.clear()
    }

    /** Objects whose full geometry is cached now. */
    val targetCount: Int get() = targets.size

    /** Forgets the geometry of objects that are not in [objects] once the cache holds many more (deleted or replaced ones). */
    fun trimTo(objects: List<VObject>) {
        if (targets.size <= objects.size * 2 + SLACK && bounds.size <= objects.size * 2 + SLACK) return
        val keep = IdentityHashMap<VObject, Boolean>(objects.size * 2)
        for (o in objects) keep[o] = true
        targets.keys.retainAll { keep.containsKey(it) }
        bounds.keys.retainAll { keep.containsKey(it) }
    }

    private companion object {
        const val MAX = 8192
        /** Entries kept beyond twice the live objects before [trimTo] trims. */
        const val SLACK = 256
    }
}
