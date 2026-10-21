package com.brushwork.paint.ui.common.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.editor.EditorIcons

/**
 * v1.10 (R4, R3): the icon of `ArrayLabels110.BEHIND` (an array on a new layer behind): the Array tool's icon
 * ([EditorIcons.tool] of [ToolId.ARRAY]), a little smaller in the upper left, with a small
 * layer-below badge in the lower right (an outlined layer over a solid one). It is never
 * `Icons.Outlined.FlipToBack`, which already means "Backward" in the object bar.
 */
val EditorIcons.arrayBehind: ImageVector get() = ArrayBehindIcon.icon

/** Builds [EditorIcons.arrayBehind] once. */
internal object ArrayBehindIcon {
    /** The Array icon's share of the 24 dp box (it moves up and left by the rest). */
    private const val ARRAY_SCALE = 0.75f

    val icon: ImageVector by lazy {
        val b = ImageVector.Builder(name = "ArrayBehind", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        b.addGroup(name = "array", scaleX = ARRAY_SCALE, scaleY = ARRAY_SCALE, translationX = 0f, translationY = -0.5f)
        copy(EditorIcons.tool(ToolId.ARRAY).root, b)
        b.clearGroup()
        // The badge: the upper layer outlined, the one below it solid (the new layer is behind).
        b.path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.3f, strokeLineJoin = StrokeJoin.Round) {
            moveTo(15.2f, 16.2f); lineTo(19f, 14.3f); lineTo(22.8f, 16.2f); lineTo(19f, 18.1f); close()
        }
        b.path(fill = SolidColor(Color.Black)) {
            moveTo(14.6f, 18.4f); lineTo(16f, 17.7f); lineTo(19f, 19.2f); lineTo(22f, 17.7f); lineTo(23.4f, 18.4f)
            lineTo(19f, 20.6f); close()
            moveTo(14.6f, 20.6f); lineTo(16f, 19.9f); lineTo(19f, 21.4f); lineTo(22f, 19.9f); lineTo(23.4f, 20.6f)
            lineTo(19f, 22.8f); close()
        }
        b.build()
    }

    /** Adds [group]'s groups and paths, as they are, to [b]'s current group. */
    private fun copy(group: VectorGroup, b: ImageVector.Builder) {
        for (node in group) {
            when (node) {
                is VectorGroup -> {
                    b.addGroup(
                        name = node.name, rotate = node.rotation, pivotX = node.pivotX, pivotY = node.pivotY,
                        scaleX = node.scaleX, scaleY = node.scaleY, translationX = node.translationX,
                        translationY = node.translationY, clipPathData = node.clipPathData,
                    )
                    copy(node, b)
                    b.clearGroup()
                }
                is VectorPath -> b.addPath(
                    pathData = node.pathData, pathFillType = node.pathFillType, name = node.name, fill = node.fill,
                    fillAlpha = node.fillAlpha, stroke = node.stroke, strokeAlpha = node.strokeAlpha,
                    strokeLineWidth = node.strokeLineWidth, strokeLineCap = node.strokeLineCap,
                    strokeLineJoin = node.strokeLineJoin, strokeLineMiter = node.strokeLineMiter,
                    trimPathStart = node.trimPathStart, trimPathEnd = node.trimPathEnd, trimPathOffset = node.trimPathOffset,
                )
            }
        }
    }
}
