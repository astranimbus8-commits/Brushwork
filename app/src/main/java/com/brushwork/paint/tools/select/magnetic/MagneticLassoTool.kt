package com.brushwork.paint.tools.select.magnetic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.diagnostics.CrashLog
import com.brushwork.paint.engine.RenderScaled
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.tools.DeletingTool
import com.brushwork.paint.tools.ObjectDeletion
import com.brushwork.paint.tools.ObjectPosition
import com.brushwork.paint.tools.PillPositionTool
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.select.LassoCommit
import com.brushwork.paint.tools.select.MagneticSettings
import com.brushwork.paint.tools.select.PersistedOption
import com.brushwork.paint.tools.select.SampleSource
import com.brushwork.paint.tools.select.SelectionOverlay
import com.brushwork.paint.ui.common.MagneticLabels18
import com.brushwork.paint.ui.common.PillLabels
import com.brushwork.paint.ui.common.StabilityLabels19
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Magnetic lasso (v1.8 item 3, design §3.4): tap points around an object; each segment between
 * two anchors follows the strongest edge between them ([LiveWire] over an [EdgeMap]).
 *
 * - A tap places an anchor, snapped (option) to the strongest edge within reach of the finger.
 *   Press and drag shows the live segment to the finger; the anchor lands where it lifts.
 * - A touch within [GRAB_DP] of an anchor drags it (its two segments are traced again); a tap on
 *   one makes it the active anchor (the X / Y pill's point, "Delete point", the pill's trash).
 * - Every add, move and delete is one in-tool step (the top row's Undo / Redo). The strip's
 *   discard drops all anchors as one step too, so Undo brings them back: the tool keeps pending
 *   work while it has steps to undo ([hasPendingWork]).
 * - Closing (tap the first anchor, the strip's close, a double tap) commits ONE selection step
 *   named "Magnetic lasso", combined by [mode], through [LassoCommit] (on a vector layer the
 *   outline selects objects).
 * - The [loupe] shows from finger-down to lift for every touch that places or drags an anchor.
 *
 * The edge map is built on a worker (one thread, the newest build wins) from the pixels Refer
 * names when the tool activates and again when they change (the source, the active layer, a layer
 * edit). Segments are traced on the same worker, newest outline first; until a segment is traced
 * (and before the map is ready) it is drawn straight and dashed.
 *
 * v1.9 (A7a step 6, R9): the pixels are rendered on the worker at the map's size
 * ([RenderScaled], at most 2048 px, on the paper), never flattened at full size on the main
 * thread. Every job is guarded: a failure of the edge map, a trace or the closing is kept as a
 * report ([CrashLog]) and said ([StabilityLabels19.ERROR_TOAST]; out of memory:
 * [MagneticLabels18.OUT_OF_MEMORY]); the tool goes back to straight segments (the touch ends, the
 * map goes and is not built again for the same pixels) and the outline still closes. Never a crash.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MagneticLassoTool(controller: EditorController) : Tool(controller), PillPositionTool, DeletingTool {
    override val id = ToolId.MAGNETIC_LASSO

    var settings: MagneticSettings by PersistedOption(controller.settings, "select.magnetic", MagneticSettings.serializer(), MagneticSettings())
        private set

    var mode by mutableStateOf(SelectionMode.REPLACE)

    internal val points = MagneticPoints()

    /** Anchors of the open outline (Compose state). */
    val anchorCount: Int get() = points.count

    /** The active anchor, -1 with none (Compose state). */
    val activeAnchor: Int get() = points.active

    /** The loupe while a finger places or drags an anchor, else null (tool state for tests; drawn in [drawOverlay]). */
    var loupe: Loupe? by mutableStateOf(null)
        private set

    /** True while a closed outline is being turned into the selection. */
    var busy by mutableStateOf(false)
        private set

    /** True once the edge map of the current pixels is built (Compose state). */
    var edgeMapReady by mutableStateOf(false)
        private set

    internal var edgeMap: EdgeMap? = null
        private set

    /** True while no edge map build and no trace is running (tests wait for it). */
    internal val idle: Boolean get() = mapJob?.isActive != true && traceJob?.isActive != true && !busy

    override val hasPendingWork: Boolean get() = points.count > 0 || points.undoCount > 0
    override val canUndoStep: Boolean get() = points.undoCount > 0
    override val canRedoStep: Boolean get() = points.redoCount > 0

    private val worker = Dispatchers.Default.limitedParallelism(1)
    /** Used on [worker] only. */
    private var wire = LiveWire()
    private var mapKey: MapKey? = null
    private var failedKey: MapKey? = null
    /**
     * Renders the pixels the edge map is built from at the map's size, on [worker] (v1.9:
     * [RenderScaled], on the paper as the canvas shows it); a test makes it run out of memory or fail.
     */
    internal var renderSource: (RenderScaled.Source) -> Bitmap = { RenderScaled.render(it, EdgeMap.MAX_SIDE, withPaper = true) }

    /** Runs on [worker] before each segment is traced (a test makes a trace fail). */
    internal var beforeTrace: () -> Unit = {}
    private var mapJob: Job? = null
    private var traceJob: Job? = null

    private var touch: Touch? = null
    /** The live segment (press and drag of a new anchor): from the last anchor to the finger. */
    private var livePath: FloatArray? = null
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var pillEdit = false
    private var pillPushed = false
    /** The outline shown until its selection has been applied. */
    private var committing: Path? = null
    private val screenPath = Path()
    private val mapped = FloatArray(2)

    /** A finger on the canvas; [picked] is the anchor that was active before it (a cancelled touch picks it again). */
    private class Touch(val grabbed: Int, val downX: Float, val downY: Float, val picked: Int) {
        var moved = false
        var x = downX
        var y = downY
    }

    /** What the edge map was built from: the Refer pixels and their version (a paint stroke, an undo changes it). */
    private data class MapKey(
        val source: SampleSource,
        val layer: Layer?,
        val bitmap: Bitmap?,
        val pixels: Long,
        val layersVersion: Int,
        val width: Int,
        val height: Int,
    )

    /** One segment to trace: anchor [index] to the next ([LIVE]: the last anchor to the finger). */
    private class Seg(val index: Int, val ax: Float, val ay: Float, val bx: Float, val by: Float)

    // ------------------------------------------------------------------ settings

    /** Changes the options: a new Refer rebuilds the edge map, a new contrast traces again. */
    fun updateSettings(s: MagneticSettings) {
        val old = settings
        if (s == old) return
        settings = s
        if (s.source != old.source) ensureEdgeMap()
        if (s.floor != old.floor) {
            points.forgetSegments()
            livePath = null
            requestTrace()
        }
        controller.invalidateOverlay()
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onActivate() {
        // A map that ran out of memory is tried again once per activation (memory may be free now).
        failedKey = null
        ensureEdgeMap()
    }

    override fun onDeactivate() {
        endTouch()
        super.onDeactivate()
        // Steps of an outline taken back to nothing end with the tool (as the lasso's do).
        if (!hasPendingWork && points.redoCount > 0) points.resetHistory()
        traceJob?.cancel()
    }

    override fun onDispose() {
        mapJob?.cancel()
        traceJob?.cancel()
        dropEdgeMap()
        mapKey = null
        failedKey = null
    }

    private fun dropEdgeMap() {
        edgeMap = null
        edgeMapReady = false
        wire = LiveWire()
    }

    /** The pixels Refer names now: the active layer's (not a folder's), else the canvas. */
    private fun currentKey(): MapKey {
        val c = controller
        val layer = c.activeLayer
        return if (settings.source == SampleSource.LAYER && layer.bitmap !== Layer.FOLDER_BITMAP) {
            MapKey(SampleSource.LAYER, layer, layer.bitmap, layer.contentVersion, 0, c.doc.width, c.doc.height)
        } else {
            var sum = 0L
            for (l in c.doc.layers) sum += l.contentVersion
            MapKey(SampleSource.CANVAS, null, null, sum, c.layersVersion, c.doc.width, c.doc.height)
        }
    }

    /**
     * Builds the edge map of the pixels Refer names, unless the one built (or building) is of
     * them as they are now: called on activation, on a new Refer and on every finger-down, so a
     * pixel undo or redo while the tool is active (it changes the layer's `contentVersion` only,
     * not `layersVersion`) or a render landing is seen.
     */
    private fun ensureEdgeMap() {
        val c = controller
        val key = currentKey()
        if (key == mapKey && (edgeMap != null || mapJob?.isActive == true)) return
        // Out of memory (or a failure) for these pixels once: segments stay straight, no new try on every touch.
        if (key == failedKey) return
        mapJob?.cancel()
        dropEdgeMap()
        points.forgetSegments()
        livePath = null
        mapKey = key
        val docW = c.doc.width
        // v1.9 (A7a): only the source is taken here (no pixel read); it is rendered on the worker.
        var source = sourceOf(key)
        mapJob = c.scope.launch {
            var tries = 0
            while (true) {
                try {
                    val map = withContext(worker) {
                        val pixels = renderSource(source)
                        try {
                            EdgeMap.build(pixels, docW) { !isActive }
                        } finally {
                            pixels.recycle()
                        }
                    }
                    if (map != null && mapKey == key) {
                        edgeMap = map
                        edgeMapReady = true
                        points.forgetSegments()
                        requestTrace()
                        c.invalidateOverlay()
                    }
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: OutOfMemoryError) {
                    if (mapKey == key) mapFailed(key, e)
                    return@launch
                } catch (e: Throwable) {
                    if (mapKey != key) return@launch
                    // A layer recycled or replaced while it was read: once more, from the pixels as they are now.
                    if (e is RuntimeException && ++tries == 1 && currentKey() == key) {
                        source = sourceOf(key)
                        continue
                    }
                    mapFailed(key, e)
                    return@launch
                }
            }
        }
    }

    /** What [key] names, to render off the main thread: the layer's pixels or the canvas. Main thread. */
    private fun sourceOf(key: MapKey): RenderScaled.Source {
        val bitmap = key.bitmap
        return if (key.source == SampleSource.LAYER && bitmap != null) RenderScaled.Source.layer(bitmap) else RenderScaled.Source.canvas(controller.doc)
    }

    /**
     * No edge map of [key]'s pixels ([e]: no memory, or a failure kept as a report): said once
     * ([MagneticLabels18.OUT_OF_MEMORY], else [StabilityLabels19.ERROR_TOAST]), and segments
     * stay straight (no new try on every touch; the next activation or other pixels try again).
     */
    private fun mapFailed(key: MapKey, e: Throwable) {
        mapKey = null
        failedKey = key
        failed(e, "edge map")
    }

    /**
     * v1.9 (A7a): a job of the tool failed with [e] at [where]: kept as a report (no personal
     * data: the place and the error's class and stack) and said once; never thrown on.
     */
    private fun failed(e: Throwable, where: String) {
        CrashLog.record(e, "magnetic lasso: $where", fatal = false)
        controller.toast(if (e is OutOfMemoryError) MagneticLabels18.OUT_OF_MEMORY else StabilityLabels19.ERROR_TOAST.format(id.label))
    }

    /**
     * v1.9 (A7a): a trace failed with [e]: the tool goes back to straight segments. The touch
     * ends (its loupe goes), the edge map goes and is not built again for these pixels (the next
     * activation or other pixels try again); the anchors and the segments traced so far stay, and
     * the outline still closes.
     */
    private fun traceFailed(e: Throwable) {
        failed(e, "trace")
        mapJob?.cancel()
        failedKey = mapKey ?: currentKey()
        mapKey = null
        dropEdgeMap()
        endTouch()
        controller.invalidateOverlay()
    }

    // ------------------------------------------------------------------ tracing

    private val floor: Int get() = settings.floor

    /** Traces what the outline misses, newest state first, one segment at a time on [worker]. */
    private fun requestTrace() {
        if (edgeMap == null || traceJob?.isActive == true) return
        traceJob = controller.scope.launch {
            try {
                while (true) {
                    val map = edgeMap ?: break
                    val seg = nextSegment() ?: break
                    val floor = floor
                    val path = withContext(worker) {
                        beforeTrace()
                        trace(map, seg, floor) { !isActive }
                    } ?: straight(seg)
                    if (map !== edgeMap || floor != this@MagneticLassoTool.floor) continue
                    if (seg.index == LIVE) {
                        if (liveEnds()?.let { same(it, seg) } == true) livePath = path
                    } else if (!points.setSegment(seg.index, seg.ax, seg.ay, seg.bx, seg.by, path)) {
                        continue
                    }
                    controller.invalidateOverlay()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                traceFailed(e)
            }
        }
    }

    /** The live segment's ends (last anchor, finger) while a finger places a new anchor. */
    private fun liveEnds(): Seg? {
        val t = touch ?: return null
        val n = points.count
        if (t.grabbed >= 0 || n == 0) return null
        return Seg(LIVE, points.x(n - 1), points.y(n - 1), t.x, t.y)
    }

    private fun same(s: Seg, o: Seg) = s.ax == o.ax && s.ay == o.ay && s.bx == o.bx && s.by == o.by

    private fun matches(path: FloatArray?, s: Seg) =
        path != null && path[0] == s.ax && path[1] == s.ay && path[path.size - 2] == s.bx && path[path.size - 1] == s.by

    /** The segment to trace next: the live one, then the active anchor's two, then the rest. */
    private fun nextSegment(): Seg? {
        liveEnds()?.let { if (!matches(livePath, it)) return it }
        val n = points.count
        val a = points.active
        for (i in listOf(a - 1, a)) if (i in 0 until n - 1 && points.segment(i) == null) return seg(i)
        for (i in 0 until n - 1) if (points.segment(i) == null) return seg(i)
        return null
    }

    private fun seg(i: Int) = Seg(i, points.x(i), points.y(i), points.x(i + 1), points.y(i + 1))

    private fun straight(s: Seg) = floatArrayOf(s.ax, s.ay, s.bx, s.by)

    /** The live wire from [s]'s first end to its second (document px, ends exact). Worker; null when cancelled. */
    private fun trace(map: EdgeMap, s: Seg, floor: Int, cancelled: () -> Boolean): FloatArray? {
        val k = map.scale
        val p = wire.path(map, (s.ax * k).toInt(), (s.ay * k).toInt(), (s.bx * k).toInt(), (s.by * k).toInt(), floor, cancelled) ?: return null
        val n = p.size / 2
        val out = FloatArray(2 * maxOf(2, n))
        out[0] = s.ax; out[1] = s.ay
        for (i in 1 until n - 1) {
            out[2 * i] = (p[2 * i] + 0.5f) / k
            out[2 * i + 1] = (p[2 * i + 1] + 0.5f) / k
        }
        out[out.size - 2] = s.bx; out[out.size - 1] = s.by
        return out
    }

    // ------------------------------------------------------------------ input

    private fun docLength(dp: Float): Float {
        val t = controller.viewTransform
        return t.screenToDocLength(t.dp(dp))
    }

    override fun onDown(p: ToolPoint) {
        if (busy) return
        // The pixels may have changed since the map was built (an undo, a layer's late render).
        ensureEdgeMap()
        val grabbed = points.nearest(p.x, p.y, docLength(GRAB_DP))
        touch = Touch(grabbed, p.x, p.y, points.active)
        if (grabbed >= 0) points.activate(grabbed)
        livePath = null
        loupe = loupeAt(p)
        requestTrace()
        controller.invalidateOverlay()
    }

    private fun loupeAt(p: ToolPoint) = Loupe.at(Vec2(p.x, p.y), controller.viewTransform, Loupe.chromeTop(controller))

    override fun onMove(p: ToolPoint) {
        val t = touch ?: return
        t.x = p.x; t.y = p.y
        loupe = loupeAt(p)
        if (!t.moved && hypot(p.x - t.downX, p.y - t.downY) >= docLength(SLOP_DP)) {
            t.moved = true
            if (t.grabbed >= 0) points.beginEdit()
        }
        if (t.moved && t.grabbed >= 0) points.move(t.grabbed, p.x, p.y)
        requestTrace()
        controller.invalidateOverlay()
    }

    override fun onUp(p: ToolPoint) {
        val t = touch ?: return
        endTouch()
        val tap = !t.moved
        if (tap && isDoubleTap(p)) {
            lastTapTime = 0L
            // The second tap of a double tap closes the outline (it never adds an anchor).
            if (points.count >= 3) commit()
            controller.invalidateOverlay()
            return
        }
        if (tap && p.time > 0L) { lastTapTime = p.time; lastTapX = p.x; lastTapY = p.y }
        val g = t.grabbed
        when {
            g >= 0 && t.moved -> {
                val q = snapped(p.x, p.y)
                points.move(g, q.x, q.y)
            }
            // A tap on the first anchor closes the outline (where the finger is, not where it snaps).
            g == 0 && points.count >= 3 -> { commit(); return }
            g >= 0 -> points.activate(g)
            else -> {
                val q = snapped(p.x, p.y)
                val n = points.count
                // An anchor snapped onto the last one isn't added twice.
                if (n == 0 || hypot(q.x - points.x(n - 1), q.y - points.y(n - 1)) > docLength(DUPLICATE_DP)) points.add(q.x, q.y)
            }
        }
        requestTrace()
        controller.invalidateOverlay()
    }

    private fun isDoubleTap(p: ToolPoint): Boolean =
        p.time > 0L && lastTapTime > 0L && p.time - lastTapTime in 0L..DOUBLE_TAP_MS &&
            hypot(p.x - lastTapX, p.y - lastTapY) <= docLength(DOUBLE_TAP_DP)

    override fun onCancel() {
        val t = touch
        if (t != null && t.grabbed >= 0) {
            if (t.moved) points.cancelEdit()
            // A cancelled touch (a second finger: a pinch, a pan, a two-finger tap) is no tap: the
            // anchor picked before it stays the one "Delete point" and the pill act on.
            points.activate(t.picked)
        }
        endTouch()
        controller.invalidateOverlay()
    }

    /** A finger held still keeps its loupe: nothing else happens. */
    override fun onLongPress(p: ToolPoint): Boolean = touch != null

    private fun endTouch() {
        touch = null
        loupe = null
        livePath = null
    }

    /**
     * ([x], [y]) moved onto the strongest edge within reach while snapping is on and the map is
     * ready: to the NEAREST edge-map pixel at least half as strong as the strongest one within
     * reach, then on across the edge to the strongest pixel within [RIDGE_PX] (the edge's centre
     * line, not its flank), the centre of that pixel. So the point moves across the edge, never
     * along it (an edge's strength varies along it in a photo, and its single strongest pixel can
     * sit anywhere in the reach). Unchanged while snapping is off or the map not ready, and where
     * no edge reaches the contrast floor.
     */
    internal fun snapped(x: Float, y: Float): Vec2 {
        val map = edgeMap
        if (!settings.snapToEdge || map == null) return Vec2(x, y)
        val k = map.scale
        val r = docLength(settings.edgeReachDp.toFloat()) * k
        val cx = x * k; val cy = y * k
        val x0 = floor(cx - r).toInt().coerceIn(0, map.width - 1); val x1 = ceil(cx + r).toInt().coerceIn(0, map.width - 1)
        val y0 = floor(cy - r).toInt().coerceIn(0, map.height - 1); val y1 = ceil(cy + r).toInt().coerceIn(0, map.height - 1)
        fun within(px: Int, py: Int): Float {
            val dx = px + 0.5f - cx; val dy = py + 0.5f - cy
            return dx * dx + dy * dy
        }
        var best = 0
        for (py in y0..y1) for (px in x0..x1) if (within(px, py) <= r * r) best = maxOf(best, map.at(px, py))
        if (best < maxOf(1, floor)) return Vec2(x, y)
        val strong = maxOf(1, floor, (best + 1) / 2)
        var bestD = Float.MAX_VALUE; var bx = -1; var by = -1
        for (py in y0..y1) for (px in x0..x1) {
            val d = within(px, py)
            if (d <= r * r && d < bestD && map.at(px, py) >= strong) { bestD = d; bx = px; by = py }
        }
        // Then onto the edge's centre line, not its flank: on across the edge (away from the finger)
        // to the strongest pixel within [RIDGE_PX]. Never along it: the strength varies along an edge.
        val ux = bx + 0.5f - cx; val uy = by + 0.5f - cy
        val len = hypot(ux, uy)
        if (len >= 0.5f) {
            var g = map.at(bx, by); var rx = bx; var ry = by
            for (step in 1..RIDGE_PX) {
                val qx = floor(bx + 0.5f + ux / len * step).toInt(); val qy = floor(by + 0.5f + uy / len * step).toInt()
                if (qx !in 0 until map.width || qy !in 0 until map.height) break
                if (map.at(qx, qy) > g) { g = map.at(qx, qy); rx = qx; ry = qy }
            }
            bx = rx; by = ry
        }
        return Vec2((bx + 0.5f) / k, (by + 0.5f) / k)
    }

    // ------------------------------------------------------------------ steps

    override fun undoStep(): Boolean {
        if (!points.undo()) return false
        requestTrace()
        return true
    }

    override fun redoStep(): Boolean {
        if (!points.redo()) return false
        requestTrace()
        return true
    }

    /** "Delete point" and the pill's trash: deletes the active anchor (one step); its neighbours are joined. */
    fun deleteActiveAnchor() {
        val i = points.active
        if (i !in 0 until points.count) return
        points.delete(i)
        requestTrace()
        controller.invalidateOverlay()
    }

    /** The strip's discard: drops every anchor as one step (Undo brings them back). */
    fun discardOutline() {
        endTouch()
        points.clear(step = true)
        controller.invalidateOverlay()
    }

    /** Throws the outline and its steps away (✕, a tool switch with fewer than three anchors). */
    override fun discard() {
        endTouch()
        points.clear(step = false)
        controller.invalidateOverlay()
    }

    /**
     * Closes the outline: ONE selection step "Magnetic lasso", combined by [mode]. The segments
     * not traced yet (the closing one usually) are traced first, on the worker. Fewer than three
     * anchors close nothing: the outline is dropped quietly.
     */
    override fun commit() {
        if (points.count < 3) { discard(); return }
        val xy = points.anchors()
        val segs = points.segments()
        val map = edgeMap
        val floor = floor
        val mode = mode
        val antiAlias = settings.antiAlias
        endTouch()
        points.clear(step = false)
        committing = outline(xy, segs, null)
        busy = true
        controller.invalidateOverlay()
        controller.scope.launch {
            val path = try {
                withContext(worker) {
                    val n = xy.size / 2
                    fun s(i: Int, j: Int) = Seg(i, xy[2 * i], xy[2 * i + 1], xy[2 * j], xy[2 * j + 1])
                    for (i in segs.indices) {
                        if (segs[i] == null) segs[i] = map?.let { beforeTrace(); trace(it, s(i, i + 1), floor) { !isActive } } ?: straight(s(i, i + 1))
                    }
                    val closing = map?.let { beforeTrace(); trace(it, s(n - 1, 0), floor) { !isActive } } ?: straight(s(n - 1, 0))
                    outline(xy, segs, closing)
                }
            } catch (e: CancellationException) {
                busy = false
                committing = null
                throw e
            } catch (e: Throwable) {
                // v1.9 (A7a): the outline as it was drawn (traced where it was, straight elsewhere) is still selected.
                failed(e, "close")
                outline(xy, segs, null)
            }
            committing = path
            try {
                LassoCommit.apply(controller, path, mode, antiAlias, ToolId.MAGNETIC_LASSO.label, onFinished = {
                    busy = false
                    committing = null
                    controller.invalidateOverlay()
                })
            } catch (e: CancellationException) {
                busy = false
                committing = null
                throw e
            } catch (e: Throwable) {
                busy = false
                committing = null
                failed(e, "select")
                controller.invalidateOverlay()
            }
        }
    }

    /**
     * The outline through anchors [xy] along [segs] (straight where null), closed along
     * [closing] (straight when null). Document px.
     */
    private fun outline(xy: FloatArray, segs: Array<FloatArray?>, closing: FloatArray?): Path {
        val path = Path()
        val n = xy.size / 2
        if (n == 0) return path
        path.moveTo(xy[0], xy[1])
        for (i in 0 until n - 1) {
            val s = segs.getOrNull(i)
            if (s == null) path.lineTo(xy[2 * i + 2], xy[2 * i + 3])
            else for (k in 1 until s.size / 2) path.lineTo(s[2 * k], s[2 * k + 1])
        }
        if (closing != null) for (k in 1 until closing.size / 2 - 1) path.lineTo(closing[2 * k], closing[2 * k + 1])
        path.close()
        return path
    }

    // ------------------------------------------------------------------ the pill

    override val pillUnit: LengthUnit get() = LengthUnit.PX

    /** The X / Y pill: the active anchor ("Point 3"); null while the outline is empty. */
    override val pillPosition: ObjectPosition = object : ObjectPosition {
        override val position: Vec2?
            get() {
                points.version
                val i = points.active
                return if (i in 0 until points.count) Vec2(points.x(i), points.y(i)) else null
            }

        override val label: String get() = "Point ${points.active + 1}"

        override fun setPosition(x: Float?, y: Float?) {
            val i = points.active
            if (i !in 0 until points.count) return
            val nx = x?.takeIf { it.isFinite() } ?: points.x(i)
            val ny = y?.takeIf { it.isFinite() } ?: points.y(i)
            if (nx == points.x(i) && ny == points.y(i)) return
            // One step per drag of the pill (or per typed value outside one).
            if (!pillEdit || !pillPushed) { points.beginEdit(); pillPushed = pillEdit }
            points.move(i, nx, ny)
            requestTrace()
            controller.invalidateOverlay()
        }

        override fun beginPositionEdit() { pillEdit = true; pillPushed = false }

        override fun endPositionEdit() { pillEdit = false; pillPushed = false }
    }

    private val deletion = object : ObjectDeletion {
        override val deleteLabel: String? get() = if (points.count > 0) PillLabels.DELETE_POINTS else null
        override fun delete() = deleteActiveAnchor()
    }

    /** The pill's trash: the active anchor, while the outline has one. */
    override val objectDeletion: ObjectDeletion? get() = if (points.count > 0) deletion else null

    // ------------------------------------------------------------------ overlay

    private val solidHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = -1; strokeJoin = Paint.Join.ROUND }
    private val solidInk = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF1E88E5.toInt(); strokeJoin = Paint.Join.ROUND }
    private val reachRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x80FFFFFF.toInt() }
    private val reachInk = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x60000000 }

    override fun drawOverlay(canvas: Canvas, t: ViewTransform) {
        committing?.let { SelectionOverlay.drawDocPath(canvas, t, it) }
        drawOutline(canvas, t)
        val tc = touch
        if (tc != null && settings.snapToEdge) {
            // The faint ring of the snap's reach around the finger (under the loupe).
            map(t, tc.x, tc.y)
            val r = t.dp(settings.edgeReachDp.toFloat())
            reachRing.strokeWidth = t.dp(2f); reachInk.strokeWidth = t.dp(1f)
            canvas.drawCircle(mapped[0], mapped[1], r, reachRing)
            canvas.drawCircle(mapped[0], mapped[1], r, reachInk)
        }
        loupe?.draw(canvas, t, controller.compositor, controller.doc.width, controller.doc.height) { drawOutline(it, t, inLoupe = true) }
    }

    /**
     * The anchors, their segments (traced: solid; not yet: straight and dashed) and the live
     * segment, in view px. [inLoupe]: magnified by the loupe, so thinner lines and no handles.
     */
    private fun drawOutline(canvas: Canvas, t: ViewTransform, inLoupe: Boolean = false) {
        val n = points.count
        val w = if (inLoupe) 1f / Loupe.MAGNIFICATION else 1f
        solidHalo.strokeWidth = t.dp(3f) * w
        solidInk.strokeWidth = t.dp(1.5f) * w
        for (i in 0 until n - 1) {
            val s = points.segment(i)
            if (s != null) drawSolid(canvas, t, s) else drawDashed(canvas, t, points.x(i), points.y(i), points.x(i + 1), points.y(i + 1))
        }
        liveEnds()?.let { live ->
            val p = livePath
            if (matches(p, live)) drawSolid(canvas, t, p!!) else drawDashed(canvas, t, live.ax, live.ay, live.bx, live.by)
        }
        if (inLoupe) return
        for (i in 0 until n) {
            map(t, points.x(i), points.y(i))
            SelectionOverlay.drawVertex(canvas, t, mapped[0], mapped[1], highlighted = i == points.active || (i == 0 && n >= 3))
        }
    }

    private fun drawSolid(canvas: Canvas, t: ViewTransform, s: FloatArray) {
        screenPath.reset()
        for (k in 0 until s.size / 2) {
            map(t, s[2 * k], s[2 * k + 1])
            if (k == 0) screenPath.moveTo(mapped[0], mapped[1]) else screenPath.lineTo(mapped[0], mapped[1])
        }
        canvas.drawPath(screenPath, solidHalo)
        canvas.drawPath(screenPath, solidInk)
    }

    private fun drawDashed(canvas: Canvas, t: ViewTransform, ax: Float, ay: Float, bx: Float, by: Float) {
        screenPath.reset()
        map(t, ax, ay); screenPath.moveTo(mapped[0], mapped[1])
        map(t, bx, by); screenPath.lineTo(mapped[0], mapped[1])
        SelectionOverlay.drawScreenPath(canvas, t, screenPath)
    }

    private fun map(t: ViewTransform, x: Float, y: Float) {
        mapped[0] = x; mapped[1] = y
        t.matrix.mapPoints(mapped)
    }

    /** True when segment [i] (anchor [i] to the next) is traced; untraced ones are drawn straight and dashed. */
    internal fun isTraced(i: Int): Boolean = points.segment(i) != null

    companion object {
        /** A touch this close (dp) to an anchor drags it. */
        const val GRAB_DP = 24f
        /** A touch on an anchor must move this far (dp) before it drags it. */
        private const val SLOP_DP = 6f
        /** An anchor snapped this close (dp) to the last one isn't added again. */
        private const val DUPLICATE_DP = 3f
        /** Two taps this quick (ms) and this close (dp) are a double tap: it closes the outline. */
        const val DOUBLE_TAP_MS = 300L
        const val DOUBLE_TAP_DP = 32f
        private const val LIVE = -1
        /** A snapped anchor moves at most this many edge-map px on across an edge, from its flank to its centre line. */
        private const val RIDGE_PX = 2
    }
}
