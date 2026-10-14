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
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.tools.strip.LocalStripContext
import com.brushwork.paint.ui.tools.strip.StripRow
import com.brushwork.paint.ui.tools.strip.StripSpec
import com.brushwork.paint.ui.vector.ErasePixelsDialog
import com.brushwork.paint.ui.vector.VectorEraseChooser
import com.brushwork.paint.ui.vector.VectorEraserStrip
import com.brushwork.paint.vector.draw.VectorEraserModes

/**
 * The strip of the Brush, Eraser, Smudge and Blur (v1.8, design §3.9 and §4.6; area D).
 *
 * - Brush, raster Eraser, Smudge, Blur: row 1 only, no More row and no toggle: "Choose brush" 96,
 *   "Brush size" 76, "Opacity" 64 ("Strength" for smudge and blur), "Pressure changes size" 40
 *   (288 dp: it fits 360 dp with the VECTOR cell, 288).
 * - The Eraser erasing a vector layer's objects (v1.9: or a legacy shape layer's, R11 a): row 1
 *   is the three modes 120 and "Brush size" 76 (200 dp, at both widths); the More row, under the
 *   selected mode's note (its hint, [VectorEraseMode.note]), "Opacity", "Choose brush", "Pressure changes size"
 *   and, last and in the warning tint, [com.brushwork.paint.ui.common.EraseLabels19.TURN_INTO_PIXELS]
 *   ([VectorEraseChooser]), which asks first ([ErasePixelsDialog]). While the layer's mask is
 *   painted the eraser erases pixels: the raster row.
 *
 * "All brush settings" stays inside the size and opacity popups (as in v1.7). The brush panel it
 * and "Choose brush" open is an overlay: it outlives the More row; so does the dialog.
 */
@Composable
internal fun brushStrip(tool: BrushTool, c: EditorController): StripSpec {
    val strip = LocalStripContext.current
    var panelOpen by rememberSaveable { mutableStateOf(false) }
    // The layer the pixels dialog asks about (the active layer when its item was tapped), else null.
    var asking by remember(c) { mutableStateOf<Layer?>(null) }
    val openPanel = {
        strip.closeMore()
        panelOpen = true
    }
    val overlays: @Composable () -> Unit = {
        if (panelOpen) BrushPanel(c, onDismiss = { panelOpen = false })
        asking?.let { layer -> ErasePixelsDialog(c, layer, onDismiss = { asking = null }) }
    }
    val items = BrushStripItems.of(tool, openPanel) ?: return StripSpec(emptyList(), overlays = overlays)
    val erasesObjects by remember(c) { derivedStateOf { VectorEraserStrip.erasesObjects(c) } }
    if (tool.id == ToolId.ERASER && erasesObjects) {
        val layer = c.activeLayer
        val mode = VectorEraserModes.mode(c)
        val modes = VectorEraseChooser.item(strip, layer, mode) { VectorEraserModes.setMode(c, it) }
        val pixels = VectorEraseChooser.pixelsItem(strip, layer) {
            strip.closeMore()
            asking = layer
        }
        if (modes != null && pixels != null) {
            return StripSpec(
                items = listOf(
                    modes,
                    items.size,
                    items.opacity.placed(StripRow.MORE),
                    items.choose.placed(StripRow.MORE),
                    items.pressure.placed(StripRow.MORE),
                    pixels,
                ),
                hint = mode.note,
                overlays = overlays,
            )
        }
    }
    return StripSpec(items = items.all, overlays = overlays)
}
