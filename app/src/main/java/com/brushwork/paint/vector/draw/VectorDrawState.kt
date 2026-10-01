package com.brushwork.paint.vector.draw

import android.os.SystemClock
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer
import com.brushwork.paint.vector.VectorContent
import java.util.IdentityHashMap
import java.util.WeakHashMap

/**
 * What the vector drawing seams (brush capture, eraser, bucket; v1.5 A3) remember per editor:
 * the eraser mode (Compose state, persisted in `AppSettings.vectorEraserMode`), the one-time
 * hints of this editor session, the object geometry cache and the queue of their content
 * updates. Main thread.
 */
internal class VectorDrawState(private val c: EditorController) {
    /** The vector eraser's mode (Compose state). */
    val eraseMode: MutableState<VectorEraseMode> = mutableStateOf(VectorEraseMode.parse(c.settings.vectorEraserMode))

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
     * Applies new content to a vector layer: `VectorLayers.update`, which may render in the
     * background and call its last argument later (replaceable in tests).
     */
    var update: (layer: Layer, after: VectorContent, label: String, onDone: (Boolean) -> Unit) -> Unit =
        { layer, after, label, onDone -> c.vectors.update(layer, after, label, onDone = onDone) }

    /** Per layer: when the running update started (uptime ms) and the jobs waiting for it. */
    private val running = IdentityHashMap<Layer, Long>()
    private val generation = IdentityHashMap<Layer, Int>()
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

    private fun next(layer: Layer) {
        val queue = waiting[layer]
        val job = queue?.removeFirstOrNull()
        if (queue != null && queue.isEmpty()) waiting.remove(layer)
        if (job == null) {
            running.remove(layer)
            generation.remove(layer)
            return
        }
        run(layer, job)
    }

    companion object {
        /** An update that has not reported back after this long (ms) no longer holds the next ones. */
        private const val STUCK_MS = 30_000L

        private val states = WeakHashMap<EditorController, VectorDrawState>()

        fun of(c: EditorController): VectorDrawState = states.getOrPut(c) { VectorDrawState(c) }
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
