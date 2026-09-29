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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.engine.LayerRenderOverride
import com.brushwork.paint.engine.SelectionAction
import com.brushwork.paint.engine.UndoAction
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor

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

    /** Corner handles keep the aspect ratio (free mode); also links width/height in the Numbers sheet. */
    var keepAspect by mutableStateOf(true)

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

    /** Hint shown in the options strip when nothing is being transformed. */
    val statusText: String
        get() {
            controller.layersVersion // re-read when the active layer, its locks or mask editing change
            val layer = controller.activeLayer
            val mask = layer.editingMask && layer.mask != null
            return when {
                isPreparing -> "Preparing…"
                layer.locked -> "The layer is locked"
                !layer.visible -> "The layer is hidden"
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
    ) {
        /** Floating bitmap pixels -> document. */
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
            canvas.drawBitmap(s.layer.bitmap, 0f, 0f, null)
            clearSource(canvas, s)
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
        if (hasPendingWork) commit()
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
    fun startPlacement(layer: Layer, image: Bitmap) {
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
            if (isFreshImportLayer(layer)) controller.undo()
            return
        }
        val doc = controller.doc
        val initial = TransformState.placement(floating.width, floating.height, doc.width, doc.height)
        startSession(
            Session(layer, EditTarget.CONTENT, layer.bitmap, floating, floating !== image, null, null, 0, initial, placement = true),
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

    private class Gesture(val hit: HandleHit, val start: TransformState, val from: Vec2, val pivot: Vec2, val rotateEdge: Int)

    private var gesture: Gesture? = null

    override fun onDown(p: ToolPoint) {
        gesture = null
        if (liveSession() == null && !beginLift()) return
        val st = transformState ?: return
        val t = controller.viewTransform
        val layout = HandleLayout.compute(st, { t.docToScreen(it) }, t.density)
        val hit = layout.hitTest(t.docToScreen(Vec2(p.x, p.y)))
        gesture = Gesture(hit, st, Vec2(p.x, p.y), st.center(), layout.rotateEdge)
        controller.invalidateOverlay()
    }

    override fun onMove(p: ToolPoint) {
        val g = gesture ?: return
        if (session == null) { gesture = null; return }
        if (!p.x.isFinite() || !p.y.isFinite()) return
        val to = Vec2(p.x, p.y)
        val distort = mode == Mode.DISTORT
        val next = when (g.hit.kind) {
            HandleKind.MOVE -> TransformHandles.move(g.start, g.from, to)
            HandleKind.ROTATE -> TransformHandles.rotate(g.start, g.pivot, g.from, to)
            HandleKind.CORNER ->
                if (distort) TransformHandles.distortCorner(g.start, g.hit.index, g.from, to)
                else TransformHandles.corner(g.start, g.hit.index, g.from, to, keepAspect)
            HandleKind.EDGE ->
                if (distort) TransformHandles.distortEdge(g.start, g.hit.index, g.from, to)
                else TransformHandles.edge(g.start, g.hit.index, g.from, to)
        } ?: return // an invalid (non-convex) distort keeps the last valid shape
        if (next != transformState) applyState(next)
    }

    override fun onUp(p: ToolPoint) {
        if (gesture == null) return
        onMove(p)
        gesture = null
        transformState?.let { st ->
            val snapped = st.snappedToPixels()
            if (snapped != st) applyState(snapped)
        }
        controller.invalidateOverlay()
    }

    override fun onCancel() {
        val g = gesture ?: return
        gesture = null
        if (session != null && transformState != g.start) applyState(g.start)
        controller.invalidateOverlay()
    }

    // ------------------------------------------------------------------ commands (options strip / Numbers)

    /** Moves by a document-pixel offset. */
    fun moveBy(dx: Float, dy: Float) {
        if (dx.isFinite() && dy.isFinite()) update { it.translated(dx, dy) }
    }

    /** One nudge-pad press: moves by [nudgeStepPx] in the given direction (-1, 0, 1). */
    fun nudge(dx: Int, dy: Int) = moveBy((dx * nudgeStepPx).toFloat(), (dy * nudgeStepPx).toFloat())

    /** Places the bounds' left/top edge (document pixels; null = unchanged). Non-finite values are ignored. */
    fun setPosition(left: Double? = null, top: Double? = null) {
        val l = left?.takeIf { it.isFinite() }
        val t = top?.takeIf { it.isFinite() }
        if (l == null && t == null) return
        update { it.withPosition(l?.toFloat(), t?.toFloat()) }
    }

    /** Sets width and/or height (document pixels), honoring [keepAspect]. Non-finite values are ignored. */
    fun setSize(width: Double? = null, height: Double? = null) {
        val w = width?.takeIf { it.isFinite() }
        val h = height?.takeIf { it.isFinite() }
        if (w == null && h == null) return
        update { it.withSize(w?.toFloat(), h?.toFloat(), keepAspect) }
    }

    /** Sets the absolute rotation in degrees (around the center). */
    fun setRotation(degrees: Double) {
        if (degrees.isFinite()) update { it.withRotation(degrees.toFloat()) }
    }

    /** Sets a uniform scale relative to the original size (100 = original), around the center. */
    fun setScalePercent(percent: Double) {
        if (percent.isFinite()) update { it.withScalePercent(percent.toFloat()) }
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

    private inline fun update(f: (TransformState) -> TransformState) {
        if (liveSession() == null || gesture != null) return
        val st = transformState ?: return
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
        if (liftJob != null) return false
        val layer = controller.activeLayer
        if (!controller.checkEditable(layer)) return false
        val target = controller.editTargetOf(layer)
        if (target == EditTarget.CONTENT && layer.alphaLocked) {
            controller.toast("Transparency is locked on \"${layer.name}\". Unlock it to transform.")
            return false
        }
        val bmp = targetBitmapOf(layer, target) ?: return false
        val bg = if (target == EditTarget.MASK) maskBackground(bmp) else 0
        val sel = controller.selection
        if (sel != null) return lift(layer, target, bmp, sel.bounds, sel, bg)
        val empty: Int? = if (target == EditTarget.MASK) bg else null
        if (bmp.width.toLong() * bmp.height <= SYNC_SCAN_PIXELS) {
            val r = ContentBounds.of(bmp, empty) ?: return nothingToTransform(target)
            return lift(layer, target, bmp, r, null, bg)
        }
        // Large layer: find the content bounds off the main thread, then lift. The bitmap is only
        // read; the result is thrown away if the layer changed meanwhile (contentVersion).
        val version = layer.contentVersion
        isPreparing = true
        liftJob = controller.scope.launch(Dispatchers.Main) {
            val me = coroutineContext[Job]
            val r = try {
                withContext(Dispatchers.Default) { ContentBounds.of(bmp, empty) { !isActive } }
            } finally {
                if (liftJob === me) { liftJob = null; isPreparing = false }
            }
            val stillWanted = session == null && controller.activeToolId == ToolId.TRANSFORM &&
                controller.activeLayer === layer && controller.selection == null &&
                targetBitmapOf(layer, controller.editTargetOf(layer)) === bmp
            when {
                !stillWanted -> {}
                layer.contentVersion != version -> beginLift() // changed meanwhile (e.g. undo): rescan
                r == null -> nothingToTransform(target)
                else -> lift(layer, target, bmp, r, null, bg)
            }
        }
        return false
    }

    private fun nothingToTransform(target: EditTarget): Boolean {
        controller.toast(if (target == EditTarget.MASK) "Nothing to transform on this mask" else "Nothing to transform on this layer")
        return false
    }

    private fun lift(layer: Layer, target: EditTarget, bmp: Bitmap, rect: Rect, sel: Selection?, bg: Int): Boolean {
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
        if (sel != null) c.drawBitmap(sel.mask, -r.left.toFloat(), -r.top.toFloat(), dstInPaint)
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
        applyState(state)
    }

    // ------------------------------------------------------------------ finishing

    /**
     * Bakes the pending transform into the layer with ONE undo step. With [moveSelection] the
     * selection the content was lifted with moves along (same step); otherwise the current
     * selection is left alone (it was just replaced by the user).
     */
    private fun applyPending(s: Session, moveSelection: Boolean) {
        val st = transformState ?: return endSession(s)
        // The layer/bitmap went away underneath us (deleted, canvas resized...): nothing to bake.
        if (!isValid(s)) return cancelSession(s)
        if (s.layer.locked) {
            controller.toast("Layer \"${s.layer.name}\" is locked, so the transform was not applied")
            return cancelSession(s)
        }
        // Unchanged: nothing to record.
        if (!s.placement && st.sameGeometry(s.initial)) return endSession(s)
        val label = if (s.placement) IMPORT_LABEL else TRANSFORM_LABEL
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
            return
        }
        val extras = if (moveSelection) moveSelection(s, st, label) else emptyList()
        endSession(s)
        controller.commitEdit(rec, label, extras)
    }

    /** Ends the session without changes; a discarded placement also removes its empty layer. */
    private fun cancelSession(s: Session) {
        endSession(s)
        // Deferred: discard() is also called from inside controller.deleteLayer(), which removes
        // a layer by a precomputed index right after — changing the list now would break it.
        if (s.placement) controller.scope.launch(Dispatchers.Main) { removePlacementLayer(s.layer) }
    }

    /** Clears the session (no pixel changes) and redraws what the preview covered. */
    private fun endSession(s: Session) {
        gesture = null
        session = null
        if (controller.renderOverride === s.preview) controller.renderOverride = null
        val last = lastBounds
        lastBounds = null
        transformState = null
        isPlacement = false
        numbersOpen = false
        invalidateBoth(last, s.liftRect)
        s.releaseLevels()
        if (s.ownsFloating) s.floating.recycle()
    }

    private fun cancelJobs() {
        activationJob?.cancel(); activationJob = null
        liftJob?.cancel(); liftJob = null
        isPreparing = false
    }

    /** Removes the (still empty) layer of a discarded placement. */
    private fun removePlacementLayer(layer: Layer) {
        // A transform started meanwhile: undo()/deleteLayer() would discard it, so leave the layer.
        if (controller.doc.indexOf(layer) < 0 || session != null) return
        // Nothing was recorded since the layer was added: undo its AddLayerAction so no
        // history entry remains (Redo can still bring the empty layer back: the controller has
        // no way to drop a redo entry). Otherwise delete it as a regular step.
        if (isFreshImportLayer(layer)) controller.undo()
        else if (controller.doc.layers.size > 1) controller.deleteLayer(layer)
        // else: it is the only layer left (the others were deleted meanwhile); a drawing needs one.
    }

    /**
     * True when the newest undo step is (by all we can see) the "Import picture" AddLayerAction
     * of [layer]: startPlacement() gets the empty layer importImageAsLayer() just added, and a
     * layer that was painted since (fill from a menu, a committed placement) no longer counts.
     */
    private fun isFreshImportLayer(layer: Layer): Boolean =
        controller.doc.indexOf(layer) >= 0 && layer.contentVersion == 0L &&
            controller.undoManager.undoLabel == IMPORT_LABEL

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
        if (new.isDistorted) {
            val c = new.corners()
            val w = s.floating.width.toFloat()
            val h = s.floating.height.toFloat()
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
        if (src !== s.floating) s.drawMatrix.preScale(s.floating.width.toFloat() / src.width, s.floating.height.toFloat() / src.height)
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

        /** Layers up to this many pixels are scanned for content bounds on the main thread. */
        private const val SYNC_SCAN_PIXELS = 2_000_000L
        private const val LIMIT = 1e8f
        private const val ACCENT = 0xFF4DA3FF.toInt()
    }
}
