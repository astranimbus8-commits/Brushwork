package com.brushwork.paint.vector.draw

import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VectorContent
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.min

/** The vector eraser's modes (persisted in `AppSettings.vectorEraserMode` by name). */
enum class VectorEraseMode(val label: String, val description: String) {
    /** Every touched object goes. */
    OBJECT("Object", "Erases every object it touches"),
    /** Strokes and open lines are cut where the eraser passes; closed or filled objects go whole. */
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
 */
internal class EraseSession(
    val content: VectorContent,
    val mode: VectorEraseMode,
    private val targets: TargetCache = TargetCache(),
) {
    private val objects: List<VObject> = content.objects
    private val n = objects.size

    init {
        targets.trimTo(objects)
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

    /** True when a closed or filled object is erased whole by the partial eraser. */
    var removedWholeInPartial = false
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
     * point (or a disc for the first one) is applied to every object it can reach.
     */
    fun add(x: Float, y: Float, r: Float) {
        if (!x.isFinite() || !y.isFinite()) return
        val radius = if (r.isFinite()) max(0.5f, r) else 0.5f
        val cx = if (lastX.isNaN()) x else lastX
        val cy = if (lastY.isNaN()) y else lastY
        val rr = if (lastX.isNaN()) radius else max(radius, lastR)
        lastX = x; lastY = y; lastR = radius
        apply(cx, cy, x, y, rr)
    }

    private fun apply(cx: Float, cy: Float, dx: Float, dy: Float, r: Float) {
        // Widest reach of any object is unknown: test each object's box grown by its own reach.
        val l = min(cx, dx) - r; val t = min(cy, dy) - r
        val rt = max(cx, dx) + r; val b = max(cy, dy) + r
        for (i in 0 until n) {
            if (whole[i]) continue
            if (!targets.boundsOf(objects[i]).let { it[0] <= rt && it[2] >= l && it[1] <= b && it[3] >= t }) continue
            val tg = target(i)
            if (!tg.intersects(l, t, rt, b)) continue
            when (mode) {
                VectorEraseMode.OBJECT -> if (tg.touchedBy(cx, cy, dx, dy, r)) doom(i)
                VectorEraseMode.PARTIAL -> {
                    if (tg.cut == CutKind.WHOLE) {
                        if (tg.touchedBy(cx, cy, dx, dy, r)) {
                            doom(i)
                            // (A dot is a line with nothing to cut: no hint for it.)
                            if (!tg.isLine) removedWholeInPartial = true
                        }
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
                    if (!tg.isLine) continue
                    if (tg.cut == CutKind.WHOLE) {
                        // A dot: the touched piece is all of it.
                        if (tg.touchedBy(cx, cy, dx, dy, r)) doom(i)
                        continue
                    }
                    val line = tg.lines[0]
                    val set = touched[i] ?: Intervals().also { touched[i] = it }
                    val sum0 = total(set)
                    val size0 = set.size
                    EraseMath.capsuleIntervals(line, cx, cy, dx, dy, r, set)
                    if (set.isEmpty || (set.size == size0 && total(set) == sum0)) continue
                    val cr = crossings[i] ?: crossingsOf(i).also { crossings[i] = it }
                    val out = Intervals()
                    EraseMath.piecesToIntersection(line, cr, set, r, out)
                    val old = removed[i]
                    if (old == null || total(old) != total(out) || old.size != out.size) {
                        removed[i] = out
                        if (!out.isEmpty) mark(i)
                    }
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
        for (k in 0 until n) {
            if (k == i) continue
            val bb = targets.boundsOf(objects[k])
            if (bb[0] > tg.right || bb[2] < tg.left || bb[1] > tg.bottom || bb[3] < tg.top) continue
            for (other in target(k).lines) EraseMath.crossings(line, other, out)
        }
        out.sort()
        return out
    }

    /**
     * The content once the erased objects and parts are gone (a cut object's first piece keeps
     * its id and place, further pieces get new ids right after it), or null when nothing goes.
     */
    fun result(): VectorContent? {
        if (!hasEffect) return null
        val map = HashMap<Long, List<VObject>>()
        for (i in 0 until n) {
            val o = objects[i]
            if (whole[i]) { map[o.id] = emptyList(); continue }
            val set = removed[i] ?: continue
            if (set.isEmpty) continue
            val pieces = ErasePieces.pieces(target(i), set) ?: continue
            map[o.id] = pieces
        }
        if (map.isEmpty()) return null
        return content.replaced(map)
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
        val b = if (o is com.brushwork.paint.vector.VStroke) {
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
        } else {
            val tg = of(o)
            floatArrayOf(tg.left, tg.top, tg.right, tg.bottom)
        }
        bounds[o] = b
        return b
    }

    fun clear() {
        targets.clear()
        bounds.clear()
    }

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
