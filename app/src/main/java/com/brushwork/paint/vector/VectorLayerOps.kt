package com.brushwork.paint.vector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import com.brushwork.paint.ColorModeOps
import com.brushwork.paint.EditEvent
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.engine.LayerDataAction
import com.brushwork.paint.engine.RemoveLayerAction
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.select.MarchingSquares
import com.brushwork.paint.vector.geom.ObjectIndex
import com.brushwork.paint.vector.geom.TileSet
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Layer-level operations on vector layers (v1.5 §5.4 and §4.9's table; API frozen, owned by A1).
 * The controller and the layers window delegate to these; false / null means "not handled": the
 * caller falls back to today's raster behaviour (which turns the layer into a raster layer,
 * undoably). Main thread only. Each operation is ONE undo step.
 */
object VectorLayerOps {
    /** Simplification tolerance of traced selection outlines (document px). */
    private const val TRACE_EPSILON = 0.75f

    /** Turns the vector layer into a plain raster layer: its pixels stay, its objects go (one step "Rasterize vector layer"; toast). */
    fun rasterize(c: EditorController, layer: Layer): Boolean {
        if (!layer.isVectorLayer || c.doc.indexOf(layer) < 0) return false
        c.vectors.flushPending()
        val before = layer.dataSnapshot()
        c.setLayerData(layer, before.copy(vector = null), "Rasterize vector layer")
        if (!layer.isVectorLayer) c.toast("\"${layer.name}\" is now a regular layer (undo to get its objects back)")
        // Refused (locked / hidden): the controller said why; handled either way.
        return true
    }

    /**
     * Merges the vector layer [upper] into the vector layer [lower] right below it by
     * concatenating their objects ([upper]'s on top, with new ids): both stay editable. Only when
     * the result looks the same: [upper] Normal, 100 %, without mask and not clipping, [lower] at
     * 100 % without mask (its opacity and mask would apply to the merged objects too). The merged
     * pixels are [upper]'s cache drawn over [lower]'s (one SRC_OVER per pixel: equal to a fresh
     * render of the merged content within one level where upper objects overlap each other).
     * One step "Merge down"; false = the raster merge.
     */
    fun mergeVector(c: EditorController, upper: Layer, lower: Layer): Boolean {
        val uc = upper.vector ?: return false
        val lc = lower.vector ?: return false
        if (upper.blendMode != LayerBlendMode.NORMAL || upper.opacity < 1f || upper.mask != null || upper.maskSpec != null ||
            upper.clipping || upper.adjustment != null) return false
        if (lower.opacity < 1f || lower.mask != null || lower.maskSpec != null || lower.adjustment != null) return false
        val idx = c.doc.indexOf(upper)
        if (idx <= 0 || c.doc.layers[idx - 1] !== lower) return false
        c.vectors.flushPending()
        if (upper.vector !== uc || lower.vector !== lc) return false
        val merged = lc.plus(uc.objects).first
        val area = Rect()
        ObjectIndex.of(uc).unionBounds().takeUnless { it.isEmpty }?.let { b ->
            area.set(floor(b.left).toInt(), floor(b.top).toInt(), ceil(b.right).toInt(), ceil(b.bottom).toInt())
        }
        if (!area.intersect(0, 0, c.doc.width, c.doc.height)) area.setEmpty()
        val label = "Merge down"
        var ok = false
        // Like the raster merge, a locked or hidden lower layer is merged into as well (its
        // objects stay editable): the step is recorded here, not through the checks of
        // updateLayerData.
        c.groupUndo(label) {
            val before = lower.dataSnapshot()
            val data = before.copy(vector = merged)
            val dataAction = LayerDataAction(label, lower, before, data)
            if (!area.isEmpty) {
                val rec = c.beginEdit(lower, EditTarget.CONTENT).also { it.preserveData = true }
                try {
                    rec.touch(area)
                    Canvas(lower.bitmap).drawBitmap(upper.bitmap, area, area, null)
                } catch (e: OutOfMemoryError) {
                    rec.abort()
                    return@groupUndo
                }
                lower.restoreData(data)
                if (!c.commitEdit(rec, label, listOf(dataAction))) pushData(c, lower, dataAction, label)
            } else {
                lower.restoreData(data)
                pushData(c, lower, dataAction, label)
            }
            val at = c.doc.indexOf(upper)
            val remove = RemoveLayerAction(upper, at, label)
            remove.redo(c)
            c.pushUndo(remove)
            c.structural { c.doc.activeLayerIndex = c.doc.indexOf(lower) }
            ok = true
        }
        return ok
    }

    /** Records [action] (already applied to [layer]) as a data change: saved, redrawn, reported. */
    private fun pushData(c: EditorController, layer: Layer, action: LayerDataAction, label: String) {
        layer.markChanged()
        c.pushUndo(action)
        c.notifyLayersChanged()
        c.queueEdit(EditEvent(layer, EditTarget.CONTENT, null, label))
    }

    /**
     * A new vector layer above [layer] with only the objects [sel] touches (Duplicate with a
     * selection; their ids kept), its mask and mask spec copied whole. One step "Duplicate
     * selection". The copy's pixels are the rendering of those objects (the cache itself when
     * every object is touched).
     */
    fun duplicateTouched(c: EditorController, layer: Layer, sel: Selection): Layer? {
        val content = layer.vector ?: return null
        c.vectors.flushPending()
        val at = c.doc.indexOf(layer) + 1
        if (at <= 0) return null
        val ids = VectorOps.touching(content, sel)
        val subset = VectorContent(version = content.version, objects = content.objects.filter { it.id in ids }, nextId = content.nextId)
        val w = c.doc.width
        val h = c.doc.height
        val copy = try {
            val pixels = if (ids.size == content.objects.size) {
                BitmapUtils.copy(layer.bitmap)
            } else {
                BitmapUtils.createLayerBitmap(w, h).also { b -> if (ids.isNotEmpty()) drawSubset(b, layer, content, subset, ids, w, h, c.doc.colorMode) }
            }
            Layer(c.doc.newLayerId(), uniqueName(c, "${layer.name} copy"), pixels).also {
                it.mask = layer.mask?.let { m -> BitmapUtils.copy(m) }
                it.restoreData(layer.dataSnapshot().copy(text = null, shape = null, vector = subset))
            }
        } catch (e: OutOfMemoryError) {
            c.toast("Not enough memory to duplicate this layer")
            return null
        }
        copy.copyPropsFrom(layer.props().copy(name = copy.name))
        // v1.7 (rule S): directly above the layer at its level; the caller reports it as DUPLICATED.
        if (!c.structure.place(copy, c.structure.above(layer), "Duplicate selection")) { copy.recycleBitmaps(); return null }
        if (ids.isEmpty()) c.toast("The selection touches no objects of \"${layer.name}\"")
        return copy
    }

    /**
     * Draws the rendering of [subset] (the objects [ids] of [layer]'s [content]) into [canvas]
     * (document px, empty): the grid tiles the other objects can't reach are the layer's cache
     * there (exactly the rendering of the objects that reach them), so only the tiles both kinds
     * reach are rendered — a large selection on the main thread stays quick.
     */
    private fun drawSubset(target: Bitmap, layer: Layer, content: VectorContent, subset: VectorContent, ids: Set<Long>, w: Int, h: Int, mode: ColorMode) {
        val canvas = Canvas(target)
        val t = VectorLayerRenderer.TILE
        val index = ObjectIndex.of(content)
        val mine = TileSet(w, h, t)
        val others = TileSet(w, h, t)
        for ((i, o) in content.objects.withIndex()) (if (o.id in ids) mine else others).addObject(o, index.bounds(i))
        val copy = TileSet(w, h, t)
        val render = TileSet(w, h, t)
        val r = Rect()
        for (row in 0 until mine.rows) for (col in 0 until mine.cols) {
            if (!mine.has(col, row)) continue
            (if (others.has(col, row)) render else copy).addRect(mine.tileRect(col, row, r))
        }
        for (rect in copy.rects()) canvas.drawBitmap(layer.bitmap, rect, rect, null)
        val doc = Rect(0, 0, w, h)
        val tips = TipCache(8L shl 20)
        for (rect in render.rects()) {
            canvas.save()
            canvas.clipRect(rect)
            VectorLayerRenderer.render(canvas, subset, rect, tips = tips, document = doc)
            canvas.restore()
        }
        tips.clear()
        // The cache is already held to the document's color mode; rendered tiles are held now.
        if (mode != ColorMode.RGB) for (rect in render.rects()) ColorModeOps.constrain(target, rect, mode)
    }

    /**
     * [content] mirrored inside a [w] x [h] canvas (Flip layer): every object mapped by the
     * mirror (strokes and paths exactly; shapes stay shapes when they mirror into themselves or
     * have custom points, else they become paths). Ids are kept.
     */
    fun flipped(content: VectorContent, w: Int, h: Int, horizontal: Boolean): VectorContent? {
        val m = if (horizontal) floatArrayOf(-1f, 0f, w.toFloat(), 0f, 1f, 0f, 0f, 0f, 1f) else floatArrayOf(1f, 0f, 0f, 0f, -1f, h.toFloat(), 0f, 0f, 1f)
        return content.copy(objects = content.objects.map { VectorOps.transformed(it, m) })
    }

    /**
     * Clear on a vector layer: removes the objects [sel] touches, or every object (one step
     * [label]: "Clear", or "Cut" when the selection was copied first). True also when nothing was
     * touched (a message says so): a vector layer is never rasterized by Clear.
     */
    fun clear(c: EditorController, layer: Layer, sel: Selection?, label: String = "Clear"): Boolean {
        val content = layer.vector ?: return false
        c.vectors.flushPending()
        val now = layer.vector ?: return false
        val ids = if (sel == null) now.objects.mapTo(HashSet()) { it.id } else VectorOps.touching(now, sel)
        if (ids.isEmpty()) {
            c.toast(if (sel == null || content.objects.isEmpty()) "\"${layer.name}\" has no objects to clear" else "The selection touches no objects")
            return true
        }
        c.vectors.update(layer, now.without(ids), label)
        return true
    }

    /**
     * Fill on a vector layer: a new top object filled with [color] whose outline is the
     * selection's (traced at its 50 % edge, holes included, even-odd), or the canvas rectangle
     * (one step "Fill"). True also when the selection is empty (a message says so).
     */
    fun fill(c: EditorController, layer: Layer, sel: Selection?, color: Int): Boolean {
        if (layer.vector == null) return false
        val subpaths = if (sel == null) {
            val w = c.doc.width.toFloat()
            val h = c.doc.height.toFloat()
            listOf(VSubpath(listOf(VAnchor(0f, 0f, true), VAnchor(w, 0f, true), VAnchor(w, h, true), VAnchor(0f, h, true)), closed = true))
        } else {
            try { traceSelection(sel) } catch (e: OutOfMemoryError) { c.toast("Not enough memory to fill the selection"); return true }
        }
        if (subpaths.isEmpty()) { c.toast("The selection is empty"); return true }
        val path = VPath(0, subpaths = subpaths, fillRule = VFillRule.EVENODD, fill = VPaint.Solid(color))
        c.vectors.addObjects(layer, listOf(path), "Fill")
        return true
    }

    /**
     * The outline of [sel] where it is at least half selected, as closed sharp sub-paths in
     * document px (pixel edges exactly on pixel borders; straight runs simplified to their ends).
     */
    internal fun traceSelection(sel: Selection): List<VSubpath> {
        val b = sel.bounds
        if (b.isEmpty) return emptyList()
        val sub = Bitmap.createBitmap(sel.mask, b.left, b.top, b.width(), b.height())
        val bytes = try { BitmapUtils.alpha8ToBytes(sub) } finally { if (sub !== sel.mask) sub.recycle() }
        val contours = MarchingSquares.contours(bytes, b.width(), b.height()) ?: return emptyList()
        val out = ArrayList<VSubpath>(contours.size)
        // Grid sample (i, j) is the centre of pixel (left + i, top + j).
        val ox = b.left + 0.5f
        val oy = b.top + 0.5f
        for (raw in contours) {
            val pts = MarchingSquares.simplifyClosed(raw, TRACE_EPSILON)
            val n = pts.size / 2
            if (n < 3) continue
            out += VSubpath(List(n) { i -> VAnchor(pts[2 * i] + ox, pts[2 * i + 1] + oy, sharp = true) }, closed = true)
        }
        return out
    }

    private fun uniqueName(c: EditorController, base: String): String {
        val names = c.doc.layers.map { it.name }.toSet()
        if (base !in names) return base
        var n = 2
        while ("$base $n" in names) n++
        return "$base $n"
    }

    /** Bounds helper for tests: the union paint bounds of [content] (document px). */
    internal fun contentBounds(content: VectorContent): RectF = ObjectIndex.of(content).unionBounds()
}
