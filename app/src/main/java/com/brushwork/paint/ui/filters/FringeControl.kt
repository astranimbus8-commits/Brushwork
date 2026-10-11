package com.brushwork.paint.ui.filters

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.art.FringeColors
import com.brushwork.paint.ui.color.ColorPickerDialog
import com.brushwork.paint.ui.common.ColorSwatch
import com.brushwork.paint.ui.common.FringeLabels18
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.theme.BrushworkColors

/**
 * v1.8 (item 5, design §3.6 (a), D16): the control of a [FilterParam.Fringes].
 *
 * - The preset chips, each tagged [V18Tags.FRINGE_PREFIX] + the preset's name and showing its
 *   three colors as dots (a ring for "No color"); the chip whose colors are the current ones is
 *   selected. A preset fills the three swatches, which stay editable.
 * - The three fringe swatches, 40 dp: first (moves forward), middle (stays), last (moves back).
 *   A tap opens the color picker, unchanged ([ColorPickerDialog], opaque colors); its pick
 *   replaces that swatch's color. The swatch being picked is outlined.
 * - A [FringeLabels18.NO_COLOR] chip beside the middle swatch sets it to 0 ("No color": the
 *   swatch shows the checkerboard); a later pick replaces it.
 *
 * The colors shown are the ones the filter draws ([FringeColors.resolve]: a v1.7 Chromatic
 * aberration's "order" shows as its colors until a change stores them). Every change is whole at
 * once ([ParamHost.onChangeFinished] right after it).
 */
@Composable
internal fun FringeControl(host: ParamHost, p: FilterParam.Fringes, enabled: Boolean) {
    var picking by remember { mutableIntStateOf(-1) }
    val colors = FringeColors.resolve(host.values, p)
    val names = listOf(FringeLabels18.FIRST, FringeLabels18.MIDDLE, FringeLabels18.LAST)
    val chipColors = FilterChipDefaults.filterChipColors(selectedContainerColor = BrushworkColors.AccentDim, selectedLabelColor = Color.White)
    fun store(n: IntArray) {
        host.update(p.key, n)
        host.onChangeFinished()
    }
    fun set(i: Int, c: Int) = store(FringeColors.resolve(host.values, p).also { it[i] = c })

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
                            onClick = { if (enabled) store(preset.colors.copyOf()) },
                            label = { Text(preset.name) },
                            leadingIcon = { PresetDots(preset.colors) },
                            enabled = enabled,
                            colors = chipColors,
                            modifier = Modifier.testTag(V18Tags.FRINGE_PREFIX + preset.name),
                        )
                    }
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 6.dp).alpha(if (enabled) 1f else 0.38f),
        ) {
            for (i in 0 until 3) {
                ColorSwatch(
                    colors[i],
                    size = 40.dp,
                    selected = picking == i,
                    onClick = { if (enabled) picking = i },
                    modifier = Modifier.semantics {
                        contentDescription = names[i]
                        stateDescription = if (colors[i] == FringeColors.NONE) FringeLabels18.NO_COLOR else ColorUtils.toHex(colors[i])
                    },
                )
                if (i == 1) {
                    FilterChip(
                        selected = colors[1] == FringeColors.NONE,
                        onClick = { if (enabled) set(1, FringeColors.NONE) },
                        label = { Text(FringeLabels18.NO_COLOR) },
                        enabled = enabled,
                        colors = chipColors,
                    )
                }
            }
        }
    }
    val i = picking
    if (i in 0..2) {
        ColorPickerDialog(
            // "No color" has no color to start from: the default's (the middle: green).
            initial = colors[i].takeIf { it != FringeColors.NONE } ?: p.default.getOrNull(i)?.takeIf { it != FringeColors.NONE } ?: 0xFF000000.toInt(),
            onPick = { c -> set(i, c) },
            onDismiss = { picking = -1 },
            title = names[i],
        )
    }
}

/** A preset's three colors as small dots, first to last; "No color" as an empty ring. */
@Composable
private fun PresetDots(colors: IntArray) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        for (c in colors) {
            val dot = Modifier.size(6.dp)
            Box(if (c == FringeColors.NONE) dot.border(1.dp, BrushworkColors.OnChromeDim, CircleShape) else dot.background(Color(c), CircleShape))
        }
    }
}
