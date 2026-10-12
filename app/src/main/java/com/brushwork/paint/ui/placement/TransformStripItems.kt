package com.brushwork.paint.ui.placement

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterCenterFocus
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.common.ToolIconButton
import com.brushwork.paint.ui.common.TransformLabels17
import com.brushwork.paint.ui.common.TransformLabels18
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.tools.strip.LocalStripContext
import com.brushwork.paint.ui.tools.strip.StripItem
import com.brushwork.paint.ui.tools.strip.StripRow
import com.brushwork.paint.ui.tools.strip.StripSpec

/**
 * The Transform tool's strip (v1.8, design §3.9 "Transform" and "Transform, Free deform"; area G).
 *
 * - Nothing being transformed: v1.7's status text ("Touch the canvas to transform the layer",
 *   "The layer is locked", ...) on row 1, the strip's only content (no toggle).
 * - The box modes: row 1 the mode dropdown, "Keep aspect ratio", "From center", the flips (their
 *   width kept while a text kept as text can't be mirrored); the More row "Delete" ("Delete
 *   picture" while placing), the quarter turns, "Fit to canvas", "Reset transform", "Snap to
 *   objects", "Choose interpolation" and "Numbers" (the Numbers sheet, an overlay), under
 *   [TransformLabels18.BOX_HINT] as its hint.
 * - Free deform: row 1 the mode dropdown, [TransformLabels18.MESH_SIZE] (the mesh's "3 × 3"
 *   opening the v1.7 "Mesh columns" / "Mesh rows" steppers) and "Reset mesh"; the More row
 *   "Smooth mesh", "Select all points" / "Deselect all points", "Delete" and "Choose
 *   interpolation", under the "Select several" hint while the pill's "Select several" is on.
 */
@Composable
internal fun transformSpec(tool: TransformTool): StripSpec {
    // transformState changes on every drag frame; the strip only cares whether one exists.
    val active by remember(tool) { derivedStateOf { tool.transformState != null } }
    val mesh by remember(tool) { derivedStateOf { tool.isMeshShown } }
    // (The mesh changes on every drag frame too: its selection is read the same way.)
    val allPoints by remember(tool) { derivedStateOf { tool.allPointsSelected } }
    // As in v1.7, the Numbers sheet belongs to the box modes of a transform in progress.
    val overlays: @Composable () -> Unit = { if (tool.numbersOpen && active && !mesh) TransformNumbersSheet(tool) }
    if (!active) {
        ClosesMoreRow()
        val status = tool.statusText
        return StripSpec(listOf(TransformStripItems.status(status)), overlays = overlays)
    }
    val T = TransformStripItems
    if (mesh) {
        return StripSpec(
            items = listOf(
                T.mode(tool), T.meshSize(tool), T.meshReset(tool),
                T.smooth(tool), T.selectPoints(tool, allPoints), T.delete(tool), T.interpolation(tool),
            ),
            hint = if (tool.selectSeveral) PointLabels.SEVERAL_HINT else null,
            overlays = overlays,
            movedInto = TransformStripItems.MESH_POPUP.associateWith { TransformStripItems.MESH_SIZE } + T.modesInside(tool.mode),
        )
    }
    return StripSpec(
        items = listOf(
            T.mode(tool), T.aspect(tool), T.center(tool), T.flipH(tool), T.flipV(tool),
            T.delete(tool), T.rotate(tool, clockwise = false), T.rotate(tool, clockwise = true), T.fit(tool), T.reset(tool),
            T.snap(tool), T.interpolation(tool), T.numbers(tool),
        ),
        // v1.8 integration: how the box works while it is active (the idle status text names the
        // first touch, which has happened by now).
        hint = TransformLabels18.BOX_HINT,
        overlays = overlays,
        movedInto = T.modesInside(tool.mode),
    )
}

/** The Transform strip's item keys, widths (measured, rounded up to 4 dp) and handles (the v1.7 labels). */
internal object TransformStripItems {
    const val STATUS = "transform.status"
    const val MODE = "transform.mode"
    const val ASPECT = "transform.aspect"
    const val CENTER = "transform.center"
    const val FLIP_H = "transform.flipH"
    const val FLIP_V = "transform.flipV"
    const val DELETE = "transform.delete"
    const val ROTATE_CCW = "transform.rotateCcw"
    const val ROTATE_CW = "transform.rotateCw"
    const val FIT = "transform.fit"
    const val RESET = "transform.reset"
    const val SNAP = "transform.snap"
    const val INTERPOLATION = "transform.interpolation"
    const val NUMBERS = "transform.numbers"
    const val MESH_SIZE = "transform.meshSize"
    const val MESH_RESET = "transform.meshReset"
    const val SMOOTH = "transform.smooth"
    const val SELECT_POINTS = "transform.selectPoints"

    /** The status text alone on row 1: up to two lines at 11 sp, within row 1 at 360 dp with the VECTOR cell. */
    const val STATUS_DP = 284f
    const val MODE_DP = 56f
    const val ICON_DP = 40f

    /** "Delete picture" after its icon, at 11 sp. */
    const val DELETE_DP = 104f

    /** The caption over the interpolation ("Smooth" / "Nearest") and ▾. */
    const val INTERPOLATION_DP = 80f
    const val NUMBERS_DP = 64f

    /** The mesh ("12 × 12" at most) and ▾. */
    const val MESH_SIZE_DP = 72f
    const val SMOOTH_DP = 80f

    /** "Deselect all points" on two lines at 11 sp. */
    const val SELECT_POINTS_DP = 80f

    const val FROM_CENTER = "From center"
    const val FLIP_HORIZONTALLY = "Flip horizontally"
    const val FLIP_VERTICALLY = "Flip vertically"
    const val ROTATE_CCW_LABEL = "Rotate 90° counter-clockwise"
    const val ROTATE_CW_LABEL = "Rotate 90° clockwise"
    const val FIT_LABEL = "Fit to canvas"
    const val RESET_LABEL = "Reset transform"
    const val INTERPOLATION_LABEL = "Choose interpolation"
    const val NUMBERS_LABEL = "Numbers"
    const val DELETE_LABEL = "Delete"
    const val DELETE_PICTURE = "Delete picture"

    /** The v1.7 controls inside the [TransformLabels18.MESH_SIZE] popup. */
    val MESH_POPUP: List<String> = listOf(
        TransformLabels17.COLUMNS, TransformLabels17.ROWS,
        MeshStepperLabels.FEWER_COLUMNS, MeshStepperLabels.MORE_COLUMNS, MeshStepperLabels.FEWER_ROWS, MeshStepperLabels.MORE_ROWS,
    )

    /**
     * Rule 5 (`movedInto`): v1.7's mode chips other than [current] ("Distort", "Free deform", ...)
     * are inside the mode dropdown, which row 1 shows as [current]'s label.
     */
    fun modesInside(current: TransformTool.Mode): Map<String, String> =
        TransformTool.Mode.entries.filter { it != current }.associate { it.label to MODE }

    fun keepAspectLabel(on: Boolean) = if (on) "Keep aspect ratio: on" else "Keep aspect ratio: off"

    fun deleteLabel(tool: TransformTool) = if (tool.isPlacement) DELETE_PICTURE else DELETE_LABEL

    /** The mesh's visible size: "3 × 3" (columns × rows). */
    fun meshText(columns: Int, rows: Int) = "$columns × $rows"

    fun status(text: String) = StripItem(STATUS, text, STATUS_DP.dp) { StripStatusText(text, STATUS_DP.dp) }

    fun mode(tool: TransformTool) = StripItem(MODE, tool.mode.label, MODE_DP.dp) { ModeMenu(tool) }

    fun aspect(tool: TransformTool) = StripItem(ASPECT, keepAspectLabel(tool.keepAspect), ICON_DP.dp) {
        ToolIconButton(
            if (tool.keepAspect) Icons.Filled.Link else Icons.Filled.LinkOff,
            contentDescription = keepAspectLabel(tool.keepAspect),
            onClick = { tool.keepAspect = !tool.keepAspect },
            selected = tool.keepAspect,
            enabled = tool.mode == TransformTool.Mode.FREE && !tool.uniformOnly,
            size = ICON_DP.dp,
        )
    }

    fun center(tool: TransformTool) = StripItem(CENTER, FROM_CENTER, ICON_DP.dp) {
        val on = tool.scaleFromCenter
        ToolIconButton(
            Icons.Filled.FilterCenterFocus,
            contentDescription = FROM_CENTER,
            onClick = { tool.scaleFromCenter = !on },
            selected = on,
            enabled = tool.mode == TransformTool.Mode.FREE,
            size = ICON_DP.dp,
            modifier = Modifier.semantics { stateDescription = if (on) "On" else "Off" },
        )
    }

    // v1.7 (§3.11): a text kept as text is never mirrored; the flips' width stays (no jump).
    fun flipH(tool: TransformTool) = StripItem(FLIP_H, FLIP_HORIZONTALLY, ICON_DP.dp, shown = tool.flipsAllowed) {
        ToolIconButton(Icons.Filled.Flip, FLIP_HORIZONTALLY, onClick = { tool.flip(horizontal = true) }, size = ICON_DP.dp)
    }

    fun flipV(tool: TransformTool) = StripItem(FLIP_V, FLIP_VERTICALLY, ICON_DP.dp, shown = tool.flipsAllowed) {
        ToolIconButton(Icons.Filled.Flip, FLIP_VERTICALLY, onClick = { tool.flip(horizontal = false) }, modifier = Modifier.rotate(90f), size = ICON_DP.dp)
    }

    /** Deletes what is being transformed (one undo step): not a duplicate, Transform has no pill trash. */
    fun delete(tool: TransformTool) = StripItem(DELETE, deleteLabel(tool), DELETE_DP.dp, row = StripRow.MORE) {
        StripTextButton(deleteLabel(tool), DELETE_DP.dp, onClick = { tool.deleteContent() }, color = BrushworkColors.Danger, icon = Icons.Filled.Delete)
    }

    fun rotate(tool: TransformTool, clockwise: Boolean) = StripItem(
        if (clockwise) ROTATE_CW else ROTATE_CCW,
        if (clockwise) ROTATE_CW_LABEL else ROTATE_CCW_LABEL,
        ICON_DP.dp,
        row = StripRow.MORE,
    ) {
        ToolIconButton(
            if (clockwise) Icons.Filled.Rotate90DegreesCw else Icons.Filled.Rotate90DegreesCcw,
            if (clockwise) ROTATE_CW_LABEL else ROTATE_CCW_LABEL,
            onClick = { tool.rotate90(clockwise = clockwise) },
            size = ICON_DP.dp,
        )
    }

    fun fit(tool: TransformTool) = StripItem(FIT, FIT_LABEL, ICON_DP.dp, row = StripRow.MORE) {
        ToolIconButton(Icons.Filled.FitScreen, FIT_LABEL, onClick = { tool.fitToCanvas() }, size = ICON_DP.dp)
    }

    fun reset(tool: TransformTool) = StripItem(RESET, RESET_LABEL, ICON_DP.dp, row = StripRow.MORE) {
        ToolIconButton(Icons.Filled.RestartAlt, RESET_LABEL, onClick = { tool.reset() }, size = ICON_DP.dp)
    }

    fun snap(tool: TransformTool) = snapItem(SNAP, on = { tool.snapToObjects }) { tool.snapToObjects = it }

    fun interpolation(tool: TransformTool) = StripItem(INTERPOLATION, INTERPOLATION_LABEL, INTERPOLATION_DP.dp, row = StripRow.MORE) {
        InterpolationMenu(tool)
    }

    fun numbers(tool: TransformTool) = StripItem(NUMBERS, NUMBERS_LABEL, NUMBERS_DP.dp, row = StripRow.MORE) {
        val strip = LocalStripContext.current
        StripTextButton(NUMBERS_LABEL, NUMBERS_DP.dp, onClick = {
            // D25: the sheet is an overlay; the More row closes first.
            strip.closeMore()
            tool.numbersOpen = true
        })
    }

    fun meshSize(tool: TransformTool) = StripItem(MESH_SIZE, TransformLabels18.MESH_SIZE, MESH_SIZE_DP.dp) { MeshSizeMenu(tool) }

    fun meshReset(tool: TransformTool) = StripItem(MESH_RESET, TransformLabels17.RESET, ICON_DP.dp) {
        val changed by remember(tool) { derivedStateOf { tool.isMeshChanged } }
        ToolIconButton(Icons.Filled.RestartAlt, TransformLabels17.RESET, onClick = { tool.resetMesh() }, enabled = changed, size = ICON_DP.dp)
    }

    fun smooth(tool: TransformTool) = StripItem(SMOOTH, TransformLabels17.SMOOTH, SMOOTH_DP.dp, row = StripRow.MORE) {
        val on = tool.smoothMesh
        StripTextButton(TransformLabels17.SMOOTH, SMOOTH_DP.dp, onClick = { tool.smoothMesh = !on }, checked = on)
    }

    /** "Select all points", or "Deselect all points" when [all] of them are. */
    fun selectPoints(tool: TransformTool, all: Boolean): StripItem {
        val label = if (all) PointLabels.DESELECT_ALL else PointLabels.SELECT_ALL
        return StripItem(SELECT_POINTS, label, SELECT_POINTS_DP.dp, row = StripRow.MORE) {
            StripTextButton(label, SELECT_POINTS_DP.dp, onClick = { tool.selectAllPoints() })
        }
    }

    /** A mode's icon, on the mode button and in its dropdown. */
    fun modeIcon(m: TransformTool.Mode): ImageVector = when (m) {
        TransformTool.Mode.FREE -> Icons.Filled.CropFree
        TransformTool.Mode.DISTORT -> Icons.Filled.Transform
        TransformTool.Mode.MESH -> Icons.Filled.GridOn
    }
}

/**
 * The mode dropdown (v1.8 §3.9: v1.7's three mode chips in one): the current mode's icon and ▾,
 * announced by its label ("Free", "Distort", "Free deform"). Inside, the three modes. A mode that
 * what is lifted can't take is dimmed with its caption as its state ("Rasterize to deform",
 * "Apply the array to deform"...); a tap on it shows the caption under it, plus "Rasterize and
 * deform" when rasterizing the layer first makes the mode available (as v1.7's chip did).
 */
@Composable
private fun ModeMenu(tool: TransformTool) {
    var open by remember { mutableStateOf(false) }
    var explained by remember { mutableStateOf<TransformTool.Mode?>(null) }
    val current = tool.mode
    Box {
        StripMenuButton(current.label, TransformStripItems.MODE_DP.dp, open, longPress = true, onClick = {
            explained = null
            open = true
        }) {
            Icon(TransformStripItems.modeIcon(current), contentDescription = null, tint = BrushworkColors.Accent, modifier = Modifier.size(24.dp))
        }
        StripMenu(open, onDismiss = { open = false }) {
            for (m in TransformTool.Mode.entries) {
                val refusal = tool.modeRefusal(m)
                DropdownMenuItem(
                    text = { Text(m.label, color = if (refusal != null) BrushworkColors.OnChromeDim else Color.Unspecified) },
                    onClick = {
                        if (refusal == null) {
                            open = false
                            tool.mode = m
                        } else {
                            explained = m
                        }
                    },
                    leadingIcon = { Icon(TransformStripItems.modeIcon(m), contentDescription = null) },
                    trailingIcon = { if (m == current) Icon(Icons.Filled.Check, contentDescription = null) },
                    modifier = Modifier.semantics { if (refusal != null) stateDescription = refusal },
                )
                if (explained == m && refusal != null) {
                    DropdownMenuItem(
                        text = { Text(refusal, style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim) },
                        onClick = {},
                        enabled = false,
                    )
                    if (tool.canRasterizeFor(m)) {
                        DropdownMenuItem(
                            text = { Text(TransformLabels17.RASTERIZE_AND_DEFORM) },
                            onClick = {
                                open = false
                                tool.rasterizeAndDeform(m)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * [TransformLabels18.MESH_SIZE] (§3.9): the mesh's "3 × 3" and ▾, tagged
 * [V17Tags.MESH]; a tap opens a popup with v1.7's "Mesh columns" and "Mesh rows" steppers (1..12),
 * their labels unchanged. The popup is a dropdown: it never closes the More row.
 */
@Composable
private fun MeshSizeMenu(tool: TransformTool) {
    val cols by remember(tool) { derivedStateOf { tool.meshColumns } }
    val rows by remember(tool) { derivedStateOf { tool.meshRows } }
    var open by remember { mutableStateOf(false) }
    Box(Modifier.testTag(V17Tags.MESH)) {
        StripMenuButton(TransformLabels18.MESH_SIZE, TransformStripItems.MESH_SIZE_DP.dp, open, onClick = { open = true }) {
            Text(
                TransformStripItems.meshText(cols, rows),
                fontSize = 12.sp,
                color = BrushworkColors.OnChrome,
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.Center,
            )
        }
        StripMenu(open, onDismiss = { open = false }) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                MeshStepper(TransformLabels17.COLUMNS, cols, MeshStepperLabels.FEWER_COLUMNS, MeshStepperLabels.MORE_COLUMNS) { tool.setMeshCells(it, tool.meshRows) }
                MeshStepper(TransformLabels17.ROWS, rows, MeshStepperLabels.FEWER_ROWS, MeshStepperLabels.MORE_ROWS) { tool.setMeshCells(tool.meshColumns, it) }
            }
        }
    }
}

/**
 * "Choose interpolation": the caption [TransformLabels18.INTERPOLATION_CAPTION] over the current
 * interpolation ("Smooth" / "Nearest") and ▾; its dropdown lists both with their descriptions,
 * the current one checked (v1.7's menu, now opaque).
 */
@Composable
private fun InterpolationMenu(tool: TransformTool) {
    var open by remember { mutableStateOf(false) }
    Box {
        // The caption says what the button chooses; the current value and ▾ under it (v1.8 polish).
        StripMenuButton(TransformStripItems.INTERPOLATION_LABEL, TransformStripItems.INTERPOLATION_DP.dp, open, onClick = { open = true }, arrow = false) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    TransformLabels18.INTERPOLATION_CAPTION, fontSize = 10.sp, lineHeight = 12.sp, color = BrushworkColors.OnChromeDim,
                    maxLines = 1, softWrap = false,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tool.interpolation.label, fontSize = 12.sp, lineHeight = 14.sp, color = BrushworkColors.OnChrome, maxLines = 1, softWrap = false)
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = BrushworkColors.OnChromeDim, modifier = Modifier.size(16.dp))
                }
            }
        }
        StripMenu(open, onDismiss = { open = false }) {
            TransformTool.Interpolation.entries.forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(mode.label)
                            Text(mode.description, style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim)
                        }
                    },
                    leadingIcon = {
                        if (mode == tool.interpolation) Icon(Icons.Filled.Check, contentDescription = null)
                        else Spacer(Modifier.size(24.dp))
                    },
                    onClick = {
                        tool.interpolation = mode
                        open = false
                    },
                )
            }
        }
    }
}
