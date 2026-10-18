package com.brushwork.paint.ui.placement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.brushwork.paint.ui.theme.BrushworkColors

/**
 * One-of-several chips for the text sheets that wrap onto more lines instead of scrolling
 * sideways (v1.9, R5, R6, R7): on a 360 dp phone, or at a large font scale, every choice stays in
 * view, where `ChoiceChips` would hide the last ones behind its scroll edge. 6 dp between chips
 * and between lines. [chipMinWidth] keeps short labels ("Off", "1.") at a comfortable target;
 * [tags] (one per option, or null) are the chips' test tags.
 */
@Composable
internal fun TextFlowChips(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    chipMinWidth: Dp = Dp.Unspecified,
    enabled: Boolean = true,
    tags: List<String>? = null,
) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        options.forEachIndexed { i, label ->
            val tag = tags?.getOrNull(i)
            FilterChip(
                selected = i == selected,
                onClick = { onSelect(i) },
                enabled = enabled,
                // The label is centred in a chip widened to [chipMinWidth] (8 dp padding each side).
                label = { Text(label, textAlign = TextAlign.Center, modifier = if (chipMinWidth == Dp.Unspecified) Modifier else Modifier.widthIn(min = chipMinWidth - 16.dp)) },
                modifier = Modifier
                    .then(if (chipMinWidth == Dp.Unspecified) Modifier else Modifier.widthIn(min = chipMinWidth))
                    .then(if (tag == null) Modifier else Modifier.testTag(tag)),
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BrushworkColors.AccentDim, selectedLabelColor = Color.White),
            )
        }
    }
}
