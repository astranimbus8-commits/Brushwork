package com.brushwork.paint.tools.vector

import android.graphics.Bitmap
import android.graphics.Canvas
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
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushPreset
import com.brushwork.paint.brush.PathStrokeInput
import com.brushwork.paint.brush.StrokeKind
import com.brushwork.paint.brush.StrokeRaster
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.core.Affine2
import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.IncrementMath
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.engine.LayerConvert
import com.brushwork.paint.engine.LayerRenderOverride
import com.brushwork.paint.engine.LayerStructure
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.ArrayInstances
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.GridType
import com.brushwork.paint.model.IncrementKind
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.ColoredObjectTool
import com.brushwork.paint.tools.DeletingTool
import com.brushwork.paint.tools.ErasableTool
import com.brushwork.paint.tools.ObjectColorEditor
import com.brushwork.paint.tools.ObjectDeletion
import com.brushwork.paint.tools.ObjectPosition
import com.brushwork.paint.tools.ObjectScale
import com.brushwork.paint.tools.PillPositionTool
import com.brushwork.paint.tools.PinchTargeting
import com.brushwork.paint.tools.ScaledTool
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.points.BevelPoint
import com.brushwork.paint.tools.points.CornerTool
import com.brushwork.paint.tools.points.Mixed
import com.brushwork.paint.tools.points.PointBevel
import com.brushwork.paint.tools.points.PointCornerEditor
import com.brushwork.paint.tools.points.PointEditor
import com.brushwork.paint.tools.points.PointGizmo
import com.brushwork.paint.tools.points.PointGroupMath
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.transform.ContentBounds
import com.brushwork.paint.tools.transform.DocBox
import com.brushwork.paint.tools.transform.IncrementReadout
import com.brushwork.paint.tools.transform.SnapAxis
import com.brushwork.paint.tools.transform.SnapGuide
import com.brushwork.paint.tools.transform.SnapGuideRenderer
import com.brushwork.paint.tools.transform.SnapHit
import com.brushwork.paint.tools.transform.SnapLine
import com.brushwork.paint.tools.transform.TransformIncrements
import com.brushwork.paint.tools.transform.offset
import com.brushwork.paint.ui.common.CogLabels18
import com.brushwork.paint.ui.common.EraseLabels19
import com.brushwork.paint.ui.common.FolderLabels
import com.brushwork.paint.ui.common.LayerLabels19
import com.brushwork.paint.ui.common.MirrorLabels18
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.common.PointLabels19
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.vector.CopyMaps
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStrokeKind
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorLayers
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.draw.EraseSource
import com.brushwork.paint.vector.draw.ToolErase
import com.brushwork.paint.vector.draw.ToolEraseResult
import com.brushwork.paint.vector.draw.ToolEraseSession
import com.brushwork.paint.vector.edit.VectorEditSession
import com.brushwork.paint.vector.render.VectorLayerRenderer
import com.brushwork.paint.vector.select.ObjectActions
import com.brushwork.paint.vector.select.ObjectEdits
import com.brushwork.paint.vector.select.PendingRenders
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How a shape's outline is painted. */
@Serializable
enum class ShapeStroke(val label: String) {
    /** A plain anti-aliased line (caps, joins and corner styles exactly as set). */
    PLAIN("Plain line"),
    /** The last painting tool (brush / smudge / blur) is driven along the outline. */
    BRUSH("Current brush"),
}

/** Persisted options of the shape tool. Lengths are document pixels. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ShapeSettings(
    val type: ShapeType = ShapeType.RECTANGLE,
    val style: ShapeStyle = ShapeStyle.STROKE,
    /** Stroke width used when [useBrushSize] is off. */
    val strokeWidth: Float = 8f,
    /** The stroke width follows the size of the current brush (the size slider). */
    val useBrushSize: Boolean = true,
    /** Plain line or painted with the current brush. */
    val strokeWith: ShapeStroke = ShapeStroke.PLAIN,
    /** Fill color, or null to follow the main drawing color. */
    val fillColor: Int? = null,
    val lineCap: LineCapStyle = LineCapStyle.ROUND,
    val corner: CornerStyle = CornerStyle.SHARP,
    val cornerRadius: Float = 30f,
    val sides: Int = 5,
    val starPoints: Int = 5,
    /** Star inner radius as a fraction of the outer radius. */
    val innerRatio: Float = 0.45f,
    val arrowHeads: ArrowHeads = ArrowHeads.END,
    val arrowHeadStyle: ArrowHeadStyle = ArrowHeadStyle.FILLED,
    /** Arrowhead length as a multiple of the stroke width. */
    val arrowHeadScale: Float = 4f,
    val fromCenter: Boolean = false,
    val keepProportions: Boolean = false,
    /** Lines snap to 15 degree steps; rotation snaps to 15 degrees. */
    val snapAngle: Boolean = false,
    /** Unit used by the numeric fields. */
    val unit: LengthUnit = LengthUnit.PX,
    val nudgeStepPx: Float = 1f,
    /**
     * "Editable (own layer)": a new shape goes into a new layer of its own that keeps the shape
     * (tap it with the shape tool to edit it again); off = painted into the active layer.
     */
    val editable: Boolean = true,
    /** v1.8 (item 7): the cog's teeth, 3..64 ([CogLabels18.TEETH]). */
    val cogTeeth: Int = CogParams.DEFAULT.teeth,
    /** v1.8: the teeth's depth, 0.05..0.5 of the outer radius ([CogLabels18.TOOTH_DEPTH]). */
    val cogDepth: Float = CogParams.DEFAULT.depth,
    /** v1.8: a tooth's width, 0.2..0.8 of the pitch ([CogLabels18.TOOTH_WIDTH]). */
    val cogToothWidth: Float = CogParams.DEFAULT.toothWidth,
    /** v1.8: how much a tooth narrows toward its tip, 0..0.9 ([CogLabels18.TOOTH_TAPER]). */
    val cogTaper: Float = CogParams.DEFAULT.taper,
    /** v1.8: the hole's radius, 0..0.8 of the root radius; 0 = no hole ([CogLabels18.HOLE_SIZE]). */
    val cogHole: Float = CogParams.DEFAULT.hole,
    /**
     * v1.9 (R14; PlaceLabels19.NEW_LAYER_EACH_TIME in the Shape cog): with [editable] on, every
     * new shape gets a layer of its own (v1.8); off (default), a new shape joins the selected
     * shape layer. Never written while off (I13), so v1.8 reads the settings.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val newLayerEachTime: Boolean = false,
) {
    /** v1.8: the cog fields as one value. */
    val cogParams: CogParams get() = CogParams(cogTeeth, cogDepth, cogToothWidth, cogTaper, cogHole)

    val outlineParams: OutlineParams get() = OutlineParams(sides, starPoints, innerRatio, corner, cornerRadius, cogParams)

    /** The shape has an outline (lines and arrows always do). */
    val strokes: Boolean get() = type.isLineLike || style.stroke

    /** Clamps every value to its supported range; non-finite values are taken from [fallback]. */
    fun sanitized(fallback: ShapeSettings = DEFAULT) = copy(
        strokeWidth = strokeWidth.finiteOr(fallback.strokeWidth).coerceIn(MIN_STROKE, MAX_STROKE),
        cornerRadius = cornerRadius.finiteOr(fallback.cornerRadius).coerceIn(0f, MAX_LENGTH),
        sides = sides.coerceIn(ShapeGeometry.MIN_SIDES, ShapeGeometry.MAX_SIDES),
        starPoints = starPoints.coerceIn(ShapeGeometry.MIN_SIDES, ShapeGeometry.MAX_SIDES),
        innerRatio = innerRatio.finiteOr(fallback.innerRatio).coerceIn(0.05f, 0.95f),
        arrowHeadScale = arrowHeadScale.finiteOr(fallback.arrowHeadScale).coerceIn(2f, 12f),
        nudgeStepPx = nudgeStepPx.finiteOr(fallback.nudgeStepPx).coerceIn(0.01f, MAX_LENGTH),
        cogTeeth = cogTeeth.coerceIn(CogParams.MIN_TEETH, CogParams.MAX_TEETH),
        cogDepth = cogDepth.finiteOr(fallback.cogDepth).coerceIn(CogParams.MIN_DEPTH, CogParams.MAX_DEPTH),
        cogToothWidth = cogToothWidth.finiteOr(fallback.cogToothWidth).coerceIn(CogParams.MIN_TOOTH_WIDTH, CogParams.MAX_TOOTH_WIDTH),
        cogTaper = cogTaper.finiteOr(fallback.cogTaper).coerceIn(0f, CogParams.MAX_TAPER),
        cogHole = cogHole.finiteOr(fallback.cogHole).coerceIn(0f, CogParams.MAX_HOLE),
    )

    /** These options with the drawing options of [o] (the ones that are not part of a shape). */
    fun withBehaviourOf(o: ShapeSettings) = copy(
        fromCenter = o.fromCenter,
        keepProportions = o.keepProportions,
        snapAngle = o.snapAngle,
        unit = o.unit,
        nudgeStepPx = o.nudgeStepPx,
        editable = o.editable,
        newLayerEachTime = o.newLayerEachTime,
    )

    /** These options showing the look of the placed shape [s] (its width is fixed, not the brush size). */
    fun showing(s: ShapeObject) = copy(
        type = s.type,
        style = s.style,
        strokeWidth = s.strokeWidth,
        useBrushSize = false,
        strokeWith = s.strokeWith,
        fillColor = if (s.fillFollowsColor) null else s.fillColor,
        lineCap = s.lineCap,
        corner = s.corner,
        cornerRadius = s.cornerRadius,
        sides = s.sides,
        starPoints = s.starPoints,
        innerRatio = s.innerRatio,
        arrowHeads = s.arrowHeads,
        arrowHeadStyle = s.arrowHeadStyle,
        arrowHeadScale = s.arrowHeadScale,
    ).let { if (s.type == ShapeType.COG) it.copy(cogTeeth = s.cogTeeth, cogDepth = s.cogDepth, cogToothWidth = s.cogToothWidth, cogTaper = s.cogTaper, cogHole = s.cogHole) else it }

    companion object {
        const val MIN_STROKE = 0.25f
        const val MAX_STROKE = 2000f
        const val MAX_LENGTH = 100_000f
        private val DEFAULT = ShapeSettings()
    }
}

/**
 * Lines, rectangles, ellipses, polygons, stars and arrows. Drag to create; the shape then stays
 * editable (move, 8 resize handles, rotation handle, two-finger pinch inside it, numeric entry)
 * until committed with ✓, by tapping outside it, or by switching tools. A plain outline previews
 * through the compositor; with "Current brush" the painting tool's own stroke is shown live.
 *
 * EDITABLE SHAPES ("Editable (own layer)", on by default): a new shape is committed into a new
 * layer of its own that keeps the shape object ([Layer.shapeData], JSON via [ShapeCodec]). A tap
 * on such a shape (its outline or its fill; the active layer first, then from the top) opens it
 * again: the layer becomes active, its pixels are hidden while it is edited ([EditOverride]: the
 * edited shape is drawn in their place through the compositor), the options and the main color
 * show the shape's own, and ✓ / a tap outside / switching tools or layers re-renders the layer as
 * ONE undo step "Edit shape" ([EditorController.updateShapeLayer]; nothing is recorded when
 * nothing changed); ✕ leaves the layer as it was. A drag that starts outside the open shape
 * always draws a NEW shape (also over another shape layer), like the shape tools of drawing apps.
 *
 * POINTS ("Points"): the pending shape is converted to its own points ([ShapePoints], stored
 * box-local so the box handles keep transforming it). Drag a point to move it; tap the outline
 * or a "+" between two points to insert one (the outline does not change); tap / long-press a
 * point to select it for delete / sharp / smooth / automatic tangent and to drag its tangent
 * handles.
 *
 * PER-EDIT UNDO (v1.9, R17): while a shape is pending (new, a reopened shape layer or a reopened
 * shape object of a vector layer), each finished move, resize, rotation, line-end or point drag,
 * nudge run, pinch, typed or scrubbed value and point edit is one in-tool step, for every shape
 * type, with or without its own points. Undo (button or two-finger tap) takes back the last edit,
 * not the placement; at the bottom of these steps Undo acts as in v1.8 (a new shape is discarded,
 * an untouched reopened shape closes and the document's last step is undone). The commit is still
 * ONE document step.
 *
 * ERASE MODE (v1.9, R11; [ErasableTool]): bottom-bar slot 1 ([EraseLabels19.WITH_SHAPE]) or the
 * last row of "Stroke with" ([EraseLabels19.INSTEAD_OF_PAINT]) turns it on, after what is pending
 * lands. Every shape then drawn is an eraser: its outline (dashed, with its copies) erases live as
 * it changes, through a [ToolEraseSession] on the active layer ([ToolErase.begin]: pixels of a
 * raster layer or a mask, objects of a vector or legacy shape layer in the Eraser's mode; refused
 * with its message, the outline shows alone), with the stroke at its width and, for a filled
 * closed style, its fill. Every edit of the pending shape works as when painting and updates the
 * erase. Its commit (✓, a tap outside, a tool or layer switch, turning erase mode off) is ONE step
 * [EraseLabels19.STEP_SHAPE]; ✕ leaves no trace. "Editable (own layer)", PlaceLabels19.NEW_LAYER_EACH_TIME
 * and joining are ignored, a tap opens no shape, and "Turn into path" and the object items are
 * not offered. Transient: never saved, off whenever the tool is chosen again ([onSelected]).
 *
 * VECTOR LAYERS (v1.5): on a vector layer a new shape becomes a shape object (`VShape`) on top
 * of the layer ("Editable (own layer)" is implied), ONE undo step "Shape"; a brush outline shown
 * live is kept as it is (its seed is stored, so the object's replay is those very pixels). A tap
 * on a shape object reopens it through a `VectorEditSession` (the layer with a hole where the
 * object was, the edited shape and its live brush stroke drawn there); ✓ replaces it in place as
 * ONE step "Edit shape", ✕ leaves the layer untouched. Shape objects ignore the pixel selection
 * and the alpha lock; outlines of tools that move pixels (smudge, blur, watercolor) are refused.
 *
 * SNAP TO OBJECTS ([EditorController.snapping], one setting for the whole app): the corner being
 * dragged out, moved shapes (their outline's bounds), resize handles, line ends, points and
 * tangent handles align to the canvas, the other layers' content and drawn lines, the points of
 * the other shape layers ([ShapeOutlines.featurePoints]) and the shape's own other points, with
 * magenta guides; the shape layer being edited is not a target. Axes that don't snap keep
 * following the square grid when grid snapping is on.
 *
 * INCREMENTS (v1.6 §3.4, `controller.increments`; off by default, and then every gesture is the
 * v1.5 one): a moved shape, a dragged point or tangent handle moves by multiples of the Length
 * step from where it started; a new or resized box gets its dragged width / height, and a line its
 * length, on multiples of it (absolute sizes); the rotation handle, a line's direction and a
 * pinch's angle land on the Angle step (instead of the 15° option); a pinch scales by multiples of
 * the Scale step from its start. Per axis a guide wins, then the grid, then the step; the readout
 * (`increments.readout`) says where a stepped gesture is.
 */
class ShapeTool(controller: EditorController) : Tool(controller), PointEditor, PillPositionTool, ScaledTool, DeletingTool, ErasableTool,
    ColoredObjectTool, CornerTool {
    override val id = ToolId.SHAPE

    /** Current options (Compose state); change them with [update]. */
    var settings by mutableStateOf(loadSettings())
        private set

    private var boxState by mutableStateOf<ShapeBox?>(null)

    /** The pending (still editable) shape, or null (Compose state). */
    var box: ShapeBox?
        get() = boxState
        private set(value) {
            // A shape opens or closes: the pill's scale reference is taken again ([ShapeObjectScale]).
            if ((value == null) != (boxState == null)) openCount++
            boxState = value
        }

    /** Counts the pending shape opening and closing (the pill's scale reference follows it). */
    internal var openCount = 0
        private set

    private var pointsState by mutableStateOf<List<ShapePoint>?>(null)

    /**
     * Custom points of the pending shape (box-local, normalized), or null for its regular outline.
     * A change of their number keeps [pointSelection] sized to them (callers that insert or delete
     * points map the selection themselves: `afterInsert` / `afterRemove`).
     */
    var points: List<ShapePoint>?
        get() = pointsState
        private set(value) {
            pointsState = value
            val n = value?.size ?: 0
            if (pointSelection.size != n) pointSelection = pointSelection.resized(n)
        }

    /** Point editing is on for the pending shape. */
    var pointsMode by mutableStateOf(false)
        private set

    /**
     * v1.7 (item 1, §3.1): the selected points of the pending shape in points mode (Compose
     * state), sized to [points]. One point selected is v1.6's selection ([selectedPoint]).
     */
    override var pointSelection by mutableStateOf(PointSelection.none(0))
        private set

    /**
     * Index of the selected point (points mode) or -1: the primary of [pointSelection] (the point
     * the single-point actions and the numbers act on). Setting it selects only that point.
     */
    var selectedPoint: Int
        get() = pointSelection.primary
        private set(value) {
            pointSelection = PointSelection.none(points?.size ?: 0).only(value)
        }

    /** The shape layer being edited again (null while a new shape is placed). */
    var editingLayer by mutableStateOf<Layer?>(null)
        private set

    /** True when [undoStep] can take back an edit of the pending shape (v1.9, R17: of any shape). */
    override var canUndoStep by mutableStateOf(false)
        private set

    /** Number of edits [redoStep] can bring back. */
    var redoCount by mutableIntStateOf(0)
        private set

    override val hasPendingWork: Boolean get() = box != null

    /**
     * A shape layer that was only opened (nothing changed yet) doesn't swallow an undo. v1.9
     * (R17): one whose edits brought it back as it was opened still has them to take back (undo
     * then takes back the last of them, never the document's step before the shape).
     */
    override val hasUserChanges: Boolean
        get() {
            val b = box ?: return false
            if (!reopened || canUndoStep) return true
            return loadedObject != objectFor(b, points)
        }

    /** A placed shape is open for editing (a shape layer, or a shape object of a vector layer). */
    private val reopened: Boolean get() = editingLayer != null || vectorSession != null

    override val canRedoStep: Boolean get() = redoCount > 0 && box != null

    private var targetLayer: Layer? = null
    private var creatingBox: ShapeBox? = null
    private val preview = PreviewHost(controller)
    private val brushPreview = BrushStrokePreview(controller, paintToolId = { brushToolId() }, presetOverride = { editBrush?.second })
    private val specOverlay = SpecOverlay()
    private val renderer = VectorRenderer()
    /** Plain items drawn in the overlay while the brush preview owns the render override. */
    private var overlaySpecs: List<VectorPaintSpec> = emptyList()
    private var observeJob: Job? = null
    /** True while [commit] runs (adding a layer pauses this tool: that must not end anything). */
    private var inCommit = false

    // ------------------------------------------------------------------ edited shape layer

    /** The user's own options while a shape layer is edited (the strip shows the shape's). */
    private var userSettings: ShapeSettings? = null
    /** The user's main color when the shape layer was opened (it comes back unless they picked another one). */
    private var userColor: Int? = null
    /** The main color the opened shape set (its stroke color). */
    private var openedColor = 0
    /** The shape as opened (committing it unchanged records nothing). */
    private var loadedObject: ShapeObject? = null
    /** Where the edited layer's pixels are (hidden while it is edited). */
    private var loadedInk: Rect? = null
    /** The painting tool and brush a re-opened brush-stroked shape was drawn with. */
    private var editBrush: Pair<ToolId, BrushPreset>? = null
    /**
     * v1.8 (I15, §3.1): the live copies of the pending shape: a re-opened shape's stored ones
     * (whatever the symmetry ruler is now), or a NEW shape's, taken from the ruler at its start
     * point ([useNewMaps]); they become its copies when it is committed.
     */
    private var openCopies: CopyMaps by mutableStateOf(CopyMaps.NONE)
    /**
     * v1.8 (§3.1): a NEW shape's Perspective array maps (not affine, so not [openCopies]): its
     * copies are drawn through them and committed as pixels or as plain paths; empty otherwise.
     */
    private var openPerspective: List<FloatArray> by mutableStateOf(emptyList())
    /** v1.8 (§3.1): the symmetry maps of the shape being dragged out ([creatingBox]), taken at its start point. */
    private var creatingMaps: List<FloatArray> = emptyList()
    /** v1.8: [MirrorLabels18.PERSPECTIVE_BAKED] / [MirrorLabels18.PERSPECTIVE_PIXELS] were shown (once per session each). */
    private var bakedHintShown = false
    private var pixelsHintShown = false
    private var editOverride: EditOverride? = null
    /** The outline a brush follows, drawn as a guide when the brush can't be shown live. */
    private var editGuide: Path? = null
    private val guidePath = Path()
    /** The brush outline of a NEW shape drawn as a guide when it can't be shown live (see [refreshPreview]). */
    private var brushGuide: Path? = null
    private val brushGuidePath = Path()

    // ------------------------------------------------------------------ vector layers (v1.5, A3)

    /** A shape object of a vector layer reopened for editing: its preview (the layer with a hole), or null. */
    private var vectorSession: VectorEditSession? = null
    /** Id and opacity of the reopened shape object. */
    private var vectorEditId = 0L
    private var vectorEditOpacity = 1f
    /** Preview of a NEW shape on a vector layer: the layer, the shape and the live brush stroke. */
    private var vectorPreview: VectorShapePreview? = null
    /** Regions the vector preview drew last (redrawn when it changes). */
    private var vectorShown: List<Rect> = emptyList()
    /** Brush outlines that can't be shown live on a vector layer, drawn as guides. */
    private var vectorGuides: List<Path> = emptyList()
    /** Bumped by every reopen request: an answer to an older one is dropped. */
    private var reopenToken = 0
    /** The shape object the last commit placed or closed (the tap that closed it doesn't reopen it). */
    private var lastVectorId: Long? = null

    /** A shape object of a vector layer is open for editing (Compose state). */
    var editingObject by mutableStateOf(false)
        private set

    // ------------------------------------------------------------------ erase mode (v1.9, R11)

    /** Erase mode is on (Compose state): the shapes drawn erase (see the class comment). */
    override var eraseOn by mutableStateOf(false)
        private set

    override val eraseLabel: String get() = EraseLabels19.WITH_SHAPE

    override val erasingLabel: String get() = EraseLabels19.ERASING_SHAPE

    /** The erase of the outline being drawn, open from its first change to its commit (null before, or refused). */
    internal var eraseSession: ToolEraseSession? = null
        private set

    /** [ToolErase.begin] refused the outline being drawn (it said why once): it shows alone until its commit asks again. */
    private var eraseRefused = false

    /** The outline being erased with, its copies included (document px), drawn dashed; null without one. */
    internal var eraseOutline: Path? = null
        private set
    private val erasePath = Path()

    // ------------------------------------------------------------------ gestures

    /**
     * v1.7 additions: GROUP_POINT drags the selected points by one of them (a tap acts on that
     * point), GIZMO is a drag on the group gizmo ([gizmoPart]), MARQUEE box-selects ("Select
     * several").
     */
    private enum class Mode { NONE, CREATE, MOVE, RESIZE, ROTATE, LINE_START, LINE_END, POINT, NEW_POINT, HANDLE_IN, HANDLE_OUT, GROUP_POINT, GIZMO, MARQUEE }

    private var mode = Mode.NONE
    private var handle: ShapeGeometry.Handle? = null
    private var startBox: ShapeBox? = null
    private var downPoint = Vec2.ZERO
    private var anchor = Vec2.ZERO
    /** The current gesture moved past the touch slop. */
    private var started = false
    /** The current touch long-pressed a point and selected it. */
    private var longPressed = false
    /** Point gestures: the point dragged (or whose handle is dragged). */
    private var dragIndex = -1
    /** Point gestures: the shape's points in document px when the gesture started. */
    private var startAnchors: List<ShapeAnchor> = emptyList()
    /** Point gestures: where the dragged point / handle end was when the gesture started. */
    private var grabStart = Vec2.ZERO
    /** Move: the outline's bounds when the gesture started. */
    private var startBounds: DocBox? = null
    /** State when the gesture started (a cancelled gesture goes back to it). */
    private var gestureState: PendingState? = null
    private var gestureHistory = 0
    private var gestureRedo: List<PendingState> = emptyList()
    /** The gesture already saved the shape for undo. */
    private var gesturePushed = false

    /** The shape when a two-finger pinch on it began (null when not pinching). */
    private var pinchStart: ShapeBox? = null
    private var pinchFocus = Vec2.ZERO
    private var pinchPushed = false
    /**
     * v1.9 (R17): the redo steps when the two-finger gesture began. Its step is saved at once, which
     * drops them; a two-finger tap (the app's undo: the gesture is cancelled, then undo runs), a
     * cancelled pinch or one that changed nothing brings them back with its step gone.
     */
    private var pinchRedo: List<PendingState> = emptyList()

    // ------------------------------------------------------------------ group gestures (v1.7 item 1)

    private val gizmo = PointGizmo()
    /** GIZMO: the gizmo when the finger went down, and the part it grabbed. */
    private var gizmoLayout: PointGizmo.Layout? = null
    private var gizmoPart = PointGizmo.Part.NONE
    /** GROUP_POINT, GIZMO: the point under the finger when it went down (a tap acts on it), or -1. */
    private var tapIndex = -1
    /** MARQUEE: where the finger is now (document px). */
    private var marqueeEnd: Vec2? = null
    /** A two-finger pinch that started inside the gizmo scales and rotates the selected points about this pivot. */
    private var groupPinchPivot: Vec2? = null

    // ------------------------------------------------------------------ snapping

    private val snap = controller.newSnapSession()
    /** Guides of a resize handle (built here: only the dragged sides are shown). */
    private var resizeGuides: List<SnapGuide> = emptyList()

    // ------------------------------------------------------------------ in-tool history

    private data class PendingState(
        val box: ShapeBox,
        val points: List<ShapePoint>?,
        val pointsMode: Boolean,
        val selection: PointSelection,
        // v1.8 (§3.1): the shape's live copies ([MirrorLabels18.REMOVE] is an in-tool step).
        val copies: CopyMaps = CopyMaps.NONE,
        val perspective: List<FloatArray> = emptyList(),
    )

    /** The pending shape as an in-tool step saves it ([b] its box). */
    private fun pendingState(b: ShapeBox) = PendingState(b, points, pointsMode, pointSelection, openCopies, openPerspective)

    private val history = ArrayDeque<PendingState>()
    private val redo = ArrayDeque<PendingState>()
    private var historyKey: Any? = null
    private var historyKeyTime = 0L
    /** A slider drag is in progress ([beginNumericEdit]): its edits share one step whatever the pace. */
    private var numericHeld = false
    private data class NumericKey(val kind: String, val index: Int)
    /** A run of nudges of the same group of points (one in-tool step). */
    private data class GroupNudgeKey(val selection: PointSelection)

    /** Time source for coalescing numeric edits (replaceable in tests). */
    internal var clock: () -> Long = { SystemClock.uptimeMillis() }

    // ------------------------------------------------------------------ drawing helpers

    private val painter = OverlayPainter()
    private val boxPath = Path()
    private val bandPath = Path()
    private val marqueePath = Path()
    private val outlinePath = Path()
    private var outlineKey: Any? = null
    private val pts = FloatArray(2)

    /** Decoded shapes of shape layers (hit testing, snapping), keyed by layer id. */
    private val decodedLayers = HashMap<Long, Pair<String, ShapeObject?>>()
    private val featureCache = HashMap<Long, Triple<String, String, List<SnapLine>>>()

    init {
        // The points of every shape layer are snap targets for all tools.
        controller.snapping.addLayerFeatures { layer -> layerFeatures(layer) }
    }

    // ------------------------------------------------------------------ settings

    /** Changes the options; a pending shape updates live. */
    fun update(transform: (ShapeSettings) -> ShapeSettings) {
        val old = settings
        val new = transform(old).sanitized(fallback = old)
        if (new == old) return
        if (refusesIntoFolder(old, new)) {
            controller.toast(FolderLabels.PAINT_REFUSAL)
            return
        }
        settings = new
        // v1.9 (R18): another action closes the bevel popup (the bevel stays).
        closeBevelPopup()
        // While a shape layer is edited the options show that shape: only the drawing options
        // are the user's own (and saved).
        val user = userSettings
        val toSave = if (user != null) user.withBehaviourOf(new).also { userSettings = it } else new
        runCatching { controller.settings.putObject(PREFS_KEY, ShapeSettings.serializer(), toSave) }
        val b = box
        if (b != null && old.type != new.type && points != null) {
            // Custom points belong to the old type: the new type starts from its regular outline.
            points = null
            pointsMode = false
            selectedPoint = -1
            selectSeveral = false
            clearHistory()
            if (old.type.isLineLike == new.type.isLineLike) box = clean(b)
        }
        if (b != null && old.type.isLineLike != new.type.isLineLike) {
            // v1.9 (R17): the edits before a change between a line-like and a box-like type do
            // not map onto the new geometry: the in-tool history starts again (a change within
            // one kind keeps it: its boxes still apply).
            clearHistory()
            box = clean(convert(b, new.type))
        }
        refreshPreview()
    }

    /**
     * v1.7 (§3.8): a pending NEW shape placed with a folder active goes into a layer of its own;
     * options that would paint it into the folder instead ("Editable (own layer)" off, or a
     * smudge / blur outline) are refused and kept as they were. (A shape being placed later is
     * refused when it starts, [checkCanPlaceNew].)
     */
    private fun refusesIntoFolder(old: ShapeSettings, new: ShapeSettings): Boolean {
        if (box == null || editingLayer != null || vectorSession != null || userSettings != null) return false
        return paintsIntoFolder(new) && !paintsIntoFolder(old)
    }

    private fun loadSettings(): ShapeSettings =
        runCatching { controller.settings.getObject(PREFS_KEY, ShapeSettings.serializer()) }.getOrNull()?.sanitized() ?: ShapeSettings()

    /** Size of the current painting tool's brush (what "Use brush size" follows), or null. */
    val brushSize: Float?
        get() = controller.presetFor(controller.lastPaintTool)?.size?.takeIf { it.isFinite() && it > 0f }
            ?.coerceIn(ShapeSettings.MIN_STROKE, ShapeSettings.MAX_STROKE)

    /** The stroke width in use: the brush size when "Use brush size" is on (Compose state). */
    val strokeWidth: Float
        get() = strokeWidthOf(settings)

    private fun strokeWidthOf(s: ShapeSettings): Float = if (s.useBrushSize) brushSize ?: s.strokeWidth else s.strokeWidth

    /**
     * The options a NEW shape is drawn with: the user's own, also while a shape layer is edited
     * (the strip then shows the opened shape's, which come back to the user's when it closes).
     */
    private fun newShapeSettings(): ShapeSettings = userSettings ?: settings

    /** The main color a NEW shape gets: the user's own color comes back when the opened shape closes. */
    private fun newShapeColor(): Int {
        val uc = userColor
        return if (reopened && uc != null && controller.color == openedColor) uc else controller.color
    }

    /**
     * True when shapes drawn on [layer] become objects of it: a vector layer whose content (not
     * its mask) is edited (v1.5, §4.9).
     */
    private fun isVectorTarget(layer: Layer): Boolean =
        layer.isVectorLayer && controller.editTargetOf(layer) == EditTarget.CONTENT

    /**
     * New shapes become shape objects of the active vector layer ("Editable (own layer)" is
     * implied there), or a shape object is open for editing (Compose state).
     */
    val drawsOnVectorLayer: Boolean
        get() {
            controller.layersVersion
            return editingObject || isVectorTarget(controller.doc.activeLayer)
        }

    /** The vector layer the pending / dragged shapes belong to, or null. */
    private fun vectorLayerOfPending(): Layer? {
        vectorSession?.let { return it.layer }
        if (editingLayer != null) return null
        return (targetLayer ?: controller.doc.activeLayer).takeIf { isVectorTarget(it) }
    }

    /**
     * True when a NEW shape goes into a layer of its own ("Editable"). Smudge and blur outlines
     * only move pixels that are already there, so they are painted into the active layer. On a
     * vector layer shapes are objects of that layer. v1.8 (§3.1): a shape with Perspective array
     * copies ([perspective]) can't stay editable (its copies are not affine): it is painted into
     * the active layer too.
     */
    private fun placesInNewLayer(s: ShapeSettings = newShapeSettings(), perspective: Boolean = newPerspective): Boolean {
        if (isVectorTarget(targetLayer ?: controller.doc.activeLayer)) return false
        if (!s.editable || perspective) return false
        if (s.strokeWith != ShapeStroke.BRUSH || !s.strokes) return true
        val tool = controller.lastPaintTool
        val preset = controller.presetFor(tool) ?: return true
        val kind = StrokeKind.of(tool, preset)
        return kind != StrokeKind.SMUDGE && kind != StrokeKind.BLUR
    }

    /**
     * True when a new shape needs the active layer: when it is painted into it, it must be
     * editable. v1.7 (§3.8): the Shape tool works with a folder active (a new shape layer goes in
     * at `structure.insertionPoint()`), but with "Editable (own layer)" off it would paint into
     * the folder itself, which has no pixels: refused with "Choose a layer inside the folder to
     * paint". v1.8: [perspective], the new shape has Perspective array copies.
     */
    private fun checkCanPlaceNew(perspective: Boolean = newPerspective): Boolean {
        if (placesInNewLayer(perspective = perspective)) return true
        if (paintsIntoFolder(perspective = perspective)) { controller.toast(FolderLabels.PAINT_REFUSAL); return false }
        return controller.checkEditable()
    }

    /** A new shape painted into the active layer ([placesInNewLayer] false for [s]) would paint a folder. */
    private fun paintsIntoFolder(s: ShapeSettings = newShapeSettings(), perspective: Boolean = newPerspective): Boolean =
        (targetLayer ?: controller.doc.activeLayer).isFolder && !placesInNewLayer(s, perspective)

    /** New shapes go into a shape layer of their own (Compose state; see [placesInNewLayer]). */
    val newShapesEditable: Boolean get() = placesInNewLayer()

    /**
     * Sets the stroke width. With "Use brush size" the width IS the brush size, so the brush
     * (and the size slider) change with it.
     */
    fun setStrokeWidth(w: Float) {
        if (!w.isFinite()) return
        val v = w.coerceIn(ShapeSettings.MIN_STROKE, ShapeSettings.MAX_STROKE)
        val paintTool = controller.lastPaintTool
        val preset = controller.presetFor(paintTool)
        if (settings.useBrushSize && preset != null) {
            if (preset.size != v) controller.updatePreset(paintTool, preset.copy(size = v))
            refreshPreview()
        } else {
            update { it.copy(strokeWidth = v) }
        }
    }

    /** The painting tool that paints brush outlines: the edited shape's own, else the last one used. */
    private fun brushToolId(): ToolId = editBrush?.first ?: controller.lastPaintTool

    /** The brush that paints brush outlines (see [brushToolId]). */
    private fun brushPresetInUse(): BrushPreset? = editBrush?.second ?: controller.presetFor(controller.lastPaintTool)

    /** The brush of a re-opened brush-stroked shape (null while placing a new shape). */
    val editedShapeBrush: BrushPreset? get() = editBrush?.second

    /** A re-opened shape stops using its own brush and takes the current one. */
    fun useCurrentBrush() {
        if (editBrush == null) return
        editBrush = null
        refreshPreview()
    }

    /** Line <-> box conversion when the type changes while a shape is pending. */
    private fun convert(b: ShapeBox, type: ShapeType): ShapeBox = if (type.isLineLike) {
        ShapeBox.line(b.toDoc(Vec2(-b.w / 2f, -b.h / 2f)), b.toDoc(Vec2(b.w / 2f, b.h / 2f)))
    } else {
        val s = b.start; val e = b.end
        val len = b.w
        ShapeBox((s.x + e.x) / 2f, (s.y + e.y) / 2f, max(abs(e.x - s.x), len / 2f).coerceAtLeast(1f), max(abs(e.y - s.y), len / 2f).coerceAtLeast(1f), 0f)
    }

    /** Proportion to keep when "keep proportions" is on (natural shape aspect). */
    private fun naturalAspect(s: ShapeSettings = settings): Float = ShapeGeometry.naturalAspect(s.type, s.outlineParams)

    /** The shape is a line / arrow shown with its two end handles (no custom points). */
    private val lineHandles: Boolean get() = settings.type.isLineLike && points == null

    /** Custom outlines of lines and arrows are open, the others closed. */
    private val closedShape: Boolean get() = !settings.type.isLineLike

    // ------------------------------------------------------------------ the shape object

    /**
     * The pending shape [b] (with custom [pts]) as it would be placed now: with the options [s],
     * main color [color] and, for a brush outline, the opened shape's own brush [own] (else the
     * current one). v1.8: [copies], the re-opened shape's live copies (none for a new shape).
     */
    private fun objectFor(
        b: ShapeBox,
        pts: List<ShapePoint>?,
        s: ShapeSettings = settings,
        color: Int = controller.color,
        own: Pair<ToolId, BrushPreset>? = editBrush,
        copies: CopyMaps = openCopies,
    ): ShapeObject {
        val brush = s.strokeWith == ShapeStroke.BRUSH && s.strokes
        val tool = if (!brush) null else own?.first ?: controller.lastPaintTool
        val preset = if (!brush) null else own?.second ?: controller.presetFor(controller.lastPaintTool)
        val cog = s.type == ShapeType.COG
        return ShapeObject(
            type = s.type,
            cx = b.cx, cy = b.cy, w = b.w, h = b.h, rotation = b.rotationDeg,
            style = s.style,
            strokeWidth = strokeWidthOf(s),
            strokeWith = s.strokeWith,
            strokeColor = color,
            fillColor = s.fillColor ?: color,
            fillFollowsColor = s.fillColor == null,
            lineCap = s.lineCap,
            corner = s.corner,
            cornerRadius = s.cornerRadius,
            sides = s.sides,
            starPoints = s.starPoints,
            innerRatio = s.innerRatio,
            arrowHeads = s.arrowHeads,
            arrowHeadStyle = s.arrowHeadStyle,
            arrowHeadScale = s.arrowHeadScale,
            brushTool = tool?.name,
            brushPreset = preset,
            points = pts,
            // v1.8: only a cog carries the cog fields (any other shape writes exactly as in v1.7).
            cogTeeth = if (cog) s.cogTeeth else CogParams.DEFAULT.teeth,
            cogDepth = if (cog) s.cogDepth else CogParams.DEFAULT.depth,
            cogToothWidth = if (cog) s.cogToothWidth else CogParams.DEFAULT.toothWidth,
            cogTaper = if (cog) s.cogTaper else CogParams.DEFAULT.taper,
            cogHole = if (cog) s.cogHole else CogParams.DEFAULT.hole,
            copies = copies,
        )
    }

    /** The pending shape as it would be placed now (null when there is none). */
    fun pendingObject(): ShapeObject? = box?.let { objectFor(it, points) }

    /**
     * v1.8 (design §3.1): the open shape has symmetry copies (its stored ones, or a new shape's
     * from the ruler): the More row then offers [MirrorLabels18.REMOVE]. Compose state.
     */
    val hasCopies: Boolean get() = box != null && (!openCopies.isEmpty || openPerspective.isNotEmpty())

    /**
     * v1.8 (design §3.1) [MirrorLabels18.REMOVE]: the open shape drops its copies, as ONE in-tool
     * step (undo brings them back); the commit then stores none and paints the source alone.
     * False when there are none.
     */
    fun removeCopies(): Boolean {
        if (!hasCopies) return false
        pushHistory()
        openCopies = CopyMaps.NONE
        openPerspective = emptyList()
        refreshPreview()
        return true
    }

    /**
     * A NEW shape being dragged out in [b] (the user's own options and color, also while a shape
     * layer is open); v1.8: with the copies the ruler gave at its start point ([creatingMaps]).
     */
    private fun newObject(b: ShapeBox): ShapeObject =
        objectFor(b, null, newShapeSettings(), newShapeColor(), own = null, copies = ObjectCopies.fromSymmetry(creatingMaps) ?: CopyMaps.NONE)

    // ------------------------------------------------------------------ symmetry copies (v1.8, §3.1)

    /**
     * The pending shape is a NEW one: its copies come from the symmetry maps [raw] taken at its
     * start point (affine: [openCopies]; Perspective array: [openPerspective]; none: neither).
     */
    private fun useNewMaps(raw: List<FloatArray>) {
        val kept = ObjectCopies.fromSymmetry(raw)
        openCopies = kept ?: CopyMaps.NONE
        openPerspective = if (kept == null) raw else emptyList()
    }

    /** The maps the pending shape's copies are drawn with (the identity first); empty without copies. */
    private fun pendingMaps(): List<FloatArray> = openPerspective.ifEmpty { openCopies.maps }

    /** The maps the shape being dragged out is drawn with (see [pendingMaps]). */
    private fun creatingDrawMaps(): List<FloatArray> = ObjectCopies.fromSymmetry(creatingMaps)?.maps ?: creatingMaps

    /** The pending NEW shape has Perspective array copies (or, with none pending, the one being dragged out). */
    private val newPerspective: Boolean
        get() = if (box != null) openPerspective.isNotEmpty() else ObjectCopies.isPerspective(creatingMaps)

    /**
     * The plain items of [o] with its copies [maps]: their union into a raster layer ([union],
     * [ShapeCopies.unionSpec]), else one item per copy, as a shape layer or a vector object draws them.
     */
    private fun specsOf(o: ShapeObject, maps: List<FloatArray>, brush: Boolean, union: Boolean): List<VectorPaintSpec> =
        if (union) listOfNotNull(ShapeCopies.unionSpec(o, maps, brush)) else ShapeCopies.eachSpecs(o, maps, brush)

    /** Shows the hint [text] unless it was [shown] already this session; returns true (shown now). */
    private fun hintOnce(text: String, shown: Boolean): Boolean {
        if (!shown) controller.toast(text)
        return true
    }

    // ------------------------------------------------------------------ numeric editing

    /**
     * Makes sure a shape is pending (a default one centered on the canvas is created for numeric
     * entry). Returns false when the shape would be painted into an active layer that can't be
     * edited.
     */
    fun ensurePending(): Boolean {
        if (box != null) return true
        val d = controller.doc
        val cx = d.width / 2f; val cy = d.height / 2f
        // v1.8 (§3.1): its copies are taken at its start point, the centre.
        val maps = ObjectCopies.mapsAt(controller, cx, cy)
        if (!eraseOn && !checkCanPlaceNew(ObjectCopies.isPerspective(maps))) return false
        val size = max(1f, min(d.width, d.height) / 3f)
        targetLayer = d.activeLayer
        useNewMaps(maps)
        box = if (settings.type.isLineLike) {
            ShapeBox(cx, cy, size, 0f, 0f)
        } else {
            val a = if (settings.keepProportions) naturalAspect() else 1f
            if (a >= 1f) ShapeBox(cx, cy, size, size / a, 0f) else ShapeBox(cx, cy, size * a, size, 0f)
        }
        refreshPreview()
        return true
    }

    /** Replaces the pending shape's placement (numeric fields). Non-finite input is ignored. */
    fun place(b: ShapeBox) {
        val cur = box ?: return
        if (!(b.cx.isFinite() && b.cy.isFinite() && b.w.isFinite() && b.h.isFinite() && b.rotationDeg.isFinite())) return
        val next = clean(b)
        if (next == cur) return
        // v1.9 (R17): a typed or scrubbed placement is an in-tool step for every shape.
        pushHistory(NumericKey("place", 0))
        box = next
        refreshPreview()
    }

    /**
     * A slider drag of the placement (the X / Y strip) starts: its moves are one in-tool undo
     * step however long the finger rests, and a new step even right after another edit.
     */
    fun beginNumericEdit() {
        historyKey = null
        numericHeld = true
    }

    /** The drag, arrow run or typed value of [beginNumericEdit] is complete: the next one is a new step. */
    fun endNumericEdit() {
        historyKey = null
        numericHeld = false
    }

    private fun clean(b: ShapeBox): ShapeBox {
        val lim = ShapeSettings.MAX_LENGTH
        val custom = points != null
        val line = settings.type.isLineLike && !custom
        val minSize = if (custom || line) 0f else 1f
        return ShapeBox(
            b.cx.coerceIn(-lim, lim), b.cy.coerceIn(-lim, lim),
            b.w.coerceIn(minSize, lim),
            if (line) 0f else b.h.coerceIn(minSize, lim),
            ShapeGeometry.normalizeDegrees(b.rotationDeg),
        )
    }

    /**
     * Moves the selected point (points mode) or the whole pending shape by the nudge step in
     * direction ([dx], [dy]). A run of nudges is one undo step.
     */
    fun nudge(dx: Int, dy: Int) {
        val b = box ?: return
        val step = settings.nudgeStepPx
        val d = Vec2(dx * step, dy * step)
        val anchors = docAnchors()
        val selection = anchors?.let { pointSelection.resized(it.size) }
        if (pointsMode && anchors != null && selection != null && !selection.isEmpty) {
            // One point: v1.6's key; several: a run on the same selection.
            pushHistory(if (selection.isSingle) NumericKey("nudge", selection.primary) else GroupNudgeKey(selection))
            applyAnchors(anchors.mapIndexed { i, a -> if (i in selection) a.moved(a.pos + d) else a }, b.rotationDeg)
            refreshPreview()
            return
        }
        // v1.9 (R17): a run of nudges of the whole shape is an in-tool step for every shape.
        pushHistory(NumericKey("nudge", -1))
        box = clean(b.translated(d.x, d.y))
        refreshPreview()
    }

    // ------------------------------------------------------------------ points

    /** The pending shape's points in document px (null without custom points). */
    fun docAnchors(): List<ShapeAnchor>? {
        val b = box ?: return null
        val p = points ?: return null
        return ShapePoints.docAnchors(b, p)
    }

    /**
     * Point editing on / off for the pending shape. The first time, the shape is converted to its
     * own points (the outline does not change); leaving the mode keeps them.
     */
    fun setPointEditing(on: Boolean) {
        if (box == null || on == pointsMode) return
        if (on) {
            if (points == null) {
                // v1.8 (D21): a cog with a hole is two contours; points are one.
                ShapePoints.refusal(settings.type, settings.outlineParams)?.let {
                    controller.toast(it)
                    return
                }
                // v1.9 (R17): turning the outline into points changes nothing on screen, so after
                // other edits it is no step of its own: undo takes back the edit before it (and
                // leaves Points mode). At the bottom of the steps it is one, as in v1.8: undo
                // then gives the regular shape back instead of discarding or closing it.
                if (history.isEmpty()) {
                    pushHistory()
                } else {
                    clearRedo()
                    historyKey = null
                }
                points = ShapePoints.fromRegular(settings.type, settings.outlineParams)
            }
            pointsMode = true
        } else {
            pointsMode = false
            selectedPoint = -1
            selectSeveral = false
        }
        refreshPreview()
    }

    /** Goes back to the regular outline of the shape type (its box is kept). */
    fun resetShape() {
        val b = box ?: return
        if (points == null) return
        pushHistory()
        points = null
        pointsMode = false
        selectedPoint = -1
        selectSeveral = false
        // A regular shape needs a size (custom points may have been put on one line).
        box = clean(b)
        refreshPreview()
    }

    fun selectPoint(index: Int) {
        val n = points?.size ?: 0
        selectedPoint = if (index in 0 until n) index else -1
        controller.invalidateOverlay()
    }

    /** Fewest points the pending shape can have. */
    override val minPoints: Int get() = ShapePoints.minPoints(closedShape)

    /** Deletes point [index] (refused with a message below [minPoints]). */
    fun deletePoint(index: Int) {
        val b = box ?: return
        val anchors = docAnchors() ?: return
        if (index !in anchors.indices) return
        if (anchors.size <= minPoints) {
            controller.toast(minPointsMessage())
            return
        }
        pushHistory()
        applyAnchors(anchors.filterIndexed { i, _ -> i != index }, b.rotationDeg)
        selectedPoint = -1
        refreshPreview()
    }

    /** The refusal below [minPoints]: "A shape needs at least 3 points" / "A line needs at least 2 points". */
    private fun minPointsMessage(): String =
        if (closedShape) "A shape needs at least $minPoints points" else "A line needs at least $minPoints points"

    // ------------------------------------------------------------------ several points (v1.7 item 1, §3.1)

    /**
     * "Select several" (Compose state): a tap on a point adds or removes it, a drag on the canvas
     * box-selects, a tap elsewhere clears the selection and no point is ever inserted. It turns
     * itself off when the shape closes, another shape opens or points mode ends. Turning it on
     * shows its hint as the editor's message (v1.7's strip chip did; v1.8, F4: the X / Y pill's
     * cell turns it on now, and Shape has no hint line of its own).
     */
    override var selectSeveral: Boolean
        get() = severalState
        set(v) {
            if (v && !severalState) controller.toast(PointLabels.SEVERAL_HINT)
            severalState = v
        }

    private var severalState by mutableStateOf(false)

    /** v1.8 (F4): the X / Y pill's "Select several" cell shows in Points mode only (where the v1.7 strip chip was). */
    override val selectSeveralAvailable: Boolean get() = pointsMode && pointCount > 0

    /** The pending shape's number of points (0 without a pending shape with its own points). */
    override val pointCount: Int get() = if (box != null) points?.size ?: 0 else 0

    /** Replaces the selection ("Select all points", "Deselect point"...); not an in-tool step. */
    override fun selectPoints(s: PointSelection) {
        val n = pointCount
        val next = if (n == 0) PointSelection.none(0) else s.resized(n)
        if (next == pointSelection) return
        pointSelection = next
        controller.invalidateOverlay()
    }

    /** Document px of point [i] (NaN when there is no such point). */
    override fun pointAt(i: Int): Vec2 = docAnchors()?.getOrNull(i)?.pos ?: Vec2(Float.NaN, Float.NaN)

    /** The points (document px) when the group edit in progress began; null at rest. */
    private var groupBase: List<ShapeAnchor>? = null
    /** The points the group edit in progress maps. */
    private var groupSelection = PointSelection.none(0)
    /** The box rotation the group edit keeps (the box is fitted to the points at it). */
    private var groupRotation = 0f

    /**
     * Starts ONE group edit of the selected points (the in-tool step is saved now): everything
     * until [endGroupEdit] maps the points as they are now. [label] names the gesture for the
     * reader only: in-tool steps all read "last shape edit".
     */
    override fun beginGroupEdit(label: String) {
        if (groupBase != null) return
        if (box == null || pointSelection.isEmpty) return
        val anchors = docAnchors() ?: return
        historyKey = null
        pushHistory()
        startGroup(anchors)
    }

    /** Captures the points for a group edit whose in-tool step is already saved. */
    private fun startGroup(anchors: List<ShapeAnchor>) {
        groupBase = anchors
        groupSelection = pointSelection.resized(anchors.size)
        groupRotation = box?.rotationDeg ?: 0f
    }

    /** The captured selected points (and their tangent handles, as vectors) mapped by [m]. */
    override fun setGroupTransform(m: Affine2) {
        val base = groupBase ?: return
        if (box == null) return
        applyAnchors(mappedAnchors(base, groupSelection, m), groupRotation)
        refreshPreview()
    }

    override fun endGroupEdit() {
        groupBase = null
        historyKey = null
    }

    /** A group edit ([beginGroupEdit]) is in progress. */
    internal val inGroupEdit: Boolean get() = groupBase != null

    /**
     * Deletes the selected points as one in-tool step. Refused with "A shape needs at least N
     * points" when fewer than [minPoints] would remain (the pill offers "Delete shape" instead
     * while every point is selected).
     */
    override fun deleteSelectedPoints(): Boolean {
        val b = box ?: return false
        val anchors = docAnchors() ?: return false
        val sel = pointSelection.resized(anchors.size)
        if (sel.isEmpty) return false
        if (anchors.size - sel.count < minPoints) {
            controller.toast(minPointsMessage())
            return false
        }
        // The selection after the removal, computed before the points change (their setter resizes it).
        val next = sel.afterRemove(sel.indices)
        pushHistory()
        applyAnchors(anchors.filterIndexed { i, _ -> i !in sel }, b.rotationDeg)
        pointSelection = next
        refreshPreview()
        return true
    }

    /** [base] with the points of [sel] mapped by [m]: positions by the map, explicit tangent handles as vectors. */
    private fun mappedAnchors(base: List<ShapeAnchor>, sel: PointSelection, m: Affine2): List<ShapeAnchor> {
        if (m == Affine2.IDENTITY || sel.isEmpty) return base
        return base.mapIndexed { i, a ->
            if (i !in sel) a else a.copy(
                pos = m.map(a.pos),
                handleIn = a.handleIn?.let { m.mapVector(it) },
                handleOut = a.handleOut?.let { m.mapVector(it) },
            )
        }
    }

    /** The group gizmo of [anchors]' selected points (two or more selected in points mode), or null. */
    private fun groupLayout(anchors: List<ShapeAnchor>): PointGizmo.Layout? {
        val sel = pointSelection
        if (!pointsMode || sel.count < 2) return null
        val pts = sel.indices.mapNotNull { anchors.getOrNull(it)?.pos }
        return gizmo.layout(pts, controller.viewTransform)
    }

    /** The pill's "Keep scale proportions" (the gizmo's corners scale proportionally while it is on, the default). */
    private fun keepScaleProportions(): Boolean =
        runCatching { controller.settings.getObject(PILL_KEEP_PROPORTIONS_KEY, Boolean.serializer()) }.getOrNull() ?: true

    /** Makes point [index] smooth (automatic tangent) or a sharp corner. */
    fun setPointSmooth(index: Int, smooth: Boolean) {
        val b = box ?: return
        val anchors = docAnchors() ?: return
        val a = anchors.getOrNull(index) ?: return
        if (a.smooth == smooth && !a.hasExplicitHandles) return
        pushHistory()
        applyAnchors(ShapePoints.setSmooth(anchors, index, smooth), b.rotationDeg)
        refreshPreview()
    }

    /** Point [index] gets its automatic tangent back. */
    fun resetTangent(index: Int) {
        val b = box ?: return
        val anchors = docAnchors() ?: return
        val a = anchors.getOrNull(index) ?: return
        if (!a.hasExplicitHandles) return
        pushHistory()
        applyAnchors(ShapePoints.autoTangent(anchors, index), b.rotationDeg)
        refreshPreview()
    }

    // ------------------------------------------------------------------ properties of the selected points (v1.7 items 1, 2)

    /** The selected points of [anchors] (ascending). */
    private fun selectedOf(anchors: List<ShapeAnchor>): List<Int> = pointSelection.resized(anchors.size).indices

    /**
     * "Smooth" over the selected points (Compose state): `Same(true)`, `Same(false)` or a
     * `Spread` (the three-state chip's "Mixed"); null without a selected point.
     */
    val selectionSmooth: Mixed<Boolean>?
        get() {
            if (box == null) return null
            val pts = points ?: return null
            val idx = pointSelection.resized(pts.size).indices
            if (idx.isEmpty()) return null
            return Mixed.of(BooleanArray(idx.size) { pts[idx[it]].smooth })
        }

    /**
     * Makes every selected point smooth (automatic tangents) or a sharp corner, as ONE in-tool
     * step (with one point selected exactly [setPointSmooth]); nothing when nothing would change.
     */
    fun setSelectedSmooth(smooth: Boolean) {
        val b = box ?: return
        val anchors = docAnchors() ?: return
        val idx = selectedOf(anchors).filter { anchors[it].smooth != smooth || anchors[it].hasExplicitHandles }
        if (idx.isEmpty()) return
        pushHistory()
        var next = anchors
        for (i in idx) next = ShapePoints.setSmooth(next, i, smooth)
        applyAnchors(next, b.rotationDeg)
        refreshPreview()
    }

    /** Some selected smooth point has tangent handles of its own ("Auto tangent" is offered; Compose state). */
    val selectionHasExplicitHandles: Boolean
        get() {
            if (box == null) return false
            val pts = points ?: return false
            return pointSelection.resized(pts.size).indices.any { i -> pts[i].let { it.smooth && (it.handleIn != null || it.handleOut != null) } }
        }

    /** The selected smooth points with handles of their own get their automatic tangents back: ONE in-tool step. */
    fun resetSelectedTangents() {
        val b = box ?: return
        val anchors = docAnchors() ?: return
        val idx = selectedOf(anchors).filter { anchors[it].smooth && anchors[it].hasExplicitHandles }
        if (idx.isEmpty()) return
        pushHistory()
        var next = anchors
        for (i in idx) next = ShapePoints.autoTangent(next, i)
        applyAnchors(next, b.rotationDeg)
        refreshPreview()
    }

    /** The corner style the pending shape's own points are drawn with (as `ShapeOutlines` draws a custom outline). */
    private val customCorner: CornerStyle get() = if (settings.type.hasCorners) settings.corner else CornerStyle.SHARP

    /** The selected points that can be rounded: corners between two straight sides (Compose state; see [ShapeRoundness.targets]). */
    fun roundnessTargets(): IntArray {
        val anchors = docAnchors() ?: return IntArray(0)
        return ShapeRoundness.targets(anchors, closedShape, selectedOf(anchors).toIntArray())
    }

    /**
     * "Point roundness" of the selected corners that can be rounded, in document px (Compose
     * state): their own radius, else the shape's "Corner radius" while its corners are treated,
     * else 0. Null when no such corner is selected (the field is disabled with "Only corners
     * between straight sides can be rounded").
     */
    val pointRoundness: Mixed<Float>?
        get() {
            val anchors = docAnchors() ?: return null
            val t = ShapeRoundness.targets(anchors, closedShape, selectedOf(anchors).toIntArray())
            if (t.isEmpty()) return null
            return Mixed.of(ShapeRoundness.values(anchors, t, customCorner, settings.cornerRadius))
        }

    /** The points (document px) when the roundness scrub in progress began; null at rest. */
    private var roundBase: List<ShapeAnchor>? = null
    /** The corners the roundness scrub in progress changes. */
    private var roundTargets = IntArray(0)
    /** The roundness scrub in progress has saved its in-tool step (at its first actual change). */
    private var roundStepSaved = false

    /** Starts a scrub of "Point roundness": everything until [endPointRoundness] is ONE in-tool step. */
    fun beginPointRoundness() {
        if (roundBase != null) return
        val anchors = docAnchors() ?: return
        val t = ShapeRoundness.targets(anchors, closedShape, selectedOf(anchors).toIntArray())
        if (t.isEmpty()) return
        roundBase = anchors
        roundTargets = t
        roundStepSaved = false
    }

    /** The scrub at [now] from [start] (px): each corner's roundness when it began plus the difference, clamped to 0..500. */
    fun dragPointRoundness(start: Float, now: Float) {
        val base = roundBase ?: return
        val b = box ?: return
        if (!start.isFinite() || !now.isFinite()) return
        val next = ShapeRoundness.shifted(base, roundTargets, customCorner, settings.cornerRadius, now - start)
        if (!roundStepSaved) {
            if (next == base) return
            historyKey = null
            pushHistory()
            roundStepSaved = true
        }
        applyAnchors(next, b.rotationDeg)
        refreshPreview()
    }

    /** The roundness scrub is complete. */
    fun endPointRoundness() {
        roundBase = null
        roundStepSaved = false
        historyKey = null
    }

    /**
     * A typed "Point roundness" (document px): an absolute expression sets every selected corner
     * that can be rounded, a relative one (`*2`, `/2`) applies to each. ONE in-tool step; false
     * when the text is invalid or no such corner is selected.
     */
    fun typePointRoundness(text: String): Boolean {
        val anchors = docAnchors() ?: return false
        val t = ShapeRoundness.targets(anchors, closedShape, selectedOf(anchors).toIntArray())
        val next = ShapeRoundness.typed(anchors, t, customCorner, settings.cornerRadius, text) ?: return false
        applyPointEdit(anchors, next)
        return true
    }

    /** Every selected corner that can be rounded at roundness [r] (document px, clamped to 0..500): ONE in-tool step. */
    fun setPointRoundness(r: Float) {
        if (!r.isFinite()) return
        val anchors = docAnchors() ?: return
        val t = ShapeRoundness.targets(anchors, closedShape, selectedOf(anchors).toIntArray())
        if (t.isEmpty()) return
        applyPointEdit(anchors, ShapeRoundness.withValues(anchors, t, FloatArray(t.size) { r }))
    }

    /** Some selected point has a roundness of its own ("Reset point roundness" is enabled; Compose state). */
    val canResetPointRoundness: Boolean
        get() {
            if (box == null) return false
            val pts = points ?: return false
            return pointSelection.resized(pts.size).indices.any { pts[it].radius != null }
        }

    /** "Reset point roundness": the selected points follow the shape's own "Corner radius" again. ONE in-tool step. */
    fun resetPointRoundness() {
        val anchors = docAnchors() ?: return
        val idx = selectedOf(anchors).filter { anchors[it].radius != null }
        if (idx.isEmpty()) return
        applyPointEdit(anchors, ShapeRoundness.reset(anchors, idx.toIntArray()))
    }

    /** [next] replaces the points [anchors] as one in-tool step of its own (nothing when equal). */
    private fun applyPointEdit(anchors: List<ShapeAnchor>, next: List<ShapeAnchor>) {
        val b = box ?: return
        if (next == anchors) return
        historyKey = null
        pushHistory()
        applyAnchors(next, b.rotationDeg)
        refreshPreview()
    }

    // ------------------------------------------------------------------ turn into path (v1.7 item 6, §3.6)

    /**
     * Why "Turn into path" is refused for the pending shape, or null (Compose state): arrows can't
     * become paths ("Arrows can't become paths"), and a path lives in a vector layer, which can't
     * hold an outline painted by a tool that moves pixels (smudge, blur, watercolor: the message
     * a shape object of a vector layer gets, v1.5).
     */
    val turnIntoPathRefusal: String?
        get() {
            if (box == null) return null
            val s = settings
            if (s.type == ShapeType.ARROW) return PointLabels.ARROW_REFUSAL
            if (s.strokeWith == ShapeStroke.BRUSH && s.strokes && brushMovesPixels()) return movesPixelsMessage()
            return null
        }

    /** "Turn into path" is offered: a shape is pending and nothing refuses it ([turnIntoPathRefusal]; Compose state). */
    val canTurnIntoPath: Boolean get() = box != null && turnIntoPathRefusal == null

    /**
     * "Turn into path" (design §3.6). The pending shape edit lands first, as its own step: a NEW
     * shape is placed as an object of the vector layer it is drawn on, else in a shape layer of
     * its own (also with "Editable (own layer)" off: a path lives in a vector layer). Then the
     * WHOLE shape becomes one Path object ([ShapeToSpline.convert]) as ONE undo step "Turn into
     * path": a shape layer becomes a vector layer holding the path (re-rendered by the vector
     * renderer, a live array kept), a shape object of a vector layer is replaced in place (same
     * id and opacity, its brush seed kept). The Path tool then opens the path with the selected
     * corners (Points mode) selected; with none selected every corner stays sharp and nothing
     * moves. Arrows, and outlines of tools that move pixels, are refused before anything is placed
     * ([turnIntoPathRefusal]). True when the conversion was applied (or, for a large vector layer,
     * is rendering).
     */
    fun turnIntoPath(): Boolean {
        // v1.9 (R11): an outline erasing is no shape to convert.
        if (eraseOn) return false
        val b = box ?: return false
        turnIntoPathRefusal?.let {
            controller.toast(it)
            return false
        }
        val pts = points
        val selected = if (pointsMode && pts != null) pointSelection.resized(pts.size).indices.toIntArray() else IntArray(0)
        val doc = controller.doc
        val session = vectorSession
        val editLayer = editingLayer
        val vectorLayer = session?.layer ?: (targetLayer ?: doc.activeLayer).takeIf { editLayer == null && isVectorTarget(it) }
        val layersBefore = doc.layers.mapTo(HashSet()) { it.id }
        // v1.8 (§3.1): a new shape's Perspective array copies become plain paths beside its path.
        val bake = if (session == null && editLayer == null && vectorLayer == null) openPerspective else emptyList()
        // 1. The pending edit, as its own step (nothing is recorded for an untouched reopened shape).
        if (vectorLayer != null) lastVectorId = null
        if (session != null || editLayer != null || vectorLayer != null) commit() else commitNewLayer(b)
        // Refused (locked layer, outside the canvas...): the shape stays pending, with the message.
        if (box != null) return false
        controller.vectors.flushPending()
        // 2. The conversion, ONE step.
        if (vectorLayer != null) {
            val id = lastVectorId ?: return false
            return objectToPath(vectorLayer, id, selected)
        }
        val layer = editLayer ?: doc.layers.firstOrNull { it.id !in layersBefore && it.isShapeLayer } ?: return false
        return layerToPath(layer, selected, bake)
    }

    /**
     * Shape layer [layer] becomes a vector layer with its shape as one path (see [turnIntoPath]).
     * v1.8: [bake], a new shape's Perspective array maps: each copy is added as a plain path too.
     */
    private fun layerToPath(layer: Layer, selected: IntArray, bake: List<FloatArray> = emptyList()): Boolean {
        val doc = controller.doc
        if (doc.indexOf(layer) < 0) return false
        val o = decoded(layer) ?: return false
        val result = converted(o, selected) ?: run {
            controller.toast(CANT_CONVERT_MESSAGE)
            return false
        }
        val before = layer.dataSnapshot()
        val baked = if (bake.isEmpty()) listOf(result.path) else ObjectCopies.baked(result.path, bake, ObjectCopies.bakeCanvas(doc, layer))
        val (content, ids) = VectorContent.EMPTY.plus(baked)
        val after = before.copy(text = null, shape = null, vector = content)
        // A live array keeps repeating the source: the cache shows the expanded content.
        val view = ArrayDraw.effectiveVector(after) ?: content
        // Where the shape's pixels are and where the path paints (the array's copies are added by updateLayerData).
        val dirty = Rect()
        inkOf(layer, o)?.let { dirty.union(it) }
        paintRect(o)?.let { dirty.union(it) }
        for (p in baked) VectorOps.bounds(p).let { r ->
            if (!r.isEmpty) dirty.union(Rect(floor(r.left).toInt(), floor(r.top).toInt(), ceil(r.right).toInt(), ceil(r.bottom).toInt()))
        }
        dirty.inset(-2, -2)
        val whole = Rect(0, 0, doc.width, doc.height)
        val ok = controller.updateLayerData(layer, after, HistoryLabels.TURN_INTO_PATH, dirty) { c ->
            VectorLayerRenderer.render(c, view, whole, tips = TipCache(), document = whole)
        }
        if (!ok || layer.vector !== content) return false
        openInPathTool(layer, ids.first(), result.selectedSplineIndices)
        compoundHint(o)
        if (bake.isNotEmpty()) bakedHintShown = hintOnce(MirrorLabels18.PERSPECTIVE_BAKED, bakedHintShown)
        return true
    }

    /** Shape object [id] of vector layer [layer] is replaced by its path in place (see [turnIntoPath]). */
    private fun objectToPath(layer: Layer, id: Long, selected: IntArray): Boolean {
        if (controller.doc.indexOf(layer) < 0) return false
        val content = layer.vector ?: return false
        val vs = content.byId(id) as? VShape ?: return false
        val result = converted(vs.shape, selected) ?: run {
            controller.toast(CANT_CONVERT_MESSAGE)
            return false
        }
        // A brush line keeps the shape's grain (a plain one has none: the Path tool rebuilds it
        // with seed 0, and the reopened path must be unchanged for undo to pass it by).
        val stroke = result.path.stroke?.let { if (it.kind == VStrokeKind.BRUSH) it.copy(seed = vs.seed) else it }
        val path = result.path.copy(id = vs.id, opacity = vs.opacity, stroke = stroke)
        var refused = false
        controller.vectors.update(layer, content.replaced(mapOf(id to listOf(path))), HistoryLabels.TURN_INTO_PATH) { ok ->
            if (ok) {
                openInPathTool(layer, id, result.selectedSplineIndices)
                compoundHint(vs.shape)
            } else {
                refused = true
            }
        }
        return !refused
    }

    /**
     * [o] as a path ([ShapeToSpline.convert], which reads the source shape only); v1.8 (I15): its
     * live symmetry copies stay the path's.
     */
    private fun converted(o: ShapeObject, selected: IntArray): ConvertResult? =
        ShapeToSpline.convert(ObjectCopies.source(o), selected)?.let { ConvertResult(it.path.copy(copies = o.copies), it.selectedSplineIndices) }

    /** v1.8 (D21): a holed cog became a compound path, which the Path tool can't open as points: says why. */
    private fun compoundHint(o: ShapeObject) {
        if (ShapeToSpline.isCompound(o)) controller.toast(CogLabels18.COMPOUND_HINT)
    }

    /** The Path tool opens path [id] of [layer] with the control points [select] selected (area B's `openPath`). */
    private fun openInPathTool(layer: Layer, id: Long, select: IntArray) {
        val path = controller.tools[ToolId.PATH] as? CurveTool ?: return
        path.openPath(layer.id, id, select)
    }

    // ------------------------------------------------------------------ the pill (v1.7 items 1, 9, 13; §4.6)

    /**
     * v1.7 (items 1 and 13, §3.1): the X / Y pill's ONE source for this tool's lifetime
     * ([ShapePillPosition]): the single selected point ("Point 3"), the selected points' box
     * centre ("Selected points"), else the shape's centre ("Center"); null while no shape is
     * pending.
     */
    override val pillPosition: ObjectPosition = ShapePillPosition(this)

    /** The unit of the shape's own fields. */
    override val pillUnit: LengthUnit get() = settings.unit

    private val scale = ShapeObjectScale(this)

    /** v1.7 (item 9): the pill's Scale row while a shape is pending ([ShapeObjectScale]). */
    override val objectScale: ObjectScale? get() = if (box != null) scale else null

    private val deletion = ShapeObjectDeletion(this)

    /** v1.7 (item 13): the pill's trash cell while a shape is pending ([ShapeObjectDeletion]). */
    override val objectDeletion: ObjectDeletion? get() = if (box != null) deletion else null

    // region v110-A2 colors
    /** v1.10 (R2): the pill's Object colors cell. F0: none, so the cell stays hidden (A2 body). */
    override val objectColors: ObjectColorEditor? = null
    // endregion

    /**
     * The points the pill acts on as a group, with their selection: in Points mode with at least
     * one point selected; null otherwise (the pill acts on the whole shape).
     */
    internal fun pillPoints(): Pair<List<ShapeAnchor>, PointSelection>? {
        if (!pointsMode || box == null) return null
        val anchors = docAnchors() ?: return null
        val sel = pointSelection.resized(anchors.size)
        return if (sel.isEmpty) null else anchors to sel
    }

    // region v110-A5 corners
    /** v1.10 (R12): the pill's Sharp / Smooth cell. F0: none, so the cell stays hidden (A5 body). */
    override val pointCorners: PointCornerEditor? = null
    // endregion

    /**
     * "Delete shape" (the pill's trash cell, design §3.13): ONE controller step "Delete shape"
     * that ends the session. A shape layer being edited is deleted (`LayerStructure.delete`; the
     * last pixel layer is refused with its message and stays open), a shape object is removed
     * from its vector layer, and a shape never placed is cleared (nothing to undo). Pending edits
     * of a placed shape go with it: one undo gives it back as it was before it was opened.
     */
    fun deleteShape() {
        if (box == null) return
        val doc = controller.doc
        val editLayer = editingLayer
        val session = vectorSession
        when {
            editLayer != null -> {
                if (doc.indexOf(editLayer) >= 0 && doc.pixelLayerCount <= 1) {
                    controller.toast(LayerStructure.LAST_LAYER)
                    return
                }
                discard()
                if (doc.indexOf(editLayer) >= 0) controller.structure.delete(editLayer, keepChildren = false, label = HistoryLabels.DELETE_SHAPE)
            }
            session != null -> {
                val layer = session.layer
                val id = vectorEditId
                discard()
                val content = layer.vector ?: return
                if (doc.indexOf(layer) < 0 || content.byId(id) == null) return
                controller.vectors.update(layer, content.without(setOf(id)), HistoryLabels.DELETE_SHAPE)
            }
            else -> discard()
        }
    }

    /** Moves point [index] to document point [p] (numeric entry; edits of one point share one undo step). */
    fun movePoint(index: Int, p: Vec2) {
        val b = box ?: return
        val anchors = docAnchors() ?: return
        val a = anchors.getOrNull(index) ?: return
        if (!p.x.isFinite() || !p.y.isFinite()) return
        val lim = ShapeSettings.MAX_LENGTH
        val q = Vec2(p.x.coerceIn(-lim, lim), p.y.coerceIn(-lim, lim))
        if (q == a.pos) return
        pushHistory(NumericKey("move", index))
        applyAnchors(anchors.mapIndexed { i, x -> if (i == index) x.moved(q) else x }, b.rotationDeg)
        refreshPreview()
    }

    /** Stores [anchors] (document px) as the custom outline, fitting the box to it. */
    private fun applyAnchors(anchors: List<ShapeAnchor>, rotationDeg: Float) {
        val (nb, np) = ShapePoints.fit(rotationDeg, anchors, closedShape)
        box = nb
        points = np
    }

    // ------------------------------------------------------------------ bevel (v1.9, R18)

    /**
     * The bevel the popup adjusts: the points and the selection before it ([base], document px),
     * and what its latest apply made. Session state (I22): the popup's changes re-apply from
     * [base] in place, the bevel is ONE in-tool step, and the commit is still one document step.
     */
    private class BevelSession(
        val base: List<ShapeAnchor>,
        val baseSelection: PointSelection,
        val rotationDeg: Float,
        var amount: Float,
        var segments: Int,
    ) {
        var points: List<ShapePoint>? = null
        var box: ShapeBox? = null
        var made: PointSelection = PointSelection.none(0)
    }

    private var bevelSession: BevelSession? = null

    private var bevelPopupState by mutableStateOf(false)

    /** The popup's Amount (0.01..0.5) and Segments (1..8): the remembered ones, or the open bevel's (Compose state). */
    var bevelAmount by mutableFloatStateOf(PointBevel.clampAmount(controller.settings.shapeBevelAmount))
        private set
    var bevelSegments by mutableIntStateOf(PointBevel.clampSegments(controller.settings.shapeBevelSegments))
        private set

    /**
     * The bevel popup shows (Compose state): from [bevelPoints] until "Done", a tap on the canvas
     * (consumed: it adds no point), another edit, undo, or the shape closing.
     */
    val bevelPopupOpen: Boolean
        get() = bevelPopupState && box != null && pointsMode && bevelSession?.let { isCurrent(it) } == true

    /** A one-finger gesture that started while the popup showed: it only closes the popup. */
    private var bevelTap = false

    /** [PointLabels19.BEVEL] shows: in Points mode (Compose state). */
    val bevelShown: Boolean get() = box != null && pointsMode && points != null

    /** [PointLabels19.BEVEL] applies: in Points mode with points selected (Compose state). */
    val canBevel: Boolean get() = bevelShown && !pointSelection.resized(pointCount).isEmpty

    /** The open bevel is what the shape shows, and its points are still the selected ones. */
    private fun isCurrent(s: BevelSession): Boolean =
        s.points != null && points == s.points && box == s.box && pointSelection == s.made

    /**
     * R18 [PointLabels19.BEVEL]: bevels the selected sharp corners at once with the remembered
     * Amount and Segments, as ONE in-tool step, selects the new points and opens the popup on
     * that step. A second tap while the new points stay selected reopens the popup instead of
     * bevelling again. Points the bevel does not touch keep their smoothness, handles and
     * roundness (through `origin`); new points are sharp, without handles and with a roundness of
     * 0, so the shape's corner radius does not round them. Says why when nothing changes
     * ([PointLabels19.BEVEL_SHARP_ONLY], [PointLabels19.BEVEL_TOO_MANY]).
     */
    fun bevelPoints(): Boolean {
        val b = box ?: return false
        if (!pointsMode) return false
        val anchors = docAnchors() ?: return false
        val sel = pointSelection.resized(anchors.size)
        if (sel.isEmpty) return false
        bevelSession?.let { s ->
            if (isCurrent(s)) {
                bevelPopupState = true
                return true
            }
        }
        val amount = PointBevel.clampAmount(controller.settings.shapeBevelAmount)
        val segments = PointBevel.clampSegments(controller.settings.shapeBevelSegments)
        val r = bevelOf(anchors, sel, amount, segments)
        if (r !is PointBevel.Result.Ok) {
            bevelRefused(r)
            return false
        }
        historyKey = null
        pushHistory()
        val s = BevelSession(anchors, sel, b.rotationDeg, amount, segments)
        bevelSession = s
        applyBevel(s, r)
        bevelPopupState = true
        return true
    }

    /** The popup's Amount: the open bevel again from its points before it (no new step). */
    fun changeBevelAmount(amount: Float) {
        val s = bevelSession?.takeIf { isCurrent(it) } ?: return
        rebevel(s, PointBevel.clampAmount(amount), s.segments)
    }

    /** The popup's Segments: the open bevel again from its points before it (no new step). */
    fun changeBevelSegments(segments: Int) {
        val s = bevelSession?.takeIf { isCurrent(it) } ?: return
        rebevel(s, s.amount, PointBevel.clampSegments(segments))
    }

    /** "Done" (or a tap on the canvas): the popup closes; the bevel stays as it is. */
    fun closeBevelPopup() {
        bevelPopupState = false
    }

    private fun rebevel(s: BevelSession, amount: Float, segments: Int) {
        if (amount == s.amount && segments == s.segments) return
        val r = bevelOf(s.base, s.baseSelection, amount, segments)
        if (r !is PointBevel.Result.Ok) {
            // Too many points: the bevel keeps its last values.
            bevelRefused(r)
            return
        }
        s.amount = amount
        s.segments = segments
        applyBevel(s, r)
        // Remembered for the next bevel.
        controller.settings.shapeBevelAmount = amount
        controller.settings.shapeBevelSegments = segments
    }

    private fun bevelOf(anchors: List<ShapeAnchor>, sel: PointSelection, amount: Float, segments: Int): PointBevel.Result {
        val closed = closedShape
        val sharp = PointBevel.shapeSharp(anchors, closed, settings.corner, settings.cornerRadius)
        return PointBevel.bevel(
            anchors.map { BevelPoint(it.pos.x, it.pos.y) }, closed, sharp, sel.indices.toSet(),
            amount, segments, PointBevel.MAX_POINTS_SHAPE,
        )
    }

    private fun bevelRefused(r: PointBevel.Result) {
        when (r) {
            is PointBevel.Result.TooMany -> controller.toast(String.format(Locale.ROOT, PointLabels19.BEVEL_TOO_MANY, r.max))
            PointBevel.Result.NoSharpCorner -> controller.toast(PointLabels19.BEVEL_SHARP_ONLY)
            is PointBevel.Result.Ok -> {}
        }
    }

    /** Puts [r] on the shape: untouched points as they were, new ones sharp with a roundness of 0, selected. */
    private fun applyBevel(s: BevelSession, r: PointBevel.Result.Ok) {
        val anchors = r.points.mapIndexed { i, p ->
            val o = r.origin[i]
            if (o >= 0) s.base[o] else ShapeAnchor(Vec2(p.x, p.y), smooth = false, handleIn = null, handleOut = null, radius = 0f)
        }
        applyAnchors(anchors, s.rotationDeg)
        pointSelection = PointSelection.none(anchors.size).plusAll(r.selected)
        s.points = points
        s.box = box
        s.made = pointSelection
        bevelAmount = s.amount
        bevelSegments = s.segments
        refreshPreview()
    }

    /** Another edit, an undo or the shape closing ends the bevel session (its popup closes). */
    private fun endBevel() {
        bevelSession = null
        bevelPopupState = false
    }

    // ------------------------------------------------------------------ handle scaling (v1.6 §3.3, Points)

    /** Which handles the Handles group scales (Compose state). */
    var handleSide by mutableStateOf(ShapeHandleSide.BOTH)

    /** The Handles group acts on every point even while one is selected (Compose state). */
    var handlesAllPoints by mutableStateOf(false)

    /**
     * The factor of the handle change in progress, relative to the handles when it began (1 at
     * rest: the group's value then reads 100 %; Compose state).
     */
    var handleScale by mutableFloatStateOf(1f)
        private set

    /** The points (document px) when the handle change in progress began; null at rest. */
    private var handleBase: List<ShapeAnchor>? = null

    /** The points the change in progress scales. */
    private var handleIndices = IntArray(0)

    /** The change in progress has saved its undo step (it does so at its first actual change). */
    private var handleStepSaved = false

    /** True while a handle change (a slider drag, a held arrow) is in progress. */
    val handleScaling: Boolean get() = handleBase != null

    /** The points the Handles group acts on now: the selected ones, or all of them without a selection or with [handlesAllPoints]. */
    private fun handleTargets(count: Int): IntArray {
        val sel = pointSelection.resized(count)
        return if (handlesAllPoints || sel.isEmpty) IntArray(count) { it } else sel.indices.toIntArray()
    }

    /**
     * The Handles group has something to scale: one of the points it acts on (see [handleTargets])
     * has a tangent handle (a smooth point, or explicit handles). Reads Compose state.
     */
    val canScaleHandles: Boolean
        get() {
            val anchors = docAnchors() ?: return false
            return handleTargets(anchors.size).any { ShapePoints.hasHandles(anchors, it, closedShape) }
        }

    /**
     * Starts a change of the tangent handles (the Handles group in points mode): the selected
     * point's, or every point's without a selection or with [handlesAllPoints]. Everything until
     * [endHandleScale] is ONE in-tool undo step (saved at the first actual change, so a change
     * that changes nothing leaves no step), its factor relative to the handles as they are now.
     * False without a shape with its own points, or when none of those points has a handle.
     */
    fun beginHandleScale(): Boolean {
        if (handleBase != null) return true
        val anchors = docAnchors() ?: return false
        if (anchors.isEmpty()) return false
        val targets = handleTargets(anchors.size)
        if (targets.none { ShapePoints.hasHandles(anchors, it, closedShape) }) return false
        handleBase = anchors
        handleIndices = targets
        handleStepSaved = false
        handleScale = 1f
        return true
    }

    /** The handles of the change in progress at [k] × their length when it began (see [ShapePoints.scaledHandles]). */
    fun scaleHandlesTo(k: Float) {
        val base = handleBase ?: return
        val b = box ?: return
        if (!k.isFinite()) return
        val kk = k.coerceIn(ShapePoints.MIN_HANDLE_SCALE, ShapePoints.MAX_HANDLE_SCALE)
        if (kk == handleScale) return
        if (!handleStepSaved) {
            // A step of its own (not merged into an edit just before), saved before the first change.
            historyKey = null
            pushHistory()
            handleStepSaved = true
        }
        handleScale = kk
        applyAnchors(ShapePoints.scaledHandles(base, handleIndices, kk, handleSide, closedShape), b.rotationDeg)
        refreshPreview()
    }

    /** The handle change is complete: the next one starts from the handles as they are (100 % again). */
    fun endHandleScale() {
        handleBase = null
        handleStepSaved = false
        handleScale = 1f
        historyKey = null
    }

    /** A typed handle scale ([k] = 1.5 for 150 %), exactly as typed: one in-tool undo step. */
    fun scaleHandles(k: Float) {
        if (!k.isFinite() || !beginHandleScale()) return
        scaleHandlesTo(k)
        endHandleScale()
    }

    /**
     * One press (or repeat, while held) of ‹ / ›: shorter or [longer] handles, × 0.9 / × 1.1, or to
     * the next multiple of the Scale increment (100 → 110 → 120 % of the change's start) while
     * increments are on. The release ends the change ([endHandleScale]).
     */
    fun stepHandles(longer: Boolean) {
        if (!beginHandleScale()) return
        val k = handleScale
        val step = controller.increments.step(IncrementKind.SCALE)
        val next = if (step == null) {
            if (longer) k * 1.1f else k * 0.9f
        } else {
            val m = k * 100f / step
            val n = if (longer) floor(m + 1e-4f) + 1f else ceil(m - 1e-4f) - 1f
            max(n, 1f) * step / 100f
        }
        scaleHandlesTo(next)
    }

    // ------------------------------------------------------------------ in-tool history

    /**
     * Saves the pending shape for [undoStep] (and drops the redo steps: this is a new edit).
     * Consecutive edits with the same non-null [key] that follow each other quickly (typing a
     * coordinate, holding a nudge arrow) share one step.
     */
    private fun pushHistory(key: Any? = null) {
        val b = box ?: return
        // v1.9 (R18): another edit ends the open bevel (its popup closes).
        endBevel()
        val now = if (key != null) clock() else 0L
        val coalesce = key != null && key == historyKey && history.isNotEmpty() && (numericHeld || now - historyKeyTime <= COALESCE_MS)
        historyKey = key
        historyKeyTime = now
        clearRedo()
        if (coalesce) return
        history.addLast(pendingState(b))
        while (history.size > MAX_HISTORY) history.removeFirst()
        canUndoStep = true
    }

    private fun clearRedo() {
        if (redo.isEmpty()) return
        redo.clear()
        redoCount = 0
    }

    private fun clearHistory() {
        endBevel()
        history.clear()
        redo.clear()
        redoCount = 0
        canUndoStep = false
        historyKey = null
        handleBase = null
        handleStepSaved = false
        handleScale = 1f
        roundBase = null
        roundStepSaved = false
    }

    /**
     * Takes back one edit of the pending shape (moved, resized, turned, nudged, pinched, a typed
     * value, a point moved, inserted, deleted, made smooth...; v1.9, R17: of every shape, not only
     * one with its own points). Also used by the app's undo (button / two-finger tap); without such
     * edits undo acts as in v1.8 (it discards the pending shape).
     */
    override fun undoStep(): Boolean {
        val b = box ?: return false
        val prev = history.removeLastOrNull() ?: return false
        redo.addLast(pendingState(b))
        redoCount = redo.size
        historyKey = null
        restoreState(prev)
        canUndoStep = history.isNotEmpty()
        return true
    }

    /** Brings back the edit last taken back by [undoStep]. */
    override fun redoStep(): Boolean {
        val b = box ?: return false
        val next = redo.removeLastOrNull() ?: return false
        redoCount = redo.size
        history.addLast(pendingState(b))
        while (history.size > MAX_HISTORY) history.removeFirst()
        canUndoStep = true
        historyKey = null
        restoreState(next)
        return true
    }

    private fun restoreState(s: PendingState) {
        endBevel()
        handleBase = null
        handleStepSaved = false
        handleScale = 1f
        roundBase = null
        roundStepSaved = false
        box = s.box
        points = s.points
        pointsMode = s.pointsMode && s.points != null
        pointSelection = s.selection.resized(s.points?.size ?: 0)
        openCopies = s.copies
        openPerspective = s.perspective
        refreshPreview()
    }

    /**
     * The in-tool history and the pending shape at [historyMark] time: equal marks mean nothing
     * changed in between (the states compare by value, so a coalesced edit, which keeps no step
     * of its own, still counts as a change).
     */
    private data class HistoryMark(
        val settings: ShapeSettings,
        val userSettings: ShapeSettings?,
        val depth: Int,
        val top: PendingState?,
        val state: PendingState?,
        val redo: List<PendingState>,
    )

    /**
     * v1.7 (item 10, §3.10): a mark of the options, the pending shape and its in-tool steps, for
     * a history tap over the UI to take back what its first finger changed.
     */
    override fun historyMark(): Any = HistoryMark(
        settings, userSettings, history.size, history.lastOrNull(),
        box?.let { pendingState(it) }, redo.toList(),
    )

    /**
     * v1.7 (item 10): back to [mark] ([historyMark]). The options shown come back (the
     * controller rolls the saved preferences back itself); with the same shape still pending, the
     * in-tool steps pushed since are dropped (not moved to redo) and the redo steps and the shape
     * (box, points, mode, selection) are those of the mark; a NEW shape that was not pending then
     * (a Numbers field made one) is discarded. Nothing happens for another tool's mark.
     */
    override fun rollbackHistory(mark: Any?) {
        val m = mark as? HistoryMark ?: return
        // The options: only while the same kind of shape is open (an opened shape shows its own).
        if ((m.userSettings == null) == (userSettings == null) && (settings != m.settings || userSettings != m.userSettings)) {
            settings = m.settings
            userSettings = m.userSettings
        }
        val st = m.state
        if (st == null) {
            if (box != null && !reopened) discard() else refreshPreview()
            return
        }
        if (box == null) return
        while (history.size > m.depth) history.removeLast()
        redo.clear()
        redo.addAll(m.redo)
        redoCount = redo.size
        historyKey = null
        numericHeld = false
        canUndoStep = history.isNotEmpty()
        restoreState(st)
    }

    // ------------------------------------------------------------------ input

    override fun onDown(p: ToolPoint) {
        val pt = Vec2(p.x, p.y)
        // v1.9 (R18): a finger on the canvas while the bevel popup shows only closes it (on
        // release): it neither adds, selects nor moves anything. Two fingers still pan and zoom.
        bevelTap = bevelPopupOpen
        if (bevelTap) {
            mode = Mode.NONE
            return
        }
        downPoint = pt
        started = false
        longPressed = false
        gesturePushed = false
        resizeGuides = emptyList()
        val b = box
        gestureState = b?.let { pendingState(it) }
        gestureHistory = history.size
        gestureRedo = redo.toList()
        if (b != null) {
            val hit = if (pointsMode && points != null) hitPoints(b, pt) else hitTest(b, pt)
            if (hit != null) {
                mode = hit
                startBox = box
                // Handles are dragged by the finger's motion (they don't jump under the finger).
                when (hit) {
                    Mode.RESIZE -> handle?.let { grabStart = handlePoint(b, it) }
                    Mode.LINE_START -> grabStart = b.start
                    Mode.LINE_END -> grabStart = b.end
                    else -> {}
                }
                beginSnap()
                return
            }
        }
        // A drag draws a new shape (its first corner may snap once the finger really moves: it
        // is a new point); a tap commits the pending one and opens the shape under the finger
        // (see onUp). The pending shape is not a layer yet: its points are targets too, so
        // shapes drawn one after the other line up.
        mode = Mode.CREATE
        anchor = pt
        val pending = b?.let { pb -> ShapeOutlines.featurePoints(objectFor(pb, points)).flatMap { SnapLine.point(it, settings.type.label) } } ?: emptyList()
        snap.begin(exclude = snapExclude(), extra = { pending })
    }

    /**
     * Layers the edited shape doesn't snap to: an opened shape layer (it holds only the shape), a
     * vector layer whose only object is the opened shape object (its content bounds are the shape's).
     */
    private fun snapExclude(): List<Layer> {
        editingLayer?.let { return listOf(it) }
        val s = vectorSession ?: return emptyList()
        return if ((s.layer.vector?.objects?.size ?: 0) <= 1) listOf(s.layer) else emptyList()
    }

    // region v110-A5 snap
    /** Starts snapping for a gesture on the pending shape (see the class comment). */
    private fun beginSnap() {
        val exclude = snapExclude()
        // v1.10 (A5, R8): a new gesture measures its own moving group ([groupSnapMove]).
        groupSnapBase = null
        when (mode) {
            Mode.POINT, Mode.NEW_POINT, Mode.HANDLE_IN, Mode.HANDLE_OUT -> {
                // The shape's other points: points line up with each other.
                val keepOwn = mode == Mode.HANDLE_IN || mode == Mode.HANDLE_OUT
                val others = startAnchors.filterIndexed { i, _ -> keepOwn || i != dragIndex }.flatMap { SnapLine.point(it.pos, "Vertex") }
                snap.begin(exclude = exclude, extra = { others })
            }
            Mode.GROUP_POINT -> {
                // The points that stay: the group (and the point joining it) moves along.
                val sel = pointSelection
                val others = startAnchors.filterIndexed { i, _ -> i !in sel && i != dragIndex }.flatMap { SnapLine.point(it.pos, "Vertex") }
                snap.begin(exclude = exclude, extra = { others })
            }
            Mode.GIZMO -> {
                // v1.10 (A5, R8 cause A): the gizmo moves the selected points; the others are targets.
                val sel = pointSelection
                val others = startAnchors.filterIndexed { i, _ -> i !in sel }.flatMap { SnapLine.point(it.pos, "Vertex") }
                snap.begin(exclude = exclude, extra = { others })
            }
            Mode.MOVE -> {
                startBounds = box?.let { outlineBounds(objectFor(it, points)) }
                snap.begin(exclude = exclude)
            }
            else -> snap.begin(exclude = exclude)
        }
    }
    // endregion

    override fun onMove(p: ToolPoint) {
        val pt = Vec2(p.x, p.y)
        val s = settings
        if (mode == Mode.NONE) return
        // Nothing changes until the finger really moves, so a tap never nudges, snaps or
        // re-quantizes the pending shape.
        if (!started) {
            if (pt.distanceTo(downPoint) < controller.docLength(TOUCH_SLOP_DP)) return
            if (mode == Mode.CREATE) {
                // v1.9 (R11): erase mode erases with one outline at a time: the pending one's
                // erase lands first (refused: it stays, and no new shape starts).
                if (eraseOn && box != null) {
                    commit()
                    if (box != null) { mode = Mode.NONE; snap.end(); return }
                }
                // v1.8 (§3.1): the ruler's maps are taken once, at the new shape's start point.
                creatingMaps = ObjectCopies.mapsAt(controller, downPoint.x, downPoint.y)
                // (An erase places no shape: its layer's check is the erase's own, ToolErase.begin.)
                if (!eraseOn && !checkCanPlaceNew(ObjectCopies.isPerspective(creatingMaps))) { creatingMaps = emptyList(); mode = Mode.NONE; snap.end(); return }
                if (box == null) targetLayer = controller.doc.activeLayer
                // The first corner is a new point: it may snap (from where the finger landed).
                anchor = snap.snapPoint(downPoint)
                if (anchor != downPoint) creatingMaps = ObjectCopies.mapsAt(controller, anchor.x, anchor.y)
            }
            started = true
            if (mode == Mode.MARQUEE) {
                // Only the selection changes (on release): nothing to save, the shape stays as drawn.
                marqueeEnd = pt
                controller.invalidateOverlay()
                return
            }
            // A drag on an unselected point with "Select several" on adds it to the group it moves.
            if (mode == Mode.GROUP_POINT && dragIndex !in pointSelection) {
                gesturePushed = true
                historyKey = null
                pushHistory()
                pointSelection = pointSelection.plus(dragIndex)
                startGroup(docAnchors() ?: startAnchors)
            }
            // A group gesture is one in-tool step that restores the points and the selection.
            if ((mode == Mode.GROUP_POINT || mode == Mode.GIZMO) && !gesturePushed) {
                beginGroupEdit(if (mode == Mode.GIZMO) "Gizmo" else "Move points")
                gesturePushed = true
            }
            // v1.9 (R17): every finished move, resize, rotation, line-end or point drag of the
            // pending shape is one in-tool step, with or without its own points.
            if (mode != Mode.CREATE && !gesturePushed) { pushHistory(); gesturePushed = true }
            // The point being dragged is the selected one (its actions and numbers follow it).
            if (mode == Mode.POINT && dragIndex >= 0) selectedPoint = dragIndex
            // The preview follows the finger as cheaply as possible until it lifts.
            setDragging(true)
        }
        stepReadout = null
        when (mode) {
            Mode.NONE -> return
            Mode.MARQUEE -> {
                marqueeEnd = pt
                controller.invalidateOverlay()
                return
            }
            Mode.GROUP_POINT -> dragGroup(pt)
            Mode.GIZMO -> dragGizmo(pt)
            Mode.CREATE -> creatingBox = creationBox(pt)
            Mode.MOVE -> box = moved(pt) ?: return
            Mode.RESIZE -> {
                val start = startBox ?: return
                val h = handle ?: return
                val aspect = if (s.keepProportions && start.h > 0f) start.w / start.h else null
                val target = resizeTarget(start, h, grabStart + (pt - downPoint))
                val step = controller.increments.step(IncrementKind.LENGTH)
                val nb = clean(
                    if (step == null) ShapeGeometry.resize(start, h, target.first, s.fromCenter, aspect)
                    else resizeStepped(start, h, target, s.fromCenter, aspect, step),
                )
                box = nb
                resizeGuides = resizeGuidesFor(nb, target.second, target.third)
            }
            Mode.ROTATE -> {
                val start = startBox ?: return
                val v = pt - start.center
                val v0 = downPoint - start.center
                if (v.lengthSq < 1e-6f || v0.lengthSq < 1e-6f) return
                // Relative to where the handle was grabbed, so the shape never jumps.
                var deg = start.rotationDeg + Math.toDegrees(ShapeGeometry.signedAngle(v0, v).toDouble()).toFloat()
                // v1.6: an Angle step replaces the 15° option (absolute multiples).
                val angleStep = controller.increments.step(IncrementKind.ANGLE)
                deg = when {
                    angleStep != null -> IncrementMath.snapAngle(deg, angleStep).also { stepReadout = IncrementReadout.angle(it) }
                    s.snapAngle -> ShapeGeometry.snapDegrees(deg)
                    else -> ShapeGeometry.normalizeDegrees(deg)
                }
                box = start.copy(rotationDeg = deg)
            }
            Mode.LINE_START, Mode.LINE_END -> {
                val start = startBox ?: return
                val fixed = if (mode == Mode.LINE_START) start.end else start.start
                val q = lineEnd(fixed, snap.snapPoint(grabStart + (pt - downPoint)), s.snapAngle)
                box = if (mode == Mode.LINE_START) ShapeBox.line(q, fixed) else ShapeBox.line(fixed, q)
            }
            Mode.POINT, Mode.NEW_POINT -> dragPoint(pt)
            Mode.HANDLE_IN, Mode.HANDLE_OUT -> dragHandle(pt)
        }
        showReadout(stepReadout)
        refreshPreview()
    }

    /** A moved shape: its outline's bounds snap like the transform tool's box; else the old grid rule. */
    private fun moved(pt: Vec2): ShapeBox? {
        val start = startBox ?: return null
        val delta = pt - downPoint
        val r = startBounds?.let { snap.snapMove(it.offset(delta.x, delta.y)) }
        // Axes that didn't snap: the box's top-left corner (a line's start) follows the grid.
        val ref = if (lineHandles) start.start else start.toDoc(Vec2(-start.w / 2f, -start.h / 2f))
        val g = controller.snapToGrid(ref + delta) - ref
        val dx = if (r != null && r.snappedX) delta.x + r.dx else g.x
        val dy = if (r != null && r.snappedY) delta.y + r.dy else g.y
        // v1.6: an axis that neither a guide nor the grid decided moves by multiples of the Length step.
        val step = controller.increments.step(IncrementKind.LENGTH)
        if (step != null && !gridSnaps) {
            val sx = if (r != null && r.snappedX) dx else IncrementMath.snapDelta(delta.x, step)
            val sy = if (r != null && r.snappedY) dy else IncrementMath.snapDelta(delta.y, step)
            stepReadout = IncrementReadout.move(sx, sy)
            return start.translated(sx, sy)
        }
        return start.translated(dx, dy)
    }

    // ------------------------------------------------------------------ increments (v1.6 §3.4)

    /**
     * Square-grid snapping is on: an axis that no guide decided follows the grid, which beats the
     * increment (§3.4: a guide, then the grid, then the step).
     */
    private val gridSnaps: Boolean
        get() {
            val g = controller.grid
            return g.enabled && g.snap && g.type == GridType.SQUARE && g.spacingPx > 0f
        }

    /** What the gesture being stepped shows ([com.brushwork.paint.snap.Increments.readout]); null when nothing is stepped. */
    private var stepReadout: String? = null

    private fun showReadout(text: String?) {
        val inc = controller.increments
        if (inc.readout != text) inc.readout = text
    }

    /**
     * A point dragged by [d] from [from] that the snap session put at [snapped]: with a Length
     * step, each axis that neither a guide nor the grid decided moves by a multiple of the step.
     */
    private fun steppedPoint(from: Vec2, d: Vec2, snapped: Vec2): Vec2 {
        val step = controller.increments.step(IncrementKind.LENGTH) ?: return snapped
        if (gridSnaps) return snapped
        val gx = snap.guides.any { it.axis == SnapAxis.X }
        val gy = snap.guides.any { it.axis == SnapAxis.Y }
        val p = Vec2(
            if (gx) snapped.x else from.x + IncrementMath.snapDelta(d.x, step),
            if (gy) snapped.y else from.y + IncrementMath.snapDelta(d.y, step),
        )
        stepReadout = IncrementReadout.move(p.x - from.x, p.y - from.y)
        return p
    }

    /**
     * The free end of a line seen from its [fixed] end, [q] being the finger's point after the snap
     * session. Without increments exactly v1.5 (the 15° option); with an Angle step the direction
     * lands on its multiples instead of 15°, and with a Length step the length (× [lengthFactor]:
     * 2 for a line drawn from its center) on the step's multiples, unless a guide or the grid
     * placed [q].
     */
    private fun lineEnd(fixed: Vec2, q: Vec2, snapAngle: Boolean, lengthFactor: Float = 1f): Vec2 {
        val inc = controller.increments
        val angleStep = inc.step(IncrementKind.ANGLE)
        val lengthStep = inc.step(IncrementKind.LENGTH)
        var p = q
        if (angleStep != null) {
            if (snap.guides.isEmpty() && !gridSnaps) p = ShapeGeometry.snapAngle(fixed, p, angleStep)
        } else if (snapAngle) {
            val a = ShapeGeometry.snapAngle(fixed, p)
            if (a.distanceTo(p) > 1e-3f) snap.clearGuides()
            p = a
        }
        if (angleStep == null && lengthStep == null) return p
        if (lengthStep != null && snap.guides.isEmpty() && !gridSnaps) {
            val d = p - fixed
            val len = d.length * lengthFactor
            if (len > 1e-6f) p = fixed + d * (max(IncrementMath.snap(len, lengthStep), lengthStep) / len)
        }
        val d = p - fixed
        stepReadout = "${IncrementReadout.length(d.length * lengthFactor)} · ${IncrementReadout.angle(Math.toDegrees(kotlin.math.atan2(d.y, d.x).toDouble()).toFloat())}"
        return p
    }

    /**
     * A resize handle dragged with a Length step: the dragged width / height on multiples of
     * [step] (see [ShapeGeometry.resizeStepped]), except a size whose moving side a guide placed
     * ([target]'s hits, on boxes turned by a multiple of 90°; any guide on other boxes) or that
     * follows the grid.
     */
    private fun resizeStepped(start: ShapeBox, h: ShapeGeometry.Handle, target: Triple<Vec2, SnapHit?, SnapHit?>, fromCenter: Boolean, aspect: Float?, step: Float): ShapeBox {
        val rot = ShapeGeometry.normalizeDegrees(start.rotationDeg)
        val quarter = (rot / 90f).roundToInt()
        val aligned = abs(rot - quarter * 90f) <= 1e-3f
        val grid = gridSnaps
        val (guideW, guideH) = if (aligned) {
            val swap = quarter % 2 != 0
            val gx = target.second != null
            val gy = target.third != null
            if (swap) gy to gx else gx to gy
        } else {
            val any = snap.guides.isNotEmpty()
            any to any
        }
        val b = ShapeGeometry.resizeStepped(start, h, target.first, fromCenter, aspect, step, stepW = !grid && !guideW, stepH = !grid && !guideH)
        stepReadout = IncrementReadout.size(b.w, b.h)
        return b
    }

    /**
     * Where a resize handle goes for finger [pt]: on a box turned by a multiple of 90° the moving
     * sides snap on their own axis (the hits are returned for the guides); otherwise the handle
     * point snaps on both axes. Axes that didn't snap follow the grid.
     */
    private fun resizeTarget(start: ShapeBox, h: ShapeGeometry.Handle, pt: Vec2): Triple<Vec2, SnapHit?, SnapHit?> {
        val rot = ShapeGeometry.normalizeDegrees(start.rotationDeg)
        val quarter = (rot / 90f).roundToInt()
        if (abs(rot - quarter * 90f) > 1e-3f) return Triple(snap.snapPoint(pt), null, null)
        snap.clearGuides()
        val swap = quarter % 2 != 0
        val movesX = if (swap) h.fy != 0 else h.fx != 0
        val movesY = if (swap) h.fx != 0 else h.fy != 0
        val grid = controller.snapToGrid(pt)
        val hx = if (movesX) snap.snapValue(pt.x, SnapAxis.X) else null
        val hy = if (movesY) snap.snapValue(pt.y, SnapAxis.Y) else null
        return Triple(Vec2(hx?.pos ?: grid.x, hy?.pos ?: grid.y), hx, hy)
    }

    /** Guides of the sides a resize handle snapped ([hx] / [hy]) across the resized box [b]. */
    private fun resizeGuidesFor(b: ShapeBox, hx: SnapHit?, hy: SnapHit?): List<SnapGuide> {
        if (hx == null && hy == null) return emptyList()
        val bounds = Bounds.of(b.corners()) ?: return emptyList()
        val out = ArrayList<SnapGuide>(2)
        hx?.line?.let { l -> out += SnapGuide(SnapAxis.X, l.pos, min(l.spanStart, bounds.top), max(l.spanEnd, bounds.bottom), l.label, l.source) }
        hy?.line?.let { l -> out += SnapGuide(SnapAxis.Y, l.pos, min(l.spanStart, bounds.left), max(l.spanEnd, bounds.right), l.label, l.source) }
        return out
    }

    /** A dragged point: its start position plus the finger's motion, snapped. */
    private fun dragPoint(pt: Vec2) {
        val start = startBox ?: return
        if (dragIndex !in startAnchors.indices) return
        val d = pt - downPoint
        val target = steppedPoint(grabStart, d, snap.snapPoint(grabStart + d))
        applyAnchors(startAnchors.mapIndexed { i, a -> if (i == dragIndex) a.moved(target) else a }, start.rotationDeg)
    }

    // region v110-A5 snap
    /**
     * The selected points dragged by one of them. v1.10 (A5, R8 cause B): the GROUP snaps (its box
     * and every moving point, `PointGroupSnap`), then the grid and the step; in 1.9 only the grabbed
     * point snapped.
     */
    private fun dragGroup(pt: Vec2) {
        val move = groupSnapMove(pt - downPoint, dragIndex)
        setGroupTransform(Affine2.translate(move.x, move.y))
    }

    /**
     * A drag on the gizmo: its map from where the finger went down (increments as `PointGizmo.dragMap`
     * applies them). v1.10 (A5, R8 cause A): a drag inside the box moves the group as [dragGroup]
     * does, snapped; a scale handle snaps the edges it drags (`PointGroupSnap.scale`); the rotate
     * knob keeps its map.
     */
    private fun dragGizmo(pt: Vec2) {
        val layout = gizmoLayout ?: return
        if (gizmoPart == PointGizmo.Part.MOVE) {
            val move = groupSnapMove(pt - downPoint, -1)
            setGroupTransform(Affine2.translate(move.x, move.y))
            return
        }
        val inc = controller.increments.state
        var m = gizmo.dragMap(layout, gizmoPart, downPoint, pt, keepScaleProportions(), inc)
        if (gizmoPart != PointGizmo.Part.ROTATE) {
            val box = groupSnapMeasure(-1)
            if (box != null) m = com.brushwork.paint.tools.points.PointGroupSnap.of(snap, controller).scale(m, gizmoPart, box, layout.pivotDoc, keepScaleProportions())
        }
        if (inc.enabled) stepReadout = gizmoReadout(m)
        setGroupTransform(m)
    }

    /** The group edit whose moving points [groupSnapPoints] holds (null: measure them again). */
    private var groupSnapBase: List<ShapeAnchor>? = null
    /** The moving points where the group gesture began, as x, y pairs. */
    private var groupSnapPoints = FloatArray(0)
    /** Their bounds then. */
    private var groupSnapBox: DocBox? = null
    /** The grabbed point's index in [groupSnapPoints], or -1 (the gizmo). */
    private var groupSnapGrabbed = -1

    /**
     * v1.10 (A5, R8): the moving points of the group edit in progress, measured once per gesture
     * ([groupSnapPoints], [groupSnapGrabbed] for the point [grabbed], a shape point index or -1):
     * their bounds where the gesture began, or null (no group edit, or no finite point).
     */
    private fun groupSnapMeasure(grabbed: Int): DocBox? {
        val base = groupBase ?: return null
        if (groupSnapBase !== base) {
            val moving = groupSelection.indices.filter { it in base.indices }
            groupSnapPoints = com.brushwork.paint.tools.points.PointGroupSnap.pairs(moving.map { base[it].pos })
            groupSnapBox = com.brushwork.paint.tools.points.PointGroupSnap.bounds(groupSnapPoints)
            groupSnapGrabbed = moving.indexOf(grabbed)
            groupSnapBase = base
        }
        return groupSnapBox
    }

    /**
     * v1.10 (A5, R8): the snapped delta of the group edit in progress for the finger's raw delta
     * [d], the point [grabbed] (a shape point index, or -1) under the finger ([groupSnapMeasure]);
     * while increments are on the readout shows the move.
     */
    private fun groupSnapMove(d: Vec2, grabbed: Int): Vec2 {
        val box = groupSnapMeasure(grabbed) ?: return d
        val move = com.brushwork.paint.tools.points.PointGroupSnap.of(snap, controller).snap(groupSnapPoints, box, groupSnapGrabbed, d)
        if (controller.increments.state.enabled) stepReadout = IncrementReadout.move(move.x, move.y)
        return move
    }
    // endregion

    /** What a stepped gizmo drag shows: the move, the scale or the angle. */
    private fun gizmoReadout(m: Affine2): String? = when (gizmoPart) {
        PointGizmo.Part.NONE -> null
        PointGizmo.Part.MOVE -> IncrementReadout.move(m.tx, m.ty)
        PointGizmo.Part.ROTATE -> IncrementReadout.angle(Math.toDegrees(kotlin.math.atan2(m.b, m.a).toDouble()).toFloat())
        else -> IncrementReadout.scale(m.a * 100f, m.d * 100f)
    }

    /** A dragged tangent handle (its end snaps like a point). */
    private fun dragHandle(pt: Vec2) {
        val start = startBox ?: return
        val a = startAnchors.getOrNull(dragIndex) ?: return
        val d = pt - downPoint
        val end = steppedPoint(grabStart, d, snap.snapPoint(grabStart + d))
        val v = end - a.pos
        if (v.length < 1e-3f) return
        applyAnchors(ShapePoints.dragHandle(startAnchors, dragIndex, closedShape, out = mode == Mode.HANDLE_OUT, v = v), start.rotationDeg)
    }

    override fun onUp(p: ToolPoint) {
        if (bevelTap) {
            bevelTap = false
            closeBevelPopup()
            mode = Mode.NONE
            controller.invalidateOverlay()
            return
        }
        when (mode) {
            Mode.NONE -> {}
            Mode.CREATE -> createUp(Vec2(p.x, p.y))
            Mode.POINT -> when {
                started -> onMove(p)
                // A tap selects the point (again: deselects it).
                !longPressed -> selectPoint(if (selectedPoint == dragIndex) -1 else dragIndex)
            }
            Mode.MOVE -> when {
                started -> onMove(p)
                // A tap on the shape in points mode drops the point selection.
                pointsMode && !longPressed -> selectPoint(-1)
            }
            Mode.GROUP_POINT, Mode.GIZMO -> when {
                started -> { onMove(p); endGroupEdit() }
                longPressed -> {}
                tapIndex >= 0 -> tapPoint(tapIndex)
                // A tap inside the gizmo (not on a point) drops the selection, like a tap on the shape.
                mode == Mode.GIZMO && gizmoPart == PointGizmo.Part.MOVE -> selectPoints(PointSelection.none(pointCount))
            }
            Mode.MARQUEE -> if (started) finishMarquee(Vec2(p.x, p.y)) else if (!longPressed) selectPoints(PointSelection.none(pointCount))
            else -> if (started) onMove(p)
        }
        mode = Mode.NONE
        startBox = null
        startBounds = null
        handle = null
        endGroupGesture()
        snap.end()
        showReadout(null)
        if (resizeGuides.isNotEmpty()) { resizeGuides = emptyList(); controller.invalidateOverlay() }
        // The drag is over: a plain shape goes back into the layer, a brush outline is refined
        // once it rests a moment.
        setDragging(false)
        controller.invalidateOverlay()
    }

    /**
     * A tap on point [i] in points mode: with "Select several" it is added or removed; otherwise
     * only it is selected (a tap on the only selected point deselects it, as in v1.6).
     */
    private fun tapPoint(i: Int) {
        val n = pointCount
        if (i !in 0 until n) return
        val s = pointSelection.resized(n)
        selectPoints(
            when {
                selectSeveral -> s.toggled(i)
                s.isSingle && s.primary == i -> PointSelection.none(n)
                else -> s.only(i)
            },
        )
    }

    /** A marquee released at [end]: the points inside the screen rectangle from where it began join the selection. */
    private fun finishMarquee(end: Vec2) {
        val anchors = docAnchors() ?: return
        val t = controller.viewTransform
        val a = t.docToScreen(downPoint)
        val b = t.docToScreen(end)
        val inside = PointGroupMath.inside(anchors.map { t.docToScreen(it.pos) }, RectF(a.x, a.y, b.x, b.y))
        if (inside.isNotEmpty()) selectPoints(pointSelection.resized(anchors.size).plusAll(inside))
    }

    /** Clears what a group gesture (a drag on points, the gizmo or a marquee) kept while it ran. */
    private fun endGroupGesture() {
        if (groupBase != null) endGroupEdit()
        gizmoLayout = null
        gizmoPart = PointGizmo.Part.NONE
        tapIndex = -1
        marqueeEnd = null
    }

    /** End of a CREATE gesture: a new shape, or a tap (commit the pending one, open the shape tapped). */
    private fun createUp(pt: Vec2) {
        if (started) creatingBox = creationBox(pt)
        val created = creatingBox?.takeIf { started && isBigEnough(it) }
        creatingBox = null
        val maps = creatingMaps
        creatingMaps = emptyList()
        if (created == null) {
            val tap = !started
            var closed: Layer? = null
            var closedObject: Long? = null
            if (box != null) {
                // A tap (or a drag too small to make a shape) outside the pending shape commits it.
                val before = controller.doc.layers.size
                val edited = editingLayer
                lastVectorId = null
                commit()
                if (box == null) {
                    closed = if (controller.doc.layers.size > before) controller.doc.activeLayer else edited
                    closedObject = lastVectorId
                }
            }
            if (box == null) targetLayer = null
            // The tap opens the shape under the finger, never the one it just placed or closed (a
            // tap just outside an opened shape closes it: shapes open from a little farther away).
            // On a vector layer its shape objects come first. v1.9 (R11): never in erase mode.
            if (tap && box == null && !eraseOn && !openShapeObjectAt(pt, skip = closedObject)) shapeLayerAt(pt, skip = closed)?.let { editLayer(it) }
        } else {
            if (box != null) commit()
            if (box == null) {
                targetLayer = controller.doc.activeLayer
                box = created
                points = null
                pointsMode = false
                selectedPoint = -1
                selectSeveral = false
                clearHistory()
                // v1.8 (§3.1): its copies, from the maps taken at its start point.
                useNewMaps(maps)
            }
        }
        refreshPreview()
    }

    override fun onCancel() {
        // A cancelled finger (a pinch began: pan and zoom) leaves the bevel popup open.
        bevelTap = false
        setDragging(false)
        snap.end()
        showReadout(null)
        resizeGuides = emptyList()
        when (mode) {
            Mode.CREATE -> { creatingBox = null; creatingMaps = emptyList() }
            Mode.NONE, Mode.MARQUEE -> {}
            else -> restoreGesture()
        }
        mode = Mode.NONE
        startBox = null
        startBounds = null
        handle = null
        groupBase = null
        endGroupGesture()
        if (box == null) targetLayer = null
        refreshPreview()
    }

    /** Back to the state before the cancelled gesture (points it inserted included). */
    private fun restoreGesture() {
        val st = gestureState ?: return
        box = st.box
        points = st.points
        pointsMode = st.pointsMode
        pointSelection = st.selection.resized(st.points?.size ?: 0)
        while (history.size > gestureHistory) history.removeLast()
        redo.clear()
        redo.addAll(gestureRedo)
        redoCount = redo.size
        historyKey = null
        canUndoStep = history.isNotEmpty()
    }

    /**
     * A finger (or a pinch) starts / stops dragging the shape: while it drags, a brush outline
     * follows as a light draft and a plain shape is drawn in the overlay (no canvas recomposition).
     */
    private fun setDragging(on: Boolean) {
        brushPreview.interacting = on
        preview.interacting = on
        val was = dragging
        dragging = on
        // An opened shape layer (or a shape on a vector layer) drawn in the overlay during the
        // drag goes back into the layer.
        if (was && !on && box != null && (editingLayer != null || vectorLayerOfPending() != null)) refreshPreview()
    }

    /** A finger (or two) is dragging the shape (see [setDragging]). */
    private var dragging = false

    /**
     * Holding a finger still on the pending shape (a handle, a point, or the shape to move it)
     * keeps that edit going instead of turning into color picking (a point gets selected);
     * elsewhere the controller decides.
     */
    override fun onLongPress(p: ToolPoint): Boolean = when {
        // The finger that closes the bevel popup picks no color either.
        bevelTap -> true
        else -> longPressIn(mode)
    }

    private fun longPressIn(mode: Mode): Boolean = when (mode) {
        Mode.NONE, Mode.CREATE -> false
        Mode.POINT -> {
            if (!started) {
                selectPoint(dragIndex)
                longPressed = true
            }
            true
        }
        else -> {
            // A held finger on the group (or a held marquee) neither toggles nor clears points on release.
            if ((mode == Mode.MOVE || mode == Mode.GROUP_POINT || mode == Mode.GIZMO || mode == Mode.MARQUEE) && !started) longPressed = true
            true
        }
    }

    // ------------------------------------------------------------------ two-finger pinch

    /** Two fingers on (or around) the pending shape scale, rotate and move it. */
    override fun onTwoFingerStart(focus: Vec2, a: Vec2, b: Vec2): Boolean {
        val bx = box ?: return false
        val t = controller.viewTransform
        // v1.7 (§3.1): a pinch that starts inside the gizmo scales and rotates the selected points.
        val layout = if (pointsMode) docAnchors()?.let { groupLayout(it) } else null
        if (layout != null && PinchTargeting.acceptsQuad(a, b, layout.cornersScreen.map { t.screenToDoc(it) }, t)) {
            creatingBox = null
            mode = Mode.NONE
            endGroupGesture()
            pinchRedo = redo.toList()
            beginGroupEdit("Pinch")
            if (groupBase == null) return false
            groupPinchPivot = layout.pivotDoc
            pinchFocus = focus
            setDragging(true)
            return true
        }
        // With "Select several" two fingers outside the gizmo pan and zoom the view.
        if (pointsMode && selectSeveral) return false
        // A finger (not just the midpoint) must be on the shape's box, or near a line (v1.5, §4.7).
        val accepted = if (lineHandles) {
            PinchTargeting.acceptsSegment(a, b, bx.start, bx.end, strokeWidth / 2f, t)
        } else {
            PinchTargeting.acceptsQuad(a, b, bx.corners(), t)
        }
        if (!accepted) return false
        creatingBox = null
        mode = Mode.NONE
        pinchStart = bx
        pinchFocus = focus
        // v1.9 (R17): a pinch is one in-tool step for every shape (a pinch that moved nothing drops it, below).
        pinchRedo = redo.toList()
        pushHistory()
        pinchPushed = true
        setDragging(true)
        return true
    }

    override fun onTwoFingerGesture(translation: Vec2, scale: Float, rotationDeg: Float) {
        groupPinchPivot?.let { pivot ->
            if (!translation.x.isFinite() || !translation.y.isFinite() || !scale.isFinite() || scale <= 0f || !rotationDeg.isFinite()) return
            setGroupTransform(groupPinchMap(pivot, translation, scale, rotationDeg))
            return
        }
        val start = pinchStart ?: return
        if (box == null) { pinchStart = null; return }
        if (!translation.x.isFinite() || !translation.y.isFinite() || !scale.isFinite() || scale <= 0f || !rotationDeg.isFinite()) return
        box = pinched(start, pinchFocus, translation, scale, rotationDeg, settings.snapAngle)
        refreshPreview()
    }

    override fun onTwoFingerEnd(cancelled: Boolean) {
        if (groupPinchPivot != null) {
            groupPinchPivot = null
            showReadout(null)
            setDragging(false)
            val b = box
            if (cancelled && b != null) {
                // Back to the points before the pinch (its step is dropped).
                history.removeLastOrNull()?.let { restoreState(it) }
                dropPinchStep()
            } else if (b != null && history.lastOrNull() == pendingState(b)) {
                // v1.9 (R17): a pinch that left the points as they were is no edit.
                history.removeLast()
                dropPinchStep()
            }
            endGroupEdit()
            refreshPreview()
            return
        }
        val start = pinchStart ?: return
        pinchStart = null
        showReadout(null)
        setDragging(false)
        if (cancelled && box != null) {
            box = start
            if (pinchPushed) { history.removeLastOrNull(); dropPinchStep() }
        } else if (pinchPushed && box == start) {
            // v1.9 (R17): a pinch that left the shape as it was is no edit (its step is dropped).
            history.removeLastOrNull()
            dropPinchStep()
        }
        pinchPushed = false
        refreshPreview()
    }

    /**
     * v1.9 (R17): the two-finger gesture's step was taken off [history] (a tap, a cancelled pinch
     * or one that changed nothing): the redo steps are those it began with ([pinchRedo]), so the
     * two-finger tap that undoes never loses what earlier undos left to redo.
     */
    private fun dropPinchStep() {
        canUndoStep = history.isNotEmpty()
        redo.clear()
        redo.addAll(pinchRedo)
        redoCount = redo.size
        historyKey = null
        pinchRedo = emptyList()
    }

    /**
     * The map of a pinch on the gizmo: the canvas's [scale] and [rotationDeg] about the gizmo's
     * [pivot] (the box centre), then its [translation] (the fingers' midpoint moved), the form
     * `PointGroupMath.pinch` gives for the fingers themselves. With increments on, the scale lands
     * on the Scale step (relative to the pinch's start) and the angle on the Angle step.
     */
    private fun groupPinchMap(pivot: Vec2, translation: Vec2, scale: Float, rotationDeg: Float): Affine2 {
        val inc = controller.increments
        val scaleStep = inc.step(IncrementKind.SCALE)
        val angleStep = inc.step(IncrementKind.ANGLE)
        val k = if (scaleStep != null) TransformIncrements.relativeFactor(scale, scaleStep) else scale
        val deg = if (angleStep != null) IncrementMath.snapAngle(rotationDeg, angleStep) else rotationDeg
        if (scaleStep != null || angleStep != null) {
            showReadout(listOfNotNull(scaleStep?.let { IncrementReadout.percent(k * 100f) }, angleStep?.let { IncrementReadout.angle(deg) }).joinToString(" · "))
        }
        return Affine2.translate(translation.x, translation.y) * Affine2.rotateAbout(pivot, deg) * Affine2.scaleAbout(pivot, k, k)
    }

    /**
     * [start] scaled by [scale] and rotated by [rotationDeg] around the pinch [focus], then moved
     * by [translation] (rotation snapped to 15° steps with [snap]).
     */
    private fun pinched(start: ShapeBox, focus: Vec2, translation: Vec2, scale: Float, rotationDeg: Float, snap: Boolean): ShapeBox {
        // v1.6 increments: the scale on the Scale step relative to the pinch's start (100, 110,
        // 120 % of the shape as it was), the angle on the Angle step instead of the 15° option.
        val inc = controller.increments
        val scaleStep = inc.step(IncrementKind.SCALE)
        val angleStep = inc.step(IncrementKind.ANGLE)
        val k = if (scaleStep != null) TransformIncrements.relativeFactor(scale, scaleStep) else scale
        val target = start.rotationDeg + rotationDeg
        val deg = when {
            angleStep != null -> IncrementMath.snapAngle(target, angleStep)
            snap -> ShapeGeometry.snapDegrees(target)
            else -> ShapeGeometry.normalizeDegrees(target)
        }
        if (scaleStep != null || angleStep != null) {
            showReadout(listOfNotNull(scaleStep?.let { IncrementReadout.percent(k * 100f) }, angleStep?.let { IncrementReadout.angle(deg) }).joinToString(" · "))
        }
        val delta = ShapeGeometry.normalizeDegrees(deg - start.rotationDeg) * Geometry.DEG
        val c = focus + (start.center - focus).rotated(delta) * k + translation
        return clean(ShapeBox(c.x, c.y, start.w * k, start.h * k, deg))
    }

    /** True when [p] is on the pending shape (its box with a finger's margin, or near a line). */
    private fun isOnShape(b: ShapeBox, p: Vec2): Boolean {
        val tol = controller.docLength(HANDLE_TOUCH_DP)
        if (lineHandles) {
            return Geometry.distanceToSegment(p, b.start, b.end) <= tol * 1.5f + strokeWidth / 2f
        }
        val local = b.toLocal(p)
        return abs(local.x) <= b.w / 2f + tol && abs(local.y) <= b.h / 2f + tol
    }

    // ------------------------------------------------------------------ geometry helpers

    /** The new shape dragged out from [anchor] to [pt] (with the options a new shape gets). */
    private fun creationBox(pt: Vec2): ShapeBox {
        val s = newShapeSettings()
        var cur = snap.snapPoint(pt)
        return if (s.type.isLineLike) {
            cur = lineEnd(anchor, cur, s.snapAngle, if (s.fromCenter) 2f else 1f)
            if (s.fromCenter) ShapeBox.line(anchor * 2f - cur, cur) else ShapeBox.line(anchor, cur)
        } else {
            val aspect = if (s.keepProportions) naturalAspect(s) else null
            // v1.6: the width and height on multiples of the Length step (axes no guide or grid decided).
            val step = controller.increments.step(IncrementKind.LENGTH)
            if (step == null || gridSnaps) {
                ShapeGeometry.dragBox(anchor, cur, s.fromCenter, aspect)
            } else {
                val gx = snap.guides.any { it.axis == SnapAxis.X }
                val gy = snap.guides.any { it.axis == SnapAxis.Y }
                ShapeGeometry.dragBoxStepped(anchor, cur, s.fromCenter, aspect, step, stepW = !gx, stepH = !gy)
                    .also { stepReadout = IncrementReadout.size(it.w, it.h) }
            }
        }
    }

    private fun isBigEnough(b: ShapeBox): Boolean {
        val minLen = controller.docLength(MIN_SIZE_DP)
        return if (newShapeSettings().type.isLineLike) b.w >= minLen else max(b.w, b.h) >= minLen && min(b.w, b.h) >= 1f
    }

    private fun rotationHandle(b: ShapeBox): Vec2 = b.toDoc(Vec2(0f, -b.h / 2f - controller.docLength(ROTATE_OFFSET_DP)))

    private fun handlePoint(b: ShapeBox, h: ShapeGeometry.Handle): Vec2 = b.toDoc(Vec2(h.fx * b.w / 2f, h.fy * b.h / 2f))

    /** The resize handles shown for [b] (a custom outline of zero height / width has fewer). */
    private fun visibleHandles(b: ShapeBox): List<ShapeGeometry.Handle> {
        if (points == null) return ShapeGeometry.Handle.entries
        val flatH = b.h < FLAT_PX
        val flatW = b.w < FLAT_PX
        return ShapeGeometry.Handle.entries.filter { h -> !(flatH && h.fy != 0) && !(flatW && h.fx != 0) }
    }

    private fun hitTest(b: ShapeBox, p: Vec2): Mode? {
        val tol = controller.docLength(HANDLE_TOUCH_DP)
        val width = strokeWidth
        if (lineHandles) {
            val ds = p.distanceTo(b.start); val de = p.distanceTo(b.end)
            if (min(ds, de) <= tol) return if (de <= ds) Mode.LINE_END else Mode.LINE_START
            val reach = max(tol * 0.75f, width / 2f)
            return if (Geometry.distanceToSegment(p, b.start, b.end) <= reach) Mode.MOVE else null
        }
        if (p.distanceTo(rotationHandle(b)) <= tol) return Mode.ROTATE
        var best: ShapeGeometry.Handle? = null
        var bestD = tol
        for (h in visibleHandles(b)) {
            val d = p.distanceTo(handlePoint(b, h))
            if (d <= bestD) { best = h; bestD = d }
        }
        val local = b.toLocal(p)
        val inside = abs(local.x) <= b.w / 2f && abs(local.y) <= b.h / 2f
        if (best != null && !(inside && bestD > controller.docLength(HANDLE_DRAW_DP * 1.5f))) {
            handle = best
            return Mode.RESIZE
        }
        val pad = max(tol * 0.5f, width / 2f)
        return if (abs(local.x) <= b.w / 2f + pad && abs(local.y) <= b.h / 2f + pad) Mode.MOVE else null
    }

    /**
     * Points mode: a tangent handle of the selected point, a point, a "+" between two points or
     * the outline (both insert a point there), or the shape itself (move).
     */
    private fun hitPoints(b: ShapeBox, pt: Vec2): Mode? {
        val pts = points ?: return null
        val anchors = ShapePoints.docAnchors(b, pts)
        val closed = closedShape
        val tol = controller.docLength(HANDLE_TOUCH_DP)
        val selection = pointSelection.resized(anchors.size)
        val sel = selectedPoint
        tapIndex = -1
        // v1.6: the tangent handles of the one selected point.
        if (selection.isSingle && sel in anchors.indices) {
            val (hIn, hOut) = ShapePoints.handles(anchors, sel, closed)
            val a = anchors[sel].pos
            val dOut = if (hOut.length > HANDLE_MIN_PX) pt.distanceTo(a + hOut) else Float.MAX_VALUE
            val dIn = if (hIn.length > HANDLE_MIN_PX) pt.distanceTo(a + hIn) else Float.MAX_VALUE
            val dh = min(dOut, dIn)
            // The point itself wins when the finger is closer to it than to its handles.
            if (dh <= tol && dh < pt.distanceTo(a)) {
                startPointGesture(anchors, sel, if (dOut <= dIn) a + hOut else a + hIn)
                return if (dOut <= dIn) Mode.HANDLE_OUT else Mode.HANDLE_IN
            }
        }
        var idx = -1
        var bestD = tol
        // Later points win ties so the newest one is grabbed when points overlap.
        for (i in anchors.indices.reversed()) {
            val d = anchors[i].pos.distanceTo(pt)
            if (d < bestD) { idx = i; bestD = d }
        }
        // v1.7 (§3.1): with two or more points selected, the gizmo's handles and knob. A selected
        // point at least as close as the handle wins (a drag on it moves the group: two points in
        // a row sit on the box's side handles); otherwise a drag scales or turns the group and a
        // tap still acts on the point under the finger.
        val t = controller.viewTransform
        val layout = groupLayout(anchors)
        val screen = t.docToScreen(pt)
        val part = layout?.let { gizmo.hit(it, screen, t) } ?: PointGizmo.Part.NONE
        if (layout != null && part != PointGizmo.Part.NONE && part != PointGizmo.Part.MOVE) {
            val handleAt = gizmoHandleAt(layout, part)
            val pointWins = idx in selection && handleAt != null && t.docToScreen(anchors[idx].pos).distanceTo(screen) <= handleAt.distanceTo(screen) + PointGizmo.TIE_PX
            if (!pointWins) {
                startGizmo(anchors, layout, part)
                tapIndex = idx
                return Mode.GIZMO
            }
        }
        if (idx >= 0) {
            startPointGesture(anchors, idx, anchors[idx].pos)
            tapIndex = idx
            // A point of the group (or any point with "Select several") drags the group.
            return if (selectSeveral || (idx in selection && selection.count >= 2)) Mode.GROUP_POINT else Mode.POINT
        }
        // Inside the gizmo, not on a point: the group moves.
        if (layout != null && part == PointGizmo.Part.MOVE) {
            startGizmo(anchors, layout, part)
            return Mode.GIZMO
        }
        // "Select several" never inserts points: the rest of the canvas box-selects.
        if (selectSeveral) return Mode.MARQUEE
        // A group is selected: a tap elsewhere on the shape drops it ("+" are hidden meanwhile).
        if (selection.count >= 2) return if (isOnShape(b, pt)) Mode.MOVE else null
        for (s in plusSegments(anchors)) {
            if (ShapePoints.pointOn(anchors, closed, s, 0.5f).distanceTo(pt) <= tol * PLUS_TOUCH) return insertPoint(b, anchors, s, 0.5f)
        }
        val hit = ShapePoints.nearest(anchors, closed, pt)
        if (hit != null && hit.distance <= max(tol * 0.6f, strokeWidth / 2f)) return insertPoint(b, anchors, hit.segment, hit.t)
        return if (isOnShape(b, pt)) Mode.MOVE else null
    }

    /** Inserts a point on segment [s] at [t] (outline unchanged) and lets the finger drag it. */
    private fun insertPoint(b: ShapeBox, anchors: List<ShapeAnchor>, s: Int, t: Float): Mode {
        pushHistory()
        gesturePushed = true
        val (list, at) = ShapePoints.insert(anchors, closedShape, s, t)
        // The outline does not change, so neither does the box.
        points = ShapePoints.normalize(b, list)
        selectedPoint = at
        startPointGesture(list, at, list[at].pos)
        refreshPreview()
        return Mode.NEW_POINT
    }

    private fun startPointGesture(anchors: List<ShapeAnchor>, index: Int, grab: Vec2) {
        startAnchors = anchors
        dragIndex = index
        grabStart = grab
    }

    /** Where [part] (a handle or the knob) of [layout] is on screen, as `PointGizmo` draws it. */
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

    /** A gizmo gesture on [part] of [layout] (the points as they are now). */
    private fun startGizmo(anchors: List<ShapeAnchor>, layout: PointGizmo.Layout, part: PointGizmo.Part) {
        startAnchors = anchors
        dragIndex = -1
        gizmoLayout = layout
        gizmoPart = part
    }

    /** Segments long enough on screen to show a "+" in their middle. */
    private fun plusSegments(anchors: List<ShapeAnchor>): List<Int> {
        val minLen = controller.docLength(PLUS_MIN_SEGMENT_DP)
        val n = ShapePoints.segmentCount(anchors.size, closedShape)
        return (0 until n).filter { s -> anchors[s].pos.distanceTo(anchors[(s + 1) % anchors.size].pos) >= minLen }
    }

    /** The outline's bounds (document px) as a snap box. */
    private fun outlineBounds(o: ShapeObject): DocBox? {
        val b = ShapeOutlines.outline(o).bounds(0.25f) ?: return null
        if (!b.left.isFinite() || !b.top.isFinite() || !b.right.isFinite() || !b.bottom.isFinite()) return null
        return DocBox(b.left, b.top, b.right, b.bottom)
    }

    // ------------------------------------------------------------------ preview

    /** True when the outline of a NEW shape is painted with the painting tool (on the active layer). */
    private fun paintsWithBrush(layer: Layer): Boolean {
        val s = settings
        return s.strokeWith == ShapeStroke.BRUSH && s.strokes && layer === controller.doc.activeLayer
    }

    /** Rebuilds the preview of the pending / in-creation shapes and redraws. */
    fun refreshPreview() {
        if (eraseOn) {
            refreshErase()
            return
        }
        val vectorLayer = vectorLayerOfPending()
        if (vectorLayer != null) {
            refreshVectorPreview(vectorLayer)
            return
        }
        if (vectorPreview != null || vectorGuides.isNotEmpty()) releaseVectorPreview()
        val editLayer = editingLayer
        if (editLayer != null) {
            refreshEditPreview(editLayer)
            return
        }
        val layer = targetLayer ?: controller.doc.activeLayer
        val asNew = placesInNewLayer()
        val pending = box?.let { objectFor(it, points) }
        val creating = creatingBox?.let { newObject(it) }
        // v1.8 (§3.1): each shape is drawn with its copies (the maps taken at its start point):
        // into a raster layer as one union, into a new shape layer one copy after the other.
        val pMaps = pendingMaps()
        val cMaps = creatingDrawMaps()
        // v1.7 (§3.8): nothing is ever painted into a folder (such a shape is refused when it is
        // placed): it shows as plain outlines over the canvas.
        val intoFolder = !asNew && layer.isFolder
        val brushObj = if (!intoFolder && paintsWithBrush(layer)) creating ?: pending else null
        brushGuide = null
        if (brushObj != null) {
            ensureObserving()
            preview.release()
            val brushMaps = if (brushObj === creating) cMaps else pMaps
            // Only one shape can be the live brush stroke: while a new one is dragged out, the
            // pending one is shown as a plain outline until it is committed.
            val older = if (creating != null) pending else null
            overlaySpecs = (older?.let { specsOf(it, pMaps, brush = false, union = !asNew) } ?: emptyList()) +
                specsOf(brushObj, brushMaps, brush = true, union = !asNew)
            val whole = ShapeOutlines.brushOutline(brushObj)
            // v1.8 (D21): a cog with a hole is two strokes: the teeth are the live one, the hole
            // is a guide until the shape is placed (then both are painted).
            val contours = ShapeOutlines.contoursOf(whole)
            val path = contours.first()
            if (asNew && !liveBrushForNewLayer(layer)) {
                // The brush would paint the active layer differently from the new layer the
                // shape goes into (locked, hidden, alpha lock, mask): its outline is shown as a
                // guide and painted exactly when the shape is placed.
                brushPreview.cancel()
                specOverlay.setBand(null, 0f)
                brushGuide = ShapeCopies.mappedAll(whole, brushMaps).toAndroidPath(brushGuidePath)
            } else {
                if (overlaySpecs.isNotEmpty()) specOverlay.setBand(ShapeCopies.mappedAll(whole, brushMaps).toAndroidPath(bandPath), brushPresetInUse()?.size ?: 0f)
                brushGuide = restOf(contours)?.let { ShapeCopies.mappedAll(it, brushMaps) }?.toAndroidPath(brushGuidePath)
                // v1.8: the painting tool's stroke repeats on every copy (one symmetric stroke).
                brushPreview.request(path.ops, maps = brushMaps) { brushStrokeInput(path, out = it) }
            }
        } else {
            brushPreview.cancel()
            overlaySpecs = emptyList()
            val specs = (pending?.let { specsOf(it, pMaps, brush = false, union = !asNew) } ?: emptyList()) +
                (creating?.let { specsOf(it, cMaps, brush = false, union = !asNew) } ?: emptyList())
            if (specs.isNotEmpty()) ensureObserving()
            preview.show(layer, specs, asNewLayer = asNew || intoFolder, overlayOnly = intoFolder || (asNew && newLayerPreviewInOverlay(layer)))
        }
        controller.invalidateOverlay()
    }

    // ------------------------------------------------------------------ erase mode (v1.9, R11)

    /**
     * v1.9 (R11, [ErasableTool]): erase mode on or off. What is pending lands first in the mode it
     * was drawn in (placed, or its erase committed: [EraseLabels19.STEP_SHAPE]), or goes when it
     * can't land; an open bevel popup closes.
     */
    override fun setErase(on: Boolean) {
        if (on == eraseOn) return
        closeBevelPopup()
        if (hasPendingWork) commit()
        if (hasPendingWork) discard()
        endErase()
        eraseOn = on
        refreshPreview()
    }

    /** v1.9 (R11): erase mode is transient: it is off whenever the tool is chosen again. */
    override fun onSelected() {
        if (eraseOn) setErase(false)
    }

    /**
     * Erase mode's preview: no paint preview at all (the erase session owns the layer's render
     * override), the outline being drawn erases live ([showErase]) and shows dashed.
     */
    private fun refreshErase() {
        brushPreview.cancel()
        specOverlay.setBand(null, 0f)
        overlaySpecs = emptyList()
        brushGuide = null
        preview.release()
        if (vectorPreview != null || vectorGuides.isNotEmpty()) releaseVectorPreview()
        showErase(begin = !eraseRefused)
        if (eraseSession != null) ensureObserving()
        controller.invalidateOverlay()
    }

    /**
     * The outline being drawn (the one dragged out, else the pending one; with its copies) as an
     * eraser: [eraseOutline] for the overlay and, when [begin] allows, its live erase on the
     * shape's layer ([ToolErase.begin]; refused after saying why: [eraseRefused], the outline then
     * shows alone). Its footprint is the stroke at its width (the brush's size for a brush
     * outline) and, for a filled closed style, the fill. Returns the open session (null without
     * an outline, or refused).
     */
    private fun showErase(begin: Boolean): ToolEraseSession? {
        val creating = creatingBox
        val o = creating?.let { newObject(it) } ?: box?.let { objectFor(it, points) }
        if (o == null) {
            endErase()
            return null
        }
        val maps = if (creating != null) creatingDrawMaps() else pendingMaps()
        val path = ShapeCopies.mappedAll(ShapeOutlines.brushOutline(o), maps).toAndroidPath(erasePath)
        eraseOutline = path
        var s = eraseSession
        if (s == null && begin) {
            s = ToolErase.begin(controller, targetLayer ?: controller.doc.activeLayer, EraseSource.SHAPE)
            eraseSession = s
            eraseRefused = s == null
        }
        s?.update(path, eraseWidth(o), !o.type.isLineLike && o.style.fill)
        return s
    }

    /** The width [o]'s outline erases with (document px; 0 without a stroke). */
    private fun eraseWidth(o: ShapeObject): Float = when {
        !o.strokes -> 0f
        o.paintsWithBrush -> o.brushPreset?.size?.takeIf { it.isFinite() && it > 0f } ?: o.strokeWidth
        else -> o.strokeWidth
    }

    /** Ends the outline's erase without a step (the layer looks as before) and forgets the outline. */
    private fun endErase() {
        eraseSession?.cancel()
        eraseSession = null
        eraseRefused = false
        eraseOutline = null
    }

    /**
     * Erase mode's commit: the outline's erase lands as ONE step [EraseLabels19.STEP_SHAPE] (none
     * when nothing was under it) and the outline goes. Refused (the layer can't be edited now; the
     * message was shown): the outline stays pending.
     */
    private fun commitErase() {
        val s = eraseSession ?: showErase(begin = true) ?: return
        eraseSession = null
        inCommit = true
        val result = try {
            s.commit(EraseLabels19.STEP_SHAPE)
        } finally {
            inCommit = false
        }
        if (result is ToolEraseResult.Refused) {
            eraseRefused = true
            controller.invalidateOverlay()
            return
        }
        resetPending()
        controller.invalidateOverlay()
    }

    /**
     * A new shape that goes into a layer of its own is normally previewed inside the active
     * layer (just above its pixels: that looks exactly like a new layer above it when the active
     * layer is a plain one). When the active layer would change how it looks (hidden, opacity,
     * blend mode, mask, clipping) it is drawn over the canvas instead, unless layers above would
     * then be covered (a hidden active layer always uses the overlay: inside it, it would not show).
     * v1.7 (§3.8): with an OPEN folder active the new layer becomes the folder's top child, and
     * the compositor draws the preview inside the folder (false); a closed folder is never plain.
     */
    internal fun newLayerPreviewInOverlay(layer: Layer): Boolean {
        if (!controller.doc.effectiveVisible(layer)) return true
        if (layer.isFolder && layer.folderOpen) return false
        if (isPlain(layer)) return false
        val layers = controller.doc.layers
        val index = controller.doc.indexOf(layer)
        if (index < 0) return false
        for (i in index + 1 until layers.size) {
            if (layers[i].visible && layers[i].opacity > 0f) return false
        }
        return true
    }

    /**
     * [layer] shows its content as it is: fully opaque, normal blending, no mask, not clipped.
     * v1.7: never a folder (it shows its children's composite, not content of its own).
     */
    internal fun isPlain(layer: Layer): Boolean =
        !layer.isFolder && layer.opacity >= 1f && layer.blendMode == LayerBlendMode.NORMAL && !layer.clipping && !(layer.mask != null && layer.maskEnabled)

    /**
     * The brush outline of a new shape that goes into a layer of its own can be shown live on the
     * active layer (as it will look in the new one): the active layer takes a plain stroke and
     * shows it as it is. v1.7: never on a folder (it has no pixels to take a stroke): the outline
     * is shown as a guide and painted when the shape is placed.
     */
    internal fun liveBrushForNewLayer(layer: Layer): Boolean =
        !layer.isFolder && controller.doc.effectiveVisible(layer) && !controller.doc.effectiveLocked(layer) && !layer.alphaLocked && controller.editTargetOf(layer) == EditTarget.CONTENT && isPlain(layer)

    /**
     * The preview while a shape layer is edited: the layer's pixels are hidden and the edited
     * shape drawn in their place ([EditOverride]); a brush outline is the painting tool's live
     * stroke (shown inside the same override) unless that tool edits pixels directly (smudge,
     * blur, watercolor: its outline is shown as a guide until ✓). A new shape dragged out
     * meanwhile is drawn in the overlay.
     */
    private fun refreshEditPreview(layer: Layer) {
        ensureObserving()
        preview.release()
        val b = box
        if (b == null) {
            brushPreview.cancel()
            overlaySpecs = emptyList()
            controller.invalidateOverlay()
            return
        }
        val o = objectFor(b, points)
        val brush = o.paintsWithBrush
        val live = brush && liveBrushWhileEditing(layer)
        // The overlay only holds plain shapes here (the brush stroke is inside the override).
        specOverlay.setBand(null, 0f)
        val ov = installEditOverride(layer)
        // v1.8 (I15): the shape's live copies (its stored maps) follow every edit.
        val maps = o.copies.maps
        val specs = ShapeCopies.eachSpecs(o, maps, brush)
        // While a finger drags a plain shape that looks the same over the finished image, it is
        // drawn in the overlay: the canvas tiles (the layer's hidden pixels) stay as they are.
        val inOverlay = dragging && !brush && editDrawsInOverlay(layer)
        // v1.7 (I14): placed as ArrayDraw.sourceBounds places the committed shape's copies.
        val arraySource = if (layer.array != null) VectorOps.bounds(VShape(0L, shape = o)) else null
        setEditSpecs(ov, if (inOverlay) emptyList() else specs, arraySource)
        // A new shape dragged out meanwhile looks as it will once the opened one is closed.
        val creating = creatingBox?.let { newObject(it) }
        overlaySpecs = (if (inOverlay) specs else emptyList()) +
            (creating?.let { specsOf(it, creatingDrawMaps(), brush = false, union = !placesInNewLayer()) } ?: emptyList())
        val whole = if (brush) ShapeOutlines.brushOutline(o) else null
        // v1.8 (D21): a cog's hole is a guide while the teeth are the live stroke.
        val contours = whole?.let { ShapeOutlines.contoursOf(it) }
        val path = contours?.first()
        editGuide = when {
            whole == null -> null
            !live -> ShapeCopies.mappedAll(whole, maps).toAndroidPath(guidePath)
            else -> restOf(contours)?.let { ShapeCopies.mappedAll(it, maps) }?.toAndroidPath(guidePath)
        }
        if (path != null && live) brushPreview.request(path.ops, maps = maps) { brushStrokeInput(path, out = it) } else brushPreview.cancel()
        controller.invalidateOverlay()
    }

    /** v1.8 (D21): every contour of [contours] after the first (a cog's hole) as one path; null with one contour. */
    private fun restOf(contours: List<VectorPath>?): VectorPath? =
        if (contours == null || contours.size < 2) null else VectorPath(contours.drop(1).flatMap { it.ops })

    /**
     * v1.8 (D21): paints [contours] for real, a stroke each (a cog with a hole: the teeth, then the
     * hole), the first with the session's random values and contour i with those + i, as the
     * vector renderer seeds a path's sub-paths and [outlineReplay] seeds them; the session's own
     * come back afterwards. One contour: exactly the v1.7 commit. v1.8 (§3.1): each stroke
     * repeats on every map of [maps] (one symmetric stroke, `BrushTool.beginPath`'s maps).
     */
    private fun commitContours(contours: List<VectorPath>, maps: List<FloatArray> = emptyList()) {
        val seed = brushPreview.sessionSeed
        contours.forEachIndexed { i, path ->
            if (i > 0) brushPreview.useSeed(seed + i)
            brushPreview.commit(path.ops, maps) { brushStrokeInput(path, out = it) }
        }
        if (contours.size > 1) brushPreview.useSeed(seed)
    }

    /**
     * True when drawing the edited shape over the finished image looks exactly like drawing it
     * into [layer] (see PreviewHost): a normal, opaque, unmasked, unclipped layer with nothing
     * visible above it, no selection (a re-edited shape ignores it, the overlay would not), a
     * color document, at a zoom where the canvas is drawn smoothed.
     */
    private fun editDrawsInOverlay(layer: Layer): Boolean {
        // v1.7 (I14): an array's copies are drawn through the edit override only.
        if (layer.array != null) return false
        if (controller.viewTransform.zoom >= OVERLAY_MAX_ZOOM) return false
        val doc = controller.doc
        val index = doc.indexOf(layer)
        if (index < 0 || !doc.effectiveVisible(layer) || layer.opacity < 1f) return false
        if (layer.blendMode != LayerBlendMode.NORMAL || layer.clipping) return false
        if (layer.mask != null && layer.maskEnabled) return false
        if (doc.colorMode == ColorMode.MONOCHROME || controller.selection != null) return false
        val layers = doc.layers
        for (i in index + 1 until layers.size) {
            if (layers[i].visible && layers[i].opacity > 0f) return false
        }
        return true
    }

    /**
     * The edited shape's brush stroke can be shown live (a stroke drawn through a buffer, on the
     * layer's content; not with alpha lock or a selection: the outline is repainted whole over
     * cleared pixels, which the lock would keep empty and the selection would cut in the
     * preview, see [commitLayerEdit]).
     */
    private fun liveBrushWhileEditing(layer: Layer): Boolean {
        if (controller.editTargetOf(layer) != EditTarget.CONTENT || layer.alphaLocked || controller.selection != null) return false
        val tool = brushToolId()
        val preset = brushPresetInUse() ?: return false
        return !StrokeKind.of(tool, preset).isDirect
    }

    /** Runs a waiting live-brush replay now (the main looper does it otherwise). */
    internal fun flushPreview() = brushPreview.flush()

    // ------------------------------------------------------------------ vector layers (v1.5, A3)

    /**
     * The preview on a vector layer: the shapes are drawn over the layer's pixels (a new shape is
     * the topmost object), or over the layer with the opened object's hole ([VectorEditSession]);
     * never clipped by the pixel selection or the alpha lock (a shape object ignores both). One
     * brush outline is the painting tool's live stroke (the one dragged out, else the pending
     * one), adopted inside the same override; brush outlines that can't be shown live (smudge or
     * blur, a selection, alpha lock) are shown as guides.
     *
     * While a finger drags plain shapes that look the same over the finished image (see
     * [editDrawsInOverlay]) they are drawn in the overlay instead, as on raster layers: the canvas
     * tiles are not recomposited on every frame of the drag. They go back into the layer when
     * the finger lifts.
     */
    private fun refreshVectorPreview(layer: Layer) {
        preview.release()
        overlaySpecs = emptyList()
        specOverlay.setBand(null, 0f)
        brushGuide = null
        editGuide = null
        val pending = box?.let { objectFor(it, points) }
        val creating = creatingBox?.let { newObject(it) }
        if (pending == null && creating == null) {
            brushPreview.cancel()
            vectorGuides = emptyList()
            setVectorSpecs(layer, emptyList())
            controller.invalidateOverlay()
            return
        }
        ensureObserving()
        // A reopened object that is partly transparent: its plain parts are previewed with its
        // opacity, its brush outline as a guide (a live stroke would show it opaque).
        val opaque = vectorSession == null || vectorEditOpacity >= 1f
        val brushObj = (creating ?: pending)?.takeIf { it.paintsWithBrush }
        val live = brushObj != null && opaque && liveBrushOnVector(layer)
        val specs = ArrayList<VectorPaintSpec>(2)
        val guides = ArrayList<Path>(2)
        var path: VectorPath? = null
        var liveMaps: List<FloatArray> = emptyList()
        for (o in listOfNotNull(pending, creating)) {
            // v1.8 (I15): with its copies (a perspective one's as the plain paths it becomes).
            val maps = if (o === creating) creatingDrawMaps() else pendingMaps()
            specs += ShapeCopies.eachSpecs(o, maps, o.paintsWithBrush)
            if (!o.paintsWithBrush) continue
            if (live && o === brushObj) {
                // v1.8 (D21): the teeth are the live stroke. A cog's hole is a guide: the object
                // is rendered from its data on commit, and VectorLayerRenderer's shapeBrushReplays
                // replays every contour (seed + index), the hole included.
                val contours = ShapeOutlines.brushContours(o)
                path = contours.first()
                liveMaps = maps
                for (k in 1 until contours.size) guides += ShapeCopies.mappedAll(contours[k], maps).toAndroidPath()
            } else {
                guides += ShapeCopies.mappedAll(ShapeOutlines.brushOutline(o), maps).toAndroidPath()
            }
        }
        val inOverlay = dragging && !live && opaque && specs.isNotEmpty() && editDrawsInOverlay(layer)
        setVectorSpecs(layer, if (inOverlay) emptyList() else specs)
        if (inOverlay) overlaySpecs = specs
        vectorGuides = guides
        // (path is set only when live.)
        val livePath = path
        if (livePath != null) brushPreview.request(livePath.ops, maps = liveMaps) { brushStrokeInput(livePath, out = it) } else brushPreview.cancel()
        controller.invalidateOverlay()
    }

    /** Shows [specs] on the vector [layer] (inside the opened object's session, or the new-shape preview). */
    private fun setVectorSpecs(layer: Layer, specs: List<VectorPaintSpec>) {
        val old = vectorShown
        val regions = ArrayList<Rect>()
        for (s in specs) regions += s.regions
        val session = vectorSession
        if (session != null) {
            val shown = specs.toList()
            val alpha = (vectorEditOpacity.coerceIn(0f, 1f) * 255f).roundToInt()
            session.drawPreview = when {
                shown.isEmpty() -> null
                alpha >= 255 -> { cv -> drawSpecs(cv, shown) }
                else -> { cv ->
                    val save = cv.saveLayerAlpha(null, alpha)
                    drawSpecs(cv, shown)
                    cv.restoreToCount(save)
                }
            }
            if (controller.renderOverride !== session) session.adoptInner()
        } else if (specs.isEmpty() && !brushPreview.isLive) {
            releaseVectorPreview(keepGuides = true)
        } else {
            val ov = vectorPreview?.takeIf { it.layer === layer } ?: run {
                releaseVectorPreview(keepGuides = true)
                VectorShapePreview(layer).also { vectorPreview = it }
            }
            ov.specs = specs.toList()
            val cur = controller.renderOverride
            if (cur !== ov) {
                ov.inner = cur?.takeIf { it.layer === layer && brushPreview.isLive }
                controller.renderOverride = ov
            }
        }
        vectorShown = regions
        invalidateTiles(old)
        invalidateTiles(regions)
    }

    private fun drawSpecs(canvas: Canvas, specs: List<VectorPaintSpec>) {
        val mode = controller.doc.colorMode
        for (s in specs) renderer.draw(canvas, s, false, mode)
    }

    /** Removes the new-shape preview of a vector layer (and its guides, unless [keepGuides]). */
    private fun releaseVectorPreview(keepGuides: Boolean = false) {
        if (!keepGuides) vectorGuides = emptyList()
        val ov = vectorPreview ?: return
        vectorPreview = null
        if (controller.renderOverride === ov) controller.renderOverride = ov.inner?.takeIf { it !== ov && brushPreview.isLive }
        ov.inner = null
        invalidateTiles(vectorShown)
        vectorShown = emptyList()
    }

    /**
     * A NEW shape on a vector layer: the layer's pixels, the shape over them (the topmost object)
     * and the painting tool's live brush stroke ([inner], shown without the layer's pixels a
     * second time).
     */
    private inner class VectorShapePreview(override val layer: Layer) : LayerRenderOverride {
        var specs: List<VectorPaintSpec> = emptyList()
        var inner: LayerRenderOverride? = null

        override fun drawContent(canvas: Canvas): Boolean {
            canvas.drawBitmap(layer.bitmap, 0f, 0f, null)
            drawSpecs(canvas, specs)
            val i = inner
            if (i != null && i !== this && i.layer === layer) {
                val real = layer.bitmap
                layer.bitmap = blank
                try {
                    i.drawContent(canvas)
                } finally {
                    layer.bitmap = real
                }
            }
            return true
        }
    }

    /**
     * The brush outline of a shape on vector [layer] can be the painting tool's live stroke: the
     * stroke paints the active layer's content exactly as the object's replay will (started
     * unclipped by the selection and the alpha lock, see the init block; not a smudge / blur /
     * watercolor tool that moves pixels). v1.7 (I14): not on a layer with a live array either: the
     * live stroke would paint the source alone, while its cache repeats every object.
     */
    private fun liveBrushOnVector(layer: Layer): Boolean {
        if (layer !== controller.doc.activeLayer || !controller.doc.effectiveVisible(layer) || controller.doc.effectiveLocked(layer)) return false
        if (controller.editTargetOf(layer) != EditTarget.CONTENT || layer.array != null) return false
        return !brushMovesPixels()
    }

    /** True when the outline's painting tool moves pixels (smudge, blur, watercolor): not on vector layers. */
    private fun brushMovesPixels(): Boolean {
        val preset = brushPresetInUse() ?: return false
        return StrokeKind.of(brushToolId(), preset).isDirect
    }

    /** Why a shape with such an outline can't become an object of a vector layer. */
    private fun movesPixelsMessage(): String {
        val tool = brushToolId()
        val name = if (tool == ToolId.BRUSH) brushPresetInUse()?.name ?: tool.label else tool.label
        return "$name outlines need a raster layer: choose \"Plain line\" or a painting brush"
    }

    /** True when a NEW shape on vector [layer] would be refused when placed (shown in the options). */
    val outlineNeedsRaster: Boolean
        get() {
            val s = newShapeSettings()
            return drawsOnVectorLayer && s.strokeWith == ShapeStroke.BRUSH && s.strokes && brushMovesPixels()
        }

    /**
     * Opens the topmost shape object of the active vector layer at [p] (not [skip]) for
     * editing; false when there is none.
     */
    private fun openShapeObjectAt(p: Vec2, skip: Long?): Boolean {
        val layer = controller.doc.activeLayer
        if (!isVectorTarget(layer) || !controller.doc.effectiveVisible(layer) || controller.doc.effectiveLocked(layer)) return false
        val content = layer.vector ?: return false
        val tol = controller.docLength(HIT_TOLERANCE_DP)
        for (i in content.objects.indices.reversed()) {
            val o = content.objects[i] as? VShape ?: continue
            if (o.id == skip) continue
            if (ObjectCopies.hits(o.shape, p, tol)) return editShapeObject(layer, o.id)
        }
        return false
    }

    // region v110-A6 open
    /**
     * Opens shape object [id] of vector layer [layer] for editing (v1.5): the layer becomes
     * active, the object is shown through an edit session (the layer with a hole, the edited
     * shape drawn over it), the options and the main color show the shape's own, and ✓ replaces
     * it as ONE undo step "Edit shape" (same id and place); ✕ leaves the layer untouched. Returns
     * false when it can't be opened (the session may also arrive later).
     */
    fun editShapeObject(layer: Layer, id: Long): Boolean {
        val open = vectorSession
        if (open != null && open.layer === layer && vectorEditId == id && box != null) return true
        val doc = controller.doc
        if (doc.indexOf(layer) < 0 || !isVectorTarget(layer)) return false
        if (layer.vector?.byId(id) !is VShape) return false
        if (!controller.checkEditable(layer)) return false
        // Finish the pending shape first.
        if (box != null) {
            commit()
            if (box != null) discard()
        }
        if (doc.indexOf(layer) < 0) return false
        // Selecting pauses this tool (onDeactivate / onActivate); nothing is pending now.
        controller.selectLayer(layer)
        if (controller.doc.activeLayer !== layer) return false
        val token = ++reopenToken
        var answered = false
        var opened = false
        controller.vectors.beginEdit(layer, setOf(id)) { session ->
            answered = true
            if (session == null) return@beginEdit
            val current = layer.vector?.byId(id) as? VShape
            if (token != reopenToken || box != null || current == null || controller.currentTool !== this || controller.doc.activeLayer !== layer) {
                session.cancel()
                return@beginEdit
            }
            startObjectEdit(session, current)
            opened = true
        }
        return opened || !answered
    }
    // endregion

    private fun startObjectEdit(session: VectorEditSession, current: VShape) {
        val shape = current.shape
        brushPreview.end()
        val user = settings
        userSettings = user
        settings = user.showing(shape)
        editBrush = shape.brushToolId?.let { t -> shape.brushPreset?.let { t to it } }?.takeIf { shape.paintsWithBrush }
        vectorSession = session
        editingObject = true
        vectorEditId = current.id
        vectorEditOpacity = current.opacity
        targetLayer = session.layer
        box = shape.box
        points = shape.points
        pointsMode = false
        selectedPoint = -1
        selectSeveral = false
        clearHistory()
        userColor = controller.color
        openedColor = shape.strokeColor
        controller.color = shape.strokeColor
        openCopies = shape.copies
        openPerspective = emptyList()
        loadedObject = objectFor(shape.box, shape.points)
        // The outline is shown with its own texture (its replay's random values).
        brushPreview.useSeed(current.seed)
        refreshPreview()
    }

    /**
     * Frees the floating bitmap of an ended [session] (the edited object rendered alone, up to
     * 2048 px: the shape tool draws the shape itself and never uses it; its holder frees it).
     */
    private fun recycleFloating(session: VectorEditSession) {
        val f = session.floating ?: return
        if (!session.isOpen && !f.isRecycled) f.recycle()
    }

    /** Stops editing the shape object (no change): the session ends, the user's options come back. */
    private fun endObjectEdit() {
        val s = vectorSession
        vectorSession = null
        editingObject = false
        if (s != null) {
            s.inner = null
            s.drawPreview = null
            // (After a commit the session already ended: its floating preview is freed when the
            // change lands, see commitVectorEdit.)
            if (s.isOpen) {
                s.cancel()
                recycleFloating(s)
            }
        }
        invalidateTiles(vectorShown)
        vectorShown = emptyList()
        vectorGuides = emptyList()
        loadedObject = null
        editBrush = null
        openCopies = CopyMaps.NONE
        restoreUserOptions()
        controller.invalidateOverlay()
    }

    /**
     * Places the pending NEW shape as a shape object on top of vector [layer], ONE undo step
     * "Shape". A brush outline shown live is kept as it is on screen (its pixels are the object's
     * replay: same samples, brush and random values) and the object is added as data; otherwise
     * the object is rendered into the layer. A shape outside the canvas stays pending.
     */
    private fun commitVectorNew(layer: Layer, b: ShapeBox) {
        val doc = controller.doc
        if (doc.indexOf(layer) < 0) { discard(); return }
        if (!controller.checkEditable(layer)) return
        val o = objectFor(b, points)
        if (o.paintsWithBrush && brushMovesPixels()) { controller.toast(movesPixelsMessage()); return }
        val area = paintRect(o)
        if (area == null || !area.intersect(0, 0, doc.width, doc.height)) {
            controller.toast("The shape is outside the canvas")
            return
        }
        val contours = if (o.paintsWithBrush) ShapeOutlines.brushContours(o) else emptyList()
        // v1.8 (§3.1): affine copies stay the object's (o.copies); Perspective array copies are
        // baked into plain paths, added with it in the same step.
        val copies = openCopies
        val perspective = openPerspective
        // Kept live only when nothing plain goes under the stroke (a fill must be drawn first).
        // v1.8 (D21): and only with one contour (a cog with a hole is rendered from the object:
        // the live stroke is its teeth alone); v1.8: and without copies (rendered from the object).
        val livePath = contours.singleOrNull()?.takeIf {
            copies.isEmpty && perspective.isEmpty() && ShapeOutlines.paintSpec(o, brush = true) == null && liveBrushOnVector(layer)
        }
        val shape = VShape(0, shape = o, seed = brushPreview.sessionSeed)
        val objects = if (perspective.isEmpty()) listOf(shape) else ObjectCopies.baked(shape, perspective, ObjectCopies.bakeCanvas(doc, layer))
        val pendingPoints = points
        val pendingTarget = targetLayer
        var ids: List<Long> = emptyList()
        inCommit = true
        try {
            if (livePath != null) {
                // The live stroke stays on screen until it is committed: only this preview goes.
                val ov = vectorPreview
                vectorPreview = null
                vectorGuides = emptyList()
                if (ov != null && controller.renderOverride === ov) controller.renderOverride = ov.inner
                vectorShown = emptyList()
                resetPending()
                var painted = false
                controller.undoStepNamed(SHAPE_LABEL) {
                    val um = controller.undoManager
                    val mark = um.undoCount
                    controller.keepLayerData(layer) { brushPreview.commit(livePath.ops) { brushStrokeInput(livePath, out = it) } }
                    // The data goes with the pixels only (I1): a stroke that left nothing on the
                    // layer is rendered from the object below instead.
                    painted = um.undoCount > mark
                    if (painted) ids = controller.vectors.appendData(layer, listOf(shape), SHAPE_LABEL)
                }
                if (!painted) ids = controller.vectors.addObjects(layer, listOf(shape), SHAPE_LABEL)
            } else {
                brushPreview.cancel()
                releaseVectorPreview()
                resetPending()
                // (Perspective: the shape and its baked copies, ONE step.)
                ids = controller.vectors.addObjects(layer, objects, SHAPE_LABEL)
            }
        } catch (e: OutOfMemoryError) {
            controller.toast("Not enough memory for the shape")
        } finally {
            inCommit = false
        }
        brushPreview.end()
        if (ids.isEmpty()) {
            // Not placed (refused, memory): the shape stays pending.
            box = b
            points = pendingPoints
            targetLayer = pendingTarget
            openCopies = copies
            openPerspective = perspective
            refreshPreview()
        } else {
            lastVectorId = ids.first()
            if (perspective.isNotEmpty()) bakedHintShown = hintOnce(MirrorLabels18.PERSPECTIVE_BAKED, bakedHintShown)
        }
        controller.invalidateOverlay()
    }

    /**
     * Replaces the opened shape object by the pending shape as ONE undo step "Edit shape" (same
     * id and z position; the layer is re-rendered there). Nothing is recorded when nothing
     * changed; ✕ ([discard]) leaves the layer exactly as it was.
     */
    private fun commitVectorEdit(session: VectorEditSession, b: ShapeBox) {
        val layer = session.layer
        if (controller.doc.indexOf(layer) < 0 || !session.isOpen) { discard(); return }
        val o = objectFor(b, points)
        if (o == loadedObject) { lastVectorId = vectorEditId; discard(); return }
        if (!controller.checkEditable(layer)) return
        if (o.paintsWithBrush && brushMovesPixels()) { controller.toast(movesPixelsMessage()); return }
        val shape = VShape(vectorEditId, opacity = vectorEditOpacity, shape = o, seed = brushPreview.sessionSeed)
        lastVectorId = vectorEditId
        inCommit = true
        try {
            brushPreview.cancel()
            // The live stroke is gone: the session must not hand it back when it ends.
            session.inner = null
            session.drawPreview = null
            session.commit(listOf(shape), EDIT_SHAPE_LABEL) { ok ->
                // The session's floating preview is not used here: freed once the change landed.
                recycleFloating(session)
                if (!ok) controller.toast("The shape couldn't be updated")
            }
        } catch (e: OutOfMemoryError) {
            controller.toast("Not enough memory to update the shape")
        } finally {
            inCommit = false
        }
        resetPending()
        brushPreview.end()
        endObjectEdit()
    }

    // ------------------------------------------------------------------ editing shape layers

    /**
     * Draws the shape layer being edited: its pixels are hidden and the edited shape is drawn in
     * their place THROUGH THE COMPOSITOR, so the layer's order, opacity, blend mode, mask and the
     * layers clipped to it look exactly like the result. The painting tool's live stroke of a
     * brush outline ([inner], its own override) is drawn over the fill, without the layer's old
     * pixels. v1.7 (I14): on a layer with a live array the edited shape is drawn once per copy
     * ([ArrayDraw.drawWithArray], placed from [arraySource], the edited shape's bounds as
     * `ArrayDraw.sourceBounds` measures shape data), so the copies follow the edit; the live
     * brush stroke is shown on the source alone until ✓ paints it on every copy.
     */
    private inner class EditOverride(override val layer: Layer) : LayerRenderOverride {
        var specs: List<VectorPaintSpec> = emptyList()
        var regions: List<Rect> = emptyList()
        var inner: LayerRenderOverride? = null
        var arraySource: RectF? = null

        override fun drawContent(canvas: Canvas): Boolean {
            val mode = controller.doc.colorMode
            val array = layer.array
            val source = arraySource
            if (array != null && source != null) {
                ArrayDraw.drawWithArray(canvas, array, source) { c -> for (s in specs) renderer.draw(c, s, false, mode) }
            } else {
                for (s in specs) renderer.draw(canvas, s, false, mode)
            }
            val i = inner
            if (i != null && i !== this) {
                // The painting tool's override draws the layer's bitmap under its stroke: for
                // this one call (main thread) the layer shows an empty bitmap instead.
                val real = layer.bitmap
                layer.bitmap = blank
                try {
                    i.drawContent(canvas)
                } finally {
                    layer.bitmap = real
                }
            }
            return true
        }
    }

    /** A transparent stand-in for a layer's pixels (see [EditOverride]). */
    private val blank: Bitmap by lazy { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }

    /** The edit override of [layer], installed as the controller's render override. */
    private fun installEditOverride(layer: Layer): EditOverride {
        val ov = editOverride?.takeIf { it.layer === layer } ?: EditOverride(layer).also { editOverride = it }
        val cur = controller.renderOverride
        if (cur !== ov) {
            ov.inner = if (cur != null && cur.layer === layer && brushPreview.isLive) cur else null
            controller.renderOverride = ov
            // First shown (or shown again after something replaced it): the old pixels and the
            // shape need a redraw.
            loadedInk?.let { controller.tiles.invalidate(it) }
            invalidateTiles(ov.regions)
        }
        return ov
    }

    /** Shows [specs] in [ov]; [arraySource]: the edited shape's bounds when its layer has a live array (see [EditOverride]). */
    private fun setEditSpecs(ov: EditOverride, specs: List<VectorPaintSpec>, arraySource: RectF? = null) {
        val old = ov.regions
        val regions = ArrayList<Rect>()
        for (s in specs) regions += s.regions
        val array = ov.layer.array
        if (array != null && arraySource != null && specs.isNotEmpty() && !arraySource.isEmpty) {
            // Every copy's tiles are redrawn too.
            val all = ArrayInstances.bounds(array.spec, arraySource)
            if (all.left.isFinite() && all.top.isFinite() && all.right.isFinite() && all.bottom.isFinite()) {
                regions += Rect().also { r -> all.roundOut(r); r.inset(-2, -2) }
            }
        }
        ov.specs = specs
        ov.arraySource = arraySource
        ov.regions = regions
        invalidateTiles(old)
        invalidateTiles(regions)
    }

    private fun invalidateTiles(rects: List<Rect>) {
        if (rects.isEmpty()) return
        val tiles = controller.tiles
        for (r in rects) tiles.invalidate(r)
        controller.invalidateOverlay()
    }

    /** The live brush stroke started or ended: it is shown inside the edit override. */
    private fun onBrushLiveChanged() {
        // Committing paints the stroke into the layer for real: the edit override is going away.
        if (inCommit) return
        // A shape object of a vector layer: its edit session shows the live stroke inside it.
        val session = vectorSession
        if (session != null) {
            if (brushPreview.isLive) {
                session.adoptInner()
            } else {
                session.inner = null
                if (controller.renderOverride == null) session.adoptInner()
            }
            return
        }
        val vp = vectorPreview
        if (vp != null) {
            val cur = controller.renderOverride
            if (brushPreview.isLive) {
                if (cur !== vp) {
                    vp.inner = cur?.takeIf { it.layer === vp.layer }
                    controller.renderOverride = vp
                }
            } else {
                vp.inner = null
                if (cur == null) controller.renderOverride = vp
            }
            controller.invalidateDoc(null)
            return
        }
        val ov = editOverride ?: return
        if (editingLayer !== ov.layer) return
        val cur = controller.renderOverride
        if (brushPreview.isLive) {
            if (cur !== ov) {
                ov.inner = if (cur != null && cur.layer === ov.layer) cur else null
                controller.renderOverride = ov
            } else {
                ov.inner = null
            }
        } else {
            ov.inner = null
            if (cur == null) controller.renderOverride = ov
        }
    }

    init {
        brushPreview.onLiveChanged = { onBrushLiveChanged() }
        // The brush outline of a shape object on a vector layer is neither clipped by the
        // selection nor by alpha lock (objects aren't): its live pixels are then exactly the
        // object's replay, also while a selection is active (A4's BrushStrokePreview.unclipped).
        brushPreview.unclipped = { vectorLayerOfPending() != null }
    }

    /**
     * Opens the shape of shape layer [layer] for editing: the layer becomes active, its pixels
     * are hidden while it is edited, the options and the main color show the shape's own, and
     * ✓ re-renders it as one undo step. Returns false (with a message) when it can't be edited.
     */
    fun editLayer(layer: Layer): Boolean {
        if (editingLayer === layer && box != null) return true
        val doc = controller.doc
        if (doc.indexOf(layer) < 0) return false
        val obj = decoded(layer)
        if (obj == null) {
            controller.toast("\"${layer.name}\" is not an editable shape layer")
            return false
        }
        if (!controller.checkEditable(layer)) return false
        // v1.9 (R11): opening a shape to edit it ends erase mode (its pending erase lands).
        if (eraseOn) setErase(false)
        // Finish the pending shape first (placing it may add a layer).
        if (box != null) {
            commit()
            if (box != null) discard()
        }
        if (doc.indexOf(layer) < 0) return false
        // Selecting pauses this tool (onDeactivate / onActivate); nothing is pending now.
        controller.selectLayer(layer)
        if (controller.doc.activeLayer !== layer) return false
        val user = settings
        userSettings = user
        settings = user.showing(obj)
        editBrush = obj.brushToolId?.let { t -> obj.brushPreset?.let { t to it } }?.takeIf { obj.paintsWithBrush }
        editingLayer = layer
        targetLayer = layer
        box = obj.box
        points = obj.points
        pointsMode = false
        selectedPoint = -1
        selectSeveral = false
        clearHistory()
        // The main color shows the shape's stroke color (changing it recolors the shape); the
        // user's own comes back when the shape closes, unless they picked another one meanwhile.
        userColor = controller.color
        openedColor = obj.strokeColor
        controller.color = obj.strokeColor
        openCopies = obj.copies
        openPerspective = emptyList()
        loadedObject = objectFor(obj.box, obj.points)
        loadedInk = inkOf(layer, obj)
        // v1.7 (I14): the hidden pixels include every copy of a live array.
        ArrayDraw.cacheBounds(layer.dataSnapshot())?.takeIf { !it.isEmpty }?.let { b ->
            val r = Rect().also { b.roundOut(it); it.inset(-1, -1) }
            loadedInk = loadedInk?.apply { union(r) } ?: r
        }
        refreshPreview()
        return true
    }

    /** Topmost visible, unlocked shape layer whose shape is at [p] (the active layer first), or null. */
    fun shapeLayerAt(p: Vec2, skip: Layer? = null): Layer? {
        val doc = controller.doc
        val tol = controller.docLength(HIT_TOLERANCE_DP)
        val active = doc.activeLayer
        fun hits(l: Layer): Boolean {
            if (l === skip || !l.isShapeLayer || !doc.effectiveVisible(l) || doc.effectiveLocked(l)) return false
            val o = decoded(l) ?: return false
            return ObjectCopies.hits(o, p, tol)
        }
        if (hits(active)) return active
        for (i in doc.layers.indices.reversed()) {
            val l = doc.layers[i]
            if (l !== active && hits(l)) return l
        }
        return null
    }

    /** The shape stored in [layer] (cached while its data is unchanged). */
    private fun decoded(layer: Layer): ShapeObject? {
        val data = layer.shapeData ?: return null
        decodedLayers[layer.id]?.let { (d, o) -> if (d == data) return o }
        val o = ShapeCodec.decode(data)
        if (decodedLayers.size >= LAYER_CACHE_SIZE) decodedLayers.clear()
        decodedLayers[layer.id] = data to o
        return o
    }

    /** The points of shape layer [layer] other things snap to (see [ShapeOutlines.featurePoints]). */
    private fun layerFeatures(layer: Layer): List<SnapLine> {
        val data = layer.shapeData ?: return emptyList()
        featureCache[layer.id]?.let { (d, name, lines) -> if (d == data && name == layer.name) return lines }
        val o = decoded(layer)
        val label = "${layer.name} point"
        val lines = o?.let { ShapeOutlines.featurePoints(it).flatMap { p -> SnapLine.point(p, label) } } ?: emptyList()
        if (featureCache.size >= LAYER_CACHE_SIZE) featureCache.clear()
        featureCache[layer.id] = Triple(data, layer.name, lines)
        return lines
    }

    /**
     * Document rect of everything [o] paints (plain parts and the brush's reach), or null; v1.8
     * (I17): every live copy's too.
     */
    private fun paintRect(o: ShapeObject): Rect? {
        if (!o.copies.isEmpty) {
            val src = paintRect(ObjectCopies.source(o)) ?: return null
            val all = ObjectCopies.mappedBounds(RectF(src), o.copies)
            return Rect().also { all.roundOut(it); it.inset(-1, -1) }.takeUnless { it.isEmpty }
        }
        val r = Rect()
        val tmp = Rect()
        ShapeOutlines.paintSpec(o, brush = false)?.let { it.boundsRect(tmp); r.union(tmp) }
        if (o.paintsWithBrush) {
            ShapeOutlines.brushOutline(o).controlBounds()?.let { b ->
                val reach = (o.brushPreset?.size ?: o.strokeWidth) / 2f + 2f
                Rect(
                    kotlin.math.floor(b.left - reach).toInt(), kotlin.math.floor(b.top - reach).toInt(),
                    kotlin.math.ceil(b.right + reach).toInt(), kotlin.math.ceil(b.bottom + reach).toInt(),
                ).let { r.union(it) }
            }
        }
        return r.takeUnless { it.isEmpty }
    }

    /**
     * Where the pixels of shape layer [layer] (drawn from [o]) really are: the area around the
     * computed bounds is scanned, or the whole layer when the ink reaches its edge.
     */
    private fun inkOf(layer: Layer, o: ShapeObject): Rect? {
        val guess = paintRect(o)
        return try {
            if (guess != null) {
                val margin = max(32, max(guess.width(), guess.height()) / 8)
                val region = Rect(guess).apply { inset(-margin, -margin) }
                if (region.intersect(0, 0, layer.width, layer.height)) {
                    val ink = ContentBounds.of(layer.bitmap, region = region)
                    val atEdge = ink != null && (
                        (ink.left <= region.left && region.left > 0) || (ink.top <= region.top && region.top > 0) ||
                            (ink.right >= region.right && region.right < layer.width) || (ink.bottom >= region.bottom && region.bottom < layer.height)
                        )
                    if (!atEdge) return ink ?: guess
                }
            }
            ContentBounds.of(layer.bitmap) ?: guess
        } catch (e: OutOfMemoryError) {
            guess
        }
    }

    /** Stops editing the shape layer (no pixel change): shows its pixels again, the user's options come back. */
    private fun endLayerEdit() {
        val ov = editOverride
        editOverride = null
        if (ov != null) {
            ov.inner = null
            if (controller.renderOverride === ov) controller.renderOverride = null
            invalidateTiles(ov.regions)
        }
        loadedInk?.let { controller.tiles.invalidate(it) }
        loadedInk = null
        loadedObject = null
        editingLayer = null
        editBrush = null
        openCopies = CopyMaps.NONE
        editGuide = null
        restoreUserOptions()
        controller.invalidateOverlay()
    }

    /** The user's own options and main color come back after an opened shape closes. */
    private fun restoreUserOptions() {
        userSettings?.let { u -> settings = u.withBehaviourOf(settings) }
        userSettings = null
        val uc = userColor
        userColor = null
        if (uc != null && controller.color == openedColor) controller.color = uc
    }

    // ------------------------------------------------------------------ commit / discard

    /** Clears the pending shape (not the edited layer's state; the brush preview is left to the caller). */
    private fun resetPending() {
        box = null
        points = null
        pointsMode = false
        selectedPoint = -1
        selectSeveral = false
        groupBase = null
        groupPinchPivot = null
        creatingBox = null
        creatingMaps = emptyList()
        // v1.8: the copies go with the shape (a commit that is refused puts them back).
        openCopies = CopyMaps.NONE
        openPerspective = emptyList()
        targetLayer = null
        pinchStart = null
        overlaySpecs = emptyList()
        brushGuide = null
        vectorGuides = emptyList()
        preview.release()
        preview.interacting = false
        // v1.9 (R11): an erase still open goes with its outline.
        endErase()
        clearHistory()
    }

    override fun commit() {
        val b = box ?: return
        if (eraseOn) {
            commitErase()
            return
        }
        val session = vectorSession
        if (session != null) {
            commitVectorEdit(session, b)
            return
        }
        val editLayer = editingLayer
        if (editLayer != null) {
            commitLayerEdit(editLayer, b)
            return
        }
        val layer = targetLayer ?: controller.doc.activeLayer
        if (isVectorTarget(layer)) {
            commitVectorNew(layer, b)
            return
        }
        // v1.9 (R14, S1): into the selected shape layer, which becomes a vector layer.
        joinTarget(settings)?.let {
            commitJoin(it, b)
            return
        }
        if (placesInNewLayer(settings)) {
            commitNewLayer(b)
            return
        }
        if (controller.doc.indexOf(layer) < 0) { discard(); return }
        if (!controller.checkEditable(layer)) return
        val o = objectFor(b, points)
        val brush = paintsWithBrush(layer)
        // v1.8 (§3.1): the copies are painted in the same step: the plain items as one union
        // (translucent copies never darken each other), the brush as one symmetric stroke.
        val maps = pendingMaps()
        val spec = ShapeCopies.unionSpec(o, maps, brush)
        // v1.8 (D21): one stroke per contour (a cog with a hole: the teeth, then the hole).
        val contours = if (brush) ShapeOutlines.brushContours(o) else emptyList()
        // Perspective copies with "Editable (own layer)" on: the shape can't stay editable.
        val asPixels = openPerspective.isNotEmpty() && placesInNewLayer(settings, perspective = false)
        resetPending()
        // Fill (plain) and outline (brush) are ONE undo step, named "Shape".
        controller.undoStepNamed("Shape") {
            if (spec != null) {
                // The fill goes under the brush stroke: a live stroke is restarted after it.
                brushPreview.cancel()
                VectorCommit.commit(controller, layer, listOf(spec), "Shape")
            }
            commitContours(contours, maps)
        }
        brushPreview.end()
        if (asPixels) pixelsHintShown = hintOnce(MirrorLabels18.PERSPECTIVE_PIXELS, pixelsHintShown)
        controller.invalidateOverlay()
    }

    /**
     * Places the pending NEW shape into a new layer of its own above the active one (named after
     * its type), keeping the shape so it can be edited again: the layer, its fill and its brush
     * outline are ONE undo step "Shape". A shape outside the canvas (or the selection) stays
     * pending with a message.
     */
    private fun commitNewLayer(b: ShapeBox) {
        val o = objectFor(b, points)
        val brush = o.paintsWithBrush
        // v1.8 (I15): the layer keeps the shape's copies (o.copies) and shows them: one item per
        // copy, as the layer is drawn again later; the brush as one symmetric stroke.
        val copies = openCopies
        val perspective = openPerspective
        val maps = o.copies.maps
        val specs = ShapeCopies.eachSpecs(o, maps, brush)
        val contours = if (brush) ShapeOutlines.brushContours(o) else emptyList()
        val doc = controller.doc
        val area = paintRect(o)
        if (area == null || !area.intersect(0, 0, doc.width, doc.height)) {
            controller.toast("The shape is outside the canvas")
            return
        }
        val sel = controller.selection
        if (sel != null && !Rect.intersects(area, sel.bounds)) {
            controller.toast("The shape is outside the selection")
            return
        }
        val data = ShapeCodec.encode(o)
        val pendingPoints = points
        val pendingTarget = targetLayer
        // Adding the layer pauses this tool: nothing may be pending then.
        resetPending()
        var added: Layer? = null
        inCommit = true
        try {
            controller.undoStepNamed("Shape") {
                brushPreview.cancel()
                val layer = controller.addLayerWithContent(o.type.label, "Shape", shapeData = data) { c ->
                    for (s in specs) drawSpec(c, s, sel)
                }
                added = layer
                // The brush paints the active layer: the new one.
                if (layer != null && contours.isNotEmpty()) controller.keepLayerData(layer) {
                    commitContours(contours, maps)
                }
            }
        } catch (e: OutOfMemoryError) {
            controller.toast("Not enough memory for another layer")
        } finally {
            inCommit = false
        }
        brushPreview.end()
        if (added == null) {
            // Not placed (layer limit, memory): the shape stays pending.
            box = b
            points = pendingPoints
            targetLayer = pendingTarget
            openCopies = copies
            openPerspective = perspective
            refreshPreview()
        }
        controller.invalidateOverlay()
    }

    // ------------------------------------------------------------------ joining a shape layer (v1.9, R14 S1)

    /**
     * v1.9 (R14, S1): the legacy shape layer a NEW shape joins, or null (v1.8: a layer of its
     * own). With "Editable (own layer)" on and [ShapeSettings.newLayerEachTime] off, a new shape
     * that would get a layer of its own joins the selected shape layer instead: the layer becomes
     * a vector layer in place ([LayerConvert]) and the shape one of its objects, in the same step.
     * Not into a layer that may not be edited (locked or hidden, also through a folder; its mask
     * edited), nor with Perspective copies or an outline whose brush moves pixels: those place
     * the shape as in v1.8. Without [decode] the layer's shape data is not decoded (the strip asks
     * every drag frame, [objectActionsApply]); a layer whose data does not decode is then taken.
     */
    private fun joinTarget(s: ShapeSettings = newShapeSettings(), decode: Boolean = true): Layer? {
        if (s.newLayerEachTime || editingLayer != null || vectorSession != null) return null
        if (!placesInNewLayer(s)) return null
        val doc = controller.doc
        val layer = targetLayer ?: doc.activeLayer
        if (!layer.isShapeLayer || layer.isVectorLayer || doc.indexOf(layer) < 0) return null
        if (controller.editTargetOf(layer) != EditTarget.CONTENT) return null
        if (layer.isFolder || !doc.effectiveVisible(layer) || doc.effectiveLocked(layer)) return null
        val b = box ?: return null
        if (objectFor(b, points).paintsWithBrush && brushMovesPixels()) return null
        if (decode && LayerConvert.shapeToVector(layer) == null) return null
        return layer
    }

    /**
     * S1: the pending new shape joins [layer] (see [joinTarget]): the conversion and the new
     * object are ONE step [SHAPE_LABEL] (rendered at once: no render may land as a step of its
     * own). A shape outside the canvas stays pending and nothing converts.
     */
    private fun commitJoin(layer: Layer, b: ShapeBox) {
        val o = objectFor(b, points)
        val doc = controller.doc
        val area = paintRect(o)
        if (area == null || !area.intersect(0, 0, doc.width, doc.height)) {
            controller.toast("The shape is outside the canvas")
            return
        }
        val vectors = controller.vectors
        val policy = vectors.policy
        vectors.policy = VectorLayers.Policy.SYNC
        try {
            controller.undoStepNamed(SHAPE_LABEL) {
                LayerConvert.toVectorAction(controller, layer, label = SHAPE_LABEL)?.let { controller.pushUndo(it) }
                commitVectorNew(layer, b)
            }
        } finally {
            vectors.policy = policy
        }
    }

    // ------------------------------------------------------------------ object actions (v1.9, R14)

    /**
     * v1.9 (R14): the More row's object items apply ([LayerLabels19.DUPLICATE],
     * [ObjectEdits.Arrange.FORWARD], [ObjectEdits.Arrange.BACKWARD]): a shape is open outside
     * Points mode and is, or once placed becomes, a shape object of a vector layer: a reopened
     * shape object, a new shape on a vector layer, or a new shape that joins the selected shape
     * layer ([joinTarget]). Not for a reopened legacy shape layer (its one shape has no objects
     * beside it) nor for a shape painted or placed in a layer of its own. Compose state; cheap
     * (the strip asks every drag frame).
     */
    val objectActionsApply: Boolean get() = objectActionsApply(decode = false)

    private fun objectActionsApply(decode: Boolean): Boolean {
        if (box == null || pointsMode || eraseOn) return false
        if (editingObject) return true
        if (editingLayer != null) return false
        controller.layersVersion
        val layer = targetLayer ?: controller.doc.activeLayer
        return isVectorTarget(layer) || joinTarget(settings, decode) != null
    }

    /**
     * v1.9 (R14): the More row's [LayerLabels19.DUPLICATE]. The open shape is placed (a new one:
     * its own step [SHAPE_LABEL]; a reopened one: its edit, nothing for an untouched one), the
     * object bar's [ObjectActions.duplicate] copies it (its own step), and the copy opens. False
     * when nothing was copied: the placed shape opens again (or stays pending when it could not
     * be placed, with the message).
     */
    fun duplicateOpen(): Boolean = actOnPlaced { ObjectActions.duplicate(controller) }

    /**
     * v1.9 (R14): the More row's [ObjectEdits.Arrange.FORWARD] / [ObjectEdits.Arrange.BACKWARD]:
     * as [duplicateOpen], through the object bar's [ObjectActions.arrange] (past the next object
     * it overlaps, one step; "already" with nothing to pass); the shape opens again.
     */
    fun arrangeOpen(how: ObjectEdits.Arrange): Boolean = actOnPlaced { ObjectActions.arrange(controller, how) }

    /**
     * Places the open shape, runs the object bar's [action] on it alone (selected for the call),
     * and opens the object it leaves selected (the copy) or the shape itself. Renders land at once
     * meanwhile (as [commitJoin]): the object acted on and the one opened are known now.
     */
    private fun actOnPlaced(action: () -> Boolean): Boolean {
        if (!objectActionsApply(decode = true)) return false
        val vectors = controller.vectors
        val policy = vectors.policy
        vectors.policy = VectorLayers.Policy.SYNC
        try {
            val layer = vectorSession?.layer ?: (targetLayer ?: controller.doc.activeLayer)
            lastVectorId = null
            commit()
            vectors.flushPending()
            val id = lastVectorId
            // Refused (locked, outside the canvas...): the shape stays pending, with the message.
            if (box != null || id == null || layer.vector?.byId(id) !is VShape) return false
            if (controller.doc.activeLayer !== layer) controller.selectLayer(layer)
            var done = false
            if (controller.doc.activeLayer === layer) {
                if (PendingRenders.busy(controller)) {
                    controller.toast(ObjectActions.STILL_UPDATING)
                } else {
                    vectors.setSelection(layer, setOf(id))
                    done = action()
                }
            }
            val left = vectors.selectedIds.singleOrNull()?.takeIf { done && vectors.selectedLayer === layer }
            vectors.setSelection(null, emptySet())
            val open = left?.takeIf { layer.vector?.byId(it) is VShape } ?: id
            if (controller.doc.activeLayer === layer) editShapeObject(layer, open)
            controller.invalidateOverlay()
            return done
        } finally {
            vectors.policy = policy
        }
    }

    /** Draws [spec] into a new layer's canvas, limited to [sel] (in bounded tiles). */
    private fun drawSpec(c: Canvas, spec: VectorPaintSpec, sel: Selection?) {
        val mode = controller.doc.colorMode
        if (sel == null) {
            renderer.draw(c, spec, false, mode)
            return
        }
        val r = spec.boundsRect()
        if (!r.intersect(sel.bounds)) return
        val tile = Rect()
        var y = r.top
        while (y < r.bottom) {
            var x = r.left
            while (x < r.right) {
                tile.set(x, y, min(x + DRAW_TILE, r.right), min(y + DRAW_TILE, r.bottom))
                if (spec.regions.any { Rect.intersects(it, tile) }) {
                    c.save()
                    c.clipRect(tile)
                    renderer.drawClipped(c, spec, sel, false, tile, false, mode)
                    c.restore()
                }
                x += DRAW_TILE
            }
            y += DRAW_TILE
        }
    }

    /**
     * Re-renders the edited shape layer [layer] with the pending shape [b] as ONE undo step
     * "Edit shape" (pixels and shape data; a brush outline is replayed with the shape's own
     * brush in the same step). Nothing is recorded when nothing changed. A refusal (layer
     * locked or hidden meanwhile) keeps the shape open. v1.7 (I14): with a live array every copy
     * gets the outline too ([outlineReplay] inside the draw that `updateShapeLayer` repeats),
     * except an outline whose tool moves pixels (smudge, blur, watercolor), which is painted on
     * the source alone, as in v1.6.
     */
    private fun commitLayerEdit(layer: Layer, b: ShapeBox) {
        val doc = controller.doc
        if (doc.indexOf(layer) < 0) { discard(); return }
        val o = objectFor(b, points)
        val loaded = loadedObject
        if (o == loaded) { discard(); return }
        if (!controller.checkEditable(layer)) return
        val brush = o.paintsWithBrush
        // v1.8 (I17): the fill and plain line are drawn once per live copy (none: once, as in v1.7).
        val copies = o.copies
        val spec = ShapeOutlines.paintSpec(ObjectCopies.source(o), brush)
        // v1.8 (D21): one stroke per contour (a cog with a hole: the teeth, then the hole).
        val contours = if (brush) ShapeOutlines.brushContours(o) else emptyList()
        val path = contours.firstOrNull()
        // Everything the old shape covered (its real pixels) plus the new shape.
        val dirty = Rect()
        loadedInk?.let { dirty.union(it) }
        loaded?.let { paintRect(it) }?.let { dirty.union(it) }
        paintRect(o)?.let { dirty.union(it) }
        dirty.inset(-2, -2)
        val data = ShapeCodec.encode(o)
        val ov = editOverride
        // The new pixels show from now on (the brush stroke is painted again from scratch, after
        // the fill it goes over).
        brushPreview.cancel()
        if (ov != null) {
            ov.inner = null
            if (controller.renderOverride === ov) controller.renderOverride = null
        }
        // v1.7 (I14): on a layer with a live array the outline is replayed inside the draw, which
        // updateShapeLayer repeats for every copy (the painting tool's stroke would reach the
        // source alone). Without an array it is painted exactly as in v1.6, below, and so is an
        // outline whose tool moves pixels (smudge, blur, watercolor: a replay can't paint it).
        // v1.8 (I17): live copies are replayed there too (into the one stroke buffer, as the vector
        // renderer replays them).
        val perCopy = path != null && (layer.array != null || !copies.isEmpty) && !brushMovesPixels()
        val replay = if (perCopy) outlineReplay(o, contours, brushPreview.sessionSeed) else null
        var done = false
        inCommit = true
        val maskEditing = layer.editingMask
        val alphaLocked = layer.alphaLocked
        try {
            controller.undoStepNamed("Edit shape") {
                done = controller.updateShapeLayer(layer, data, "Edit shape", dirty) { c ->
                    spec?.let { s -> ObjectCopies.drawEach(c, copies) { cc -> renderer.draw(cc, s, false, doc.colorMode) } }
                    replay?.invoke(c)
                }
                if (done && path != null && !perCopy) {
                    // The outline is painted on the layer's pixels, never into its mask, and the
                    // shape is drawn again whole, from scratch, like its fill: neither alpha lock
                    // (which would keep the just cleared outline empty) nor a selection (which
                    // would cut it) applies.
                    layer.editingMask = false
                    layer.alphaLocked = false
                    val sel = controller.selection
                    if (sel != null) controller.setSelection(null, recordUndo = false)
                    try {
                        // v1.8 (I17): with live copies, one symmetric stroke (a smudge or blur moves
                        // the pixels under every copy; BrushTool.beginPath's maps).
                        controller.keepLayerData(layer) {
                            commitContours(contours, copies.maps)
                        }
                    } finally {
                        layer.editingMask = maskEditing
                        layer.alphaLocked = alphaLocked
                        if (sel != null) controller.setSelection(sel, recordUndo = false)
                    }
                }
            }
        } catch (e: OutOfMemoryError) {
            controller.toast("Not enough memory to update the shape")
        } finally {
            inCommit = false
        }
        if (!done) {
            // Not applied: keep editing (the shape is shown again).
            refreshPreview()
            return
        }
        resetPending()
        brushPreview.end()
        endLayerEdit()
        controller.invalidateOverlay()
    }

    /**
     * v1.7 (I14): [o]'s brush outline [path] as a draw that can be repeated (an array's copies):
     * replayed with the shape's own brush and [seed] as the vector renderer replays a shape
     * object's outline (`VectorLayerRenderer`, a [VShape]: the same samples, the last point once
     * more as the live stroke's lift). Dabs are clipped and cut at the document united with the
     * stroke's full reach (`StrokeRaster.strokeBounds`, scatter included), in source coordinates:
     * a stroke within the document is cut exactly as the live stroke cuts it (at the document),
     * and a copy is never cut where its source lies outside the document. Null when the outline
     * has fewer than two samples. v1.8 (D21): one stroke per contour of [contours] (a cog with a
     * hole: the teeth with [seed], the hole with [seed] + 1, as the vector renderer seeds the
     * sub-paths of a path).
     */
    private fun outlineReplay(o: ShapeObject, contours: List<VectorPath>, seed: Long): ((Canvas) -> Unit)? {
        val strokes = contours.mapNotNull { path ->
            val input = brushStrokeInput(path, out = PathStrokeInput())
            val n = input.size
            if (n < 2) return@mapNotNull null
            val xs = FloatArray(n + 1); val ys = FloatArray(n + 1); val ps = FloatArray(n + 1)
            input.x.copyInto(xs, 0, 0, n); input.y.copyInto(ys, 0, 0, n); input.pressure.copyInto(ps, 0, 0, n)
            xs[n] = xs[n - 1]; ys[n] = ys[n - 1]; ps[n] = ps[n - 1]
            PackedPoints(xs, ys, ps)
        }
        if (strokes.isEmpty()) return null
        val preset = VectorOps.brushPresetOf(o)
        val color = o.strokeColor
        val doc = controller.doc
        val area = Rect(0, 0, doc.width, doc.height)
        for (points in strokes) {
            // (v1.8: with live copies, the reach of every copy.)
            val reach = ObjectCopies.mappedBounds(StrokeRaster.strokeBounds(preset, 1f, points), o.copies)
            if (!reach.isEmpty && reach.left.isFinite() && reach.top.isFinite() && reach.right.isFinite() && reach.bottom.isFinite()) {
                area.union(Rect().also { reach.roundOut(it) })
            }
        }
        val raster = StrokeRaster(TipCache())
        val maps = o.copies.maps
        return { c ->
            strokes.forEachIndexed { i, points -> raster.render(c, area, preset, color, seed + i, true, points, cut = area, copies = maps) }
        }
    }

    override fun discard() {
        resetPending()
        mode = Mode.NONE
        startBox = null
        snap.end()
        resizeGuides = emptyList()
        brushPreview.end()
        if (editingLayer != null) endLayerEdit()
        if (vectorSession != null) endObjectEdit()
        releaseVectorPreview()
        controller.invalidateOverlay()
    }

    override fun onActivate() {
        if (inCommit) return
        ensureObserving()
    }

    /**
     * The preview depends on state the tool doesn't own (main color, selection, layer props, the
     * painting tool's preset: size slider). Started on activation and again whenever a shape
     * appears, because some controller operations call onDeactivate without a following onActivate.
     */
    private fun ensureObserving() {
        if (observeJob?.isActive == true) return
        observeJob = controller.scope.launch {
            snapshotFlow {
                listOf(controller.color, controller.selection, controller.layersVersion, controller.lastPaintTool, controller.presetFor(controller.lastPaintTool))
            }
                .drop(1)
                .collect { if (box != null && !inCommit) refreshPreview() }
        }
    }

    override fun onSelectionChanged() {
        // (Committing an edited brush shape sets the selection aside for a moment, and so does
        // starting the unclipped live stroke of a shape object's brush outline.)
        if (inCommit || brushPreview.liftingClip) return
        if (box != null || creatingBox != null) refreshPreview()
    }

    override fun onDeactivate() {
        // Adding the committed shape's layer pauses this tool: nothing to finish then.
        if (inCommit) return
        observeJob?.cancel()
        observeJob = null
        if (mode == Mode.CREATE) { creatingBox = null; creatingMaps = emptyList() }
        mode = Mode.NONE
        pinchStart = null
        groupPinchPivot = null
        endGroupGesture()
        // "Select several" ends with the tool (§3.1).
        selectSeveral = false
        snap.end()
        showReadout(null)
        resizeGuides = emptyList()
        if (hasPendingWork) commit()
        if (hasPendingWork) discard()
        // v1.9 (R11): a shape being dragged out in erase mode leaves no erase behind.
        endErase()
        overlaySpecs = emptyList()
        preview.release()
        preview.interacting = false
        brushPreview.end()
        // Never leave an edited layer's pixels hidden.
        if (editingLayer != null) endLayerEdit()
        if (vectorSession != null) endObjectEdit()
        releaseVectorPreview()
        // A reopen request still on its way is dropped.
        reopenToken++
        clearHistory()
    }

    override fun onDispose() {
        endErase()
        brushPreview.end()
        decodedLayers.clear()
        featureCache.clear()
    }

    // ------------------------------------------------------------------ overlay

    private fun map(t: ViewTransform, p: Vec2): FloatArray {
        pts[0] = p.x; pts[1] = p.y
        t.matrix.mapPoints(pts)
        return pts
    }

    /** The outline of the pending shape as an android path (cached while it is unchanged). */
    private fun outlinePathOf(o: ShapeObject): Path {
        if (outlineKey != o) {
            ShapeOutlines.outline(o).toAndroidPath(outlinePath)
            outlineKey = o
        }
        return outlinePath
    }

    override fun drawOverlay(canvas: Canvas, t: ViewTransform) {
        drawShapeOverlay(canvas, t)
        // Smart guides on top of everything.
        if (snap.guides.isNotEmpty()) snap.draw(canvas, t, movingBox())
        else if (resizeGuides.isNotEmpty()) {
            SnapGuideRenderer.draw(canvas, t, resizeGuides, controller.doc.width.toFloat(), controller.doc.height.toFloat(), movingBox())
        }
    }

    /** The pending (or in-creation) shape's box as a snap box (keeps guide labels off it). */
    private fun movingBox(): DocBox? {
        val b = creatingBox ?: box ?: return null
        val bounds = Bounds.of(b.corners()) ?: return null
        return DocBox(bounds.left, bounds.top, bounds.right, bounds.bottom)
    }

    private fun drawShapeOverlay(canvas: Canvas, t: ViewTransform) {
        val creating = creatingBox
        val b = creating ?: box ?: return
        preview.drawOverlay(canvas, t)
        if (overlaySpecs.isNotEmpty()) {
            // Drawn as the content of a new layer / of the edited shape layer (never into a mask).
            val asNew = editingLayer != null || placesInNewLayer()
            specOverlay.draw(canvas, t, controller, targetLayer ?: controller.doc.activeLayer, overlaySpecs, keepBandFree = brushPreview.isLive, asNewLayer = asNew)
        }
        editGuide?.let { if (creating == null) painter.path(canvas, t, it) }
        brushGuide?.let { painter.path(canvas, t, it) }
        for (g in vectorGuides) painter.path(canvas, t, g)
        // v1.9 (R11): the outline erasing (with its copies) is drawn dashed.
        eraseOutline?.let { painter.path(canvas, t, it, dashed = true) }
        val pending = box
        val pts = points
        if (creating == null && pending != null && pointsMode && pts != null) {
            drawPoints(canvas, t, pending, pts)
            return
        }
        if (creating == null && lineHandles) {
            map(t, b.start).let { painter.handle(canvas, t, it[0], it[1]) }
            map(t, b.end).let { painter.handle(canvas, t, it[0], it[1]) }
            return
        }
        if (creating != null && newShapeSettings().type.isLineLike) return
        val c = b.corners()
        boxPath.rewind()
        boxPath.moveTo(c[0].x, c[0].y)
        for (i in 1..3) boxPath.lineTo(c[i].x, c[i].y)
        boxPath.close()
        painter.path(canvas, t, boxPath, dashed = true)
        if (creating != null) return
        val top = map(t, b.toDoc(Vec2(0f, -b.h / 2f))).let { it[0] to it[1] }
        val rot = map(t, rotationHandle(b)).let { it[0] to it[1] }
        painter.line(canvas, t, top.first, top.second, rot.first, rot.second)
        painter.handle(canvas, t, rot.first, rot.second, active = true)
        for (h in visibleHandles(b)) {
            val q = map(t, handlePoint(b, h))
            painter.handle(canvas, t, q[0], q[1], square = h.fx != 0 && h.fy != 0, small = h.fx == 0 || h.fy == 0)
        }
    }

    /**
     * Points mode: the outline, a "+" between points (not while a group is selected or "Select
     * several" is on: no point is inserted then), the one selected point's tangent handles, every
     * point (the selected ones accent-filled), the group gizmo with two or more selected and the
     * marquee being dragged.
     */
    private fun drawPoints(canvas: Canvas, t: ViewTransform, b: ShapeBox, pts: List<ShapePoint>) {
        val o = objectFor(b, pts)
        // (Erasing, the outline is the dashed one drawn before.)
        if (!eraseOn) painter.path(canvas, t, outlinePathOf(o))
        val anchors = ShapePoints.docAnchors(b, pts)
        val closed = closedShape
        val selection = pointSelection.resized(anchors.size)
        if (!selectSeveral && selection.count < 2) {
            for (s in plusSegments(anchors)) {
                val q = map(t, ShapePoints.pointOn(anchors, closed, s, 0.5f))
                painter.plus(canvas, t, q[0], q[1])
            }
        }
        val sel = selectedPoint
        if (selection.isSingle && sel in anchors.indices) {
            val (hIn, hOut) = ShapePoints.handles(anchors, sel, closed)
            val a = map(t, anchors[sel].pos).let { it[0] to it[1] }
            for (h in listOf(hIn, hOut)) {
                if (h.length <= HANDLE_MIN_PX) continue
                val q = map(t, anchors[sel].pos + h).let { it[0] to it[1] }
                painter.line(canvas, t, a.first, a.second, q.first, q.second)
                painter.handle(canvas, t, q.first, q.second, small = true, active = true)
            }
        }
        for (i in anchors.indices) {
            val q = map(t, anchors[i].pos)
            painter.handle(canvas, t, q[0], q[1], square = !anchors[i].smooth, active = i in selection)
        }
        groupLayout(anchors)?.let { gizmo.draw(canvas, it, t, if (mode == Mode.GIZMO) gizmoPart else PointGizmo.Part.NONE) }
        val end = marqueeEnd
        if (mode == Mode.MARQUEE && end != null) {
            // A screen rectangle (the view may be turned): its corners back in the document.
            val a = t.docToScreen(downPoint)
            val c = t.docToScreen(end)
            val corners = listOf(Vec2(a.x, a.y), Vec2(c.x, a.y), Vec2(c.x, c.y), Vec2(a.x, c.y)).map { t.screenToDoc(it) }
            marqueePath.rewind()
            marqueePath.moveTo(corners[0].x, corners[0].y)
            for (k in 1..3) marqueePath.lineTo(corners[k].x, corners[k].y)
            marqueePath.close()
            painter.path(canvas, t, marqueePath, dashed = true)
        }
    }

    companion object {
        /** Undo step of a placed shape. */
        const val SHAPE_LABEL = "Shape"
        /** Undo step of an edited shape (layer or object). */
        const val EDIT_SHAPE_LABEL = "Edit shape"
        private const val PREFS_KEY = "vec.shape"
        /** The pill's "Keep scale proportions" (area I's preference, read only: design §3.9). */
        private const val PILL_KEEP_PROPORTIONS_KEY = "pill.keepProportions"
        /** "Turn into path" refused for a shape without an outline (as "Convert to vector layer" says it). */
        private const val CANT_CONVERT_MESSAGE = "This shape can't be converted"
        private const val TOUCH_SLOP_DP = 6f
        private const val MIN_SIZE_DP = 4f
        private const val HANDLE_TOUCH_DP = 22f
        private const val HANDLE_DRAW_DP = 7f
        private const val ROTATE_OFFSET_DP = 34f
        /** How close (dp) a tap must be to a shape's outline (or inside its fill) to open it. */
        private const val HIT_TOLERANCE_DP = 16f
        /** Segments shorter than this on screen (dp) show no "+" (it would crowd the points). */
        private const val PLUS_MIN_SEGMENT_DP = 64f
        /** Grab radius of a "+" as a fraction of a point's (44 dp across: points are tested first). */
        private const val PLUS_TOUCH = 1f
        /** Handles shorter than this (document px) are not shown or grabbed. */
        private const val HANDLE_MIN_PX = 1e-2f
        /** A custom outline flatter than this (document px) shows no handles across it. */
        private const val FLAT_PX = 0.5f
        private const val MAX_HISTORY = 200
        private const val COALESCE_MS = 1500L
        private const val LAYER_CACHE_SIZE = 64
        private const val DRAW_TILE = 512
        /** From this zoom on the canvas pixels are drawn as crisp squares (see PreviewHost). */
        private const val OVERLAY_MAX_ZOOM = 2.5f
    }
}
