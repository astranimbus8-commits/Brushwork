package com.brushwork.paint.tools.select

import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.Selection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/** Marching ants: the selection edge as a document-space [Path], computed in the background. */
object SelectionOutline {
    /** Largest marching-squares grid; bigger selections are downsampled. */
    private const val MAX_CELLS = 2_000_000
    /** Outlines with more points are recomputed on a coarser grid so drawing stays cheap. */
    private const val MAX_POINTS = 120_000
    private const val SIMPLIFY_EPSILON = 0.35f

    /** Running outline job per editor (main thread only). */
    private val jobs = WeakHashMap<EditorController, Job>()

    /** Computes [sel].outline (doc-space Path of the selection edge) off the main thread, then invalidates. */
    fun computeAsync(controller: EditorController, sel: Selection) {
        jobs.remove(controller)?.cancel()
        if (sel.isEmpty) return
        jobs[controller] = controller.scope.launch {
            val self = coroutineContext[Job]
            val path = try {
                withContext(Dispatchers.Default) { buildOutline(sel) { self?.isActive == false } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                null // the bounds rectangle is drawn instead
            } catch (e: Exception) {
                null
            }
            // A newer selection replaced this one: drop the result.
            if (path != null && controller.selection === sel) {
                sel.outline = path
                controller.invalidateOverlay()
            }
        }
    }

    /**
     * Traces the edge of [sel] (threshold 128) with marching squares. Large selections are
     * downsampled (max per block, so thin areas keep an outline). Returns null if nothing was
     * traced or [cancelled].
     */
    internal fun buildOutline(sel: Selection, cancelled: () -> Boolean = { false }): Path? {
        val b = sel.bounds
        if (b.isEmpty) return null
        val bw = b.width(); val bh = b.height()
        val bytes = SelectionMasks.crop(sel.mask, b)
        if (cancelled()) return null
        var factor = max(1, ceil(sqrt(bw.toDouble() * bh / MAX_CELLS)).toInt())
        while (true) {
            val grid = MarchingSquares.downsampleMax(bytes, bw, bh, factor)
            val gw = (bw + factor - 1) / factor
            val gh = (bh + factor - 1) / factor
            val raw = MarchingSquares.contours(grid, gw, gh, cancelled) ?: return null
            val polys = raw.map { MarchingSquares.simplifyClosed(it, SIMPLIFY_EPSILON) }
            if (MarchingSquares.pointCount(polys) > MAX_POINTS && factor < 64) {
                factor *= 2
                continue
            }
            if (polys.isEmpty()) return null
            return toPath(polys, b.left.toFloat(), b.top.toFloat(), factor.toFloat())
        }
    }

    /** Grid sample i sits at the center of its block: doc = origin + (i + 0.5) * factor. */
    private fun toPath(polys: List<FloatArray>, ox: Float, oy: Float, f: Float): Path {
        val path = Path()
        for (p in polys) {
            path.moveTo(ox + (p[0] + 0.5f) * f, oy + (p[1] + 0.5f) * f)
            var k = 2
            while (k < p.size) {
                path.lineTo(ox + (p[k] + 0.5f) * f, oy + (p[k + 1] + 0.5f) * f)
                k += 2
            }
            path.close()
        }
        return path
    }

    /** Draws marching ants in screen space. [phase] animates the dash offset. */
    fun draw(canvas: Canvas, t: ViewTransform, sel: Selection, phase: Float) {
        val path = sel.outline
        if (path != null) SelectionOverlay.drawDocPath(canvas, t, path, phase)
        else if (!sel.isEmpty) SelectionOverlay.drawDocRect(canvas, t, RectF(sel.bounds), phase)
    }
}
