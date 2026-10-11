package com.brushwork.paint.ui.editor.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import com.brushwork.paint.EditorController
import com.brushwork.paint.ui.editor.blockCanvasTouches
import com.brushwork.paint.ui.theme.IbisColors
import com.brushwork.paint.ui.theme.IbisDims
import com.brushwork.paint.ui.tools.ToolOptionsBar
import com.brushwork.paint.ui.tools.strip.StripState

/**
 * The options strip panel (v1.6 §3.7.2): the tool's [ToolOptionsBar] with row 1 in a floating
 * rounded panel ([IbisColors.OptionsStrip], radius 10, 44 dp tall) with white content. The strip
 * hangs its More row under the panel, outside it (v1.8, design §3.9). A tool without options
 * shows no panel at all (it takes no room and no touches). [state] is the More row's open state
 * (`EditorScreen`'s).
 *
 * The bar reports the width of what the tool shows while it is measured; the panel's layout reads
 * whether that is empty from state that is written only when it flips (once per tool at most),
 * so the strip settles in one extra layout pass and never loops.
 *
 * The flag starts over (not empty) for each tool and on each vector-mode change: a hidden bar is
 * not placed, and Compose does not measure an unplaced node again on its own, so a flag kept
 * across tools would leave the strip hidden for good after one tool without options. Starting
 * over makes the panel measure the new bar in the same pass (the bar's report flips it back
 * before the panel lays out, so a tool without options never shows an empty panel).
 *
 * The bar is the first node; whatever follows it (the strip's overlays) is placed even while the
 * panel is hidden, so a sheet or a popup of the strip keeps its anchor.
 */
@Composable
internal fun OptionsStripPanel(controller: EditorController, modifier: Modifier = Modifier, state: StripState? = null) {
    val toolId = controller.activeToolId
    // Derived, as in ToolOptionsBar: isVectorMode reads layersVersion (every committed edit).
    val vectorMode by remember(controller) { derivedStateOf { controller.isVectorMode } }
    var empty by remember(toolId, vectorMode) { mutableStateOf(false) }
    val shape = RoundedCornerShape(IbisDims.OptionsStripRadius)
    Layout(
        content = {
            ToolOptionsBar(
                controller,
                Modifier.fillMaxSize(),
                onContentWidth = { w -> if ((w <= 0) != empty) empty = w <= 0 },
                panelModifier = Modifier
                    .fillMaxWidth()
                    .height(IbisDims.OptionsStripHeight)
                    .clip(shape)
                    .background(IbisColors.OptionsStrip, shape)
                    .blockCanvasTouches()
                    .testTag(ChromeTags.OPTIONS_STRIP),
                state = state,
            )
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints) }
        val bar = placeables.firstOrNull()
        if (bar == null || empty) {
            layout(0, 0) { for (i in 1 until placeables.size) placeables[i].place(0, 0) }
        } else {
            layout(bar.width, bar.height) { placeables.forEach { it.place(0, 0) } }
        }
    }
}
