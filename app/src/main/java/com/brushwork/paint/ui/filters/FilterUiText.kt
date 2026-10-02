package com.brushwork.paint.ui.filters

import com.brushwork.paint.core.Units
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterParam
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/** Value snapping and display text for slider parameters. */
object SliderFormat {

    /** Clamps to the range and snaps to the parameter's step (if any). */
    fun snap(p: FilterParam.Slider, v: Float): Float {
        val c = v.coerceIn(p.min, p.max)
        if (p.step <= 0f) return c
        return (p.min + ((c - p.min) / p.step).roundToInt() * p.step).coerceIn(p.min, p.max)
    }

    /** One press of the -/+ buttons. */
    fun increment(p: FilterParam.Slider): Float {
        if (p.step > 0f) return p.step
        val raw = (p.max - p.min) / 100f
        if (raw <= 0f) return 0f
        // Round to 1, 2 or 5 x 10^n so the value text stays tidy.
        val mag = 10f.pow(kotlin.math.floor(kotlin.math.log10(raw)))
        val n = raw / mag
        return mag * when { n < 1.5f -> 1f; n < 3.5f -> 2f; n < 7.5f -> 5f; else -> 10f }
    }

    fun nudge(p: FilterParam.Slider, v: Float, direction: Int): Float = snap(p, v + direction * increment(p))

    fun decimals(p: FilterParam.Slider): Int {
        if (p.step >= 1f) return 0
        if (p.step > 0f) {
            for (d in 0..4) {
                val scaled = p.step * 10f.pow(d)
                if (abs(scaled - scaled.roundToInt()) < 1e-3f) return d
            }
            return 4
        }
        val range = p.max - p.min
        return when { range >= 100f -> 0; range >= 10f -> 1; else -> 2 }
    }

    /** True for exposure-like values shown signed with fixed decimals ("+0.50 EV", §4.8a). */
    fun isSigned(p: FilterParam.Slider): Boolean = p.suffix == "EV"

    /** "12 px", "50%", "0.35", "45°"; exposure "+0.50 EV", "0.00 EV", "-1.25 EV". */
    fun format(p: FilterParam.Slider, v: Float): String {
        if (isSigned(p)) {
            val s = String.format(java.util.Locale.US, "%.${decimals(p)}f", v)
            // A value that rounds to zero is shown unsigned ("0.00", never "-0.00" or "+0.00").
            val text = when {
                s.none { it in '1'..'9' } -> s.trimStart('-')
                v > 0f -> "+$s"
                else -> s
            }
            return "$text ${p.suffix}"
        }
        val number = if (p.step >= 1f) v.roundToInt().toString() else Units.formatNumber(v.toDouble(), decimals(p))
        val suffix = when {
            p.pixels -> "px"
            else -> p.suffix
        }
        if (suffix.isEmpty()) return number
        return if (suffix.first().isLetter()) "$number $suffix" else "$number$suffix"
    }
}

/** Short descriptions and extra search words of filters (v1.5; owned by A5 after the foundation). */
object FilterDescriptions {
    private val descriptions = mapOf(
        "adjust.tone" to "Exposure, contrast, highlights, shadows, whites and blacks, like Lightroom's tone panel. Colors keep their hue.",
    )

    private val keywords = mapOf(
        "adjust.tone" to "exposure lightroom shadows highlights whites blacks",
    )

    /** One-sentence description of [f], or null. */
    fun of(f: Filter): String? = descriptions[f.id]

    /** Extra lowercase words [f] is found by in the search (empty when none). */
    fun keywords(f: Filter): String = keywords[f.id] ?: ""
}

/** Filter search for the browser. */
object FilterSearch {
    /**
     * Filters whose name, category title or search keywords ([FilterDescriptions.keywords])
     * contain every word of [query] (case-insensitive). Name-prefix matches come first, then
     * other name matches, then category / keyword matches.
     */
    fun search(filters: List<Filter>, query: String): List<Filter> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return filters
        fun rank(f: Filter): Int? {
            val name = f.name.lowercase()
            val cat = f.category.title.lowercase()
            val extra = FilterDescriptions.keywords(f)
            if (!words.all { name.contains(it) || cat.contains(it) || extra.contains(it) }) return null
            return when {
                name.startsWith(words.first()) -> 0
                words.all { name.contains(it) } -> 1
                else -> 2
            }
        }
        return filters.mapNotNull { f -> rank(f)?.let { f to it } }.sortedBy { it.second }.map { it.first }
    }
}
