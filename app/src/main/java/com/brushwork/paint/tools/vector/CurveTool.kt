package com.brushwork.paint.tools.vector

import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** How a committed curve is stroked. */
@Serializable
enum class CurveStroke(val label: String) {
    /** Drives the last painting tool (brush / smudge / blur) along the path. */
    BRUSH("Current brush"),
    /** A plain anti-aliased line of a fixed width. */
    PLAIN("Plain line"),
    NONE("No stroke"),
}

/** Persisted options of the curve / polyline tools. Lengths are document pixels. */
@Serializable
data class CurveSettings(
    val closed: Boolean = false,
    /** 0 = Catmull-Rom, 1 = straight segments. */
    val tension: Float = 0f,
    val stroke: CurveStroke = CurveStroke.BRUSH,
    val plainWidth: Float = 6f,
    val fill: Boolean = false,
    /** Fill color, or null to follow the main drawing color. */
    val fillColor: Int? = null,
    /** Brush strokes fade in/out through a pressure ramp. */
    val taper: Boolean = false,
    /** Length of each taper as a percentage of the path length. */
    val taperPercent: Float = 20f,
    val unit: LengthUnit = LengthUnit.PX,
    val nudgeStepPx: Float = 1f,
) {
    fun sanitized() = copy(
        tension = tension.coerceIn(0f, 1f),
        plainWidth = plainWidth.coerceIn(ShapeSettings.MIN_STROKE, ShapeSettings.MAX_STROKE),
        taperPercent = taperPercent.coerceIn(1f, 50f),
        nudgeStepPx = nudgeStepPx.coerceIn(0.01f, ShapeSettings.MAX_LENGTH),
    )
}

/**
 * Bezier curve tool, or polyline tool when [polyline] (all corners sharp). Tap to add anchors
 * (tapping near the path inserts one), drag anchors or tangent handles to edit, tap / long-press
 * an anchor to select it for sharp / smooth / delete / numeric editing. ✓ strokes the path with
 * the current brush or a plain line and optionally fills it.
 */
class CurveTool(controller: EditorController, val polyline: Boolean) : Tool(controller) {
    override val id = if (polyline) ToolId.POLYLINE else ToolId.CURVE

    /** Current options (Compose state); change them with [update]. */
    var settings by mutableStateOf(loadSettings())
        private set

    /** Anchors of the pending path (Compose state). */
    var anchors by mutableStateOf<List<CurveAnchor>>(emptyList())
        private set

    /** Index of the selected anchor or -1. */
    var selected by mutableIntStateOf(-1)
        private set

    /** True when [undoStep] can go back. */
    var canUndoStep by mutableStateOf(false)
        private set

    override val hasPendingWork: Boolean get() = anchors.isNotEmpty()

    private val history = ArrayDeque<List<CurveAnchor>>()
    private var targetLayer: Layer? = null
    private var preview: VectorPreview? = null
    private var previewDirty = Rect()
    private var observeJob: Job? = null

    private enum class Drag { NONE, ANCHOR, NEW_ANCHOR, HANDLE_IN, HANDLE_OUT, IGNORE }

    private var drag = Drag.NONE
    private var dragIndex = -1
    private var downPoint = Vec2.ZERO
    private var moved = false
    private var gestureStart: List<CurveAnchor> = emptyList()
    private var gestureSelected = -1
    private var gestureHistorySize = 0

    private val painter = OverlayPainter()
    private val docPath = Path()
    private val pts = FloatArray(2)

    private val tension: Float get() = if (polyline) 1f else settings.tension

    /** The current path (straight segments for the polyline tool). */
    fun path(): VectorPath = CurveGeometry.toPath(anchors, settings.closed, tension, polyline)

    // ------------------------------------------------------------------ settings

    fun update(transform: (CurveSettings) -> CurveSettings) {
        val new = transform(settings).sanitized()
        if (new == settings) return
        settings = new
        runCatching { controller.settings.putObject(prefsKey, CurveSettings.serializer(), new) }
        changed()
    }

    private val prefsKey: String get() = if (polyline) "vec.polyline" else "vec.curve"

    private fun loadSettings(): CurveSettings =
        runCatching { controller.settings.getObject(prefsKey, CurveSettings.serializer()) }.getOrNull()?.sanitized() ?: CurveSettings()

    // ------------------------------------------------------------------ editing actions

    private fun pushHistory() {
        history.addLast(anchors)
        while (history.size > MAX_HISTORY) history.removeFirst()
        canUndoStep = true
    }

    /** Steps back one anchor edit (add, move, delete, corner change). */
    fun undoStep() {
        val prev = history.removeLastOrNull() ?: return
        anchors = prev
        if (selected !in prev.indices) selected = -1
        canUndoStep = history.isNotEmpty()
        if (prev.isNotEmpty() && targetLayer == null) targetLayer = controller.doc.activeLayer
        changed()
    }

    fun select(index: Int) {
        selected = if (index in anchors.indices) index else -1
        controller.invalidateOverlay()
    }

    fun deselect() = select(-1)

    /** Makes anchor [index] a corner (sharp) or smooth. */
    fun setSharp(index: Int, sharp: Boolean) {
        val a = anchors.getOrNull(index) ?: return
        if (a.sharp == sharp && !a.hasCustomTangent) return
        pushHistory()
        replace(index, a.copy(sharp = sharp, handleIn = null, handleOut = null))
    }

    /** Drops the dragged tangent of anchor [index] and goes back to the automatic one. */
    fun resetTangent(index: Int) {
        val a = anchors.getOrNull(index) ?: return
        if (!a.hasCustomTangent) return
        pushHistory()
        replace(index, a.withAutoTangent())
    }

    fun deleteAnchor(index: Int) {
        if (index !in anchors.indices) return
        pushHistory()
        anchors = anchors.toMutableList().also { it.removeAt(index) }
        selected = -1
        changed()
    }

    /** Moves anchor [index] to [p] (numeric entry). */
    fun moveAnchor(index: Int, p: Vec2) {
        val a = anchors.getOrNull(index) ?: return
        val lim = ShapeSettings.MAX_LENGTH
        val q = Vec2(p.x.coerceIn(-lim, lim), p.y.coerceIn(-lim, lim))
        if (q == a.pos) return
        pushHistory()
        replace(index, a.moved(q))
    }

    /** Nudges the selected anchor (or the whole path when none is selected) by the nudge step. */
    fun nudge(dx: Int, dy: Int) {
        if (anchors.isEmpty()) return
        val step = settings.nudgeStepPx
        val d = Vec2(dx * step, dy * step)
        pushHistory()
        anchors = if (selected in anchors.indices) {
            anchors.mapIndexed { i, a -> if (i == selected) a.moved(a.pos + d) else a }
        } else {
            anchors.map { it.moved(it.pos + d) }
        }
        changed()
    }

    private fun replace(index: Int, a: CurveAnchor) {
        anchors = anchors.toMutableList().also { it[index] = a }
        changed()
    }

    /** Bezier handles (in, out) of anchor [i] as offsets, as currently drawn. */
    fun handlesOf(i: Int): Pair<Vec2, Vec2> = CurveGeometry.handles(anchors, i, settings.closed, tension)

    // ------------------------------------------------------------------ input

    override fun onDown(p: ToolPoint) {
        val pt = Vec2(p.x, p.y)
        downPoint = pt
        moved = false
        gestureStart = anchors
        gestureSelected = selected
        gestureHistorySize = history.size
        if (anchors.isEmpty() && !controller.checkEditable()) { drag = Drag.IGNORE; return }
        val tol = controller.docLength(HANDLE_TOUCH_DP)
        val sel = selected
        if (!polyline && sel in anchors.indices && !anchors[sel].sharp) {
            val (hIn, hOut) = handlesOf(sel)
            val a = anchors[sel].pos
            val dOut = if (hOut.length > 1e-3f) pt.distanceTo(a + hOut) else Float.MAX_VALUE
            val dIn = if (hIn.length > 1e-3f) pt.distanceTo(a + hIn) else Float.MAX_VALUE
            if (minOf(dOut, dIn) <= tol) {
                drag = if (dOut <= dIn) Drag.HANDLE_OUT else Drag.HANDLE_IN
                dragIndex = sel
                return
            }
        }
        val idx = nearestAnchor(pt, tol)
        if (idx >= 0) {
            drag = Drag.ANCHOR
            dragIndex = idx
            return
        }
        // New anchor: inserted when tapping on the path, appended otherwise.
        pushHistory()
        val hit = if (anchors.size >= 2) CurveGeometry.nearest(anchors, pt, settings.closed, tension, polyline) else null
        val list = anchors.toMutableList()
        if (hit != null && hit.distance <= tol * 0.6f) {
            dragIndex = hit.segment + 1
            val q = controller.snapToGrid(hit.point)
            list.add(dragIndex, CurveAnchor(q.x, q.y, sharp = polyline))
        } else {
            val q = controller.snapToGrid(pt)
            list.add(CurveAnchor(q.x, q.y, sharp = polyline))
            dragIndex = list.lastIndex
        }
        if (targetLayer == null) targetLayer = controller.doc.activeLayer
        anchors = list
        selected = -1
        drag = Drag.NEW_ANCHOR
        changed()
    }

    override fun onMove(p: ToolPoint) {
        val pt = Vec2(p.x, p.y)
        when (drag) {
            Drag.NONE, Drag.IGNORE -> return
            Drag.ANCHOR, Drag.NEW_ANCHOR -> {
                if (!moved && pt.distanceTo(downPoint) < controller.docLength(TOUCH_SLOP_DP)) return
                if (!moved && drag == Drag.ANCHOR) pushHistory()
                moved = true
                val a = anchors.getOrNull(dragIndex) ?: return
                replace(dragIndex, a.moved(controller.snapToGrid(pt)))
            }
            Drag.HANDLE_IN, Drag.HANDLE_OUT -> {
                val a = anchors.getOrNull(dragIndex) ?: return
                if (!moved) { pushHistory(); moved = true }
                val (hIn, hOut) = handlesOf(dragIndex)
                val v = pt - a.pos
                if (v.length < 1e-3f) return
                // Smooth anchor: the other handle stays collinear and keeps its length.
                replace(dragIndex, if (drag == Drag.HANDLE_OUT) {
                    a.copy(handleOut = v, handleIn = -v.normalized() * hIn.length)
                } else {
                    a.copy(handleIn = v, handleOut = -v.normalized() * hOut.length)
                })
            }
        }
    }

    override fun onUp(p: ToolPoint) {
        when (drag) {
            Drag.ANCHOR -> if (moved) onMove(p) else select(if (selected == dragIndex) -1 else dragIndex)
            Drag.NEW_ANCHOR, Drag.HANDLE_IN, Drag.HANDLE_OUT -> onMove(p)
            Drag.NONE, Drag.IGNORE -> {}
        }
        drag = Drag.NONE
        controller.invalidateOverlay()
    }

    override fun onCancel() {
        if (drag != Drag.NONE && drag != Drag.IGNORE) {
            anchors = gestureStart
            selected = gestureSelected
            while (history.size > gestureHistorySize) history.removeLast()
            canUndoStep = history.isNotEmpty()
            if (anchors.isEmpty()) targetLayer = null
        }
        drag = Drag.NONE
        changed()
    }

    override fun onLongPress(p: ToolPoint): Boolean {
        if (drag == Drag.ANCHOR && !moved) {
            select(dragIndex)
            drag = Drag.IGNORE // the rest of this gesture does nothing
            return true
        }
        return false
    }

    private fun nearestAnchor(p: Vec2, tol: Float): Int {
        var best = -1
        var bestD = tol
        // Later anchors win ties so the newest point is grabbed when points overlap.
        for (i in anchors.indices.reversed()) {
            val d = anchors[i].pos.distanceTo(p)
            if (d < bestD) { best = i; bestD = d }
        }
        return best
    }

    // ------------------------------------------------------------------ preview

    private fun buildSpecs(): List<VectorPaintSpec> {
        if (anchors.size < 2) return emptyList()
        val s = settings
        val path = path()
        val fill = if (s.fill && anchors.size >= 3) path else null
        val stroke = if (s.stroke == CurveStroke.PLAIN) path else null
        return listOfNotNull(
            VectorPaintSpec.build(fill, s.fillColor ?: controller.color, stroke, controller.color, s.plainWidth, LineCapStyle.ROUND, JoinStyle.ROUND),
        )
    }

    /** Rebuilds the guide path and the compositor preview (plain line / fill), then redraws. */
    private fun changed() {
        if (anchors.size >= 2) path().toAndroidPath(docPath) else docPath.rewind()
        val specs = buildSpecs()
        if (specs.isEmpty()) {
            releasePreview()
        } else {
            val layer = targetLayer ?: controller.doc.activeLayer
            val pv = preview?.takeIf { it.layer === layer } ?: run {
                releasePreview()
                VectorPreview(controller, layer).also { preview = it }
            }
            pv.specs = specs
            if (controller.renderOverride !== pv) controller.renderOverride = pv
            val dirty = pv.dirtyRect()
            val inv = Rect(previewDirty).apply { union(dirty) }
            previewDirty = dirty
            if (!inv.isEmpty) controller.invalidateDoc(inv)
        }
        controller.invalidateOverlay()
    }

    private fun releasePreview() {
        val pv = preview
        if (pv != null && controller.renderOverride === pv) controller.renderOverride = null
        preview = null
        if (!previewDirty.isEmpty) controller.invalidateDoc(Rect(previewDirty))
        previewDirty = Rect()
    }

    // ------------------------------------------------------------------ commit / discard

    override fun commit() {
        if (anchors.size < 2) { discard(); return }
        val layer = targetLayer ?: controller.doc.activeLayer
        if (controller.doc.indexOf(layer) < 0) { discard(); return }
        if (!controller.checkEditable(layer)) return
        val s = settings
        val path = path()
        val specs = buildSpecs()
        clear()
        if (specs.isNotEmpty()) {
            val label = when {
                s.stroke != CurveStroke.PLAIN -> "Fill path"
                polyline -> "Polyline"
                else -> "Curve"
            }
            VectorCommit.commit(controller, layer, specs, label)
        }
        if (s.stroke == CurveStroke.BRUSH && layer === controller.doc.activeLayer) strokeWithBrush(path, s)
        controller.invalidateOverlay()
    }

    override fun discard() {
        clear()
        controller.invalidateOverlay()
    }

    private fun clear() {
        anchors = emptyList()
        selected = -1
        history.clear()
        canUndoStep = false
        drag = Drag.NONE
        targetLayer = null
        docPath.rewind()
        releasePreview()
    }

    /**
     * Paints the path with the last painting tool through the generic Tool API: even samples,
     * stylus points so the given pressure (1, or the taper ramp) is honored.
     */
    private fun strokeWithBrush(path: VectorPath, s: CurveSettings) {
        val samples = CurveGeometry.sample(path, BRUSH_SAMPLE_SPACING)
        if (samples.size < 2) return
        val tool = controller.tools[controller.lastPaintTool] ?: return
        if (tool === this) return
        val total = VectorPath.length(samples)
        val taperLen = if (s.taper) total * s.taperPercent / 100f else 0f
        val t0 = SystemClock.uptimeMillis()
        var dist = 0f
        fun point(i: Int): ToolPoint {
            if (i > 0) dist += samples[i].distanceTo(samples[i - 1])
            val pressure = if (s.taper) CurveGeometry.taperPressure(dist, total, taperLen) else 1f
            return ToolPoint(samples[i].x, samples[i].y, pressure, t0 + i, isStylus = true)
        }
        tool.onDown(point(0))
        for (i in 1 until samples.lastIndex) tool.onMove(point(i))
        tool.onUp(point(samples.lastIndex))
    }

    override fun onActivate() {
        observeJob?.cancel()
        observeJob = controller.scope.launch {
            snapshotFlow { Triple(controller.color, controller.selection, controller.layersVersion) }
                .drop(1)
                .collect { if (anchors.size >= 2) changed() }
        }
    }

    override fun onDeactivate() {
        observeJob?.cancel()
        observeJob = null
        drag = Drag.NONE
        if (hasPendingWork) commit()
        if (hasPendingWork) discard()
        releasePreview()
    }

    // ------------------------------------------------------------------ overlay

    private fun map(t: ViewTransform, p: Vec2): FloatArray {
        pts[0] = p.x; pts[1] = p.y
        t.matrix.mapPoints(pts)
        return pts
    }

    override fun drawOverlay(canvas: Canvas, t: ViewTransform) {
        val list = anchors
        if (list.isEmpty()) return
        if (list.size >= 2) painter.path(canvas, t, docPath)
        val sel = selected
        if (!polyline && sel in list.indices && !list[sel].sharp) {
            val (hIn, hOut) = handlesOf(sel)
            val a = map(t, list[sel].pos).let { it[0] to it[1] }
            for (h in listOf(hIn, hOut)) {
                if (h.length < 1e-3f) continue
                val q = map(t, list[sel].pos + h).let { it[0] to it[1] }
                painter.line(canvas, t, a.first, a.second, q.first, q.second)
                painter.handle(canvas, t, q.first, q.second, small = true, active = true)
            }
        }
        for (i in list.indices) {
            val q = map(t, list[i].pos)
            painter.handle(canvas, t, q[0], q[1], square = list[i].sharp || polyline, active = i == sel)
        }
    }

    companion object {
        private const val MAX_HISTORY = 200
        private const val TOUCH_SLOP_DP = 6f
        private const val HANDLE_TOUCH_DP = 22f
        private const val BRUSH_SAMPLE_SPACING = 0.75f
    }
}
