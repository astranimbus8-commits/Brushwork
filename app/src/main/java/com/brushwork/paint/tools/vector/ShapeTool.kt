package com.brushwork.paint.tools.vector

import android.graphics.Canvas
import android.graphics.Path
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Geometry
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Persisted options of the shape tool. Lengths are document pixels. */
@Serializable
data class ShapeSettings(
    val type: ShapeType = ShapeType.RECTANGLE,
    val style: ShapeStyle = ShapeStyle.STROKE,
    val strokeWidth: Float = 8f,
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
) {
    val outlineParams: OutlineParams get() = OutlineParams(sides, starPoints, innerRatio, corner, cornerRadius)

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

    companion object {
        const val MIN_STROKE = 0.25f
        const val MAX_STROKE = 2000f
        const val MAX_LENGTH = 100_000f
        private val DEFAULT = ShapeSettings()
    }
}

/**
 * Lines, rectangles, ellipses, polygons, stars and arrows. Drag to create; the shape then stays
 * editable (move, 8 resize handles, rotation handle, numeric entry) until committed with ✓,
 * by tapping outside it, or by switching tools. The preview goes through the compositor.
 */
class ShapeTool(controller: EditorController) : Tool(controller) {
    override val id = ToolId.SHAPE

    /** Current options (Compose state); change them with [update]. */
    var settings by mutableStateOf(loadSettings())
        private set

    /** The pending (still editable) shape, or null. */
    var box by mutableStateOf<ShapeBox?>(null)
        private set

    override val hasPendingWork: Boolean get() = box != null

    private var targetLayer: Layer? = null
    private var creatingBox: ShapeBox? = null
    private val preview = PreviewHost(controller)
    private var observeJob: Job? = null

    private enum class Mode { NONE, CREATE, MOVE, RESIZE, ROTATE, LINE_START, LINE_END }

    private var mode = Mode.NONE
    private var handle: ShapeGeometry.Handle? = null
    private var startBox: ShapeBox? = null
    private var downPoint = Vec2.ZERO
    private var anchor = Vec2.ZERO
    /** The current gesture moved past the touch slop. */
    private var started = false

    private val painter = OverlayPainter()
    private val boxPath = Path()
    private val pts = FloatArray(2)

    // ------------------------------------------------------------------ settings

    /** Changes the options; a pending shape updates live. */
    fun update(transform: (ShapeSettings) -> ShapeSettings) {
        val old = settings
        val new = transform(old).sanitized(fallback = old)
        if (new == old) return
        settings = new
        runCatching { controller.settings.putObject(PREFS_KEY, ShapeSettings.serializer(), new) }
        val b = box
        if (b != null && old.type.isLineLike != new.type.isLineLike) box = convert(b, new.type)
        refreshPreview()
    }

    private fun loadSettings(): ShapeSettings =
        runCatching { controller.settings.getObject(PREFS_KEY, ShapeSettings.serializer()) }.getOrNull()?.sanitized() ?: ShapeSettings()

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
        if (box == null) return
        if (!(b.cx.isFinite() && b.cy.isFinite() && b.w.isFinite() && b.h.isFinite() && b.rotationDeg.isFinite())) return
        val lim = ShapeSettings.MAX_LENGTH
        val clean = ShapeBox(
            b.cx.coerceIn(-lim, lim), b.cy.coerceIn(-lim, lim),
            if (settings.type.isLineLike) b.w.coerceIn(0f, lim) else b.w.coerceIn(1f, lim),
            if (settings.type.isLineLike) 0f else b.h.coerceIn(1f, lim),
            ShapeGeometry.normalizeDegrees(b.rotationDeg),
        )
        box = clean
        refreshPreview()
    }

    /** Moves the pending shape by the nudge step in direction ([dx], [dy]). */
    fun nudge(dx: Int, dy: Int) {
        val b = box ?: return
        val step = settings.nudgeStepPx
        place(b.translated(dx * step, dy * step))
    }

    // ------------------------------------------------------------------ input

    override fun onDown(p: ToolPoint) {
        val pt = Vec2(p.x, p.y)
        downPoint = pt
        started = false
        val b = box
        if (b != null) {
            val hit = hitTest(b, pt)
            if (hit != null) {
                mode = hit
                startBox = b
                return
            }
        }
        if (!controller.checkEditable()) { mode = Mode.NONE; return }
        mode = Mode.CREATE
        anchor = controller.snapToGrid(pt)
    }

    override fun onMove(p: ToolPoint) {
        val pt = Vec2(p.x, p.y)
        val s = settings
        if (mode == Mode.NONE) return
        // Nothing changes until the finger really moves, so a tap never nudges, snaps or
        // re-quantizes the pending shape.
        if (!started) {
            if (pt.distanceTo(downPoint) < controller.docLength(TOUCH_SLOP_DP)) return
            started = true
            if (mode == Mode.CREATE && box == null) targetLayer = controller.doc.activeLayer
        }
        when (mode) {
            Mode.NONE -> return
            Mode.CREATE -> creatingBox = creationBox(pt)
            Mode.MOVE -> {
                val start = startBox ?: return
                val ref = if (s.type.isLineLike) start.start else start.toDoc(Vec2(-start.w / 2f, -start.h / 2f))
                val d = controller.snapToGrid(ref + (pt - downPoint)) - ref
                box = start.translated(d.x, d.y)
            }
            Mode.RESIZE -> {
                val start = startBox ?: return
                val h = handle ?: return
                val aspect = if (s.keepProportions && start.h > 0f) start.w / start.h else null
                box = ShapeGeometry.resize(start, h, controller.snapToGrid(pt), s.fromCenter, aspect)
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
                var q = controller.snapToGrid(pt)
                if (s.snapAngle) q = ShapeGeometry.snapAngle(fixed, q)
                box = if (mode == Mode.LINE_START) ShapeBox.line(q, fixed) else ShapeBox.line(fixed, q)
            }
        }
        refreshPreview()
    }

    override fun onUp(p: ToolPoint) {
        when (mode) {
            Mode.NONE -> {}
            Mode.CREATE -> {
                if (started) creatingBox = creationBox(Vec2(p.x, p.y))
                val created = creatingBox?.takeIf { started && isBigEnough(it) }
                creatingBox = null
                if (created == null) {
                    // A tap (or a drag too small to make a shape) outside the pending shape commits it.
                    if (box != null) commit()
                } else {
                    val layer = controller.doc.activeLayer
                    if (box != null) commit()
                    if (box == null) {
                        targetLayer = layer
                        box = created
                    }
                }
                if (box == null) targetLayer = null
                refreshPreview()
            }
            else -> onMove(p)
        }
        mode = Mode.NONE
        startBox = null
        handle = null
    }

    override fun onCancel() {
        when (mode) {
            Mode.CREATE -> creatingBox = null
            Mode.NONE -> {}
            else -> startBox?.let { box = it }
        }
        mode = Mode.NONE
        startBox = null
        handle = null
        if (box == null) targetLayer = null
        refreshPreview()
    }

    private fun creationBox(pt: Vec2): ShapeBox {
        val s = settings
        var cur = controller.snapToGrid(pt)
        return if (s.type.isLineLike) {
            if (s.snapAngle) cur = ShapeGeometry.snapAngle(anchor, cur)
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

    private fun hitTest(b: ShapeBox, p: Vec2): Mode? {
        val tol = controller.docLength(HANDLE_TOUCH_DP)
        if (settings.type.isLineLike) {
            val ds = p.distanceTo(b.start); val de = p.distanceTo(b.end)
            if (min(ds, de) <= tol) return if (de <= ds) Mode.LINE_END else Mode.LINE_START
            val reach = max(tol * 0.75f, settings.strokeWidth / 2f)
            return if (Geometry.distanceToSegment(p, b.start, b.end) <= reach) Mode.MOVE else null
        }
        if (p.distanceTo(rotationHandle(b)) <= tol) return Mode.ROTATE
        var best: ShapeGeometry.Handle? = null
        var bestD = tol
        for (h in ShapeGeometry.Handle.entries) {
            val d = p.distanceTo(handlePoint(b, h))
            if (d <= bestD) { best = h; bestD = d }
        }
        val local = b.toLocal(p)
        val inside = abs(local.x) <= b.w / 2f && abs(local.y) <= b.h / 2f
        if (best != null && !(inside && bestD > controller.docLength(HANDLE_DRAW_DP * 1.5f))) {
            handle = best
            return Mode.RESIZE
        }
        val pad = max(tol * 0.5f, settings.strokeWidth / 2f)
        return if (abs(local.x) <= b.w / 2f + pad && abs(local.y) <= b.h / 2f + pad) Mode.MOVE else null
    }

    // ------------------------------------------------------------------ preview

    private fun buildSpec(b: ShapeBox): VectorPaintSpec? {
        val s = settings
        val color = controller.color
        return when (s.type) {
            ShapeType.LINE -> VectorPaintSpec.build(
                null, 0, VectorPath.polyline(listOf(b.start, b.end)), color, s.strokeWidth, s.lineCap, JoinStyle.ROUND,
            )
            ShapeType.ARROW -> {
                val g = ShapeGeometry.arrow(b.start, b.end, s.strokeWidth, s.arrowHeads, s.arrowHeadStyle, s.arrowHeadScale)
                VectorPaintSpec.build(null, 0, g.stroke, color, s.strokeWidth, s.lineCap, JoinStyle.ROUND, g.fill)
            }
            else -> {
                val outline = ShapeGeometry.outline(s.type, b, s.outlineParams)
                val join = if (s.type == ShapeType.ELLIPSE) JoinStyle.ROUND else ShapeGeometry.joinFor(s.corner)
                VectorPaintSpec.build(
                    if (s.style.fill) outline else null, s.fillColor ?: color,
                    if (s.style.stroke) outline else null, color, s.strokeWidth, LineCapStyle.ROUND, join,
                )
            }
        }
    }

    /** Rebuilds the compositor preview of the pending / in-creation shapes and redraws. */
    fun refreshPreview() {
        val specs = listOfNotNull(box?.let { buildSpec(it) }, creatingBox?.let { buildSpec(it) })
        if (specs.isNotEmpty()) ensureObserving()
        preview.show(targetLayer ?: controller.doc.activeLayer, specs)
        controller.invalidateOverlay()
    }

    private fun releasePreview() = preview.release()

    // ------------------------------------------------------------------ commit / discard

    override fun commit() {
        val b = box ?: return
        val layer = targetLayer ?: controller.doc.activeLayer
        if (controller.doc.indexOf(layer) < 0) { discard(); return }
        if (!controller.checkEditable(layer)) return
        val spec = buildSpec(b)
        box = null
        creatingBox = null
        targetLayer = null
        releasePreview()
        if (spec != null) VectorCommit.commit(controller, layer, listOf(spec), "Shape")
        controller.invalidateOverlay()
    }

    override fun discard() {
        box = null
        creatingBox = null
        targetLayer = null
        mode = Mode.NONE
        startBox = null
        releasePreview()
        controller.invalidateOverlay()
    }

    override fun onActivate() = ensureObserving()

    /**
     * The preview depends on state the tool doesn't own (main color, selection, layer props).
     * Started on activation and again whenever a shape appears, because some controller
     * operations call onDeactivate without a following onActivate.
     */
    private fun ensureObserving() {
        if (observeJob?.isActive == true) return
        observeJob = controller.scope.launch {
            snapshotFlow { Triple(controller.color, controller.selection, controller.layersVersion) }
                .drop(1)
                .collect { if (box != null) refreshPreview() }
        }
    }

    override fun onSelectionChanged() {
        if (box != null || creatingBox != null) refreshPreview()
    }

    override fun onDeactivate() {
        observeJob?.cancel()
        observeJob = null
        if (mode == Mode.CREATE) creatingBox = null
        mode = Mode.NONE
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
        val creating = creatingBox
        val b = creating ?: box ?: return
        if (settings.type.isLineLike) {
            if (creating != null) return
            map(t, b.start).let { painter.handle(canvas, t, it[0], it[1]) }
            map(t, b.end).let { painter.handle(canvas, t, it[0], it[1]) }
            return
        }
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
        for (h in ShapeGeometry.Handle.entries) {
            val q = map(t, handlePoint(b, h))
            painter.handle(canvas, t, q[0], q[1], square = h.fx != 0 && h.fy != 0, small = h.fx == 0 || h.fy == 0)
        }
    }

    companion object {
        private const val PREFS_KEY = "vec.shape"
        private const val TOUCH_SLOP_DP = 6f
        private const val MIN_SIZE_DP = 4f
        private const val HANDLE_TOUCH_DP = 22f
        private const val HANDLE_DRAW_DP = 7f
        private const val ROTATE_OFFSET_DP = 34f
    }
}
