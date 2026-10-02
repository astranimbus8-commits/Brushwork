package com.brushwork.paint.ui.vector

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.JoinInner
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.vector.draw.VectorEraseMode
import com.brushwork.paint.vector.draw.VectorEraserModes

/**
 * The vector eraser's mode chips (Object / Partial / To intersection; v1.5 §4.9, owned by A3),
 * shown after the eraser's own options while the active layer is a vector layer (not while its
 * mask is painted: the eraser then erases mask pixels). The mode is remembered
 * (AppSettings.vectorEraserMode).
 */
@Composable
fun VectorEraserOptions(controller: EditorController) {
    val erasesObjects by remember(controller) {
        derivedStateOf {
            controller.layersVersion
            val layer = controller.activeLayer
            layer.isVectorLayer && controller.editTargetOf(layer) == EditTarget.CONTENT
        }
    }
    if (!erasesObjects) return
    val mode = VectorEraserModes.mode(controller)
    VectorEraseMode.entries.forEach { m ->
        OptionChip(m.label, m == mode, { VectorEraserModes.setMode(controller, m) }, icon = iconOf(m))
    }
}

private fun iconOf(m: VectorEraseMode): ImageVector = when (m) {
    VectorEraseMode.OBJECT -> Icons.Outlined.DeleteSweep
    VectorEraseMode.PARTIAL -> Icons.Filled.ContentCut
    VectorEraseMode.TO_INTERSECTION -> Icons.Filled.JoinInner
}
