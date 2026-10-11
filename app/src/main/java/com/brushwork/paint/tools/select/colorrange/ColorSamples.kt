package com.brushwork.paint.tools.select.colorrange

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Color select's samples (v1.8, design §3.5) with their in-tool history: each add and each
 * clear is one step the top row's Undo takes back. At most [MAX] samples; one more replaces the
 * oldest. Main thread only; the counts are Compose state.
 */
internal class ColorSamples {

    /** A sampled [color] (opaque ARGB) and where it was tapped (document px; NaN when not tapped). */
    data class Sample(val color: Int, val x: Float = Float.NaN, val y: Float = Float.NaN) {
        val tapped: Boolean get() = !x.isNaN() && !y.isNaN()
    }

    /** The samples, oldest first. */
    var list: List<Sample> by mutableStateOf(emptyList())
        private set

    /** Steps Undo can take back (since the last confirm). */
    var undoCount by mutableIntStateOf(0)
        private set

    /** Steps Redo can bring back. */
    var redoCount by mutableIntStateOf(0)
        private set

    private val undo = ArrayDeque<List<Sample>>()
    private val redo = ArrayDeque<List<Sample>>()

    /** The samples at the last confirm: what [revert] goes back to. */
    private var base: List<Sample> = emptyList()

    /** The samples' colors, oldest first. */
    fun colors(): IntArray = IntArray(list.size) { list[it].color }

    /** Adds [s] (one step); the oldest goes when [MAX] are there. */
    fun add(s: Sample) {
        push()
        list = (list + s).takeLast(MAX)
    }

    /** Removes every sample (one step); nothing happens with none. */
    fun clear(): Boolean {
        if (list.isEmpty()) return false
        push()
        list = emptyList()
        return true
    }

    fun undo(): Boolean {
        val prev = undo.removeLastOrNull() ?: return false
        redo.addLast(list)
        list = prev
        counts()
        return true
    }

    fun redo(): Boolean {
        val next = redo.removeLastOrNull() ?: return false
        undo.addLast(list)
        list = next
        counts()
        return true
    }

    /** Keeps the samples, forgets their steps (a confirm, the tool closing). */
    fun resetHistory() {
        undo.clear(); redo.clear()
        base = list
        counts()
    }

    /** Back to the samples before the steps not confirmed yet, and their steps forgotten (✕). */
    fun revert() {
        list = base
        resetHistory()
    }

    private fun push() {
        undo.addLast(list)
        while (undo.size > MAX_HISTORY) undo.removeFirst()
        redo.clear()
        counts()
    }

    private fun counts() {
        undoCount = undo.size
        redoCount = redo.size
    }

    companion object {
        /** Samples kept at once. */
        const val MAX = 8

        /** Steps kept (each is a list of at most [MAX] samples). */
        const val MAX_HISTORY = 100
    }
}
