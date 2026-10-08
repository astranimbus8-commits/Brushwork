package com.brushwork.paint.ui.array

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.brushwork.paint.tools.array.ArrayTool
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.vector.Hint

/**
 * The Curve mode's guide choice in the Array sheet (v1.7 item 3, §3.3 a; area E): "Draw guide"
 * (the next finger stroke on the canvas becomes the guide) and "Use a path" (the next tap on a
 * vector path copies its first subpath). Tapping the chosen one again stops waiting. The hint
 * under them says what the canvas expects.
 */
@Composable
internal fun ArrayCurvePicker(tool: ArrayTool, hasGuide: Boolean) {
    val input = tool.guideInput
    Row(Modifier.fillMaxWidth()) {
        GuideChip(DRAW_GUIDE, input == ArrayTool.GuideInput.DRAW, Icons.Filled.Draw) {
            tool.startGuideInput(if (input == ArrayTool.GuideInput.DRAW) ArrayTool.GuideInput.NONE else ArrayTool.GuideInput.DRAW)
        }
        GuideChip(USE_PATH, input == ArrayTool.GuideInput.PICK, Icons.Filled.Timeline) {
            tool.startGuideInput(if (input == ArrayTool.GuideInput.PICK) ArrayTool.GuideInput.NONE else ArrayTool.GuideInput.PICK)
        }
    }
    when {
        input == ArrayTool.GuideInput.DRAW -> Hint(DRAW_HINT)
        input == ArrayTool.GuideInput.PICK -> Hint(PICK_HINT)
        !hasGuide -> Hint(NO_GUIDE_HINT)
    }
}

/** A guide choice: the option chip look at the sheet's 44 dp finger height (a plain chip is 32 dp). */
@Composable
private fun GuideChip(label: String, selected: Boolean, icon: ImageVector, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BrushworkColors.AccentDim, selectedLabelColor = Color.White, selectedLeadingIconColor = Color.White),
        modifier = Modifier.padding(horizontal = 3.dp).heightIn(min = 44.dp),
    )
}

internal const val DRAW_GUIDE = "Draw guide"
internal const val USE_PATH = "Use a path"
private const val DRAW_HINT = "Draw the guide on the canvas with one finger"
private const val PICK_HINT = "Tap a vector path on the canvas: its shape is copied"
private const val NO_GUIDE_HINT = "Draw a guide or use a path: the copies follow it"
