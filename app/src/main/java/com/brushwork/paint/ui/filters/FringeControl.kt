package com.brushwork.paint.ui.filters

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.ui.color.ColorPickerDialog
import com.brushwork.paint.ui.common.ColorSwatch
import com.brushwork.paint.ui.common.FringeLabels18
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.theme.BrushworkColors

/**
 * v1.8 (item 5): the control of a [FilterParam.Fringes]: its preset chips (tagged
 * [V18Tags.FRINGE_PREFIX] + the preset's name), the three fringe swatches (first, middle, last;
 * each opens the color picker, a pick replaces that color), and a "No color" chip beside the
 * middle swatch that sets it to 0 (D16). The color picker itself is unchanged.
 *
 * F1 stub (minimal, working): area A owns this file and finishes its look.
 */
@Composable
internal fun FringeControl(host: ParamHost, p: FilterParam.Fringes, enabled: Boolean) {
    var picking by remember { mutableIntStateOf(-1) }
    val colors = host.values.colors(p.key)
    val names = listOf(FringeLabels18.FIRST, FringeLabels18.MIDDLE, FringeLabels18.LAST)
    fun set(i: Int, c: Int) {
        val n = host.values.colors(p.key)
        n[i] = c
        host.update(p.key, n)
        host.onChangeFinished()
    }

    Column(Modifier.fillMaxWidth()) {
        Text(p.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp))
        if (p.presets.isNotEmpty()) {
            // Scroll only when the width is bounded (as ChoiceChips: a nested horizontal scroll crashes).
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val scroll = if (constraints.hasBoundedWidth) Modifier.horizontalScroll(rememberScrollState()) else Modifier
                Row(scroll, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (preset in p.presets) {
                        FilterChip(
                            selected = preset.colors.contentEquals(colors),
                            onClick = { host.update(p.key, preset.colors.copyOf()); host.onChangeFinished() },
                            label = { Text(preset.name) },
                            enabled = enabled,
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BrushworkColors.AccentDim, selectedLabelColor = Color.White),
                            modifier = Modifier.testTag(V18Tags.FRINGE_PREFIX + preset.name),
                        )
                    }
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 6.dp),
        ) {
            for (i in 0 until 3) {
                ColorSwatch(
                    colors[i],
                    size = 40.dp,
                    onClick = { if (enabled) picking = i },
                    modifier = Modifier.semantics { contentDescription = names[i] },
                )
                if (i == 1) {
                    FilterChip(
                        selected = colors[1] == 0,
                        onClick = { set(1, 0) },
                        label = { Text(FringeLabels18.NO_COLOR) },
                        enabled = enabled,
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BrushworkColors.AccentDim, selectedLabelColor = Color.White),
                    )
                }
            }
        }
    }
    val i = picking
    if (i in 0..2) {
        ColorPickerDialog(
            initial = colors[i].takeIf { it != 0 } ?: p.default.getOrNull(i)?.takeIf { it != 0 } ?: 0xFF000000.toInt(),
            onPick = { c -> set(i, c) },
            onDismiss = { picking = -1 },
            title = names[i],
        )
    }
}
