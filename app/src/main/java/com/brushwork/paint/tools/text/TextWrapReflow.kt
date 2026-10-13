package com.brushwork.paint.tools.text

import android.graphics.Canvas
import android.graphics.Rect
import com.brushwork.paint.EditEvent
import com.brushwork.paint.EditListener
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer
import java.lang.ref.WeakReference

/**
 * Re-flows text wrapped around a picture when that picture's layer is edited (v1.5 §4.1). The
 * controller creates it at init (`controller.textWrap`, so it works before the Text tool exists)
 * and registers it as an [EditListener]: after ANY committed edit of a source layer (transform,
 * brush, eraser, clear, filter, vector edit, mask change, mask switched on or off...), every text
 * layer wrapped around it
 * gets the picture's new outline and is drawn again INSIDE that edit's undo step
 * ([EditorController.amendLastStep], invariant I2): one undo restores picture and text.
 *
 * - Undo and redo never re-flow (the controller doesn't report them; the amended step restores
 *   the text with its picture).
 * - The text open in the Text tool is skipped: the tool re-flows it live ([TextTool.onWrapSourceEdited]).
 * - Locked text layers are left as they are (they can't be edited); hidden ones follow their
 *   picture (`updateTextLayer(allowHidden = true)`), so they are right when shown again.
 * - A picture layer that is deleted is no edit: its texts keep their last outline.
 * - One edit may report several events for a layer (a vector stroke: pixels, then data): the
 *   outline cache ([contours], keyed by content version) makes the second one find nothing new.
 */
class TextWrapReflow(private val c: EditorController) : EditListener {

    /** Outlines of picture layers, shared with the Text tool. */
    val contours = WrapContours()

    private var toolRef: WeakReference<TextTool>? = null

    /** Decoded texts of text layers, by layer id (dropped when the stored text changes). */
    private val decoded = HashMap<Long, Pair<String, List<TextItem>>>()

    /** Texts re-flowed since this controller started (for tests and diagnostics). */
    var reflowCount: Int = 0
        private set

    /** The Text tool registers itself (tools are created lazily, after this listener). */
    internal fun attach(tool: TextTool) {
        toolRef = WeakReference(tool)
    }

    override fun onEdited(e: EditEvent) {
        val source = e.layer
        // Text and adjustment layers are never pictures (and a re-flow's own edit lands here).
        if (source.isTextLayer || source.isAdjustmentLayer) return
        val doc = c.doc
        if (doc.indexOf(source) < 0) return
        val tool = toolRef?.get()
        tool?.onWrapSourceEdited(source)
        val open = tool?.takeIf { it.item != null }?.editingLayer
        var alive: HashSet<Long>? = null
        for (layer in doc.layers) {
            val data = layer.textData ?: continue
            if (alive == null) alive = HashSet()
            alive += layer.id
            val items = itemsOf(layer, data)
            if (items.none { wrapsAround(it, source) }) continue
            // A locked text stays as it is (locked = no edits); a hidden one follows its picture.
            if (layer === open || doc.effectiveLocked(layer)) continue
            reflow(layer, items, source)
        }
        if (decoded.size > (alive?.size ?: 0) + CACHE_SLACK) decoded.keys.retainAll(alive ?: emptySet())
    }

    private fun wrapsAround(item: TextItem, source: Layer): Boolean = item.wrapActive && item.wrap.sourceLayerId == source.id

    /** Every text of [layer] (stored as [data]), bottom first; empty when it can't be read. */
    private fun itemsOf(layer: Layer, data: String): List<TextItem> {
        val cached = decoded[layer.id]
        if (cached != null && (cached.first === data || cached.first == data)) return cached.second
        val items = TextCodec.decodeAll(data)
        decoded[layer.id] = data to items
        return items
    }

    /**
     * Draws text layer [layer] ([items], bottom first) again with every text that wraps around
     * [source] fitted to its current outline, folded into the newest undo step. Nothing happens
     * when no outline changed (or none can be traced). v1.9 (F2): every wrapping text of the layer
     * re-flows and every text is drawn again (a frame of a linked story re-flows its story only
     * when it is alone in its layer, as I24 keeps it); one text re-flows exactly as in v1.8.
     */
    private fun reflow(layer: Layer, items: List<TextItem>, source: Layer) {
        var changed = false
        val next = ArrayList<TextItem>(items.size)
        for (item in items) {
            if (!wrapsAround(item, source)) {
                next += item
                continue
            }
            val polys = contours.polygons(source, item.wrap.contour)
            if (polys == null || polys == item.wrap.polygons) {
                next += item
                continue
            }
            // v1.6 seam: a frame of a linked story re-flows its whole story (its frames' slices
            // depend on each other), in this step; never re-rendered alone.
            if (item.thread.isOn) {
                if (items.size == 1) {
                    c.textThreads.reflowStory(item.thread.storyId)
                    return
                }
                next += item
                continue
            }
            next += item.copy(wrap = item.wrap.copy(polygons = polys))
            changed = true
        }
        if (!changed) return
        val preps = next.map { TextRenderer.prepare(it) }
        // Everything the old texts covered (their real pixels) and the new texts.
        val dirty = Rect()
        for (k in items.indices) {
            if (next[k] !== items[k]) textInkOf(layer, items[k])?.let { dirty.union(it) }
            textRectOf(next[k], preps[k])?.let { dirty.union(it) }
        }
        if (dirty.isEmpty) return
        dirty.inset(-1, -1)
        val json = TextCodec.encodeAll(next)
        val draw: (Canvas) -> Unit = { cv -> for (k in next.indices) TextRenderer.drawItem(cv, next[k], preps[k], null) }
        try {
            c.amendLastStep {
                // A hidden text still follows its picture, so it is right when it is shown again.
                if (c.updateTextLayer(layer, json, REFLOW_LABEL, dirty, allowHidden = true, draw = draw)) reflowCount++
            }
        } catch (e: OutOfMemoryError) {
            c.toast("Not enough memory to re-flow \"${layer.name}\"")
        }
    }

    companion object {
        /** Label of a re-flow's own step (it is folded into the edit that caused it, which keeps its label). */
        const val REFLOW_LABEL = "Re-flow text"

        private const val CACHE_SLACK = 8
    }
}
