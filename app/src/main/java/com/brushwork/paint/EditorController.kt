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
import com.brushwork.paint.array.ArrayOps
import com.brushwork.paint.assist.GridRenderer
import com.brushwork.paint.assist.RulerRenderer
import com.brushwork.paint.assist.StrokeAssist
import com.brushwork.paint.assist.SymmetryGuides
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.BrushPreset
import com.brushwork.paint.brush.BrushPresetStore
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.AddLayerAction
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.engine.CompositeAction
import com.brushwork.paint.engine.DisplayTiles
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.engine.FolderComposite
import com.brushwork.paint.engine.LambdaAction
import com.brushwork.paint.engine.LayerDataAction
import com.brushwork.paint.engine.LayerPropsAction
import com.brushwork.paint.engine.LayerRenderOverride
import com.brushwork.paint.engine.LayerStructure
import com.brushwork.paint.engine.MaskChangeAction
import com.brushwork.paint.engine.MoveLayerAction
import com.brushwork.paint.engine.PixelEditRecorder
import com.brushwork.paint.engine.RemoveLayerAction
import com.brushwork.paint.engine.SavedSelectionsAction
import com.brushwork.paint.engine.SelectionAction
import com.brushwork.paint.engine.UndoAction
import com.brushwork.paint.engine.UndoManager
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.engine.live.LiveAdjust
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
import com.brushwork.paint.model.LayerTree
import com.brushwork.paint.model.RulerSettings
import com.brushwork.paint.model.SavedSelection
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.model.StabilizerSettings
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.snap.Increments
import com.brushwork.paint.snap.SnapService
import com.brushwork.paint.snap.SnapSession
import com.brushwork.paint.tools.LayerToolRules
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolFactory
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.select.SelectionOutline
import com.brushwork.paint.tools.text.TextWrapReflow
import com.brushwork.paint.tools.text.frames.TextThreads
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.ui.common.FolderLabels
import com.brushwork.paint.ui.common.SavedSelectionLabels
import com.brushwork.paint.vector.LayerDataTransforms
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorLayerOps
import com.brushwork.paint.vector.VectorLayers
import com.brushwork.paint.vector.select.PendingRenders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

/** What happened to a layer in a [LayerListEvent] (v1.6). */
enum class LayerListKind { ADDED, DUPLICATED, REMOVED, MERGED }

/**
 * A layer entered or left the stack as part of the step named [label] (v1.6, §4.3): [layer] was
 * added, duplicated (the copy), removed, or merged down (the upper layer, now gone); [source]: the
 * original of a duplicate, the layer merged into; null otherwise. Emitted by `addLayer` /
 * `addLayerWith` (`addVectorLayer`, `addAdjustmentLayer`, `paste`, `importImageAsLayer`...),
 * `addLayerWithContent`, `duplicateLayer`, `deleteLayer`, merge down and `ImportLayers.insert`
 * (each imported layer ADDED). Delivered to the
 * [LayerListListener]s in the same rounds as [EditEvent]s, when the outermost
 * [EditorController.editScope] ends: the operation's step is complete, so a listener that edits
 * folds into it with [EditorController.amendLastStep] (I2). Never for undo / redo.
 */
data class LayerListEvent(val kind: LayerListKind, val layer: Layer, val source: Layer?, val label: String)

/** Reacts to layers entering or leaving the stack (v1.6; e.g. linked text frames heal). Main thread. */
fun interface LayerListListener {
    fun onLayerList(e: LayerListEvent)
}

/**
 * v1.7 (item 10): a point in the undo history ([EditorController.undoMarker]);
 * [EditorController.rollbackTo] takes back every step pushed after it. [depth] = the number of
 * undo steps when it was taken, [top] = the newest of them (by identity; null with an empty
 * history), [dropped] = `UndoManager.dropped` then (a mark of an empty history holds only while
 * no step has been dropped since).
 */
class UndoMarker internal constructor(internal val depth: Int, internal val top: UndoAction?, internal val dropped: Long = 0)

/**
 * v1.7 (item 10): what a history tap over the UI puts back ([EditorController.uiMark],
 * [EditorController.restoreUiMark]): the undo history ([marker]), the settings journal position,
 * the live preset of every brush-engine tool (compared by reference), the colour, and the active
 * tool's own in-tool history ([toolMark] of [tool]).
 */
class UiMark internal constructor(
    val marker: UndoMarker,
    internal val settingsJournal: Int,
    internal val presets: Map<ToolId, BrushPreset>,
    internal val color: Int,
    internal val tool: Tool,
    internal val toolMark: Any?,
) {
    /** Set by the first [EditorController.releaseUiMark]: a second release (gesture end AND cancel) does nothing. */
    internal var released = false
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

    val canAddLayer: Boolean get() = effectiveLayerCount < maxLayers

    /**
     * v1.7 (I14): what [maxLayers] counts: the pixel layers (folders count 0) plus the array
     * sources, `pixelLayerCount + ceil(sum of ArrayPixels bytes / layerBytes)`. [canAddLayer],
     * [canAddAdjustmentLayer], `ImportLayers`' room check, `ArrayOps` and the layer window's
     * "n / max" all read it. Without folders and arrays it is `doc.layers.size` (v1.6).
     */
    val effectiveLayerCount: Int
        get() {
            val per = max(1L, doc.layerBytes)
            val arrayBytes = doc.layers.sumOf { it.array?.pixels?.bytes ?: 0L }
            return doc.pixelLayerCount + ((arrayBytes + per - 1) / per).toInt()
        }

    /** The message shown when a new layer would pass [maxLayers]. */
    internal fun layerLimitMessage(): String = "Layer limit reached (${maxLayers}) for this canvas size"

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

    /** Queued [EditEvent]s and [LayerListEvent]s (v1.6), in the order they happened. */
    private val queuedEdits = ArrayList<Any>()

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

    private val layerListListeners = ArrayList<LayerListListener>()

    /** Listens to layers entering or leaving the stack (v1.6, see [LayerListEvent]). */
    fun addLayerListListener(l: LayerListListener) {
        if (layerListListeners.none { it === l }) layerListListeners += l
    }

    fun removeLayerListListener(l: LayerListListener) {
        layerListListeners.removeAll { it === l }
    }

    /** Queues [e] for the layer-list listeners (dropped during undo / redo); delivered with the edit events. */
    @PublishedApi internal fun queueLayerList(e: LayerListEvent) {
        if (inHistory || layerListListeners.isEmpty()) return
        queuedEdits += e
        if (editDepth == 0) deliverEdits()
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
                for (e in batch) when (e) {
                    is EditEvent -> for (l in editListeners.toList()) l.onEdited(e)
                    is LayerListEvent -> for (l in layerListListeners.toList()) l.onLayerList(e)
                }
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

    // ------------------------------------------------------------------ history marks (v1.7, item 10)

    /**
     * The current point of the undo history. A pending live edit records its step first (it
     * belongs before the mark).
     */
    fun undoMarker(): UndoMarker {
        if (editDepth == 0) flushDeferredSteps()
        val um = undoManager
        return UndoMarker(um.undoCount, um.undoAt(um.undoCount - 1), um.dropped)
    }

    /**
     * Undoes and DROPS every step pushed after [marker]: nothing goes to redo and the redo stack
     * is untouched. Vector work still on its way and a pending live edit land first (they are
     * after the mark). False when the history no longer holds the marker (its newest step was
     * trimmed, cleared or folded into a later step).
     */
    fun rollbackTo(marker: UndoMarker): Boolean = rollbackSteps(marker) >= 0

    /** [rollbackTo]: the number of steps taken back, or -1 when the history no longer holds [marker]. */
    private fun rollbackSteps(marker: UndoMarker): Int {
        if (editDepth == 0) flushDeferredSteps()
        settleVectorWork()
        val um = undoManager
        val top = marker.top
        val keep = if (top == null) {
            if (um.dropped != marker.dropped) return -1
            0
        } else {
            val i = um.undoIndexOf(top)
            if (i < 0) return -1
            i + 1
        }
        if (um.undoCount <= keep) return 0
        var count = 0
        inHistoryDo {
            val steps = um.takeSince(keep)
            count = steps.size
            for (a in steps.asReversed()) a.undo(this)
            // Undone and never redone: released like a step leaving the redo stack.
            for (a in steps) a.dispose()
            true
        }
        editCount++
        doc.touch()
        invalidateOverlay()
        return count
    }

    /**
     * Opens a UI mark ([UiMark]): the history is not trimmed and the `AppSettings` journal
     * records while at least one mark is open. Close it with [releaseUiMark].
     */
    fun uiMark(): UiMark {
        val marker = undoMarker()
        undoManager.holdTrim()
        settings.openJournal()
        val presets = LinkedHashMap<ToolId, BrushPreset>()
        for (id in PAINT_TOOLS) presetFor(id)?.let { presets[id] = it }
        val tool = currentTool
        return UiMark(marker, settings.journalPosition(), presets, color, tool, tool.historyMark())
    }

    /**
     * Puts back what changed since [m]: if the active tool is still `m.tool`, its in-tool steps
     * first (`rollbackHistory`, which [undo] would otherwise step through first); then the undo
     * history ([rollbackTo]); the settings journal; every live preset changed through
     * [updatePreset] (put back and persisted as the side slider's step end does); the colour.
     * True when anything changed. Does not close the mark.
     */
    fun restoreUiMark(m: UiMark): Boolean {
        var changed = false
        val tool = currentTool
        if (tool === m.tool && tool.historyMark() != m.toolMark) {
            tool.rollbackHistory(m.toolMark)
            invalidateOverlay()
            changed = true
        }
        if (rollbackSteps(m.marker) > 0) changed = true
        if (settings.rollbackJournal(m.settingsJournal)) changed = true
        for ((id, preset) in m.presets) {
            if (presetFor(id) === preset) continue
            updatePreset(id, preset)
            BrushPresetStore.get(appContext).persist(this, id)
            changed = true
        }
        if (color != m.color) {
            color = m.color
            changed = true
        }
        return changed
    }

    /**
     * Closes [m] (every gesture end and every cancel): trimming resumes; with no mark open the
     * journal is emptied. Only the first release of a mark counts, so releasing it twice never
     * ends another open mark's trim hold or journal.
     */
    fun releaseUiMark(m: UiMark) {
        if (m.released) return
        m.released = true
        undoManager.releaseTrim()
        settings.closeJournal()
    }

    fun undo() {
        val session = filterSession
        if (session != null) { session.cancel(); return }
        // A pending live edit becomes its step first: undo then takes it back.
        flushDeferredSteps()
        // So do vector edits still rendering and the object edits waiting for them (v1.5): undo
        // takes back the newest, and nothing lands on top of what it took back.
        settleVectorWork()
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
        settleVectorWork()
        val tool = currentTool
        if (tool.hasPendingWork) {
            if (tool.redoStep()) { invalidateOverlay(); return }
            if (tool.hasUserChanges) return
            tool.discard()
            invalidateOverlay()
        }
        if (inHistoryDo { undoManager.redo(this) }) { editCount++; doc.touch() }
    }

    /**
     * Lands the vector work still on its way (v1.5): a render in flight and the object edits
     * queued behind it ([PendingRenders]: Object bar actions, lifts), in the order they were asked
     * for, so what follows (undo, redo, a layer operation, a filter, closing) works on them and
     * records its step after theirs. Not inside another step (they would join it), nor during
     * undo / redo or while edit listeners are told.
     */
    internal fun settleVectorWork() {
        if (editDepth != 0 || inHistory || delivering) return
        PendingRenders.settle(this)
    }

    /**
     * [settleVectorWork] when object edits are queued behind a vector render (an Object bar action
     * pressed a moment ago); otherwise nothing, so an operation that records no step (switching
     * layers, painting the mask) never waits for a render still running in the background.
     */
    private fun settleQueuedVectorWork() {
        if (PendingRenders.hasWaiting(this)) settleVectorWork()
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
        verifyTree()
        layersVersion++
        invalidateDoc(null)
    }

    /**
     * v1.7 safety net (I11): after every [structural] block of a document with a folder (or a
     * parent), [LayerTree.check] must hold and every folder must keep [Layer.FOLDER_BITMAP]. A
     * debug build (and the unit tests) throws, so a missed site shows at once; a release build
     * repairs with [LayerTree.sanitize] and logs, so a project can never become unloadable.
     */
    private fun verifyTree() {
        val layers = doc.layers
        if (layers.none { it.isFolder || it.parentId != Layer.ROOT_ID }) return
        val problem = LayerTree.check(layers)
        val ownBitmap = layers.firstOrNull { it.isFolder && it.bitmap !== Layer.FOLDER_BITMAP }
        if (problem == null && ownBitmap == null) return
        val what = problem ?: "$ownBitmap has a bitmap of its own"
        check(!isDebugBuild) { "Layer structure broken: $what" }
        android.util.Log.w("EditorController", "Layer structure broken, repaired: $what")
        if (problem != null) {
            val active = doc.layers.getOrNull(doc.activeLayerIndex)
            LayerTree.sanitize(layers)
            doc.activeLayerIndex = active?.let { doc.indexOf(it) }?.takeIf { it >= 0 } ?: doc.activeLayerIndex.coerceIn(0, layers.lastIndex)
        }
    }

    /** True in a debuggable build and under Robolectric (the [verifyTree] safety net throws there). */
    private val isDebugBuild: Boolean by lazy {
        (appContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 ||
            android.os.Build.FINGERPRINT == "robolectric"
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
        // v1.7 (item 18): the symmetry guides (area H decides when they show; the stub draws nothing).
        SymmetryGuides.draw(canvas, t, doc, activeToolId == ToolId.SYMMETRY)
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

    /**
     * False (with a message) when [layer] is locked or hidden (unless [allowHidden]). v1.7 (I11):
     * also through a folder: a child of a locked folder is locked ("Folder “X” is locked", X the
     * nearest locked folder) and a child of a hidden folder hidden ("Folder “X” is hidden"); a
     * folder itself is refused with "Choose a layer inside the folder to paint" unless
     * [allowFolder] (only structural operations, the Transform tool's folder lift and the folder
     * ⋮ actions pass true). Every pixel path that is not a tool (clear, flip, fill, the selection
     * bar, layer and mask actions) runs this check, so none of them reaches [Layer.FOLDER_BITMAP].
     */
    fun checkUsable(layer: Layer = doc.activeLayer, allowFolder: Boolean = false, allowHidden: Boolean = false): Boolean {
        if (layer.isFolder && !allowFolder) { toast(FolderLabels.PAINT_REFUSAL); return false }
        if (layer.locked) { toast("Layer \"${layer.name}\" is locked"); return false }
        val ancestors = if (layer.parentId == Layer.ROOT_ID) emptyList() else LayerTree.ancestors(doc.layers, doc.indexOf(layer)).map { doc.layers[it] }
        ancestors.firstOrNull { it.locked }?.let { toast(FolderLabels.locked(it.name)); return false }
        if (allowHidden) return true
        if (!layer.visible) { toast("Layer \"${layer.name}\" is hidden"); return false }
        ancestors.firstOrNull { !it.visible }?.let { toast(FolderLabels.hidden(it.name)); return false }
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
    fun beginEdit(layer: Layer = doc.activeLayer, target: EditTarget = editTargetOf(layer)): PixelEditRecorder {
        // v1.7: the last line of defence (a crash here means a missed guard, see checkUsable).
        check(!layer.isFolder) { "A folder has no pixels to edit: $layer" }
        return PixelEditRecorder(layer, target)
    }

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
        // An Object bar action waiting for a vector render is made on its layer first (v1.5; it
        // works on the active layer's objects, so it would be dropped after the switch).
        settleQueuedVectorWork()
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
        // Object edits queued behind a vector render (v1.5) land first, while the tool is still
        // active: one of them may lift its objects again, which the pause then commits or lets go
        // (never a lift of the content as it was before this operation, left open across it).
        settleQueuedVectorWork()
        currentTool.onDeactivate()
        // A live edit still on its way (a vector render in the background, also one the tool's
        // commit just started) records its step now, BEFORE the operation reads or changes the
        // layers: otherwise a deleted layer drops it (undoing the deletion brings back what was
        // erased) and a duplicate copies the layer as it was before it (v1.5 QA).
        if (editDepth == 0) flushDeferredSteps()
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
        // v1.6 (V4): the step's edit scope sits INSIDE withToolPaused, so the tool's pending work
        // commits first (its own steps and events), and a layer-list listener amends this step.
        return withToolPaused {
            editScope {
                val layer = Layer(doc.newLayerId(), name ?: uniqueLayerName("Layer ${doc.pixelLayerCount + 1}"), bmp)
                init(layer)
                // v1.7 (rule S): at [index] in the folder of the row below it, else at structure.insertionPoint().
                val at = index?.let { val i = it.coerceIn(0, doc.layers.size); LayerStructure.Insertion(i, doc.parentBelow(i)) }
                if (structure.insert(layer, at, label)) layer else { bmp.recycle(); null }
            }
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
            editScope {
                val layer = Layer(doc.newLayerId(), uniqueLayerName(name), bmp).also { it.textData = textData; it.shapeData = shapeData }
                if (structure.insert(layer, null, label)) layer else { bmp.recycle(); null }
            }
        }
    }

    /**
     * Re-renders the editable text layer [layer] with new text: clears [dirty] (document px; it
     * must cover the old AND the new text, null = the whole layer), lets [draw] paint the new text
     * and stores [textData] — one undo step named [label] that restores both pixels and text.
     * Returns false if the layer is gone or can't be edited. With [allowHidden] a hidden (not
     * locked) layer is updated too: text wrapped around a picture follows it while hidden, so it
     * is right when shown again. (A wrapper of [updateLayerData].)
     */
    fun updateTextLayer(layer: Layer, textData: String, label: String, dirty: Rect? = null, allowHidden: Boolean = false, draw: (Canvas) -> Unit): Boolean =
        updateLayerData(layer, layer.dataSnapshot().copy(text = textData), label, dirty, EditTarget.CONTENT, draw, "Not enough memory to update the text", allowHidden)

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
     * locked or hidden (adjustment layers are accepted: their data can always change); with
     * [allowHidden] a hidden layer is updated too (an edit that follows another layer's, e.g. a
     * re-flow of wrapped text, not a tool's).
     */
    internal fun updateLayerData(
        layer: Layer,
        after: LayerData,
        label: String,
        dirty: Rect?,
        target: EditTarget = EditTarget.CONTENT,
        allowHidden: Boolean = false,
        draw: ((Canvas) -> Unit)?,
    ): Boolean = updateLayerData(layer, after, label, dirty, target, draw, "Not enough memory for \"$label\"", allowHidden)

    private fun updateLayerData(
        layer: Layer,
        after: LayerData,
        label: String,
        dirty: Rect?,
        target: EditTarget,
        draw: ((Canvas) -> Unit)?,
        oomMessage: String,
        allowHidden: Boolean = false,
    ): Boolean = editScope {
        if (doc.indexOf(layer) < 0 || !checkUsable(layer, allowHidden = allowHidden)) return@editScope false
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
        // v1.7: a folder goes with its whole block (the layer window asks first, see deleteFolder).
        if (layer.isFolder) { deleteFolder(layer, keepChildren = false); return }
        if (doc.pixelLayerCount <= 1) { toast(LayerStructure.LAST_LAYER); return }
        if (doc.indexOf(layer) < 0) return
        // Layers are deleted without a confirmation, so pending tool work (a shape, text or
        // transform) is committed first rather than silently thrown away; undo restores both.
        // Resolve the index after committing (committing text can insert a layer): the structure
        // does (v1.7 X1; v1.6: emits REMOVED).
        withToolPaused { structure.delete(layer, keepChildren = false, label = "Delete layer") }
    }

    /**
     * Duplicates [layer] above itself. With an active selection only the selected pixels are
     * copied (like "copy selection to new layer"); the layer mask, if any, is copied whole.
     * v1.7: a folder duplicates its whole block ("Duplicate folder").
     */
    fun duplicateLayer(layer: Layer = activeLayer): Layer? {
        if (!canAddLayer) { toast(layerLimitMessage()); return null }
        // Copy after committing pending work so the duplicate includes it (v1.6: emits DUPLICATED).
        return withToolPaused { structure.duplicate(layer, if (layer.isFolder) FolderLabels.DUPLICATE else "Duplicate layer") }
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
        // v1.7 (rule C): a folder copies the composite of its children.
        val source = if (layer.isFolder) folderPixels(layer) ?: return false else layer.bitmap
        try {
            return copyFrom(layer, source)
        } finally {
            if (source !== layer.bitmap) source.recycle()
        }
    }

    private fun copyFrom(layer: Layer, source: Bitmap): Boolean {
        val sel = selection
        val rect = if (sel != null) Rect(sel.bounds) else contentBounds(source)
        if (rect == null || rect.isEmpty || !rect.intersect(0, 0, doc.width, doc.height)) {
            toast("Nothing to copy on \"${layer.name}\""); return false
        }
        val out = try {
            BitmapUtils.createLayerBitmap(rect.width(), rect.height()).also { b ->
                val c = Canvas(b)
                c.drawBitmap(source, -rect.left.toFloat(), -rect.top.toFloat(), null)
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

    /**
     * Moves [layer] to [toIndex] (0 = bottom). v1.7: a folder moves with its block, and the moved
     * unit takes the parent of the row above it (the drop rule of `LayerStructure.move`).
     */
    fun moveLayer(layer: Layer, toIndex: Int) {
        val from = doc.indexOf(layer)
        val to = toIndex.coerceIn(0, doc.layers.lastIndex)
        if (from < 0 || from == to) return
        withToolPaused { structure.move(layer, to, null, "Move layer") }
    }

    /**
     * One unit up at its level (v1.7: over a whole sibling folder; the top child of a folder leaves
     * it, directly above the folder). Without folders: v1.6's `moveLayer(layer, index + 1)`.
     */
    fun moveLayerUp(layer: Layer = activeLayer) {
        if (!doc.hasFolders) return moveLayer(layer, doc.indexOf(layer) + 1)
        val layers = doc.layers
        val i = doc.indexOf(layer)
        if (i < 0 || i + 1 >= layers.size) return
        val parent = LayerTree.parentOf(layers, i)
        if (i + 1 == parent) {
            // The folder's top child: out, directly above the folder.
            moveBlock(layer, parent, layers[parent].parentId)
            return
        }
        // The row above is the bottom of the sibling unit above: its own row is the first one at this level.
        var top = i + 1
        while (top < layers.lastIndex && layers[top].parentId != layer.parentId) top++
        moveBlock(layer, top, layer.parentId)
    }

    /**
     * One unit down at its level (v1.7: under a whole sibling folder; the bottom child of a
     * folder leaves it, directly below the folder). Without folders: v1.6's `moveLayer(layer, index - 1)`.
     */
    fun moveLayerDown(layer: Layer = activeLayer) {
        if (!doc.hasFolders) return moveLayer(layer, doc.indexOf(layer) - 1)
        val layers = doc.layers
        val i = doc.indexOf(layer)
        if (i < 0) return
        val b = LayerTree.block(layers, i)
        val parent = LayerTree.parentOf(layers, i)
        val parentFirst = if (parent < 0) 0 else LayerTree.block(layers, parent).first
        if (b.first - 1 < parentFirst) {
            // The folder's bottom child: out, directly below the folder (the flat order stays).
            if (parent >= 0) moveBlock(layer, i, layers[parent].parentId)
            return
        }
        // The row below the block is the top of the sibling unit below.
        val unit = LayerTree.block(layers, b.first - 1)
        moveBlock(layer, unit.first + (b.last - b.first), layer.parentId)
    }

    /** Merges [layer] into the layer below it (v1.7: its sibling below; a folder merges as "Merge folder"). */
    fun mergeDown(layer: Layer = activeLayer) {
        if (layer.isFolder) { mergeFolder(layer); return }
        val idx = doc.indexOf(layer)
        val lower = siblingBelow(idx)
        if (lower == null) { toast("There is no layer below to merge into"); return }
        if (lower.isFolder) { toast(FolderLabels.MERGE_INTO_REFUSAL); return }
        // An adjustment layer has no pixels to receive the merge (its effect on the layers below
        // would be lost); merging one DOWN applies its effect.
        if (lower.isAdjustmentLayer) { toast("Layers can't be merged into an adjustment layer"); return }
        withToolPaused { mergeDownNow(layer) }
    }

    /** The layer directly below the row at [idx] at its own level (v1.6: `layers[idx - 1]`), or null. */
    private fun siblingBelow(idx: Int): Layer? {
        if (idx <= 0 || idx > doc.layers.lastIndex) return null
        return doc.layers[idx - 1].takeIf { it.parentId == doc.layers[idx].parentId }
    }

    private fun mergeDownNow(layer: Layer) {
        val idx = doc.indexOf(layer)
        val lower = siblingBelow(idx) ?: return
        if (lower.isAdjustmentLayer || lower.isFolder) return
        editScope { mergeDownInto(layer, idx, lower) }
    }

    private fun mergeDownInto(layer: Layer, idx: Int, lower: Layer) {
        // Two vector layers can keep their objects (A1); otherwise the result is pixels.
        if (layer.isVectorLayer && lower.isVectorLayer && VectorLayerOps.mergeVector(this, layer, lower)) {
            queueLayerList(LayerListEvent(LayerListKind.MERGED, layer, lower, "Merge down"))
            return
        }
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
        // v1.6: the upper layer left the stack (a linked text frame heals).
        queueLayerList(LayerListEvent(LayerListKind.MERGED, layer, lower, "Merge down"))
    }

    // ------------------------------------------------------------------ layer tree (v1.7, item 8)

    /**
     * v1.7 (I11, X1): the only code that changes the layer structure (sweep rule S). Areas that
     * add layers ("Array N", "Pathfinder N", "Layer from folder") call `structure.insert`; every
     * call is one step.
     */
    internal val structure: LayerStructure = LayerStructure(this)

    private val folderCount: Int get() = doc.layers.count { it.isFolder }

    /** "Folder N", unused. */
    private fun newFolderName(): String = uniqueLayerName("Folder ${folderCount + 1}")

    /** "New folder": an empty, open folder directly above the active layer at its level; it becomes active. One step. */
    fun addFolder(): Layer? {
        if (folderCount >= LayerTree.MAX_FOLDERS) { toast(FolderLabels.COUNT_LIMIT); return null }
        return withToolPaused {
            editScope {
                val folder = Layer.newFolder(doc.newLayerId(), newFolderName())
                if (structure.insert(folder, structure.above(activeLayer), FolderLabels.NEW)) folder else null
            }
        }
    }

    /**
     * "Put in new folder": a new folder directly above [layer] (its block) at its level, with
     * [layer] moved in as its only child; [layer] stays active. One step.
     */
    fun putInNewFolder(layer: Layer = activeLayer): Layer? {
        if (doc.indexOf(layer) < 0) return null
        if (folderCount >= LayerTree.MAX_FOLDERS) { toast(FolderLabels.COUNT_LIMIT); return null }
        return withToolPaused {
            editScope {
                val idx = doc.indexOf(layer)
                if (idx < 0) return@editScope null
                val folder = Layer.newFolder(doc.newLayerId(), newFolderName())
                val base = LayerTree.inserted(doc.layers, idx + 1, listOf(folder), layer.parentId)
                val parents = base.parents.copyOf().also { it[idx] = folder.id }
                if (structure.apply(FolderLabels.PUT_IN_NEW, LayerTree.Plan(base.order, parents, idx))) folder else null
            }
        }
    }

    /**
     * Swipe right, "Move into folder above": [layer] (its block) becomes the bottom child of the
     * sibling folder directly above it. One "Move into folder" step; false when there is no such
     * folder, or with "Folders can be nested 8 deep" when the nesting would get too deep.
     */
    fun putIntoFolderAbove(layer: Layer): Boolean = withToolPaused {
        editScope {
            val i = doc.indexOf(layer)
            if (i < 0) return@editScope false
            val plan = LayerTree.putIntoFolderAbove(doc.layers, i)
            if (plan == null) {
                if (siblingFolderAbove(i) != null) toast(FolderLabels.DEPTH_LIMIT)
                return@editScope false
            }
            structure.apply(MOVE_INTO_FOLDER_LABEL, plan)
        }
    }

    /** The sibling unit directly above the row at [i] when it is a folder whose block starts right above [i]. */
    private fun siblingFolderAbove(i: Int): Layer? {
        val layers = doc.layers
        val pid = layers[i].parentId
        var j = i + 1
        while (j < layers.size && layers[j].parentId != pid) {
            if (layers[j].id == pid) return null
            j++
        }
        return layers.getOrNull(j)?.takeIf { it.isFolder && LayerTree.block(layers, j).first == i + 1 }
    }

    /** Swipe left, "Move out of folder": a folder's BOTTOM child leaves it and sits directly below the folder's block. One step. */
    fun takeOutOfFolder(layer: Layer): Boolean = withToolPaused {
        editScope {
            val i = doc.indexOf(layer)
            if (i < 0) return@editScope false
            val plan = LayerTree.takeOutOfFolder(doc.layers, i) ?: return@editScope false
            structure.apply(FolderLabels.MOVE_OUT, plan)
        }
    }

    /**
     * The long-press drag, across folders: [layer]'s block moves so that its top lands at flat
     * index [toIndex], in folder [newParent] (null: the drop rule of §3.8, see
     * `LayerStructure.move`). One "Move layer" step.
     */
    fun moveBlock(layer: Layer, toIndex: Int, newParent: Long?): Boolean =
        withToolPaused { structure.move(layer, toIndex, newParent, "Move layer") }

    /** Opens or closes [folder]'s rows in the layer window: view state, no step; marks the document changed (saved with it). */
    fun setFolderOpen(folder: Layer, open: Boolean) {
        if (!folder.isFolder || folder.folderOpen == open) return
        folder.folderOpen = open
        editCount++
        doc.touch()
        layersVersion++
    }

    /** "Pass through" on or off for [folder]: one `LayerDataAction` step (the spec rides `LayerData`). */
    fun setFolderPassThrough(folder: Layer, on: Boolean) {
        editScope {
            val spec = folder.folder ?: return@editScope
            if (spec.passThrough == on || doc.indexOf(folder) < 0) return@editScope
            val before = folder.dataSnapshot()
            val after = before.copy(folder = spec.copy(passThrough = on))
            folder.restoreData(after)
            folder.markChanged()
            pushUndo(LayerDataAction(FolderLabels.PASS_THROUGH, folder, before, after, doc.bounds))
            notifyLayersChanged()
            invalidateDoc(null)
        }
    }

    /**
     * "Merge folder": one raster layer at the folder's place from the composite of its children
     * (`FolderComposite.renderBlock`), carrying the folder's name, opacity, blend (a pass-through
     * folder merges as Normal), eye, lock and clipping; the block goes. ONE `LayerTreeAction`
     * step; the merged layer becomes active. MERGED events (source = the new layer) for every
     * layer that left.
     */
    fun mergeFolder(folder: Layer): Boolean {
        if (!folder.isFolder || doc.indexOf(folder) < 0) return false
        return withToolPaused {
            editScope {
                val f = doc.indexOf(folder)
                if (f < 0) return@editScope false
                val block = LayerTree.block(doc.layers, f)
                val members = doc.layers.subList(block.first, f + 1).toList()
                // A block without pixel layers turns into one more pixel layer.
                if (members.all { it.isFolder } && !canAddLayer) { toast(layerLimitMessage()); return@editScope false }
                val pixels = folderPixels(folder) ?: return@editScope false
                val merged = Layer(doc.newLayerId(), folder.name, pixels)
                val props = folder.props()
                merged.copyPropsFrom(if (folder.folder?.passThrough == true) props.copy(blendMode = LayerBlendMode.NORMAL) else props)
                val order = ArrayList<Layer>(doc.layers.size)
                val parents = ArrayList<Long>(doc.layers.size)
                for ((i, l) in doc.layers.withIndex()) {
                    if (i == block.first) { order += merged; parents += folder.parentId }
                    if (i in block) continue
                    order += l
                    parents += l.parentId
                }
                if (!structure.apply(FolderLabels.MERGE, LayerTree.Plan(order, parents.toLongArray(), block.first))) {
                    pixels.recycle()
                    return@editScope false
                }
                for (l in members) queueLayerList(LayerListEvent(LayerListKind.MERGED, l, merged, FolderLabels.MERGE))
                true
            }
        }
    }

    /**
     * "Layer from folder": a new raster layer directly above [folder] at its level with the
     * composite of its children (the folder's opacity and blend carried, Normal for pass-through);
     * the folder is kept. One step; the new layer becomes active.
     */
    fun layerFromFolder(folder: Layer): Layer? {
        if (!folder.isFolder || doc.indexOf(folder) < 0) return null
        if (!canAddLayer) { toast(layerLimitMessage()); return null }
        return withToolPaused {
            editScope {
                if (doc.indexOf(folder) < 0) return@editScope null
                val pixels = folderPixels(folder) ?: return@editScope null
                val layer = Layer(doc.newLayerId(), uniqueLayerName(folder.name), pixels)
                val props = folder.props()
                layer.copyPropsFrom(
                    props.copy(
                        name = layer.name, visible = true, clipping = false, locked = false, alphaLocked = false,
                        blendMode = if (folder.folder?.passThrough == true) LayerBlendMode.NORMAL else props.blendMode,
                    )
                )
                if (structure.insert(layer, structure.above(folder), FolderLabels.FROM_FOLDER)) layer else { pixels.recycle(); null }
            }
        }
    }

    /** The composite of [folder]'s children as new pixels in the document's color mode; null (with a message) without memory. */
    private fun folderPixels(folder: Layer): Bitmap? = try {
        FolderComposite.renderBlock(doc, folder).also { b ->
            if (doc.colorMode != ColorMode.RGB) ColorModeOps.constrain(b, doc.bounds, doc.colorMode)
        }
    } catch (e: OutOfMemoryError) {
        toast("Not enough memory for this folder")
        null
    }

    /** "Ungroup folder": the children move to the folder's level and the folder goes. One step. */
    fun ungroupFolder(folder: Layer): Boolean {
        if (!folder.isFolder || doc.indexOf(folder) < 0) return false
        return withToolPaused { structure.delete(folder, keepChildren = true, label = FolderLabels.UNGROUP) }
    }

    /** "Delete all" ([keepChildren] false: the folder and its block) or "Folder only" (ungroup and delete). One step. */
    fun deleteFolder(folder: Layer, keepChildren: Boolean) {
        if (!folder.isFolder || doc.indexOf(folder) < 0) return
        withToolPaused { structure.delete(folder, keepChildren, DELETE_FOLDER_LABEL) }
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
                mask.reportLostSpec(this)
                queueEdit(EditEvent(layer, EditTarget.MASK, null, label))
            }
            return
        }
        if (mask == null) {
            vectors.update(layer, mirrored, label)
            return
        }
        // The mask bitmap flips right before the new pixels are drawn (the step's edit event sees
        // both flipped); its spec and its undo action join the step once it is recorded. Whether
        // the content's step was recorded is told by the layer's content: a new instance (the
        // newest step is the flip's), or the same one when nothing changed after all (the edit
        // was re-based onto content that already was the mirrored one): the mask flip is then a
        // step of its own, never folded into an older step that happens to have the same label.
        var contentBefore: VectorContent? = null
        vectors.updateInternal(
            layer, mirrored, label, null, null,
            beforeApply = {
                contentBefore = layer.vector
                mask.flipBitmap(this)
            },
            onDone = { applied ->
                if (!applied) {
                    mask.flipBitmap(this)
                } else {
                    mask.setSpec(this, after = true)
                    if (layer.vector !== contentBefore) {
                        amendLastStep { pushUndo(mask) }
                    } else {
                        editScope {
                            pushUndo(mask)
                            queueEdit(EditEvent(layer, EditTarget.MASK, null, label))
                        }
                    }
                    mask.reportLostSpec(this)
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
            // In place: earlier steps that keep this mask bitmap must keep finding it (v1.5 QA).
            layer.mask?.let { BitmapUtils.flipInPlace(it, horizontal) }
            layer.markChanged()
        }

        fun setSpec(c: EditorController, after: Boolean) {
            layer.maskSpec = if (after) specAfter else specBefore
            layer.markChanged()
            c.notifyLayersChanged()
        }

        /** Says so when the flip made an editable mask a painted one (once the flip is done). */
        fun reportLostSpec(c: EditorController) {
            if (specBefore != null && specAfter == null) c.toast("The mask of \"${layer.name}\" is now a painted mask (undo to get the editable mask back)")
        }

        override fun undo(c: EditorController) { flipBitmap(c); setSpec(c, after = false) }
        override fun redo(c: EditorController) { flipBitmap(c); setSpec(c, after = true) }
    }

    private fun flipLayerNow(layer: Layer, horizontal: Boolean) = editScope {
        val flip: (EditorController) -> Unit = { c ->
            c.structural {
                // In place (self-inverse): the layer keeps its bitmaps, so an earlier step that
                // holds them (a canvas operation, a merge) still sees every later edit undone in
                // them; a new bitmap per flip left those edits in the held one, and its redo
                // brought them back (v1.5 QA).
                BitmapUtils.flipInPlace(layer.bitmap, horizontal)
                layer.mask?.let { BitmapUtils.flipInPlace(it, horizontal) }
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
        // A live edit still on its way (a vector render in the background) lands BEFORE the
        // layer is hidden or locked: afterwards it would be refused there and lost (v1.5 QA).
        if (editDepth == 0) flushDeferredSteps()
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
    fun toggleAlphaLock(layer: Layer) {
        if (layer.isFolder) { toast(FolderLabels.NO_ALPHA_LOCK); return }
        setLayerProps(layer, layer.props().copy(alphaLocked = !layer.alphaLocked), "Lock alpha")
    }
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
        if (layer.isFolder) { toast(FolderLabels.NO_MASK); return }
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

    /**
     * Switches [layer]'s mask on or off (one step). It changes what the layer shows, so it is
     * reported as an edit of the mask (v1.5: text wrapped around the layer re-flows in that step).
     */
    fun setMaskEnabled(layer: Layer, enabled: Boolean) = editScope {
        if (layer.maskEnabled == enabled) return@editScope
        val label = if (enabled) "Enable mask" else "Disable mask"
        setLayerProps(layer, layer.props().copy(maskEnabled = enabled), label)
        if (layer.mask != null) queueEdit(EditEvent(layer, EditTarget.MASK, null, label))
    }

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

    // ------------------------------------------------------------------ saved selections (v1.7, item 14)

    /** Ids and names taken by saves still compressing (two quick saves never share a name; they count toward the limit). */
    private val pendingSavedIds = HashSet<Long>()
    private val pendingSavedNames = HashSet<String>()

    /**
     * The highest revision given to each saved selection's id in this session. An update after
     * an undo never reuses a revision: `sel_<id>_r<revision>.bin` may already be on disk, listed
     * by the last save, with other pixels, and the save skips files that are (LayerEntries).
     */
    private val issuedSavedRevisions = HashMap<Long, Long>()

    /**
     * The revision saved selection [id] gets when its pixels change: above [current] and above
     * every revision given to [id] in this session (a revision loaded from the project is the
     * only one the last save can list from before the session). Main thread.
     */
    internal fun nextSavedRevision(id: Long, current: Long): Long {
        val r = maxOf(current, issuedSavedRevisions[id] ?: 0L) + 1
        issuedSavedRevisions[id] = r
        return r
    }

    /**
     * [after], the saved selections after an edit of [before] (a canvas operation's
     * `SavedSelectionOps.mappedForCanvas`), with every entry whose pixels changed (the same id,
     * another `packed`) given [nextSavedRevision], so its file never takes the name of another
     * version's. [after] itself when none changed. Main thread.
     */
    internal fun withNewSavedRevisions(before: List<SavedSelection>, after: List<SavedSelection>): List<SavedSelection> {
        if (before.isEmpty() || after.isEmpty()) return after
        val old = before.associateBy { it.id }
        var changed = false
        val out = after.map { e ->
            val b = old[e.id]
            if (b == null || b.packed === e.packed) {
                e
            } else {
                changed = true
                SavedSelection(e.id, e.name, Rect(e.bounds), e.packed, nextSavedRevision(e.id, b.revision))
            }
        }
        return if (changed) out else after
    }

    /**
     * "Save selection": the active selection is compressed on `Dispatchers.Default`, then added
     * to `doc.savedSelections` (oldest first; the rows list them newest first) as "Selection N"
     * with one `SavedSelectionsAction` step on the main thread. Its id is `doc.newSelectionId()`:
     * never reused in the document, a deleted (or undone) entry's included. False (with the
     * message) without a selection or at a limit (32 entries, 32 MB packed).
     */
    fun saveSelection(): Boolean {
        val sel = selection?.takeUnless { it.isEmpty } ?: return false
        val list = doc.savedSelections
        if (list.size + pendingSavedIds.size >= SavedSelection.MAX) { toast(SavedSelectionLabels.LIMIT); return false }
        if (list.sumOf { it.bytes } >= SavedSelection.MAX_TOTAL_BYTES) { toast(SavedSelectionLabels.FULL); return false }
        val id = doc.newSelectionId()
        val names = list.mapTo(HashSet()) { it.name } + pendingSavedNames
        var n = list.size + pendingSavedIds.size + 1
        while ("Selection $n" in names) n++
        val name = "Selection $n"
        pendingSavedIds += id
        pendingSavedNames += name
        scope.launch {
            try {
                val saved = packSelection(id, name, sel, revision = 1) ?: return@launch
                val now = doc.savedSelections
                when {
                    now.size >= SavedSelection.MAX -> toast(SavedSelectionLabels.LIMIT)
                    now.sumOf { it.bytes } + saved.bytes > SavedSelection.MAX_TOTAL_BYTES -> toast(SavedSelectionLabels.FULL)
                    else -> setSavedSelections(now + saved, SavedSelectionLabels.SAVE)
                }
            } finally {
                pendingSavedIds -= id
                pendingSavedNames -= name
            }
        }
        return true
    }

    /**
     * "Update from selection": saved selection [id] takes the active selection (same name, the
     * revision + 1; above every revision this id had in the session, so an update after an undo
     * gets a new file name), compressed in the background; one "Update saved selection" step.
     * False without a selection or such an entry.
     */
    fun updateSavedSelection(id: Long): Boolean {
        val sel = selection?.takeUnless { it.isEmpty } ?: return false
        val old = doc.savedSelections.firstOrNull { it.id == id } ?: return false
        val revision = nextSavedRevision(id, old.revision)
        scope.launch {
            val saved = packSelection(id, old.name, sel, revision) ?: return@launch
            val now = doc.savedSelections
            val i = now.indexOfFirst { it.id == id }
            if (i < 0) return@launch
            if (now.sumOf { it.bytes } - now[i].bytes + saved.bytes > SavedSelection.MAX_TOTAL_BYTES) {
                toast(SavedSelectionLabels.FULL); return@launch
            }
            // The entry keeps its current name (a rename may have happened meanwhile).
            val entry = if (now[i].name == saved.name) saved else SavedSelection(id, now[i].name, saved.bounds, saved.packed, saved.revision)
            setSavedSelections(now.toMutableList().also { it[i] = entry }, UPDATE_SAVED_SELECTION_LABEL)
        }
        return true
    }

    /** "Rename selection": one "Rename saved selection" step; a blank or unchanged name does nothing. */
    fun renameSavedSelection(id: Long, name: String) {
        val trimmed = name.trim()
        val list = doc.savedSelections
        val i = list.indexOfFirst { it.id == id }
        if (i < 0 || trimmed.isEmpty() || list[i].name == trimmed) return
        setSavedSelections(list.toMutableList().also { it[i] = list[i].renamed(trimmed) }, RENAME_SAVED_SELECTION_LABEL)
    }

    /** "Delete saved selection": one step. */
    fun deleteSavedSelection(id: Long) {
        val list = doc.savedSelections
        if (list.none { it.id == id }) return
        setSavedSelections(list.filter { it.id != id }, SavedSelectionLabels.DELETE)
    }

    /**
     * "Load selection" ([SelectionMode.REPLACE]) and "Add to / Subtract from / Intersect with
     * selection": saved selection [id] is inflated in the background, then combined with the
     * active selection (`Selection.combine`) as the usual `SelectionAction` step.
     */
    fun loadSavedSelection(id: Long, mode: SelectionMode) {
        val saved = doc.savedSelections.firstOrNull { it.id == id } ?: return
        val w = doc.width
        val h = doc.height
        scope.launch {
            val loaded = try {
                withContext(Dispatchers.Default) { saved.toSelection(w, h) }
            } catch (e: OutOfMemoryError) {
                toast("Not enough memory to load this selection"); return@launch
            }
            if (doc.width != w || doc.height != h) return@launch
            val base = selection
            val label = when (mode) {
                SelectionMode.REPLACE -> SavedSelectionLabels.LOAD
                SelectionMode.ADD -> SavedSelectionLabels.ADD
                SelectionMode.SUBTRACT -> SavedSelectionLabels.SUBTRACT
                SelectionMode.INTERSECT -> SavedSelectionLabels.INTERSECT
            }
            val result = when {
                base == null -> if (mode == SelectionMode.REPLACE || mode == SelectionMode.ADD) loaded else return@launch
                else -> base.combine(loaded, mode)
            }
            setSelection(result, label = label)
        }
    }

    /** [sel] packed as a saved selection on `Dispatchers.Default`; null (with a message) when empty or without memory. */
    private suspend fun packSelection(id: Long, name: String, sel: Selection, revision: Long): SavedSelection? = try {
        withContext(Dispatchers.Default) { SavedSelection.of(id, name, sel, revision) }
    } catch (e: OutOfMemoryError) {
        toast("Not enough memory to save this selection"); null
    }

    /** Sets `doc.savedSelections` to [after] as one `SavedSelectionsAction` step [label]. */
    private fun setSavedSelections(after: List<SavedSelection>, label: String) {
        val before = doc.savedSelections
        val action = SavedSelectionsAction(before, after, label)
        action.redo(this)
        pushUndo(action)
    }

    // ------------------------------------------------------------------ symmetry (v1.7, item 18)

    /** The symmetry drawing aid (saved with the project; area H's tool and guides read it). */
    var symmetry by mutableStateOf(doc.symmetry)
        private set

    /** Sets the symmetry aid: not undoable; saved with the project; redraws the overlay. */
    fun updateSymmetry(s: SymmetrySettings) {
        if (s == symmetry) return
        symmetry = s
        doc.symmetry = s
        editCount++
        doc.touch()
        invalidateOverlay()
    }

    // ------------------------------------------------------------------ arrays (v1.7, item 3): entry points into area E's ArrayOps

    fun arrayFromSelection(): Boolean = ArrayOps.fromSelection(this)
    fun arrayFromObjects(objectIds: Set<Long>): Boolean = ArrayOps.fromObjects(this, objectIds)
    /** The layer ⋮ "Array…". */
    fun arrayWholeLayer(layer: Layer): Boolean = ArrayOps.fromLayer(this, layer)

    // ------------------------------------------------------------------ filters

    fun startFilter(filter: Filter) {
        // An adjustment layer's effect is edited with the Masks tool (vector layers are
        // allowed: applying the filter turns them into raster layers, undoably). Refused before
        // anything else happens, so the tool stays as it was.
        // v1.7: a folder has no pixels to filter.
        if (activeLayer.isFolder) { toast(FolderLabels.PAINT_REFUSAL); return }
        if (activeLayer.isAdjustmentLayer) { toast(ADJUSTMENT_FILTER_MESSAGE); return }
        filterSession?.cancel()
        // A vector edit still rendering and the object edits waiting for it land first (v1.5),
        // while the tool is still active (an Object bar action may lift its objects again, which
        // the tool then commits or lets go): the filter previews and applies to their result, and
        // none of them lands inside the filter's own step or under its preview later.
        settleVectorWork()
        currentTool.onDeactivate()
        // (What the tool's commit rendered in the background lands too.)
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
        // v1.6: a live adjustment session frees its proxy tiles and caches.
        runCatching { liveAdjust.release() }
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
     * The vector layer the Vector button last left, and the raster layer it went to: tapped again
     * from there, the button goes back to that same vector layer (a new transparent canvas's empty
     * Background must not become a second vector layer).
     */
    private var vectorLeftLayer: Layer? = null
    private var vectorLeftTo: Layer? = null

    /**
     * The Vector button. Off: an empty plain layer is converted in place; else the visible,
     * unlocked vector layer right above is selected; else a new "Vector N" layer is added above.
     * Tapped again on the layer it just went back to, it returns to the vector layer it left.
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
            vectorLeftLayer = layer
            vectorLeftTo = activeLayer.takeIf { it !== layer }
            return
        }
        val left = vectorLeftLayer?.takeIf {
            layer === vectorLeftTo && doc.indexOf(it) >= 0 && it.isVectorLayer && doc.effectiveVisible(it) && !doc.effectiveLocked(it)
        }
        vectorLeftLayer = null
        vectorLeftTo = null
        if (left != null) {
            selectLayer(left)
            vectorReturnLayer = layer
            return
        }
        val idx = doc.indexOf(layer)
        val above = doc.layers.getOrNull(idx + 1)
        val ok = when {
            // (A hidden layer can't be edited: a new vector layer goes above it instead.)
            doc.effectiveVisible(layer) && isEmptyPlainLayer(layer) -> convertToVectorLayer(layer)
            above != null && above.isVectorLayer && doc.effectiveVisible(above) && !doc.effectiveLocked(above) -> { selectLayer(above); true }
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
    private fun isRasterLayer(l: Layer): Boolean = !l.isVectorLayer && !l.isAdjustmentLayer && !l.isFolder

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
        !layer.isFolder && !doc.effectiveLocked(layer) && layer.mask == null && layer.dataSnapshot().isEmpty && contentBounds(layer.bitmap) == null

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
    val canAddAdjustmentLayer: Boolean get() = effectiveLayerCount + 2 <= maxLayers

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

    // ------------------------------------------------------------------ v1.6 services (§4.3)

    /**
     * The app-wide increment steps (v1.6 §3.4, `snap/Increments.kt`): Compose state, persisted in
     * [settings]. Off by default (I8: then every helper is the identity). One per controller, so
     * no state leaks between editors or tests. Exposed to `ui/common` through `LocalIncrements`.
     */
    val increments: Increments = Increments(settings)

    /**
     * Live adjustment previews (v1.6 §3.1, `engine/live/LiveAdjust.kt`; area A): adjustment
     * sliders, mask handle drags and adjustment-layer opacity drags call `liveAdjust.touch` and
     * `end`; the canvas view asks it to draw the frame first. Under Robolectric its policy is
     * EXACT (I8): no session starts and `touch` only invalidates.
     */
    val liveAdjust: LiveAdjust = LiveAdjust(this)

    /**
     * Keeps linked text stories whole (v1.6 §3.6, `tools/text/frames/TextThreads.kt`; area D): an
     * edit listener AND a layer-list listener, registered here so it works before the Text frames
     * tool exists.
     */
    val textThreads: TextThreads = TextThreads(this).also { addEditListener(it); addLayerListListener(it) }

    companion object {
        /** Tools whose brush the side sliders show. CLONE and MASK never become [lastPaintTool]. */
        val PAINT_TOOLS = setOf(ToolId.BRUSH, ToolId.ERASER, ToolId.SMUDGE, ToolId.BLUR, ToolId.CLONE, ToolId.MASK)

        /** Rounds of edit-event delivery (listeners reacting to listeners) before the rest is dropped. */
        private const val MAX_EDIT_ROUNDS = 4

        /** v1.7 history labels (§4.8) of the folder operations whose menu text differs. */
        const val MOVE_INTO_FOLDER_LABEL = "Move into folder"
        const val DELETE_FOLDER_LABEL = "Delete folder"
        const val UPDATE_SAVED_SELECTION_LABEL = "Update saved selection"
        const val RENAME_SAVED_SELECTION_LABEL = "Rename saved selection"

        /** Shown when a filter is started on an adjustment layer. */
        const val ADJUSTMENT_FILTER_MESSAGE = "Adjustment layers have no pixels — edit the effect in Masks"

        const val PASTE_LABEL = "Paste"

        /**
         * Tools that use the drawing color: a long press there picks a color (see pointerLongPress).
         * v1.6: Path too; not Text frames (its drags must never turn into picks).
         */
        val HOLD_PICK_TOOLS = setOf(ToolId.BRUSH, ToolId.FILL, ToolId.SHAPE, ToolId.CURVE, ToolId.POLYLINE, ToolId.TEXT, ToolId.PATH)
    }
}
