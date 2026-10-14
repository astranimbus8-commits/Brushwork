package com.brushwork.paint.tools.text

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.brushwork.paint.engine.MemoryGovernor
import com.brushwork.paint.engine.MemoryPressure
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * v1.9 (R5) “Optical” pair kerning (Illustrator's “Optical”): the space between two letters is
 * set from their shapes, not from the font's kerning pairs, so a font without good pairs (or with
 * none) still sets "AV", "To" or "r." evenly.
 *
 * Each letter (a grapheme cluster) has a profile ([BANDS] horizontal bands from the font's ascent
 * to its descent): per band, the white between the letter's origin and its ink, and between its
 * ink and its advance. Two letters side by side leave, per band where both have ink, the sum of
 * the second's left white and the first's right white, at most [DEPTH_EM] (deeper white reads as
 * open space whatever its depth); the pair's gap is the mean over those bands (over the bands
 * where either has ink, a missing side counting [DEPTH_EM], when no band has both). The pair is
 * then moved so its gap equals the font's own "nn" gap, at most [CLAMP_EM] either way, and the
 * font's kerning of the pair is taken back. Pairs with a space and the end of a paragraph are left
 * alone, and so is a paragraph not drawn one cluster at a time (right-to-left or shaped scripts,
 * [TextKerns.applies]): it keeps the font's shaping. A letter drawn inside a ligature (no advance
 * of its own, the "i" of "fi") leaves both its pairs alone.
 *
 * Profiles are measured once per font and letter at [REF_PX] ([Paint.getTextPath], drawn into a
 * small alpha bitmap) and kept in an LRU of [CACHE_SIZE] (about [BYTES_PER_PROFILE] bytes each).
 * A pair never reaches across a space, so a word's kerns depend on the word alone: they are kept
 * per font, size and letter spacing in an LRU of [WORD_CACHE_SIZE] words, and a text laid out
 * again (or typed into: one word changes) measures only its new words. Both are registered with
 * [MemoryGovernor] at priority 0 (a display cache): MODERATE pressure halves them, LOW and
 * CRITICAL empty them. Thread-safe (one lock; the measuring bitmap is shared).
 */
internal object OpticalKerning {
    /** Horizontal bands of a profile, from the font's ascent to its descent. */
    const val BANDS = 24

    /** The em size letters are measured at, px (1 px = 1/100 em). */
    const val REF_PX = 100f

    /** The most an optical kern moves a pair, either way, in em. */
    const val CLAMP_EM = 0.15f

    /** The deepest white a band counts, in em. */
    const val DEPTH_EM = 0.25f

    /** Profiles kept (least recently used dropped first). */
    const val CACHE_SIZE = 4096

    /** What one cached profile holds, about (its key, its 48 bytes and the map's entry). */
    const val BYTES_PER_PROFILE = 160L

    /** Words whose kerns are kept (least recently used dropped first). */
    const val WORD_CACHE_SIZE = 1024

    /** What one cached word holds, about (its key, two small arrays and the map's entry). */
    const val BYTES_PER_WORD = 192L

    /** A profile's white in 1/[UNITS] em, saturated at ±127 (deeper than [DEPTH_EM] makes no difference). */
    private const val UNITS = 400f

    /** A band without ink. */
    private const val NONE: Byte = Byte.MIN_VALUE

    /** Ink at least this opaque (0..255) counts. */
    private const val INK = 128

    /** The widest letter measured, in em (wider ink is cut at its right). */
    private const val MAX_WIDTH_EM = 4

    /** What font, style and features a profile was measured with (shared by every profile of them). */
    private data class Style(val typeface: Typeface?, val fakeBold: Boolean, val skewX: Float, val scaleX: Float, val features: String?, val variation: String?)

    private data class Key(val style: Style, val glyph: String)

    /** A [Style] at a text size and letter spacing (the words' advances depend on both). */
    private data class Sized(val style: Style, val size: Float, val letterSpacing: Float)

    private data class WordKey(val sized: Sized, val word: String)

    /**
     * A word's kerns: per character, the font's kerning [taken] back (px at the word's size, the
     * kerned advance's difference to the plain one), and the optical [kern] after it (em; NaN
     * where the character ends no cluster or its pair is left alone).
     */
    private class Word(val taken: FloatArray, val kern: FloatArray)

    private val lock = Any()

    /** The profiles, least recently used first: [BANDS] left whites, then [BANDS] right whites. */
    private val cache = object : LinkedHashMap<Key, ByteArray>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, ByteArray>?): Boolean = size > CACHE_SIZE
    }

    /** The words' kerns, least recently used first. */
    private val words = object : LinkedHashMap<WordKey, Word>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<WordKey, Word>?): Boolean = size > WORD_CACHE_SIZE
    }

    /** The styles in use (each [Key] shares one), cleared with the cache. */
    private val styles = HashMap<Style, Style>()

    /** The "nn" gap of each style (em, NaN without ink), cleared with the cache. */
    private val targets = HashMap<Style, Float>()

    private var scratch: Bitmap? = null
    private val path = Path()
    private val bounds = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    /** The name of the caches' [MemoryGovernor] entry. */
    const val GOVERNOR_NAME = "Optical kerning profiles"

    init {
        // A display cache (priority 0, trimmed first), registered when Optical is first used.
        MemoryGovernor.register(GOVERNOR_NAME, 0, { bytes() }) { p -> trim(p) }
    }

    /** The profiles cached now. */
    fun size(): Int = synchronized(lock) { cache.size }

    /** The words cached now. */
    fun wordCount(): Int = synchronized(lock) { words.size }

    /** What the caches hold, about ([BYTES_PER_PROFILE] per profile, [BYTES_PER_WORD] per word). */
    fun bytes(): Long = synchronized(lock) { cache.size * BYTES_PER_PROFILE + words.size * BYTES_PER_WORD }

    /**
     * Frees profiles and words as [pressure] asks: MODERATE drops the least recently used half of
     * each, LOW and CRITICAL every one. Returns the bytes freed (about).
     */
    fun trim(pressure: MemoryPressure): Long = synchronized(lock) {
        val before = bytes()
        when (pressure) {
            MemoryPressure.NORMAL -> {}
            MemoryPressure.MODERATE -> {
                dropOldest(cache.entries.iterator(), cache.size / 2)
                dropOldest(words.entries.iterator(), words.size / 2)
            }
            MemoryPressure.LOW, MemoryPressure.CRITICAL -> {
                cache.clear()
                words.clear()
                styles.clear()
                targets.clear()
                scratch?.recycle()
                scratch = null
            }
        }
        before - bytes()
    }

    private fun dropOldest(it: MutableIterator<*>, count: Int) {
        var k = 0
        while (k < count && it.hasNext()) {
            it.next()
            it.remove()
            k++
        }
    }

    /**
     * The extra advance after each character of `source[from, length)` (index 0 = `source[from]`)
     * that sets every pair optically (see the object comment), or null when it moves nothing.
     * [paint] measures as the text is measured (typeface, size, letter spacing and features, the
     * font's kerning on); [factors] (scaled letters, per source character) scale each character's
     * taken-back kerning and each pair's kern with the letter before the gap.
     */
    fun advancesPx(source: String, from: Int, paint: Paint, factors: FloatArray?): FloatArray? {
        val n = source.length
        if (from >= n - 1) return null
        val ref = refPaint(paint)
        val size = paint.textSize
        val applies = TextKerns.applyRule(source)
        var plain: Paint? = null
        var out: FloatArray? = null
        synchronized(lock) {
            val style = styleOf(ref)
            val target = targets.getOrPut(style) { profileLocked(ref, style, "n").let { gap(it, it) } }
            if (target.isNaN()) return null
            val sized = Sized(style, size, paint.letterSpacing)
            var ps = from
            while (ps < n) {
                var pe = source.indexOf('\n', ps)
                if (pe < 0) pe = n
                // A paragraph drawn as a whole (right-to-left, shaped) has no gap a kern applies to.
                var supported: Boolean? = null
                var i = ps
                while (i < pe) {
                    if (source[i].isWhitespace()) { i++; continue }
                    var e = i + 1
                    while (e < pe && !source[e].isWhitespace()) e++
                    if (e - i >= 2) {
                        if (supported == null) supported = (ps until pe - 1).any { applies(it) }
                        if (supported) {
                            val key = WordKey(sized, source.substring(i, e))
                            val w = words[key] ?: measureWord(source, i, e, applies, paint, plain ?: plainOf(paint).also { plain = it }, ref, style, target).also { words[key] = it }
                            out = addWord(w, i, from, size, factors, out, n)
                        }
                    }
                    i = e
                }
                ps = pe + 1
            }
        }
        return out
    }

    /** [paint] with the font's kerning off (otherwise the same). */
    private fun plainOf(paint: Paint): Paint = Paint(paint).apply { fontFeatureSettings = KerningModes.withoutKerning(paint.fontFeatureSettings) }

    /**
     * Adds word [w] (at `source[i]`) to [out] (or to a new array of the extras after
     * `source[from]`, [n] the source's length) and returns it. A cluster's taken-back kerning
     * adds up over its characters (each scaled with its letter) and goes with its pair's kern.
     */
    private fun addWord(w: Word, i: Int, from: Int, size: Float, factors: FloatArray?, out: FloatArray?, n: Int): FloatArray? {
        var o = out
        var acc = 0f
        for (k in w.taken.indices) {
            val f = factors?.getOrNull(i + k) ?: 1f
            acc += w.taken[k] * f
            val kern = w.kern[k]
            if (kern.isNaN()) continue
            val px = acc + kern * size * f
            acc = 0f
            if (px != 0f) (o ?: FloatArray(n - from).also { o = it })[i + k - from] = px
        }
        return o
    }

    /**
     * The kerns of the word `source[a, b)` (no space in it, in a paragraph drawn one cluster at a
     * time) as [paint] sets it: its clusters ([applies]); per pair whose clusters both have ink
     * and an advance of their own, the optical kern after the first and, on the first's
     * characters, the font's kerning taken back (kerned against plain advances, measured in the
     * word). Every other character takes nothing back and has no kern (NaN).
     */
    private fun measureWord(source: String, a: Int, b: Int, applies: (Int) -> Boolean, paint: Paint, plain: Paint, ref: Paint, style: Style, target: Float): Word {
        val len = b - a
        val on = FloatArray(len)
        val off = FloatArray(len)
        paint.getTextWidths(source, a, b, on)
        plain.getTextWidths(source, a, b, off)
        val starts = IntArrayList()
        starts.add(a)
        for (j in a until b - 1) if (applies(j)) starts.add(j + 1)
        val count = starts.size
        // Per cluster its profile; null without ink or without an advance of its own (a ligature's tail).
        val profiles = arrayOfNulls<ByteArray>(count)
        for (c in 0 until count) {
            val s = starts[c]
            val e = if (c + 1 < count) starts[c + 1] else b
            var adv = 0f
            for (j in s until e) adv += on[j - a]
            val glyph = source.substring(s, e)
            profiles[c] = if (adv > 0f && !glyph.isBlank()) profileLocked(ref, style, glyph) else null
        }
        val taken = FloatArray(len)
        val kern = FloatArray(len) { Float.NaN }
        for (c in 0 until count - 1) {
            val p = profiles[c] ?: continue
            val q = profiles[c + 1] ?: continue
            val g = gap(p, q)
            if (g.isNaN()) continue
            val s = starts[c]
            val e = starts[c + 1]
            kern[e - 1 - a] = (target - g).coerceIn(-CLAMP_EM, CLAMP_EM)
            for (j in s until e) taken[j - a] = (off[j - a] - on[j - a]).let { if (it.isFinite()) it else 0f }
        }
        return Word(taken, kern)
    }

    /** The profile of [glyph] in [paint]'s font (see the object comment), measured or cached. */
    internal fun profile(paint: Paint, glyph: String): ByteArray {
        val ref = refPaint(paint)
        return synchronized(lock) { profileLocked(ref, styleOf(ref), glyph) }
    }

    /**
     * The white (em) between [a] and [b] set side by side: the mean over the bands where both have
     * ink of their whites across (each at most [DEPTH_EM]); without such a band, the mean over the
     * bands where either has ink (a side without ink counting [DEPTH_EM]); NaN when neither has ink.
     */
    internal fun gap(a: ByteArray, b: ByteArray): Float {
        val depth = DEPTH_EM * UNITS
        var both = 0f
        var nBoth = 0
        var either = 0f
        var nEither = 0
        for (k in 0 until BANDS) {
            val r = a[BANDS + k]
            val l = b[k]
            if (r == NONE && l == NONE) continue
            if (r != NONE && l != NONE) {
                val d = min(r + l.toFloat(), depth)
                both += d
                nBoth++
                either += d
            } else {
                either += depth
            }
            nEither++
        }
        return when {
            nBoth > 0 -> both / nBoth / UNITS
            nEither > 0 -> either / nEither / UNITS
            else -> Float.NaN
        }
    }

    /** [paint] at [REF_PX], without letter spacing and the font's kerning (it measures one letter). */
    private fun refPaint(paint: Paint): Paint = Paint(paint).apply {
        textSize = REF_PX
        letterSpacing = 0f
        fontFeatureSettings = KerningModes.withoutKerning(paint.fontFeatureSettings)
        textAlign = Paint.Align.LEFT
        style = Paint.Style.FILL
        isAntiAlias = true
        shader = null
        maskFilter = null
        pathEffect = null
    }

    private fun styleOf(ref: Paint): Style {
        val s = Style(ref.typeface, ref.isFakeBoldText, ref.textSkewX, ref.textScaleX, ref.fontFeatureSettings, ref.fontVariationSettings)
        return styles.getOrPut(s) { s }
    }

    private fun profileLocked(ref: Paint, style: Style, glyph: String): ByteArray {
        val key = Key(style, glyph)
        cache[key]?.let { return it }
        return measure(ref, glyph).also { cache[key] = it }
    }

    /** Measures [glyph]'s profile with [ref] (at [REF_PX]); every band [NONE] when it has no ink. */
    private fun measure(ref: Paint, glyph: String): ByteArray {
        val out = ByteArray(2 * BANDS) { NONE }
        val fm = ref.fontMetrics
        val top = fm.ascent
        val height = ceil(fm.descent - top).toInt().coerceAtLeast(BANDS)
        val advance = ref.measureText(glyph)
        path.rewind()
        ref.getTextPath(glyph, 0, glyph.length, 0f, 0f, path)
        path.computeBounds(bounds, true)
        if (bounds.isEmpty) return out
        val x0 = floor(bounds.left).toInt() - 1
        val width = (ceil(bounds.right).toInt() + 1 - x0).coerceIn(1, (MAX_WIDTH_EM * REF_PX).toInt())
        val bmp = scratch?.takeIf { it.width >= width && it.height >= height }
            ?: Bitmap.createBitmap(maxOf(width, scratch?.width ?: 0, (2 * REF_PX).toInt()), maxOf(height, scratch?.height ?: 0), Bitmap.Config.ALPHA_8).also {
                scratch?.recycle()
                scratch = it
            }
        bmp.eraseColor(0)
        val canvas = Canvas(bmp)
        canvas.translate(-x0.toFloat(), -top)
        canvas.drawPath(path, fill)
        val stride = bmp.rowBytes
        val px = ByteArray(stride * bmp.height)
        bmp.copyPixelsToBuffer(ByteBuffer.wrap(px))
        for (k in 0 until BANDS) {
            val y0 = k * height / BANDS
            val y1 = (k + 1) * height / BANDS
            var minX = Int.MAX_VALUE
            var maxX = -1
            for (y in y0 until y1) {
                val row = y * stride
                for (x in 0 until minOf(width, minX)) if ((px[row + x].toInt() and 0xFF) >= INK) { minX = x; break }
                var x = width - 1
                while (x > maxX) {
                    if ((px[row + x].toInt() and 0xFF) >= INK) { maxX = x; break }
                    x--
                }
            }
            if (maxX < 0) continue
            out[k] = units((minX + x0) / REF_PX)
            out[BANDS + k] = units((advance - (maxX + 1 + x0)) / REF_PX)
        }
        return out
    }

    /** [em] in 1/[UNITS] em, saturated (never [NONE]). */
    private fun units(em: Float): Byte = (em * UNITS).roundToInt().coerceIn(-127, 127).toByte()

    /** A growable list of ints (no boxing). */
    private class IntArrayList {
        private var a = IntArray(16)
        var size = 0
            private set

        fun clear() { size = 0 }

        fun add(v: Int) {
            if (size == a.size) a = a.copyOf(size * 2)
            a[size++] = v
        }

        operator fun get(i: Int): Int = a[i]
    }
}
