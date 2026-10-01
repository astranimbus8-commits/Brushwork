package com.brushwork.paint.vector.select

import android.graphics.Rect
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.select.SelectionJobs
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorOps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Which objects of a vector layer a pixel selection touches (`VectorOps.touching`), for the
 * object selection funnel and the Transform lift (v1.5 §4.9, A2). `touching` rasterizes each
 * candidate's footprint against the selection in 512 px squares (draw, mask, read back, scan:
 * about 2 ms a square on the T606): a handful of squares is done right away; more runs on
 * [Dispatchers.Default] (the content and the selection are immutable), a few objects at a time
 * so that a cancel (a newer selection, Stop on the busy overlay) stops it promptly, with the busy
 * overlay after 400 ms. The main thread stays responsive.
 */
internal object ObjectTouch {
    /** Footprint squares (512 px, see `VectorOps.touching`) still rasterized on the main thread (≈ 15 ms on the T606). */
    const val SYNC_SQUARES = 8

    /** Layers with more objects are always searched in the background (their bounds alone take a while). */
    const val SYNC_OBJECTS = 64

    /** Objects tested between two cancellation checks of a background search. */
    private const val CHUNK = 48

    /** Side of the squares `VectorOps.touching` rasterizes footprints in. */
    private const val SQUARE = 512

    /**
     * The number of 512 px squares `VectorOps.touching` may rasterize for [content] and [sel]
     * (objects whose paint bounds meet the selection's bounds), capped just above [limit].
     */
    fun squares(content: VectorContent, sel: Selection, limit: Int = SYNC_SQUARES): Int {
        if (sel.isEmpty) return 0
        val sb = sel.bounds
        var n = 0
        for (o in content.objects) {
            val b = VectorOps.bounds(o)
            if (b.isEmpty) continue
            val r = Rect(floor(b.left).toInt() - 1, floor(b.top).toInt() - 1, ceil(b.right).toInt() + 1, ceil(b.bottom).toInt() + 1)
            if (!r.intersect(sb)) continue
            n += ((r.width() + SQUARE - 1) / SQUARE) * ((r.height() + SQUARE - 1) / SQUARE)
            if (n > limit) return n
        }
        return n
    }

    /**
     * The ids of [content]'s objects that [sel] touches, handed to [onResult] on the main thread:
     * at once when it is cheap (at most [SYNC_OBJECTS] objects and [squares] ≤ [SYNC_SQUARES]; the
     * returned job is then null), else after a background computation (the returned job).
     * [onFailed] runs instead when there was no memory for it, [onCancelled] (main thread) when
     * the job was cancelled before it delivered.
     */
    fun run(
        c: EditorController,
        content: VectorContent,
        sel: Selection,
        busyLabel: String,
        onFailed: () -> Unit = {},
        onCancelled: () -> Unit = {},
        onResult: (Set<Long>) -> Unit,
    ): Job? {
        if (content.objects.size <= SYNC_OBJECTS && squares(content, sel) <= SYNC_SQUARES) {
            val ids = try {
                VectorOps.touching(content, sel)
            } catch (e: OutOfMemoryError) {
                c.toast(NO_MEMORY)
                onFailed()
                return null
            }
            onResult(ids)
            return null
        }
        var delivered = false
        val job = c.scope.launch(Dispatchers.Main) {
            val ids = try {
                withContext(Dispatchers.Default) { touchingCancellable(content, sel) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                delivered = true
                c.toast(NO_MEMORY)
                onFailed()
                return@launch
            }
            delivered = true
            onResult(ids)
        }
        // Cancelled before it delivered (also before it even started): tell the caller, on main.
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException && !delivered) c.scope.launch(Dispatchers.Main.immediate) { onCancelled() }
        }
        SelectionJobs.showBusyIfSlow(c, job, busyLabel)
        return job
    }

    /**
     * [VectorOps.touching] a few candidates (objects whose paint bounds meet the selection's) at
     * a time, stopping when the coroutine is cancelled. Same result, in stacking order.
     */
    private suspend fun touchingCancellable(content: VectorContent, sel: Selection): Set<Long> {
        if (sel.isEmpty) return emptySet()
        val sb = sel.bounds
        val candidates = ArrayList<VObject>()
        for ((k, o) in content.objects.withIndex()) {
            if (k % 256 == 0) coroutineContext.ensureActive()
            val b = VectorOps.bounds(o)
            if (b.isEmpty) continue
            val r = Rect(floor(b.left).toInt() - 1, floor(b.top).toInt() - 1, ceil(b.right).toInt() + 1, ceil(b.bottom).toInt() + 1)
            if (r.intersect(sb)) candidates += o
        }
        val out = LinkedHashSet<Long>()
        var i = 0
        while (i < candidates.size) {
            coroutineContext.ensureActive()
            val chunk = candidates.subList(i, minOf(candidates.size, i + CHUNK))
            out += VectorOps.touching(VectorContent(objects = chunk), sel)
            i += CHUNK
        }
        return out
    }

    private const val NO_MEMORY = "Not enough memory to find the objects"
}
