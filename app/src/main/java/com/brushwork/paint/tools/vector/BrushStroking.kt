package com.brushwork.paint.tools.vector

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushPreset
import com.brushwork.paint.brush.BrushTool
import com.brushwork.paint.brush.PathStrokeInput
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
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/*
 * Painting a vector path with a painting tool (brush / eraser / smudge / blur), and showing that
 * stroke live while the path is still being edited. Shared by the curve / polyline and shape tools.
 */

/** Distance between the points fed to the painting tool (document px). */
internal const val BRUSH_SAMPLE_SPACING = 0.75f

/** Flattening tolerance of the path a brush follows (document px). */
internal const val BRUSH_SAMPLE_TOLERANCE = 0.1f

private const val BRUSH_FLATTEN_TOLERANCE = BRUSH_SAMPLE_TOLERANCE

/**
 * Per-sample line widths along a path stroke (v1.5 §4.5, filled by A4): [widthsAtSamples] has
 * one width factor per sample of the stroke's input; the pressures are multiplied by
 * `w / max(w)` so the brush (with `pressureSize`) follows the thickness.
 */
data class WidthProfile(val widthsAtSamples: FloatArray) {
    override fun equals(other: Any?): Boolean = other is WidthProfile && widthsAtSamples.contentEquals(other.widthsAtSamples)
    override fun hashCode(): Int = widthsAtSamples.contentHashCode()
}

/**
 * Input points for painting [path] (its first sub-path) with a brush, into [out]: even samples
 * [BRUSH_SAMPLE_SPACING] px apart (first and last points included, a closed path ends back at
 * its start) with pressure 1, or a taper ramp over [taperFraction] of the length at each end;
 * [widths] (one per sample) scale the pressures (see [WidthProfile]).
 *
 * Exactly the points of `CurveGeometry.sample(path, BRUSH_SAMPLE_SPACING)` (same float
 * operations in the same order), computed without an object per point: this runs for every
 * live replay of a long path. Main thread only (shared scratch); see [brushStrokeSamples].
 */
internal fun brushStrokeInput(path: VectorPath, taperFraction: Float = 0f, out: PathStrokeInput = PathStrokeInput(), widths: WidthProfile? = null): PathStrokeInput =
    strokeSamples(FlatScratch.MAIN, path, taperFraction, widths, out)

/**
 * The thread-safe twin of [brushStrokeInput] (its own scratch arrays): bit-identical samples,
 * for background renderers of vector layers (StrokeRaster along a VPath).
 */
internal fun brushStrokeSamples(path: VectorPath, taperFraction: Float, widths: WidthProfile?, out: PathStrokeInput): PathStrokeInput =
    strokeSamples(FlatScratch(), path, taperFraction, widths, out)

private fun strokeSamples(scratch: FlatScratch, path: VectorPath, taperFraction: Float, widths: WidthProfile?, out: PathStrokeInput): PathStrokeInput {
    out.clear()
    val flat = scratch.flattenFirst(path, BRUSH_FLATTEN_TOLERANCE)
    scratch.resample(flat, BRUSH_SAMPLE_SPACING, out)
    val n = out.size
    if (n < 2) { out.clear(); return out }
    val xs = out.x; val ys = out.y; val ps = out.pressure
    var total = 0f
    for (i in 1 until n) total += hypot(xs[i - 1] - xs[i], ys[i - 1] - ys[i])
    val taperLen = total * taperFraction.coerceIn(0f, 0.5f)
    var dist = 0f
    for (i in 0 until n) {
        if (i > 0) dist += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        ps[i] = if (taperLen > 0f) CurveGeometry.taperPressure(dist, total, taperLen) else 1f
    }
    applyWidthProfile(out, widths)
    return out
}

/**
 * Multiplies the pressures of [out] by `w / max(w)` of [widths] (one factor per sample; ignored
 * when shorter than the input or when its largest factor is not > 0). Exactly what
 * [brushStrokeInput] does with its `widths`, for an input computed without them.
 */
internal fun applyWidthProfile(out: PathStrokeInput, widths: WidthProfile?) {
    val n = out.size
    val ps = out.pressure
    val w = widths?.widthsAtSamples
    if (w != null && w.size >= n) {
        var wMax = 0f
        for (i in 0 until n) if (w[i] > wMax) wMax = w[i]
        if (wMax > 0f && wMax.isFinite()) for (i in 0 until n) ps[i] *= (w[i] / wMax).coerceIn(0f, 1f)
    }
}

/** Largest factor of the first [n] entries of [widths] (0 when none is > 0). */
internal fun profileMax(widths: WidthProfile, n: Int = widths.widthsAtSamples.size): Float {
    val w = widths.widthsAtSamples
    var m = 0f
    for (i in 0 until minOf(n, w.size)) if (w[i] > m) m = w[i]
    return if (m.isFinite()) m else 0f
}

/**
 * Array versions of `VectorPath.flatten` (first sub-path) and `VectorPath.resample`, with the
 * same arithmetic so the samples are bit-identical. One instance per thread: [MAIN] is shared on
 * the main thread, background renderers make their own.
 */
internal class FlatScratch {
    /** The flattened first sub-path: [n] points in [x] / [y]. */
    class Flat {
        var x = FloatArray(512)
        var y = FloatArray(512)
        var n = 0
        var closed = false

        fun add(px: Float, py: Float) {
            if (n == x.size) { x = x.copyOf(n * 2); y = y.copyOf(n * 2) }
            x[n] = px; y[n] = py; n++
        }
    }

    private val flat = Flat()

    fun flattenFirst(path: VectorPath, tolerance: Float): Flat {
        val f = flat
        f.n = 0
        f.closed = false
        var started = false
        var sx = 0f; var sy = 0f
        var lx = 0f; var ly = 0f
        loop@ for (op in path.ops) {
            when (op) {
                is PathOp.MoveTo -> {
                    if (started) break@loop
                    started = true
                    f.add(op.p.x, op.p.y)
                    sx = op.p.x; sy = op.p.y; lx = sx; ly = sy
                }
                is PathOp.LineTo -> {
                    if (!started) { started = true; f.add(lx, ly); sx = lx; sy = ly }
                    f.add(op.p.x, op.p.y)
                    lx = op.p.x; ly = op.p.y
                }
                is PathOp.CubicTo -> {
                    if (!started) { started = true; f.add(lx, ly); sx = lx; sy = ly }
                    flattenCubic(lx, ly, op.c1.x, op.c1.y, op.c2.x, op.c2.y, op.p.x, op.p.y, tolerance, f)
                    lx = op.p.x; ly = op.p.y
                }
                PathOp.Close -> {
                    if (started) { f.closed = true; break@loop }
                    lx = sx; ly = sy
                }
            }
        }
        return f
    }

    /** `VectorPath.flattenCubic` on floats. */
    private fun flattenCubic(p0x: Float, p0y: Float, c1x: Float, c1y: Float, c2x: Float, c2y: Float, p1x: Float, p1y: Float, tolerance: Float, out: Flat) {
        val dd = max(hypot(p0x - c1x * 2f + c2x, p0y - c1y * 2f + c2y), hypot(c1x - c2x * 2f + p1x, c1y - c2y * 2f + p1y))
        val n = ceil(sqrt(0.75f * dd / tolerance.coerceAtLeast(1e-3f))).toInt().coerceIn(1, 2000)
        for (i in 1..n) {
            val t = i.toFloat() / n
            val u = 1f - t
            val a = u * u * u; val b = 3f * u * u * t; val c = 3f * u * t * t; val d = t * t * t
            out.add(a * p0x + b * c1x + c * c2x + d * p1x, a * p0y + b * c1y + c * c2y + d * p1y)
        }
    }

    /** `VectorPath.resample(points, spacing, closed)` into [out] (pressure left at 1). */
    fun resample(f: Flat, spacing: Float, out: PathStrokeInput) {
        var n = f.n
        if (n == 0) return
        val xs = f.x; val ys = f.y
        // A closed path returns to its first point.
        if (f.closed && n > 1 && (xs[0].compareTo(xs[n - 1]) != 0 || ys[0].compareTo(ys[n - 1]) != 0)) {
            f.add(xs[0], ys[0])
            n = f.n
        }
        val px = f.x; val py = f.y
        val step = spacing.coerceAtLeast(1e-3f)
        out.add(px[0], py[0], 1f)
        var carry = 0f
        for (i in 1 until n) {
            val ax = px[i - 1]; val ay = py[i - 1]
            val bx = px[i]; val by = py[i]
            val seg = hypot(ax - bx, ay - by)
            if (seg <= 0f) continue
            var d = step - carry
            while (d <= seg) {
                val t = d / seg
                out.add(ax + (bx - ax) * t, ay + (by - ay) * t, 1f)
                d += step
            }
            carry = seg - (d - step)
        }
        val ex = px[n - 1]; val ey = py[n - 1]
        val last = out.size - 1
        if (out.size > 1 && hypot(out.x[last] - ex, out.y[last] - ey) <= 1e-3f) {
            out.x[last] = ex; out.y[last] = ey
        } else if (out.x[last].compareTo(ex) != 0 || out.y[last].compareTo(ey) != 0) {
            out.add(ex, ey, 1f)
        }
    }

    companion object {
        /** The main thread's instance. */
        val MAIN = FlatScratch()
    }
}

/**
 * Drives a painting tool along a path and keeps the stroke UNFINISHED (no onUp) as a live
 * preview while the path is being edited: the painting tool then shows it itself (through its
 * render override, or directly in the pixels for smudge / blur) exactly as it will be painted.
 *
 * Every change calls [request]; replays are coalesced on the main looper (at most one per
 * frame, further apart when a replay is expensive) and skipped when nothing that affects the
 * stroke changed. With the brush / eraser a replay only re-renders the stroke from the first
 * point that changed ([BrushTool.updatePath]), and while a finger drags the path
 * ([interacting]) a long re-render is a lighter draft that is redrawn exactly, part by part,
 * once the finger lifts and the path rests. The work of one replay is capped by a budget
 * measured on this device ([budget]), so dragging stays smooth on a slow phone too. Other
 * painting tools (smudge / blur / watercolor, or any [Tool]) are replayed from scratch: the
 * previous preview is cancelled (`onCancel` leaves no trace), then the new points are fed
 * through onDown + onMove (while a finger drags, only if that fits in a frame; otherwise once
 * the drag ends). [commit] finishes the stroke (onUp) as a real edit, always exact;
 * [cancel] / [end] drop it.
 *
 * Main thread only.
 */
internal class BrushStrokePreview(
    private val controller: EditorController,
    /**
     * The brush to paint with instead of the painting tool's current one (looked up at every
     * replay; null = the current one), e.g. the brush a shape layer was drawn with. It is only
     * handed to the painting tool while a stroke starts, so the user's brush and its saved
     * settings never change.
     */
    private val presetOverride: () -> BrushPreset? = { null },
    /** The painting tool to use (looked up at every replay). */
    private val paintToolId: () -> ToolId = { controller.lastPaintTool },
) {
    /**
     * Called whenever the live stroke started, changed tool or ended (the painting tool may have
     * installed or removed its render override): a tool that shows the stroke inside its own
     * override (an edited shape layer) adopts it here.
     */
    var onLiveChanged: (() -> Unit)? = null

    /**
     * While this returns true, strokes start as if there were no pixel selection and the
     * layer's transparency were not locked (v1.5: the path will be an object of a vector layer,
     * which neither clips; its replay must equal the live pixels). Both are only lifted for the
     * moment the painting tool starts the stroke (it keeps what it saw then).
     */
    var unclipped: () -> Boolean = { false }

    /** The random values of the brush for this editing session (a replay with it gives the same texture). */
    val sessionSeed: Long get() = seed

    /**
     * Paints with the random values [s] from now on (a reopened path keeps the texture it was
     * painted with). A live stroke with other values is dropped first.
     */
    fun useSeed(s: Long) {
        if (s == seed) return
        cancel()
        seed = s
    }

    /**
     * True while a stroke starts with the selection lifted (see [unclipped]): the tool hears
     * `onSelectionChanged` twice then, and should ignore it.
     */
    var liftingClip = false
        private set

    /** Runs [block] (which starts a stroke on [layer]) without the selection and alpha lock when [unclipped]. */
    private inline fun <T> clipFree(layer: Layer, block: () -> T): T {
        if (!unclipped()) return block()
        val sel = controller.selection
        val locked = layer.alphaLocked
        if (sel == null && !locked) return block()
        liftingClip = true
        try {
            if (sel != null) controller.setSelection(null, recordUndo = false)
            layer.alphaLocked = false
            try {
                return block()
            } finally {
                layer.alphaLocked = locked
                if (sel != null) controller.setSelection(sel, recordUndo = false)
            }
        } finally {
            liftingClip = false
        }
    }

    /** The brush the stroke is painted with ([presetOverride], else the tool's current one). */
    private fun presetOf(id: ToolId): BrushPreset? = presetOverride() ?: controller.presetFor(id)

    /**
     * Runs [block] (which starts a stroke of painting tool [id]) with the [presetOverride] as the
     * tool's brush; the current brush is back right after (a stroke keeps the brush it started with).
     */
    private inline fun <T> withPreset(id: ToolId, block: () -> T): T {
        val o = presetOverride()
        val cur = controller.presetFor(id)
        if (o == null || cur == null || o == cur) return block()
        controller.updatePreset(id, o)
        try {
            return block()
        } finally {
            controller.updatePreset(id, cur)
        }
    }

    /** A replay waiting to run: [key] identifies the geometry, [points] computes the input. */
    private class Request(val key: Any, val points: (PathStrokeInput) -> Unit)

    /** The unfinished stroke on screen; [exact] is false for a draft ([BrushTool.isDraft]). */
    private class Live(val tool: Tool, val key: Key, val request: Request, val exact: Boolean, val last: ToolPoint)

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
    ) {
        /** Same stroke apart from the path: an update of the live stroke can follow it. */
        fun sameStroke(o: Key): Boolean =
            toolId == o.toolId && tool === o.tool && preset == o.preset && color == o.color && layer === o.layer &&
                props == o.props && target == o.target && selection == o.selection && colorMode == o.colorMode
    }

    private val handler = Handler(Looper.getMainLooper())
    private val runnable = Runnable { scheduled = false; flush() }
    private var scheduled = false
    /** Uptime at which the scheduled replay runs. */
    private var scheduledFor = 0L
    private var pending: Request? = null
    private var live: Live? = null
    /** Key of the last replay the painting tool refused (locked layer...): not retried until something changes. */
    private var refusedKey: Key? = null
    /** Message a refused preview already showed during this editing session (not repeated). */
    private var shownToast: String? = null
    private var lastRunAt = Long.MIN_VALUE / 2
    private var lastCostMs = 0L
    /**
     * Uptime of the last change of the stroke ([request]) or of the end of a drag ([interacting]):
     * a draft is refined once the path has rested [REFINE_DELAY_MS] since then.
     */
    private var restingSince = Long.MIN_VALUE / 2
    /** Time the last replay from scratch took (ms). */
    private var fullReplayMs = 0L
    /** Replays from scratch slower than this (ms) wait for the end of a drag (tests lower it). */
    internal var dragReplayLimitMs = MAX_DRAG_REPLAY_MS
    /** The waiting replay is held back until the drag ends (see [flush]). */
    private var deferred = false
    /** Random values of the brush for this editing session: a replay never changes its texture. */
    private var seed = newSeed()
    private val input = PathStrokeInput(1024)

    /** True while an unfinished preview stroke is shown. */
    val isLive: Boolean get() = live != null

    /** True while the stroke on screen is a draft (see [interacting]). */
    val isDraft: Boolean get() = live?.exact == false

    /** True while a replay is waiting to run. */
    val hasPending: Boolean get() = pending != null

    /**
     * True while a finger drags the path: the stroke then follows it as a draft (see
     * [BrushTool.updatePath]) and is not refined; a stroke replayed from scratch that takes
     * longer than a frame waits for the drag to end. Set back to false when the finger lifts: a
     * draft on screen is refined once the path has rested for [REFINE_DELAY_MS].
     */
    var interacting = false
        set(value) {
            if (field == value) return
            field = value
            if (value) return
            restingSince = SystemClock.uptimeMillis()
            if (deferred) {
                // The stroke held back during the drag follows now.
                deferred = false
                pending?.let { schedule(it, 0L) }
                return
            }
            val cur = live
            // A waiting replay (the last position of the drag) runs as scheduled; a draft
            // already on screen is refined once the path rests.
            if (pending == null && cur != null && !cur.exact) schedule(cur.request, REFINE_DELAY_MS)
        }

    /** How long a refinement still waits for the path to rest (ms). */
    private fun refineDelay(): Long = (restingSince + REFINE_DELAY_MS - SystemClock.uptimeMillis()).coerceAtLeast(0L)

    private fun paintTool(): Pair<ToolId, Tool>? {
        val id = paintToolId()
        val t = controller.tools[id] ?: return null
        return id to t
    }

    private fun keyFor(geometry: Any, id: ToolId, tool: Tool): Key {
        val layer = controller.doc.activeLayer
        return Key(
            geometry, id, tool, presetOf(id), controller.color, layer, layer.props(),
            controller.editTargetOf(layer), controller.selection, controller.doc.colorMode,
        )
    }

    /**
     * Shows the stroke for [geometry] (compared with equals: pass immutable data such as the
     * path's ops). [points] must fill its argument with the input computed from captured,
     * immutable values; it runs when the coalesced replay happens, not before [minDelayMs] from
     * now (a point added under a finger that may still turn into a two-finger tap or pinch waits
     * for that to be ruled out).
     */
    fun request(geometry: Any, minDelayMs: Long = 0L, points: (PathStrokeInput) -> Unit) {
        val cur = live
        val tool = paintTool()
        val req = Request(geometry, points)
        val shown = cur != null && tool != null && cur.key == keyFor(geometry, tool.first, tool.second)
        // The stroke changes (also without a finger on the canvas: nudge arrows held down, numeric
        // fields scrubbed, sliders): a draft is only refined once the changes stop for a moment,
        // instead of refining parts that the next change throws away again.
        if (!shown) restingSince = SystemClock.uptimeMillis()
        if (deferred && interacting) {
            // Held back until the drag ends: only the latest geometry is kept.
            pending = req
            return
        }
        if (shown) {
            // Already shown (e.g. a tap that only selected a point, or a cancelled touch)...
            pending = null
            unschedule()
            // ...as a draft: its refinement goes on once the path rests.
            if (!cur.exact && !interacting) schedule(req, refineDelay())
            return
        }
        schedule(req, minDelayMs)
    }

    private fun schedule(req: Request, minDelayMs: Long) {
        pending = req
        val now = SystemClock.uptimeMillis()
        // The replay's own time, plus about half as much again for redrawing the tiles it
        // touched, must leave the main thread time for input and drawing.
        val interval = (lastCostMs * 3 / 2).coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
        val delay = maxOf((lastRunAt + interval - now).coerceIn(0L, interval), minDelayMs)
        if (scheduled) {
            // Keeps the scheduled time unless this request may run sooner.
            if (now + delay >= scheduledFor) return
            handler.removeCallbacks(runnable)
        }
        scheduled = true
        scheduledFor = now + delay
        handler.postDelayed(runnable, delay)
    }

    /**
     * Learns this device's speed from a replay of [tool] that took [ns] and started when the
     * tool's [BrushTool.dabWork] was [work0]. Replays that did little are not a fair sample
     * (their fixed costs dominate).
     */
    private fun measure(tool: BrushTool, work0: Double, ns: Long) {
        val work = tool.dabWork - work0
        if (work < MIN_SAMPLE_WORK || ns <= 0L) return
        val sample = (ns / work).coerceIn(MIN_NS_PER_UNIT, MAX_NS_PER_UNIT)
        nsPerUnit += (sample - nsPerUnit) * SPEED_SMOOTHING
    }

    /**
     * Runs a waiting replay now (tests; the looper does it otherwise). A replay never does much
     * more than [budget] of dab work: a longer re-render is a draft, and a draft on screen is
     * refined part by part on the following frames once no finger drags the path.
     */
    fun flush() {
        unschedule()
        val req = pending ?: return
        pending = null
        val (id, tool) = paintTool() ?: run { cancelLive(); return }
        val key = keyFor(req.key, id, tool)
        val cur = live
        if (cur != null && cur.key == key) {
            if (cur.exact || interacting) return
            if (tool is BrushTool && cur.tool === tool && tool.isStroking) {
                // This very path as a draft: the next part of it becomes exact.
                val t0 = SystemClock.uptimeMillis()
                val n0 = System.nanoTime()
                val w0 = tool.dabWork
                val more = tool.refinePath(budget())
                measure(tool, w0, System.nanoTime() - n0)
                live = Live(tool, key, req, exact = !tool.isDraft, last = cur.last)
                ran(t0)
                if (more) schedule(req, 0L)
                return
            }
        }
        if (cur == null && key == refusedKey) return
        refusedKey = null
        val layer = key.layer
        // The painting tool would refuse with a message on every replay: no preview instead
        // (committing still reports it).
        if (layer.locked || !layer.visible) { cancelLive(); refusedKey = key; return }
        input.clear()
        req.points(input)
        if (input.size < 2) { cancelLive(); return }
        val t0 = SystemClock.uptimeMillis()
        val n0 = System.nanoTime()
        val w0 = (tool as? BrushTool)?.dabWork ?: 0.0
        val budget = budget()
        val updated = cur != null && tool is BrushTool && cur.tool === tool && tool.isStroking &&
            cur.key.sameStroke(key) && tool.updatePath(input, budget)
        if (!updated) {
            if (interacting && cur != null && fullReplayMs > dragReplayLimitMs) {
                // Smudge / blur / watercolor strokes are replayed from scratch: when that takes
                // longer than a frame, the stroke waits for the drag to end (the guide path
                // follows the finger) instead of stalling every frame of it.
                pending = req
                deferred = true
                return
            }
            cancelLive()
            if (!start(tool, key, budget)) return
            fullReplayMs = SystemClock.uptimeMillis() - t0
        }
        if (tool is BrushTool) measure(tool, w0, System.nanoTime() - n0)
        val exact = !(tool is BrushTool && tool.isDraft)
        live = Live(tool, key, req, exact = exact, last = lastPoint(t0))
        if (!updated) onLiveChanged?.invoke()
        ran(t0)
        // Refined part by part once the path rests (not while a finger drags it).
        if (!exact && !interacting) schedule(req, refineDelay())
    }

    private fun ran(t0: Long) {
        val t1 = SystemClock.uptimeMillis()
        lastCostMs = t1 - t0
        lastRunAt = t1
    }

    /** Starts the stroke for [input] from scratch; false when the painting tool refused. */
    private fun start(tool: Tool, key: Key, budget: Float): Boolean {
        val before = controller.message
        withPreset(key.toolId) {
            clipFree(key.layer) {
                if (tool is BrushTool) tool.beginPath(input, seed, budget)
                else tool.onDown(ToolPoint(input.x[0], input.y[0], input.pressure[0], SystemClock.uptimeMillis(), isStylus = true))
            }
        }
        val msg = controller.message
        if (msg !== before && msg != null) {
            // Say it once per editing session, not on every replay.
            if (msg == shownToast) controller.message = before else shownToast = msg
        }
        if (tool is BrushTool) {
            if (!tool.isStroking) { refusedKey = key; return false }
        } else {
            val t0 = SystemClock.uptimeMillis()
            for (i in 1 until input.size) tool.onMove(ToolPoint(input.x[i], input.y[i], input.pressure[i], t0 + i, isStylus = true))
        }
        return true
    }

    private fun lastPoint(t0: Long): ToolPoint {
        val i = input.size - 1
        return ToolPoint(input.x[i], input.y[i], input.pressure[i], t0 + i, isStylus = true)
    }

    /**
     * Paints the stroke for real (one undo step made by the painting tool). When the preview on
     * screen is this stroke it is finished as is (a draft is redrawn exactly first; identical
     * result, no second pass); otherwise the live stroke follows the path first, or the path is
     * painted from scratch. Returns false if there was nothing to paint.
     */
    fun commit(geometry: Any, points: (PathStrokeInput) -> Unit): Boolean {
        pending = null
        deferred = false
        unschedule()
        val (id, tool) = paintTool() ?: run { cancelLive(); return false }
        val key = keyFor(geometry, id, tool)
        val cur = live
        if (cur != null && cur.key == key) {
            if (!cur.exact && tool is BrushTool) {
                input.clear()
                points(input)
                if (input.size >= 2) tool.updatePath(input, 0f)
            }
            live = null
            tool.onUp(cur.last.copy(time = cur.last.time + 1))
            onLiveChanged?.invoke()
            return true
        }
        input.clear()
        points(input)
        if (input.size < 2) { cancelLive(); return false }
        val t0 = SystemClock.uptimeMillis()
        if (cur != null && tool is BrushTool && cur.tool === tool && tool.isStroking && cur.key.sameStroke(key) && tool.updatePath(input, 0f)) {
            live = null
            tool.onUp(lastPoint(t0))
            onLiveChanged?.invoke()
            return true
        }
        cancelLive()
        if (tool is BrushTool) {
            if (withPreset(id) { clipFree(key.layer) { tool.beginPath(input, seed) } }) tool.onUp(lastPoint(t0))
        } else {
            withPreset(id) { clipFree(key.layer) { tool.onDown(ToolPoint(input.x[0], input.y[0], input.pressure[0], t0, isStylus = true)) } }
            for (i in 1 until input.size - 1) tool.onMove(ToolPoint(input.x[i], input.y[i], input.pressure[i], t0 + i, isStylus = true))
            tool.onUp(lastPoint(t0))
        }
        onLiveChanged?.invoke()
        return true
    }

    /** Drops the preview (the painting tool leaves no trace) and any waiting replay. */
    fun cancel() {
        pending = null
        deferred = false
        unschedule()
        cancelLive()
        refusedKey = null
    }

    /**
     * [cancel], and ends this editing session: forgets the messages shown and the random values
     * of the brush, and the finger is no longer [interacting].
     */
    fun end() {
        cancel()
        interacting = false
        shownToast = null
        seed = newSeed()
    }

    private fun cancelLive() {
        val l = live ?: return
        live = null
        l.tool.onCancel()
        onLiveChanged?.invoke()
    }

    private fun unschedule() {
        if (!scheduled) return
        handler.removeCallbacks(runnable)
        scheduled = false
    }

    internal companion object {
        /** Minimum time between two replays (one frame); 1.5 times the last replay's cost if larger. */
        private const val MIN_INTERVAL_MS = 16L
        private const val MAX_INTERVAL_MS = 250L

        /** How long a draft waits after a drag before it is refined (ms): the finger may grab again. */
        const val REFINE_DELAY_MS = 200L

        /**
         * A stroke that is replayed from scratch (smudge / blur / watercolor) and took longer
         * than this (ms) waits for the end of a drag instead of following the finger.
         */
        const val MAX_DRAG_REPLAY_MS = 16L

        /**
         * Time one replay may take (ns): about a third of a 60 Hz frame, leaving the rest for the
         * touch input and for redrawing the tiles the replay changed.
         */
        private const val TARGET_REPLAY_NS = 6_000_000.0

        /**
         * Largest work of one replay ([BrushTool.pathDabCost] units: about 500 dabs of a small
         * brush, or 2 to 3 ms on a desktop computer), also on devices faster than that.
         */
        const val MAX_BUDGET = 2_500_000f

        /** Smallest work of one replay, however slow the device seems (drafts can't go sparser). */
        const val MIN_BUDGET = 250_000f

        /** Replays that did less work than this are not used to measure the speed. */
        private const val MIN_SAMPLE_WORK = 150_000.0
        private const val MIN_NS_PER_UNIT = 0.05
        private const val MAX_NS_PER_UNIT = 50.0

        /** Weight of a new speed sample (the rest is the running average). */
        private const val SPEED_SMOOTHING = 0.3

        /**
         * Measured nanoseconds per unit of dab work on this device, shared by every preview (the
         * device doesn't change from one tool to the next). Starts at a desktop computer's speed:
         * a slow phone lowers it within a few replays.
         */
        @Volatile
        var nsPerUnit = 1.0

        /**
         * Work allowed for one replay ([BrushTool.pathDabCost] units): what this device draws in
         * about [TARGET_REPLAY_NS] (see [measure]), within [MIN_BUDGET]..[MAX_BUDGET]. Beyond it a
         * re-render is a draft, and a draft is refined by parts of this size.
         */
        fun budget(): Float = (TARGET_REPLAY_NS / nsPerUnit).toFloat().coerceIn(MIN_BUDGET, MAX_BUDGET)

        private var seeds = 0L

        private fun newSeed(): Long = System.nanoTime() xor (++seeds * -0x61c8864680b583ebL)
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
 * The band is erased from the items by stroking the path (no outline of the stroke is computed
 * on the CPU, which would cost milliseconds per frame while a long path is dragged).
 */
internal class SpecOverlay {
    private val renderer = VectorRenderer()
    private val clip = Rect()
    private val bounds = RectF()
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private val bandSource = Path()
    private var bandWidth = 0f
    private var hasBand = false

    /**
     * The live brush stroke follows [outline] (document px) with a brush [width] px wide; null
     * (or no width) removes the band.
     */
    fun setBand(outline: Path?, width: Float) {
        if (outline == null || outline.isEmpty || !width.isFinite() || width <= 0f) {
            hasBand = false
            return
        }
        bandSource.set(outline)
        bandWidth = width
        hasBand = true
    }

    /**
     * Draws [specs]; with [keepBandFree] the brush band ([setBand]) is left out of them. With
     * [asNewLayer] they are drawn as the content of a new (visible, opaque, normal) layer that
     * will be added above [layer]: not into its mask, not with its visibility or opacity. With
     * [ignoreSelection] the items are not limited to the pixel selection (objects of a vector
     * layer, v1.5).
     */
    fun draw(
        canvas: Canvas,
        t: ViewTransform,
        controller: EditorController,
        layer: Layer,
        specs: List<VectorPaintSpec>,
        keepBandFree: Boolean = false,
        asNewLayer: Boolean = false,
        ignoreSelection: Boolean = false,
    ) {
        if (specs.isEmpty() || (!asNewLayer && !layer.visible)) return
        val doc = controller.doc
        val maskMode = !asNewLayer && controller.editTargetOf(layer) == EditTarget.MASK
        clip.set(0, 0, doc.width, doc.height)
        bounds.setEmpty()
        for (s in specs) bounds.union(s.bounds)
        if (!bounds.intersect(0f, 0f, doc.width.toFloat(), doc.height.toFloat())) return
        canvas.save()
        canvas.concat(t.matrix)
        val alpha = if (asNewLayer) 255 else (layer.opacity.coerceIn(0f, 1f) * 255f).toInt()
        val band = keepBandFree && hasBand
        // The band is erased inside an isolated layer (from the items only, not the canvas).
        val save = if (alpha < 255 || band) canvas.saveLayerAlpha(bounds, alpha) else canvas.save()
        canvas.clipRect(clip)
        val sel = if (ignoreSelection) null else controller.selection
        for (s in specs) renderer.drawClipped(canvas, s, sel, false, clip, maskMode, doc.colorMode)
        if (band) {
            bandPaint.strokeWidth = bandWidth
            canvas.drawPath(bandSource, bandPaint)
        }
        canvas.restoreToCount(save)
        canvas.restore()
    }
}

/**
 * Runs [block] and folds every undo action it pushes into ONE step named [label], also when it
 * pushes a single one: a curve / shape painted with the brush is undone as "Curve" / "Shape",
 * not as the painting tool's own "Brush" step. Edit listeners hear about the edits once the
 * step is complete (inside [EditorController.editScope]).
 */
internal fun EditorController.undoStepNamed(label: String, block: () -> Unit) = editScope {
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
