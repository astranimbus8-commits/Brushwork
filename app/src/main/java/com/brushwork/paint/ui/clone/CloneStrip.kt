package com.brushwork.paint.ui.clone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.clone.CloneTool
import com.brushwork.paint.ui.brush.BrushPanel
import com.brushwork.paint.ui.brush.BrushStripItems
import com.brushwork.paint.ui.brush.placed
import com.brushwork.paint.ui.tools.strip.LocalStripContext
import com.brushwork.paint.ui.tools.strip.StripRow
import com.brushwork.paint.ui.tools.strip.StripSpec

/**
 * The Clone stamp's strip (v1.8, design §3.9 and §4.6; area D).
 *
 * Row 1: "Set source" 92 ("Tap the source" while armed), "Aligned" 40, "Sample from" 96 (236 dp:
 * it fits 360 dp with the VECTOR cell and the toggle, 240). The More row: the hint "Long-press
 * where to copy from" while there is no source and Set source is not armed (v1.7 showed it first
 * on the strip; a tap arms Set source and closes the row), "Show source", then the brush's
 * controls ("Choose brush", "Brush size", "Opacity", "Pressure changes size"; v1.7 showed them
 * after a divider). The brush panel "Choose brush" and "All brush settings" open is an overlay:
 * it outlives the More row.
 */
@Composable
internal fun cloneStrip(tool: CloneTool, c: EditorController): StripSpec {
    val strip = LocalStripContext.current
    var panelOpen by rememberSaveable { mutableStateOf(false) }
    val openPanel = {
        strip.closeMore()
        panelOpen = true
    }
    val overlays: @Composable () -> Unit = { if (panelOpen) BrushPanel(c, onDismiss = { panelOpen = false }) }
    val brush = BrushStripItems.of(tool.brush, openPanel)
    val needsSource = tool.anchor.source == null && !tool.armed
    val items = buildList {
        add(CloneStripItems.setSource(tool))
        add(CloneStripItems.aligned(tool))
        add(CloneStripItems.sampleFrom(tool))
        if (needsSource) add(CloneStripItems.hint(tool, strip.closeMore))
        add(CloneStripItems.showSource(tool))
        brush?.all?.forEach { add(it.placed(StripRow.MORE)) }
    }
    return StripSpec(items, overlays = overlays)
}
