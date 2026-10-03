package com.brushwork.paint.ui.layers

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.Selection
import com.brushwork.paint.ui.editor.EditorIcons
import com.brushwork.paint.ui.theme.IbisColors
import com.brushwork.paint.ui.theme.IbisDims
import kotlin.math.roundToInt

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
    /** v1.6: the layer's number as the window shows it, counted from the bottom (1 = bottom). */
    val number: Int = 0,
    /** v1.6: the badge of a frame of a linked text story (⛓ "k/m"), or null. */
    val frame: FrameBadge? = null,
    /** v1.6: the effect of an adjustment layer ("Tone"), or null. */
    val effectName: String? = null,
) {
    /** An editable text layer (its text can be edited again with the text tool). */
    val isText: Boolean get() = kind == LayerKindBadge.TEXT

    /** A frame of a linked text story (edited by the Text frames tool; also [isText]). */
    val isFrame: Boolean get() = frame != null

    /** An editable shape layer (its shape can be edited again with the shape tool). */
    val isShape: Boolean get() = kind == LayerKindBadge.SHAPE

    /** A vector layer (its objects stay editable). */
    val isVector: Boolean get() = kind == LayerKindBadge.VECTOR

    /** An adjustment layer (its effect applies to the layers below). */
    val isAdjustment: Boolean get() = kind == LayerKindBadge.ADJUSTMENT

    companion object {
        /** Rows for [topFirst] (display order). Decodes text data afresh (see the cached overload). */
        fun build(doc: Document, topFirst: List<Layer>): List<LayerRowModel> = build(doc, topFirst, FrameInfoCache())

        /** Rows for [topFirst] (display order); [frames] keeps the decoded threads of text layers. */
        fun build(doc: Document, topFirst: List<Layer>, frames: FrameInfoCache): List<LayerRowModel> {
            val docOrder = topFirst.asReversed()
            val clips = LayerListMath.clipStructureForDisplay(docOrder.map { it.clipping })
            val badges = frames.badges(topFirst)
            val active = doc.activeLayer
            return topFirst.mapIndexed { i, l ->
                val clip = clips[i]
                val adjustment = l.adjustment
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
                    number = topFirst.size - i,
                    frame = badges[i],
                    effectName = adjustment?.let { AdjustmentEffects.displayName(it) },
                )
            }
        }
    }
}

/** Gutter left of the thumbnail: the clip bracket's room when clipped, a small pad otherwise. */
private val PLAIN_GUTTER = 4.dp
private val ROW_LINE = Color(0xFFC4C4C4)
private val THUMB_EDGE = Color(0xFF9A9A9A)
private val THUMB_BACK = Color(0xFFF4F4F4)
private val DIM_TEXT = Color(0xFF5A5A5A)
private val EYE_TINT = Color(0xFF3C3C3C)
private val DANGER = Color(0xFFE53935)
private val SELECTION_PINK = Color(0xFFF4BFCB)

/**
 * One ibisPaint layer row (design §3.7.7): clip bracket, thumbnail (2 dp border when active) with
 * its kind badge, the mask square (a tap switches the edit target), the number with the lock
 * icons over the eye and "100%" / "Normal", the name, and the ≡ handle (the list drags the row
 * from there at once; [onMove] gives the handle's accessibility actions).
 *
 * A tap selects at once; a second tap within the double-tap time runs [onEdit] (text, story,
 * shape, objects, adjustment). The list turns a long press into the layer's ⋮ menu, or into a
 * reorder drag when the finger then moves.
 */
@Composable
internal fun LayerRow(
    row: LayerRowModel,
    thumbs: LayerThumbnails,
    docAspect: Float,
    dragging: Boolean,
    height: Dp,
    thumbSize: Dp,
    onSelect: () -> Unit,
    onToggleVisible: () -> Unit,
    onMaskSquare: () -> Unit,
    onMove: (up: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onEdit: (() -> Unit)? = null,
) {
    val n = row.number
    val dim = if (!row.visible || row.baseHidden) 0.45f else 1f
    // A tap selects at once (selecting again is harmless); a second tap within the double-tap
    // time edits (combinedClickable would hold every single tap back).
    val doubleTapMs = LocalViewConfiguration.current.doubleTapTimeoutMillis
    val lastTap = remember(row.layer) { longArrayOf(-1L) }
    val click = Modifier.clickable(onClickLabel = LayerLabels.selectRow(n)) {
        val now = SystemClock.uptimeMillis()
        val last = lastTap[0]
        if (onEdit != null && last >= 0L && now - last <= doubleTapMs) {
            lastTap[0] = -1L
            onEdit()
        } else {
            lastTap[0] = now
            onSelect()
        }
    }
    val background = when {
        dragging -> IbisColors.ListRowSelected
        row.active -> IbisColors.ListRowSelected
        else -> IbisColors.ListRow
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .then(if (dragging) Modifier.shadow(8.dp) else Modifier)
            .background(background)
            .drawBehind { drawLine(ROW_LINE, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f) }
            .then(click),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val gutter = if (row.clip.clipped) IbisDims.LayerClipGutter else PLAIN_GUTTER
        Box(Modifier.width(gutter).fillMaxHeight()) {
            if (row.clip.clipped) ClipBracket(row.clip, Modifier.fillMaxSize())
        }
        Box(Modifier.alpha(dim)) {
            RowThumbnail(
                image = thumbs.content(row.layer),
                aspect = docAspect,
                size = thumbSize,
                selected = row.active && !row.editingMask,
                overlayText = row.effectName,
                modifier = Modifier.testTag(LayerWindowTags.thumb(row.layer.id)),
            ) { KindBadge(row) }
        }
        // A masked row in an 80 dp row stacks its eye over its mask square (one 40 dp column):
        // side by side they would leave "100%" / "Normal" no room on a 360–392 dp phone, and the
        // eye would shrink under 40 dp. A shorter row (the side-by-side window) keeps them in line.
        val stacked = row.hasMask && height >= IbisDims.LayerEyeTouch + IbisDims.LayerMaskTouch
        val eye = @Composable { EyeButton(row, n, onToggleVisible) }
        val mask = @Composable {
            val editingThis = row.active && row.editingMask && !row.isAdjustment
            MaskSquare(
                image = thumbs.mask(row.layer),
                aspect = docAspect,
                editing = row.active && row.editingMask,
                enabled = row.maskEnabled,
                spec = row.maskIsSpec,
                label = if (editingThis) LayerLabels.editContent(n) else LayerLabels.editMask(n),
                description = LayerLabels.maskOf(n),
                specBadge = LayerLabels.specMaskBadge(n),
                onClick = onMaskSquare,
                modifier = Modifier.alpha(dim),
            )
        }
        if (stacked) {
            Column(Modifier.width(IbisDims.LayerEyeTouch).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                eye()
                mask()
            }
        } else if (row.hasMask) {
            mask()
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = 4.dp), verticalArrangement = Arrangement.Center) {
            val tall = height.value >= LayerWindowMetrics.TALL_ROW
            // An 80 dp row: the number at ibisPaint's size on a 22 dp line (22 + the 40 dp eye
            // line + the 16 dp name = 78). A short side-by-side row keeps 16 + 40.
            if (tall) {
                TallNumberLine(row, n, Modifier.fillMaxWidth().height(NUMBER_LINE).alpha(dim))
            } else {
                Row(Modifier.fillMaxWidth().height(16.dp).alpha(dim), verticalAlignment = Alignment.CenterVertically) {
                    Text(n.toString(), color = IbisColors.ListText, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Spacer(Modifier.width(4.dp))
                    RowName(row.name, Modifier.weight(1f, fill = false))
                    Spacer(Modifier.weight(1f))
                    NumberLineBadges(row, mask = row.editingMask && !row.isAdjustment)
                }
            }
            Row(Modifier.fillMaxWidth().height(IbisDims.LayerEyeTouch), verticalAlignment = Alignment.CenterVertically) {
                if (!stacked) eye()
                RowValues(row, Modifier.weight(1f).alpha(dim))
            }
            if (tall) RowName(row.name, Modifier.fillMaxWidth().height(16.dp).alpha(dim))
        }
        DragHandle(n, onMove)
    }
}

/** The eye (Ø 28 in a 40 dp target): "Hide layer N" / "Show layer N". */
@Composable
private fun EyeButton(row: LayerRowModel, n: Int, onToggleVisible: () -> Unit) {
    IconButton(onClick = onToggleVisible, modifier = Modifier.size(IbisDims.LayerEyeTouch)) {
        Icon(
            if (row.visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
            contentDescription = if (row.visible) LayerLabels.hide(n) else LayerLabels.show(n),
            tint = if (row.visible) EYE_TINT else THUMB_EDGE,
            modifier = Modifier.size(IbisDims.LayerEye - 2.dp),
        )
    }
}

/**
 * "100%" over "Normal", at ibisPaint's size ([IbisDims.LayerRowValueText]) where they fit, one
 * size for both lines, smaller where the values are narrow (a 360 dp phone, a clipped row, the
 * side-by-side window), down to [IbisDims.LayerRowTextMin]; a blend mode too long even then
 * ellipsizes. Spoken (and found by tests) as ONE description that names the row
 * ([LayerLabels.rowState]: "Layer 2: 100%, Normal"), so the rows' values, effect names and lock
 * states never repeat a label of another row or of the blend dropdown (I10).
 */
@Composable
private fun RowValues(row: LayerRowModel, modifier: Modifier) {
    val pct = (row.opacity * 100f).roundToInt()
    val state = LayerLabels.rowState(row.number, pct, row.blendMode.label, row.effectName, row.locked, row.alphaLocked)
    val lines = listOf("$pct%", row.blendMode.label)
    BoxWithConstraints(
        modifier.clearAndSetSemantics {
            contentDescription = state
            testTag = LayerWindowTags.values(row.layer.id)
        },
        contentAlignment = Alignment.Center,
    ) {
        val style = rememberFittedStyle(lines, constraints, IbisDims.LayerRowValueText, IbisDims.LayerRowTextMin, VALUE_LINE_GAP)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(lines[0], color = IbisColors.ListText, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(lines[1], color = IbisColors.ListText, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The number line of an 80 dp row: the number at ibisPaint's size, then the badges at their own
 * width (a Row measures them before the weighted number), the number fitted down to the room they
 * leave. The MASK badge gives way first where the line cannot hold it with the number at
 * [IbisDims.LayerRowTextMin] (a clipped, masked, locked two-digit row on a 360 dp phone): the mask
 * square beside it already marks the edit target with its accent border, as ibisPaint shows it,
 * and is spoken "Edit content N".
 */
@Composable
private fun TallNumberLine(row: LayerRowModel, n: Int, modifier: Modifier) {
    val digits = n.toString()
    BoxWithConstraints(modifier) {
        val measurer = rememberTextMeasurer(cacheSize = 0)
        val base = LocalTextStyle.current
        val density = LocalDensity.current
        val wantsMask = row.editingMask && !row.isAdjustment
        val room = constraints.maxWidth
        val mask = wantsMask && remember(measurer, base, density, digits, room, row.alphaLocked, row.locked) {
            with(density) {
                fun width(text: String, style: TextStyle) = measurer.measure(text, base.merge(style), maxLines = 1, softWrap = false).size.width
                var need = width(digits, TextStyle(fontSize = IbisDims.LayerRowTextMin, fontWeight = FontWeight.Normal)) +
                    width("MASK", MASK_TEXT) + (MASK_PAD * 2 + BADGE_GAP).roundToPx()
                if (row.alphaLocked) need += width("α", ALPHA_TEXT) + (ALPHA_LOCK + BADGE_GAP).roundToPx()
                if (row.locked) need += IbisDims.LayerLockIcon.roundToPx()
                need <= room
            }
        }
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().testTag(LayerWindowTags.number(row.layer.id)), contentAlignment = Alignment.CenterStart) {
                // Under the minimum only for a three-digit number with every badge on the
                // narrowest row: smaller rather than cut.
                val style = rememberFittedStyle(listOf(digits), constraints, IbisDims.LayerRowNumberText, NUMBER_FLOOR, 0.sp)
                Text(digits, color = IbisColors.ListText, style = style, fontWeight = FontWeight.Normal, maxLines = 1, softWrap = false)
            }
            NumberLineBadges(row, mask)
        }
    }
}

/** The number line's badges: MASK (when [mask]), α + lock, lock. */
@Composable
private fun NumberLineBadges(row: LayerRowModel, mask: Boolean) {
    if (mask) {
        Text(
            "MASK",
            color = IbisColors.Accent,
            style = LocalTextStyle.current.merge(MASK_TEXT),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.testTag(LayerWindowTags.badge(row.layer.id, LayerWindowTags.BADGE_MASK)).border(1.dp, IbisColors.Accent, RoundedCornerShape(2.dp)).padding(horizontal = MASK_PAD),
        )
        Spacer(Modifier.width(BADGE_GAP))
    }
    // (Spoken with the row's values: two locked rows must not share a label, I10.)
    if (row.alphaLocked) {
        AlphaLockBadge(DIM_TEXT, LayerWindowTags.badge(row.layer.id, LayerWindowTags.BADGE_ALPHA))
        Spacer(Modifier.width(BADGE_GAP))
    }
    if (row.locked) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = DIM_TEXT, modifier = Modifier.size(IbisDims.LayerLockIcon).testTag(LayerWindowTags.badge(row.layer.id, LayerWindowTags.BADGE_LOCK)))
    }
}

private val MASK_TEXT = TextStyle(fontSize = 8.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold)
private val ALPHA_TEXT = TextStyle(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold)
private val MASK_PAD = 2.dp
private val ALPHA_LOCK = 9.dp
private val BADGE_GAP = 2.dp
private val NUMBER_FLOOR = 9.sp

/** The number line of an 80 dp row; the gap (sp) between stacked value lines, and between the Selection row's. */
private val NUMBER_LINE = 22.dp
private val VALUE_LINE_GAP = 2.sp
private val SELECTION_LINE_GAP = 4.sp

/**
 * The text style at which [lines] fit [constraints] stacked, one line each: see [fitTextStyle].
 * Measured again only when the lines, the room or the style change.
 */
@Composable
private fun rememberFittedStyle(lines: List<String>, constraints: Constraints, max: TextUnit, min: TextUnit, lineGap: TextUnit): TextStyle {
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val base = LocalTextStyle.current
    return remember(measurer, base, lines, constraints.maxWidth, constraints.maxHeight, max, min, lineGap) {
        fitTextStyle(measurer, lines, base, constraints.maxWidth, constraints.maxHeight, max, min, lineGap)
    }
}

/**
 * [base] at the largest size from [max] down to [min] (in 0.5 sp steps) at which every one of
 * [lines] fits [maxWidthPx] on one line and all of them stacked, each line [lineGap] taller than
 * the size, fit [maxHeightPx]; at [min] when none does (the lines then ellipsize). One size for
 * all the lines, as ibisPaint sets "100%" over "Normal".
 */
internal fun fitTextStyle(
    measurer: TextMeasurer,
    lines: List<String>,
    base: TextStyle,
    maxWidthPx: Int,
    maxHeightPx: Int,
    max: TextUnit,
    min: TextUnit,
    lineGap: TextUnit,
): TextStyle {
    // Each line exactly its line height (Mode.Tight: a line would otherwise keep at least the
    // font's full ascent and descent, 1.17 em, and two 18 sp lines would not fit the 40 dp eye
    // line; the glyphs, caps 0.71 em and descenders 0.21 em, stay well inside 1.11 em).
    fun style(sp: Float) = base.copy(fontSize = sp.sp, lineHeight = (sp + lineGap.value).sp, lineHeightStyle = EXACT_LINES)
    /** The widest line and the stacked height at [sp] (px). */
    fun measure(sp: Float): Pair<Int, Int> {
        val s = style(sp)
        var w = 0
        var h = 0
        for (l in lines) {
            val r = measurer.measure(l, s, maxLines = 1, softWrap = false)
            w = maxOf(w, r.size.width)
            h += r.size.height
        }
        return w to h
    }
    fun fits(sp: Float): Boolean = measure(sp).let { (w, h) -> w <= maxWidthPx && h <= maxHeightPx }
    val top = max.value
    val bottom = min.value
    val (w0, h0) = measure(top)
    if (w0 <= maxWidthPx && h0 <= maxHeightPx) return style(top)
    // Width and height grow about in step with the size: start near the answer, then settle on it.
    val ratio = minOf(maxWidthPx.toFloat() / w0.coerceAtLeast(1), maxHeightPx.toFloat() / h0.coerceAtLeast(1))
    var sp = (kotlin.math.floor(top * ratio / FIT_STEP) * FIT_STEP).coerceIn(bottom, top)
    while (sp > bottom && !fits(sp)) sp = (sp - FIT_STEP).coerceAtLeast(bottom)
    while (sp + FIT_STEP < top && fits(sp + FIT_STEP)) sp += FIT_STEP
    return style(sp)
}

private const val FIT_STEP = 0.5f
private val EXACT_LINES = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both, LineHeightStyle.Mode.Tight)

@Composable
private fun RowName(name: String, modifier: Modifier) {
    Text(
        name,
        color = IbisColors.ListText,
        fontSize = IbisDims.LayerRowText,
        lineHeight = 14.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * The ≡ handle (40 dp wide, the row's height). The list starts a reorder drag from here at once;
 * accessibility services move the layer with its actions.
 */
@Composable
private fun DragHandle(n: Int, onMove: (up: Boolean) -> Unit) {
    Box(
        Modifier
            .width(IbisDims.LayerDragHandleWidth)
            .fillMaxHeight()
            .semantics {
                contentDescription = LayerLabels.reorder(n)
                customActions = listOf(
                    CustomAccessibilityAction(LayerLabels.moveUp(n)) { onMove(true); true },
                    CustomAccessibilityAction(LayerLabels.moveDown(n)) { onMove(false); true },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(width = 18.dp, height = 14.dp)) {
            val stroke = 2.dp.toPx()
            for (i in 0..2) {
                val y = stroke / 2f + i * (size.height - stroke) / 2f
                drawLine(THUMB_EDGE, Offset(0f, y), Offset(size.width, y), stroke, cap = StrokeCap.Round)
            }
        }
    }
}

/**
 * Square thumbnail: the picture fitted at the document's aspect ratio on a checker, a 2 dp
 * border when [selected], and [badge] in a corner.
 */
@Composable
private fun RowThumbnail(
    image: ImageBitmap,
    aspect: Float,
    size: Dp,
    selected: Boolean,
    overlayText: String?,
    modifier: Modifier = Modifier,
    badge: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .size(size)
            .background(THUMB_BACK)
            .border(if (selected) IbisDims.LayerThumbBorder else 1.dp, if (selected) IbisColors.ThumbSelectedBorder else THUMB_EDGE),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .padding(if (selected) IbisDims.LayerThumbBorder else 1.dp)
                .aspectRatio(aspect, matchHeightConstraintsFirst = aspect < 1f)
                // (A checker clamped to the box: whole cells would spill under the picture.)
                .drawBehind { checker(IbisColors.CheckerLight, IbisColors.CheckerLight2, 4.dp.toPx()) },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                filterQuality = FilterQuality.Low,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (overlayText != null) {
            // Across the whole thumbnail, not just the picture: a tall canvas's picture is too
            // narrow for a word ("To" / "ne"). The effect's name is spoken with the row's values
            // (RowValues), not here.
            Text(
                overlayText,
                color = IbisColors.ListText,
                fontSize = 9.sp,
                lineHeight = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .background(Color(0xCCFFFFFF))
                    .padding(horizontal = 2.dp)
                    .clearAndSetSemantics { },
            )
        }
        badge()
    }
}

/** The kind badge in the thumbnail's corner (16 dp; the v1.5 kind names with the row's number, [LayerLabels.badge]). */
@Composable
private fun BoxScope.KindBadge(row: LayerRowModel) {
    val m = Modifier.align(Alignment.BottomEnd).padding(1.dp)
    val n = row.number
    val frame = row.frame
    when {
        frame != null -> FrameBadgeView(frame, LayerLabels.frameBadge(n, frame), m)
        row.isText -> TextLayerBadge(n, m)
        row.isShape -> ShapeLayerBadge(n, m)
        row.isVector -> LetterBadge("V", LayerLabels.badge(n, LayerLabels.VECTOR_BADGE), m)
        row.isAdjustment -> IconBadge(Icons.Filled.Contrast, LayerLabels.badge(n, LayerLabels.ADJUSTMENT_BADGE), m)
    }
}

private val BADGE_SHAPE = RoundedCornerShape(3.dp)

@Composable
private fun BadgeBox(description: String, modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .defaultMinSize(minWidth = IbisDims.LayerBadge)
            .height(IbisDims.LayerBadge)
            .clip(BADGE_SHAPE)
            .background(IbisColors.ThumbSelectedBorder)
            .padding(horizontal = 2.dp)
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun LetterBadge(letter: String, description: String, modifier: Modifier) =
    BadgeBox(description, modifier) {
        Text(letter, color = Color.White, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold)
    }

/** "T" badge of an editable text layer (row [n]). */
@Composable
internal fun TextLayerBadge(n: Int, modifier: Modifier = Modifier) = LetterBadge("T", LayerLabels.badge(n, LayerLabels.TEXT_BADGE), modifier)

/** Badge of an editable shape layer (row [n]; a square and a circle, like a shape tool icon). */
@Composable
internal fun ShapeLayerBadge(n: Int, modifier: Modifier = Modifier) =
    BadgeBox(LayerLabels.badge(n, LayerLabels.SHAPE_BADGE), modifier) {
        Canvas(Modifier.size(width = 12.dp, height = 10.dp)) {
            val s = 1.2.dp.toPx()
            val h = size.height
            drawRect(Color.White, topLeft = Offset(s / 2f, h * 0.3f), size = Size(h * 0.6f, h * 0.6f), style = Stroke(s))
            drawCircle(Color.White, radius = h * 0.32f, center = Offset(size.width - h * 0.34f, h * 0.36f), style = Stroke(s))
        }
    }

/** Small icon badge (adjustment layers, editable masks). */
@Composable
internal fun IconBadge(icon: ImageVector, description: String, modifier: Modifier = Modifier) =
    BadgeBox(description, modifier) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(11.dp))
    }

/** ⛓ "k/m" of a linked text frame, with a red + while the story is overset. */
@Composable
private fun FrameBadgeView(frame: FrameBadge, description: String, modifier: Modifier) =
    BadgeBox(description, modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Link, contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
            Text(frame.text, color = Color.White, fontSize = 9.sp, lineHeight = 11.sp, fontWeight = FontWeight.Bold)
            if (frame.overset) {
                Text("+", color = DANGER, fontSize = 11.sp, lineHeight = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.background(Color.White).padding(horizontal = 1.dp))
            }
        }
    }

/** Small "α + lock" icon of alpha-locked layers (spoken with the row's values, [LayerLabels.rowState]). */
@Composable
internal fun AlphaLockBadge(tint: Color, tag: String? = null) {
    Row(
        Modifier.clearAndSetSemantics { if (tag != null) testTag = tag },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("α", color = tint, style = LocalTextStyle.current.merge(ALPHA_TEXT))
        Icon(Icons.Filled.Lock, contentDescription = null, tint = tint, modifier = Modifier.size(ALPHA_LOCK))
    }
}

/**
 * The mask square right of the thumbnail: 26 dp visual in a 40 dp target; a 2 dp accent border
 * while the mask is edited, a red cross while it is disabled, the editable-mask badge on a spec
 * mask (described as [specBadge]).
 */
@Composable
private fun MaskSquare(
    image: ImageBitmap?,
    aspect: Float,
    editing: Boolean,
    enabled: Boolean,
    spec: Boolean,
    label: String,
    description: String,
    specBadge: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(IbisDims.LayerMaskTouch)
            .semantics { contentDescription = description }
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(IbisDims.LayerMaskSquare)
                .background(Color.Black)
                .border(if (editing) 2.dp else 1.dp, if (editing) IbisColors.Accent else THUMB_EDGE),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.Low,
                    modifier = Modifier.padding(if (editing) 2.dp else 1.dp).aspectRatio(aspect, matchHeightConstraintsFirst = aspect < 1f),
                )
            }
            if (!enabled) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = 2.dp.toPx()
                    drawLine(DANGER, Offset(0f, 0f), Offset(size.width, size.height), w)
                    drawLine(DANGER, Offset(size.width, 0f), Offset(0f, size.height), w)
                }
            }
        }
        if (spec) IconBadge(EditorIcons.Masks, specBadge, Modifier.align(Alignment.BottomEnd))
    }
}

/** "↳" bracket linking a clipped row down to its base layer. */
@Composable
private fun ClipBracket(clip: ClipInfo, modifier: Modifier) {
    val color = IbisColors.Accent
    Canvas(modifier) {
        val stroke = 2.dp.toPx()
        val x = size.width * 0.45f
        val cy = size.height / 2f
        val top = if (clip.continuesAbove) 0f else cy
        drawLine(color, Offset(x, top), Offset(x, size.height), stroke)
        drawLine(color, Offset(x, cy), Offset(size.width, cy), stroke, cap = StrokeCap.Round)
        if (clip.lowestInGroup) {
            // Arrowhead pointing at the base below.
            val a = 4.dp.toPx()
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

/**
 * The "Selection Layer" row at the top of the list (ibisPaint): a pink checker thumbnail with the
 * selected area and its bounds, "Selection Layer" over "No Selection" or "W × H px". A tap opens
 * the Selection panel ([onOpen]).
 */
@Composable
internal fun SelectionLayerRow(
    selection: Selection?,
    docAspect: Float,
    height: Dp,
    thumbSize: Dp,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(IbisColors.ListRow)
            .drawBehind { drawLine(ROW_LINE, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f) }
            .clickable(onClickLabel = LayerLabels.SELECTION_ROW, role = Role.Button, onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(PLAIN_GUTTER))
        SelectionThumbnail(selection, docAspect, thumbSize)
        // Both lines at ibisPaint's size, one size, smaller where the row is narrow (a 360 dp phone).
        BoxWithConstraints(Modifier.weight(1f).padding(start = 6.dp, end = 4.dp)) {
            val b = selection?.bounds
            val lines = listOf(LayerLabels.SELECTION_ROW, if (b == null) LayerLabels.NO_SELECTION else "${b.width()} × ${b.height()} px")
            val style = rememberFittedStyle(lines, constraints, IbisDims.LayerSelectionRowText, IbisDims.LayerRowTextMin, SELECTION_LINE_GAP)
            Column {
                Text(lines[0], color = IbisColors.ListText, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(lines[1], color = DIM_TEXT, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SelectionThumbnail(selection: Selection?, aspect: Float, side: Dp) {
    val px = with(LocalDensity.current) { side.roundToPx() }.coerceIn(16, 192)
    // Once per selection: the mask (ALPHA_8, document-sized) scaled to the thumbnail.
    val mask = remember(selection, px) {
        selection?.let { s -> runCatching { LayerThumbnails.downscale(s.mask, px).asImageBitmap() }.getOrNull() }
    }
    Box(Modifier.size(side).background(THUMB_BACK).border(1.dp, THUMB_EDGE), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .padding(1.dp)
                .aspectRatio(aspect, matchHeightConstraintsFirst = aspect < 1f)
                .drawBehind { pinkChecker(4.dp.toPx()) },
        ) {
            if (mask != null) {
                Image(
                    bitmap = mask,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    filterQuality = FilterQuality.Low,
                    colorFilter = ColorFilter.tint(Color(0xB33F6BA3), BlendMode.SrcIn),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            val bounds = selection?.bounds
            val docW = selection?.mask?.width ?: 0
            val docH = selection?.mask?.height ?: 0
            Canvas(Modifier.fillMaxSize()) {
                val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))
                if (bounds != null && docW > 0 && docH > 0) {
                    val sx = size.width / docW
                    val sy = size.height / docH
                    drawRect(
                        Color.Black,
                        topLeft = Offset(bounds.left * sx, bounds.top * sy),
                        size = Size((bounds.width() * sx).coerceAtLeast(1f), (bounds.height() * sy).coerceAtLeast(1f)),
                        style = Stroke(1.dp.toPx(), pathEffect = dash),
                    )
                } else {
                    // The dotted rectangle of an empty selection (ibisPaint).
                    val w = size.width * 0.4f
                    val h = size.height * 0.3f
                    drawRect(
                        Color.Black,
                        topLeft = Offset((size.width - w) / 2f, (size.height - h) / 2f),
                        size = Size(w, h),
                        style = Stroke(1.dp.toPx(), pathEffect = dash),
                    )
                }
            }
        }
    }
}

/** The Selection Layer's pink checker, clamped to the box (no cell spills past its edges). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.pinkChecker(cell: Float) {
    drawRect(Color.White)
    val n = cell.coerceAtLeast(1f)
    var y = 0f
    var r = 0
    while (y < size.height) {
        var x = if (r % 2 == 0) 0f else n
        while (x < size.width) {
            drawRect(SELECTION_PINK, topLeft = Offset(x, y), size = Size(minOf(n, size.width - x), minOf(n, size.height - y)))
            x += 2 * n
        }
        y += n
        r++
    }
}
