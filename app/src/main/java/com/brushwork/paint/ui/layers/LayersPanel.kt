package com.brushwork.paint.ui.layers

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer
import com.brushwork.paint.ui.common.BwDialog
import com.brushwork.paint.ui.common.SheetBackground
import com.brushwork.paint.ui.editor.SliderMath
import com.brushwork.paint.ui.editor.ValueInputDialog
import com.brushwork.paint.ui.editor.endCanvasGesture
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlin.math.roundToInt

/**
 * ibisPaint-style layers WINDOW: a small translucent floating panel (the host anchors it at the
 * bottom-right corner) that is not modal — the canvas around it keeps working. It shows the
 * layer stack (top first) with thumbnails, masks, clipping brackets, visibility and lock badges,
 * long-press-drag reordering, the active layer's blend mode / clipping / locks / opacity and a
 * compact action row (add, duplicate, delete, merge down, and a menu with everything else).
 * Refreshes whenever [EditorController.layersVersion] (or the undo history) changes.
 *
 * [onDismiss] closes it (its ✕ button). [onImportPicture] asks the host to pick an image to add
 * as a layer; the window calls [onDismiss] right before it, so the placement is visible once the
 * picture arrives. [modifier] positions the window; its size comes from the screen size
 * ([LayerListMath.windowSize]), shrunk to the space the host gives it.
 *
 * Delete removes the active layer at once, without asking (undo brings it back);
 * [onLayerDeleted] then gets the removed layer, e.g. to offer Undo in a snackbar.
 */
@Composable
fun LayersPanel(
    controller: EditorController,
    onDismiss: () -> Unit,
    onImportPicture: () -> Unit,
    modifier: Modifier = Modifier,
    onLayerDeleted: (Layer) -> Unit = {},
) {
    val doc = controller.doc
    // Layer objects are not observable: these counters change on every layer edit, undo or redo.
    val layersVersion = controller.layersVersion
    val editCount = controller.editCount
    val hasSelection = controller.selection != null

    val density = LocalDensity.current
    val thumbPx = with(density) { THUMB_SIZE.roundToPx() }.coerceIn(40, 192)
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

    var renameId by rememberSaveable { mutableStateOf<Long?>(null) }
    var opacityId by rememberSaveable { mutableStateOf<Long?>(null) }

    val config = LocalConfiguration.current
    val size = LayerListMath.windowSize(config.screenWidthDp, config.screenHeightDp)

    Surface(
        modifier = modifier
            .width(size.width.dp)
            .height(size.height.dp)
            .semantics { paneTitle = "Layers" },
        shape = RoundedCornerShape(16.dp),
        color = SheetBackground,
        contentColor = BrushworkColors.OnChrome,
        border = BorderStroke(1.dp, BrushworkColors.ChromeBorder),
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.fillMaxSize()) {
            // ---- header
            Row(Modifier.fillMaxWidth().height(40.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Layers", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(
                    "$layerCount / $maxLayers",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (canAddLayer) BrushworkColors.OnChromeDim else BrushworkColors.Danger,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Close layers")
                }
            }
            HorizontalDivider(color = BrushworkColors.ChromeBorder)

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
                LayerProperties(controller, activeRow, isBottom = activeDocIndex == 0, onTypeOpacity = { opacityId = active.id })
                LayerToolbar(
                    controller = controller,
                    row = activeRow,
                    docIndex = activeDocIndex,
                    layerCount = layerCount,
                    canAddLayer = canAddLayer,
                    hasSelection = hasSelection,
                    // No confirmation: undo (or the snackbar's Undo) brings the layer back.
                    onDelete = {
                        val count = controller.doc.layers.size
                        controller.deleteLayer(active)
                        if (controller.doc.layers.size < count) onLayerDeleted(active)
                    },
                    onRename = { renameId = active.id },
                    // Close first so the transform placement of the imported picture is visible.
                    onImportPicture = { onDismiss(); onImportPicture() },
                )
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                if (maxHeight < SIDE_BY_SIDE_MAX_HEIGHT && maxWidth >= SIDE_BY_SIDE_MIN_WIDTH) {
                    // Short, wide window (phone in landscape): list beside the controls.
                    Row(Modifier.fillMaxSize()) {
                        list(Modifier.weight(1f).fillMaxHeight().padding(start = 4.dp))
                        Column(
                            Modifier.width(CONTROLS_WIDTH).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 6.dp),
                            content = controls,
                        )
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        list(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp))
                        HorizontalDivider(color = BrushworkColors.ChromeBorder)
                        Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp), content = controls)
                    }
                }
            }
        }
    }

    // Forget dialogs whose layer disappeared (e.g. undo of its creation behind the dialog).
    LaunchedEffect(layersVersion, editCount) {
        renameId?.let { if (doc.layerById(it) == null) renameId = null }
        opacityId?.let { if (doc.layerById(it) == null) opacityId = null }
    }

    renameId?.let(doc::layerById)?.let { layer ->
        RenameDialog(
            initial = layer.name,
            onRename = { name -> renameId = null; if (name != layer.name) controller.renameLayer(layer, name) },
            onDismiss = { renameId = null },
        )
    }

    opacityId?.let(doc::layerById)?.let { layer ->
        ValueInputDialog(
            title = "Layer opacity",
            label = "Layer opacity",
            initial = layer.opacity,
            format = { "${(it * 100f).roundToInt()}" },
            parse = SliderMath::parsePercent,
            step = SliderMath::stepPercent,
            toFraction = { it },
            fromFraction = { (it * 100f).roundToInt() / 100f },
            rangeText = "0 – 100 %",
            suffix = "%",
            onApply = { v -> controller.fromPanel { controller.setLayerProps(layer, layer.props().copy(opacity = v), "Opacity") } },
            onDismiss = { opacityId = null },
        )
    }
}

/** Below this height the list and the controls don't both fit stacked (controls take ~170 dp). */
private val SIDE_BY_SIDE_MAX_HEIGHT = 260.dp
private val SIDE_BY_SIDE_MIN_WIDTH = 370.dp
private val CONTROLS_WIDTH = 236.dp

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
    // Open with the active layer in view, one row of context above it.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (activeDisplay - 1).coerceAtLeast(0))
    val haptics = LocalHapticFeedback.current
    val reorder = rememberReorderState(
        listState = listState,
        canDrag = { doc.layers.size > 1 },
        onStart = {
            controller.endCanvasGesture()
            dragOrder.value = doc.layers.asReversed().toList()
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        },
        // Read/write the state directly: several moves can arrive between recompositions.
        onMove = { from, to -> dragOrder.value?.let { dragOrder.value = LayerListMath.moved(it, from, to) } },
        onDrop = { index ->
            val order = dragOrder.value
            dragOrder.value = null
            if (index >= 0 && order != null) {
                val layer = order[index]
                // moveLayer makes the moved layer active without the tool lifecycle; selecting it
                // first lets the current tool commit/re-target (which may itself add a layer).
                controller.fromPanel { controller.selectLayer(layer) }
                if (order.size == doc.layers.size) {
                    val target = LayerListMath.displayToDoc(index, order.size)
                    if (doc.indexOf(layer) != target) controller.moveLayer(layer, target)
                }
            }
        },
    )

    // Keep the active layer in view when it changes (added, duplicated, moved with the buttons).
    LaunchedEffect(activeDisplay) {
        if (activeDisplay < 0 || reorder.isDragging) return@LaunchedEffect
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == activeDisplay }
        val fullyVisible = item != null && item.offset >= info.viewportStartOffset && item.offset + item.size <= info.viewportEndOffset
        if (!fullyVisible) listState.animateScrollToItem((activeDisplay - 1).coerceAtLeast(0))
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
                onSelect = { controller.fromPanel { controller.selectLayer(layer) } },
                onToggleVisible = { controller.fromPanel { controller.toggleVisibility(layer) } },
                onEditContent = { controller.fromPanel { LayerOps.editTarget(controller, layer, mask = false) } },
                onEditMask = { controller.fromPanel { LayerOps.editTarget(controller, layer, mask = true) } },
                modifier = itemModifier.padding(vertical = 1.dp),
                onEditText = { controller.fromPanel { LayerOps.editText(controller, layer) } },
                onEditShape = { controller.fromPanel { LayerOps.editShape(controller, layer) } },
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
        // Inside the dialog's own composition so the requester is attached when this runs.
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
}

private const val MAX_NAME_LENGTH = 64
