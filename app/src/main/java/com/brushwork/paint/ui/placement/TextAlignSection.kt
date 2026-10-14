package com.brushwork.paint.ui.placement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignCenter
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.brushwork.paint.tools.text.TextAlign
import com.brushwork.paint.tools.text.TextJustify
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.ui.common.SectionHeader
import com.brushwork.paint.ui.common.TextLabels19
import com.brushwork.paint.ui.common.ToolIconButton
import com.brushwork.paint.ui.theme.BrushworkColors

/**
 * The text sheet's Alignment section (v1.9, R4): the three alignment buttons ("Left", "Center",
 * "Right"; vertical text "Top", "Center", "Bottom") and, for horizontal text, the switch rows
 * “Justify lines” and “Justify the last line too” (enabled only while the first is on).
 *
 * With “Justify lines” on, the buttons set the LAST line's alignment, which gives Illustrator's
 * seven modes: the caption “Last line:” stands before them and each button's stateDescription
 * reads "Last line: <its label>". With “Justify the last line too” on, every line is justified
 * and the buttons are disabled (they would change nothing). Vertical text keeps the three buttons
 * only (v1.9 justifies horizontal text); text on a path has no Alignment section (C23). [style]
 * applies a change to the text's look, as the sheet's other rows do.
 */
@Composable
internal fun TextAlignSection(spec: TextSpec, style: ((TextSpec) -> TextSpec) -> Unit) {
    val vertical = spec.vertical
    val justify = if (vertical) TextJustify.OFF else spec.justify
    val on = justify != TextJustify.OFF
    val all = justify == TextJustify.ALL
    SectionHeader(if (vertical) "Column alignment" else "Alignment")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (on) {
            // Shrinks (and wraps) before it pushes a button off a narrow sheet at a large font scale.
            Text(
                TextLabels19.LAST_LINE,
                style = MaterialTheme.typography.bodyMedium,
                color = BrushworkColors.OnChromeDim,
                modifier = Modifier.weight(1f, fill = false).padding(end = 4.dp),
            )
        }
        TextAlign.entries.forEach { a ->
            val icon = when (a) {
                TextAlign.START -> if (vertical) Icons.Filled.VerticalAlignTop else Icons.AutoMirrored.Filled.FormatAlignLeft
                TextAlign.CENTER -> if (vertical) Icons.Filled.VerticalAlignCenter else Icons.Filled.FormatAlignCenter
                TextAlign.END -> if (vertical) Icons.Filled.VerticalAlignBottom else Icons.AutoMirrored.Filled.FormatAlignRight
            }
            val label = if (vertical) a.verticalLabel else a.horizontalLabel
            ToolIconButton(
                icon, label,
                onClick = { style { it.copy(align = a) } },
                selected = spec.align == a,
                enabled = !all,
                modifier = if (on) Modifier.semantics { stateDescription = "${TextLabels19.LAST_LINE} $label" } else Modifier,
            )
        }
    }
    if (!vertical) {
        JustifyRow(TextLabels19.JUSTIFY, on, enabled = true) { v ->
            style { it.copy(justify = if (v) TextJustify.ON else TextJustify.OFF) }
        }
        JustifyRow(TextLabels19.JUSTIFY_LAST, all, enabled = on) { v ->
            style { it.copy(justify = if (v) TextJustify.ALL else TextJustify.ON) }
        }
    }
}

/**
 * A switch row as the sheet's `ToggleRow` (the whole row toggles), which can be disabled: dimmed
 * and not toggleable. At least 48 dp tall, so it is a full finger target with or without a note.
 */
@Composable
private fun JustifyRow(label: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) LocalContentColor.current else BrushworkColors.OnChromeDim,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
