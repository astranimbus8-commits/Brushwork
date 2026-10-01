package com.brushwork.paint.ui.common

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlignHorizontalCenter
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.brushwork.paint.EditorController
import com.brushwork.paint.ui.theme.BrushworkColors

/**
 * The app-wide "Snap to objects" toggle ([EditorController.snapping]) for a tool's options strip:
 * dragged points, shapes and boxes align to the canvas, other layers' content, points and the
 * lines drawn in layers (Table filter lines, frame borders...). [compact] shows "Snap" only.
 */
@Composable
fun SnapToObjectsChip(controller: EditorController, compact: Boolean = true, modifier: Modifier = Modifier) {
    val on = controller.snapping.enabled
    FilterChip(
        selected = on,
        onClick = { controller.snapping.enabled = !on },
        label = { Text(if (compact) "Snap" else "Snap to objects", maxLines = 1) },
        leadingIcon = { Icon(Icons.Filled.AlignHorizontalCenter, contentDescription = null, modifier = Modifier.size(18.dp)) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = BrushworkColors.AccentDim,
            selectedLabelColor = Color.White,
            selectedLeadingIconColor = Color.White,
        ),
        modifier = modifier
            .padding(horizontal = 4.dp)
            .semantics { stateDescription = if (on) "Snap to objects: on" else "Snap to objects: off" },
    )
}
