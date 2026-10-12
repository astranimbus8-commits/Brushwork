package com.brushwork.paint.tools

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * v1.9 (I22, design 2.4.1): an in-tool or in-session history of small immutable states (no
 * bitmaps): a filter's parameters, a text path's nodes. It is session state, never document
 * history: it lives as long as its tool or session and is never saved.
 *
 * [current] is the state shown now; [record] adds a finished change, [undo] / [redo] step through
 * them. At most [cap] steps can be taken back: recording past it drops the oldest. [current],
 * [canUndo], [canRedo] and [depth] are Compose state, so the chrome reads them directly.
 * Main thread only.
 */
class ToolHistory<S : Any>(initial: S, private val cap: Int = 100) {
    /** The states before [current], oldest first (at most [cap]). */
    private val past = ArrayDeque<S>()

    /** The states undone from [current], the next redo last. */
    private val future = ArrayDeque<S>()

    /** The state shown now. */
    var current: S by mutableStateOf(initial)
        private set

    /** True when [undo] has a step to take back. */
    var canUndo: Boolean by mutableStateOf(false)
        private set

    /** True when [redo] has a step to bring back. */
    var canRedo: Boolean by mutableStateOf(false)
        private set

    /** How many steps [undo] can take back now. */
    var depth: Int by mutableIntStateOf(0)
        private set

    /**
     * Records a finished change: [state] becomes [current], the redo tail is dropped. A no-op when
     * [state] equals [current]; past [cap] steps the oldest is dropped.
     */
    fun record(state: S) {
        if (state == current) return
        past.addLast(current)
        future.clear()
        while (past.size > cap.coerceAtLeast(0)) past.removeFirst()
        current = state
        sync()
    }

    /** One step back: the state before [current], which becomes [current]; null at the bottom. */
    fun undo(): S? {
        val s = past.removeLastOrNull() ?: return null
        future.addLast(current)
        current = s
        sync()
        return s
    }

    /** One step forward again after [undo]; null when there is nothing to redo. */
    fun redo(): S? {
        val s = future.removeLastOrNull() ?: return null
        past.addLast(current)
        current = s
        sync()
        return s
    }

    /** Makes [state] a new bottom: [current], with no past and no future. */
    fun reset(state: S) {
        past.clear()
        future.clear()
        current = state
        sync()
    }

    private fun sync() {
        canUndo = past.isNotEmpty()
        canRedo = future.isNotEmpty()
        depth = past.size
    }
}
