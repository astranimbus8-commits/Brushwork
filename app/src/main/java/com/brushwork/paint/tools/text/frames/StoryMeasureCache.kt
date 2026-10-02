package com.brushwork.paint.tools.text.frames

import com.brushwork.paint.fonts.FontStore
import com.brushwork.paint.tools.text.PreparedText
import com.brushwork.paint.tools.text.TextFrameLayout
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.text.WrapText

/**
 * The measured stories of linked text frames (v1.6, §3.6c budgets; area D). Laying out a frame
 * ([TextRenderer.frameLayout]) measures `story.substring(start)`; a re-flow computes every frame's
 * end that way and then draws the frames, which would measure every tail twice. This cache keeps
 * exactly what `frameLayout` itself measured, keyed by (story, start, look), and hands it back to
 * the next layout of the same tail, so flowing and drawing a chain measure each tail once.
 *
 * Exactness (I1): nothing here derives a measurement; an entry is only ever the [WrapText]
 * `frameLayout` produced for that very tail and look (`frameLayout` checks the text again before
 * it reuses one), so a frame laid out through the cache equals a fresh layout of it.
 *
 * Main thread (it is not synchronized). Bounded by the characters it holds.
 */
class StoryMeasureCache internal constructor(private val maxChars: Int = DEFAULT_MAX_CHARS) {

    /** What a measurement depends on besides the text: the look without the frame's size, and the imported fonts. */
    private data class Key(val story: String, val start: Int, val look: TextSpec, val fonts: Int)

    private val map = LinkedHashMap<Key, WrapText>(16, 0.75f, true)
    private var chars = 0L

    /** Hits and misses since this cache was made (tests and diagnostics). */
    var hits = 0
        private set
    var misses = 0
        private set

    private fun keyOf(item: TextItem): Key? {
        val th = item.thread
        if (!th.isOn) return null
        return Key(th.story, th.start, lookOf(item.spec), FontStore.generation)
    }

    /** The measured tail of frame [item]'s story from its start, if this cache has it. */
    fun get(item: TextItem): WrapText? {
        val k = keyOf(item) ?: return null
        val w = map[k]
        if (w != null) hits++ else misses++
        return w
    }

    /** Keeps [measured], the tail `frameLayout` measured for frame [item]. */
    fun put(item: TextItem, measured: WrapText) {
        val k = keyOf(item) ?: return
        if (measured.text.length != item.thread.story.length - item.thread.start) return
        val old = map.put(k, measured)
        if (old != null) chars -= old.text.length
        chars += measured.text.length
        trim()
    }

    private fun trim() {
        val it = map.entries.iterator()
        while (chars > maxChars && map.size > 1 && it.hasNext()) {
            val e = it.next()
            chars -= e.value.text.length
            it.remove()
        }
    }

    /** [TextRenderer.frameLayout] of frame [probe], measuring its tail only when this cache doesn't have it. */
    fun frameLayout(probe: TextItem): TextFrameLayout {
        val fl = TextRenderer.frameLayout(probe, get(probe))
        put(probe, fl.measured)
        return fl
    }

    /**
     * Frame [item] ready to draw: [TextRenderer.prepare] (so the renderer's own threaded path
     * lays it out), handed this cache's measured tail as the measurement to reuse. [reuse] (the
     * frame's previous preparation) is returned as it is when it still matches.
     */
    fun prepare(item: TextItem, reuse: PreparedText? = null): PreparedText {
        if (reuse != null && reuse.matches(item)) return reuse
        if (!item.thread.isOn) return TextRenderer.prepare(item, reuse)
        val measured = get(item) ?: return TextRenderer.prepare(item, reuse).also { p -> p.wrapText?.let { put(item, it) } }
        // A stand-in that never matches the item (its text can't be the frame's) but carries the
        // look and the measured tail: prepare() then lays the frame out reusing that measurement.
        if (item.text == STAND_IN_TEXT) return TextRenderer.prepare(item, reuse)
        val standIn = PreparedText(STAND_IN_TEXT, item.spec, item.path, null, null, null, wrapText = measured, thread = item.thread)
        return TextRenderer.prepare(item, standIn)
    }

    fun clear() {
        map.clear()
        chars = 0
    }

    companion object {
        /** Characters of measured tails kept (about 10 bytes each). */
        const val DEFAULT_MAX_CHARS = 1_500_000

        /** The text of the stand-in preparation (never a frame's text in practice; checked anyway). */
        private const val STAND_IN_TEXT = "\u0000￿ frame stand-in ￿\u0000"

        /** What a frame's measurement depends on in [spec]: everything but the frame's size. */
        fun lookOf(spec: TextSpec): TextSpec = FrameGeometry.storyLook(spec)
    }
}
