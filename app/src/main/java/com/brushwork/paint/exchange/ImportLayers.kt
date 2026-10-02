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
                var at = (doc.activeLayerIndex + 1).coerceIn(0, doc.layers.size)
                for (n in layers) {
                    val layer = Layer(doc.newLayerId(), uniqueName(c, n.name), n.bitmap)
                    n.props?.let { layer.copyPropsFrom(it.copy(name = layer.name)) }
                    layer.mask = n.mask
                    layer.restoreData(n.data)
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

    /** [base], or "[base] 2", "[base] 3"... so names stay unique. */
    fun uniqueName(c: EditorController, base: String): String {
        val names = c.doc.layers.map { it.name }.toSet()
        val clean = base.trim().ifEmpty { "Imported" }.take(64)
        if (clean !in names) return clean
        var n = 2
        while ("$clean $n" in names) n++
        return "$clean $n"
    }
}
