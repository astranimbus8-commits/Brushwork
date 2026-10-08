package com.brushwork.paint.ui.array

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Timeline
import com.brushwork.paint.tools.array.ArrayTool
import com.brushwork.paint.ui.vector.Hint
import com.brushwork.paint.ui.vector.OptionChip

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
        OptionChip(DRAW_GUIDE, input == ArrayTool.GuideInput.DRAW, {
            tool.startGuideInput(if (input == ArrayTool.GuideInput.DRAW) ArrayTool.GuideInput.NONE else ArrayTool.GuideInput.DRAW)
        }, icon = Icons.Filled.Draw)
        OptionChip(USE_PATH, input == ArrayTool.GuideInput.PICK, {
            tool.startGuideInput(if (input == ArrayTool.GuideInput.PICK) ArrayTool.GuideInput.NONE else ArrayTool.GuideInput.PICK)
        }, icon = Icons.Filled.Timeline)
    }
    when {
        input == ArrayTool.GuideInput.DRAW -> Hint(DRAW_HINT)
        input == ArrayTool.GuideInput.PICK -> Hint(PICK_HINT)
        !hasGuide -> Hint(NO_GUIDE_HINT)
    }
}

internal const val DRAW_GUIDE = "Draw guide"
internal const val USE_PATH = "Use a path"
private const val DRAW_HINT = "Draw the guide on the canvas with one finger"
private const val PICK_HINT = "Tap a vector path on the canvas: its shape is copied"
private const val NO_GUIDE_HINT = "Draw a guide or use a path: the copies follow it"
