package com.brushwork.paint.ui.color

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.brushwork.paint.ColorModeOps
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.common.BwSheet
import com.brushwork.paint.ui.common.ColorSwatch
import com.brushwork.paint.ui.common.PanelCard
import com.brushwork.paint.ui.theme.BrushworkColors

/**
 * Drawing-color panel: previous/current comparison, primary/secondary swap, HSB wheel, RGB and
 * HSB sliders, hex entry, eyedropper shortcut, saved palettes and recent colors. Edits update
 * `controller.color` live (always opaque; brush opacity is separate).
 */
@Composable
fun ColorPickerPanel(controller: EditorController, onDismiss: () -> Unit) {
    val store = rememberPaletteStore()
    val previous = rememberSaveable { controller.color or OPAQUE }
    val state = rememberColorEditState(controller.color or OPAQUE) { c -> controller.color = c or OPAQUE }
    // Document.colorMode isn't observable; every mode change (and its undo) bumps docVersion.
    val colorMode = remember(controller.docVersion) { controller.doc.colorMode }
    val display: (Int) -> Int = remember(colorMode) { { c -> ColorModeOps.displayColor(c, colorMode) } }
    val mode = PickerMode.entries.getOrElse(store.data.pickerMode) { PickerMode.WHEEL }

    // Follow changes made elsewhere (swap, undo of a color pick, eyedropper...).
    LaunchedEffect(state) {
        snapshotFlow { controller.color }.collect { c -> if (c != state.color) state.setColor(c or OPAQUE, notify = false) }
    }
    // Record the color in "recent" when the panel closes with a different color.
    val latestPrevious by rememberUpdatedState(previous)
    DisposableEffect(controller, store) {
        onDispose {
            val c = controller.color or OPAQUE
            if (c != latestPrevious) store.addRecent(c)
        }
    }

    // Stable callbacks: the palette/recent sections then skip recomposition while dragging.
    val use: (Int) -> Unit = remember(state, store) {
        { c ->
            val o = c or OPAQUE
            state.setColor(o)
            store.addRecent(o)
        }
    }
    val current: () -> Int = remember(state) { { state.color } }
    val swap: () -> Unit = remember(controller) {
        {
            val primary = controller.color
            controller.color = controller.secondaryColor or OPAQUE
            controller.secondaryColor = primary
        }
    }

    BwSheet(
        title = "Color",
        onDismiss = onDismiss,
        actions = {
            IconButton(onClick = { controller.selectTool(ToolId.EYEDROPPER); onDismiss() }) {
                Icon(Icons.Filled.Colorize, contentDescription = "Eyedropper: pick a color from the canvas")
            }
        },
    ) {
        // The sheet is its own window: its focus manager is only visible inside the content.
        val focusManager = LocalFocusManager.current
        Row(Modifier.clearFocusOnPress(focusManager), verticalAlignment = Alignment.CenterVertically) {
            CompareSwatch(
                previous = display(previous),
                current = display(state.color),
                onRevert = { state.setColor(previous) },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            PrimarySecondary(
                primary = display(controller.color),
                secondary = display(controller.secondaryColor),
                onSwap = swap,
            )
            IconButton(onClick = swap) { Icon(Icons.Filled.SwapHoriz, contentDescription = "Swap primary and secondary colors") }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ModeTabs(mode, onSelect = { store.setPickerMode(it) }, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            HexField(state, withAlpha = false, modifier = Modifier.width(132.dp))
        }
        Spacer(Modifier.height(8.dp))
        PickerBody(state, mode, wheelMaxSize = 290.dp)
        if (colorMode != ColorMode.RGB) ColorModeHint(colorMode)
        PaletteSection(store, current = current, onUse = use, display = display)
        RecentColorsSection(store, onUse = use, display = display)
    }
}

/** Primary swatch over the secondary one (tap to swap). */
@Composable
private fun PrimarySecondary(primary: Int, secondary: Int, onSwap: () -> Unit) {
    Box(
        Modifier
            .size(width = 52.dp, height = 48.dp)
            .clickable(onClickLabel = "Swap primary and secondary colors", role = Role.Button, onClick = onSwap)
    ) {
        ColorSwatch(secondary, Modifier.align(Alignment.BottomEnd), size = 30.dp)
        ColorSwatch(primary, Modifier.align(Alignment.TopStart), size = 34.dp, selected = true)
    }
}

@Composable
private fun ColorModeHint(mode: ColorMode) {
    val text = when (mode) {
        ColorMode.GRAYSCALE -> "This canvas is grayscale: colors are painted as their gray value. Swatches show the painted result."
        ColorMode.MONOCHROME -> "This canvas is 1-bit monochrome: every color is painted as pure black or white. Swatches show the painted result."
        ColorMode.RGB -> return
    }
    PanelCard(Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = BrushworkColors.Accent)
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChrome, modifier = Modifier.fillMaxWidth())
        }
    }
}
