package com.brushwork.paint.ui.placement

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brushwork.paint.ui.common.closeOnSecondFinger
import com.brushwork.paint.ui.common.iconLongPressLabel
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
 * type button). With [announceContent] false the texts and icons inside are not announced
 * either (a short name whose description is the handle, I10). With [longPress] (an icon alone, no
 * text) a long press shows [description] in a bubble and opens nothing (v1.8 polish).
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
    announceContent: Boolean = true,
    longPress: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val own: SemanticsPropertyReceiver.() -> Unit = {
        contentDescription = description
        if (state != null) stateDescription = state
    }
    Row(
        Modifier
            .width(width)
            .heightIn(min = 40.dp)
            .clip(stripShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, stripShape)
            .then(if (open) Modifier.clearAndSetSemantics { } else Modifier)
            .then(if (longPress) Modifier.iconLongPressLabel(description) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .then(if (announceContent) Modifier.semantics(properties = own) else Modifier.clearAndSetSemantics(own))
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
 * A bordered strip button [width] wide showing [text] on up to two lines at 11 sp, after [icon]
 * when given (above it when [stacked], at 10 sp: a 40 dp button), with ▾ when [arrow] (it opens a
 * dropdown). A toggle passes [checked]: it is then a checkbox, tinted while on (or [on] tints a
 * plain button that opens what it switches); [state] is an optional state description. Its
 * handle is [text], or [description] when given (the text is then announced too, unless
 * [announceText] is false). [dimmed] draws it faded while still tappable (a tap explains why).
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
    icon: ImageVector? = null,
    description: String? = null,
    announceText: Boolean = true,
    stacked: Boolean = false,
    on: Boolean = checked == true,
    dimmed: Boolean = false,
) {
    val action = if (checked == null) {
        Modifier.clickable(enabled = enabled, onClick = onClick)
    } else {
        Modifier.triStateToggleable(state = ToggleableState(checked), enabled = enabled, role = Role.Checkbox, onClick = onClick)
    }
    val own: SemanticsPropertyReceiver.() -> Unit = {
        if (description != null) contentDescription = description
        if (state != null) stateDescription = state
    }
    val semantics = when {
        description != null && !announceText -> Modifier.clearAndSetSemantics(own)
        description != null || state != null -> Modifier.semantics(properties = own)
        else -> Modifier
    }
    val tint = if (on) Color.White else color
    val label: @Composable (Modifier) -> Unit = { m ->
        Text(
            text,
            fontSize = if (stacked) 10.sp else 11.sp,
            lineHeight = if (stacked) 12.sp else 13.sp,
            color = tint,
            maxLines = if (stacked) 1 else 2,
            softWrap = !stacked,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = m,
        )
    }
    val box = Modifier
        .width(width)
        .heightIn(min = 40.dp)
        .clip(stripShape)
        .then(if (on) Modifier else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, stripShape))
        .then(if (on) Modifier.background(BrushworkColors.AccentDim) else Modifier)
        .then(action)
        .then(semantics)
        .alpha(if (!enabled) 0.5f else if (dimmed) 0.45f else 1f)
    if (stacked) {
        Column(box, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            label(Modifier)
        }
        return
    }
    Row(
        box.padding(start = 4.dp, end = if (arrow) 0.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Box(Modifier.width(4.dp))
        }
        label(Modifier.weight(1f, fill = false))
        if (arrow) Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = BrushworkColors.OnChromeDim, modifier = Modifier.size(18.dp))
    }
}

/** "Snap to objects" on G's More rows (§3.9: one name per tool, the longer one), 11 sp. */
internal const val SNAP_LABEL = "Snap to objects"

/** [SNAP_LABEL] at 11 sp on one line. */
internal const val SNAP_DP = 96f

/** "Snap to objects: on" / "off", the v1.7 chip's state. */
internal fun snapState(on: Boolean) = if (on) "Snap to objects: on" else "Snap to objects: off"

/**
 * "Snap to objects" on the More row as [key]: a toggle of the app-wide setting, read through [on]
 * and switched through [set] (the Transform tool also clears its guides).
 */
internal fun snapItem(key: String, on: () -> Boolean, set: (Boolean) -> Unit): StripItem =
    StripItem(key, SNAP_LABEL, SNAP_DP.dp, row = StripRow.MORE) {
        val now = on()
        StripTextButton(SNAP_LABEL, SNAP_DP.dp, onClick = { set(!now) }, checked = now, state = snapState(now))
    }

/**
 * A status or hint text that stays on row 1 (not a control), [width] wide, up to two lines at 11 sp.
 * [balanced]: two lines of even length instead of a full first line and a lone last word (a v1.7
 * text that may not be reworded, v1.8 polish).
 */
@Composable
internal fun StripStatusText(text: String, width: Dp, leading: (@Composable () -> Unit)? = null, balanced: Boolean = false) {
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
            style = if (balanced) LocalTextStyle.current.copy(lineBreak = BALANCED_LINES) else LocalTextStyle.current,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** [StripStatusText]'s balanced breaking: even lines, the usual rules for where a line may end. */
private val BALANCED_LINES = LineBreak(LineBreak.Strategy.Balanced, LineBreak.Strictness.Normal, LineBreak.WordBreak.Default)

/**
 * v1.8: for a strip state with nothing on the More row (Transform once nothing is transformed,
 * after ✓), closed a More row left open by the state before (D25). v1.9 (R3): rows close only by
 * their toggle, so this is a no-op; a row with nothing to show is hidden by its host (and shows
 * again when the state has items for it). The call sites stay.
 */
@Composable
internal fun ClosesMoreRow() {
    // Nothing: v1.9 (R3), rows close only by their toggle.
}
