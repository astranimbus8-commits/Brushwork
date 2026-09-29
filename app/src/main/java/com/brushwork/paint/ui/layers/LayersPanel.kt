package com.brushwork.paint.ui.layers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer
import com.brushwork.paint.ui.common.BwDialog
import com.brushwork.paint.ui.common.BwSheet
import com.brushwork.paint.ui.theme.BrushworkColors

/**
 * ibisPaint-style layers panel: the layer stack (top first) with thumbnails, masks, clipping
 * brackets and visibility toggles, long-press-drag reordering, and the active layer's properties
 * and actions. Refreshes whenever [EditorController.layersVersion] (or the undo history) changes.
 *
 * [onImportPicture] asks the host to pick an image to add as a layer; the panel closes afterwards
 * so the placement (transform tool) is visible.
 */
@Composable
fun LayersPanel(controller: EditorController, onDismiss: () -> Unit, onImportPicture: () -> Unit) {
    val doc = controller.doc
    // Layer objects are not observable: these counters change on every layer edit, undo or redo.
    val layersVersion = controller.layersVersion
    val editCount = controller.editCount
    val hasSelection = controller.selection != null

    val density = LocalDensity.current
    val thumbPx = with(density) { THUMB_SIZE.roundToPx() }.coerceIn(48, 192)
    val thumbs = remember(thumbPx) { LayerThumbnails(thumbPx) }

    // Local order while a row is being dragged (top first); null = follow the document.
    val dragOrderState = remember { mutableStateOf<List<Layer>?>(null) }
    val dragOrder = dragOrderState.value
    val rows = remember(layersVersion, editCount, dragOrder) {
        LayerRowModel.build(doc, dragOrder ?: doc.layers.asReversed().toList())
    }
    SideEffect { thumbs.retain(doc.layers.mapTo(HashSet()) { it.id }) }

    val active = doc.activeLayer
    val activeRow = rows.firstOrNull { it.layer === active } ?: rows.first()
    val activeDocIndex = doc.indexOf(active)
    val layerCount = doc.layers.size
    val canAddLayer = controller.canAddLayer
    val maxLayers = controller.maxLayers

    var deleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    var renameId by rememberSaveable { mutableStateOf<Long?>(null) }

    val shortScreen = LocalConfiguration.current.screenHeightDp < 480
    BwSheet(
        title = "Layers",
        onDismiss = onDismiss,
        modifier = Modifier.fillMaxHeight(if (shortScreen) 0.94f else 0.7f),
        scrollable = false,
        actions = {
            Text(
                "$layerCount / $maxLayers layers",
                style = MaterialTheme.typography.labelMedium,
                color = if (canAddLayer) BrushworkColors.OnChromeDim else BrushworkColors.Danger,
                modifier = Modifier.padding(end = 4.dp),
            )
        },
    ) {
        val list: @Composable (Modifier) -> Unit = { m ->
            LayerList(
                controller = controller,
                rows = rows,
                thumbs = thumbs,
                docAspect = doc.width.toFloat() / doc.height.coerceAtLeast(1),
                dragOrder = dragOrderState,
                modifier = m,
            )
        }
        val controls: @Composable ColumnScope.() -> Unit = {
            LayerProperties(controller, activeRow, isBottom = activeDocIndex == 0)
            LayerToolbar(
                controller = controller,
                row = activeRow,
                docIndex = activeDocIndex,
                layerCount = layerCount,
                canAddLayer = canAddLayer,
                hasSelection = hasSelection,
                onDelete = { deleteId = active.id },
                onRename = { renameId = active.id },
                onImportPicture = { onImportPicture(); onDismiss() },
            )
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if (maxWidth >= 560.dp && maxHeight < 420.dp) {
                // Landscape phone: list beside the controls.
                Row(Modifier.fillMaxSize()) {
                    list(Modifier.weight(1f).fillMaxHeight())
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState()), content = controls)
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    list(Modifier.weight(1f).fillMaxWidth())
                    HorizontalDivider(color = BrushworkColors.ChromeBorder, modifier = Modifier.padding(bottom = 6.dp))
                    controls()
                }
            }
        }
    }

    // Forget dialogs whose layer disappeared (e.g. undo of its creation behind the dialog).
    LaunchedEffect(layersVersion, editCount) {
        deleteId?.let { if (doc.layerById(it) == null) deleteId = null }
        renameId?.let { if (doc.layerById(it) == null) renameId = null }
    }

    deleteId?.let(doc::layerById)?.let { layer ->
        BwDialog(
            title = "Delete layer?",
            onDismiss = { deleteId = null },
            confirmText = "Delete",
            onConfirm = { deleteId = null; controller.deleteLayer(layer) },
        ) {
            Text("\"${layer.name}\" will be deleted. You can undo this.")
        }
    }

    renameId?.let(doc::layerById)?.let { layer ->
        RenameDialog(
            initial = layer.name,
            onRename = { name -> renameId = null; if (name != layer.name) controller.renameLayer(layer, name) },
            onDismiss = { renameId = null },
        )
    }
}

@Composable
private fun LayerList(
    controller: EditorController,
    rows: List<LayerRowModel>,
    thumbs: LayerThumbnails,
    docAspect: Float,
    dragOrder: MutableState<List<Layer>?>,
    modifier: Modifier,
) {
    val doc = controller.doc
    val activeDisplay = rows.indexOfFirst { it.active }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = activeDisplay.coerceAtLeast(0))
    val haptics = LocalHapticFeedback.current
    val reorder = rememberReorderState(
        listState = listState,
        canDrag = { doc.layers.size > 1 },
        onStart = {
            dragOrder.value = doc.layers.asReversed().toList()
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        },
        // Read/write the state directly: several moves can arrive between recompositions.
        onMove = { from, to -> dragOrder.value?.let { dragOrder.value = LayerListMath.moved(it, from, to) } },
        onDrop = { index ->
            val order = dragOrder.value
            dragOrder.value = null
            if (index >= 0 && order != null && order.size == doc.layers.size) {
                val layer = order[index]
                val target = LayerListMath.displayToDoc(index, order.size)
                if (doc.indexOf(layer) != target) controller.moveLayer(layer, target)
            }
        },
    )

    // Keep the active layer in view when it changes (added, duplicated, moved with the buttons).
    LaunchedEffect(activeDisplay) {
        if (activeDisplay < 0 || reorder.isDragging) return@LaunchedEffect
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == activeDisplay }
        val fullyVisible = item != null && item.offset >= info.viewportStartOffset && item.offset + item.size <= info.viewportEndOffset
        if (!fullyVisible) listState.animateScrollToItem(activeDisplay)
    }

    LazyColumn(state = listState, modifier = modifier.reorderContainer(reorder)) {
        itemsIndexed(rows, key = { _, r -> r.layer.id }) { index, row ->
            val dragging = index == reorder.draggingIndex
            val itemModifier = when {
                dragging -> Modifier.zIndex(1f).graphicsLayer { translationY = reorder.draggingOffset }
                index == reorder.settlingIndex -> Modifier.zIndex(1f).graphicsLayer { translationY = reorder.settleOffset.value }
                else -> Modifier.animateItem()
            }
            val layer = row.layer
            LayerRow(
                row = row,
                thumbs = thumbs,
                docAspect = docAspect,
                dragging = dragging,
                onSelect = { controller.selectLayer(layer) },
                onToggleVisible = { controller.toggleVisibility(layer) },
                onEditContent = { LayerOps.editTarget(controller, layer, mask = false) },
                onEditMask = { LayerOps.editTarget(controller, layer, mask = true) },
                modifier = itemModifier,
            )
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length)))
    }
    val focus = remember { FocusRequester() }
    val confirm = { onRename(value.text.trim().ifEmpty { initial }) }
    BwDialog(title = "Rename layer", onDismiss = onDismiss, confirmText = "Rename", onConfirm = confirm) {
        OutlinedTextField(
            value = value,
            onValueChange = { if (it.text.length <= MAX_NAME_LENGTH) value = it },
            label = { Text("Name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { confirm() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        Box(Modifier.height(4.dp))
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

private const val MAX_NAME_LENGTH = 64
