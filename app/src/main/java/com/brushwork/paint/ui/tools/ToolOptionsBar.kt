package com.brushwork.paint.ui.tools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.brushwork.paint.EditorController
import com.brushwork.paint.ui.tools.strip.LocalStripContext
import com.brushwork.paint.ui.tools.strip.StripContext
import com.brushwork.paint.ui.tools.strip.StripHost
import com.brushwork.paint.ui.tools.strip.StripState

/**
 * The per-tool options strip (v1.6: inside the floating options panel under the top row; v1.8:
 * the call `StripHost(ToolStrips.of(controller), ...)`, design §4.6). Each tool's builder
 * ([ToolStrips]) describes its strip; a tool not converted yet draws its v1.7 composable exactly
 * as v1.7 did, in one row that scrolls sideways ([modifier] is that row's).
 *
 * [onContentWidth] receives the width (px) of what the tool shows on row 1, at layout time (0 = a
 * tool without options): the panel hides itself then. [panelModifier] styles row 1's container
 * (the More row hangs under it, outside it). [state] is the More row's open state, hoisted in
 * `EditorScreen`; without one the bar keeps its own.
 *
 * The [LocalStripContext] the builders read is the caller's with this bar's vector mode, and with
 * `closeMore` closing this bar's More row too.
 */
@Composable
fun ToolOptionsBar(
    controller: EditorController,
    modifier: Modifier = Modifier,
    onContentWidth: ((Int) -> Unit)? = null,
    panelModifier: Modifier = Modifier,
    state: StripState? = null,
) {
    val toolId = controller.activeToolId
    if (controller.tools[toolId] == null) return
    // Derived: isVectorMode reads layersVersion, which changes with every committed edit.
    val vectorMode by remember(controller) { derivedStateOf { controller.isVectorMode } }
    val own = remember { StripState() }
    val strip = state ?: own
    val outer = LocalStripContext.current
    val context = remember(outer, vectorMode, strip) {
        StripContext(
            selectionBarShown = outer.selectionBarShown,
            vectorMode = vectorMode,
            closeMore = {
                outer.closeMore()
                strip.closeAll()
            },
        )
    }
    CompositionLocalProvider(LocalStripContext provides context) {
        StripHost(
            spec = ToolStrips.of(controller),
            toolId = toolId,
            vectorMode = vectorMode,
            state = strip,
            modifier = modifier,
            panelModifier = panelModifier,
            onContentWidth = onContentWidth,
        )
    }
}
