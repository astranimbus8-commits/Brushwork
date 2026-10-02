package com.brushwork.paint.ui.layers

import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextThreadSpec

/**
 * The linked-story thread of each text layer, as the layer window's rows show it (the frame
 * badge ⛓ "k/m"). Decoding a frame's text data parses its whole story (up to 50 000 characters,
 * copied in every frame), so the result is kept per layer for the identity of its `textData`
 * string: a layer list refresh re-parses only the frames that actually changed. Main thread.
 */
class FrameInfoCache {
    private class Entry(val data: String, val thread: TextThreadSpec?)

    private val entries = HashMap<Long, Entry>()

    /** [layer]'s thread when it is a frame of a linked story, else null. */
    fun threadOf(layer: Layer): TextThreadSpec? {
        val data = layer.textData ?: run { entries.remove(layer.id); return null }
        val cached = entries[layer.id]
        if (cached != null && cached.data === data) return cached.thread
        val thread = TextCodec.decode(data)?.thread?.takeIf { it.isOn }
        entries[layer.id] = Entry(data, thread)
        return thread
    }

    /** Frame badges of [layers] (null for layers that are not frames), in the same order. */
    fun badges(layers: List<Layer>): List<FrameBadge?> = LayerListMath.frameBadges(layers.map(::threadOf))

    /** Drops entries of layers that no longer exist. */
    fun retain(ids: Set<Long>) {
        entries.keys.retainAll(ids)
    }

    /** Number of cached layers (tests). */
    val size: Int get() = entries.size
}
