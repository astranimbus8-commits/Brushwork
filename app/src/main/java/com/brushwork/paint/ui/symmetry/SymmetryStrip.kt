package com.brushwork.paint.ui.symmetry

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterVintage
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.tools.symmetry.SymmetryTool
import com.brushwork.paint.ui.common.SymmetryLabels
import com.brushwork.paint.ui.common.ToolIconButton
import com.brushwork.paint.ui.common.closeOnSecondFinger
import com.brushwork.paint.ui.common.iconLongPressLabel
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.theme.IbisColors
import com.brushwork.paint.ui.tools.strip.StripItem
import com.brushwork.paint.ui.tools.strip.StripRow
import com.brushwork.paint.ui.tools.strip.StripSpec

/**
 * The Symmetry tool's strip (v1.8, design §3.9 and §4.6; area D).
 *
 * Row 1: the type 56 (v1.7's six type chips become one dropdown: the button shows the current
 * type's icon and is described by its label, "Mirror ruler"; the entries keep the v1.7 texts),
 * then by type:
 * - Kaleidoscope and Rotation: "Divisions" 64 and "Angle" 64 (AUTO 1);
 * - Mirror: "Angle" 64 (AUTO 1);
 * - Array: "Spacing X" 64 and "Spacing Y" 64 (AUTO 1);
 * - Off and Perspective array: nothing (the perspective array is shaped on the canvas);
 * and "Done" 72 (back to the last painting tool). 268 dp in full, 200 without the AUTO item.
 * The More row: "Angle" (Array) and "Reset symmetry".
 *
 * A value control shows v1.7's "Label value" text ("Angle 0°", "Divisions 6", "Spacing X 300 px")
 * on two lines and opens the same field with its slider in a dropdown. Every change goes through
 * `updateSymmetry` (not undoable, like the ruler).
 */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun symmetryStrip(tool: SymmetryTool, c: EditorController): StripSpec {
    val s by remember(c) { derivedStateOf { c.symmetry.sanitized() } }
    val angle = SymmetryStripItems.angle(c, s)
    val items = buildList {
        add(SymmetryStripItems.type(c, s.type))
        when (s.type) {
            SymmetryType.KALEIDOSCOPE, SymmetryType.ROTATION -> {
                add(SymmetryStripItems.divisions(c, s))
                add(angle.on(StripRow.ROW1, overflowRank = 1))
            }
            SymmetryType.MIRROR -> add(angle.on(StripRow.ROW1, overflowRank = 1))
            SymmetryType.ARRAY -> {
                add(SymmetryStripItems.spacing(c, x = true, s))
                add(SymmetryStripItems.spacing(c, x = false, s).on(StripRow.ROW1, overflowRank = 1))
            }
            SymmetryType.PERSPECTIVE_ARRAY, SymmetryType.OFF -> {}
        }
        add(SymmetryStripItems.done(c))
        if (s.type == SymmetryType.ARRAY) add(angle.on(StripRow.MORE))
        add(SymmetryStripItems.reset(c, s))
    }
    // The six v1.7 type chips are entries of the type dropdown.
    return StripSpec(items, movedInto = SymmetryType.entries.associate { it.label to SymmetryStripItems.TYPE })
}

/** The symmetry strip's items: keys, widths and the v1.7 handles. */
internal object SymmetryStripItems {
    const val TYPE = "symmetry.type"
    const val DIVISIONS = "symmetry.divisions"
    const val ANGLE = "symmetry.angle"
    const val SPACING_X = "symmetry.spacingX"
    const val SPACING_Y = "symmetry.spacingY"
    const val DONE = "symmetry.done"
    const val RESET = "symmetry.reset"

    const val TYPE_DP = 56f
    const val VALUE_DP = 64f
    const val DONE_DP = 72f
    const val RESET_DP = 40f

    const val ANGLE_LABEL = "Angle"
    const val SPACING_X_LABEL = "Spacing X"
    const val SPACING_Y_LABEL = "Spacing Y"
    const val DONE_LABEL = "Done"

    fun angleText(s: SymmetrySettings) = "$ANGLE_LABEL ${SymmetryUi.formatAngle(SymmetryUi.shownAngle(s))}°"
    fun divisionsText(s: SymmetrySettings) = "${SymmetryLabels.DIVISIONS} ${s.divisions}"
    fun spacingText(x: Boolean, s: SymmetrySettings) =
        if (x) "$SPACING_X_LABEL ${SymmetryUi.px(s.spacingX)} px" else "$SPACING_Y_LABEL ${SymmetryUi.px(s.spacingY)} px"

    fun type(c: EditorController, current: SymmetryType) = StripItem(TYPE, current.label, TYPE_DP.dp) { TypeMenu(c, current) }

    fun divisions(c: EditorController, s: SymmetrySettings) = StripItem(DIVISIONS, divisionsText(s), VALUE_DP.dp) {
        ValueButton(divisionsText(s)) { DivisionsField(c, s.divisions) }
    }

    fun angle(c: EditorController, s: SymmetrySettings) = StripItem(ANGLE, angleText(s), VALUE_DP.dp) {
        ValueButton(angleText(s)) { AngleField(c, s) }
    }

    fun spacing(c: EditorController, x: Boolean, s: SymmetrySettings) = StripItem(if (x) SPACING_X else SPACING_Y, spacingText(x, s), VALUE_DP.dp) {
        ValueButton(spacingText(x, s)) {
            if (x) {
                SpacingField(c, SPACING_X_LABEL, s.spacingX) { v -> SymmetryUi.update(c) { it.copy(spacingX = v) } }
            } else {
                SpacingField(c, SPACING_Y_LABEL, s.spacingY) { v -> SymmetryUi.update(c) { it.copy(spacingY = v) } }
            }
        }
    }

    fun done(c: EditorController) = StripItem(DONE, DONE_LABEL, DONE_DP.dp) {
        Button(
            onClick = { c.selectTool(c.lastPaintTool) },
            contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier.width(DONE_DP.dp),
        ) { Text(DONE_LABEL, maxLines = 1) }
    }

    fun reset(c: EditorController, s: SymmetrySettings) = StripItem(RESET, SymmetryLabels.RESET, RESET_DP.dp, row = StripRow.MORE) {
        ToolIconButton(
            icon = Icons.Filled.RestartAlt,
            contentDescription = SymmetryLabels.RESET,
            onClick = { SymmetryUi.update(c) { SymmetryUi.reset(it) } },
            enabled = s != SymmetryUi.reset(s),
            size = RESET_DP.dp,
        )
    }

    /** The icon of [type] on the type button and in its dropdown. */
    fun iconOf(type: SymmetryType): ImageVector = when (type) {
        SymmetryType.OFF -> Icons.Filled.Block
        SymmetryType.MIRROR -> Icons.Filled.Flip
        SymmetryType.KALEIDOSCOPE -> Icons.Filled.FilterVintage
        SymmetryType.ROTATION -> Icons.Filled.Autorenew
        SymmetryType.ARRAY -> Icons.Filled.GridView
        SymmetryType.PERSPECTIVE_ARRAY -> Icons.Filled.ViewInAr
    }
}

/** This item on [row], with [overflowRank] (AUTO when above 0). */
private fun StripItem.on(row: StripRow, overflowRank: Int = 0) =
    StripItem(key = key, label = label, width = width, row = row, overflowRank = overflowRank, shown = shown, content = content)

private val chipShape = RoundedCornerShape(8.dp)

/**
 * The type button (the current type's icon and ▾, described by its label) and its dropdown of the
 * six types. While the dropdown is open the button leaves the semantics (a tap outside the menu
 * only closes it), so the current type's label belongs to its entry alone: one control per label.
 */
@Composable
private fun TypeMenu(c: EditorController, current: SymmetryType) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.width(SymmetryStripItems.TYPE_DP.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .clip(chipShape)
                .border(1.dp, MaterialTheme.colorScheme.outline, chipShape)
                .then(if (open) Modifier.clearAndSetSemantics { } else Modifier)
                .iconLongPressLabel(current.label)
                .clickable { open = true }
                .semantics { contentDescription = current.label }
                .padding(start = 6.dp, end = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(SymmetryStripItems.iconOf(current), contentDescription = null, tint = BrushworkColors.Accent, modifier = Modifier.size(24.dp))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = BrushworkColors.OnChromeDim, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.closeOnSecondFinger { open = false }, containerColor = IbisColors.Menu) {
            for (type in SymmetryType.entries) {
                DropdownMenuItem(
                    text = { Text(type.label) },
                    onClick = {
                        open = false
                        SymmetryUi.update(c) { it.copy(type = type) }
                    },
                    leadingIcon = { Icon(SymmetryStripItems.iconOf(type), contentDescription = null) },
                    trailingIcon = { if (type == current) Icon(Icons.Filled.Check, contentDescription = null) },
                )
            }
        }
    }
}

/**
 * A value control 64 dp wide: v1.7's "Label value" text (one text, its handle, on up to two lines)
 * opening [editor] (a field and its slider) in a dropdown anchored here.
 */
@Composable
private fun ValueButton(text: String, editor: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.width(SymmetryStripItems.VALUE_DP.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .clip(chipShape)
                .border(1.dp, MaterialTheme.colorScheme.outline, chipShape)
                .clickable { open = true }
                .padding(horizontal = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, fontSize = 11.sp, lineHeight = 13.sp, color = BrushworkColors.OnChrome, maxLines = 2, textAlign = TextAlign.Center)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.closeOnSecondFinger { open = false }, containerColor = IbisColors.Menu) {
            // Fixed width: the menu measures its content's intrinsic width.
            Column(Modifier.width(300.dp).padding(horizontal = 16.dp, vertical = 4.dp)) { editor() }
        }
    }
}
