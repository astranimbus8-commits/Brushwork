package com.brushwork.paint.ui.engine

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.brushwork.paint.engine.LayerStorageMode
import com.brushwork.paint.ui.common.BwSheet
import com.brushwork.paint.ui.common.EngineLabels110
import com.brushwork.paint.ui.theme.BrushworkColors

/**
 * v1.10 (A10, design 2.11): the half-height sheet [LayerStorageSection]'s row opens, titled
 * [EngineLabels110.LAYER_STORAGE]: the row's [subtitle] and [EngineLabels110.FOR_THIS_PHONE], three
 * radio rows ([EngineLabels110.MODE_AUTOMATIC], [EngineLabels110.MODE_FULL],
 * [EngineLabels110.MODE_CLASSIC]; [onMode] at a tap), [EngineLabels110.APPLIES_NEXT] under them,
 * then [EngineLabels110.CHECK_AGAIN] ([onCheckAgain]; off while a check is [pending]) and
 * [EngineLabels110.COPY_DETAILS] ([onCopyDetails]). Opened from the settings dialog's window, so
 * `BwSheet` draws it modal (its body scrolls).
 *
 * Step 7: under the buttons, the collapsible [EngineLabels110.DETAILS] row (a tap calls
 * [onToggleDetails]); while [detailsOpen] the [details] text shows under it, one line per row
 * (tagged [DETAILS_TAG]), the same text Copy details copies. Nothing shows while it is still
 * being read ([details] null).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LayerStorageSheet(
    mode: LayerStorageMode,
    subtitle: String?,
    pending: Boolean,
    onMode: (LayerStorageMode) -> Unit,
    onCheckAgain: () -> Unit,
    onCopyDetails: () -> Unit,
    onDismiss: () -> Unit,
    detailsOpen: Boolean = false,
    details: String? = null,
    onToggleDetails: () -> Unit = {},
) {
    BwSheet(title = EngineLabels110.LAYER_STORAGE, onDismiss = onDismiss) {
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        Text(EngineLabels110.FOR_THIS_PHONE, style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim)
        Column(Modifier.fillMaxWidth().padding(top = 8.dp).selectableGroup()) {
            ModeRow(EngineLabels110.MODE_AUTOMATIC, mode == LayerStorageMode.AUTOMATIC) { onMode(LayerStorageMode.AUTOMATIC) }
            ModeRow(EngineLabels110.MODE_FULL, mode == LayerStorageMode.FULL) { onMode(LayerStorageMode.FULL) }
            ModeRow(EngineLabels110.MODE_CLASSIC, mode == LayerStorageMode.CLASSIC) { onMode(LayerStorageMode.CLASSIC) }
        }
        Text(
            EngineLabels110.APPLIES_NEXT,
            style = MaterialTheme.typography.bodySmall,
            color = BrushworkColors.OnChromeDim,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
        )
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SheetButton(EngineLabels110.CHECK_AGAIN, enabled = !pending, onClick = onCheckAgain)
            SheetButton(EngineLabels110.COPY_DETAILS, onClick = onCopyDetails)
        }
        DetailsRow(detailsOpen, onToggleDetails)
        if (detailsOpen && details != null) {
            Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 8.dp).testTag(DETAILS_TAG)) {
                for (line in details.lines()) {
                    if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim)
                }
            }
        }
    }
}

/** The test tag of the open details list (it is never a label). */
internal const val DETAILS_TAG = "engine.details"

/** The collapsible row of the details list, at least 48 dp tall: the whole row opens or closes it. */
@Composable
private fun DetailsRow(open: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(EngineLabels110.DETAILS, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = BrushworkColors.OnChromeDim)
    }
}

/** A radio row at least 48 dp tall: the whole row selects. */
@Composable
private fun ModeRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = BrushworkColors.Accent))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** A text button at least 40 dp tall, its text wrapped, never cut. */
@Composable
private fun SheetButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.heightIn(min = 40.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, color = if (enabled) BrushworkColors.Accent else BrushworkColors.OnChromeDim, textAlign = TextAlign.Center)
    }
}
