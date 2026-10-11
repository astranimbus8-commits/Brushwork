package com.brushwork.paint.ui.placement

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brushwork.paint.ui.common.closeOnSecondFinger
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.theme.IbisColors
import com.brushwork.paint.ui.tools.strip.StripItem
import com.brushwork.paint.ui.tools.strip.StripRow

/*
 * v1.8 G (design §3.9): the pieces area G's converted strips share (Transform, Text, Frame
 * divider, Text frames, Masks, Remove, Pathfinder). Every item is drawn at its declared width,
 * so a control here takes a [Dp] width and never grows past it; texts that would not fit one
 * line wrap to a second line at 11 sp (the strip is 44 dp tall).
 */

/** This item on [row], with [overflowRank] (AUTO when above 0). */
internal fun StripItem.placed(row: StripRow, overflowRank: Int = 0): StripItem =
    StripItem(key = key, label = label, width = width, row = row, overflowRank = overflowRank, shown = shown, content = content)

/** This item, drawn only when [shown] (its width stays reserved, §4.6). */
internal fun StripItem.shownIf(shown: Boolean): StripItem =
    StripItem(key = key, label = label, width = width, row = row, overflowRank = overflowRank, shown = shown, content = content)

private val stripShape = RoundedCornerShape(8.dp)

/**
 * A dropdown or popup anchored in a strip item (§3.9 "Dropdowns"): opaque ([IbisColors.Menu]),
 * closed by a second finger, and it never closes the More row.
 */
@Composable
internal fun StripMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.closeOnSecondFinger(onDismiss),
        containerColor = IbisColors.Menu,
        content = content,
    )
}

/**
 * The button of a strip dropdown or popup, [width] wide and bordered: [content] (an icon, a
 * short text) and ▾ (none when [arrow] is false: a readout), described [description]. While
 * [open] it leaves the semantics (a tap outside the menu only closes it), so a label it shares
 * with a control inside belongs to that control alone: one control per label (as D's symmetry
 * type button).
 */
@Composable
internal fun StripMenuButton(
    description: String,
    width: Dp,
    open: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    state: String? = null,
    arrow: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier
            .width(width)
            .heightIn(min = 40.dp)
            .clip(stripShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, stripShape)
            .then(if (open) Modifier.clearAndSetSemantics { } else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                contentDescription = description
                if (state != null) stateDescription = state
            }
            .alpha(if (enabled) 1f else 0.5f)
            .padding(start = 4.dp, end = if (arrow) 1.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        content()
        if (arrow) Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = BrushworkColors.OnChromeDim, modifier = Modifier.size(18.dp))
    }
}

/**
 * A bordered strip button [width] wide showing [text] (its handle) on up to two lines at 11 sp,
 * with ▾ when [arrow] (it opens a dropdown). A toggle passes [checked]: it is then tinted while
 * on and announces its state ([state], else on / off).
 */
@Composable
internal fun StripTextButton(
    text: String,
    width: Dp,
    onClick: () -> Unit,
    enabled: Boolean = true,
    checked: Boolean? = null,
    state: String? = null,
    arrow: Boolean = false,
    color: Color = BrushworkColors.OnChrome,
) {
    val on = checked == true
    val action = if (checked == null) {
        Modifier.clickable(enabled = enabled, onClick = onClick)
    } else {
        Modifier.triStateToggleable(state = ToggleableState(on), enabled = enabled, role = Role.Checkbox, onClick = onClick)
    }
    Row(
        Modifier
            .width(width)
            .heightIn(min = 40.dp)
            .clip(stripShape)
            .then(if (on) Modifier else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, stripShape))
            .then(if (on) Modifier.background(BrushworkColors.AccentDim) else Modifier)
            .then(action)
            .then(if (state != null) Modifier.semantics { stateDescription = state } else Modifier)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(start = 4.dp, end = if (arrow) 0.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            color = if (on) Color.White else color,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (arrow) Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = BrushworkColors.OnChromeDim, modifier = Modifier.size(18.dp))
    }
}

/** A status or hint text that stays on row 1 (not a control), [width] wide, up to two lines at 11 sp. */
@Composable
internal fun StripStatusText(text: String, width: Dp, leading: (@Composable () -> Unit)? = null) {
    Row(Modifier.width(width).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) {
            leading()
            Box(Modifier.width(6.dp))
        }
        Text(
            text,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            color = BrushworkColors.OnChromeDim,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
