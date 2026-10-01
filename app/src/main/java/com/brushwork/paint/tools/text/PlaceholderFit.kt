package com.brushwork.paint.tools.text

import kotlin.math.max
import kotlin.math.min

/**
 * Fits placeholder text into a text box, measured with the real layout ([TextRenderer.layout],
 * StaticLayout for horizontal text): the result is the longest run of whole placeholder words
 * (Japanese: characters) that fits, so one more would overflow. Pure computation on its inputs
 * (no shared state): may run on a background thread.
 */
object PlaceholderFit {
    /** Most characters a fill produces (a huge box at a tiny size would otherwise take long). */
    const val MAX_CHARS = 20_000

    private const val EPS = 0.01f

    /** Height (horizontal) or width (vertical) of [text] laid out in [spec]'s box, without its minimum size. */
    fun extent(text: String, spec: TextSpec): Float {
        val bare = spec.copy(box = spec.box.copy(minHeight = 0f, minWidth = 0f))
        // Only the text area counts (not the ink margin): skip measuring every glyph's ink.
        val block = TextRenderer.layout(text, bare, measureInk = false)
        return if (spec.vertical) block.contentWidth else block.contentHeight
    }

    /**
     * Whether [text] fits a box of [spec]'s wrap length and [depth] across. [wrapped]: the text
     * flows around a picture (v1.5), measured with its wrapped layout in a box [depth] tall.
     */
    fun fits(text: String, spec: TextSpec, depth: Float, wrapped: TextItem? = null): Boolean {
        if (wrapped != null && wrapped.wrapActive && !spec.vertical) {
            return TextRenderer.wrappedTextHeight(wrapped.copy(spec = spec), text, depth + 2f * spec.box.inset) <= depth + EPS
        }
        return extent(text, spec) <= depth + EPS
    }

    /**
     * [prefix] followed by as many placeholder units of [kind] as fit [spec]'s box (fixed width
     * for horizontal text, fixed height for vertical text) when it is [depth] tall (horizontal)
     * or wide (vertical). Null when not even one unit fits (or [spec] has no fixed box). [wrapped]:
     * the text flows around a picture (see [fits]).
     */
    fun fill(prefix: String, kind: PlaceholderKind, spec: TextSpec, depth: Float, wrapped: TextItem? = null): String? {
        if (spec.box.wrapFor(spec.vertical) <= 0f || !(depth > 0f)) return null
        val tokens = PlaceholderText.tokens(kind, max(0, MAX_CHARS - prefix.length))
        if (tokens.isEmpty()) return null
        val full = StringBuilder(prefix)
        val ends = IntArray(tokens.size)
        tokens.forEachIndexed { i, t ->
            // The first unit after existing text needs no leading space of its own.
            full.append(if (i == 0 && (prefix.isEmpty() || prefix.last().isWhitespace())) t.trimStart() else t)
            ends[i] = full.length
        }
        val all = full.toString()
        fun textOf(n: Int) = all.substring(0, ends[n - 1])
        val memo = HashMap<Int, Boolean>()
        fun ok(n: Int): Boolean = memo.getOrPut(n) { fits(textOf(n), spec, depth, wrapped) }
        if (!ok(1)) return null
        val n = tokens.size
        // Grow until it overflows, then bisect: about 2 x log2(words) layouts.
        var lo = 1
        var hi = 2
        while (hi <= n && ok(hi)) { lo = hi; hi *= 2 }
        if (hi > n) {
            if (ok(n)) return textOf(n)
            hi = n
        }
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (ok(mid)) lo = mid else hi = mid
        }
        // Line breaking isn't strictly monotonic: make sure the next unit really overflows.
        var steps = 0
        while (lo < n && steps < 16 && ok(lo + 1)) { lo++; steps++ }
        return textOf(lo)
    }

    /**
     * The other side of the box a fill fills: [TextBoxSpec.minHeight] (horizontal) or
     * [TextBoxSpec.minWidth] (vertical) when set, else three quarters of the wrap length (at
     * least one line / column).
     */
    fun depthFor(spec: TextSpec): Float {
        val set = spec.box.depthFor(spec.vertical)
        if (set > 0f) return set
        val wrap = spec.box.wrapFor(spec.vertical)
        val line = spec.sizePx * max(1f, spec.lineSpacing) * (if (spec.vertical) 1f else 1.2f)
        return max(line, wrap * 0.75f)
    }

    /** A comfortable wrap length for a paragraph of [spec] on a [docW] x [docH] canvas. */
    fun paragraphWrap(spec: TextSpec, docW: Int, docH: Int, maxBox: Float): Float {
        val want = if (spec.vertical) min(docH * 0.8f, spec.sizePx * 16f) else min(docW * 0.8f, spec.sizePx * 22f)
        return want.coerceIn(min(spec.sizePx, maxBox), maxBox)
    }

    /** Everything [edit] needs, captured on the main thread. */
    class Request(
        val item: TextItem,
        val kind: PlaceholderKind,
        val amount: PlaceholderAmount,
        val replace: Boolean,
        val docW: Int,
        val docH: Int,
        val maxBox: Float,
    ) {
        fun edit(): Edit? = edit(item, kind, amount, replace, docW, docH, maxBox)
    }

    /** New text and look of a text object after inserting placeholder text. */
    class Edit(val text: String, val spec: TextSpec)

    /**
     * [item] with placeholder text of [kind]: replacing its text or after it ([replace]).
     * [PlaceholderAmount.FILL] (straight text with a fixed box width, or height for vertical
     * text) fills the box exactly: the box gets its other side ([depthFor]) as a minimum, so it
     * shows the filled area. Other amounts insert a fixed text; a paragraph in a text without a
     * fixed box gets a comfortable wrap length ([paragraphWrap]) instead of one endless line.
     * Null when a fill has no room for even one word.
     */
    fun edit(item: TextItem, kind: PlaceholderKind, amount: PlaceholderAmount, replace: Boolean, docW: Int, docH: Int, maxBox: Float): Edit? {
        val spec = item.spec
        val straight = !item.path.isActive
        val base = if (replace) "" else item.text
        if (amount == PlaceholderAmount.FILL && straight && spec.box.wrapFor(spec.vertical) > 0f) {
            val depth = depthFor(spec)
            val boxed = spec.copy(box = if (spec.vertical) spec.box.copy(minWidth = depth) else spec.box.copy(minHeight = depth))
            val text = fill(base + PlaceholderText.separator(base, kind, amount), kind, boxed, depth, item.takeIf { it.wrapActive }) ?: return null
            return Edit(text, boxed)
        }
        val amt = if (amount == PlaceholderAmount.FILL) PlaceholderAmount.PARAGRAPH else amount
        val text = base + PlaceholderText.separator(base, kind, amt) + PlaceholderText.text(kind, amt)
        var out = spec
        if (straight && amt != PlaceholderAmount.SHORT && spec.box.wrapFor(spec.vertical) <= 0f) {
            val wrap = paragraphWrap(spec, docW, docH, maxBox)
            val natural = TextRenderer.layout(text, spec, measureInk = false)
            val along = if (spec.vertical) natural.contentHeight else natural.contentWidth
            if (along > wrap) out = spec.copy(box = if (spec.vertical) spec.box.copy(height = wrap) else spec.box.copy(width = wrap))
        }
        return Edit(text, out)
    }
}
