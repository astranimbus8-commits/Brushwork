package com.brushwork.paint.ui.editor

import kotlin.math.sqrt

/**
 * v1.7 (item 10, design §3.10; area I): "undo and redo also works when using the 2 and 3 fingers
 * over a UI". The canvas recognizes its own two- and three-finger taps ([TouchGestureClassifier]);
 * this hub recognizes the ones that touch the UI too: every pointer of the editor's windows
 * passes through it (Compose's Initial pass, before any control sees it), whether it landed on
 * the canvas or on a control.
 *
 * A gesture with a finger on the UI is a history tap under the canvas' rules (every finger down
 * and up within [tapTimeoutMs] of the first down, none moved [tapSlopPx] or more, at most 2 or 3
 * fingers at once, fingers only). Then:
 * - the hub CLAIMS it as soon as a second finger is down while a tap is still possible: from then
 *   on its events are consumed before the controls see them (the button under the first finger
 *   does not fire), and the canvas drops what its fingers started ([Host.yieldCanvas]);
 * - what the UI did before the claim (a slider that jumped under the first finger) is put back
 *   from the [Host.openMark] mark taken at the first down; then ONE undo (or redo) runs.
 * A gesture on the canvas alone stays the canvas' (its own taps); its mark is released at once.
 *
 * Pure and allocation-free: fixed arrays of [MAX_POINTERS], one call per pointer change. Pointers
 * are told apart by window ([slot], up to 16) and id.
 */
class HistoryTapHub(
    private val host: Host,
    private val tapSlopPx: Float,
    private val tapTimeoutMs: Long = TouchGestureClassifier.TAP_TIMEOUT_MS,
) {
    /** What a history tap over the UI acts on (the editor: [EditorHistoryTaps]). */
    interface Host {
        /** Whether a two-finger undo ([redo] false) or three-finger redo tap is on now. */
        fun allowed(redo: Boolean): Boolean

        /** Remembers the state a tap puts back (the first down of a gesture that may be one). */
        fun openMark()

        /** Forgets the [openMark] mark (every gesture end; at once for a canvas-only gesture). */
        fun releaseMark()

        /** The gesture is claimed while fingers are on the canvas: it drops what they started. */
        fun yieldCanvas()

        /** The history tap: put back the mark, then undo ([redo] false) or redo once. */
        fun historyTap(redo: Boolean)
    }

    private val keys = LongArray(MAX_POINTERS)
    private val downX = FloatArray(MAX_POINTERS)
    private val downY = FloatArray(MAX_POINTERS)
    /** The pointer landed on the canvas ([canvasDown]); else on the UI. */
    private val onCanvas = BooleanArray(MAX_POINTERS)
    /** Its canvas / UI side is known (the down event's passes are over). */
    private val decided = BooleanArray(MAX_POINTERS)
    private var count = 0
    private var maxPointers = 0
    private var startTime = 0L
    private var maxMove = 0f
    /** Not a tap any more: a stylus or mouse, too many pointers, or a cancel. */
    private var invalid = false
    private var anyUi = false
    private var anyCanvas = false
    private var yielded = false
    private var markOpen = false
    // The last pointer went up; [upsDone] ends the gesture (with a tap: [endTap], [endRedo]).
    private var ending = false
    private var endTap = false
    private var endRedo = false

    /** The current gesture is a history tap candidate: its events are consumed. */
    var claimed = false
        private set

    /** Pointers down now (tests). */
    val pointersDown: Int get() = count

    /**
     * Pointer [id] of window [slot] went down at [x], [y] (px) at [timeMs]; [finger] is false for
     * a stylus, an eraser or a mouse. Initial pass, before the pointer reaches anything. True:
     * consume the event (the gesture is claimed).
     */
    fun down(slot: Int, id: Long, x: Float, y: Float, timeMs: Long, finger: Boolean): Boolean {
        if (ending) upsDone()
        if (count == 0) startGesture(timeMs)
        val key = keyOf(slot, id)
        if (indexOf(key) >= 0) return claimed
        if (count == MAX_POINTERS) {
            invalid = true
            releaseMark()
            return claimed
        }
        keys[count] = key
        downX[count] = x
        downY[count] = y
        onCanvas[count] = false
        decided[count] = false
        count++
        if (count > maxPointers) maxPointers = count
        if (!finger) invalid = true
        if (tapPossible(timeMs) && tapAllowed(count)) {
            if (!markOpen) {
                markOpen = true
                host.openMark()
            }
            // A finger is already on the UI: claim before this one reaches anything.
            if (!claimed && count >= 2 && anyUi) claimed = true
        } else {
            // No tap (or none that is on) can come of it any more.
            releaseMark()
        }
        return claimed
    }

    /** Pointer [id] of window [slot] went down on the canvas (the canvas' own Initial pass). */
    fun canvasDown(slot: Int, id: Long) {
        val i = indexOf(keyOf(slot, id))
        if (i >= 0 && !decided[i]) onCanvas[i] = true
    }

    /**
     * Every pass of an event with a down is over (Final pass): the new pointers are on the canvas
     * or on the UI now. With no finger on the UI the gesture is the canvas' and the mark goes;
     * else a second finger claims it.
     */
    fun downsDone(timeMs: Long) {
        for (i in 0 until count) {
            if (decided[i]) continue
            decided[i] = true
            if (onCanvas[i]) anyCanvas = true else anyUi = true
        }
        if (!anyUi) {
            releaseMark()
            return
        }
        if (!claimed && count >= 2 && tapPossible(timeMs) && tapAllowed(count)) claimed = true
        if (claimed && anyCanvas && !yielded) {
            yielded = true
            host.yieldCanvas()
        }
    }

    /** Pointer [id] of window [slot] is at [x], [y] at [timeMs]. */
    fun move(slot: Int, id: Long, x: Float, y: Float, timeMs: Long) {
        val i = indexOf(keyOf(slot, id))
        if (i < 0) return
        val dx = x - downX[i]
        val dy = y - downY[i]
        val d = sqrt(dx * dx + dy * dy)
        if (d > maxMove) maxMove = d
        closeMarkIfUseless(timeMs)
    }

    /**
     * Pointer [id] of window [slot] went up at [timeMs] (Initial pass). True: consume the event.
     * The last one ends the gesture once the event's passes are over ([upsDone]).
     */
    fun up(slot: Int, id: Long, timeMs: Long): Boolean {
        val i = indexOf(keyOf(slot, id))
        if (i < 0) return claimed
        val consume = claimed
        removeAt(i)
        if (count == 0) {
            ending = true
            endTap = claimed && tapPossible(timeMs)
            endRedo = maxPointers == 3
        }
        return consume
    }

    /**
     * Every pass of an event with an up is over (Final pass). After the last up, a claimed
     * history tap undoes or redoes: only now, so what a control did on that (consumed) up, such
     * as ending its edit with a step, is put back with the rest.
     */
    fun upsDone() {
        if (!ending) return
        ending = false
        if (endTap && host.allowed(endRedo)) host.historyTap(endRedo)
        endGesture()
    }

    /**
     * The touches of window [slot] were cancelled (or its feed went away): its pointers are
     * forgotten and the gesture is no tap.
     */
    fun cancel(slot: Int) {
        var removed = false
        var i = count - 1
        while (i >= 0) {
            if ((keys[i] and SLOT_MASK) == (slot.toLong() and SLOT_MASK)) {
                removeAt(i)
                removed = true
            }
            i--
        }
        if (!removed) return
        invalid = true
        if (count == 0) endGesture() else releaseMark()
    }

    private fun startGesture(timeMs: Long) {
        startTime = timeMs
        maxPointers = 0
        maxMove = 0f
        invalid = false
        anyUi = false
        anyCanvas = false
        yielded = false
        claimed = false
    }

    private fun endGesture() {
        releaseMark()
        claimed = false
        yielded = false
        count = 0
        ending = false
    }

    private fun tapPossible(timeMs: Long): Boolean =
        !invalid && maxPointers <= 3 && maxMove < tapSlopPx && timeMs - startTime <= tapTimeoutMs

    /** A tap with [n] fingers down so far may still end as one that is on (2 can become 3). */
    private fun tapAllowed(n: Int): Boolean = if (n >= 3) host.allowed(true) else host.allowed(false) || host.allowed(true)

    /** The gesture can no longer be a tap: nothing will put the mark back, so it goes now. */
    private fun closeMarkIfUseless(timeMs: Long) {
        if (markOpen && !tapPossible(timeMs)) releaseMark()
    }

    private fun releaseMark() {
        if (!markOpen) return
        markOpen = false
        host.releaseMark()
    }

    private fun indexOf(key: Long): Int {
        for (i in 0 until count) if (keys[i] == key) return i
        return -1
    }

    private fun removeAt(i: Int) {
        val last = count - 1
        if (i != last) {
            keys[i] = keys[last]
            downX[i] = downX[last]
            downY[i] = downY[last]
            onCanvas[i] = onCanvas[last]
            decided[i] = decided[last]
        }
        count = last
    }

    private fun keyOf(slot: Int, id: Long): Long = (id shl SLOT_BITS) or (slot.toLong() and SLOT_MASK)

    companion object {
        /** Pointers tracked at once; one more makes the gesture no tap. */
        const val MAX_POINTERS = 10
        private const val SLOT_BITS = 4
        private const val SLOT_MASK = (1L shl SLOT_BITS) - 1
    }
}
