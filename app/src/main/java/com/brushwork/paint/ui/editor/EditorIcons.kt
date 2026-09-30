package com.brushwork.paint.ui.editor

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.AutoFixNormal
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.HighlightAlt
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Polyline
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.automirrored.filled.ViewQuilt
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.brushwork.paint.tools.ToolId

/** Icons used by the editor chrome. */
object EditorIcons {
    /** Material has no eraser glyph: a tilted eraser block with a solid rubber tip on a baseline. */
    val Eraser: ImageVector by lazy {
        ImageVector.Builder(name = "Eraser", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .path(fill = SolidColor(Color.Black)) {
                // Rubber tip (lower-left part of the block).
                moveTo(8.1f, 9.2f)
                lineTo(4.2f, 13.1f)
                lineTo(9.9f, 18.8f)
                lineTo(13.8f, 14.9f)
                close()
            }
            .path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineJoin = StrokeJoin.Round) {
                // Body outline.
                moveTo(8.1f, 9.2f)
                lineTo(14.1f, 3.2f)
                lineTo(19.8f, 8.9f)
                lineTo(13.8f, 14.9f)
                close()
            }
            .path(fill = SolidColor(Color.Black)) {
                // Baseline.
                moveTo(12.5f, 19.4f)
                lineTo(21f, 19.4f)
                lineTo(21f, 21.2f)
                lineTo(12.5f, 21.2f)
                close()
            }
            .build()
    }

    fun tool(id: ToolId): ImageVector = when (id) {
        ToolId.BRUSH -> Icons.Filled.Brush
        ToolId.ERASER -> Eraser
        ToolId.SMUDGE -> Icons.Filled.TouchApp
        ToolId.BLUR -> Icons.Filled.BlurOn
        ToolId.FILL -> Icons.Filled.FormatColorFill
        ToolId.EYEDROPPER -> Icons.Filled.Colorize
        ToolId.MAGIC_WAND -> Icons.Filled.AutoFixHigh
        ToolId.LASSO -> Icons.Filled.Gesture
        ToolId.MARQUEE -> Icons.Filled.HighlightAlt
        ToolId.TRANSFORM -> Icons.Filled.OpenWith
        ToolId.TEXT -> Icons.Filled.TextFields
        ToolId.SHAPE -> Icons.Filled.Category
        ToolId.CURVE -> Icons.Filled.Timeline
        ToolId.POLYLINE -> Icons.Filled.Polyline
        ToolId.FRAME_DIVIDER -> Icons.AutoMirrored.Filled.ViewQuilt
        ToolId.RULER -> Icons.Filled.Straighten
        ToolId.OBJECT_SELECT -> Icons.Filled.CenterFocusWeak
        ToolId.REMOVE -> Icons.Filled.AutoFixNormal
    }
}
