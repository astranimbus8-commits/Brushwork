package com.brushwork.paint.tools.vector

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.brushwork.paint.AppSettings
import com.brushwork.paint.ColorModeOps
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushPreset
import com.brushwork.paint.brush.BrushPresetStore
import com.brushwork.paint.brush.PathStrokeInput
import com.brushwork.paint.brush.StrokeKind
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.brush.sanitized
import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.IncrementMath
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.engine.LayerRenderOverride
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.GridType
import com.brushwork.paint.model.IncrementKind
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ObjectPosition
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.select.pointBox
import com.brushwork.paint.tools.select.pointLines
import com.brushwork.paint.tools.select.applyPointHits
import com.brushwork.paint.tools.select.snapPointToObjects
import com.brushwork.paint.tools.transform.SnapAxis
import com.brushwork.paint.tools.transform.SnapGuide
import com.brushwork.paint.tools.vector.spline.NurbsGeometry
import com.brushwork.paint.tools.vector.spline.PathOverlay
import com.brushwork.paint.tools.vector.spline.SplineBezier
import com.brushwork.paint.tools.vector.spline.SplineEditing
import com.brushwork.paint.tools.vector.spline.SplinePresets
import com.brushwork.paint.ui.theme.IbisDims
import com.brushwork.paint.vector.VFillRule
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSpline
import com.brushwork.paint.vector.VSplinePoint
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
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How a committed curve is stroked. */
@Serializable
enum class CurveStroke(val label: String) {
    /** Drives the last painting tool (brush / smudge / blur) along the path. */
    BRUSH("Current brush"),
    /** A plain anti-aliased line of a fixed width. */
    PLAIN("Plain line"),
    NONE("No stroke"),
}

/**
 * The three tools [CurveTool] implements (v1.6): Bézier curves, polylines (all corners sharp)
 * and Path (a NURBS / B-spline through control points, like a Blender path).
 */
enum class CurveKind { CURVE, POLYLINE, PATH }

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
    /**
     * Path tool (v1.6): the order a new path starts with (2..6, [VSpline.DEFAULT_ORDER]); the
     * pending path keeps its own in its spline. Its Cyclic default is [closed].
     */
    val pathOrder: Int = VSpline.DEFAULT_ORDER,
    /** Path tool (v1.6): a new open path touches its first and last points (clamped knots). */
    val pathEndpoint: Boolean = true,
    /**
     * v1.7 (item 7): the stroke kind "Stroke" and "Both" restore after "Fill" (a preference, not
     * document data; never [CurveStroke.NONE]).
     */
    val lastStroke: CurveStroke = CurveStroke.BRUSH,
) {
    /** Clamps every value to its supported range; non-finite values are taken from [fallback]. */
    fun sanitized(fallback: CurveSettings = DEFAULT) = copy(
        tension = tension.finiteOr(fallback.tension).coerceIn(0f, 1f),
        plainWidth = plainWidth.finiteOr(fallback.plainWidth).coerceIn(ShapeSettings.MIN_STROKE, ShapeSettings.MAX_STROKE),
        taperPercent = taperPercent.finiteOr(fallback.taperPercent).coerceIn(1f, 50f),
        nudgeStepPx = nudgeStepPx.finiteOr(fallback.nudgeStepPx).coerceIn(0.01f, ShapeSettings.MAX_LENGTH),
        pathOrder = pathOrder.coerceIn(VSpline.MIN_ORDER, VSpline.MAX_ORDER),
        lastStroke = if (lastStroke == CurveStroke.NONE) CurveStroke.BRUSH else lastStroke,
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
 *   no path pending, tapping a path object of the layer (the line of a filled path with a line: a
 *   tap inside it starts a new path there) reopens it ([reopen]): its own look
 *   (width unlinked, its colors, its brush) is edited, ✓ replaces it ("Edit path"), ✕ leaves it
 *   as it was. A brush that needs pixels (smudge, blur, watercolor) draws a plain line there.
 *
 * v1.6 (§3.2, §3.3, §3.4): one class, three [CurveKind]s — the Curve, Polyline and Path tools.
 * - **Path** ([CurveKind.PATH], like a Blender path): the pending path is a [VSpline] (control
 *   points, order, endpoint, cyclic, per-point weight and thickness; [spline]); its [anchors]
 *   are always DERIVED through [SplineBezier.toSubpath], so the preview, brush stroke, fill,
 *   raster and vector commits are the Curve pipeline's. Tap empty canvas to add a control point
 *   (after the selected one, which then moves on to the new point; v1.7: before it when it is
 *   the first point of an open path; else at the end), tap near
 *   the dashed control polygon to insert one there, drag a point to move it, tap a point to
 *   select it ([selectedPoint]). On a vector layer ✓ adds a [VPath] with its spline ("Path");
 *   a tap on a spline path that passes the I9 check ([SplineBezier.matches]) reopens it ("Edit
 *   path"). Curve / Polyline tapping such a path switch to Path, Path tapping a plain path
 *   switches to the tool that edits it. [toBezier] hands the pending path to the Curve tool
 *   (one in-tool step there: its undo hands it back). Path has its own settings ("vec.path").
 *   The Capsule quick start's look and a handed-over path's look are that pending path's only:
 *   the tool's own settings come back when it ends ([ownSettings]).
 * - **Handles** (Curve): the selected point's handles (or all points') scale by a factor
 *   relative to the change's start ([scaleHandles]; slider, ‹ ›, typed value or a pinch on
 *   the point, one in-tool step each). "Handle size" ([handleSize], app-wide) scales the drawn
 *   points and handles and their grab radii.
 * - **Increments** (§3.4): point drags move by multiples of the Length step from where the
 *   point was (after object and grid snapping, per axis); handle scaling uses the Scale step.
 *   With increments off every gesture is exactly v1.5.
 */
class CurveTool(controller: EditorController, val kind: CurveKind) : Tool(controller) {
    /** v1.5 constructor: the Polyline tool when [polyline], else the Curve tool. */
    constructor(controller: EditorController, polyline: Boolean) : this(controller, if (polyline) CurveKind.POLYLINE else CurveKind.CURVE)

    /** True for the Polyline tool (all corners sharp). */
    val polyline: Boolean get() = kind == CurveKind.POLYLINE

    override val id = when (kind) {
        CurveKind.CURVE -> ToolId.CURVE
        CurveKind.POLYLINE -> ToolId.POLYLINE
        CurveKind.PATH -> ToolId.PATH
    }

    /** True for the Path tool (a NURBS / B-spline through control points). */
    val isPath: Boolean get() = kind == CurveKind.PATH

    /**
     * v1.6: in PATH mode, the X / Y pill's target (the selected control point); null = the Curve
     * adapter (`CurvePointPosition`: the selected anchor). The X / Y strip reads it ONCE per
     * selected tool (`coordinateSourceOf` is remembered per tool), so in PATH mode it is one
     * stable, non-null instance whose `position` is null while no control point is selected.
     */
    val splinePointPosition: ObjectPosition? = if (kind == CurveKind.PATH) SplinePointPosition() else null

    /** Current options (Compose state); change them with [update]. */
    var settings by mutableStateOf(loadSettings())
        private set

    /**
     * Anchors of the pending path (Compose state). In PATH mode they are derived from [spline]
     * (its Bézier form) and never edited directly.
     */
    var anchors by mutableStateOf<List<CurveAnchor>>(emptyList())
        private set

    /** Index of the selected anchor or -1 (always -1 in PATH mode: see [selectedPoint]). */
    var selected by mutableIntStateOf(-1)
        private set

    /** PATH: the control points, order, endpoint and cyclic of the pending path (Compose state); null when none. */
    var spline by mutableStateOf<VSpline?>(null)
        private set

    /**
     * PATH: converts the pending spline to its Bézier form, re-converting only the spans an edit
     * touched (a point drag on a long path converts at most p + 1 spans per move); bit for bit
     * [SplineBezier.toSubpath], so the anchors and I9 are as a fresh conversion gives.
     */
    private val splineBezier = SplineBezier.Incremental()

    /** PATH: index of the selected control point or -1 (Compose state). */
    var selectedPoint by mutableIntStateOf(-1)
        private set

    /** The selected point the strip and the Numbers sheet edit: the control point (Path) or the anchor. */
    val selectedIndex: Int get() = if (isPath) selectedPoint else selected

    /** Number of points the user edits: control points (Path) or anchors. */
    val pointCount: Int get() = if (isPath) spline?.points?.size ?: 0 else anchors.size

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
     * The reopened path has a gradient fill (an imported SVG): without a fill color of its own it
     * keeps that gradient (see the fill of [commit]), not the main color.
     */
    val reopenedGradientFill: Boolean get() = reopenedState && reopened?.original?.fill.let { it != null && it !is VPaint.Solid }

    /**
     * Shows a ring of the real line diameter at the selected anchor (set while its thickness
     * slider is dragged).
     */
    var thicknessRing by mutableStateOf(false)

    /**
     * One in-tool undo state: the anchors (Curve, Polyline) or the spline (Path: its anchors are
     * derived again on restore). [back] marks the Curve tool's first step after [toBezier]: its
     * undo hands the path back to the Path tool as it was. [toBezier] marks the Path tool's redo
     * of that hand-back (redo converts again). [look]: the look the path showed then, when it
     * was not the tool's own ([ownSettings]; a redone quick start shows its look again).
     */
    private class EditState(
        val anchors: List<CurveAnchor>,
        val spline: VSpline?,
        val back: Handoff? = null,
        val toBezier: Boolean = false,
        val look: CurveSettings? = null,
    )

    private val history = ArrayDeque<EditState>()
    private val redo = ArrayDeque<EditState>()
    private var historyKey: Any? = null
    private var historyKeyTime = 0L
    /** A slider drag is in progress ([beginNumericEdit]): its edits share one step whatever the pace. */
    private var numericHeld = false
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
    private var gestureStart = EditState(emptyList(), null)
    private var gestureSelected = -1
    private var gestureSelectedPoint = -1
    private var gestureHistorySize = 0
    private var gestureRedo: List<EditState> = emptyList()
    /** The path object a tap (no pending path) would reopen. */
    private var reopenCandidate: VPath? = null
    /** Which tool reopens [reopenCandidate]: this one, or the kind it switches to (Curve ↔ Path). */
    private var reopenKind = kind
    /** Where the dragged point (or tangent handle's end) was when the drag began (increments step from there, §3.4). */
    private var dragStartPos = Vec2.ZERO

    private val painter = OverlayPainter()
    private val pointPainter = PathOverlay()
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

    /** Tension of the derived curve: straight for Polyline, 0 for Path (its anchors carry explicit handles). */
    private val tension: Float get() = if (polyline) 1f else if (isPath) 0f else settings.tension

    /**
     * Whether the pending path is drawn closed: the Closed setting (Curve, Polyline) or a cyclic
     * spline of at least 3 points (Path).
     */
    private val isClosed: Boolean
        get() = if (isPath) spline?.let { NurbsGeometry.isClosed(it) } == true else settings.closed

    /** True when the fill can show: 3 anchors, or (Path) 3 control points. */
    private val canFill: Boolean
        get() = if (isPath) (spline?.points?.size ?: 0) >= 3 && anchors.size >= 2 else anchors.size >= 3

    /** The current path (straight segments for the polyline tool). */
    fun path(): VectorPath = CurveGeometry.toPath(anchors, isClosed, tension, polyline)

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
        // While a path object is reopened, or the pending path shows a look of its own (a quick
        // start's, a path handed over by To Bézier: [ownSettings]), the strip shows THAT look:
        // only the unit and the nudge step are the user's own settings then.
        val user = reopened?.userSettings ?: ownSettings
        val toSave = if (user != null) user.copy(unit = new.unit, nudgeStepPx = new.nudgeStepPx) else new
        val r = reopened
        if (r != null) r.userSettings = toSave else if (ownSettings != null) ownSettings = toSave
        runCatching { controller.settings.putObject(prefsKey, CurveSettings.serializer(), toSave) }
        changed()
    }

    /**
     * The tool's own settings while the pending path shows a look that is not theirs: the
     * Capsule quick start's (fill, no line) or that of a path handed over by To Bézier (or by
     * undoing it). They come back when that path ends (✓, ✕, undone away, handed on), so a quick
     * start or a conversion never changes how the next path of this tool looks; meanwhile the
     * strip edits the pending path's look ([update] saves only the unit and nudge step), as for a
     * reopened path object. Null otherwise (and always while a path object is reopened).
     */
    private var ownSettings: CurveSettings? = null

    /** The pending path shows [look] (see [ownSettings]); the unit and nudge step stay the user's. */
    private fun showLook(look: CurveSettings) {
        if (reopened != null) return
        val own = ownSettings ?: settings
        ownSettings = own
        settings = look.copy(unit = own.unit, nudgeStepPx = own.nudgeStepPx).sanitized(fallback = own)
    }

    /** The pending path is gone: the tool's own settings come back (see [ownSettings]). */
    private fun endLook() {
        val own = ownSettings ?: return
        ownSettings = null
        settings = own.sanitized()
    }

    /** Each tool keeps its own options (v1.6: Path's quick starts set fill and stroke without touching Curve's). */
    private val prefsKey: String
        get() = when (kind) {
            CurveKind.CURVE -> "vec.curve"
            CurveKind.POLYLINE -> "vec.polyline"
            CurveKind.PATH -> "vec.path"
        }

    /** The undo label of ✓ (a new path): the tool's name. */
    private val stepLabel: String
        get() = when (kind) {
            CurveKind.CURVE -> "Curve"
            CurveKind.POLYLINE -> "Polyline"
            CurveKind.PATH -> PATH_LABEL
        }

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
        val coalesce = key != null && key == historyKey && history.isNotEmpty() && (numericHeld || now - historyKeyTime <= COALESCE_MS)
        historyKey = key
        historyKeyTime = now
        clearRedo()
        if (coalesce) return
        history.addLast(currentState())
        trimHistory()
        canUndoStep = true
    }

    /** The pending path as an in-tool undo state. */
    private fun currentState(): EditState {
        val look = if (ownSettings != null) settings else null
        return if (isPath) EditState(emptyList(), spline, look = look) else EditState(anchors, null, look = look)
    }

    /** Keeps at most [MAX_HISTORY] states (never dropping the way back to the Path tool: it is the first one). */
    private fun trimHistory() {
        while (history.size > MAX_HISTORY) {
            val first = history.first()
            if (first.back != null && history.size > 1) history.removeAt(1) else history.removeFirst()
        }
    }

    private fun clearRedo() {
        if (redo.isEmpty()) return
        redo.clear()
        redoCount = 0
    }

    /**
     * A slider drag (X / Y strip, thickness) starts: its edits are one undo step however long the
     * finger rests on the way, and a new step even right after another edit ([endNumericEdit]
     * ends it).
     */
    fun beginNumericEdit() {
        historyKey = null
        numericHeld = true
    }

    /**
     * A slider drag, a held arrow or a typed value is complete: the next numeric edit (of any
     * point) is a new undo step even when it follows at once.
     */
    fun endNumericEdit() {
        historyKey = null
        numericHeld = false
    }

    /**
     * Steps back one anchor edit (add, move, delete, corner change). Also used by the app's undo
     * (button / two-finger tap) so it takes back the last point instead of the whole curve.
     */
    override fun undoStep(): Boolean {
        val prev = history.removeLastOrNull() ?: return false
        prev.back?.let { back ->
            // The first step after To Bézier: the path goes back to the Path tool as it was.
            history.addLast(prev)
            return handBack(back)
        }
        redo.addLast(currentState())
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
        if (next.toBezier) return toBezier()
        history.addLast(currentState())
        trimHistory()
        canUndoStep = true
        historyKey = null
        restore(next)
        return true
    }

    private fun restore(state: EditState) {
        // A path that showed a look of its own (a redone quick start) shows it again; the tool's
        // own settings come back when the path is undone away.
        state.look?.let { if (ownSettings == null) showLook(it) }
        if (isPath) {
            setSplineState(state.spline)
            if (selectedPoint !in 0 until pointCount) selectedPoint = -1
        } else {
            anchors = state.anchors
            if (selected !in anchors.indices) selected = -1
        }
        if (anchors.isEmpty()) {
            targetLayer = null
            endLook()
        } else if (targetLayer == null) {
            targetLayer = controller.doc.activeLayer
        }
        changed()
    }

    /** PATH: the pending spline becomes [s] (null or no points = none) and the anchors its Bézier form. */
    private fun setSplineState(s: VSpline?) {
        val v = s?.takeIf { it.points.isNotEmpty() }
        spline = v
        anchors = if (v == null) emptyList() else splineBezier.toSubpath(v).anchors.map { it.toCurveAnchor() }
    }

    /**
     * Appends an anchor at [p] and selects it (numeric entry). Returns false when the active
     * layer can't be edited. PATH: after the selected control point (v1.7, item 19: before it
     * when it is the first point of an open path of at least 2 points), with its thickness.
     */
    fun addAnchor(p: Vec2): Boolean {
        if (!p.x.isFinite() || !p.y.isFinite()) return false
        if (anchors.isEmpty() && !controller.checkEditable()) return false
        val lim = ShapeSettings.MAX_LENGTH
        if (isPath) {
            // A control point at the end (or next to the selected one), selected.
            val s = spline ?: newSpline()
            if (s.points.size >= VSpline.MAX_POINTS) {
                controller.toast(TOO_MANY_POINTS)
                return false
            }
            pushHistory()
            if (targetLayer == null) targetLayer = controller.doc.activeLayer
            val keepSelecting = selectedPoint in s.points.indices
            val at = when {
                // v1.7 (item 19): the first point of an open path extends it from its start.
                keepSelecting && selectedPoint == 0 && !s.cyclic && s.points.size >= 2 -> 0
                keepSelecting -> selectedPoint + 1
                else -> s.points.size
            }
            // The selected point's thickness (else the last one's).
            val width = (if (keepSelecting) s.points[selectedPoint] else s.points.lastOrNull())?.width ?: 1f
            setSplineState(SplineEditing.inserted(s, at, VSplinePoint(p.x.coerceIn(-lim, lim), p.y.coerceIn(-lim, lim), width = width)))
            selectedPoint = at
            changed()
            return true
        }
        pushHistory()
        if (targetLayer == null) targetLayer = controller.doc.activeLayer
        anchors = anchors + CurveAnchor(p.x.coerceIn(-lim, lim), p.y.coerceIn(-lim, lim), sharp = polyline)
        selected = anchors.lastIndex
        changed()
        return true
    }

    /** Selects point [index] (a control point in PATH mode, else an anchor); -1 or out of range deselects. */
    fun select(index: Int) {
        if (isPath) selectedPoint = if (index in 0 until pointCount) index else -1
        else selected = if (index in anchors.indices) index else -1
        controller.invalidateOverlay()
    }

    fun deselect() = select(-1)

    /** Makes anchor [index] a corner (sharp) or smooth. */
    fun setSharp(index: Int, sharp: Boolean) {
        if (isPath) return
        val a = anchors.getOrNull(index) ?: return
        if (a.sharp == sharp && !a.hasCustomTangent) return
        pushHistory()
        replace(index, a.copy(sharp = sharp, handleIn = null, handleOut = null))
    }

    /** Drops the dragged tangent of anchor [index] and goes back to the automatic one. */
    fun resetTangent(index: Int) {
        if (isPath) return
        val a = anchors.getOrNull(index) ?: return
        if (!a.hasCustomTangent) return
        pushHistory()
        replace(index, a.withAutoTangent())
    }

    /** Deletes point [index] (a control point in PATH mode); with no points left a reopened path object goes. */
    fun deleteAnchor(index: Int) {
        if (isPath) {
            val s = spline ?: return
            if (index !in s.points.indices) return
            pushHistory()
            setSplineState(SplineEditing.removed(s, index))
            selectedPoint = -1
        } else {
            if (index !in anchors.indices) return
            pushHistory()
            anchors = anchors.toMutableList().also { it.removeAt(index) }
            selected = -1
        }
        if (anchors.isEmpty()) {
            // Every point of a reopened path deleted: the path object goes (one undo step).
            if (reopened != null) { commitReopened(); return }
            targetLayer = null
            endLook()
        }
        changed()
    }

    /** Moves point [index] to [p] (numeric entry; edits of the same point share one undo step). */
    fun moveAnchor(index: Int, p: Vec2) {
        if (!p.x.isFinite() || !p.y.isFinite()) return
        val lim = ShapeSettings.MAX_LENGTH
        val q = Vec2(p.x.coerceIn(-lim, lim), p.y.coerceIn(-lim, lim))
        if (isPath) {
            val s = spline ?: return
            val old = s.points.getOrNull(index) ?: return
            if (q.x == old.x && q.y == old.y) return
            pushHistory(NumericKey("move", index))
            setSplineState(SplineEditing.moved(s, index, q))
            changed()
            return
        }
        val a = anchors.getOrNull(index) ?: return
        if (q == a.pos) return
        pushHistory(NumericKey("move", index))
        replace(index, a.moved(q))
    }

    /** Thickness factor of point [index] (a control point in PATH mode), 1 when there is none. */
    fun widthOf(index: Int): Float =
        if (isPath) spline?.points?.getOrNull(index)?.width ?: 1f else anchors.getOrNull(index)?.width ?: 1f

    /**
     * Sets the thickness factor of point [index] (0..3 = 0–300 %, §4.5; a control point in PATH
     * mode). A slider drag (or a run of changes of the same point) is one undo step;
     * [endNumericEdit] ends it.
     */
    fun setWidth(index: Int, factor: Float) {
        if (!factor.isFinite()) return
        val w = factor.coerceIn(0f, CurveWidths.MAX_FACTOR)
        if (isPath) {
            val s = spline ?: return
            val old = s.points.getOrNull(index) ?: return
            if (w == old.width) return
            pushHistory(NumericKey("thickness", index))
            setSplineState(SplineEditing.withWidth(s, index, w))
            changed()
            return
        }
        val a = anchors.getOrNull(index) ?: return
        if (w == a.width) return
        pushHistory(NumericKey("thickness", index))
        replace(index, a.copy(width = w))
    }

    /** True when every point is at 100 % thickness (Compose state). */
    val uniformWidth: Boolean get() = if (isPath) SplineEditing.isUniformWidth(spline) else CurveGeometry.isUniformWidth(anchors)

    /** Puts every point back to 100 % thickness (one undo step). */
    fun resetAllWidths() {
        if (uniformWidth) return
        pushHistory()
        if (isPath) spline?.let { setSplineState(SplineEditing.uniformWidth(it)) }
        else anchors = anchors.map { if (it.width == 1f) it else it.copy(width = 1f) }
        changed()
    }

    // ------------------------------------------------------------------ Path (v1.6, §3.2)

    /** A spline with the order, endpoint and cyclic a new path starts with (no points yet). */
    private fun newSpline() = VSpline(emptyList(), settings.pathOrder, settings.pathEndpoint, settings.closed)

    /** PATH: the order shown in the strip: the pending path's, else the next path's (Compose state). */
    val pathOrder: Int get() = spline?.order ?: settings.pathOrder

    /** PATH: Endpoint of the pending path, else of the next one (Compose state). */
    val pathEndpoint: Boolean get() = spline?.endpoint ?: settings.pathEndpoint

    /** PATH: Cyclic of the pending path, else of the next one (Compose state). */
    val pathCyclic: Boolean get() = spline?.cyclic ?: settings.closed

    /**
     * PATH: sets the order (2..6; the effective order is `min(order, points)`): of the pending
     * path as one in-tool step, and of the next path.
     */
    fun setOrder(order: Int) {
        val v = order.coerceIn(VSpline.MIN_ORDER, VSpline.MAX_ORDER)
        val s = spline
        if (s != null && s.order != v) {
            pushHistory()
            setSplineState(s.copy(order = v))
        }
        if (settings.pathOrder != v) update { it.copy(pathOrder = v) } else changed()
    }

    /** PATH: Endpoint on / off (an open curve touches its first and last points); see [setOrder]. */
    fun setEndpoint(on: Boolean) {
        val s = spline
        if (s != null && s.endpoint != on) {
            pushHistory()
            setSplineState(s.copy(endpoint = on))
        }
        if (settings.pathEndpoint != on) update { it.copy(pathEndpoint = on) } else changed()
    }

    /** PATH: Cyclic on / off (closes smoothly with 3 points or more); see [setOrder]. */
    fun setCyclic(on: Boolean) {
        val s = spline
        if (s != null && s.cyclic != on) {
            pushHistory()
            setSplineState(s.copy(cyclic = on))
        }
        if (settings.closed != on) update { it.copy(closed = on) } else changed()
    }

    /** PATH: the weight of control point [index] (1 when there is none). */
    fun weightOf(index: Int): Float = spline?.points?.getOrNull(index)?.weight ?: 1f

    /**
     * PATH: sets the weight of control point [index] (0.1..10; higher pulls the curve towards
     * it). A slider drag or a run of changes of the same point is one undo step ([endNumericEdit]).
     */
    fun setWeight(index: Int, weight: Float) {
        val s = spline ?: return
        if (!weight.isFinite()) return
        val old = s.points.getOrNull(index) ?: return
        val w = weight.coerceIn(VSpline.MIN_WEIGHT, VSpline.MAX_WEIGHT)
        if (w == old.weight) return
        pushHistory(NumericKey("weight", index))
        setSplineState(SplineEditing.withWeight(s, index, w))
        changed()
    }

    /** The Path tool's quick starts (§3.2a "Shapes ▾"). */
    enum class PathShape(val label: String) { CIRCLE("Circle"), CAPSULE("Capsule") }

    /**
     * PATH, while no point exists: starts [shape] fitted to [SplinePresets.VIEW_FRACTION] of
     * [area] (the visible part of the canvas, document px): a Circle is 8 points, a Capsule 12
     * (3 : 1, fill on, stroke off), both cyclic at order 4. One in-tool step.
     */
    fun startShape(shape: PathShape, area: RectF): Boolean {
        if (!isPath || pointCount > 0 || opening) return false
        if (area.isEmpty || !area.width().isFinite() || !area.height().isFinite()) return false
        if (!controller.checkEditable()) return false
        val points = when (shape) {
            PathShape.CIRCLE -> SplinePresets.circleIn(area).let { (c, r) -> SplinePresets.circle(c, r) }
            PathShape.CAPSULE -> SplinePresets.capsuleIn(area).let { (c, h) -> SplinePresets.capsule(c, h) }
        }.map { SplineEditing.clean(it) }
        pushHistory()
        targetLayer = controller.doc.activeLayer
        setSplineState(VSpline(points, order = 4, endpoint = settings.pathEndpoint, cyclic = true))
        selectedPoint = -1
        // The Capsule's look (fill on, no line, like the user's Blender example) is this path's
        // only: the next path looks as the user set the tool up ([ownSettings]). (Cyclic and the
        // order are the spline's own: the next path's defaults stay as they were too.)
        if (shape == PathShape.CAPSULE) showLook(settings.copy(fill = true, stroke = CurveStroke.NONE))
        changed()
        return true
    }

    /**
     * The area quick starts fit into (document px): the canvas seen in [viewArea], the part of
     * the canvas view the chrome leaves free (view px, the view transform's screen space), cut to
     * the canvas; the whole canvas when that is unknown (null) or misses it. The area is centred
     * on the document point under [viewArea]'s centre and is as large as [viewArea] at the
     * current zoom (its sides swapped when the view is turned nearer a quarter turn than not), so
     * a zoomed-in, panned or turned canvas gets the shape in the middle of what shows, sized to it.
     */
    fun shapeArea(viewArea: RectF?): RectF {
        val doc = RectF(0f, 0f, controller.doc.width.toFloat(), controller.doc.height.toFloat())
        if (viewArea == null || !(viewArea.width() > 1f) || !(viewArea.height() > 1f)) return doc
        val t = controller.viewTransform
        val zoom = t.zoom
        if (!(zoom > 0f) || !zoom.isFinite()) return doc
        val c = t.screenToDoc(viewArea.centerX(), viewArea.centerY())
        if (!c.x.isFinite() || !c.y.isFinite()) return doc
        val turned = abs(abs(t.rotationDeg) - 90f) < 45f
        val hw = (if (turned) viewArea.height() else viewArea.width()) / zoom / 2f
        val hh = (if (turned) viewArea.width() else viewArea.height()) / zoom / 2f
        val v = RectF(c.x - hw, c.y - hh, c.x + hw, c.y + hh)
        return if (v.intersect(doc) && v.width() > 1f && v.height() > 1f) v else doc
    }

    /** The X / Y pill's target in PATH mode: the selected control point (hidden while none is). */
    private inner class SplinePointPosition : ObjectPosition {
        override val position: Vec2? get() = spline?.points?.getOrNull(selectedPoint)?.let { Vec2(it.x, it.y) }
        override val label: String get() = "Point ${selectedPoint + 1}"
        override fun setPosition(x: Float?, y: Float?) {
            val i = selectedPoint
            val p = spline?.points?.getOrNull(i) ?: return
            moveAnchor(i, Vec2(x?.takeIf { it.isFinite() } ?: p.x, y?.takeIf { it.isFinite() } ?: p.y))
        }
        override fun beginPositionEdit() = beginNumericEdit()
        override fun endPositionEdit() = endNumericEdit()
    }

    /**
     * Nudges the selected anchor (or the whole path when none is selected) by the nudge step.
     * A run of nudges of the same target is one undo step.
     */
    fun nudge(dx: Int, dy: Int) {
        if (anchors.isEmpty()) return
        val step = settings.nudgeStepPx
        val d = Vec2(dx * step, dy * step)
        if (isPath) {
            val s = spline ?: return
            val i = selectedPoint
            pushHistory(NumericKey("nudge", i))
            val p = s.points.getOrNull(i)
            setSplineState(if (p != null) SplineEditing.moved(s, i, Vec2(p.x, p.y) + d) else SplineEditing.translated(s, d))
            changed()
            return
        }
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
    fun handlesOf(i: Int): Pair<Vec2, Vec2> = CurveGeometry.handles(anchors, i, isClosed, tension)

    /**
     * Diameter (document px) the line has at point [index] (a control point in PATH mode): the
     * plain line's width or the brush size, times the point's thickness.
     */
    fun diameterAt(index: Int): Float {
        if (index !in 0 until pointCount) return 0f
        val base = if (strokeMode(targetLayer ?: controller.doc.activeLayer) == CurveStroke.BRUSH) baseBrush()?.size ?: lineWidth else lineWidth
        return base * CurveWidths.factor(widthOf(index))
    }

    // ------------------------------------------------------------------ handle scaling (v1.6, §3.3)

    /**
     * The Handles group's value: the factor of the change in progress, relative to the handle
     * lengths when it began; 1 (100 %) at rest (Compose state).
     */
    var handleScale by mutableFloatStateOf(1f)
        private set

    /** Which handles a change scales: both, the incoming or the outgoing one (Compose state). */
    var handleSide by mutableStateOf(HandleSide.BOTH)

    /** Every point's handles scale (each relative to its own lengths), not only the selected point's (Compose state). */
    var handleAllPoints by mutableStateOf(false)

    /** True when the Handles group applies: the Curve tool with a path of at least two points. */
    val canScaleHandles: Boolean get() = kind == CurveKind.CURVE && anchors.size >= 2

    /** The anchors when the change began, or null at rest. */
    private var handleBase: List<CurveAnchor>? = null
    private var handleTargets = IntArray(0)
    private var handleHistorySize = 0
    private var handleRedo: List<EditState> = emptyList()
    /** A two-finger pinch is scaling the handles ([onTwoFingerStart]). */
    private var pinchingHandles = false

    /** The points a change of the handles scales: the selected one, or all when "All points" is on or none is selected. */
    private fun handleTargetsNow(): IntArray {
        val sel = selected
        return if (!handleAllPoints && sel in anchors.indices) intArrayOf(sel) else IntArray(anchors.size) { it }
    }

    /**
     * A change of the handles starts (a slider drag, a held arrow, a typed value, a pinch): the
     * value is relative to the lengths now, and everything until [endHandleScale] is one in-tool
     * step.
     */
    fun beginHandleScale() {
        if (!canScaleHandles) return
        beginNumericEdit()
        handleBase = anchors
        handleTargets = handleTargetsNow()
        handleHistorySize = history.size
        handleRedo = redo.toList()
        handleScale = 1f
    }

    /**
     * Scales the handles of the change in progress (begun now if none is) to [k] times their
     * lengths when it began ([CurveGeometry.scaledHandles]: automatic tangents are made explicit
     * first, directions never change; the factor is held to 0.01..100).
     */
    fun scaleHandles(k: Float) {
        if (!k.isFinite()) return
        if (handleBase == null) beginHandleScale()
        val base = handleBase ?: return
        val f = CurveGeometry.clampHandleScale(k)
        handleScale = f
        // Back at 100 %: the handles exactly as they were (automatic tangents stay automatic, so
        // they still follow their neighbours); a change that changes nothing records no step.
        val next = if (f == 1f) base else CurveGeometry.scaledHandles(base, handleTargets, f, handleSide, isClosed, tension)
        if (next == anchors) return
        pushHistory(NumericKey("handles", -1))
        anchors = next
        changed()
    }

    /** The change of the handles is complete: the value goes back to 100 % (the next change starts from the lengths then). */
    fun endHandleScale() {
        val base = handleBase
        if (base != null && anchors == base && history.size > handleHistorySize) {
            // It ended where it began (a slider dragged back to 100 %): no step.
            while (history.size > handleHistorySize) history.removeLast()
            redo.clear(); redo.addAll(handleRedo)
            redoCount = redo.size
            canUndoStep = history.isNotEmpty()
        }
        handleBase = null
        handleScale = 1f
        controller.increments.readout = null
        endNumericEdit()
    }

    /** A typed handle scale (percent of the lengths now; exact, never stepped): one in-tool step. */
    fun applyHandleScale(percent: Float) {
        if (!percent.isFinite() || !canScaleHandles) return
        beginHandleScale()
        scaleHandles(percent / 100f)
        endHandleScale()
    }

    /**
     * ‹ › of the Handles group: the value × 0.9 / × 1.1, or ∓ / ± one Scale increment (110 %,
     * 120 % …) while increments are on. Repeats while held; [endHandleScale] when released.
     */
    fun stepHandleScale(up: Boolean) {
        if (!canScaleHandles) return
        if (handleBase == null) beginHandleScale()
        val stepPercent = controller.increments.step(IncrementKind.SCALE)
        val next = if (stepPercent != null) {
            controller.increments.factor(handleScale + (if (up) stepPercent else -stepPercent) / 100f)
        } else {
            handleScale * if (up) HANDLE_STEP_UP else HANDLE_STEP_DOWN
        }
        scaleHandles(next)
        showScaleReadout()
    }

    /**
     * A slider position as a handle scale: [factor] snapped to the Scale increment while
     * increments are on (relative to the change's start), else as it is.
     */
    fun steppedHandleScale(factor: Float): Float = controller.increments.factor(factor)

    private fun showScaleReadout() {
        if (controller.increments.enabled) controller.increments.readout = "${(handleScale * 100f).roundToInt()} %"
    }

    /** Puts the handles back as they were when the change began and drops its step (a cancelled pinch). */
    private fun revertHandleScale() {
        val base = handleBase ?: return
        while (history.size > handleHistorySize) history.removeLast()
        redo.clear(); redo.addAll(handleRedo)
        redoCount = redo.size
        canUndoStep = history.isNotEmpty()
        anchors = base
        changed()
    }

    /**
     * A pinch scales the selected point's handles when one finger starts within
     * [IbisDims.HandlePinchDistance] (on screen) of that point or the ends of its handles; the
     * rotation is ignored. Any other pinch moves the view.
     */
    override fun onTwoFingerStart(focus: Vec2, a: Vec2, b: Vec2): Boolean {
        if (!canScaleHandles) return false
        val i = selected
        val anchor = anchors.getOrNull(i) ?: return false
        val t = controller.viewTransform
        val reach = t.dp(IbisDims.HandlePinchDistance.value) * handleSize.coerceAtLeast(1f)
        val targets = ArrayList<Vec2>(3)
        targets += anchor.pos
        if (!anchor.sharp) {
            val (hIn, hOut) = handlesOf(i)
            if (hIn.length > 1e-3f) targets += anchor.pos + hIn
            if (hOut.length > 1e-3f) targets += anchor.pos + hOut
        }
        val near = listOf(a, b).any { f ->
            f.x.isFinite() && f.y.isFinite() && targets.any { q -> t.docToScreen(f).distanceTo(t.docToScreen(q)) <= reach }
        }
        if (!near) return false
        beginHandleScale()
        pinchingHandles = true
        return true
    }

    override fun onTwoFingerGesture(translation: Vec2, scale: Float, rotationDeg: Float) {
        if (!pinchingHandles || !scale.isFinite() || scale <= 0f) return
        scaleHandles(controller.increments.factor(scale))
        showScaleReadout()
    }

    override fun onTwoFingerEnd(cancelled: Boolean) {
        if (!pinchingHandles) return
        pinchingHandles = false
        if (cancelled) revertHandleScale()
        endHandleScale()
    }

    // ------------------------------------------------------------------ handle size on screen (v1.6, §3.3)

    /** "Handle size" (app-wide, 75–200 %): the drawn size and grab radius of points and handles. */
    val handleSize: Float get() = controller.settings.curveHandleScale

    /** Sets "Handle size" ([AppSettings.MIN_CURVE_HANDLE_SCALE]..[AppSettings.MAX_CURVE_HANDLE_SCALE]). */
    fun setHandleSize(v: Float) {
        if (!v.isFinite()) return
        controller.settings.curveHandleScale = v.coerceIn(AppSettings.MIN_CURVE_HANDLE_SCALE, AppSettings.MAX_CURVE_HANDLE_SCALE)
        controller.invalidateOverlay()
    }

    /** Grab radius of points and handles (document px): [HANDLE_TOUCH_DP] at the user's handle size. */
    private fun grabRadius(): Float = controller.docLength(HANDLE_TOUCH_DP * handleSize)

    // ------------------------------------------------------------------ input

    override fun onDown(p: ToolPoint) {
        val pt = Vec2(p.x, p.y)
        downPoint = pt
        moved = false
        longPressed = false
        gestureStart = currentState()
        gestureSelected = selected
        gestureSelectedPoint = selectedPoint
        gestureHistorySize = history.size
        gestureRedo = redo.toList()
        reopenCandidate = null
        reopenKind = kind
        snap.end()
        snapMoving = null
        if (anchors.isEmpty() && !controller.checkEditable()) { drag = Drag.IGNORE; return }
        if (isPath) { pathDown(pt); return }
        val tol = grabRadius()
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
                // (The handle's end: increments step it from there.)
                dragStartPos = a + if (dOut <= dIn) hOut else hIn
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
            dragStartPos = anchors[idx].pos
            beginSnap(except = idx)
            return
        }
        if (anchors.isEmpty() && armReopen(pt)) return
        newAnchorAt(pt, controller.docLength(HANDLE_TOUCH_DP))
    }

    /**
     * With no path pending, a tap on a path object of the vector layer reopens it (a drag starts
     * a new path): in this tool, or in the one that edits it (Curve / Polyline ↔ Path). True when
     * the touch is armed for that.
     */
    private fun armReopen(pt: Vec2): Boolean {
        val (path, k) = reopenablePathAt(pt) ?: return false
        reopenCandidate = path
        reopenKind = k
        drag = Drag.REOPEN
        return true
    }

    /** PATH: the touch went down at [pt] (see the class docs for the gestures). */
    private fun pathDown(pt: Vec2) {
        val s = spline
        val idx = if (s != null) SplineEditing.nearestPoint(s.points, pt, grabRadius()) else -1
        if (s != null && idx >= 0) {
            drag = Drag.ANCHOR
            dragIndex = idx
            dragStartPos = SplineEditing.pos(s.points[idx])
            beginSnap(except = idx)
            return
        }
        if (anchors.isEmpty() && armReopen(pt)) return
        newPointAt(pt)
    }

    /**
     * PATH: a new control point under the finger at [pt]: inserted on the control polygon when
     * the finger is within [IbisDims.PathInsertDistance] of it between two points, else after the selected point
     * (which then moves on to the new one, as Blender extrudes from the selected end; v1.7, item
     * 19: before it when it is the first point of an open path of at least 2 points) or at the
     * end, with the selected point's thickness. It snaps right away and follows the finger until
     * it lifts.
     */
    private fun newPointAt(pt: Vec2) {
        val s0 = spline
        if (s0 != null && s0.points.size >= VSpline.MAX_POINTS) {
            drag = Drag.IGNORE
            controller.toast(TOO_MANY_POINTS)
            return
        }
        beginSnap(except = -1)
        pushHistory()
        val s = s0 ?: newSpline()
        val hit = if (s.points.size >= 2) SplineEditing.polygonHit(s, pt) else null
        val keepSelecting = selectedPoint in s.points.indices
        val at: Int
        val point: VSplinePoint
        // (Only between two points: beyond a segment's end, e.g. just outside a corner, the
        // nearest place is a point itself, and a point is never doubled there.)
        if (hit != null && hit.interior && hit.distance <= controller.docLength(IbisDims.PathInsertDistance.value)) {
            at = hit.segment + 1
            val q = snapAnchor(hit.point)
            point = SplineEditing.pointOnPolygon(s, hit, q)
        } else {
            at = when {
                // v1.7 (item 19): the first point of an open path extends it from its start.
                keepSelecting && selectedPoint == 0 && !s.cyclic && s.points.size >= 2 -> 0
                keepSelecting -> selectedPoint + 1
                else -> s.points.size
            }
            val q = snapAnchor(pt)
            // The selected point's thickness (else the last one's).
            val width = (if (keepSelecting) s.points[selectedPoint] else s.points.lastOrNull())?.width ?: 1f
            point = VSplinePoint(q.x, q.y, width = width)
        }
        if (targetLayer == null) targetLayer = controller.doc.activeLayer
        setSplineState(SplineEditing.inserted(s, at, point))
        selectedPoint = if (keepSelecting) at else -1
        dragIndex = at
        dragStartPos = Vec2(point.x, point.y)
        drag = Drag.NEW_ANCHOR
        changed(brushDelayMs = NEW_POINT_BRUSH_DELAY_MS)
    }

    /** Adds a new anchor under the finger at [pt]: inserted when on the path, appended otherwise. */
    private fun newAnchorAt(pt: Vec2, tol: Float) {
        // New anchor: inserted when tapping on the path, appended otherwise. It snaps right away
        // (to objects, the other anchors and, with grid snapping, the grid).
        beginSnap(except = -1)
        pushHistory()
        val hit = if (anchors.size >= 2) CurveGeometry.nearest(anchors, pt, isClosed, tension, polyline) else null
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
        dragStartPos = list[dragIndex].pos
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
                if (isPath) newPointAt(downPoint) else newAnchorAt(downPoint, controller.docLength(HANDLE_TOUCH_DP))
                onMove(p)
            }
            Drag.ANCHOR, Drag.NEW_ANCHOR -> {
                if (!moved && pt.distanceTo(downPoint) < controller.docLength(TOUCH_SLOP_DP)) return
                if (!moved && drag == Drag.ANCHOR) pushHistory()
                moved = true
                // The preview follows the finger as cheaply as possible until it lifts.
                setDragging(true)
                if (isPath) {
                    val s = spline ?: return
                    if (dragIndex !in s.points.indices) return
                    setSplineState(SplineEditing.moved(s, dragIndex, dragTarget(pt)))
                    changed()
                    return
                }
                val a = anchors.getOrNull(dragIndex) ?: return
                // What the finger alone gives is snapped (never the last snapped place), so moving
                // farther than the snap distance lets go of a guide.
                replace(dragIndex, a.moved(dragTarget(pt)))
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
                val end = handleEnd(pt)
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
                !longPressed -> select(if (selectedIndex == dragIndex) -1 else dragIndex)
            }
            Drag.NEW_ANCHOR -> {
                onMove(p)
                // A tap: the point stays, so its brush stroke can show right away.
                if (!moved) changed()
            }
            Drag.HANDLE_IN, Drag.HANDLE_OUT -> if (moved) onMove(p)
            Drag.REOPEN -> {
                val path = reopenCandidate
                val k = reopenKind
                reopenCandidate = null
                drag = Drag.NONE
                if (path != null) {
                    if (k == kind) reopen(path) else switchAndReopen(k, path)
                }
            }
            Drag.NONE, Drag.IGNORE -> {}
        }
        drag = Drag.NONE
        endSnap()
        controller.increments.readout = null
        // The drag is over: a plain line / fill goes back into the layer, a brush stroke is drawn
        // exactly once the path rests a moment.
        setDragging(false)
        controller.invalidateOverlay()
    }

    override fun onCancel() {
        setDragging(false)
        reopenCandidate = null
        if (drag != Drag.NONE && drag != Drag.IGNORE && drag != Drag.REOPEN) {
            if (isPath) setSplineState(gestureStart.spline) else anchors = gestureStart.anchors
            selected = gestureSelected
            selectedPoint = gestureSelectedPoint
            while (history.size > gestureHistorySize) history.removeLast()
            redo.clear(); redo.addAll(gestureRedo)
            redoCount = redo.size
            historyKey = null
            canUndoStep = history.isNotEmpty()
            if (anchors.isEmpty() && reopened == null) { targetLayer = null; endLook() }
        }
        drag = Drag.NONE
        endSnap()
        controller.increments.readout = null
        changed()
    }

    /**
     * Where a dragged point goes for the finger at [pt]: snapped to objects, then the grid (the
     * v1.5 [snapAnchor]); while increments are on, an axis that neither snapped moves by a
     * multiple of the Length step from where the point was when the drag began (§3.4: object
     * guide, then grid, then the increment), and the readout shows the move.
     */
    private fun dragTarget(pt: Vec2): Vec2 {
        val snapped = snapAnchor(pt)
        val step = controller.increments.step(IncrementKind.LENGTH) ?: return snapped
        // Which axes a guide or the grid placed (asked, not told from the snapped value: a finger
        // exactly on a grid line or guide is placed too, and keeps that place).
        val g = controller.grid
        val grid = g.enabled && g.snap && g.type == GridType.SQUARE && g.spacingPx > 0f
        val placedX = grid || snap.snapValue(pt.x, SnapAxis.X) != null
        val placedY = grid || snap.snapValue(pt.y, SnapAxis.Y) != null
        val d = pt - downPoint
        val x = if (placedX) snapped.x else dragStartPos.x + IncrementMath.snapDelta(d.x, step)
        val y = if (placedY) snapped.y else dragStartPos.y + IncrementMath.snapDelta(d.y, step)
        val q = Vec2(x, y)
        snapMoving = q
        controller.increments.readout = "${signed(x - dragStartPos.x)}, ${signed(y - dragStartPos.y)} px"
        return q
    }

    /**
     * Where a dragged tangent handle's end goes for the finger at [pt]: snapped to objects and
     * anchors (never to the grid); while increments are on, an axis no guide placed moves by a
     * multiple of the Length step from where the end was grabbed (§3.4, as the Shape tool's
     * handles), and the readout shows the move. Off: exactly v1.5.
     */
    private fun handleEnd(pt: Vec2): Vec2 {
        val step = controller.increments.step(IncrementKind.LENGTH) ?: return snap.snapPointToObjects(pt)
        val hx = snap.snapValue(pt.x, SnapAxis.X)
        val hy = snap.snapValue(pt.y, SnapAxis.Y)
        val snapped = snap.applyPointHits(pt, hx, hy)
        val d = pt - downPoint
        val x = if (hx != null) snapped.x else dragStartPos.x + IncrementMath.snapDelta(d.x, step)
        val y = if (hy != null) snapped.y else dragStartPos.y + IncrementMath.snapDelta(d.y, step)
        controller.increments.readout = "${signed(x - dragStartPos.x)}, ${signed(y - dragStartPos.y)} px"
        return Vec2(x, y)
    }

    private fun signed(v: Float): String {
        val r = v.roundToInt()
        return if (r > 0) "+$r" else if (r < 0) "−${abs(r)}" else "0"
    }

    /** Switches to the tool of [k] (Curve / Polyline ↔ Path) and reopens [path] there. */
    private fun switchAndReopen(k: CurveKind, path: VPath) {
        val id = when (k) {
            CurveKind.CURVE -> ToolId.CURVE
            CurveKind.POLYLINE -> ToolId.POLYLINE
            CurveKind.PATH -> ToolId.PATH
        }
        val other = controller.tools[id] as? CurveTool ?: return
        controller.selectTool(id)
        if (controller.currentTool === other) other.reopen(path)
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
        if (isPath) spline?.points?.forEachIndexed { i, p -> if (i != except) others += Vec2(p.x, p.y) }
        else anchors.forEachIndexed { i, a -> if (i != except) others += a.pos }
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

    /**
     * A path object of the active vector layer under [p] that a tap reopens, and the kind of tool
     * that edits it ([editorKindOf]): this one, or Path for a Curve / Polyline tapping a spline
     * path, or Curve / Polyline for Path tapping a plain path. (Curve and Polyline still leave
     * each other's paths alone, as in v1.5.)
     */
    private fun reopenablePathAt(p: Vec2): Pair<VPath, CurveKind>? {
        if (opening) return null
        val layer = controller.doc.activeLayer
        if (!vectorTarget(layer) || controller.doc.effectiveLocked(layer) || !controller.doc.effectiveVisible(layer)) return null
        val tol = controller.docLength(PATH_HIT_DP)
        val hit = controller.vectors.hitTest(layer, p, tol) as? VPath ?: return null
        if (!hit.isCurveEditable || hit.subpaths[0].anchors.size < 2) return null
        val k = editorKindOf(hit)
        val switches = k != kind && (isPath || k == CurveKind.PATH)
        if (k != kind && !switches) return null
        // A filled path with a visible line reopens from its line: a tap inside its fill starts
        // a new path there (curves are drawn over filled shapes; a fill alone reopens anywhere).
        val reach = lineReach(hit)
        if (hit.fill != null && reach > 0f && distanceToLine(hit, p) > reach + tol) return null
        return hit to k
    }

    /**
     * The tool that edits [p]: Path when it keeps a spline that passes the I9 check
     * ([SplineBezier.matches]; else the spline is stale and the path is a plain Bézier path),
     * otherwise Polyline or Curve by its corners.
     */
    private fun editorKindOf(p: VPath): CurveKind = when {
        p.spline != null && SplineBezier.matches(p) -> CurveKind.PATH
        p.polyline -> CurveKind.POLYLINE
        else -> CurveKind.CURVE
    }

    /** How far [p]'s line paints from its centre line (document px): 0 when it paints nothing. */
    private fun lineReach(p: VPath): Float {
        val st = p.stroke ?: return 0f
        val w = st.width.takeIf { it.isFinite() && it > 0f } ?: return 0f
        val anchors = p.subpaths.firstOrNull()?.anchors ?: return 0f
        var m = 0f
        for (a in anchors) m = max(m, CurveWidths.factor(a.width))
        return w * m / 2f
    }

    /** Distance from [q] to [p]'s centre line (document px). */
    private fun distanceToLine(p: VPath, q: Vec2): Float {
        var best = Float.POSITIVE_INFINITY
        for (poly in VectorOps.toVectorPath(p).flatten(0.5f)) {
            val pts = poly.points
            if (pts.size == 1) best = min(best, q.distanceTo(pts[0]))
            for (i in 1 until pts.size) best = min(best, Geometry.distanceToSegment(q, pts[i - 1], pts[i]))
            if (poly.closed && pts.size > 2) best = min(best, Geometry.distanceToSegment(q, pts.last(), pts[0]))
        }
        return best
    }

    /**
     * True for a single-subpath path this tool edits: curves in the Curve tool, polylines in the
     * Polyline tool, splines that pass the I9 check in the Path tool ([editorKindOf]).
     */
    private fun canReopen(p: VPath): Boolean =
        p.isCurveEditable && p.subpaths[0].anchors.size >= 2 && editorKindOf(p) == kind

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
        val sp = path.spline
        if (isPath && sp != null) {
            // The control points come back; the strip shows the path's order, endpoint and cyclic.
            val clean = sp.sanitized()
            setSplineState(clean)
            settings = settings.copy(closed = clean.cyclic, pathOrder = clean.order, pathEndpoint = clean.endpoint)
        } else {
            anchors = path.subpaths[0].anchors.map { it.toCurveAnchor() }
        }
        selected = -1
        selectedPoint = -1
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
            taperPercent = if (brushTaper && st != null) st.taperPercent.coerceIn(1f, 50f) else taperPercent,
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
        // Unchanged (or a brush stroke whose replay is still waiting): the path's own pixels.
        val useFloating = floating != null && !floating.isRecycled &&
            (pristine || (strokeMode(r.layer) == CurveStroke.BRUSH && !brushPreview.isLive && brushPreview.hasPending && sessionSpecs.isEmpty() && sessionGradient == null))
        if (floating != null && useFloating) {
            val fr = s.floatingRect
            if (s.floatingScale == 1f) {
                canvas.drawBitmap(floating, fr.left.toFloat(), fr.top.toFloat(), null)
            } else {
                canvas.drawBitmap(floating, null, RectF(fr), floatingPaint)
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

    /**
     * How the path's line is stored on [layer] (a brush that needs pixels is a plain line on a
     * vector layer), whatever the points' thickness.
     */
    private fun strokeKind(layer: Layer): CurveStroke {
        val s = settings.stroke
        if (s != CurveStroke.BRUSH || !vectorTarget(layer) || brushFitsVector()) return s
        return CurveStroke.PLAIN
    }

    /**
     * True when every point is at 0 % thickness: the line draws nothing (as a vector layer
     * renders such a path), only the fill if there is one.
     */
    private fun lineDrawsNothing(): Boolean = anchors.isNotEmpty() && !(CurveWidths.maxFactor(anchors) > 0f)

    /** How the path is drawn on [layer] right now: [strokeKind], or no line at all when [lineDrawsNothing]. */
    private fun strokeMode(layer: Layer): CurveStroke =
        if (lineDrawsNothing()) CurveStroke.NONE else strokeKind(layer)

    /** Message shown once per path when the brush can't be used on a vector layer. */
    private var warnedPlain = false

    /**
     * True when "Current brush" draws a plain line instead: the brush needs pixels (smudge,
     * blur, watercolor) and the path goes onto a vector layer (follows layer changes in Compose).
     */
    val brushDrawsPlain: Boolean
        get() {
            controller.layersVersion
            return settings.stroke == CurveStroke.BRUSH && strokeKind(targetLayer ?: controller.doc.activeLayer) == CurveStroke.PLAIN
        }

    // ------------------------------------------------------------------ preview

    /** Plain items of the path: the fill (when on) and the plain line (when that is the stroke). */
    private fun buildSpecs(path: VectorPath, mode: CurveStroke, gradientFill: Boolean = false): List<VectorPaintSpec> {
        if (anchors.size < 2) return emptyList()
        val s = settings
        val fill = if (s.fill && canFill && !gradientFill) path else null
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
        val closed = isClosed && anchors.size > 2
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
        val wMax = profileMax(WidthProfile(CurveWidths.atSamples(anchors, isClosed && anchors.size > 2, tension, polyline, sampleScratch.size)))
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
            val closed = isClosed && a.size > 2
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
        // (Path: the same spline and look are unchanged even when the stored Bézier form was
        // cut differently, e.g. an approximated spline mapped by a transform.)
        pristine = vp != null && (vp == r.original || (isPath && vp.spline != null && vp.copy(subpaths = r.original.subpaths) == r.original))
        val gradient = r.original.fill?.takeIf { it !is VPaint.Solid && settings.fill && settings.fillColor == null && canFill }
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
            val closed = isClosed && a.size > 2
            brushPreview.request(g, brushDelayMs) { brushPoints(path, g, a, closed, it) }
        } else {
            brushPreview.cancel()
        }
    }

    private fun roundOut(b: RectF): Rect =
        if (b.isEmpty) Rect() else Rect(floor(b.left).toInt(), floor(b.top).toInt(), ceil(b.right).toInt(), ceil(b.bottom).toInt())

    /** Runs a waiting live-brush replay now (the main looper does it otherwise). */
    internal fun flushPreview() = brushPreview.flush()

    /** The random values the brush stroke of the pending path is painted with (tests). */
    internal val brushSeed: Long get() = brushPreview.sessionSeed

    /** True while the painting tool's unfinished stroke shows the pending path (tests). */
    internal val brushLive: Boolean get() = brushPreview.isLive

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
        val closed = isClosed && anchors.size > 2
        val color = controller.color
        val orig = r?.original
        val origStroke = orig?.stroke
        // (A line at 0 % everywhere keeps its style next to a fill: thickness can come back.)
        val stroke = when (strokeKind(layer)) {
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
        val fill = if (s.fill && canFill) {
            val g = orig?.fill?.takeIf { it !is VPaint.Solid && s.fillColor == null }
            g ?: VPaint.Solid(s.fillColor ?: color)
        } else {
            null
        }
        if (fill == null && (stroke == null || lineDrawsNothing())) return null
        if (isPath) {
            // I9: the spline (sanitize-stable, as the codec reads it back) and EXACTLY its Bézier form.
            val sp = spline?.sanitized() ?: return null
            return VPath(
                id = orig?.id ?: 0L,
                opacity = orig?.opacity ?: 1f,
                subpaths = listOf(SplineBezier.toSubpath(sp)),
                tension = 0f,
                polyline = false,
                fillRule = orig?.fillRule ?: VFillRule.NONZERO,
                fill = fill,
                stroke = stroke,
                spline = sp,
            )
        }
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
        val closed = isClosed && a.size > 2
        brushOverride = if (brush) brushFor(path, g) else null
        resetPath()
        // Fill and brush stroke are ONE undo step, named after the tool (a plain line / fill
        // alone keeps its own name).
        val step: (String, () -> Unit) -> Unit = if (brush) controller::undoStepNamed else controller::groupUndo
        step(stepLabel) {
            if (specs.isNotEmpty()) {
                // The fill goes under the stroke, so the stroke is painted after it (smudge /
                // blur previews edit the pixels: they are restored first).
                brushPreview.cancel()
                val label = when {
                    mode != CurveStroke.PLAIN -> "Fill path"
                    polyline -> "Polyline"
                    else -> stepLabel
                }
                VectorCommit.commit(controller, layer, specs, label)
            }
            if (brush) brushPreview.commit(g) { brushPoints(path, g, a, closed, it) }
        }
        brushPreview.end()
        brushOverride = null
        endLook()
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
        val label = stepLabel
        val path = path()
        val g = brushGeometry(path, settings)
        val a = anchors
        val closed = isClosed && a.size > 2
        // (The painting tool paints the active layer: a path on another layer is drawn by its
        // layer, as is a brush line at 0 % everywhere, which paints nothing.)
        if (vp.stroke?.kind != VStrokeKind.BRUSH || strokeMode(layer) != CurveStroke.BRUSH || layer !== controller.doc.activeLayer) {
            brushPreview.cancel()
            val ids = controller.vectors.addObjects(layer, listOf(vp), label)
            // Refused (no memory...): the path stays pending.
            if (ids.isEmpty()) return
            resetPath()
            brushPreview.end()
            endLook()
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
        endLook()
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
            // (Untouched: the object as it was, so nothing is recorded.)
            val replacement = if (pristine && vp != null) r.original else vp?.copy(id = r.original.id)
            endReopen(cancelSession = false)
            r.session.commit(listOfNotNull(replacement), EDIT_PATH_LABEL)
            // A Path-tool path edited here (after To Bézier) is a plain Bézier path from now on.
            if (r.original.spline != null && replacement != null && replacement.spline == null) controller.toast(EDITED_AS_BEZIER)
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
        spline = null
        selectedPoint = -1
        handleBase = null
        handleScale = 1f
        pinchingHandles = false
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
        // A held edit never carries over to the next path (a slider that left the screen mid-drag).
        numericHeld = false
        canUndoStep = false
    }

    private fun clear() {
        opening = false
        resetPath()
        brushPreview.end()
        brushOverride = null
        endReopen()
        endLook()
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
        val scale = handleSize
        if (isPath) {
            drawPathPoints(canvas, t, scale)
            snap.draw(canvas, t, snapMoving?.let { pointBox(it) })
            return
        }
        val sel = selected
        if (!polyline && sel in list.indices && !list[sel].sharp) {
            val (hIn, hOut) = handlesOf(sel)
            val a = map(t, list[sel].pos).let { it[0] to it[1] }
            for (h in listOf(hIn, hOut)) {
                if (h.length < 1e-3f) continue
                val q = map(t, list[sel].pos + h).let { it[0] to it[1] }
                painter.line(canvas, t, a.first, a.second, q.first, q.second)
                pointPainter.handle(canvas, t, q.first, q.second, scale, small = true, active = true)
            }
        }
        // The real diameter at the selected point while its thickness is changed.
        if (thicknessRing && sel in list.indices) drawThicknessRing(canvas, t, list[sel].pos, diameterAt(sel))
        for (i in list.indices) {
            val q = map(t, list[i].pos)
            pointPainter.handle(canvas, t, q[0], q[1], scale, square = list[i].sharp || polyline, active = i == sel)
        }
        // Smart guides of the dragged point (on top, labels away from the finger).
        snap.draw(canvas, t, snapMoving?.let { pointBox(it) })
    }

    /** PATH: the dashed control polygon, the thickness ring and the control points (the selected one orange). */
    private fun drawPathPoints(canvas: Canvas, t: ViewTransform, scale: Float) {
        val s = spline ?: return
        pointPainter.controlPolygon(canvas, t, s)
        val sel = selectedPoint
        if (thicknessRing && sel in s.points.indices) drawThicknessRing(canvas, t, SplineEditing.pos(s.points[sel]), diameterAt(sel))
        for (i in s.points.indices) {
            val q = map(t, SplineEditing.pos(s.points[i]))
            pointPainter.controlPoint(canvas, t, q[0], q[1], selected = i == sel, scale = scale)
        }
    }

    /** A dashed ring of diameter [d] (document px) around [at]. */
    private fun drawThicknessRing(canvas: Canvas, t: ViewTransform, at: Vec2, d: Float) {
        if (!(d > 0f) || !d.isFinite()) return
        ringPath.rewind()
        ringPath.addCircle(at.x, at.y, d / 2f, Path.Direction.CW)
        painter.path(canvas, t, ringPath, dashed = true)
    }

    // ------------------------------------------------------------------ To Bézier (v1.6, §3.2a)

    /**
     * Pending work handed from one curve tool to another: the Path tool's spline to the Curve
     * tool as Bézier anchors ([toBezier]), and back again when that step is undone.
     */
    private class Handoff(
        val anchors: List<CurveAnchor>,
        val spline: VSpline?,
        val targetLayer: Layer?,
        /** The look the pending path shows (stroke, fill, widths, closed...). */
        val look: CurveSettings,
        val reopened: Reopened?,
        /** The random values of its brush stroke (the texture stays the same). */
        val seed: Long,
        /** The giving tool's in-tool history (a hand-back restores it). */
        val history: List<EditState>,
    )

    /**
     * PATH: turns the pending path into a Curve-tool path (smooth anchors with both handles set,
     * so every handle can be grabbed, V7) and switches to the Curve tool, which then holds it as
     * pending work; a reopened path stays open there and ✓ stores it as a plain Bézier path
     * (the spline is dropped). In the Curve tool this is one in-tool step: undoing it hands the
     * path back to the Path tool exactly as it was. False when there is nothing to convert.
     */
    fun toBezier(): Boolean {
        if (!isPath || anchors.size < 2 || drag != Drag.NONE || opening) return false
        val curve = controller.tools[ToolId.CURVE] as? CurveTool ?: return false
        if (curve === this || curve.hasPendingWork) return false
        val mine = detachPending()
        controller.selectTool(ToolId.CURVE)
        val back = Handoff(emptyList(), mine.spline, mine.targetLayer, mine.look, null, mine.seed, mine.history)
        curve.adopt(
            Handoff(mine.anchors, null, mine.targetLayer, mine.look, mine.reopened, mine.seed, emptyList()),
            history = listOf(EditState(emptyList(), null, back = back)),
        )
        return true
    }

    /** Curve: the first step after [toBezier] is undone: the path goes back to the Path tool as it was. */
    private fun handBack(back: Handoff): Boolean {
        val path = controller.tools[ToolId.PATH] as? CurveTool ?: return false
        if (path === this || path.hasPendingWork) return false
        val mine = detachPending()
        controller.selectTool(ToolId.PATH)
        path.adopt(
            Handoff(emptyList(), back.spline, mine.targetLayer, back.look, mine.reopened, mine.seed, emptyList()),
            history = back.history,
        )
        // Redo converts it again.
        path.redo.addLast(EditState(emptyList(), null, toBezier = true))
        path.redoCount = path.redo.size
        return true
    }

    /**
     * Forgets the pending path WITHOUT committing it or closing a reopened path's edit session
     * (another curve tool takes both over): the previews go, the user's own settings come back,
     * the main color stays the path's. Returns what is handed over.
     */
    private fun detachPending(): Handoff {
        // The live stroke goes first (its callback still sees the reopened session).
        brushPreview.cancel()
        val r = reopened
        val h = Handoff(
            anchors, spline, targetLayer ?: controller.doc.activeLayer, settings.copy(closed = if (isPath) pathCyclic else isClosed), r,
            brushPreview.sessionSeed, history.toList(),
        )
        if (r != null) {
            reopened = null
            reopenedState = false
            pristine = true
            r.session.drawPreview = null
            r.session.inner = null
            invalidateRegions(sessionRegions)
            sessionRegions = emptyList()
            sessionSpecs = emptyList()
            sessionGradient = null
            settings = r.userSettings.sanitized()
        }
        resetPath()
        // (A look the path showed went along with it: this tool's own settings come back.)
        endLook()
        brushPreview.end()
        brushOverride = null
        controller.invalidateOverlay()
        return h
    }

    /**
     * Takes over the pending path [h] (this tool is current and has nothing pending), with the
     * in-tool [history]: a reopened path's edit session draws through this tool from now on.
     */
    private fun adopt(h: Handoff, history: List<EditState>) {
        val r = h.reopened
        if (r != null) {
            reopened = Reopened(r.layer, r.session, r.original, settings, r.userColor, r.openedColor, r.width)
            reopenedState = true
            pristine = false
            // The strip shows the path's look (the unit and nudge step stay the user's).
            settings = h.look.copy(unit = settings.unit, nudgeStepPx = settings.nudgeStepPx).sanitized(settings)
            r.session.drawPreview = { canvas -> drawSessionPreview(canvas) }
        } else {
            // A new path keeps its look while it is pending; this tool's own settings (what its
            // next path looks like) are untouched and come back when it ends ([ownSettings]).
            val l = h.look
            showLook(
                settings.copy(
                    closed = l.closed, stroke = l.stroke, plainWidth = l.plainWidth, useBrushSize = l.useBrushSize,
                    fill = l.fill, fillColor = l.fillColor, taper = l.taper, taperPercent = l.taperPercent,
                ),
            )
        }
        targetLayer = h.targetLayer
        if (isPath) setSplineState(h.spline) else anchors = h.anchors
        selected = -1
        selectedPoint = -1
        this.history.clear()
        this.history.addAll(history)
        redo.clear()
        redoCount = 0
        historyKey = null
        canUndoStep = this.history.isNotEmpty()
        brushPreview.useSeed(h.seed)
        changed()
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
        /** Undo label of ✓ of a new Path-tool path. */
        const val PATH_LABEL = "Path"
        /** Shown when ✓ of the Curve tool stores a former Path-tool path without its spline. */
        const val EDITED_AS_BEZIER = "Path edited as a Bézier curve"
        /** Shown when a point is added to a path that has [VSpline.MAX_POINTS] already. */
        const val TOO_MANY_POINTS = "A path has at most ${VSpline.MAX_POINTS} control points"
        /** ‹ › of the Handles group without increments: × 1.1 / × 0.9 per step. */
        const val HANDLE_STEP_UP = 1.1f
        const val HANDLE_STEP_DOWN = 0.9f
        private const val OPAQUE = 0xFF000000.toInt()
    }
}
