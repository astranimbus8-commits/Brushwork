package com.brushwork.paint.exchange

import android.graphics.Bitmap
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.AddLayerAction
import com.brushwork.paint.engine.RemoveLayerAction
import com.brushwork.paint.engine.UndoAction
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.model.LayerProps
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
)

/** Inserting imported layers into the open document as one undo step (v1.5 §4.11, I2). */
object ImportLayers {
    /** Layers [c] can still add (the editor's memory-based limit). */
    fun room(c: EditorController): Int = (c.maxLayers - c.doc.layers.size).coerceAtLeast(0)

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
                var at = (doc.activeLayerIndex + 1).coerceIn(0, doc.layers.size)
                for ((k, n) in layers.withIndex()) {
                    // Unique among the layers that stay (a restored "Background" keeps its name).
                    val layer = Layer(ids[k], uniqueName(c, n.name, replace), n.bitmap)
                    n.props?.let { layer.copyPropsFrom(it.copy(name = layer.name)) }
                    layer.mask = n.mask
                    layer.restoreData(if (n.sourceId != 0L) relinked(n.data, links) { doc.newLayerId() } else n.data)
                    val index = at
                    // As the project loader does: an adjustment layer is never clipped nor a clipping base.
                    if (layer.isAdjustmentLayer || doc.layers.getOrNull(index - 1)?.isAdjustmentLayer == true) layer.clipping = false
                    c.structural {
                        doc.layers.add(index, layer)
                        doc.activeLayerIndex = index
                    }
                    c.pushUndo(AddLayerAction(layer, index, label))
                    created += layer
                    at++
                }
                for (r in replace) {
                    val idx = doc.indexOf(r)
                    if (idx < 0 || doc.layers.size <= 1) continue
                    c.structural {
                        doc.layers.removeAt(idx)
                        doc.activeLayerIndex = doc.indexOf(created.last()).coerceAtLeast(0)
                    }
                    c.pushUndo(RemoveLayerAction(r, idx, label))
                }
            }
        } finally {
            tool.onActivate()
        }
        return created
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
