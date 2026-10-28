package com.brushwork.paint.array

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import com.brushwork.paint.ColorModeOps
import com.brushwork.paint.EditorController
import com.brushwork.paint.LayerListEvent
import com.brushwork.paint.LayerListKind
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.LambdaAction
import com.brushwork.paint.engine.LayerBudget
import com.brushwork.paint.engine.UndoAction
import com.brushwork.paint.model.ArrayPixels
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerArray
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.array.ArrayTool
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.ArrayLabels110
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorLayers
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.render.RenderCache
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * v1.10 (R4, UQ4, design R4): "Array behind": the copies of a source go on a NEW layer directly
 * below it ("copy below", B2), and the source itself is not changed at all. The new layer
 * "Array N" holds a copy of the source and an array with `ArraySpec.hideSource`: it draws, exports
 * and applies every copy but copy 0, so the copies show behind the untouched source.
 *
 * - **Raster layer with a selection** ([fromSelectionBelow]): the selected pixels are COPIED
 *   (`ArraySources.selectedPixels`; no cut, so an alpha-locked source is allowed). The selection
 *   stays on the main layer.
 * - **Vector layer with a selection, or the object bar** ([fromObjectsBelow]): the touched objects
 *   are copied, ids kept (ids are per layer); the source keeps them and is not re-rendered.
 * - **Whole text, shape or vector layer** ([fromLayerBelow]: the layer menu, or the bar on such a
 *   layer): a copy of the layer's content. Linked text frames stay refused.
 * - **Raster layer without a selection**: `ArrayLabels.PLAIN_REFUSAL`, as "Array".
 *
 * The new layer goes in through `LayerStructure.below` (rule S): directly under the source, in its
 * folder, with its clipping, opacity and blend mode copied. ONE step
 * [ArrayLabels110.ARRAY_BEHIND_STEP] (I2): its undo removes the layer and selects the source
 * again, its redo adds it back. Room is asked of `LayerBudget.current` (the source bytes of a
 * pixel array count, as in v1.9), and a refusal shows its message. The Array tool then opens on
 * the new layer, as every way of making an array does.
 *
 * A layer that already has an array is not offered ([available]); asked anyway, its array opens
 * in the Array tool, as "Array…" does. On a copies-only layer ([isCopiesOnly]) "Remove array"
 * reads [ArrayLabels110.REMOVE_WITH_LAYER] and deletes the whole layer in one step
 * ([removeWithLayer], UQ11).
 *
 * v1.10 G2 (V6): the copies of a whole vector layer whose render would hold the main thread past
 * `VectorLayers`' budget render on a worker under the busy overlay ([renderBelow]); the layer
 * goes in, as the same one step, when that render is done.
 *
 * Main thread. Pixel work goes through `ArraySources` (this file reads no layer pixels itself).
 */
object ArrayBehind {

    /**
     * True when the [ArrayLabels110.BEHIND] entries can act: objects are selected on a vector layer, or the
     * active layer is a text, shape or vector layer, or a raster layer with a selection; never on
     * folders, adjustment layers, linked text frames or a layer that already has an array.
     * The editor screen reads it in a `derivedStateOf` (`EditorController.arrayBehindAvailable`),
     * so the layer list and edit counters are read first, whatever the answer: a layer's kind,
     * array and place are plain fields that change with them.
     */
    fun available(c: EditorController): Boolean {
        if (c.layersVersion < 0 || c.editCount < 0) return false
        val objectLayer = c.vectors.selectedLayer
        if (objectLayer != null && c.vectors.selectedIds.isNotEmpty()) return offered(objectLayer)
        val src = c.activeLayer
        if (!offered(src)) return false
        return src.isVectorLayer || src.isTextLayer || src.isShapeLayer || c.selection != null
    }

    /**
     * The [ArrayLabels110.BEHIND] entry of the selection bar and the object bar
     * (`EditorController.arrayBehind`): the selected objects ([fromObjectsBelow]) when there are,
     * else [fromSelectionBelow].
     */
    fun run(c: EditorController): Boolean {
        val ids = c.vectors.selectedIds
        return if (ids.isNotEmpty() && c.vectors.selectedLayer != null) fromObjectsBelow(c, ids) else fromSelectionBelow(c)
    }

    /**
     * [ArrayLabels110.BEHIND] on the active layer: a raster layer's selected pixels, a vector layer's objects
     * the selection touches (the whole layer without a selection), or a whole text or shape
     * layer, into a new copies-only layer below it (one step). True when it was done.
     */
    fun fromSelectionBelow(c: EditorController): Boolean {
        val src = c.activeLayer
        ArrayOps.refusal(src)?.let { c.toast(it); return false }
        if (src.array != null) { ArrayOps.openTool(c, src); return true }
        val sel = c.selection
        return when {
            src.isVectorLayer -> if (sel == null) fromLayerBelow(c, src) else objectsBelow(c, src, null)
            src.isTextLayer || src.isShapeLayer -> fromLayerBelow(c, src)
            sel == null -> { c.toast(ArrayLabels.PLAIN_REFUSAL); false }
            else -> pixelsBelow(c, src)
        }
    }

    /**
     * The object bar's [ArrayLabels110.BEHIND]: the objects [objectIds] of the vector layer whose objects are
     * selected (else the active layer), copied with their ids into a new copies-only layer below
     * it (one step). The object selection is cleared, as "Array" clears it.
     */
    fun fromObjectsBelow(c: EditorController, objectIds: Set<Long>): Boolean {
        val layer = c.vectors.selectedLayer?.takeIf { objectIds.isNotEmpty() } ?: c.activeLayer
        ArrayOps.refusal(layer)?.let { c.toast(it); return false }
        if (layer.array != null) { ArrayOps.openTool(c, layer); return true }
        if (!layer.isVectorLayer) { c.toast(ArrayLabels.PLAIN_REFUSAL); return false }
        if (objectIds.isEmpty()) { c.toast(ArrayOps.NO_OBJECTS); return false }
        return objectsBelow(c, layer, objectIds)
    }

    /**
     * The layer menu's [ArrayLabels110.LAYER_MENU] (and [ArrayLabels110.BEHIND] on a whole text, shape or
     * vector layer): a copy of [layer]'s content into a new copies-only layer below it (one
     * step). A raster layer takes the selection ([fromSelectionBelow]) or is refused with
     * `ArrayLabels.PLAIN_REFUSAL`; a layer with an array opens it in the Array tool.
     */
    fun fromLayerBelow(c: EditorController, layer: Layer): Boolean = wholeBelow(c, layer, background = true)

    /**
     * [fromLayerBelow]. With [background], the copies of a vector layer whose render would hold
     * the main thread past `VectorLayers`' budget render on a worker ([renderBelow], v1.10 G2, V6):
     * true then, and the layer goes in when the render is done.
     */
    private fun wholeBelow(c: EditorController, layer: Layer, background: Boolean): Boolean {
        if (c.doc.indexOf(layer) < 0) return false
        ArrayOps.refusal(layer)?.let { c.toast(it); return false }
        if (layer.array != null) { ArrayOps.openTool(c, layer); return true }
        if (!layer.isVectorLayer && !layer.isTextLayer && !layer.isShapeLayer) {
            if (c.selection == null) { c.toast(ArrayLabels.PLAIN_REFUSAL); return false }
            if (layer !== c.activeLayer) c.selectLayer(layer)
            return pixelsBelow(c, layer)
        }
        if (!c.checkUsable(layer)) return false
        if (!roomFor(c, 0L)) return false
        var later: (() -> Unit)? = null
        val made = ArrayOps.paused(c) {
            c.vectors.flushPending()
            val before = layer.dataSnapshot()
            val content = before.vector
            if (content != null && content.objects.isEmpty()) { c.toast(ArrayOps.emptyLayer(layer.name)); return@paused null }
            // The layer's content without its mask or other data: the copies are a plain layer.
            val data = LayerData(text = before.text, shape = before.shape, vector = content, array = LayerArray(behindSpec()))
            val bmp = if (content != null) {
                val shown = ArrayDraw.effectiveVector(data) ?: content
                if (background && c.vectors.rendersInBackground(shown, c.doc.bounds)) {
                    later = { renderBelow(c, layer, before, data, shown) }
                    return@paused null
                }
                drawn(c) { cv -> ArrayOps.renderTiles(cv, shown, c.doc.bounds, c.doc.bounds) }
            } else {
                // A text or shape source drawn once per copy, as `updateLayerData` draws an array.
                val one = ArraySources.sourceDraw(data, c.doc.colorMode, c.doc.width, c.doc.height)
                if (one == null) { c.toast(ArrayOps.emptyLayer(layer.name)); return@paused null }
                val bounds = ArrayDraw.sourceBounds(data) ?: RectF()
                drawn(c) { cv -> ArrayDraw.drawWithArray(cv, data.array, bounds, one) }
            } ?: return@paused null
            placeBelow(c, layer, newLayer(c, layer, bmp, data))
        }
        later?.let { it(); return true }
        if (made == null) return false
        opened(c, made, layer)
        return true
    }

    /**
     * v1.10 G2 (V6): the copies [shown] of the whole vector layer [src] (its data [before], the new
     * layer's [data]) rendered on a worker, for a render that would hold the main thread past
     * `VectorLayers`' budget (I3, `VectorLayers.rendersInBackground`; on the T606 proxy the
     * render on the main thread of a phone-size layer of 200 paths and shapes takes about 22 ms, of
     * 200 brush strokes about 83 ms, against a frame of 16.7 ms). It runs under the busy overlay
     * [VectorLayers.BUSY_LABEL] with its progress and Stop, as a background vector render shows
     * it, and the overlay takes every touch meanwhile. When the render is done the new layer goes in
     * as the same ONE step [ArrayLabels110.ARRAY_BEHIND_STEP] a render on the main thread makes (the
     * same pixels: `VectorLayerRenderer` over the whole document, the color mode applied). A source
     * that changed meanwhile (its data no longer [before], the document resized) is made again from
     * what it holds now, on the main thread; one that is gone makes nothing. Stopped, or the editor
     * closing, records nothing; out of memory says so.
     */
    private fun renderBelow(c: EditorController, src: Layer, before: LayerData, data: LayerData, shown: VectorContent) {
        val doc = Rect(c.doc.bounds)
        val colorMode = c.doc.colorMode
        val job = c.scope.async(Dispatchers.Default) {
            val bmp = try { BitmapUtils.createLayerBitmap(doc.width(), doc.height()) } catch (e: OutOfMemoryError) { return@async null }
            try {
                val tips = TipCache(8L shl 20)
                // Progress reaches the main thread at most once per percent.
                var posted = -1
                try {
                    VectorLayerRenderer.renderWith(Canvas(bmp), shown, doc, emptySet(), tips, doc, RenderCache()) { done, n ->
                        val pct = done * 100 / max(1, n)
                        if (pct != posted) {
                            posted = pct
                            c.scope.launch { if (c.busyMessage == VectorLayers.BUSY_LABEL) c.busyProgress = pct / 100f }
                        }
                        isActive
                    }
                } finally {
                    tips.clear()
                }
                ensureActive()
                if (colorMode != ColorMode.RGB) ColorModeOps.constrain(bmp, doc, colorMode)
                bmp
            } catch (e: OutOfMemoryError) {
                bmp.recycle()
                null
            } catch (t: Throwable) {
                bmp.recycle()
                throw t
            }
        }
        c.runBusy(VectorLayers.BUSY_LABEL, onCancel = { job.cancel() }) {
            val bmp = try {
                job.await()
            } catch (e: CancellationException) {
                // Stopped: nothing is made. The editor closing cancels this wait too.
                if (job.isCancelled && currentCoroutineContext().isActive) null else throw e
            }
            if (bmp == null) {
                if (!job.isCancelled) c.toast(ArrayOps.MEMORY_REFUSAL)
                return@runBusy
            }
            if (c.doc.indexOf(src) < 0 || c.doc.bounds != doc || c.doc.colorMode != colorMode || src.dataSnapshot() != before) {
                bmp.recycle()
                wholeBelow(c, src, background = false)
                return@runBusy
            }
            val made = ArrayOps.paused(c) { placeBelow(c, src, newLayer(c, src, bmp, data)) } ?: return@runBusy
            opened(c, made, src)
        }
    }

    /** True on a layer whose array hides its source: the copies-only layer "Array behind" makes. */
    fun isCopiesOnly(layer: Layer): Boolean = layer.array?.spec?.hideSource == true

    /** The remove item's words on [layer]: [ArrayLabels110.REMOVE_WITH_LAYER] on a copies-only layer, else "Remove array". */
    fun removeLabel(layer: Layer): String = if (isCopiesOnly(layer)) ArrayLabels110.REMOVE_WITH_LAYER else ArrayLabels.REMOVE

    /**
     * [ArrayLabels110.REMOVE_WITH_LAYER] (UQ11): the copies-only [layer] goes as a whole, ONE step
     * with that name (through `controller.structure`, rule S; the row below becomes active, as
     * "Delete layer" does). Undo brings it back with its array. False when it is not in the
     * document or the delete is refused (the last pixel layer, with its message).
     */
    fun removeWithLayer(c: EditorController, layer: Layer): Boolean {
        if (c.doc.indexOf(layer) < 0 || !isCopiesOnly(layer)) return false
        return ArrayOps.paused(c) {
            c.doc.indexOf(layer) >= 0 && c.structure.delete(layer, keepChildren = false, label = ArrayLabels110.REMOVE_WITH_LAYER)
        }
    }

    // ------------------------------------------------------------------ internals

    /** A new behind array: `ArraySpec()` (count 3 in Line mode, relative X 100 %) without copy 0. */
    private fun behindSpec(): ArraySpec = ArraySpec(hideSource = true)

    /** Whether [layer] may be a source: not refused (folder, adjustment, linked text) and without an array of its own. */
    private fun offered(layer: Layer): Boolean = layer.array == null && ArrayOps.refusal(layer) == null

    /** Room for one more layer holding [bytes] of array source pixels; else the budget's message. */
    private fun roomFor(c: EditorController, bytes: Long): Boolean {
        val budget = LayerBudget.current
        if (budget.canAdd(c.doc, extraBytes = bytes)) return true
        c.toast(budget.refusal(c.doc))
        return false
    }

    /** The selected pixels of the raster layer [src] copied into a new copies-only layer below it. */
    private fun pixelsBelow(c: EditorController, src: Layer): Boolean {
        if (!c.checkUsable(src)) return false
        if (!roomFor(c, 0L)) return false
        val made = ArrayOps.paused(c) {
            val sel = c.selection ?: return@paused null
            var px: ArrayPixels? = null
            try {
                px = ArraySources.selectedPixelsOf(src, sel)
                if (px == null) { c.toast(ArrayOps.EMPTY_SELECTION); return@paused null }
                if (!roomFor(c, px.bytes)) return@paused null
                ArraySources.constrain(px, c.doc.colorMode)
                val data = LayerData(array = LayerArray(behindSpec(), px))
                val bmp = drawn(c) { cv -> ArrayDraw.drawPixels(cv, data.array!!) } ?: return@paused null
                val layer = placeBelow(c, src, newLayer(c, src, bmp, data))
                if (layer != null) px = null
                layer
            } catch (e: OutOfMemoryError) {
                c.toast(ArrayOps.MEMORY_REFUSAL)
                null
            } finally {
                px?.let { ArraySources.release(it) }
            }
        } ?: return false
        opened(c, made, src)
        return true
    }

    /** The objects [ids] of [layer] (null: those the selection touches) copied into a new copies-only layer below it. */
    private fun objectsBelow(c: EditorController, layer: Layer, ids: Set<Long>?): Boolean {
        if (!c.checkUsable(layer)) return false
        if (!roomFor(c, 0L)) return false
        val made = ArrayOps.paused(c) {
            c.vectors.flushPending()
            val content = layer.vector ?: return@paused null
            val sel = c.selection
            val picked = ids ?: if (sel != null) VectorOps.touching(content, sel) else emptySet()
            val objects = content.objects.filter { it.id in picked }
            if (objects.isEmpty()) { c.toast(ArrayOps.NO_OBJECTS); return@paused null }
            val data = LayerData(vector = VectorContent(objects = objects, nextId = content.nextId), array = LayerArray(behindSpec()))
            val shown = ArrayDraw.effectiveVector(data) ?: data.vector!!
            val bmp = drawn(c) { cv -> ArrayOps.renderTiles(cv, shown, c.doc.bounds, c.doc.bounds) } ?: return@paused null
            placeBelow(c, layer, newLayer(c, layer, bmp, data))
        } ?: return false
        c.vectors.setSelection(null, emptySet())
        opened(c, made, layer)
        return true
    }

    /**
     * The Array tool opened on the new copies-only layer [made], remembering [src] so that leaving
     * the tool selects the source again (`ArrayTool.madeBehind`, the lead's choice).
     */
    private fun opened(c: EditorController, made: Layer, src: Layer) {
        ArrayOps.openTool(c, made)
        (c.tools[ToolId.ARRAY] as? ArrayTool)?.madeBehind(made, src)
    }

    /**
     * A new document-sized cache drawn by [draw], limited to the document's color mode; null
     * (with the memory message) without memory.
     */
    private fun drawn(c: EditorController, draw: (Canvas) -> Unit): Bitmap? {
        val doc = c.doc
        var bmp: Bitmap? = null
        return try {
            val b = BitmapUtils.createLayerBitmap(doc.width, doc.height)
            bmp = b
            draw(Canvas(b))
            if (doc.colorMode != ColorMode.RGB) ColorModeOps.constrain(b, doc.bounds, doc.colorMode)
            b
        } catch (e: OutOfMemoryError) {
            bmp?.recycle()
            c.toast(ArrayOps.MEMORY_REFUSAL)
            null
        }
    }

    /** The layer "Array N" showing [bmp] with [data], its clipping, opacity and blend mode copied from [src]. */
    private fun newLayer(c: EditorController, src: Layer, bmp: Bitmap, data: LayerData): Layer =
        Layer(c.doc.newLayerId(), ArraySources.newName(c), bmp).also {
            it.restoreData(data)
            it.opacity = src.opacity
            it.blendMode = src.blendMode
            it.clipping = src.clipping
        }

    /**
     * [layer] inserted directly below [src] (`LayerStructure.below`) as ONE step
     * [ArrayLabels110.ARRAY_BEHIND_STEP]; [layer] when done, else null (its cache let go).
     */
    private fun placeBelow(c: EditorController, src: Layer, layer: Layer): Layer? {
        val label = ArrayLabels110.ARRAY_BEHIND_STEP
        var ok = false
        c.groupUndo(label) {
            val add = c.structure.placed(layer, c.structure.below(src), label) ?: return@groupUndo
            ok = true
            c.pushUndo(reselectOnUndo(src, label))
            c.pushUndo(add)
            c.queueLayerList(LayerListEvent(LayerListKind.ADDED, layer, null, label))
        }
        if (!ok) {
            layer.recycleBitmaps()
            return null
        }
        return layer
    }

    /**
     * The undo half of "the source is the active row again": recorded before the insert in the
     * same step, so its undo runs after the new layer is gone and selects [src] (the layer menu
     * may have made the array behind a row that was not active). Its redo does nothing: the
     * insert's redo selects the new layer.
     */
    private fun reselectOnUndo(src: Layer, label: String): UndoAction = LambdaAction(
        label,
        onUndo = { c ->
            val i = c.doc.indexOf(src)
            if (i >= 0 && i != c.doc.activeLayerIndex) c.structural { c.doc.activeLayerIndex = i }
        },
        onRedo = {},
    )
}
