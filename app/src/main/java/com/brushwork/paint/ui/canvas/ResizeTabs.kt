package com.brushwork.paint.ui.canvas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.brushwork.paint.ColorModeOps
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Units
import com.brushwork.paint.engine.CanvasGeometry
import com.brushwork.paint.engine.CanvasOps
import com.brushwork.paint.engine.Resample
import com.brushwork.paint.ui.color.ColorPickerDialog
import com.brushwork.paint.ui.common.ChoiceChips
import com.brushwork.paint.ui.common.ColorSwatch
import com.brushwork.paint.ui.common.LengthField
import com.brushwork.paint.ui.common.NumberField
import com.brushwork.paint.ui.common.PanelCard
import com.brushwork.paint.ui.common.SectionHeader
import com.brushwork.paint.ui.common.ToggleRow
import com.brushwork.paint.ui.common.UnitSelector
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlin.math.abs

/** Largest value accepted by the size fields (the operation itself allows [CanvasOps.MAX_SIDE]). */
private const val FIELD_MAX_PX = 100_000.0

/** Unit used to show print sizes when the fields are in pixels. */
internal fun printUnitFor(unit: LengthUnit) = if (unit == LengthUnit.PX) LengthUnit.CM else unit

internal fun formatDpi(dpi: Double) = Units.formatNumber(dpi, 1)

/** A resolution limited to the supported range (also guards against a typed "NaN"). */
internal fun clampDpi(v: Double): Double =
    if (v.isNaN()) CanvasOps.MIN_DPI.toDouble() else v.coerceIn(CanvasOps.MIN_DPI.toDouble(), CanvasOps.MAX_DPI.toDouble())

@Composable
internal fun UnitHeader(title: String, unit: LengthUnit, onUnitChange: (LengthUnit) -> Unit, units: List<LengthUnit> = LengthUnit.entries) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SectionHeader(title, Modifier.weight(1f))
        UnitSelector(unit, onUnitChange, units = units)
    }
}

/** Slider range of the canvas size fields (document px, logarithmic); larger sizes can be typed. */
private const val SLIDER_MIN_PX = 1.0
private val SLIDER_MAX_PX = CanvasOps.MAX_SIDE.toDouble()

/** Resize image: new pixel size (any unit), resolution, aspect lock, percent presets, resampling. */
@Composable
internal fun ColumnScope.ResizeImageTab(
    c: EditorController,
    unit: LengthUnit,
    onUnitChange: (LengthUnit) -> Unit,
    busy: Boolean,
    onApplied: () -> Unit,
) {
    val doc = c.doc
    val curW = doc.width
    val curH = doc.height
    val curDpi = doc.dpi.toDouble()
    val aspect = curW.toDouble() / curH
    var wPx by rememberSaveable { mutableDoubleStateOf(curW.toDouble()) }
    var hPx by rememberSaveable { mutableDoubleStateOf(curH.toDouble()) }
    var dpi by rememberSaveable { mutableDoubleStateOf(curDpi) }
    var keepAspect by rememberSaveable { mutableStateOf(true) }
    var resampleIdx by rememberSaveable { mutableIntStateOf(Resample.HIGH_QUALITY.ordinal) }
    val resample = Resample.entries[resampleIdx]
    val budget = remember { CanvasOps.memoryBudget() }
    val afterCommit = rememberAfterFieldCommit()

    val newW = CanvasAdjustMath.toPixels(wPx)
    val newH = CanvasAdjustMath.toPixels(hPx)
    val layerCount = doc.layers.size
    val maskCount = doc.layers.count { it.mask != null }
    val bitmaps = layerCount + maskCount
    val error = CanvasOps.validateSize(newW, newH, bitmaps, budget)
    val sizeChanged = newW != curW || newH != curH
    val dpiChanged = dpi.toFloat() != doc.dpi
    val printUnit = printUnitFor(unit)

    TabScaffold(
        footer = {
            if (error != null) Notice(error, NoticeKind.ERROR)
            ApplyButton(
                text = if (!sizeChanged && dpiChanged) "Change resolution" else "Resize image",
                enabled = !busy && error == null && (sizeChanged || dpiChanged),
                onClick = {
                    // The resolution field clamps out-of-range text only when it commits on focus loss.
                    afterCommit {
                        val w = CanvasAdjustMath.toPixels(wPx)
                        val h = CanvasAdjustMath.toPixels(hPx)
                        if (CanvasOps.applyResizeImage(c, w, h, dpi.toFloat(), Resample.entries[resampleIdx])) onApplied()
                    }
                },
            )
        },
    ) {
        PanelCard {
            InfoRow("Current", "$curW × $curH px")
            InfoRow("Print size", "${CanvasAdjustMath.formatSize(curW.toDouble(), curH.toDouble(), printUnit, curDpi)} at ${formatDpi(curDpi)} dpi")
        }

        UnitHeader("New size", unit, onUnitChange)
        Row(verticalAlignment = Alignment.Top) {
            LengthField(
                "Width", wPx,
                onPxChange = { v -> wPx = v; if (keepAspect) hPx = v / aspect },
                unit = unit, dpi = dpi, modifier = Modifier.weight(1f), step = null, minPx = 1.0, maxPx = FIELD_MAX_PX,
                sliderMinPx = SLIDER_MIN_PX, sliderMaxPx = SLIDER_MAX_PX,
            )
            Spacer(Modifier.width(8.dp))
            LengthField(
                "Height", hPx,
                onPxChange = { v -> hPx = v; if (keepAspect) wPx = v * aspect },
                unit = unit, dpi = dpi, modifier = Modifier.weight(1f), step = null, minPx = 1.0, maxPx = FIELD_MAX_PX,
                sliderMinPx = SLIDER_MIN_PX, sliderMaxPx = SLIDER_MAX_PX,
            )
        }
        ToggleRow(
            "Keep aspect ratio", keepAspect,
            onCheckedChange = { on -> keepAspect = on; if (on) hPx = wPx / aspect },
        )
        NumberField(
            "Resolution", dpi,
            onValueChange = { typed ->
                val v = clampDpi(typed)
                wPx = CanvasAdjustMath.pxAfterDpiChange(wPx, dpi, v, unit)
                hPx = CanvasAdjustMath.pxAfterDpiChange(hPx, dpi, v, unit)
                dpi = v
            },
            modifier = Modifier.fillMaxWidth(), decimals = 1, suffix = "dpi",
            min = CanvasOps.MIN_DPI.toDouble(), max = CanvasOps.MAX_DPI.toDouble(),
        )
        if (unit != LengthUnit.PX) {
            Notice("A new resolution keeps the size in ${unit.label.lowercase()}, so the number of pixels changes with it.")
        }

        SectionHeader("Scale")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (p in intArrayOf(25, 50, 100, 200, 400)) {
                OutlinedButton(
                    onClick = { afterCommit { wPx = curW * p / 100.0; hPx = curH * p / 100.0 } },
                    modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp),
                ) { Text("$p%", maxLines = 1) }
            }
        }

        SectionHeader("Resampling")
        ChoiceChips(Resample.entries.map { it.label }, resampleIdx, { resampleIdx = it })
        Text(resample.description, style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim, modifier = Modifier.padding(top = 2.dp))

        PanelCard(Modifier.padding(top = 8.dp)) {
            InfoRow("Result", "$newW × $newH px", emphasize = true)
            InfoRow("Scale", "${CanvasAdjustMath.percent(newW, curW)} × ${CanvasAdjustMath.percent(newH, curH)}")
            InfoRow("Print size", "${CanvasAdjustMath.formatSize(newW.toDouble(), newH.toDouble(), printUnit, dpi)} at ${formatDpi(dpi)} dpi")
            InfoRow("Memory", CanvasAdjustMath.memoryLine(newW, newH, bitmaps, budget))
            Text(
                "For ${CanvasAdjustMath.describeBitmaps(layerCount, maskCount)}",
                style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim,
            )
        }

        if (error == null) {
            if (!keepAspect && CanvasAdjustMath.aspectChanged(curW, curH, newW, newH)) {
                Notice("The aspect ratio changes, so the picture will be stretched.", NoticeKind.WARNING)
            }
            if ((newW > curW * 2 || newH > curH * 2) && resample != Resample.NEAREST) {
                Notice("Enlarging more than 200% makes edges soft. Choose Nearest for pixel art.", NoticeKind.WARNING)
            }
            if (CanvasOps.estimateBytes(bitmaps, newW, newH) > budget * 0.6) {
                Notice("This is a very large canvas: fewer new layers will fit and painting may be slower.", NoticeKind.WARNING)
            }
            if (!sizeChanged && dpiChanged) {
                Notice("Only the resolution changes; the pixels stay as they are.")
            }
            if (sizeChanged) Notice("All layers and masks are resampled. Undo restores the original pixels.")
        }
    }
}

/** Canvas size: new width/height, 3x3 anchor, optional fill of the added area. */
@Composable
internal fun ColumnScope.CanvasSizeTab(
    c: EditorController,
    unit: LengthUnit,
    onUnitChange: (LengthUnit) -> Unit,
    busy: Boolean,
    thumbnail: CanvasThumbnail?,
    onApplied: () -> Unit,
) {
    val doc = c.doc
    val curW = doc.width
    val curH = doc.height
    val dpi = doc.dpi.toDouble()
    var wPx by rememberSaveable { mutableDoubleStateOf(curW.toDouble()) }
    var hPx by rememberSaveable { mutableDoubleStateOf(curH.toDouble()) }
    var anchor by rememberSaveable { mutableIntStateOf(4) }
    var fillEnabled by rememberSaveable { mutableStateOf(false) }
    var fillColor by rememberSaveable { mutableIntStateOf(0xFFFFFFFF.toInt()) }
    var picking by remember { mutableStateOf(false) }
    val budget = remember { CanvasOps.memoryBudget() }

    val newW = CanvasAdjustMath.toPixels(wPx)
    val newH = CanvasAdjustMath.toPixels(hPx)
    val ox = CanvasGeometry.anchorOffset(curW, newW, anchor % 3)
    val oy = CanvasGeometry.anchorOffset(curH, newH, anchor / 3)
    val edges = CanvasAdjustMath.edges(curW, curH, newW, newH, ox, oy)
    val grows = edges.any { it > 0 }
    val shrinks = edges.any { it < 0 }
    val bitmaps = doc.layers.size + doc.layers.count { it.mask != null }
    val error = CanvasOps.validateSize(newW, newH, bitmaps, budget)
    val shownFill = ColorModeOps.displayColor(fillColor, doc.colorMode)

    TabScaffold(
        footer = {
            if (error != null) Notice(error, NoticeKind.ERROR)
            ApplyButton(
                text = "Change canvas size",
                enabled = !busy && error == null && (newW != curW || newH != curH),
                onClick = {
                    // Read the state now (not the values of the last composition).
                    val w = CanvasAdjustMath.toPixels(wPx)
                    val h = CanvasAdjustMath.toPixels(hPx)
                    val fill = if (fillEnabled && (w > curW || h > curH)) fillColor else null
                    if (CanvasOps.applyResizeCanvas(c, w, h, anchor % 3, anchor / 3, fill)) onApplied()
                },
            )
        },
    ) {
        PanelCard {
            InfoRow("Current", "$curW × $curH px")
            InfoRow("Print size", CanvasAdjustMath.formatSize(curW.toDouble(), curH.toDouble(), printUnitFor(unit), dpi))
        }

        UnitHeader("New canvas", unit, onUnitChange)
        Row(verticalAlignment = Alignment.Top) {
            LengthField(
                "Width", wPx, { wPx = it }, unit, dpi, Modifier.weight(1f), step = null, minPx = 1.0, maxPx = FIELD_MAX_PX,
                sliderMinPx = SLIDER_MIN_PX, sliderMaxPx = SLIDER_MAX_PX,
            )
            Spacer(Modifier.width(8.dp))
            LengthField(
                "Height", hPx, { hPx = it }, unit, dpi, Modifier.weight(1f), step = null, minPx = 1.0, maxPx = FIELD_MAX_PX,
                sliderMinPx = SLIDER_MIN_PX, sliderMaxPx = SLIDER_MAX_PX,
            )
        }

        SectionHeader("Anchor")
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnchorPicker(anchor, { anchor = it }, shrinkX = newW < curW, shrinkY = newH < curH)
            Spacer(Modifier.width(12.dp))
            CanvasLayoutPreview(
                curW, curH, newW, newH, ox, oy, thumbnail?.image,
                modifier = Modifier.weight(1f).height(120.dp),
                fill = if (fillEnabled && grows) Color(shownFill) else null,
            )
        }
        Text(
            CanvasAdjustMath.formatEdges(edges),
            style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim,
            modifier = Modifier.padding(top = 6.dp),
        )

        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            ColorSwatch(shownFill, size = 40.dp, onClick = { picking = true })
            Spacer(Modifier.width(12.dp))
            ToggleRow(
                "Fill the new area of the bottom layer", fillEnabled, { fillEnabled = it },
                modifier = Modifier.weight(1f),
                description = if (fillEnabled) "Tap the swatch to change the color" else "Otherwise the new area is transparent",
            )
        }

        PanelCard(Modifier.padding(top = 8.dp)) {
            InfoRow("Result", "$newW × $newH px", emphasize = true)
            InfoRow("Memory", CanvasAdjustMath.memoryLine(newW, newH, bitmaps, budget))
        }
        if (error == null) {
            if (shrinks) Notice("Pixels outside the new canvas are cut from every layer (undo brings them back).", NoticeKind.WARNING)
            if (fillEnabled && !grows) Notice("The canvas doesn't grow, so there is no new area to fill.")
        }
    }

    if (picking) {
        ColorPickerDialog(
            initial = fillColor,
            onPick = { fillColor = it or 0xFF000000.toInt(); fillEnabled = true },
            onDismiss = { picking = false },
            title = "Fill color",
        )
    }
}

/** True when two dpi values are the same for the document's float storage. */
internal fun sameDpi(a: Double, b: Float) = abs(a.toFloat() - b) < 1e-4f
