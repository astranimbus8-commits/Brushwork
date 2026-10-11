package com.brushwork.paint.tools.select.magnetic

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * The magnetic lasso's open outline (v1.8, design §3.4): its anchors (document px), the traced
 * segment from each anchor to the next, the active anchor, and the in-tool history.
 *
 * Every edit makes new arrays (copy on write), so a history state shares its arrays with the
 * outline safely and undo / redo bring back traced segments without tracing them again. A
 * segment is a [FloatArray] of x, y pairs from its anchor to the next one, both ends exact; null
 * while it is not traced (drawn straight and dashed).
 *
 * Steps: [add], [delete], [clear] with a step, and a move between [beginEdit] and the next edit
 * (a drag, a pill edit) are one step each; [undo] / [redo] step through them. Main thread only.
 */
internal class MagneticPoints {

    private class State(val xy: FloatArray, val segments: Array<FloatArray?>, val active: Int)

    private var xy = FloatArray(0)
    private var segments: Array<FloatArray?> = arrayOfNulls(0)
    private val undo = ArrayDeque<State>()
    private val redo = ArrayDeque<State>()
    private var editRedo: List<State>? = null

    /** Anchors (Compose state). */
    var count by mutableIntStateOf(0)
        private set

    /** The active anchor (the pill's point, "Delete point"), -1 with none (Compose state). */
    var active by mutableIntStateOf(-1)
        private set

    /** Bumped on every change of the anchors (Compose state: the pill's position reads it). */
    var version by mutableIntStateOf(0)
        private set

    /** Steps [undo] can take back (Compose state). */
    var undoCount by mutableIntStateOf(0)
        private set

    /** Steps [redo] can bring back (Compose state). */
    var redoCount by mutableIntStateOf(0)
        private set

    fun x(i: Int): Float = xy[2 * i]
    fun y(i: Int): Float = xy[2 * i + 1]

    /** The traced segment from anchor [i] to anchor [i] + 1, or null (not traced yet). */
    fun segment(i: Int): FloatArray? = segments.getOrNull(i)

    /** The anchors as x0, y0, x1, y1, ... (the outline's own array: never written). */
    fun anchors(): FloatArray = xy

    /** The segments (a copy of the list; the paths themselves are never written). */
    fun segments(): Array<FloatArray?> = segments.copyOf()

    /** The anchor within [radius] of ([x], [y]), the newest of equally near ones; -1 with none. */
    fun nearest(x: Float, y: Float, radius: Float): Int {
        var best = -1
        var bestD = radius * radius
        for (i in count - 1 downTo 0) {
            val dx = x(i) - x; val dy = y(i) - y
            val d = dx * dx + dy * dy
            if (d < bestD || (best < 0 && d <= bestD)) { best = i; bestD = d }
        }
        return best
    }

    /** Appends an anchor (one step); it becomes the active one. */
    fun add(x: Float, y: Float) {
        push()
        val n = count + 1
        val next = xy.copyOf(2 * n)
        next[2 * n - 2] = x; next[2 * n - 1] = y
        set(next, segments.copyOf(n - 1), n - 1)
    }

    /** Makes [i] the active anchor (not a step). */
    fun activate(i: Int) {
        if (i in 0 until count && i != active) { active = i; version++ }
    }

    /**
     * Starts a move (a drag, a pill edit; one step with the [move]s that follow): saves the
     * outline. [cancelEdit] takes it back without a trace.
     */
    fun beginEdit() {
        val saved = redo.toList()
        push()
        editRedo = saved
    }

    /** Takes back the edit started by [beginEdit] (a cancelled drag): no step, no redo change. */
    fun cancelEdit() {
        val saved = editRedo ?: return
        editRedo = null
        val prev = undo.removeLastOrNull() ?: return
        redo.clear(); redo.addAll(saved)
        restore(prev)
    }

    /** Moves anchor [i] (part of the step [beginEdit] started); its two segments are traced again. */
    fun move(i: Int, x: Float, y: Float) {
        if (i !in 0 until count || (x(i) == x && y(i) == y)) return
        val next = xy.copyOf()
        next[2 * i] = x; next[2 * i + 1] = y
        val segs = segments.copyOf()
        if (i > 0) segs[i - 1] = null
        if (i < segs.size) segs[i] = null
        set(next, segs, i)
    }

    /**
     * Deletes anchor [i] (one step): its neighbours are joined by a new segment (traced again).
     * The anchor before it becomes the active one.
     */
    fun delete(i: Int) {
        if (i !in 0 until count) return
        push()
        val n = count - 1
        val next = FloatArray(2 * n)
        for (j in 0 until n) {
            val o = if (j < i) j else j + 1
            next[2 * j] = xy[2 * o]; next[2 * j + 1] = xy[2 * o + 1]
        }
        val segs = arrayOfNulls<FloatArray>(maxOf(0, n - 1))
        for (j in segs.indices) {
            val o = if (j < i) j else j + 1
            // Segment j joins new anchors j and j + 1: kept when they were neighbours before.
            val o2 = if (j + 1 < i) j + 1 else j + 2
            if (o2 == o + 1) segs[j] = segments[o]
        }
        set(next, segs, if (n == 0) -1 else maxOf(0, i - 1))
    }

    /** Drops every anchor: one step when [step] (undo brings them back), else history and all. */
    fun clear(step: Boolean) {
        if (step) {
            if (count == 0) return
            push()
        } else {
            resetHistory()
        }
        set(FloatArray(0), arrayOfNulls(0), -1)
    }

    /**
     * Stores [path] as segment [i] when anchors [i] and [i] + 1 are still at ([ax], [ay]) and
     * ([bx], [by]) (a trace finished for the outline as it is). True when stored.
     */
    fun setSegment(i: Int, ax: Float, ay: Float, bx: Float, by: Float, path: FloatArray): Boolean {
        if (i !in 0 until count - 1 || x(i) != ax || y(i) != ay || x(i + 1) != bx || y(i + 1) != by) return false
        val segs = segments.copyOf()
        segs[i] = path
        segments = segs
        return true
    }

    /** Forgets every traced segment, of the outline and of its history (a new edge map or contrast). */
    fun forgetSegments() {
        segments = arrayOfNulls(segments.size)
        fun blank(d: ArrayDeque<State>) {
            for (k in d.indices) d[k] = State(d[k].xy, arrayOfNulls(d[k].segments.size), d[k].active)
        }
        blank(undo); blank(redo)
    }

    fun undo(): Boolean {
        editRedo = null
        val prev = undo.removeLastOrNull() ?: return false
        redo.addLast(State(xy, segments, active))
        restore(prev)
        return true
    }

    fun redo(): Boolean {
        editRedo = null
        val next = redo.removeLastOrNull() ?: return false
        undo.addLast(State(xy, segments, active))
        restore(next)
        return true
    }

    fun resetHistory() {
        undo.clear(); redo.clear()
        editRedo = null
        counts()
    }

    private fun push() {
        editRedo = null
        undo.addLast(State(xy, segments, active))
        while (undo.size > MAX_HISTORY) undo.removeFirst()
        redo.clear()
        counts()
    }

    private fun restore(s: State) {
        set(s.xy, s.segments, s.active)
    }

    private fun set(xy: FloatArray, segments: Array<FloatArray?>, active: Int) {
        this.xy = xy
        this.segments = segments
        count = xy.size / 2
        this.active = active
        version++
        counts()
    }

    private fun counts() {
        undoCount = undo.size
        redoCount = redo.size
    }

    companion object {
        const val MAX_HISTORY = 200
    }
}
