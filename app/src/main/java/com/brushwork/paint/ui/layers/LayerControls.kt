package com.brushwork.paint.ui.layers

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SubdirectoryArrowRight
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.model.IncrementKind
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.LayerProps
import com.brushwork.paint.model.TransparencyDisplay
import com.brushwork.paint.ui.common.ColorSwatch
import com.brushwork.paint.ui.common.stepOnLongPress
import com.brushwork.paint.ui.editor.EditorIcons
import com.brushwork.paint.ui.editor.EditorPanel
import com.brushwork.paint.ui.editor.endCanvasGesture
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.theme.IbisColors
import com.brushwork.paint.ui.theme.IbisDims
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Runs a layers-window action. The window is not modal, so a finger may still be drawing on the
 * canvas: that stroke ends first (a layer switch or edit must never land mid-gesture).
 */
internal inline fun EditorController.fromPanel(block: () -> Unit) {
    endCanvasGesture()
    block()
}

// ====================================================================== shared window state

/** The ⋮ menu: opened by the strip's ⋮, on its mask page by "Layer mask", or by a row's long press. */
@Stable
internal class LayerMenuState {
    var open by mutableStateOf(false)
    var maskPage by mutableStateOf(false)

    /** Opens the menu (on the mask page when [mask]); an open menu switches page. */
    fun show(mask: Boolean) {
        maskPage = mask
        open = true
    }

    fun close() {
        open = false
        maskPage = false
    }
}

/** Where the special-layer menu hangs: under "+" (long press) or under "New special layer". */
internal enum class SpecialMenuAnchor { ADD, SPECIAL }

/** UI state of one open layer window. */
@Stable
internal class LayerWindowUi {
    val menu = LayerMenuState()
    var special by mutableStateOf<SpecialMenuAnchor?>(null)
}

/** What the window's buttons act on: the active layer's row and the host's callbacks. */
internal class LayerWindowEnv(
    val controller: EditorController,
    val row: LayerRowModel,
    val docIndex: Int,
    val layerCount: Int,
    val canAddLayer: Boolean,
    val hasSelection: Boolean,
    val ui: LayerWindowUi,
    val onDismiss: () -> Unit,
    val onImportPicture: () -> Unit,
    val onDelete: () -> Unit,
    val onRename: () -> Unit,
    val onOpenPanel: (EditorPanel) -> Unit,
) {
    val layer: Layer get() = row.layer
}

/** One icon button of the left column, the right strip or the side-by-side grid. */
internal class WindowAction(
    val label: String,
    val enabled: Boolean,
    val glyph: @Composable (tint: Color) -> Unit,
    val onLongClickLabel: String? = null,
    val onLongClick: (() -> Unit)? = null,
    /** A dropdown anchored to this button (rendered inside its box). */
    val popup: (@Composable () -> Unit)? = null,
    val onClick: () -> Unit,
)

private fun iconGlyph(icon: ImageVector, rotation: Float = 0f, size: Dp = 24.dp): @Composable (Color) -> Unit = { tint ->
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size).rotate(rotation))
}

/**
 * The left column's six buttons (design §3.7.7), row-major in two columns: + "Add layer" (long
 * press: the special-layer menu) and the canvas flip, duplicate and the vertical canvas flip,
 * import picture and "New special layer".
 */
internal fun leftActions(env: LayerWindowEnv): List<WindowAction> {
    val c = env.controller
    val ui = env.ui
    return listOf(
        WindowAction(
            LayerLabels.ADD, env.canAddLayer, iconGlyph(Icons.Filled.Add, size = 28.dp),
            onLongClickLabel = LayerLabels.ADD_LONG,
            onLongClick = { c.endCanvasGesture(); ui.special = SpecialMenuAnchor.ADD },
            popup = { SpecialLayerMenu(env, SpecialMenuAnchor.ADD) },
        ) { c.fromPanel { LayerOps.addLayer(c) } },
        WindowAction(LayerLabels.FLIP_CANVAS_H, true, iconGlyph(Icons.Filled.Flip)) { c.fromPanel { LayerOps.flipCanvas(c, horizontal = true) } },
        WindowAction(
            if (env.hasSelection) LayerLabels.DUPLICATE_SELECTION else LayerLabels.DUPLICATE,
            env.canAddLayer,
            iconGlyph(Icons.Outlined.LibraryAdd),
        ) { c.fromPanel { LayerOps.duplicate(c, env.layer) } },
        WindowAction(LayerLabels.FLIP_CANVAS_V, true, iconGlyph(Icons.Filled.Flip, rotation = 90f)) { c.fromPanel { LayerOps.flipCanvas(c, horizontal = false) } },
        // Close first so the transform placement of the imported picture is visible.
        WindowAction(LayerLabels.IMPORT, env.canAddLayer, iconGlyph(Icons.Filled.PhotoCamera)) { c.endCanvasGesture(); env.onDismiss(); env.onImportPicture() },
        WindowAction(
            LayerLabels.SPECIAL, env.canAddLayer, iconGlyph(Icons.Outlined.Layers),
            popup = { SpecialLayerMenu(env, SpecialMenuAnchor.SPECIAL) },
        ) { c.endCanvasGesture(); ui.special = SpecialMenuAnchor.SPECIAL },
    )
}

/**
 * The right strip's nine icons, top to bottom (design §3.7.7): clear, layer mask, transform, the
 * layer flips, merge down (an adjustment layer: apply to the layer below), delete, filters for
 * this layer and ⋮ (the v1.5 layer menu).
 */
internal fun stripActions(env: LayerWindowEnv): List<WindowAction> {
    val c = env.controller
    val layer = env.layer
    val row = env.row
    val maskTarget = c.editTargetOf(layer) == EditTarget.MASK
    return listOf(
        WindowAction(if (maskTarget) LayerLabels.CLEAR_MASK else LayerLabels.CLEAR, true, { tint -> ClearGlyph(tint) }) {
            c.fromPanel { LayerOps.clear(c, layer) }
        },
        WindowAction(LayerLabels.MASK, true, iconGlyph(Icons.Filled.Contrast)) { c.endCanvasGesture(); env.ui.menu.show(mask = true) },
        WindowAction(LayerLabels.TRANSFORM, true, iconGlyph(Icons.Filled.OpenWith)) {
            c.fromPanel { LayerOps.transform(c, layer) }
            env.onDismiss()
        },
        WindowAction(LayerLabels.FLIP_H, true, iconGlyph(Icons.Filled.Flip)) { c.fromPanel { LayerOps.flip(c, layer, horizontal = true) } },
        WindowAction(LayerLabels.FLIP_V, true, iconGlyph(Icons.Filled.Flip, rotation = 90f)) { c.fromPanel { LayerOps.flip(c, layer, horizontal = false) } },
        WindowAction(
            if (row.isAdjustment) LayerLabels.APPLY_BELOW else LayerLabels.MERGE,
            env.docIndex > 0,
            iconGlyph(Icons.Filled.VerticalAlignBottom),
        ) { c.fromPanel { LayerOps.mergeDown(c, layer) } },
        WindowAction(LayerLabels.DELETE, env.layerCount > 1, iconGlyph(Icons.Outlined.Delete)) { c.fromPanel(env.onDelete) },
        WindowAction(LayerLabels.FILTERS, true, { tint -> Text("FX", color = tint, fontSize = 15.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold) }) {
            c.fromPanel { env.onOpenPanel(EditorPanel.FILTERS) }
        },
        WindowAction(LayerLabels.MORE, true, iconGlyph(Icons.Filled.MoreVert), popup = { LayerMenu(env) }) {
            c.endCanvasGesture()
            env.ui.menu.show(mask = false)
        },
    )
}

/** The strip's "Clear layer" glyph: a small checker square. */
@Composable
private fun ClearGlyph(tint: Color) {
    Canvas(Modifier.size(20.dp)) {
        val n = size.width / 4f
        for (y in 0 until 4) for (x in 0 until 4) {
            if ((x + y) % 2 == 0) drawRect(tint, topLeft = Offset(x * n, y * n), size = Size(n, n))
        }
        drawRect(tint, style = Stroke(1.dp.toPx()))
    }
}

/** One icon cell (≥ 40 dp in both directions); its label is the only label of the cell. */
@Composable
internal fun ActionCell(action: WindowAction, modifier: Modifier = Modifier) {
    val tint = if (action.enabled) Color.White else Color.White.copy(alpha = 0.35f)
    Box(
        modifier
            .semantics { contentDescription = action.label }
            .combinedClickable(
                enabled = action.enabled,
                onClickLabel = action.label,
                role = Role.Button,
                onLongClickLabel = action.onLongClickLabel,
                onLongClick = action.onLongClick,
                onClick = action.onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        action.glyph(tint)
        action.popup?.invoke()
    }
}

/** The left buttons pane: [columns] columns of 50 × 40 cells (scrolls when it doesn't fit). */
@Composable
internal fun LeftButtons(actions: List<WindowAction>, columns: Int, modifier: Modifier = Modifier) {
    val cols = columns.coerceAtLeast(1)
    Column(modifier.background(IbisColors.PanelStrip), verticalArrangement = Arrangement.Center) {
        for (chunk in actions.chunked(cols)) {
            Row(Modifier.fillMaxWidth()) {
                for (a in chunk) ActionCell(a, Modifier.weight(1f).height(IbisDims.LayerButtonCellHeight))
                repeat(cols - chunk.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** The right strip: 40 dp icons at a 40 dp pitch. */
@Composable
internal fun RightStrip(actions: List<WindowAction>, modifier: Modifier = Modifier) {
    Column(modifier.background(IbisColors.PanelStrip)) {
        for (a in actions) ActionCell(a, Modifier.fillMaxWidth().height(IbisDims.LayerStripPitch))
    }
}

/** Every button of the left column and the strip as a grid (the short side-by-side window). */
@Composable
internal fun ActionGrid(actions: List<WindowAction>, perRow: Int, modifier: Modifier = Modifier) {
    Column(modifier.background(IbisColors.PanelStrip)) {
        for (chunk in actions.chunked(perRow)) {
            Row(Modifier.fillMaxWidth()) {
                for (a in chunk) ActionCell(a, Modifier.weight(1f).height(IbisDims.LayerButtonCellHeight))
                repeat(perRow - chunk.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// ====================================================================== menus

/** "New vector layer" / "New adjustment layer (Tone)" under the button of [anchor]. */
@Composable
private fun SpecialLayerMenu(env: LayerWindowEnv, anchor: SpecialMenuAnchor) {
    val c = env.controller
    val ui = env.ui
    val close = { ui.special = null }
    DropdownMenu(expanded = ui.special == anchor, onDismissRequest = close, containerColor = BrushworkColors.ChromeHigh) {
        MenuItem(LayerLabels.NEW_VECTOR, EditorIcons.Vector, enabled = env.canAddLayer) { close(); c.fromPanel { LayerOps.addVectorLayer(c) } }
        MenuItem(LayerLabels.NEW_ADJUSTMENT, Icons.Filled.Tune, enabled = c.canAddAdjustmentLayer) { close(); c.fromPanel { LayerOps.addAdjustmentLayer(c) } }
    }
}

/**
 * The ⋮ menu: the v1.5 layer menu (edit text / shape / objects / adjustment / mask, rename, move,
 * convert / rasterize, new vector / adjustment layer, the mask page, flips, clear, fill). Entries
 * the window shows as buttons at the same time are left out here (Import picture, Apply to layer
 * below) or renamed (the mask page is "Mask actions"; "Layer mask" is the strip's), so no label
 * repeats (I10).
 */
@Composable
private fun LayerMenu(env: LayerWindowEnv) {
    val c = env.controller
    val row = env.row
    val layer = row.layer
    val menu = env.ui.menu
    val docIndex = env.docIndex
    val close = { menu.close() }
    /** Closes the menu, then runs [block] as a panel action. */
    val act = { block: () -> Unit -> close(); c.fromPanel(block) }
    // Reads the layer's pixels: only while the menu is open.
    val canConvert = remember(menu.open, row.contentVersion, row.kind) { menu.open && LayerOps.canConvertToVector(c, layer) }
    DropdownMenu(expanded = menu.open, onDismissRequest = close, containerColor = BrushworkColors.ChromeHigh) {
        if (!menu.maskPage) {
            if (row.isText) {
                MenuItem("Edit text", Icons.Filled.TextFields) { act { LayerOps.editText(c, layer) } }
                HorizontalDivider(color = BrushworkColors.ChromeBorder)
            }
            if (row.isShape) {
                MenuItem("Edit shape", Icons.Outlined.Category) { act { LayerOps.editShape(c, layer) } }
                HorizontalDivider(color = BrushworkColors.ChromeBorder)
            }
            if (row.isVector) {
                MenuItem("Edit objects", Icons.Filled.OpenWith) { act { LayerOps.editObjects(c, layer) } }
                MenuItem("Rasterize vector layer", Icons.Filled.Image) { act { LayerOps.rasterizeVector(c, layer) } }
                HorizontalDivider(color = BrushworkColors.ChromeBorder)
            }
            if (row.isAdjustment) {
                MenuItem("Edit adjustment", Icons.Filled.Tune) { act { LayerOps.editAdjustment(c, layer) } }
                MenuItem("Edit mask", EditorIcons.Masks) { act { LayerOps.editAdjustmentMask(c, layer) } }
                MenuItem("Use mask as selection", Icons.Filled.SelectAll, enabled = row.hasMask) { act { LayerOps.maskToSelection(c, layer) } }
                HorizontalDivider(color = BrushworkColors.ChromeBorder)
            }
            if (canConvert) {
                MenuItem("Convert to vector layer", EditorIcons.Vector) { act { LayerOps.convertToVector(c, layer) } }
            }
            MenuItem(LayerLabels.NEW_VECTOR, EditorIcons.Vector, enabled = env.canAddLayer) { act { LayerOps.addVectorLayer(c) } }
            MenuItem(LayerLabels.NEW_ADJUSTMENT, Icons.Filled.Tune, enabled = c.canAddAdjustmentLayer) { act { LayerOps.addAdjustmentLayer(c) } }
            HorizontalDivider(color = BrushworkColors.ChromeBorder)
            MenuItem("Rename…", Icons.Filled.DriveFileRenameOutline) { close(); env.onRename() }
            HorizontalDivider(color = BrushworkColors.ChromeBorder)
            MenuItem("Move layer up", Icons.Filled.ArrowUpward, enabled = docIndex < env.layerCount - 1) { act { c.moveLayerUp(layer) } }
            MenuItem("Move layer down", Icons.Filled.ArrowDownward, enabled = docIndex > 0) { act { c.moveLayerDown(layer) } }
            HorizontalDivider(color = BrushworkColors.ChromeBorder)
            DropdownMenuItem(
                text = { Text(if (row.hasMask) LayerLabels.MASK_ACTIONS else "${LayerLabels.MASK_ACTIONS} (no mask)") },
                onClick = { menu.maskPage = true },
                leadingIcon = { Icon(Icons.Filled.Contrast, contentDescription = null) },
                trailingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
            )
            HorizontalDivider(color = BrushworkColors.ChromeBorder)
            MenuItem("Flip horizontal", Icons.Filled.Flip) { act { LayerOps.flip(c, layer, horizontal = true) } }
            MenuItem("Flip vertical", Icons.Filled.Flip, iconRotation = 90f) { act { LayerOps.flip(c, layer, horizontal = false) } }
            HorizontalDivider(color = BrushworkColors.ChromeBorder)
            MenuItem(LayerOps.clearLabel(c, layer), Icons.Filled.LayersClear) { act { LayerOps.clear(c, layer) } }
            DropdownMenuItem(
                text = { Text(LayerOps.fillLabel(c, layer)) },
                onClick = { act { LayerOps.fill(c, layer) } },
                leadingIcon = { ColorSwatch(c.color, size = 22.dp) },
            )
        } else {
            MenuItem("Back", Icons.AutoMirrored.Filled.ArrowBack) { menu.maskPage = false }
            HorizontalDivider(color = BrushworkColors.ChromeBorder)
            MenuItem("Add gradient mask…", EditorIcons.Masks) { act { LayerOps.addGradientMask(c, layer) } }
            if (row.maskIsSpec) {
                MenuItem("Convert to pixel mask", Icons.Filled.Brush) { act { LayerOps.toPixelMask(c, layer) } }
            }
            if (row.hasMask) {
                MenuItem("Load mask as selection", Icons.Filled.SelectAll) { act { LayerOps.maskToSelection(c, layer) } }
            }
            HorizontalDivider(color = BrushworkColors.ChromeBorder)
            if (!row.hasMask) {
                if (env.hasSelection) {
                    MenuItem("Add mask from selection", Icons.Filled.Add) { act { LayerOps.addMask(c, layer, fromSelection = true) } }
                }
                MenuItem(if (env.hasSelection) "Add mask (reveal all)" else "Add mask", Icons.Filled.Add) {
                    act { LayerOps.addMask(c, layer, fromSelection = false) }
                }
            } else {
                // (An adjustment layer has no content to switch to, and no pixels to apply its
                // mask to: brushes always paint its mask.)
                if (!row.isAdjustment) {
                    val editing = row.editingMask
                    MenuItem(if (editing) "Edit layer content" else "Edit mask", Icons.Filled.Edit) {
                        act { LayerOps.editTarget(c, layer, mask = !editing) }
                    }
                }
                val enabled = row.maskEnabled
                MenuItem(if (enabled) "Disable mask" else "Enable mask", if (enabled) Icons.Filled.VisibilityOff else Icons.Filled.Visibility) {
                    act { LayerOps.setMaskEnabled(c, layer, !enabled) }
                }
                MenuItem("Invert mask", Icons.Filled.InvertColors) { act { LayerOps.invertMask(c, layer) } }
                if (!row.isAdjustment) MenuItem("Apply mask", Icons.Filled.Check) { act { LayerOps.applyMask(c, layer) } }
                MenuItem("Delete mask", Icons.Filled.Delete) { act { LayerOps.deleteMask(c, layer) } }
            }
        }
    }
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, enabled: Boolean = true, iconRotation: Float = 0f, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        onClick = onClick,
        enabled = enabled,
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.rotate(iconRotation)) },
    )
}

// ====================================================================== blend row

/**
 * The blend row (56 dp, black): ↙ "Clipping", α "Alpha lock" and 🔒 "Lock layer" toggles (48 dp
 * each, with their v1.5 captions), then the white "Normal ˄" dropdown ("Choose blend mode").
 */
@Composable
internal fun BlendRow(controller: EditorController, row: LayerRowModel, isBottom: Boolean, modifier: Modifier = Modifier) {
    val layer = row.layer
    Row(modifier.fillMaxWidth().background(IbisColors.PanelStrip).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        BlendToggle(
            description = null,
            caption = LayerLabels.CLIPPING,
            checked = row.clipping,
            // A clipping flag on the bottom layer has no effect; only allow turning it off there.
            enabled = !isBottom || row.clipping,
            onToggle = { controller.fromPanel { controller.toggleClipping(layer) } },
        ) { tint -> Icon(Icons.Filled.SubdirectoryArrowRight, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp).rotate(90f)) }
        BlendToggle(
            description = LayerLabels.ALPHA_LOCK,
            caption = LayerLabels.ALPHA_LOCK_CAPTION,
            checked = row.alphaLocked,
            onToggle = { controller.fromPanel { controller.toggleAlphaLock(layer) } },
        ) { tint ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("α", color = tint, fontSize = 15.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold)
                Icon(Icons.Filled.Lock, contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
            }
        }
        BlendToggle(
            description = LayerLabels.LOCK,
            caption = LayerLabels.LOCK_CAPTION,
            checked = row.locked,
            onToggle = { controller.fromPanel { controller.toggleLock(layer) } },
        ) { tint -> Icon(if (row.locked) Icons.Filled.Lock else Icons.Filled.LockOpen, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(6.dp))
        BlendModeDropdown(
            mode = row.blendMode,
            onSelect = { m -> controller.fromPanel { controller.setBlendMode(layer, m) } },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(4.dp))
    }
}

/** A blend-row toggle: glyph over a 9 sp caption, 48 dp wide, accent while on. */
@Composable
private fun BlendToggle(
    description: String?,
    caption: String,
    checked: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean = true,
    glyph: @Composable (tint: Color) -> Unit,
) {
    val tint = when {
        !enabled -> Color.White.copy(alpha = 0.3f)
        else -> Color.White
    }
    Column(
        Modifier
            .size(width = IbisDims.BlendToggle, height = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (checked) IbisColors.Accent else Color.Transparent)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = { onToggle() }),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.height(22.dp), contentAlignment = Alignment.Center) { glyph(tint) }
        Text(caption, color = tint, fontSize = 9.sp, lineHeight = 11.sp, maxLines = 1)
    }
}

/** The white rounded "Normal ˄" dropdown (36 dp tall in a 40 dp target) listing every [LayerBlendMode]. */
@Composable
private fun BlendModeDropdown(mode: LayerBlendMode, onSelect: (LayerBlendMode) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(IbisDims.LayerEyeTouch)
                .clickable(role = Role.Button) { open = true },
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(IbisDims.BlendDropdownHeight)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White)
                    .padding(start = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(mode.label, color = IbisColors.ListText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = LayerLabels.BLEND, tint = IbisColors.ListText)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = BrushworkColors.ChromeHigh) {
            LayerBlendMode.entries.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m.label) },
                    onClick = { open = false; if (m != mode) onSelect(m) },
                    leadingIcon = {
                        if (m == mode) Icon(Icons.Filled.Check, contentDescription = "Current", tint = BrushworkColors.Accent)
                        else Spacer(Modifier.size(24.dp))
                    },
                )
            }
        }
    }
}

// ====================================================================== opacity row

/**
 * The opacity row (48 dp): "100%" (tap: "Type layer opacity"; long press: the Step popup), −,
 * the ibisPaint slider ("Layer opacity") and +. A drag previews live and records ONE "Opacity"
 * step on release; −/+ record one step each. An adjustment layer's drag goes through
 * `controller.liveAdjust` (design §3.1 C2), so the effect follows the finger without refreshing
 * the layer list on every move. With increments on, drags land on the Percent step and −/+ move
 * by it (typed values are never stepped).
 */
@Composable
internal fun OpacityRow(controller: EditorController, layer: Layer, opacity: Float, onType: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val preview = remember(layer) { OpacityPreview(controller, layer, scope) }
    var dragValue by remember(layer) { mutableStateOf<Float?>(null) }
    val readout = remember(layer) { booleanArrayOf(false) }
    val increments = controller.increments
    // Leaving composition mid-drag (window closed, layer switched) must still record the change.
    DisposableEffect(preview) {
        onDispose {
            preview.finish()
            if (readout[0]) { increments.readout = null; readout[0] = false }
        }
    }
    val shown = dragValue ?: opacity
    val pct = (shown.coerceIn(0f, 1f) * 100f).roundToInt()
    fun step(up: Boolean) = controller.fromPanel {
        val next = LayerListMath.stepOpacity(layer.opacity, up, increments.step(IncrementKind.PERCENT))
        controller.setLayerProps(layer, layer.props().copy(opacity = next), OPACITY_STEP)
    }
    Row(modifier.fillMaxWidth().background(IbisColors.OpacityRow).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(52.dp)
                .fillMaxHeight()
                .stepOnLongPress(IncrementKind.PERCENT)
                .clickable(onClickLabel = LayerLabels.TYPE_OPACITY, role = Role.Button, onClick = onType),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text("$pct%", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(end = 2.dp))
        }
        RoundStepButton(Icons.Filled.Remove, LayerLabels.LESS_OPACITY) { step(up = false) }
        IbisOpacitySlider(
            value = shown,
            onDrag = { f ->
                if (dragValue == null) controller.endCanvasGesture()
                val o = increments.percent01(f.coerceIn(0f, 1f))
                dragValue = o
                preview.update(o)
                if (increments.enabled) {
                    increments.readout = "${(o * 100f).roundToInt()} %"
                    readout[0] = true
                }
            },
            onDragEnd = {
                preview.finish()
                dragValue = null
                if (readout[0]) { increments.readout = null; readout[0] = false }
            },
            onSet = { v -> controller.fromPanel { controller.setLayerProps(layer, layer.props().copy(opacity = v), OPACITY_STEP) } },
            modifier = Modifier.weight(1f),
        )
        RoundStepButton(Icons.Filled.Add, LayerLabels.MORE_OPACITY) { step(up = true) }
    }
}

/** Undo label of every layer opacity change (v1.5). */
internal const val OPACITY_STEP = "Opacity"

/** − / +: a 22 dp black disc with a white glyph in a 40 dp target. */
@Composable
private fun RoundStepButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(IbisDims.SliderButtonTouch)
            .semantics { contentDescription = label }
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(IbisDims.SliderButton).clip(CircleShape).background(IbisColors.SliderButton), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}

private val THUMB_RADIUS = 11.dp
private val TRACK_THICKNESS = 8.dp

/**
 * The ibisPaint opacity slider: a checker → black track and a white Ø 22 thumb. A touch on the
 * track moves the thumb there; a horizontal drag follows the finger ([onDrag] with the fraction,
 * [onDragEnd] once at the end); a vertical drag is left to the containers. Accessibility sets the
 * value in one step ([onSet]).
 */
@Composable
private fun IbisOpacitySlider(
    value: Float,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onSet: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    val v = value.coerceIn(0f, 1f)
    Canvas(
        modifier
            .height(IbisDims.SliderButtonTouch)
            .semantics {
                contentDescription = LayerLabels.OPACITY
                stateDescription = "${(v * 100f).roundToInt()}%"
                progressBarRangeInfo = ProgressBarRangeInfo(v, 0f..1f)
                setProgress { target -> onSet(target.coerceIn(0f, 1f)); true }
            }
            .pointerInput(Unit) {
                val r = THUMB_RADIUS.toPx()
                fun fraction(x: Float): Float = ((x - r) / (size.width - 2f * r).coerceAtLeast(1f)).coerceIn(0f, 1f)
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                    if (slop != null) {
                        drag(fraction(slop.position.x))
                        horizontalDrag(slop.id) { change ->
                            drag(fraction(change.position.x))
                            change.consume()
                        }
                        end()
                    } else {
                        // A tap (no drag, not taken by a container): the thumb goes there.
                        val up = currentEvent.changes.firstOrNull { it.id == down.id }
                        if (up != null && !up.pressed && !up.isConsumed) {
                            up.consume()
                            drag(fraction(up.position.x))
                            end()
                        }
                    }
                }
            },
    ) {
        val r = THUMB_RADIUS.toPx()
        val th = TRACK_THICKNESS.toPx()
        val cy = size.height / 2f
        val left = r - th / 2f
        val right = size.width - r + th / 2f
        val track = Path().apply {
            addRoundRect(RoundRect(left, cy - th / 2f, right, cy + th / 2f, CornerRadius(th / 2f)))
        }
        clipPath(track) {
            checker(IbisColors.CheckerLight, IbisColors.CheckerLight2, IbisDims.SliderChecker.toPx())
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = left, endX = right))
        }
        val x = r + v * (size.width - 2f * r)
        drawCircle(IbisColors.SliderThumb, radius = r, center = Offset(x, cy))
        drawCircle(Color.Black.copy(alpha = 0.3f), radius = r - 0.5.dp.toPx(), center = Offset(x, cy), style = Stroke(IbisDims.SliderThumbRing.toPx()))
    }
}

/**
 * Live preview of one layer's opacity with a single undo step per gesture. A normal layer
 * previews through [EditorController.previewLayerProps] at most every [PREVIEW_INTERVAL_MS] (each
 * preview recomposites the canvas, v1.5). An adjustment layer's opacity goes straight to the layer
 * and through `liveAdjust.touch` on every move (its live session draws the proxy, design §3.1 C2;
 * the layer list is refreshed once, at the end), then `liveAdjust.end` refines to exact.
 */
private class OpacityPreview(
    private val controller: EditorController,
    private val layer: Layer,
    private val scope: CoroutineScope,
) {
    private val throttle = PreviewThrottle(PREVIEW_INTERVAL_MS)
    private var before: LayerProps? = null
    private var pending: Float? = null
    private var trailing: Job? = null
    private var live = false

    fun update(value: Float) {
        if (before == null) {
            before = layer.props()
            live = layer.isAdjustmentLayer
            throttle.reset()
        }
        if (live) {
            if (layer.opacity != value) {
                layer.opacity = value
                controller.liveAdjust.touch(layer, null)
            }
            return
        }
        pending = value
        val now = SystemClock.uptimeMillis()
        if (throttle.offer(now)) {
            apply()
        } else if (trailing?.isActive != true) {
            trailing = scope.launch {
                delay(throttle.delayUntilNext(SystemClock.uptimeMillis()))
                throttle.markApplied(SystemClock.uptimeMillis())
                apply()
            }
        }
    }

    private fun apply() {
        val v = pending ?: return
        pending = null
        if (before == null || layer.opacity == v) return
        controller.previewLayerProps(layer, layer.props().copy(opacity = v))
    }

    /** Applies the last value right away and records the undo step (no-op if nothing changed). */
    fun finish() {
        trailing?.cancel()
        trailing = null
        apply()
        val b = before ?: return
        before = null
        pending = null
        if (live) controller.liveAdjust.end(layer)
        live = false
        controller.commitLayerProps(layer, b, OPACITY_STEP)
    }

    private companion object {
        const val PREVIEW_INTERVAL_MS = 33L
    }
}

// ====================================================================== transparency squares

/**
 * The transparency squares (40 dp row): white, light checker, dark checker and none (the
 * surround), each a 28 dp square in a 40 dp target, the chosen one with a 2 dp accent border.
 * A view preference only ([LayerOps.setTransparencyDisplay]).
 */
@Composable
internal fun TransparencySquares(current: TransparencyDisplay, onPick: (TransparencyDisplay) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().background(IbisColors.ListRow), contentAlignment = Alignment.CenterEnd) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            for (t in TransparencyDisplay.entries) TransparencySquare(t, t == current) { onPick(t) }
        }
    }
}

@Composable
private fun RowScope.TransparencySquare(display: TransparencyDisplay, selected: Boolean, onClick: () -> Unit) {
    val label = LayerLabels.transparency(display)
    Box(
        Modifier
            .size(IbisDims.TransparencyTouch)
            .semantics { contentDescription = label }
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(IbisDims.TransparencySquare)) {
            transparencyBacking(display, size.width / 6f)
            if (display == TransparencyDisplay.NONE) {
                drawLine(Color(0xFF3C3C3C), Offset(0f, size.height), Offset(size.width, 0f), 1.5.dp.toPx())
            }
            if (selected) {
                val b = IbisDims.TransparencyBorder.toPx()
                drawRect(IbisColors.Accent, topLeft = Offset(b / 2f, b / 2f), size = Size(size.width - b, size.height - b), style = Stroke(b))
            } else {
                drawRect(Color(0xFF7A7A7A), style = Stroke(1.dp.toPx()))
            }
        }
    }
}
