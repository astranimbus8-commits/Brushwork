package com.brushwork.paint.filters.art

import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import com.brushwork.paint.filters.FringePreset
import com.brushwork.paint.ui.common.FringeLabels18

/**
 * v1.8 (item 5, design §3.6): the fringe colors of Chromatic aberration (Moving, Zooming) and
 * Glitch. A value is three ARGB swatches: the first fringe (moves forward), the middle one (stays)
 * and the last one (moves back); [NONE] (0) is a swatch with no color (FringeLabels18.NO_COLOR).
 *
 * The filters run one of two paths:
 * - **Fast path** ([oneHotFactors]): every swatch channel is 0 or 255, each channel is 255 in
 *   exactly one swatch, and each colored swatch holds at least one channel. Each channel then
 *   comes from one sample, and the v1.7 code runs with its direction factors: bit-exact with v1.7
 *   for the six v1.7 orders (default RGB included), and as fast.
 * - **General path** ([Mix]): channel c of the output is the swatch-weighted mean of the three
 *   samples' (coverage-weighted) channel c, `Σ_i F_i,c · P_i,c / Σ_i F_i,c`; a channel no swatch
 *   covers comes from the middle (unshifted) sample. The alpha is the largest alpha among the
 *   samples whose swatch is colored. With no colored swatch at all the filter returns the source.
 *   Each channel keeps its own coverage, as in v1.7 (its alpha-weighted value over the output
 *   alpha); an uncovered channel's middle sample, whose alpha the output alpha may not include
 *   (a middle swatch with no color), is divided by the larger of the two, so it never comes out
 *   brighter than that sample's own straight value ([Mix.uncovered]).
 *
 * Only the swatches' red, green and blue bytes count: a swatch's alpha is ignored (the picker
 * opens without alpha), and only the exact value 0 has no color.
 */
internal object FringeColors {
    const val NONE = 0
    const val RED = 0xFFFF0000.toInt()
    const val GREEN = 0xFF00FF00.toInt()
    const val BLUE = 0xFF0000FF.toInt()
    const val CYAN = 0xFF00FFFF.toInt()
    const val MAGENTA = 0xFFFF00FF.toInt()
    const val YELLOW = 0xFFFFFF00.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()

    /** The param key of the fringe colors, in all three filters. */
    const val KEY = "fringes"

    /** Chromatic aberration's v1.7 "Color order" key, read when [KEY] is absent. */
    const val LEGACY_ORDER_KEY = "order"

    /** v1.7's colors: red forward, green in the middle, blue back. A new array each call. */
    val rgb: IntArray get() = intArrayOf(RED, GREEN, BLUE)

    /** The presets of design §3.6: RGB, then the three two-color sets with no middle color. */
    val PRESETS: List<FringePreset> = listOf(
        FringePreset(FringeLabels18.RGB, intArrayOf(RED, GREEN, BLUE)),
        FringePreset(FringeLabels18.RED_CYAN, intArrayOf(RED, NONE, CYAN)),
        FringePreset(FringeLabels18.MAGENTA_GREEN, intArrayOf(MAGENTA, NONE, GREEN)),
        FringePreset(FringeLabels18.BLUE_YELLOW, intArrayOf(BLUE, NONE, YELLOW)),
    )

    /** The fringe-colors param; [legacyOrderKey] is Chromatic aberration's [LEGACY_ORDER_KEY], Glitch's null. */
    fun param(legacyOrderKey: String?): FilterParam.Fringes =
        FilterParam.Fringes(KEY, FringeLabels18.FRINGE_COLORS, PRESETS, rgb, legacyOrderKey)

    /**
     * The colors of v1.7 order [index] (as `ArtMath.colorOrderNames` lists them): letter i of the
     * order's name is the primary of fringe i. Null out of range, as `FilterValuesCodec` reads it.
     */
    fun legacyOrder(index: Int): IntArray? {
        val name = ArtMath.colorOrderNames.getOrNull(index) ?: return null
        return IntArray(3) { i ->
            when (name[i]) {
                'R' -> RED
                'G' -> GREEN
                else -> BLUE
            }
        }
    }

    /**
     * The colors a filter draws with (a new array): the stored three colors; when they are absent,
     * the legacy order of [FilterParam.Fringes.legacyOrderKey] (a v1.7 Chromatic aberration's
     * values); else the param's default. Never through `FilterValues.colors`, which would hide a
     * legacy order behind RGB.
     */
    fun resolve(values: FilterValues, p: FilterParam.Fringes): IntArray {
        (values.raw(p.key) as? IntArray)?.takeIf { it.size == 3 }?.let { return it.copyOf() }
        val legacy = p.legacyOrderKey?.let { values.raw(it) as? Number }?.let { legacyOrder(it.toInt()) }
        return legacy ?: p.default.copyOf()
    }

    /** True when no swatch has a color. */
    fun allNone(colors: IntArray): Boolean = colors[0] == NONE && colors[1] == NONE && colors[2] == NONE

    /**
     * The fast path's direction factors for R, G and B (as v1.7's `ArtMath.colorOrderFactors`:
     * +1 for the first fringe, 0 the middle, -1 the last), or null when the general path must run:
     * a channel byte other than 0 or 255, a channel at 255 in no swatch or in two, or a colored
     * swatch that holds no channel (its alpha would count on the general path only).
     */
    fun oneHotFactors(colors: IntArray): IntArray? {
        val k = IntArray(3)
        for (c in 0 until 3) {
            val shift = 16 - 8 * c
            var at = -1
            for (i in 0 until 3) {
                when ((colors[i] shr shift) and 0xFF) {
                    0 -> {}
                    255 -> if (at >= 0) return null else at = i
                    else -> return null
                }
            }
            if (at < 0) return null
            k[c] = 1 - at
        }
        for (i in 0 until 3) if (colors[i] != NONE && (colors[i] and 0xFFFFFF) == 0) return null
        return k
    }

    /**
     * The general path's mix of three swatches. [w] holds, at `3 * i + c`, the weight of sample i
     * in channel c: `F_i,c / Σ_j F_j,c`, or 1 for the middle sample of a channel no swatch covers.
     * [colored] marks the samples whose alpha counts. [fixed] is [w] in 1/65536 with each
     * channel's three summing to exactly 65536 (Glitch's pixels whose three samples share their
     * alpha: integer arithmetic, a uniform picture stays exact). [uncovered] marks the channels no
     * swatch covers (taken from the middle sample, divided by the larger of the output alpha and
     * the middle sample's alpha).
     */
    class Mix(colors: IntArray) {
        val w = FloatArray(9)
        val fixed = IntArray(9)
        val colored = BooleanArray(3) { colors[it] != NONE }
        val uncovered = BooleanArray(3)

        init {
            for (c in 0 until 3) {
                val shift = 16 - 8 * c
                var sum = 0
                for (i in 0 until 3) if (colors[i] != NONE) sum += (colors[i] shr shift) and 0xFF
                if (sum == 0) {
                    w[3 + c] = 1f
                    uncovered[c] = true
                } else {
                    for (i in 0 until 3) {
                        val f = if (colors[i] == NONE) 0 else (colors[i] shr shift) and 0xFF
                        w[3 * i + c] = f.toFloat() / sum
                    }
                }
            }
            for (c in 0 until 3) {
                var sum = 0
                var big = 0
                for (i in 0 until 3) {
                    fixed[3 * i + c] = Math.round(w[3 * i + c] * 65536f)
                    sum += fixed[3 * i + c]
                    if (w[3 * i + c] > w[3 * big + c]) big = i
                }
                fixed[3 * big + c] += 65536 - sum
            }
        }
    }
}
