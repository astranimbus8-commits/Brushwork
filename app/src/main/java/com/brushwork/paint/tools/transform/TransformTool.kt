package com.brushwork.paint.tools.transform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.IncrementMath
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.engine.LambdaAction
import com.brushwork.paint.engine.LayerRenderOverride
import com.brushwork.paint.engine.LayerTreeAction
import com.brushwork.paint.engine.RemoveLayerAction
import com.brushwork.paint.engine.SelectionAction
import com.brushwork.paint.engine.UndoAction
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.IncrementKind
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.PinchTargeting
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Move / scale / rotate / flip / distort the active layer or the selected pixels, and place
 * imported pictures.
 *
 * Activating the tool (or the first touch) "lifts" the content into a floating bitmap: the
 * selected pixels (times the selection alpha) when there is a selection, else the layer's whole
 * content cropped to its bounds (or the layer mask when editing it). While the transform is
 * pending the layer bitmap is untouched; the preview goes through `controller.renderOverride`.
 * [commit] bakes it with one undo step (the selection moves along); [discard] leaves no trace.
 * Changing the selection while a transform is pending applies it and lifts again with the new
 * selection.
 *
 * Smart guides ([snapToObjects], on by default): while dragging, resizing or nudging, the box's
 * left / center / right and top / center / bottom lines snap to the canvas edges and center, the
 * content bounds of the other visible layers and the lines drawn in them (found in the background
 * by the app-wide [com.brushwork.paint.snap.SnapService]),
 * the selection while placing a picture and, with grid snapping on, the grid; the guides are
 * drawn by [SnapGuideRenderer] (the math is the reusable [SnapGuides]). The Numbers sheet's
 * reference point ([anchor]) is what typed sizes, scales and rotations keep in place;
 * [scaleFromCenter] makes the handles scale around the center. [deleteContent] deletes what is
 * being transformed.
 *
 * Increments (v1.6 §3.4, `controller.increments`; off by default, and then every gesture is the
 * v1.5 one): once a drag is past its slop, a move goes by multiples of the Length step from where
 * it started, corner and side handles scale to multiples of the Scale step in percent of the
 * ORIGINAL size (100, 110, 120 … %), the rotation handle turns to multiples of the Angle step
 * (replacing the 45° detents) and a pinch does both (its small turns still keep the angle; its
 * translation is free). Per axis a guide that engages (an object, the canvas, or the grid) wins
 * over the step. While a gesture is stepped `increments.readout` says where it is ("+30 px, 0
 * px", "120 %", "45°"). Typed values are never stepped.
 */
class TransformTool(controller: EditorController) : Tool(controller) {
    override val id = ToolId.TRANSFORM

    enum class Mode(val label: String) { FREE("Free"), DISTORT("Distort") }

    enum class Interpolation(val label: String, val description: String) {
        SMOOTH("Smooth", "Bilinear filtering"),
        NEAREST("Nearest", "Hard pixels (pixel art)"),
    }

    // ------------------------------------------------------------------ observable options

    /** Free transform (scale/rotate handles) or distort (corners move freely, perspective). */
    var mode by mutableStateOf(Mode.FREE)

    private var keepAspectState by mutableStateOf(true)

    /** Corner handles keep the aspect ratio (free mode); also links width/height in the Numbers sheet. */
    var keepAspect: Boolean
        get() = keepAspectState
        set(v) {
            if (v == keepAspectState) return
            keepAspectState = v
            numericEdit = null // the next typed size starts from what is shown now
        }

    private val prefs get() = controller.settings.prefs

    private var fromCenterState by mutableStateOf(prefs.getBoolean(PREF_FROM_CENTER, false))

    /** Corner / edge handles scale around the center (the opposite side mirrors the dragged one). Remembered. */
    var scaleFromCenter: Boolean
        get() = fromCenterState
        set(v) {
            if (v == fromCenterState) return
            fromCenterState = v
            prefs.edit { putBoolean(PREF_FROM_CENTER, v) }
        }

    /**
     * Smart guides: while dragging, resizing or nudging, the box's left / center / right and top /
     * center / bottom lines snap to the canvas edges and center, the content of the other
     * visible layers and the lines drawn in them (the selection too while placing a picture)
     * and, when grid snapping is on, the grid. The app-wide "Snap to objects" setting
     * ([EditorController.snapping]); remembered, on by default.
     */
    var snapToObjects: Boolean
        get() = controller.snapping.enabled
        set(v) {
            if (v == controller.snapping.enabled) return
            controller.snapping.enabled = v
            if (!v) clearGuides()
            else liveSession()?.let { requestSnapBounds(it) }
        }

    private var anchorState by mutableStateOf(TransformAnchor.byName(prefs.getString(PREF_ANCHOR, null)) ?: TransformAnchor.CENTER)

    /**
     * Reference point: stays in place when a size, scale or rotation is typed or slid in the
     * Numbers sheet, and its position is what X / Y show. Remembered; the center by default.
     */
    var anchor: TransformAnchor
        get() = anchorState
        set(v) {
            if (v == anchorState) return
            anchorState = v
            numericEdit = null
            prefs.edit { putString(PREF_ANCHOR, v.name) }
        }

    private var interpolationState by mutableStateOf(Interpolation.SMOOTH)

    /** Resampling used for the preview and the commit. */
    var interpolation: Interpolation
        get() = interpolationState
        set(v) {
            if (v == interpolationState) return
            interpolationState = v
            rebuildPreviewPaint()
            transformState?.let { applyState(it) } // re-picks the mip level and redraws
        }

    /** Whether the "Numbers" sheet is shown (kept here so it survives configuration changes). */
    var numbersOpen by mutableStateOf(false)

    /** Unit used by the Numbers sheet. */
    var unit by mutableStateOf(LengthUnit.PX)

    private var nudgeStepState by mutableDoubleStateOf(1.0)

    /** Distance moved by one nudge-pad press, in document pixels (finite and > 0; other values are ignored). */
    var nudgeStepPx: Double
        get() = nudgeStepState
        set(v) { if (v.isFinite() && v > 0.0) nudgeStepState = v }

    /** The pending transform, or null when nothing is being transformed. */
    var transformState by mutableStateOf<TransformState?>(null)
        private set

    /** True while placing an imported picture (commit label "Import picture"). */
    var isPlacement by mutableStateOf(false)
        private set

    /** True while the content bounds of a large layer are being computed in the background. */
    var isPreparing by mutableStateOf(false)
        private set

    override val hasPendingWork: Boolean get() = transformState != null

    /** A lift nobody moved yet is the tool's own preparation; a placement is always the user's. */
    override val hasUserChanges: Boolean
        get() {
            val st = transformState ?: return false
            val s = session ?: return true
            return s.placement || !st.sameGeometry(s.initial)
        }

    /** Hint shown in the options strip when nothing is being transformed. */
    val statusText: String
        get() {
            controller.layersVersion // re-read when the active layer, its locks or mask editing change
            val layer = controller.activeLayer
            val mask = layer.editingMask && layer.mask != null
            return when {
                isPreparing -> "Preparing…"
                controller.doc.effectiveLocked(layer) -> "The layer is locked"
                !controller.doc.effectiveVisible(layer) -> "The layer is hidden"
                !mask && layer.alphaLocked -> "Transparency is locked on this layer"
                controller.selection != null -> "Touch the canvas to transform the selection"
                mask -> "Touch the canvas to transform the layer mask"
                else -> "Touch the canvas to transform the layer"
            }
        }

    // ------------------------------------------------------------------ session

    /** Everything about one lifted/placed floating bitmap. */
    private inner class Session(
        val layer: Layer,
        val target: EditTarget,
        /** The bitmap being edited (layer content or mask) at lift time. */
        val targetBitmap: Bitmap,
        val floating: Bitmap,
        val ownsFloating: Boolean,
        /** Area the content was lifted from (null for placements). */
        val liftRect: Rect?,
        /** Selection the content was lifted with; it moves along on commit. */
        val selection: Selection?,
        /** Value vacated mask pixels get (the mask's background). */
        maskBackground: Int,
        val initial: TransformState,
        val placement: Boolean,
        /** Undo label of a placement ("Import picture", "Paste"...). */
        val placementLabel: String = IMPORT_LABEL,
        /** Lifted vector objects (v1.5): [floating] is their preview, the lift commits the geometry. */
        val objectLift: ObjectLift? = null,
        /** Where [objectLift] came from (taps on the objects, inside or outside the box, go to it). */
        val objectProvider: ObjectLiftProvider? = null,
    ) {
        /** Source pixels -> document ([floating] may be smaller than the source: see [ObjectLift.floatingScale]). */
        val matrix = Matrix()
        val preview = Preview(this)

        /** Bitmap actually drawn: [floating], or a pre-halved copy for strong downscales. */
        var drawSource: Bitmap = floating
        /** Maps [drawSource] pixels -> document. */
        val drawMatrix = Matrix()

        /** Fills vacated mask pixels with the background, weighted by an ALPHA_8 selection. */
        val maskFill = Paint().apply { color = maskBackground }
        /** Overwrites vacated mask pixels with the background. */
        val maskReplace = Paint().apply { color = maskBackground; xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }

        /** Level 0 = [floating]; level k = half the size of level k-1 (box-filtered). */
        private val levels = arrayListOf(floating)
        /** No further level can be made (1x1 reached, or out of memory: don't retry every frame). */
        private var levelsExhausted = false

        fun level(k: Int): Bitmap {
            while (levels.size <= k && !levelsExhausted) {
                val prev = levels.last()
                if (prev.width <= 1 && prev.height <= 1) { levelsExhausted = true; break }
                val next = try {
                    Bitmap.createScaledBitmap(prev, maxOf(1, (prev.width + 1) / 2), maxOf(1, (prev.height + 1) / 2), true)
                } catch (e: OutOfMemoryError) {
                    levelsExhausted = true
                    break
                }
                levels += next
            }
            return levels[minOf(k, levels.lastIndex)]
        }

        fun releaseLevels() {
            for (i in 1 until levels.size) levels[i].recycle()
            levels.clear()
        }
    }

    /**
     * Draws the layer with the lifted area removed plus the transformed floating bitmap: exactly
     * what [commit] will write, even if the layer was changed from a menu meanwhile.
     */
    private inner class Preview(private val s: Session) : LayerRenderOverride {
        override val layer: Layer get() = s.layer

        override fun drawContent(canvas: Canvas): Boolean {
            if (s.target != EditTarget.CONTENT) return false
            val lift = s.objectLift
            if (lift != null) {
                // The layer without the lifted objects.
                lift.drawBase(canvas)
            } else {
                canvas.drawBitmap(s.layer.bitmap, 0f, 0f, null)
                clearSource(canvas, s)
            }
            drawFloating(canvas)
            return true
        }

        override fun drawMask(canvas: Canvas, maskPaint: Paint): Boolean {
            if (s.target != EditTarget.MASK) return false
            val mask = s.layer.mask ?: return false
            // Compose the edited mask offscreen, then apply it like the compositor would.
            val save = canvas.saveLayer(null, maskPaint)
            canvas.drawBitmap(mask, 0f, 0f, null)
            clearSource(canvas, s)
            drawFloating(canvas)
            canvas.restoreToCount(save)
            return true
        }

        private fun drawFloating(canvas: Canvas) {
            // The caller of startPlacement() may have recycled the picture: never crash drawing.
            if (!s.drawSource.isRecycled) canvas.drawBitmap(s.drawSource, s.drawMatrix, previewPaint)
        }
    }

    private var session: Session? = null

    /**
     * Where vector objects are lifted from on vector layers (v1.5 seam; tests substitute a fake).
     * [RefusingLiftProvider] means objects can't be lifted: the layer's pixels are transformed.
     */
    internal var objectLiftProvider: () -> ObjectLiftProvider = { controller.vectors.liftProvider }

    /** True while an object lift is being prepared (its provider calls back later). */
    private var objectLiftPending = false

    /** The provider and layer of the object lift being prepared (a pinch meanwhile is judged by its box). */
    private var pendingObjectProvider: ObjectLiftProvider? = null
    private var pendingObjectLayer: Layer? = null

    /** Identifies the object lift the tool waits for (an answer to an abandoned one is let go). */
    private var objectLiftTicket: Any? = null

    /** The provider that lifts objects of [layer] for [target], or null to lift pixels. */
    private fun objectProviderFor(layer: Layer, target: EditTarget): ObjectLiftProvider? {
        if (!layer.isVectorLayer || target != EditTarget.CONTENT) return null
        return objectLiftProvider().takeUnless { it === RefusingLiftProvider }
    }

    // ------------------------------------------------------------------ smart guides state

    /**
     * Content bounds and lines of the other layers (what the box snaps to) are found in the
     * background by the app-wide snapping service; its version changes when new ones arrive (a
     * gesture's snap targets are then rebuilt).
     */
    private val snapTargetsVersion: Int get() = controller.snapping.version

    /** Guides shown on the canvas right now (document px); empty when nothing is aligned. */
    private var guides: List<SnapGuide> = emptyList()

    /** The smart guides currently shown (while a drag / resize is snapped, or briefly after a nudge). */
    val activeGuides: List<SnapGuide> get() = guides

    /** True while the content bounds of other (large) layers are still being found for snapping. */
    val isFindingSnapTargets: Boolean get() = controller.snapping.isBusy

    /** Hides the guides a nudge showed after a moment. */
    private var guidesJob: Job? = null

    /**
     * The typed / slid size, scale or rotation in progress (see [endNumericEdit]): the state it
     * started from and the reference point, which stays in place. Every value of the edit is
     * applied to [start] (not to the previous value), so sliding back and forth or typing a
     * number digit by digit never accumulates rounding or size clamping.
     */
    private class NumericEdit(val kind: NumericKind, val start: TransformState, val pivot: Vec2)

    private var numericEdit: NumericEdit? = null

    /** Document area of the last preview (invalidated together with the next one). */
    private var lastBounds: Rect? = null

    private var activationJob: Job? = null
    private var liftJob: Job? = null

    /** True while this tool itself changes controller.selection (ignored by [onSelectionChanged]). */
    private var ownSelectionChange = false

    private val dstInPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val dstOutPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private var previewPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    // ------------------------------------------------------------------ lifecycle

    override fun onActivate() {
        if (session != null) return
        activationJob?.cancel()
        // Deferred one main-loop turn: importImageAsLayer() activates this tool on the new empty
        // layer right before calling startPlacement(), which then cancels this.
        activationJob = controller.scope.launch(Dispatchers.Main) {
            if (activationJob === coroutineContext[Job]) activationJob = null
            if (session == null && liftJob == null && controller.activeToolId == ToolId.TRANSFORM) beginLift()
        }
    }

    override fun onDeactivate() {
        cancelJobs()
        showReadout(null)
        if (hasPendingWork) commit()
        // Nothing to snap until the next transform (what is known stays cached).
        controller.snapping.cancel()
    }

    override fun onDispose() {
        cancelJobs()
    }

    /**
     * The selection changed from outside (menu, selection panel) while this tool is current.
     * A pending transform is applied where it is (the new selection is kept as the user set it,
     * not moved along) and the content is lifted again with the new selection. Placements are
     * not tied to the selection and stay as they are.
     */
    override fun onSelectionChanged() {
        if (ownSelectionChange) return
        val s = session
        when {
            s != null -> {
                if (s.placement) return
                applyPending(s, moveSelection = false)
                if (session == null) beginLift()
            }
            liftJob != null -> {
                cancelJobs()
                beginLift()
            }
        }
    }

    /**
     * Places [image] (e.g. an imported picture) onto the empty [layer] with transform handles.
     * It starts centered, shrunk to fit the canvas (with a small margin) when larger. Commit
     * draws it with the label "Import picture"; discarding removes the layer again. An
     * ARGB_8888 [image] is used directly until the placement ends, so the caller must not
     * recycle it before that.
     */
    fun startPlacement(layer: Layer, image: Bitmap) = startPlacement(layer, image, null, null, IMPORT_LABEL)

    /**
     * Like [startPlacement] but at an exact position: the image's top-left corner at ([left],
     * [top]) in document pixels, unscaled (null = centered and shrunk to fit). [label] names the
     * undo step and must equal the label of the AddLayerAction that created [layer] (so a
     * discarded placement can remove its layer without leaving history behind).
     */
    fun startPlacement(layer: Layer, image: Bitmap, left: Float?, top: Float?, label: String) {
        if (controller.activeToolId != ToolId.TRANSFORM) controller.selectTool(ToolId.TRANSFORM)
        cancelJobs()
        if (session != null) commit()
        if (controller.doc.indexOf(layer) < 0) return
        // Hardware / other configs can't be drawn into a software canvas: work on an ARGB copy.
        val usable = !image.isRecycled && image.width > 0 && image.height > 0
        val floating: Bitmap? = when {
            !usable -> null
            image.config == Bitmap.Config.ARGB_8888 -> image
            else -> try { image.copy(Bitmap.Config.ARGB_8888, false) } catch (e: OutOfMemoryError) { null }
        }
        if (floating == null) {
            controller.toast(if (usable) "Not enough memory to place the picture" else "The picture could not be placed")
            // Don't leave the empty layer behind when it is clearly the one just added for this.
            if (isFreshImportLayer(layer, label)) dropFreshLayer(layer)
            return
        }
        val doc = controller.doc
        val initial = if (left != null && top != null) {
            TransformState(floating.width, floating.height, left + floating.width / 2f, top + floating.height / 2f).snappedToPixels()
        } else {
            TransformState.placement(floating.width, floating.height, doc.width, doc.height)
        }
        startSession(
            Session(layer, EditTarget.CONTENT, layer.bitmap, floating, floating !== image, null, null, 0, initial, placement = true, placementLabel = label),
            initial,
        )
    }

    /** Lifts the active layer / selection now (same as touching the canvas while idle). */
    fun start() { if (liveSession() == null) beginLift() }

    override fun commit() {
        cancelJobs()
        val s = session ?: return
        applyPending(s, moveSelection = true)
    }

    override fun discard() {
        cancelJobs()
        val s = session ?: return
        cancelSession(s)
    }

    // ------------------------------------------------------------------ input

    private class Gesture(val hit: HandleHit, val start: TransformState, val from: Vec2, val pivot: Vec2, val rotateEdge: Int) {
        /** What the box snaps to during this gesture (built on the first move; rebuilt when layer bounds arrive). */
        var targets: SnapTargets? = null
        var targetsVersion = -1
        /** The last move snapped the box to a guide (release keeps that exact place). */
        var snapped = false
        /** Which document axes a guide decided in the last move (v1.6: the other axes take the increment). */
        var snappedX = false
        var snappedY = false
        /**
         * The finger has travelled more than [SNAP_SLOP_DP] from where it went down (latched):
         * only then does the box snap, so a tap or a resting finger's jitter never jumps it
         * onto a nearby guide.
         */
        var dragging = false
    }

    private var gesture: Gesture? = null

    override fun onDown(p: ToolPoint) {
        gesture = null
        clearGuides()
        numericEdit = null
        if (liveSession() == null && !beginLift()) return
        val st = transformState ?: return
        val t = controller.viewTransform
        val layout = HandleLayout.compute(st, { t.docToScreen(it) }, t.density)
        val hit = layout.hitTest(t.docToScreen(Vec2(p.x, p.y)))
        gesture = Gesture(hit, st, Vec2(p.x, p.y), st.center(), layout.rotateEdge)
        session?.let { if (snapToObjects && hit.kind != HandleKind.ROTATE) requestSnapBounds(it) }
        controller.invalidateOverlay()
    }

    override fun onMove(p: ToolPoint) {
        val g = gesture ?: return
        val s = session
        if (s == null) { gesture = null; return }
        if (!p.x.isFinite() || !p.y.isFinite()) return
        val to = Vec2(p.x, p.y)
        val distort = mode == Mode.DISTORT
        if (!g.dragging) {
            val t = controller.viewTransform
            if (t.docToScreen(to).distanceTo(t.docToScreen(g.from)) > t.dp(SNAP_SLOP_DP)) g.dragging = true
        }
        // Every frame snaps what the finger alone gives (never the last snapped state), so
        // moving farther than the snap distance lets go of a guide.
        val snap = if (g.dragging) snapContext(g, s) else null
        val hadGuides = guides.isNotEmpty()
        guides = emptyList()
        g.snapped = false
        g.snappedX = false
        g.snappedY = false
        stepReadout = null
        // v1.6 increments (§3.4): per axis a guide that engages wins (the grid is one of the
        // targets), else the step; with increments off every branch is the v1.5 one (I8).
        val inc = controller.increments
        val next = when (g.hit.kind) {
            HandleKind.MOVE -> {
                val step = if (g.dragging) inc.step(IncrementKind.LENGTH) else null
                if (step == null) snapMove(g, TransformHandles.move(g.start, g.from, to), snap) else moveStepped(g, to, snap, step)
            }
            HandleKind.ROTATE -> {
                val step = if (g.dragging) inc.step(IncrementKind.ANGLE) else null
                if (step == null) TransformHandles.rotate(g.start, g.pivot, g.from, to)
                else TransformHandles.rotateStepped(g.start, g.pivot, g.from, to, step).also { stepReadout = IncrementReadout.angle(it.rotationDeg) }
            }
            HandleKind.CORNER ->
                if (distort) distortCornerSnapped(g, to, snap)
                else resized(g, TransformHandles.corner(g.start, g.hit.index, g.from, to, keepAspect, scaleFromCenter), snap)
            HandleKind.EDGE ->
                if (distort) distortEdgeSnapped(g, to, snap)
                else resized(g, TransformHandles.edge(g.start, g.hit.index, g.from, to, scaleFromCenter), snap)
        }
        if (next == null) {
            // An invalid (non-convex) distort keeps the last valid shape, which is not on the guides.
            guides = emptyList()
            g.snapped = false
            stepReadout = null
        }
        showReadout(stepReadout)
        if (hadGuides || guides.isNotEmpty()) controller.invalidateOverlay()
        next ?: return
        if (next != transformState) applyState(next)
    }

    // ------------------------------------------------------------------ increments (v1.6 §3.4)

    /** What the gesture being quantized shows ([Increments.readout]); null when nothing is stepped. */
    private var stepReadout: String? = null

    /** Puts [text] in the increments readout (the InfoChip slot); null clears it. */
    private fun showReadout(text: String?) {
        val inc = controller.increments
        if (inc.readout != text) inc.readout = text
    }

    /**
     * A drag inside the box with a Length step: each axis of the move from the gesture start lands
     * on a multiple of [step], unless a guide (an object, the canvas, the grid) engages on that
     * axis, which then decides it exactly as without increments.
     */
    private fun moveStepped(g: Gesture, to: Vec2, snap: SnapContext?, step: Float): TransformState {
        val d = to - g.from
        val sx = IncrementMath.snapDelta(d.x, step)
        val sy = IncrementMath.snapDelta(d.y, step)
        val raw = TransformHandles.move(g.start, g.from, to)
        val r = snap?.let { SnapGuides.snapMove(raw.bounds(), it.targets, it.threshold) }
        val gx = r?.snappedX == true
        val gy = r?.snappedY == true
        if (r == null || (!gx && !gy)) {
            val st = g.start.translated(sx, sy)
            if (snap != null) guides = SnapGuides.guidesFor(st.bounds(), snap.targets)
            stepReadout = IncrementReadout.move(sx, sy)
            return st
        }
        val st = g.start.translated(
            if (gx) raw.cx - g.start.cx + r.dx else sx,
            if (gy) raw.cy - g.start.cy + r.dy else sy,
        ).pixelSettled()
        g.snapped = true
        g.snappedX = gx
        g.snappedY = gy
        guides = SnapGuides.guidesFor(st.bounds(), snap.targets, GUIDE_EPS)
        stepReadout = IncrementReadout.move(st.cx - g.start.cx, st.cy - g.start.cy)
        return st
    }

    /**
     * A corner / side drag in free mode ([raw]: what the finger alone gives), snapped to guides
     * ([snapResize]) and, with a Scale step, the axes no guide decided on the multiples of the
     * step in percent of the ORIGINAL size (100, 110, 120 … %; a corner keeping the aspect ratio
     * steps the overall scale, the Numbers sheet's "Scale"). Mirrored axes keep their sign.
     */
    private fun resized(g: Gesture, raw: TransformState, snap: SnapContext?): TransformState {
        val snapped = snapResize(g, raw, snap)
        if (!g.dragging) return snapped
        val step = controller.increments.step(IncrementKind.SCALE) ?: return snapped
        val start = g.start
        val kind = g.hit.kind
        val uniform = kind == HandleKind.CORNER && keepAspect
        // The box's own axes the gesture scales (side 0 / 2: top / bottom, the height).
        val movesX = kind == HandleKind.CORNER || g.hit.index % 2 == 1
        val movesY = kind == HandleKind.CORNER || g.hit.index % 2 == 0
        var guideX = false
        var guideY = false
        if (g.snapped) {
            if (uniform || !raw.isAxisAligned || !start.isAxisAligned) {
                // One factor drives the change: the guide decided all of it.
                guideX = true
                guideY = true
            } else {
                val quarter = abs(TransformState.roundHalfUp(start.rotationDeg / 90f).toInt()) % 2 == 1
                guideX = if (quarter) g.snappedY else g.snappedX
                guideY = if (quarter) g.snappedX else g.snappedY
            }
        }
        if ((!movesX || guideX) && (!movesY || guideY)) return snapped
        val base = if (g.snapped) snapped else raw
        val fx0 = base.sx / start.sx
        val fy0 = base.sy / start.sy
        val fx: Float
        val fy: Float
        if (uniform) {
            val k = start.clampUniform(TransformIncrements.uniformFactor(start.scalePercent, fx0, step))
            fx = k
            fy = k
        } else {
            fx = if (movesX && !guideX) start.clampX(TransformIncrements.axisFactor(start.sx, fx0, step)) else fx0
            fy = if (movesY && !guideY) start.clampY(TransformIncrements.axisFactor(start.sy, fy0, step)) else fy0
        }
        if (!fx.isFinite() || !fy.isFinite()) return snapped
        val fixed = TransformHandles.fixedPoint(start, kind, g.hit.index, scaleFromCenter)
        val st = start.scaledAbout(fixed, fx, fy)
        stepReadout = if (uniform) IncrementReadout.percent(st.scalePercent) else IncrementReadout.scale(abs(st.sx) * 100f, abs(st.sy) * 100f)
        return st
    }

    override fun onUp(p: ToolPoint) {
        val g = gesture ?: return
        // Lifted objects: a tap selects the object there instead (A2), outside the box or inside
        // it (tapping one object of the lifted drawing picks it alone, tapping the same spot
        // again goes one object deeper); the provider decides.
        val s0 = session
        val provider = s0?.objectProvider
        if (s0 != null && provider != null && !g.dragging && p.x.isFinite() && p.y.isFinite()) {
            val q = Vec2(p.x, p.y)
            // A tap (no drag) on a handle has no other use: an object under it is picked too, as
            // inside the box (empty space there changes nothing). The box of every object hugs
            // the drawing, so objects along its edges sit under the handles (v1.5 QA).
            val inside = g.hit.kind != HandleKind.MOVE || onQuad(g.start, q, 0f)
            // (Judged on the state before this tap.)
            val taken = provider.tap(q, inside = inside, moved = !g.start.sameGeometry(s0.initial))
            gesture = null
            clearGuides()
            // On lifted objects a tap selects (or does nothing): the finger's jitter while tapping
            // never moves them, and the pending transform is applied as it was before the tap.
            if (transformState != g.start) applyState(g.start)
            if (taken) {
                applyPending(s0, moveSelection = false)
                if (session == null) beginLift()
            }
            controller.invalidateOverlay()
            return
        }
        onMove(p)
        gesture = null
        clearGuides()
        showReadout(null)
        transformState?.let { st ->
            // A guide the box snapped to keeps it exactly there, and so does a mere tap (a scaled
            // box aligned to a guide earlier must not be nudged off it); a drag settles on whole
            // pixels.
            val end = if (g.snapped || !g.dragging) st.pixelSettled() else st.snappedToPixels()
            if (end != st) applyState(end)
        }
        controller.invalidateOverlay()
    }

    override fun onCancel() {
        val g = gesture ?: return
        gesture = null
        clearGuides()
        showReadout(null)
        if (session != null && transformState != g.start) applyState(g.start)
        controller.invalidateOverlay()
    }

    // ------------------------------------------------------------------ smart guides

    /** Snap targets and distance (document px) for one frame of a gesture, or null when snapping is off. */
    private class SnapContext(val targets: SnapTargets, val threshold: Float)

    private fun snapContext(g: Gesture, s: Session): SnapContext? {
        if (!snapToObjects) return null
        val t = controller.viewTransform
        val threshold = t.screenToDocLength(t.dp(SNAP_DISTANCE_DP))
        if (!threshold.isFinite() || threshold <= 0f) return null
        var targets = g.targets
        if (targets == null || g.targetsVersion != snapTargetsVersion) {
            targets = buildSnapTargets(s)
            g.targets = targets
            g.targetsVersion = snapTargetsVersion
        }
        return SnapContext(targets, threshold)
    }

    /**
     * What the transformed box aligns to: the canvas edges and center, the content bounds of the
     * other visible layers (top-most first) and the lines drawn in them, the selection while
     * placing a picture, and the grid when grid snapping is on.
     */
    private fun buildSnapTargets(s: Session): SnapTargets =
        controller.snapping.targets(exclude = listOf(s.layer), includeSelection = s.placement, includeGrid = true)

    /** Starts finding the content bounds / lines of the layers the box of [s] can snap to (cached). */
    private fun requestSnapBounds(s: Session) = controller.snapping.prepare(listOf(s.layer))

    /** A dragged box: moved onto the closest guide within reach (unscaled content stays on whole pixels). */
    private fun snapMove(g: Gesture, raw: TransformState, snap: SnapContext?): TransformState {
        if (snap == null) return raw
        val r = SnapGuides.snapMove(raw.bounds(), snap.targets, snap.threshold)
        if (!r.snappedX && !r.snappedY) {
            guides = r.guides
            return raw
        }
        val st = raw.translated(r.dx, r.dy).pixelSettled()
        g.snapped = true
        g.snappedX = r.snappedX
        g.snappedY = r.snappedY
        // Half-pixel lines (odd centers) settle half a pixel away: still show them as aligned.
        guides = SnapGuides.guidesFor(st.bounds(), snap.targets, GUIDE_EPS)
        return st
    }

    /**
     * A corner / edge drag of an axis-aligned box: the dragged side snaps to the closest guide
     * within reach (both sides for corners; with keep-aspect only the closer one, the other side
     * follows the ratio). The fixed side / center stays where it is. Rotated boxes: see
     * [snapResizeOneFactor].
     */
    private fun snapResize(g: Gesture, raw: TransformState, snap: SnapContext?): TransformState {
        if (snap == null) return raw
        val kind = g.hit.kind
        val fixed = TransformHandles.fixedPoint(g.start, kind, g.hit.index, scaleFromCenter)
        if (!raw.isAxisAligned || !g.start.isAxisAligned) return snapResizeOneFactor(g, raw, snap, fixed)
        val h = TransformHandles.handlePoint(raw, kind, g.hit.index)
        val movesX = abs(h.x - fixed.x) > 1e-3f
        val movesY = abs(h.y - fixed.y) > 1e-3f
        val hx = if (movesX) SnapGuides.snapValue(h.x, SnapAxis.X, snap.targets, snap.threshold) else null
        val hy = if (movesY) SnapGuides.snapValue(h.y, SnapAxis.Y, snap.targets, snap.threshold) else null
        if (hx == null && hy == null) return raw
        fun factor(target: Float, now: Float, pivot: Float): Float? {
            val k = (target - pivot) / (now - pivot)
            return k.takeIf { it.isFinite() && it > 0f }
        }
        val uniform = kind == HandleKind.CORNER && keepAspect
        val snapped = if (uniform) {
            // One ratio for both axes: the closer guide decides.
            val k = if (hx != null && (hy == null || hx.distance <= hy.distance)) factor(hx.pos, h.x, fixed.x)
            else hy?.let { factor(it.pos, h.y, fixed.y) }
            k?.let { raw.scaledAbout(fixed, raw.clampUniform(it), raw.clampUniform(it)) }
        } else {
            val kx = hx?.let { factor(it.pos, h.x, fixed.x) } ?: 1f
            val ky = hy?.let { factor(it.pos, h.y, fixed.y) } ?: 1f
            raw.scaledAlongDocAxes(fixed, kx, ky)
        } ?: return raw
        if (snapped.width < TransformState.MIN_SIZE || snapped.height < TransformState.MIN_SIZE) return raw
        g.snapped = true
        g.snappedX = uniform || hx != null
        g.snappedY = uniform || hy != null
        // Guides only for the dragged sides.
        val hs = TransformHandles.handlePoint(snapped, kind, g.hit.index)
        val xEdges = if (movesX) listOf(if (hs.x < fixed.x) SnapEdge.START else SnapEdge.END) else emptyList()
        val yEdges = if (movesY) listOf(if (hs.y < fixed.y) SnapEdge.START else SnapEdge.END) else emptyList()
        guides = SnapGuides.guidesFor(snapped.bounds(), snap.targets, GUIDE_EPS, xEdges, yEdges)
        return snapped
    }

    /**
     * Resizing a rotated (or perspective) box where ONE factor drives the change around the fixed
     * point: a corner handle keeping the aspect ratio (both axes) or a side handle (one axis).
     * Each line of its bounds then moves piecewise linearly with that factor, so the closest
     * moving line within reach of a guide is solved for (on the line through the current state
     * and a slightly larger one, plus one secant step when another corner takes over that side)
     * and kept only when it really lands on the guide. Corner handles without the aspect lock
     * move two factors at once and stay free.
     */
    private fun snapResizeOneFactor(g: Gesture, raw: TransformState, snap: SnapContext, fixed: Vec2): TransformState {
        val kind = g.hit.kind
        if (kind == HandleKind.CORNER && !keepAspect) return raw
        if (kind != HandleKind.CORNER && kind != HandleKind.EDGE) return raw
        fun scaled(k: Float): TransformState = when {
            kind == HandleKind.CORNER -> raw.clampUniform(k).let { raw.scaledAbout(fixed, it, it) }
            g.hit.index % 2 == 0 -> raw.scaledAbout(fixed, 1f, raw.clampY(k))
            else -> raw.scaledAbout(fixed, raw.clampX(k), 1f)
        }
        val b0 = raw.bounds()
        val b1 = scaled(1f + PROBE_FACTOR).bounds()
        val xEdges = ArrayList<SnapEdge>(3)
        val yEdges = ArrayList<SnapEdge>(3)
        var best: TransformState? = null
        var bestD = Float.POSITIVE_INFINITY
        for (axis in SnapAxis.entries) {
            for (e in SnapEdge.entries) {
                val v0 = SnapGuides.feature(b0, axis, e)
                val slope = (SnapGuides.feature(b1, axis, e) - v0) / PROBE_FACTOR
                // Lines through the fixed point stay put: they are not what the finger drags.
                if (!(abs(slope) >= MIN_LINE_SLOPE)) continue
                if (axis == SnapAxis.X) xEdges += e else yEdges += e
                val hit = SnapGuides.snapValue(v0, axis, snap.targets, snap.threshold) ?: continue
                if (hit.distance >= bestD) continue
                var k = 1f + (hit.pos - v0) / slope
                if (!k.isFinite() || k <= 0f) continue
                var cand = scaled(k)
                var v = SnapGuides.feature(cand.bounds(), axis, e)
                if (abs(v - hit.pos) > GUIDE_EPS && abs(k - 1f) > 1e-6f) {
                    val s2 = (v - v0) / (k - 1f)
                    if (!(abs(s2) >= MIN_LINE_SLOPE)) continue
                    k = 1f + (hit.pos - v0) / s2
                    if (!k.isFinite() || k <= 0f) continue
                    cand = scaled(k)
                    v = SnapGuides.feature(cand.bounds(), axis, e)
                }
                if (abs(v - hit.pos) > GUIDE_EPS) continue
                if (cand.width < TransformState.MIN_SIZE || cand.height < TransformState.MIN_SIZE) continue
                best = cand
                bestD = hit.distance
            }
        }
        val snapped = best ?: return raw
        g.snapped = true
        g.snappedX = true
        g.snappedY = true
        guides = SnapGuides.guidesFor(snapped.bounds(), snap.targets, GUIDE_EPS, xEdges, yEdges)
        return snapped
    }

    /** A distort corner drag: the corner snaps to guides on both axes. */
    private fun distortCornerSnapped(g: Gesture, to: Vec2, snap: SnapContext?): TransformState? {
        val step = if (g.dragging) controller.increments.step(IncrementKind.LENGTH) else null
        if (step != null) return distortCornerStepped(g, to, snap, step)
        var target = to
        if (snap != null) {
            val q = g.start.corner(g.hit.index) + (to - g.from)
            val (p, gs) = SnapGuides.snapPoint(q, snap.targets, snap.threshold)
            target = to + (p - q)
            guides = gs
            g.snapped = gs.isNotEmpty()
        }
        return TransformHandles.distortCorner(g.start, g.hit.index, g.from, target)
    }

    /**
     * A distort edge drag (both corners of the edge move together): the edge's ends and middle
     * snap like a thin box being moved.
     */
    private fun distortEdgeSnapped(g: Gesture, to: Vec2, snap: SnapContext?): TransformState? {
        val step = if (g.dragging) controller.increments.step(IncrementKind.LENGTH) else null
        var target = to
        var gx = false
        var gy = false
        if (snap != null) {
            val d = to - g.from
            val a = g.start.corner(g.hit.index) + d
            val b = g.start.corner((g.hit.index + 1) % 4) + d
            val seg = DocBox(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
            val r = SnapGuides.snapMove(seg, snap.targets, snap.threshold)
            if (r.snappedX || r.snappedY) {
                target = to + Vec2(r.dx, r.dy)
                guides = r.guides
                g.snapped = true
                gx = r.snappedX
                gy = r.snappedY
            }
        }
        if (step != null) {
            // v1.6: the axes no guide decided move by multiples of the Length step.
            val d = to - g.from
            val dx = if (gx) target.x - g.from.x else IncrementMath.snapDelta(d.x, step)
            val dy = if (gy) target.y - g.from.y else IncrementMath.snapDelta(d.y, step)
            target = g.from + Vec2(dx, dy)
            stepReadout = IncrementReadout.move(dx, dy)
        }
        return TransformHandles.distortEdge(g.start, g.hit.index, g.from, target)
    }

    /**
     * A distort corner drag with a Length step: the corner snaps to guides on each axis as
     * without increments; an axis no guide decided moves by a multiple of [step] from where it was.
     */
    private fun distortCornerStepped(g: Gesture, to: Vec2, snap: SnapContext?, step: Float): TransformState? {
        val d = to - g.from
        val q = g.start.corner(g.hit.index) + d
        val hx = snap?.let { SnapGuides.snapValue(q.x, SnapAxis.X, it.targets, it.threshold) }
        val hy = snap?.let { SnapGuides.snapValue(q.y, SnapAxis.Y, it.targets, it.threshold) }
        val dx = if (hx != null) d.x + (hx.pos - q.x) else IncrementMath.snapDelta(d.x, step)
        val dy = if (hy != null) d.y + (hy.pos - q.y) else IncrementMath.snapDelta(d.y, step)
        if (snap != null && (hx != null || hy != null)) {
            guides = SnapGuides.snapPoint(q, snap.targets, snap.threshold).second
            g.snapped = true
        }
        stepReadout = IncrementReadout.move(dx, dy)
        return TransformHandles.distortCorner(g.start, g.hit.index, g.from, g.from + Vec2(dx, dy))
    }

    /** Shows [list] for a moment (after a nudge). */
    private fun showGuidesBriefly(list: List<SnapGuide>) {
        guidesJob?.cancel()
        guides = list
        controller.invalidateOverlay()
        if (list.isEmpty()) return
        guidesJob = controller.scope.launch(Dispatchers.Main) {
            delay(NUDGE_GUIDES_MS)
            guides = emptyList()
            controller.invalidateOverlay()
        }
    }

    private fun clearGuides() {
        guidesJob?.cancel()
        guidesJob = null
        if (guides.isEmpty()) return
        guides = emptyList()
        controller.invalidateOverlay()
    }

    // ------------------------------------------------------------------ two-finger pinch

    /**
     * Two fingers scaling / rotating / moving the content. [focus] is their midpoint when the
     * pinch started (document px); the cumulative change reported by the canvas is applied to
     * [start] (see [TransformHandles.pinch]).
     */
    private class Pinch(val focus: Vec2) {
        /** State when the pinch started; null while the content is still being lifted (large layers). */
        var start: TransformState? = null
        var translation: Vec2 = Vec2.ZERO
        var scale: Float = 1f
        var rotationDeg: Float = 0f
        /** The fingers lifted (not cancelled) before the lift finished: apply as soon as it lands. */
        var ended: Boolean = false
    }

    private var pinch: Pinch? = null

    /**
     * Takes a two-finger gesture when finger [a] or finger [b] lands inside the box as drawn
     * (see [PinchTargeting]: a little grace, small boxes enlarged; their midpoint [focus] is only
     * the pivot), including an imported or pasted picture being placed. Nothing lifted yet: the
     * content is lifted first when a finger is on it. Otherwise the canvas zooms the view, even
     * when the fingers straddle the content.
     */
    override fun onTwoFingerStart(focus: Vec2, a: Vec2, b: Vec2): Boolean {
        pinch = null
        val pts = listOf(focus, a, b)
        if (pts.any { !it.x.isFinite() || !it.y.isFinite() }) return false
        val t = controller.viewTransform
        if (liveSession() == null && !liftForPinch(a, b)) return false
        val p = Pinch(focus)
        val st = transformState
        if (session != null && st != null) {
            if (!PinchTargeting.acceptsQuad(a, b, st.corners(), t)) return false
            p.start = st
        }
        // else: the content under the fingers is being lifted in the background (startSession
        // hands it to the pinch).
        gesture = null
        numericEdit = null
        clearGuides()
        pinch = p
        controller.invalidateOverlay()
        return true
    }

    override fun onTwoFingerGesture(translation: Vec2, scale: Float, rotationDeg: Float) {
        val p = pinch ?: return
        if (!translation.x.isFinite() || !translation.y.isFinite() || !scale.isFinite() || !rotationDeg.isFinite()) return
        p.translation = translation
        p.scale = scale
        p.rotationDeg = rotationDeg
        applyPinch(p)
    }

    override fun onTwoFingerEnd(cancelled: Boolean) {
        val p = pinch ?: return
        if (p.start == null) {
            // Still lifting: a finished pinch is applied when the content lands.
            if (cancelled || (liftJob == null && !objectLiftPending)) pinch = null else p.ended = true
            return
        }
        finishPinch(p, cancelled)
    }

    private fun applyPinch(p: Pinch) {
        val start = p.start ?: return
        if (liveSession() == null) { pinch = null; return }
        // v1.6 increments: the scale on the Scale step (percent of the original), the angle on the Angle step.
        val inc = controller.increments
        val scaleStep = inc.step(IncrementKind.SCALE)
        val angleStep = inc.step(IncrementKind.ANGLE)
        val next = if (scaleStep == null && angleStep == null) {
            TransformHandles.pinch(start, p.focus, p.translation, p.scale, p.rotationDeg)
        } else {
            TransformHandles.pinchStepped(start, p.focus, p.translation, p.scale, p.rotationDeg, scaleStep, angleStep).also { st ->
                showReadout(listOfNotNull(scaleStep?.let { IncrementReadout.percent(st.scalePercent) }, angleStep?.let { IncrementReadout.angle(st.rotationDeg) }).joinToString(" · "))
            }
        }
        if (next != transformState) applyState(next)
    }

    /** Ends the pinch: [cancelled] goes back to where it started, else it settles on whole pixels. */
    private fun finishPinch(p: Pinch, cancelled: Boolean) {
        if (pinch === p) pinch = null
        showReadout(null)
        val start = p.start ?: return
        if (liveSession() == null) return
        val st = transformState ?: return
        val end = if (cancelled) start else st.snappedToPixels()
        if (end != st) applyState(end)
        controller.invalidateOverlay()
    }

    /**
     * Nothing is lifted yet: lifts the active layer / selection when finger [a] or [b] is on
     * what would be lifted (§4.7): the selection's bounds; a small layer's content bounds (or the
     * cached bounds of a large one); otherwise the pixels right under either finger. True when a
     * transform is now in progress, or being prepared in the background for content right under
     * a finger (large layers).
     */
    private fun liftForPinch(a: Vec2, b: Vec2): Boolean {
        val src = liftSource(report = false) ?: return false
        val t = controller.viewTransform
        val probeRadius = t.screenToDocLength(t.dp(PROBE_RADIUS_DP))
        val pts = listOf(a, b)
        // Already being prepared (the first finger's touch started it): the pixels under the
        // fingers tell whether they are on the content.
        if (liftJob != null) return probe(src, pts, probeRadius)
        // Vector objects (v1.5): judged by the box their lift will show, also while that lift is
        // still being prepared in the background (it takes the pinch over when it lands).
        val provider = if (objectLiftPending) pendingObjectProvider.takeIf { pendingObjectLayer === src.layer } else objectProviderFor(src.layer, src.target)
        if (objectLiftPending && provider == null) return false
        if (provider != null) {
            val box = provider.liftBox(src.layer)
            val onObjects = if (box != null) PinchTargeting.acceptsRect(a, b, box, t) else onContent(src, a, b, pts, probeRadius) == true
            if (!onObjects) return false
            if (objectLiftPending) return true
            beginLift()
            return session != null || objectLiftPending
        }
        val sel = controller.selection
        if (sel != null) {
            if (!PinchTargeting.acceptsRect(a, b, RectF(sel.bounds), t)) return false
            return lift(src, sel.bounds, sel)
        }
        val known = if (isSmall(src.bitmap)) {
            ContentBounds.of(src.bitmap, src.empty) ?: return false
        } else {
            controller.snapping.bounds(src.layer).takeIf { src.target == EditTarget.CONTENT }
        }
        if (known != null) {
            if (!PinchTargeting.acceptsRect(a, b, RectF(known), t)) return false
            if (isSmall(src.bitmap)) return lift(src, known, null)
        } else if (!probe(src, pts, probeRadius)) {
            return false
        }
        beginLift()
        return session != null || liftJob != null
    }

    /**
     * Whether finger [a] or [b] is on what a lift of [src] would take, judged by its pixels: the
     * selection's bounds, the content bounds (known or found on a small layer), else the pixels
     * right under the fingers. Null when a small layer is empty.
     */
    private fun onContent(src: LiftSource, a: Vec2, b: Vec2, pts: List<Vec2>, probeRadius: Float): Boolean? {
        val t = controller.viewTransform
        controller.selection?.let { return PinchTargeting.acceptsRect(a, b, RectF(it.bounds), t) }
        val known = if (isSmall(src.bitmap)) {
            ContentBounds.of(src.bitmap, src.empty) ?: return null
        } else {
            controller.snapping.bounds(src.layer).takeIf { src.target == EditTarget.CONTENT }
        }
        return if (known != null) PinchTargeting.acceptsRect(a, b, RectF(known), t) else probe(src, pts, probeRadius)
    }

    /** True when [src] has content within [radius] (capped) of one of [pts]: a few small reads, no full scan. */
    private fun probe(src: LiftSource, pts: List<Vec2>, radius: Float): Boolean {
        val r = radius.coerceIn(1f, MAX_PROBE_RADIUS)
        val area = Rect()
        return pts.any { p ->
            area.set(floor(p.x - r).toInt(), floor(p.y - r).toInt(), ceil(p.x + r).toInt() + 1, ceil(p.y + r).toInt() + 1)
            ContentBounds.of(src.bitmap, src.empty, region = area) != null
        }
    }

    /** [p] inside the transformed quad or within [tol] of its outline (document px). */
    private fun onQuad(st: TransformState, p: Vec2, tol: Float): Boolean {
        val c = st.corners()
        if (Geometry.pointInPolygon(p, c)) return true
        for (i in 0 until 4) if (Geometry.distanceToSegment(p, c[i], c[(i + 1) % 4]) <= tol) return true
        return false
    }

    // ------------------------------------------------------------------ commands (options strip / Numbers)

    /** Moves by a document-pixel offset (exactly: no snapping). */
    fun moveBy(dx: Float, dy: Float) {
        if (dx.isFinite() && dy.isFinite()) update { it.translated(dx, dy) }
    }

    /**
     * One nudge-pad press: moves by [nudgeStepPx] in the given direction (-1, 0, 1). With
     * [snapToObjects] a nudge that would jump over a guide stops on it (so repeated presses
     * land on alignments), and the guides the box then lines up with show for a moment.
     */
    fun nudge(dx: Int, dy: Int) {
        val mx = (dx * nudgeStepPx).toFloat()
        val my = (dy * nudgeStepPx).toFloat()
        if (!mx.isFinite() || !my.isFinite()) return
        val s = liveSession() ?: return
        if (!snapToObjects) { moveBy(mx, my); return }
        requestSnapBounds(s)
        var shown: List<SnapGuide> = emptyList()
        update { st ->
            val targets = buildSnapTargets(s)
            val r = SnapGuides.snapNudge(st.bounds(), mx, my, targets)
            val moved = st.translated(r.dx, r.dy)
            // Stopped on a guide (maybe a half-pixel one): unscaled content stays on whole pixels.
            // A full step moves by exactly the step, as without snapping.
            val next = if (r.dx != mx || r.dy != my) moved.pixelSettled() else moved
            shown = SnapGuides.guidesFor(next.bounds(), targets, GUIDE_EPS)
            next
        }
        showGuidesBriefly(shown)
    }

    /** Places the bounds' left/top edge (document pixels; null = unchanged). Non-finite values are ignored. */
    fun setPosition(left: Double? = null, top: Double? = null) {
        val l = left?.takeIf { it.isFinite() }
        val t = top?.takeIf { it.isFinite() }
        if (l == null && t == null) return
        update { it.withPosition(l?.toFloat(), t?.toFloat()) }
    }

    /** Places the [anchor] point of the bounds (document pixels; null = unchanged). Non-finite values are ignored. */
    fun setAnchorPosition(x: Double? = null, y: Double? = null) {
        val ax = x?.takeIf { it.isFinite() }
        val ay = y?.takeIf { it.isFinite() }
        if (ax == null && ay == null) return
        update { it.withAnchorAt(anchor, ax?.toFloat(), ay?.toFloat()) }
    }

    /** Position of the [anchor] point of the current bounds (document px), or null when nothing is transformed. */
    val anchorPosition: Vec2? get() = transformState?.anchorPoint(anchor)

    /**
     * Sets width and/or height (document pixels), honoring [keepAspect]; the [anchor] point stays
     * in place. Non-finite values are ignored.
     */
    fun setSize(width: Double? = null, height: Double? = null) {
        val w = width?.takeIf { it.isFinite() }
        val h = height?.takeIf { it.isFinite() }
        if (w == null && h == null) return
        val kind = when {
            w != null && h != null -> NumericKind.SIZE
            w != null -> NumericKind.WIDTH
            else -> NumericKind.HEIGHT
        }
        numeric(kind) { e -> e.start.withSizeAbout(e.pivot, w?.toFloat(), h?.toFloat(), keepAspect) }
    }

    /** Sets the absolute rotation in degrees, turning around the [anchor] point. */
    fun setRotation(degrees: Double) {
        if (degrees.isFinite()) numeric(NumericKind.ROTATION) { e -> e.start.withRotationAbout(e.pivot, degrees.toFloat()) }
    }

    /** Sets a uniform scale relative to the original size (100 = original), keeping the [anchor] point in place. */
    fun setScalePercent(percent: Double) {
        if (percent.isFinite()) numeric(NumericKind.SCALE) { e -> e.start.withScalePercentAbout(e.pivot, percent.toFloat()) }
    }

    /**
     * A typed or slid size / scale / rotation is complete: the next one starts from the state and
     * reference point shown then. Until then every value of the edit is applied to the state it
     * started from, around the reference point as it was then, so sliding back and forth returns
     * exactly to the same place and typing "1", "10", "100" ends where typing "100" does.
     */
    fun endNumericEdit() { numericEdit = null }

    private enum class NumericKind { WIDTH, HEIGHT, SIZE, ROTATION, SCALE }

    /**
     * Applies one value of a numeric edit of [kind]. An edit of another kind (a different field,
     * e.g. its +/- buttons pressed while another field still has the focus) starts over from the
     * current state, so it never undoes what that field did.
     */
    private fun numeric(kind: NumericKind, f: (NumericEdit) -> TransformState) {
        update(numeric = true) { st ->
            val e = numericEdit?.takeIf { it.kind == kind } ?: NumericEdit(kind, st, st.anchorPoint(anchor)).also { numericEdit = it }
            f(e)
        }
    }

    /**
     * Deletes what is being transformed as ONE undo step "Delete": lifted pixels are removed
     * from the layer (the area they were lifted from is cleared and nothing is put back; a
     * lifted selection stays where it is), and a picture being placed is removed together with
     * its new layer (undoing the step brings the layer back with the picture on it). Returns
     * false if nothing was deleted (nothing lifted, the layer is locked...).
     */
    fun deleteContent(): Boolean {
        val s = liveSession() ?: return false
        gesture = null
        pinch = null
        numericEdit = null
        clearGuides()
        return if (s.placement) deletePlacement(s) else deleteLifted(s)
    }

    private fun deleteLifted(s: Session): Boolean {
        val layer = s.layer
        if (controller.doc.effectiveLocked(layer)) {
            controller.toast("Layer \"${layer.name}\" is locked")
            return false
        }
        s.objectLift?.let { lift ->
            val deleted = lift.delete(DELETE_LABEL)
            endSession(s)
            return deleted
        }
        if (s.target == EditTarget.CONTENT && layer.alphaLocked) {
            controller.toast("Transparency is locked on \"${layer.name}\". Unlock it to delete.")
            return false
        }
        val area = s.liftRect ?: return false
        val rec = controller.beginEdit(layer, s.target)
        try {
            rec.touch(area)
            clearSource(Canvas(s.targetBitmap), s)
        } catch (e: OutOfMemoryError) {
            rec.abort()
            controller.toast("Not enough memory to delete this")
            return false
        }
        endSession(s)
        return controller.commitEdit(rec, DELETE_LABEL)
    }

    private fun deletePlacement(s: Session): Boolean {
        val doc = controller.doc
        val layer = s.layer
        if (doc.pixelLayerCount <= 1) {
            // Its layer is the only one left (the others were deleted meanwhile) and a drawing
            // needs one: just drop the picture.
            cancelSession(s)
            return true
        }
        // Place the picture first (the import / paste step), so undoing the delete brings it back.
        val placed = applyPending(s, moveSelection = false)
        val idx = doc.indexOf(layer)
        if (idx < 0) return placed
        if (!placed) {
            // Nothing was drawn (e.g. the picture lies off the canvas): the empty layer just goes.
            if (!controller.doc.effectiveLocked(layer)) removePlacementLayer(layer, s.placementLabel)
            return doc.indexOf(layer) < 0
        }
        controller.structural {
            doc.layers.removeAt(idx)
            doc.activeLayerIndex = min((idx - 1).coerceAtLeast(0), doc.layers.lastIndex)
        }
        controller.pushUndo(RemoveLayerAction(layer, idx, DELETE_LABEL))
        return true
    }

    /** Mirrors along the content's own axes. */
    fun flip(horizontal: Boolean) = update { it.flipped(horizontal) }

    /** Quarter turn around the center (pixel-exact for unscaled content). */
    fun rotate90(clockwise: Boolean) = update { it.rotated90(clockwise) }

    /** Back to where the transform started (the original position, or the initial placement). */
    fun reset() {
        val s = liveSession() ?: return
        update { s.initial }
    }

    /** Scales uniformly to fit the canvas, centered, straightened (flips are kept). */
    fun fitToCanvas() = update { it.fittedTo(controller.doc.width, controller.doc.height) }

    /**
     * Applies a command to the pending transform (not while a finger is moving it). Anything but
     * a [numeric] size / scale / rotation edit starts the next numeric edit from a fresh
     * reference point, and any command hides the guides.
     */
    private inline fun update(numeric: Boolean = false, f: (TransformState) -> TransformState) {
        if (liveSession() == null || gesture != null || pinch != null) return
        val st = transformState ?: return
        if (!numeric) numericEdit = null
        clearGuides()
        val next = f(st)
        if (next != st) applyState(next)
    }

    // ------------------------------------------------------------------ lifting

    /**
     * Lifts the active layer's content (or selection) into a floating bitmap. Returns true if a
     * transform is now in progress; false if there is nothing to do or the bounds of a large
     * layer are being computed in the background (the transform then starts by itself).
     */
    private fun beginLift(): Boolean {
        if (session != null) return true
        if (liftJob != null || objectLiftPending) return false
        val src = liftSource(report = true) ?: return false
        objectProviderFor(src.layer, src.target)?.let { return beginObjectLift(it, src) }
        val sel = controller.selection
        if (sel != null) return lift(src, sel.bounds, sel)
        val bmp = src.bitmap
        if (isSmall(bmp)) {
            val r = ContentBounds.of(bmp, src.empty) ?: return nothingToTransform(src.target)
            return lift(src, r, null)
        }
        // Large layer: find the content bounds off the main thread, then lift. The bitmap is only
        // read; the result is thrown away if the layer changed meanwhile (contentVersion).
        val layer = src.layer
        val version = layer.contentVersion
        isPreparing = true
        liftJob = controller.scope.launch(Dispatchers.Main) {
            val me = coroutineContext[Job]
            val r = try {
                withContext(Dispatchers.Default) { ContentBounds.of(bmp, src.empty) { !isActive } }
            } finally {
                if (liftJob === me) { liftJob = null; isPreparing = false }
            }
            val stillWanted = session == null && controller.activeToolId == ToolId.TRANSFORM &&
                controller.activeLayer === layer && controller.selection == null &&
                targetBitmapOf(layer, controller.editTargetOf(layer)) === bmp
            when {
                !stillWanted -> {}
                layer.contentVersion != version -> beginLift() // changed meanwhile (e.g. undo): rescan
                r == null -> nothingToTransform(src.target)
                else -> lift(src, r, null)
            }
            // A pinch that waited for this lift and already ended has nothing to apply to now.
            if (session == null && liftJob == null) pinch?.let { if (it.start == null && it.ended) pinch = null }
        }
        return false
    }

    /**
     * Lifts vector objects of [src]'s layer through [provider] (v1.5): the session starts when
     * the provider hands over the lift (now, or after a background render). Returns true if a
     * transform is now in progress.
     */
    private fun beginObjectLift(provider: ObjectLiftProvider, src: LiftSource): Boolean {
        val layer = src.layer
        objectLiftPending = true
        isPreparing = true
        pendingObjectProvider = provider
        pendingObjectLayer = layer
        val ticket = Any()
        objectLiftTicket = ticket
        val accepted = provider.lift(layer) { lift ->
            // Given up meanwhile (✓, ✕, another tool, a newer lift): the objects are let go.
            if (objectLiftTicket !== ticket) { lift?.release(); return@lift }
            endObjectLiftWait()
            if (lift == null) { dropWaitingPinch(); return@lift }
            val stillWanted = session == null && controller.activeToolId == ToolId.TRANSFORM && controller.activeLayer === layer &&
                controller.doc.indexOf(layer) >= 0 && !lift.floating.isRecycled && lift.sourceRect.width() > 0 && lift.sourceRect.height() > 0
            if (!stillWanted) { lift.release(); dropWaitingPinch(); return@lift }
            startObjectSession(lift, provider)
        }
        if (!accepted) {
            endObjectLiftWait()
            return false
        }
        return session != null
    }

    private fun endObjectLiftWait() {
        objectLiftPending = false
        isPreparing = false
        pendingObjectProvider = null
        pendingObjectLayer = null
        objectLiftTicket = null
    }

    /** A pinch that waited for a lift which brought nothing has nothing to act on. */
    private fun dropWaitingPinch() {
        if (session == null && pinch?.start == null) pinch = null
    }

    private fun startObjectSession(lift: ObjectLift, provider: ObjectLiftProvider) {
        val r = lift.sourceRect
        val initial = TransformState.identity(r.left, r.top, r.width(), r.height())
        startSession(
            Session(lift.layer, EditTarget.CONTENT, lift.layer.bitmap, lift.floating, false, Rect(r), null, 0, initial, placement = false, objectLift = lift, objectProvider = provider),
            initial,
        )
    }

    /** What a lift takes: the active layer's content (or mask) bitmap and its "empty" value. */
    private class LiftSource(val layer: Layer, val target: EditTarget, val bitmap: Bitmap, val maskBackground: Int) {
        /** Empty pixels for [ContentBounds] (null = transparent). */
        val empty: Int? get() = if (target == EditTarget.MASK) maskBackground else null
    }

    /**
     * The active layer as a lift source, or null when it can't be transformed now (locked,
     * hidden, transparency locked). [report] explains why with a message.
     */
    private fun liftSource(report: Boolean): LiftSource? {
        val layer = controller.activeLayer
        if (report) {
            if (!controller.checkEditable(layer)) return null
        } else if (controller.doc.effectiveLocked(layer) || !controller.doc.effectiveVisible(layer)) {
            return null
        }
        val target = controller.editTargetOf(layer)
        if (target == EditTarget.CONTENT && layer.alphaLocked) {
            if (report) controller.toast("Transparency is locked on \"${layer.name}\". Unlock it to transform.")
            return null
        }
        val bmp = targetBitmapOf(layer, target) ?: return null
        return LiftSource(layer, target, bmp, if (target == EditTarget.MASK) maskBackground(bmp) else 0)
    }

    /** Small enough to find the content bounds on the main thread. */
    private fun isSmall(bmp: Bitmap) = bmp.width.toLong() * bmp.height <= SYNC_SCAN_PIXELS

    private fun nothingToTransform(target: EditTarget): Boolean {
        controller.toast(if (target == EditTarget.MASK) "Nothing to transform on this mask" else "Nothing to transform on this layer")
        return false
    }

    private fun lift(src: LiftSource, rect: Rect, sel: Selection?): Boolean {
        val layer = src.layer
        val target = src.target
        val bmp = src.bitmap
        val bg = src.maskBackground
        val r = Rect(rect)
        if (!r.intersect(0, 0, bmp.width, bmp.height)) return false
        val floating = try {
            BitmapUtils.createLayerBitmap(r.width(), r.height())
        } catch (e: OutOfMemoryError) {
            controller.toast("Not enough memory to transform this")
            return false
        }
        val c = Canvas(floating)
        c.drawBitmap(bmp, -r.left.toFloat(), -r.top.toFloat(), null)
        if (sel != null) BitmapUtils.maskWith(c, sel.mask, -r.left.toFloat(), -r.top.toFloat())
        val initial = TransformState.identity(r.left, r.top, r.width(), r.height())
        startSession(Session(layer, target, bmp, floating, true, r, sel, bg, initial, placement = false), initial)
        return true
    }

    private fun startSession(s: Session, state: TransformState) {
        session = s
        isPlacement = s.placement
        rebuildPreviewPaint()
        controller.renderOverride = s.preview
        lastBounds = s.liftRect?.let { Rect(it) }
        numericEdit = null
        applyState(state)
        // Find what it can snap to before the first drag (cached per layer content).
        if (snapToObjects) requestSnapBounds(s)
        // A pinch that began while this content was being lifted in the background takes over.
        pinch?.let { p ->
            if (p.start != null) return@let
            p.start = state
            applyPinch(p)
            if (p.ended) finishPinch(p, cancelled = false)
        }
    }

    // ------------------------------------------------------------------ finishing

    /**
     * Bakes the pending transform into the layer with ONE undo step. With [moveSelection] the
     * selection the content was lifted with moves along (same step); otherwise the current
     * selection is left alone (it was just replaced by the user). Returns true when a step was
     * recorded.
     */
    private fun applyPending(s: Session, moveSelection: Boolean): Boolean {
        val st = transformState ?: run { endSession(s); return false }
        // The layer/bitmap went away underneath us (deleted, canvas resized...): nothing to bake.
        if (!isValid(s)) { cancelSession(s); return false }
        if (controller.doc.effectiveLocked(s.layer)) {
            controller.toast("Layer \"${s.layer.name}\" is locked, so the transform was not applied")
            cancelSession(s)
            return false
        }
        // Unchanged: nothing to record.
        if (!s.placement && st.sameGeometry(s.initial)) { endSession(s); return false }
        s.objectLift?.let { lift ->
            // Vector objects: their geometry is mapped exactly (one step, made by the lift).
            val recorded = lift.commit(st, TRANSFORM_OBJECTS_LABEL)
            endSession(s)
            return recorded
        }
        val label = if (s.placement) s.placementLabel else TRANSFORM_LABEL
        val bmp = s.targetBitmap
        val rec = controller.beginEdit(s.layer, s.target)
        try {
            s.liftRect?.let { rec.touch(it) }
            val newRect = docRect(st)
            if (newRect.intersect(0, 0, bmp.width, bmp.height)) rec.touch(newRect)
            val canvas = Canvas(bmp)
            clearSource(canvas, s)
            canvas.drawBitmap(s.drawSource, s.drawMatrix, drawPaint(s, forPreview = false))
        } catch (e: OutOfMemoryError) {
            // Undo snapshots of a huge area didn't fit: put everything back as it was (a failed
            // placement also takes its empty layer away).
            rec.abort()
            cancelSession(s)
            controller.toast("Not enough memory to apply the transform")
            return false
        }
        val extras = if (moveSelection) moveSelection(s, st, label) else emptyList()
        // A placement on the layer that was just added for it: adding the layer and placing the
        // pixels become ONE undo step (undo removes the picture and its layer together).
        val foldWithAdd = s.placement && isFreshImportLayer(s.layer, s.placementLabel)
        endSession(s)
        val recorded = controller.commitEdit(rec, label, extras)
        if (recorded && foldWithAdd) controller.mergeLastUndo(2, label)
        return recorded
    }

    /** Ends the session without changes; a discarded placement also removes its empty layer. */
    private fun cancelSession(s: Session) {
        endSession(s)
        // Deferred: discard() is also called from inside controller.deleteLayer(), which removes
        // a layer by a precomputed index right after — changing the list now would break it.
        if (s.placement) controller.scope.launch(Dispatchers.Main) { removePlacementLayer(s.layer, s.placementLabel) }
    }

    /** Clears the session (no pixel changes) and redraws what the preview covered. */
    private fun endSession(s: Session) {
        gesture = null
        pinch = null
        session = null
        numericEdit = null
        clearGuides()
        if (controller.renderOverride === s.preview) controller.renderOverride = null
        val last = lastBounds
        lastBounds = null
        transformState = null
        isPlacement = false
        numbersOpen = false
        invalidateBoth(last, s.liftRect)
        s.releaseLevels()
        if (s.ownsFloating) s.floating.recycle()
        // After its commit / delete: a lift that applies its result later keeps what it needs.
        s.objectLift?.release()
    }

    private fun cancelJobs() {
        activationJob?.cancel(); activationJob = null
        liftJob?.cancel(); liftJob = null
        endObjectLiftWait()
        // A pinch still waiting for its lift has nothing to act on any more.
        if (pinch?.start == null) pinch = null
    }

    /** Removes the (still empty) layer of a discarded placement. */
    private fun removePlacementLayer(layer: Layer, label: String) {
        val doc = controller.doc
        // A transform started meanwhile: deleteLayer() would discard it, so leave the layer.
        if (doc.indexOf(layer) < 0 || session != null) return
        // It is the only layer left (the others were deleted meanwhile): a drawing needs one.
        if (doc.pixelLayerCount <= 1) return
        // Nothing was recorded since the layer was added: it goes without a trace. Otherwise it
        // is deleted as a regular step.
        if (isFreshImportLayer(layer, label)) dropFreshLayer(layer)
        else controller.deleteLayer(layer)
    }

    /**
     * Takes away [layer], added for a placement with nothing recorded since (see
     * [isFreshImportLayer]), together with its AddLayerAction: no history entry is left and Redo
     * can't bring the empty layer back. The layer that was active before it (right below it)
     * is active again, and the change counts as an edit (autosave must not keep the layer).
     */
    private fun dropFreshLayer(layer: Layer) {
        val doc = controller.doc
        val idx = doc.indexOf(layer)
        if (idx < 0 || doc.pixelLayerCount <= 1) return
        // v1.7 (I11): with a folder the add step is a LayerTreeAction; undoing it puts back the
        // order, the parents and the row that was active (an open folder, when the layer went
        // in as its top child).
        val add = controller.undoManager.undoAt(controller.undoManager.undoCount - 1)
        if (add is LayerTreeAction) {
            add.undo(controller)
        } else {
            controller.structural {
                doc.layers.removeAt(idx)
                doc.activeLayerIndex = (idx - 1).coerceIn(0, doc.layers.lastIndex)
            }
        }
        controller.dropLastUndo()
        // An empty step pushed and dropped again: counts as an edit, leaves no history. (The push
        // also clears the redo stack, which is empty here anyway: the AddLayerAction was the
        // newest step, see isFreshImportLayer, and pushing it cleared redo; the controller keeps
        // redo waiting while a placement is pending.)
        controller.pushUndo(LambdaAction(label = "", onUndo = {}, onRedo = {}))
        controller.dropLastUndo()
    }

    /**
     * True when the newest undo step is (by all we can see) the "Import picture" AddLayerAction
     * of [layer]: startPlacement() gets the empty layer importImageAsLayer() just added, and a
     * layer that was painted since (fill from a menu, a committed placement) no longer counts.
     */
    private fun isFreshImportLayer(layer: Layer, label: String = IMPORT_LABEL): Boolean =
        controller.doc.indexOf(layer) >= 0 && layer.contentVersion == 0L &&
            controller.undoManager.undoLabel == label

    /** The session's layer and bitmaps are still the ones it was started on. */
    private fun isValid(s: Session): Boolean {
        val doc = controller.doc
        val bmp = s.targetBitmap
        return doc.indexOf(s.layer) >= 0 && targetBitmapOf(s.layer, s.target) === bmp && !bmp.isRecycled &&
            bmp.width == doc.width && bmp.height == doc.height && !s.floating.isRecycled
    }

    /**
     * The pending session, or null. A session whose layer or bitmap was replaced from elsewhere
     * is dropped; a lost preview override (e.g. reset by the controller) is put back.
     */
    private fun liveSession(): Session? {
        val s = session ?: return null
        if (!isValid(s)) {
            cancelSession(s)
            return null
        }
        if (controller.renderOverride !== s.preview) {
            controller.renderOverride = s.preview
            lastBounds?.let { controller.invalidateDoc(it) }
        }
        return s
    }

    private fun applyState(new: TransformState) {
        val s = session ?: return
        transformState = new
        // Source size: the floating bitmap's, or the lifted objects' box (their preview may be smaller).
        val w = s.initial.srcW.toFloat()
        val h = s.initial.srcH.toFloat()
        if (new.isDistorted) {
            val c = new.corners()
            val src = floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h)
            val dst = floatArrayOf(c[0].x, c[0].y, c[1].x, c[1].y, c[2].x, c[2].y, c[3].x, c[3].y)
            if (!s.matrix.setPolyToPoly(src, 0, dst, 0, 4)) s.matrix.setValues(new.undistorted().affineValues())
        } else {
            s.matrix.setValues(new.affineValues())
        }
        // Bilinear alone aliases below 50 %: draw from a pre-halved copy for strong downscales.
        val level = if (interpolation == Interpolation.SMOOTH && !s.floating.isRecycled) new.minificationLevel() else 0
        val src = s.level(level)
        s.drawSource = src
        s.drawMatrix.set(s.matrix)
        if (src !== s.floating || src.width.toFloat() != w || src.height.toFloat() != h) s.drawMatrix.preScale(w / src.width, h / src.height)
        val nb = docRect(new)
        val old = lastBounds
        lastBounds = nb
        invalidateBoth(old, nb)
    }

    /** Redraws two document areas: as one when they overlap, else separately (a long move doesn't redraw everything between). */
    private fun invalidateBoth(a: Rect?, b: Rect?) {
        when {
            a == null && b == null -> controller.invalidateOverlay()
            a == null -> controller.invalidateDoc(b)
            b == null -> controller.invalidateDoc(a)
            Rect.intersects(a, b) -> controller.invalidateDoc(Rect(a).apply { union(b) })
            else -> { controller.invalidateDoc(a); controller.invalidateDoc(b) }
        }
    }

    // ------------------------------------------------------------------ pixel helpers

    private fun targetBitmapOf(layer: Layer, target: EditTarget): Bitmap? =
        if (target == EditTarget.MASK) layer.mask else layer.bitmap

    /** A mask's "empty" value: the most common of its four corner pixels. */
    private fun maskBackground(mask: Bitmap): Int {
        val w = mask.width - 1
        val h = mask.height - 1
        return ContentBounds.majority(intArrayOf(mask.getPixel(0, 0), mask.getPixel(w, 0), mask.getPixel(0, h), mask.getPixel(w, h)))
    }

    /** Removes the lifted pixels from [canvas] (transparent, or the background for masks). */
    private fun clearSource(canvas: Canvas, s: Session) {
        val r = s.liftRect ?: return
        val sel = s.selection
        if (s.target == EditTarget.MASK) {
            if (sel == null) {
                canvas.drawRect(r, s.maskReplace)
            } else {
                // Selected pixels fade towards the background by their selection alpha.
                canvas.save(); canvas.clipRect(r)
                canvas.drawBitmap(sel.mask, 0f, 0f, s.maskFill)
                canvas.restore()
            }
        } else if (sel == null) {
            canvas.drawRect(r, clearPaint)
        } else {
            canvas.save(); canvas.clipRect(r)
            canvas.drawBitmap(sel.mask, 0f, 0f, dstOutPaint)
            canvas.restore()
        }
    }

    /** Moves the selection that was lifted together with the pixels; returns its undo action. */
    private fun moveSelection(s: Session, st: TransformState, label: String): List<UndoAction> {
        val sel = s.selection ?: return emptyList()
        val r = s.liftRect ?: return emptyList()
        val before = controller.selection
        val moved = try {
            val crop = Bitmap.createBitmap(sel.mask, r.left, r.top, r.width(), r.height())
            val out = Bitmap.createBitmap(sel.width, sel.height, Bitmap.Config.ALPHA_8)
            val smooth = interpolation == Interpolation.SMOOTH
            val p = Paint().apply { isFilterBitmap = smooth; isAntiAlias = smooth }
            Canvas(out).drawBitmap(crop, s.matrix, p)
            if (crop !== sel.mask) crop.recycle()
            // Selected pixels can only be inside the transformed area: find the tight bounds
            // there instead of rescanning the whole document.
            val bounds = ContentBounds.of(out, region = docRect(st)) ?: Rect()
            Selection.wrap(out, bounds)
        } catch (e: OutOfMemoryError) {
            controller.toast("Not enough memory to move the selection")
            return emptyList()
        }
        ownSelectionChange = true
        try {
            controller.setSelection(moved, recordUndo = false)
        } finally {
            ownSelectionChange = false
        }
        val after = controller.selection
        return if (before === after) emptyList() else listOf(SelectionAction(before, after, label))
    }

    private fun rebuildPreviewPaint() {
        previewPaint = session?.let { drawPaint(it, forPreview = true) } ?: previewPaint
    }

    private fun drawPaint(s: Session, forPreview: Boolean): Paint = Paint().apply {
        val smooth = interpolation == Interpolation.SMOOTH
        isFilterBitmap = smooth
        isAntiAlias = smooth
        if (s.placement && s.target == EditTarget.CONTENT && s.layer.alphaLocked) {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        }
        // commitEdit() constrains the result to the color mode; make the preview match.
        if (forPreview && s.target == EditTarget.CONTENT) colorModeFilter(controller.doc.colorMode)?.let { colorFilter = it }
    }

    private fun colorModeFilter(mode: ColorMode): ColorFilter? = when (mode) {
        ColorMode.RGB -> null
        ColorMode.GRAYSCALE -> ColorMatrixColorFilter(
            floatArrayOf(
                0.299f, 0.587f, 0.114f, 0f, 0f,
                0.299f, 0.587f, 0.114f, 0f, 0f,
                0.299f, 0.587f, 0.114f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        ColorMode.MONOCHROME -> {
            // Steep ramp around 50%: approximates the 1-bit threshold applied on commit.
            val g = 255f
            val off = -127.5f * g + 127.5f
            ColorMatrixColorFilter(
                floatArrayOf(
                    0.299f * g, 0.587f * g, 0.114f * g, 0f, off,
                    0.299f * g, 0.587f * g, 0.114f * g, 0f, off,
                    0.299f * g, 0.587f * g, 0.114f * g, 0f, off,
                    0f, 0f, 0f, g, off,
                )
            )
        }
    }

    /** Document pixels covered by [st], rounded out with a margin for anti-aliased edges. */
    private fun docRect(st: TransformState): Rect {
        val b = st.bounds()
        fun lo(v: Float) = floor(v.coerceIn(-LIMIT, LIMIT)).toInt() - 2
        fun hi(v: Float) = ceil(v.coerceIn(-LIMIT, LIMIT)).toInt() + 2
        return Rect(lo(b.left), lo(b.top), hi(b.right), hi(b.bottom))
    }

    // ------------------------------------------------------------------ overlay

    private val path = Path()
    private val shadowStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x80000000.toInt() }
    private val accentStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = ACCENT }
    private val whiteStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = -1 }
    private val whiteFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = -1 }
    private val accentFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = ACCENT }

    private val outlinePath = Path()
    private val outlineMatrix = Matrix()
    private val outlineDark = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF000000.toInt() }
    private val outlineLight = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = -1 }
    private var outlineDashDensity = 0f

    /**
     * Where the lifted selection will land. The controller keeps drawing the marching ants of
     * controller.selection at the old place until commit, so this shows the new place.
     */
    private fun drawMovedSelectionOutline(canvas: Canvas, t: ViewTransform, st: TransformState) {
        val s = session ?: return
        val outline = s.selection?.outline ?: return
        val lr = s.liftRect ?: return
        if (st.sameGeometry(s.initial)) return // still on top of the ants
        if (outlineDashDensity != t.density) {
            outlineDashDensity = t.density
            outlineLight.pathEffect = DashPathEffect(floatArrayOf(t.dp(4f), t.dp(4f)), 0f)
        }
        outlineDark.strokeWidth = t.dp(1f)
        outlineLight.strokeWidth = t.dp(1f)
        // Document -> floating pixels -> transformed document -> screen.
        outlineMatrix.setTranslate(-lr.left.toFloat(), -lr.top.toFloat())
        outlineMatrix.postConcat(s.matrix)
        outlineMatrix.postConcat(t.matrix)
        outline.transform(outlineMatrix, outlinePath)
        canvas.drawPath(outlinePath, outlineDark)
        canvas.drawPath(outlinePath, outlineLight)
    }

    override fun drawOverlay(canvas: Canvas, t: ViewTransform) {
        val st = transformState ?: return
        // Smart guides under the box and its handles.
        if (guides.isNotEmpty()) SnapGuideRenderer.draw(canvas, t, guides, controller.doc.width.toFloat(), controller.doc.height.toFloat(), st.bounds())
        drawMovedSelectionOutline(canvas, t, st)
        val g = gesture
        val layout = HandleLayout.compute(st, { t.docToScreen(it) }, t.density, g?.rotateEdge?.takeIf { g.hit.kind == HandleKind.ROTATE })
        val c = layout.corners
        shadowStroke.strokeWidth = t.dp(3f)
        accentStroke.strokeWidth = t.dp(1.5f)
        path.rewind()
        path.moveTo(c[0].x, c[0].y)
        for (i in 1 until 4) path.lineTo(c[i].x, c[i].y)
        path.close()
        canvas.drawPath(path, shadowStroke)
        canvas.drawPath(path, accentStroke)

        // Rotation handle on a stem out of the top-most edge.
        val base = layout.edges[layout.rotateEdge]
        val rh = layout.rotateHandle
        canvas.drawLine(base.x, base.y, rh.x, rh.y, shadowStroke)
        canvas.drawLine(base.x, base.y, rh.x, rh.y, accentStroke)
        val rr = t.dp(9f)
        canvas.drawCircle(rh.x, rh.y, rr + t.dp(1f), shadowStroke)
        canvas.drawCircle(rh.x, rh.y, rr, accentFill)
        canvas.drawCircle(rh.x, rh.y, rr * 0.35f, whiteFill)

        accentStroke.strokeWidth = t.dp(2f)
        val er = t.dp(5f)
        for (i in 0 until 4) {
            if (!layout.edgeVisible[i]) continue
            val e = layout.edges[i]
            canvas.drawCircle(e.x, e.y, er + t.dp(1f), shadowStroke)
            canvas.drawCircle(e.x, e.y, er, whiteFill)
            canvas.drawCircle(e.x, e.y, er, accentStroke)
        }
        val cr = t.dp(7f)
        whiteStroke.strokeWidth = t.dp(2f)
        for (p in c) {
            if (mode == Mode.DISTORT) {
                // Round, filled corners signal "moves freely".
                canvas.drawCircle(p.x, p.y, cr + t.dp(1f), shadowStroke)
                canvas.drawCircle(p.x, p.y, cr, accentFill)
                canvas.drawCircle(p.x, p.y, cr, whiteStroke)
            } else {
                canvas.drawRect(p.x - cr - t.dp(1f), p.y - cr - t.dp(1f), p.x + cr + t.dp(1f), p.y + cr + t.dp(1f), shadowStroke)
                canvas.drawRect(p.x - cr, p.y - cr, p.x + cr, p.y + cr, whiteFill)
                canvas.drawRect(p.x - cr, p.y - cr, p.x + cr, p.y + cr, accentStroke)
            }
        }
    }

    companion object {
        const val TRANSFORM_LABEL = "Transform"
        const val IMPORT_LABEL = "Import picture"
        const val DELETE_LABEL = "Delete"

        /** How close (screen dp) a box line must come to a guide to snap to it. */
        const val SNAP_DISTANCE_DP = 8f
        /**
         * How far (screen dp) the finger must travel before a drag snaps (like the shape and
         * curve tools' touch slop), so a tap or a resting finger never jumps the box onto a guide.
         */
        const val SNAP_SLOP_DP = 6f
        /** A settled box within this many document px of a guide still shows it (half-pixel centers). */
        private const val GUIDE_EPS = 0.51f
        /** Relative factor change used to see how fast each line of a rotated box's bounds moves. */
        private const val PROBE_FACTOR = 0.01f
        /** Bounds lines moving less than this (document px per 100 % of scale) count as fixed. */
        private const val MIN_LINE_SLOPE = 0.5f
        /** How long a nudge shows the guides it lined up with. */
        private const val NUDGE_GUIDES_MS = 1200L

        private const val PREF_FROM_CENTER = "transform.scaleFromCenter"
        private const val PREF_ANCHOR = "transform.anchor"

        /** Layers up to this many pixels are scanned for content bounds on the main thread. */
        private const val SYNC_SCAN_PIXELS = 2_000_000L
        /** Radius (screen dp) around each finger read to tell whether it is on content (nothing lifted yet). */
        private const val PROBE_RADIUS_DP = 8f

        /** Undo label of a transform of lifted vector objects. */
        const val TRANSFORM_OBJECTS_LABEL = "Transform objects"
        /** Largest neighborhood (document px, each way) read to tell whether a finger is on content. */
        private const val MAX_PROBE_RADIUS = 64f
        private const val LIMIT = 1e8f
        private const val ACCENT = 0xFF4DA3FF.toInt()
    }
}
