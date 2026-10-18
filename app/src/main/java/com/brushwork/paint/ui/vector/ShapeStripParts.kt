package com.brushwork.paint.ui.vector

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.brushwork.paint.tools.vector.LineCapStyle
import com.brushwork.paint.ui.common.closeOnSecondFinger
import com.brushwork.paint.ui.common.longPressLabel
import com.brushwork.paint.ui.editor.EditorIcons
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.theme.IbisColors

/*
 * v1.8 (design §3.9, area F): the compact cells of the Shape tool's strip. Every cell is 40 dp
 * tall at least and known by its v1.7 label (its description, or its one text): the strip's
 * handles stay byte-identical while the controls get small enough for row 1 and a More row of
 * at most three lines. An icon cell shows its label in a bubble on a long press.
 */

private val cellShape = RoundedCornerShape(8.dp)

/** A menu cell narrower than this draws its ▾ in the glyph's corner, wider ones beside it. */
private val MENU_ROW_MIN = 48.dp

/** A cell's height: the strip's touch minimum (TouchTargetAuditTest.MIN). */
internal val SHAPE_CELL_DP = 40.dp

/**
 * A 40 dp icon toggle known by [label] (its description and long-press label), selected while
 * [on] (the accent background, `selected` and the state "On" / "Off" for screen readers).
 */
@Composable
internal fun ShapeIconToggle(
    icon: ImageVector,
    label: String,
    on: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    stateText: (Boolean) -> String = { if (it) "On" else "Off" },
) {
    Box(
        Modifier
            .size(SHAPE_CELL_DP)
            .clip(cellShape)
            .background(if (on) BrushworkColors.AccentDim else Color.Transparent)
            .longPressLabel(label)
            .clickable(enabled = enabled, role = Role.Checkbox, onClick = onClick)
            .semantics {
                contentDescription = label
                selected = on
                stateDescription = stateText(on)
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = cellTint(on, enabled), modifier = Modifier.size(22.dp))
    }
}

/**
 * A 40 dp text toggle whose one visible [text] is its label ("15°"), or [width] wide for a longer
 * one ("Points"). v1.9 (I27): at a large font scale the text shrinks to fit the cell, down to
 * [SHAPE_CELL_MIN_TEXT] (at font scale 1 and 1.3 it is 13 sp, as in v1.8).
 */
@Composable
internal fun ShapeTextToggle(text: String, on: Boolean, onClick: () -> Unit, width: Dp = SHAPE_CELL_DP) {
    Box(
        Modifier
            .width(width)
            .height(SHAPE_CELL_DP)
            .clip(cellShape)
            .background(if (on) BrushworkColors.AccentDim else Color.Transparent)
            .border(1.dp, if (on) Color.Transparent else MaterialTheme.colorScheme.outline, cellShape)
            .clickable(role = Role.Checkbox, onClick = onClick)
            .semantics {
                selected = on
                stateDescription = if (on) "On" else "Off"
            },
        contentAlignment = Alignment.Center,
    ) {
        ShapeCellText(text, if (on) Color.White else BrushworkColors.OnChrome)
    }
}

/**
 * v1.9 (I27): the smallest size a Shape cell's text shrinks to at a large font scale (scaled by
 * the font scale like any sp: 18 dp at 2.0) before it would be cut.
 */
internal val SHAPE_CELL_MIN_TEXT = 9.sp

/**
 * A Shape cell's [text] at [size] (v1.8's 13 sp) on up to [maxLines] lines, the Material text
 * style as `Text` gives it ([lineHeight] and [textAlign] when given). v1.9 (I27): where it does not
 * fit the room it is given it shrinks, in 0.25 sp steps, down to [SHAPE_CELL_MIN_TEXT], its line
 * height with it: that is set as a share of the text size, as Compose sets it under the phone's
 * non-linear font scaling (Material's 24 sp line under 13 sp text is 46 dp at 2.0, taller than the
 * 40 dp cell). Text that fits is laid out exactly as before.
 */
@Composable
internal fun ShapeCellText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    size: TextUnit = 13.sp,
    lineHeight: TextUnit = TextUnit.Unspecified,
    maxLines: Int = 1,
    textAlign: TextAlign = TextAlign.Unspecified,
) {
    val style = LocalTextStyle.current.merge(TextStyle(color = color, fontSize = size, lineHeight = lineHeight, textAlign = textAlign))
    val line = style.lineHeight
    val shared = if (line.isSp && size.isSp) style.copy(lineHeight = (line.value / size.value).em) else style
    BasicText(
        text,
        modifier = modifier,
        style = shared,
        maxLines = maxLines,
        softWrap = maxLines > 1,
        autoSize = TextAutoSize.StepBased(minFontSize = SHAPE_CELL_MIN_TEXT, maxFontSize = size, stepSize = 0.25.sp),
    )
}

/**
 * A 40 dp icon action known by [label] (its description and long-press label). [state] is read
 * out after the label ("Mixed", a refusal); [dim] greys the icon while the action would refuse.
 */
@Composable
internal fun ShapeIconAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    tint: Color = BrushworkColors.OnChrome,
    dim: Boolean = false,
    state: String? = null,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(SHAPE_CELL_DP)
            .clip(cellShape)
            .longPressLabel(label)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                if (state != null) stateDescription = state
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (!enabled || dim) BrushworkColors.OnChromeDim.copy(alpha = 0.5f) else tint, modifier = Modifier.size(22.dp))
    }
}

private fun cellTint(on: Boolean, enabled: Boolean): Color = when {
    !enabled -> BrushworkColors.OnChromeDim.copy(alpha = 0.5f)
    on -> Color.White
    else -> BrushworkColors.OnChrome
}

/**
 * A dropdown cell [width] wide: [glyph] and ▾, known by [label] (its description, the v1.7
 * DropdownChip's, and its long-press label), opening its [entries] in an opaque menu anchored
 * here (a pick closes the menu, never the More row). The [selected] entry is checked.
 * v1.9 (R11): [lastRow], when given, is a row of its own below a divider (with the eraser's
 * icon, checked while [lastRowOn]); a tap closes the menu and runs [onLastRow].
 */
@Composable
internal fun <T> ShapeMenuCell(
    label: String,
    entries: List<T>,
    selected: T,
    entryText: (T) -> String,
    onPick: (T) -> Unit,
    width: Dp,
    entryGlyph: (@Composable (T) -> Unit)? = null,
    lastRow: String? = null,
    lastRowOn: Boolean = false,
    onLastRow: () -> Unit = {},
    glyph: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.width(width)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = SHAPE_CELL_DP)
                .clip(cellShape)
                .border(1.dp, MaterialTheme.colorScheme.outline, cellShape)
                .longPressLabel(label)
                .clickable(role = Role.Button) { open = true }
                .semantics { contentDescription = label }
                .padding(start = if (width < MENU_ROW_MIN) 0.dp else 6.dp, end = if (width < MENU_ROW_MIN) 0.dp else 1.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (width < MENU_ROW_MIN) {
                // A 40 dp cell: the ▾ sits in the glyph's corner.
                Box(Modifier.size(30.dp)) {
                    Box(Modifier.size(24.dp).align(Alignment.Center), contentAlignment = Alignment.Center) { glyph() }
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = BrushworkColors.OnChromeDim, modifier = Modifier.size(14.dp).align(Alignment.BottomEnd).offset(x = 4.dp, y = 4.dp))
                }
            } else {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { glyph() }
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = BrushworkColors.OnChromeDim, modifier = Modifier.size(18.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.closeOnSecondFinger { open = false }, containerColor = IbisColors.Menu) {
            for (e in entries) {
                DropdownMenuItem(
                    text = { Text(entryText(e)) },
                    onClick = {
                        open = false
                        onPick(e)
                    },
                    leadingIcon = entryGlyph?.let { g -> { g(e) } },
                    trailingIcon = { if (e == selected) Icon(Icons.Filled.Check, contentDescription = null) },
                )
            }
            if (lastRow != null) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(lastRow) },
                    onClick = {
                        open = false
                        onLastRow()
                    },
                    modifier = Modifier.semantics { this.selected = lastRowOn },
                    leadingIcon = if (entryGlyph != null) { { Icon(EditorIcons.Eraser, contentDescription = null, modifier = Modifier.size(22.dp)) } } else null,
                    trailingIcon = { if (lastRowOn) Icon(Icons.Filled.Check, contentDescription = null) },
                )
            }
        }
    }
}

/**
 * A value cell [width] wide showing [text] (its handle: "60 px") on up to two lines; a tap runs
 * [onClick]. v1.9 (I27): a value too big for the cell shrinks ([ShapeCellText]).
 */
@Composable
internal fun ShapeValueCell(text: String, width: Dp, onClick: () -> Unit) {
    Box(
        Modifier
            .width(width)
            .heightIn(min = SHAPE_CELL_DP)
            .clip(cellShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, cellShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        ShapeCellText(text, BrushworkColors.OnChrome, size = 11.sp, lineHeight = 13.sp, maxLines = 2, textAlign = TextAlign.Center)
    }
}

/**
 * The "Handles" cell [width] wide: ⟷, known by [label] (its description and long-press label;
 * [value], the scale "100 %", is its state); a tap opens [content] (the Handles group's
 * controls, the value among them) in an opaque popup anchored here, [popupWidth] wide.
 * [open] / [onOpen] hoist the popup's state, so a control inside can close it.
 */
@Composable
internal fun ShapePopupCell(
    label: String,
    value: String,
    width: Dp,
    open: Boolean,
    onOpen: (Boolean) -> Unit,
    popupWidth: Dp,
    content: @Composable () -> Unit,
) {
    Box(Modifier.width(width)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = SHAPE_CELL_DP)
                .clip(cellShape)
                .border(1.dp, MaterialTheme.colorScheme.outline, cellShape)
                .longPressLabel(label)
                .clickable(role = Role.Button) { onOpen(true) }
                .semantics {
                    contentDescription = label
                    stateDescription = value
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Filled.SwapHoriz, contentDescription = null, tint = BrushworkColors.OnChrome, modifier = Modifier.size(22.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { onOpen(false) }, modifier = Modifier.closeOnSecondFinger { onOpen(false) }, containerColor = IbisColors.Menu) {
            // Fixed width: the menu measures its content's intrinsic width (a slider has none).
            Column(Modifier.width(popupWidth).padding(horizontal = 8.dp, vertical = 4.dp)) { content() }
        }
    }
}

/** The line ends glyph: a thick stroke with [cap] at both ends. */
@Composable
internal fun LineEndsGlyph(cap: LineCapStyle, color: Color = BrushworkColors.OnChrome) {
    Canvas(Modifier.size(22.dp)) {
        val w = 7.dp.toPx()
        val inset = 5.dp.toPx()
        drawLine(
            color = color,
            start = Offset(inset, size.height / 2f),
            end = Offset(size.width - inset, size.height / 2f),
            strokeWidth = w,
            cap = when (cap) {
                LineCapStyle.BUTT -> StrokeCap.Butt
                LineCapStyle.ROUND -> StrokeCap.Round
                LineCapStyle.SQUARE -> StrokeCap.Square
            },
        )
    }
}
