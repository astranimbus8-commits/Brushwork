package com.brushwork.paint.ui.brush

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushTool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.tools.strip.LocalStripContext
import com.brushwork.paint.ui.tools.strip.StripRow
import com.brushwork.paint.ui.tools.strip.StripSpec
import com.brushwork.paint.ui.vector.VectorEraserStrip

/**
 * The strip of the Brush, Eraser, Smudge and Blur (v1.8, design §3.9 and §4.6; area D).
 *
 * - Brush, raster Eraser, Smudge, Blur: row 1 only, no More row and no toggle: "Choose brush" 96,
 *   "Brush size" 76, "Opacity" 60 ("Strength" for smudge and blur), "Pressure changes size" 40
 *   (276 dp: it fits 360 dp with the VECTOR cell, 288).
 * - The Eraser erasing a vector layer's objects: row 1 is the three modes 120, "Brush size" 76
 *   and "Opacity" 60 (AUTO 1); the More row "Choose brush" and "Pressure changes size". While the
 *   layer's mask is painted the eraser erases pixels: the raster row.
 *
 * "All brush settings" stays inside the size and opacity popups (as in v1.7). The brush panel it
 * and "Choose brush" open is an overlay: it outlives the More row.
 */
@Composable
internal fun brushStrip(tool: BrushTool, c: EditorController): StripSpec {
    val strip = LocalStripContext.current
    var panelOpen by rememberSaveable { mutableStateOf(false) }
    val openPanel = {
        strip.closeMore()
        panelOpen = true
    }
    val overlays: @Composable () -> Unit = { if (panelOpen) BrushPanel(c, onDismiss = { panelOpen = false }) }
    val items = BrushStripItems.of(tool, openPanel) ?: return StripSpec(emptyList(), overlays = overlays)
    val erasesObjects by remember(c) { derivedStateOf { VectorEraserStrip.erasesObjects(c) } }
    return if (tool.id == ToolId.ERASER && erasesObjects) {
        StripSpec(
            items = listOf(
                VectorEraserStrip.modesItem(c),
                items.size,
                items.opacity.placed(StripRow.ROW1, overflowRank = 1),
                items.choose.placed(StripRow.MORE),
                items.pressure.placed(StripRow.MORE),
            ),
            overlays = overlays,
        )
    } else {
        StripSpec(items = items.all, overlays = overlays)
    }
}
