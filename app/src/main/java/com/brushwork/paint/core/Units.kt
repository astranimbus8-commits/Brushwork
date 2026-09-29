package com.brushwork.paint.core

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Physical / screen length units. Every conversion goes through pixels using the
 * document DPI, so "3 cm" on a 350 dpi canvas is 413.4 px.
 */
enum class LengthUnit(val label: String, val short: String) {
    PX("Pixels", "px"),
    IN("Inches", "in"),
    CM("Centimeters", "cm"),
    MM("Millimeters", "mm"),
    PT("Points", "pt");

    fun toPx(value: Double, dpi: Double): Double = when (this) {
        PX -> value
        IN -> value * dpi
        CM -> value / 2.54 * dpi
        MM -> value / 25.4 * dpi
        PT -> value / 72.0 * dpi
    }

    fun fromPx(px: Double, dpi: Double): Double = when (this) {
        PX -> px
        IN -> px / dpi
        CM -> px / dpi * 2.54
        MM -> px / dpi * 25.4
        PT -> px / dpi * 72.0
    }

    /** Sensible number of decimals when displaying a value in this unit. */
    val decimals: Int
        get() = when (this) {
            PX -> 1
            IN -> 3
            CM -> 2
            MM -> 1
            PT -> 1
        }

    /** A sensible default nudge step for arrow buttons, expressed in this unit. */
    val defaultStep: Double
        get() = when (this) {
            PX -> 1.0
            IN -> 0.01
            CM -> 0.1
            MM -> 1.0
            PT -> 1.0
        }
}

object Units {
    fun convert(value: Double, from: LengthUnit, to: LengthUnit, dpi: Double): Double =
        if (from == to) value else to.fromPx(from.toPx(value, dpi), dpi)

    /** Formats [px] in [unit], trimming trailing zeros: "12.5 cm", "300 px". */
    fun format(px: Double, unit: LengthUnit, dpi: Double, withSuffix: Boolean = true): String {
        val v = unit.fromPx(px, dpi)
        val s = formatNumber(v, unit.decimals)
        return if (withSuffix) "$s ${unit.short}" else s
    }

    fun formatNumber(v: Double, decimals: Int): String {
        if (decimals <= 0) return v.roundToInt().toString()
        var factor = 1.0
        repeat(decimals) { factor *= 10.0 }
        val rounded = Math.round(v * factor) / factor
        if (abs(rounded - Math.rint(rounded)) < 1e-9) return rounded.toLong().toString()
        var s = String.format(java.util.Locale.US, "%.${decimals}f", rounded)
        s = s.trimEnd('0').trimEnd('.')
        return s
    }

    /** Parses user text like "12,5" or "12.5"; returns null for garbage. */
    fun parse(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()
}
