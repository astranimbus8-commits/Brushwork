package com.brushwork.paint.ui.filters

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.filters.GradientStop
import kotlin.math.abs

/** Editing rules for gradient stops. Every function returns a list sorted by position. */
object GradientEditing {

    fun sorted(stops: List<GradientStop>): List<GradientStop> =
        stops.map { GradientStop(it.position.coerceIn(0f, 1f), it.color) }.sortedBy { it.position }

    /** Color at [t] (0..1): linear between the surrounding stops, flat beyond the ends. */
    fun sample(stops: List<GradientStop>, t: Float): Int {
        if (stops.isEmpty()) return 0xFF000000.toInt()
        val s = sorted(stops)
        if (t <= s.first().position) return s.first().color
        if (t >= s.last().position) return s.last().color
        for (i in 0 until s.lastIndex) {
            val a = s[i]; val b = s[i + 1]
            if (t <= b.position) {
                val span = b.position - a.position
                return if (span <= 1e-6f) b.color else ColorUtils.lerp(a.color, b.color, (t - a.position) / span)
            }
        }
        return s.last().color
    }

    /** Index of the stop nearest to [position] within [radius], or -1. */
    fun hitTest(stops: List<GradientStop>, position: Float, radius: Float): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        stops.forEachIndexed { i, s ->
            val d = abs(s.position - position)
            if (d <= radius && d < bestD) { best = i; bestD = d }
        }
        return best
    }

    /** Adds a stop at [position] with the gradient's current color there. Returns the list and the new index. */
    fun add(stops: List<GradientStop>, position: Float): Pair<List<GradientStop>, Int> {
        val p = position.coerceIn(0f, 1f)
        val stop = GradientStop(p, sample(stops, p))
        return place(sorted(stops), stop)
    }

    /** Moves stop [index] to [position]; returns the re-sorted list and the stop's new index. */
    fun move(stops: List<GradientStop>, index: Int, position: Float): Pair<List<GradientStop>, Int> {
        if (index !in stops.indices) return stops to index
        val moved = GradientStop(position.coerceIn(0f, 1f), stops[index].color)
        return place(stops.toMutableList().apply { removeAt(index) }, moved)
    }

    fun recolor(stops: List<GradientStop>, index: Int, color: Int): List<GradientStop> =
        if (index !in stops.indices) stops else stops.toMutableList().apply { set(index, GradientStop(stops[index].position, color)) }

    fun canRemove(stops: List<GradientStop>, index: Int): Boolean = stops.size > 2 && index in stops.indices

    fun remove(stops: List<GradientStop>, index: Int): List<GradientStop> =
        if (canRemove(stops, index)) stops.toMutableList().apply { removeAt(index) } else stops

    /** Mirrors the gradient (first color becomes last). */
    fun reverse(stops: List<GradientStop>): List<GradientStop> = sorted(stops.map { GradientStop(1f - it.position, it.color) })

    private fun place(others: List<GradientStop>, stop: GradientStop): Pair<List<GradientStop>, Int> {
        val list = others.toMutableList()
        // After stops at the same position, so a moved stop keeps a stable order.
        val at = list.indexOfFirst { it.position > stop.position }.let { if (it < 0) list.size else it }
        list.add(at, stop)
        return list to at
    }

    /** Ready-made gradients offered under the editor. */
    val presets: List<Pair<String, List<GradientStop>>> = listOf(
        "Black → White" to listOf(GradientStop(0f, 0xFF000000.toInt()), GradientStop(1f, 0xFFFFFFFF.toInt())),
        "Sepia" to listOf(GradientStop(0f, 0xFF1E1206.toInt()), GradientStop(0.5f, 0xFF8C6239.toInt()), GradientStop(1f, 0xFFF5E6C8.toInt())),
        "Sunset" to listOf(
            GradientStop(0f, 0xFF2A0845.toInt()), GradientStop(0.35f, 0xFFB0305C.toInt()),
            GradientStop(0.7f, 0xFFF77F3A.toInt()), GradientStop(1f, 0xFFFFE08A.toInt()),
        ),
        "Ocean" to listOf(GradientStop(0f, 0xFF02111D.toInt()), GradientStop(0.5f, 0xFF0E6BA8.toInt()), GradientStop(1f, 0xFFA6E1FA.toInt())),
        "Fire" to listOf(
            GradientStop(0f, 0xFF000000.toInt()), GradientStop(0.35f, 0xFF8B0000.toInt()),
            GradientStop(0.7f, 0xFFFF7F00.toInt()), GradientStop(1f, 0xFFFFFF80.toInt()),
        ),
        "Duotone" to listOf(GradientStop(0f, 0xFF1B1464.toInt()), GradientStop(1f, 0xFFFFC1CC.toInt())),
        "Rainbow" to listOf(
            GradientStop(0f, 0xFFFF0000.toInt()), GradientStop(0.2f, 0xFFFF9900.toInt()), GradientStop(0.4f, 0xFFFFEE00.toInt()),
            GradientStop(0.6f, 0xFF33CC33.toInt()), GradientStop(0.8f, 0xFF3366FF.toInt()), GradientStop(1f, 0xFF9933CC.toInt()),
        ),
        "Fade" to listOf(GradientStop(0f, 0x00000000), GradientStop(1f, 0xFF000000.toInt())),
    )
}
