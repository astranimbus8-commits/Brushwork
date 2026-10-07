package com.brushwork.paint.ui.layers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.IncrementKind
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.TransparencyDisplay
import com.brushwork.paint.ui.common.BwDialog
import com.brushwork.paint.ui.editor.EditorPanel
import com.brushwork.paint.ui.editor.SliderMath
import com.brushwork.paint.ui.editor.ValueInputDialog
import com.brushwork.paint.ui.editor.endCanvasGesture
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.theme.IbisColors
import com.brushwork.paint.ui.theme.IbisDims
import kotlin.math.roundToInt

/**
 * The ibisPaint layer window (v1.6, design §3.7.7): a translucent floating panel that is not
 * modal — the canvas around it keeps working. Top to bottom:
 * - the header: "Layer", the "n / max" count (red at the limit) and the ✕ ("Close layers");
 * - the main block: the left column (the flattened canvas preview over six buttons: add — long
 *   press for the special layers —, the canvas flips, duplicate, import picture, new special
 *   layer), the bottom-aligned list (the Selection Layer row, then the 80 dp layer rows top layer
 *   first, then the transparency squares) and the right strip (clear, layer mask, transform, the
 *   layer flips, merge down, delete, filters for this layer, ⋮ with the v1.5 layer menu);
 * - the blend row (clipping, alpha lock, lock, the blend mode) and the opacity row.
 * It refreshes whenever [EditorController.layersVersion] (or the undo history) changes.
 *
 * Rows: a tap selects, a second tap edits (text, story, shape, objects, adjustment), a long
 * press opens the layer's ⋮ menu (or reorders, when the finger then moves), the ≡ handle drags at
 * once; the mask square switches the edit target.
 *
 * [onDismiss] closes it (its ✕ button). [onImportPicture] asks the host to pick an image to add
 * as a layer; the window calls [onDismiss] right before it, so the placement is visible once the
 * picture arrives. Delete removes the active layer at once, without asking (undo brings it back);
 * [onLayerDeleted] then gets the removed layer, e.g. to offer Undo in a snackbar. [onOpenPanel]
 * asks the host to open one of its panels (the Selection Layer row: Selection; the strip's FX:
 * Filters).
 *
 * v1.6 sizing contract (§4.6, frozen): the window FILLS the size its [modifier] gives — the host
 * sizes it (ibisPaint: `min(382, w − 10)` × `min(520, …)`); [LayerWindowMetrics] lays the parts
 * out in whatever it gets (a short, wide window puts the list beside its controls).
 */
@Composable
fun LayersPanel(
    controller: EditorController,
    onDismiss: () -> Unit,
    onImportPicture: () -> Unit,
    modifier: Modifier = Modifier,
    onLayerDeleted: (Layer) -> Unit = {},
    onOpenPanel: (EditorPanel) -> Unit = {},
) {
    val doc = controller.doc
    // Layer objects are not observable: these counters change on every layer edit, undo or redo.
    val layersVersion = controller.layersVersion
    val editCount = controller.editCount
    val selection = controller.selection
    val hasSelection = selection != null

    val density = LocalDensity.current
    val thumbPx = with(density) { IbisDims.LayerThumb.roundToPx() }.coerceIn(40, 192)
    val thumbs = remember(thumbPx) { LayerThumbnails(thumbPx) }
    val frames = remember { FrameInfoCache() }

    // Local order while a row is being dragged (top first); null = follow the document.
    val dragOrderState = remember { mutableStateOf<List<Layer>?>(null) }
    val dragOrder = dragOrderState.value
    val rows = remember(layersVersion, editCount, dragOrder) {
        LayerRowModel.build(doc, dragOrder ?: doc.layers.asReversed().toList(), frames)
    }
    SideEffect {
        val ids = doc.layers.mapTo(HashSet()) { it.id }
        thumbs.retain(ids)
        frames.retain(ids)
    }

    val active = doc.activeLayer
    val activeRow = rows.firstOrNull { it.layer === active } ?: rows.first()
    val activeDocIndex = doc.indexOf(active)
    val layerCount = doc.layers.size
    val canAddLayer = controller.canAddLayer
    val maxLayers = controller.maxLayers

    var renameId by rememberSaveable { mutableStateOf<Long?>(null) }
    var opacityId by rememberSaveable { mutableStateOf<Long?>(null) }
    val ui = remember { LayerWindowUi() }
    // A view preference (AppSettings is not observable): mirrored here for the squares.
    var transparency by remember { mutableStateOf(controller.settings.transparencyDisplay) }
    val pickTransparency = { t: TransparencyDisplay ->
        controller.endCanvasGesture()
        LayerOps.setTransparencyDisplay(controller, t)
        transparency = t
    }

    val env = LayerWindowEnv(
        controller = controller,
        row = activeRow,
        docIndex = activeDocIndex,
        layerCount = layerCount,
        canAddLayer = canAddLayer,
        hasSelection = hasSelection,
        ui = ui,
        onDismiss = onDismiss,
        onImportPicture = onImportPicture,
        // No confirmation: undo (or the snackbar's Undo) brings the layer back.
        onDelete = {
            val count = controller.doc.layers.size
            controller.deleteLayer(active)
            if (controller.doc.layers.size < count) onLayerDeleted(active)
        },
        onRename = { renameId = active.id },
        onOpenPanel = onOpenPanel,
    )
    val docAspect = doc.width.toFloat() / doc.height.coerceAtLeast(1)
    val screenHeightDp = LocalConfiguration.current.screenHeightDp

    Surface(
        modifier = modifier
            .fillMaxSize()
            .semantics { paneTitle = LayerLabels.PANE },
        shape = RoundedCornerShape(IbisDims.LayerWindowRadius),
        color = IbisColors.Panel,
        contentColor = Color.White,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val m = LayerWindowMetrics.of(maxWidth.value, maxHeight.value, shortScreen = LayerListMath.isShortScreen(screenHeightDp))
            // A new row height or thumbnail size (the window resized: rotation, a split screen)
            // builds the list afresh: the lazy rows would otherwise keep their last measured
            // size until something else re-measured them.
            @Composable
            fun list(mod: Modifier) = key(m.row, m.thumb) {
                LayerList(
                    controller = controller,
                    rows = rows,
                    thumbs = thumbs,
                    docAspect = docAspect,
                    dragOrder = dragOrderState,
                    selection = selection,
                    metrics = m,
                    ui = ui,
                    onOpenPanel = onOpenPanel,
                    modifier = mod,
                )
            }
            Column(Modifier.fillMaxSize()) {
                WindowHeader(layerCount, maxLayers, canAddLayer, onDismiss, Modifier.height(m.header.dp))
                if (m.sideBySide) {
                    // Short, wide window (a phone in landscape): the list beside the controls.
                    Row(Modifier.fillMaxWidth().height(m.main.dp).padding(start = m.padStart.dp, end = m.padEnd.dp, bottom = m.bottomPad.dp)) {
                        Column(Modifier.weight(1f).fillMaxHeight().testTag(LayerWindowTags.LIST)) {
                            list(Modifier.fillMaxWidth().weight(1f).testTag(LayerWindowTags.ROWS))
                            TransparencySquares(transparency, pickTransparency, Modifier.height(m.transparencyRow.dp).testTag(LayerWindowTags.TRANSPARENCY))
                        }
                        Spacer(Modifier.width(m.gap1.dp))
                        Column(
                            Modifier
                                .width(m.controls.dp)
                                .fillMaxHeight()
                                .testTag(LayerWindowTags.CONTROLS)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            BlendRow(controller, activeRow, isBottom = activeDocIndex == 0, Modifier.height(m.blendRow.dp).testTag(LayerWindowTags.BLEND), wrap = m.blendWraps)
                            OpacityRow(controller, active, activeRow.opacity, onType = { opacityId = active.id }, Modifier.height(m.opacityRow.dp).testTag(LayerWindowTags.OPACITY))
                            ActionGrid(leftActions(env) + stripActions(env), perRow = m.gridColumns, Modifier.fillMaxWidth())
                        }
                    }
                } else {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(m.main.dp)
                            .testTag(LayerWindowTags.MAIN)
                            .padding(start = m.padStart.dp, end = m.padEnd.dp),
                    ) {
                        Column(Modifier.width(m.leftColumn.dp).fillMaxHeight().testTag(LayerWindowTags.LEFT)) {
                            if (m.preview > 0f) {
                                LayerCanvasPreview(controller, transparency, Modifier.fillMaxWidth().height(m.preview.dp).testTag(LayerWindowTags.PREVIEW))
                            } else if (!m.compact) {
                                Spacer(Modifier.fillMaxWidth().weight(1f).background(IbisColors.PanelOpaque))
                            }
                            if (m.compact) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .background(IbisColors.PanelStrip)
                                        .testTag(LayerWindowTags.BUTTONS)
                                        .verticalScroll(rememberScrollState()),
                                ) { LeftButtons(leftActions(env), m.leftColumns, Modifier.fillMaxWidth()) }
                            } else {
                                LeftButtons(leftActions(env), m.leftColumns, Modifier.fillMaxWidth().height(m.buttonsPane.dp).testTag(LayerWindowTags.BUTTONS))
                            }
                        }
                        Spacer(Modifier.width(m.gap1.dp))
                        Column(Modifier.width(m.list.dp).fillMaxHeight().testTag(LayerWindowTags.LIST)) {
                            list(Modifier.fillMaxWidth().weight(1f).testTag(LayerWindowTags.ROWS))
                            TransparencySquares(transparency, pickTransparency, Modifier.height(m.transparencyRow.dp).testTag(LayerWindowTags.TRANSPARENCY))
                        }
                        Spacer(Modifier.width(m.gap2.dp))
                        Box(
                            Modifier
                                .width(m.strip.dp)
                                .fillMaxHeight()
                                .background(IbisColors.PanelStrip)
                                .testTag(LayerWindowTags.STRIP)
                                .then(if (m.stripScrolls) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                        ) { RightStrip(stripActions(env), Modifier.fillMaxWidth()) }
                    }
                    BlendRow(controller, activeRow, isBottom = activeDocIndex == 0, Modifier.height(m.blendRow.dp).testTag(LayerWindowTags.BLEND))
                    OpacityRow(controller, active, activeRow.opacity, onType = { opacityId = active.id }, Modifier.height(m.opacityRow.dp).testTag(LayerWindowTags.OPACITY))
                    Spacer(Modifier.height(m.bottomPad.dp))
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
            title = LayerLabels.OPACITY,
            label = LayerLabels.OPACITY,
            initial = layer.opacity,
            format = { "${(it * 100f).roundToInt()}" },
            parse = SliderMath::parsePercent,
            step = SliderMath::stepPercent,
            toFraction = { it },
            fromFraction = { (it * 100f).roundToInt() / 100f },
            rangeText = "0 – 100 %",
            suffix = "%",
            onApply = { v -> controller.fromPanel { setLayerOpacity(controller, layer, v) } },
            onDismiss = { opacityId = null },
            // As the row's − / + and slider: − / + and the slider step by the Percent increment.
            incrementKind = IncrementKind.PERCENT,
            incrementScale = 100f,
        )
    }
}

/** "Layer", the layer count ("n / max", red at the limit) and the ✕ (kept: tests and V10). */
@Composable
private fun WindowHeader(layerCount: Int, maxLayers: Int, canAddLayer: Boolean, onDismiss: () -> Unit, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().testTag(LayerWindowTags.HEADER).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(LayerLabels.TITLE, color = Color.White, fontSize = IbisDims.LayerHeaderText, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Spacer(Modifier.weight(1f))
        Text(
            "$layerCount / $maxLayers",
            color = if (canAddLayer) BrushworkColors.OnChromeDim else BrushworkColors.Danger,
            fontSize = 12.sp,
            maxLines = 1,
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(IbisDims.LayerClose)) {
            Icon(Icons.Filled.Close, contentDescription = LayerLabels.CLOSE, tint = Color.White)
        }
    }
}

/**
 * The bottom-aligned list: the Selection Layer row (item 0) and the saved selections (item 1, one
 * item for all of them; [HEADER_ITEMS]), then the layer rows top first; the
 * filler above few rows is [IbisColors.PanelOpaque]. Rows drag from their ≡ handle at once, or
 * after a long press anywhere else; a long press released in place opens the layer's ⋮ menu.
 */
@Composable
private fun LayerList(
    controller: EditorController,
    rows: List<LayerRowModel>,
    thumbs: LayerThumbnails,
    docAspect: Float,
    dragOrder: MutableState<List<Layer>?>,
    selection: Selection?,
    metrics: LayerWindowMetrics,
    ui: LayerWindowUi,
    onOpenPanel: (EditorPanel) -> Unit,
    modifier: Modifier,
) {
    val doc = controller.doc
    val activeDisplay = rows.indexOfFirst { it.active }
    // Items 0 and 1 are the headers: the Selection Layer row and the saved selections (v1.7).
    val activeItem = activeDisplay + HEADER_ITEMS
    // Open with the active layer in view, one row of context above it (the headers from the top
    // when the active layer is the top one).
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = contextItem(activeDisplay))
    val haptics = LocalHapticFeedback.current
    val reorder = rememberReorderState(
        listState = listState,
        canDrag = { doc.layers.size > 1 },
        onStart = {
            controller.endCanvasGesture()
            dragOrder.value = doc.layers.asReversed().toList()
        },
        // Read/write the state directly: several moves can arrive between recompositions.
        onMove = { from, to -> dragOrder.value?.let { dragOrder.value = LayerListMath.moved(it, from - HEADER_ITEMS, to - HEADER_ITEMS) } },
        onDrop = { index ->
            val order = dragOrder.value
            dragOrder.value = null
            val at = index - HEADER_ITEMS
            if (at >= 0 && order != null && at < order.size) {
                val layer = order[at]
                // moveLayer makes the moved layer active without the tool lifecycle; selecting it
                // first lets the current tool commit/re-target (which may itself add a layer).
                controller.fromPanel { controller.selectLayer(layer) }
                if (order.size == doc.layers.size) {
                    val target = LayerListMath.displayToDoc(at, order.size)
                    if (doc.indexOf(layer) != target) controller.moveLayer(layer, target)
                }
            }
        },
        // Neither header (SELECTION_ROW_KEY, SAVED_SELECTIONS_KEY) is dragged or dropped on.
        isReorderable = { isLayerItem(it) },
    )

    // Keep the active layer in view when it changes (added, duplicated, moved with the buttons).
    LaunchedEffect(activeItem) {
        if (activeDisplay < 0 || reorder.isDragging) return@LaunchedEffect
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == activeItem }
        val fullyVisible = item != null && item.offset >= info.viewportStartOffset && item.offset + item.size <= info.viewportEndOffset
        if (!fullyVisible) listState.animateScrollToItem(contextItem(activeDisplay))
    }

    val longPressStart by rememberUpdatedState { _: Int -> haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    val longPress by rememberUpdatedState { index: Int ->
        // A long press released in place: that layer's ⋮ menu.
        rows.getOrNull(index - HEADER_ITEMS)?.layer?.let { layer ->
            controller.fromPanel { if (controller.activeLayer !== layer) controller.selectLayer(layer) }
            ui.menu.show(mask = false)
        }
    }
    val rowHeight = metrics.row.dp
    val thumbSize = metrics.thumb.dp
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.Bottom,
        modifier = modifier
            .background(IbisColors.PanelOpaque)
            .reorderContainer(
                reorder,
                handleWidth = IbisDims.LayerDragHandleWidth,
                onLongPressStart = { longPressStart(it) },
                onLongPress = { longPress(it) },
            ),
    ) {
        item(key = SELECTION_ROW_KEY) {
            SelectionLayerRow(
                selection = selection,
                docAspect = docAspect,
                height = rowHeight,
                thumbSize = thumbSize,
                onOpen = { controller.fromPanel { onOpenPanel(EditorPanel.SELECTION) } },
                onAdd = { controller.fromPanel { controller.saveSelection() } },
                modifier = Modifier.testTag(LayerWindowTags.SELECTION_ROW),
            )
        }
        // v1.7 (item 14): the saved selections, ONE item whatever their number (area G's rows;
        // empty when none are saved), so the header count stays HEADER_ITEMS.
        item(key = SAVED_SELECTIONS_KEY) {
            SavedSelectionRows(controller)
        }
        itemsIndexed(rows, key = { _, r -> r.layer.id }) { index, row ->
            val itemIndex = index + HEADER_ITEMS
            val dragging = itemIndex == reorder.draggingIndex
            val itemModifier = when {
                dragging -> Modifier.zIndex(1f).graphicsLayer { translationY = reorder.draggingOffset }
                itemIndex == reorder.settlingIndex -> Modifier.zIndex(1f).graphicsLayer { translationY = reorder.settleOffset.value }
                else -> Modifier.animateItem()
            }
            val layer = row.layer
            LayerRow(
                row = row,
                thumbs = thumbs,
                docAspect = docAspect,
                dragging = dragging,
                height = rowHeight,
                thumbSize = thumbSize,
                onSelect = { controller.fromPanel { controller.selectLayer(layer) } },
                onToggleVisible = { controller.fromPanel { controller.toggleVisibility(layer) } },
                onMaskSquare = {
                    controller.fromPanel {
                        // The active layer's mask square goes back to its content; any other
                        // tap edits the mask (an adjustment layer only has its mask).
                        val toMask = row.isAdjustment || !(row.active && row.editingMask)
                        LayerOps.editTarget(controller, layer, mask = toMask)
                    }
                },
                onMove = { up -> controller.fromPanel { if (up) controller.moveLayerUp(layer) else controller.moveLayerDown(layer) } },
                modifier = itemModifier.testTag(LayerWindowTags.row(layer.id)),
                onEdit = editAction(controller, row),
            )
        }
    }
}

/** What a double tap on [row] edits: its text (or story), shape, objects or adjustment; null for plain layers. */
private fun editAction(c: EditorController, row: LayerRowModel): (() -> Unit)? {
    val layer = row.layer
    return when {
        row.isText -> ({ c.fromPanel { LayerOps.editText(c, layer) } })
        row.isShape -> ({ c.fromPanel { LayerOps.editShape(c, layer) } })
        row.isVector -> ({ c.fromPanel { LayerOps.editObjects(c, layer) } })
        row.isAdjustment -> ({ c.fromPanel { LayerOps.editAdjustment(c, layer) } })
        else -> null
    }
}

private const val SELECTION_ROW_KEY = "selection-layer"

/** v1.7 (item 14): the key of the saved selections' item, right under the Selection Layer row. */
private const val SAVED_SELECTIONS_KEY = "saved-selections"

/**
 * The list's header items before the first layer row: the Selection Layer row
 * ([SELECTION_ROW_KEY]) and the saved selections ([SAVED_SELECTIONS_KEY]). Every row offset in
 * the list goes through it (v1.7, §3.14).
 */
private const val HEADER_ITEMS = 2

/** True for a layer row's item index (the headers are never dragged, nor dropped on). */
internal fun isLayerItem(index: Int): Boolean = index >= HEADER_ITEMS

/**
 * The item the list scrolls to so that display row [activeDisplay] shows with one row of context
 * above it: the row above, or the first header when it is the top row (or none is active).
 */
internal fun contextItem(activeDisplay: Int): Int =
    if (activeDisplay <= 0) 0 else activeDisplay + HEADER_ITEMS - 1

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
