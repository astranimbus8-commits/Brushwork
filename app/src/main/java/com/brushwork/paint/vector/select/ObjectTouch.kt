package com.brushwork.paint.vector.select

import android.graphics.Rect
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.select.SelectionJobs
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorOps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Which objects of a vector layer a pixel selection touches (`VectorOps.touching`), for the
 * object selection funnel and the Transform lift (v1.5 §4.9, A2). `touching` rasterizes each
 * candidate's footprint against the selection in 512 px squares: a handful of objects takes a few
 * milliseconds and is done right away; more runs on [Dispatchers.Default] (the content and the
 * selection are immutable) with the busy overlay after 400 ms, so the main thread stays responsive
 * on the T606.
 */
internal object ObjectTouch {
    /** Footprint squares (512 px, see `VectorOps.touching`) still rasterized on the main thread. */
    const val SYNC_SQUARES = 16

    /** Layers with more objects are always searched in the background (their bounds alone take a while). */
    const val SYNC_OBJECTS = 64

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
     * returned job is then null), else
     * after a background computation (the returned job; cancelling it drops the result).
     * [onFailed] runs instead when there was no memory for it.
     */
    fun run(
        c: EditorController,
        content: VectorContent,
        sel: Selection,
        busyLabel: String,
        onFailed: () -> Unit = {},
        onResult: (Set<Long>) -> Unit,
    ): Job? {
        if (content.objects.size <= SYNC_OBJECTS && squares(content, sel) <= SYNC_SQUARES) {
            val ids = try {
                VectorOps.touching(content, sel)
            } catch (e: OutOfMemoryError) {
                c.toast("Not enough memory to find the objects")
                onFailed()
                return null
            }
            onResult(ids)
            return null
        }
        val job = c.scope.launch(Dispatchers.Main) {
            val ids = try {
                withContext(Dispatchers.Default) { VectorOps.touching(content, sel) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                c.toast("Not enough memory to find the objects")
                onFailed()
                return@launch
            }
            onResult(ids)
        }
        SelectionJobs.showBusyIfSlow(c, job, busyLabel)
        return job
    }
}
