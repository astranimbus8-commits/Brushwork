package com.brushwork.paint.tools.text.frames

import android.graphics.Rect
import android.graphics.RectF
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.text.PreparedText
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextWrapReflow
import com.brushwork.paint.tools.text.textRectOf
import com.brushwork.paint.tools.transform.ContentBounds
import kotlin.math.max

/**
 * One frame layer's part of a story write (v1.6, §3.6c): [layer] (null = a frame to create, named
 * [name]) held [before] (null = nothing, or not known) and holds [after] once written.
 */
internal class FrameWrite(val layer: Layer?, val before: TextItem?, val after: TextItem, val name: String = "")

/**
 * Writes frames of linked stories into their layers (v1.6, §3.6c; area D), for the caller's step
 * (`groupUndo` or `amendLastStep`): a new frame is added with its pixels
 * (`addLayerWithContent`), a frame whose drawing changes is drawn again
 * (`updateTextLayer(allowHidden = true)`, dirty = its old ink ∪ its new box), and a frame whose
 * pixels stay the same while its stored story copy, indices or `rev` change gets a data-only
 * update (I1: pixels and data change together). Hidden frames are written too (they stay right
 * when shown again); locked ones must not be in the list (the controller refuses them).
 *
 * A heal's writes carry [TextWrapReflow.REFLOW_LABEL], so `TextThreads` ignores its own edits (the
 * amended step keeps the caller's label); a tool's writes carry the tool's step name (a story
 * whose frames come out whole makes `TextThreads` write nothing).
 */
internal class StoryWriter(
    private val c: EditorController,
    private val measures: StoryMeasureCache,
    /** Tells the frame index what a layer now stores, so nothing has to be decoded again. */
    private val written: (Layer, String, TextItem) -> Unit,
) {
    /**
     * Writes [writes] in order; returns the layer of each (the created ones included), or null
     * when one could not be written (out of memory, a layer limit reached meanwhile): what was
     * written before stays part of the caller's step. Created frames are added with
     * [createLabel], the others written with [updateLabel] (the tool's own step name when the
     * write is a user action, so a step of one write keeps that name; [TextWrapReflow.REFLOW_LABEL]
     * for a heal folded into another step).
     */
    fun write(writes: List<FrameWrite>, createLabel: String, updateLabel: String = TextWrapReflow.REFLOW_LABEL): List<Layer>? {
        val out = ArrayList<Layer>(writes.size)
        for (w in writes) {
            val layer = w.layer
            if (layer == null) {
                out += create(w, createLabel) ?: return null
                continue
            }
            if (!update(layer, w.before, w.after, updateLabel)) return null
            out += layer
        }
        return out
    }

    private fun create(w: FrameWrite, label: String): Layer? {
        val item = w.after
        val prep = measures.prepare(item)
        val json = TextCodec.encode(item)
        val layer = try {
            c.addLayerWithContent(w.name.ifBlank { "Frame" }, label, textData = json) { cv ->
                TextRenderer.drawItem(cv, item, prep, null)
            }
        } catch (e: OutOfMemoryError) {
            c.toast("Not enough memory for another layer")
            null
        } ?: return null
        written(layer, json, item)
        return layer
    }

    /**
     * Writes [after] into frame layer [layer] that held [before], as an edit named [label]; true
     * when it is stored (or already was).
     */
    fun update(layer: Layer, before: TextItem?, after: TextItem, label: String = TextWrapReflow.REFLOW_LABEL): Boolean {
        if (before == after) return true
        val json = TextCodec.encode(after)
        val ok = try {
            if (before != null && sameRendering(before, after)) {
                c.updateLayerData(layer, layer.dataSnapshot().copy(text = json), label, null, EditTarget.CONTENT, allowHidden = true, draw = null)
            } else {
                val prep = measures.prepare(after)
                val dirty = Rect()
                inkOf(layer, before)?.let { dirty.union(it) }
                textRectOf(after, prep)?.let { dirty.union(it) }
                if (dirty.isEmpty) {
                    // Nothing drawn before or after (an empty frame stays empty): only the data changes.
                    c.updateLayerData(layer, layer.dataSnapshot().copy(text = json), label, null, EditTarget.CONTENT, allowHidden = true, draw = null)
                } else {
                    dirty.inset(-1, -1)
                    c.updateTextLayer(layer, json, label, dirty, allowHidden = true) { cv -> TextRenderer.drawItem(cv, after, prep, null) }
                }
            }
        } catch (e: OutOfMemoryError) {
            c.toast("Not enough memory to update \"${layer.name}\"")
            false
        }
        if (ok) written(layer, json, after)
        return ok
    }

    /**
     * Where the pixels of text layer [layer] (drawn from [before]) are: around the frame's box for
     * a frame (no layout needed), around the text's laid-out bounds otherwise; the pixels are
     * scanned, as fonts may differ from the device that drew them (`textInkOf`'s rule). Null
     * when nothing is drawn (or [before] is unknown and the layer is empty).
     */
    fun inkOf(layer: Layer, before: TextItem?): Rect? {
        val guess: Rect? = when {
            before == null -> null
            before.threaded -> Rect().also { r ->
                val box = FrameGeometry.outerRect(before, RectF())
                val pad = before.spec.strokeWidthPx + before.spec.sizePx * 0.6f + 4f
                box.inset(-pad, -pad)
                box.roundOut(r)
            }
            else -> textRectOf(before)
        }
        return try {
            if (guess != null && !guess.isEmpty) {
                val margin = max(64, max(guess.width(), guess.height()) / 4)
                val region = Rect(guess).apply { inset(-margin, -margin) }
                if (region.intersect(0, 0, layer.width, layer.height)) {
                    val ink = ContentBounds.of(layer.bitmap, region = region)
                    val atEdge = ink != null && (
                        (ink.left <= region.left && region.left > 0) || (ink.top <= region.top && region.top > 0) ||
                            (ink.right >= region.right && region.right < layer.width) || (ink.bottom >= region.bottom && region.bottom < layer.height)
                        )
                    if (ink != null && !atEdge) return ink
                    if (ink == null) return null
                } else {
                    // The box is off the canvas: nothing of it can be drawn there.
                    return null
                }
            }
            ContentBounds.of(layer.bitmap) ?: guess
        } catch (e: OutOfMemoryError) {
            guess
        }
    }

    companion object {
        /**
         * True when frames [a] and [b] draw exactly the same pixels: the same slice, look, place
         * and wrap, starting and ending at the same story positions, with the same story text
         * from the frame's start through the end of the paragraph its slice ends in (the frame's
         * lines are laid out from those characters only: their advances are measured per
         * paragraph and its line breaks found per paragraph). Scaled letters (§3.5) depend on the
         * whole story (each letter's place in the ramp): then the whole story must be the same.
         */
        fun sameRendering(a: TextItem, b: TextItem): Boolean {
            if (a.text != b.text || a.spec != b.spec || a.cx != b.cx || a.cy != b.cy || a.rotationDeg != b.rotationDeg || a.wrap != b.wrap || a.path != b.path) return false
            val ta = a.thread
            val tb = b.thread
            if (ta.isOn != tb.isOn) return false
            if (!ta.isOn) return true
            if (ta.start != tb.start || ta.end != tb.end) return false
            if (a.spec.letterScale.isOn) return ta.story == tb.story
            val pa = ta.story.indexOf('\n', ta.end).let { if (it < 0) ta.story.length else it }
            val pb = tb.story.indexOf('\n', tb.end).let { if (it < 0) tb.story.length else it }
            return pa == pb && ta.story.regionMatches(ta.start, tb.story, tb.start, pa - ta.start)
        }

        /** [prep]'s document bounds rounded out, or null when it draws nothing. */
        fun rectOf(item: TextItem, prep: PreparedText): Rect? = textRectOf(item, prep)
    }
}
