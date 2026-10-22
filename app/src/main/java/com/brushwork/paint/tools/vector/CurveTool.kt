package com.brushwork.paint.tools.vector

import android.graphics.Canvas
import android.graphics.Matrix
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
import com.brushwork.paint.core.Affine2
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
import com.brushwork.paint.tools.ColoredObjectTool
import com.brushwork.paint.tools.DeletingTool
import com.brushwork.paint.tools.ErasableTool
import com.brushwork.paint.tools.ObjectColorEditor
import com.brushwork.paint.tools.ObjectDeletion
import com.brushwork.paint.tools.ObjectPosition
import com.brushwork.paint.tools.ObjectScale
import com.brushwork.paint.tools.PillPositionTool
import com.brushwork.paint.tools.ScaledTool
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.points.BevelPoint
import com.brushwork.paint.tools.points.CornerTool
import com.brushwork.paint.tools.points.PointBevel
import com.brushwork.paint.tools.points.PointCornerEditor
import com.brushwork.paint.tools.points.PointEditor
import com.brushwork.paint.tools.points.PointGizmo
import com.brushwork.paint.tools.points.PointGroupMath
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.select.pointBox
import com.brushwork.paint.tools.select.pointLines
import com.brushwork.paint.tools.select.applyPointHits
import com.brushwork.paint.tools.select.snapPointToObjects
import com.brushwork.paint.tools.transform.SnapAxis
import com.brushwork.paint.tools.transform.SnapGuide
import com.brushwork.paint.tools.vector.gen.CurveGenerators
import com.brushwork.paint.tools.vector.spline.NurbsGeometry
import com.brushwork.paint.tools.vector.spline.PathOverlay
import com.brushwork.paint.tools.vector.spline.SplineBezier
import com.brushwork.paint.tools.vector.spline.SplineEditing
import com.brushwork.paint.tools.vector.spline.SplinePresets
import com.brushwork.paint.ui.common.CurveGenLabels18
import com.brushwork.paint.ui.common.CurveLabels19
import com.brushwork.paint.ui.common.EraseLabels19
import com.brushwork.paint.ui.common.MirrorLabels18
import com.brushwork.paint.ui.common.PillLabels
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.common.PointLabels19
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.ui.theme.IbisDims
import com.brushwork.paint.vector.CopyMaps
import com.brushwork.paint.vector.CurveGenKind
import com.brushwork.paint.vector.CurveGenerator
import com.brushwork.paint.vector.ObjectCopies
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
import com.brushwork.paint.vector.draw.EraseCut
import com.brushwork.paint.vector.draw.EraseSource
import com.brushwork.paint.vector.draw.ToolErase
import com.brushwork.paint.vector.draw.ToolEraseResult
import com.brushwork.paint.vector.draw.ToolEraseSession
import com.brushwork.paint.vector.draw.VectorEraserModes
import com.brushwork.paint.vector.edit.VectorEditSession
import com.brushwork.paint.vector.pathfinder.PathConvert
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
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

/**
 * v1.7 (item 7): what a curve tool's path draws, the strip's three segments: its line only, its
 * fill only (the stroke kind is then [CurveStroke.NONE]; the kind before it is kept in
 * [CurveSettings.lastStroke]), or both.
 */
enum class CurvePaint { STROKE, FILL, BOTH }

/** Persisted options of the curve / polyline tools. Lengths are document pixels. */
@OptIn(ExperimentalSerializationApi::class)
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
    /**
     * v1.9 (R14, D22; CurveLabels19.OWN_LAYER): a new Curve, Polyline or Path line drawn on a
     * pixel layer goes into a vector layer of its own and stays editable; off (default) paints it
     * into the pixel layer as v1.8 does. Never written while off (I13), so v1.8 reads the settings.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val ownLayer: Boolean = false,
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
 *   switches to the tool that edits it (v1.9: Curve and Polyline swap the same way, with a
 *   toast and [slotFlash]). [toBezier] hands the pending path to the Curve tool
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
class CurveTool(controller: EditorController, val kind: CurveKind) :
    Tool(controller), PointEditor, PillPositionTool, ScaledTool, DeletingTool, ErasableTool, ColoredObjectTool, CornerTool {
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
     * In PATH mode, the X / Y pill's target: since v1.7 the tool's one pill source ([pillPosition],
     * the same stable instance), which every curve tool has; null for Curve and Polyline (v1.6 API).
     */
    val splinePointPosition: ObjectPosition? get() = if (kind == CurveKind.PATH) pillPosition else null

    /** Current options (Compose state); change them with [update]. */
    var settings by mutableStateOf(loadSettings())
        private set

    /**
     * Anchors of the pending path (Compose state). In PATH mode they are derived from [spline]
     * (its Bézier form) and never edited directly.
     */
    var anchors by mutableStateOf<List<CurveAnchor>>(emptyList())
        private set

    /**
     * v1.7 (item 1, I12): the selected points, control points in PATH mode, else anchors
     * (Compose state). Read it through [pointSelection], which matches it to [pointCount].
     */
    private var selectionState by mutableStateOf(PointSelection.none(0))

    override val pointSelection: PointSelection
        get() {
            val s = selectionState
            val n = pointCount
            return if (s.size == n) s else s.resized(n)
        }

    /**
     * Index of the selected anchor or -1 (always -1 in PATH mode: see [selectedPoint]); with
     * several points selected, the one selected last ([PointSelection.primary]). Setting it
     * selects that point alone.
     */
    var selected: Int
        get() = if (isPath) -1 else pointSelection.primary
        private set(v) {
            if (!isPath) selectionState = pointSelection.only(v)
        }

    /** PATH: the control points, order, endpoint and cyclic of the pending path (Compose state); null when none. */
    var spline by mutableStateOf<VSpline?>(null)
        private set

    /**
     * v1.8 (item 6, §3.7): the generator of the open path (a spiral, wave or rose picked from
     * [CurveGenLabels18.CURVES], or a reopened path that still matches its own, I16), or null (Compose state; always
     * null for Polyline). Its settings, sheet and two canvas handles edit the curve until a point
     * is edited by hand ([editGeometry]).
     */
    var generator by mutableStateOf<CurveGenerator?>(null)
        private set

    /**
     * v1.8 (item 1a, §3.1): the symmetry maps the open path repeats with (row-major 3 × 3, the
     * identity first): taken once, at its first point ([ObjectCopies.mapsAt]: whatever the ruler
     * is later), or a reopened path's stored ones (whatever the ruler is now); empty for none
     * (symmetry off, or [MirrorLabels18.REMOVE]); null until the path has a point.
     */
    private var maps: List<FloatArray>? = null

    /** The copies drawn besides the path: [maps] without the identity ([CurveCopies.others]). */
    private var copyMaps: List<FloatArray> = emptyList()

    /**
     * True while the open path has mirror copies (Compose state): the More row shows
     * [MirrorLabels18.REMOVE] ([removeCopies]).
     */
    var hasCopies by mutableStateOf(false)
        private set

    /** The open path's copies become [m] (see [maps]). */
    private fun setMaps(m: List<FloatArray>?) {
        maps = m
        copyMaps = CurveCopies.others(m.orEmpty())
        hasCopies = copyMaps.isNotEmpty()
        objectCopies = if (copyMaps.isEmpty()) CopyMaps.NONE else ObjectCopies.fromSymmetry(m.orEmpty()) ?: CopyMaps.NONE
    }

    /**
     * What the path object stores ([VPath.copies]): the sanitized affine copies; none for
     * perspective maps (✓ bakes them: [ObjectCopies.baked]).
     */
    private var objectCopies: CopyMaps = CopyMaps.NONE

    /** The maps (row-major 3 × 3, the identity first) the open path's copies are drawn with; empty without copies (tests). */
    internal val copyMapsNow: List<FloatArray> get() = if (copyMaps.isEmpty()) emptyList() else maps.orEmpty()

    /** True when the copies are the Perspective array ruler's: ✓ on a vector layer bakes them into plain paths. */
    private val perspectiveCopies: Boolean get() = copyMaps.isNotEmpty() && ObjectCopies.isPerspective(maps.orEmpty())

    /** What a brush stroke of the open path is replicated with ([BrushStrokePreview.request]): its maps, or none. */
    private fun brushMaps(): List<FloatArray> = copyMapsNow

    /**
     * [MirrorLabels18.REMOVE]: the open path loses its copies, as ONE
     * in-tool step (✓ then stores none). False when it has none.
     */
    fun removeCopies(): Boolean {
        if (!hasCopies) return false
        pushHistory()
        setMaps(emptyList())
        changed()
        return true
    }

    // ------------------------------------------------------------------ v1.9: Bevel points (R18, Polyline)

    /**
     * v1.9 (R18): the last bevel ([PointLabels19.BEVEL]) of the open polyline: the pre-bevel copy ([before], with
     * the selection then) that the popup re-applies from, and the values it shows (Compose state).
     * It lives while its step is the latest in-tool edit: any other edit, undo or redo ends it.
     */
    class BevelSession internal constructor(
        internal val before: List<CurveAnchor>,
        internal val selectionBefore: PointSelection,
        amount: Float,
        segments: Int,
    ) {
        /** Amount, 0.01..0.5 of the shorter adjacent edge ([PointBevel.clampAmount]). */
        var amount by mutableFloatStateOf(amount)
            internal set

        /** Segments, 1..8 ([PointBevel.clampSegments]). */
        var segments by mutableIntStateOf(segments)
            internal set

        /** The popup ([com.brushwork.paint.ui.points.BevelPopup]) is open. */
        var popupOpen by mutableStateOf(true)
            internal set

        /** What the bevel made: the anchors and the selection (its new points). */
        internal var result: List<CurveAnchor> = before
        internal var selection: PointSelection = selectionBefore
    }

    private var bevelState by mutableStateOf<BevelSession?>(null)

    /** The touch in progress went down while the bevel popup showed: its lift closes it, and adds nothing. */
    private var bevelClosingTouch = false

    /**
     * The live bevel session (Compose state): the last bevel while its points are what the tool
     * shows and they stay selected; null otherwise.
     */
    val bevelSession: BevelSession?
        get() = bevelState?.takeIf { it.result === anchors && it.selection == pointSelection }

    /** The bevel popup shows (Compose state). */
    val bevelPopupShown: Boolean get() = bevelSession?.popupOpen == true

    /**
     * [PointLabels19.BEVEL] (v1.9, R18), Polyline with points selected: bevels the selected sharp corners
     * at once with the remembered Amount and Segments ([AppSettings.curveBevelAmount],
     * [AppSettings.curveBevelSegments]) as ONE in-tool step, selects the new points and opens the
     * popup that adjusts that same step ([setBevelAmount], [setBevelSegments]). While those points
     * stay selected, a second call reopens the popup on that step instead of bevelling again.
     * [PointLabels19.BEVEL_SHARP_ONLY] when no selected point is a sharp corner (the ends of an
     * open line are not), [PointLabels19.BEVEL_TOO_MANY] past [PointBevel.MAX_POINTS_POLYLINE]
     * points; nothing changes then. True when it bevelled or reopened the popup.
     */
    fun bevelPoints(): Boolean {
        if (!polyline || groupEdit != null) return false
        bevelSession?.let {
            it.popupOpen = true
            return true
        }
        val sel = pointSelection
        if (sel.isEmpty) return false
        val amount = PointBevel.clampAmount(controller.settings.curveBevelAmount)
        val segments = PointBevel.clampSegments(controller.settings.curveBevelSegments)
        val before = anchors
        val r = bevelled(before, sel, amount, segments) ?: return false
        editGeometry()
        val session = BevelSession(before, sel, amount, segments)
        showBevel(session, r)
        bevelState = session
        return true
    }

    /** The popup's Amount: the bevel is made again from the pre-bevel copy, in the same step. False without a live session. */
    fun setBevelAmount(amount: Float): Boolean = rebevel(PointBevel.clampAmount(amount), null)

    /** The popup's segment count, as [setBevelAmount]. */
    fun setBevelSegments(segments: Int): Boolean = rebevel(null, PointBevel.clampSegments(segments))

    /** Done, or a tap outside: the popup closes; the bevel stays one step and a re-tap of the item reopens it. */
    fun closeBevelPopup() {
        bevelState?.popupOpen = false
    }

    private fun endBevel() {
        if (bevelState != null) bevelState = null
    }

    private fun rebevel(amount: Float?, segments: Int?): Boolean {
        val s = bevelSession ?: return false
        val a = amount ?: s.amount
        val n = segments ?: s.segments
        if (a == s.amount && n == s.segments) return true
        // (Too many points: the toast, and the values stay.)
        val r = bevelled(s.before, s.selectionBefore, a, n) ?: return false
        s.amount = a
        s.segments = n
        showBevel(s, r)
        controller.settings.curveBevelAmount = a
        controller.settings.curveBevelSegments = n
        return true
    }

    /** [PointBevel.bevel] of [points] at [sel], or null after its message. */
    private fun bevelled(points: List<CurveAnchor>, sel: PointSelection, amount: Float, segments: Int): PointBevel.Result.Ok? {
        val closed = isClosed
        val r = PointBevel.bevel(
            points.map { BevelPoint(it.x, it.y, it.width) }, closed, PointBevel.polylineSharp(points.size, closed),
            sel.indices.toSet(), amount, segments, PointBevel.MAX_POINTS_POLYLINE,
        )
        return when (r) {
            is PointBevel.Result.Ok -> r
            PointBevel.Result.NoSharpCorner -> {
                controller.toast(PointLabels19.BEVEL_SHARP_ONLY)
                null
            }
            is PointBevel.Result.TooMany -> {
                controller.toast(String.format(java.util.Locale.ROOT, PointLabels19.BEVEL_TOO_MANY, r.max))
                null
            }
        }
    }

    /** Shows [r] made from [session]'s copy: untouched anchors as they were, new ones sharp with the interpolated width; the new ones selected. */
    private fun showBevel(session: BevelSession, r: PointBevel.Result.Ok) {
        val src = session.before
        val next = r.points.mapIndexed { i, p ->
            val o = r.origin[i]
            if (o in src.indices) src[o] else CurveAnchor(p.x, p.y, sharp = true, width = CurveWidths.clamp(p.width))
        }
        anchors = next
        selectionState = PointSelection.of(next.size, *r.selected.toIntArray())
        session.result = next
        session.selection = pointSelection
        changed()
    }

    /** Where the generator's two canvas handles are (document px). */
    data class GeneratorHandles(
        /** Drag it to move the curve. */
        val centre: Vec2,
        /** Drag it out to scale the curve, around the centre to turn it. */
        val size: Vec2,
    )

    /** The generator's canvas handles (drawn in [drawOverlay]), or null without a generator. */
    val generatorHandles: GeneratorHandles?
        get() = generator?.let { GeneratorHandles(CurveGenerators.centre(it), CurveGenerators.sizeHandle(it)) }

    /**
     * PATH: converts the pending spline to its Bézier form, re-converting only the spans an edit
     * touched (a point drag on a long path converts at most p + 1 spans per move); bit for bit
     * [SplineBezier.toSubpath], so the anchors and I9 are as a fresh conversion gives.
     */
    private val splineBezier = SplineBezier.Incremental()

    /**
     * PATH: index of the selected control point or -1 (Compose state); with several selected,
     * the one selected last. Setting it selects that point alone.
     */
    var selectedPoint: Int
        get() = if (isPath) pointSelection.primary else -1
        private set(v) {
            if (isPath) selectionState = pointSelection.only(v)
        }

    /** The selected point the strip and the Numbers sheet edit: the control point (Path) or the anchor. */
    val selectedIndex: Int get() = if (isPath) selectedPoint else selected

    /** Number of points the user edits: control points (Path) or anchors. */
    override val pointCount: Int get() = if (isPath) spline?.points?.size ?: 0 else anchors.size

    /** v1.7 (item 1): "Select several" (Compose state; off whenever the edited path changes or ends). */
    private var severalState by mutableStateOf(false)

    /**
     * Turning it on shows its hint as the editor's message (v1.8, §3.9: the converted strip has
     * no hint line of its own; the X / Y pill's cell turns it on, as for Shape).
     */
    override var selectSeveral: Boolean
        get() = severalState
        set(v) {
            val on = v && pointCount > 0
            if (on == severalState) return
            severalState = on
            if (on) controller.toast(PointLabels.SEVERAL_HINT)
            controller.invalidateOverlay()
        }

    override val minPoints: Int get() = MIN_POINTS

    override fun pointAt(i: Int): Vec2 =
        if (isPath) spline?.points?.getOrNull(i)?.let { Vec2(it.x, it.y) } ?: Vec2.ZERO else anchors.getOrNull(i)?.pos ?: Vec2.ZERO

    /** The positions of every point (control points in PATH mode, else anchors). */
    private fun pointPositions(): List<Vec2> =
        if (isPath) spline?.points?.map { Vec2(it.x, it.y) } ?: emptyList() else anchors.map { it.pos }

    override fun selectPoints(s: PointSelection) {
        val next = s.resized(pointCount)
        if (next == pointSelection) return
        selectionState = next
        controller.invalidateOverlay()
    }

    /** "Select all points" / "Deselect all points" (not an in-tool step). */
    fun toggleSelectAll() {
        val n = pointCount
        selectPoints(if (n > 0 && pointSelection.count == n) PointSelection.none(n) else PointSelection.all(n))
    }

    /** True when [undoStep] can go back. */
    override var canUndoStep by mutableStateOf(false)
        private set

    /** Number of edits [redoStep] can bring back (Compose state; the top row's Redo, see [canRedoStep]). */
    var redoCount by mutableIntStateOf(0)
        private set

    /**
     * A path is pending, or (v1.7, item 13) a pending path was just deleted with the trash cell:
     * that is an in-tool step, so the app's undo still reaches [undoStep] and brings it back.
     */
    override val hasPendingWork: Boolean get() = anchors.isNotEmpty() || (trashedPending && canUndoStep)

    /** A reopened path nobody changed yet is not the user's work: undo closes it and goes on (Compose state). */
    override val hasUserChanges: Boolean get() = (anchors.isNotEmpty() && !(reopenedState && pristine)) || (trashedPending && canUndoStep)

    /**
     * v1.8 (F4, §3.2): true with nothing pending too (every point of a new path taken back): the
     * strips' "Redo point" is gone, and the app's redo brings the first point back while the
     * document has nothing to redo (see `EditorController.redo`).
     */
    override val canRedoStep: Boolean get() = redoCount > 0

    /** True while a path object of a vector layer is reopened for editing (Compose state). */
    val isReopened: Boolean get() = reopenedState

    /**
     * v1.9 (R14): bumped once each time a tap on a line of this tool's kind switched to this tool
     * from another curve tool ([switchAndReopen]; the toast [CurveLabels19.SWITCHED_TO] names it).
     * The bottom bar's tool slot flashes once, for [SLOT_FLASH_MS], on each change (Compose state);
     * it reads the three curve tools' signals summed ([slotFlashes]), since the switch and the
     * bump land in the same frame.
     */
    var slotFlash by mutableIntStateOf(0)
        private set

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
        /** v1.7 (item 1): the selected points then (in-tool undo restores them with the points). */
        val selection: PointSelection = PointSelection.none(0),
        /** v1.8 (item 6, I16): the generator the path had then (undo brings it back with the points). */
        val generator: CurveGenerator? = null,
        /**
         * Curve: the Closed setting then. Restored only around a generated path (a step into or
         * out of one): a generated rose is closed and a spiral is open, while v1.7's Closed toggle
         * is not an in-tool step for any other path.
         */
        val closed: Boolean = false,
        /** v1.8 (item 1a): the copies' maps then ([maps]; [MirrorLabels18.REMOVE] is a step). */
        val maps: List<FloatArray>? = null,
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
    /** v1.8 (item 1a): the group alpha [overlaySpecs] and their copies are drawn with (255: none). */
    private var overlayAlpha = 255
    private var observeJob: Job? = null

    /** The brush the live stroke is painted with instead of the painting tool's own (thickness, a reopened path's own brush). */
    private var brushOverride: BrushPreset? = null

    /**
     * What the finger does. v1.7 (item 1): [GROUP] drags (or taps) a point of a group or, with
     * "Select several", any point; [GIZMO] drags a part of the group gizmo; [MARQUEE] box-selects.
     * v1.8 (item 6): [GEN_CENTRE] / [GEN_SIZE] drag a generator's handle.
     */
    private enum class Drag { NONE, ANCHOR, NEW_ANCHOR, HANDLE_IN, HANDLE_OUT, REOPEN, IGNORE, GROUP, GIZMO, MARQUEE, GEN_CENTRE, GEN_SIZE }

    private var drag = Drag.NONE
    private var dragIndex = -1
    private var downPoint = Vec2.ZERO
    private var moved = false
    /** The current touch long-pressed an anchor and selected it (lifting keeps it selected). */
    private var longPressed = false
    private var gestureStart = EditState(emptyList(), null)
    private var gestureSelection = PointSelection.none(0)
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
        // (v1.9: also those that go into a vector layer of their own: [objectTarget].)
        brushPreview.unclipped = { objectTarget(targetLayer ?: controller.doc.activeLayer) }
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
        val toSave = when {
            // (v1.9: and "Editable (own layer)", a choice of where new paths go, not a look.)
            user != null -> user.copy(unit = new.unit, nudgeStepPx = new.nudgeStepPx, ownLayer = new.ownLayer)
            // (A generated curve's own Closed is not saved: [userClosed].)
            else -> userClosed?.let { new.copy(closed = it) } ?: new
        }
        val r = reopened
        if (r != null) r.userSettings = toSave else if (ownSettings != null) ownSettings = toSave
        runCatching { controller.settings.putObject(prefsKey, CurveSettings.serializer(), toSave) }
        changed()
    }

    /**
     * v1.7 (item 7): the segment the strip shows: Fill for no line and a fill (a v1.6 fill-only
     * path reopens so), Both for a line and a fill, Stroke for a line alone; null for neither
     * (v1.6 "No stroke" without Fill). Compose state.
     */
    val paintMode: CurvePaint?
        get() {
            val s = settings
            return when {
                s.stroke == CurveStroke.NONE -> if (s.fill) CurvePaint.FILL else null
                s.fill -> CurvePaint.BOTH
                else -> CurvePaint.STROKE
            }
        }

    /**
     * v1.7 (item 7): Stroke, Fill or Both. Fill sets the stroke kind to [CurveStroke.NONE] and
     * remembers the kind it had in [CurveSettings.lastStroke]; Stroke and Both bring it back.
     */
    fun setPaintMode(mode: CurvePaint) = update { s ->
        val kind = if (s.stroke != CurveStroke.NONE) s.stroke else s.lastStroke.takeIf { it != CurveStroke.NONE } ?: CurveStroke.BRUSH
        when (mode) {
            CurvePaint.STROKE -> s.copy(stroke = kind, fill = false, lastStroke = kind)
            CurvePaint.FILL -> s.copy(stroke = CurveStroke.NONE, fill = true, lastStroke = kind)
            CurvePaint.BOTH -> s.copy(stroke = kind, fill = true, lastStroke = kind)
        }
    }

    /** v1.7 (item 7): the stroke kind of Stroke and Both ("Current brush" or "Plain line"; remembered for after Fill). */
    fun setStrokeKind(kind: CurveStroke) {
        if (kind == CurveStroke.NONE) return
        update { it.copy(stroke = kind, lastStroke = kind) }
    }

    /**
     * v1.7 (item 7): Fill and Both can show: the pending path has 3 points (an open path fills as
     * if closed), or none yet (the choice is the next path's). Compose state.
     */
    val fillPossible: Boolean get() = pointCount == 0 || pointCount >= 3

    /**
     * The tool's own settings while the pending path shows a look that is not theirs: the
     * Capsule quick start's (fill, no line) or that of a path handed over by To Bézier (or by
     * undoing it). They come back when that path ends (✓, ✕, undone away, handed on), so a quick
     * start or a conversion never changes how the next path of this tool looks; meanwhile the
     * strip edits the pending path's look ([update] saves only the unit and nudge step), as for a
     * reopened path object. Null otherwise (and always while a path object is reopened).
     */
    private var ownSettings: CurveSettings? = null

    /**
     * Curve: the user's own Closed setting while a pending generated curve shows the one its kind
     * has instead (a rose is closed, a spiral open: [showClosed]) and no other look is shown; null
     * otherwise. Only Closed is that curve's own: the strip's other edits on it (stroke kind,
     * width, Taper ends...) stay the tool's settings, as on any curve ([update]); Closed comes back
     * when the curve ends ([endLook]).
     */
    private var userClosed: Boolean? = null

    /** The pending path shows [look] (see [ownSettings]); the unit, nudge step and own layer (v1.9) stay the user's. */
    private fun showLook(look: CurveSettings) {
        if (reopened != null) return
        val own = ownSettings ?: userClosed?.let { settings.copy(closed = it) } ?: settings
        userClosed = null
        ownSettings = own
        settings = look.copy(unit = own.unit, nudgeStepPx = own.nudgeStepPx, ownLayer = own.ownLayer).sanitized(fallback = own)
    }

    /** The pending path is gone: the tool's own settings come back (see [ownSettings], [userClosed]). */
    private fun endLook() {
        userClosed?.let { c ->
            userClosed = null
            settings = settings.copy(closed = c)
        }
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
        // v1.9 (R18): another edit ends the bevel session (its popup adjusts only the latest step).
        endBevel()
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
        val sel = pointSelection
        val g = generator
        return if (isPath) EditState(emptyList(), spline, look = look, selection = sel, generator = g, maps = maps)
        else EditState(anchors, null, look = look, selection = sel, generator = g, closed = settings.closed, maps = maps)
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
        if (generatorUnsaved) {
            generatorUnsaved = false
            generator?.let { saveLastGenerator(it) }
        }
    }

    // ------------------------------------------------------------------ generated curves (v1.8, item 6, §3.7)

    /**
     * I16: THE one place where a hand edit of the geometry clears the generator. It records the
     * in-tool step as [pushHistory] does (edits keyed [key] share one) and, on a generated path,
     * clears the generator in that same step (its undo brings the generator back with the
     * points), with [CurveGenLabels18.EDITED_HINT] once per session. Moving, adding and deleting
     * points, dragging a tangent handle, Sharp / Smooth, the automatic tangent, handle lengths,
     * weights, the order, Endpoint, Cyclic and Closed come here; thickness, color and stroke edits
     * do not. (A move, turn or uniform scale of EVERY point is no edit of the curve: the generator
     * follows it, [mapGenerator].)
     */
    private fun editGeometry(key: Any? = null) {
        pushHistory(key)
        if (generator == null) return
        generator = null
        generatorDropped()
    }

    /**
     * The once-per-session messages this tool showed ([CurveGenLabels18.EDITED_HINT],
     * [MirrorLabels18.PERSPECTIVE_BAKED]). Each curve tool lives as long as the editor, and
     * [toastOncePerSession] asks the Curve, Polyline and Path tools alike, so a message shows
     * once per editor session whichever of them shows it first.
     */
    private val hintsShown = HashSet<String>()

    /** Shows [message] unless a curve tool of this editor already showed it. */
    private fun toastOncePerSession(message: String) {
        if (message in hintsShown) return
        val peers = listOf(ToolId.CURVE, ToolId.POLYLINE, ToolId.PATH).mapNotNull { controller.tools[it] as? CurveTool }
        if (peers.any { message in it.hintsShown }) return
        hintsShown += message
        controller.toast(message)
    }

    /** The Path strip showed its width caption as a message (once per session: only Path shows it). */
    internal var widthCaptionShown = false

    /** The generator went during the touch in progress: the hint waits until it ends (a cancelled touch shows none). */
    private var droppedDuringTouch = false

    /** A finger is down on the canvas (between [onDown] and [onUp] / [onCancel]). */
    private var touching = false

    /** A sheet's slider changed the generator: [endNumericEdit] saves the settings. */
    private var generatorUnsaved = false

    /** The generator was cleared by a hand edit: the hint, once per session (when the touch ends). */
    private fun generatorDropped() {
        if (touching) { droppedDuringTouch = true; return }
        toastOncePerSession(CurveGenLabels18.EDITED_HINT)
    }

    /**
     * The points [sel] were mapped by [m]: a similarity (move, turn, uniform scale, mirror) of
     * EVERY point carries the generator [base] along ([CurveGenerator.mapped]; the curve is still
     * its generator's, as the Transform tool keeps it); any other map is a hand edit and clears it.
     * [hint]: the hint now (else the caller shows it when the edit ends).
     */
    private fun mapGenerator(base: CurveGenerator?, sel: PointSelection, m: Affine2, hint: Boolean = true) {
        if (base == null) return
        val mapped = if (sel.count == pointCount && m.isFinite()) base.mapped(m.toArray9()) else null
        generator = mapped
        if (mapped == null && hint) generatorDropped()
    }

    /** Curve: the Closed setting the open path shows becomes [closed] (a generated rose is closed, a spiral open). */
    private fun showClosed(closed: Boolean) {
        if (isPath || settings.closed == closed) return
        when {
            reopened != null -> settings = settings.copy(closed = closed)
            ownSettings != null -> showLook(settings.copy(closed = closed))
            else -> {
                // Closed alone is the curve's own ([userClosed]): the user's comes back when it ends.
                val own = userClosed ?: settings.closed
                userClosed = if (closed == own) null else own
                settings = settings.copy(closed = closed)
            }
        }
    }

    /**
     * Closed on / off (the strip's chip, "Closed path" in the settings): the Curve and Polyline
     * setting, Path's Cyclic ([setCyclic]). On a generated curve it edits the geometry ([editGeometry]).
     */
    fun setClosed(on: Boolean) {
        if (isPath) { setCyclic(on); return }
        if (settings.closed == on) return
        if (generator != null) editGeometry()
        update { it.copy(closed = on) }
    }

    /**
     * [CurveGenLabels18.CURVES] ▸ [genKind] (Curve and Path, never Polyline), while nothing is open: places that
     * curve at the centre of the canvas seen in [viewArea] (as [shapeArea]: view px, null for the
     * whole canvas), its size handle [GENERATOR_VIEW_FRACTION] × the area's short side from the
     * centre (a wave is twice that long), with the last-used settings ([LAST_GENERATOR_KEY]), and
     * opens it for editing. One in-tool step. False when nothing was placed.
     */
    fun startGenerator(genKind: CurveGenKind, viewArea: RectF?): Boolean {
        if (polyline || pointCount > 0 || opening) return false
        if (!controller.checkEditable()) return false
        val area = shapeArea(viewArea)
        val reach = GENERATOR_VIEW_FRACTION * min(area.width(), area.height())
        if (!(reach > 0f) || !reach.isFinite()) return false
        val last = lastGenerator()
        val size = if (genKind == CurveGenKind.SINE) reach * 2f else reach
        val g = last.copy(kind = genKind, cx = area.centerX(), cy = area.centerY(), size = size, amplitude = last.amplitude * size, angleDeg = 0f)
            .sanitized() ?: return false
        pushHistory()
        targetLayer = controller.doc.activeLayer
        applyGenerator(g)
        return true
    }

    /**
     * The generator sheet changed a setting: the open curve is built again from [g]. Each change
     * is one in-tool step, and a slider drag (between [beginNumericEdit] and [endNumericEdit]) is
     * ONE step however long it is. The settings become the last-used ones ([LAST_GENERATOR_KEY]).
     * Nothing happens without a generated path.
     */
    fun changeGenerator(g: CurveGenerator) {
        val now = generator ?: return
        val next = g.sanitized() ?: return
        if (next == now) return
        pushHistory(if (numericHeld) NumericKey("generator", -1) else null)
        applyGenerator(next)
        if (numericHeld) generatorUnsaved = true else saveLastGenerator(next)
    }

    /**
     * The open path becomes [g]'s curve ([CurveGenerators.build]; [g] sanitized): its points (a
     * thickness every point shared is kept, else 100 %), Curve's Closed setting, no point
     * selected. No step of its own.
     */
    private fun applyGenerator(g: CurveGenerator) {
        val b = CurveGenerators.build(g, kind)
        val w = commonWidth()
        if (isPath) {
            val s = b.spline ?: return
            setSplineState(if (w == 1f) s else s.copy(points = s.points.map { it.copy(width = w) }))
        } else {
            showClosed(b.subpath.closed)
            anchors = b.subpath.anchors.map { v -> v.toCurveAnchor().let { if (w == 1f) it else it.copy(width = w) } }
        }
        generator = g
        selectionState = PointSelection.none(pointCount)
        changed()
    }

    /** The thickness every point has (1 when they differ or there is none). */
    private fun commonWidth(): Float {
        val n = pointCount
        if (n == 0) return 1f
        val w = widthOf(0)
        for (i in 1 until n) if (widthOf(i) != w) return 1f
        return w
    }

    /** The last-used generator settings: placement-free (centre 0, size 1, the wave height relative to the size, angle 0). */
    private fun lastGenerator(): CurveGenerator =
        runCatching { controller.settings.getObject(LAST_GENERATOR_KEY, CurveGenerator.serializer()) }.getOrNull()?.sanitized()
            ?: placementFree(CurveGenerator())

    private fun saveLastGenerator(g: CurveGenerator) {
        runCatching { controller.settings.putObject(LAST_GENERATOR_KEY, CurveGenerator.serializer(), placementFree(g)) }
    }

    private fun placementFree(g: CurveGenerator) = g.copy(cx = 0f, cy = 0f, size = 1f, amplitude = g.amplitude / g.size, angleDeg = 0f)

    /** The generator when a handle drag began. */
    private var genStart: CurveGenerator? = null

    /**
     * A touch at [pt] on one of the generator's handles (the nearer within the grab radius; they
     * win over the points under them) starts a [Drag.GEN_CENTRE] or [Drag.GEN_SIZE] drag.
     */
    private fun generatorDown(pt: Vec2): Boolean {
        val g = generator ?: return false
        val h = generatorHandles ?: return false
        val dc = pt.distanceTo(h.centre)
        val ds = pt.distanceTo(h.size)
        if (min(dc, ds) > grabRadius()) return false
        drag = if (ds <= dc) Drag.GEN_SIZE else Drag.GEN_CENTRE
        genStart = g
        return true
    }

    /** A move of a generator handle drag: the centre moves the curve, the size handle scales and turns it. One in-tool step. */
    private fun generatorMove(pt: Vec2) {
        val g0 = genStart ?: return
        if (!moved) {
            if (pt.distanceTo(downPoint) < controller.docLength(TOUCH_SLOP_DP)) return
            pushHistory()
            moved = true
            setDragging(true)
        }
        val d = pt - downPoint
        val next = if (drag == Drag.GEN_CENTRE) {
            g0.copy(cx = g0.cx + d.x, cy = g0.cy + d.y).sanitized()
        } else {
            CurveGenerators.withSizeHandle(g0, CurveGenerators.sizeHandle(g0) + d)
        }
        if (next != null && next != generator) applyGenerator(next)
    }

    /** The generator's handles: a line from the centre to the size handle, the two handles over everything. */
    private fun drawGeneratorHandles(canvas: Canvas, t: ViewTransform, scale: Float) {
        val h = generatorHandles ?: return
        val c = map(t, h.centre).let { it[0] to it[1] }
        val s = map(t, h.size).let { it[0] to it[1] }
        painter.line(canvas, t, c.first, c.second, s.first, s.second)
        pointPainter.generatorHandle(canvas, t, c.first, c.second, scale, centre = true, active = drag == Drag.GEN_CENTRE)
        pointPainter.generatorHandle(canvas, t, s.first, s.second, scale, centre = false, active = drag == Drag.GEN_SIZE)
    }

    /**
     * Steps back one anchor edit (add, move, delete, corner change). Also used by the app's undo
     * (button / two-finger tap) so it takes back the last point instead of the whole curve.
     */
    override fun undoStep(): Boolean {
        endBevel()
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

    /** Brings back the edit last taken back by [undoStep] (the app's redo: the top row's Redo, a three-finger tap). */
    override fun redoStep(): Boolean {
        endBevel()
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
        val before = pointSelection
        // (A step into or out of a generated curve: the Closed setting it had comes back with it.)
        if (!isPath && (generator != null || state.generator != null)) showClosed(state.closed)
        generator = state.generator
        setMaps(state.maps)
        if (isPath) setSplineState(state.spline) else anchors = state.anchors
        // v1.7 (item 1, I12): a step taken with several points selected (or undone while several
        // are) brings its selection back with the points; otherwise, as in v1.6, the selected
        // point stays selected while it still exists.
        val n = pointCount
        val then = state.selection
        selectionState = if (then.count >= 2 || before.count >= 2) then.resized(n) else before.resized(n).let { s ->
            if (s.primary in 0 until n) s.only(s.primary) else PointSelection.none(n)
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
        val v = s?.takeIf { it.points.isNotEmpty() }?.let { endsSmooth(it) }
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
            editGeometry()
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
        editGeometry()
        if (targetLayer == null) targetLayer = controller.doc.activeLayer
        anchors = anchors + CurveAnchor(p.x.coerceIn(-lim, lim), p.y.coerceIn(-lim, lim), sharp = polyline)
        selected = anchors.lastIndex
        changed()
        return true
    }

    /**
     * v1.8 (§3.9, the strip's "Add point"): a new point midway along the segment after the
     * selected point (with several selected, the one selected last; the segment before it when
     * it ends an open path; the last segment with none selected), on the line with the thickness
     * the line has there (Path: midway between the two control points, its weight and thickness
     * between theirs); after a lone point, [ADD_POINT_GAP_DP] to its right. The new point is
     * selected. One in-tool step, a hand edit of the geometry ([editGeometry]: a generated curve
     * loses its generator). False when nothing is open or the path is full.
     */
    fun addPointAfterSelected(): Boolean {
        val n = pointCount
        if (n == 0 || groupEdit != null) return false
        val lim = ShapeSettings.MAX_LENGTH
        val primary = pointSelection.primary
        if (isPath) {
            val s = spline ?: return false
            if (s.points.size >= VSpline.MAX_POINTS) {
                controller.toast(TOO_MANY_POINTS)
                return false
            }
            val pts = s.points
            val at: Int
            val point: VSplinePoint
            if (pts.size == 1) {
                val p = pts[0]
                at = 1
                point = p.copy(x = (p.x + controller.docLength(ADD_POINT_GAP_DP)).coerceIn(-lim, lim))
            } else {
                val segs = if (s.cyclic && pts.size >= 3) pts.size else pts.size - 1
                val seg = (if (primary in pts.indices) primary else segs - 1).coerceAtMost(segs - 1)
                val a = pts[seg]
                val b = pts[(seg + 1) % pts.size]
                val mid = Vec2((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                at = seg + 1
                point = SplineEditing.pointOnPolygon(s, SplineEditing.PolygonHit(seg, 0.5f, mid, 0f), mid)
            }
            editGeometry()
            setSplineState(SplineEditing.inserted(s, at, point))
            selectedPoint = at
            changed()
            return true
        }
        val list = anchors.toMutableList()
        val at: Int
        if (list.size == 1) {
            val a = list[0]
            at = 1
            list.add(CurveAnchor((a.x + controller.docLength(ADD_POINT_GAP_DP)).coerceIn(-lim, lim), a.y, sharp = polyline, width = a.width))
        } else {
            val segs = CurveGeometry.segmentCount(list.size, isClosed)
            val seg = (if (primary in list.indices) primary else segs - 1).coerceAtMost(segs - 1)
            val (p0, c1, c2, p1) = CurveGeometry.segment(list, seg, isClosed, tension, polyline)
            // The cubic at t = 0.5.
            val mid = (p0 + c1 * 3f + c2 * 3f + p1) / 8f
            at = seg + 1
            list.add(at, CurveAnchor(mid.x.coerceIn(-lim, lim), mid.y.coerceIn(-lim, lim), sharp = polyline, width = insertedWidth(CurveHit(seg, 0.5f, mid, 0f))))
        }
        editGeometry()
        anchors = list
        selected = at
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

    /**
     * Makes point [index] a corner (sharp) or smooth: a Curve anchor (its dragged tangent goes),
     * or (v1.7, item 4) a Path control point that is not an end of an open path ([canBeSharp]).
     * One in-tool step.
     */
    fun setSharp(index: Int, sharp: Boolean) {
        if (isPath) {
            setSharpPoints(listOf(index), sharp)
            return
        }
        val a = anchors.getOrNull(index) ?: return
        if (a.sharp == sharp && !a.hasCustomTangent) return
        editGeometry()
        replace(index, a.copy(sharp = sharp, handleIn = null, handleOut = null))
    }

    /**
     * v1.7 (item 4): point [index] can be a corner: a Curve anchor, or a Path control point that
     * is not an end of an open path (the ends are always reached: "Ends are always sharp").
     * Never a Polyline's (all its points are corners).
     */
    fun canBeSharp(index: Int): Boolean {
        if (polyline) return false
        if (!isPath) return index in anchors.indices
        val s = spline ?: return false
        if (index !in s.points.indices) return false
        return s.cyclic || (index != 0 && index != s.points.lastIndex)
    }

    /**
     * v1.7 (items 1 and 4): the points [indices] become corners ([sharp]) or smooth, as ONE
     * in-tool step; points that can't be ([canBeSharp]) are left alone. A Curve anchor that
     * changes loses its dragged tangent (as [setSharp]).
     */
    fun setSharpPoints(indices: List<Int>, sharp: Boolean) {
        val want = indices.filter { canBeSharp(it) }.toSet()
        if (want.isEmpty()) return
        if (isPath) {
            val s = spline ?: return
            if (want.all { s.points[it].sharp == sharp }) return
            editGeometry()
            setSplineState(s.copy(points = s.points.mapIndexed { i, p -> if (i in want && p.sharp != sharp) p.copy(sharp = sharp) else p }))
            changed()
            return
        }
        if (want.all { anchors[it].sharp == sharp && !anchors[it].hasCustomTangent }) return
        editGeometry()
        anchors = anchors.mapIndexed { i, a -> if (i in want && (a.sharp != sharp || a.hasCustomTangent)) a.copy(sharp = sharp, handleIn = null, handleOut = null) else a }
        changed()
    }

    /** v1.7 (item 1): sharp or not, for each selected point that [canBeSharp] (the three-state chip). */
    fun selectedSharp(): BooleanArray {
        val idx = pointSelection.indices.filter { canBeSharp(it) }
        val s = spline
        return BooleanArray(idx.size) { k -> if (isPath) s?.points?.getOrNull(idx[k])?.sharp == true else anchors.getOrNull(idx[k])?.sharp == true }
    }

    /** An open spline's two ends are never sharp (a corner joins two pieces; [VSpline.sanitized]). */
    private fun endsSmooth(s: VSpline): VSpline {
        val p = s.points
        if (s.cyclic || p.isEmpty() || (!p[0].sharp && !p[p.lastIndex].sharp)) return s
        val out = p.toMutableList()
        if (out[0].sharp) out[0] = out[0].copy(sharp = false)
        if (out[out.lastIndex].sharp) out[out.lastIndex] = out[out.lastIndex].copy(sharp = false)
        return s.copy(points = out)
    }

    /** Drops the dragged tangent of anchor [index] and goes back to the automatic one. */
    fun resetTangent(index: Int) {
        if (isPath) return
        val a = anchors.getOrNull(index) ?: return
        if (!a.hasCustomTangent) return
        editGeometry()
        replace(index, a.withAutoTangent())
    }

    /** Deletes point [index] (a control point in PATH mode); with no points left a reopened path object goes. */
    fun deleteAnchor(index: Int) {
        if (isPath) {
            val s = spline ?: return
            if (index !in s.points.indices) return
            editGeometry()
            setSplineState(SplineEditing.removed(s, index))
            selectedPoint = -1
        } else {
            if (index !in anchors.indices) return
            editGeometry()
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
            editGeometry(NumericKey("move", index))
            setSplineState(SplineEditing.moved(s, index, q))
            changed()
            return
        }
        val a = anchors.getOrNull(index) ?: return
        if (q == a.pos) return
        editGeometry(NumericKey("move", index))
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

    // ------------------------------------------------------------------ several points (v1.7, item 1)

    /**
     * A group edit in progress ([beginGroupEdit]): the points it maps (captured at its start),
     * the selection then, and what [endGroupEdit] needs to drop its step when nothing changed.
     */
    private class GroupEdit(
        val anchors: List<CurveAnchor>,
        val spline: VSpline?,
        val selection: PointSelection,
        val historySize: Int,
        val redo: List<EditState>,
        /** Every point is mapped: PATH maps its Bézier form directly and converts once at the end. */
        val all: Boolean,
        /** v1.8 (I16): the generator when it began (a similarity of every point maps it, [mapGenerator]). */
        val generator: CurveGenerator?,
    )

    private var groupEdit: GroupEdit? = null

    /** True while a group edit is in progress. */
    internal val groupEditing: Boolean get() = groupEdit != null

    /**
     * A group gesture drags a path of more than [LIVE_OUTLINE_MAX_POINTS] points (§3.1c): a
     * plain line of varying thickness shows its centre line only, and its outline again when the
     * finger lifts ([endGroupEdit]).
     */
    internal val centreLineOnly: Boolean get() = groupEdit != null && pointCount > LIVE_OUTLINE_MAX_POINTS

    override fun beginGroupEdit(label: String) {
        if (groupEdit != null) endGroupEdit()
        val sel = pointSelection
        if (sel.isEmpty || anchors.isEmpty()) return
        beginNumericEdit()
        val size = history.size
        val redoNow = redo.toList()
        pushHistory()
        groupEdit = GroupEdit(anchors, spline, sel, size, redoNow, sel.count == pointCount, generator)
    }

    override fun setGroupTransform(m: Affine2) {
        val g = groupEdit ?: return
        if (!m.isFinite()) return
        val s = g.spline
        if (isPath && s != null) {
            val next = s.copy(points = CurveGroupMath.mappedPoints(s.points, g.selection, m))
            if (g.all) {
                // NURBS and Bézier forms are affine-invariant: the cached Bézier form is mapped as
                // it is, and converted once when the edit ends (the anchors are then exactly I9's).
                spline = next
                anchors = CurveGroupMath.mappedAnchors(g.anchors, null, m, VSpline.MAX_COORD)
            } else {
                setSplineState(next)
            }
        } else {
            anchors = CurveGroupMath.mappedAnchors(g.anchors, g.selection, m, ShapeSettings.MAX_LENGTH)
        }
        // (Decided again every frame from where the edit began; the hint waits for its end.)
        mapGenerator(g.generator, g.selection, m, hint = false)
        changed()
    }

    override fun endGroupEdit() {
        val g = groupEdit ?: return
        groupEdit = null
        if (isPath && g.all) setSplineState(spline)
        val unchanged = if (isPath) spline == g.spline else anchors == g.anchors
        if (unchanged) {
            dropStepsSince(g.historySize, g.redo)
            generator = g.generator
        } else if (g.generator != null && generator == null) {
            generatorDropped()
        }
        endNumericEdit()
        changed()
    }

    /** Puts the points back as they were when the group edit began and drops its step (a cancelled gesture). */
    private fun abortGroupEdit() {
        val g = groupEdit ?: return
        groupEdit = null
        if (isPath) setSplineState(g.spline) else anchors = g.anchors
        generator = g.generator
        selectionState = g.selection
        dropStepsSince(g.historySize, g.redo)
        endNumericEdit()
        changed()
    }

    /** Drops the in-tool steps pushed since the history had [size] steps, and brings back [redoThen]. */
    private fun dropStepsSince(size: Int, redoThen: List<EditState>) {
        while (history.size > size) history.removeLast()
        redo.clear(); redo.addAll(redoThen)
        redoCount = redo.size
        historyKey = null
        canUndoStep = history.isNotEmpty()
    }

    /**
     * Deletes the selected points as one in-tool step. With every point selected the object goes
     * ([deleteObject]); a deletion of several that would leave a single point is refused with a
     * toast. One selected point (of two or more) is deleted as the strip's "Delete point" does
     * ([deleteAnchor], I12: as in v1.6, which has no minimum).
     */
    override fun deleteSelectedPoints(): Boolean {
        val sel = pointSelection
        if (sel.isEmpty || anchors.isEmpty() || groupEdit != null) return false
        val left = pointCount - sel.count
        if (left <= 0) return deleteObject()
        if (sel.isSingle) {
            deleteAnchor(sel.primary)
            return true
        }
        if (left < minPoints) {
            controller.toast(minPointsMessage())
            return false
        }
        editGeometry()
        if (isPath) {
            val s = spline ?: return false
            setSplineState(s.copy(points = s.points.filterIndexed { i, _ -> i !in sel }))
        } else {
            anchors = anchors.filterIndexed { i, _ -> i !in sel }
        }
        selectionState = PointSelection.none(pointCount)
        changed()
        return true
    }

    /** "A path needs at least 2 points" (the refusal of [deleteSelectedPoints]). */
    private fun minPointsMessage(): String = "A ${objectNoun()} needs at least $MIN_POINTS points"

    /** The object this tool edits, in the trash cell's words: "curve", "polyline" or "path". */
    private fun objectNoun(): String = when (kind) {
        CurveKind.CURVE -> "curve"
        CurveKind.POLYLINE -> "polyline"
        CurveKind.PATH -> "path"
    }

    /**
     * v1.7 (item 13): the whole object goes. A reopened path object is removed from its layer as
     * ONE controller step ("Delete path"...; undo brings it back) and the tool's session ends; a
     * pending path that was never applied is cleared as an in-tool step.
     */
    private fun deleteObject(): Boolean {
        if (anchors.isEmpty() || groupEdit != null) return false
        val r = reopened
        if (r != null) {
            inCommit = true
            try {
                brushPreview.cancel()
                r.session.inner = null
                r.session.drawPreview = null
                endReopen(cancelSession = false)
                r.session.commit(emptyList(), deleteHistoryLabel())
            } finally {
                inCommit = false
            }
            resetPath()
            brushPreview.end()
            brushOverride = null
            controller.invalidateOverlay()
            return true
        }
        pushHistory()
        if (isPath) setSplineState(null) else anchors = emptyList()
        // (The whole curve goes, its generator with it: no hand edit, no hint; undo brings both.)
        generator = null
        selectionState = PointSelection.none(0)
        severalState = false
        trashedPending = true
        targetLayer = null
        endLook()
        changed()
        controller.invalidateOverlay()
        return true
    }

    private fun deleteHistoryLabel(): String = when (kind) {
        CurveKind.CURVE -> HistoryLabels.DELETE_CURVE
        CurveKind.POLYLINE -> HistoryLabels.DELETE_POLYLINE
        CurveKind.PATH -> HistoryLabels.DELETE_PATH
    }

    /** A pending path was deleted with the trash cell (its in-tool undo brings it back; Compose state). */
    private var trashedPending by mutableStateOf(false)

    /**
     * v1.7 (item 1): the thickness factors of the points [indices] become [values] (each held to
     * 0..3 = 0–300 %): ONE in-tool step, and a scrub (a run of calls between [beginNumericEdit]
     * and [endNumericEdit], or quickly after each other) shares it.
     */
    fun setWidths(indices: List<Int>, values: FloatArray) {
        if (indices.size != values.size || indices.isEmpty()) return
        val n = pointCount
        val want = HashMap<Int, Float>(indices.size * 2)
        for (k in indices.indices) {
            val i = indices[k]
            val v = values[k]
            if (i !in 0 until n || !v.isFinite()) continue
            want[i] = v.coerceIn(0f, CurveWidths.MAX_FACTOR)
        }
        if (want.all { (i, w) -> widthOf(i) == w }) return
        pushHistory(NumericKey("thickness", -1))
        if (isPath) {
            val s = spline ?: return
            setSplineState(s.copy(points = s.points.mapIndexed { i, p -> want[i]?.let { if (it == p.width) p else p.copy(width = it) } ?: p }))
        } else {
            anchors = anchors.mapIndexed { i, a -> want[i]?.let { if (it == a.width) a else a.copy(width = it) } ?: a }
        }
        changed()
    }

    /**
     * v1.7 (item 1), PATH: the weights of the control points [indices] become [values] (each held
     * to [VSpline.MIN_WEIGHT]..[VSpline.MAX_WEIGHT]); one in-tool step, as [setWidths].
     */
    fun setWeights(indices: List<Int>, values: FloatArray) {
        val s = spline ?: return
        if (indices.size != values.size || indices.isEmpty()) return
        val want = HashMap<Int, Float>(indices.size * 2)
        for (k in indices.indices) {
            val i = indices[k]
            val v = values[k]
            if (i !in s.points.indices || !v.isFinite()) continue
            want[i] = v.coerceIn(VSpline.MIN_WEIGHT, VSpline.MAX_WEIGHT)
        }
        if (want.all { (i, w) -> s.points[i].weight == w }) return
        editGeometry(NumericKey("weight", -1))
        setSplineState(s.copy(points = s.points.mapIndexed { i, p -> want[i]?.let { if (it == p.weight) p else p.copy(weight = it) } ?: p }))
        changed()
    }

    /** The thickness factors of the selected points (in selection order). */
    fun selectedWidths(): FloatArray = pointSelection.indices.map { widthOf(it) }.toFloatArray()

    /** PATH: the weights of the selected control points (in selection order). */
    fun selectedWeights(): FloatArray = pointSelection.indices.map { weightOf(it) }.toFloatArray()

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
            editGeometry()
            setSplineState(s.copy(order = v))
        }
        if (settings.pathOrder != v) update { it.copy(pathOrder = v) } else changed()
    }

    /** PATH: Endpoint on / off (an open curve touches its first and last points); see [setOrder]. */
    fun setEndpoint(on: Boolean) {
        val s = spline
        if (s != null && s.endpoint != on) {
            editGeometry()
            setSplineState(s.copy(endpoint = on))
        }
        if (settings.pathEndpoint != on) update { it.copy(pathEndpoint = on) } else changed()
    }

    /** PATH: Cyclic on / off (closes smoothly with 3 points or more); see [setOrder]. */
    fun setCyclic(on: Boolean) {
        val s = spline
        if (s != null && s.cyclic != on) {
            editGeometry()
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
        editGeometry(NumericKey("weight", index))
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

    // ------------------------------------------------------------------ the pill (v1.7, items 1, 9, 12, 13)

    override val pillUnit: LengthUnit get() = settings.unit

    /**
     * The ONE pill source (design §4.6): the single selected point ("Point 3", as v1.6), the box
     * centre of two or more ("Selected points": X / Y move the group), or with no point selected
     * the centre of the open path's points ("Center": X / Y move the whole path). Its position is
     * null only while no path is open.
     */
    override val pillPosition: ObjectPosition = PillPosition()

    private val pillScale = PillScale()
    private val pillDeletion = PillDeletion()

    /** The pill's Scale row, whenever a path is open (Compose state). */
    override val objectScale: ObjectScale? get() = if (pointCount > 0) pillScale else null

    /** The pill's trash cell, whenever a path is open (Compose state). */
    override val objectDeletion: ObjectDeletion? get() = if (pointCount > 0) pillDeletion else null

    // region v110-A2 colors
    /** v1.10 (R2): the pill's Object colors cell. F0: none, so the cell stays hidden (A2 body). */
    override val objectColors: ObjectColorEditor? = null
    // endregion

    /** The points the pill's group edits act on: the selection with two or more selected, else every point. */
    private fun pillTargets(): PointSelection {
        val sel = pointSelection
        return if (sel.count >= 2) sel else PointSelection.all(pointCount)
    }

    // region v110-A5 corners
    /**
     * v1.10 (A5, R12): the pill's Sharp / Smooth cell, one editor for the tool's life (the editor
     * screen remembers the cell by it); its state hides the cell without a selected point.
     */
    override val pointCorners: PointCornerEditor? by lazy(LazyThreadSafetyMode.NONE) { com.brushwork.paint.tools.points.CurveCornerEditor(this) }
    // endregion

    /**
     * The points of [sel] of [baseAnchors] (Curve, Polyline) or [baseSpline] (Path) mapped by [m],
     * as one in-tool step that edits keyed [key] share (a scrub, a held arrow, quick typing).
     * Thickness is not scaled (item 12): it is a factor of the line width, as the Shape tool keeps
     * its stroke width.
     */
    private fun mapPointsStep(
        key: Any?, sel: PointSelection, m: Affine2, baseAnchors: List<CurveAnchor>, baseSpline: VSpline?, baseGenerator: CurveGenerator? = generator,
    ) {
        if (!m.isFinite() || anchors.isEmpty() || groupEdit != null) return
        pushHistory(key)
        if (isPath) {
            val s = baseSpline ?: return
            setSplineState(s.copy(points = CurveGroupMath.mappedPoints(s.points, sel, m)))
        } else {
            anchors = CurveGroupMath.mappedAnchors(baseAnchors, sel, m, ShapeSettings.MAX_LENGTH)
        }
        // v1.8 (I16): the whole curve moved or scaled alike keeps its generator, anything else clears it.
        mapGenerator(baseGenerator, sel, m)
        changed()
    }

    private inner class PillPosition : ObjectPosition {
        override val position: Vec2?
            get() {
                if (pointCount == 0) return null
                val sel = pointSelection
                if (sel.isSingle) return pointAt(sel.primary)
                val box = PointGroupMath.bounds(pointPositions(), pillTargets()) ?: return null
                return Vec2(box.centerX(), box.centerY())
            }

        override val label: String
            get() {
                val sel = pointSelection
                return when {
                    sel.isSingle -> "Point ${sel.primary + 1}"
                    sel.count >= 2 -> SELECTED_POINTS_LABEL
                    else -> CENTER_LABEL
                }
            }

        override fun setPosition(x: Float?, y: Float?) {
            if (pointCount == 0) return
            val sel = pointSelection
            if (sel.isSingle) {
                // One point: exactly v1.6 (its own step, keyed by the point).
                val i = sel.primary
                val p = pointAt(i)
                moveAnchor(i, Vec2(x?.takeIf { it.isFinite() } ?: p.x, y?.takeIf { it.isFinite() } ?: p.y))
                return
            }
            val now = position ?: return
            val dx = x?.takeIf { it.isFinite() }?.let { it - now.x } ?: 0f
            val dy = y?.takeIf { it.isFinite() }?.let { it - now.y } ?: 0f
            if (dx == 0f && dy == 0f) return
            mapPointsStep(NumericKey("groupMove", -1), pillTargets(), Affine2.translate(dx, dy), anchors, spline)
        }

        override fun beginPositionEdit() = beginNumericEdit()
        override fun endPositionEdit() = endNumericEdit()
    }

    /**
     * The Scale row's reference: the points [sel] of [anchors] / [spline] as they were when the
     * row first saw this selection, about the centre of their box. [result] is what the row made
     * of them last, at [percent].
     */
    private class ScaleRef(val sel: PointSelection, val anchors: List<CurveAnchor>, val spline: VSpline?, val centre: Vec2, val generator: CurveGenerator?) {
        var result: Any? = null
        var percent = HUNDRED
    }

    /** Kept while the same points stay selected and only the Scale row changed them (not Compose state). */
    private var scaleRef: ScaleRef? = null

    private fun pointsNow(): Any? = if (isPath) spline else anchors

    /** The Scale row's reference box, captured again when the selection or the points changed otherwise. */
    private fun scaleRefNow(): ScaleRef? {
        if (pointCount == 0) return null
        val sel = pillTargets()
        val now = pointsNow()
        val r = scaleRef
        if (r != null && r.sel == sel && (now === (if (isPath) r.spline else r.anchors) || now === r.result)) return r
        val box = PointGroupMath.bounds(pointPositions(), sel) ?: return null
        return ScaleRef(sel, anchors, spline, Vec2(box.centerX(), box.centerY()), generator).also { scaleRef = it }
    }

    private inner class PillScale : ObjectScale {
        override val scalePercent: Vec2?
            get() {
                val r = scaleRefNow() ?: return null
                return if (r.result != null && pointsNow() === r.result) r.percent else HUNDRED
            }

        override fun beginScaleEdit() = beginNumericEdit()

        override fun setScale(xPercent: Float?, yPercent: Float?) {
            if (groupEdit != null) return
            val r = scaleRefNow() ?: return
            val cur = if (r.result != null && pointsNow() === r.result) r.percent else HUNDRED
            val px = (xPercent?.takeIf { it.isFinite() } ?: cur.x).coerceIn(MIN_SCALE_PERCENT, MAX_SCALE_PERCENT)
            val py = (yPercent?.takeIf { it.isFinite() } ?: cur.y).coerceIn(MIN_SCALE_PERCENT, MAX_SCALE_PERCENT)
            if (px == cur.x && py == cur.y) return
            // From the reference points every time: a scrub never accumulates rounding.
            mapPointsStep(NumericKey("scale", -1), r.sel, Affine2.scaleAbout(r.centre, px / 100f, py / 100f), r.anchors, r.spline, r.generator)
            r.result = pointsNow()
            r.percent = Vec2(px, py)
        }

        override fun endScaleEdit() = endNumericEdit()
    }

    private inner class PillDeletion : ObjectDeletion {
        /** "Delete selected points" with some but not all selected; otherwise "Delete curve" / "Delete polyline" / "Delete path". */
        override val deleteLabel: String?
            get() {
                val n = pointCount
                if (n == 0) return null
                return if (pointSelection.count in 1 until n) PillLabels.DELETE_POINTS else PillLabels.deleteObject(objectNoun())
            }

        override fun delete() {
            val n = pointCount
            if (n == 0) return
            if (pointSelection.count in 1 until n) deleteSelectedPoints() else deleteObject()
        }
    }

    // ------------------------------------------------------------------ history taps over the UI (v1.7, item 10)

    /**
     * The tool's in-tool history at a moment ([historyMark]): equal marks mean nothing changed.
     * The states compare by identity, the points by value.
     */
    private data class ToolMark(
        val epoch: Int,
        val session: Reopened?,
        val history: List<EditState>,
        val redo: List<EditState>,
        val anchors: List<CurveAnchor>,
        val spline: VSpline?,
        val selection: PointSelection,
        val several: Boolean,
        val trashed: Boolean,
        val targetLayer: Layer?,
        val generator: CurveGenerator?,
        /** Curve: the Closed setting while generated (null otherwise: v1.7's marks never see it). */
        val closed: Boolean?,
        /** v1.8 (item 1a): the copies' maps. */
        val maps: List<FloatArray>?,
    )

    /** Counts the paths the tool has edited: a mark of another path never rolls this one back. */
    private var historyEpoch = 0

    override fun historyMark(): Any? = ToolMark(
        historyEpoch, reopened, history.toList(), redo.toList(), anchors, spline, pointSelection, severalState, trashedPending, targetLayer, generator, generator?.let { settings.closed }, maps,
    )

    /**
     * Puts the pending path, its selection and its in-tool undo and redo back as they were at
     * [mark] (the steps since are dropped, not moved to redo), while the tool still edits the same
     * path; otherwise nothing.
     */
    override fun rollbackHistory(mark: Any?) {
        val m = mark as? ToolMark ?: return
        if (m.epoch != historyEpoch || m.session !== reopened) return
        groupEdit = null
        history.clear(); history.addAll(m.history)
        redo.clear(); redo.addAll(m.redo)
        redoCount = redo.size
        historyKey = null
        numericHeld = false
        canUndoStep = history.isNotEmpty()
        m.closed?.let { showClosed(it) }
        generator = m.generator
        setMaps(m.maps)
        if (isPath) setSplineState(m.spline) else anchors = m.anchors
        selectionState = m.selection.resized(pointCount)
        severalState = m.several && pointCount > 0
        trashedPending = m.trashed
        targetLayer = m.targetLayer
        changed()
        controller.invalidateOverlay()
    }

    /**
     * Nudges the selected anchor (or the whole path when none is selected) by the nudge step.
     * A run of nudges of the same target is one undo step.
     */
    fun nudge(dx: Int, dy: Int) {
        if (anchors.isEmpty()) return
        val step = settings.nudgeStepPx
        val d = Vec2(dx * step, dy * step)
        val group = pointSelection
        if (group.count >= 2) {
            // v1.7 (item 1): several selected points move together (a run of nudges is one step).
            pushHistory(NumericKey("nudge", -2))
            val m = Affine2.translate(d.x, d.y)
            val s = spline
            if (isPath && s != null) setSplineState(s.copy(points = CurveGroupMath.mappedPoints(s.points, group, m)))
            else anchors = CurveGroupMath.mappedAnchors(anchors, group, m, ShapeSettings.MAX_LENGTH)
            // (Every point selected: the generated curve moves along with its generator.)
            mapGenerator(generator, group, m)
            changed()
            return
        }
        val whole = Affine2.translate(d.x, d.y)
        if (isPath) {
            val s = spline ?: return
            val i = selectedPoint
            val p = s.points.getOrNull(i)
            if (p != null) editGeometry(NumericKey("nudge", i)) else pushHistory(NumericKey("nudge", i))
            setSplineState(if (p != null) SplineEditing.moved(s, i, Vec2(p.x, p.y) + d) else SplineEditing.translated(s, d))
            if (p == null) mapGenerator(generator, PointSelection.all(pointCount), whole)
            changed()
            return
        }
        val one = selected in anchors.indices
        if (one) editGeometry(NumericKey("nudge", selected)) else pushHistory(NumericKey("nudge", selected))
        anchors = if (one) {
            anchors.mapIndexed { i, a -> if (i == selected) a.moved(a.pos + d) else a }
        } else {
            anchors.map { it.moved(it.pos + d) }
        }
        if (!one) mapGenerator(generator, PointSelection.all(pointCount), whole)
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
    /** The generator when the change began (back when it ends where it began). */
    private var handleGenerator: CurveGenerator? = null
    /** A two-finger pinch is scaling the handles ([onTwoFingerStart]). */
    private var pinchingHandles = false

    /**
     * The points a change of the handles scales: the selected ones (v1.7: all of a group), or all
     * when "All points" is on or none is selected.
     */
    private fun handleTargetsNow(): IntArray {
        val sel = pointSelection
        return if (!handleAllPoints && !sel.isEmpty) sel.indices.toIntArray() else IntArray(anchors.size) { it }
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
        handleGenerator = generator
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
        editGeometry(NumericKey("handles", -1))
        anchors = next
        changed()
    }

    /** The change of the handles is complete: the value goes back to 100 % (the next change starts from the lengths then). */
    fun endHandleScale() {
        val base = handleBase
        if (base != null && anchors == base && history.size > handleHistorySize) {
            // It ended where it began (a slider dragged back to 100 %): no step.
            while (history.size > handleHistorySize) history.removeLast()
            generator = handleGenerator
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
        generator = handleGenerator
        changed()
    }

    /**
     * A pinch scales the selected point's handles when one finger starts within
     * [IbisDims.HandlePinchDistance] (on screen) of that point or the ends of its handles; the
     * rotation is ignored. Any other pinch moves the view.
     */
    override fun onTwoFingerStart(focus: Vec2, a: Vec2, b: Vec2): Boolean {
        // v1.9 (R18): while the bevel popup shows, two fingers pan and zoom the view.
        if (bevelPopupShown) return false
        if (groupPinchStart(focus, a, b)) return true
        if (!canScaleHandles) return false
        val i = if (pointSelection.isSingle) selected else -1
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
        if (pinchingGroup) {
            groupPinch(translation, scale, rotationDeg)
            return
        }
        if (!pinchingHandles || !scale.isFinite() || scale <= 0f) return
        scaleHandles(controller.increments.factor(scale))
        showScaleReadout()
    }

    override fun onTwoFingerEnd(cancelled: Boolean) {
        if (pinchingGroup) {
            pinchingGroup = false
            setDragging(false)
            if (cancelled) abortGroupEdit() else endGroupEdit()
            controller.increments.readout = null
            return
        }
        if (!pinchingHandles) return
        pinchingHandles = false
        if (cancelled) revertHandleScale()
        endHandleScale()
    }

    // ------------------------------------------------------------------ group gestures (v1.7, item 1)

    /** The group gizmo (two or more points selected). */
    private val gizmo = PointGizmo()

    /** The gizmo part a [Drag.GIZMO] touch drags, and the gizmo as it was when the touch began. */
    private var gizmoPart = PointGizmo.Part.NONE
    private var gizmoLayout: PointGizmo.Layout? = null

    /** The marquee's far corner (document px) while a [Drag.MARQUEE] touch box-selects; null before it moves. */
    private var marqueeTo: Vec2? = null
    private val marqueePath = Path()

    /** A pinch that started inside the gizmo box scales and rotates the selected points about [pinchPivot]. */
    private var pinchingGroup = false
    private var pinchPivot = Vec2.ZERO

    /** The positions of the selected points (document px). */
    private fun selectedPositions(): List<Vec2> {
        val sel = pointSelection
        val out = ArrayList<Vec2>(sel.count)
        for (i in sel.indices) out += pointAt(i)
        return out
    }

    /** The gizmo where it is now: null unless two or more points are selected. */
    private fun gizmoNow(t: ViewTransform = controller.viewTransform): PointGizmo.Layout? =
        if (pointSelection.count >= 2 && anchors.isNotEmpty()) gizmo.layout(selectedPositions(), t) else null

    /** "Keep scale proportions" (the pill's preference, on until the user turns it off): gizmo corners scale both axes alike. */
    private fun keepProportions(): Boolean =
        runCatching { controller.settings.getObject(KEEP_PROPORTIONS_KEY, Boolean.serializer()) }.getOrNull() ?: true

    /** Where gizmo [part] is drawn on screen (null for the box itself). */
    private fun gizmoHandleAt(layout: PointGizmo.Layout, part: PointGizmo.Part): Vec2? {
        val c = layout.cornersScreen
        if (c.size != 4) return null
        fun mid(a: Vec2, b: Vec2) = (a + b) * 0.5f
        return when (part) {
            PointGizmo.Part.ROTATE -> layout.rotateHandleScreen
            PointGizmo.Part.SCALE_NW -> c[0]
            PointGizmo.Part.SCALE_NE -> c[1]
            PointGizmo.Part.SCALE_SE -> c[2]
            PointGizmo.Part.SCALE_SW -> c[3]
            PointGizmo.Part.SCALE_N -> mid(c[0], c[1])
            PointGizmo.Part.SCALE_E -> mid(c[1], c[2])
            PointGizmo.Part.SCALE_S -> mid(c[2], c[3])
            PointGizmo.Part.SCALE_W -> mid(c[3], c[0])
            PointGizmo.Part.NONE, PointGizmo.Part.MOVE -> null
        }
    }

    /** The point (control point in PATH mode, else anchor) within [tol] of [p], or -1; later points win ties. */
    private fun nearestPointIndex(p: Vec2, tol: Float): Int {
        val s = spline
        return if (isPath) (if (s != null) SplineEditing.nearestPoint(s.points, p, tol) else -1) else nearestAnchor(p, tol)
    }

    // region v110-A5 snap
    /**
     * The touch at [pt] belongs to the point group (true; [drag] is set): with "Select several",
     * or with two or more points selected. The gizmo's handles win over a point unless the point
     * is at least as close (as in the Shape tool: a point ON a handle, e.g. the lowest of three in
     * a V on the bottom edge's handle, is still tapped and dragged as a point); a touch inside the
     * box that is not on a point moves the group. Otherwise (false)
     * the v1.6 gestures apply: a point that is not in the group is dragged alone (and then selected
     * alone), empty canvas adds a point.
     */
    private fun groupDown(pt: Vec2): Boolean {
        if (pointCount == 0) {
            severalState = false
            return false
        }
        val sel = pointSelection
        val several = severalState
        groupSnapBox = null
        if (!several && sel.count < 2) return false
        val t = controller.viewTransform
        val idx = nearestPointIndex(pt, grabRadius())
        val layout = gizmoNow(t)
        if (layout != null) {
            val screen = t.docToScreen(pt)
            val part = gizmo.hit(layout, screen, t)
            val handle = gizmoHandleAt(layout, part)
            val pointWins = idx >= 0 && (handle == null || t.docToScreen(pointAt(idx)).distanceTo(screen) <= handle.distanceTo(screen) + PointGizmo.TIE_PX)
            if ((handle != null || part == PointGizmo.Part.MOVE) && !pointWins) {
                drag = Drag.GIZMO
                gizmoPart = part
                gizmoLayout = layout
                // v1.10 (A5, R8 cause A): a drag inside the box moves the group, snapped; a scale
                // handle snaps the edges it drags. The rotate knob keeps its angle steps only.
                if (part != PointGizmo.Part.ROTATE) beginGroupSnap(sel, -1)
                return true
            }
        }
        if (idx >= 0) {
            if (!several && idx !in sel) return false
            drag = Drag.GROUP
            dragIndex = idx
            dragStartPos = pointAt(idx)
            // The other points are snap targets (the group moves together, so not its own points).
            beginGroupSnap(if (several) sel.plus(idx) else sel, idx)
            return true
        }
        if (!several) return false
        drag = Drag.MARQUEE
        marqueeTo = null
        return true
    }

    /** A move of a [Drag.GROUP], [Drag.GIZMO] or [Drag.MARQUEE] touch. */
    private fun groupMove(pt: Vec2) {
        if (!moved && pt.distanceTo(downPoint) < controller.docLength(TOUCH_SLOP_DP)) return
        val first = !moved
        moved = true
        when (drag) {
            Drag.MARQUEE -> {
                marqueeTo = pt
                controller.invalidateOverlay()
            }
            Drag.GROUP -> {
                if (first) {
                    // "Select several": the dragged point joins the group.
                    if (severalState && dragIndex !in pointSelection) selectionState = pointSelection.plus(dragIndex)
                    beginGroupEdit(GROUP_MOVE_LABEL)
                    setDragging(true)
                }
                // v1.10 (A5, R8 cause B): the group snaps (in 1.9 only the finger's point did).
                val d = groupSnapMove(pt - downPoint)
                setGroupTransform(Affine2.translate(d.x, d.y))
            }
            Drag.GIZMO -> {
                val layout = gizmoLayout ?: return
                if (first) {
                    beginGroupEdit(GROUP_MOVE_LABEL)
                    setDragging(true)
                }
                if (gizmoPart == PointGizmo.Part.MOVE) {
                    val d = groupSnapMove(pt - downPoint)
                    setGroupTransform(Affine2.translate(d.x, d.y))
                } else {
                    var m = gizmo.dragMap(layout, gizmoPart, downPoint, pt, keepProportions(), controller.increments.state)
                    val box = groupSnapBox
                    if (box != null) m = com.brushwork.paint.tools.points.PointGroupSnap.of(snap, controller).scale(m, gizmoPart, box, layout.pivotDoc, keepProportions())
                    setGroupTransform(m)
                }
            }
            else -> {}
        }
    }

    /** The finger of a [Drag.GROUP], [Drag.GIZMO] or [Drag.MARQUEE] touch lifted at [pt]. */
    private fun groupUp(pt: Vec2) {
        when (drag) {
            Drag.GROUP -> when {
                moved -> { groupMove(pt); endGroupEdit() }
                // A tap: "Select several" toggles the point, else it becomes the only one selected.
                !longPressed -> selectPoints(if (severalState) pointSelection.toggled(dragIndex) else pointSelection.only(dragIndex))
            }
            Drag.GIZMO -> when {
                moved -> { groupMove(pt); endGroupEdit() }
                // A tap inside the box (not on a point or handle) clears the selection.
                gizmoPart == PointGizmo.Part.MOVE -> selectPoints(PointSelection.none(pointCount))
            }
            Drag.MARQUEE -> {
                val from = downPoint
                if (moved) {
                    val box = RectF(min(from.x, pt.x), min(from.y, pt.y), max(from.x, pt.x), max(from.y, pt.y))
                    selectPoints(pointSelection.plusAll(PointGroupMath.inside(pointPositions(), box)))
                } else {
                    // A tap on empty canvas clears the selection (no point is added).
                    selectPoints(PointSelection.none(pointCount))
                }
                marqueeTo = null
            }
            else -> {}
        }
        gizmoLayout = null
        gizmoPart = PointGizmo.Part.NONE
        groupSnapBox = null
    }

    /** v1.10 (A5, R8): the points a group move snaps, where the touch began, as x, y pairs. */
    private var groupSnapPoints = FloatArray(0)
    /** Their bounds then; null when no group move is snapped. */
    private var groupSnapBox: com.brushwork.paint.tools.transform.DocBox? = null
    /** The grabbed point's index in [groupSnapPoints], or -1 (the gizmo's box). */
    private var groupSnapGrabbed = -1

    /**
     * v1.10 (A5, R8): a group move of the points [moving] begins, [grabbed] (a point index, or -1)
     * under the finger: their positions now are what [groupSnapMove] snaps, and the other points
     * are the snap targets (the group moves together, so never its own points).
     */
    private fun beginGroupSnap(moving: PointSelection, grabbed: Int) {
        val idx = moving.indices.filter { it < pointCount }
        groupSnapPoints = com.brushwork.paint.tools.points.PointGroupSnap.pairs(idx.map { pointAt(it) })
        groupSnapBox = com.brushwork.paint.tools.points.PointGroupSnap.bounds(groupSnapPoints)
        groupSnapGrabbed = idx.indexOf(grabbed)
        beginSnapWithout { it in moving }
    }

    /**
     * v1.10 (A5, R8): the snapped delta of the group move for the finger's raw delta [d]: the
     * group's box and points snap, then the grid, then the step (`PointGroupSnap`). Guide labels
     * keep off the grabbed point (or the box's centre); while increments are on the readout shows
     * the move, as a dragged point's does.
     */
    private fun groupSnapMove(d: Vec2): Vec2 {
        val box = groupSnapBox ?: return d
        val move = com.brushwork.paint.tools.points.PointGroupSnap.of(snap, controller).snap(groupSnapPoints, box, groupSnapGrabbed, d)
        val g = groupSnapGrabbed
        snapMoving = if (g >= 0) Vec2(groupSnapPoints[g * 2] + move.x, groupSnapPoints[g * 2 + 1] + move.y)
        else Vec2((box.left + box.right) / 2f + move.x, (box.top + box.bottom) / 2f + move.y)
        if (controller.increments.state.enabled) controller.increments.readout = "${signed(move.x)}, ${signed(move.y)} px"
        return move
    }
    // endregion

    /**
     * A two-finger pinch that starts inside the gizmo box (a finger or their midpoint on it)
     * scales and rotates the selected points about the box centre, plus the fingers' move: ONE
     * in-tool step. One that starts outside moves the view, as before.
     */
    private fun groupPinchStart(focus: Vec2, a: Vec2, b: Vec2): Boolean {
        val t = controller.viewTransform
        val layout = gizmoNow(t) ?: return false
        val inside = listOf(focus, a, b).any { f ->
            f.x.isFinite() && f.y.isFinite() && gizmo.hit(layout, t.docToScreen(f), t) != PointGizmo.Part.NONE
        }
        if (!inside) return false
        beginGroupEdit(GROUP_MOVE_LABEL)
        if (!groupEditing) return false
        pinchPivot = layout.pivotDoc
        pinchingGroup = true
        setDragging(true)
        return true
    }

    /** The pinch so far: [translation] of the fingers' midpoint, [scale] and [rotationDeg] (increments apply). */
    private fun groupPinch(translation: Vec2, scale: Float, rotationDeg: Float) {
        if (!scale.isFinite() || scale <= 0f || !rotationDeg.isFinite() || !translation.x.isFinite() || !translation.y.isFinite()) return
        val inc = controller.increments
        val k = inc.factor(scale)
        val deg = inc.angle(rotationDeg)
        val d = inc.lengthDelta(translation)
        val p = pinchPivot
        setGroupTransform(Affine2.translate(d.x, d.y) * Affine2.rotateAbout(p, deg) * Affine2.scaleAbout(p, k, k))
        if (inc.enabled) inc.readout = "${(k * 100f).roundToInt()} %, ${deg.roundToInt()}°"
    }

    /** The gizmo of the selected points and the marquee being dragged (screen space, over the points). */
    private fun drawGroupOverlay(canvas: Canvas, t: ViewTransform) {
        gizmoNow(t)?.let { gizmo.draw(canvas, it, t, if (drag == Drag.GIZMO) gizmoPart else PointGizmo.Part.NONE) }
        val to = marqueeTo
        if (drag == Drag.MARQUEE && to != null) {
            val from = downPoint
            marqueePath.rewind()
            marqueePath.addRect(min(from.x, to.x), min(from.y, to.y), max(from.x, to.x), max(from.y, to.y), Path.Direction.CW)
            painter.path(canvas, t, marqueePath, dashed = true)
        }
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
        gestureSelection = pointSelection
        gestureHistorySize = history.size
        gestureRedo = redo.toList()
        reopenCandidate = null
        reopenKind = kind
        snap.end()
        snapMoving = null
        touching = true
        droppedDuringTouch = false
        // v1.9 (R18): the touch that closes the bevel popup is consumed: its lift closes it, no point is added.
        bevelClosingTouch = bevelPopupShown
        if (bevelClosingTouch) { drag = Drag.IGNORE; return }
        if (anchors.isEmpty() && !controller.checkEditable()) { drag = Drag.IGNORE; return }
        // v1.8 (item 6): a generator's handles win over the points under them.
        if (generatorDown(pt)) return
        if (isPath) { if (!groupDown(pt)) pathDown(pt); return }
        val tol = grabRadius()
        val sel = if (pointSelection.isSingle) selected else -1
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
        if (groupDown(pt)) return
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
     * a new path): in this tool, or in the one that edits it (Curve / Polyline ↔ Path; v1.9:
     * Curve ↔ Polyline too). True when the touch is armed for that.
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
        editGeometry()
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
        editGeometry()
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
            Drag.GROUP, Drag.GIZMO, Drag.MARQUEE -> groupMove(pt)
            Drag.GEN_CENTRE, Drag.GEN_SIZE -> generatorMove(pt)
            Drag.REOPEN -> {
                if (pt.distanceTo(downPoint) < controller.docLength(TOUCH_SLOP_DP)) return
                // Not a tap: a new path starts where the finger went down, as anywhere else.
                reopenCandidate = null
                if (isPath) newPointAt(downPoint) else newAnchorAt(downPoint, controller.docLength(HANDLE_TOUCH_DP))
                onMove(p)
            }
            Drag.ANCHOR, Drag.NEW_ANCHOR -> {
                if (!moved && pt.distanceTo(downPoint) < controller.docLength(TOUCH_SLOP_DP)) return
                if (!moved && drag == Drag.ANCHOR) editGeometry()
                // v1.7: a point dragged out of a group of two or more is then the only one selected.
                if (!moved && drag == Drag.ANCHOR && pointSelection.count >= 2) selectionState = pointSelection.only(dragIndex)
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
                    editGeometry(); moved = true
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
            Drag.GEN_CENTRE, Drag.GEN_SIZE -> if (moved) onMove(p)
            Drag.GROUP, Drag.GIZMO, Drag.MARQUEE -> groupUp(Vec2(p.x, p.y))
            Drag.REOPEN -> {
                val path = reopenCandidate
                val k = reopenKind
                reopenCandidate = null
                drag = Drag.NONE
                if (path != null) {
                    if (k == kind) reopen(path) else switchAndReopen(k, path)
                }
            }
            Drag.NONE, Drag.IGNORE -> if (bevelClosingTouch) {
                bevelClosingTouch = false
                closeBevelPopup()
            }
        }
        drag = Drag.NONE
        genStart = null
        endSnap()
        controller.increments.readout = null
        // The drag is over: a plain line / fill goes back into the layer, a brush stroke is drawn
        // exactly once the path rests a moment.
        setDragging(false)
        // (A generator the touch cleared: the hint now that it is over.)
        touching = false
        if (droppedDuringTouch) {
            droppedDuringTouch = false
            generatorDropped()
        }
        controller.invalidateOverlay()
    }

    override fun onCancel() {
        // v1.9 (R18): a second finger (pan, zoom, a two-finger tap) passes the bevel popup by.
        bevelClosingTouch = false
        setDragging(false)
        reopenCandidate = null
        marqueeTo = null
        gizmoLayout = null
        genStart = null
        // (A cancelled touch, e.g. the first finger of a two-finger undo, edited nothing: no hint.)
        touching = false
        droppedDuringTouch = false
        if (groupEditing) {
            // A group gesture: its points, selection and step go back as they were.
            abortGroupEdit()
            drag = Drag.NONE
        }
        if (drag != Drag.NONE && drag != Drag.IGNORE && drag != Drag.REOPEN) {
            if (!isPath && (generator != null || gestureStart.generator != null)) showClosed(gestureStart.closed)
            generator = gestureStart.generator
            if (isPath) setSplineState(gestureStart.spline) else anchors = gestureStart.anchors
            selectionState = gestureSelection
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

    /**
     * Switches to the tool of [k] and reopens [path] there: Curve / Polyline ↔ Path (v1.6), and
     * v1.9 (R14) Curve ↔ Polyline. When the path opens, the toast [CurveLabels19.SWITCHED_TO]
     * names that tool and its [slotFlash] is bumped (the bottom bar flashes its tool slot once).
     */
    private fun switchAndReopen(k: CurveKind, path: VPath) {
        val id = when (k) {
            CurveKind.CURVE -> ToolId.CURVE
            CurveKind.POLYLINE -> ToolId.POLYLINE
            CurveKind.PATH -> ToolId.PATH
        }
        val other = controller.tools[id] as? CurveTool ?: return
        controller.selectTool(id)
        if (controller.currentTool !== other || !other.reopen(path)) return
        controller.toast(String.format(java.util.Locale.ROOT, CurveLabels19.SWITCHED_TO, id.label))
        other.slotFlash++
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
        Drag.GROUP -> {
            if (!moved) {
                // As a tap, then the finger can still drag the group.
                selectPoints(if (severalState) pointSelection.plus(dragIndex) else pointSelection.only(dragIndex))
                longPressed = true
            }
            true
        }
        Drag.GIZMO, Drag.MARQUEE -> true
        Drag.HANDLE_IN, Drag.HANDLE_OUT -> true
        Drag.GEN_CENTRE, Drag.GEN_SIZE -> true
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

    // region v110-A5 snap
    /**
     * Starts snapping for this touch. The anchors (all but [except]) are point targets as they
     * are now (a copy: the dragged point never becomes its own target when targets are rebuilt);
     * the selection and other objects are found by the snapping service.
     */
    private fun beginSnap(except: Int) = beginSnapWithout { it == except }

    /** [beginSnap] with every point for which [skip] is true left out (the points that move together). */
    private inline fun beginSnapWithout(skip: (Int) -> Boolean) {
        val others = ArrayList<Vec2>(anchors.size)
        if (isPath) spline?.points?.forEachIndexed { i, p -> if (!skip(i)) others += Vec2(p.x, p.y) }
        else anchors.forEachIndexed { i, a -> if (!skip(i)) others += a.pos }
        snap.begin(exclude = ownLayerExclude(), includeSelection = true) { pointLines(others, POINT_LABEL) }
    }

    /**
     * v1.10 (A5, R8 cause E): the reopened path's layer when the path is its only object. Until the
     * edit is committed the layer's pixels still hold the path as it was (the edit session only hides it on screen),
     * so its content bounds are the path's own old extent: in 1.9 the points snapped back onto it.
     * With other objects the layer stays a target (their bounds are what it offers).
     */
    private fun ownLayerExclude(): List<Layer> {
        val r = reopened ?: return emptyList()
        val objects = r.layer.vector?.objects ?: return emptyList()
        return if (objects.size == 1 && objects[0].id == r.original.id) listOf(r.layer) else emptyList()
    }
    // endregion

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
     * v1.9 (R14, D22; CurveLabels19.OWN_LAYER): ✓ puts the new path drawn on [layer] into a
     * vector layer of its own ("Curves N" above it: [commitOwnLayer]) instead of painting it into
     * [layer]'s pixels. Only with "Editable (own layer)" on, for a NEW path (never a reopened one,
     * never while erasing: the erase comes first), on the active layer when it is a plain pixel
     * layer whose content is edited: not a vector, text, shape or adjustment layer, a folder or a
     * mask. A line with a brush that needs pixels (smudge, blur, watercolor) is painted into
     * [layer] as before, as the Shape tool does (on a vector layer it would be a plain line).
     */
    private fun ownLayerTarget(layer: Layer): Boolean =
        settings.ownLayer && !eraseState && reopened == null && layer === controller.doc.activeLayer &&
            !layer.isFolder && !layer.isVectorLayer && !layer.isTextLayer && !layer.isShapeLayer && !layer.isAdjustmentLayer &&
            controller.editTargetOf(layer) == EditTarget.CONTENT &&
            (settings.stroke != CurveStroke.BRUSH || brushFitsVector())

    /**
     * What this tool draws on [layer] becomes an object, of [layer] ([vectorTarget]) or of a layer
     * of its own ([ownLayerTarget]): its preview is neither clipped by the selection nor by alpha
     * lock, and its copies are drawn one by one, as the object will be.
     */
    private fun objectTarget(layer: Layer): Boolean = vectorTarget(layer) || ownLayerTarget(layer)

    /**
     * A path object of the active vector layer under [p] that a tap reopens, and the kind of tool
     * that edits it ([kindOf]): this one, or Path for a Curve / Polyline tapping a spline
     * path, or Curve / Polyline for Path tapping a plain path. v1.9 (R14): Curve and Polyline
     * reopen each other's paths too, each in its own tool (up to v1.8 they left them alone).
     */
    private fun reopenablePathAt(p: Vec2): Pair<VPath, CurveKind>? {
        // (v1.9: while erasing, a tap starts an outline there.)
        if (opening || eraseState) return null
        val layer = controller.doc.activeLayer
        if (!vectorTarget(layer) || controller.doc.effectiveLocked(layer) || !controller.doc.effectiveVisible(layer)) return null
        val tol = controller.docLength(PATH_HIT_DP)
        val hit = controller.vectors.hitTest(layer, p, tol) as? VPath ?: return null
        if (!hit.isCurveEditable || hit.subpaths[0].anchors.size < 2) return null
        val k = kindOf(hit)
        // A filled path with a visible line reopens from its line: a tap inside its fill starts
        // a new path there (curves are drawn over filled shapes; a fill alone reopens anywhere).
        // v1.8 (item 1a): the line of any of its copies reopens the path itself (its source).
        val reach = lineReach(hit)
        if (hit.fill != null && reach > 0f && !ObjectCopies.hits(hit.copies, p, tol) { q, t -> distanceToLine(hit, q) <= reach + t }) return null
        return hit to k
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
     * Polyline tool, splines that pass the I9 check in the Path tool ([kindOf]).
     */
    private fun canReopen(p: VPath): Boolean =
        p.isCurveEditable && p.subpaths[0].anchors.size >= 2 && kindOf(p) == kind

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
        /**
         * What "unchanged" compares with: [original], without a generator its geometry no longer
         * matches (I16: the tool dropped it silently; an untouched path is stored as it was).
         */
        val baseline: VPath = original,
    )

    private var reopened: Reopened? = null
    private var reopenedState by mutableStateOf(false)
    /** The reopened path is exactly as it was (Compose state). */
    private var pristine by mutableStateOf(true)
    /** A reopen waits for the vector layers' edit session. */
    private var opening = false
    /** True while ✓ applies the path (live-stroke callbacks are ignored then). */
    private var inCommit = false
    /** v1.9 (R14): true while ✓ adds the path's own vector layer ([commitOwnLayer]). */
    private var addingOwnLayer = false
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
    fun reopen(path: VPath): Boolean = reopenSelecting(path, null)

    /** [reopen] with the points [select] selected once the path is open (null or empty: none). */
    private fun reopenSelecting(path: VPath, select: IntArray?): Boolean {
        if (anchors.isNotEmpty() || opening || !canReopen(path)) return false
        val layer = controller.doc.activeLayer
        if (!vectorTarget(layer) || layer.vector?.byId(path.id) == null) return false
        if (!controller.checkEditable(layer)) return false
        // (v1.9: a path opened for editing is painted again.)
        if (eraseState) setErase(false)
        opening = true
        // (The edit session may be prepared in the background: the path opens when it is ready,
        // unless a new path was started or the tool left meanwhile.)
        controller.vectors.beginEdit(layer, setOf(path.id)) { session ->
            val wanted = opening && anchors.isEmpty() && controller.currentTool === this && controller.doc.activeLayer === layer
            opening = false
            if (session == null) return@beginEdit
            if (!wanted) { session.cancel(); return@beginEdit }
            open(session, layer, path, select)
        }
        return reopened != null || opening
    }

    /**
     * v1.7 (item 6, §3.6; area B): switches to the Path tool on the layer [layerId] and opens its
     * path object [objectId] there ([reopen]) with the control points [select] selected; the
     * Shape tool's "Turn into path" hands a converted shape over this way. Called on any kind's
     * instance, the Path tool's does it. Nothing happens without such a layer; when the object
     * can't be opened (not a spline path that passes I9, a locked or hidden layer...), the Path
     * tool is still active on the layer. A pending path of the Path tool is applied first.
     * Indices of [select] past the path's control points are ignored; two or more selected show
     * the group gizmo, as after a box selection.
     */
    fun openPath(layerId: Long, objectId: Long, select: IntArray) {
        val tool = controller.tools[ToolId.PATH] as? CurveTool ?: return
        val layer = controller.doc.layerById(layerId) ?: return
        controller.selectLayer(layer)
        controller.selectTool(ToolId.PATH)
        if (controller.currentTool !== tool || controller.doc.activeLayer !== layer) return
        if (tool.hasPendingWork) tool.commit()
        val path = layer.vector?.byId(objectId) as? VPath ?: return
        tool.reopenSelecting(path, select)
    }

    // region v110-A6 openObject
    /**
     * v1.10 (R10): opens path object [id] of layer [layerId] in the tool of [kind] ([kindOf]), for
     * the bars' Edit. True when it opened. F0 stub: false (A6 body and its guard).
     *
     * A6: called on any kind's instance, the tool of [kind] does it, as [openPath] does for the
     * Path tool: the layer is selected, that tool becomes current, its own pending path is applied
     * and it reopens the path ([reopen]); true also while the edit session is still being
     * prepared (the path opens when it is ready). False, opening nothing, without such a layer or
     * path, or for a path of another kind. The guard: a second call while that tool is opening
     * this path, or has it open, changes nothing (true): the open path is never applied and
     * opened again.
     */
    fun openObject(layerId: Long, id: Long, kind: CurveKind): Boolean {
        val toolId = when (kind) {
            CurveKind.CURVE -> ToolId.CURVE
            CurveKind.POLYLINE -> ToolId.POLYLINE
            CurveKind.PATH -> ToolId.PATH
        }
        val tool = controller.tools[toolId] as? CurveTool ?: return false
        val layer = controller.doc.layerById(layerId) ?: return false
        val path = layer.vector?.byId(id) as? VPath ?: return false
        if (kindOf(path) != kind) return false
        if (controller.currentTool === tool && controller.doc.activeLayer === layer && tool.isOpening(layer, id)) return true
        controller.selectLayer(layer)
        controller.selectTool(toolId)
        if (controller.currentTool !== tool || controller.doc.activeLayer !== layer) return false
        if (tool.hasPendingWork) tool.commit()
        val current = layer.vector?.byId(id) as? VPath ?: return false
        val started = tool.reopenSelecting(current, null)
        tool.openingObject = if (tool.opening) layer to id else null
        return started
    }

    /** v1.10 (A6): the path object (its layer and id) an [openObject] waits to open; read only while [opening]. */
    private var openingObject: Pair<Layer, Long>? = null

    /** v1.10 (A6): true while path [id] of [layer] is opening in this tool or open in it. */
    private fun isOpening(layer: Layer, id: Long): Boolean {
        val waiting = openingObject
        if (opening && waiting != null && waiting.first === layer && waiting.second == id) return true
        val open = reopened ?: return false
        return open.layer === layer && open.original.id == id && anchors.isNotEmpty()
    }
    // endregion

    private fun open(session: VectorEditSession, layer: Layer, path: VPath, select: IntArray? = null) {
        val st = path.stroke
        val user = settings
        val strokeColor = st?.color ?: (path.fill as? VPaint.Solid)?.color ?: controller.color
        // v1.8 (I16): the generator stays only while the geometry is still its own (else it goes silently).
        val kept = path.generator?.takeIf { !polyline && CurveGenerators.matches(path) }
        val baseline = if (path.generator != null && kept == null) path.copy(generator = null) else path
        reopened = Reopened(layer, session, path, user, controller.color, strokeColor, st?.takeIf { it.kind == VStrokeKind.PLAIN }?.width, baseline)
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
        generator = kept
        // v1.8 (item 1a, §3.1): the stored copies, whatever the symmetry ruler is now.
        setMaps(path.copies.maps)
        // v1.7 (item 6): [openPath] hands over the points to select (a converted shape's corner).
        val n = pointCount
        selectionState = select?.filter { it in 0 until n }?.takeIf { it.isNotEmpty() }
            ?.let { PointSelection.none(n).plusAll(it) } ?: PointSelection.none(n)
        severalState = false
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
        // v1.8 (item 1a): and once per stored copy, as the layer draws the object.
        ObjectCopies.drawEach(canvas, objectCopies) { c -> for (spec in sessionSpecs) renderer.draw(c, spec, false, mode) }
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
    private fun buildSpecs(path: VectorPath, mode: CurveStroke, gradientFill: Boolean = false, opaque: Boolean = false): List<VectorPaintSpec> {
        if (anchors.size < 2) return emptyList()
        val s = settings
        val fill = if (s.fill && canFill && !gradientFill) path else null
        val st = reopened?.original?.stroke
        val cap = st?.cap ?: LineCapStyle.ROUND
        val join = st?.join ?: JoinStyle.ROUND
        // v1.8 (item 1a): [opaque] items go into a translucent group (the copies of a raster path).
        val color = controller.color.let { if (opaque) it or OPAQUE else it }
        val fillColor = (s.fillColor ?: controller.color).let { if (opaque) it or OPAQUE else it }
        if (mode != CurveStroke.PLAIN) {
            return listOfNotNull(VectorPaintSpec.build(fill, fillColor, null, color, 0f))
        }
        val width = lineWidth
        if (CurveGeometry.isUniformWidth(anchors)) {
            return listOfNotNull(VectorPaintSpec.build(fill, fillColor, path, color, width, cap, join))
        }
        // v1.7 (item 1, §3.1c): the centre line only while a group gesture drags a long path.
        if (centreLineOnly) {
            return listOfNotNull(VectorPaintSpec.build(fill, fillColor, path, color, controller.docLength(CENTRE_LINE_DP), cap, join))
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
        // v1.8 (item 1a, §3.1): the copies' maps are taken once, at the path's first point.
        if (maps == null && reopened == null && pointCount > 0) pointAt(0).let { setMaps(ObjectCopies.mapsAt(controller, it.x, it.y)) }
        val path = if (anchors.size >= 2) path() else null
        if (path != null) path.toAndroidPath(docPath) else docPath.rewind()
        val layer = targetLayer ?: controller.doc.activeLayer
        // v1.9 (R11 a): erasing, the outline erases live instead of painting (no reopened path then).
        if (eraseState && reopened == null) {
            changedErasing(layer)
            controller.invalidateOverlay()
            return
        }
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
        val brushLine = path != null && mode == CurveStroke.BRUSH && layer === controller.doc.activeLayer
        val copies = copyMaps
        // v1.8 (item 1a): a raster layer's copies are one translucent group (the items opaque in it);
        // a vector layer draws an object's copies one by one (v1.9: as does a path's own layer).
        val alpha = if (copies.isNotEmpty() && !objectTarget(layer)) groupAlpha(mode) else 255
        val specs = if (path != null) buildSpecs(path, mode, opaque = alpha < 255) else emptyList()
        if (brushLine && path != null) {
            preview.release()
            hideCopies()
            overlaySpecs = specs
            overlayAlpha = alpha
            val g = brushGeometry(path, settings)
            brushOverride = brushFor(path, g)
            val size = (brushOverride ?: controller.presetFor(brushToolId()))?.size ?: 0f
            if (specs.isNotEmpty()) specOverlay.setBand(docPath, size)
            val a = anchors
            val closed = isClosed && a.size > 2
            brushPreview.request(g, brushDelayMs, brushMaps()) { brushPoints(path, g, a, closed, it) }
        } else {
            brushPreview.cancel()
            overlaySpecs = emptyList()
            if (copies.isNotEmpty() && specs.isNotEmpty()) {
                // v1.8 (item 1a): the path and its copies, on every frame of the edit.
                preview.release()
                hideUnclipped()
                showCopies(layer, specs, copies, alpha, clipped = !objectTarget(layer))
            } else if (objectTarget(layer) && (controller.selection != null || layer.alphaLocked)) {
                // An object is neither clipped by the selection nor by alpha lock.
                hideCopies()
                preview.release()
                showUnclipped(layer, specs)
            } else {
                hideCopies()
                hideUnclipped()
                preview.show(layer, specs)
            }
        }
        controller.invalidateOverlay()
    }

    /**
     * v1.8 (item 1a, §3.1): the alpha a raster path's copies are painted with as ONE group, so
     * overlapping copies never darken: the paint's, when the line and the fill share it; 255 (no
     * group: each copy as a stroke of its own) when they differ.
     */
    private fun groupAlpha(mode: CurveStroke): Int {
        val s = settings
        val line = if (mode == CurveStroke.PLAIN) controller.color ushr 24 else null
        val fill = if (s.fill && canFill) (s.fillColor ?: controller.color) ushr 24 else null
        return when {
            line != null && fill != null -> if (line == fill) line else 255
            else -> line ?: fill ?: 255
        }
    }

    private fun changedReopened(r: Reopened, path: VectorPath?, mode: CurveStroke, brushDelayMs: Long) {
        preview.release()
        hideUnclipped()
        hideCopies()
        overlaySpecs = emptyList()
        val vp = buildVPath()
        // (Path: the same spline and look are unchanged even when the stored Bézier form was
        // cut differently, e.g. an approximated spline mapped by a transform.)
        pristine = vp != null && (vp == r.baseline || (isPath && vp.spline != null && vp.copy(subpaths = r.baseline.subpaths) == r.baseline))
        val gradient = r.original.fill?.takeIf { it !is VPaint.Solid && settings.fill && settings.fillColor == null && canFill }
        val specs = if (path != null) buildSpecs(path, mode, gradientFill = gradient != null) else emptyList()
        sessionGradient = if (gradient != null && vp != null) vp.copy(stroke = null) else null
        // v1.8 (item 1a): the specs are drawn once more per stored copy (the gradient fill's object
        // carries them itself).
        val doc = controller.doc
        val regions = ArrayList<Rect>(CurveCopies.regions(specs, copyMaps, Rect(0, 0, doc.width, doc.height)))
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
            brushPreview.request(g, brushDelayMs, brushMaps()) { brushPoints(path, g, a, closed, it) }
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

    // ------------------------------------------------------------------ the preview of a path with copies

    /** v1.8 (item 1a): the preview of the pending path while it has copies (null: none). */
    private var copiesPreview: CopiesPreview? = null

    /** True while the pending path and its copies are previewed on the canvas (tests). */
    internal val copiesShown: Boolean get() = copiesPreview != null

    /**
     * Previews [specs] on [layer] as they are and once per map of [copies] ([CurveCopies.draw]):
     * [alpha] groups them on a raster layer ([clipped]); a vector layer's copies are drawn as its
     * renderer draws them.
     */
    private fun showCopies(layer: Layer, specs: List<VectorPaintSpec>, copies: List<FloatArray>, alpha: Int, clipped: Boolean) {
        if (specs.isEmpty()) { hideCopies(); return }
        val ov = copiesPreview?.takeIf { it.layer === layer } ?: run {
            hideCopies()
            CopiesPreview(controller, layer).also { copiesPreview = it }
        }
        val old = ov.regions
        val doc = controller.doc
        val regions = CurveCopies.regions(specs, copies, Rect(0, 0, doc.width, doc.height))
        ov.specs = specs
        ov.copies = copies
        ov.alpha = alpha
        ov.clipped = clipped
        ov.regions = regions
        if (controller.renderOverride !== ov) controller.renderOverride = ov
        invalidateRegions(old)
        invalidateRegions(regions)
    }

    private fun hideCopies() {
        val ov = copiesPreview ?: return
        copiesPreview = null
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
                generator = generator,
                copies = objectCopies,
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
            generator = if (polyline) null else generator,
            copies = objectCopies,
        )
    }

    /**
     * I16 as the path is stored: [vp] keeps its generator only while its geometry is the
     * generator's ([CurveGenerators.matches]; every hand edit already cleared it through
     * [editGeometry], so this only guards what no step foresaw).
     */
    private fun checkedGenerator(vp: VPath): VPath =
        if (vp.generator != null && !CurveGenerators.matches(vp)) vp.copy(generator = null) else vp

    // ------------------------------------------------------------------ commit / discard

    override fun commit() {
        if (reopened != null) { commitReopened(); return }
        if (anchors.size < 2) { discard(); return }
        val layer = targetLayer ?: controller.doc.activeLayer
        if (controller.doc.indexOf(layer) < 0) { discard(); return }
        // v1.9 (R11 a): the erase checks the layer itself (one message).
        if (eraseState) { commitErase(layer); return }
        if (!controller.checkEditable(layer)) return
        if (vectorTarget(layer)) { commitObject(layer); return }
        // v1.9 (R14, D22): "Editable (own layer)" on a pixel layer.
        if (ownLayerTarget(layer)) { commitOwnLayer(); return }
        val s = settings
        val path = path()
        val mode = strokeMode(layer)
        // v1.8 (item 1a): the copies are painted in the same step, as the preview drew them (taken
        // before the path is reset).
        val copies = copyMaps
        val alpha = if (copies.isNotEmpty()) groupAlpha(mode) else 255
        val specs = buildSpecs(path, mode, opaque = alpha < 255)
        val bMaps = brushMaps()
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
                if (copies.isEmpty()) VectorCommit.commit(controller, layer, specs, label)
                else CurveCopies.commit(controller, layer, specs, copies, alpha, label)
            }
            if (brush) brushPreview.commit(g, bMaps) { brushPoints(path, g, a, closed, it) }
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
        val vp = buildVPath()?.let { checkedGenerator(it) } ?: run { discard(); return }
        val label = stepLabel
        val path = path()
        val g = brushGeometry(path, settings)
        val a = anchors
        val closed = isClosed && a.size > 2
        // v1.8 (item 1a, D2): perspective copies become plain paths of their own, added with the
        // path in ONE step and drawn by the layer (a brush line too).
        val bake = perspectiveCopies
        val bakeMaps = maps.orEmpty()
        val bMaps = brushMaps()
        // (The painting tool paints the active layer: a path on another layer is drawn by its
        // layer, as is a brush line at 0 % everywhere, which paints nothing.)
        if (bake || vp.stroke?.kind != VStrokeKind.BRUSH || strokeMode(layer) != CurveStroke.BRUSH || layer !== controller.doc.activeLayer) {
            brushPreview.cancel()
            val objects = if (bake) ObjectCopies.baked(vp, bakeMaps, ObjectCopies.bakeCanvas(controller.doc, layer)) else listOf(vp)
            val ids = controller.vectors.addObjects(layer, objects, label)
            // Refused (no memory...): the path stays pending.
            if (ids.isEmpty()) return
            if (bake) toastOncePerSession(MirrorLabels18.PERSPECTIVE_BAKED)
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
                controller.keepLayerData(layer) { brushPreview.commit(g, bMaps) { brushPoints(path, g, a, closed, it) } }
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
     * v1.9 (R14, D22): ✓ of a new path with "Editable (own layer)" on a pixel layer
     * ([ownLayerTarget]). A new vector layer "Curves N" goes in above it and becomes the active
     * layer, so the next paths join it as objects ([vectorTarget]). The path becomes its object,
     * drawn by the layer (a brush line too, as on a vector layer that is not the active one). The
     * layer and the object are ONE undo step named after the tool; the pixel layer is left as it
     * was. Adding the layer pauses this tool (`withToolPaused`): [addingOwnLayer] keeps the
     * pending path through that pause, so at the layer limit (the controller's message) the path
     * stays pending. Out of memory for the object, the empty layer goes again in the same step.
     */
    private fun commitOwnLayer() {
        val vp = buildVPath()?.let { checkedGenerator(it) } ?: run { discard(); return }
        val label = stepLabel
        // v1.8 (item 1a, D2): perspective copies become plain paths of their own, as on a vector layer.
        val bake = perspectiveCopies
        val bakeMaps = maps.orEmpty()
        var placed = false
        inCommit = true
        addingOwnLayer = true
        try {
            // The live brush stroke drew on the pixel layer: its pixels come back first.
            brushPreview.cancel()
            controller.groupUndo(label) {
                val v = controller.addVectorLayer(label = label, baseName = CurveLabels19.LAYER_BASE) ?: return@groupUndo
                val objects = if (bake) ObjectCopies.baked(vp, bakeMaps, ObjectCopies.bakeCanvas(controller.doc, v)) else listOf(vp)
                placed = controller.vectors.addObjects(v, objects, label).isNotEmpty()
                if (!placed) controller.structure.delete(v, keepChildren = false, label)
            }
        } finally {
            addingOwnLayer = false
            inCommit = false
        }
        if (!placed) {
            // The path stays pending, previewed on the pixel layer again.
            changed()
            return
        }
        if (bake) toastOncePerSession(MirrorLabels18.PERSPECTIVE_BAKED)
        resetPath()
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
            val replacement = if (pristine && vp != null) r.original else vp?.let { checkedGenerator(it) }?.copy(id = r.original.id)
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
        spline = null
        generator = null
        genStart = null
        setMaps(null)
        selectionState = PointSelection.none(0)
        severalState = false
        groupEdit = null
        trashedPending = false
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
        hideCopies()
        thicknessRing = false
        warnedPlain = false
        endErase()
        eraseRefused = null
    }

    private fun clearHistory() {
        endBevel()
        historyEpoch++
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
                // (v1.9: the Eraser's mode, which an erase on a vector or shape layer follows.)
                listOf(
                    controller.color, controller.selection, controller.layersVersion, controller.lastPaintTool, controller.presetFor(controller.lastPaintTool),
                    VectorEraserModes.mode(controller),
                )
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
        // v1.9 (R14): the pause while ✓ adds the path's own layer ([commitOwnLayer]) keeps the path.
        if (addingOwnLayer) return
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
            specOverlay.draw(
                canvas, t, controller, layer, overlaySpecs, keepBandFree = brushPreview.isLive, ignoreSelection = objectTarget(layer),
                copies = copyMaps, groupAlpha = overlayAlpha,
            )
        }
        // v1.9 (R11 a): dashed (the accent) while the outline erases.
        if (list.size >= 2) painter.path(canvas, t, docPath, dashed = eraseState)
        val scale = handleSize
        if (isPath) {
            drawPathPoints(canvas, t, scale)
            drawGroupOverlay(canvas, t)
            drawGeneratorHandles(canvas, t, scale)
            snap.draw(canvas, t, snapMoving?.let { pointBox(it) })
            return
        }
        val group = pointSelection
        // (Tangent handles show for a single selected point only.)
        val sel = if (group.isSingle) group.primary else -1
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
            pointPainter.handle(canvas, t, q[0], q[1], scale, square = list[i].sharp || polyline, active = i in group)
        }
        drawGroupOverlay(canvas, t)
        drawGeneratorHandles(canvas, t, scale)
        // Smart guides of the dragged point (on top, labels away from the finger).
        snap.draw(canvas, t, snapMoving?.let { pointBox(it) })
    }

    /** PATH: the dashed control polygon, the thickness ring and the control points (the selected one orange). */
    private fun drawPathPoints(canvas: Canvas, t: ViewTransform, scale: Float) {
        val s = spline ?: return
        pointPainter.controlPolygon(canvas, t, s)
        val group = pointSelection
        val sel = if (group.isSingle) group.primary else -1
        if (thicknessRing && sel in s.points.indices) drawThicknessRing(canvas, t, SplineEditing.pos(s.points[sel]), diameterAt(sel))
        for (i in s.points.indices) {
            val q = map(t, SplineEditing.pos(s.points[i]))
            pointPainter.controlPoint(canvas, t, q[0], q[1], selected = i in group, scale = scale, square = s.points[i].sharp)
        }
    }

    /** A dashed ring of diameter [d] (document px) around [at]. */
    private fun drawThicknessRing(canvas: Canvas, t: ViewTransform, at: Vec2, d: Float) {
        if (!(d > 0f) || !d.isFinite()) return
        ringPath.rewind()
        ringPath.addCircle(at.x, at.y, d / 2f, Path.Direction.CW)
        painter.path(canvas, t, ringPath, dashed = true)
    }

    // ------------------------------------------------------------------ erase mode (v1.9, R11 a)

    private var eraseState by mutableStateOf(false)

    /**
     * v1.9 (R11 a, design 2.4.12): the pending path erases instead of painting (bottom-bar slot 1,
     * or [EraseLabels19.INSTEAD_OF_PAINT] in the Stroke-kind menu; Compose state). Its footprint
     * is what the tool would paint ([eraseOutline]), and it erases live as it changes
     * ([ToolErase]): the pixels of a raster or text layer or of a mask, the objects of a vector or
     * legacy shape layer in the Eraser's mode. The outline is drawn dashed. ✓ (a tap outside, a
     * tool switch) makes ONE step ([eraseStepLabel]). Transient: never saved, off when the tool is
     * chosen again ([onSelected]), on from one path to the next until switched off. Meanwhile a tap
     * on a path object starts an outline there (no reopening).
     */
    override val eraseOn: Boolean get() = eraseState

    override val eraseLabel: String
        get() = when (kind) {
            CurveKind.CURVE -> EraseLabels19.WITH_CURVE
            CurveKind.POLYLINE -> EraseLabels19.WITH_POLYLINE
            CurveKind.PATH -> EraseLabels19.WITH_PATH
        }

    override val erasingLabel: String
        get() = when (kind) {
            CurveKind.CURVE -> EraseLabels19.ERASING_CURVE
            CurveKind.POLYLINE -> EraseLabels19.ERASING_POLYLINE
            CurveKind.PATH -> EraseLabels19.ERASING_PATH
        }

    /** The layer the pending path (or the next one) paints or erases: where it began, else the active layer (follows the layers in Compose). */
    val eraseTarget: Layer
        get() {
            controller.layersVersion
            return targetLayer ?: controller.doc.activeLayer
        }

    private val eraseSource: EraseSource
        get() = when (kind) {
            CurveKind.CURVE -> EraseSource.CURVE
            CurveKind.POLYLINE -> EraseSource.POLYLINE
            CurveKind.PATH -> EraseSource.PATH
        }

    /** The pending path's open erase and its layer (null: not erasing, fewer than 2 points, or refused). */
    private var eraseSession: ToolEraseSession? = null
    private var eraseLayer: Layer? = null

    /**
     * Where the erase was refused last (the layer and `layersVersion`): it isn't asked again, with
     * its message on every point, until the layers change (unlocked, shown...) or ✓.
     */
    private var eraseRefused: Pair<Layer, Int>? = null

    /**
     * Turns erase mode [on] or off. A pending path stays and shows as an erase (or as paint again);
     * a reopened path is applied first, as a tool switch applies it.
     */
    override fun setErase(on: Boolean) {
        if (on == eraseState) return
        if (on && reopened != null) commitReopened()
        eraseState = on
        eraseRefused = null
        if (!on) endErase()
        if (anchors.size >= 2) changed() else controller.invalidateOverlay()
    }

    /** v1.9 (R11 a): erase mode is off whenever the user picks the tool. */
    override fun onSelected() {
        if (eraseState) setErase(false)
    }

    /** Ends the open erase without a step (the layer shows as before). */
    private fun endErase() {
        eraseSession?.cancel()
        eraseSession = null
        eraseLayer = null
    }

    /**
     * [changed] while erasing: the tool's own previews go (the erase owns the render override; the
     * overlay draws the outline only) and the erase on [layer] follows the outline.
     */
    private fun changedErasing(layer: Layer) {
        brushPreview.cancel()
        brushOverride = null
        preview.release()
        hideUnclipped()
        hideCopies()
        overlaySpecs = emptyList()
        if (anchors.size < 2) { endErase(); return }
        val s = eraseSessionOn(layer) ?: return
        val o = eraseOutline(layer)
        s.update(o.outline, o.width, o.filled)
    }

    /** The open erase on [layer], begun when needed; null when [layer] refuses it ([eraseRefused]). */
    private fun eraseSessionOn(layer: Layer): ToolEraseSession? {
        eraseSession?.let { if (eraseLayer === layer) return it }
        endErase()
        val stamp = controller.layersVersion
        val refused = eraseRefused
        if (refused != null && refused.first === layer && refused.second == stamp) return null
        val s = ToolErase.begin(controller, layer, eraseSource)
        if (s == null) {
            eraseRefused = layer to stamp
            return null
        }
        eraseRefused = null
        eraseSession = s
        eraseLayer = layer
        return s
    }

    /** An erase footprint ([ToolEraseSession.update]): [outline] stroked [width] wide, plus its region when [filled]. */
    private class EraseOutline(val outline: Path, val width: Float, val filled: Boolean)

    /**
     * The pending path's footprint on [layer]: what the tool would paint there. The line at its
     * width ("Current brush": the brush's size; none when every point is at 0 %), plus the fill
     * when it is on (an open path fills as if closed, as it paints). With varying thickness the
     * line is its outline ([varyingOutline]); the copies (symmetry rulers) erase too, so a fill or
     * a varying line is one region, united (a mirrored copy winds the other way). The line keeps
     * its full width along the brush's taper.
     */
    private fun eraseOutline(layer: Layer): EraseOutline {
        val width = when (strokeMode(layer)) {
            CurveStroke.NONE -> 0f
            CurveStroke.BRUSH -> baseBrush()?.size ?: lineWidth
            CurveStroke.PLAIN -> lineWidth
        }
        val fill = settings.fill && canFill
        val copies = copyMaps
        val uniform = !(width > 0f) || CurveGeometry.isUniformWidth(anchors)
        if (uniform && copies.isEmpty()) return EraseOutline(docPath, width, fill)
        if (uniform && !fill) {
            // (A line doesn't depend on its contours' direction: the copies' lines join the path's.)
            val all = Path(docPath)
            for (m in copies) all.addPath(docPath, matrixOf(m))
            return EraseOutline(all, width, false)
        }
        var own = when {
            !(width > 0f) -> Path()
            uniform -> EraseCut.strokeRegion(docPath, width / 2f)
            else -> varyingOutline(width).toAndroidPath()
        }
        if (fill) own = united(own, docPath)
        var region = own
        for (m in copies) region = united(region, Path(own).apply { transform(matrixOf(m)) })
        return EraseOutline(region, 0f, true)
    }

    private fun united(a: Path, b: Path): Path = PathConvert.op(a, b, Path.Op.UNION) ?: Path(a).apply { addPath(b) }

    private fun matrixOf(m: FloatArray): Matrix = Matrix().apply { setValues(m) }

    /**
     * The erase's step: [EraseLabels19.STEP_PATH] (Path tool); for Curve and Polyline
     * [EraseLabels19.STEP_FILL] when no line erases (the fill alone), else [EraseLabels19.STEP_LINE].
     */
    private fun eraseStepLabel(layer: Layer): String = when {
        isPath -> EraseLabels19.STEP_PATH
        strokeMode(layer) == CurveStroke.NONE -> EraseLabels19.STEP_FILL
        else -> EraseLabels19.STEP_LINE
    }

    /**
     * ✓ while erasing: ONE step ([eraseStepLabel]) when the outline erased something, recorded by
     * the erase; nothing under it, no step. When [layer] refuses (its message shows) the path
     * stays, as a painted path stays.
     */
    private fun commitErase(layer: Layer) {
        // (✓ asks again: the refusal's message shows once more.)
        eraseRefused = null
        val s = eraseSessionOn(layer) ?: return
        val o = eraseOutline(layer)
        s.update(o.outline, o.width, o.filled)
        eraseSession = null
        eraseLayer = null
        if (s.commit(eraseStepLabel(layer)) is ToolEraseResult.Refused) {
            eraseRefused = layer to controller.layersVersion
            controller.invalidateOverlay()
            return
        }
        resetPath()
        brushPreview.end()
        brushOverride = null
        endLook()
        controller.invalidateOverlay()
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
        /** v1.8: the path's generator (Path ↔ Curve hand-back keeps it; To Bézier drops it: the Bézier form is no fit of the curve). */
        val generator: CurveGenerator? = null,
        /** v1.8 (item 1a): the copies' maps ([maps]; they go along both ways). */
        val maps: List<FloatArray>? = null,
        /** v1.9 (R11 a): the path erases ([eraseOn]; it goes along both ways). */
        val erase: Boolean = false,
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
        val back = Handoff(emptyList(), mine.spline, mine.targetLayer, mine.look, null, mine.seed, mine.history, mine.generator, mine.maps, mine.erase)
        curve.adopt(
            Handoff(mine.anchors, null, mine.targetLayer, mine.look, mine.reopened, mine.seed, emptyList(), maps = mine.maps, erase = mine.erase),
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
            Handoff(emptyList(), back.spline, mine.targetLayer, back.look, mine.reopened, mine.seed, emptyList(), back.generator, back.maps, mine.erase),
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
            brushPreview.sessionSeed, history.toList(), generator, maps, eraseState,
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
            reopened = Reopened(r.layer, r.session, r.original, settings, r.userColor, r.openedColor, r.width, r.baseline)
            reopenedState = true
            pristine = false
            // The strip shows the path's look (the unit and nudge step stay the user's).
            settings = h.look.copy(unit = settings.unit, nudgeStepPx = settings.nudgeStepPx, ownLayer = settings.ownLayer).sanitized(settings)
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
        generator = if (polyline) null else h.generator
        setMaps(h.maps)
        selectionState = PointSelection.none(pointCount)
        severalState = false
        this.history.clear()
        this.history.addAll(history)
        redo.clear()
        redoCount = 0
        historyKey = null
        canUndoStep = this.history.isNotEmpty()
        brushPreview.useSeed(h.seed)
        eraseState = h.erase
        eraseRefused = null
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
        /** How far right of a lone point [addPointAfterSelected] puts the next one (screen dp). */
        private const val ADD_POINT_GAP_DP = 48f
        /** How close to a path object a tap reopens it (screen dp). */
        private const val PATH_HIT_DP = 12f
        /** How long the brush stroke waits for a point just placed under a finger (see onDown). */
        internal const val NEW_POINT_BRUSH_DELAY_MS = 150L
        /** Undo label of ✓ on a reopened path object. */
        const val EDIT_PATH_LABEL = "Edit path"
        /** v1.9 (R14): how long the bottom bar's tool slot flashes after a switch ([slotFlash]). */
        const val SLOT_FLASH_MS = 200L

        /**
         * v1.9 (R14): the [slotFlash] signals of the Curve, Polyline and Path tools of [tools] (the
         * controller's), summed (Compose state). It rises by one with each switch that reopens a
         * line in another curve tool, and never with a tool change of its own, so the bottom bar
         * flashes its tool slot once, for [SLOT_FLASH_MS], each time it rises.
         */
        fun slotFlashes(tools: Map<ToolId, Tool>): Int =
            (tools[ToolId.CURVE] as? CurveTool)?.slotFlash.orZero() +
                (tools[ToolId.POLYLINE] as? CurveTool)?.slotFlash.orZero() +
                (tools[ToolId.PATH] as? CurveTool)?.slotFlash.orZero()

        private fun Int?.orZero(): Int = this ?: 0

        /**
         * The tool that edits [p]: Path when it keeps a spline that passes the I9 check
         * ([SplineBezier.matches]; else the spline is stale and the path is a plain Bézier path),
         * otherwise Polyline or Curve by its corners. v1.10 (F0): in the companion, so the bars'
         * Edit (A6) opens a path in its own tool.
         */
        fun kindOf(p: VPath): CurveKind = when {
            p.spline != null && SplineBezier.matches(p) -> CurveKind.PATH
            p.polyline -> CurveKind.POLYLINE
            else -> CurveKind.CURVE
        }

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
        /** v1.7 (item 1): a curve, polyline or path keeps at least this many points ([deleteSelectedPoints]). */
        const val MIN_POINTS = 2

        /** v1.7 (item 1, §3.1c): above this many points a group drag shows the line's centre, [CENTRE_LINE_DP] wide on screen. */
        internal const val LIVE_OUTLINE_MAX_POINTS = 500
        private const val CENTRE_LINE_DP = 1f

        /** The in-tool step of a group gesture (in-tool steps show no label; the session commits as [EDIT_PATH_LABEL]). */
        private const val GROUP_MOVE_LABEL = EDIT_PATH_LABEL

        /** The X / Y pill's "Keep scale proportions" preference (AppSettings object; on by default). */
        internal const val KEEP_PROPORTIONS_KEY = "pill.keepProportions"

        /** The pill's position labels besides "Point 3": two or more points, and no point selected (I10). */
        const val SELECTED_POINTS_LABEL = "Selected points"
        const val CENTER_LABEL = "Center"

        /** v1.8 (item 6): the last-used generator settings (AppSettings object, placement-free; Curve and Path share it). */
        const val LAST_GENERATOR_KEY = "curve.generator.last"

        /** v1.8 (item 6): a picked curve's size handle lies this fraction of the view's short side from its centre. */
        const val GENERATOR_VIEW_FRACTION = 0.3f

        /** The pill's Scale row: 100 % is the points as selected; held to 1 %..10 000 %. */
        private val HUNDRED = Vec2(100f, 100f)
        private const val MIN_SCALE_PERCENT = 1f
        private const val MAX_SCALE_PERCENT = 10_000f
    }
}
