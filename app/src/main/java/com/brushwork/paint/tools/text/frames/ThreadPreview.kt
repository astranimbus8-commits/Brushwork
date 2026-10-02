package com.brushwork.paint.tools.text.frames

import android.graphics.Canvas
import android.graphics.Rect
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.MultiLayerRenderOverride
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.text.PreparedText
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import java.util.IdentityHashMap

/**
 * The live preview of linked text frames being moved, resized or re-flowed by a story edit
 * (v1.6, §3.6c; area D): ONE render override for every affected frame layer
 * ([MultiLayerRenderOverride]), drawing each one's pending item in place of its pixels, through
 * the compositor, so its order, opacity, blend mode, mask and the layers clipped to it look as
 * they will. A view (I7): nothing here touches a layer's pixels or data.
 *
 * Installed as `controller.renderOverride` while it shows something; [clear] removes it and
 * redraws what it covered. Main thread.
 */
internal class ThreadPreview(private val c: EditorController, private val threads: TextThreads) : MultiLayerRenderOverride {

    private class Shown(val item: TextItem, val prepared: PreparedText, val rect: Rect?)

    private val shown = IdentityHashMap<Layer, Shown>()

    /** Where each previewed layer's stored pixels are (hidden while previewed). */
    private val hiddenInk = IdentityHashMap<Layer, Rect?>()

    private var first: Layer? = null

    override val layer: Layer get() = first ?: shown.keys.first()

    override val layers: Set<Layer> get() = shown.keys

    /** True while it is installed and shows at least one layer. */
    val isActive: Boolean get() = shown.isNotEmpty() && c.renderOverride === this

    /** The pending item drawn for [l] (null when [l] is not previewed). */
    fun itemFor(l: Layer): TextItem? = shown[l]?.item

    override fun drawContent(canvas: Canvas): Boolean = drawContentFor(layer, canvas)

    override fun drawContentFor(target: Layer, canvas: Canvas): Boolean {
        val s = shown[target] ?: return false
        TextRenderer.drawItem(canvas, s.item, s.prepared, null, c.doc.colorMode)
        return true
    }

    /**
     * Shows [items] (frame layer → its pending item) in place of those layers; layers previewed
     * before and not in [items] show their pixels again. Redraws only what changed.
     */
    fun show(items: Map<Layer, TextItem>) {
        if (items.isEmpty()) { clear(); return }
        // Layers that leave the preview: their own pixels come back.
        val gone = shown.keys.filter { it !in items.keys }
        for (l in gone) {
            shown.remove(l)?.rect?.let { c.invalidateDoc(it) }
            hiddenInk.remove(l)?.let { c.invalidateDoc(it) }
        }
        for ((l, item) in items) {
            val old = shown[l]
            if (old != null && old.item == item) continue
            val prep = threads.prepare(item, old?.prepared)
            val rect = StoryWriter.rectOf(item, prep)?.apply { inset(-PREVIEW_SLACK_PX, -PREVIEW_SLACK_PX) }
            shown[l] = Shown(item, prep, rect)
            if (!hiddenInk.containsKey(l)) {
                val ink = threads.writer.inkOf(l, threads.frameOf(l))
                hiddenInk[l] = ink
                ink?.let { c.invalidateDoc(it) }
            }
            old?.rect?.let { if (it != rect) c.invalidateDoc(it) }
            rect?.let { c.invalidateDoc(it) }
        }
        first = shown.keys.firstOrNull()
        if (c.renderOverride !== this) c.renderOverride = this
    }

    /** Stops previewing: every layer shows its own pixels again. */
    fun clear() {
        if (c.renderOverride === this) c.renderOverride = null
        for (s in shown.values) s.rect?.let { c.invalidateDoc(it) }
        for (r in hiddenInk.values) r?.let { c.invalidateDoc(it) }
        shown.clear()
        hiddenInk.clear()
        first = null
    }

    private companion object {
        /** Extra document px redrawn around a previewed frame. */
        const val PREVIEW_SLACK_PX = 2
    }
}
