package com.brushwork.paint.ui.selection

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.outlined.AddBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.IndeterminateCheckBox
import androidx.compose.material.icons.outlined.JoinInner
import androidx.compose.material.icons.outlined.Polyline
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.tools.select.LassoKind
import com.brushwork.paint.ui.common.ToolIconButton

// The selection tools' shared looks and texts. (v1.8: the strips themselves are in
// SelectionStrips.kt, their controls in SelectionStripControls.kt.)

internal fun lassoKindIcon(kind: LassoKind): ImageVector = when (kind) {
    LassoKind.FREEHAND -> Icons.Filled.Gesture
    LassoKind.POLYGON -> Icons.Outlined.Polyline
    LassoKind.CURVE -> CurveLassoIcon
}

/** How to use a lasso mode, shown as a message whenever the strip switches to it (none for freehand). */
internal fun lassoKindHint(kind: LassoKind): String? = when (kind) {
    LassoKind.FREEHAND -> null
    LassoKind.POLYGON -> "Tap the corners, then tap the first one to close"
    LassoKind.CURVE -> "Tap to place points, then tap the first one to close. Long-press a point to make it sharp or delete it"
}

/** The long-pressed curve point, as far as the strip shows it. */
internal data class LassoPoint(val index: Int, val sharp: Boolean)

/** A smooth closed outline through round points (tinted like the Material icons). */
private val CurveLassoIcon: ImageVector by lazy {
    ImageVector.Builder("CurveLasso", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(5f, 13f)
            curveTo(4.2f, 8.2f, 8.5f, 4f, 13f, 4.5f)
            curveTo(18f, 5f, 21f, 9f, 19.5f, 13.5f)
            curveTo(18f, 18f, 12.5f, 20.5f, 8.5f, 19f)
            curveTo(6.5f, 18.25f, 5.5f, 16f, 5f, 13f)
            close()
        }
        .path(fill = SolidColor(Color.Black)) {
            for ((x, y) in listOf(5f to 13f, 13f to 4.5f, 19.5f to 13.5f, 8.5f to 19f)) {
                moveTo(x - 2f, y)
                arcTo(2f, 2f, 0f, false, true, x + 2f, y)
                arcTo(2f, 2f, 0f, false, true, x - 2f, y)
                close()
            }
        }
        .build()
}

internal fun modeIcon(mode: SelectionMode): ImageVector = when (mode) {
    SelectionMode.REPLACE -> Icons.Outlined.CheckBoxOutlineBlank
    SelectionMode.ADD -> Icons.Outlined.AddBox
    SelectionMode.SUBTRACT -> Icons.Outlined.IndeterminateCheckBox
    SelectionMode.INTERSECT -> Icons.Outlined.JoinInner
}

internal fun modeDescription(mode: SelectionMode): String = when (mode) {
    SelectionMode.REPLACE -> "New selection"
    SelectionMode.ADD -> "Add to selection"
    SelectionMode.SUBTRACT -> "Subtract from selection"
    SelectionMode.INTERSECT -> "Intersect with selection"
}

/** New / Add / Subtract / Intersect as four icon toggles (the selection menu's). */
@Composable
internal fun SelectionModeButtons(mode: SelectionMode, onChange: (SelectionMode) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SelectionMode.entries.forEach { m ->
            ToolIconButton(modeIcon(m), modeDescription(m), onClick = { onChange(m) }, selected = m == mode, size = 40.dp)
        }
    }
}
