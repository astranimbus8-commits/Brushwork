package com.brushwork.paint.ui.placement

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ImageNotSupported
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.text.TextWrapSpec
import com.brushwork.paint.tools.text.WrapContour
import com.brushwork.paint.tools.text.WrapSides
import com.brushwork.paint.ui.common.BwSheet
import com.brushwork.paint.ui.common.ChoiceChips
import com.brushwork.paint.ui.common.LengthField
import com.brushwork.paint.ui.common.SectionHeader
import com.brushwork.paint.ui.common.ToggleRow
import com.brushwork.paint.ui.common.UnitSelector
import com.brushwork.paint.ui.common.checkerboard
import com.brushwork.paint.ui.layers.LayerThumbnails
import com.brushwork.paint.ui.theme.BrushworkColors

/**
 * "Wrap around picture" (v1.5 §4.1): a half-height, non-modal sheet of the Text tool. The text
 * flows around the opaque outline (or the box) of a picture layer: which layer (or Off), the
 * contour, the distance kept from it, the side(s) the lines go and whether the outline is shown.
 * Every change re-flows the pending text live; it is part of the pending text edit (one undo step
 * on ✓). The canvas stays usable: the text can still be dragged, resized and pinched.
 */
@Composable
fun TextWrapSheet(tool: TextTool) {
    val c = tool.controller
    val item = tool.item ?: return
    val wrap = item.wrap
    val thumbPx = with(LocalDensity.current) { THUMB.roundToPx() }
    val thumbs = remember(thumbPx) { LayerThumbnails(thumbPx) }
    // Layers aren't Compose state: follow the counters that change with them.
    val sources = remember(c.layersVersion, c.editCount, tool.editingLayer) { tool.wrapSources() }
    val deleted = remember(c.layersVersion, wrap.sourceLayerId) { tool.wrapSourceDeleted }
    val aspect = c.doc.width.toFloat() / c.doc.height.coerceAtLeast(1)
    var unit by remember { mutableStateOf(LengthUnit.PX) }

    BwSheet(title = "Wrap around picture", onDismiss = { tool.wrapSheetOpen = false }) {
        if (!item.canWrap) {
            SheetHint(TextTool.WRAP_HORIZONTAL_ONLY + ": switch the text to horizontal, straight text to wrap it around a picture.")
            return@BwSheet
        }
        SectionHeader("Around")
        SourceRow("Off", selected = !wrap.isOn, image = null, aspect = aspect, onClick = { tool.setWrapSource(null) })
        if (deleted) {
            SourceRow("(deleted layer)", selected = true, image = null, aspect = aspect, missing = true, onClick = {})
            SheetHint("The picture was deleted: the text keeps its last outline. Pick another layer or turn wrap off.")
        }
        for (layer in sources) {
            SourceRow(
                label = layer.name + if (!c.doc.effectiveVisible(layer)) " (hidden)" else "",
                selected = wrap.isOn && layer.id == wrap.sourceLayerId,
                image = thumbs.content(layer),
                aspect = aspect,
                onClick = { tool.setWrapSource(layer) },
            )
        }
        if (sources.isEmpty()) SheetHint("No picture layer yet: add one (or paste a picture), then wrap the text around it.")

        SectionHeader("Contour", Modifier.padding(top = 8.dp))
        ChoiceChips(WrapContour.entries.map { it.label }, wrap.contour.ordinal, { tool.setWrapContour(WrapContour.entries[it]) })
        SheetHint(if (wrap.contour == WrapContour.SHAPE) "Lines follow the picture's opaque pixels." else "Lines keep clear of the picture's whole rectangle.")

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            SectionHeader("Distance", Modifier.weight(1f))
            UnitSelector(unit, { unit = it })
        }
        LengthField(
            label = "Distance",
            px = wrap.gapPx.toDouble(),
            onPxChange = { tool.setWrapGap(it.toFloat()) },
            unit = unit,
            dpi = c.doc.dpi.toDouble().takeIf { it.isFinite() && it > 0.0 } ?: 72.0,
            minPx = 0.0,
            maxPx = TextWrapSpec.MAX_GAP_PX.toDouble(),
            sliderMinPx = 0.0,
            sliderMaxPx = TextWrapSpec.MAX_GAP_PX.toDouble(),
        )

        SectionHeader("Sides", Modifier.padding(top = 8.dp))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WrapSides.entries.forEach { s ->
                FilterChip(
                    selected = s == wrap.sides,
                    onClick = { tool.setWrapSides(s) },
                    label = { Text(s.label) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BrushworkColors.AccentDim, selectedLabelColor = Color.White),
                )
            }
        }
        SheetHint(
            when (wrap.sides) {
                WrapSides.LARGEST -> "Each line goes on the side of the picture with more room."
                WrapSides.BOTH -> "Lines continue past the picture on its other side."
                WrapSides.LEFT -> "Lines stay left of the picture."
                WrapSides.RIGHT -> "Lines stay right of the picture."
            }
        )
        ToggleRow(
            "Show outline", tool.showWrapOutline, { tool.showWrapOutline = it },
            modifier = Modifier.padding(top = 4.dp),
            description = "Dashed outline of the picture while the text is placed",
        )
    }
}

/** One choice of the "Around" list: a radio button, the layer's thumbnail and its name (48 dp tall). */
@Composable
private fun SourceRow(label: String, selected: Boolean, image: ImageBitmap?, aspect: Float, missing: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) BrushworkColors.AccentDim.copy(alpha = 0.35f) else Color.Transparent)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = BrushworkColors.Accent))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(THUMB), contentAlignment = Alignment.Center) {
            val shape = RoundedCornerShape(3.dp)
            when {
                image != null -> Box(
                    Modifier
                        .aspectRatio(aspect, matchHeightConstraintsFirst = aspect < 1f)
                        .clip(shape)
                        .checkerboard(4.dp)
                        .border(1.dp, BrushworkColors.ChromeBorder, shape),
                ) {
                    Image(image, contentDescription = null, contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.Low, modifier = Modifier.fillMaxSize())
                }
                else -> Icon(
                    if (missing) Icons.Filled.ImageNotSupported else Icons.Filled.Block,
                    contentDescription = null,
                    tint = BrushworkColors.OnChromeDim,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (missing) BrushworkColors.OnChromeDim else BrushworkColors.OnChrome,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SheetHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
}

/** Thumbnail slot of a layer in the list. */
private val THUMB = 40.dp
