package com.brushwork.paint.tools.vector

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.brushwork.paint.ColorModeOps
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushPreset
import com.brushwork.paint.brush.BrushPresetStore
import com.brushwork.paint.brush.PathStrokeInput
import com.brushwork.paint.brush.StrokeKind
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.brush.sanitized
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.engine.LayerRenderOverride
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.select.pointBox
import com.brushwork.paint.tools.select.pointLines
import com.brushwork.paint.tools.select.snapPointToObjects
import com.brushwork.paint.tools.transform.SnapGuide
import com.brushwork.paint.vector.VFillRule
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStrokeKind
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.edit.VectorEditSession
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

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
    /**
     * The plain line is as wide as the current brush (v1.5, like `ShapeSettings.useBrushSize`);
     * false uses [plainWidth]. Stored settings without the field take this default.
     */
    val useBrushSize: Boolean = true,
) {
    /** Clamps every value to its supported range; non-finite values are taken from [fallback]. */
    fun sanitized(fallback: CurveSettings = DEFAULT) = copy(
        tension = tension.finiteOr(fallback.tension).coerceIn(0f, 1f),
        plainWidth = plainWidth.finiteOr(fallback.plainWidth).coerceIn(ShapeSettings.MIN_STROKE, ShapeSettings.MAX_STROKE),
        taperPercent = taperPercent.finiteOr(fallback.taperPercent).coerceIn(1f, 50f),
        nudgeStepPx = nudgeStepPx.finiteOr(fallback.nudgeStepPx).coerceIn(0.01f, ShapeSettings.MAX_LENGTH),
    )

    companion object {
        private val DEFAULT = CurveSettings()
    }
}

/**
 * Bezier curve tool, or polyline tool when [polyline] (all corners sharp). Tap to add anchors
 * (tapping near the path inserts one), drag any anchor or tangent handle to edit it, tap /
 * long-press an anchor to select it for sharp / smooth / delete / numeric editing. Undo (the
 * app's undo button and two-finger tap included) takes back one anchor edit at a time, redo
 * brings it back. With "Current brush" the painting tool's real stroke is shown live while the
 * path is edited; ✓ paints it (plus the optional fill) as one undo step.
 *
 * "Snap to objects" (the app-wide setting): a new anchor (also one inserted on the path, and one
 * just tapped) and a dragged anchor (once it moved past the touch slop, so a tap never moves it)
 * snap per axis to the canvas edges and center, the selection, other layers' content bounds, the
 * lines drawn in layers (Table filter lines...), shape vertices and the path's other anchors
 * (lining up with them or landing on them), with magenta guides while the finger is down; an axis
 * that didn't snap follows the square grid when grid snapping is on (exactly as before when
 * snapping is off). Tangent handle ends snap to the same targets, their own anchor included.
 *
 * v1.5 (§4.4, §4.5, §4.9):
 * - The plain line is as wide as the current brush while "Use brush size" is on (the default):
 *   the side size slider resizes it live ([lineWidth]).
 * - Every anchor has a thickness factor ([CurveAnchor.width], 0–300 %, [setWidth]) that blends
 *   with smoothstep along the arc length to its neighbours: a brush gets it as pressure (its
 *   size scaled up to the thickest point, size following pressure, opacity not), a plain line
 *   becomes a [VariableWidthOutline]. At 100 % everywhere everything is drawn exactly as before.
 * - On a vector layer (painting its content) ✓ adds the path as a [VPath] object (anchors,
 *   handles, widths, plain or brush stroke with its brush and texture seed, fill): a live brush
 *   stroke keeps its pixels (they are the replay's), anything else is rendered by the layer. With
 *   no path pending, tapping a path object of the layer reopens it ([reopen]): its own look
 *   (width unlinked, its colors, its brush) is edited, ✓ replaces it ("Edit path"), ✕ leaves it
 *   as it was. A brush that needs pixels (smudge, blur, watercolor) draws a plain line there.
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
    override var canUndoStep by mutableStateOf(false)
        private set

    /** Number of edits [redoStep] can bring back (Compose state; the in-tool redo button). */
    var redoCount by mutableIntStateOf(0)
        private set

    override val hasPendingWork: Boolean get() = anchors.isNotEmpty()

    /** A reopened path nobody changed yet is not the user's work: undo closes it and goes on (Compose state). */
    override val hasUserChanges: Boolean get() = anchors.isNotEmpty() && !(reopenedState && pristine)

    // The app's redo only reaches the tool while a path is pending (see EditorController.redo).
    override val canRedoStep: Boolean get() = redoCount > 0 && anchors.isNotEmpty()

    /** True while a path object of a vector layer is reopened for editing (Compose state). */
    val isReopened: Boolean get() = reopenedState

    /**
     * Shows a ring of the real line diameter at the selected anchor (set while its thickness
     * slider is dragged).
     */
    var thicknessRing by mutableStateOf(false)

    private val history = ArrayDeque<List<CurveAnchor>>()
    private val redo = ArrayDeque<List<CurveAnchor>>()
    private var historyKey: Any? = null
    private var historyKeyTime = 0L
    private data class NumericKey(val kind: String, val index: Int)

    /** Time source for coalescing numeric edits (replaceable in tests). */
    internal var clock: () -> Long = { SystemClock.uptimeMillis() }
    private var targetLayer: Layer? = null
    private val preview = PreviewHost(controller)
    private val brushPreview = BrushStrokePreview(controller, presetOverride = { brushOverride }, paintToolId = { brushToolId() })
    private val specOverlay = SpecOverlay()
    /** Plain items drawn in the overlay while the brush preview owns the render override (the fill). */
    private var overlaySpecs: List<VectorPaintSpec> = emptyList()
    private var observeJob: Job? = null

    /** The brush the live stroke is painted with instead of the painting tool's own (thickness, a reopened path's own brush). */
    private var brushOverride: BrushPreset? = null

    private enum class Drag { NONE, ANCHOR, NEW_ANCHOR, HANDLE_IN, HANDLE_OUT, REOPEN, IGNORE }

    private var drag = Drag.NONE
    private var dragIndex = -1
    private var downPoint = Vec2.ZERO
    private var moved = false
    /** The current touch long-pressed an anchor and selected it (lifting keeps it selected). */
    private var longPressed = false
    private var gestureStart: List<CurveAnchor> = emptyList()
    private var gestureSelected = -1
    private var gestureHistorySize = 0
    private var gestureRedo: List<List<CurveAnchor>> = emptyList()
    /** The path object a tap (no pending path) would reopen. */
    private var reopenCandidate: VPath? = null

    private val painter = OverlayPainter()
    private val docPath = Path()
    private val pts = FloatArray(2)

    /**
     * "Snap to objects" for the dragged / new anchor and tangent handle: the canvas, the selection,
     * other layers' content and drawn lines (Table filter lines...), shape vertices, the grid and
     * the path's other anchors. One per tool, begun on every touch.
     */
    private val snap = controller.newSnapSession()

    /** Where the dragged point snapped to (keeps the guide labels off it), or null. */
    private var snapMoving: Vec2? = null

    /** Guides shown right now (document px); empty when nothing is aligned. */
    internal val activeGuides: List<SnapGuide> get() = snap.guides

    private val tension: Float get() = if (polyline) 1f else settings.tension

    /** The current path (straight segments for the polyline tool). */
    fun path(): VectorPath = CurveGeometry.toPath(anchors, settings.closed, tension, polyline)

    init {
        // Strokes of paths that become vector objects are neither clipped by the selection nor
        // by alpha lock (the objects aren't): the live pixels are then exactly the replay's.
        brushPreview.unclipped = { vectorTarget(targetLayer ?: controller.doc.activeLayer) }
        brushPreview.onLiveChanged = { onBrushLiveChanged() }
    }

    // ------------------------------------------------------------------ settings

    fun update(transform: (CurveSettings) -> CurveSettings) {
        val new = transform(settings).sanitized(fallback = settings)
        if (new == settings) return
        settings = new
        // While a path object is reopened the strip shows ITS look: only the unit and the nudge
        // step are the user's own settings then.
        val user = reopened?.userSettings
        val toSave = if (user != null) user.copy(unit = new.unit, nudgeStepPx = new.nudgeStepPx) else new
        reopened?.let { it.userSettings = toSave }
        runCatching { controller.settings.putObject(prefsKey, CurveSettings.serializer(), toSave) }
        changed()
    }

    private val prefsKey: String get() = if (polyline) "vec.polyline" else "vec.curve"

    private fun loadSettings(): CurveSettings =
        runCatching { controller.settings.getObject(prefsKey, CurveSettings.serializer()) }.getOrNull()?.sanitized() ?: CurveSettings()

    /**
     * Width of the plain line (document px): the size of the current painting tool's brush while
     * "Use brush size" is on, else [CurveSettings.plainWidth]; a reopened path's own width.
     */
    val lineWidth: Float
        get() {
            reopened?.width?.let { return it }
            val s = settings
            if (!s.useBrushSize) return s.plainWidth
            val size = controller.presetFor(controller.lastPaintTool)?.size
            return if (size != null && size.isFinite() && size > 0f) size else s.plainWidth
        }

    /** True while the plain line follows the brush size (Compose state). */
    val widthLinked: Boolean get() = reopenedState.not() && settings.useBrushSize

    private var brushSizeEdited = false

    /**
     * Sets the plain line's width: while it follows the brush size, the brush is resized (like
     * the side slider); otherwise (and for a reopened path) the line's own width.
     */
    fun setLineWidth(v: Float) {
        if (!v.isFinite()) return
        val w = v.coerceIn(ShapeSettings.MIN_STROKE, ShapeSettings.MAX_STROKE)
        val r = reopened
        if (r != null) {
            r.width = null
            if (settings.plainWidth == w) changed() else update { it.copy(plainWidth = w) }
            return
        }
        val tool = controller.lastPaintTool
        val preset = controller.presetFor(tool)
        if (settings.useBrushSize && preset != null) {
            if (preset.size != w) controller.updatePreset(tool, preset.copy(size = w).sanitized())
            brushSizeEdited = true
            changed()
        } else {
            update { it.copy(plainWidth = w) }
        }
    }

    /** Saves a brush size changed through [setLineWidth] (call when the editing ends: a sheet closes). */
    fun persistBrushSize() {
        if (!brushSizeEdited) return
        brushSizeEdited = false
        runCatching { BrushPresetStore.get(controller.appContext).persist(controller, controller.lastPaintTool) }
    }

    // ------------------------------------------------------------------ editing actions

    /**
     * Saves the anchors for [undoStep] (and drops the redo steps: this is a new edit).
     * Consecutive edits with the same non-null [key] that follow each other quickly (typing a
     * coordinate, holding a nudge arrow) share one step.
     */
    private fun pushHistory(key: Any? = null) {
        val now = if (key != null) clock() else 0L
        val coalesce = key != null && key == historyKey && history.isNotEmpty() && now - historyKeyTime <= COALESCE_MS
        historyKey = key
        historyKeyTime = now
        clearRedo()
        if (coalesce) return
        history.addLast(anchors)
        while (history.size > MAX_HISTORY) history.removeFirst()
        canUndoStep = true
    }

    private fun clearRedo() {
        if (redo.isEmpty()) return
        redo.clear()
        redoCount = 0
    }

    /**
     * A slider drag, a held arrow or a typed value is complete: the next numeric edit (of any
     * point) is a new undo step even when it follows at once.
     */
    fun endNumericEdit() {
        historyKey = null
    }

    /**
     * Steps back one anchor edit (add, move, delete, corner change). Also used by the app's undo
     * (button / two-finger tap) so it takes back the last point instead of the whole curve.
     */
    override fun undoStep(): Boolean {
        val prev = history.removeLastOrNull() ?: return false
        redo.addLast(anchors)
        redoCount = redo.size
        historyKey = null
        restore(prev)
        canUndoStep = history.isNotEmpty()
        return true
    }

    /** Brings back the edit last taken back by [undoStep] (the app's redo, or the in-tool button). */
    override fun redoStep(): Boolean {
        val next = redo.removeLastOrNull() ?: return false
        redoCount = redo.size
        history.addLast(anchors)
        while (history.size > MAX_HISTORY) history.removeFirst()
        canUndoStep = true
        historyKey = null
        restore(next)
        return true
    }

    private fun restore(list: List<CurveAnchor>) {
        anchors = list
        if (selected !in list.indices) selected = -1
        if (list.isEmpty()) targetLayer = null
        else if (targetLayer == null) targetLayer = controller.doc.activeLayer
        changed()
    }

    /**
     * Appends an anchor at [p] and selects it (numeric entry). Returns false when the active
     * layer can't be edited.
     */
    fun addAnchor(p: Vec2): Boolean {
        if (!p.x.isFinite() || !p.y.isFinite()) return false
        if (anchors.isEmpty() && !controller.checkEditable()) return false
        val lim = ShapeSettings.MAX_LENGTH
        pushHistory()
        if (targetLayer == null) targetLayer = controller.doc.activeLayer
        anchors = anchors + CurveAnchor(p.x.coerceIn(-lim, lim), p.y.coerceIn(-lim, lim), sharp = polyline)
        selected = anchors.lastIndex
        changed()
        return true
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
        if (anchors.isEmpty()) {
            // Every point of a reopened path deleted: the path object goes (one undo step).
            if (reopened != null) { commitReopened(); return }
            targetLayer = null
        }
        changed()
    }

    /** Moves anchor [index] to [p] (numeric entry; edits of the same anchor share one undo step). */
    fun moveAnchor(index: Int, p: Vec2) {
        val a = anchors.getOrNull(index) ?: return
        if (!p.x.isFinite() || !p.y.isFinite()) return
        val lim = ShapeSettings.MAX_LENGTH
        val q = Vec2(p.x.coerceIn(-lim, lim), p.y.coerceIn(-lim, lim))
        if (q == a.pos) return
        pushHistory(NumericKey("move", index))
        replace(index, a.moved(q))
    }

    /**
     * Sets the thickness factor of anchor [index] (0..3 = 0–300 %, §4.5). A slider drag (or a run
     * of changes of the same point) is one undo step; [endNumericEdit] ends it.
     */
    fun setWidth(index: Int, factor: Float) {
        val a = anchors.getOrNull(index) ?: return
        if (!factor.isFinite()) return
        val w = factor.coerceIn(0f, CurveWidths.MAX_FACTOR)
        if (w == a.width) return
        pushHistory(NumericKey("thickness", index))
        replace(index, a.copy(width = w))
    }

    /** Puts every anchor back to 100 % thickness (one undo step). */
    fun resetAllWidths() {
        if (CurveGeometry.isUniformWidth(anchors)) return
        pushHistory()
        anchors = anchors.map { if (it.width == 1f) it else it.copy(width = 1f) }
        changed()
    }

    /**
     * Nudges the selected anchor (or the whole path when none is selected) by the nudge step.
     * A run of nudges of the same target is one undo step.
     */
    fun nudge(dx: Int, dy: Int) {
        if (anchors.isEmpty()) return
        val step = settings.nudgeStepPx
        val d = Vec2(dx * step, dy * step)
        pushHistory(NumericKey("nudge", selected))
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

    /**
     * Diameter (document px) the line has at anchor [index]: the plain line's width or the brush
     * size, times the anchor's thickness.
     */
    fun diameterAt(index: Int): Float {
        val a = anchors.getOrNull(index) ?: return 0f
        val base = if (strokeMode(targetLayer ?: controller.doc.activeLayer) == CurveStroke.BRUSH) baseBrush()?.size ?: lineWidth else lineWidth
        return base * CurveWidths.factor(a.width)
    }

    // ------------------------------------------------------------------ input

    override fun onDown(p: ToolPoint) {
        val pt = Vec2(p.x, p.y)
        downPoint = pt
        moved = false
        longPressed = false
        gestureStart = anchors
        gestureSelected = selected
        gestureHistorySize = history.size
        gestureRedo = redo.toList()
        reopenCandidate = null
        snap.end()
        snapMoving = null
        if (anchors.isEmpty() && !controller.checkEditable()) { drag = Drag.IGNORE; return }
        val tol = controller.docLength(HANDLE_TOUCH_DP)
        val sel = selected
        if (!polyline && sel in anchors.indices && !anchors[sel].sharp) {
            val (hIn, hOut) = handlesOf(sel)
            val a = anchors[sel].pos
            val dOut = if (hOut.length > 1e-3f) pt.distanceTo(a + hOut) else Float.MAX_VALUE
            val dIn = if (hIn.length > 1e-3f) pt.distanceTo(a + hIn) else Float.MAX_VALUE
            // The anchor itself wins when the finger is closer to it than to its handles.
            if (minOf(dOut, dIn) <= tol && minOf(dOut, dIn) < pt.distanceTo(a)) {
                drag = if (dOut <= dIn) Drag.HANDLE_OUT else Drag.HANDLE_IN
                dragIndex = sel
                // Its own anchor is a target too: the tangent then lies level or upright.
                beginSnap(except = -1)
                return
            }
        }
        val idx = nearestAnchor(pt, tol)
        if (idx >= 0) {
            // Every existing point can be grabbed and moved at any time (it snaps once it moves).
            drag = Drag.ANCHOR
            dragIndex = idx
            beginSnap(except = idx)
            return
        }
        if (anchors.isEmpty()) {
            // A tap on a path object of the vector layer reopens it (a drag starts a new path).
            reopenablePathAt(pt)?.let { path ->
                reopenCandidate = path
                drag = Drag.REOPEN
                return
            }
        }
        newAnchorAt(pt, tol)
    }

    /** Adds a new anchor under the finger at [pt]: inserted when on the path, appended otherwise. */
    private fun newAnchorAt(pt: Vec2, tol: Float) {
        // New anchor: inserted when tapping on the path, appended otherwise. It snaps right away
        // (to objects, the other anchors and, with grid snapping, the grid).
        beginSnap(except = -1)
        pushHistory()
        val hit = if (anchors.size >= 2) CurveGeometry.nearest(anchors, pt, settings.closed, tension, polyline) else null
        val list = anchors.toMutableList()
        if (hit != null && hit.distance <= tol * 0.6f) {
            dragIndex = hit.segment + 1
            val q = snapAnchor(hit.point)
            // An inserted point takes the thickness the line has there (the line keeps its look).
            list.add(dragIndex, CurveAnchor(q.x, q.y, sharp = polyline, width = insertedWidth(hit)))
        } else {
            val q = snapAnchor(pt)
            list.add(CurveAnchor(q.x, q.y, sharp = polyline, width = anchors.lastOrNull()?.width ?: 1f))
            dragIndex = list.lastIndex
        }
        if (targetLayer == null) targetLayer = controller.doc.activeLayer
        anchors = list
        selected = -1
        drag = Drag.NEW_ANCHOR
        // The first finger of a two-finger tap (undo) or pinch (zoom) lands here too, and the
        // point goes away again when the second finger cancels this touch: the brush stroke waits
        // a moment so it doesn't flash to that point (and cost a replay) every time.
        changed(brushDelayMs = NEW_POINT_BRUSH_DELAY_MS)
    }

    /** Thickness of the line where [hit] lies (smoothstep between the segment's anchors). */
    private fun insertedWidth(hit: CurveHit): Float {
        val a = anchors.getOrNull(hit.segment) ?: return 1f
        val b = anchors.getOrNull((hit.segment + 1) % anchors.size) ?: return a.width
        if (a.width == b.width) return a.width
        return CurveWidths.clamp(CurveGeometry.widthAt(anchors, hit.segment, hit.t))
    }

    override fun onMove(p: ToolPoint) {
        val pt = Vec2(p.x, p.y)
        when (drag) {
            Drag.NONE, Drag.IGNORE -> return
            Drag.REOPEN -> {
                if (pt.distanceTo(downPoint) < controller.docLength(TOUCH_SLOP_DP)) return
                // Not a tap: a new path starts where the finger went down, as anywhere else.
                reopenCandidate = null
                newAnchorAt(downPoint, controller.docLength(HANDLE_TOUCH_DP))
                onMove(p)
            }
            Drag.ANCHOR, Drag.NEW_ANCHOR -> {
                if (!moved && pt.distanceTo(downPoint) < controller.docLength(TOUCH_SLOP_DP)) return
                if (!moved && drag == Drag.ANCHOR) pushHistory()
                moved = true
                // The preview follows the finger as cheaply as possible until it lifts.
                setDragging(true)
                val a = anchors.getOrNull(dragIndex) ?: return
                // What the finger alone gives is snapped (never the last snapped place), so moving
                // farther than the snap distance lets go of a guide.
                replace(dragIndex, a.moved(snapAnchor(pt)))
            }
            Drag.HANDLE_IN, Drag.HANDLE_OUT -> {
                val a = anchors.getOrNull(dragIndex) ?: return
                if (!moved) {
                    if (pt.distanceTo(downPoint) < controller.docLength(TOUCH_SLOP_DP)) return
                    pushHistory(); moved = true
                    setDragging(true)
                }
                val (hIn, hOut) = handlesOf(dragIndex)
                // The handle's end snaps to objects and anchors (never to the grid, as before).
                val end = snap.snapPointToObjects(pt)
                var v = end - a.pos
                snapMoving = end
                if (v.length < 1e-3f) {
                    // Snapped onto its own anchor: no tangent there, follow the finger instead.
                    snap.clearGuides()
                    snapMoving = null
                    v = pt - a.pos
                }
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
            Drag.ANCHOR -> when {
                moved -> onMove(p)
                !longPressed -> select(if (selected == dragIndex) -1 else dragIndex)
            }
            Drag.NEW_ANCHOR -> {
                onMove(p)
                // A tap: the point stays, so its brush stroke can show right away.
                if (!moved) changed()
            }
            Drag.HANDLE_IN, Drag.HANDLE_OUT -> if (moved) onMove(p)
            Drag.REOPEN -> {
                val path = reopenCandidate
                reopenCandidate = null
                drag = Drag.NONE
                if (path != null) reopen(path)
            }
            Drag.NONE, Drag.IGNORE -> {}
        }
        drag = Drag.NONE
        endSnap()
        // The drag is over: a plain line / fill goes back into the layer, a brush stroke is drawn
        // exactly once the path rests a moment.
        setDragging(false)
        controller.invalidateOverlay()
    }

    override fun onCancel() {
        setDragging(false)
        reopenCandidate = null
        if (drag != Drag.NONE && drag != Drag.IGNORE && drag != Drag.REOPEN) {
            anchors = gestureStart
            selected = gestureSelected
            while (history.size > gestureHistorySize) history.removeLast()
            redo.clear(); redo.addAll(gestureRedo)
            redoCount = redo.size
            historyKey = null
            canUndoStep = history.isNotEmpty()
            if (anchors.isEmpty() && reopened == null) targetLayer = null
        }
        drag = Drag.NONE
        endSnap()
        changed()
    }

    /**
     * A long press on an anchor selects it (its sharp / smooth / delete actions appear) and the
     * finger can still drag it; on a tangent handle it keeps the drag going. Elsewhere it is left
     * to the controller (color picking).
     */
    override fun onLongPress(p: ToolPoint): Boolean = when (drag) {
        Drag.ANCHOR -> {
            if (!moved) {
                select(dragIndex)
                longPressed = true
            }
            true
        }
        Drag.HANDLE_IN, Drag.HANDLE_OUT -> true
        else -> false
    }

    /**
     * A finger starts / stops dragging the path: while it drags, the brush stroke follows as a
     * light draft and a plain line / fill is drawn in the overlay (no canvas recomposition).
     */
    private fun setDragging(on: Boolean) {
        brushPreview.interacting = on
        preview.interacting = on
    }

    /**
     * Starts snapping for this touch. The anchors (all but [except]) are point targets as they
     * are now (a copy: the dragged point never becomes its own target when targets are rebuilt);
     * the selection and other objects are found by the snapping service.
     */
    private fun beginSnap(except: Int) {
        val others = ArrayList<Vec2>(anchors.size)
        anchors.forEachIndexed { i, a -> if (i != except) others += a.pos }
        snap.begin(includeSelection = true) { pointLines(others, POINT_LABEL) }
    }

    /**
     * An anchor at [p]: snapped to objects / the other anchors when "Snap to objects" is on, and
     * on axes that didn't snap to the square grid when grid snapping is on (with snapping off it
     * is exactly the old grid snap).
     */
    private fun snapAnchor(p: Vec2): Vec2 = snap.snapPoint(p).also { snapMoving = it }

    private fun endSnap() {
        snapMoving = null
        snap.end()
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

    // ------------------------------------------------------------------ vector layers (v1.5)

    /** True when what this tool draws on [layer] becomes an object of it (a vector layer's content). */
    private fun vectorTarget(layer: Layer): Boolean =
        layer.isVectorLayer && controller.editTargetOf(layer) == EditTarget.CONTENT

    /** A path object of the active vector layer under [p] that this tool can reopen. */
    private fun reopenablePathAt(p: Vec2): VPath? {
        if (opening) return null
        val layer = controller.doc.activeLayer
        if (!vectorTarget(layer) || layer.locked || !layer.visible) return null
        val hit = controller.vectors.hitTest(layer, p, controller.docLength(PATH_HIT_DP)) as? VPath ?: return null
        return hit.takeIf { canReopen(it) }
    }

    /** True for a single-subpath path of this tool's kind (curves in the Curve tool, polylines in the Polyline tool). */
    private fun canReopen(p: VPath): Boolean =
        p.isCurveEditable && p.polyline == polyline && p.subpaths[0].anchors.size >= 2

    /** A path object being edited again (see [reopen]). */
    private class Reopened(
        val layer: Layer,
        val session: VectorEditSession,
        val original: VPath,
        /** The user's own settings, back when the path closes (unit / nudge step follow edits). */
        var userSettings: CurveSettings,
        val userColor: Int,
        val openedColor: Int,
        /** The path's plain line width until the user sets one (exact, also outside the settings' range). */
        var width: Float?,
    )

    private var reopened: Reopened? = null
    private var reopenedState by mutableStateOf(false)
    /** The reopened path is exactly as it was (Compose state). */
    private var pristine by mutableStateOf(true)
    /** A reopen waits for the vector layers' edit session. */
    private var opening = false
    /** True while ✓ applies the path (live-stroke callbacks are ignored then). */
    private var inCommit = false
    /** Document regions the reopened preview drew last (redrawn when it changes). */
    private var sessionRegions: List<Rect> = emptyList()
    private var sessionSpecs: List<VectorPaintSpec> = emptyList()
    private var sessionGradient: VPath? = null
    private val renderer = VectorRenderer()
    /** Brush tips of the layer renderer (fills and gradients only draw paths: it stays tiny). */
    private var tipCache: TipCache? = null
    private val tips: TipCache get() = tipCache ?: TipCache(1L shl 20).also { tipCache = it }

    /**
     * Reopens the path object [path] of the active vector layer (a single sub-path of this tool's
     * kind): its anchors become the pending path (handles and thickness kept), the strip shows
     * its look, the main color becomes its line color, and the layer shows everything but it
     * until ✓ / ✕. Returns false when it can't be edited (another path is pending, the layer is
     * locked or hidden...).
     */
    fun reopen(path: VPath): Boolean {
        if (anchors.isNotEmpty() || opening || !canReopen(path)) return false
        val layer = controller.doc.activeLayer
        if (!vectorTarget(layer) || layer.vector?.byId(path.id) == null) return false
        if (!controller.checkEditable(layer)) return false
        opening = true
        // (The edit session may be prepared in the background: the path opens when it is ready,
        // unless a new path was started or the tool left meanwhile.)
        controller.vectors.beginEdit(layer, setOf(path.id)) { session ->
            val wanted = opening && anchors.isEmpty() && controller.currentTool === this && controller.doc.activeLayer === layer
            opening = false
            if (session == null) return@beginEdit
            if (!wanted) { session.cancel(); return@beginEdit }
            open(session, layer, path)
        }
        return reopened != null || opening
    }

    private fun open(session: VectorEditSession, layer: Layer, path: VPath) {
        val st = path.stroke
        val user = settings
        val strokeColor = st?.color ?: (path.fill as? VPaint.Solid)?.color ?: controller.color
        reopened = Reopened(layer, session, path, user, controller.color, strokeColor, st?.takeIf { it.kind == VStrokeKind.PLAIN }?.width)
        reopenedState = true
        pristine = true
        settings = user.showing(path)
        // The main color shows the path's line color (changing it recolors the path); the
        // user's own comes back when it closes, unless they picked another one meanwhile.
        controller.color = strokeColor
        if (st?.kind == VStrokeKind.BRUSH) brushPreview.useSeed(st.seed)
        targetLayer = layer
        anchors = path.subpaths[0].anchors.map { it.toCurveAnchor() }
        selected = -1
        clearHistory()
        session.drawPreview = { canvas -> drawSessionPreview(canvas) }
        changed()
    }

    /** These settings showing the look of the path object [p] (see [reopen]). */
    private fun CurveSettings.showing(p: VPath): CurveSettings {
        val st = p.stroke
        val brushTaper = st?.kind == VStrokeKind.BRUSH && st.taperPercent > 0f
        val fillColor = (p.fill as? VPaint.Solid)?.color
        return copy(
            closed = p.subpaths[0].closed,
            tension = if (p.polyline) tension else p.tension.finiteOr(0f).coerceIn(0f, 1f),
            stroke = when (st?.kind) {
                null -> CurveStroke.NONE
                VStrokeKind.PLAIN -> CurveStroke.PLAIN
                VStrokeKind.BRUSH -> CurveStroke.BRUSH
            },
            plainWidth = st?.takeIf { it.kind == VStrokeKind.PLAIN }?.width?.finiteOr(plainWidth)?.coerceIn(ShapeSettings.MIN_STROKE, ShapeSettings.MAX_STROKE) ?: plainWidth,
            useBrushSize = false,
            fill = p.fill != null,
            // A fill of the line color follows the main color, as it did when it was drawn.
            fillColor = if (fillColor == null || st == null || fillColor == st.color) null else fillColor,
            taper = brushTaper,
            taperPercent = if (brushTaper) st!!.taperPercent.coerceIn(1f, 50f) else taperPercent,
        )
    }

    /**
     * Leaves the reopened state: the user's options and color come back; with [cancelSession]
     * the layer shows the path as it was again (else the caller commits the session).
     */
    private fun endReopen(cancelSession: Boolean = true) {
        val r = reopened ?: return
        reopened = null
        reopenedState = false
        pristine = true
        r.session.drawPreview = null
        r.session.inner = null
        if (cancelSession) r.session.cancel()
        invalidateRegions(sessionRegions)
        sessionRegions = emptyList()
        sessionSpecs = emptyList()
        sessionGradient = null
        settings = r.userSettings.sanitized()
        if (controller.color == r.openedColor) controller.color = r.userColor
        controller.invalidateOverlay()
    }

    /** The brush stroke of a reopened path started or ended: it is shown inside the edit session. */
    private fun onBrushLiveChanged() {
        if (inCommit) return
        val r = reopened ?: return
        if (brushPreview.isLive) r.session.adoptInner() else r.session.inner = null
        invalidateRegions(sessionRegions)
    }

    /** What a reopened path's edit session draws in place of the path (document px). */
    private fun drawSessionPreview(canvas: Canvas) {
        val r = reopened ?: return
        val s = r.session
        val floating = s.floating
        // Unchanged (or a brush stroke not replayed yet): the path's own pixels.
        val useFloating = floating != null && !floating.isRecycled &&
            (pristine || (strokeMode(r.layer) == CurveStroke.BRUSH && !brushPreview.isLive && sessionSpecs.isEmpty() && sessionGradient == null))
        if (useFloating) {
            val fr = s.floatingRect
            if (s.floatingScale == 1f) {
                canvas.drawBitmap(floating!!, fr.left.toFloat(), fr.top.toFloat(), null)
            } else {
                canvas.drawBitmap(floating!!, null, RectF(fr), floatingPaint)
            }
            return
        }
        // A semi-transparent path fades as a whole (its fill and line together), as the layer draws it.
        val opacity = r.original.opacity.let { if (it.isFinite()) it.coerceIn(0f, 1f) else 1f }
        val save = if (opacity < 1f) canvas.saveLayerAlpha(null, (opacity * 255f + 0.5f).toInt()) else canvas.save()
        sessionGradient?.let { g ->
            val clip = Rect()
            if (canvas.getClipBounds(clip)) {
                VectorLayerRenderer.render(canvas, VectorContent(objects = listOf(g.copy(opacity = 1f))), clip, tips = tips, document = Rect(0, 0, controller.doc.width, controller.doc.height))
            }
        }
        val mode = controller.doc.colorMode
        for (spec in sessionSpecs) renderer.draw(canvas, spec, false, mode)
        canvas.restoreToCount(save)
    }

    private val floatingPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private fun invalidateRegions(rects: List<Rect>) {
        if (rects.isEmpty()) return
        for (r in rects) controller.tiles.invalidate(r)
        controller.invalidateOverlay()
    }

    /** True when the selected brush can be an object of a vector layer (coverage brushes of the Brush tool). */
    private fun brushFitsVector(): Boolean {
        val id = brushToolId()
        val p = baseBrush() ?: return false
        return id == ToolId.BRUSH && !StrokeKind.of(id, p).isDirect
    }

    /** The painting tool the brush stroke uses: a reopened path's own, else the last one picked. */
    private fun brushToolId(): ToolId {
        val st = reopened?.original?.stroke
        return if (st != null && st.kind == VStrokeKind.BRUSH) st.brushTool ?: ToolId.BRUSH else controller.lastPaintTool
    }

    /** The brush (at 100 % thickness) the stroke is painted with: a reopened path's own, else the painting tool's. */
    private fun baseBrush(): BrushPreset? {
        val st = reopened?.original?.stroke
        if (st != null && st.kind == VStrokeKind.BRUSH) return VectorOps.brushOf(st)
        return controller.presetFor(controller.lastPaintTool)
    }

    /** How the path is stroked on [layer] right now (a brush that needs pixels draws a plain line on a vector layer). */
    private fun strokeMode(layer: Layer): CurveStroke {
        val s = settings.stroke
        if (s != CurveStroke.BRUSH || !vectorTarget(layer) || brushFitsVector()) return s
        return CurveStroke.PLAIN
    }

    /** Message shown once per path when the brush can't be used on a vector layer. */
    private var warnedPlain = false

    // ------------------------------------------------------------------ preview

    /** Plain items of the path: the fill (when on) and the plain line (when that is the stroke). */
    private fun buildSpecs(path: VectorPath, mode: CurveStroke, gradientFill: Boolean = false): List<VectorPaintSpec> {
        if (anchors.size < 2) return emptyList()
        val s = settings
        val fill = if (s.fill && anchors.size >= 3 && !gradientFill) path else null
        val st = reopened?.original?.stroke
        val cap = st?.cap ?: LineCapStyle.ROUND
        val join = st?.join ?: JoinStyle.ROUND
        val color = controller.color
        val fillColor = s.fillColor ?: color
        if (mode != CurveStroke.PLAIN) {
            return listOfNotNull(VectorPaintSpec.build(fill, fillColor, null, color, 0f))
        }
        val width = lineWidth
        if (CurveGeometry.isUniformWidth(anchors)) {
            return listOfNotNull(VectorPaintSpec.build(fill, fillColor, path, color, width, cap, join))
        }
        // Varying thickness: a filled outline (round joins and caps), painted with the line color.
        val outline = varyingOutline(width)
        return listOfNotNull(VectorPaintSpec.build(fill, fillColor, null, color, width, LineCapStyle.ROUND, JoinStyle.ROUND, strokeFill = outline))
    }

    /** The plain line's outline with the anchors' thickness factors (§4.5). */
    private fun varyingOutline(width: Float): VectorPath {
        val closed = settings.closed && anchors.size > 2
        val line = CurveWidths.line(anchors, closed, tension, polyline, width) ?: return VectorPath.EMPTY
        return VariableWidthOutline.build(line.xs, line.ys, line.ws, line.n, closed, CurveWidths.LINE_TOLERANCE)
    }

    /** The brush stroke of [path]: what identifies it for the live preview. */
    private data class BrushGeometry(val ops: List<PathOp>, val taperFraction: Float, val widths: List<Float>?)

    private fun brushGeometry(path: VectorPath, s: CurveSettings) = BrushGeometry(
        path.ops,
        if (s.taper) s.taperPercent / 100f else 0f,
        if (CurveGeometry.isUniformWidth(anchors)) null else anchors.map { it.width },
    )

    /** The brush input along [path] (samples, taper ramp, thickness as pressure). Main thread. */
    private fun brushPoints(path: VectorPath, g: BrushGeometry, a: List<CurveAnchor>, closed: Boolean, out: PathStrokeInput) {
        brushStrokeInput(path, g.taperFraction, out)
        if (g.widths != null) applyWidthProfile(out, WidthProfile(CurveWidths.atSamples(a, closed, tension, polyline, out.size)))
    }

    private val sampleScratch = PathStrokeInput(1024)

    /**
     * The brush the live stroke of [path] needs instead of the painting tool's: a reopened
     * path's own brush; with varying thickness, sized up to the thickest sample with its size
     * following pressure and its opacity not (V15). Null = the tool's own brush as it is.
     */
    private fun brushFor(path: VectorPath, g: BrushGeometry): BrushPreset? {
        val st = reopened?.original?.stroke
        val own = if (st != null && st.kind == VStrokeKind.BRUSH) VectorOps.brushOf(st) else null
        if (g.widths == null) return own
        val base = own ?: controller.presetFor(brushToolId()) ?: return null
        brushStrokeInput(path, g.taperFraction, sampleScratch)
        val wMax = profileMax(WidthProfile(CurveWidths.atSamples(anchors, settings.closed && anchors.size > 2, tension, polyline, sampleScratch.size)))
        if (!(wMax > 0f)) return own
        return base.copy(size = base.size * wMax, pressureSize = true, minSizeRatio = 0f, pressureOpacity = false)
    }

    /**
     * Rebuilds the guide path and the preview, then redraws. A plain line / fill goes through the
     * compositor; with "Current brush" the painting tool's own unfinished stroke is the preview
     * (replayed, coalesced) and the fill is drawn in the overlay. A reopened path is drawn by its
     * edit session (the brush stroke inside it).
     */
    private fun changed(brushDelayMs: Long = 0L) {
        if (anchors.isNotEmpty()) ensureObserving()
        val path = if (anchors.size >= 2) path() else null
        if (path != null) path.toAndroidPath(docPath) else docPath.rewind()
        val layer = targetLayer ?: controller.doc.activeLayer
        val mode = strokeMode(layer)
        if (path != null && settings.stroke == CurveStroke.BRUSH && mode == CurveStroke.PLAIN && !warnedPlain) {
            warnedPlain = true
            controller.toast("${baseBrush()?.name ?: controller.lastPaintTool.label} needs a raster layer — the curve is drawn as a plain line")
        }
        val r = reopened
        if (r != null) {
            changedReopened(r, path, mode, brushDelayMs)
            controller.invalidateOverlay()
            return
        }
        val specs = if (path != null) buildSpecs(path, mode) else emptyList()
        if (path != null && mode == CurveStroke.BRUSH && layer === controller.doc.activeLayer) {
            preview.release()
            overlaySpecs = specs
            val g = brushGeometry(path, settings)
            brushOverride = brushFor(path, g)
            val size = (brushOverride ?: controller.presetFor(brushToolId()))?.size ?: 0f
            if (specs.isNotEmpty()) specOverlay.setBand(docPath, size)
            val a = anchors
            val closed = settings.closed && a.size > 2
            brushPreview.request(g, brushDelayMs) { brushPoints(path, g, a, closed, it) }
        } else {
            brushPreview.cancel()
            overlaySpecs = emptyList()
            if (vectorTarget(layer) && (controller.selection != null || layer.alphaLocked)) {
                // An object is neither clipped by the selection nor by alpha lock.
                preview.release()
                showUnclipped(layer, specs)
            } else {
                hideUnclipped()
                preview.show(layer, specs)
            }
        }
        controller.invalidateOverlay()
    }

    private fun changedReopened(r: Reopened, path: VectorPath?, mode: CurveStroke, brushDelayMs: Long) {
        preview.release()
        hideUnclipped()
        overlaySpecs = emptyList()
        val vp = buildVPath()
        pristine = vp != null && vp == r.original
        val gradient = r.original.fill?.takeIf { it !is VPaint.Solid && settings.fill && settings.fillColor == null && anchors.size >= 3 }
        val specs = if (path != null) buildSpecs(path, mode, gradientFill = gradient != null) else emptyList()
        sessionGradient = if (gradient != null && vp != null) vp.copy(stroke = null) else null
        val regions = ArrayList<Rect>()
        for (s in specs) regions += s.regions
        sessionGradient?.let { g -> regions += roundOut(VectorOps.bounds(g)) }
        // (Where the path's own pixels are shown while it is unchanged.)
        if (!r.session.floatingRect.isEmpty) regions += Rect(r.session.floatingRect)
        sessionSpecs = specs
        invalidateRegions(sessionRegions)
        invalidateRegions(regions)
        sessionRegions = regions
        if (path != null && mode == CurveStroke.BRUSH && !pristine) {
            val g = brushGeometry(path, settings)
            brushOverride = brushFor(path, g)
            val a = anchors
            val closed = settings.closed && a.size > 2
            brushPreview.request(g, brushDelayMs) { brushPoints(path, g, a, closed, it) }
        } else {
            brushPreview.cancel()
        }
    }

    private fun roundOut(b: RectF): Rect =
        if (b.isEmpty) Rect() else Rect(floor(b.left).toInt(), floor(b.top).toInt(), ceil(b.right).toInt(), ceil(b.bottom).toInt())

    /** Runs a waiting live-brush replay now (the main looper does it otherwise). */
    internal fun flushPreview() = brushPreview.flush()

    // ------------------------------------------------------------------ the preview of an object that ignores the selection

    /** Draws the layer with the items over it, without the selection's or alpha lock's clipping (vector objects). */
    private inner class UnclippedPreview(override val layer: Layer) : LayerRenderOverride {
        var specs: List<VectorPaintSpec> = emptyList()
        var regions: List<Rect> = emptyList()

        override fun drawContent(canvas: Canvas): Boolean {
            canvas.drawBitmap(layer.bitmap, 0f, 0f, null)
            val mode = controller.doc.colorMode
            for (s in specs) renderer.draw(canvas, s, false, mode)
            return true
        }
    }

    private var unclipped: UnclippedPreview? = null

    private fun showUnclipped(layer: Layer, specs: List<VectorPaintSpec>) {
        if (specs.isEmpty()) { hideUnclipped(); return }
        val ov = unclipped?.takeIf { it.layer === layer } ?: run {
            hideUnclipped()
            UnclippedPreview(layer).also { unclipped = it }
        }
        val old = ov.regions
        val regions = ArrayList<Rect>()
        for (s in specs) regions += s.regions
        ov.specs = specs
        ov.regions = regions
        if (controller.renderOverride !== ov) controller.renderOverride = ov
        invalidateRegions(old)
        invalidateRegions(regions)
    }

    private fun hideUnclipped() {
        val ov = unclipped ?: return
        unclipped = null
        if (controller.renderOverride === ov) controller.renderOverride = null
        invalidateRegions(ov.regions)
    }

    // ------------------------------------------------------------------ the path as an object

    /**
     * The pending path as a vector path object (id 0; a reopened path keeps its id, opacity,
     * fill rule and stroke details), or null when it draws nothing.
     */
    internal fun buildVPath(): VPath? {
        if (anchors.size < 2) return null
        val s = settings
        val r = reopened
        val layer = targetLayer ?: controller.doc.activeLayer
        val closed = s.closed && anchors.size > 2
        val color = controller.color
        val orig = r?.original
        val origStroke = orig?.stroke
        val stroke = when (strokeMode(layer)) {
            CurveStroke.PLAIN -> VStrokeStyle(
                kind = VStrokeKind.PLAIN, color = color, width = lineWidth,
                cap = origStroke?.takeIf { it.kind == VStrokeKind.PLAIN }?.cap ?: LineCapStyle.ROUND,
                join = origStroke?.takeIf { it.kind == VStrokeKind.PLAIN }?.join ?: JoinStyle.ROUND,
                miter = origStroke?.takeIf { it.kind == VStrokeKind.PLAIN }?.miter ?: 4f,
            )
            CurveStroke.BRUSH -> {
                val strokeColor = ColorModeOps.displayColor(color or OPAQUE, controller.doc.colorMode)
                if (origStroke != null && origStroke.kind == VStrokeKind.BRUSH) {
                    // A reopened brush path keeps its brush and texture.
                    origStroke.copy(color = strokeColor, seed = brushPreview.sessionSeed, taperPercent = if (s.taper) s.taperPercent else 0f)
                } else {
                    val brush = controller.presetFor(controller.lastPaintTool)?.sanitized() ?: return null
                    VStrokeStyle(
                        kind = VStrokeKind.BRUSH, color = strokeColor, width = brush.size,
                        brushTool = controller.lastPaintTool, brush = brush, seed = brushPreview.sessionSeed,
                        taperPercent = if (s.taper) s.taperPercent else 0f,
                    )
                }
            }
            CurveStroke.NONE -> null
        }
        val fill = if (s.fill && anchors.size >= 3) {
            val g = orig?.fill?.takeIf { it !is VPaint.Solid && s.fillColor == null }
            g ?: VPaint.Solid(s.fillColor ?: color)
        } else {
            null
        }
        if (stroke == null && fill == null) return null
        return VPath(
            id = orig?.id ?: 0L,
            opacity = orig?.opacity ?: 1f,
            subpaths = listOf(VSubpath(anchors.map { it.toVAnchor() }, closed)),
            tension = if (polyline) orig?.tension ?: 1f else s.tension,
            polyline = polyline,
            fillRule = orig?.fillRule ?: VFillRule.NONZERO,
            fill = fill,
            stroke = stroke,
        )
    }

    // ------------------------------------------------------------------ commit / discard

    override fun commit() {
        if (reopened != null) { commitReopened(); return }
        if (anchors.size < 2) { discard(); return }
        val layer = targetLayer ?: controller.doc.activeLayer
        if (controller.doc.indexOf(layer) < 0) { discard(); return }
        if (!controller.checkEditable(layer)) return
        if (vectorTarget(layer)) { commitObject(layer); return }
        val s = settings
        val path = path()
        val mode = strokeMode(layer)
        val specs = buildSpecs(path, mode)
        val brush = mode == CurveStroke.BRUSH && layer === controller.doc.activeLayer
        val g = brushGeometry(path, s)
        val a = anchors
        val closed = s.closed && a.size > 2
        brushOverride = if (brush) brushFor(path, g) else null
        resetPath()
        // Fill and brush stroke are ONE undo step, named after the tool (a plain line / fill
        // alone keeps its own name).
        val step: (String, () -> Unit) -> Unit = if (brush) controller::undoStepNamed else controller::groupUndo
        step(if (polyline) "Polyline" else "Curve") {
            if (specs.isNotEmpty()) {
                // The fill goes under the stroke, so the stroke is painted after it (smudge /
                // blur previews edit the pixels: they are restored first).
                brushPreview.cancel()
                val label = when {
                    mode != CurveStroke.PLAIN -> "Fill path"
                    polyline -> "Polyline"
                    else -> "Curve"
                }
                VectorCommit.commit(controller, layer, specs, label)
            }
            if (brush) brushPreview.commit(g) { brushPoints(path, g, a, closed, it) }
        }
        brushPreview.end()
        brushOverride = null
        controller.invalidateOverlay()
    }

    /**
     * ✓ on a vector layer: the path becomes an object of it, ONE undo step named after the tool.
     * A live brush stroke is painted for real (its pixels are exactly the object's replay; the
     * fill under it is drawn first, as the layer draws it) and the object is added to the data;
     * anything else is added and drawn by the layer. A path that draws nothing is dropped.
     */
    private fun commitObject(layer: Layer) {
        val vp = buildVPath() ?: run { discard(); return }
        val label = if (polyline) "Polyline" else "Curve"
        val path = path()
        val g = brushGeometry(path, settings)
        val a = anchors
        val closed = settings.closed && a.size > 2
        if (vp.stroke?.kind != VStrokeKind.BRUSH) {
            brushPreview.cancel()
            val ids = controller.vectors.addObjects(layer, listOf(vp), label)
            // Refused (no memory...): the path stays pending.
            if (ids.isEmpty()) return
            resetPath()
            brushPreview.end()
            controller.invalidateOverlay()
            return
        }
        brushOverride = brushFor(path, g)
        resetPath()
        inCommit = true
        try {
            controller.undoStepNamed(label) {
                vp.fill?.let { drawFillLikeTheLayer(layer, vp.copy(stroke = null), label) }
                controller.keepLayerData(layer) { brushPreview.commit(g) { brushPoints(path, g, a, closed, it) } }
                controller.vectors.appendData(layer, listOf(vp), label)
            }
        } finally {
            inCommit = false
        }
        brushPreview.end()
        brushOverride = null
        controller.invalidateOverlay()
    }

    /**
     * Draws the fill-only [fillPath] into vector layer [layer]'s pixels exactly as the layer's
     * renderer draws it (on its tile grid, which the dirty area follows), keeping the layer's
     * data (the object is appended after the brush stroke).
     */
    private fun drawFillLikeTheLayer(layer: Layer, fillPath: VPath, label: String) {
        val doc = controller.doc
        val b = roundOut(VectorOps.bounds(fillPath))
        val t = VectorLayerRenderer.TILE
        val area = Rect(
            Math.floorDiv(b.left, t) * t, Math.floorDiv(b.top, t) * t,
            -Math.floorDiv(-b.right, t) * t, -Math.floorDiv(-b.bottom, t) * t,
        )
        if (b.isEmpty || !area.intersect(0, 0, doc.width, doc.height)) return
        val rec = controller.beginEdit(layer, EditTarget.CONTENT).also { it.preserveData = true }
        try {
            rec.touch(area)
            val canvas = Canvas(layer.bitmap)
            canvas.clipRect(area)
            VectorLayerRenderer.render(canvas, VectorContent(objects = listOf(fillPath)), area, tips = tips, document = Rect(0, 0, doc.width, doc.height))
        } catch (e: OutOfMemoryError) {
            rec.abort()
            controller.toast("Not enough memory for \"$label\"")
            return
        }
        controller.commitEdit(rec, label)
    }

    /**
     * ✓ of a reopened path: it replaces the object (same id and place in the stack) as ONE undo
     * step "Edit path" (nothing when unchanged); with fewer than two points left the object goes.
     */
    private fun commitReopened() {
        val r = reopened ?: return
        val vp = buildVPath()
        inCommit = true
        try {
            brushPreview.cancel()
            r.session.inner = null
            r.session.drawPreview = null
            val replacement = vp?.copy(id = r.original.id)
            endReopen(cancelSession = false)
            r.session.commit(listOfNotNull(replacement), EDIT_PATH_LABEL)
        } finally {
            inCommit = false
        }
        resetPath()
        brushPreview.end()
        brushOverride = null
        controller.invalidateOverlay()
    }

    override fun discard() {
        clear()
        controller.invalidateOverlay()
    }

    /** Forgets the path and its in-tool history (the brush preview is left to the caller). */
    private fun resetPath() {
        anchors = emptyList()
        selected = -1
        clearHistory()
        drag = Drag.NONE
        endSnap()
        targetLayer = null
        docPath.rewind()
        overlaySpecs = emptyList()
        preview.release()
        preview.interacting = false
        hideUnclipped()
        thicknessRing = false
        warnedPlain = false
    }

    private fun clearHistory() {
        history.clear()
        redo.clear()
        redoCount = 0
        historyKey = null
        canUndoStep = false
    }

    private fun clear() {
        opening = false
        resetPath()
        brushPreview.end()
        brushOverride = null
        endReopen()
    }

    override fun onActivate() = ensureObserving()

    /**
     * The preview depends on the main color, selection, layer props and the painting tool's
     * preset (the size / opacity sliders). Started on activation and again whenever anchors
     * exist, because some controller operations call onDeactivate without a following onActivate.
     */
    private fun ensureObserving() {
        if (observeJob?.isActive == true) return
        observeJob = controller.scope.launch {
            snapshotFlow {
                listOf(controller.color, controller.selection, controller.layersVersion, controller.lastPaintTool, controller.presetFor(controller.lastPaintTool))
            }
                .drop(1)
                .collect { if (anchors.size >= 2 && !inCommit) changed() }
        }
    }

    override fun onSelectionChanged() {
        // (A brush stroke of a vector object starts with the selection lifted for a moment.)
        if (brushPreview.liftingClip || inCommit) return
        if (anchors.size >= 2) changed()
    }

    override fun onDeactivate() {
        observeJob?.cancel()
        observeJob = null
        drag = Drag.NONE
        if (hasPendingWork) commit()
        // Drops a path whose layer refused the commit (locked / hidden) and any in-tool history
        // left over from a path whose points were all deleted.
        clear()
    }

    override fun onDispose() {
        brushPreview.end()
        tipCache?.clear()
        tipCache = null
    }

    // ------------------------------------------------------------------ overlay

    private fun map(t: ViewTransform, p: Vec2): FloatArray {
        pts[0] = p.x; pts[1] = p.y
        t.matrix.mapPoints(pts)
        return pts
    }

    private val ringPath = Path()

    override fun drawOverlay(canvas: Canvas, t: ViewTransform) {
        val list = anchors
        if (list.isEmpty()) return
        preview.drawOverlay(canvas, t)
        if (overlaySpecs.isNotEmpty()) {
            val layer = targetLayer ?: controller.doc.activeLayer
            specOverlay.draw(canvas, t, controller, layer, overlaySpecs, keepBandFree = brushPreview.isLive, ignoreSelection = vectorTarget(layer))
        }
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
        // The real diameter at the selected point while its thickness is changed.
        if (thicknessRing && sel in list.indices) {
            val d = diameterAt(sel)
            if (d > 0f && d.isFinite()) {
                ringPath.rewind()
                ringPath.addCircle(list[sel].x, list[sel].y, d / 2f, Path.Direction.CW)
                painter.path(canvas, t, ringPath, dashed = true)
            }
        }
        for (i in list.indices) {
            val q = map(t, list[i].pos)
            painter.handle(canvas, t, q[0], q[1], square = list[i].sharp || polyline, active = i == sel)
        }
        // Smart guides of the dragged point (on top, labels away from the finger).
        snap.draw(canvas, t, snapMoving?.let { pointBox(it) })
    }

    companion object {
        /** Guide label of the path's other anchors. */
        private const val POINT_LABEL = "Point"
        private const val MAX_HISTORY = 200
        /** Keyed numeric edits closer together than this share one in-tool undo step. */
        private const val COALESCE_MS = 1500L
        private const val TOUCH_SLOP_DP = 6f
        /** Grab radius of anchors and tangent handles (screen dp): generous for fingers. */
        private const val HANDLE_TOUCH_DP = 24f
        /** How close to a path object a tap reopens it (screen dp). */
        private const val PATH_HIT_DP = 12f
        /** How long the brush stroke waits for a point just placed under a finger (see onDown). */
        internal const val NEW_POINT_BRUSH_DELAY_MS = 150L
        /** Undo label of ✓ on a reopened path object. */
        const val EDIT_PATH_LABEL = "Edit path"
        private const val OPAQUE = 0xFF000000.toInt()
    }
}
