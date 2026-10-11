package com.brushwork.paint.ui.selection

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChangeHistory
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.outlined.AddBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.HighlightAlt
import androidx.compose.material.icons.outlined.IndeterminateCheckBox
import androidx.compose.material.icons.outlined.JoinInner
import androidx.compose.material.icons.outlined.Polyline
import androidx.compose.material.icons.outlined.Rectangle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.brushwork.paint.ColorModeOps
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.tools.select.EyedropperTool
import com.brushwork.paint.tools.select.FillSettings
import com.brushwork.paint.tools.select.FillTool
import com.brushwork.paint.tools.select.LassoKind
import com.brushwork.paint.tools.select.LassoTool
import com.brushwork.paint.tools.select.MagicWandTool
import com.brushwork.paint.tools.select.MarqueeShape
import com.brushwork.paint.tools.select.MarqueeTool
import com.brushwork.paint.tools.select.SampleSource
import com.brushwork.paint.ui.common.ColorSwatch
import com.brushwork.paint.ui.common.SnapToObjectsChip
import com.brushwork.paint.ui.common.LabeledSlider
import com.brushwork.paint.ui.common.NumberField
import com.brushwork.paint.ui.common.ToolIconButton
import com.brushwork.paint.ui.common.closeOnSecondFinger
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.vector.ActionChip
import kotlin.math.roundToInt

// ---------------------------------------------------------------------- tool option strips

/**
 * Lasso: Freehand / Polygon / Curve, mode, anti-alias. Polygon and curve: ✓ / ✕ for an
 * unfinished outline; curve: sharp / smooth / delete for the long-pressed point. (v1.8, F4: the
 * top row's Undo / Redo take back and bring back the last corner or point; the strip's own left.)
 */
@Composable
fun LassoOptions(tool: LassoTool) {
    val s = tool.settings
    val kind = tool.kind
    // Derived: dragging a curve point changes its list on every move, the strip only needs this.
    val pending by remember(tool) { derivedStateOf { tool.hasPendingWork } }
    OptionsRow {
        // While an outline is being placed the picker shrinks to icons, so the point actions and
        // ✓ fit on the phone's first screen of the strip.
        LassoKindPicker(kind, compact = pending) { k ->
            if (k != tool.kind) {
                tool.setKind(k)
                // The strip's own hint sits past the labelled chips, off a phone's first screen.
                lassoKindHint(k)?.let { tool.controller.toast(it) }
            }
        }
        StripDivider()
        when (kind) {
            LassoKind.POLYGON -> {
                if (pending) {
                    Text("${tool.vertexCount} pt", style = MaterialTheme.typography.labelMedium, color = BrushworkColors.OnChromeDim)
                    ToolIconButton(Icons.Filled.Close, "Discard polygon", onClick = { tool.discard() }, size = 40.dp)
                    ToolIconButton(Icons.Filled.Check, "Close polygon", onClick = { tool.commit() }, enabled = tool.vertexCount >= 3, size = 40.dp)
                } else {
                    Text("Tap corners", style = MaterialTheme.typography.labelMedium, color = BrushworkColors.OnChromeDim)
                }
                SnapToObjectsChip(tool.controller)
                StripDivider()
            }
            LassoKind.CURVE -> {
                CurveLassoControls(tool)
                SnapToObjectsChip(tool.controller)
                StripDivider()
            }
            LassoKind.FREEHAND -> {}
        }
        SelectionModeButtons(tool.mode) { tool.mode = it }
        StripDivider()
        ToggleChip("Anti-alias", s.antiAlias) { tool.settings = tool.settings.copy(antiAlias = it) }
        StripDivider()
        SelectionShortcuts(tool.controller)
        BusyIndicator(tool.busy)
    }
}

/** Freehand / Polygon / Curve: labelled chips, or 40 dp icon toggles when [compact]. */
@Composable
private fun LassoKindPicker(kind: LassoKind, compact: Boolean, onSelect: (LassoKind) -> Unit) {
    LassoKind.entries.forEach { k ->
        if (compact) {
            ToolIconButton(lassoKindIcon(k), "${k.label} lasso", onClick = { onSelect(k) }, selected = k == kind, size = 40.dp)
        } else {
            FilterChip(
                selected = k == kind,
                onClick = { onSelect(k) },
                label = { Text(k.label, maxLines = 1) },
                leadingIcon = { Icon(lassoKindIcon(k), contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) },
                colors = chipColors,
            )
        }
    }
}

private fun lassoKindIcon(kind: LassoKind): ImageVector = when (kind) {
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
private data class LassoPoint(val index: Int, val sharp: Boolean)

/**
 * Curve lasso: sharp / smooth / delete for the selected point, the point count with ✕ / ✓ (or a
 * hint while there are no points). (v1.8, F4: undo / redo of the last point edit are the top
 * row's.)
 */
@Composable
private fun CurveLassoControls(tool: LassoTool) {
    val curve = tool.curve
    // Derived so that dragging a point (a new list on every move) doesn't recompose the strip.
    val point by remember(curve) {
        derivedStateOf { curve.anchors.getOrNull(curve.selected)?.let { LassoPoint(curve.selected, it.sharp) } }
    }
    val count by remember(curve) { derivedStateOf { curve.count } }
    point?.let { a ->
        if (a.sharp) ActionChip("Smooth", Icons.Filled.Gesture) { curve.setSharp(a.index, false) }
        else ActionChip("Sharp", Icons.Filled.ChangeHistory) { curve.setSharp(a.index, true) }
        ActionChip("Delete", Icons.Outlined.Delete, tint = BrushworkColors.Danger) { curve.deleteAnchor(a.index) }
        StripDivider()
    }
    if (count > 0) {
        Text("$count pt", style = MaterialTheme.typography.labelMedium, color = BrushworkColors.OnChromeDim)
        ToolIconButton(Icons.Filled.Close, "Discard curve", onClick = { tool.discard() }, size = 40.dp)
        ToolIconButton(Icons.Filled.Check, "Close curve", onClick = { tool.commit() }, enabled = count >= 3, size = 40.dp)
    } else {
        Text("Tap points · long-press one to edit", style = MaterialTheme.typography.labelMedium, color = BrushworkColors.OnChromeDim)
    }
}

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

// ---------------------------------------------------------------------- shared strip controls

@Composable
private fun OptionsRow(content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

@Composable
private fun StripDivider() {
    Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(24.dp).background(BrushworkColors.ChromeBorder))
}

@Composable
private fun BusyIndicator(busy: Boolean) {
    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = BrushworkColors.Accent)
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

/** New / Add / Subtract / Intersect as four icon toggles. */
@Composable
internal fun SelectionModeButtons(mode: SelectionMode, onChange: (SelectionMode) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SelectionMode.entries.forEach { m ->
            ToolIconButton(modeIcon(m), modeDescription(m), onClick = { onChange(m) }, selected = m == mode, size = 40.dp)
        }
    }
}

/** Deselect + open the selection menu. */
@Composable
private fun SelectionShortcuts(controller: EditorController) {
    var menu by remember { mutableStateOf(false) }
    ToolIconButton(Icons.Outlined.Deselect, "Deselect", onClick = { controller.deselect() }, enabled = controller.selection != null, size = 40.dp)
    ToolIconButton(Icons.Outlined.HighlightAlt, "Selection menu", onClick = { menu = true }, size = 40.dp)
    if (menu) SelectionPanel(controller) { menu = false }
}

private val chipColors
    @Composable get() = FilterChipDefaults.filterChipColors(
        selectedContainerColor = BrushworkColors.AccentDim,
        selectedLabelColor = Color.White,
        selectedLeadingIconColor = Color.White,
    )

/** On/off chip with a check mark when on. */
@Composable
internal fun ToggleChip(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = checked,
        onClick = { onChange(!checked) },
        label = { Text(label) },
        leadingIcon = if (checked) ({ Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) }) else null,
        colors = chipColors,
    )
}
