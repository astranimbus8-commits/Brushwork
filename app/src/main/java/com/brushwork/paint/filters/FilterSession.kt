package com.brushwork.paint.filters

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.brushwork.paint.ColorModeOps
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.core.Parallel
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.diagnostics.Breadcrumbs
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.engine.LayerRenderOverride
import com.brushwork.paint.engine.MemoryGovernor
import com.brushwork.paint.engine.PerfTrace
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.model.Selection
import com.brushwork.paint.segmentation.SegmentationService
import com.brushwork.paint.tools.InToolHistoryOwner
import com.brushwork.paint.tools.ToolHistory
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.transform.DataRender
import com.brushwork.paint.ui.common.FilterLabels19
import com.brushwork.paint.ui.common.StabilityLabels19
import com.brushwork.paint.vector.render.RenderCache
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * A filter being previewed on the active layer. Created by EditorController.startFilter().
 * Must set controller.filterSession = null when finished (applied or cancelled).
 *
 * Preview: the target (layer content, or its mask while editing the mask) is downscaled once so
 * the long side is at most [FilterSessionMath.PREVIEW_MAX_SIDE]; every parameter change renders
 * the filter on that copy on [Dispatchers.Default] and shows it through
 * `controller.renderOverride`. Renders are throttled: a change during the debounce is picked up
 * when the render starts, a change during a fast render queues one more render, and a change
 * during a slow render restarts it.
 *
 * Apply: runs the filter at full resolution in the background, masks the result by the
 * selection (and alpha lock / mask rules), writes only the changed pixels and records undo.
 *
 * v1.9 (R8): while open, the session is the controller's [EditorController.inToolHistory]: Undo
 * and Redo walk its own steps ([history]) first; at the bottom v1.8's Undo cancels it.
 *
 * v1.10 (R6, A4): [selectedIds] are the layer's selected objects when the filter started (kept
 * by `EditorController.startFilter`); a filter with a `colorMapper` recolors them in object mode.
 *
 * All public members must be called on the main thread.
 */
class FilterSession(
    val controller: EditorController,
    val filter: Filter,
    val selectedIds: Set<Long> = emptySet(),
) : InToolHistoryOwner {

    /** The layer being filtered (fixed for the session, even if another layer becomes active). */
    val layer: Layer = controller.activeLayer

    /** Whether the filter edits the layer's pixels or its mask. */
    val target: EditTarget = controller.editTargetOf(layer)

    private val targetBitmap: Bitmap = if (target == EditTarget.MASK) layer.mask!! else layer.bitmap

    /** v1.10 (F0, C16): the layer's [Layer.pixelGeneration] at the start (a replaced bitmap or mask bumps it). */
    private val targetGeneration: Long = layer.pixelGeneration

    /** Parameters dragged on the canvas. */
    val pointParams: List<FilterParam.Point> = filter.params.filterIsInstance<FilterParam.Point>()

    // ------------------------------------------------------------------ observable state

    /** Current values. Replaced (never mutated) on every change: safe to hand to background jobs. */
    var values: FilterValues by mutableStateOf(sessionDefaults())
        private set

    /** A preview render is scheduled or running. */
    var isRendering by mutableStateOf(false)
        private set

    /** A full-resolution apply is running. */
    var isApplying by mutableStateOf(false)
        private set

    /** 0..1 progress of the running apply, < 0 when unknown. */
    var applyProgress by mutableFloatStateOf(-1f)
        private set

    /** The original is shown instead of the preview ("Compare" held down). */
    var isComparing by mutableStateOf(false)
        private set

    /** At least one preview has been rendered. */
    var hasPreview by mutableStateOf(false)
        private set

    /** The shown preview doesn't match [values] (filters without live preview wait for "Preview"). */
    var previewStale by mutableStateOf(true)
        private set

    /** Luminance histogram (256 bins) of the preview source inside the selection; null until ready. */
    var histogram by mutableStateOf<IntArray?>(null)
        private set

    /** Key of the point parameter currently dragged on the canvas. */
    var draggingPoint by mutableStateOf<String?>(null)
        private set

    /** The session was applied or cancelled. */
    var isClosed by mutableStateOf(false)
        private set

    /** Preview width / full width (1 for small canvases). */
    var previewScale: Float = 1f
        private set

    // ------------------------------------------------------------------ v1.10 object mode (R6)

    /**
     * v1.10 (R6, A4): the filter recolors the objects of a vector, text or shape layer through its
     * [Filter.colorMapper] instead of filtering pixels (decided at the start). Preview (C12): for
     * a whole vector or shape layer without an array tint the v1.9 pixel preview is exact and is
     * kept ([ObjectColorTargets.exactByPixels]); otherwise the preview re-renders the layer's
     * data with mapped colors (a live array's copies through `ArrayDraw`) and holds no copy of
     * the layer's pixels. Apply changes the data as one step, re-rendered: nothing is
     * rasterized. Which objects: [ObjectColorTargets].
     */
    val objectMode: Boolean = target == EditTarget.CONTENT && filter.colorMapper(filter.defaultValues()) != null &&
        ObjectColorTargets.hasObjects(layer.dataSnapshot())

    /** v1.10: the rule that chose the recolored objects, once read (object mode only). */
    internal var objectRule by mutableStateOf<ObjectColorTargets.Rule?>(null)
        private set

    // ------------------------------------------------------------------ v1.9 steps (R8)

    /**
     * v1.9 (R8, I22): the panel's own steps. Its bottom is the opening state ([start]); each
     * finished change of a control and each reset adds one ([record]). The states are [values]
     * instances, which are never mutated. Dropped with the session: ✓ records one document step,
     * as in v1.8. At most 100 steps can be taken back.
     */
    val history: ToolHistory<FilterValues> = ToolHistory(values)

    /** The label of each step [history] can take back, oldest first (as many as its depth). */
    private val undoLabels = ArrayDeque<String>()

    /** The labels of the steps [history] can bring back, the next redo last. */
    private val redoLabels = ArrayDeque<String>()

    /** [pendingParam]'s answer for the pair of states [pendingValues] and [pendingBase]. */
    private var pendingValues: FilterValues? = null
    private var pendingBase: FilterValues? = null
    private var pendingAnswer: FilterParam? = null

    // ------------------------------------------------------------------ preview internals

    private var previewSrc: PixelBuffer? = null
    /** layer.contentVersion when [previewSrc] was read. */
    private var sourceVersion = Long.MIN_VALUE
    private var previewSel: ByteArray? = null
    /** The selection [previewSel] was built from. */
    private var previewSelSource: Selection? = null
    private var previewBitmap: Bitmap? = null

    /** Object mode: the data and targets the preview renders, read at [version] with [selection]. */
    private class ObjectSource(val data: LayerData, val targets: ObjectColorTargets, val version: Long, val selection: Selection?)

    private var objectSource: ObjectSource? = null
    /** Object mode: renders land here in the background, then trade places with [previewBitmap] on the main thread. */
    private var scratchBitmap: Bitmap? = null
    /** Guards [scratchBitmap], [scratchOwner] and the render caches: one render at a time, never one into a recycled bitmap. */
    private val scratchLock = Any()
    /** The render whose pixels [scratchBitmap] holds (a stopped render may have drawn after it). */
    private var scratchOwner: Job? = null
    private val objectTips by lazy { TipCache(OBJECT_TIP_BYTES) }
    private val objectCache by lazy { RenderCache() }

    private var previewJob: Job? = null
    private var analysisJob: Job? = null
    private var documentWatch: Job? = null
    private var applyJob: Job? = null
    /** The preview job currently running the filter (null while debouncing / idle). */
    private var computingJob: Job? = null
    private var computeStartNs = 0L
    private var rerunRequested = false
    private var lastRenderMs = 0L
    @Volatile private var rawApplyProgress = -1f
    /** Stop was requested for the current apply (possibly before its coroutine started). */
    private var applyCancelRequested = false

    /** Debounce before a preview render starts (tests set 0). */
    internal var debounceMs: Long = 80L

    private val docRect = RectF(0f, 0f, controller.doc.width.toFloat(), controller.doc.height.toFloat())
    private val previewPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    /** Replaces the original inside the preview region (the override draws into an isolated layer). */
    private val previewReplacePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
    /** Region where the shown preview differs from the layer (see [regionFor]); null = everywhere. */
    private var shownRegion: Rect? = null
    private var maskPaintKey: Paint? = null
    private var maskPreviewPaint: Paint? = null
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val services: FilterServices by lazy { SegmentationService.get(controller.appContext).asFilterServices() }

    private var grabDx = 0f
    private var grabDy = 0f
    /** Value of the dragged point before the gesture (restored if the gesture is cancelled). */
    private var dragStartValue: FloatArray? = null

    private val override = object : LayerRenderOverride {
        override val layer: Layer get() = this@FilterSession.layer

        // With a selection, only the selection's area comes from the (downscaled) preview; the rest
        // is the untouched original, drawn at full sharpness.
        override fun drawContent(canvas: Canvas): Boolean {
            if (target != EditTarget.CONTENT) return false
            val bmp = previewBitmap ?: return false
            val region = shownRegion
            if (region == null) {
                canvas.drawBitmap(bmp, null, docRect, previewPaint)
            } else {
                canvas.drawBitmap(layer.bitmap, 0f, 0f, null)
                canvas.save()
                canvas.clipRect(region)
                canvas.drawBitmap(bmp, null, docRect, previewReplacePaint)
                canvas.restore()
            }
            return true
        }

        override fun drawMask(canvas: Canvas, maskPaint: Paint): Boolean {
            if (target != EditTarget.MASK) return false
            val bmp = previewBitmap ?: return false
            val original = layer.mask ?: return false
            val region = shownRegion
            if (region == null) {
                canvas.drawBitmap(bmp, null, docRect, filteredMaskPaint(maskPaint))
            } else {
                canvas.save()
                canvas.clipOutRect(region)
                canvas.drawBitmap(original, 0f, 0f, maskPaint)
                canvas.restore()
                canvas.save()
                canvas.clipRect(region)
                canvas.drawBitmap(bmp, null, docRect, filteredMaskPaint(maskPaint))
                canvas.restore()
            }
            return true
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** Builds the preview source and renders the first preview. Called by EditorController.startFilter(). */
    fun start() {
        if (isClosed) return
        // Adjustment layers have no pixels: their effect is edited with the Masks tool (v1.5).
        if (layer.isAdjustmentLayer) {
            controller.toast(EditorController.ADJUSTMENT_FILTER_MESSAGE)
            close()
            return
        }
        values = sessionDefaults()
        try {
            if (objectMode) buildObjectSource() else buildPreviewSource()
        } catch (e: OutOfMemoryError) {
            controller.toast("Not enough memory to preview \"${filter.name}\"")
            close()
            // startFilter() assigns controller.filterSession after start() returns: clear it right after.
            controller.scope.launch(Dispatchers.Main) {
                if (controller.filterSession === this@FilterSession) controller.filterSession = null
            }
            return
        }
        // v1.9 (R8): the opening state is the bottom of the panel's steps; Undo and Redo walk them.
        history.reset(values)
        undoLabels.clear()
        redoLabels.clear()
        controller.inToolHistory = this
        runCatching { FilterRecents.record(controller.appContext, filter.id) }
        analyzeSourceAsync(firstTime = true)
        objectSource?.let { if (ObjectColorTargets.isEmpty(it.data)) controller.toast(emptyMessage()) }
        if (filter.livePreview) requestPreview(0L)
        // Menus stay usable while the session is open (select all, clear or fill the layer, delete
        // it...): keep the preview in sync, or close the session if its layer is gone.
        documentWatch = controller.scope.launch {
            snapshotFlow { controller.selection to controller.layersVersion }.collect { syncWithDocument() }
        }
    }

    /** Re-renders the preview if the selection or the layer's pixels changed; cancels if the layer is gone. */
    private fun syncWithDocument() {
        if (isClosed || isApplying || !hasSource()) return
        if (!checkTarget()) return
        if (refreshSources() != Refresh.CHANGED) return
        previewStale = true
        if (filter.livePreview) requestPreview(0L)
    }

    private enum class Refresh { NONE, CHANGED, FAILED }

    /** Rebuilds the preview source and/or selection when the layer pixels or the selection changed. */
    private fun refreshSources(): Refresh {
        if (objectMode) return refreshObjectSource()
        val contentChanged = layer.contentVersion != sourceVersion
        val selectionChanged = controller.selection !== previewSelSource
        if (!contentChanged && !selectionChanged) return Refresh.NONE
        try {
            if (contentChanged) buildPreviewSource() else buildPreviewSelection()
        } catch (e: OutOfMemoryError) {
            controller.toast("Not enough memory to preview \"${filter.name}\"")
            return Refresh.FAILED
        }
        analyzeSourceAsync(firstTime = false)
        return Refresh.CHANGED
    }

    /** Discards the preview and closes the session; the layer is left untouched. */
    fun cancel() {
        if (isClosed) return
        close()
    }

    private fun close() {
        isClosed = true
        previewJob?.cancel(); previewJob = null
        analysisJob?.cancel(); analysisJob = null
        documentWatch?.cancel(); documentWatch = null
        applyJob?.cancel(); applyJob = null
        computingJob = null
        isRendering = false
        isComparing = false
        draggingPoint = null
        clearOverride()
        if (hasPreview) controller.invalidateDoc(shownRegion)
        if (controller.filterSession === this) controller.filterSession = null
        // v1.9 (R8): the panel's steps go with it.
        if (controller.inToolHistory === this) controller.inToolHistory = null
        previewBitmap?.recycle()
        previewBitmap = null
        previewSrc = null
        previewSel = null
        previewSelSource = null
        // v1.10: a stopped object render still drawing finishes its object first.
        synchronized(scratchLock) {
            scratchBitmap?.recycle()
            scratchBitmap = null
            scratchOwner = null
        }
        objectSource = null
    }

    // ------------------------------------------------------------------ parameters

    /**
     * Sets one parameter value and schedules a preview. The change is live: it becomes a step of
     * [history] when its control finishes it ([commitStep]). v1.9: a change of another control
     * that was never finished (text typed without leaving its field, a run of − / + taps) becomes
     * its own step first, so each step names one control.
     */
    fun update(key: String, value: Any) {
        if (isClosed || isApplying) return
        if (!heldByTap) FilterSessionMath.firstDifference(filter.params, values, history.current, except = key)?.let { record(it.label) }
        values = values.copy().set(key, value)
        onValuesChanged()
    }

    /**
     * v1.9 (R8 (a)): a control finished its change of [key] (a slider released, a value typed, a
     * chip, switch, colour or seed chosen, a curve point, gradient stop or point dragged, a text
     * field left, a fringe preset or swatch chosen): the values become one step, labelled with
     * the control's label. Nothing when they equal the last step. Ignored while a history tap
     * holds the panel ([heldByTap]).
     */
    fun commitStep(key: String) {
        if (isClosed || isApplying || heldByTap) return
        val p = filter.params.firstOrNull { it.key == key }
        if (p != null) record(p.label) else settle()
    }

    /**
     * v1.9 (R8): a two- or three-finger history tap claimed a touch that started in the panel
     * (set by the panel, cleared when the tap undoes or redoes and when the next touch starts).
     * The controls then end what the first finger started (a slider's drag stops, a − / + is
     * released) and the tap puts the values back before it undoes: those ends are not steps.
     */
    internal var heldByTap = false

    /**
     * Restores every parameter to its default (drawing-color parameters to the current drawing
     * color). v1.9 (R8): one step, [RESET_ALL].
     */
    fun reset() {
        if (isClosed || isApplying) return
        settle()
        values = sessionDefaults()
        onValuesChanged()
        record(RESET_ALL)
    }

    /** Restores one parameter to its default. v1.9 (R8): one step, "Reset <label>" ([FilterLabels19.RESET_ONE]). */
    fun resetParam(key: String) {
        val p = filter.params.firstOrNull { it.key == key } ?: return
        if (isClosed || isApplying) return
        settle()
        update(key, sessionDefault(p))
        record(String.format(FilterLabels19.RESET_ONE, p.label))
    }

    /** Default of [p] in this session: [FilterParam.Color.useDrawingColor] means the current drawing color. */
    internal fun sessionDefault(p: FilterParam): Any =
        if (p is FilterParam.Color && p.useDrawingColor) controller.color else p.defaultValue()

    /** The filter's defaults with drawing-color parameters set to the current drawing color. */
    private fun sessionDefaults(): FilterValues {
        val v = filter.defaultValues()
        for (p in filter.params) if (p is FilterParam.Color && p.useDrawingColor) v.set(p.key, controller.color)
        return v
    }

    /** Renders the preview now (the "Preview" button of filters without live preview). */
    fun renderPreview() {
        if (isClosed || isApplying) return
        requestPreview(0L)
    }

    private fun onValuesChanged() {
        previewStale = true
        if (pointParams.isNotEmpty()) controller.invalidateOverlay()
        if (filter.livePreview) requestPreview(debounceMs)
    }

    // ------------------------------------------------------------------ v1.9 steps (R8)

    // While ✓ applies, the steps wait: Undo stops the apply and closes the panel as in v1.8, so the
    // labels say so ("Filter cancelled", "Finish the filter first"), not a step's name.
    override val canUndo: Boolean get() = !isClosed && !isApplying && (history.canUndo || pendingParam() != null)

    override val canRedo: Boolean get() = !isClosed && !isApplying && history.canRedo && pendingParam() == null

    /** The control whose step Undo takes back: a change not recorded yet first ([pendingParam]). */
    override val undoLabel: String?
        get() {
            if (isClosed || isApplying) return null
            pendingParam()?.let { return it.label }
            return if (history.depth > 0) undoLabels.lastOrNull() else null
        }

    override val redoLabel: String? get() = if (canRedo) redoLabels.lastOrNull() else null

    /**
     * Takes back the panel's last step: a change not recorded yet becomes its step first, so Undo
     * takes it back. False at the bottom (the opening state): the controller then cancels the
     * session, as v1.8's Undo did. Landing on the bottom shows [FilterLabels19.SESSION_AT_START].
     */
    override fun undoStep(): Boolean {
        if (isClosed || isApplying) return false
        heldByTap = false
        // A point dragged on the canvas when a two-finger tap lands there goes back first.
        if (draggingPoint != null) onPointerCancel()
        settle()
        val state = history.undo() ?: return false
        undoLabels.removeLastOrNull()?.let { redoLabels.addLast(it) }
        show(state)
        if (!history.canUndo) controller.toast(FilterLabels19.SESSION_AT_START)
        return true
    }

    /**
     * Brings back the step the last [undoStep] took back. False at the top, or when a change not
     * recorded yet is pending: that change is a new step, which drops the redo steps.
     */
    override fun redoStep(): Boolean {
        if (isClosed || isApplying) return false
        heldByTap = false
        if (draggingPoint != null) onPointerCancel()
        if (pendingParam() != null) {
            settle()
            return false
        }
        val state = history.redo() ?: return false
        redoLabels.removeLastOrNull()?.let { undoLabels.addLast(it) }
        show(state)
        return true
    }

    /**
     * Records [values] as one step labelled [label] ("Undo: <label>"), unless they equal the
     * current step ([FilterSessionMath.sameValues]). The redo steps are dropped; past the cap the
     * oldest step and its label go.
     */
    private fun record(label: String) {
        val v = values
        if (FilterSessionMath.sameValues(v, history.current)) return
        history.record(v)
        undoLabels.addLast(label)
        while (undoLabels.size > history.depth) undoLabels.removeFirst()
        redoLabels.clear()
    }

    /** A change not recorded yet ([pendingParam]) becomes its step, labelled with its control. */
    private fun settle() {
        pendingParam()?.let { record(it.label) }
    }

    /**
     * The first parameter whose value differs from [history]'s current step: a change that is not
     * a step yet. Null when there is none. Cached per pair of states (both are Compose state, so
     * the chrome reading [canUndo] recomposes when either changes).
     */
    private fun pendingParam(): FilterParam? {
        val v = values
        val base = history.current
        if (v === base) return null
        if (v !== pendingValues || base !== pendingBase) {
            pendingAnswer = FilterSessionMath.firstDifference(filter.params, v, base)
            pendingValues = v
            pendingBase = base
        }
        return pendingAnswer
    }

    /** Shows a step's [state] (Undo, Redo): the preview renders at once, without the debounce (R8 (c)). */
    private fun show(state: FilterValues) {
        values = state
        previewStale = true
        if (pointParams.isNotEmpty()) controller.invalidateOverlay()
        if (filter.livePreview) requestPreview(0L)
    }

    // ------------------------------------------------------------------ compare

    /** "Compare" button: while [showOriginal] is true the original layer is shown instead of the preview. */
    fun compare(showOriginal: Boolean) {
        if (isClosed || isComparing == showOriginal) return
        isComparing = showOriginal
        if (showOriginal) clearOverride() else installOverride()
        if (hasPreview) controller.invalidateDoc(shownRegion)
    }

    // ------------------------------------------------------------------ preview

    /** (Re)reads the downscaled target and selection; the preview bitmap is created once. */
    private fun buildPreviewSource() {
        val w = targetBitmap.width; val h = targetBitmap.height
        val (pw, ph) = FilterSessionMath.previewSize(w, h)
        previewScale = pw.toFloat() / w
        val version = layer.contentVersion
        val scaled = FilterSessionBitmaps.downscale(targetBitmap, pw, ph)
        try {
            previewSrc = BitmapUtils.toPixelBuffer(scaled)
        } finally {
            if (scaled !== targetBitmap) scaled.recycle()
        }
        sourceVersion = version
        buildPreviewSelection()
        val bmp = previewBitmap
        if (bmp == null || bmp.width != pw || bmp.height != ph) {
            previewBitmap = BitmapUtils.createLayerBitmap(pw, ph)
            bmp?.recycle()
        }
    }

    private fun buildPreviewSelection() {
        val src = previewSrc ?: return
        val sel = controller.selection
        previewSel = sel?.let { scaledSelectionBytes(it, src.width, src.height) }
        previewSelSource = sel
    }

    private fun analyzeSourceAsync(firstTime: Boolean) {
        val src = previewSrc ?: return
        val sel = previewSel
        // Object mode tells an empty layer by its data (start).
        val warnEmpty = firstTime && target == EditTarget.CONTENT && !filter.generatesContent && !objectMode
        analysisJob?.cancel()
        analysisJob = controller.scope.launch {
            val (hist, empty) = withContext(Dispatchers.Default) {
                FilterSessionMath.luminanceHistogram(src, sel) to (warnEmpty && FilterSessionMath.isFullyTransparent(src))
            }
            if (isClosed) return@launch
            histogram = hist
            if (empty) controller.toast(emptyMessage())
        }
    }

    private fun emptyMessage() = "\"${layer.name}\" is empty, so ${filter.name} has nothing to change"

    /** The preview has what it renders from: the downscaled pixels, or (object mode) the layer's data. */
    private fun hasSource() = previewSrc != null || objectSource != null

    /** Test hook: a downscaled copy of the layer's pixels is held (in object mode only while [ObjectColorTargets.exactByPixels]). */
    internal val holdsPixelCopy: Boolean get() = previewSrc != null

    private fun requestPreview(delayMs: Long) {
        if (isClosed || !hasSource()) return
        val running = previewJob
        if (running != null && running.isActive) {
            // Still debouncing: the render reads the latest values when it starts.
            if (computingJob !== running) return
            val elapsedMs = (System.nanoTime() - computeStartNs) / 1_000_000
            if (lastRenderMs < SLOW_RENDER_MS && elapsedMs < SLOW_RENDER_MS) { rerunRequested = true; return }
            running.cancel()
        }
        launchPreview(delayMs)
    }

    private fun launchPreview(delayMs: Long) {
        val job = controller.scope.launch(start = CoroutineStart.LAZY) {
            val self = currentCoroutineContext().job
            isRendering = true
            try {
                var wait = delayMs
                do {
                    if (wait > 0) delay(wait)
                    wait = 0L
                    rerunRequested = false
                    renderOnce(self)
                } while (rerunRequested && !isClosed)
            } finally {
                if (computingJob === self) computingJob = null
                if (previewJob === self) { isRendering = false; previewJob = null }
            }
        }
        previewJob = job
        job.start()
    }

    private suspend fun renderOnce(self: Job) {
        if (!checkTarget()) return
        if (refreshSources() == Refresh.FAILED) return
        // v1.10: object mode without the pixel copy (C12) re-renders the objects.
        if (objectMode && previewSrc == null) return renderObjectsOnce(self)
        val src = previewSrc ?: return
        val sel = previewSel
        val selSource = previewSelSource
        val vals = values
        val alphaLocked = layer.alphaLocked
        val mask = target == EditTarget.MASK
        val colorMode = if (mask) ColorMode.RGB else controller.doc.colorMode
        val scale = previewScale
        val dpi = controller.doc.dpi
        val svc = services
        computingJob = self
        computeStartNs = System.nanoTime()
        val result = try {
            withContext(Dispatchers.Default) {
                val job = currentCoroutineContext().job
                val ctx = FilterContext(scale = scale, dpi = dpi, services = svc, cancelled = { !job.isActive })
                runFilter(src, vals, ctx, sel, alphaLocked, mask, colorMode, region = null, findChanges = false).out
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            controller.toast("Not enough memory to preview \"${filter.name}\"")
            null
        } catch (e: Exception) {
            controller.toast("${filter.name} failed: ${e.message ?: e.javaClass.simpleName}")
            null
        } finally {
            if (computingJob === self) computingJob = null
        }
        lastRenderMs = (System.nanoTime() - computeStartNs) / 1_000_000
        // v1.9 (design 2.4.4): each preview render (renderPreview's and the live ones) as PerfTrace.FILTER_PREVIEW.
        PerfTrace.record(PerfTrace.FILTER_PREVIEW, System.nanoTime() - computeStartNs)
        if (result == null || isClosed) return
        publishPreview(result, vals, src, selSource)
    }

    /** Shows a rendered preview; [src], [vals] and [selSource] are what it was rendered from. */
    private fun publishPreview(result: PixelBuffer, vals: FilterValues, src: PixelBuffer, selSource: Selection?) {
        val bmp = previewBitmap ?: return
        BitmapUtils.writePixelBuffer(bmp, result)
        val region = regionFor(selSource)
        // Redraw where the old preview was shown and where the new one will be.
        val dirty = if (hasPreview && !isComparing) unionOrAll(shownRegion, region) else region
        shownRegion = region
        hasPreview = true
        previewStale = vals !== values || src !== previewSrc || selSource !== previewSelSource
        if (!isComparing) {
            installOverride()
            controller.invalidateDoc(dirty)
        }
    }

    /**
     * Document area where the filtered layer can differ from the original: the selection bounds
     * plus a margin for the preview's down/up-scaling. Null = the whole document.
     */
    private fun regionFor(sel: Selection?): Rect? {
        val b = sel?.bounds ?: return null
        val w = controller.doc.width; val h = controller.doc.height
        val margin = ceil(3f / previewScale).toInt() + 1
        val r = Rect(b.left - margin, b.top - margin, b.right + margin, b.bottom + margin)
        if (!r.intersect(0, 0, w, h)) return null
        return if (r.left == 0 && r.top == 0 && r.right == w && r.bottom == h) null else r
    }

    private fun unionOrAll(a: Rect?, b: Rect?): Rect? = if (a == null || b == null) null else Rect(a).apply { union(b) }

    private fun installOverride() {
        if (!isClosed && hasPreview && previewBitmap != null) controller.renderOverride = override
    }

    private fun clearOverride() {
        if (controller.renderOverride === override) controller.renderOverride = null
    }

    /** The compositor's mask paint plus bitmap filtering (the preview is scaled up). */
    private fun filteredMaskPaint(maskPaint: Paint): Paint {
        val cached = maskPreviewPaint
        if (cached != null && maskPaintKey === maskPaint) return cached
        return Paint(maskPaint).apply { isFilterBitmap = true }.also { maskPreviewPaint = it; maskPaintKey = maskPaint }
    }

    // ------------------------------------------------------------------ v1.10 object preview (R6)

    /**
     * Object mode: reads the layer's data and the targets, then the preview's source: the
     * downscaled pixels when they preview exactly (C12, [ObjectColorTargets.exactByPixels]), else
     * the render bitmaps; the other path's buffers are let go, as the rule can change with the
     * selection. The preview bitmaps are created once.
     */
    private fun buildObjectSource() {
        val sel = controller.selection
        val data = layer.dataSnapshot()
        val s = ObjectSource(data, ObjectColorTargets.of(data, selectedIds, sel), layer.contentVersion, sel)
        objectSource = s
        objectRule = s.targets.rule
        if (s.targets.exactByPixels(data)) {
            synchronized(scratchLock) {
                scratchBitmap?.recycle()
                scratchBitmap = null
                scratchOwner = null
            }
            buildPreviewSource()
            return
        }
        previewSrc = null
        previewSel = null
        val (pw, ph) = FilterSessionMath.previewSize(controller.doc.width, controller.doc.height)
        previewScale = pw.toFloat() / controller.doc.width
        sourceVersion = s.version
        previewSelSource = sel
        val bmp = previewBitmap
        if (bmp == null || bmp.width != pw || bmp.height != ph) {
            previewBitmap = BitmapUtils.createLayerBitmap(pw, ph)
            bmp?.recycle()
        }
        synchronized(scratchLock) {
            val old = scratchBitmap
            if (old == null || old.width != pw || old.height != ph) {
                scratchBitmap = BitmapUtils.createLayerBitmap(pw, ph)
                old?.recycle()
            }
        }
    }

    /** Object mode: re-reads the data and the targets when the layer's data or the selection changed. */
    private fun refreshObjectSource(): Refresh {
        val s = objectSource
        if (s != null && s.version == layer.contentVersion && s.selection === controller.selection && s.data == layer.dataSnapshot()) {
            return Refresh.NONE
        }
        try {
            buildObjectSource()
        } catch (e: OutOfMemoryError) {
            controller.toast("Not enough memory to preview \"${filter.name}\"")
            return Refresh.FAILED
        }
        return Refresh.CHANGED
    }

    /**
     * Object mode: the layer's data with the targets' colors mapped by the filter's colorMapper,
     * rendered at [previewScale] into [scratchBitmap] on [Dispatchers.Default], then shown over
     * the whole layer (objects touching a selection change whole, so there is no region). The
     * sources are fresh ([renderOnce]).
     */
    private suspend fun renderObjectsOnce(self: Job) {
        val src = objectSource ?: return
        val vals = values
        val mapper = filter.colorMapper(vals) ?: return
        val colorMode = controller.doc.colorMode
        val scale = previewScale
        val doc = Rect(0, 0, controller.doc.width, controller.doc.height)
        computingJob = self
        computeStartNs = System.nanoTime()
        val rendered = try {
            withContext(Dispatchers.Default) {
                val job = currentCoroutineContext().job
                val after = src.targets.map(src.data, mapper)
                synchronized(scratchLock) {
                    val out = scratchBitmap
                    if (out == null || !job.isActive) return@withContext false
                    out.eraseColor(0)
                    val cv = Canvas(out)
                    cv.scale(scale, scale)
                    val done = drawObjects(cv, after, doc) { job.isActive }
                    // Same constraint commitEdit applies, so the preview shows the final colors.
                    if (done && colorMode != ColorMode.RGB) ColorModeOps.constrain(out, Rect(0, 0, out.width, out.height), colorMode)
                    if (done) scratchOwner = self
                    done
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            controller.toast("Not enough memory to preview \"${filter.name}\"")
            false
        } catch (e: Exception) {
            controller.toast("${filter.name} failed: ${e.message ?: e.javaClass.simpleName}")
            false
        } finally {
            if (computingJob === self) computingJob = null
        }
        lastRenderMs = (System.nanoTime() - computeStartNs) / 1_000_000
        PerfTrace.record(PerfTrace.FILTER_PREVIEW, System.nanoTime() - computeStartNs)
        if (!rendered || isClosed) return
        publishObjectPreview(self, vals, src)
    }

    /** Object mode: [scratchBitmap], rendered by [self] from [vals] and [src], becomes the shown preview. */
    private fun publishObjectPreview(self: Job, vals: FilterValues, src: ObjectSource) {
        synchronized(scratchLock) {
            val shown = scratchBitmap
            if (scratchOwner !== self || shown == null) return
            scratchBitmap = previewBitmap
            previewBitmap = shown
            scratchOwner = null
        }
        shownRegion = null
        hasPreview = true
        previewStale = vals !== values || src !== objectSource
        if (!isComparing) {
            installOverride()
            controller.invalidateDoc(null)
        }
    }

    /**
     * Draws [d] (document px) as the layer's cache shows it, as `DataRender` and
     * `updateLayerData` render it: vector objects with a live array's copies
     * (`ArrayDraw.effectiveVector`), a text or shape source repeated per copy
     * (`ArrayDraw.drawWithArray`). False when [active] stopped it. Under [scratchLock].
     */
    private fun drawObjects(cv: Canvas, d: LayerData, doc: Rect, active: () -> Boolean): Boolean {
        val array = d.array
        if (array?.pixels != null) {
            ArrayDraw.drawPixels(cv, array)
            return true
        }
        d.vector?.let { v ->
            val content = ArrayDraw.effectiveVector(d) ?: v
            return VectorLayerRenderer.renderWith(cv, content, doc, emptySet(), objectTips, doc, objectCache) { _, _ -> active() }
        }
        val source: (Canvas) -> Unit = when {
            d.text != null -> {
                val items = TextCodec.decodeAll(d.text)
                val preps = items.map { TextRenderer.prepare(it) }
                ({ c -> for (k in items.indices) TextRenderer.drawItem(c, items[k], preps[k], null) })
            }
            d.shape != null -> {
                val content = ObjectColorTargets.shapeContent(d.shape) ?: return true
                ({ c -> VectorLayerRenderer.render(c, content, doc, tips = objectTips, document = doc) })
            }
            else -> return true
        }
        if (array != null) ArrayDraw.drawWithArray(cv, array, ArrayDraw.sourceBounds(d) ?: RectF(), source) else source(cv)
        return active()
    }

    // ------------------------------------------------------------------ apply

    // region v19-A7b apply
    /**
     * Runs the filter at full resolution in the background (with a progress overlay), then writes
     * the result inside the selection and records one undo step. On failure or out-of-memory the
     * session stays open; [cancelApply] stops a long run.
     */
    fun apply() {
        if (isClosed || isApplying) return
        if (!checkTarget()) return
        if (!controller.checkEditable(layer)) return
        Breadcrumbs.add("filter:${filter.id}:apply")
        // v1.10 (R6): object mode changes the layer's data, never its pixels alone.
        if (objectMode) { applyObjects(); return }
        val vals = values
        val sel = controller.selection
        // v1.9 (A7b step 3): a local filter runs in bands of its region (FilterTiling), the others
        // on the whole image as in v1.8; the same pixels either way.
        val mapper = FilterTiling.mapperFor(filter, vals)
        val region = sel?.let { regionOf(it) }
        // v1.9 (design 2.4.13, A7b step 2): the buffers must fit in the free heap less 64 MB.
        val estimate = if (mapper != null) FilterTiling.estimate(targetBitmap.width, targetBitmap.height, region, sel != null)
        else targetBitmap.width.toLong() * targetBitmap.height * 8
        if (!MemoryGovernor.admit(estimate, filter.name)) {
            controller.toast(String.format(StabilityLabels19.NO_MEMORY_FOR, filter.name))
            return
        }
        // A pending preview is dropped (CPU goes to the apply); it's re-run if the apply stops.
        val droppedPreview = previewJob?.isActive == true
        previewJob?.cancel()
        val alphaLocked = layer.alphaLocked
        val mask = target == EditTarget.MASK
        val colorMode = if (mask) ColorMode.RGB else controller.doc.colorMode
        val dpi = controller.doc.dpi
        val svc = services
        val bmp = targetBitmap
        // The pixels are read in the background; if anything edits them meanwhile, don't overwrite.
        val version = layer.contentVersion
        isApplying = true
        applyCancelRequested = false
        applyProgress = -1f
        rawApplyProgress = -1f
        controller.runBusy(filter.name, onCancel = { cancelApply() }) {
            applyJob = currentCoroutineContext().job
            try {
                // Closed or stopped before this coroutine got to run.
                if (isClosed || applyCancelRequested) return@runBusy
                val outcome = coroutineScope {
                    val ticker = launch {
                        while (true) {
                            val p = rawApplyProgress
                            if (p >= 0f) { applyProgress = p; controller.busyProgress = p }
                            delay(100)
                        }
                    }
                    try {
                        withContext(Dispatchers.Default) {
                            val job = currentCoroutineContext().job
                            val ctx = FilterContext(
                                scale = 1f, dpi = dpi, services = svc,
                                cancelled = { !job.isActive },
                                progressSink = { rawApplyProgress = it * 0.9f },
                            )
                            val selBytes = sel?.let { BitmapUtils.alpha8ToBytes(it.mask) }
                            if (mapper != null) {
                                FilterTiling.run(
                                    bmp.width, bmp.height,
                                    { dst, x, y, cols, rows -> bmp.getPixels(dst, 0, cols, x, y, cols, rows) },
                                    mapper, selBytes, alphaLocked, mask, colorMode, region,
                                    checkCancelled = ctx::checkCancelled, progress = ctx::progress,
                                )
                            } else {
                                val src = BitmapUtils.toPixelBuffer(bmp)
                                val whole = runFilter(src, vals, ctx, selBytes, alphaLocked, mask, colorMode, region, findChanges = true)
                                FilterTiling.Result.whole(whole.out, whole.changed)
                            }
                        }
                    } finally {
                        ticker.cancel()
                    }
                }
                commitResult(outcome, version)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                controller.toast("Not enough memory to apply \"${filter.name}\" to the whole layer")
            } catch (e: Exception) {
                controller.toast("${filter.name} failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                applyJob = null
                isApplying = false
                applyProgress = -1f
                if (!isClosed) {
                    val stale = controller.selection !== previewSelSource || layer.contentVersion != sourceVersion
                    if (stale) syncWithDocument() else if (droppedPreview) requestPreview(0L)
                }
            }
        }
    }

    /**
     * Stops a running apply; the session stays open with its preview. Also offered by the
     * editor's busy overlay (controller.busyCancel).
     */
    fun cancelApply() {
        if (!isApplying) return
        applyCancelRequested = true
        applyJob?.cancel()
    }

    private fun commitResult(outcome: FilterTiling.Result, version: Long) {
        if (isClosed || !checkTarget()) return
        if (layer.contentVersion != version) {
            controller.toast("\"${layer.name}\" changed while ${filter.name} was running, so it wasn't applied")
            return
        }
        val changed = outcome.changed
        if (changed == null) {
            controller.toast(unchangedMessage())
            close()
            return
        }
        val rect = Rect(changed.left, changed.top, changed.right, changed.bottom)
        val rec = controller.beginEdit(layer, target)
        rec.touch(rect)
        targetBitmap.setPixels(outcome.pixels, outcome.offsetOf(rect.left, rect.top), outcome.region.width, rect.left, rect.top, rect.width(), rect.height())
        clearOverride()
        controller.commitEdit(rec, filter.name)
        close()
    }
    // endregion

    private fun unchangedMessage() = "${filter.name} didn't change anything here"

    /**
     * v1.10 (R6, I1, I2): object mode's apply. The targets' colors are mapped in the layer's data,
     * which is re-rendered with it as ONE step named after the filter; nothing is rasterized and
     * the layer keeps its vector content, texts or shape. Vector content goes through
     * `VectorLayers.update` (sync or async; a live array's copies follow their source and keep
     * its tint), a text or shape layer through `DataRender.apply`. The preview stays shown until
     * the layer shows the result; a data equal to the layer's says so, as the pixel path does.
     */
    private fun applyObjects() {
        if (refreshObjectSource() == Refresh.FAILED) return
        val src = objectSource ?: return
        val mapper = filter.colorMapper(values) ?: return
        val after = src.targets.map(src.data, mapper)
        if (after == src.data) {
            controller.toast(unchangedMessage())
            close()
            return
        }
        previewJob?.cancel()
        val vector = after.vector
        if (vector != null) {
            isApplying = true
            controller.vectors.update(layer, vector, filter.name) { applied ->
                isApplying = false
                if (applied) close() else if (!isClosed) syncWithDocument()
            }
            return
        }
        if (DataRender.apply(controller, layer, after, filter.name, Rect(), allowHidden = false)) close()
    }

    /**
     * False (and the session is closed) if the target layer was removed or its bitmap replaced.
     * v1.10 (F0, C16): replaced is a changed [Layer.pixelGeneration], which the layer's bitmap and
     * mask share.
     */
    private fun checkTarget(): Boolean {
        val ok = controller.doc.indexOf(layer) >= 0 && layer.pixelGeneration == targetGeneration && !targetBitmap.isRecycled &&
            targetBitmap.width == controller.doc.width && targetBitmap.height == controller.doc.height
        if (!ok) {
            controller.toast("The layer changed, so ${filter.name} was cancelled")
            cancel()
        }
        return ok
    }

    private class RunResult(val out: PixelBuffer, val changed: PixelRect?)

    /** Filter + selection/alpha-lock/mask/color-mode rules; shared by preview and apply. */
    private fun runFilter(
        src: PixelBuffer,
        vals: FilterValues,
        ctx: FilterContext,
        sel: ByteArray?,
        alphaLocked: Boolean,
        mask: Boolean,
        colorMode: ColorMode,
        region: PixelRect?,
        findChanges: Boolean,
    ): RunResult {
        var out = filter.apply(src, vals, ctx)
        if (out === src) out = src.copy() // defensive: the result is modified in place below
        ctx.checkCancelled()
        val r = region ?: PixelRect.full(src.width, src.height)
        FilterSessionMath.compose(src, out, sel, alphaLocked, mask, r, ctx::checkCancelled)
        if (colorMode != ColorMode.RGB) {
            // Same constraint commitEdit applies, so the preview shows the final colors.
            val p = out.pixels; val w = out.width
            Parallel.forRange(r.height, 4) { y0, y1 ->
                for (y in r.top + y0 until r.top + y1) for (x in r.left until r.right) p[y * w + x] = ColorModeOps.constrainPixel(p[y * w + x], colorMode)
            }
        }
        ctx.checkCancelled()
        val changed = if (findChanges) FilterSessionMath.changedBounds(src, out, r) else null
        return RunResult(out, changed)
    }

    private fun regionOf(sel: Selection): PixelRect = sel.bounds.let { PixelRect(it.left, it.top, it.right, it.bottom) }

    /** Selection coverage downscaled to the preview size (packed bytes). */
    private fun scaledSelectionBytes(sel: Selection, w: Int, h: Int): ByteArray {
        val m = sel.mask
        val scaled = FilterSessionBitmaps.downscale(m, w, h)
        try {
            return BitmapUtils.alpha8ToBytes(scaled)
        } finally {
            if (scaled !== m) scaled.recycle()
        }
    }

    // ------------------------------------------------------------------ point parameters

    /** Document position of point parameter [key]. */
    fun pointPosition(key: String): Pair<Float, Float> {
        val v = values.point(key)
        return v[0] * controller.doc.width to v[1] * controller.doc.height
    }

    /** Returns true (and takes the gesture) if the filter has point parameters to drag. */
    fun onPointerDown(p: ToolPoint): Boolean {
        if (isClosed || isApplying || pointParams.isEmpty()) return false
        if (draggingPoint != null) onPointerCancel() // the previous gesture never ended
        heldByTap = false
        val t = controller.viewTransform
        val finger = t.docToScreen(p.x, p.y)
        var best = pointParams.first()
        var bestDist = Float.MAX_VALUE
        for (pp in pointParams) {
            val (dx, dy) = pointPosition(pp.key)
            val s = t.docToScreen(dx, dy)
            val d = hypot(s.x - finger.x, s.y - finger.y)
            if (d < bestDist) { bestDist = d; best = pp }
        }
        // Grabbing a handle keeps its offset; touching elsewhere jumps the nearest point there.
        val (bx, by) = pointPosition(best.key)
        if (bestDist <= t.dp(HANDLE_GRAB_DP)) { grabDx = bx - p.x; grabDy = by - p.y } else { grabDx = 0f; grabDy = 0f }
        draggingPoint = best.key
        dragStartValue = values.point(best.key).copyOf()
        movePointTo(p)
        controller.invalidateOverlay()
        return true
    }

    fun onPointerMove(p: ToolPoint) {
        if (draggingPoint != null) movePointTo(p)
    }

    /** The drag ended: v1.9 (R8), the point's new place is one step. */
    fun onPointerUp(p: ToolPoint) {
        val key = draggingPoint ?: return
        movePointTo(p)
        draggingPoint = null
        dragStartValue = null
        controller.invalidateOverlay()
        commitStep(key)
    }

    /**
     * The canvas gesture was cancelled (a second finger landed for pinch-zoom or a two-finger
     * tap): the dragged point goes back to where it was before the gesture.
     */
    fun onPointerCancel() {
        val key = draggingPoint ?: return
        val start = dragStartValue
        draggingPoint = null
        dragStartValue = null
        if (start != null && !values.point(key).contentEquals(start)) update(key, start)
        controller.invalidateOverlay()
    }

    private fun movePointTo(p: ToolPoint) {
        val key = draggingPoint ?: return
        val w = controller.doc.width.toFloat(); val h = controller.doc.height.toFloat()
        val nx = ((p.x + grabDx) / w).coerceIn(0f, 1f)
        val ny = ((p.y + grabDy) / h).coerceIn(0f, 1f)
        val old = values.point(key)
        if (old[0] == nx && old[1] == ny) return
        update(key, floatArrayOf(nx, ny))
        controller.invalidateOverlay()
    }

    /** Crosshair handles for point parameters, in screen space. */
    fun drawOverlay(canvas: Canvas, t: ViewTransform) {
        if (isClosed || pointParams.isEmpty()) return
        // EditorController.pointerCancel() doesn't notify the session, so a drag that ended
        // without onPointerUp was cancelled: undo its move before drawing the handles.
        if (draggingPoint != null && !controller.isInteracting) onPointerCancel()
        val r = t.dp(11f)
        for (pp in pointParams) {
            val (dx, dy) = pointPosition(pp.key)
            val s = t.docToScreen(dx, dy)
            val active = draggingPoint == pp.key
            for (pass in 0..1) {
                overlayPaint.strokeWidth = if (pass == 0) t.dp(4f) else t.dp(1.75f)
                overlayPaint.color = if (pass == 0) 0x99000000.toInt() else if (active) ACCENT else 0xFFFFFFFF.toInt()
                canvas.drawCircle(s.x, s.y, r, overlayPaint)
                val a = r * 0.45f; val b = r * 1.9f
                canvas.drawLine(s.x - b, s.y, s.x - a, s.y, overlayPaint)
                canvas.drawLine(s.x + a, s.y, s.x + b, s.y, overlayPaint)
                canvas.drawLine(s.x, s.y - b, s.x, s.y - a, overlayPaint)
                canvas.drawLine(s.x, s.y + a, s.x, s.y + b, overlayPaint)
            }
            if (pointParams.size > 1) {
                labelPaint.textSize = t.dp(12f)
                labelPaint.setShadowLayer(t.dp(2f), 0f, 0f, 0xFF000000.toInt())
                labelPaint.color = 0xFFFFFFFF.toInt()
                canvas.drawText(pp.label, s.x + r * 1.4f, s.y - r * 1.4f, labelPaint)
            }
        }
    }

    companion object {
        /** v1.9 (R8): the header's reset button, and the label of the step it records ("Undo: Reset to defaults"). */
        const val RESET_ALL = "Reset to defaults"

        /** A render slower than this is restarted (not queued) when parameters change. */
        private const val SLOW_RENDER_MS = 250L
        /** Bytes of brush tips the object preview's renders may cache. */
        private const val OBJECT_TIP_BYTES = 8L shl 20
        private const val HANDLE_GRAB_DP = 36f
        private const val ACCENT = 0xFF4DA3FF.toInt()
    }
}
