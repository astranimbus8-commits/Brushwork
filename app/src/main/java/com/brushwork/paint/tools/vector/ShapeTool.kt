package com.brushwork.paint.tools.vector

import android.graphics.Bitmap
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
import com.brushwork.paint.brush.BrushPreset
import com.brushwork.paint.brush.StrokeKind
import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.engine.LayerRenderOverride
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.transform.ContentBounds
import com.brushwork.paint.tools.transform.DocBox
import com.brushwork.paint.tools.transform.SnapAxis
import com.brushwork.paint.tools.transform.SnapGuide
import com.brushwork.paint.tools.transform.SnapGuideRenderer
import com.brushwork.paint.tools.transform.SnapHit
import com.brushwork.paint.tools.transform.SnapLine
import com.brushwork.paint.tools.transform.offset
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.math.abs
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
) {
    val outlineParams: OutlineParams get() = OutlineParams(sides, starPoints, innerRatio, corner, cornerRadius)

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
    )

    /** These options with the drawing options of [o] (the ones that are not part of a shape). */
    fun withBehaviourOf(o: ShapeSettings) = copy(
        fromCenter = o.fromCenter,
        keepProportions = o.keepProportions,
        snapAngle = o.snapAngle,
        unit = o.unit,
        nudgeStepPx = o.nudgeStepPx,
        editable = o.editable,
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
    )

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
 * handles. Undo (button or two-finger tap) takes back one edit while such a shape is pending.
 *
 * SNAP TO OBJECTS ([EditorController.snapping], one setting for the whole app): the corner being
 * dragged out, moved shapes (their outline's bounds), resize handles, line ends, points and
 * tangent handles align to the canvas, the other layers' content and drawn lines, the points of
 * the other shape layers ([ShapeOutlines.featurePoints]) and the shape's own other points, with
 * magenta guides; the shape layer being edited is not a target. Axes that don't snap keep
 * following the square grid when grid snapping is on.
 */
class ShapeTool(controller: EditorController) : Tool(controller) {
    override val id = ToolId.SHAPE

    /** Current options (Compose state); change them with [update]. */
    var settings by mutableStateOf(loadSettings())
        private set

    /** The pending (still editable) shape, or null. */
    var box by mutableStateOf<ShapeBox?>(null)
        private set

    /** Custom points of the pending shape (box-local, normalized), or null for its regular outline. */
    var points by mutableStateOf<List<ShapePoint>?>(null)
        private set

    /** Point editing is on for the pending shape. */
    var pointsMode by mutableStateOf(false)
        private set

    /** Index of the selected point (points mode) or -1. */
    var selectedPoint by mutableIntStateOf(-1)
        private set

    /** The shape layer being edited again (null while a new shape is placed). */
    var editingLayer by mutableStateOf<Layer?>(null)
        private set

    /** True when [undoStep] can take back an edit of the pending shape's points. */
    override var canUndoStep by mutableStateOf(false)
        private set

    /** Number of edits [redoStep] can bring back. */
    var redoCount by mutableIntStateOf(0)
        private set

    override val hasPendingWork: Boolean get() = box != null

    /** A shape layer that was only opened (nothing changed yet) doesn't swallow an undo. */
    override val hasUserChanges: Boolean
        get() {
            val b = box ?: return false
            if (editingLayer == null) return true
            return loadedObject != objectFor(b, points)
        }

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
    /** The shape as opened (committing it unchanged records nothing). */
    private var loadedObject: ShapeObject? = null
    /** Where the edited layer's pixels are (hidden while it is edited). */
    private var loadedInk: Rect? = null
    /** The painting tool and brush a re-opened brush-stroked shape was drawn with. */
    private var editBrush: Pair<ToolId, BrushPreset>? = null
    private var editOverride: EditOverride? = null
    /** The outline a brush follows, drawn as a guide when the brush can't be shown live. */
    private var editGuide: Path? = null
    private val guidePath = Path()

    // ------------------------------------------------------------------ gestures

    private enum class Mode { NONE, CREATE, MOVE, RESIZE, ROTATE, LINE_START, LINE_END, POINT, NEW_POINT, HANDLE_IN, HANDLE_OUT }

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

    // ------------------------------------------------------------------ snapping

    private val snap = controller.newSnapSession()
    /** Guides of a resize handle (built here: only the dragged sides are shown). */
    private var resizeGuides: List<SnapGuide> = emptyList()

    // ------------------------------------------------------------------ in-tool history

    private data class PendingState(val box: ShapeBox, val points: List<ShapePoint>?, val pointsMode: Boolean, val selected: Int)

    private val history = ArrayDeque<PendingState>()
    private val redo = ArrayDeque<PendingState>()
    private var historyKey: Any? = null
    private var historyKeyTime = 0L
    private data class NumericKey(val kind: String, val index: Int)

    /** Time source for coalescing numeric edits (replaceable in tests). */
    internal var clock: () -> Long = { SystemClock.uptimeMillis() }

    // ------------------------------------------------------------------ drawing helpers

    private val painter = OverlayPainter()
    private val boxPath = Path()
    private val bandPath = Path()
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
        settings = new
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
            clearHistory()
        }
        if (b != null && old.type.isLineLike != new.type.isLineLike) box = convert(b, new.type)
        refreshPreview()
    }

    private fun loadSettings(): ShapeSettings =
        runCatching { controller.settings.getObject(PREFS_KEY, ShapeSettings.serializer()) }.getOrNull()?.sanitized() ?: ShapeSettings()

    /** Size of the current painting tool's brush (what "Use brush size" follows), or null. */
    val brushSize: Float?
        get() = controller.presetFor(controller.lastPaintTool)?.size?.takeIf { it.isFinite() && it > 0f }
            ?.coerceIn(ShapeSettings.MIN_STROKE, ShapeSettings.MAX_STROKE)

    /** The stroke width in use: the brush size when "Use brush size" is on (Compose state). */
    val strokeWidth: Float
        get() = settings.let { s -> if (s.useBrushSize) brushSize ?: s.strokeWidth else s.strokeWidth }

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
    private fun naturalAspect(): Float = ShapeGeometry.naturalAspect(settings.type, settings.outlineParams)

    /** The shape is a line / arrow shown with its two end handles (no custom points). */
    private val lineHandles: Boolean get() = settings.type.isLineLike && points == null

    /** Custom outlines of lines and arrows are open, the others closed. */
    private val closedShape: Boolean get() = !settings.type.isLineLike

    // ------------------------------------------------------------------ the shape object

    /** The pending shape [b] (with custom [pts]) as it would be placed now. */
    private fun objectFor(b: ShapeBox, pts: List<ShapePoint>?): ShapeObject {
        val s = settings
        val color = controller.color
        val brush = s.strokeWith == ShapeStroke.BRUSH && s.strokes
        val eb = editBrush
        val tool = if (!brush) null else eb?.first ?: controller.lastPaintTool
        val preset = if (!brush) null else eb?.second ?: controller.presetFor(controller.lastPaintTool)
        return ShapeObject(
            type = s.type,
            cx = b.cx, cy = b.cy, w = b.w, h = b.h, rotation = b.rotationDeg,
            style = s.style,
            strokeWidth = strokeWidth,
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
        )
    }

    /** The pending shape as it would be placed now (null when there is none). */
    fun pendingObject(): ShapeObject? = box?.let { objectFor(it, points) }

    // ------------------------------------------------------------------ numeric editing

    /**
     * Makes sure a shape is pending (a default one centered on the canvas is created for numeric
     * entry). Returns false when the active layer can't be edited.
     */
    fun ensurePending(): Boolean {
        if (box != null) return true
        if (!controller.checkEditable()) return false
        val d = controller.doc
        val size = max(1f, min(d.width, d.height) / 3f)
        val cx = d.width / 2f; val cy = d.height / 2f
        targetLayer = d.activeLayer
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
        if (points != null) pushHistory(NumericKey("place", 0))
        box = next
        refreshPreview()
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
        val sel = selectedPoint
        val anchors = docAnchors()
        if (pointsMode && anchors != null && sel in anchors.indices) {
            pushHistory(NumericKey("nudge", sel))
            applyAnchors(anchors.mapIndexed { i, a -> if (i == sel) a.moved(a.pos + d) else a }, b.rotationDeg)
            refreshPreview()
            return
        }
        if (points != null) pushHistory(NumericKey("nudge", -1))
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
                pushHistory()
                points = ShapePoints.fromRegular(settings.type, settings.outlineParams)
            }
            pointsMode = true
        } else {
            pointsMode = false
            selectedPoint = -1
        }
        refreshPreview()
    }

    /** Goes back to the regular outline of the shape type (its box is kept). */
    fun resetShape() {
        if (box == null || points == null) return
        pushHistory()
        points = null
        pointsMode = false
        selectedPoint = -1
        refreshPreview()
    }

    fun selectPoint(index: Int) {
        val n = points?.size ?: 0
        selectedPoint = if (index in 0 until n) index else -1
        controller.invalidateOverlay()
    }

    /** Fewest points the pending shape can have. */
    val minPoints: Int get() = ShapePoints.minPoints(closedShape)

    /** Deletes point [index] (refused with a message below [minPoints]). */
    fun deletePoint(index: Int) {
        val b = box ?: return
        val anchors = docAnchors() ?: return
        if (index !in anchors.indices) return
        if (anchors.size <= minPoints) {
            controller.toast(if (closedShape) "A shape needs at least $minPoints points" else "A line needs at least $minPoints points")
            return
        }
        pushHistory()
        applyAnchors(anchors.filterIndexed { i, _ -> i != index }, b.rotationDeg)
        selectedPoint = -1
        refreshPreview()
    }

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

    // ------------------------------------------------------------------ in-tool history

    /**
     * Saves the pending shape for [undoStep] (and drops the redo steps: this is a new edit).
     * Consecutive edits with the same non-null [key] that follow each other quickly (typing a
     * coordinate, holding a nudge arrow) share one step.
     */
    private fun pushHistory(key: Any? = null) {
        val b = box ?: return
        val now = if (key != null) clock() else 0L
        val coalesce = key != null && key == historyKey && history.isNotEmpty() && now - historyKeyTime <= COALESCE_MS
        historyKey = key
        historyKeyTime = now
        clearRedo()
        if (coalesce) return
        history.addLast(PendingState(b, points, pointsMode, selectedPoint))
        while (history.size > MAX_HISTORY) history.removeFirst()
        canUndoStep = true
    }

    private fun clearRedo() {
        if (redo.isEmpty()) return
        redo.clear()
        redoCount = 0
    }

    private fun clearHistory() {
        history.clear()
        redo.clear()
        redoCount = 0
        canUndoStep = false
        historyKey = null
    }

    /**
     * Takes back one edit of a shape with its own points (a point moved, inserted, deleted,
     * made smooth... or the shape moved / resized meanwhile). Also used by the app's undo
     * (button / two-finger tap); without such edits undo discards the pending shape.
     */
    override fun undoStep(): Boolean {
        val b = box ?: return false
        val prev = history.removeLastOrNull() ?: return false
        redo.addLast(PendingState(b, points, pointsMode, selectedPoint))
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
        history.addLast(PendingState(b, points, pointsMode, selectedPoint))
        while (history.size > MAX_HISTORY) history.removeFirst()
        canUndoStep = true
        historyKey = null
        restoreState(next)
        return true
    }

    private fun restoreState(s: PendingState) {
        box = s.box
        points = s.points
        pointsMode = s.pointsMode && s.points != null
        selectedPoint = if (s.points != null && s.selected in s.points.indices) s.selected else -1
        refreshPreview()
    }

    // ------------------------------------------------------------------ input

    override fun onDown(p: ToolPoint) {
        val pt = Vec2(p.x, p.y)
        downPoint = pt
        started = false
        longPressed = false
        gesturePushed = false
        resizeGuides = emptyList()
        val b = box
        gestureState = b?.let { PendingState(it, points, pointsMode, selectedPoint) }
        gestureHistory = history.size
        gestureRedo = redo.toList()
        if (b != null) {
            val hit = if (pointsMode && points != null) hitPoints(b, pt) else hitTest(b, pt)
            if (hit != null) {
                mode = hit
                startBox = box
                beginSnap()
                return
            }
        }
        // A drag draws a new shape (its first corner may snap: it is a new point); a tap
        // commits the pending one and opens the shape under the finger (see onUp).
        mode = Mode.CREATE
        snap.begin(exclude = listOfNotNull(editingLayer))
        anchor = snap.snapPoint(pt)
    }

    /** Starts snapping for a gesture on the pending shape (see the class comment). */
    private fun beginSnap() {
        val exclude = listOfNotNull(editingLayer)
        when (mode) {
            Mode.POINT, Mode.NEW_POINT, Mode.HANDLE_IN, Mode.HANDLE_OUT -> {
                // The shape's other points: points line up with each other.
                val keepOwn = mode == Mode.HANDLE_IN || mode == Mode.HANDLE_OUT
                val others = startAnchors.filterIndexed { i, _ -> keepOwn || i != dragIndex }.flatMap { SnapLine.point(it.pos, "Vertex") }
                snap.begin(exclude = exclude, extra = { others })
            }
            Mode.MOVE -> {
                startBounds = box?.let { outlineBounds(objectFor(it, points)) }
                snap.begin(exclude = exclude)
            }
            else -> snap.begin(exclude = exclude)
        }
    }

    override fun onMove(p: ToolPoint) {
        val pt = Vec2(p.x, p.y)
        val s = settings
        if (mode == Mode.NONE) return
        // Nothing changes until the finger really moves, so a tap never nudges, snaps or
        // re-quantizes the pending shape.
        if (!started) {
            if (pt.distanceTo(downPoint) < controller.docLength(TOUCH_SLOP_DP)) return
            if (mode == Mode.CREATE) {
                if (!controller.checkEditable()) { mode = Mode.NONE; snap.end(); return }
                if (box == null) targetLayer = controller.doc.activeLayer
            }
            started = true
            // A shape with its own points: every change can be undone one at a time.
            if (mode != Mode.CREATE && !gesturePushed && points != null) { pushHistory(); gesturePushed = true }
            // The preview follows the finger as cheaply as possible until it lifts.
            setDragging(true)
        }
        when (mode) {
            Mode.NONE -> return
            Mode.CREATE -> creatingBox = creationBox(pt)
            Mode.MOVE -> box = moved(pt) ?: return
            Mode.RESIZE -> {
                val start = startBox ?: return
                val h = handle ?: return
                val aspect = if (s.keepProportions && start.h > 0f) start.w / start.h else null
                val target = resizeTarget(start, h, pt)
                val nb = clean(ShapeGeometry.resize(start, h, target.first, s.fromCenter, aspect))
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
                deg = if (s.snapAngle) ShapeGeometry.snapDegrees(deg) else ShapeGeometry.normalizeDegrees(deg)
                box = start.copy(rotationDeg = deg)
            }
            Mode.LINE_START, Mode.LINE_END -> {
                val start = startBox ?: return
                val fixed = if (mode == Mode.LINE_START) start.end else start.start
                var q = snap.snapPoint(pt)
                if (s.snapAngle) {
                    val a = ShapeGeometry.snapAngle(fixed, q)
                    if (a.distanceTo(q) > 1e-3f) snap.clearGuides()
                    q = a
                }
                box = if (mode == Mode.LINE_START) ShapeBox.line(q, fixed) else ShapeBox.line(fixed, q)
            }
            Mode.POINT, Mode.NEW_POINT -> dragPoint(pt)
            Mode.HANDLE_IN, Mode.HANDLE_OUT -> dragHandle(pt)
        }
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
        return start.translated(dx, dy)
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
        val target = snap.snapPoint(grabStart + (pt - downPoint))
        applyAnchors(startAnchors.mapIndexed { i, a -> if (i == dragIndex) a.moved(target) else a }, start.rotationDeg)
    }

    /** A dragged tangent handle (its end snaps like a point). */
    private fun dragHandle(pt: Vec2) {
        val start = startBox ?: return
        val a = startAnchors.getOrNull(dragIndex) ?: return
        val end = snap.snapPoint(grabStart + (pt - downPoint))
        val v = end - a.pos
        if (v.length < 1e-3f) return
        applyAnchors(ShapePoints.dragHandle(startAnchors, dragIndex, closedShape, out = mode == Mode.HANDLE_OUT, v = v), start.rotationDeg)
    }

    override fun onUp(p: ToolPoint) {
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
            else -> if (started) onMove(p)
        }
        mode = Mode.NONE
        startBox = null
        startBounds = null
        handle = null
        snap.end()
        if (resizeGuides.isNotEmpty()) { resizeGuides = emptyList(); controller.invalidateOverlay() }
        // The drag is over: a plain shape goes back into the layer, a brush outline is refined
        // once it rests a moment.
        setDragging(false)
        controller.invalidateOverlay()
    }

    /** End of a CREATE gesture: a new shape, or a tap (commit the pending one, open the shape tapped). */
    private fun createUp(pt: Vec2) {
        if (started) creatingBox = creationBox(pt)
        val created = creatingBox?.takeIf { started && isBigEnough(it) }
        creatingBox = null
        if (created == null) {
            val tap = !started
            var added: Layer? = null
            if (box != null) {
                // A tap (or a drag too small to make a shape) outside the pending shape commits it.
                val before = controller.doc.layers.size
                commit()
                if (controller.doc.layers.size > before) added = controller.doc.activeLayer
            }
            if (box == null) targetLayer = null
            // The tap opens the shape under the finger (never the one it just placed).
            if (tap && box == null) shapeLayerAt(pt, skip = added)?.let { editLayer(it) }
        } else {
            if (box != null) commit()
            if (box == null) {
                targetLayer = controller.doc.activeLayer
                box = created
                points = null
                pointsMode = false
                selectedPoint = -1
                clearHistory()
            }
        }
        refreshPreview()
    }

    override fun onCancel() {
        setDragging(false)
        snap.end()
        resizeGuides = emptyList()
        when (mode) {
            Mode.CREATE -> creatingBox = null
            Mode.NONE -> {}
            else -> restoreGesture()
        }
        mode = Mode.NONE
        startBox = null
        startBounds = null
        handle = null
        if (box == null) targetLayer = null
        refreshPreview()
    }

    /** Back to the state before the cancelled gesture (points it inserted included). */
    private fun restoreGesture() {
        val st = gestureState ?: return
        box = st.box
        points = st.points
        pointsMode = st.pointsMode
        selectedPoint = st.selected
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
        // An opened shape layer drawn in the overlay during the drag goes back into the layer.
        if (was && !on && editingLayer != null && box != null) refreshPreview()
    }

    /** A finger (or two) is dragging the shape (see [setDragging]). */
    private var dragging = false

    /**
     * Holding a finger still on the pending shape (a handle, a point, or the shape to move it)
     * keeps that edit going instead of turning into color picking (a point gets selected);
     * elsewhere the controller decides.
     */
    override fun onLongPress(p: ToolPoint): Boolean = when (mode) {
        Mode.NONE, Mode.CREATE -> false
        Mode.POINT -> {
            if (!started) {
                selectPoint(dragIndex)
                longPressed = true
            }
            true
        }
        else -> {
            if (mode == Mode.MOVE && !started) longPressed = true
            true
        }
    }

    // ------------------------------------------------------------------ two-finger pinch

    /** Two fingers on (or around) the pending shape scale, rotate and move it. */
    override fun onTwoFingerStart(focus: Vec2, a: Vec2, b: Vec2): Boolean {
        val bx = box ?: return false
        if (!isOnShape(bx, focus)) return false
        creatingBox = null
        mode = Mode.NONE
        pinchStart = bx
        pinchFocus = focus
        pinchPushed = points != null
        if (pinchPushed) pushHistory()
        setDragging(true)
        return true
    }

    override fun onTwoFingerGesture(translation: Vec2, scale: Float, rotationDeg: Float) {
        val start = pinchStart ?: return
        if (box == null) { pinchStart = null; return }
        if (!translation.x.isFinite() || !translation.y.isFinite() || !scale.isFinite() || scale <= 0f || !rotationDeg.isFinite()) return
        box = pinched(start, pinchFocus, translation, scale, rotationDeg, settings.snapAngle)
        refreshPreview()
    }

    override fun onTwoFingerEnd(cancelled: Boolean) {
        val start = pinchStart ?: return
        pinchStart = null
        setDragging(false)
        if (cancelled && box != null) {
            box = start
            if (pinchPushed) history.removeLastOrNull().also { canUndoStep = history.isNotEmpty() }
        }
        pinchPushed = false
        refreshPreview()
    }

    /**
     * [start] scaled by [scale] and rotated by [rotationDeg] around the pinch [focus], then moved
     * by [translation] (rotation snapped to 15° steps with [snap]).
     */
    private fun pinched(start: ShapeBox, focus: Vec2, translation: Vec2, scale: Float, rotationDeg: Float, snap: Boolean): ShapeBox {
        val target = start.rotationDeg + rotationDeg
        val deg = if (snap) ShapeGeometry.snapDegrees(target) else ShapeGeometry.normalizeDegrees(target)
        val delta = ShapeGeometry.normalizeDegrees(deg - start.rotationDeg) * Geometry.DEG
        val c = focus + (start.center - focus).rotated(delta) * scale + translation
        return clean(ShapeBox(c.x, c.y, start.w * scale, start.h * scale, deg))
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

    private fun creationBox(pt: Vec2): ShapeBox {
        val s = settings
        var cur = snap.snapPoint(pt)
        return if (s.type.isLineLike) {
            if (s.snapAngle) {
                val a = ShapeGeometry.snapAngle(anchor, cur)
                if (a.distanceTo(cur) > 1e-3f) snap.clearGuides()
                cur = a
            }
            if (s.fromCenter) ShapeBox.line(anchor * 2f - cur, cur) else ShapeBox.line(anchor, cur)
        } else {
            ShapeGeometry.dragBox(anchor, cur, s.fromCenter, if (s.keepProportions) naturalAspect() else null)
        }
    }

    private fun isBigEnough(b: ShapeBox): Boolean {
        val minLen = controller.docLength(MIN_SIZE_DP)
        return if (settings.type.isLineLike) b.w >= minLen else max(b.w, b.h) >= minLen && min(b.w, b.h) >= 1f
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
        val sel = selectedPoint
        if (sel in anchors.indices) {
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
        if (idx >= 0) {
            startPointGesture(anchors, idx, anchors[idx].pos)
            return Mode.POINT
        }
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

    /**
     * The plain items of shape [o]: everything when [brush] is false; with the brush only what
     * stays plain (the fill, filled arrowheads), since the brush paints the outline.
     */
    private fun buildSpec(o: ShapeObject, brush: Boolean): VectorPaintSpec? {
        val color = o.strokeColor
        val w = o.strokeWidth
        return when (o.type) {
            ShapeType.LINE -> if (brush) null else VectorPaintSpec.build(
                null, 0, ShapeOutlines.outline(o), color, w, o.lineCap, ShapeOutlines.join(o),
            )
            ShapeType.ARROW -> {
                val g = ShapeOutlines.arrow(o)
                VectorPaintSpec.build(null, 0, if (brush) null else g.stroke, color, w, o.lineCap, JoinStyle.ROUND, g.fill)
            }
            else -> {
                val outline = ShapeOutlines.outline(o)
                VectorPaintSpec.build(
                    if (o.style.fill) outline else null, o.fillColor,
                    if (o.style.stroke && !brush) outline else null, color, w, LineCapStyle.ROUND, ShapeOutlines.join(o),
                )
            }
        }
    }

    /** Rebuilds the preview of the pending / in-creation shapes and redraws. */
    fun refreshPreview() {
        val editLayer = editingLayer
        if (editLayer != null) {
            refreshEditPreview(editLayer)
            return
        }
        val layer = targetLayer ?: controller.doc.activeLayer
        val asNew = settings.editable
        val pending = box?.let { objectFor(it, points) }
        val creating = creatingBox?.let { objectFor(it, null) }
        val brushObj = if (paintsWithBrush(layer)) creating ?: pending else null
        if (brushObj != null) {
            ensureObserving()
            preview.release()
            // Only one shape can be the live brush stroke: while a new one is dragged out, the
            // pending one is shown as a plain outline until it is committed.
            val older = if (creating != null) pending else null
            overlaySpecs = listOfNotNull(older?.let { buildSpec(it, brush = false) }, buildSpec(brushObj, brush = true))
            val path = ShapeOutlines.brushOutline(brushObj)
            if (overlaySpecs.isNotEmpty()) specOverlay.setBand(path.toAndroidPath(bandPath), brushPresetInUse()?.size ?: 0f)
            brushPreview.request(path.ops) { brushStrokeInput(path, out = it) }
        } else {
            brushPreview.cancel()
            overlaySpecs = emptyList()
            val specs = listOfNotNull(pending?.let { buildSpec(it, brush = false) }, creating?.let { buildSpec(it, brush = false) })
            if (specs.isNotEmpty()) ensureObserving()
            preview.show(layer, specs, asNewLayer = asNew)
        }
        controller.invalidateOverlay()
    }

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
        val ov = installEditOverride(layer)
        val specs = listOfNotNull(buildSpec(o, brush))
        // While a finger drags a plain shape that looks the same over the finished image, it is
        // drawn in the overlay: the canvas tiles (the layer's hidden pixels) stay as they are.
        val inOverlay = dragging && !brush && editDrawsInOverlay(layer)
        setEditSpecs(ov, if (inOverlay) emptyList() else specs)
        overlaySpecs = (if (inOverlay) specs else emptyList()) + listOfNotNull(creatingBox?.let { buildSpec(objectFor(it, null), brush = false) })
        val path = if (brush) ShapeOutlines.brushOutline(o) else null
        editGuide = if (path != null && !live) path.toAndroidPath(guidePath) else null
        if (path != null && live) brushPreview.request(path.ops) { brushStrokeInput(path, out = it) } else brushPreview.cancel()
        controller.invalidateOverlay()
    }

    /**
     * True when drawing the edited shape over the finished image looks exactly like drawing it
     * into [layer] (see PreviewHost): a normal, opaque, unmasked, unclipped layer with nothing
     * visible above it, no selection (a re-edited shape ignores it, the overlay would not), a
     * color document, at a zoom where the canvas is drawn smoothed.
     */
    private fun editDrawsInOverlay(layer: Layer): Boolean {
        if (controller.viewTransform.zoom >= OVERLAY_MAX_ZOOM) return false
        val doc = controller.doc
        val index = doc.indexOf(layer)
        if (index < 0 || !layer.visible || layer.opacity < 1f) return false
        if (layer.blendMode != LayerBlendMode.NORMAL || layer.clipping) return false
        if (layer.mask != null && layer.maskEnabled) return false
        if (doc.colorMode == ColorMode.MONOCHROME || controller.selection != null) return false
        val layers = doc.layers
        for (i in index + 1 until layers.size) {
            if (layers[i].visible && layers[i].opacity > 0f) return false
        }
        return true
    }

    /** The edited shape's brush stroke can be shown live (a stroke drawn through a buffer, on the layer's content). */
    private fun liveBrushWhileEditing(layer: Layer): Boolean {
        if (controller.editTargetOf(layer) != EditTarget.CONTENT) return false
        val tool = brushToolId()
        val preset = brushPresetInUse() ?: return false
        return !StrokeKind.of(tool, preset).isDirect
    }

    /** Runs a waiting live-brush replay now (the main looper does it otherwise). */
    internal fun flushPreview() = brushPreview.flush()

    // ------------------------------------------------------------------ editing shape layers

    /**
     * Draws the shape layer being edited: its pixels are hidden and the edited shape is drawn in
     * their place THROUGH THE COMPOSITOR, so the layer's order, opacity, blend mode, mask and the
     * layers clipped to it look exactly like the result. The painting tool's live stroke of a
     * brush outline ([inner], its own override) is drawn over the fill, without the layer's old
     * pixels.
     */
    private inner class EditOverride(override val layer: Layer) : LayerRenderOverride {
        var specs: List<VectorPaintSpec> = emptyList()
        var regions: List<Rect> = emptyList()
        var inner: LayerRenderOverride? = null

        override fun drawContent(canvas: Canvas): Boolean {
            val mode = controller.doc.colorMode
            for (s in specs) renderer.draw(canvas, s, false, mode)
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

    private fun setEditSpecs(ov: EditOverride, specs: List<VectorPaintSpec>) {
        val old = ov.regions
        val regions = ArrayList<Rect>()
        for (s in specs) regions += s.regions
        ov.specs = specs
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
        clearHistory()
        // The main color shows the shape's stroke color (changing it recolors the shape).
        controller.color = obj.strokeColor
        loadedObject = objectFor(obj.box, obj.points)
        loadedInk = inkOf(layer, obj)
        refreshPreview()
        return true
    }

    /** Topmost visible, unlocked shape layer whose shape is at [p] (the active layer first), or null. */
    fun shapeLayerAt(p: Vec2, skip: Layer? = null): Layer? {
        val doc = controller.doc
        val tol = controller.docLength(HIT_TOLERANCE_DP)
        val active = doc.activeLayer
        fun hits(l: Layer): Boolean {
            if (l === skip || !l.isShapeLayer || !l.visible || l.locked) return false
            val o = decoded(l) ?: return false
            return ShapeOutlines.hits(o, p, tol)
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

    /** Document rect of everything [o] paints (plain parts and the brush's reach), or null. */
    private fun paintRect(o: ShapeObject): Rect? {
        val r = Rect()
        val tmp = Rect()
        buildSpec(o, brush = false)?.let { it.boundsRect(tmp); r.union(tmp) }
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
        editGuide = null
        userSettings?.let { u -> settings = u.withBehaviourOf(settings) }
        userSettings = null
        controller.invalidateOverlay()
    }

    // ------------------------------------------------------------------ commit / discard

    /** Clears the pending shape (not the edited layer's state; the brush preview is left to the caller). */
    private fun resetPending() {
        box = null
        points = null
        pointsMode = false
        selectedPoint = -1
        creatingBox = null
        targetLayer = null
        pinchStart = null
        overlaySpecs = emptyList()
        preview.release()
        preview.interacting = false
        clearHistory()
    }

    override fun commit() {
        val b = box ?: return
        val editLayer = editingLayer
        if (editLayer != null) {
            commitLayerEdit(editLayer, b)
            return
        }
        val layer = targetLayer ?: controller.doc.activeLayer
        if (settings.editable) {
            commitNewLayer(b)
            return
        }
        if (controller.doc.indexOf(layer) < 0) { discard(); return }
        if (!controller.checkEditable(layer)) return
        val o = objectFor(b, points)
        val brush = paintsWithBrush(layer)
        val spec = buildSpec(o, brush)
        val path = if (brush) ShapeOutlines.brushOutline(o) else null
        resetPending()
        // Fill (plain) and outline (brush) are ONE undo step, named "Shape".
        controller.undoStepNamed("Shape") {
            if (spec != null) {
                // The fill goes under the brush stroke: a live stroke is restarted after it.
                brushPreview.cancel()
                VectorCommit.commit(controller, layer, listOf(spec), "Shape")
            }
            if (path != null) brushPreview.commit(path.ops) { brushStrokeInput(path, out = it) }
        }
        brushPreview.end()
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
        val spec = buildSpec(o, brush)
        val path = if (brush) ShapeOutlines.brushOutline(o) else null
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
                    spec?.let { drawSpec(c, it, sel) }
                }
                added = layer
                // The brush paints the active layer: the new one.
                if (layer != null && path != null) controller.keepLayerData(layer) { brushPreview.commit(path.ops) { brushStrokeInput(path, out = it) } }
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
            refreshPreview()
        }
        controller.invalidateOverlay()
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
     * locked or hidden meanwhile) keeps the shape open.
     */
    private fun commitLayerEdit(layer: Layer, b: ShapeBox) {
        val doc = controller.doc
        if (doc.indexOf(layer) < 0) { discard(); return }
        val o = objectFor(b, points)
        val loaded = loadedObject
        if (o == loaded) { discard(); return }
        if (!controller.checkEditable(layer)) return
        val brush = o.paintsWithBrush
        val spec = buildSpec(o, brush)
        val path = if (brush) ShapeOutlines.brushOutline(o) else null
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
        var done = false
        inCommit = true
        val maskEditing = layer.editingMask
        try {
            controller.undoStepNamed("Edit shape") {
                done = controller.updateShapeLayer(layer, data, "Edit shape", dirty) { c ->
                    spec?.let { renderer.draw(c, it, false, doc.colorMode) }
                }
                if (done && path != null) {
                    // The outline is painted on the layer's pixels, never into its mask.
                    layer.editingMask = false
                    try {
                        controller.keepLayerData(layer) { brushPreview.commit(path.ops) { brushStrokeInput(path, out = it) } }
                    } finally {
                        layer.editingMask = maskEditing
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

    override fun discard() {
        resetPending()
        mode = Mode.NONE
        startBox = null
        snap.end()
        resizeGuides = emptyList()
        brushPreview.end()
        if (editingLayer != null) endLayerEdit()
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
        if (box != null || creatingBox != null) refreshPreview()
    }

    override fun onDeactivate() {
        // Adding the committed shape's layer pauses this tool: nothing to finish then.
        if (inCommit) return
        observeJob?.cancel()
        observeJob = null
        if (mode == Mode.CREATE) creatingBox = null
        mode = Mode.NONE
        pinchStart = null
        snap.end()
        resizeGuides = emptyList()
        if (hasPendingWork) commit()
        if (hasPendingWork) discard()
        overlaySpecs = emptyList()
        preview.release()
        preview.interacting = false
        brushPreview.end()
        // Never leave an edited layer's pixels hidden.
        if (editingLayer != null) endLayerEdit()
        clearHistory()
    }

    override fun onDispose() {
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
            val asNew = editingLayer != null || settings.editable
            specOverlay.draw(canvas, t, controller, targetLayer ?: controller.doc.activeLayer, overlaySpecs, keepBandFree = brushPreview.isLive, asNewLayer = asNew)
        }
        editGuide?.let { if (creating == null) painter.path(canvas, t, it) }
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
        if (creating != null && settings.type.isLineLike) return
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

    /** Points mode: the outline, a "+" between points, the selected point's tangent handles and every point. */
    private fun drawPoints(canvas: Canvas, t: ViewTransform, b: ShapeBox, pts: List<ShapePoint>) {
        val o = objectFor(b, pts)
        painter.path(canvas, t, outlinePathOf(o))
        val anchors = ShapePoints.docAnchors(b, pts)
        val closed = closedShape
        for (s in plusSegments(anchors)) {
            val q = map(t, ShapePoints.pointOn(anchors, closed, s, 0.5f))
            painter.plus(canvas, t, q[0], q[1])
        }
        val sel = selectedPoint
        if (sel in anchors.indices) {
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
            painter.handle(canvas, t, q[0], q[1], square = !anchors[i].smooth, active = i == sel)
        }
    }

    companion object {
        private const val PREFS_KEY = "vec.shape"
        private const val TOUCH_SLOP_DP = 6f
        private const val MIN_SIZE_DP = 4f
        private const val HANDLE_TOUCH_DP = 22f
        private const val HANDLE_DRAW_DP = 7f
        private const val ROTATE_OFFSET_DP = 34f
        /** How close (dp) a tap must be to a shape's outline (or inside its fill) to open it. */
        private const val HIT_TOLERANCE_DP = 16f
        /** Segments shorter than this on screen (dp) show no "+" (it would crowd the points). */
        private const val PLUS_MIN_SEGMENT_DP = 64f
        /** Grab radius of a "+" as a fraction of a point's. */
        private const val PLUS_TOUCH = 0.8f
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
