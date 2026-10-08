package com.brushwork.paint.array

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import com.brushwork.paint.ColorModeOps
import com.brushwork.paint.EditorController
import com.brushwork.paint.LayerListEvent
import com.brushwork.paint.LayerListKind
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.model.ArrayLayout
import com.brushwork.paint.model.ArrayPixels
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerArray
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.array.ArrayTool
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.geom.ObjectIndex
import com.brushwork.paint.vector.render.VectorLayerRenderer
import com.brushwork.paint.vector.select.PendingRenders

/**
 * v1.7 (item 3, §3.3; area E): the array operations. Each user action is ONE undo step, data and
 * pixels together (I1, I14):
 *
 * - **Making an array** ("Array"): [fromSelection] moves the selected pixels of a raster layer
 *   into a new layer "Array N" directly above (opacity, blend and clipping copied; the source
 *   loses them in the same step, as the selection bar's Cut does); [fromObjects] moves vector
 *   objects into a new vector layer directly above; [fromLayer] arrays a whole text, shape or
 *   vector layer in place. A new array is `ArraySpec()`: count 3 in Line mode, relative X 100 %.
 *   The new layer goes in through `controller.structure` (rule S) and the Array tool opens.
 * - **Editing** ("Edit array", [edit]), **applying** ("Apply array", [apply]) and **removing**
 *   ("Remove array", [remove]), on every source kind.
 * - **"Edit source pixels" / "Finish source edit"** ([editSource], [finishSource]): a raster
 *   source shown alone at its place for painting (painting then does not bake, I14), and taken
 *   back as the layer's non-transparent pixels.
 *
 * Refusals come with their message: folders ("Folders can't be arrayed"), adjustment layers,
 * linked text frames and a plain raster layer without a selection (§3.3 a); locked or hidden
 * layers (the controller's messages); and the memory rule: an array's source pixels count toward
 * `effectiveLayerCount`, so a new array is refused with "Not enough memory for another layer"
 * when it would pass `maxLayers`.
 *
 * Pending tool work is committed first (the tool is paused around each operation, as the
 * controller's layer operations do). Main thread.
 */
object ArrayOps {
    /** Refusal on an adjustment layer (it has no pixels or objects to repeat). */
    const val ADJUSTMENT_REFUSAL = "Adjustment layers can't be arrayed"

    /** Refusal when making an array would pass `maxLayers` (the existing message, §3.3 b). */
    const val MEMORY_REFUSAL = "Not enough memory for another layer"

    /** The selection covers no painted pixel of the layer. */
    const val EMPTY_SELECTION = "The selection holds no painted pixels"

    /** The selection (or the object list) holds none of the layer's objects. */
    const val NO_OBJECTS = "The selection touches no objects"

    /** "Finish source edit" on a layer left without pixels. */
    const val EMPTY_SOURCE = "The source is empty: paint it, or remove the array"

    /** "Edit source pixels" on an array whose source is text, a shape or vector objects. */
    const val NOT_PIXELS = "Only an array made from a selection has source pixels"

    /** Refusal on an alpha-locked layer (its pixels can't be moved out). */
    fun alphaLocked(name: String) = "Can't make an array: transparency is locked on \"$name\""

    /** A layer with nothing to repeat. */
    fun emptyLayer(name: String) = "\"$name\" has nothing to array"

    /**
     * "Array from selection" (the selection bar's "Array"): on a raster layer the selected pixels
     * move into a new layer "Array N" directly above, whose array repeats them (one step "Array",
     * the source's pixel edit and the new layer; the selection stays). On a vector layer the
     * objects the selection touches move ([fromObjects]); on a text or shape layer the layer as a
     * whole is arrayed ([fromLayer]: the text or shape stays editable). True when it was done (or
     * is on its way, for a vector layer rendering in the background).
     */
    fun fromSelection(c: EditorController): Boolean {
        val src = c.activeLayer
        refusal(src)?.let { c.toast(it); return false }
        val sel = c.selection
        return when {
            src.isVectorLayer -> if (sel == null) fromLayer(c, src) else objectsArray(c, src, null)
            src.isTextLayer || src.isShapeLayer -> fromLayer(c, src)
            sel == null -> { c.toast(ArrayLabels.PLAIN_REFUSAL); false }
            else -> pixelArray(c, src)
        }
    }

    /**
     * "Array from objects" (the vector object bar's "Array"): the objects [objectIds] of the
     * vector layer whose objects are selected (else the active layer) move, ids kept, into a new
     * vector layer directly above that repeats them (one step "Array"). The object selection is
     * cleared.
     */
    fun fromObjects(c: EditorController, objectIds: Set<Long>): Boolean {
        val layer = c.vectors.selectedLayer?.takeIf { objectIds.isNotEmpty() } ?: c.activeLayer
        refusal(layer)?.let { c.toast(it); return false }
        if (!layer.isVectorLayer) { c.toast(ArrayLabels.PLAIN_REFUSAL); return false }
        if (objectIds.isEmpty()) { c.toast(NO_OBJECTS); return false }
        return objectsArray(c, layer, objectIds)
    }

    /**
     * Layer ⋮ "Array…": [layer] as a whole becomes the source of a live array in place (one step
     * "Array"): its text, shape or vector objects stay editable with their own tools. A layer
     * that already has an array opens it in the Array tool (no step); a raster layer takes the
     * selection ([fromSelection]) or is refused with "Select some pixels, or pick a text, shape
     * or vector layer".
     */
    fun fromLayer(c: EditorController, layer: Layer): Boolean {
        if (c.doc.indexOf(layer) < 0) return false
        refusal(layer)?.let { c.toast(it); return false }
        if (layer.array != null) { openTool(c, layer); return true }
        if (!layer.isVectorLayer && !layer.isTextLayer && !layer.isShapeLayer) {
            if (c.selection == null) { c.toast(ArrayLabels.PLAIN_REFUSAL); return false }
            if (layer !== c.activeLayer) c.selectLayer(layer)
            return pixelArray(c, layer)
        }
        if (!c.checkUsable(layer)) return false
        val done = paused(c) {
            val before = layer.dataSnapshot()
            val array = LayerArray(ArraySpec())
            if (before.vector != null) {
                if (before.vector.objects.isEmpty()) { c.toast(emptyLayer(layer.name)); return@paused false }
                c.vectors.updateArray(layer, array, ArrayLabels.BUTTON)
                true
            } else {
                val after = before.copy(array = array)
                val draw = ArraySources.sourceDraw(after, c.doc.colorMode, c.doc.width, c.doc.height)
                if (draw == null) { c.toast(emptyLayer(layer.name)); return@paused false }
                c.updateLayerData(layer, after, ArrayLabels.BUTTON, ArraySources.sourceRect(after), draw = draw)
            }
        }
        if (done) openTool(c, layer)
        return done
    }

    /**
     * "Edit array": [layer]'s array takes [spec] (sanitized; "Edit source pixels" mode is kept as
     * it is) and the copies re-render, as ONE step "Edit array". True without a step when nothing
     * changes. While the source is being edited the spec changes alone (the layer keeps showing
     * the source being painted).
     */
    fun edit(c: EditorController, layer: Layer, spec: ArraySpec): Boolean = edit(c, layer, spec, ArrayLabels.EDIT)

    /**
     * [edit] as a step named [label] (the tool's handles and the sheet use "Edit array").
     * [onDone] tells when the new copies are on the layer (a vector layer may render in the
     * background; every other kind before this returns) and whether the edit was applied.
     */
    internal fun edit(c: EditorController, layer: Layer, spec: ArraySpec, label: String, onDone: (applied: Boolean) -> Unit = {}): Boolean {
        val a = layer.array ?: run { onDone(false); return false }
        val s = spec.sanitized().let { if (it.editingSource != a.spec.editingSource) it.copy(editingSource = a.spec.editingSource) else it }
        if (s == a.spec) { onDone(true); return true }
        return paused(c) { setArray(c, layer, a.copy(spec = s), label, onDone) }
    }

    /**
     * "Apply array" (one step): a vector array's copies become real objects (new ids; refused
     * above [ArraySpec.MAX_INSTANCES] objects with "Too many copies to apply: lower the count"),
     * a shape array becomes a vector layer with one shape per copy, a raster array becomes plain
     * pixels. A text array becomes pixels after the confirmation "Apply turns the text into
     * pixels": the layer is selected in the Array tool, which asks (true: the question is up).
     */
    fun apply(c: EditorController, layer: Layer): Boolean {
        val d = layer.dataSnapshot()
        if (ArraySources.kindOf(d) == ArraySources.Kind.TEXT) {
            if (c.doc.indexOf(layer) < 0 || !c.checkUsable(layer)) return false
            openTool(c, layer)
            val tool = c.tools[ToolId.ARRAY] as? ArrayTool ?: return false
            tool.askApplyText(layer)
            return true
        }
        return applyNow(c, layer)
    }

    /** [apply] without the text confirmation (the Array tool's dialog, once confirmed). */
    internal fun applyNow(c: EditorController, layer: Layer): Boolean {
        if (c.doc.indexOf(layer) < 0 || layer.array == null) return false
        return paused(c) {
            val before = layer.dataSnapshot()
            val a = before.array ?: return@paused false
            when (ArraySources.kindOf(before)) {
                ArraySources.Kind.VECTOR -> applyVector(c, layer, before, a)
                ArraySources.Kind.SHAPE -> applyShape(c, layer, before, a)
                ArraySources.Kind.TEXT -> c.updateLayerData(layer, before.copy(text = null, array = null), ArrayLabels.APPLY, null, draw = null)
                ArraySources.Kind.PIXELS -> applyPixels(c, layer, before, a)
                null -> c.updateLayerData(layer, before.copy(array = null), ArrayLabels.APPLY, null, draw = null)
            }
        }
    }

    /** "Remove array" (one step): the copies go and the source stays, editable as before. */
    fun remove(c: EditorController, layer: Layer): Boolean {
        if (c.doc.indexOf(layer) < 0 || layer.array == null) return false
        return paused(c) { setArray(c, layer, null, ArrayLabels.REMOVE) }
    }

    /**
     * "Edit source pixels" (one step): a raster array shows its source alone at its place, and
     * pixel edits change the source without baking (I14) until [finishSource]. True without a
     * step when already editing it.
     */
    fun editSource(c: EditorController, layer: Layer): Boolean {
        val a = layer.array ?: return false
        if (a.pixels == null) { c.toast(NOT_PIXELS); return false }
        if (a.spec.editingSource) return true
        if (c.doc.indexOf(layer) < 0) return false
        val editing = a.copy(spec = a.spec.copy(editingSource = true))
        return paused(c) {
            c.updateLayerData(layer, layer.dataSnapshot().copy(array = editing), ArrayLabels.EDIT_SOURCE, Rect()) { cv -> ArrayDraw.drawPixels(cv, editing) }
        }
    }

    /**
     * "Finish source edit" (one step; also run when the Array tool opens on the layer): the
     * layer's non-transparent pixels become the array's new source and the copies re-render.
     * Refused when the layer is empty or the new source would pass the memory rule.
     */
    fun finishSource(c: EditorController, layer: Layer): Boolean {
        val a = layer.array ?: return false
        if (!a.spec.editingSource || c.doc.indexOf(layer) < 0) return false
        if (!c.checkUsable(layer)) return false
        return paused(c) {
            val px = try { ArraySources.layerPixels(layer.bitmap) } catch (e: OutOfMemoryError) { c.toast(MEMORY_REFUSAL); return@paused false }
            if (px == null) { c.toast(EMPTY_SOURCE); return@paused false }
            if (!ArraySources.hasRoom(c, 0, px.bytes, freedBytes = a.pixels?.bytes ?: 0L)) {
                px.bitmap.recycle()
                c.toast(MEMORY_REFUSAL)
                return@paused false
            }
            val done = finishInto(c, layer, a.spec.copy(editingSource = false), px, ArrayLabels.FINISH_SOURCE)
            if (!done) px.bitmap.recycle()
            done
        }
    }

    // ------------------------------------------------------------------ making arrays

    /** Why [layer] can't be arrayed (folders, adjustment layers, linked text frames), or null. */
    internal fun refusal(layer: Layer): String? = when {
        layer.isFolder -> ArrayLabels.FOLDER_REFUSAL
        layer.isAdjustmentLayer -> ADJUSTMENT_REFUSAL
        ArraySources.isLinkedText(layer) -> ArrayLabels.LINKED_REFUSAL
        else -> null
    }

    /** The selected pixels of [src] (the active layer) into a new "Array N" layer above it. */
    private fun pixelArray(c: EditorController, src: Layer): Boolean {
        if (!c.checkUsable(src)) return false
        if (src.alphaLocked) { c.toast(alphaLocked(src.name)); return false }
        if (!ArraySources.hasRoom(c, 1, 0L)) { c.toast(MEMORY_REFUSAL); return false }
        val layer = paused(c) {
            val sel = c.selection ?: return@paused null
            makePixelArray(c, src, sel)
        } ?: return false
        openTool(c, layer)
        return true
    }

    private fun makePixelArray(c: EditorController, src: Layer, sel: Selection): Layer? {
        val doc = c.doc
        var px: ArrayPixels? = null
        var bmp: Bitmap? = null
        try {
            px = ArraySources.selectedPixels(src.bitmap, sel)
            if (px == null) { c.toast(EMPTY_SELECTION); return null }
            if (!ArraySources.hasRoom(c, 1, px.bytes)) { c.toast(MEMORY_REFUSAL); return null }
            if (doc.colorMode != ColorMode.RGB) ColorModeOps.constrain(px.bitmap, Rect(0, 0, px.bitmap.width, px.bitmap.height), doc.colorMode)
            val array = LayerArray(ArraySpec(), px)
            bmp = BitmapUtils.createLayerBitmap(doc.width, doc.height)
            ArrayDraw.drawPixels(Canvas(bmp), array)
            if (doc.colorMode != ColorMode.RGB) ColorModeOps.constrain(bmp, doc.bounds, doc.colorMode)
            val layer = Layer(doc.newLayerId(), ArraySources.newName(c), bmp).also {
                it.array = array
                it.opacity = src.opacity
                it.blendMode = src.blendMode
                it.clipping = src.clipping
            }
            val placed = c.editScope {
                val rec = c.beginEdit(src, EditTarget.CONTENT)
                rec.touch(sel.bounds)
                Canvas(src.bitmap).apply {
                    clipRect(sel.bounds)
                    drawBitmap(sel.mask, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
                }
                if (doc.colorMode != ColorMode.RGB) ColorModeOps.constrain(src.bitmap, sel.bounds, doc.colorMode)
                // v1.7 (rule S): directly above the source at its level (without folders: the
                // v1.6 AddLayerAction), recorded in the same step as the pixel edit.
                val add = c.structure.placed(layer, c.structure.above(src), ArrayLabels.BUTTON)
                if (add == null) {
                    rec.abort()
                    false
                } else {
                    if (!c.commitEdit(rec, ArrayLabels.BUTTON, listOf(add))) c.pushUndo(add)
                    c.queueLayerList(LayerListEvent(LayerListKind.ADDED, layer, null, ArrayLabels.BUTTON))
                    true
                }
            }
            if (!placed) return null
            px = null
            bmp = null
            return layer
        } catch (e: OutOfMemoryError) {
            c.toast(MEMORY_REFUSAL)
            return null
        } finally {
            px?.bitmap?.recycle()
            bmp?.recycle()
        }
    }

    /**
     * The objects [ids] of the vector layer [layer] (null: those the selection touches) into a
     * new vector array layer above it: ONE step (the source re-rendered without them, and the new
     * layer), rendered on the main thread so both land in the same step.
     */
    private fun objectsArray(c: EditorController, layer: Layer, ids: Set<Long>?): Boolean {
        if (!c.checkUsable(layer)) return false
        if (!ArraySources.hasRoom(c, 1, 0L)) { c.toast(MEMORY_REFUSAL); return false }
        val made = paused(c) {
            c.vectors.flushPending()
            val content = layer.vector ?: return@paused null
            val sel = c.selection
            val picked = ids ?: if (sel != null) VectorOps.touching(content, sel) else emptySet()
            val objects = content.objects.filter { it.id in picked }
            if (objects.isEmpty()) { c.toast(NO_OBJECTS); return@paused null }
            makeObjectsArray(c, layer, content, objects)
        } ?: return false
        c.vectors.setSelection(null, emptySet())
        openTool(c, made)
        return true
    }

    private fun makeObjectsArray(c: EditorController, layer: Layer, content: VectorContent, objects: List<VObject>): Layer? {
        val doc = c.doc
        val ids = objects.mapTo(HashSet()) { it.id }
        val data = LayerData(vector = VectorContent(objects = objects, nextId = content.nextId), array = LayerArray(ArraySpec()))
        val bmp = try {
            BitmapUtils.createLayerBitmap(doc.width, doc.height).also { b ->
                renderTiles(Canvas(b), ArrayDraw.effectiveVector(data) ?: data.vector!!, doc.bounds, doc.bounds)
                if (doc.colorMode != ColorMode.RGB) ColorModeOps.constrain(b, doc.bounds, doc.colorMode)
            }
        } catch (e: OutOfMemoryError) {
            c.toast(MEMORY_REFUSAL)
            return null
        }
        val newLayer = Layer(doc.newLayerId(), ArraySources.newName(c), bmp).also {
            it.restoreData(data)
            it.opacity = layer.opacity
            it.blendMode = layer.blendMode
            it.clipping = layer.clipping
        }
        val before = layer.dataSnapshot()
        val after = before.copy(vector = content.without(ids))
        // What the source's cache must change: the moved objects (and every copy of its own
        // array), on whole render tiles so the re-render equals a full one.
        val area = Rect()
        for (o in objects) area.union(ArraySources.rectOf(VectorOps.bounds(o)))
        for (d in listOf(before, after)) area.union(ArraySources.rectOf(ArrayDraw.cacheBounds(d)))
        area.inset(-2, -2)
        val region = tileAligned(area, doc.width, doc.height)
        val view = ArrayDraw.effectiveVector(after) ?: after.vector!!
        var ok = false
        c.groupUndo(ArrayLabels.BUTTON) {
            if (c.structure.insert(newLayer, c.structure.above(layer), ArrayLabels.BUTTON)) {
                ok = true
                if (!region.isEmpty) c.updateLayerData(layer, after, ArrayLabels.BUTTON, region) { cv -> renderTiles(cv, view, region, doc.bounds) }
                else c.updateLayerData(layer, after, ArrayLabels.BUTTON, null, draw = null)
            }
        }
        if (!ok) { bmp.recycle(); return null }
        return newLayer
    }

    // ------------------------------------------------------------------ edit, apply, remove

    /**
     * Sets [layer]'s array to [array] (null removes it) and re-renders, as ONE step [label]: a
     * vector layer through `vectors.updateArray`; text and shape sources drawn again by their
     * own renderer once per copy (`ArrayDraw.drawWithArray` inside `updateLayerData`); a raster
     * source with `ArrayDraw.drawPixels`, or alone at its place when removed. While a raster
     * source is being edited only the data changes (the layer shows the source being painted).
     */
    private fun setArray(c: EditorController, layer: Layer, array: LayerArray?, label: String, onDone: (applied: Boolean) -> Unit = {}): Boolean {
        if (ArraySources.kindOf(layer.dataSnapshot()) == ArraySources.Kind.VECTOR) {
            if (!c.checkUsable(layer)) { onDone(false); return false }
            c.vectors.updateArray(layer, array, label, onDone = onDone)
            return true
        }
        val done = setArrayNow(c, layer, array, label)
        onDone(done)
        return done
    }

    /** [setArray] of a text, shape or raster source: drawn on the main thread, before it returns. */
    private fun setArrayNow(c: EditorController, layer: Layer, array: LayerArray?, label: String): Boolean {
        val before = layer.dataSnapshot()
        val after = before.copy(array = array)
        val doc = c.doc
        return when (ArraySources.kindOf(before)) {
            ArraySources.Kind.VECTOR -> false
            ArraySources.Kind.TEXT, ArraySources.Kind.SHAPE -> {
                val draw = ArraySources.sourceDraw(after, doc.colorMode, doc.width, doc.height) ?: return false
                val dirty = ArraySources.sourceRect(before).also { it.union(ArraySources.sourceRect(after)) }
                c.updateLayerData(layer, after, label, dirty, draw = draw)
            }
            ArraySources.Kind.PIXELS -> {
                val old = before.array!!
                val px = old.pixels!!
                when {
                    // The layer shows the source being painted: only the data changes.
                    old.spec.editingSource -> c.updateLayerData(layer, after, label, null, draw = null)
                    array == null -> c.updateLayerData(layer, after, label, Rect(px.left, px.top, px.left + px.bitmap.width, px.top + px.bitmap.height)) { cv ->
                        cv.drawBitmap(px.bitmap, px.left.toFloat(), px.top.toFloat(), null)
                    }
                    else -> c.updateLayerData(layer, after, label, Rect()) { cv -> ArrayDraw.drawPixels(cv, array) }
                }
            }
            null -> c.updateLayerData(layer, after, label, null, draw = null)
        }
    }

    /** A vector array's copies as real objects: the expanded content with new ids for the copies. */
    private fun applyVector(c: EditorController, layer: Layer, before: LayerData, a: LayerArray): Boolean {
        val v = before.vector ?: return false
        if (!c.checkUsable(layer)) return false
        val n = if (v.objects.isEmpty()) 1 else ArrayLayout.matrices(a.spec, ObjectIndex.of(v).unionBounds()).size
        if (n.toLong() * v.objects.size > ArraySpec.MAX_INSTANCES) { c.toast(ArrayLabels.TOO_MANY); return false }
        val eff = ArrayDraw.effectiveVector(before) ?: v
        val sourceIds = v.objects.mapTo(HashSet()) { it.id }
        var next = v.nextId
        val objects = eff.objects.map { o -> if (o.id in sourceIds) o else o.withId(next++) }
        c.vectors.updateArray(layer, null, ArrayLabels.APPLY, after = v.copy(objects = objects, nextId = next))
        return true
    }

    /** A shape array becomes a vector layer: one shape per copy (copy N − 1 first, the source on top). */
    private fun applyShape(c: EditorController, layer: Layer, before: LayerData, a: LayerArray): Boolean {
        val o = before.shape?.let { ShapeCodec.decode(it) } ?: return false
        val source = VShape(0L, shape = o)
        val ms = ArrayLayout.matrices(a.spec, VectorOps.bounds(source))
        val objects = ArrayList<VObject>(ms.size)
        for (k in ms.size - 1 downTo 1) {
            if (ms[k].any { !it.isFinite() }) continue
            objects += VectorOps.transformed(source, ms[k])
        }
        objects += source
        val content = VectorContent.EMPTY.plus(objects).first
        val after = before.copy(shape = null, vector = content, array = null)
        val area = ArraySources.rectOf(ArrayDraw.cacheBounds(before)).also { it.union(ArraySources.sourceRect(before)); it.inset(-2, -2) }
        val region = tileAligned(area, c.doc.width, c.doc.height)
        if (region.isEmpty) return c.updateLayerData(layer, after, ArrayLabels.APPLY, null, draw = null)
        return c.updateLayerData(layer, after, ArrayLabels.APPLY, region) { cv -> renderTiles(cv, content, region, c.doc.bounds) }
    }

    /**
     * A raster array becomes plain pixels: only the data changes (the cache already shows the
     * copies); while its source is being edited, the copies of the painted source are drawn first.
     */
    private fun applyPixels(c: EditorController, layer: Layer, before: LayerData, a: LayerArray): Boolean {
        val after = before.copy(array = null)
        if (!a.spec.editingSource) return c.updateLayerData(layer, after, ArrayLabels.APPLY, null, draw = null)
        val px = ArraySources.layerPixels(layer.bitmap) ?: return c.updateLayerData(layer, after, ArrayLabels.APPLY, null, draw = null)
        try {
            val baked = LayerArray(a.spec.copy(editingSource = false), px)
            val dirty = ArraySources.rectOf(ArrayDraw.cacheBounds(LayerData(array = baked))).also { it.inset(-1, -1) }
            return c.updateLayerData(layer, after, ArrayLabels.APPLY, dirty) { cv -> ArrayDraw.drawPixels(cv, baked) }
        } finally {
            // (Drawn into the cache, never published in data.)
            px.bitmap.recycle()
        }
    }

    /** Sets [px] as [layer]'s source with [spec] and re-renders the copies: one step [label]. */
    private fun finishInto(c: EditorController, layer: Layer, spec: ArraySpec, px: ArrayPixels, label: String): Boolean {
        val array = LayerArray(spec, px)
        val dirty = Rect(px.left, px.top, px.left + px.bitmap.width, px.top + px.bitmap.height)
        return c.updateLayerData(layer, layer.dataSnapshot().copy(array = array), label, dirty) { cv -> ArrayDraw.drawPixels(cv, array) }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Runs [block] with the current tool paused (its pending work committed first, then the tool
     * re-targets the layer), as the controller's layer operations do (its private
     * `withToolPaused`, step for step): object edits queued behind a vector render land first,
     * and a live edit still on its way records its step before the operation reads the layers.
     */
    internal inline fun <T> paused(c: EditorController, block: () -> T): T {
        if (PendingRenders.hasWaiting(c)) c.settleVectorWork()
        c.currentTool.onDeactivate()
        if (c.editDepth == 0) c.flushDeferredSteps()
        try {
            return block()
        } finally {
            c.currentTool.onActivate()
        }
    }

    /** Selects [layer] and the Array tool. */
    internal fun openTool(c: EditorController, layer: Layer) {
        if (c.doc.indexOf(layer) < 0) return
        if (c.activeLayer !== layer) c.selectLayer(layer)
        c.selectTool(ToolId.ARRAY)
        (c.tools[ToolId.ARRAY] as? ArrayTool)?.sheetOpen = layer.array != null
        c.invalidateOverlay()
    }

    /** [r] grown to whole render tiles (`VectorLayerRenderer.TILE`, document-anchored) within the document. */
    internal fun tileAligned(r: Rect, w: Int, h: Int): Rect {
        if (r.isEmpty) return Rect()
        val t = VectorLayerRenderer.TILE
        val out = Rect(
            Math.floorDiv(r.left, t) * t, Math.floorDiv(r.top, t) * t,
            (Math.floorDiv(r.right - 1, t) + 1) * t, (Math.floorDiv(r.bottom - 1, t) + 1) * t,
        )
        return if (out.intersect(0, 0, w, h)) out else Rect()
    }

    /** Renders [content] within [region] (whole tiles), dabs cut at [document], as a vector layer's cache is rendered. */
    internal fun renderTiles(canvas: Canvas, content: VectorContent, region: Rect, document: Rect) {
        val tips = TipCache(8L shl 20)
        try {
            VectorLayerRenderer.render(canvas, content, region, tips = tips, document = document)
        } finally {
            tips.clear()
        }
    }
}
