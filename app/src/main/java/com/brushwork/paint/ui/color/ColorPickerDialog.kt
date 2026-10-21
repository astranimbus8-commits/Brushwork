package com.brushwork.paint.ui.color

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.brushwork.paint.ui.common.BwSheet
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlinx.coroutines.flow.conflate

/**
 * Stand-alone color picker for any module that needs a color (shape fill, text color, grid
 * color, filter color parameters...): wheel / RGB / HSB, hex, the active palette and the recent
 * colors. [showAlpha] adds an opacity slider; without it the result is always opaque.
 * OK calls [onPick] with the color and then [onDismiss]; Cancel (and closing the sheet) only
 * calls [onDismiss].
 *
 * Shown as a half-height, see-through sheet like every editor panel, so the canvas stays in
 * view while picking.
 *
 * v1.10 (R7, C1): with [onPreview], the color being edited is delivered live, at most once per
 * frame (conflated), from the first change on. Cancel and Back (and a modal sheet's outside tap)
 * call onPreview(start) and then [onDismiss]; OK calls [onPick] only (no further preview). In the
 * editor's non-modal sheet an outside touch only minimizes it and keeps the preview. Without
 * [onPreview] the dialog behaves as in v1.9.
 */
@Composable
fun ColorPickerDialog(
    initial: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Pick a color",
    showAlpha: Boolean = false,
    onPreview: ((Int) -> Unit)? = null,
) {
    val model = rememberColorDialogModel(initial, showAlpha)
    val cancel: () -> Unit = if (onPreview == null) onDismiss else {
        {
            model.closed = true
            onPreview(model.start)
            onDismiss()
        }
    }
    BwSheet(
        title = title,
        onDismiss = cancel,
        showClose = false,
        actions = { ColorDialogActions(model, onPick, onDismiss, onCancel = cancel) },
    ) {
        ColorDialogBody(model, onPreview)
    }
}

/** State of an open color dialog: the color it started from and the one being edited. */
@Stable
internal class ColorDialogModel(val start: Int, val state: ColorEditState, val showAlpha: Boolean, val store: PaletteStore) {
    /** The color OK delivers (opaque unless the dialog shows alpha). */
    val picked: Int get() = if (showAlpha) state.color else state.color or OPAQUE

    /** v1.10 (R7): set by OK and Cancel; no live preview is delivered after it. */
    var closed: Boolean = false

    /** Records a changed color in the recents, then delivers it and closes. */
    fun confirm(onPick: (Int) -> Unit, onDismiss: () -> Unit) {
        closed = true
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

/** Cancel / OK, shown in the sheet's title row. v1.10: Cancel calls [onCancel] ([onDismiss] by default; with a live preview it restores the start color first). */
@Composable
internal fun RowScope.ColorDialogActions(
    model: ColorDialogModel,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
    onCancel: () -> Unit = onDismiss,
) {
    val focusManager = LocalFocusManager.current
    TextButton(onClick = onCancel) { Text("Cancel") }
    TextButton(onClick = {
        // A hex being typed applies first.
        focusManager.clearFocus()
        model.confirm(onPick, onDismiss)
    }) { Text("OK", fontWeight = FontWeight.SemiBold, color = BrushworkColors.Accent) }
}

/**
 * Comparison + hex, mode tabs, wheel or sliders, optional opacity, palette and recents.
 *
 * v1.10 (R7, C1): [onPreview] gets the color being edited ([ColorDialogModel.picked]) at most once
 * per frame (conflated), from the first change on (the unchanged start color is not delivered, so
 * opening never touches the object), and never after OK or Cancel ([ColorDialogModel.closed]).
 * [extraAbove] is the height of a row the caller puts above the body (the Object colors sheet's
 * tabs, 48 dp), so the wheel and OK still fit without scrolling.
 */
@Composable
internal fun ColumnScope.ColorDialogBody(model: ColorDialogModel, onPreview: ((Int) -> Unit)? = null, extraAbove: Dp = 0.dp) {
    val state = model.state
    val store = model.store
    val showAlpha = model.showAlpha
    if (onPreview != null) {
        val deliver by rememberUpdatedState(onPreview)
        LaunchedEffect(model) {
            var first = true
            snapshotFlow { model.picked }.conflate().collect { c ->
                withFrameNanos { }
                val unchangedStart = first && c == model.start
                first = false
                if (!unchangedStart && !model.closed) deliver(c)
            }
        }
    }
    val mode = PickerMode.entries.getOrElse(store.data.pickerMode) { PickerMode.WHEEL }
    val use: (Int) -> Unit = remember(state, showAlpha) { { c -> state.setColor(if (showAlpha) c else c or OPAQUE) } }
    val current: () -> Int = remember(state) { { state.color } }

    val hexWidth = if (showAlpha) 152.dp else 128.dp
    val revert = { state.setColor(model.start) }
    if (mode == PickerMode.WHEEL) {
        ModeTabs(mode, onSelect = { store.setPickerMode(it) }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        // The wheel beside the comparison and hex: the biggest wheel whose row (and the opacity
        // slider under it) fits in the half-height sheet without scrolling.
        val screenWidth = LocalConfiguration.current.screenWidthDp.dp.coerceAtMost(SheetMaxWidth)
        val byWidth = screenWidth - 32.dp - hexWidth - 12.dp
        val byHeight = wheelSizeForSheet(controlsAbove = 60.dp + extraAbove + if (showAlpha) AlphaRowHeight else 0.dp)
        Row(verticalAlignment = Alignment.Top) {
            HsbWheel(state, Modifier.size(minOf(byWidth, byHeight).coerceAtLeast(120.dp)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                CompareSwatch(previous = model.start, current = state.color, onRevert = revert, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                HexField(state, withAlpha = showAlpha, modifier = Modifier.fillMaxWidth())
            }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CompareSwatch(previous = model.start, current = state.color, onRevert = revert, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            HexField(state, withAlpha = showAlpha, modifier = Modifier.width(hexWidth))
        }
        Spacer(Modifier.height(8.dp))
        ModeTabs(mode, onSelect = { store.setPickerMode(it) }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        PickerBody(state, mode, wheelMaxSize = 240.dp)
    }
    if (showAlpha) AlphaSlider(state, Modifier.padding(top = 4.dp))
    PaletteSection(store, current = current, onUse = use, manage = false)
    RecentColorsSection(store, onUse = use)
}

/** Widest a bottom sheet gets (Material's default), whatever the screen. */
private val SheetMaxWidth = 640.dp

/** Height of the opacity row (gradient slider beside its number field). */
private val AlphaRowHeight = 72.dp
