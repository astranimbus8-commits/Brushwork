package com.brushwork.paint.tools.text

/*
 * v1.7 (item 17, area D): manual kerning. Pure Kotlin (no android imports), unit-tested on the JVM.
 *
 * A kern ([TextKern]) is extra space after one character, the GAP between character `index` and
 * character `index + 1` (UTF-16 indices into the text; for a frame of a linked story, into the
 * story, so reflowing the story between frames never moves a kern). It belongs to the character
 * before the gap: an edit keeps it with that character, and deleting that character deletes it.
 *
 * The editor names gaps by its cursor and selection ([gaps]): a cursor at c (no selection) is the
 * gap between characters c − 1 and c; a selection [s, e) is every gap inside it, s … e − 2.
 */
object TextKerns {

    /** Old text's chars [start, start + removed) replaced by [inserted] new chars. */
    data class Edit(val start: Int, val removed: Int, val inserted: Int)

    /**
     * The one edit that turns [old] into [new], or null when they are equal. [cursor] (the new
     * text's cursor after the edit, −1 when unknown) settles where an edit inside a run of equal
     * characters happened: typing "a" at 1 in "aab" gives "aaab", which an insertion at 0, 1 or 2
     * would all give; the cursor (at 2) says it was 1. Without a cursor the common start wins
     * (an insertion at the end of the run).
     */
    fun diff(old: String, new: String, cursor: Int = -1): Edit? {
        if (old == new) return null
        val limit = minOf(old.length, new.length)
        var suffix = 0
        while (suffix < limit && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
        val prefix: Int
        if (cursor in 0..new.length) {
            // The edit ends at the cursor at the latest: the common end is what follows it.
            suffix = minOf(suffix, new.length - cursor)
            var p = 0
            while (p < limit - suffix && old[p] == new[p]) p++
            prefix = p
        } else {
            var p = 0
            while (p < limit && old[p] == new[p]) p++
            prefix = p
            suffix = minOf(suffix, limit - prefix)
        }
        return Edit(prefix, old.length - suffix - prefix, new.length - suffix - prefix)
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
     * The gaps the editor's cursor or selection names in a text of [length] chars (see the file
     * comment), or null when it names none: a cursor at either end of the text, or a selection
     * of one character.
     */
    fun gaps(selStart: Int, selEnd: Int, length: Int): IntRange? {
        val s = minOf(selStart, selEnd).coerceIn(0, length)
        val e = maxOf(selStart, selEnd).coerceIn(0, length)
        if (s == e) return if (s in 1 until length) (s - 1)..(s - 1) else null
        return if (e - s >= 2) s..(e - 2) else null
    }

    /** Value (1/1000 em) of the kern of [gap], 0 when it has none. [kerns] sorted by index. */
    fun valueAt(kerns: List<TextKern>, gap: Int): Int {
        val i = indexOf(kerns, gap)
        return if (i >= 0) kerns[i].value else 0
    }

    /** The value every gap of [gaps] has (0 = no kern), or null when they differ ("Mixed"). */
    fun commonValue(kerns: List<TextKern>, gaps: IntRange): Int? {
        if (gaps.isEmpty()) return 0
        val first = valueAt(kerns, gaps.first)
        if (gaps.first == gaps.last) return first
        // Kerns inside the range, in order: every gap must have one (of the same value), or none.
        var i = lowerBound(kerns, gaps.first)
        var count = 0
        while (i < kerns.size && kerns[i].index <= gaps.last) {
            if (kerns[i].value != first) return null
            count++
            i++
        }
        val span = gaps.last - gaps.first + 1
        return if (count == 0 || count == span) first else null
    }

    /**
     * [kerns] with every gap of [gaps] set to [value] (0 removes them), sanitized against a text
     * of [length] chars.
     */
    fun withValue(kerns: List<TextKern>, gaps: IntRange, value: Int, length: Int): List<TextKern> {
        if (gaps.isEmpty()) return kerns
        val v = value.coerceIn(TextKern.MIN_VALUE, TextKern.MAX_VALUE)
        val out = ArrayList<TextKern>(kerns.size + if (v != 0) gaps.last - gaps.first + 1 else 0)
        var i = 0
        while (i < kerns.size && kerns[i].index < gaps.first) out += kerns[i++]
        if (v != 0) for (g in gaps) out += TextKern(g, v)
        while (i < kerns.size && kerns[i].index <= gaps.last) i++
        while (i < kerns.size) out += kerns[i++]
        return TextKern.sanitized(out, length)
    }

    /**
     * The extra advance (px) after each character of [text] that the renderer adds, or null
     * when no kern applies. [text] is the source (the item's text, or a linked story) from
     * [offset] on: a kern at source index k is the gap after `text[k − offset]`. [sizePx] is the
     * font size (a kern is `value / 1000` em); [factors] (v1.6 letter scaling: per source
     * character, null when unscaled) scale it with the size of the character before the gap.
     *
     * A kern applies when [supported] (the source can be drawn one cluster at a time:
     * [LetterRamp.supports]; right-to-left and shaping scripts keep their shaping and ignore
     * kerns), its gap lies in [text], is no line break (`\n` or `\r` on either side) and falls
     * between two grapheme clusters (an accent stays on its letter).
     */
    fun advancesPx(text: String, kerns: List<TextKern>, offset: Int, sizePx: Float, supported: Boolean, factors: FloatArray? = null): FloatArray? {
        if (kerns.isEmpty() || !supported || !(sizePx > 0f) || !sizePx.isFinite()) return null
        val n = text.length
        var out: FloatArray? = null
        var bounds: BooleanArray? = null
        val em = sizePx / 1000f
        for (k in kerns) {
            val j = k.index - offset
            if (j < 0) continue
            if (j + 1 >= n) break
            val a = text[j]
            val b = text[j + 1]
            if (a == '\n' || a == '\r' || b == '\n' || b == '\r') continue
            if (needsClusters(a, b)) {
                val bd = bounds ?: clusterStarts(text).also { bounds = it }
                if (!bd[j + 1]) continue
            }
            val f = factors?.getOrNull(offset + j) ?: 1f
            val px = k.value * em * f
            if (px == 0f || !px.isFinite()) continue
            val o = out ?: FloatArray(n).also { out = it }
            o[j] = px
        }
        return out
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
