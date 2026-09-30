package com.brushwork.paint.ui.color

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.brushwork.paint.ui.common.BwSheet
import com.brushwork.paint.ui.theme.BrushworkColors

/**
 * Stand-alone color picker for any module that needs a color (shape fill, text color, grid
 * color, filter color parameters...): wheel / RGB / HSB, hex, the active palette and the recent
 * colors. [showAlpha] adds an opacity slider; without it the result is always opaque.
 * OK calls [onPick] with the color and then [onDismiss]; Cancel (and closing the sheet) only
 * calls [onDismiss].
 *
 * Shown as a half-height, see-through sheet like every editor panel, so the canvas stays in
 * view while picking.
 */
@Composable
fun ColorPickerDialog(
    initial: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Pick a color",
    showAlpha: Boolean = false,
) {
    val model = rememberColorDialogModel(initial, showAlpha)
    BwSheet(
        title = title,
        onDismiss = onDismiss,
        showClose = false,
        actions = { ColorDialogActions(model, onPick, onDismiss) },
    ) {
        ColorDialogBody(model)
    }
}

/** State of an open color dialog: the color it started from and the one being edited. */
@Stable
internal class ColorDialogModel(val start: Int, val state: ColorEditState, val showAlpha: Boolean, val store: PaletteStore) {
    /** The color OK delivers (opaque unless the dialog shows alpha). */
    val picked: Int get() = if (showAlpha) state.color else state.color or OPAQUE

    /** Records a changed color in the recents, then delivers it and closes. */
    fun confirm(onPick: (Int) -> Unit, onDismiss: () -> Unit) {
        val c = picked
        if (c != start) store.addRecent(c)
        onPick(c)
        onDismiss()
    }
}

@Composable
internal fun rememberColorDialogModel(initial: Int, showAlpha: Boolean): ColorDialogModel {
    val store = rememberPaletteStore()
    val start = rememberSaveable { if (showAlpha) initial else initial or OPAQUE }
    val state = rememberColorEditState(start)
    return remember(start, state, showAlpha, store) { ColorDialogModel(start, state, showAlpha, store) }
}

/** Cancel / OK, shown in the sheet's title row. */
@Composable
internal fun RowScope.ColorDialogActions(model: ColorDialogModel, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val focusManager = LocalFocusManager.current
    TextButton(onClick = onDismiss) { Text("Cancel") }
    TextButton(onClick = {
        // A hex being typed applies first.
        focusManager.clearFocus()
        model.confirm(onPick, onDismiss)
    }) { Text("OK", fontWeight = FontWeight.SemiBold, color = BrushworkColors.Accent) }
}

/** Comparison + hex, mode tabs, wheel or sliders, optional opacity, palette and recents. */
@Composable
internal fun ColumnScope.ColorDialogBody(model: ColorDialogModel) {
    val state = model.state
    val store = model.store
    val showAlpha = model.showAlpha
    val mode = PickerMode.entries.getOrElse(store.data.pickerMode) { PickerMode.WHEEL }
    val use: (Int) -> Unit = remember(state, showAlpha) { { c -> state.setColor(if (showAlpha) c else c or OPAQUE) } }
    val current: () -> Int = remember(state) { { state.color } }

    Row(verticalAlignment = Alignment.CenterVertically) {
        CompareSwatch(previous = model.start, current = state.color, onRevert = { state.setColor(model.start) }, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        HexField(state, withAlpha = showAlpha, modifier = Modifier.width(if (showAlpha) 152.dp else 128.dp))
    }
    Spacer(Modifier.height(8.dp))
    ModeTabs(mode, onSelect = { store.setPickerMode(it) }, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    // Wheel (and the opacity slider under it) fit in the half-height sheet without scrolling.
    PickerBody(state, mode, wheelMaxSize = wheelSizeForSheet(controlsAbove = 124.dp + if (showAlpha) 48.dp else 0.dp))
    if (showAlpha) AlphaSlider(state, Modifier.padding(top = 4.dp))
    PaletteSection(store, current = current, onUse = use, manage = false)
    RecentColorsSection(store, onUse = use)
}
