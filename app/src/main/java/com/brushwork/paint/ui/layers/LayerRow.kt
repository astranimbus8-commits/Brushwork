package com.brushwork.paint.ui.layers

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import android.os.SystemClock
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
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.ui.common.checkerboard
import com.brushwork.paint.ui.editor.EditorIcons
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlin.math.roundToInt

/** Height of every layer row (uniform so drag-reorder index math stays simple). */
internal val LAYER_ROW_HEIGHT = 52.dp
internal val THUMB_SIZE = 40.dp
private val CLIP_GUTTER = 16.dp

/** What kind of editable layer a row shows a badge for (v1.5). */
internal enum class LayerKindBadge { NONE, TEXT, SHAPE, VECTOR, ADJUSTMENT }

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
    /** The kind of editable layer (text, shape, vector, adjustment), or NONE. */
    val kind: LayerKindBadge = LayerKindBadge.NONE,
    /** The mask is an editable (gradient) mask. */
    val maskIsSpec: Boolean = false,
) {
    /** An editable text layer (its text can be edited again with the text tool). */
    val isText: Boolean get() = kind == LayerKindBadge.TEXT

    /** An editable shape layer (its shape can be edited again with the shape tool). */
    val isShape: Boolean get() = kind == LayerKindBadge.SHAPE

    /** A vector layer (its objects stay editable). */
    val isVector: Boolean get() = kind == LayerKindBadge.VECTOR

    /** An adjustment layer (its effect applies to the layers below). */
    val isAdjustment: Boolean get() = kind == LayerKindBadge.ADJUSTMENT

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
                    kind = when {
                        l.isAdjustmentLayer -> LayerKindBadge.ADJUSTMENT
                        l.isVectorLayer -> LayerKindBadge.VECTOR
                        l.isTextLayer -> LayerKindBadge.TEXT
                        l.isShapeLayer -> LayerKindBadge.SHAPE
                        else -> LayerKindBadge.NONE
                    },
                    maskIsSpec = l.mask != null && l.maskSpec != null,
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
    /** Double-tapping a text layer's row edits its text (null = rows without text). */
    onEditText: (() -> Unit)? = null,
    /** Double-tapping a shape layer's row opens its shape in the shape tool. */
    onEditShape: (() -> Unit)? = null,
    /** Double-tapping a vector layer's row edits its objects (Transform). */
    onEditVector: (() -> Unit)? = null,
    /** Double-tapping an adjustment layer's row edits its effect. */
    onEditAdjustment: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(8.dp)
    val background = when {
        dragging -> BrushworkColors.ChromeHigh
        row.active -> BrushworkColors.AccentDim.copy(alpha = 0.45f)
        else -> Color.Transparent
    }
    val dim = if (!row.visible || row.baseHidden) 0.45f else 1f
    // A tap selects at once (selecting again is harmless); a second tap on a text / shape row
    // within the double-tap time edits its text / shape. (combinedClickable would hold every
    // single tap back.)
    val doubleTapMs = LocalViewConfiguration.current.doubleTapTimeoutMillis
    val lastTap = remember(row.layer) { longArrayOf(-1L) }
    val click = Modifier.clickable(onClickLabel = "Select layer") {
        val now = SystemClock.uptimeMillis()
        val edit = when {
            row.isText -> onEditText
            row.isShape -> onEditShape
            row.isVector -> onEditVector
            row.isAdjustment -> onEditAdjustment
            else -> null
        }
        val last = lastTap[0]
        if (edit != null && last >= 0L && now - last <= doubleTapMs) {
            lastTap[0] = -1L
            edit()
        } else {
            lastTap[0] = now
            onSelect()
        }
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(LAYER_ROW_HEIGHT)
            .then(if (dragging) Modifier.shadow(8.dp, shape) else Modifier)
            .clip(shape)
            .background(background)
            .then(if (row.active) Modifier.border(1.dp, BrushworkColors.Accent.copy(alpha = 0.7f), shape) else Modifier)
            .then(click)
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
                if (row.isText) { TextLayerBadge(); Spacer(Modifier.width(4.dp)) }
                if (row.isShape) { ShapeLayerBadge(); Spacer(Modifier.width(4.dp)) }
                if (row.isVector) { IconBadge(EditorIcons.Vector, "Vector layer"); Spacer(Modifier.width(4.dp)) }
                if (row.isAdjustment) { IconBadge(Icons.Filled.Tune, "Adjustment layer"); Spacer(Modifier.width(4.dp)) }
                if (row.maskIsSpec) { IconBadge(EditorIcons.Masks, "Editable mask"); Spacer(Modifier.width(4.dp)) }
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

/** "T" badge of an editable text layer. */
@Composable
internal fun TextLayerBadge(tint: Color = BrushworkColors.Accent) {
    Text(
        "T",
        color = tint,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .border(1.dp, tint, RoundedCornerShape(3.dp))
            .padding(horizontal = 3.dp)
            .semantics { contentDescription = "Text layer" },
    )
}

/** Small badge of editable shape layers (a square and a circle, like a shape tool icon). */
@Composable
internal fun ShapeLayerBadge(tint: Color = BrushworkColors.Accent) {
    Box(
        Modifier
            .border(1.dp, tint, RoundedCornerShape(3.dp))
            .padding(horizontal = 2.dp, vertical = 1.dp)
            .semantics { contentDescription = "Shape layer" },
    ) {
        Canvas(Modifier.size(width = 13.dp, height = 10.dp)) {
            val s = 1.2.dp.toPx()
            val h = size.height
            drawRect(tint, topLeft = Offset(s / 2f, h * 0.3f), size = androidx.compose.ui.geometry.Size(h * 0.6f, h * 0.6f), style = androidx.compose.ui.graphics.drawscope.Stroke(s))
            drawCircle(tint, radius = h * 0.32f, center = Offset(size.width - h * 0.34f, h * 0.36f), style = androidx.compose.ui.graphics.drawscope.Stroke(s))
        }
    }
}

/** Small framed icon badge (vector layers, adjustment layers, editable masks; v1.5). */
@Composable
internal fun IconBadge(icon: ImageVector, description: String, tint: Color = BrushworkColors.Accent) {
    Box(
        Modifier
            .border(1.dp, tint, RoundedCornerShape(3.dp))
            .padding(horizontal = 2.dp, vertical = 1.dp)
            .semantics { contentDescription = description },
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(10.dp))
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
