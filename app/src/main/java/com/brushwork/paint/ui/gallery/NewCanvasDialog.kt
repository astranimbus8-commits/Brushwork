package com.brushwork.paint.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Units
import com.brushwork.paint.storage.NewCanvasSpec
import com.brushwork.paint.ui.color.ColorPickerDialog
import com.brushwork.paint.ui.common.ChoiceChips
import com.brushwork.paint.ui.common.ColorSwatch
import com.brushwork.paint.ui.common.LengthField
import com.brushwork.paint.ui.common.NumberField
import com.brushwork.paint.ui.common.PanelCard
import com.brushwork.paint.ui.common.SectionHeader
import com.brushwork.paint.ui.common.UnitSelector
import com.brushwork.paint.ui.theme.BrushworkColors

private enum class SizeGroup(val label: String) {
    SCREEN("Screen & illustration"),
    PRINT("Print"),
    CUSTOM("Custom"),
}

private enum class BackgroundChoice { WHITE, TRANSPARENT, CUSTOM }

/**
 * Full-screen "New canvas" dialog: name, size (screen presets, paper sizes at a chosen DPI, or
 * a custom size in px/in/cm/mm/pt), background, and the resulting memory use and layer limit.
 * [onCreate] receives a validated spec; [creating] shows progress and blocks dismissal.
 */
@Composable
fun NewCanvasDialog(creating: Boolean, onDismiss: () -> Unit, onCreate: (NewCanvasSpec) -> Unit) {
    Dialog(
        onDismissRequest = { if (!creating) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = BrushworkColors.OnChrome) {
            NewCanvasContent(creating, onDismiss, onCreate)
        }
    }
}

@Composable
private fun NewCanvasContent(creating: Boolean, onDismiss: () -> Unit, onCreate: (NewCanvasSpec) -> Unit) {
    val maxHeap = remember { Runtime.getRuntime().maxMemory() }
    val focusManager = LocalFocusManager.current

    var name by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf(SizeGroup.SCREEN) }
    var size by rememberSaveable { mutableStateOf(CanvasSize()) }
    var unit by rememberSaveable { mutableStateOf(LengthUnit.PX) }
    var background by rememberSaveable { mutableStateOf(BackgroundChoice.WHITE) }
    var customColor by rememberSaveable { mutableIntStateOf(0xFFF3E7D3.toInt()) }
    var pickingColor by remember { mutableStateOf(false) }

    val w = size.width
    val h = size.height
    val check = CanvasPresets.check(w, h, maxHeap)

    // Print sizes and physical units keep their physical size when the resolution changes;
    // a pixel size stays the same.
    fun changeDpi(value: Double) {
        size = size.withDpi(value, keepPhysical = group == SizeGroup.PRINT || unit != LengthUnit.PX)
    }

    fun create() {
        // Commit a number that is still being typed (fields commit on focus loss).
        focusManager.clearFocus()
        val cw = size.width
        val ch = size.height
        if (creating || !CanvasPresets.check(cw, ch, maxHeap).ok) return
        val bg = when (background) {
            BackgroundChoice.WHITE -> 0xFFFFFFFF.toInt()
            BackgroundChoice.TRANSPARENT -> null
            BackgroundChoice.CUSTOM -> customColor
        }
        onCreate(NewCanvasSpec(name.trim().ifEmpty { "Untitled" }, cw, ch, size.dpi.toFloat(), bg))
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDismiss, enabled = !creating) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            Text(
                "New canvas",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
                maxLines = 1,
            )
            Button(
                onClick = { create() },
                enabled = check.ok && !creating,
                colors = ButtonDefaults.buttonColors(containerColor = BrushworkColors.Accent),
            ) {
                if (creating) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Create")
            }
        }
        HorizontalDivider(color = BrushworkColors.ChromeBorder)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(100) },
                label = { Text("Name") },
                placeholder = { Text("Untitled") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )

            SectionHeader("Size")
            ChoiceChips(SizeGroup.entries.map { it.label }, group.ordinal, { group = SizeGroup.entries[it] })
            Spacer(Modifier.height(8.dp))
            when (group) {
                SizeGroup.SCREEN -> ScreenPresets(w, h) { size = size.withPreset(it) }
                SizeGroup.PRINT -> PrintPresets(
                    dpi = size.dpi,
                    landscape = size.landscape,
                    selectedPaper = size.paper?.name,
                    onDpi = { changeDpi(it) },
                    onOrientation = { size = size.withOrientation(it) },
                    onPick = { size = size.withPaper(it) },
                )
                SizeGroup.CUSTOM -> CustomSize(
                    widthPx = size.widthPx,
                    heightPx = size.heightPx,
                    unit = unit,
                    dpi = size.dpi,
                    onWidth = { size = size.withPixels(it, size.heightPx) },
                    onHeight = { size = size.withPixels(size.widthPx, it) },
                    onUnit = { unit = it },
                    onDpi = { changeDpi(it) },
                    onSwap = { size = size.swapped() },
                )
            }

            SectionHeader("Background")
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                BackgroundOption("White", 0xFFFFFFFF.toInt(), background == BackgroundChoice.WHITE) { background = BackgroundChoice.WHITE }
                BackgroundOption("Transparent", 0, background == BackgroundChoice.TRANSPARENT) { background = BackgroundChoice.TRANSPARENT }
                BackgroundOption("Color…", customColor, background == BackgroundChoice.CUSTOM) { pickingColor = true }
            }

            SectionHeader("Result")
            SummaryCard(w, h, size.dpi, unit, check, maxHeap)
        }
    }

    if (pickingColor) {
        ColorPickerDialog(
            initial = customColor,
            onPick = {
                customColor = it or 0xFF000000.toInt()
                background = BackgroundChoice.CUSTOM
            },
            onDismiss = { pickingColor = false },
            title = "Background color",
            showAlpha = false,
        )
    }
}

@Composable
private fun ScreenPresets(w: Int, h: Int, onPick: (PixelPreset) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 3,
    ) {
        for (p in CanvasPresets.screen) {
            PresetTile(selected = p.width == w && p.height == h, onClick = { onPick(p) }, modifier = Modifier.weight(1f)) {
                AspectGlyph(p.width, p.height)
                Spacer(Modifier.height(6.dp))
                Text("${p.width} × ${p.height}", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(p.aspect, style = MaterialTheme.typography.labelSmall, color = BrushworkColors.OnChromeDim, maxLines = 1)
            }
        }
    }
}

@Composable
private fun PrintPresets(
    dpi: Double,
    landscape: Boolean,
    selectedPaper: String?,
    onDpi: (Double) -> Unit,
    onOrientation: (Boolean) -> Unit,
    onPick: (PaperPreset) -> Unit,
) {
    val choices = CanvasPresets.dpiChoices
    Text("Resolution", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 2.dp))
    ChoiceChips(choices.map { "$it dpi" }, choices.indexOfFirst { it.toDouble() == dpi }, { onDpi(choices[it].toDouble()) })
    Text("Orientation", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
    ChoiceChips(listOf("Portrait", "Landscape"), if (landscape) 1 else 0, { onOrientation(it == 1) })
    Spacer(Modifier.height(6.dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (p in CanvasPresets.paper) {
            val (pw, ph) = p.pixels(dpi, landscape)
            val selected = p.name == selectedPaper
            val shape = RoundedCornerShape(10.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(shape)
                    .background(if (selected) BrushworkColors.AccentDim.copy(alpha = 0.35f) else BrushworkColors.ChromeHigh)
                    .border(if (selected) 2.dp else 1.dp, if (selected) BrushworkColors.Accent else BrushworkColors.ChromeBorder, shape)
                    .selectable(selected = selected, onClick = { onPick(p) }, role = Role.RadioButton)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AspectGlyph(pw, ph, box = 24.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(p.sizeLabel(landscape), style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim)
                }
                Text("$pw × $ph px", style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim)
            }
        }
    }
}

@Composable
private fun CustomSize(
    widthPx: Double,
    heightPx: Double,
    unit: LengthUnit,
    dpi: Double,
    onWidth: (Double) -> Unit,
    onHeight: (Double) -> Unit,
    onUnit: (LengthUnit) -> Unit,
    onDpi: (Double) -> Unit,
    onSwap: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LengthField("Width", widthPx, onWidth, unit, dpi, Modifier.weight(1f), step = null, minPx = 1.0, maxPx = 100_000.0)
        IconButton(onClick = onSwap) { Icon(Icons.Filled.SwapHoriz, contentDescription = "Swap width and height") }
        LengthField("Height", heightPx, onHeight, unit, dpi, Modifier.weight(1f), step = null, minPx = 1.0, maxPx = 100_000.0)
    }
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Unit", style = MaterialTheme.typography.bodyMedium)
        UnitSelector(unit, onUnit)
        Spacer(Modifier.width(8.dp))
        NumberField("Resolution", dpi, onDpi, Modifier.weight(1f), decimals = 1, suffix = "dpi", min = CanvasPresets.MIN_DPI, max = CanvasPresets.MAX_DPI)
    }
    Text(
        if (unit == LengthUnit.PX) "Resolution sets the physical print size and unit conversions."
        else "Sizes in ${unit.label.lowercase()} are converted to pixels at this resolution.",
        style = MaterialTheme.typography.bodySmall,
        color = BrushworkColors.OnChromeDim,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun PresetTile(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .clip(shape)
            .background(if (selected) BrushworkColors.AccentDim.copy(alpha = 0.35f) else BrushworkColors.ChromeHigh)
            .border(if (selected) 2.dp else 1.dp, if (selected) BrushworkColors.Accent else BrushworkColors.ChromeBorder, shape)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

/** A small outline rectangle with the canvas' proportions. */
@Composable
private fun AspectGlyph(w: Int, h: Int, box: Dp = 28.dp) {
    val r = if (h > 0) w.toFloat() / h else 1f
    val gw = if (r >= 1f) box else (box * r).coerceAtLeast(3.dp)
    val gh = if (r >= 1f) (box / r).coerceAtLeast(3.dp) else box
    Box(Modifier.size(box), contentAlignment = Alignment.Center) {
        Box(Modifier.size(gw, gh).border(1.5.dp, BrushworkColors.OnChromeDim, RoundedCornerShape(2.dp)))
    }
}

@Composable
private fun BackgroundOption(label: String, color: Int, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ColorSwatch(color, size = 44.dp, selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (selected) BrushworkColors.OnChrome else BrushworkColors.OnChromeDim, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun SummaryCard(w: Int, h: Int, dpi: Double, unit: LengthUnit, check: CanvasCheck, maxHeap: Long) {
    PanelCard {
        Text("$w × $h px", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (w > 0 && h > 0) {
            Text(
                "${CanvasPresets.physicalLabel(w, h, dpi, unit)} · ${Units.formatNumber(dpi, 1)} dpi · ${CanvasPresets.aspectLabel(w, h)}",
                style = MaterialTheme.typography.bodySmall,
                color = BrushworkColors.OnChromeDim,
            )
        }
        Spacer(Modifier.height(8.dp))
        InfoRow("Memory per layer", CanvasPresets.formatBytes(CanvasPresets.layerBytes(w, h)))
        InfoRow("Layers on this device", if (check.ok) "up to ${CanvasPresets.maxLayers(w, h, maxHeap)}" else "—")
        val message = check.message
        if (message != null) {
            Text(message, color = BrushworkColors.Danger, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = BrushworkColors.OnChromeDim, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Color.White)
    }
}
