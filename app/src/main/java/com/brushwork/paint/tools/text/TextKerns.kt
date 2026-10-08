package com.brushwork.paint.tools.text

/*
 * v1.7 (item 17, area D): manual kerning. Pure Kotlin (no android imports), unit-tested on the JVM.
 *
 * A kern ([TextKern]) is extra space after one character, the GAP between character `index` and
 * character `index + 1` (UTF-16 indices into the text; for a frame of a linked story, into the
 * story, so reflowing the story between frames never moves a kern). It belongs to the character
 * before the gap: an edit keeps it with that character, and deleting that character deletes it.
 *
 * The editor names gaps by its cursor and selection ([gaps]), between grapheme clusters only (a
 * letter with its accents, an emoji with its skin tone is one letter): a cursor at c (no selection)
 * is the gap between characters c − 1 and c; a selection [s, e) is every gap between two of the
 * clusters it touches.
 */
object TextKerns {

    /** Old text's chars [start, start + removed) replaced by [inserted] new chars. */
    data class Edit(val start: Int, val removed: Int, val inserted: Int)

    /**
     * The one edit that turns [old] into [new], or null when they are equal. [cursor] (the new
     * text's cursor after the edit, −1 when unknown) settles where an edit inside a run of equal
     * characters happened: typing "a" at 1 in "aab" gives "aaab", which an insertion at 0, 1 or 2
     * would all give; the cursor (at 2) says it was 1. Without a cursor the common start wins
     * (an insertion at the end of the run). A cursor that no smallest edit ends at (a whole
     * text set at once, which leaves the cursor at its end) is not used.
     */
    fun diff(old: String, new: String, cursor: Int = -1): Edit? {
        if (old == new) return null
        val limit = minOf(old.length, new.length)
        var common = 0
        while (common < limit && old[old.length - 1 - common] == new[new.length - 1 - common]) common++
        var p = 0
        while (p < limit && old[p] == new[p]) p++
        val free = Edit(p, old.length - minOf(common, limit - p) - p, new.length - minOf(common, limit - p) - p)
        if (cursor !in 0..new.length) return free
        // The edit ends at the cursor at the latest: the common end is what follows it.
        val suffix = minOf(common, new.length - cursor)
        var prefix = 0
        while (prefix < limit - suffix && old[prefix] == new[prefix]) prefix++
        val atCursor = Edit(prefix, old.length - suffix - prefix, new.length - suffix - prefix)
        return if (atCursor.removed + atCursor.inserted == free.removed + free.inserted) atCursor else free
    }

    /**
     * [kerns] after [edit]: kerns of characters before the edit stay (also the one of the
     * character right before it: its gap now leads into the new characters), kerns of removed
     * characters go, kerns after the edit move by `inserted − removed`. Not sanitized against the
     * new length (see [edited]); the same list when nothing moves.
     */
    fun remap(kerns: List<TextKern>, edit: Edit): List<TextKern> = remap(kerns, edit.start, edit.removed, edit.inserted)

    /** [remap] of the edit replacing [removed] chars at [start] by [inserted] chars. */
    fun remap(kerns: List<TextKern>, start: Int, removed: Int, inserted: Int): List<TextKern> {
        if (kerns.isEmpty()) return kerns
        val end = start + removed
        val shift = inserted - removed
        if (kerns.last().index < start) return kerns
        val out = ArrayList<TextKern>(kerns.size)
        for (k in kerns) {
            when {
                k.index < start -> out += k
                k.index < end -> Unit // its character was removed
                else -> out += if (shift == 0) k else k.copy(index = k.index + shift)
            }
        }
        return out
    }

    /**
     * The kerns of [old] (indexed into it) for its edit [new]: [diff] (with [cursor], see there)
     * then [remap], sanitized against the new text ([TextKern.sanitized]). The same list when
     * nothing changes.
     */
    fun edited(kerns: List<TextKern>, old: String, new: String, cursor: Int = -1): List<TextKern> {
        if (kerns.isEmpty()) return kerns
        val e = diff(old, new, cursor) ?: return kerns
        return TextKern.sanitized(remap(kerns, e), new.length)
    }

    /** The kerns of `text[start, end)` re-indexed into that slice (gaps inside it only). */
    fun slice(kerns: List<TextKern>, start: Int, end: Int): List<TextKern> {
        if (kerns.isEmpty()) return kerns
        val out = ArrayList<TextKern>()
        for (k in kerns) if (k.index >= start && k.index + 1 < end) out += TextKern(k.index - start, k.value)
        return out
    }

    /** [kerns] with every index moved by [by] (a text appended after [by] characters). */
    fun shifted(kerns: List<TextKern>, by: Int): List<TextKern> =
        if (by == 0 || kerns.isEmpty()) kerns else kerns.map { TextKern(it.index + by, it.value) }

    /**
     * The gaps the editor's cursor or selection names in [text] (see the file comment), in order,
     * or null when it names none. Only gaps between grapheme clusters ([LetterRamp.clusterBounds],
     * as [advancesPx] finds them), so no kern is ever stored where it can't apply inside a letter:
     * - a cursor names the gap at it: none at either end of the text, or inside a cluster;
     * - a selection first grows to whole clusters (one that starts or ends inside an emoji with
     *   its skin tone, or a letter with its accent, takes all of it), then names every cluster
     *   boundary inside it: none when it holds one cluster.
     */
    fun gaps(selStart: Int, selEnd: Int, text: String): List<Int>? {
        val n = text.length
        val s0 = minOf(selStart, selEnd).coerceIn(0, n)
        val e0 = maxOf(selStart, selEnd).coerceIn(0, n)
        val clusters = Clusters(text)
        if (s0 == e0) return if (s0 in 1 until n && clusters.isBoundary(s0)) listOf(s0 - 1) else null
        val e = clusters.ceil(e0)
        val s = clusters.floor(s0)
        var out: ArrayList<Int>? = null
        for (b in s + 1 until e) {
            if (clusters.isBoundary(b)) (out ?: ArrayList<Int>().also { out = it }) += b - 1
        }
        return out
    }

    /**
     * Whether a kern of [gap] (after `text[gap]`) moves the letters after it, as [advancesPx]
     * decides: no line break on either side, a grapheme cluster boundary, and a paragraph that
     * can be drawn one cluster at a time ([LetterRamp.supports]).
     */
    fun applies(text: String, gap: Int): Boolean = GapRule(text).check(gap) == GapRule.APPLIES

    /** The gaps of [gaps] a kern applies to in [text] ([applies]), in their order. */
    fun applying(text: String, gaps: Iterable<Int>): List<Int> {
        val rule = GapRule(text)
        return gaps.filter { rule.check(it) == GapRule.APPLIES }
    }

    /** Why a kern can't apply to any gap the editor names (the Kerning row then says so). */
    enum class Refusal {
        /** The gaps are beside line breaks. */
        LINE_BREAK,

        /** A gap's paragraph is right-to-left or in a script whose letters are shaped together. */
        SCRIPT,
    }

    /**
     * Why no gap of [gaps] takes a kern in [text] ([applies]), or null when one does (or [gaps]
     * is empty). A script that keeps its shaping is the reason when any gap meets one.
     */
    fun refusal(text: String, gaps: Iterable<Int>): Refusal? {
        val rule = GapRule(text)
        var any = false
        var script = false
        for (g in gaps) {
            any = true
            when (rule.check(g)) {
                GapRule.APPLIES -> return null
                GapRule.SCRIPT -> script = true
            }
        }
        return when {
            !any -> null
            script -> Refusal.SCRIPT
            else -> Refusal.LINE_BREAK
        }
    }

    /** The start of the grapheme cluster [p] of [text] is in (as [gaps] finds clusters). */
    internal fun clusterStart(text: String, p: Int): Int = Clusters(text).floor(p)

    /** The end of the grapheme cluster that starts at [p] of [text] (its length at the end). */
    internal fun clusterEnd(text: String, p: Int): Int {
        if (p >= text.length) return text.length
        return Clusters(text).ceil(p + 1)
    }

    /** Value (1/1000 em) of the kern of [gap], 0 when it has none. [kerns] sorted by index. */
    fun valueAt(kerns: List<TextKern>, gap: Int): Int {
        val i = indexOf(kerns, gap)
        return if (i >= 0) kerns[i].value else 0
    }

    /** The value every gap of [gaps] has (0 = no kern), or null when they differ ("Mixed"). */
    fun commonValue(kerns: List<TextKern>, gaps: Iterable<Int>): Int? {
        val it = gaps.iterator()
        if (!it.hasNext()) return 0
        val first = valueAt(kerns, it.next())
        while (it.hasNext()) if (valueAt(kerns, it.next()) != first) return null
        return first
    }

    /**
     * [kerns] with every gap of [gaps] set to [value] (0 removes them), sanitized against a text
     * of [length] chars.
     */
    fun withValue(kerns: List<TextKern>, gaps: Iterable<Int>, value: Int, length: Int): List<TextKern> {
        val set = gaps.toSortedSet()
        if (set.isEmpty()) return kerns
        val v = value.coerceIn(TextKern.MIN_VALUE, TextKern.MAX_VALUE)
        val out = ArrayList<TextKern>(kerns.size + if (v != 0) set.size else 0)
        for (k in kerns) if (k.index !in set) out += k
        if (v != 0) for (g in set) out += TextKern(g, v)
        return TextKern.sanitized(out, length)
    }

    /**
     * [kerns] with every gap of [gaps] moved by [delta] (1/1000 em; a gap without a kern starts
     * at 0, so mixed values keep their differences), each clamped, sanitized against a text of
     * [length] chars.
     */
    fun nudged(kerns: List<TextKern>, gaps: Iterable<Int>, delta: Int, length: Int): List<TextKern> {
        if (delta == 0) return kerns
        val set = gaps.toSortedSet()
        if (set.isEmpty()) return kerns
        val out = ArrayList<TextKern>(kerns.size + set.size)
        for (k in kerns) if (k.index !in set) out += k
        for (g in set) {
            val v = (valueAt(kerns, g).toLong() + delta).coerceIn(TextKern.MIN_VALUE.toLong(), TextKern.MAX_VALUE.toLong()).toInt()
            if (v != 0) out += TextKern(g, v)
        }
        return TextKern.sanitized(out, length)
    }

    /**
     * The extra advance (px) after each character of `source[from, length)` that the renderer
     * adds (index 0 = `source[from]`), or null when no kern applies. [source] is the item's text,
     * or a linked story ([from]: the frame's start). [sizePx] is the font size (a kern is
     * `value / 1000` em); [factors] (v1.6 letter scaling: per source character, null when
     * unscaled) scale it with the size of the character before the gap.
     *
     * A kern applies when its gap lies at or after [from], is no line break (`\n` or `\r` on
     * either side), falls between two grapheme clusters (an accent stays on its letter), and its
     * PARAGRAPH (the source between line breaks, also its part before [from]) can be drawn one
     * cluster at a time ([LetterRamp.supports]): right-to-left and shaping scripts keep their
     * shaping and ignore kerns, and a line holding them is never drawn in pieces. What a kern
     * does depends only on its paragraph.
     */
    fun advancesPx(source: String, kerns: List<TextKern>, from: Int, sizePx: Float, factors: FloatArray? = null): FloatArray? {
        if (kerns.isEmpty() || !(sizePx > 0f) || !sizePx.isFinite()) return null
        val n = source.length
        var out: FloatArray? = null
        val em = sizePx / 1000f
        val rule = GapRule(source)
        for (k in kerns) {
            val i = k.index
            if (i < from) continue
            if (rule.check(i) != GapRule.APPLIES) continue
            val f = factors?.getOrNull(i) ?: 1f
            val px = k.value * em * f
            if (px == 0f || !px.isFinite()) continue
            val o = out ?: FloatArray(n - from).also { out = it }
            o[i - from] = px
        }
        return out
    }

    /**
     * The one rule of where a kern applies ([advancesPx], [applies], [refusal]) for gaps of
     * [text]: asked gap by gap, in any order, it keeps the paragraph of the last one asked
     * (whether it is supported) so a run of gaps looks at each paragraph once.
     */
    private class GapRule(private val text: String) {
        private val clusters = Clusters(text)

        // The paragraph of the last gap looked at: [ps, pe) and whether it is supported.
        private var ps = 0
        private var pe = -1
        private var supported = false

        /** [APPLIES], or why a kern of gap [i] (after `text[i]`) changes nothing. */
        fun check(i: Int): Int {
            if (i < 0 || i + 1 >= text.length) return OUTSIDE
            val a = text[i]
            val b = text[i + 1]
            if (a == '\n' || a == '\r' || b == '\n' || b == '\r') return LINE_BREAK
            if (i >= pe || i < ps) {
                ps = text.lastIndexOf('\n', i) + 1
                pe = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                supported = supports(text, ps, pe)
            }
            if (!supported) return SCRIPT
            return if (clusters.isBoundary(i + 1)) APPLIES else INSIDE_CLUSTER
        }

        companion object {
            const val APPLIES = 0
            const val OUTSIDE = 1
            const val LINE_BREAK = 2
            const val SCRIPT = 3
            const val INSIDE_CLUSTER = 4
        }
    }

    /**
     * The grapheme cluster boundaries of [text] as the renderer finds them: between two
     * characters below U+0300 always (plain Latin text needs no break iterator), at a line
     * break, and otherwise those of the paragraph ([LetterRamp.clusterBounds] of the text between
     * line breaks), measured once while the positions asked stay inside it.
     */
    private class Clusters(private val text: String) {
        // The paragraph measured last: [ps, pe) and its cluster starts.
        private var ps = 0
        private var pe = -1
        private var starts: BooleanArray? = null

        /** Whether a grapheme cluster starts at [p] (both ends of the text included). */
        fun isBoundary(p: Int): Boolean {
            if (p <= 0 || p >= text.length) return true
            val a = text[p - 1]
            val b = text[p]
            if (a == '\n' || b == '\n' || !needsClusters(a, b)) return true
            if (p <= ps || p >= pe) {
                ps = text.lastIndexOf('\n', p - 1) + 1
                pe = text.indexOf('\n', p).let { if (it < 0) text.length else it }
                starts = null
            }
            val s = starts ?: clusterStarts(text.substring(ps, pe)).also { starts = it }
            return s[p - ps]
        }

        /** The boundary at or before [p]. */
        fun floor(p: Int): Int {
            var q = p.coerceIn(0, text.length)
            while (!isBoundary(q)) q--
            return q
        }

        /** The boundary at or after [p]. */
        fun ceil(p: Int): Int {
            var q = p.coerceIn(0, text.length)
            while (!isBoundary(q)) q++
            return q
        }
    }

    /** [LetterRamp.supports] of `source[start, end)` (plain ASCII without a copy). */
    private fun supports(source: String, start: Int, end: Int): Boolean {
        for (i in start until end) if (source[i].code >= 0x80) return LetterRamp.supports(source.substring(start, end))
        return true
    }

    /** Whether a cluster boundary between [a] and [b] can't be assumed (plain Latin characters always have one). */
    private fun needsClusters(a: Char, b: Char): Boolean = a.code >= 0x300 || b.code >= 0x300

    /** Per index of [text] (and its length): whether a grapheme cluster starts there. */
    private fun clusterStarts(text: String): BooleanArray {
        val out = BooleanArray(text.length + 1)
        for (p in LetterRamp.clusterBounds(text)) out[p] = true
        return out
    }

    /** Position of the kern of [gap] in [kerns] (sorted by index), or −1. */
    private fun indexOf(kerns: List<TextKern>, gap: Int): Int {
        val i = lowerBound(kerns, gap)
        return if (i < kerns.size && kerns[i].index == gap) i else -1
    }

    /** First position in [kerns] (sorted by index) whose index is ≥ [gap]. */
    private fun lowerBound(kerns: List<TextKern>, gap: Int): Int {
        var lo = 0
        var hi = kerns.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (kerns[mid].index < gap) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
