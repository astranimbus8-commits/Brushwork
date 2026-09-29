package com.brushwork.paint.ui.color

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.ui.common.BwDialog
import com.brushwork.paint.ui.common.ColorSwatch

// STUB - replaced by the color module (full wheel + sliders + palette in a dialog).
/**
 * Stand-alone color picker dialog for any module that needs a color (shape fill, text color,
 * grid color, filter color parameters...). [showAlpha] adds an alpha slider.
 */
@Composable
fun ColorPickerDialog(
    initial: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Pick a color",
    showAlpha: Boolean = false,
) {
    var hex by remember { mutableStateOf(ColorUtils.toHex(initial, showAlpha)) }
    val parsed = ColorUtils.parseHex(hex)
    BwDialog(title = title, onDismiss = onDismiss, onConfirm = { parsed?.let(onPick); onDismiss() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ColorSwatch(parsed ?: initial)
            Spacer(Modifier.width(12.dp))
            OutlinedTextField(value = hex, onValueChange = { hex = it }, label = { Text("Hex") }, singleLine = true)
        }
    }
}
