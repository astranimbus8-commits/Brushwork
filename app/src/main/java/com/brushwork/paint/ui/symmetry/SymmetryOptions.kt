package com.brushwork.paint.ui.symmetry

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brushwork.paint.EditorController
import com.brushwork.paint.assist.SymmetryHandles
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.tools.symmetry.SymmetryTool
import com.brushwork.paint.ui.common.ChoiceChips
import com.brushwork.paint.ui.common.NumberField
import com.brushwork.paint.ui.common.SymmetryLabels
import com.brushwork.paint.ui.common.ToolIconButton
import kotlin.math.roundToInt

/**
 * The Symmetry tool's options strip (v1.7 item 18, §3.18; area H): the type chips
 * (`SymmetryType.label`), then the chosen ruler's numbers as "Label value ▾" chips that open a
 * field with a slider ("Divisions" and "Angle" for the kaleidoscope and rotation, "Angle" for the
 * mirror, "Spacing X", "Spacing Y" and "Angle" for the array; the perspective array is shaped on
 * the canvas), "Reset symmetry" and "Done" (back to the last painting tool). Every change goes
 * through `updateSymmetry` (not undoable, like the ruler). Emits one Row, like every tool's
 * options.
 */
@Composable
fun SymmetryOptions(tool: SymmetryTool) {
    val controller = tool.controller
    val s by remember(controller) { derivedStateOf { controller.symmetry.sanitized() } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        ChoiceChips(
            options = SymmetryType.entries.map { it.label },
            selected = s.type.ordinal,
            onSelect = { i -> SymmetryUi.update(controller) { it.copy(type = SymmetryType.entries[i]) } },
        )
        Spacer(Modifier.width(8.dp))
        when (s.type) {
            SymmetryType.KALEIDOSCOPE, SymmetryType.ROTATION -> {
                DivisionsChip(controller, s)
                AngleChip(controller, s)
            }
            SymmetryType.MIRROR -> AngleChip(controller, s)
            SymmetryType.ARRAY -> {
                ValueChip("Spacing X", "${SymmetryUi.px(s.spacingX)} px") { SpacingField(controller, "Spacing X", s.spacingX) { v -> SymmetryUi.update(controller) { it.copy(spacingX = v) } } }
                ValueChip("Spacing Y", "${SymmetryUi.px(s.spacingY)} px") { SpacingField(controller, "Spacing Y", s.spacingY) { v -> SymmetryUi.update(controller) { it.copy(spacingY = v) } } }
                AngleChip(controller, s)
            }
            SymmetryType.PERSPECTIVE_ARRAY, SymmetryType.OFF -> {}
        }
        ToolIconButton(
            icon = Icons.Filled.RestartAlt,
            contentDescription = SymmetryLabels.RESET,
            onClick = { SymmetryUi.update(controller) { SymmetryUi.reset(it) } },
            enabled = s != SymmetryUi.reset(s),
            size = 40.dp,
        )
        Button(
            onClick = { controller.selectTool(controller.lastPaintTool) },
            contentPadding = PaddingValues(horizontal = 14.dp),
        ) { Text("Done") }
    }
}

@Composable
private fun DivisionsChip(controller: EditorController, s: SymmetrySettings) {
    ValueChip(SymmetryLabels.DIVISIONS, "${s.divisions}") { DivisionsField(controller, s.divisions) }
}

@Composable
private fun AngleChip(controller: EditorController, s: SymmetrySettings) {
    val shown = SymmetryUi.shownAngle(s)
    ValueChip("Angle", "${SymmetryUi.formatAngle(shown)}°") {
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
private fun SpacingField(controller: EditorController, label: String, value: Float, onChange: (Float) -> Unit) {
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

/** A "Label value ▾" chip opening [content] (a field with its slider) below it. */
@Composable
private fun ValueChip(label: String, value: String, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text("$label $value", maxLines = 1) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, Modifier.size(AssistChipDefaults.IconSize)) },
            modifier = Modifier.padding(end = 6.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.width(300.dp).padding(horizontal = 16.dp, vertical = 4.dp)) { content() }
        }
    }
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
