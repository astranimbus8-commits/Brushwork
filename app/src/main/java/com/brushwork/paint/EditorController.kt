package com.brushwork.paint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
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
import com.brushwork.paint.filters.FilterSession
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.GridSettings
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.LayerProps
import com.brushwork.paint.model.RulerSettings
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.StabilizerSettings
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolFactory
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.select.SelectionOutline
import com.brushwork.paint.tools.transform.TransformTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

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

    var selection by mutableStateOf<Selection?>(null)
        private set

    // Only the model default (-1, -1) means "not placed yet"; a ruler may legitimately sit left of
    // or above the canvas.
    var ruler by mutableStateOf(doc.ruler.let { if (it.centerX == -1f && it.centerY == -1f) it.copy(centerX = doc.width / 2f, centerY = doc.height / 2f) else it })
        private set

    /** Transform tools set this while the selection is being moved (hides the stale ants). */
    var hideSelectionOutline by mutableStateOf(false)

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
        undoManager.push(action)
        editCount++
        doc.touch()
    }

    /**
     * Runs [block] and folds every undo action it pushes into ONE step named [label] (e.g. a curve
     * that is filled and then stroked with the brush tool).
     */
    fun groupUndo(label: String, block: () -> Unit) {
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
        if (undoManager.undo(this)) { editCount++; doc.touch() }
    }

    fun redo() {
        if (filterSession != null) return
        val tool = currentTool
        if (tool.hasPendingWork) {
            if (tool.redoStep()) { invalidateOverlay(); return }
            if (tool.hasUserChanges) return
            tool.discard()
            invalidateOverlay()
        }
        if (undoManager.redo(this)) { editCount++; doc.touch() }
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

    /** Preset of a painting tool (brush/eraser/smudge/blur), else null. */
    fun presetFor(id: ToolId): BrushPreset? = when (id) {
        ToolId.BRUSH -> brush
        ToolId.ERASER -> eraser
        ToolId.SMUDGE -> smudgeBrush
        ToolId.BLUR -> blurBrush
        else -> null
    }

    fun updatePreset(id: ToolId, preset: BrushPreset) {
        when (id) {
            ToolId.BRUSH -> brush = preset
            ToolId.ERASER -> eraser = preset
            ToolId.SMUDGE -> smudgeBrush = preset
            ToolId.BLUR -> blurBrush = preset
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

    /** Returns false (and shows a message) if the active layer can't be painted on. */
    fun checkEditable(layer: Layer = doc.activeLayer): Boolean {
        if (layer.locked) { toast("Layer \"${layer.name}\" is locked"); return false }
        if (!layer.visible) { toast("Layer \"${layer.name}\" is hidden"); return false }
        return true
    }

    /** Edit target of [layer] for painting tools (mask when editing a mask). */
    fun editTargetOf(layer: Layer): EditTarget = if (layer.editingMask && layer.mask != null) EditTarget.MASK else EditTarget.CONTENT

    /** Starts recording a pixel edit on [layer] (see [PixelEditRecorder]). */
    fun beginEdit(layer: Layer = doc.activeLayer, target: EditTarget = editTargetOf(layer)): PixelEditRecorder =
        PixelEditRecorder(layer, target)

    /**
     * Finishes a pixel edit: applies color-mode constraints to the touched area, pushes the undo
     * action, marks the layer changed and redraws. Returns false if nothing was touched.
     */
    fun commitEdit(recorder: PixelEditRecorder, label: String, extraActions: List<UndoAction> = emptyList()): Boolean {
        if (recorder.isEmpty) return false
        val rect = Rect(recorder.touched)
        if (recorder.target == EditTarget.CONTENT && doc.colorMode != ColorMode.RGB) {
            // Only the snapshotted tiles can have changed (cheaper than the union bounding box).
            for (tile in recorder.touchedTileRects()) ColorModeOps.constrain(recorder.layer.bitmap, tile, doc.colorMode)
        }
        val action = recorder.finish(label) ?: return false
        // Extra actions (e.g. a SelectionAction applied with recordUndo = false) join the same step.
        pushUndo(if (extraActions.isEmpty()) action else CompositeAction(label, listOf(action) + extraActions))
        recorder.layer.markChanged()
        layersVersion++
        invalidateDoc(rect)
        return true
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
    fun addLayer(name: String? = null, index: Int? = null, label: String = "Add layer"): Layer? {
        if (!canAddLayer) { toast("Layer limit reached (${maxLayers}) for this canvas size"); return null }
        val bmp = try { BitmapUtils.createLayerBitmap(doc.width, doc.height) } catch (e: OutOfMemoryError) { toast("Not enough memory for another layer"); return null }
        return withToolPaused {
            val layer = Layer(doc.newLayerId(), name ?: uniqueLayerName("Layer ${doc.layers.size + 1}"), bmp)
            val at = (index ?: (doc.activeLayerIndex + 1)).coerceIn(0, doc.layers.size)
            structural {
                doc.layers.add(at, layer)
                doc.activeLayerIndex = at
            }
            pushUndo(AddLayerAction(layer, at, label))
            layer
        }
    }

    /** Adds a new layer above the active one and lets [draw] paint into it (document coordinates). */
    fun addLayerWithContent(name: String, label: String, draw: (Canvas) -> Unit): Layer? {
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
            val layer = Layer(doc.newLayerId(), uniqueLayerName(name), bmp)
            val at = (doc.activeLayerIndex + 1).coerceIn(0, doc.layers.size)
            structural {
                doc.layers.add(at, layer)
                doc.activeLayerIndex = at
            }
            pushUndo(AddLayerAction(layer, at, label))
            layer
        }
    }

    fun deleteLayer(layer: Layer = activeLayer) {
        if (doc.layers.size <= 1) { toast("A drawing needs at least one layer"); return }
        val idx = doc.indexOf(layer)
        if (idx < 0) return
        currentTool.discard()
        structural {
            doc.layers.removeAt(idx)
            doc.activeLayerIndex = min((idx - 1).coerceAtLeast(0), doc.layers.lastIndex)
        }
        pushUndo(RemoveLayerAction(layer, idx))
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
            val copy = try {
                val pixels = BitmapUtils.copy(layer.bitmap)
                if (sel != null) BitmapUtils.maskWith(Canvas(pixels), sel.mask)
                Layer(doc.newLayerId(), uniqueLayerName("${layer.name} copy"), pixels).also {
                    it.mask = layer.mask?.let { m -> BitmapUtils.copy(m) }
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
        if (doc.indexOf(layer) <= 0) { toast("There is no layer below to merge into"); return }
        withToolPaused { mergeDownNow(layer) }
    }

    private fun mergeDownNow(layer: Layer) {
        val idx = doc.indexOf(layer)
        if (idx <= 0) return
        val lower = doc.layers[idx - 1]
        // Flatten lower (+ its mask, opacity) and upper (with blend, opacity, mask, clipping) via a
        // temporary two-layer document so the result matches what's on screen. When both clip to
        // the same base further down, the upper one is simply drawn over the lower one here (the
        // merged layer keeps the lower layer's clipping).
        val tmpDoc = Document("merge", "merge", doc.width, doc.height)
        val lowerView = Layer(-1, lower.name, lower.bitmap).also {
            it.copyPropsFrom(lower.props().copy(blendMode = LayerBlendMode.NORMAL, visible = true, clipping = false))
            it.mask = lower.mask
        }
        val upperView = Layer(-2, layer.name, layer.bitmap).also {
            it.copyPropsFrom(layer.props().copy(visible = true, clipping = layer.clipping && !lower.clipping))
            it.mask = layer.mask
        }
        tmpDoc.layers += lowerView
        tmpDoc.layers += upperView
        val merged = Compositor(tmpDoc) { null }.renderFlattened()
        val beforeBmp = lower.bitmap; val beforeMask = lower.mask; val beforeProps = lower.props()
        val afterProps = beforeProps.copy(opacity = 1f, maskEnabled = true)
        val replace = LambdaAction("Merge down", byteSize = beforeBmp.byteCount.toLong() + (beforeMask?.byteCount ?: 0),
            onUndo = { c -> c.structural { lower.bitmap = beforeBmp; lower.mask = beforeMask; lower.copyPropsFrom(beforeProps); lower.markChanged() } },
            onRedo = { c -> c.structural { lower.bitmap = merged; lower.mask = null; lower.editingMask = false; lower.copyPropsFrom(afterProps); lower.markChanged() } },
        )
        val remove = RemoveLayerAction(layer, idx, "Merge down")
        replace.redo(this)
        remove.redo(this)
        structural { doc.activeLayerIndex = doc.indexOf(lower) }
        pushUndo(CompositeAction("Merge down", listOf(replace, remove)))
    }

    /** Mirrors a layer (pixels and mask). Self-inverse, so undo just flips again. */
    fun flipLayer(layer: Layer = activeLayer, horizontal: Boolean) {
        if (!checkEditable(layer)) return
        withToolPaused { flipLayerNow(layer, horizontal) }
    }

    private fun flipLayerNow(layer: Layer, horizontal: Boolean) {
        val flip: (EditorController) -> Unit = { c ->
            c.structural {
                val old = layer.bitmap
                layer.bitmap = BitmapUtils.flipped(old, horizontal)
                layer.mask = layer.mask?.let { BitmapUtils.flipped(it, horizontal) }
                layer.markChanged()
            }
        }
        flip(this)
        pushUndo(LambdaAction(if (horizontal) "Flip layer horizontally" else "Flip layer vertically", onUndo = flip, onRedo = flip))
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
    fun toggleClipping(layer: Layer) = setLayerProps(layer, layer.props().copy(clipping = !layer.clipping), "Clipping")
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
        val action = MaskChangeAction(layer, m, null, "Delete mask")
        action.redo(this)
        pushUndo(action)
    }

    /** Bakes the mask into the layer's pixels and removes it. */
    fun applyMask(layer: Layer = activeLayer) {
        val m = layer.mask ?: return
        val rec = beginEdit(layer, EditTarget.CONTENT)
        rec.touchAll()
        Canvas(layer.bitmap).drawBitmap(m, 0f, 0f, BitmapUtils.newMaskApplyPaint())
        val pix = rec.finish("Apply mask")
        val maskAction = MaskChangeAction(layer, m, null, "Apply mask")
        maskAction.redo(this)
        layer.markChanged()
        pushUndo(CompositeAction("Apply mask", listOfNotNull(pix, maskAction)))
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
        filterSession?.cancel()
        currentTool.onDeactivate()
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
        tiles.release()
        undoManager.clear()
    }

    companion object {
        val PAINT_TOOLS = setOf(ToolId.BRUSH, ToolId.ERASER, ToolId.SMUDGE, ToolId.BLUR)

        const val PASTE_LABEL = "Paste"

        /** Tools that use the drawing color: a long press there picks a color (see pointerLongPress). */
        val HOLD_PICK_TOOLS = setOf(ToolId.BRUSH, ToolId.FILL, ToolId.SHAPE, ToolId.CURVE, ToolId.POLYLINE, ToolId.TEXT)
    }
}
