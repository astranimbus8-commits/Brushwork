package com.brushwork.paint.engine

import android.graphics.Canvas
import android.util.Log
import com.brushwork.paint.EditorController
import com.brushwork.paint.LayerListEvent
import com.brushwork.paint.LayerListKind
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerTree
import com.brushwork.paint.ui.common.FolderLabels
import kotlin.math.min

/**
 * v1.7 (I11, extraction X1): the ONLY code that changes a document's structure. Each call is ONE
 * step: the v1.6 [AddLayerAction] / [RemoveLayerAction] / [MoveLayerAction] when no folder exists
 * before or after it (so a document without folders records exactly what v1.6 recorded), else one
 * [LayerTreeAction]. Plans come from the pure [LayerTree] and are validated before they apply.
 *
 * Callers pause the tool around these (`EditorController.withToolPaused`); every call runs in its
 * own edit scope, so the layer-list events it queues ([LayerListEvent]) are delivered once its
 * step is complete. A false or null result comes with its message (except "nothing to change").
 * Main thread.
 */
internal class LayerStructure(private val c: EditorController) {
    /** Where a new layer goes: flat index [index] (its top row) inside folder [parentId]. */
    data class Insertion(val index: Int, val parentId: Long)

    private val doc: Document get() = c.doc

    /** Directly above the active row at its level; with an OPEN folder active: its top child. */
    fun insertionPoint(): Insertion {
        val layers = doc.layers
        if (layers.isEmpty()) return Insertion(0, Layer.ROOT_ID)
        val i = doc.activeLayerIndex.coerceIn(0, layers.lastIndex)
        val active = layers[i]
        return if (active.isFolder && active.folderOpen) Insertion(i, active.id) else Insertion(i + 1, active.parentId)
    }

    /** Directly above [layer] at its level (Pathfinder results, "Layer from folder", "Array N" above its source). */
    fun above(layer: Layer): Insertion {
        val i = doc.indexOf(layer)
        return if (i < 0) insertionPoint() else Insertion(i + 1, layer.parentId)
    }

    /**
     * v1.10 (F0, R4): directly below [layer] at its level, in its folder ("Array behind": the new
     * layer takes [layer]'s place in the stack and [layer] stays right above it). The caller
     * copies [layer]'s clipping, so a clipped source and its copy clip onto the same base.
     */
    fun below(layer: Layer): Insertion = Insertion(doc.indexOf(layer), layer.parentId)

    // region v110-A7 blank
    /**
     * Every live insert of a new layer goes through here (sweep rule S): the v1.6 AddLayerAction
     * without folders, else one LayerTreeAction. [at] null = [insertionPoint]. The new layer becomes
     * active and an ADDED [LayerListEvent] is queued. Callers that group several edits wrap it in
     * `groupUndo`.
     */
    fun insert(layer: Layer, at: Insertion? = null, label: String): Boolean = c.editScope {
        if (!place(layer, at, label)) return@editScope false
        c.queueLayerList(LayerListEvent(LayerListKind.ADDED, layer, null, label))
        true
    }

    /** [insert] without the layer-list event (callers that report the layer otherwise, e.g. as DUPLICATED). */
    fun place(layer: Layer, at: Insertion?, label: String): Boolean {
        val action = placed(layer, at, label) ?: return false
        c.pushUndo(action)
        return true
    }

    /**
     * [place] without pushing: the insert is applied and its action returned for the caller to
     * record inside its own step (the selection bar's "Cut to new layer" joins it to the pixel
     * edit, as v1.6 did). Null (with the message) when refused.
     */
    fun placed(layer: Layer, at: Insertion?, label: String): UndoAction? {
        val ins = at ?: insertionPoint()
        val pos = ins.index.coerceIn(0, doc.layers.size)
        if (!layer.isFolder && ins.parentId == Layer.ROOT_ID && !doc.hasFolders) {
            // This insert cannot be refused: the layer goes in blank when it is (v1.10, A7).
            blankIfNew(layer)
            // v1.6 (I11): the same mutation and the same action; v1.7: its undo selects the row
            // that was active before the add again.
            val activeBefore = doc.layers.getOrNull(doc.activeLayerIndex)
            c.structural {
                doc.insertLayer(pos, layer, Layer.ROOT_ID)
                doc.activeLayerIndex = pos
            }
            return AddLayerAction(layer, pos, label, activeBefore)
        }
        if (layer.isFolder && folderCount() >= LayerTree.MAX_FOLDERS) { c.toast(FolderLabels.COUNT_LIMIT); return null }
        // v1.10 (A7): blank only once the tree took it (same main-thread call, nothing drawn in
        // between), so a refused insert gives the caller its layer and bitmap back untouched.
        return applied(label, LayerTree.inserted(doc.layers, pos, listOf(layer), ins.parentId))?.also { blankIfNew(layer) }
    }

    /**
     * v1.10 (A7, design 2.13): a new layer whose content was never painted (a flat store with
     * `contentVersion` 0 and no visible pixel: "Add layer", an adjustment or vector layer, an empty
     * import) goes in as `BlankPixels` and costs 0 bytes until its first write; its bitmap is freed
     * here, in the insert's own main-thread call, before anything draws the layer. The pixels stay
     * the same (transparent), so `pixelGeneration` does not move and the insert stays one step; the
     * mask is kept as it is. A refused insert leaves the layer as its caller made it.
     *
     * Every caller of [insert], [place] and [placed] lets go of its own bitmap once the insert
     * succeeds (it reads the layer through `layer.bitmap`, which materializes a blank layer). The
     * emptiness check is v1.9's full scan (`TileOps.isTransparent`), on main: about the cost of
     * allocating the layer; it stops at the first painted pixel of a layer made with content.
     */
    private fun blankIfNew(layer: Layer) {
        val p = layer.pixels
        if (layer.isFolder || p !is com.brushwork.paint.engine.tiles.FlatPixels || layer.contentVersion != 0L) return
        if (!p.isEmpty()) return
        layer.rehouse(com.brushwork.paint.engine.tiles.BlankPixels(p.width, p.height), layer.maskPixels)
        com.brushwork.paint.engine.tiles.TileOps.dispose(p)
    }
    // endregion

    /**
     * Applies [plan] as ONE [LayerTreeAction] named [label]. False when the plan breaks I11 (too
     * deep, too many folders: with their message) or changes nothing.
     */
    fun apply(label: String, plan: LayerTree.Plan): Boolean {
        val action = applied(label, plan) ?: return false
        c.pushUndo(action)
        return true
    }

    /** [apply] without pushing: the applied [LayerTreeAction], or null. */
    private fun applied(label: String, plan: LayerTree.Plan): UndoAction? {
        LayerTree.check(plan)?.let { problem ->
            when {
                plan.order.count { it.isFolder } > LayerTree.MAX_FOLDERS -> c.toast(FolderLabels.COUNT_LIMIT)
                "deep" in problem -> c.toast(FolderLabels.DEPTH_LIMIT)
                else -> Log.w(TAG, "Refused \"$label\": $problem")
            }
            return null
        }
        val before = doc.slots()
        val after = plan.order.mapIndexed { i, l -> LayerSlot(l, plan.parents[i]) }
        val activeBefore = doc.layers.getOrNull(doc.activeLayerIndex)
        val activeAfter = plan.order.getOrNull(plan.active)
        if (before == after && activeBefore === activeAfter) return null
        val action = LayerTreeAction(label, before, after, activeBefore, activeAfter)
        action.redo(c)
        return action
    }

    /**
     * Deletes [layer]: a folder with its whole block, or with [keepChildren] ("Folder only") the
     * folder alone, its direct children moving to its level first. The row below becomes active
     * (v1.6). Refused for the last pixel layer ("A drawing needs at least one layer"). REMOVED
     * events for every layer that left.
     */
    fun delete(layer: Layer, keepChildren: Boolean, label: String): Boolean = c.editScope {
        val idx = doc.indexOf(layer)
        if (idx < 0) return@editScope false
        val first = if (layer.isFolder && !keepChildren) LayerTree.block(doc.layers, idx).first else idx
        val removed = doc.layers.subList(first, idx + 1).toList()
        if (doc.pixelLayerCount - removed.count { !it.isFolder } < 1) {
            c.toast(LAST_LAYER); return@editScope false
        }
        if (!layer.isFolder && !doc.hasFolders) {
            // v1.6, exactly (I11).
            c.structural {
                doc.layers.removeAt(idx)
                doc.activeLayerIndex = min((idx - 1).coerceAtLeast(0), doc.layers.lastIndex)
            }
            val action = RemoveLayerAction(layer, idx, label)
            c.pushUndo(action)
            c.queueLayerList(LayerListEvent(LayerListKind.REMOVED, layer, null, action.label))
            return@editScope true
        }
        val order = ArrayList<Layer>(doc.layers.size)
        val parents = ArrayList<Long>(doc.layers.size)
        for ((i, l) in doc.layers.withIndex()) {
            if (i in first..idx) continue
            order += l
            parents += if (keepChildren && l.parentId == layer.id) layer.parentId else l.parentId
        }
        val active = (first - 1).coerceIn(0, order.lastIndex)
        if (!apply(label, LayerTree.Plan(order, parents.toLongArray(), active))) return@editScope false
        for (l in removed) c.queueLayerList(LayerListEvent(LayerListKind.REMOVED, l, null, label))
        true
    }

    // region v110-A8 duplicate
    /**
     * Duplicates [layer] directly above itself (above its block), the copy active. A layer: as
     * v1.6's "Duplicate layer" (with a selection, only the selected pixels: "Duplicate selection";
     * a vector layer with a selection copies the objects it touches). A folder duplicates its whole
     * block, new ids, parents remapped. Null when the copies need more room than `maxLayers`
     * allows, or without memory (with the message). DUPLICATED events (source = the original).
     */
    fun duplicate(layer: Layer, label: String): Layer? = c.editScope {
        if (doc.indexOf(layer) < 0) return@editScope null
        if (layer.isFolder) duplicateFolder(layer, label) else duplicateLayer(layer, label)
    }

    private fun duplicateLayer(layer: Layer, label: String): Layer? {
        // Copy after committing pending work (the caller paused the tool) so the duplicate includes it.
        val sel = c.selection
        // A vector layer with a selection: the objects it touches, as a vector layer.
        if (sel != null && layer.isVectorLayer) {
            com.brushwork.paint.vector.VectorLayerOps.duplicateTouched(c, layer, sel)?.let {
                c.queueLayerList(LayerListEvent(LayerListKind.DUPLICATED, it, layer, SELECTION_LABEL))
                return it
            }
        }
        // v1.9 (A7b step 6, the lead at A7b's merge): a mask counts in the layer cap, so a copy
        // with its mask needs two rooms (the caller checked one).
        val rooms = if (layer.hasMask) 2 else 1
        if (c.effectiveLayerCount + rooms > c.maxLayers) { c.toast(c.layerLimitMessage()); return null }
        val copy = try {
            // v1.10 (A8, design 2.6): the stores are copied by kind ([copyOf]): a parked one as a
            // copy-on-write snapshot sharing its tiles, so nothing is read or materialized.
            val pixels = if (sel == null) copyOf(layer, layer.pixels, mask = false) else selectedCopy(layer, sel.mask)
            val mask = try {
                layer.maskPixels?.let { copyOf(layer, it, mask = true) }
            } catch (e: OutOfMemoryError) {
                com.brushwork.paint.engine.tiles.TileOps.dispose(pixels)
                throw e
            }
            Layer(doc.newLayerId(), uniqueName("${layer.name} copy"), pixels).also {
                it.maskPixels = mask
                // A partial copy is no longer the text / shape / vector object: only whole
                // copies keep the content data (the mask is copied whole, so its spec stays).
                val data = layer.dataSnapshot()
                it.restoreData(if (sel == null) data else data.rasterizedContent())
            }
        } catch (e: OutOfMemoryError) {
            c.toast("Not enough memory to duplicate this layer"); return null
        }
        copy.copyPropsFrom(layer.props().copy(name = copy.name))
        val stepLabel = if (sel != null) SELECTION_LABEL else label
        if (!place(copy, above(layer), stepLabel)) { copy.recycleBitmaps(); return null }
        c.queueLayerList(LayerListEvent(LayerListKind.DUPLICATED, copy, layer, stepLabel))
        return copy
    }

    /**
     * v1.10 (A8, design 2.6 and 2.16): [p], [layer]'s content (or its mask when [mask]), copied for
     * a duplicate, by kind. A PARKED store becomes a copy-on-write `snapshot()`: a store of its own
     * sharing every tile (refs + 1 each), 0 bytes, no pixel read on main, nothing materialized; the
     * copy's first write replaces tiles in its own store only. A blank store is another blank one;
     * a flat bitmap is copied as v1.9 did, and so is an EVICTED store, through the facade (A11
     * reloads it): its saved file is the source's own, rewritten by the source's next save, so a
     * copy never shares it. The copy is held by the new layer alone: `Layer.recycleBitmaps` (a
     * refused insert), the add's or the tree step's dispose (an undone duplicate dropped from the
     * redo stack) let go of its tiles. Throws [OutOfMemoryError] when a flat copy has no room.
     * Main only.
     */
    private fun copyOf(layer: Layer, p: com.brushwork.paint.engine.tiles.PixelStore, mask: Boolean): com.brushwork.paint.engine.tiles.PixelStore = when (p) {
        is com.brushwork.paint.engine.tiles.TiledPixels ->
            if (p.evictedTo == null) p.snapshot()
            else com.brushwork.paint.engine.tiles.FlatPixels(BitmapUtils.copy(if (mask) layer.mask!! else layer.bitmap))
        is com.brushwork.paint.engine.tiles.BlankPixels -> com.brushwork.paint.engine.tiles.BlankPixels(p.width, p.height)
        is com.brushwork.paint.engine.tiles.FlatPixels -> com.brushwork.paint.engine.tiles.FlatPixels(BitmapUtils.copy(p.bitmap))
        com.brushwork.paint.engine.tiles.FolderPixels -> com.brushwork.paint.engine.tiles.FolderPixels
    }

    /**
     * "Duplicate selection" (v1.6): a flat copy of [layer]'s content holding only the pixels
     * [selMask] selects. v1.10 (A8): a parked layer is drawn from its tiles into the copy
     * (`TiledPixels.materialize`; the layer itself stays parked, but a SWAPPED tile is read back
     * on main, as the compositor does: a known gap of this rare path); a blank one copies to a
     * blank store. Throws [OutOfMemoryError] when there is no room.
     */
    private fun selectedCopy(layer: Layer, selMask: android.graphics.Bitmap): com.brushwork.paint.engine.tiles.PixelStore {
        val bitmap = when (val p = layer.pixels) {
            is com.brushwork.paint.engine.tiles.BlankPixels -> return com.brushwork.paint.engine.tiles.BlankPixels(p.width, p.height)
            is com.brushwork.paint.engine.tiles.FlatPixels -> BitmapUtils.copy(p.bitmap)
            is com.brushwork.paint.engine.tiles.TiledPixels -> if (p.evictedTo == null) p.materialize() else BitmapUtils.copy(layer.bitmap)
            com.brushwork.paint.engine.tiles.FolderPixels -> BitmapUtils.copy(layer.bitmap)
        }
        BitmapUtils.maskWith(Canvas(bitmap), selMask)
        return com.brushwork.paint.engine.tiles.FlatPixels(bitmap)
    }
    // endregion

    // A folder: its whole block (every child and its mask), new ids, parents remapped.

    // region v110-A8 duplicate
    private fun duplicateFolder(folder: Layer, label: String): Layer? {
        val idx = doc.indexOf(folder)
        val members = doc.layers.subList(LayerTree.block(doc.layers, idx).first, idx + 1).toList()
        val pixelCopies = members.count { !it.isFolder }
        // v1.9 (A7b step 6, the lead at A7b's merge): the copies' masks count in the layer cap too.
        val maskCopies = members.count { !it.isFolder && it.hasMask }
        if (c.effectiveLayerCount + pixelCopies + maskCopies > c.maxLayers) { c.toast(c.layerLimitMessage()); return null }
        if (folderCount() + members.size - pixelCopies > LayerTree.MAX_FOLDERS) { c.toast(FolderLabels.COUNT_LIMIT); return null }
        val ids = HashMap<Long, Long>()
        val copies = ArrayList<Layer>(members.size)
        try {
            for (m in members) {
                val id = doc.newLayerId()
                ids[m.id] = id
                val name = if (m === folder) uniqueName("${m.name} copy") else m.name
                val copy = if (m.isFolder) {
                    Layer.newFolder(id, name, m.folder!!).also { it.folderOpen = m.folderOpen }
                } else {
                    // v1.10 (A8, design 2.6): every child and its mask by kind ([copyOf]): a
                    // parked one as a copy-on-write snapshot sharing its tiles.
                    val pixels = copyOf(m, m.pixels, mask = false)
                    val mask = try {
                        m.maskPixels?.let { copyOf(m, it, mask = true) }
                    } catch (e: OutOfMemoryError) {
                        com.brushwork.paint.engine.tiles.TileOps.dispose(pixels)
                        throw e
                    }
                    Layer(id, name, pixels).also { it.maskPixels = mask }
                }
                copy.restoreData(m.dataSnapshot())
                copy.copyPropsFrom(m.props().copy(name = name))
                copies += copy
            }
        } catch (e: OutOfMemoryError) {
            copies.forEach { it.recycleBitmaps() }
            c.toast("Not enough memory to duplicate this folder"); return null
        }
        // Inside the copy every parent is the copy of the original's parent; the top keeps the folder's level.
        for ((m, copy) in members.zip(copies)) copy.parentId = ids[m.parentId] ?: m.parentId
        if (!apply(label, LayerTree.inserted(doc.layers, idx + 1, copies, folder.parentId))) {
            copies.forEach { it.recycleBitmaps() }
            return null
        }
        for ((m, copy) in members.zip(copies)) c.queueLayerList(LayerListEvent(LayerListKind.DUPLICATED, copy, m, label))
        return copies.last()
    }
    // endregion

    /**
     * Moves [layer] (a folder with its block) so that its top lands at flat index [toIndex] of the
     * result, in folder [newParent]. [newParent] null: the drop rule of §3.8 (the parent of the row
     * above the gap, or the top child of an OPEN folder there). Without folders: the v1.6
     * MoveLayerAction ("Move layer"). False when nothing moves, or with "Folders can be nested 8
     * deep" when the drop is refused (into itself, too deep).
     */
    fun move(layer: Layer, toIndex: Int, newParent: Long?, label: String): Boolean = c.editScope {
        val from = doc.indexOf(layer)
        if (from < 0) return@editScope false
        if (!layer.isFolder && !doc.hasFolders && (newParent == null || newParent == Layer.ROOT_ID)) {
            // v1.6, exactly (I11).
            val to = toIndex.coerceIn(0, doc.layers.lastIndex)
            if (from == to) return@editScope false
            c.structural {
                doc.layers.removeAt(from)
                doc.layers.add(to, layer)
                doc.activeLayerIndex = to
            }
            c.pushUndo(MoveLayerAction(layer, from, to))
            return@editScope true
        }
        val parent = newParent ?: dropParent(from, toIndex)
        val plan = LayerTree.movedBlock(doc.layers, from, toIndex, parent)
        if (plan == null) { c.toast(FolderLabels.DEPTH_LIMIT); return@editScope false }
        apply(label, plan)
    }

    /**
     * The parent a block dropped with its top at [toIndex] gets (§3.8): that of the VISIBLE row
     * directly above the gap (a row hidden in a closed folder counts as that folder), or, when
     * that row is an open or non-empty folder whose rows start right above the gap, the folder
     * itself (its top child).
     */
    private fun dropParent(from: Int, toIndex: Int): Long {
        val layers = doc.layers
        val b = LayerTree.block(layers, from)
        val rest = layers.filterIndexed { i, _ -> i !in b }
        val size = b.last - b.first + 1
        val at = toIndex.coerceIn(size - 1, layers.lastIndex) - (size - 1)
        val above = rest.getOrNull(at) ?: return Layer.ROOT_ID
        val closed = LayerTree.ancestors(rest, at).lastOrNull { !rest[it].folderOpen }
        if (closed != null && LayerTree.block(rest, closed).first == at) return rest[closed].parentId
        val inside = above.isFolder && (above.folderOpen || LayerTree.block(rest, at).first < at)
        return if (inside) above.id else above.parentId
    }

    private fun folderCount(): Int = doc.layers.count { it.isFolder }

    private fun uniqueName(base: String): String {
        val names = doc.layers.mapTo(HashSet()) { it.name }
        if (base !in names) return base
        var n = 2
        while ("$base $n" in names) n++
        return "$base $n"
    }

    companion object {
        private const val TAG = "LayerStructure"
        const val LAST_LAYER = "A drawing needs at least one layer"
        const val SELECTION_LABEL = "Duplicate selection"
    }
}
