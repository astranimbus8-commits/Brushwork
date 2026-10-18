package com.brushwork.paint.ui.vector

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.ui.common.BwDialog
import com.brushwork.paint.ui.common.EraseLabels19
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.vector.VectorLayerOps

/**
 * v1.9 (R11 a; owner A5): asks before [EraseLabels19.TURN_INTO_PIXELS] (the Eraser's More row,
 * the four tools' mode cell): [EraseLabels19.PIXELS_TITLE] with [EraseLabels19.PIXELS_BODY],
 * [EraseLabels19.PIXELS_CONFIRM] and the dialogs' Cancel. The confirm turns [layer] into pixels
 * ([ErasePixels.turnIntoPixels]); either button closes it ([onDismiss]).
 */
@Composable
fun ErasePixelsDialog(controller: EditorController, layer: Layer, onDismiss: () -> Unit) {
    BwDialog(
        title = EraseLabels19.PIXELS_TITLE,
        onDismiss = onDismiss,
        confirmText = EraseLabels19.PIXELS_CONFIRM,
        onConfirm = {
            onDismiss()
            ErasePixels.turnIntoPixels(controller, layer)
        },
    ) {
        Text(EraseLabels19.PIXELS_BODY, style = MaterialTheme.typography.bodyMedium, color = BrushworkColors.OnChrome)
    }
}

/** v1.9 (R11 a; owner A5): [EraseLabels19.TURN_INTO_PIXELS], an action, never a stored mode. */
object ErasePixels {
    /**
     * Turns [layer] into a raster layer with v1.8's rasterize step, ONE undo step that undo turns
     * back: a vector layer's ("Rasterize vector layer", `VectorLayerOps.rasterize`), a legacy
     * shape layer's ([TransformTool.RASTERIZE_SHAPE_LABEL]), each with its toast. The Eraser then
     * erases its pixels. False when nothing changed (refused: the controller said why).
     */
    fun turnIntoPixels(c: EditorController, layer: Layer): Boolean {
        if (c.doc.indexOf(layer) < 0) return false
        return when {
            layer.isVectorLayer -> {
                VectorLayerOps.rasterize(c, layer)
                !layer.isVectorLayer
            }
            layer.isShapeLayer -> {
                c.setLayerData(layer, layer.dataSnapshot().rasterizedContent(), TransformTool.RASTERIZE_SHAPE_LABEL)
                if (layer.isShapeLayer) return false
                c.toast(TransformTool.rasterizedMessage(layer.name, "shape"))
                true
            }
            else -> false
        }
    }
}
