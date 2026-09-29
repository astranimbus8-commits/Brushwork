package com.brushwork.paint.ui.canvas

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.engine.CanvasOps
import com.brushwork.paint.engine.CanvasRotation
import com.brushwork.paint.engine.CanvasSnapshot
import com.brushwork.paint.engine.ColorModeConverter
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.ui.common.ChoiceChips
import com.brushwork.paint.ui.common.LabeledSlider
import com.brushwork.paint.ui.common.LengthField
import com.brushwork.paint.ui.common.NumberField
import com.brushwork.paint.ui.common.PanelCard
import com.brushwork.paint.ui.common.SectionHeader
import com.brushwork.paint.ui.common.ToggleRow
import com.brushwork.paint.ui.common.UnitSelector
import com.brushwork.paint.ui.common.checkerboard
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Painted-area detection result ([rect] empty = nothing painted). */
private class Detected(val rect: Rect)

@Composable
private fun CardTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = modifier)
}

@Composable
private fun CardText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim, modifier = Modifier.padding(top = 2.dp))
}

// ------------------------------------------------------------------ trim & crop

/** Trim transparent edges, crop to the selection, or crop to a numeric rectangle. */
@Composable
internal fun TrimCropTab(
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
    val sel = c.selection?.takeUnless { it.isEmpty }
    val detected by produceState<Detected?>(null) {
        val snap = CanvasSnapshot.of(doc)
        value = try {
            Detected(withContext(Dispatchers.Default) { CanvasOps.opaqueBounds(snap) })
        } catch (e: OutOfMemoryError) {
            null
        }
    }
    val painted = detected?.rect
    val trimmable = painted != null && !painted.isEmpty && (painted.width() != curW || painted.height() != curH)

    PanelCard {
        CardTitle("Trim transparent edges")
        CardText("Crops the canvas to the painted area of all visible layers.")
        val status = when {
            detected == null -> "Finding the painted area…"
            painted == null || painted.isEmpty -> "The visible layers are empty."
            !trimmable -> "There are no transparent edges."
            else -> "Result: ${painted.width()} × ${painted.height()} px (from x ${painted.left}, y ${painted.top})"
        }
        Text(status, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
        ApplyButton("Trim", enabled = !busy && trimmable, onClick = { if (CanvasOps.applyTrim(c)) onApplied() })
    }

    PanelCard {
        CardTitle("Crop to selection")
        if (sel != null) {
            val b = sel.bounds
            CardText("Crops to the selection's bounding box: ${b.width()} × ${b.height()} px at x ${b.left}, y ${b.top}. The selection is removed afterwards.")
        } else {
            CardText("Make a selection first; the canvas is cropped to its bounding box.")
        }
        ApplyButton("Crop to selection", enabled = !busy && sel != null, onClick = { if (CanvasOps.applyCropToSelection(c)) onApplied() })
    }

    PanelCard {
        val initial = sel?.bounds ?: Rect(0, 0, curW, curH)
        var x by rememberSaveable { mutableDoubleStateOf(initial.left.toDouble()) }
        var y by rememberSaveable { mutableDoubleStateOf(initial.top.toDouble()) }
        var w by rememberSaveable { mutableDoubleStateOf(initial.width().toDouble()) }
        var h by rememberSaveable { mutableDoubleStateOf(initial.height().toDouble()) }
        fun setRect(r: Rect) { x = r.left.toDouble(); y = r.top.toDouble(); w = r.width().toDouble(); h = r.height().toDouble() }

        Row(verticalAlignment = Alignment.CenterVertically) {
            CardTitle("Crop to rectangle", Modifier.weight(1f))
            UnitSelector(unit, onUnitChange)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            LengthField("X", x, { x = it }, unit, dpi, Modifier.weight(1f), step = null, minPx = 0.0, maxPx = (curW - 1).toDouble())
            Spacer(Modifier.width(8.dp))
            LengthField("Y", y, { y = it }, unit, dpi, Modifier.weight(1f), step = null, minPx = 0.0, maxPx = (curH - 1).toDouble())
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            LengthField("Width", w, { w = it }, unit, dpi, Modifier.weight(1f), step = null, minPx = 1.0, maxPx = curW.toDouble())
            Spacer(Modifier.width(8.dp))
            LengthField("Height", h, { h = it }, unit, dpi, Modifier.weight(1f), step = null, minPx = 1.0, maxPx = curH.toDouble())
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { setRect(Rect(0, 0, curW, curH)) }) { Text("Whole canvas") }
            TextButton(onClick = { sel?.let { setRect(it.bounds) } }, enabled = sel != null) { Text("Selection") }
            TextButton(onClick = { painted?.let { setRect(it) } }, enabled = painted != null && !painted.isEmpty) { Text("Painted") }
        }

        val rx = x.roundToInt().coerceIn(0, curW - 1)
        val ry = y.roundToInt().coerceIn(0, curH - 1)
        val rw = w.roundToInt().coerceIn(1, curW - rx)
        val rh = h.roundToInt().coerceIn(1, curH - ry)
        val clamped = rw != w.roundToInt() || rh != h.roundToInt()
        val whole = rw == curW && rh == curH
        CropPreview(curW, curH, rx, ry, rw, rh, thumbnail)
        Text("Result: $rw × $rh px", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
        if (clamped) Notice("The rectangle was limited to the canvas.", NoticeKind.WARNING)
        if (whole) Notice("The rectangle covers the whole canvas.")
        ApplyButton(
            "Crop",
            enabled = !busy && !whole,
            onClick = { if (CanvasOps.applyCrop(c, Rect(rx, ry, rx + rw, ry + rh))) onApplied() },
        )
    }
}

/** The document with the kept rectangle highlighted. */
@Composable
private fun CropPreview(docW: Int, docH: Int, x: Int, y: Int, w: Int, h: Int, thumbnail: CanvasThumbnail?) {
    // Seen from the new canvas: the old image (the document) sits at (-x, -y).
    CanvasLayoutPreview(
        oldW = docW, oldH = docH, newW = w, newH = h, ox = -x, oy = -y,
        thumbnail = thumbnail?.image,
        modifier = Modifier.fillMaxWidth().height(140.dp).padding(top = 6.dp),
    )
}

// ------------------------------------------------------------------ rotate & flip

/** One-tap rotations and mirrors of the whole canvas. The sheet stays open to combine them. */
@Composable
internal fun RotateFlipTab(c: EditorController, busy: Boolean) {
    val w = c.doc.width
    val h = c.doc.height
    CardText("Turns or mirrors the whole drawing: every layer and mask. Current size: $w × $h px.")
    Spacer(Modifier.height(6.dp))
    ActionRow(Icons.Filled.Rotate90DegreesCw, "Rotate 90° clockwise", "$w × $h → $h × $w px", enabled = !busy,
        onClick = { CanvasOps.applyRotate(c, CanvasRotation.CW_90) })
    ActionRow(Icons.Filled.Rotate90DegreesCcw, "Rotate 90° counter-clockwise", "$w × $h → $h × $w px", enabled = !busy,
        onClick = { CanvasOps.applyRotate(c, CanvasRotation.CCW_90) })
    ActionRow(Icons.Filled.Autorenew, "Rotate 180°", "Upside down; the size stays $w × $h px", enabled = !busy,
        onClick = { CanvasOps.applyRotate(c, CanvasRotation.R_180) })
    ActionRow(Icons.Filled.Flip, "Flip horizontally", "Mirror left and right", enabled = !busy,
        onClick = { CanvasOps.applyFlip(c, horizontal = true) })
    ActionRow(Icons.Filled.Flip, "Flip vertically", "Mirror top and bottom", enabled = !busy,
        onClick = { CanvasOps.applyFlip(c, horizontal = false) }, iconModifier = Modifier.rotate(90f))
    SectionHeader("Checking your drawing")
    ToggleRow(
        "Mirror the view only", c.viewMirrored, { c.viewMirrored = it },
        description = "Shows the canvas mirrored without changing any pixels",
    )
}

// ------------------------------------------------------------------ resolution

/** Changes the dpi without resampling: the print size changes, the pixels don't. */
@Composable
internal fun ResolutionTab(c: EditorController, busy: Boolean, onApplied: () -> Unit) {
    val doc = c.doc
    val w = doc.width.toDouble()
    val h = doc.height.toDouble()
    val curDpi = doc.dpi.toDouble()
    var dpi by rememberSaveable { mutableDoubleStateOf(curDpi) }
    var printUnit by rememberSaveable { mutableStateOf(LengthUnit.CM) }
    val presets = listOf(72, 96, 150, 300, 350, 600)

    PanelCard {
        InfoRow("Pixels", "${doc.width} × ${doc.height} px")
        InfoRow("Resolution", "${formatDpi(curDpi)} dpi")
    }
    SectionHeader("New resolution")
    NumberField(
        "Resolution", dpi, { dpi = it },
        modifier = Modifier.fillMaxWidth(), decimals = 1, suffix = "dpi", min = 1.0, max = 10_000.0, step = 1.0,
    )
    ChoiceChips(
        presets.map { "$it dpi" },
        presets.indexOfFirst { sameDpi(it.toDouble(), dpi.toFloat()) },
        { dpi = presets[it].toDouble() },
        modifier = Modifier.padding(top = 6.dp),
    )
    UnitHeader("Print size", printUnit, { printUnit = it }, units = listOf(LengthUnit.IN, LengthUnit.CM, LengthUnit.MM, LengthUnit.PT))
    PanelCard {
        InfoRow("Now", CanvasAdjustMath.formatSize(w, h, printUnit, curDpi))
        InfoRow("After", CanvasAdjustMath.formatSize(w, h, printUnit, dpi), emphasize = true)
    }
    Notice("Pixels are not changed. The resolution sets the printed size and how physical units (cm, in, mm, pt) convert to pixels. To change the number of pixels, use Image size.")
    ApplyButton(
        "Set ${formatDpi(dpi)} dpi",
        enabled = !busy && !sameDpi(dpi, doc.dpi),
        onClick = { if (CanvasOps.applyDpi(c, dpi.toFloat())) onApplied() },
    )
}

// ------------------------------------------------------------------ color mode

private fun modeDescription(mode: ColorMode, current: ColorMode): String = when (mode) {
    ColorMode.RGB -> if (current == ColorMode.RGB) "Full color." else "Allows color again. Existing pixels keep their current gray values."
    ColorMode.GRAYSCALE -> "Every layer becomes shades of gray (luminance); transparency is kept. New strokes are gray too."
    ColorMode.MONOCHROME -> "Every pixel becomes pure black or white and fully opaque or transparent, like 1-bit line art. New strokes follow the same rule."
}

/** RGB / grayscale / 1-bit monochrome conversion of every layer. */
@Composable
internal fun ColorModeTab(c: EditorController, busy: Boolean, thumbnail: CanvasThumbnail?, onApplied: () -> Unit) {
    val current = c.doc.colorMode
    var modeIdx by rememberSaveable { mutableIntStateOf(current.ordinal) }
    var threshold by rememberSaveable { mutableIntStateOf(128) }
    var dither by rememberSaveable { mutableStateOf(false) }
    val mode = ColorMode.entries[modeIdx]
    val convertsPixels = mode != current && mode != ColorMode.RGB &&
        !(mode == ColorMode.GRAYSCALE && current == ColorMode.MONOCHROME)

    PanelCard { InfoRow("Current mode", current.label, emphasize = true) }
    SectionHeader("Convert to")
    ChoiceChips(listOf("RGB", "Grayscale", "Monochrome"), modeIdx, { modeIdx = it })
    CardText(modeDescription(mode, current))

    if (mode == ColorMode.MONOCHROME && convertsPixels) {
        LabeledSlider(
            "Threshold", threshold.toFloat(), { threshold = it.roundToInt().coerceIn(1, 255) }, 1f..255f,
            valueText = "$threshold",
            modifier = Modifier.padding(top = 8.dp),
        )
        CardText("Pixels at least this bright become white; darker ones become black.")
        ToggleRow(
            "Dithering (Floyd–Steinberg)", dither, { dither = it },
            description = "Simulates gray tones with patterns of black and white dots",
        )
    }

    val preview by produceState<ImageBitmap?>(null, thumbnail, mode, threshold, dither, convertsPixels) {
        val t = thumbnail
        value = when {
            t == null -> null
            !convertsPixels -> t.image
            else -> withContext(Dispatchers.Default) {
                val px = t.pixels.copyOf()
                ColorModeConverter(t.width, mode, threshold, dither).convertRows(px, t.height)
                Bitmap.createBitmap(px, t.width, t.height, Bitmap.Config.ARGB_8888).asImageBitmap()
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(200.dp).padding(top = 8.dp), contentAlignment = Alignment.Center) {
        val img = preview
        if (img != null && thumbnail != null) {
            Image(
                img, contentDescription = "Preview of the converted drawing",
                modifier = Modifier.aspectRatio(thumbnail.width.toFloat() / thumbnail.height).checkerboard(),
                contentScale = ContentScale.Fit,
                filterQuality = if (mode == ColorMode.MONOCHROME) FilterQuality.None else FilterQuality.Low,
            )
        } else {
            Text("Preparing preview…", style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim)
        }
    }
    CardText("Preview of the whole drawing (approximate; each layer is converted separately).")

    if (convertsPixels) {
        Notice("Color information is discarded. Undo restores it. Layer masks are kept as they are.", NoticeKind.WARNING)
    } else if (mode != current) {
        Notice("Only the mode changes; no pixels are modified.")
    }
    ApplyButton(
        text = if (mode == current) "Already ${current.label}" else "Convert to ${mode.label}",
        enabled = !busy && mode != current,
        onClick = { if (CanvasOps.applyColorMode(c, mode, threshold, dither)) onApplied() },
    )
}
