package com.brushwork.paint.ui.filters

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.ui.common.closeOnSecondFinger
import com.brushwork.paint.ui.theme.BrushworkColors

/**
 * v1.9 (R10): a choice shown as a dropdown, as the layers panel shows its blend modes: one 44 dp
 * row with the label, the current option on a button and the R8 reset icon ([ResetSlot]); the
 * options are menu rows, shown only while the menu is open (a check before the current one). A
 * pick is one finished change. Used for the chromatic-aberration filters' "Blend mode".
 */
@Composable
internal fun ChoiceDropdown(host: ParamHost, p: FilterParam.Choice, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    val current = host.values.choice(p.key).coerceIn(0, p.options.lastIndex)
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(p.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Box {
            OutlinedButton(
                onClick = { open = true },
                enabled = enabled,
                modifier = Modifier.heightIn(min = 40.dp).widthIn(max = 200.dp),
                // Narrow paddings leave the longest mode name room at large font scales.
                contentPadding = PaddingValues(start = 12.dp, end = 4.dp),
            ) {
                Text(p.options[current], maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.closeOnSecondFinger { open = false }) {
                p.options.forEachIndexed { i, option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            open = false
                            if (i != current) { host.update(p.key, i); host.onChangeFinished(p.key) }
                        },
                        modifier = Modifier.semantics { selected = i == current },
                        leadingIcon = {
                            if (i == current) Icon(Icons.Filled.Check, contentDescription = null, tint = BrushworkColors.Accent)
                            else Spacer(Modifier.size(24.dp))
                        },
                    )
                }
            }
        }
        ResetSlot(host, p, enabled)
    }
}
