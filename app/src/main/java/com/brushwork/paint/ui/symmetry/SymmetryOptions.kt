package com.brushwork.paint.ui.symmetry

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.brushwork.paint.EditorController
import com.brushwork.paint.assist.SymmetryHandles
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.ui.common.NumberField
import com.brushwork.paint.ui.common.SymmetryLabels
import kotlin.math.roundToInt

// The Symmetry tool's value fields (opened from its strip, SymmetryStrip.kt, and the Ruler panel) and
// what the symmetry controls share.

/** "Angle" (the shown angle, -180..180°) as a field with a slider. */
@Composable
internal fun AngleField(controller: EditorController, s: SymmetrySettings) {
    val shown = SymmetryUi.shownAngle(s)
    NumberField(
        label = "Angle",
        value = shown.toDouble(),
        onValueChange = { v -> if (v.isFinite()) SymmetryUi.update(controller) { it.copy(angleDeg = SymmetryUi.storedAngle(v.toFloat())) } },
        modifier = Modifier.fillMaxWidth(),
        decimals = 1,
        suffix = "°",
        step = 1.0,
        sliderMin = -180.0,
        sliderMax = 180.0,
    )
}

/** "Divisions" (2..32) as a field with a slider; shared with the Ruler panel. */
@Composable
internal fun DivisionsField(controller: EditorController, divisions: Int) {
    NumberField(
        label = SymmetryLabels.DIVISIONS,
        value = divisions.toDouble(),
        onValueChange = { v -> if (v.isFinite()) SymmetryUi.update(controller) { it.copy(divisions = v.roundToInt()) } },
        modifier = Modifier.fillMaxWidth(),
        decimals = 0,
        min = SymmetrySettings.MIN_DIVISIONS.toDouble(),
        max = SymmetrySettings.MAX_DIVISIONS.toDouble(),
        step = 1.0,
        sliderMin = SymmetrySettings.MIN_DIVISIONS.toDouble(),
        sliderMax = SymmetrySettings.MAX_DIVISIONS.toDouble(),
    )
}

@Composable
internal fun SpacingField(controller: EditorController, label: String, value: Float, onChange: (Float) -> Unit) {
    val d = controller.doc
    NumberField(
        label = label,
        value = value.toDouble(),
        onValueChange = { v -> if (v.isFinite()) onChange(v.toFloat().coerceIn(SymmetrySettings.MIN_SPACING, SymmetrySettings.MAX_SPACING)) },
        modifier = Modifier.fillMaxWidth(),
        decimals = 1,
        suffix = "px",
        min = SymmetrySettings.MIN_SPACING.toDouble(),
        max = SymmetrySettings.MAX_SPACING.toDouble(),
        step = 1.0,
        sliderMin = 8.0,
        sliderMax = maxOf(d.width, d.height, 16).toDouble(),
        logSlider = true,
    )
}

/** What the symmetry controls share (the tool's strip and the Ruler panel). */
internal object SymmetryUi {
    /** Applies [block] to the current settings, sanitized (one live, not undoable change). */
    fun update(controller: EditorController, block: (SymmetrySettings) -> SymmetrySettings) {
        controller.updateSymmetry(block(controller.symmetry.sanitized()).sanitized())
    }

    /** "Reset symmetry": the defaults (centre, angle, divisions, spacings, cell), keeping the ruler chosen. */
    fun reset(s: SymmetrySettings): SymmetrySettings = SymmetrySettings(type = s.type)

    /**
     * The angle as the controls show it: 0° is the default (a vertical mirror, the first spoke
     * straight down, an upright array grid), as `angleDeg` − 90 in (-180, 180].
     */
    fun shownAngle(s: SymmetrySettings): Float = SymmetryHandles.normalized(s.angleDeg - 90f)

    /** The stored `angleDeg` of a [shown] angle. */
    fun storedAngle(shown: Float): Float = SymmetryHandles.normalized(shown + 90f)

    fun formatAngle(a: Float): String {
        val r = (a * 10f).roundToInt() / 10f
        return if (r == r.roundToInt().toFloat()) "${r.roundToInt()}" else "$r"
    }

    fun px(v: Float): String = if (v == v.roundToInt().toFloat()) "${v.roundToInt()}" else "${(v * 10f).roundToInt() / 10f}"
}
