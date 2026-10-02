package com.brushwork.paint.ui.layers

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SubdirectoryArrowRight
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.LayerProps
import com.brushwork.paint.ui.common.ColorSwatch
import com.brushwork.paint.ui.editor.EditorIcons
import com.brushwork.paint.ui.editor.endCanvasGesture
import com.brushwork.paint.ui.theme.BrushworkColors
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

/**
 * Properties of the active layer, compact: blend mode + clipping / alpha lock / lock toggles on
 * one row, the opacity slider (live preview, one undo step) with its typed value on the next.
 */
@Composable
internal fun LayerProperties(controller: EditorController, row: LayerRowModel, isBottom: Boolean, onTypeOpacity: () -> Unit) {
    val layer = row.layer
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        BlendModeButton(
            mode = row.blendMode,
            onSelect = { m -> controller.fromPanel { controller.setBlendMode(layer, m) } },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(2.dp))
        PropToggle(
            label = "Clipping",
            checked = row.clipping,
            // A clipping flag on the bottom layer has no effect; only allow turning it off there.
            enabled = !isBottom || row.clipping,
            onToggle = { controller.fromPanel { controller.toggleClipping(layer) } },
        ) { tint -> Icon(Icons.Filled.SubdirectoryArrowRight, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp)) }
        PropToggle(
            label = "α lock",
            checked = row.alphaLocked,
            onToggle = { controller.fromPanel { controller.toggleAlphaLock(layer) } },
        ) { tint -> AlphaLockBadge(tint) }
        PropToggle(
            label = "Lock",
            checked = row.locked,
            onToggle = { controller.fromPanel { controller.toggleLock(layer) } },
        ) { tint -> Icon(if (row.locked) Icons.Filled.Lock else Icons.Filled.LockOpen, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp)) }
    }
    OpacitySlider(controller, layer, row.opacity, onTypeOpacity)
}

/** Drop-down listing every [LayerBlendMode]. */
@Composable
private fun BlendModeButton(mode: LayerBlendMode, onSelect: (LayerBlendMode) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface(
            onClick = { open = true },
            shape = RoundedCornerShape(8.dp),
            color = BrushworkColors.ChromeHigh,
            modifier = Modifier.fillMaxWidth().height(40.dp),
        ) {
            Row(Modifier.padding(start = 8.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Blend", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, lineHeight = 10.sp, color = BrushworkColors.OnChromeDim, maxLines = 1)
                    Text(mode.label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose blend mode")
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

/** Compact icon-over-label toggle (48 x 40 dp touch target). */
@Composable
private fun PropToggle(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean = true,
    glyph: @Composable (tint: Color) -> Unit,
) {
    val tint = when {
        !enabled -> BrushworkColors.OnChromeDim.copy(alpha = 0.4f)
        checked -> Color.White
        else -> BrushworkColors.OnChrome
    }
    Column(
        Modifier
            .size(width = 48.dp, height = 40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (checked) BrushworkColors.AccentDim else Color.Transparent)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = { onToggle() }),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.height(18.dp), contentAlignment = Alignment.Center) { glyph(tint) }
        Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, lineHeight = 11.sp, color = tint, maxLines = 1)
    }
}

/**
 * Opacity 0-100 %. Dragging previews live through [EditorController.previewLayerProps] at most
 * ~30 times a second (each preview recomposites the whole canvas); releasing records ONE undo
 * step. Tapping the value ([onType]) lets the user type it.
 */
@Composable
private fun OpacitySlider(controller: EditorController, layer: Layer, opacity: Float, onType: () -> Unit) {
    val scope = rememberCoroutineScope()
    val preview = remember(layer) { OpacityPreview(controller, layer, scope) }
    var dragValue by remember(layer) { mutableStateOf<Float?>(null) }
    // Leaving composition mid-drag (window closed) must still record the change.
    DisposableEffect(preview) { onDispose { preview.finish() } }
    val shown = dragValue ?: opacity
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Opacity, contentDescription = null, tint = BrushworkColors.OnChromeDim, modifier = Modifier.padding(start = 4.dp, end = 2.dp).size(16.dp))
        Slider(
            value = (shown * 100f).coerceIn(0f, 100f),
            onValueChange = { v ->
                if (dragValue == null) controller.endCanvasGesture()
                val o = (v / 100f).coerceIn(0f, 1f)
                dragValue = o
                preview.update(o)
            },
            valueRange = 0f..100f,
            onValueChangeFinished = {
                preview.finish()
                dragValue = null
            },
            colors = SliderDefaults.colors(thumbColor = BrushworkColors.Accent, activeTrackColor = BrushworkColors.Accent),
            modifier = Modifier.weight(1f).semantics { contentDescription = "Layer opacity" },
        )
        Box(
            Modifier
                .width(52.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClickLabel = "Type layer opacity", role = Role.Button, onClick = onType),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${(shown * 100f).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(BrushworkColors.ChromeHigh).padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}

/** Throttled live preview of one layer's opacity with a single undo step per gesture. */
private class OpacityPreview(
    private val controller: EditorController,
    private val layer: Layer,
    private val scope: CoroutineScope,
) {
    private val throttle = PreviewThrottle(PREVIEW_INTERVAL_MS)
    private var before: LayerProps? = null
    private var pending: Float? = null
    private var trailing: Job? = null

    fun update(value: Float) {
        if (before == null) {
            before = layer.props()
            throttle.reset()
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
        controller.commitLayerProps(layer, b, "Opacity")
    }

    private companion object {
        const val PREVIEW_INTERVAL_MS = 33L
    }
}

/**
 * The window's action row: add, duplicate (only the selected pixels while there is a selection),
 * delete, merge down, and the overflow menu with everything else.
 */
@Composable
internal fun LayerToolbar(
    controller: EditorController,
    row: LayerRowModel,
    docIndex: Int,
    layerCount: Int,
    canAddLayer: Boolean,
    hasSelection: Boolean,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onImportPicture: () -> Unit,
) {
    val layer = row.layer
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        BarButton(Icons.Filled.Add, "Add layer", enabled = canAddLayer) { controller.fromPanel { LayerOps.addLayer(controller) } }
        BarButton(
            Icons.Filled.ContentCopy,
            if (hasSelection) "Duplicate layer (selected pixels only)" else "Duplicate layer",
            enabled = canAddLayer,
        ) { controller.fromPanel { LayerOps.duplicate(controller, layer) } }
        BarButton(Icons.Filled.Delete, "Delete layer", enabled = layerCount > 1) { controller.fromPanel(onDelete) }
        BarButton(Icons.Filled.Merge, "Merge down", enabled = docIndex > 0, rotation = 180f) { controller.fromPanel { LayerOps.mergeDown(controller, layer) } }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            OverflowMenuButton(controller, row, docIndex, layerCount, canAddLayer, hasSelection, onRename, onImportPicture)
        }
    }
}

@Composable
private fun RowScope.BarButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    rotation: Float = 0f,
    onClick: () -> Unit,
) {
    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
        BarIcon(icon, description, enabled, rotation, onClick)
    }
}

@Composable
private fun BarIcon(icon: ImageVector, description: String, enabled: Boolean, rotation: Float = 0f, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(44.dp),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = BrushworkColors.OnChrome,
            disabledContentColor = BrushworkColors.OnChromeDim.copy(alpha = 0.4f),
        ),
    ) { Icon(icon, contentDescription = description, modifier = Modifier.rotate(rotation)) }
}

/**
 * Everything that doesn't fit the action row: import, rename, move up/down, the mask actions (a
 * sub-page of the same menu), flips, clear and fill.
 */
@Composable
private fun OverflowMenuButton(
    controller: EditorController,
    row: LayerRowModel,
    docIndex: Int,
    layerCount: Int,
    canAddLayer: Boolean,
    hasSelection: Boolean,
    onRename: () -> Unit,
    onImportPicture: () -> Unit,
) {
    val layer = row.layer
    var open by remember { mutableStateOf(false) }
    var maskPage by remember { mutableStateOf(false) }
    val close = { open = false; maskPage = false }
    /** Closes the menu, then runs [block] as a panel action. */
    val act = { block: () -> Unit -> close(); controller.fromPanel(block) }
    // Reads the layer's pixels: only while the menu is open.
    val canConvert = remember(open, row.contentVersion, row.kind) { open && LayerOps.canConvertToVector(controller, layer) }
    Box {
        BarIcon(Icons.Filled.MoreVert, "More layer actions", enabled = true) { maskPage = false; open = true }
        DropdownMenu(expanded = open, onDismissRequest = close, containerColor = BrushworkColors.ChromeHigh) {
            if (!maskPage) {
                if (row.isText) {
                    MenuItem("Edit text", Icons.Filled.TextFields) { act { LayerOps.editText(controller, layer) } }
                    HorizontalDivider(color = BrushworkColors.ChromeBorder)
                }
                if (row.isShape) {
                    MenuItem("Edit shape", Icons.Outlined.Category) { act { LayerOps.editShape(controller, layer) } }
                    HorizontalDivider(color = BrushworkColors.ChromeBorder)
                }
                if (row.isVector) {
                    MenuItem("Edit objects", Icons.Filled.OpenWith) { act { LayerOps.editObjects(controller, layer) } }
                    MenuItem("Rasterize vector layer", Icons.Filled.Image) { act { LayerOps.rasterizeVector(controller, layer) } }
                    HorizontalDivider(color = BrushworkColors.ChromeBorder)
                }
                if (row.isAdjustment) {
                    MenuItem("Edit adjustment", Icons.Filled.Tune) { act { LayerOps.editAdjustment(controller, layer) } }
                    MenuItem("Edit mask", EditorIcons.Masks) { act { LayerOps.editAdjustmentMask(controller, layer) } }
                    MenuItem("Apply to layer below", Icons.Filled.Merge, enabled = docIndex > 0, iconRotation = 180f) { act { LayerOps.mergeDown(controller, layer) } }
                    MenuItem("Use mask as selection", Icons.Filled.SelectAll, enabled = row.hasMask) { act { LayerOps.maskToSelection(controller, layer) } }
                    HorizontalDivider(color = BrushworkColors.ChromeBorder)
                }
                if (canConvert) {
                    MenuItem("Convert to vector layer", EditorIcons.Vector) { act { LayerOps.convertToVector(controller, layer) } }
                }
                MenuItem("New vector layer", EditorIcons.Vector, enabled = canAddLayer) { act { LayerOps.addVectorLayer(controller) } }
                MenuItem("New adjustment layer (Tone)", Icons.Filled.Tune, enabled = controller.canAddAdjustmentLayer) { act { LayerOps.addAdjustmentLayer(controller) } }
                HorizontalDivider(color = BrushworkColors.ChromeBorder)
                MenuItem("Import picture", Icons.Filled.AddPhotoAlternate, enabled = canAddLayer) { close(); onImportPicture() }
                MenuItem("Rename…", Icons.Filled.DriveFileRenameOutline) { close(); onRename() }
                HorizontalDivider(color = BrushworkColors.ChromeBorder)
                MenuItem("Move layer up", Icons.Filled.ArrowUpward, enabled = docIndex < layerCount - 1) { act { controller.moveLayerUp(layer) } }
                MenuItem("Move layer down", Icons.Filled.ArrowDownward, enabled = docIndex > 0) { act { controller.moveLayerDown(layer) } }
                HorizontalDivider(color = BrushworkColors.ChromeBorder)
                DropdownMenuItem(
                    text = { Text(if (row.hasMask) "Layer mask" else "Layer mask (none)") },
                    onClick = { maskPage = true },
                    leadingIcon = { Icon(Icons.Filled.Contrast, contentDescription = null) },
                    trailingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Open mask actions") },
                )
                HorizontalDivider(color = BrushworkColors.ChromeBorder)
                MenuItem("Flip horizontal", Icons.Filled.Flip) { act { LayerOps.flip(controller, layer, horizontal = true) } }
                MenuItem("Flip vertical", Icons.Filled.Flip, iconRotation = 90f) { act { LayerOps.flip(controller, layer, horizontal = false) } }
                HorizontalDivider(color = BrushworkColors.ChromeBorder)
                MenuItem(LayerOps.clearLabel(controller, layer), Icons.Filled.LayersClear) { act { LayerOps.clear(controller, layer) } }
                DropdownMenuItem(
                    text = { Text(LayerOps.fillLabel(controller, layer)) },
                    onClick = { act { LayerOps.fill(controller, layer) } },
                    leadingIcon = { ColorSwatch(controller.color, size = 22.dp) },
                )
            } else {
                MenuItem("Back", Icons.AutoMirrored.Filled.ArrowBack) { maskPage = false }
                HorizontalDivider(color = BrushworkColors.ChromeBorder)
                MenuItem("Add gradient mask…", EditorIcons.Masks) { act { LayerOps.addGradientMask(controller, layer) } }
                if (row.maskIsSpec) {
                    MenuItem("Convert to pixel mask", Icons.Filled.Brush) { act { LayerOps.toPixelMask(controller, layer) } }
                }
                if (row.hasMask) {
                    MenuItem("Load mask as selection", Icons.Filled.SelectAll) { act { LayerOps.maskToSelection(controller, layer) } }
                }
                HorizontalDivider(color = BrushworkColors.ChromeBorder)
                if (!row.hasMask) {
                    if (hasSelection) {
                        MenuItem("Add mask from selection", Icons.Filled.Add) { act { LayerOps.addMask(controller, layer, fromSelection = true) } }
                    }
                    MenuItem(if (hasSelection) "Add mask (reveal all)" else "Add mask", Icons.Filled.Add) {
                        act { LayerOps.addMask(controller, layer, fromSelection = false) }
                    }
                } else {
                    // (An adjustment layer has no content to switch to, and no pixels to apply
                    // its mask to: brushes always paint its mask.)
                    if (!row.isAdjustment) {
                        val editing = row.editingMask
                        MenuItem(if (editing) "Edit layer content" else "Edit mask", Icons.Filled.Edit) {
                            act { LayerOps.editTarget(controller, layer, mask = !editing) }
                        }
                    }
                    val enabled = row.maskEnabled
                    MenuItem(if (enabled) "Disable mask" else "Enable mask", if (enabled) Icons.Filled.VisibilityOff else Icons.Filled.Visibility) {
                        act { LayerOps.setMaskEnabled(controller, layer, !enabled) }
                    }
                    MenuItem("Invert mask", Icons.Filled.InvertColors) { act { LayerOps.invertMask(controller, layer) } }
                    if (!row.isAdjustment) MenuItem("Apply mask", Icons.Filled.Check) { act { LayerOps.applyMask(controller, layer) } }
                    MenuItem("Delete mask", Icons.Filled.Delete) { act { LayerOps.deleteMask(controller, layer) } }
                }
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
