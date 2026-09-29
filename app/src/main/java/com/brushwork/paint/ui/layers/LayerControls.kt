package com.brushwork.paint.ui.layers

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SubdirectoryArrowRight
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.LayerProps
import com.brushwork.paint.ui.common.ColorSwatch
import com.brushwork.paint.ui.common.LabeledSlider
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Properties of the active layer: blend mode, clipping / alpha lock / lock, opacity. */
@Composable
internal fun LayerProperties(controller: EditorController, row: LayerRowModel, isBottom: Boolean) {
    val layer = row.layer
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BlendModeButton(
            mode = row.blendMode,
            onSelect = { controller.setBlendMode(layer, it) },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(4.dp))
        PropToggle(
            label = "Clipping",
            checked = row.clipping,
            // A clipping flag on the bottom layer has no effect; only allow turning it off there.
            enabled = !isBottom || row.clipping,
            onToggle = { controller.toggleClipping(layer) },
        ) { tint -> Icon(Icons.Filled.SubdirectoryArrowRight, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp)) }
        PropToggle(
            label = "α lock",
            checked = row.alphaLocked,
            onToggle = { controller.toggleAlphaLock(layer) },
        ) { tint -> AlphaLockBadge(tint) }
        PropToggle(
            label = "Lock",
            checked = row.locked,
            onToggle = { controller.toggleLock(layer) },
        ) { tint -> Icon(if (row.locked) Icons.Filled.Lock else Icons.Filled.LockOpen, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp)) }
    }
    OpacitySlider(controller, layer, row.opacity)
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
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Row(Modifier.padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Blend mode", style = MaterialTheme.typography.labelSmall, color = BrushworkColors.OnChromeDim, maxLines = 1)
                    Text(mode.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

/** Compact icon-over-label toggle (>= 48dp touch target). */
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
            .size(width = 56.dp, height = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (checked) BrushworkColors.AccentDim else Color.Transparent)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = { onToggle() }),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Box(Modifier.height(20.dp), contentAlignment = Alignment.Center) { glyph(tint) }
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
    }
}

/**
 * Opacity 0-100 %. Dragging previews live through [EditorController.previewLayerProps] at most
 * ~30 times a second (each preview recomposites the whole canvas); releasing records ONE undo step.
 */
@Composable
private fun OpacitySlider(controller: EditorController, layer: Layer, opacity: Float) {
    val scope = rememberCoroutineScope()
    val preview = remember(layer) { OpacityPreview(controller, layer, scope) }
    var dragValue by remember(layer) { mutableStateOf<Float?>(null) }
    // Leaving composition mid-drag (sheet dismissed) must still record the change.
    DisposableEffect(preview) { onDispose { preview.finish() } }
    val shown = dragValue ?: opacity
    LabeledSlider(
        label = "Opacity",
        value = shown * 100f,
        onValueChange = { v ->
            val o = (v / 100f).coerceIn(0f, 1f)
            dragValue = o
            preview.update(o)
        },
        valueRange = 0f..100f,
        valueText = "${(shown * 100f).roundToInt()}%",
        onValueChangeFinished = {
            preview.finish()
            dragValue = null
        },
    )
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

/** Icon toolbar + mask menu + overflow menu for the active layer. */
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
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BarButton(Icons.Filled.Add, "Add layer", enabled = canAddLayer) { LayerOps.addLayer(controller) }
        BarButton(Icons.Filled.ContentCopy, "Duplicate layer", enabled = canAddLayer) { LayerOps.duplicate(controller, layer) }
        BarButton(Icons.Filled.Delete, "Delete layer", enabled = layerCount > 1, onClick = onDelete)
        BarButton(Icons.Filled.Merge, "Merge down", enabled = docIndex > 0, rotation = 180f) { LayerOps.mergeDown(controller, layer) }
        BarButton(Icons.Filled.ArrowUpward, "Move layer up", enabled = docIndex < layerCount - 1) { controller.moveLayerUp(layer) }
        BarButton(Icons.Filled.ArrowDownward, "Move layer down", enabled = docIndex > 0) { controller.moveLayerDown(layer) }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            MaskMenuButton(controller, row, hasSelection)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            OverflowMenuButton(controller, row, canAddLayer, onRename, onImportPicture)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BarButton(
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

@Composable
private fun MaskMenuButton(controller: EditorController, row: LayerRowModel, hasSelection: Boolean) {
    val layer = row.layer
    var open by remember { mutableStateOf(false) }
    Box {
        BarIcon(Icons.Filled.Contrast, "Layer mask", enabled = true) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = BrushworkColors.ChromeHigh) {
            if (!row.hasMask) {
                if (hasSelection) {
                    MenuItem("Add mask from selection", Icons.Filled.Add) { open = false; LayerOps.addMask(controller, layer, fromSelection = true) }
                }
                MenuItem(if (hasSelection) "Add mask (reveal all)" else "Add mask", Icons.Filled.Add) {
                    open = false; LayerOps.addMask(controller, layer, fromSelection = false)
                }
            } else {
                val editing = row.editingMask
                MenuItem(if (editing) "Edit layer content" else "Edit mask", Icons.Filled.Edit) {
                    open = false; LayerOps.editTarget(controller, layer, mask = !editing)
                }
                val enabled = row.maskEnabled
                MenuItem(if (enabled) "Disable mask" else "Enable mask", if (enabled) Icons.Filled.VisibilityOff else Icons.Filled.Visibility) {
                    open = false; LayerOps.setMaskEnabled(controller, layer, !enabled)
                }
                MenuItem("Invert mask", Icons.Filled.InvertColors) { open = false; LayerOps.invertMask(controller, layer) }
                MenuItem("Apply mask", Icons.Filled.Check) { open = false; LayerOps.applyMask(controller, layer) }
                MenuItem("Delete mask", Icons.Filled.Delete) { open = false; LayerOps.deleteMask(controller, layer) }
            }
        }
    }
}

@Composable
private fun OverflowMenuButton(
    controller: EditorController,
    row: LayerRowModel,
    canAddLayer: Boolean,
    onRename: () -> Unit,
    onImportPicture: () -> Unit,
) {
    val layer = row.layer
    var open by remember { mutableStateOf(false) }
    Box {
        BarIcon(Icons.Filled.MoreVert, "More layer actions", enabled = true) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = BrushworkColors.ChromeHigh) {
            MenuItem("Import picture", Icons.Filled.AddPhotoAlternate, enabled = canAddLayer) { open = false; onImportPicture() }
            MenuItem("Rename…", Icons.Filled.DriveFileRenameOutline) { open = false; onRename() }
            HorizontalDivider(color = BrushworkColors.ChromeBorder)
            MenuItem("Flip horizontal", Icons.Filled.Flip) { open = false; LayerOps.flip(controller, layer, horizontal = true) }
            MenuItem("Flip vertical", Icons.Filled.Flip, iconRotation = 90f) { open = false; LayerOps.flip(controller, layer, horizontal = false) }
            HorizontalDivider(color = BrushworkColors.ChromeBorder)
            MenuItem(LayerOps.clearLabel(controller, layer), Icons.Filled.LayersClear) { open = false; LayerOps.clear(controller, layer) }
            DropdownMenuItem(
                text = { Text(LayerOps.fillLabel(controller, layer)) },
                onClick = { open = false; LayerOps.fill(controller, layer) },
                leadingIcon = { ColorSwatch(controller.color, size = 22.dp) },
            )
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
