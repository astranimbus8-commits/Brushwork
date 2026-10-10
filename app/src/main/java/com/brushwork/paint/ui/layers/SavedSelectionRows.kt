package com.brushwork.paint.ui.layers

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FilterCenterFocus
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.SavedSelection
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.tools.select.SavedSelectionOps
import com.brushwork.paint.ui.common.BwDialog
import com.brushwork.paint.ui.common.SavedSelectionLabels
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.theme.IbisColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The saved selections' rows (v1.7 item 14, §3.14; area G): listed under the "Selection Layer"
 * row of the layer window, newest first, 48 dp each with a mask thumbnail and the name, and each
 * with its ⋮ menu (Load, Add to, Subtract from, Intersect with, Update from, Rename, Delete).
 *
 * The layer list shows them as ONE `LazyColumn` item (its header count stays `HEADER_ITEMS = 2`
 * in `LayersPanel.kt`), so this composable emits a [Column] of rows: nothing when none are saved.
 * A tap on a row opens its menu (the whole row is the target; the ⋮ glyph shows it has one). The
 * rows are not layers: they are never dragged, and a layer dragged onto them goes nowhere.
 *
 * Each row's thumbnail is drawn from the entry's crop in the background
 * ([SavedSelectionOps.thumbnail]): no document-size mask is inflated for it.
 *
 * A save still compressing (`EditorController.pendingSavedSelections`, §3.14 (c)) shows its row
 * at once, on top with its name and a spinner in place of the thumbnail; an entry being updated
 * shows a spinner in place of its ⋮. Neither can be tapped (no menu: nothing to load, rename or
 * delete yet) until the save lands as its step.
 */
@Composable
fun SavedSelectionRows(controller: EditorController) {
    val c = controller
    // Undo, redo and canvas operations replace the list with notifyLayersChanged.
    c.layersVersion
    val list = c.doc.savedSelections
    val pending = c.pendingSavedSelections
    val hasSelection = c.selection?.isEmpty == false
    var renaming by remember { mutableStateOf<SavedSelection?>(null) }
    Column(Modifier.fillMaxWidth()) {
        // Saves still compressing are the newest: on top, newest first (the tag is the id the
        // entry will have).
        for (p in pending.asReversed()) {
            if (!p.update) key(p.id) { PendingSavedSelectionRow(p.id, p.name) }
        }
        for (e in list.asReversed()) {
            // Keyed by id: a new entry (on top) or a deleted one doesn't shift the other rows'
            // state, so their thumbnails are not drawn again and an open menu stays on its row.
            key(e.id) {
                SavedSelectionRow(
                    controller = c,
                    entry = e,
                    hasSelection = hasSelection,
                    updating = pending.any { it.update && it.id == e.id },
                    onRename = { renaming = e },
                )
            }
        }
    }
    renaming?.let { e ->
        RenameSavedSelectionDialog(
            initial = e.name,
            onRename = { name ->
                renaming = null
                c.fromPanel { c.renameSavedSelection(e.id, name) }
            },
            onDismiss = { renaming = null },
        )
    }
}

/** A save still compressing: its name and a spinner, not clickable (no menu yet). */
@Composable
private fun PendingSavedSelectionRow(id: Long, name: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(IbisColors.ListRow)
            .drawBehind { drawLine(ROW_LINE, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f) }
            .testTag(V17Tags.savedSelectionRow(id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(INDENT))
        Box(Modifier.size(THUMB).background(THUMB_BACK).border(1.dp, THUMB_EDGE), contentAlignment = Alignment.Center) {
            Spinner()
        }
        Text(
            name,
            color = DIM,
            fontSize = NAME_SIZE,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 8.dp, end = 4.dp),
        )
    }
}

@Composable
private fun Spinner(modifier: Modifier = Modifier) =
    CircularProgressIndicator(modifier.size(SPINNER), color = COVERAGE, strokeWidth = 2.dp)

@Composable
private fun SavedSelectionRow(controller: EditorController, entry: SavedSelection, hasSelection: Boolean, updating: Boolean, onRename: () -> Unit) {
    val c = controller
    var menuOpen by remember { mutableStateOf(false) }
    val close = { menuOpen = false }
    val act = { block: () -> Unit -> close(); c.fromPanel(block) }
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(ROW_HEIGHT)
                .background(IbisColors.ListRow)
                .drawBehind { drawLine(ROW_LINE, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f) }
                // While an update compresses the row waits for it (no menu).
                .then(if (updating) Modifier else Modifier.clickable(role = Role.Button) { menuOpen = true })
                .testTag(V17Tags.savedSelectionRow(entry.id)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Indented under the Selection Layer row: these belong to it.
            Spacer(Modifier.width(INDENT))
            SavedSelectionThumbnail(entry, c.doc.width, c.doc.height, THUMB)
            Text(
                entry.name,
                color = IbisColors.ListText,
                fontSize = NAME_SIZE,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 8.dp, end = 4.dp),
            )
            if (updating) {
                Spinner(Modifier.padding(end = 10.dp))
            } else {
                Icon(Icons.Filled.MoreVert, contentDescription = null, tint = DIM, modifier = Modifier.padding(end = 10.dp).size(22.dp))
            }
        }
        DropdownMenu(expanded = menuOpen && !updating, onDismissRequest = close, containerColor = BrushworkColors.ChromeHigh) {
            Item(SavedSelectionLabels.LOAD, Icons.Filled.FileDownload) { act { c.loadSavedSelection(entry.id, SelectionMode.REPLACE) } }
            Item(SavedSelectionLabels.ADD, Icons.Filled.AddCircleOutline) { act { c.loadSavedSelection(entry.id, SelectionMode.ADD) } }
            // Without an active selection there is nothing to subtract from or intersect with.
            Item(SavedSelectionLabels.SUBTRACT, Icons.Filled.RemoveCircleOutline, enabled = hasSelection) {
                act { c.loadSavedSelection(entry.id, SelectionMode.SUBTRACT) }
            }
            Item(SavedSelectionLabels.INTERSECT, Icons.Filled.FilterCenterFocus, enabled = hasSelection) {
                act { c.loadSavedSelection(entry.id, SelectionMode.INTERSECT) }
            }
            HorizontalDivider(color = BrushworkColors.ChromeBorder)
            Item(SavedSelectionLabels.UPDATE, Icons.Filled.Sync, enabled = hasSelection) { act { c.updateSavedSelection(entry.id) } }
            Item(SavedSelectionLabels.RENAME, Icons.Filled.DriveFileRenameOutline) { close(); onRename() }
            Item(SavedSelectionLabels.DELETE, Icons.Filled.Delete) { act { c.deleteSavedSelection(entry.id) } }
        }
    }
}

@Composable
private fun Item(text: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        onClick = onClick,
        enabled = enabled,
        leadingIcon = { Icon(icon, contentDescription = null) },
    )
}

/**
 * The entry's coverage over the document, at the document's aspect, on a light square (the
 * Selection Layer row's look: blue coverage over white).
 */
@Composable
private fun SavedSelectionThumbnail(entry: SavedSelection, docW: Int, docH: Int, side: Dp) {
    val px = with(LocalDensity.current) { side.roundToPx() }.coerceIn(16, 192)
    val aspect = if (docW > 0 && docH > 0) docW.toFloat() / docH else 1f
    val tw = if (aspect >= 1f) px else max(1, (px * aspect).roundToInt())
    val th = if (aspect >= 1f) max(1, (px / aspect).roundToInt()) else px
    // Keyed by the pixels (a rename keeps them), not by the entry instance.
    val image by produceState<ImageBitmap?>(null, entry.packed, entry.bounds, docW, docH, tw, th) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                BitmapUtils.bytesToAlpha8(SavedSelectionOps.thumbnail(entry, docW, docH, tw, th), tw, th).asImageBitmap()
            }.getOrNull()
        }
    }
    Box(Modifier.size(side).background(THUMB_BACK).border(1.dp, THUMB_EDGE), contentAlignment = Alignment.Center) {
        Box(Modifier.padding(1.dp).aspectRatio(aspect, matchHeightConstraintsFirst = aspect < 1f).background(Color.White)) {
            image?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    filterQuality = FilterQuality.Low,
                    colorFilter = ColorFilter.tint(COVERAGE, BlendMode.SrcIn),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** "Rename selection": the name selected, Done or "Rename" applies it (a blank name keeps the old one). */
@Composable
private fun RenameSavedSelectionDialog(initial: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length)))
    }
    val focus = remember { FocusRequester() }
    val confirm = { onRename(value.text.trim().ifEmpty { initial }) }
    BwDialog(title = SavedSelectionLabels.RENAME, onDismiss = onDismiss, confirmText = RENAME_CONFIRM, onConfirm = confirm) {
        OutlinedTextField(
            value = value,
            onValueChange = { if (it.text.length <= MAX_NAME) value = it },
            label = { Text(NAME_FIELD) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { confirm() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        Box(Modifier.height(4.dp))
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
}

/** The dialog's button and field (the layer rename dialog's words). */
private const val RENAME_CONFIRM = "Rename"
private const val NAME_FIELD = "Name"

private const val MAX_NAME = 64
private val ROW_HEIGHT = 48.dp
private val THUMB = 40.dp
private val SPINNER = 20.dp
private val INDENT = 16.dp
private val NAME_SIZE = 16.sp
private val ROW_LINE = Color(0xFFC4C4C4)
private val THUMB_EDGE = Color(0xFF9A9A9A)
private val THUMB_BACK = Color(0xFFF4F4F4)
private val DIM = Color(0xFF5A5A5A)
private val COVERAGE = Color(0xFF3F6BA3)
