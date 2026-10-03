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
 * exactly what `frameLayout` itself measured and hands it back to the next layout of the same
 * tail, so flowing and drawing a chain measure each tail once.
 *
 * A tail is found by its TEXT (and the look): typing in the first frame of a long story moves the
 * later frames' starts, but their tails are often the very same characters as before, which are
 * not measured again. Scaled letters (§3.5) also depend on the letters before the tail (each
 * letter's place in the ramp): a scaled tail found by its text is only taken when it was measured
 * with the factors the item's story gives it ([TextRenderer.canReuseMeasured]); otherwise it is a
 * miss. So retyping a letter or adding a space in frame 1 finds the later tails again, a letter
 * more or less (with "Whole text") measures them again, and with "Each paragraph" an edit in an
 * earlier paragraph finds the tails of the later paragraphs again.
 *
 * Exactness (I1, I7): nothing here derives a measurement; an entry is only ever the [WrapText]
 * `frameLayout` produced for that very tail and look, handed out only when `frameLayout` would
 * reuse it (the same check: the text and, for scaled letters, the factors), so a frame laid out
 * through the cache equals a fresh layout of it.
 *
 * Main thread (it is not synchronized). Bounded by the characters it holds.
 */
class StoryMeasureCache internal constructor(private val maxChars: Int = DEFAULT_MAX_CHARS) {

    /**
     * Where a measurement is filed: the tail's text, the look without the frame's size, the
     * imported fonts. (Scaled letters' factors are checked on [get]: one entry per tail text.)
     */
    private data class Key(val text: String, val look: TextSpec, val fonts: Int)

    private val map = LinkedHashMap<Key, WrapText>(16, 0.75f, true)
    private var chars = 0L

    /** Hits and misses since this cache was made (tests and diagnostics). */
    var hits = 0
        private set
    var misses = 0
        private set

    /** Characters of the measured tails held now. */
    val size: Long get() = chars

    private fun keyOf(item: TextItem, tail: String?): Key? {
        val th = item.thread
        if (!th.isOn) return null
        val start = th.start.coerceIn(0, th.story.length)
        return Key(tail ?: th.story.substring(start), lookOf(item.spec), FontStore.generation)
    }

    /**
     * The measured tail of frame [item]'s story from its start, if this cache has it measured as
     * [TextRenderer.frameLayout] measures it for [item] (scaled letters: with the same factors).
     */
    fun get(item: TextItem): WrapText? {
        val k = keyOf(item, null) ?: return null
        val w = map[k]?.takeIf { TextRenderer.canReuseMeasured(item, it) }
        if (w != null) hits++ else misses++
        return w
    }

    /** Keeps [measured], the tail `frameLayout` measured for frame [item]. */
    fun put(item: TextItem, measured: WrapText) {
        val th = item.thread
        val start = th.start.coerceIn(0, th.story.length)
        if (measured.text.length != th.story.length - start) return
        val k = keyOf(item, measured.text) ?: return
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
        /**
         * Characters of measured tails kept (about 11 bytes each, 15 with scaled letters, whose
         * tails keep their factors: 6 MB, or 9 MB with scaled letters, at most). A 50,000-character
         * story in 10 frames has about 275,000 characters of tails.
         */
        const val DEFAULT_MAX_CHARS = 600_000

        /** The text of the stand-in preparation (never a frame's text in practice; checked anyway). */
        private const val STAND_IN_TEXT = "\u0000￿ frame stand-in ￿\u0000"

        /** What a frame's measurement depends on in [spec]: everything but the frame's size. */
        fun lookOf(spec: TextSpec): TextSpec = FrameGeometry.storyLook(spec)
    }
}
