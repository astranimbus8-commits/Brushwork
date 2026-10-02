package com.brushwork.paint.ui.editor

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Approval
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.AutoFixNormal
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.HighlightAlt
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.PhotoFilter
import androidx.compose.material.icons.filled.Polyline
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.automirrored.filled.ViewQuilt
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
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

    /**
     * The Vector button (v1.5): a pen nib under a Bezier handle with two anchor squares (the
     * universal "pen tool" sign of vector apps).
     */
    val Vector: ImageVector by lazy {
        ImageVector.Builder(name = "Vector", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                // Nib with its breather hole.
                moveTo(12f, 21f)
                lineTo(7.2f, 13f)
                lineTo(9.6f, 7.6f)
                lineTo(14.4f, 7.6f)
                lineTo(16.8f, 13f)
                close()
                moveTo(12f, 11.3f)
                arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 12f, 14.3f)
                arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 12f, 11.3f)
                close()
            }
            .path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.5f) {
                // The handle line between the anchors.
                moveTo(6f, 4.5f)
                lineTo(18f, 4.5f)
            }
            .path(fill = SolidColor(Color.Black)) {
                // Anchor squares.
                moveTo(2.5f, 2.5f); lineTo(6.5f, 2.5f); lineTo(6.5f, 6.5f); lineTo(2.5f, 6.5f); close()
                moveTo(17.5f, 2.5f); lineTo(21.5f, 2.5f); lineTo(21.5f, 6.5f); lineTo(17.5f, 6.5f); close()
            }
            .build()
    }

    /** Clone stamp tool (v1.5). */
    val CloneStamp: ImageVector get() = Icons.Filled.Approval

    /** Masks tool (v1.5): a gradient square. */
    val Masks: ImageVector get() = Icons.Filled.Gradient

    /** The Filters tile of the tools grid (v1.5; it used to be a top-bar action). */
    val FiltersTile: ImageVector get() = Icons.Filled.PhotoFilter

    /** The Path tool (v1.6). Placeholder until area E draws "a smooth arc over a dashed control polygon". */
    val Path: ImageVector get() = Icons.Filled.Timeline

    /** The Text frames tool (v1.6). Placeholder until area E draws the final glyph. */
    val TextFrames: ImageVector get() = Icons.AutoMirrored.Filled.ViewQuilt

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
        ToolId.CLONE -> CloneStamp
        ToolId.MASK -> Masks
        ToolId.PATH -> Path
        ToolId.TEXT_FRAMES -> TextFrames
    }
}
