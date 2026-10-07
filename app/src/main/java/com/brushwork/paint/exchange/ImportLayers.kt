package com.brushwork.paint.exchange

import android.graphics.Bitmap
import com.brushwork.paint.EditorController
import com.brushwork.paint.LayerListEvent
import com.brushwork.paint.LayerListKind
import com.brushwork.paint.engine.LayerStructure
import com.brushwork.paint.engine.UndoAction
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.FolderSpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.model.LayerProps
import com.brushwork.paint.model.LayerTree
import com.brushwork.paint.tools.text.TextCodec

/**
 * A layer prepared for an import (pixels rendered and data set off the main thread, not yet in
 * the document): [bitmap] is document-sized and already equals the rendering of [data] (I1).
 */
class NewLayer(
    val name: String,
    val bitmap: Bitmap,
    /** Properties (name aside); null = a plain new layer's. */
    val props: LayerProps? = null,
    val data: LayerData = LayerData.NONE,
    val mask: Bitmap? = null,
    /**
     * The layer's id in the file it comes from (a Brushwork payload; 0 = none). Links between
     * imported layers (a text wrapped around a picture) are moved to the new layers' ids.
     */
    val sourceId: Long = 0L,
    /**
     * v1.7 (I11, payload v2): a folder's settings; null for every other layer. A folder's
     * [bitmap] is `Layer.FOLDER_BITMAP` (never recycled: rule B).
     */
    val folder: FolderSpec? = null,
    /** v1.7: the [sourceId] of the folder this layer is in, in the same import (0 = top level). */
    val parentSourceId: Long = 0L,
    /** v1.7: a folder's rows are shown in the layer window. */
    val folderOpen: Boolean = true,
) {
    /** True when this import carries part of a layer tree (a folder, or a layer inside one). */
    internal val inTree: Boolean get() = folder != null || parentSourceId != 0L
}

/** Inserting imported layers into the open document as one undo step (v1.5 §4.11, I2). */
object ImportLayers {
    /** Layers [c] can still add (the editor's memory-based limit). */
    fun room(c: EditorController): Int = (c.maxLayers - c.effectiveLayerCount).coerceAtLeast(0)

    /**
     * Inserts [layers] (bottom first) right above the active layer and removes [replace] (layers
     * the import takes the place of, e.g. a new artwork's empty "Layer 1") as ONE undo step
     * [label]; the topmost new layer becomes active. With [colorMode] (a Brushwork file restored
     * into the new artwork made for it) the document takes that color mode in the same step. The
     * current tool is paused around it (its pending work committed first). Returns the inserted
     * layers in order. Main thread.
     */
    fun insert(
        c: EditorController,
        layers: List<NewLayer>,
        label: String,
        replace: List<Layer> = emptyList(),
        colorMode: ColorMode? = null,
    ): List<Layer> {
        if (layers.isEmpty()) return emptyList()
        val doc = c.doc
        val tool = c.currentTool
        tool.onDeactivate()
        val created = ArrayList<Layer>()
        try {
            c.groupUndo(label) {
                if (colorMode != null && colorMode != doc.colorMode) {
                    c.pushUndo(ColorModeAction(doc.colorMode, colorMode, label))
                    doc.colorMode = colorMode
                    c.onDocumentGeometryChanged()
                }
                // The new layers' ids first: links between the file's layers point at them.
                val ids = layers.map { doc.newLayerId() }
                val links = HashMap<Long, Long>()
                layers.forEachIndexed { i, n -> if (n.sourceId != 0L) links[n.sourceId] = ids[i] }
                // v1.7 (rule S): through LayerStructure, at the insertion point's level (without
                // folders: right above the active layer, the v1.6 AddLayerAction).
                val point = c.structure.insertionPoint()
                var at = point.index.coerceIn(0, doc.layers.size)
                if (layers.any { it.inTree }) created += insertTree(c, layers, ids, links, label, replace, point)
                else for ((k, n) in layers.withIndex()) {
                    // Unique among the layers that stay (a restored "Background" keeps its name).
                    val layer = Layer(ids[k], uniqueName(c, n.name, replace), n.bitmap)
                    n.props?.let { layer.copyPropsFrom(it.copy(name = layer.name)) }
                    layer.mask = n.mask
                    layer.restoreData(if (n.sourceId != 0L) relinked(n.data, links) { doc.newLayerId() } else n.data)
                    val index = at
                    // As the project loader does: an adjustment layer is never clipped nor a clipping base.
                    if (layer.isAdjustmentLayer || doc.layers.getOrNull(index - 1)?.isAdjustmentLayer == true) layer.clipping = false
                    // Reported like any added layer (v1.6 QA, ADDED): frames brought in again under a
                    // story id the document has are taken apart in THIS step, before any edit
                    // (a frame's own edit would otherwise flow both copies as one chain).
                    if (!c.structure.insert(layer, LayerStructure.Insertion(index, point.parentId), label)) {
                        layer.recycleBitmaps()
                        continue
                    }
                    created += layer
                    at++
                }
                // v1.7 (rule S): only pixel layers are replaced (never a folder, nor part of a
                // folder's block), through LayerStructure; the newest import stays active.
                for (r in replace) {
                    val idx = doc.indexOf(r)
                    if (idx < 0 || r.isFolder || r.parentId != Layer.ROOT_ID || doc.pixelLayerCount <= 1) continue
                    if (!c.structure.delete(r, keepChildren = false, label = label)) continue
                    created.lastOrNull()?.let { top ->
                        c.structural { doc.activeLayerIndex = doc.indexOf(top).coerceAtLeast(0) }
                    }
                }
            }
        } finally {
            tool.onActivate()
        }
        return created
    }

    /**
     * v1.7 (I11): [layers] with folders (a payload-v2 tree) inserted at [point] as ONE
     * `LayerTreeAction` [label] (inside the caller's group): parents are moved to the new ids,
     * the import's own tree is repaired (`LayerTree.sanitize`) and its top-level units go into
     * [point]'s folder. ADDED for every new layer; the topmost becomes active. Empty (the
     * layers' pixels freed, with the message) when the document can't take the tree (too many
     * folders, too deep).
     */
    private fun insertTree(
        c: EditorController,
        layers: List<NewLayer>,
        ids: List<Long>,
        links: Map<Long, Long>,
        label: String,
        replace: List<Layer>,
        point: LayerStructure.Insertion,
    ): List<Layer> {
        val doc = c.doc
        val block = ArrayList<Layer>(layers.size)
        for ((k, n) in layers.withIndex()) {
            val name = uniqueName(c, n.name, replace)
            val layer = if (n.folder != null) {
                Layer.newFolder(ids[k], name, n.folder).also { it.folderOpen = n.folderOpen }
            } else {
                Layer(ids[k], name, n.bitmap).also { l ->
                    l.mask = n.mask
                    l.restoreData(if (n.sourceId != 0L) relinked(n.data, links) { doc.newLayerId() } else n.data)
                }
            }
            n.props?.let { layer.copyPropsFrom(it.copy(name = layer.name)) }
            layer.parentId = links[n.parentSourceId] ?: Layer.ROOT_ID
            block += layer
        }
        LayerTree.sanitize(block)
        val at = point.index.coerceIn(0, doc.layers.size)
        val order = ArrayList<Layer>(doc.layers.size + block.size)
        order.addAll(doc.layers.subList(0, at))
        order.addAll(block)
        order.addAll(doc.layers.subList(at, doc.layers.size))
        val parents = LongArray(order.size) { i ->
            val l = order[i]
            if (i in at until at + block.size && l.parentId == Layer.ROOT_ID) point.parentId else l.parentId
        }
        // As the project loader does: an adjustment layer is never clipped nor a clipping base.
        for (i in at until at + block.size) {
            val l = order[i]
            if (l.isAdjustmentLayer || order.getOrNull(i - 1)?.isAdjustmentLayer == true) l.clipping = false
        }
        if (!c.structure.apply(label, LayerTree.Plan(order, parents, at + block.size - 1))) {
            block.forEach { it.recycleBitmaps() }
            return emptyList()
        }
        for (l in block) c.queueLayerList(LayerListEvent(LayerListKind.ADDED, l, null, label))
        return block
    }

    /**
     * [data] of a layer from a Brushwork file with its links moved to this document: a text
     * wrapped around a picture of the file wraps around that picture's new layer ([links]: file id
     * -> new id). A picture that didn't come in (layer limit) gets an id no layer has
     * ([unusedId]), as if it had been deleted: the text keeps its outline and layout, and never
     * follows a layer of this document that happens to have the file's id. The pixels don't
     * change (the layout doesn't depend on the id).
     */
    internal fun relinked(data: LayerData, links: Map<Long, Long>, unusedId: () -> Long): LayerData {
        val text = data.text ?: return data
        val item = TextCodec.decode(text) ?: return data
        val from = item.wrap.sourceLayerId
        if (from == 0L) return data
        val to = links[from] ?: unusedId()
        if (to == from) return data
        return data.copy(text = TextCodec.encode(item.copy(wrap = item.wrap.copy(sourceLayerId = to))))
    }

    /** The document's color mode changed by an import (part of the import's step). */
    private class ColorModeAction(private val before: ColorMode, private val after: ColorMode, override val label: String) : UndoAction {
        override val byteSize: Long get() = 0L

        override fun undo(c: EditorController) = set(c, before)

        override fun redo(c: EditorController) = set(c, after)

        private fun set(c: EditorController, mode: ColorMode) {
            c.doc.colorMode = mode
            // Refreshes what depends on the mode (color picker, display tiles).
            c.onDocumentGeometryChanged()
        }
    }

    /** [base], or "[base] 2", "[base] 3"... so names stay unique (the layers in [leaving] don't count). */
    fun uniqueName(c: EditorController, base: String, leaving: List<Layer> = emptyList()): String {
        val names = c.doc.layers.filter { l -> leaving.none { it === l } }.map { it.name }.toSet()
        val clean = base.trim().ifEmpty { "Imported" }.take(64)
        if (clean !in names) return clean
        var n = 2
        while ("$clean $n" in names) n++
        return "$clean $n"
    }
}
