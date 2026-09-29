package com.brushwork.paint.ui.color

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brushwork.paint.ui.common.BwDialog

/**
 * Stand-alone color picker dialog for any module that needs a color (shape fill, text color,
 * grid color, filter color parameters...): wheel / RGB / HSB, hex, the active palette and the
 * recent colors. [showAlpha] adds an opacity slider; without it the result is always opaque.
 * OK calls [onPick] with the color and then [onDismiss]; Cancel only calls [onDismiss].
 */
@Composable
fun ColorPickerDialog(
    initial: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Pick a color",
    showAlpha: Boolean = false,
) {
    val store = rememberPaletteStore()
    val start = rememberSaveable { if (showAlpha) initial else initial or OPAQUE }
    val state = rememberColorEditState(start)
    val mode = PickerMode.entries.getOrElse(store.data.pickerMode) { PickerMode.WHEEL }

    fun use(c: Int) = state.setColor(if (showAlpha) c else c or OPAQUE)

    BwDialog(
        title = title,
        onDismiss = onDismiss,
        confirmText = "OK",
        onConfirm = {
            val picked = if (showAlpha) state.color else state.color or OPAQUE
            if (picked != start) store.addRecent(picked)
            onPick(picked)
            onDismiss()
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CompareSwatch(previous = start, current = state.color, onRevert = { state.setColor(start) }, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            HexField(state, withAlpha = showAlpha, modifier = Modifier.width(if (showAlpha) 152.dp else 128.dp))
        }
        Spacer(Modifier.height(8.dp))
        ModeTabs(mode, onSelect = { store.setPickerMode(it) }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        PickerBody(state, mode, wheelMaxSize = 240.dp)
        if (showAlpha) AlphaSlider(state, Modifier.padding(top = 4.dp))
        PaletteSection(store, current = state.color, onUse = ::use, manage = false)
        RecentColorsSection(store, onUse = ::use)
    }
}
