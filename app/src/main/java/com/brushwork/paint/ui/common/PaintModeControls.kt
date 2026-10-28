package com.brushwork.paint.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.brushwork.paint.ui.theme.BrushworkColors

/** v1.10 (R3): how an object is painted: its outline only, its fill only, or both. */
enum class PaintMode { STROKE, FILL, BOTH }

/** The name of [mode] everywhere (R3): CurveLabels17's three texts. */
internal fun paintModeName(mode: PaintMode): String = when (mode) {
    PaintMode.STROKE -> CurveLabels17.STROKE_ONLY
    PaintMode.FILL -> CurveLabels17.FILL_ONLY
    PaintMode.BOTH -> CurveLabels17.BOTH
}

/**
 * v1.10 (R3): v1.9's Shape `StyleGlyph` drawing (`ui/vector/VectorWidgets.kt`), pixel-identical
 * (PaintModeGlyphParityTest): a rounded square, filled with [tint] when the mode fills (with a thin
 * dim outline when it does not stroke), outlined in the chrome color when it strokes. 22 dp, as
 * v1.9 draws it (design 3.2 says 24 dp; pixel identity wins).
 */
@Composable
fun PaintModeGlyph(mode: PaintMode, tint: Color, modifier: Modifier = Modifier) {
    val fill = mode != PaintMode.STROKE
    val stroke = mode != PaintMode.FILL
    Canvas(modifier.size(22.dp)) {
        val inset = 3.dp.toPx()
        val r = CornerRadius(4.dp.toPx())
        val topLeft = Offset(inset, inset)
        val sz = Size(size.width - 2 * inset, size.height - 2 * inset)
        if (fill) {
            drawRoundRect(tint, topLeft, sz, r)
            if (!stroke) drawRoundRect(BrushworkColors.OnChromeDim, topLeft, sz, r, style = Stroke(1.dp.toPx()))
        }
        if (stroke) drawRoundRect(BrushworkColors.OnChrome, topLeft, sz, r, style = Stroke(2.5.dp.toPx()))
    }
}

/**
 * v1.10 (R3): three 48 x 40 dp segments, icons only (Stroke, Fill, Both). The long-press names
 * ([iconLongPressLabel], inside an [IconLongPressScope]) and content descriptions are the same
 * everywhere: CurveLabels17.STROKE_ONLY, FILL_ONLY and BOTH. Each segment is tagged
 * "$tagPrefix.<MODE>" (the enum name). [selected] null selects none; a segment that is not
 * [enabled] is dimmed and ignores taps.
 *
 * v1.10 G2 (V4, R3): a mode with a [refusal] (e.g. Fill and Both of a curve of 1 or 2 points,
 * CurveLabels17.FILL_NEEDS_3) is dimmed and marked disabled, reads the refusal as its state, and a
 * tap hands it to [onRefused] instead of [onSelect] (v1.7: "a tap says why"); none by default.
 */
@Composable
fun PaintModeSegments(
    selected: PaintMode?,
    enabled: (PaintMode) -> Boolean = { true },
    tagPrefix: String,
    refusal: (PaintMode) -> String? = { null },
    onRefused: (String) -> Unit = {},
    onSelect: (PaintMode) -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Row(Modifier.selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
        for (mode in PaintMode.entries) {
            val on = mode == selected
            val refused = refusal(mode)
            val active = refused == null && enabled(mode)
            val name = paintModeName(mode)
            Box(
                Modifier
                    .size(48.dp, 40.dp)
                    .background(if (on) BrushworkColors.AccentDim else Color.Transparent, shape)
                    .testTag("$tagPrefix.${mode.name}")
                    .iconLongPressLabel(name)
                    .then(
                        if (refused == null) {
                            Modifier.selectable(selected = on, enabled = active, role = Role.RadioButton, onClick = { onSelect(mode) })
                        } else {
                            // Still tappable: the tap says why.
                            Modifier.clickable(role = Role.RadioButton) { onRefused(refused) }
                        },
                    )
                    .semantics {
                        contentDescription = name
                        if (refused != null) {
                            this.selected = on
                            stateDescription = refused
                            disabled()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                PaintModeGlyph(mode, if (on) Color.White else BrushworkColors.OnChrome, Modifier.alpha(if (active) 1f else 0.38f))
            }
        }
    }
}
