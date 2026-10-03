package com.brushwork.paint.ui.editor.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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

/**
 * The options strip panel (v1.6 §3.7.2): the tool's [ToolOptionsBar] in a floating rounded
 * panel ([IbisColors.OptionsStrip], radius 10, 44 dp tall) with white content that scrolls
 * sideways. A tool without options shows no panel at all (it takes no room and no touches).
 *
 * The bar reports the width of what the tool shows while it is measured; the panel's layout reads
 * whether that is empty from state that is written only when it flips (once per tool at most),
 * so the strip settles in one extra layout pass and never loops.
 */
@Composable
internal fun OptionsStripPanel(controller: EditorController, modifier: Modifier = Modifier) {
    var empty by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(IbisDims.OptionsStripRadius)
    Layout(
        content = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(IbisDims.OptionsStripHeight)
                    .clip(shape)
                    .background(IbisColors.OptionsStrip, shape)
                    .blockCanvasTouches()
                    .testTag(ChromeTags.OPTIONS_STRIP),
            ) {
                ToolOptionsBar(controller, Modifier.fillMaxSize(), onContentWidth = { w -> if ((w <= 0) != empty) empty = w <= 0 })
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val p = measurables.first().measure(constraints)
        if (empty) layout(0, 0) {} else layout(p.width, p.height) { p.place(0, 0) }
    }
}
