package com.brushwork.paint.ui.selection

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.HighlightAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.ui.common.LabeledSlider
import com.brushwork.paint.ui.common.MagneticLabels18
import com.brushwork.paint.ui.common.NumberAdjust
import com.brushwork.paint.ui.common.NumberField
import com.brushwork.paint.ui.common.ToolIconButton
import com.brushwork.paint.ui.common.closeOnSecondFinger
import com.brushwork.paint.ui.common.iconLongPressLabel
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.theme.IbisColors
import com.brushwork.paint.ui.tools.strip.LocalStripContext
import com.brushwork.paint.ui.tools.strip.StripItem
import com.brushwork.paint.ui.tools.strip.StripRow
import kotlin.math.roundToInt

// The selection strips' compact controls (v1.8, design §3.9): each fits the width its strip
// declares for it (at least that wide, wider only when its text needs it, which the inventory
// test catches), 40 dp tall, with the v1.7 handle (description, else visible text) kept byte for
// byte. Every dropdown and popup is opaque (IbisColors.Menu) and stays anchored in its item, so
// it never closes the More row.

/** Rounded outline of the strip's buttons. */
private val ButtonShape = RoundedCornerShape(8.dp)

/** The touch height of every strip control (row 1 and More-row lines are 44 dp). */
internal val StripControlHeight = 40.dp

/** The declared width (dp) of a 40 dp icon item. */
internal val IconItemWidth = 40.dp

/** The base look of a strip button: outline, or the accent fill when [on]. */
private fun Modifier.stripButton(on: Boolean = false, enabled: Boolean = true): Modifier = this
    .height(StripControlHeight)
    .clip(ButtonShape)
    .background(if (on) BrushworkColors.AccentDim else Color.Transparent)
    .border(1.dp, if (on) BrushworkColors.AccentDim else BrushworkColors.ChromeBorder.copy(alpha = if (enabled) 1f else 0.5f), ButtonShape)

/**
 * An opaque dropdown anchored in a strip item ([IbisColors.Menu]); a second finger closes it,
 * as v1.7's strip menus did.
 */
@Composable
internal fun StripDropdown(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.closeOnSecondFinger(onDismiss),
        containerColor = IbisColors.Menu,
        content = content,
    )
}

/**
 * A dropdown entry: [text] (its handle), an optional [icon], a check mark when [checked]; a
 * [description], when given, is heard (and found) besides the text.
 */
@Composable
internal fun StripMenuItem(text: String, checked: Boolean, icon: ImageVector? = null, description: String? = null, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, color = BrushworkColors.OnChrome) },
        onClick = onClick,
        modifier = if (description != null) Modifier.semantics { contentDescription = description } else Modifier,
        leadingIcon = icon?.let { { Icon(it, contentDescription = null, tint = if (checked) BrushworkColors.Accent else BrushworkColors.OnChrome) } },
        trailingIcon = { if (checked) Icon(Icons.Filled.Check, contentDescription = null, tint = BrushworkColors.Accent) },
    )
}

/**
 * A two-line button: a small [label] over its [value] and a ▾, known by [description] (the v1.7
 * handle, "Tolerance 32", "Refer: Layer") and, when given, [state]. [fixed]: exactly [width] wide,
 * a long value cut with an ellipsis (its [state] says it whole). [valueContent], when given, is
 * drawn in the value's place (Color select's sample swatches); it is never read out, the
 * [state] says the value.
 */
@Composable
internal fun StripValueButton(
    label: String,
    value: String,
    description: String,
    width: Dp,
    onClick: () -> Unit,
    state: String? = null,
    fixed: Boolean = false,
    valueContent: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .then(if (fixed) Modifier.width(width) else Modifier.widthIn(min = width))
            .stripButton()
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                if (state != null) stateDescription = state
                onClick { onClick(); true }
            }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(label, fontSize = 10.sp, lineHeight = 12.sp, color = BrushworkColors.OnChromeDim, maxLines = 1, softWrap = false)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (valueContent != null) {
                    Box(
                        (if (fixed) Modifier.weight(1f, fill = false) else Modifier).height(14.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) { valueContent() }
                } else {
                    Text(
                        value, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, color = BrushworkColors.OnChrome,
                        maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                        modifier = if (fixed) Modifier.weight(1f, fill = false) else Modifier,
                    )
                }
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = BrushworkColors.OnChromeDim, modifier = Modifier.size(12.dp))
            }
        }
    }
}

/**
 * An integer in [range] as "[label] [value][suffix] ▾": a popup with the v1.7 slider and its
 * number field. Its handle is v1.7's chip text, "Tolerance 32" / "Close gaps 0 px"; a v1.8 control
 * ([stateful]) is known by its [label] alone and says its value as its state, so the popup's own
 * label is never a part of another handle (design §4.8 rule 4).
 */
@Composable
internal fun StripSliderChip(
    label: String,
    value: Int,
    range: IntRange,
    width: Dp,
    suffix: String = "",
    stateful: Boolean = false,
    onChange: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        val text = "$value$suffix"
        if (stateful) StripValueButton(label, text, label, width, onClick = { open = true }, state = text)
        else StripValueButton(label, text, "$label $text", width, onClick = { open = true })
        StripDropdown(open, onDismiss = { open = false }) {
            Column(Modifier.width(280.dp).padding(horizontal = 16.dp, vertical = 4.dp)) {
                val span = range.last - range.first
                LabeledSlider(
                    label = label,
                    value = value.toFloat(),
                    onValueChange = { onChange(it.roundToInt().coerceIn(range)) },
                    valueRange = range.first.toFloat()..range.last.toFloat(),
                    steps = if (span in 2..20) span - 1 else 0,
                    valueText = "$value$suffix",
                )
                NumberField(
                    label = label,
                    value = value.toDouble(),
                    onValueChange = { onChange(it.roundToInt().coerceIn(range)) },
                    decimals = 0,
                    suffix = suffix.trim(),
                    min = range.first.toDouble(),
                    max = range.last.toDouble(),
                    // A v1.8 control has no − / + buttons: their "Decrease / Increase <label>" would hold its name (§4.8 rule 4).
                    step = if (stateful) null else 1.0,
                    adjust = NumberAdjust.NONE, // the slider is right above
                )
            }
        }
    }
}

/** One of [options] as "[label]: [text] ▾" (v1.7's handle) and a dropdown of the options' texts. */
@Composable
internal fun <T> StripChoiceChip(label: String, options: List<T>, selected: T, text: (T) -> String, width: Dp, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        StripValueButton(label, text(selected), "$label: ${text(selected)}", width, onClick = { open = true })
        StripDropdown(open, onDismiss = { open = false }) {
            options.forEach { o ->
                StripMenuItem(text(o), checked = o == selected) {
                    onSelect(o)
                    open = false
                }
            }
        }
    }
}

/** An on / off button known by its visible [label]; the accent fill (and its checkbox state) shows it on. */
@Composable
internal fun StripToggle(label: String, checked: Boolean, width: Dp, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .widthIn(min = width)
            .stripButton(on = checked)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = if (checked) Color.White else BrushworkColors.OnChrome,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * [MagneticLabels18.SELECTION_MODE] (v1.8, design §3.9 and §4.8): one 56 dp dropdown in place of v1.7's four mode
 * buttons. The button shows the mode's icon, says the mode as its state, and lists the four modes
 * by their v1.7 descriptions.
 */
@Composable
internal fun SelectionModeDropdown(mode: SelectionMode, onChange: (SelectionMode) -> Unit) {
    IconDropdown(modeIcon(mode), MagneticLabels18.SELECTION_MODE, SelectionModeWidth, state = modeDescription(mode)) { close ->
        SelectionMode.entries.forEach { m ->
            StripMenuItem(modeDescription(m), checked = m == mode, icon = modeIcon(m)) {
                onChange(m)
                close()
            }
        }
    }
}

/**
 * A [width] button showing [icon] and ▾, known by [description] (and [state] when given), that
 * opens [entries] in an opaque dropdown anchored in it; `close` closes the dropdown. On a strip,
 * a long press shows [description] ([iconLongPressLabel]).
 */
@Composable
internal fun IconDropdown(
    icon: ImageVector,
    description: String,
    width: Dp,
    state: String? = null,
    entries: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .width(width)
                .stripButton()
                .iconLongPressLabel(description)
                .clickable(role = Role.Button) { open = true }
                .clearAndSetSemantics {
                    contentDescription = description
                    if (state != null) stateDescription = state
                    role = Role.Button
                    onClick { open = true; true }
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, tint = BrushworkColors.OnChrome, modifier = Modifier.size(22.dp))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = BrushworkColors.OnChromeDim, modifier = Modifier.size(16.dp))
        }
        StripDropdown(open, onDismiss = { open = false }) { entries { open = false } }
    }
}

/**
 * A 40 dp ✓ known by [description] ("Close polygon"), with the outline's point count under it
 * ("3 pt", v1.7's readout); it needs three points. A long press shows [description].
 */
@Composable
internal fun CountedCloseButton(description: String, count: Int, onClick: () -> Unit) {
    val enabled = count >= 3
    Column(
        Modifier
            .size(IconItemWidth)
            .clip(ButtonShape)
            .iconLongPressLabel(description)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.Check,
            contentDescription = null,
            tint = if (enabled) BrushworkColors.OnChrome else BrushworkColors.OnChromeDim,
            modifier = Modifier.size(20.dp),
        )
        Text("$count pt", fontSize = 9.sp, lineHeight = 10.sp, color = BrushworkColors.OnChromeDim, maxLines = 1, softWrap = false)
    }
}

/** A 40 dp icon action known by [description] (shown by a long press), tinted [tint] (the curve point's actions). */
@Composable
internal fun StripIconAction(icon: ImageVector, description: String, tint: Color = BrushworkColors.OnChrome, onClick: () -> Unit) {
    Box(
        Modifier
            .size(IconItemWidth)
            .clip(ButtonShape)
            .iconLongPressLabel(description)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** The declared width of the mode dropdown. */
internal val SelectionModeWidth = 56.dp

/** The item key of every selection strip's mode dropdown. */
internal const val MODE_KEY = "select.mode"

/** The mode dropdown item, with the four v1.7 mode handles inside its dropdown (inventory rule 5). */
internal fun selectionModeItem(mode: SelectionMode, onChange: (SelectionMode) -> Unit): StripItem =
    StripItem(MODE_KEY, MagneticLabels18.SELECTION_MODE, SelectionModeWidth) { SelectionModeDropdown(mode, onChange) }

/** `StripSpec.movedInto` of a strip with [selectionModeItem]: the four mode texts live in its dropdown. */
internal val modesMovedInto: Map<String, String> = SelectionMode.entries.associate { modeDescription(it) to MODE_KEY }

/**
 * The More row's "Deselect" and "Selection menu" (v1.8, design §3.9): only while the selection
 * bar is hidden, which then offers both. The menu is a sheet: the item closes the More row and
 * raises [openMenu]; the strip's overlays show [SelectionMenuOverlay].
 */
@Composable
internal fun selectionShortcutItems(c: EditorController, openMenu: () -> Unit): List<StripItem> {
    if (LocalStripContext.current.selectionBarShown) return emptyList()
    return listOf(
        StripItem("select.deselect", "Deselect", IconItemWidth, row = StripRow.MORE) {
            ToolIconButton(Icons.Outlined.Deselect, "Deselect", onClick = { c.deselect() }, enabled = c.selection != null, size = IconItemWidth)
        },
        StripItem("select.menu", "Selection menu", IconItemWidth, row = StripRow.MORE) {
            val ctx = LocalStripContext.current
            ToolIconButton(Icons.Outlined.HighlightAlt, "Selection menu", onClick = {
                ctx.closeMore()
                openMenu()
            }, size = IconItemWidth)
        },
    )
}

/** The selection menu sheet of the strip's overlays, open while [open]. */
@Composable
internal fun SelectionMenuOverlay(c: EditorController, open: Boolean, onDismiss: () -> Unit) {
    if (open) SelectionPanel(c, onDismiss)
}
