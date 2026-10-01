package com.brushwork.paint.vector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.EditEvent
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.StrokeHook
import com.brushwork.paint.brush.StrokeInfo
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.engine.LayerDataAction
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.tools.transform.ObjectLiftProvider
import com.brushwork.paint.vector.draw.VectorStrokeCapture
import com.brushwork.paint.vector.edit.VectorEditSession
import com.brushwork.paint.vector.lift.VectorLift
import com.brushwork.paint.vector.render.VectorLayerRenderer
import com.brushwork.paint.vector.select.VectorObjectSelection
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The vector layer service of one editor (`EditorController.vectors`, v1.5 §5.4). Its API is
 * frozen; the implementation is owned by A1 (VEC-CORE): F2 writes a synchronous reference (clear
 * and replay the intersecting objects on the main thread), A1 replaces the internals (cost
 * model, async patches, spatial grid, edit sessions) behind the same API.
 *
 * Every edit keeps a vector layer's pixels (its render cache) equal to the rendering of its
 * [VectorContent], changing both in ONE undo step on the main thread (I1, I2). Undo and redo swap
 * tiles and data and never re-render.
 *
 * F2 reference: everything runs synchronously on the main thread ([isRendering] stays false);
 * dirty regions are the union bounding box of what changed (A1: 256 px tile sets); [update]
 * ignores its [ShiftHint].
 */
class VectorLayers internal constructor(private val c: EditorController) {

    /** The main thread's brush tips for re-renders. */
    private val tips = TipCache(8L shl 20)

    /**
     * New topmost objects: drawn over the cache (no re-render), tiles + LayerDataAction, one step.
     * Returns the new ids (empty when [layer] is not a vector layer or refuses: locked, hidden).
     */
    fun addObjects(layer: Layer, objects: List<VObject>, label: String): List<Long> {
        if (objects.isEmpty() || c.doc.indexOf(layer) < 0) return emptyList()
        val before = layer.dataSnapshot()
        val current = before.vector ?: return emptyList()
        if (!usable(layer)) return emptyList()
        val (content, ids) = current.plus(objects)
        val added = content.objects.subList(content.objects.size - objects.size, content.objects.size)
        val after = before.copy(vector = content)
        val area = docRect(unionBounds(added))
        c.editScope {
            if (area.isEmpty) {
                // Nothing of it lands on the canvas: the data alone.
                c.setLayerData(layer, after, label)
                return@editScope
            }
            val rec = c.beginEdit(layer, EditTarget.CONTENT).also { it.preserveData = true }
            try {
                rec.touch(area)
                val canvas = Canvas(layer.bitmap)
                canvas.clipRect(area)
                VectorLayerRenderer.render(canvas, VectorContent(objects = added), area, tips = tips)
            } catch (e: OutOfMemoryError) {
                rec.abort()
                c.toast("Not enough memory for \"$label\"")
                return@editScope
            }
            layer.restoreData(after)
            if (!c.commitEdit(rec, label, listOf(LayerDataAction(label, layer, before, after)))) {
                layer.markChanged()
                c.pushUndo(LayerDataAction(label, layer, before, after))
                c.notifyLayersChanged()
                c.queueEdit(EditEvent(layer, EditTarget.CONTENT, null, label))
            }
        }
        return if (layer.vector === content) ids else emptyList()
    }

    /**
     * Data-only append; the caller already committed the pixels inside keepLayerData (live brush
     * stroke). Call inside groupUndo. Only for vector layers (a raster layer's pixels are not a
     * rendering of objects): returns no ids otherwise.
     */
    fun appendData(layer: Layer, objects: List<VObject>, label: String): List<Long> {
        if (objects.isEmpty() || c.doc.indexOf(layer) < 0) return emptyList()
        val before = layer.dataSnapshot()
        val current = before.vector ?: return emptyList()
        val (content, ids) = current.plus(objects)
        c.setLayerData(layer, before.copy(vector = content), label)
        // Refused (locked or hidden layer): nothing was added.
        return if (layer.vector === content) ids else emptyList()
    }

    /**
     * New content; re-renders [dirty] (null = union of changed objects' tiles). Sync or async;
     * data and pixels are applied together (I1). [onDone] tells whether it was applied (true
     * also when [after] equals the content: then nothing is recorded and the layer keeps its
     * instance; false for a layer that is not a vector layer, is gone, locked or hidden).
     *
     * F2 reference: synchronous. The re-rendered area is the bounding box of [dirty], else of
     * everything that changed (objects added, removed, replaced or moved in z-order, old and new
     * bounds); every object reaching it is replayed there.
     */
    fun update(
        layer: Layer,
        after: VectorContent,
        label: String,
        dirty: List<Rect>? = null,
        /** A pure whole-pixel move of [ShiftHint.ids]: cache tiles shifted, no re-render (F2: ignored). */
        shift: ShiftHint? = null,
        onDone: (applied: Boolean) -> Unit = {},
    ) {
        val before = layer.vector
        if (before == null || c.doc.indexOf(layer) < 0) { onDone(false); return }
        // Nothing changes (also an equal copy): no re-render and no step.
        if (after === before || after == before) { onDone(true); return }
        val region = if (dirty != null) {
            val r = Rect()
            for (d in dirty) r.union(d)
            r
        } else docRect(changedBounds(before, after))
        if (!region.intersect(0, 0, c.doc.width, c.doc.height)) region.setEmpty()
        val data = layer.dataSnapshot().copy(vector = after)
        val ok = try {
            if (region.isEmpty) {
                c.updateLayerData(layer, data, label, null, EditTarget.CONTENT, null)
            } else {
                c.updateLayerData(layer, data, label, region, EditTarget.CONTENT) { canvas ->
                    VectorLayerRenderer.render(canvas, after, region, tips = tips)
                }
            }
        } catch (e: OutOfMemoryError) {
            c.toast("Not enough memory for \"$label\"")
            false
        }
        onDone(ok && layer.vector === after)
    }

    /** A pure whole-pixel translation of the objects [ids] by ([dx], [dy]) document px. */
    data class ShiftHint(val ids: Set<Long>, val dx: Int, val dy: Int)

    private var rendering by mutableStateOf(false)

    /** True while a render runs in the background (Compose state; always false in the F2 reference). */
    val isRendering: Boolean get() = rendering

    /** Everything [obj] can paint (document px, including the brush radius). */
    fun paintBounds(obj: VObject): RectF = VectorOps.bounds(obj)

    /**
     * The topmost object of [layer] under [p] within [tolDoc] px; [below] = only objects under
     * that id, and when none is, from the top again (repeated taps cycle through overlaps).
     */
    fun hitTest(layer: Layer, p: Vec2, tolDoc: Float, below: Long? = null): VObject? {
        val content = layer.vector ?: return null
        val objs = content.objects
        if (objs.isEmpty()) return null
        var start = objs.lastIndex
        val from = below?.let { content.indexOf(it) } ?: -1
        if (from >= 0) start = from - 1
        for (i in start downTo 0) if (VectorOps.hit(objs[i], p, tolDoc)) return objs[i]
        if (from >= 0) {
            for (i in objs.lastIndex downTo from) if (VectorOps.hit(objs[i], p, tolDoc)) return objs[i]
        }
        return null
    }

    /** Ids of the objects of [layer] that [sel] touches. */
    fun touching(layer: Layer, sel: Selection): Set<Long> {
        val content = layer.vector ?: return emptySet()
        return VectorOps.touching(content, sel)
    }

    /**
     * Prepares an edit preview of the objects [ids] (hole + floating) and installs it as the
     * render override (with no [VectorEditSession.inner]: a painting tool's live stroke is
     * adopted later through [VectorEditSession.adoptInner]); [onReady] gets null when refused
     * (not a vector layer, none of the ids exist, locked or hidden layer, no memory). Ids that no
     * longer exist are left out of the session's [VectorEditSession.ids].
     *
     * F2 reference: rendered synchronously, [onReady] runs before this returns (A1 may call it
     * later). Lifting every object uses a cropped copy of the cache as the floating bitmap and an
     * empty hole. Memory guard: hole + floating within a heap / 8 budget (else both are rendered
     * smaller), floating at most 2048 px.
     */
    fun beginEdit(layer: Layer, ids: Set<Long>, onReady: (VectorEditSession?) -> Unit) {
        val content = layer.vector
        if (content == null || c.doc.indexOf(layer) < 0 || ids.isEmpty()) { onReady(null); return }
        val present = ids.filterTo(LinkedHashSet()) { content.byId(it) != null }
        if (present.isEmpty() || !usable(layer)) { onReady(null); return }
        val edited = content.objects.filter { it.id in present }
        val all = present.size == content.objects.size
        val docW = c.doc.width
        val docH = c.doc.height
        // The edited objects' box, kept within a document's size around the canvas.
        val floatingRect = roundOut(unionBounds(edited))
        if (!floatingRect.intersect(-docW, -docH, 2 * docW, 2 * docH)) floatingRect.setEmpty()
        val holeRect = Rect(floatingRect)
        if (!holeRect.intersect(0, 0, docW, docH)) holeRect.setEmpty()

        val budget = Runtime.getRuntime().maxMemory() / 8
        val fw = floatingRect.width().toLong()
        val fh = floatingRect.height().toLong()
        val holeArea = if (all) 0L else holeRect.width().toLong() * holeRect.height()
        var fScale = if (fw <= 0 || fh <= 0) 1f else min(1f, MAX_FLOATING / max(fw, fh).toFloat())
        var hScale = 1f
        val need = 4L * (holeArea + (fw * fh * fScale * fScale).toLong())
        if (need > budget && need > 0) {
            val s = sqrt(budget.toDouble() / need).toFloat()
            fScale *= s
            hScale = s
        }

        var floating: Bitmap? = null
        var hole: Bitmap? = null
        try {
            if (!floatingRect.isEmpty) {
                val bw = max(1, ceil(fw * fScale).toInt())
                val bh = max(1, ceil(fh * fScale).toInt())
                floating = BitmapUtils.createLayerBitmap(bw, bh).also { b ->
                    val cv = Canvas(b)
                    cv.scale(fScale, fScale)
                    cv.translate(-floatingRect.left.toFloat(), -floatingRect.top.toFloat())
                    if (all) {
                        // Every object is lifted: the cache is exactly their rendering.
                        cv.drawBitmap(layer.bitmap, 0f, 0f, if (fScale == 1f) null else Paint(Paint.FILTER_BITMAP_FLAG))
                    } else {
                        VectorLayerRenderer.render(cv, VectorContent(objects = edited), floatingRect, tips = tips)
                    }
                }
            }
            if (!all && !holeRect.isEmpty) {
                val others = content.without(present)
                if (others.objects.isNotEmpty()) {
                    val bw = max(1, ceil(holeRect.width() * hScale).toInt())
                    val bh = max(1, ceil(holeRect.height() * hScale).toInt())
                    hole = BitmapUtils.createLayerBitmap(bw, bh).also { b ->
                        val cv = Canvas(b)
                        cv.scale(hScale, hScale)
                        cv.translate(-holeRect.left.toFloat(), -holeRect.top.toFloat())
                        VectorLayerRenderer.render(cv, others, holeRect, tips = tips)
                    }
                }
            }
        } catch (e: OutOfMemoryError) {
            floating?.recycle()
            hole?.recycle()
            c.toast("Not enough memory to edit these objects")
            onReady(null)
            return
        }
        val session = VectorEditSession(c, layer, present, floating, floatingRect, fScale, holeRect, hole, hScale)
        // Installed on its own: a painting tool's live stroke is adopted by the caller
        // (VectorEditSession.adoptInner), never whatever override happens to be installed.
        c.renderOverride = session
        c.invalidateDoc(null)
        onReady(session)
    }

    // ------------------------------------------------------------------ object selection
    // Runtime only, not history; cleared when the layer goes or its ids vanish.

    private var selLayer by mutableStateOf<Layer?>(null)
    private var selIds by mutableStateOf<Set<Long>>(emptySet())

    /** The vector layer whose objects are selected (null when none is). */
    val selectedLayer: Layer?
        get() {
            c.layersVersion // re-read when layers change (deleted, rasterized)
            val l = selLayer ?: return null
            return if (l.isVectorLayer && c.doc.indexOf(l) >= 0) l else null
        }

    /** The selected objects of [selectedLayer] that still exist (Compose state). */
    val selectedIds: Set<Long>
        get() {
            val ids = selIds
            if (ids.isEmpty()) return ids
            val content = selectedLayer?.vector ?: return emptySet()
            return if (ids.all { content.byId(it) != null }) ids else ids.filterTo(HashSet()) { content.byId(it) != null }
        }

    /** Selects the objects [ids] of [layer] (null or no ids = nothing selected). */
    fun setSelection(layer: Layer?, ids: Set<Long>) {
        if (layer == null || ids.isEmpty()) {
            selLayer = null
            selIds = emptySet()
        } else {
            selLayer = layer
            selIds = ids.toSet()
        }
        c.invalidateOverlay()
    }

    // ------------------------------------------------------------------ seams
    // Lines written by the lead, delegating to area-owned objects; A1 must keep them.

    /** Decides what a starting brush stroke does on a vector layer (A3). */
    fun strokeHook(info: StrokeInfo): StrokeHook = VectorStrokeCapture.hookFor(c, info)

    /** Lifts vector objects for the Transform tool (A2). */
    val liftProvider: ObjectLiftProvider get() = VectorLift.provider(c)

    /** Lasso / Select shape results on a vector layer select objects (A2). True when handled. */
    fun selectObjects(sel: Selection, mode: SelectionMode): Boolean =
        selectObjectsHook?.invoke(sel, mode) ?: VectorObjectSelection.select(c, sel, mode)

    /** Test seam: replaces [selectObjects]' delegate (null = the A2 object selection). */
    internal var selectObjectsHook: ((Selection, SelectionMode) -> Boolean)? = null

    /** Object selection feedback (A2). */
    fun drawOverlay(canvas: Canvas, t: ViewTransform) = VectorObjectSelection.drawOverlay(c, canvas, t)

    /** The editor closes: drop every cache and background job. */
    fun dispose() {
        selLayer = null
        selIds = emptySet()
        rendering = false
        tips.clear()
    }

    // ------------------------------------------------------------------ helpers

    /** False (with the controller's message) when [layer] is locked or hidden. */
    private fun usable(layer: Layer): Boolean {
        if (layer.locked) { c.toast("Layer \"${layer.name}\" is locked"); return false }
        if (!layer.visible) { c.toast("Layer \"${layer.name}\" is hidden"); return false }
        return true
    }

    private fun unionBounds(objects: List<VObject>): RectF {
        val r = RectF()
        for (o in objects) {
            val b = VectorOps.bounds(o)
            if (!b.isEmpty) r.union(b)
        }
        return r
    }

    /**
     * Bounds of everything that differs between [before] and [after]: objects only in one of
     * them, objects replaced by another value, and objects whose order among the common ones
     * changed (old and new bounds).
     */
    private fun changedBounds(before: VectorContent, after: VectorContent): RectF {
        val r = RectF()
        fun add(o: VObject) { val b = VectorOps.bounds(o); if (!b.isEmpty) r.union(b) }
        val oldById = HashMap<Long, VObject>(before.objects.size * 2)
        for (o in before.objects) oldById[o.id] = o
        val newById = HashMap<Long, VObject>(after.objects.size * 2)
        for (o in after.objects) newById[o.id] = o
        for (o in before.objects) {
            val n = newById[o.id]
            if (n == null) add(o) else if (n !== o && n != o) { add(o); add(n) }
        }
        for (o in after.objects) if (o.id !in oldById) add(o)
        // Z-order: the common objects in each order; those whose rank changed are repainted.
        val oldOrder = before.objects.filter { it.id in newById }.map { it.id }
        val newOrder = after.objects.filter { it.id in oldById }.map { it.id }
        if (oldOrder != newOrder) {
            for (i in oldOrder.indices) {
                if (oldOrder[i] != newOrder.getOrNull(i)) {
                    oldById[oldOrder[i]]?.let { add(it) }
                    newById[oldOrder[i]]?.let { add(it) }
                }
            }
        }
        return r
    }

    private fun roundOut(r: RectF): Rect =
        if (r.isEmpty) Rect() else Rect(floor(r.left).toInt(), floor(r.top).toInt(), ceil(r.right).toInt(), ceil(r.bottom).toInt())

    /** [r] rounded out and clipped to the document (empty when outside). */
    private fun docRect(r: RectF): Rect {
        val out = roundOut(r)
        if (out.isEmpty || !out.intersect(0, 0, c.doc.width, c.doc.height)) return Rect()
        return out
    }

    private companion object {
        /** Largest side of a floating preview bitmap (px). */
        const val MAX_FLOATING = 2048f
    }
}
