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

    /** "12 px", "50%", "0.35", "45°". */
    fun format(p: FilterParam.Slider, v: Float): String {
        val number = if (p.step >= 1f) v.roundToInt().toString() else Units.formatNumber(v.toDouble(), decimals(p))
        val suffix = when {
            p.pixels -> "px"
            else -> p.suffix
        }
        if (suffix.isEmpty()) return number
        return if (suffix.first().isLetter()) "$number $suffix" else "$number$suffix"
    }
}

/** Filter search for the browser. */
object FilterSearch {
    /**
     * Filters whose name or category title contains every word of [query] (case-insensitive).
     * Name-prefix matches come first, then other name matches, then category-only matches.
     */
    fun search(filters: List<Filter>, query: String): List<Filter> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return filters
        fun rank(f: Filter): Int? {
            val name = f.name.lowercase()
            val cat = f.category.title.lowercase()
            if (!words.all { name.contains(it) || cat.contains(it) }) return null
            return when {
                name.startsWith(words.first()) -> 0
                words.all { name.contains(it) } -> 1
                else -> 2
            }
        }
        return filters.mapNotNull { f -> rank(f)?.let { f to it } }.sortedBy { it.second }.map { it.first }
    }
}
