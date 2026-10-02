package com.brushwork.paint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.assist.GridRenderer
import com.brushwork.paint.assist.RulerRenderer
import com.brushwork.paint.assist.StrokeAssist
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.BrushPreset
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.AddLayerAction
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.engine.CompositeAction
import com.brushwork.paint.engine.DisplayTiles
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.engine.LambdaAction
import com.brushwork.paint.engine.LayerDataAction
import com.brushwork.paint.engine.LayerPropsAction
import com.brushwork.paint.engine.LayerRenderOverride
import com.brushwork.paint.engine.MaskChangeAction
import com.brushwork.paint.engine.MoveLayerAction
import com.brushwork.paint.engine.PixelEditRecorder
import com.brushwork.paint.engine.RemoveLayerAction
import com.brushwork.paint.engine.SelectionAction
import com.brushwork.paint.engine.UndoAction
import com.brushwork.paint.engine.UndoManager
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.FilterSession
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.masks.MaskSpec
import com.brushwork.paint.masks.MaskSpecs
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.GridSettings
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.model.LayerProps
import com.brushwork.paint.model.RulerSettings
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.StabilizerSettings
import com.brushwork.paint.snap.SnapService
import com.brushwork.paint.snap.SnapSession
import com.brushwork.paint.tools.LayerToolRules
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolFactory
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.select.SelectionOutline
import com.brushwork.paint.tools.text.TextWrapReflow
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorLayerOps
import com.brushwork.paint.vector.LayerDataTransforms
import com.brushwork.paint.vector.VectorLayers
import com.brushwork.paint.vector.select.PendingRenders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * A committed edit of [layer] (v1.5): its [target] changed within [rect] (document px; null =
 * unknown / the whole layer, or data only), as the step named [label]. Delivered to
 * [EditListener]s when the outermost [EditorController.editScope] ends; never for undo / redo.
 */
data class EditEvent(val layer: Layer, val target: EditTarget, val rect: Rect?, val label: String)

/**
 * Reacts to committed edits (v1.5; e.g. text wrapped around a picture re-flows). A listener that
 * pushes undo steps MUST do it inside [EditorController.amendLastStep], so the user's action
 * stays one undo step (I2). Main thread.
 */
fun interface EditListener {
    fun onEdited(e: EditEvent)
}

/**
 * A live edit that records its undo step only when it ends (v1.5; e.g. the Masks tool's Adjust
 * sheet previews effect values with no undo, then records one "Edit adjustment" step). While it
 * is registered ([EditorController.addDeferredStep]), [flush] is called before any other step is
 * pushed and before undo / redo, so it records its step first and the history stays in order.
 * [flush] may push one step (it is not called again for its own pushes) and must leave nothing
 * pending; it is not called while undo / redo run or while edit listeners are notified. It may
 * run after the triggering action already changed the document but before that action's step is
 * pushed, so it must only touch its own data (not depend on the active layer or on layer
 * properties). Keep one pending only while its owner tool is active (grouping by step count, like
 * a placement's `mergeLastUndo(2)`, assumes nothing is flushed in between). Main thread.
 */
fun interface DeferredStep {
    fun flush()
}

/**
 * Central editor state + operations. One instance per open document; lives in a ViewModel.
 * EVERYTHING here must be called on the main thread (use [scope] + withContext for background
 * work and come back to the main thread to touch the document).
 *
 * Compose UI observes the `by mutableStateOf` properties. Bitmaps are not observable: bump
 * [layersVersion] after structural/visual layer changes so panels refresh (the helpers here do).
 */
class EditorController(
    val appContext: Context,
    val doc: Document,
    val scope: CoroutineScope,
    val settings: AppSettings,
) {
    // ------------------------------------------------------------------ rendering

    /** Live preview hook used by tools/filters (see [LayerRenderOverride]). */
    var renderOverride: LayerRenderOverride? = null

    val compositor = Compositor(doc) { renderOverride }

    var tiles: DisplayTiles = DisplayTiles(doc.width, doc.height)
        private set

    /** Updated by the canvas view. */
    val viewTransform = ViewTransform()

    /** Set by the canvas view: schedules a redraw (postInvalidateOnAnimation). */
    var onInvalidate: (() -> Unit)? = null

    /** Marks a document region (null = all) for recomposition and schedules a redraw. */
    fun invalidateDoc(rect: Rect?) {
        tiles.invalidate(rect)
        onInvalidate?.invoke()
    }

    /** Redraw overlays only (handles, guides, marching ants). */
    fun invalidateOverlay() { onInvalidate?.invoke() }

    // ------------------------------------------------------------------ observable state

    /** Bumped on any layer list / property / thumbnail-relevant change. */
    var layersVersion by mutableIntStateOf(0)
        private set

    /** Bumped when the document size changes (canvas view refits). */
    var docVersion by mutableIntStateOf(0)
        private set

    /** Bumped on every undoable edit; used for autosave. */
    var editCount by mutableIntStateOf(0)
        private set

    var activeToolId by mutableStateOf(ToolId.BRUSH)
        private set

    /** Last non-eraser painting tool, for the brush/eraser toggle button. */
    var lastPaintTool by mutableStateOf(ToolId.BRUSH)
        private set

    /** Primary drawing color (opaque ARGB). */
    var color by mutableIntStateOf(0xFF000000.toInt())
    var secondaryColor by mutableIntStateOf(0xFFFFFFFF.toInt())

    var brush by mutableStateOf(BrushLibrary.defaultBrush)
    var eraser by mutableStateOf(BrushLibrary.defaultEraser)
    var smudgeBrush by mutableStateOf(BrushLibrary.defaultSmudge)
    var blurBrush by mutableStateOf(BrushLibrary.defaultBlur)
    /** The Masks tool's brush component brush (v1.5; [presetFor] / [updatePreset] of MASK). */
    var maskBrush by mutableStateOf(BrushLibrary.defaultMaskBrush)
    /** The clone stamp's brush (v1.5; [presetFor] / [updatePreset] of CLONE). */
    var cloneBrush by mutableStateOf(BrushLibrary.defaultClone)

    var selection by mutableStateOf<Selection?>(null)
        private set

    // Only the model default (-1, -1) means "not placed yet"; a ruler may legitimately sit left of
    // or above the canvas.
    var ruler by mutableStateOf(doc.ruler.let { if (it.centerX == -1f && it.centerY == -1f) it.copy(centerX = doc.width / 2f, centerY = doc.height / 2f) else it })
        private set

    /** Transform tools set this while the selection is being moved (hides the stale ants). */
    var hideSelectionOutline by mutableStateOf(false)

    /**
     * "Snap to objects" for every tool (one setting) and what dragged things align to: canvas,
     * layer content, points, lines drawn in layers (Table filter lines...). Tools take a
     * [newSnapSession] per drag.
     */
    val snapping = SnapService(this)

    /** A snapping helper for one tool's drags (see [SnapSession]). */
    fun newSnapSession() = SnapSession(snapping, this)

    var grid by mutableStateOf(doc.grid)
        private set

    var stabilizer by mutableStateOf(settings.stabilizer)
        private set

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /** Non-null while a long operation runs (shows a progress overlay). */
    var busyMessage by mutableStateOf<String?>(null)
        private set
    /** 0..1, or < 0 for indeterminate. */
    var busyProgress by mutableFloatStateOf(-1f)

    /** One-shot user message (snackbar). The UI clears it after showing. */
    var message by mutableStateOf<String?>(null)

    /** Active filter being previewed (null when none). */
    var filterSession by mutableStateOf<FilterSession?>(null)
        internal set

    /** View-only horizontal mirror of the canvas (to check drawings, like ibisPaint's flip). */
    var viewMirrored by mutableStateOf(false)

    fun toast(text: String) { message = text }

    // ------------------------------------------------------------------ memory / history

    private val maxHeap = Runtime.getRuntime().maxMemory()

    val undoManager = UndoManager(maxBytes = max(48L shl 20, maxHeap / 4)).also { um ->
        um.onChanged = { canUndo = um.canUndo; canRedo = um.canRedo }
    }

    /** Maximum number of layers for this canvas size given the heap. */
    val maxLayers: Int
        get() {
            val budget = (maxHeap * 0.55).toLong()
            val per = max(1L, doc.layerBytes)
            return (budget / per - 3).toInt().coerceIn(2, 100)
        }

    val canAddLayer: Boolean get() = doc.layers.size < maxLayers

    fun pushUndo(action: UndoAction) {
        // A pending live edit records its step first (inside an edit scope that was done when
        // the scope began).
        if (editDepth == 0) flushDeferredSteps()
        undoManager.push(action)
        editCount++
        doc.touch()
    }

    /**
     * Runs [block] and folds every undo action it pushes into ONE step named [label] (e.g. a curve
     * that is filled and then stroked with the brush tool). Edit listeners hear about the edits
     * once the step is complete (inside [editScope]).
     */
    fun groupUndo(label: String, block: () -> Unit) = editScope {
        val mark = undoManager.undoCount
        try {
            block()
        } finally {
            val added = undoManager.takeSince(mark)
            when {
                added.size == 1 -> undoManager.pushRaw(added[0])
                added.size > 1 -> undoManager.pushRaw(CompositeAction(label, added))
            }
        }
    }

    // ------------------------------------------------------------------ edit events (v1.5, I2)

    private val editListeners = ArrayList<EditListener>()

    /** Depth of nested [editScope]s; events are delivered when the outermost one ends. */
    @PublishedApi internal var editDepth = 0

    private val queuedEdits = ArrayList<EditEvent>()

    /** True while undo / redo run: their edits are not reported (listeners never react to history). */
    private var inHistory = false

    /** True while queued events are being delivered (events queued meanwhile join the delivery). */
    private var delivering = false

    /** Listens to committed edits (see [EditEvent]). */
    fun addEditListener(l: EditListener) {
        if (editListeners.none { it === l }) editListeners += l
    }

    fun removeEditListener(l: EditListener) {
        editListeners.removeAll { it === l }
    }

    /**
     * Runs [block] as part of one user action: [commitEdit], [updateLayerData], [setLayerData],
     * [groupUndo] and `undoStepNamed` run inside it. The edit events they queue are delivered
     * when the OUTERMOST scope ends (so the action's undo step is complete and listeners can
     * [amendLastStep] it), never during undo / redo. The history is not trimmed until the
     * outermost scope (and the listeners) are done ([UndoManager.holdTrim]): steps grouped by a
     * mark taken inside a scope stay grouped also when the history is full.
     */
    inline fun <T> editScope(block: () -> T): T {
        // A pending live edit records its step before this action's steps (see [DeferredStep]).
        if (editDepth == 0) flushDeferredSteps()
        editDepth++
        undoManager.holdTrim()
        try {
            return block()
        } finally {
            editDepth--
            try {
                if (editDepth == 0) deliverEdits()
            } finally {
                undoManager.releaseTrim()
            }
        }
    }

    private val deferredSteps = ArrayList<DeferredStep>()

    /** True while [DeferredStep]s record their steps (their own pushes don't flush again). */
    private var flushingDeferred = false

    /** Registers a live edit that records its step later (see [DeferredStep]). */
    fun addDeferredStep(d: DeferredStep) {
        if (deferredSteps.none { it === d }) deferredSteps += d
    }

    fun removeDeferredStep(d: DeferredStep) {
        deferredSteps.removeAll { it === d }
    }

    /** Lets every [DeferredStep] record its step now (not during undo / redo or listener delivery). */
    @PublishedApi internal fun flushDeferredSteps() {
        if (deferredSteps.isEmpty() || flushingDeferred || inHistory || delivering) return
        flushingDeferred = true
        try {
            for (d in deferredSteps.toList()) d.flush()
        } finally {
            flushingDeferred = false
        }
    }

    /** Queues [e] for the listeners (dropped during undo / redo). */
    @PublishedApi internal fun queueEdit(e: EditEvent) {
        if (inHistory || editListeners.isEmpty()) return
        queuedEdits += e
        if (editDepth == 0) deliverEdits()
    }

    @PublishedApi internal fun deliverEdits() {
        if (queuedEdits.isEmpty() || delivering) return
        if (inHistory) { queuedEdits.clear(); return }
        delivering = true
        try {
            var rounds = 0
            // Listeners may edit (and queue events) themselves: those are delivered in the next
            // round; a bounded number of rounds stops listeners that keep reacting to each other.
            while (queuedEdits.isNotEmpty() && rounds++ < MAX_EDIT_ROUNDS) {
                val batch = queuedEdits.toList()
                queuedEdits.clear()
                for (e in batch) for (l in editListeners.toList()) l.onEdited(e)
            }
            queuedEdits.clear()
        } finally {
            delivering = false
        }
    }

    /**
     * Runs [block] and folds every undo step it pushes INTO the newest existing step (which keeps
     * its label), so a listener's follow-up edit undoes together with the edit that caused it
     * (I2). Without any step yet, what [block] pushes stays as it is. Works when the history is
     * full too (trimming waits until the step is complete).
     */
    fun amendLastStep(block: () -> Unit) {
        val um = undoManager
        um.holdTrim()
        try {
            val mark = um.undoCount
            try {
                block()
            } finally {
                if (mark > 0) {
                    val added = um.takeSince(mark)
                    if (added.isNotEmpty()) {
                        val last = um.popLast()
                        if (last != null) um.pushRaw(CompositeAction(last.label, listOf(last) + added))
                        else added.forEach { um.pushRaw(it) }
                    }
                }
            }
        } finally {
            um.releaseTrim()
        }
    }

    /** Folds the newest [count] undo steps into one step named [label] (no-op if fewer exist). */
    fun mergeLastUndo(count: Int, label: String) {
        if (count < 2 || undoManager.undoCount < count) return
        val actions = undoManager.takeSince(undoManager.undoCount - count)
        undoManager.pushRaw(CompositeAction(label, actions))
    }

    /**
     * Removes the newest undo step WITHOUT undoing it and without leaving it on the redo stack.
     * The caller must already have reverted its effect (e.g. a discarded picture placement).
     */
    fun dropLastUndo(): UndoAction? = undoManager.popLast()

    fun undo() {
        val session = filterSession
        if (session != null) { session.cancel(); return }
        // A pending live edit becomes its step first: undo then takes it back.
        flushDeferredSteps()
        // So do vector edits still rendering and the object edits waiting for them (v1.5): undo
        // takes back the newest, and nothing lands on top of what it took back.
        PendingRenders.settle(this)
        val tool = currentTool
        if (tool.hasPendingWork) {
            // Tools with steps (points of a curve/polygon) take back only the last one.
            if (tool.hasUserChanges && tool.undoStep()) { invalidateOverlay(); return }
            // The user's pending work is what undo takes back; an untouched automatic lift
            // (transform tool) is just dropped and the last step is undone as usual.
            val userWork = tool.hasUserChanges
            tool.discard()
            invalidateOverlay()
            if (userWork) return
        }
        if (inHistoryDo { undoManager.undo(this) }) { editCount++; doc.touch() }
    }

    fun redo() {
        if (filterSession != null) return
        // A pending live edit becomes its step first (it clears the redo stack, as any new edit).
        flushDeferredSteps()
        // So do vector edits still rendering and the object edits waiting for them (v1.5).
        PendingRenders.settle(this)
        val tool = currentTool
        if (tool.hasPendingWork) {
            if (tool.redoStep()) { invalidateOverlay(); return }
            if (tool.hasUserChanges) return
            tool.discard()
            invalidateOverlay()
        }
        if (inHistoryDo { undoManager.redo(this) }) { editCount++; doc.touch() }
    }

    /** Runs an undo / redo: edits it causes (a tool reacting to a restored selection...) are not reported to edit listeners. */
    private inline fun inHistoryDo(block: () -> Boolean): Boolean {
        val prev = inHistory
        inHistory = true
        try {
            return block()
        } finally {
            inHistory = prev
            if (!prev) queuedEdits.clear()
        }
    }

    /** Tells observers (layer panel thumbnails, etc.) that layer content/properties changed. */
    fun notifyLayersChanged() { layersVersion++ }

    /** Runs a structural change and refreshes everything that depends on the layer list. */
    fun structural(block: () -> Unit) {
        block()
        if (doc.layers.isNotEmpty()) doc.activeLayerIndex = doc.activeLayerIndex.coerceIn(0, doc.layers.lastIndex)
        layersVersion++
        invalidateDoc(null)
    }

    /** Called after the document size / all layer bitmaps were replaced. */
    fun onDocumentGeometryChanged() {
        renderOverride = null
        tiles.release()
        tiles = DisplayTiles(doc.width, doc.height)
        if (selection != null) { selection = null }
        // Canvas operations move the ruler themselves (updateRuler); just keep the document in sync.
        doc.ruler = ruler
        docVersion++
        layersVersion++
        invalidateDoc(null)
    }

    // ------------------------------------------------------------------ tools

    private val toolsLazy = lazy { ToolFactory.create(this) }
    val tools: Map<ToolId, Tool> by toolsLazy

    val currentTool: Tool get() = tools.getValue(activeToolId)

    val strokeAssist: StrokeAssist by lazy { StrokeAssist(this) }

    fun selectTool(id: ToolId) {
        if (id == activeToolId) return
        filterSession?.cancel()
        currentTool.onDeactivate()
        activeToolId = id
        if (id == ToolId.BRUSH || id == ToolId.SMUDGE || id == ToolId.BLUR) lastPaintTool = id
        currentTool.onActivate()
        currentTool.onSelected()
        invalidateOverlay()
    }

    /** The brush/eraser toggle button. */
    fun toggleEraser() {
        if (activeToolId == ToolId.ERASER) selectTool(lastPaintTool) else selectTool(ToolId.ERASER)
    }

    val isPaintTool: Boolean get() = activeToolId in PAINT_TOOLS

    /** Preset of a painting tool (brush/eraser/smudge/blur/clone stamp/mask brush), else null. */
    fun presetFor(id: ToolId): BrushPreset? = when (id) {
        ToolId.BRUSH -> brush
        ToolId.ERASER -> eraser
        ToolId.SMUDGE -> smudgeBrush
        ToolId.BLUR -> blurBrush
        ToolId.CLONE -> cloneBrush
        ToolId.MASK -> maskBrush
        else -> null
    }

    fun updatePreset(id: ToolId, preset: BrushPreset) {
        when (id) {
            ToolId.BRUSH -> brush = preset
            ToolId.ERASER -> eraser = preset
            ToolId.SMUDGE -> smudgeBrush = preset
            ToolId.BLUR -> blurBrush = preset
            ToolId.CLONE -> cloneBrush = preset
            ToolId.MASK -> maskBrush = preset
            else -> {}
        }
    }

    /** Preset shown by the side size/opacity sliders (current paint tool, else the brush). */
    val sliderToolId: ToolId get() = if (isPaintTool) activeToolId else lastPaintTool

    // ------------------------------------------------------------------ input dispatch

    private var gestureTool: Tool? = null
    private var gestureToFilter = false

    /** True while a finger/stylus gesture is being processed by a tool or filter. */
    val isInteracting: Boolean get() = gestureTool != null || gestureToFilter
    private var gestureAssisted = false
    private var lastAssisted: ToolPoint? = null

    fun pointerDown(p: ToolPoint) {
        val session = filterSession
        if (session != null) {
            gestureToFilter = session.onPointerDown(p)
            gestureTool = null
            return
        }
        gestureToFilter = false
        // Tools that need pixels can't start on vector / adjustment layers: the gesture is
        // ignored until the finger lifts (no stroke, no undo step).
        LayerToolRules.refusal(activeToolId, activeLayer)?.let { msg ->
            toast(msg)
            gestureTool = null
            return
        }
        val tool = currentTool
        gestureTool = tool
        gestureAssisted = tool.usesStrokeAssist
        if (gestureAssisted) {
            val s = strokeAssist.down(p)
            lastAssisted = s
            tool.onDown(s)
        } else {
            tool.onDown(p)
        }
    }

    fun pointerMove(p: ToolPoint) {
        if (gestureToFilter) { filterSession?.onPointerMove(p); return }
        val tool = gestureTool ?: return
        if (gestureAssisted) {
            for (q in strokeAssist.move(p)) { lastAssisted = q; tool.onMove(q) }
            invalidateOverlay()
        } else {
            tool.onMove(p)
        }
    }

    fun pointerUp(p: ToolPoint) {
        if (gestureToFilter) { filterSession?.onPointerUp(p); gestureToFilter = false; return }
        val tool = gestureTool ?: return
        gestureTool = null
        if (gestureAssisted) {
            val pts = strokeAssist.up(p)
            for (i in 0 until pts.size - 1) tool.onMove(pts[i])
            tool.onUp(pts.lastOrNull() ?: lastAssisted ?: p)
            invalidateOverlay()
        } else {
            tool.onUp(p)
        }
        endHoldPicking()
    }

    fun pointerCancel() {
        if (gestureToFilter) { filterSession?.onPointerCancel(); gestureToFilter = false; return }
        val tool = gestureTool ?: return
        gestureTool = null
        if (gestureAssisted) strokeAssist.cancel()
        tool.onCancel()
        endHoldPicking()
        invalidateOverlay()
    }

    /**
     * The finger stayed still ~450 ms. The tool gets it first (e.g. the curve tool's point menu);
     * otherwise, for tools that paint with the drawing color, the gesture turns into a temporary
     * eyedropper: the stroke so far is cancelled and the eyedropper follows the finger (showing
     * a preview square) until it lifts, then the picked color becomes the drawing color and the
     * tool stays the same.
     */
    fun pointerLongPress(p: ToolPoint): Boolean {
        if (gestureToFilter) return false
        val tool = gestureTool ?: return false
        if (tool.onLongPress(p)) return true
        // (Tools with grabbable handles/points claim the long press themselves in onLongPress.)
        if (holdPicking || !settings.longPressEyedropper || activeToolId !in HOLD_PICK_TOOLS) return false
        val picker = tools[ToolId.EYEDROPPER] ?: return false
        if (gestureAssisted) strokeAssist.cancel()
        tool.onCancel()
        gestureAssisted = false
        holdPicking = true
        gestureTool = picker
        picker.onDown(p)
        invalidateOverlay()
        return true
    }

    /**
     * True while a long-press eyedropper gesture runs (see [pointerLongPress]); the eyedropper
     * must then not switch tools when it finishes, and draws its preview square.
     */
    var holdPicking by mutableStateOf(false)
        private set

    /** Ends hold-picking after the gesture (called from pointerUp/pointerCancel). */
    private fun endHoldPicking() {
        if (holdPicking) { holdPicking = false; invalidateOverlay() }
    }

    // ------------------------------------------------------------------ two-finger gestures

    private var twoFingerTool: Tool? = null

    /**
     * The canvas view offers every two-finger gesture to the current tool first (after any
     * one-finger gesture was cancelled). Returns true if the tool takes it; the view then feeds
     * [twoFingerGesture] and [twoFingerEnd] instead of moving the view.
     */
    fun twoFingerStart(focus: Vec2, a: Vec2, b: Vec2): Boolean {
        twoFingerTool = null
        if (filterSession != null || busyMessage != null) return false
        val tool = currentTool
        if (!tool.onTwoFingerStart(focus, a, b)) return false
        twoFingerTool = tool
        return true
    }

    fun twoFingerGesture(translation: Vec2, scale: Float, rotationDeg: Float) {
        twoFingerTool?.onTwoFingerGesture(translation, scale, rotationDeg)
    }

    fun twoFingerEnd(cancelled: Boolean) {
        val t = twoFingerTool ?: return
        twoFingerTool = null
        t.onTwoFingerEnd(cancelled)
        invalidateOverlay()
    }

    /** Draws grid, ruler, marching ants and tool overlays in screen space. */
    fun drawOverlays(canvas: Canvas, antsPhase: Float) {
        val t = viewTransform
        if (grid.enabled) GridRenderer.draw(canvas, t, doc, grid)
        if (ruler.enabled || activeToolId == ToolId.RULER) RulerRenderer.draw(canvas, t, doc, ruler, activeToolId == ToolId.RULER)
        if (!hideSelectionOutline) selection?.let { SelectionOutline.draw(canvas, t, it, antsPhase) }
        vectors.drawOverlay(canvas, t)
        currentTool.drawOverlay(canvas, t)
        if (holdPicking) tools[ToolId.EYEDROPPER]?.drawOverlay(canvas, t)
        if (gestureAssisted && gestureTool != null) strokeAssist.drawOverlay(canvas, t)
        filterSession?.drawOverlay(canvas, t)
    }

    // ------------------------------------------------------------------ assists

    // Not undoable, but they are saved with the document, so count them as edits for autosave.
    fun updateRuler(r: RulerSettings) { if (r == ruler) return; ruler = r; doc.ruler = r; editCount++; doc.touch(); invalidateOverlay() }
    fun updateGrid(g: GridSettings) { if (g == grid) return; grid = g; doc.grid = g; editCount++; doc.touch(); invalidateOverlay() }
    fun updateStabilizer(s: StabilizerSettings) { stabilizer = s; settings.stabilizer = s }

    // ------------------------------------------------------------------ pixel edits

    /**
     * Returns false (and shows a message) if the active layer can't be painted on: locked,
     * hidden, or an adjustment layer without a mask (it has no pixels; its mask is painted when
     * it has one). For the tools' paths; data updates use the lighter check of [updateLayerData].
     */
    fun checkEditable(layer: Layer = doc.activeLayer): Boolean {
        if (!checkUsable(layer)) return false
        if (layer.isAdjustmentLayer && editTargetOf(layer) == EditTarget.CONTENT) { toast(LayerToolRules.ADJUSTMENT_MESSAGE); return false }
        return true
    }

    /** False (with a message) when [layer] is locked or hidden. */
    private fun checkUsable(layer: Layer): Boolean {
        if (layer.locked) { toast("Layer \"${layer.name}\" is locked"); return false }
        if (!layer.visible) { toast("Layer \"${layer.name}\" is hidden"); return false }
        return true
    }

    /**
     * Edit target of [layer] for painting tools: the mask when editing a mask, and always the
     * mask of an adjustment layer that has one (its pixels are not used).
     */
    fun editTargetOf(layer: Layer): EditTarget = when {
        layer.mask != null && (layer.editingMask || layer.isAdjustmentLayer) -> EditTarget.MASK
        else -> EditTarget.CONTENT
    }

    /** Starts recording a pixel edit on [layer] (see [PixelEditRecorder]). */
    fun beginEdit(layer: Layer = doc.activeLayer, target: EditTarget = editTargetOf(layer)): PixelEditRecorder =
        PixelEditRecorder(layer, target)

    /**
     * A pixel edit of [target] of [layer] makes the matching editable data stale (I1): unless
     * [keep], it is cleared now (a CONTENT edit clears text, shape and vector data; a MASK edit
     * clears the mask spec; an adjustment is never cleared) and the undo action restoring it is
     * returned (null when nothing was cleared). The message names what was lost.
     */
    private fun rasterizeDataAction(layer: Layer, target: EditTarget, keep: Boolean): UndoAction? {
        if (keep || layer === keepDataLayer) return null
        val before = layer.dataSnapshot()
        val after = if (target == EditTarget.MASK) before.rasterizedMask() else before.rasterizedContent()
        if (after == before) return null
        layer.restoreData(after)
        val label = rasterizeMessage(layer, before, after)
        return LayerDataAction(label, layer, before, after)
    }

    /** Shows what a data change from [before] to [after] made uneditable; returns a step label for it. */
    private fun rasterizeMessage(layer: Layer, before: LayerData, after: LayerData): String {
        val kind = when {
            before.text != null && after.text == null -> "text"
            before.shape != null && after.shape == null -> "shape"
            before.vector != null && after.vector == null -> "vector objects"
            else -> null
        }
        if (kind != null) {
            toast("\"${layer.name}\" is now a regular layer (its $kind can no longer be edited; undo to get it back)")
            return when (kind) { "text" -> "Rasterize text"; "shape" -> "Rasterize shape"; else -> "Rasterize vector layer" }
        }
        if (before.maskSpec != null && after.maskSpec == null) {
            toast("The mask of \"${layer.name}\" is now a painted mask (undo to get the editable mask back)")
            return "Painted mask"
        }
        return "Edit layer data"
    }

    /** While [keepLayerData] runs: the layer whose editable data pixel edits don't clear. */
    private var keepDataLayer: Layer? = null

    /**
     * Runs [block] (e.g. a brush replayed along a shape outline into its own shape layer) without
     * turning [layer] into a raster layer: pixel edits of [layer] committed inside keep its
     * text / shape data. Not reentrant for different layers (the innermost wins until it ends).
     */
    fun <T> keepLayerData(layer: Layer, block: () -> T): T {
        val prev = keepDataLayer
        keepDataLayer = layer
        try {
            return block()
        } finally {
            keepDataLayer = prev
        }
    }

    /**
     * Finishes a pixel edit: applies color-mode constraints to the touched area, pushes the undo
     * action, marks the layer changed and redraws. Returns false if nothing was touched. Editable
     * data the edit made stale is cleared in the same step (see [rasterizeDataAction]); edit
     * listeners hear about it (see [editScope]).
     */
    fun commitEdit(recorder: PixelEditRecorder, label: String, extraActions: List<UndoAction> = emptyList()): Boolean = editScope {
        if (recorder.isEmpty) return@editScope false
        val rect = Rect(recorder.touched)
        if (recorder.target == EditTarget.CONTENT && doc.colorMode != ColorMode.RGB) {
            // Only the snapshotted tiles can have changed (cheaper than the union bounding box).
            for (tile in recorder.touchedTileRects()) ColorModeOps.constrain(recorder.layer.bitmap, tile, doc.colorMode)
        }
        val action = recorder.finish(label) ?: return@editScope false
        // Painting on an editable layer turns it into a normal layer (undo restores the data).
        val rasterize = rasterizeDataAction(recorder.layer, recorder.target, recorder.preserveData)
        val all = listOf(action) + extraActions + listOfNotNull(rasterize)
        // Extra actions (e.g. a SelectionAction applied with recordUndo = false) join the same step.
        pushUndo(if (all.size == 1) action else CompositeAction(label, all))
        recorder.layer.markChanged()
        layersVersion++
        invalidateDoc(rect)
        queueEdit(EditEvent(recorder.layer, recorder.target, rect, label))
        true
    }

    /**
     * Convenience: snapshot the whole target of [layer], run [block] on its bitmap, commit.
     * Use for whole-layer operations (filters, fills).
     */
    fun editWholeLayer(layer: Layer, label: String, target: EditTarget = editTargetOf(layer), block: (Bitmap) -> Unit): Boolean {
        val rec = beginEdit(layer, target)
        rec.touchAll()
        val bmp = if (target == EditTarget.MASK) layer.mask!! else layer.bitmap
        block(bmp)
        return commitEdit(rec, label)
    }

    // ------------------------------------------------------------------ layers

    val activeLayer: Layer get() = doc.activeLayer

    fun selectLayer(index: Int) {
        val target = doc.layers.getOrNull(index) ?: return
        selectLayer(target)
    }

    fun selectLayer(layer: Layer) {
        if (layer === activeLayer) return
        // Deactivating may commit pending work that inserts a layer, so resolve the index after.
        currentTool.onDeactivate()
        val idx = doc.indexOf(layer)
        if (idx < 0) return
        doc.activeLayerIndex = idx
        layersVersion++
        currentTool.onActivate()
        invalidateOverlay()
    }

    private fun uniqueLayerName(base: String): String {
        val names = doc.layers.map { it.name }.toSet()
        if (base !in names) return base
        var n = 2
        while ("$base $n" in names) n++
        return "$base $n"
    }

    /**
     * Commits/pauses the current tool around a layer operation (onDeactivate before, onActivate
     * after), so pending work is baked in first and the tool re-targets the active layer after.
     */
    private inline fun <T> withToolPaused(block: () -> T): T {
        currentTool.onDeactivate()
        try {
            return block()
        } finally {
            currentTool.onActivate()
        }
    }

    /**
     * Adds an empty layer above the active one (or at [index], resolved AFTER pending tool work
     * is committed). Returns null if at the limit.
     */
    fun addLayer(name: String? = null, index: Int? = null, label: String = "Add layer"): Layer? = addLayerWith(name, index, label) {}

    /**
     * [addLayer] whose new layer is set up by [init] (editable data, mask...) before it is
     * inserted, so the step and the tool's re-activation see it complete.
     */
    private fun addLayerWith(name: String?, index: Int?, label: String, init: (Layer) -> Unit): Layer? {
        if (!canAddLayer) { toast("Layer limit reached (${maxLayers}) for this canvas size"); return null }
        val bmp = try { BitmapUtils.createLayerBitmap(doc.width, doc.height) } catch (e: OutOfMemoryError) { toast("Not enough memory for another layer"); return null }
        return withToolPaused {
            val layer = Layer(doc.newLayerId(), name ?: uniqueLayerName("Layer ${doc.layers.size + 1}"), bmp)
            init(layer)
            val at = (index ?: (doc.activeLayerIndex + 1)).coerceIn(0, doc.layers.size)
            structural {
                doc.layers.add(at, layer)
                doc.activeLayerIndex = at
            }
            pushUndo(AddLayerAction(layer, at, label))
            layer
        }
    }

    /**
     * Adds a new layer above the active one and lets [draw] paint into it (document coordinates).
     * [textData] / [shapeData] make it an editable text / shape layer.
     */
    fun addLayerWithContent(name: String, label: String, textData: String? = null, shapeData: String? = null, draw: (Canvas) -> Unit): Layer? {
        if (!canAddLayer) { toast("Layer limit reached (${maxLayers}) for this canvas size"); return null }
        val bmp = try {
            BitmapUtils.createLayerBitmap(doc.width, doc.height).also { b ->
                draw(Canvas(b))
                if (doc.colorMode != ColorMode.RGB) ColorModeOps.constrain(b, doc.bounds, doc.colorMode)
            }
        } catch (e: OutOfMemoryError) {
            toast("Not enough memory for another layer"); return null
        }
        return withToolPaused {
            val layer = Layer(doc.newLayerId(), uniqueLayerName(name), bmp).also { it.textData = textData; it.shapeData = shapeData }
            val at = (doc.activeLayerIndex + 1).coerceIn(0, doc.layers.size)
            structural {
                doc.layers.add(at, layer)
                doc.activeLayerIndex = at
            }
            pushUndo(AddLayerAction(layer, at, label))
            layer
        }
    }

    /**
     * Re-renders the editable text layer [layer] with new text: clears [dirty] (document px; it
     * must cover the old AND the new text, null = the whole layer), lets [draw] paint the new text
     * and stores [textData] — one undo step named [label] that restores both pixels and text.
     * Returns false if the layer is gone or can't be edited. (A wrapper of [updateLayerData].)
     */
    fun updateTextLayer(layer: Layer, textData: String, label: String, dirty: Rect? = null, draw: (Canvas) -> Unit): Boolean =
        updateLayerData(layer, layer.dataSnapshot().copy(text = textData), label, dirty, EditTarget.CONTENT, draw, "Not enough memory to update the text")

    /**
     * Re-renders the editable shape layer [layer]: clears [dirty] (document px; it must cover the
     * old AND the new shape, null = the whole layer), lets [draw] paint the new shape and stores
     * [shapeData] — one undo step named [label] that restores both pixels and shape data. Returns
     * false if the layer is gone or can't be edited. To add a brush stroke to the same layer in the
     * same step, run both inside [undoStepNamed]-style grouping and [keepLayerData]. (A wrapper
     * of [updateLayerData].)
     */
    fun updateShapeLayer(layer: Layer, shapeData: String, label: String, dirty: Rect? = null, draw: (Canvas) -> Unit): Boolean =
        updateLayerData(layer, layer.dataSnapshot().copy(shape = shapeData), label, dirty, EditTarget.CONTENT, draw, "Not enough memory to update the shape")

    /**
     * Changes [layer]'s editable data to [after] and its pixels to match, as ONE undo step named
     * [label] (I1): the [target] bitmap is cleared within [dirty] (document px; it must cover the
     * old AND the new rendering, null = the whole layer) and [draw] paints the new rendering
     * there; the step restores both pixels (tiles of [dirty]) and data. With a null [draw] only
     * the data changes (the pixels must already match). Returns false if the layer is gone,
     * locked or hidden (adjustment layers are accepted: their data can always change).
     */
    internal fun updateLayerData(
        layer: Layer,
        after: LayerData,
        label: String,
        dirty: Rect?,
        target: EditTarget = EditTarget.CONTENT,
        draw: ((Canvas) -> Unit)?,
    ): Boolean = updateLayerData(layer, after, label, dirty, target, draw, "Not enough memory for \"$label\"")

    private fun updateLayerData(
        layer: Layer,
        after: LayerData,
        label: String,
        dirty: Rect?,
        target: EditTarget,
        draw: ((Canvas) -> Unit)?,
        oomMessage: String,
    ): Boolean = editScope {
        if (doc.indexOf(layer) < 0 || !checkUsable(layer)) return@editScope false
        val before = layer.dataSnapshot()
        if (draw == null) {
            storeData(layer, before, after, label, target)
            return@editScope true
        }
        val bmp = (if (target == EditTarget.MASK) layer.mask else layer.bitmap) ?: return@editScope false
        val area = Rect(dirty ?: doc.bounds)
        if (!area.intersect(0, 0, doc.width, doc.height)) area.set(0, 0, 0, 0)
        val rec = beginEdit(layer, target).also { it.preserveData = true }
        try {
            if (!area.isEmpty) rec.touch(area)
            val c = Canvas(bmp)
            // Only the snapshotted area may change, or undo couldn't restore it.
            c.save()
            c.clipRect(area)
            c.drawColor(0, PorterDuff.Mode.CLEAR)
            draw(c)
            c.restore()
        } catch (e: OutOfMemoryError) {
            rec.abort()
            toast(oomMessage)
            return@editScope false
        }
        layer.restoreData(after)
        val dataAction = LayerDataAction(label, layer, before, after)
        if (!commitEdit(rec, label, listOf(dataAction))) {
            // Nothing was touched (empty area): still record the data change.
            if (before != after) {
                layer.markChanged()
                pushUndo(dataAction)
                queueEdit(EditEvent(layer, target, null, label))
            }
            notifyLayersChanged()
        }
        true
    }

    /** Sets [after] as [layer]'s data with a data-only undo step (nothing when unchanged). */
    private fun storeData(layer: Layer, before: LayerData, after: LayerData, label: String, target: EditTarget) {
        if (before == after) return
        layer.restoreData(after)
        layer.markChanged()
        pushUndo(LayerDataAction(label, layer, before, after))
        notifyLayersChanged()
        queueEdit(EditEvent(layer, target, null, label))
    }

    /**
     * Data-only step (v1.5): sets [layer]'s editable data to [after] (e.g. an adjustment's effect,
     * a vector object appended whose pixels were already committed). The layer must be present,
     * unlocked and visible. One undo step named [label]; nothing when unchanged.
     */
    fun setLayerData(layer: Layer, after: LayerData, label: String) = editScope {
        if (doc.indexOf(layer) < 0 || !checkUsable(layer)) return@editScope
        val before = layer.dataSnapshot()
        val target = if (before.maskSpec != after.maskSpec && before.rasterizedMask() == after.rasterizedMask()) EditTarget.MASK else EditTarget.CONTENT
        storeData(layer, before, after, label, target)
    }

    fun deleteLayer(layer: Layer = activeLayer) {
        if (doc.layers.size <= 1) { toast("A drawing needs at least one layer"); return }
        if (doc.indexOf(layer) < 0) return
        // Layers are deleted without a confirmation, so pending tool work (a shape, text or
        // transform) is committed first rather than silently thrown away; undo restores both.
        withToolPaused {
            // Resolve the index after committing: committing text can insert a layer.
            val idx = doc.indexOf(layer)
            if (idx < 0 || doc.layers.size <= 1) return@withToolPaused
            structural {
                doc.layers.removeAt(idx)
                doc.activeLayerIndex = min((idx - 1).coerceAtLeast(0), doc.layers.lastIndex)
            }
            pushUndo(RemoveLayerAction(layer, idx))
        }
    }

    /**
     * Duplicates [layer] above itself. With an active selection only the selected pixels are
     * copied (like "copy selection to new layer"); the layer mask, if any, is copied whole.
     */
    fun duplicateLayer(layer: Layer = activeLayer): Layer? {
        if (!canAddLayer) { toast("Layer limit reached (${maxLayers}) for this canvas size"); return null }
        return withToolPaused {
            // Copy after committing pending work so the duplicate includes it.
            val sel = selection
            // A vector layer with a selection: the objects it touches, as a vector layer.
            if (sel != null && layer.isVectorLayer) {
                VectorLayerOps.duplicateTouched(this, layer, sel)?.let { return@withToolPaused it }
            }
            val copy = try {
                val pixels = BitmapUtils.copy(layer.bitmap)
                if (sel != null) BitmapUtils.maskWith(Canvas(pixels), sel.mask)
                Layer(doc.newLayerId(), uniqueLayerName("${layer.name} copy"), pixels).also {
                    it.mask = layer.mask?.let { m -> BitmapUtils.copy(m) }
                    // A partial copy is no longer the text / shape / vector object: only whole
                    // copies keep the content data (the mask is copied whole, so its spec stays).
                    val data = layer.dataSnapshot()
                    it.restoreData(if (sel == null) data else data.rasterizedContent())
                }
            } catch (e: OutOfMemoryError) {
                toast("Not enough memory to duplicate this layer"); return@withToolPaused null
            }
            copy.copyPropsFrom(layer.props().copy(name = copy.name))
            val at = doc.indexOf(layer) + 1
            if (at <= 0) return@withToolPaused null
            structural {
                doc.layers.add(at, copy)
                doc.activeLayerIndex = at
            }
            pushUndo(AddLayerAction(copy, at, if (sel != null) "Duplicate selection" else "Duplicate layer"))
            copy
        }
    }

    // ------------------------------------------------------------------ clipboard

    /** Pixels copied with [copySelection]; [left]/[top] = where they came from (document px). */
    class ClipboardImage(val bitmap: Bitmap, val left: Int, val top: Int)

    /** This editor's internal clipboard (null = empty). */
    var clipboard by mutableStateOf<ClipboardImage?>(null)
        private set

    /**
     * Copies the selected pixels of the active layer (without a selection: the layer's painted
     * area). Returns false (with a message) if there is nothing to copy.
     */
    fun copySelection(): Boolean {
        val layer = activeLayer
        val sel = selection
        val rect = if (sel != null) Rect(sel.bounds) else contentBounds(layer.bitmap)
        if (rect == null || rect.isEmpty || !rect.intersect(0, 0, doc.width, doc.height)) {
            toast("Nothing to copy on \"${layer.name}\""); return false
        }
        val out = try {
            BitmapUtils.createLayerBitmap(rect.width(), rect.height()).also { b ->
                val c = Canvas(b)
                c.drawBitmap(layer.bitmap, -rect.left.toFloat(), -rect.top.toFloat(), null)
                if (sel != null) BitmapUtils.maskWith(c, sel.mask, -rect.left.toFloat(), -rect.top.toFloat())
            }
        } catch (e: OutOfMemoryError) {
            toast("Not enough memory to copy this"); return false
        }
        clipboard = ClipboardImage(out, rect.left, rect.top)
        toast(if (sel != null) "Selection copied" else "Layer copied")
        return true
    }

    /** Copies (see [copySelection]) and then clears the copied area, as one "Cut" undo step. */
    fun cutSelection(): Boolean {
        if (!checkEditable()) return false
        if (!copySelection()) return false
        clearLayer(activeLayer, label = "Cut")
        toast(if (selection != null) "Selection cut" else "Layer cut")
        return true
    }

    /**
     * Pastes the clipboard into a new layer above the active one, at the place it was copied
     * from, and starts the transform tool on it so it can be moved/scaled before confirming.
     */
    fun paste(): Layer? {
        val clip = clipboard ?: run { toast("Nothing to paste yet"); return null }
        val image = try { BitmapUtils.copy(clip.bitmap) } catch (e: OutOfMemoryError) { toast("Not enough memory to paste"); return null }
        val layer = addLayer(uniqueLayerName("Pasted"), label = PASTE_LABEL) ?: return null
        selectTool(ToolId.TRANSFORM)
        (tools[ToolId.TRANSFORM] as? TransformTool)?.startPlacement(layer, image, clip.left.toFloat(), clip.top.toFloat(), PASTE_LABEL)
        return layer
    }

    /** Tight bounds of the non-transparent pixels of [bmp], or null if it is empty. */
    private fun contentBounds(bmp: Bitmap): Rect? {
        val w = bmp.width; val h = bmp.height
        val row = IntArray(w)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            var any = false
            for (x in 0 until w) if (row[x] ushr 24 != 0) { any = true; if (x < minX) minX = x; if (x > maxX) maxX = x }
            if (any) { if (y < minY) minY = y; maxY = y }
        }
        return if (maxX < 0) null else Rect(minX, minY, maxX + 1, maxY + 1)
    }

    /** Moves [layer] to [toIndex] (0 = bottom). */
    fun moveLayer(layer: Layer, toIndex: Int) {
        val from = doc.indexOf(layer)
        val to = toIndex.coerceIn(0, doc.layers.lastIndex)
        if (from < 0 || from == to) return
        withToolPaused {
            val f = doc.indexOf(layer)
            if (f < 0 || f == to) return@withToolPaused
            structural {
                doc.layers.removeAt(f)
                doc.layers.add(to, layer)
                doc.activeLayerIndex = to
            }
            pushUndo(MoveLayerAction(layer, f, to))
        }
    }

    fun moveLayerUp(layer: Layer = activeLayer) = moveLayer(layer, doc.indexOf(layer) + 1)
    fun moveLayerDown(layer: Layer = activeLayer) = moveLayer(layer, doc.indexOf(layer) - 1)

    /** Merges [layer] into the layer below it. */
    fun mergeDown(layer: Layer = activeLayer) {
        val idx = doc.indexOf(layer)
        if (idx <= 0) { toast("There is no layer below to merge into"); return }
        // An adjustment layer has no pixels to receive the merge (its effect on the layers below
        // would be lost); merging one DOWN applies its effect.
        if (doc.layers[idx - 1].isAdjustmentLayer) { toast("Layers can't be merged into an adjustment layer"); return }
        withToolPaused { mergeDownNow(layer) }
    }

    private fun mergeDownNow(layer: Layer) {
        val idx = doc.indexOf(layer)
        if (idx <= 0) return
        val lower = doc.layers[idx - 1]
        if (lower.isAdjustmentLayer) return
        editScope { mergeDownInto(layer, idx, lower) }
    }

    private fun mergeDownInto(layer: Layer, idx: Int, lower: Layer) {
        // Two vector layers can keep their objects (A1); otherwise the result is pixels.
        if (layer.isVectorLayer && lower.isVectorLayer && VectorLayerOps.mergeVector(this, layer, lower)) return
        // Flatten lower (+ its mask, opacity) and upper (with blend, opacity, mask, clipping) via a
        // temporary two-layer document so the result matches what's on screen. When both clip to
        // the same base further down, the upper one is simply drawn over the lower one here (the
        // merged layer keeps the lower layer's clipping). The views carry the adjustment and mask
        // specs: merging an adjustment layer down applies its effect to the layer below.
        val tmpDoc = Document("merge", "merge", doc.width, doc.height)
        val lowerView = Layer(-1, lower.name, lower.bitmap).also {
            it.copyPropsFrom(lower.props().copy(blendMode = LayerBlendMode.NORMAL, visible = true, clipping = false))
            it.mask = lower.mask
            it.maskSpec = lower.maskSpec
            it.adjustment = lower.adjustment
        }
        val upperView = Layer(-2, layer.name, layer.bitmap).also {
            it.copyPropsFrom(layer.props().copy(visible = true, clipping = layer.clipping && !lower.clipping))
            it.mask = layer.mask
            it.maskSpec = layer.maskSpec
            it.adjustment = layer.adjustment
        }
        tmpDoc.layers += lowerView
        tmpDoc.layers += upperView
        val merged = Compositor(tmpDoc) { null }.renderFlattened()
        val beforeBmp = lower.bitmap; val beforeMask = lower.mask; val beforeProps = lower.props()
        val dataBefore = lower.dataSnapshot()
        // The merged pixels are plain pixels: no editable data, no mask.
        val dataAfter = LayerData.NONE
        val afterProps = beforeProps.copy(opacity = 1f, maskEnabled = true)
        val replace = LambdaAction("Merge down", byteSize = beforeBmp.byteCount.toLong() + (beforeMask?.byteCount ?: 0),
            onUndo = { c -> c.structural { lower.bitmap = beforeBmp; lower.mask = beforeMask; lower.restoreData(dataBefore); lower.copyPropsFrom(beforeProps); lower.markChanged() } },
            onRedo = { c -> c.structural { lower.bitmap = merged; lower.mask = null; lower.restoreData(dataAfter); lower.editingMask = false; lower.copyPropsFrom(afterProps); lower.markChanged() } },
        )
        val remove = RemoveLayerAction(layer, idx, "Merge down")
        replace.redo(this)
        remove.redo(this)
        structural { doc.activeLayerIndex = doc.indexOf(lower) }
        pushUndo(CompositeAction("Merge down", listOf(replace, remove)))
        // A committed edit of the lower layer (text wrapped around it re-flows).
        queueEdit(EditEvent(lower, EditTarget.CONTENT, null, "Merge down"))
    }

    /** Mirrors a layer (pixels and mask). Self-inverse, so undo just flips again. */
    fun flipLayer(layer: Layer = activeLayer, horizontal: Boolean) {
        if (!checkEditable(layer)) return
        withToolPaused {
            // A vector edit still rendering lands first: the flip mirrors its result.
            vectors.flushPending()
            val content = layer.vector
            if (content != null && !LayerDataTransforms.turnsExactly(content, 0, mirror = true)) flipVectorLayerNow(layer, horizontal, content)
            else flipLayerNow(layer, horizontal)
        }
    }

    /**
     * Flip layer for a vector layer whose brushes don't mirror into themselves (paper grain,
     * scatter, textured or angled tips; v1.5): its mirrored objects are rendered again instead of
     * flipping its pixels, as canvas flips do, or a later partial re-render would show seams in
     * the texture. One step, rendered in the background when it is long (`vectors.update`); a mask
     * flips with it, in the same step, when the new pixels land.
     */
    private fun flipVectorLayerNow(layer: Layer, horizontal: Boolean, content: VectorContent) {
        val label = flipLabel(horizontal)
        val mirrored = VectorLayerOps.flipped(content, doc.width, doc.height, horizontal) ?: return flipLayerNow(layer, horizontal)
        val mask = if (layer.mask != null) flipMaskAction(layer, horizontal, label) else null
        if (mirrored == content) {
            // The drawing mirrors into itself: its pixels already are its rendering.
            if (mask != null) editScope {
                mask.redo(this)
                pushUndo(mask)
                queueEdit(EditEvent(layer, EditTarget.MASK, null, label))
            }
            return
        }
        if (mask == null) {
            vectors.update(layer, mirrored, label)
            return
        }
        // The mask bitmap flips right before the new pixels are drawn (the step's edit event sees
        // both flipped); its spec and its undo action join the step once it is recorded.
        vectors.updateInternal(
            layer, mirrored, label, null, null,
            beforeApply = { mask.flipBitmap(this) },
            onDone = { applied ->
                if (!applied) {
                    mask.flipBitmap(this)
                } else {
                    mask.setSpec(this, after = true)
                    if (undoManager.undoLabel == label) amendLastStep { pushUndo(mask) } else pushUndo(mask)
                }
            },
            attempt = 0,
        )
    }

    private fun flipLabel(horizontal: Boolean) = if (horizontal) "Flip layer horizontally" else "Flip layer vertically"

    /** [layer]'s mask bitmap and spec mirrored (an undo action: undo flips back, redo flips again). */
    private fun flipMaskAction(layer: Layer, horizontal: Boolean, label: String): FlipMaskAction {
        val m = Matrix().apply { if (horizontal) setScale(-1f, 1f, doc.width / 2f, 0f) else setScale(1f, -1f, 0f, doc.height / 2f) }
        val specBefore = layer.maskSpec
        val specAfter = specBefore?.let { MaskSpecs.transformed(it, m) }
        if (specBefore != null && specAfter == null) toast("The mask of \"${layer.name}\" is now a painted mask (undo to get the editable mask back)")
        return FlipMaskAction(label, layer, horizontal, specBefore, specAfter)
    }

    /** Mirrors a layer's mask (self-inverse bitmap flip) and sets its spec before / after. */
    private class FlipMaskAction(
        override val label: String,
        private val layer: Layer,
        private val horizontal: Boolean,
        private val specBefore: MaskSpec?,
        private val specAfter: MaskSpec?,
    ) : UndoAction {
        override val byteSize: Long get() = 256L

        fun flipBitmap(c: EditorController) = c.structural {
            layer.mask = layer.mask?.let { BitmapUtils.flipped(it, horizontal) }
            layer.markChanged()
        }

        fun setSpec(c: EditorController, after: Boolean) {
            layer.maskSpec = if (after) specAfter else specBefore
            layer.markChanged()
            c.notifyLayersChanged()
        }

        override fun undo(c: EditorController) { flipBitmap(c); setSpec(c, after = false) }
        override fun redo(c: EditorController) { flipBitmap(c); setSpec(c, after = true) }
    }

    private fun flipLayerNow(layer: Layer, horizontal: Boolean) = editScope {
        val flip: (EditorController) -> Unit = { c ->
            c.structural {
                val old = layer.bitmap
                layer.bitmap = BitmapUtils.flipped(old, horizontal)
                layer.mask = layer.mask?.let { BitmapUtils.flipped(it, horizontal) }
                layer.markChanged()
            }
        }
        flip(this)
        val label = flipLabel(horizontal)
        val flipAction = LambdaAction(label, onUndo = flip, onRedo = flip)
        // Vector content and mask specs are mirrored with the pixels when they can be (A1 / A5);
        // what can't be is cleared like any pixel edit (text and shapes are, as in v1.4).
        val before = layer.dataSnapshot()
        val m = Matrix().apply { if (horizontal) setScale(-1f, 1f, doc.width / 2f, 0f) else setScale(1f, -1f, 0f, doc.height / 2f) }
        val after = before.copy(
            text = null,
            shape = null,
            vector = before.vector?.let { VectorLayerOps.flipped(it, doc.width, doc.height, horizontal) },
            maskSpec = before.maskSpec?.let { MaskSpecs.transformed(it, m) },
        )
        val dataAction = if (after != before) {
            layer.restoreData(after)
            LayerDataAction(rasterizeMessage(layer, before, after), layer, before, after)
        } else null
        pushUndo(if (dataAction == null) flipAction else CompositeAction(label, listOf(flipAction, dataAction)))
        // A committed edit of the layer (text wrapped around it re-flows).
        queueEdit(EditEvent(layer, EditTarget.CONTENT, null, label))
    }

    /** Applies property changes with undo. Use [previewLayerProps] for live slider dragging. */
    fun setLayerProps(layer: Layer, props: LayerProps, label: String = "Layer properties") {
        val before = layer.props()
        if (before == props) return
        structural { layer.copyPropsFrom(props) }
        pushUndo(LayerPropsAction(layer, before, props, label))
    }

    /** Applies props WITHOUT undo (live preview). Finish with [setLayerProps] from the original. */
    fun previewLayerProps(layer: Layer, props: LayerProps) {
        structural { layer.copyPropsFrom(props) }
    }

    /** Commits a live preview: records undo from [before] to the layer's current props. */
    fun commitLayerProps(layer: Layer, before: LayerProps, label: String = "Layer properties") {
        val after = layer.props()
        if (before == after) return
        pushUndo(LayerPropsAction(layer, before, after, label))
        layersVersion++
    }

    fun toggleVisibility(layer: Layer) = setLayerProps(layer, layer.props().copy(visible = !layer.visible), "Visibility")
    /** Clipping on / off; refused on adjustment layers and on a layer right above one (they are never clipping bases). */
    fun toggleClipping(layer: Layer) {
        if (!layer.clipping) {
            if (layer.isAdjustmentLayer) { toast("Adjustment layers can't be clipped"); return }
            val idx = doc.indexOf(layer)
            if (idx > 0 && doc.layers[idx - 1].isAdjustmentLayer) { toast("Layers can't be clipped to an adjustment layer"); return }
        }
        setLayerProps(layer, layer.props().copy(clipping = !layer.clipping), "Clipping")
    }
    fun toggleAlphaLock(layer: Layer) = setLayerProps(layer, layer.props().copy(alphaLocked = !layer.alphaLocked), "Lock alpha")
    fun toggleLock(layer: Layer) = setLayerProps(layer, layer.props().copy(locked = !layer.locked), "Lock layer")
    fun renameLayer(layer: Layer, name: String) = setLayerProps(layer, layer.props().copy(name = name.ifBlank { layer.name }), "Rename layer")
    fun setBlendMode(layer: Layer, mode: LayerBlendMode) = setLayerProps(layer, layer.props().copy(blendMode = mode), "Blend mode")

    /**
     * Clears the layer (or only the selected area). Follows the edit target: when the layer's mask
     * is being edited, the mask is cleared to black (hidden). Refused on alpha-locked content.
     */
    fun clearLayer(layer: Layer = activeLayer, label: String = "Clear") {
        if (!checkEditable(layer)) return
        withToolPaused {
            val target = editTargetOf(layer)
            // A vector layer removes the objects the selection touches (A1); else pixels as today.
            if (target == EditTarget.CONTENT && layer.isVectorLayer && VectorLayerOps.clear(this, layer, selection, label)) return@withToolPaused
            if (target == EditTarget.CONTENT && layer.alphaLocked) {
                toast("Transparency is locked on \"${layer.name}\""); return@withToolPaused
            }
            val sel = selection
            val rec = beginEdit(layer, target)
            rec.touch(sel?.bounds ?: doc.bounds)
            val bmp = if (target == EditTarget.MASK) layer.mask!! else layer.bitmap
            val c = Canvas(bmp)
            if (target == EditTarget.MASK) {
                if (sel == null) bmp.eraseColor(0xFF000000.toInt())
                else c.drawBitmap(sel.mask, 0f, 0f, Paint().apply { color = 0xFF000000.toInt() })
            } else {
                if (sel == null) bmp.eraseColor(0)
                else c.drawBitmap(sel.mask, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
            }
            commitEdit(rec, label)
        }
    }

    /**
     * Fills the layer (or the selection) with [fill]. When the mask is being edited the mask is
     * filled with the color's luminance. Alpha lock keeps transparent pixels transparent.
     */
    fun fillLayer(layer: Layer = activeLayer, fill: Int = color) {
        if (!checkEditable(layer)) return
        withToolPaused {
            val target = editTargetOf(layer)
            // A vector layer gets a filled object (A1); else pixels as today.
            if (target == EditTarget.CONTENT && layer.isVectorLayer && VectorLayerOps.fill(this, layer, selection, fill)) return@withToolPaused
            val sel = selection
            val rec = beginEdit(layer, target)
            rec.touch(sel?.bounds ?: doc.bounds)
            val bmp = if (target == EditTarget.MASK) layer.mask!! else layer.bitmap
            val c = Canvas(bmp)
            val paintColor = if (target == EditTarget.MASK) com.brushwork.paint.core.ColorUtils.gray(com.brushwork.paint.core.ColorUtils.luminance(fill)) else fill
            val atop = target == EditTarget.CONTENT && layer.alphaLocked
            if (sel == null) {
                if (atop) c.drawColor(paintColor, PorterDuff.Mode.SRC_ATOP) else c.drawColor(paintColor, PorterDuff.Mode.SRC_OVER)
            } else {
                val p = Paint().apply { color = paintColor; if (atop) xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) }
                c.drawBitmap(sel.mask, 0f, 0f, p)
            }
            commitEdit(rec, "Fill")
        }
    }

    // ------------------------------------------------------------------ masks

    fun addMask(layer: Layer = activeLayer, fromSelection: Boolean = selection != null) {
        if (layer.mask != null) return
        val mask = BitmapUtils.createMaskBitmap(doc.width, doc.height, if (fromSelection) 0xFF000000.toInt() else -1)
        val sel = selection
        if (fromSelection && sel != null) Canvas(mask).drawBitmap(sel.mask, 0f, 0f, Paint().apply { color = -1 })
        val action = MaskChangeAction(layer, null, mask, "Add mask")
        action.redo(this)
        layer.editingMask = true
        pushUndo(action)
    }

    fun deleteMask(layer: Layer = activeLayer) {
        val m = layer.mask ?: return
        editScope {
            // An editable mask's spec goes with its mask (I1), in the same step.
            val dataBefore = layer.dataSnapshot()
            val action = MaskChangeAction(layer, m, null, "Delete mask")
            action.redo(this)
            val dataAfter = dataBefore.rasterizedMask()
            val dataAction = if (dataAfter != dataBefore) {
                layer.restoreData(dataAfter)
                LayerDataAction("Delete mask", layer, dataBefore, dataAfter)
            } else null
            pushUndo(if (dataAction == null) action else CompositeAction("Delete mask", listOf(action, dataAction)))
            queueEdit(EditEvent(layer, EditTarget.MASK, null, "Delete mask"))
        }
    }

    /** Bakes the mask into the layer's pixels and removes it. */
    fun applyMask(layer: Layer = activeLayer) {
        val m = layer.mask ?: return
        editScope {
            val rec = beginEdit(layer, EditTarget.CONTENT)
            rec.touchAll()
            Canvas(layer.bitmap).drawBitmap(m, 0f, 0f, BitmapUtils.newMaskApplyPaint())
            val pix = rec.finish("Apply mask")
            val maskAction = MaskChangeAction(layer, m, null, "Apply mask")
            maskAction.redo(this)
            // New pixels and no mask: the content data and the mask spec no longer match (I1).
            val dataBefore = layer.dataSnapshot()
            val dataAfter = dataBefore.rasterizedContent().rasterizedMask()
            val dataAction = if (dataAfter != dataBefore) {
                layer.restoreData(dataAfter)
                LayerDataAction(rasterizeMessage(layer, dataBefore, dataAfter), layer, dataBefore, dataAfter)
            } else null
            layer.markChanged()
            pushUndo(CompositeAction("Apply mask", listOfNotNull(pix, maskAction, dataAction)))
            queueEdit(EditEvent(layer, EditTarget.CONTENT, null, "Apply mask"))
        }
    }

    fun invertMask(layer: Layer = activeLayer) {
        val m = layer.mask ?: return
        editWholeLayer(layer, "Invert mask", EditTarget.MASK) { bmp ->
            val src = BitmapUtils.copy(bmp)
            val invert = android.graphics.ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
            val p = Paint().apply {
                colorFilter = android.graphics.ColorMatrixColorFilter(invert)
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
            }
            Canvas(bmp).drawBitmap(src, 0f, 0f, p)
            src.recycle()
        }
    }

    fun setMaskEnabled(layer: Layer, enabled: Boolean) = setLayerProps(layer, layer.props().copy(maskEnabled = enabled), if (enabled) "Enable mask" else "Disable mask")

    /** Switches painting between the layer's pixels and its mask (not an undoable change). */
    fun setEditingMask(layer: Layer, editing: Boolean) {
        if (editing && layer.mask == null) return
        if (layer.editingMask == editing) return
        withToolPaused {
            layer.editingMask = editing
            layersVersion++
        }
    }

    // ------------------------------------------------------------------ import

    /** Adds [image] as a new layer and switches to the transform tool to place it. */
    fun importImageAsLayer(image: Bitmap, name: String = "Imported picture") {
        val layer = addLayer(uniqueLayerName(name), label = "Import picture") ?: return
        selectTool(ToolId.TRANSFORM)
        (tools[ToolId.TRANSFORM] as? TransformTool)?.startPlacement(layer, image)
    }

    // ------------------------------------------------------------------ selection

    fun setSelection(sel: Selection?, recordUndo: Boolean = true, label: String = "Selection") {
        val new = sel?.takeUnless { it.isEmpty }
        val before = selection
        if (before == null && new == null) return
        selection = new
        if (recordUndo) pushUndo(SelectionAction(before, new, label))
        if (new != null && new.outline == null) SelectionOutline.computeAsync(this, new)
        currentTool.onSelectionChanged()
        invalidateOverlay()
    }

    fun selectAll() = setSelection(Selection.all(doc.width, doc.height), label = "Select all")
    fun deselect() = setSelection(null, label = "Deselect")
    fun invertSelection() {
        val s = selection
        setSelection(s?.inverted() ?: Selection.all(doc.width, doc.height), label = "Invert selection")
    }

    // ------------------------------------------------------------------ filters

    fun startFilter(filter: Filter) {
        // An adjustment layer's effect is edited with the Masks tool (vector layers are
        // allowed: applying the filter turns them into raster layers, undoably). Refused before
        // anything else happens, so the tool stays as it was.
        if (activeLayer.isAdjustmentLayer) { toast(ADJUSTMENT_FILTER_MESSAGE); return }
        filterSession?.cancel()
        currentTool.onDeactivate()
        // A vector edit still rendering lands first (v1.5): the filter previews and applies to
        // its result, and the render can't land inside the filter's own step later.
        vectors.flushPending()
        if (!checkEditable()) return
        // A session that closes itself during start() (target not usable) must not stay installed.
        filterSession = FilterSession(this, filter).also { it.start() }.takeUnless { it.isClosed }
    }

    // ------------------------------------------------------------------ busy work

    /**
     * Non-null while the running busy operation can be stopped; the busy overlay shows a Stop
     * button that calls it.
     */
    var busyCancel by mutableStateOf<(() -> Unit)?>(null)
        private set

    /**
     * Runs [block] with a progress overlay; exceptions are shown as a message. [onCancel] (if
     * given) is offered to the user as a Stop button while the block runs.
     */
    fun runBusy(label: String, onCancel: (() -> Unit)? = null, block: suspend () -> Unit) {
        scope.launch {
            busyMessage = label
            busyProgress = -1f
            busyCancel = onCancel
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                toast("Not enough memory for \"$label\"")
            } catch (e: Exception) {
                toast("$label failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                busyMessage = null
                busyProgress = -1f
                busyCancel = null
            }
        }
    }

    fun dispose() {
        runCatching { currentTool.onDeactivate() }
        filterSession?.cancel()
        if (toolsLazy.isInitialized()) tools.values.forEach { runCatching { it.onDispose() } }
        vectors.dispose()
        snapping.clear()
        tiles.release()
        undoManager.clear()
    }

    // ------------------------------------------------------------------ vector mode (v1.5, §4.9)

    /**
     * The vector layer service: object edits, rendering, object selection and the seams the
     * tools use on vector layers (owned by A1).
     */
    val vectors: VectorLayers = VectorLayers(this)

    /**
     * Vector mode is exactly "the active layer is a vector layer" (derived, never stored): what
     * the user draws stays editable. Reads [layersVersion], so Compose follows layer changes.
     */
    val isVectorMode: Boolean
        get() {
            layersVersion
            return activeLayer.isVectorLayer
        }

    /** The raster layer the Vector button came from (it goes back there when tapped again). */
    private var vectorReturnLayer: Layer? = null

    /** The vector mode hint was shown in this editor session. */
    private var vectorHintShown = false

    /**
     * The Vector button. Off: an empty plain layer is converted in place; else the visible,
     * unlocked vector layer right above is selected; else a new "Vector N" layer is added above.
     * On: back to the layer it came from (else the nearest raster layer below, then above, else a
     * new layer). Not while a filter is previewed.
     */
    fun toggleVectorMode() {
        if (filterSession != null) return
        // Pending tool work (a shape being placed...) is committed first: it may paint the active
        // layer or add one, so what happens next is decided on the result.
        commitToolWork()
        val layer = activeLayer
        if (layer.isVectorLayer) {
            val back = vectorReturnLayer?.takeIf { doc.indexOf(it) >= 0 && isRasterLayer(it) } ?: nearestRasterLayer(layer)
            vectorReturnLayer = null
            if (back != null) selectLayer(back) else addLayer()
            return
        }
        val idx = doc.indexOf(layer)
        val above = doc.layers.getOrNull(idx + 1)
        val ok = when {
            // (A hidden layer can't be edited: a new vector layer goes above it instead.)
            layer.visible && isEmptyPlainLayer(layer) -> convertToVectorLayer(layer)
            above != null && above.isVectorLayer && above.visible && !above.locked -> { selectLayer(above); true }
            else -> addVectorLayer() != null
        }
        if (!ok) return
        vectorReturnLayer = layer
        if (!vectorHintShown) {
            vectorHintShown = true
            toast("Vector mode: what you draw stays editable. Tap Vector again to go back.")
        }
    }

    /** Commits the current tool's pending work (a shape or text being placed, a transform...). */
    private fun commitToolWork() {
        val tool = currentTool
        if (tool.hasPendingWork) {
            tool.commit()
            invalidateOverlay()
        }
    }

    /** A layer the Vector button can go back to (not vector, not an adjustment layer). */
    private fun isRasterLayer(l: Layer): Boolean = !l.isVectorLayer && !l.isAdjustmentLayer

    /** The nearest raster layer below [from], else above, or null. */
    private fun nearestRasterLayer(from: Layer): Layer? {
        val idx = doc.indexOf(from)
        for (i in idx - 1 downTo 0) if (isRasterLayer(doc.layers[i])) return doc.layers[i]
        for (i in idx + 1 until doc.layers.size) if (isRasterLayer(doc.layers[i])) return doc.layers[i]
        return null
    }

    /**
     * True for an empty plain raster layer: no painted pixel, no mask, no editable data of any
     * kind, not locked (it can become a vector layer in place).
     */
    fun isEmptyPlainLayer(layer: Layer): Boolean =
        !layer.locked && layer.mask == null && layer.dataSnapshot().isEmpty && contentBounds(layer.bitmap) == null

    /**
     * Adds an empty vector layer above the active one ("Vector N" unless [name]) as one undo step
     * [label]. Returns null at the layer limit.
     */
    fun addVectorLayer(name: String? = null, label: String = "Add vector layer"): Layer? =
        addLayerWith(name ?: uniqueLayerName("Vector ${doc.layers.count { it.isVectorLayer } + 1}"), null, label) { it.vector = VectorContent.EMPTY }

    /**
     * Turns [layer] into a vector layer in place as one undo step [label]: an empty plain layer
     * (renamed "Vector N" when it has the default "Layer N" name), or a shape layer (its shape
     * becomes one VShape object; the pixels stay). False (with a message) for anything else.
     */
    fun convertToVectorLayer(layer: Layer, label: String = "Convert to vector layer"): Boolean {
        // Pending tool work may still paint the layer: it is decided on the committed result.
        commitToolWork()
        if (doc.indexOf(layer) < 0 || layer.isVectorLayer) return false
        if (!checkUsable(layer)) return false
        val before = layer.dataSnapshot()
        val content: VectorContent = when {
            layer.isShapeLayer -> {
                val shape = ShapeCodec.decode(layer.shapeData) ?: run { toast("This shape can't be converted"); return false }
                VectorContent.EMPTY.plus(listOf(VShape(id = 0, shape = shape))).first
            }
            isEmptyPlainLayer(layer) -> VectorContent.EMPTY
            else -> { toast("Only empty layers and shape layers can become vector layers"); return false }
        }
        val after = before.copy(text = null, shape = null, vector = content)
        val newName = Regex("Layer (\\d+)").matchEntire(layer.name)?.let { uniqueLayerName("Vector ${it.groupValues[1]}") }
        groupUndo(label) {
            if (newName != null) setLayerProps(layer, layer.props().copy(name = newName), label)
            setLayerData(layer, after, label)
        }
        return layer.isVectorLayer
    }

    // ------------------------------------------------------------------ adjustment layers & editable masks (v1.5, §4.3)

    /** An adjustment layer needs two layer slots (its bitmap and its mask). */
    val canAddAdjustmentLayer: Boolean get() = doc.layers.size + 2 <= maxLayers

    /**
     * Adds an adjustment layer with effect [spec] above the active layer (named after the effect:
     * "Tone 1"), with an editable mask rendered from [mask] when given; one undo step [label].
     * Returns null at the layer limit or without memory.
     */
    fun addAdjustmentLayer(spec: AdjustmentSpec, mask: MaskSpec?, label: String = "New adjustment layer"): Layer? {
        if (!canAddAdjustmentLayer) { toast("Layer limit reached (${maxLayers}) for this canvas size"); return null }
        val maskBmp = try {
            mask?.let { renderMaskSpec(it) }
        } catch (e: OutOfMemoryError) {
            toast("Not enough memory for another layer"); return null
        }
        val base = FilterRegistry.byId(spec.filterId)?.name ?: "Adjustment"
        var n = 1
        while (doc.layers.any { it.name == "$base $n" }) n++
        val layer = addLayerWith("$base $n", null, label) { l ->
            l.adjustment = spec
            if (maskBmp != null) { l.mask = maskBmp; l.maskSpec = mask }
        }
        if (layer == null) maskBmp?.recycle()
        return layer
    }

    /** [spec] rendered into a new document-sized mask bitmap. */
    private fun renderMaskSpec(spec: MaskSpec): Bitmap {
        val w = doc.width; val h = doc.height
        val bmp = BitmapUtils.createMaskBitmap(w, h, 0xFF000000.toInt())
        val band = max(1, min(h, (1 shl 18) / max(1, w)))
        val row = IntArray(w * band)
        var y = 0
        while (y < h) {
            val rows = min(band, h - y)
            MaskSpecs.render(spec, w, h, Rect(0, y, w, y + rows), row, w)
            bmp.setPixels(row, 0, w, 0, y, w, rows)
            y += rows
        }
        return bmp
    }

    /** The luminance of [layer]'s mask as a soft selection (one undo step "Mask to selection"). */
    fun selectionFromMask(layer: Layer) {
        val m = layer.mask ?: run { toast("\"${layer.name}\" has no mask"); return }
        val sel = try {
            Selection.fromBytes(BitmapUtils.maskToBytes(m), m.width, m.height)
        } catch (e: OutOfMemoryError) {
            toast("Not enough memory to select the mask"); return
        }
        if (sel.isEmpty) { toast("The mask of \"${layer.name}\" hides everything"); return }
        setSelection(sel, label = "Mask to selection")
    }

    // ------------------------------------------------------------------ text wrap (v1.5, §4.1)

    /** Re-flows wrapped text when its picture layer is edited (an edit listener, owned by A7). */
    val textWrap: TextWrapReflow = TextWrapReflow(this).also { addEditListener(it) }

    companion object {
        /** Tools whose brush the side sliders show. CLONE and MASK never become [lastPaintTool]. */
        val PAINT_TOOLS = setOf(ToolId.BRUSH, ToolId.ERASER, ToolId.SMUDGE, ToolId.BLUR, ToolId.CLONE, ToolId.MASK)

        /** Rounds of edit-event delivery (listeners reacting to listeners) before the rest is dropped. */
        private const val MAX_EDIT_ROUNDS = 4

        /** Shown when a filter is started on an adjustment layer. */
        const val ADJUSTMENT_FILTER_MESSAGE = "Adjustment layers have no pixels — edit the effect in Masks"

        const val PASTE_LABEL = "Paste"

        /** Tools that use the drawing color: a long press there picks a color (see pointerLongPress). */
        val HOLD_PICK_TOOLS = setOf(ToolId.BRUSH, ToolId.FILL, ToolId.SHAPE, ToolId.CURVE, ToolId.POLYLINE, ToolId.TEXT)
    }
}
