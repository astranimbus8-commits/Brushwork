package com.brushwork.paint.ui.layers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.brushwork.paint.ui.common.LayerLabels19
import com.brushwork.paint.ui.common.V19Tags
import com.brushwork.paint.ui.theme.BrushworkColors

/**
 * v1.9 (R19): the question before "Merge down" turns two data layers (text, shape or vector) into
 * pixels: [LayerLabels19.MERGE_TITLE], the body naming [reason] (a [LayerLabels19] WHY_* text),
 * [LayerLabels19.MERGE_CONFIRM] ([onMerge]) and Cancel ([onDismiss], as a tap outside: no step).
 * With [onKeepEditable] (only for [LayerLabels19.WHY_OVERLAP]), [LayerLabels19.KEEP_EDITABLE] and its
 * note [LayerLabels19.KEEP_EDITABLE_NOTE] follow the body. The body scrolls under a large font.
 */
@Composable
internal fun MergeIntoPixelsDialog(reason: String, onMerge: () -> Unit, onKeepEditable: (() -> Unit)?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(V19Tags.MERGE_DIALOG),
        title = { Text(LayerLabels19.MERGE_TITLE) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(String.format(LayerLabels19.MERGE_BODY, reason))
                if (onKeepEditable != null) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onKeepEditable) { Text(LayerLabels19.KEEP_EDITABLE) }
                    Text(
                        LayerLabels19.KEEP_EDITABLE_NOTE,
                        style = MaterialTheme.typography.bodySmall,
                        color = BrushworkColors.OnChromeDim,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onMerge) { Text(LayerLabels19.MERGE_CONFIRM) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = BrushworkColors.ChromeHigh,
    )
}
