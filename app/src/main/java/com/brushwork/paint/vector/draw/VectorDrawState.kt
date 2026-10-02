package com.brushwork.paint.vector.draw

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer
import com.brushwork.paint.vector.VectorContent
import kotlinx.coroutines.Job
import java.util.IdentityHashMap
import java.util.WeakHashMap

/**
 * What the vector drawing seams (brush capture, eraser, bucket; v1.5 A3) remember per editor:
 * the eraser mode (Compose state, persisted in `AppSettings.vectorEraserMode`), the one-time
 * hints of this editor session, the object geometry cache and the queue of their content
 * updates. Main thread.
 *
 * It never holds its editor (nor a layer it doesn't need): the states live in a weak map keyed
 * by the editor, and a value that referenced its key would keep every closed editor, with its
 * document and layer bitmaps, alive for the life of the app. Jobs waiting for an update do hold
 * their editor: they are dropped when the editor closes (its scope ends; a background render on
 * its way is abandoned then and never reports back).
 */
internal class VectorDrawState(initialMode: VectorEraseMode) {
    /** The vector eraser's mode (Compose state). */
    val eraseMode: MutableState<VectorEraseMode> = mutableStateOf(initialMode)

    /** "Selections don't limit vector strokes" was shown. */
    var selectionHintShown = false

    /** "Closed shapes and fills are erased whole" was shown. */
    var partialWholeHintShown = false

    /** What the eraser does on a vector layer (and where its modes are) was shown. */
    var eraserHintShown = false

    /** Geometry of the objects the eraser and the bucket test (by identity). */
    val targets = TargetCache()

    /** True while the bucket traces an enclosed area (taps are ignored meanwhile). */
    var filling = false

    // ------------------------------------------------------------------ content updates

    /**
     * Applies new content to a vector layer of editor `c`: `VectorLayers.update`, which may
     * render in the background and call its last argument later (replaceable in tests).
     */
    var update: (c: EditorController, layer: Layer, after: VectorContent, label: String, onDone: (Boolean) -> Unit) -> Unit = DEFAULT_UPDATE

    /**
     * Per layer: when the running update started (uptime ms). Weak (layers compare by identity):
     * an update that never reports back doesn't keep a deleted layer and its bitmap alive.
     */
    private val running = WeakHashMap<Layer, Long>()
    /** Per layer: the number of the latest run (weak, as [running]). */
    private val generation = WeakHashMap<Layer, Int>()
    /** Per layer: the jobs waiting for the running update (drained as the updates report back). */
    private val waiting = IdentityHashMap<Layer, ArrayDeque<(() -> Unit) -> Unit>>()

    /**
     * Runs [job] for [layer] now, or once the eraser and bucket updates submitted before it were
     * applied: [job] computes its new content from the layer's content when it runs (an update
     * computed earlier, while a background render of the previous one was on its way, would
     * bring back what that one removed). [job] must call its argument exactly once, when its
     * update is done or when it decided not to change anything.
     */
    fun serial(layer: Layer, job: (finish: () -> Unit) -> Unit) {
        val since = running[layer]
        if (since != null && SystemClock.uptimeMillis() - since < STUCK_MS) {
            waiting.getOrPut(layer) { ArrayDeque() }.addLast(job)
            return
        }
        run(layer, job)
    }

    private fun run(layer: Layer, job: (() -> Unit) -> Unit) {
        running[layer] = SystemClock.uptimeMillis()
        val gen = (generation[layer] ?: 0) + 1
        generation[layer] = gen
        var finished = false
        val finish = {
            if (!finished) {
                finished = true
                // (A job that outlived the stuck guard does not start the next one twice.)
                if (generation[layer] == gen) next(layer)
            }
        }
        try {
            job(finish)
        } catch (e: Throwable) {
            // A failed job never holds the ones after it.
            finish()
            throw e
        }
    }

    /** The editor closed: the updates on their way never report back, the jobs waiting for them are dropped. */
    fun abandon() {
        waiting.clear()
        running.clear()
    }

    private fun next(layer: Layer) {
        val queue = waiting[layer]
        val job = queue?.removeFirstOrNull()
        if (queue != null && queue.isEmpty()) waiting.remove(layer)
        if (job == null) {
            // (The generation keeps counting: a job that outlived the stuck guard and reports
            // back much later never matches a newer run.)
            running.remove(layer)
            return
        }
        run(layer, job)
    }

    companion object {
        /** An update that has not reported back after this long (ms) no longer holds the next ones. */
        private const val STUCK_MS = 30_000L

        private val DEFAULT_UPDATE: (EditorController, Layer, VectorContent, String, (Boolean) -> Unit) -> Unit =
            { c, layer, after, label, onDone -> c.vectors.update(layer, after, label, onDone = onDone) }

        /** Per editor (weak keys; a state never references its editor, see the class comment). */
        private val states = WeakHashMap<EditorController, VectorDrawState>()

        fun of(c: EditorController): VectorDrawState {
            states[c]?.let { return it }
            val s = VectorDrawState(VectorEraseMode.parse(c.settings.vectorEraserMode))
            states[c] = s
            // (The handler is held by the editor's own scope, not by anything static.)
            c.scope.coroutineContext[Job]?.invokeOnCompletion { Handler(Looper.getMainLooper()).post { s.abandon() } }
            return s
        }
    }
}

/** The vector eraser's mode for editor [c] (Compose state) and how to change it (persisted). */
object VectorEraserModes {
    fun mode(c: EditorController): VectorEraseMode = VectorDrawState.of(c).eraseMode.value

    fun setMode(c: EditorController, m: VectorEraseMode) {
        val s = VectorDrawState.of(c)
        if (s.eraseMode.value == m) return
        s.eraseMode.value = m
        c.settings.vectorEraserMode = m.name
    }
}
