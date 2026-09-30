package com.brushwork.paint.tools.vector

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushPreset
import com.brushwork.paint.brush.BrushTool
import com.brushwork.paint.engine.CompositeAction
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerProps
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint

/*
 * Painting a vector path with a painting tool (brush / eraser / smudge / blur), and showing that
 * stroke live while the path is still being edited. Shared by the curve / polyline and shape tools.
 */

/** Distance between the points fed to the painting tool (document px). */
internal const val BRUSH_SAMPLE_SPACING = 0.75f

/**
 * Input points for painting [path] (its first sub-path) with a brush: even samples, stylus
 * points so the given pressure (1, or a taper ramp over [taperFraction] of the length at each
 * end) is honored, and increasing time stamps starting at [t0].
 */
internal fun brushStrokePoints(path: VectorPath, taperFraction: Float = 0f, t0: Long = SystemClock.uptimeMillis()): List<ToolPoint> {
    val samples = CurveGeometry.sample(path, BRUSH_SAMPLE_SPACING)
    if (samples.size < 2) return emptyList()
    val total = VectorPath.length(samples)
    val taperLen = total * taperFraction.coerceIn(0f, 0.5f)
    val out = ArrayList<ToolPoint>(samples.size)
    var dist = 0f
    for (i in samples.indices) {
        if (i > 0) dist += samples[i].distanceTo(samples[i - 1])
        val pressure = if (taperLen > 0f) CurveGeometry.taperPressure(dist, total, taperLen) else 1f
        out += ToolPoint(samples[i].x, samples[i].y, pressure, t0 + i, isStylus = true)
    }
    return out
}

/**
 * Drives a painting tool along a path through the generic [Tool] API, and keeps the stroke
 * UNFINISHED (onDown + onMove, no onUp) as a live preview while the path is being edited: the
 * painting tool then shows it itself (through its render override, or directly in the pixels for
 * smudge / blur) exactly as it will be painted.
 *
 * Every change calls [request]; replays are coalesced on the main looper (at most one per
 * frame, and further apart when a replay is expensive so dragging stays smooth) and skipped when
 * nothing that affects the stroke changed. A replay first cancels the previous preview
 * (`onCancel` leaves no trace), then feeds the new points. [commit] finishes the stroke (onUp)
 * as a real edit; [cancel] / [end] drop it.
 *
 * Main thread only.
 */
internal class BrushStrokePreview(
    private val controller: EditorController,
    /** The painting tool to use (looked up at every replay). */
    private val paintToolId: () -> ToolId = { controller.lastPaintTool },
) {
    /** A replay waiting to run: [key] identifies the geometry, [points] computes the input. */
    private class Request(val key: Any, val points: () -> List<ToolPoint>)

    /** The unfinished stroke on screen. */
    private class Live(val tool: Tool, val key: Key, val last: ToolPoint)

    /**
     * Everything that changes how the stroke looks: the geometry plus the painting tool, its
     * preset, the color and the layer / selection it paints on.
     */
    private data class Key(
        val geometry: Any,
        val toolId: ToolId,
        val tool: Tool,
        val preset: BrushPreset?,
        val color: Int,
        val layer: Layer,
        val props: LayerProps,
        val target: EditTarget,
        val selection: Selection?,
        val colorMode: ColorMode,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val runnable = Runnable { scheduled = false; flush() }
    private var scheduled = false
    private var pending: Request? = null
    private var live: Live? = null
    /** Key of the last replay the painting tool refused (locked layer...): not retried until something changes. */
    private var refusedKey: Key? = null
    /** Message a refused preview already showed during this editing session (not repeated). */
    private var shownToast: String? = null
    private var lastRunAt = Long.MIN_VALUE / 2
    private var lastCostMs = 0L

    /** True while an unfinished preview stroke is shown. */
    val isLive: Boolean get() = live != null

    /** True while a replay is waiting to run. */
    val hasPending: Boolean get() = pending != null

    private fun paintTool(): Pair<ToolId, Tool>? {
        val id = paintToolId()
        val t = controller.tools[id] ?: return null
        return id to t
    }

    private fun keyFor(geometry: Any, id: ToolId, tool: Tool): Key {
        val layer = controller.doc.activeLayer
        return Key(
            geometry, id, tool, controller.presetFor(id), controller.color, layer, layer.props(),
            controller.editTargetOf(layer), controller.selection, controller.doc.colorMode,
        )
    }

    /**
     * Shows the stroke for [geometry] (compared with equals: pass immutable data such as the
     * path's ops). [points] must compute the input from captured, immutable values; it runs when
     * the coalesced replay happens.
     */
    fun request(geometry: Any, points: () -> List<ToolPoint>) {
        val cur = live
        val tool = paintTool()
        if (cur != null && tool != null && cur.key == keyFor(geometry, tool.first, tool.second)) {
            // Already shown (e.g. a tap that only selected a point).
            pending = null
            unschedule()
            return
        }
        pending = Request(geometry, points)
        if (scheduled) return
        scheduled = true
        val now = SystemClock.uptimeMillis()
        // The replay's own time, plus about as much again for redrawing the tiles it touched,
        // must leave the main thread free most of the time.
        val interval = (lastCostMs * 3).coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
        handler.postDelayed(runnable, (lastRunAt + interval - now).coerceIn(0L, interval))
    }

    /** Runs a waiting replay now (tests; the looper does it otherwise). */
    fun flush() {
        unschedule()
        val req = pending ?: return
        pending = null
        val (id, tool) = paintTool() ?: run { cancelLive(); return }
        val key = keyFor(req.key, id, tool)
        if (live?.key == key || (live == null && key == refusedKey)) return
        cancelLive()
        refusedKey = null
        val layer = key.layer
        // The painting tool would refuse with a message on every replay: no preview instead
        // (committing still reports it).
        if (layer.locked || !layer.visible) { refusedKey = key; return }
        val pts = req.points()
        if (pts.size < 2) return
        val t0 = SystemClock.uptimeMillis()
        val before = controller.message
        tool.onDown(pts[0])
        val msg = controller.message
        if (msg !== before && msg != null) {
            // Say it once per editing session, not on every replay.
            if (msg == shownToast) controller.message = before else shownToast = msg
        }
        if (tool is BrushTool && !tool.isStroking) {
            refusedKey = key
            return
        }
        for (i in 1 until pts.size) tool.onMove(pts[i])
        live = Live(tool, key, pts.last())
        val t1 = SystemClock.uptimeMillis()
        lastCostMs = t1 - t0
        lastRunAt = t1
    }

    /**
     * Paints the stroke for real (one undo step made by the painting tool). When the preview on
     * screen is exactly this stroke it is finished as is (identical result, no second pass);
     * otherwise the path is replayed from scratch. Returns false if there was nothing to paint.
     */
    fun commit(geometry: Any, points: () -> List<ToolPoint>): Boolean {
        pending = null
        unschedule()
        val (id, tool) = paintTool() ?: run { cancelLive(); return false }
        val key = keyFor(geometry, id, tool)
        val cur = live
        if (cur != null && cur.key == key) {
            live = null
            tool.onUp(cur.last.copy(time = cur.last.time + 1))
            return true
        }
        cancelLive()
        val pts = points()
        if (pts.size < 2) return false
        tool.onDown(pts[0])
        for (i in 1 until pts.lastIndex) tool.onMove(pts[i])
        tool.onUp(pts.last())
        return true
    }

    /** Drops the preview (the painting tool leaves no trace) and any waiting replay. */
    fun cancel() {
        pending = null
        unschedule()
        cancelLive()
        refusedKey = null
    }

    /** [cancel], and forgets the messages shown during this editing session. */
    fun end() {
        cancel()
        shownToast = null
    }

    private fun cancelLive() {
        val l = live ?: return
        live = null
        l.tool.onCancel()
    }

    private fun unschedule() {
        if (!scheduled) return
        handler.removeCallbacks(runnable)
        scheduled = false
    }

    private companion object {
        /** Minimum time between two replays (ms); three times the last replay's cost if larger. */
        const val MIN_INTERVAL_MS = 32L
        const val MAX_INTERVAL_MS = 250L
    }
}

/**
 * Draws plain vector items (the fill of a path painted with a brush) in the screen overlay. Only
 * one render override can exist and the brush preview uses it, so these items are shown on top
 * of the canvas instead of inside the layer: with the layer's opacity, the selection and the
 * color mode, but without its blend mode.
 *
 * The overlay is above the live brush stroke, while the committed stroke is painted OVER the
 * fill: the band the brush covers ([setBand]) is kept free so the whole stroke stays visible.
 */
internal class SpecOverlay {
    private val renderer = VectorRenderer()
    private val clip = Rect()
    private val bounds = RectF()
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val bandSource = Path()
    private val band = Path()
    private var bandWidth = 0f
    private var hasBand = false
    private var bandDirty = false

    /**
     * The live brush stroke follows [outline] (document px) with a brush [width] px wide; null
     * (or no width) removes the band. The band's outline is computed lazily when drawn.
     */
    fun setBand(outline: Path?, width: Float) {
        if (outline == null || outline.isEmpty || !width.isFinite() || width <= 0f) {
            hasBand = false
            return
        }
        bandSource.set(outline)
        bandWidth = width
        hasBand = true
        bandDirty = true
    }

    /** Draws [specs]; with [keepBandFree] the brush band ([setBand]) is left out of them. */
    fun draw(canvas: Canvas, t: ViewTransform, controller: EditorController, layer: Layer, specs: List<VectorPaintSpec>, keepBandFree: Boolean = false) {
        if (specs.isEmpty() || !layer.visible) return
        val doc = controller.doc
        val maskMode = controller.editTargetOf(layer) == EditTarget.MASK
        clip.set(0, 0, doc.width, doc.height)
        bounds.setEmpty()
        for (s in specs) bounds.union(s.bounds)
        if (!bounds.intersect(0f, 0f, doc.width.toFloat(), doc.height.toFloat())) return
        canvas.save()
        canvas.concat(t.matrix)
        val alpha = (layer.opacity.coerceIn(0f, 1f) * 255f).toInt()
        val save = if (alpha < 255) canvas.saveLayerAlpha(bounds, alpha) else canvas.save()
        canvas.clipRect(clip)
        if (keepBandFree && hasBand) {
            if (bandDirty) {
                band.rewind()
                bandPaint.strokeWidth = bandWidth
                bandPaint.getFillPath(bandSource, band)
                bandDirty = false
            }
            canvas.clipOutPath(band)
        }
        val sel = controller.selection
        for (s in specs) renderer.drawClipped(canvas, s, sel, false, clip, maskMode, doc.colorMode)
        canvas.restoreToCount(save)
        canvas.restore()
    }
}

/**
 * Runs [block] and folds every undo action it pushes into ONE step named [label], also when it
 * pushes a single one: a curve / shape painted with the brush is undone as "Curve" / "Shape",
 * not as the painting tool's own "Brush" step.
 */
internal fun EditorController.undoStepNamed(label: String, block: () -> Unit) {
    val um = undoManager
    val mark = um.undoCount
    try {
        block()
    } finally {
        val added = um.takeSince(mark)
        when {
            added.isEmpty() -> {}
            added.size == 1 && added[0].label == label -> um.pushRaw(added[0])
            else -> um.pushRaw(CompositeAction(label, added))
        }
    }
}
