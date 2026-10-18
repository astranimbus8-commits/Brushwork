package com.brushwork.paint.tools.text

import android.graphics.Rect
import android.graphics.RectF
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.CompositeAction
import com.brushwork.paint.engine.UndoAction
import com.brushwork.paint.model.Layer
import com.brushwork.paint.ui.common.LayerLabels19
import com.brushwork.paint.vector.select.ObjectActions

/**
 * v1.9 (R14, design 2.4.15; owner A3, called by A4): operations on the texts of a text layer as
 * items.
 */
object TextItemOps {
    /** True: [duplicateItems] works (A4 shows its Duplicate on text layers). */
    val available: Boolean = true

    /**
     * R14: duplicates the texts of [layer] whose bounds meet [area] (every text when null) and whose
     * ids are in [ids] (every id when null), offset like the object bar's Duplicate
     * ([ObjectActions.duplicateOffset], right and down); the copies go right above the topmost
     * duplicated text, in their order, with new ids. Re-renders the layer where the copies land
     * (I1) and returns the undo action of that change, labelled
     * [LayerLabels19.DUPLICATE_STEP]. The action is NOT in the history: the caller records it
     * (`EditorController.pushUndo`) or folds it into its own step (A4's selection bar; A3's Text
     * More row with the open text's id, 0 for the text of a one-text layer).
     *
     * Null when nothing was duplicated: no text matches; the layer is hidden, locked, no text
     * layer, a frame of a linked story or holds a text that wraps around a picture (those stay
     * alone, I24); or the copies would pass [TextCodec.MAX_ITEMS] texts (the layer is unchanged).
     */
    fun duplicateItems(c: EditorController, layer: Layer, area: RectF?, ids: Set<Int>? = null): UndoAction? {
        val plan = plan(c, layer, area, ids) ?: return null
        // Held, so the mark stays valid when the history is full (the oldest step would go first).
        c.undoManager.holdTrim()
        try {
            val mark = c.undoManager.undoCount
            val done = c.updateTextLayer(layer, TextCodec.encodeAll(plan.items), LayerLabels19.DUPLICATE_STEP, plan.dirty) { cv ->
                TextLayerDraw.drawMeeting(cv, layer, plan.dirty, plan.items, plan.preps)
            }
            if (!done) return null
            val added = c.undoManager.takeSince(mark)
            return when {
                added.isEmpty() -> null
                added.size == 1 -> added[0]
                else -> CompositeAction(LayerLabels19.DUPLICATE_STEP, added)
            }
        } finally {
            c.undoManager.releaseTrim()
        }
    }

    private class Plan(val items: List<TextItem>, val preps: List<PreparedText>, val dirty: Rect)

    private fun plan(c: EditorController, layer: Layer, area: RectF?, ids: Set<Int>?): Plan? {
        if (!layer.isTextLayer || c.doc.indexOf(layer) < 0) return null
        if (!c.doc.effectiveVisible(layer) || c.doc.effectiveLocked(layer)) return null
        if (c.textThreads.isFrame(layer)) return null
        val items = TextCodec.decodeAll(layer.textData)
        if (items.isEmpty() || items.any { it.wrap.isOn || it.threaded }) return null
        val preps = items.map { TextRenderer.prepare(it) }
        val chosen = items.indices.filter { k ->
            (ids == null || items[k].id in ids) && (area == null || preps[k].docBounds(items[k]).let { RectF.intersects(it, area) })
        }
        if (chosen.isEmpty() || items.size + chosen.size > TextCodec.MAX_ITEMS) return null
        val d = ObjectActions.duplicateOffset(c)
        val m = floatArrayOf(1f, 0f, d, 0f, 1f, d, 0f, 0f, 1f)
        // A text that cannot move (pushed off the coordinate range) is copied in place.
        val copies = chosen.map { k -> (TextTransforms.mapped(items[k], m) ?: items[k]).copy(id = 0) }
        val top = chosen.last()
        val out = ArrayList<TextItem>(items.size + copies.size)
        val outPreps = ArrayList<PreparedText>(items.size + copies.size)
        for (k in items.indices) {
            out += items[k]
            outPreps += preps[k]
            if (k == top) {
                out += copies
                outPreps += copies.map { TextRenderer.prepare(it) }
            }
        }
        val dirty = Rect()
        val r = Rect()
        for (j in copies.indices) {
            TextLayerDraw.inkRect(out[top + 1 + j], outPreps[top + 1 + j], r)
            if (!r.isEmpty) dirty.union(r)
        }
        if (dirty.isEmpty) return null
        return Plan(out, outPreps, dirty)
    }
}
