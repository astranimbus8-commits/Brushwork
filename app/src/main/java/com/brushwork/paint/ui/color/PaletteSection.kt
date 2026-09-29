package com.brushwork.paint.ui.color

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.brushwork.paint.ui.common.BwDialog
import com.brushwork.paint.ui.common.ChoiceChips
import com.brushwork.paint.ui.common.ColorSwatch
import com.brushwork.paint.ui.common.SectionHeader
import com.brushwork.paint.ui.theme.BrushworkColors

/**
 * Palette chips + swatches of the active palette. Tap a swatch to [onUse] it; long-press (user
 * palettes) for replace / move / delete. [current] supplies the color added by "+" and used by
 * "Replace" (a lambda so dragging the picker doesn't recompose the swatches). [display] maps colors for preview (grayscale documents). With [manage] the header
 * menu can create, rename, duplicate and delete palettes.
 */
@Composable
fun PaletteSection(
    store: PaletteStore,
    current: () -> Int,
    onUse: (Int) -> Unit,
    modifier: Modifier = Modifier,
    display: (Int) -> Int = { it },
    manage: Boolean = true,
    swatchSize: Dp = 40.dp,
) {
    val data = store.data
    val active = data.active
    val editable = data.isEditable(active.id)
    var menuOpen by remember { mutableStateOf(false) }
    var swatchMenu by remember { mutableIntStateOf(-1) }
    var dialog by remember { mutableStateOf<PaletteDialog?>(null) }

    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader("Palette", Modifier.weight(1f))
            if (manage) {
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Palette options") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("New palette") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                            onClick = { menuOpen = false; dialog = PaletteDialog.Create },
                        )
                        DropdownMenuItem(
                            text = { Text("Rename \"${active.name}\"") },
                            leadingIcon = { Icon(Icons.Filled.Edit, null) },
                            enabled = editable,
                            onClick = { menuOpen = false; dialog = PaletteDialog.Rename(active.id, active.name) },
                        )
                        DropdownMenuItem(
                            text = { Text("Duplicate \"${active.name}\"") },
                            leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                            onClick = { menuOpen = false; store.duplicatePalette(active.id) },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete \"${active.name}\"") },
                            leadingIcon = { Icon(Icons.Filled.Delete, null) },
                            enabled = editable && data.palettes.size > 1,
                            onClick = { menuOpen = false; dialog = PaletteDialog.Delete(active.id, active.name, active.colors.size) },
                        )
                    }
                }
            }
        }
        val all = data.all
        ChoiceChips(
            options = all.map { it.name },
            selected = all.indexOfFirst { it.id == active.id },
            onSelect = { i -> all.getOrNull(i)?.let { store.setActive(it.id) }; swatchMenu = -1 },
        )
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            active.colors.forEachIndexed { i, c ->
                Box {
                    ColorSwatch(
                        display(c),
                        size = swatchSize,
                        onClick = { onUse(c) },
                        onLongClick = if (editable) ({ swatchMenu = i }) else null,
                    )
                    if (editable) {
                        SwatchMenu(
                            expanded = swatchMenu == i,
                            canMoveLeft = i > 0,
                            canMoveRight = i < active.colors.lastIndex,
                            onDismiss = { swatchMenu = -1 },
                            onReplace = { store.replaceColor(active.id, i, current()); swatchMenu = -1 },
                            onMove = { d -> store.moveColor(active.id, i, d); swatchMenu = -1 },
                            onDelete = { store.removeColor(active.id, i); swatchMenu = -1 },
                        )
                    }
                }
            }
            if (editable && active.colors.size < PaletteData.MAX_COLORS) {
                val shape = RoundedCornerShape(6.dp)
                Box(
                    Modifier
                        .size(swatchSize)
                        .clip(shape)
                        .border(1.dp, BrushworkColors.ChromeBorder, shape)
                        .clickable(onClickLabel = "Add current color to palette") { store.addColor(active.id, current()) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Add, contentDescription = "Add current color to palette", tint = BrushworkColors.OnChrome) }
            }
        }
        val hint = when {
            !editable && manage -> "Built-in palette (read-only). Use the ⋮ menu to duplicate it into an editable copy."
            !editable -> "Built-in palette (read-only)."
            active.colors.isEmpty() -> "Tap + to add the current color."
            manage -> "Long-press a swatch to replace, move or delete it."
            else -> null
        }
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim, modifier = Modifier.padding(top = 6.dp))
        }
    }

    when (val d = dialog) {
        null -> {}
        PaletteDialog.Create -> NameDialog(
            title = "New palette",
            initial = "",
            confirmText = "Create",
            onConfirm = { store.createPalette(it); dialog = null },
            onDismiss = { dialog = null },
        )
        is PaletteDialog.Rename -> NameDialog(
            title = "Rename palette",
            initial = d.name,
            confirmText = "Rename",
            onConfirm = { store.renamePalette(d.id, it); dialog = null },
            onDismiss = { dialog = null },
        )
        is PaletteDialog.Delete -> BwDialog(
            title = "Delete palette?",
            onDismiss = { dialog = null },
            confirmText = "Delete",
            onConfirm = { store.deletePalette(d.id); dialog = null },
        ) {
            Text("\"${d.name}\" and its ${d.count} ${if (d.count == 1) "color" else "colors"} will be removed. This can't be undone.")
        }
    }
}

private sealed interface PaletteDialog {
    data object Create : PaletteDialog
    data class Rename(val id: String, val name: String) : PaletteDialog
    data class Delete(val id: String, val name: String, val count: Int) : PaletteDialog
}

@Composable
private fun SwatchMenu(
    expanded: Boolean,
    canMoveLeft: Boolean,
    canMoveRight: Boolean,
    onDismiss: () -> Unit,
    onReplace: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("Replace with current color") }, leadingIcon = { Icon(Icons.Filled.FormatColorFill, null) }, onClick = onReplace)
        DropdownMenuItem(text = { Text("Move left") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null) }, enabled = canMoveLeft, onClick = { onMove(-1) })
        DropdownMenuItem(text = { Text("Move right") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) }, enabled = canMoveRight, onClick = { onMove(1) })
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Delete", color = BrushworkColors.Danger) },
            leadingIcon = { Icon(Icons.Filled.Delete, null, tint = BrushworkColors.Danger) },
            onClick = onDelete,
        )
    }
}

@Composable
private fun NameDialog(title: String, initial: String, confirmText: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val valid = PaletteData.cleanName(name) != null
    BwDialog(
        title = title,
        onDismiss = onDismiss,
        confirmText = confirmText,
        onConfirm = { if (valid) onConfirm(name) },
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(PaletteData.MAX_NAME_LENGTH) },
            label = { Text("Name") },
            singleLine = true,
            isError = !valid && name.isNotEmpty(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(name) }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The last colors used (newest first). Tap to [onUse]. */
@Composable
fun RecentColorsSection(
    store: PaletteStore,
    onUse: (Int) -> Unit,
    modifier: Modifier = Modifier,
    display: (Int) -> Int = { it },
    swatchSize: Dp = 40.dp,
) {
    val recent = store.data.recent
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader("Recent", Modifier.weight(1f))
            if (recent.isNotEmpty()) TextButton(onClick = { store.clearRecent() }) { Text("Clear") }
        }
        if (recent.isEmpty()) {
            Text("Colors you use will appear here.", style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                recent.forEach { c -> ColorSwatch(display(c), size = swatchSize, onClick = { onUse(c) }) }
            }
        }
    }
}
