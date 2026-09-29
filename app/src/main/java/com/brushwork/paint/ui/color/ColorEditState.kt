package com.brushwork.paint.ui.color

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.brushwork.paint.core.ColorUtils

/**
 * The color being edited by a picker: the packed ARGB [color] plus the [hsb] the wheel and HSB
 * sliders show (kept separately so hue/saturation stay put for grays and black).
 * [onUserChange] fires for edits made through the picker, not for [setColor] with notify = false.
 */
@Stable
class ColorEditState(initial: Int, initialHsb: Hsb, private val onUserChange: (Int) -> Unit) {
    var color by mutableIntStateOf(initial)
        private set
    var hsb by mutableStateOf(initialHsb)
        private set

    val alpha: Int get() = color ushr 24

    /** Sets hue (degrees 0..360), saturation and brightness (0..1); alpha is kept. */
    fun setHsb(h: Float = hsb.h, s: Float = hsb.s, b: Float = hsb.b) {
        val next = Hsb(h.coerceIn(0f, 360f), s.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
        hsb = next
        update(next.toColor(alpha), true)
    }

    /** Sets the full ARGB color (hue/saturation are kept if [c] is gray or black). */
    fun setColor(c: Int, notify: Boolean = true) {
        hsb = Hsb.fromColor(c, hsb)
        update(c, notify)
    }

    /** Sets R, G, B (0..255) keeping alpha. */
    fun setRgb(r: Int, g: Int, b: Int) = setColor(ColorUtils.argb(alpha, r, g, b))

    fun setAlpha(a: Int) = update(ColorUtils.withAlpha(color, a), true)

    private fun update(c: Int, notify: Boolean) {
        color = c
        PickerMemory.record(c, hsb)
        if (notify) onUserChange(c)
    }

    companion object {
        fun saver(onUserChange: (Int) -> Unit) = listSaver<ColorEditState, Any>(
            save = { listOf(it.color, it.hsb.h, it.hsb.s, it.hsb.b) },
            restore = { ColorEditState(it[0] as Int, Hsb(it[1] as Float, it[2] as Float, it[3] as Float), onUserChange) },
        )
    }
}

/** A [ColorEditState] that survives configuration changes. */
@Composable
fun rememberColorEditState(initial: Int, onUserChange: (Int) -> Unit = {}): ColorEditState {
    val callback by rememberUpdatedState(onUserChange)
    val relay: (Int) -> Unit = { callback(it) }
    return rememberSaveable(saver = ColorEditState.saver(relay)) {
        ColorEditState(initial, PickerMemory.hsbFor(initial), relay)
    }
}

/**
 * Remembers the HSB of the last edited color for the whole process, so reopening a picker on
 * black or gray shows the hue the user was working with instead of snapping to red.
 */
internal object PickerMemory {
    private var rgb = -1
    private var hsb: Hsb? = null

    fun hsbFor(color: Int): Hsb {
        val last = hsb
        return if (last != null && (color and 0xFFFFFF) == rgb) last else Hsb.fromColor(color, last)
    }

    fun record(color: Int, value: Hsb) {
        rgb = color and 0xFFFFFF
        hsb = value
    }
}
