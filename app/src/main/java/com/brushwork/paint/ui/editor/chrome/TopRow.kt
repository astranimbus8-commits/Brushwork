package com.brushwork.paint.ui.editor.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.brushwork.paint.ui.editor.MenuEntry
import com.brushwork.paint.ui.editor.blockCanvasTouches
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.theme.IbisColors
import com.brushwork.paint.ui.theme.IbisDims

/** One circle of the top row: its [label] (I10), glyph, "on" state and action. */
internal class TopButton(
    val slot: ChromeLayout.TopSlot,
    val label: String,
    val icon: ImageVector,
    val on: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * The ibisPaint top row (v1.6 §3.7.3): up to 8 grey circles over the canvas surround (no bar
 * background), left-aligned at [spec]'s pitch. "On" fills a circle with [IbisColors.TopButtonOn];
 * a disabled one is lighter with a faded glyph. The last circle opens the More menu, a dark
 * dropdown under the row's left end ([moreHeader] over [moreEntries]; on narrow screens the folded
 * circles come first as checkable entries, [folded]).
 */
@Composable
internal fun TopRow(
    spec: ChromeLayout.TopRow,
    buttons: List<TopButton>,
    moreOpen: Boolean,
    onMoreOpenChange: (Boolean) -> Unit,
    moreHeader: String,
    moreEntries: List<MenuEntry>,
    modifier: Modifier = Modifier,
) {
    val bySlot = buttons.associateBy { it.slot }
    Box(modifier.fillMaxWidth().height(IbisDims.TopRowHeight).testTag(ChromeTags.TOP_ROW)) {
        Row(Modifier.blockCanvasTouches()) {
            for (slot in spec.slots) {
                val b = bySlot[slot] ?: continue
                TopCircle(b, spec)
            }
        }
        // The More menu drops from the row's left end (ibisPaint's dropdown position).
        val folded = spec.folded.mapNotNull { bySlot[it] }.map { b ->
            MenuEntry(b.label, b.icon, checked = b.on, enabled = b.enabled, onClick = b.onClick)
        }
        val entries = if (folded.isEmpty()) moreEntries else folded + moreEntries.mapIndexed { i, e -> if (i == 0) e.copy(dividerBefore = true) else e }
        MoreMenu(moreOpen, { onMoreOpenChange(false) }, moreHeader, entries)
    }
}

@Composable
private fun TopCircle(b: TopButton, spec: ChromeLayout.TopRow) {
    val interaction = remember { MutableInteractionSource() }
    val fill = when {
        !b.enabled -> IbisColors.TopButtonDisabled
        b.on -> IbisColors.TopButtonOn
        else -> IbisColors.TopButton
    }
    Box(
        Modifier
            .width(spec.pitch.dp)
            .height(IbisDims.TopRowHeight)
            .semantics {
                contentDescription = b.label
                if (b.slot != ChromeLayout.TopSlot.UNDO && b.slot != ChromeLayout.TopSlot.REDO && b.slot != ChromeLayout.TopSlot.MORE) {
                    selected = b.on
                    stateDescription = if (b.on) "On" else "Off"
                }
            }
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = false, radius = (spec.circle / 2f).dp),
                enabled = b.enabled,
                role = Role.Button,
                onClick = b.onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(spec.circle.dp).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
            Icon(
                b.icon,
                contentDescription = null,
                tint = IbisColors.TopGlyph,
                modifier = Modifier.size(IbisDims.TopGlyph).alpha(if (b.enabled) 1f else 0.6f),
            )
        }
    }
}

/**
 * The More menu (v1.6 §3.7.6): a dark dropdown ([IbisColors.Sheet]) 280 dp wide under the top
 * row's left end, scrolling; the document's name and size head it (moved from the v1.5 top bar).
 * An entry closes the menu and acts.
 */
@Composable
private fun MoreMenu(expanded: Boolean, onDismiss: () -> Unit, header: String, entries: List<MenuEntry>) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = DpOffset(IbisDims.OptionsStripSide, 0.dp),
        containerColor = IbisColors.Sheet,
        shape = RoundedCornerShape(IbisDims.OptionsStripRadius),
        modifier = Modifier.width(IbisDims.MoreMenuWidth),
    ) {
        Column(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(header, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = BrushworkColors.OnChrome, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        HorizontalDivider(color = BrushworkColors.ChromeBorder)
        entries.forEach { e ->
            if (e.dividerBefore) HorizontalDivider(color = BrushworkColors.ChromeBorder)
            DropdownMenuItem(
                text = { Text(e.label) },
                leadingIcon = { Icon(e.icon, contentDescription = null) },
                trailingIcon = if (e.checked == true) ({ Icon(Icons.Filled.Check, contentDescription = "On", tint = BrushworkColors.Accent) }) else null,
                onClick = { onDismiss(); e.onClick() },
                enabled = e.enabled,
                colors = MenuDefaults.itemColors(
                    textColor = BrushworkColors.OnChrome,
                    leadingIconColor = BrushworkColors.OnChrome,
                    disabledTextColor = BrushworkColors.OnChromeDim.copy(alpha = 0.6f),
                    disabledLeadingIconColor = BrushworkColors.OnChromeDim.copy(alpha = 0.6f),
                ),
            )
        }
    }
}
