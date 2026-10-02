package com.brushwork.paint.vector.select

import com.brushwork.paint.EditorController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Keeps A2's object edits in order with the vector renders still in flight (v1.5 §4.9, I1/I3).
 *
 * `vectors.update` may render in the background and apply the new content only later. An Object
 * bar action or a Transform lift computed meanwhile would start from the content BEFORE that
 * update (a lift would show the objects where they were, an action would bring back what the
 * pending update changes). So while one of A2's own updates is in flight ([begin] … its end), or
 * while the vector service renders (`vectors.isRendering`), such work waits ([whenIdle]) and runs,
 * in order, as soon as everything landed: right when A2's last update reports back, or, for the
 * service's own renders, at the next poll.
 *
 * Main thread only. The per-editor state is reachable only from the work in flight (the registry
 * holds it weakly), so a closed editor is never kept alive by it.
 */
internal object PendingRenders {
    private class State {
        /** A2 updates sent and not reported back yet. */
        var inFlight = 0

        /** Work waiting for everything to land (oldest first), with the key it was queued under. */
        val waiting = ArrayDeque<Pair<Any?, () -> Unit>>()

        /** Waits for the vector service's own background render (`vectors.isRendering`). */
        var poll: Job? = null
    }

    private val states = WeakHashMap<EditorController, WeakReference<State>>()

    private fun state(c: EditorController): State =
        states[c]?.get() ?: State().also { states[c] = WeakReference(it) }

    /** How often (ms) the end of the vector service's own background render is looked for. */
    private const val POLL_MS = 32L

    /** True while an update is in flight: work on the objects' content must wait ([whenIdle]). */
    fun busy(c: EditorController): Boolean {
        if (c.vectors.isRendering) return true
        val st = states[c]?.get() ?: return false
        return st.inFlight > 0
    }

    /**
     * Runs [block] now when nothing is in flight (true), else queues it behind what is (false):
     * it then runs, on the main thread, once everything landed (in the order it was queued).
     * [key] tags it (see [isWaiting]).
     */
    fun whenIdle(c: EditorController, key: Any? = null, block: () -> Unit): Boolean {
        if (!busy(c)) {
            block()
            return true
        }
        val st = state(c)
        st.waiting.addLast(key to block)
        watch(c, st)
        return false
    }

    /**
     * Marks one of A2's updates as in flight; call the returned function when it reported back
     * (`onDone`, applied or not). Calling it again does nothing. The work queued meanwhile runs
     * then.
     */
    fun begin(c: EditorController): () -> Unit {
        val st = state(c)
        st.inFlight++
        var open = true
        var watchdog: Job? = null
        val end: () -> Unit = {
            if (open) {
                open = false
                watchdog?.cancel()
                st.inFlight--
                drain(c, st)
            }
        }
        // An update that never reports back must not hold every later edit forever.
        watchdog = c.scope.launch(Dispatchers.Main) {
            delay(MAX_WAIT_MS)
            end()
        }
        return end
    }

    /** The longest an update may stay in flight before the work waiting for it runs anyway. */
    private const val MAX_WAIT_MS = 60_000L

    /** Runs the queued work that may run now; each piece may start a new update (then the rest waits for it). */
    private fun drain(c: EditorController, st: State) {
        while (st.waiting.isNotEmpty() && !busy(c)) st.waiting.removeFirst().second.invoke()
        if (st.waiting.isNotEmpty()) watch(c, st)
    }

    /** True while work queued under [key] waits (e.g. one Object bar action at a time). */
    fun isWaiting(c: EditorController, key: Any): Boolean = states[c]?.get()?.waiting?.any { it.first == key } == true

    /** Looks for the end of the vector service's own render (A2's own updates drain when they report back). */
    private fun watch(c: EditorController, st: State) {
        if (!c.vectors.isRendering || st.poll?.isActive == true) return
        st.poll = c.scope.launch(Dispatchers.Main) {
            while (c.vectors.isRendering) delay(POLL_MS)
            st.poll = null
            drain(c, st)
        }
    }
}
