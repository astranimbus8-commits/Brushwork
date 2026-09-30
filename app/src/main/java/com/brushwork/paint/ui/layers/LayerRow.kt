package com.brushwork.paint.ui.layers

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.ui.common.checkerboard
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlin.math.roundToInt

/** Height of every layer row (uniform so drag-reorder index math stays simple). */
internal val LAYER_ROW_HEIGHT = 52.dp
internal val THUMB_SIZE = 40.dp
private val CLIP_GUTTER = 16.dp

/**
 * Immutable snapshot of what a row shows. Layer objects are mutable and not observable (and
 * compared by identity under strong skipping), so rows take this instead of reading the layer.
 */
@Immutable
internal data class LayerRowModel(
    val layer: Layer,
    val name: String,
    val blendMode: LayerBlendMode,
    val opacity: Float,
    val visible: Boolean,
    val clipping: Boolean,
    val locked: Boolean,
    val alphaLocked: Boolean,
    val hasMask: Boolean,
    val maskEnabled: Boolean,
    val editingMask: Boolean,
    val contentVersion: Long,
    val active: Boolean,
    val clip: ClipInfo,
    /** Clipped to a hidden base (the compositor then hides the whole group). */
    val baseHidden: Boolean,
) {
    companion object {
        /** Rows for [topFirst] (display order). */
        fun build(doc: Document, topFirst: List<Layer>): List<LayerRowModel> {
            val docOrder = topFirst.asReversed()
            val clips = LayerListMath.clipStructureForDisplay(docOrder.map { it.clipping })
            val active = doc.activeLayer
            return topFirst.mapIndexed { i, l ->
                val clip = clips[i]
                LayerRowModel(
                    layer = l,
                    name = l.name,
                    blendMode = l.blendMode,
                    opacity = l.opacity,
                    visible = l.visible,
                    clipping = l.clipping,
                    locked = l.locked,
                    alphaLocked = l.alphaLocked,
                    hasMask = l.mask != null,
                    maskEnabled = l.maskEnabled,
                    editingMask = l.editingMask && l.mask != null,
                    contentVersion = l.contentVersion,
                    active = l === active,
                    clip = clip,
                    baseHidden = clip.clipped && !docOrder[clip.baseIndex].visible,
                )
            }
        }
    }
}

@Composable
internal fun LayerRow(
    row: LayerRowModel,
    thumbs: LayerThumbnails,
    docAspect: Float,
    dragging: Boolean,
    onSelect: () -> Unit,
    onToggleVisible: () -> Unit,
    onEditContent: () -> Unit,
    onEditMask: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    val background = when {
        dragging -> BrushworkColors.ChromeHigh
        row.active -> BrushworkColors.AccentDim.copy(alpha = 0.45f)
        else -> Color.Transparent
    }
    val dim = if (!row.visible || row.baseHidden) 0.45f else 1f
    Row(
        modifier
            .fillMaxWidth()
            .height(LAYER_ROW_HEIGHT)
            .then(if (dragging) Modifier.shadow(8.dp, shape) else Modifier)
            .clip(shape)
            .background(background)
            .then(if (row.active) Modifier.border(1.dp, BrushworkColors.Accent.copy(alpha = 0.7f), shape) else Modifier)
            .clickable(onClickLabel = "Select layer", onClick = onSelect)
            .padding(start = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.clip.clipped) ClipBracket(row.clip, Modifier.width(CLIP_GUTTER).fillMaxHeight())
        val contentImage = thumbs.content(row.layer)
        Row(Modifier.alpha(dim), verticalAlignment = Alignment.CenterVertically) {
            Thumbnail(
                image = contentImage,
                aspect = docAspect,
                transparentBacking = true,
                highlighted = row.hasMask && !row.editingMask,
                crossed = false,
                description = if (row.hasMask) "Edit layer content" else "Layer thumbnail",
                onClick = onEditContent,
            )
            if (row.hasMask) {
                val maskImage = thumbs.mask(row.layer)
                if (maskImage != null) {
                    Spacer(Modifier.width(2.dp))
                    Thumbnail(
                        image = maskImage,
                        aspect = docAspect,
                        transparentBacking = false,
                        highlighted = row.editingMask,
                        crossed = !row.maskEnabled,
                        description = "Edit mask",
                        onClick = onEditMask,
                    )
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f).alpha(dim), verticalArrangement = Arrangement.Center) {
            Text(
                row.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (row.active) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${row.blendMode.label} · ${(row.opacity * 100f).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = BrushworkColors.OnChromeDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.editingMask) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "MASK",
                        color = BrushworkColors.Accent,
                        fontSize = 9.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.border(1.dp, BrushworkColors.Accent, RoundedCornerShape(3.dp)).padding(horizontal = 3.dp),
                    )
                }
                if (row.alphaLocked) { Spacer(Modifier.width(6.dp)); AlphaLockBadge() }
                if (row.locked) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Filled.Lock, contentDescription = "Locked", tint = BrushworkColors.OnChromeDim, modifier = Modifier.size(14.dp))
                }
            }
        }
        IconButton(onClick = onToggleVisible, modifier = Modifier.size(40.dp)) {
            Icon(
                if (row.visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                contentDescription = if (row.visible) "Hide layer" else "Show layer",
                tint = if (row.visible) BrushworkColors.OnChrome else BrushworkColors.OnChromeDim,
            )
        }
    }
}

/** Square slot with the image fitted at the document's aspect ratio. */
@Composable
private fun Thumbnail(
    image: ImageBitmap,
    aspect: Float,
    transparentBacking: Boolean,
    highlighted: Boolean,
    crossed: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(3.dp)
    Box(
        Modifier
            .size(THUMB_SIZE)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClickLabel = description, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .padding(2.dp)
                .aspectRatio(aspect, matchHeightConstraintsFirst = aspect < 1f)
                .clip(shape)
                .then(if (transparentBacking) Modifier.checkerboard(4.dp) else Modifier.background(Color.Black))
                .border(if (highlighted) 2.dp else 1.dp, if (highlighted) BrushworkColors.Accent else BrushworkColors.ChromeBorder, shape),
        ) {
            Image(
                bitmap = image,
                contentDescription = description,
                contentScale = ContentScale.FillBounds,
                filterQuality = FilterQuality.Low,
                modifier = Modifier.fillMaxSize(),
            )
            if (crossed) {
                // Disabled mask: red cross like most painting apps.
                Canvas(Modifier.fillMaxSize()) {
                    val w = 2.dp.toPx()
                    drawLine(BrushworkColors.Danger, Offset(0f, 0f), Offset(size.width, size.height), w)
                    drawLine(BrushworkColors.Danger, Offset(size.width, 0f), Offset(0f, size.height), w)
                }
            }
        }
    }
}

/** "↳" bracket linking a clipped row down to its base layer. */
@Composable
private fun ClipBracket(clip: ClipInfo, modifier: Modifier) {
    val color = BrushworkColors.Accent
    Canvas(modifier) {
        val stroke = 2.dp.toPx()
        val x = size.width * 0.45f
        val cy = size.height / 2f
        val top = if (clip.continuesAbove) 0f else cy
        drawLine(color, Offset(x, top), Offset(x, size.height), stroke)
        drawLine(color, Offset(x, cy), Offset(size.width, cy), stroke, cap = StrokeCap.Round)
        if (clip.lowestInGroup) {
            // Arrowhead pointing at the base below.
            val a = 5.dp.toPx()
            val tip = size.height - 1.dp.toPx()
            val p = Path().apply {
                moveTo(x - a, tip - a)
                lineTo(x, tip)
                lineTo(x + a, tip - a)
                close()
            }
            drawPath(p, color)
        }
    }
}

/** Small "α + lock" badge for alpha-locked layers. */
@Composable
internal fun AlphaLockBadge(tint: Color = BrushworkColors.OnChromeDim) {
    Row(
        Modifier
            .border(1.dp, tint, RoundedCornerShape(3.dp))
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("α", color = tint, fontSize = 10.sp, lineHeight = 12.sp)
        Icon(Icons.Filled.Lock, contentDescription = "Alpha locked", tint = tint, modifier = Modifier.size(9.dp))
    }
}
