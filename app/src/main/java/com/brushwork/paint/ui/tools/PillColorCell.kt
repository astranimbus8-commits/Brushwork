package com.brushwork.paint.ui.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.brushwork.paint.tools.ObjectColorEditor
import com.brushwork.paint.tools.ObjectColorFace
import com.brushwork.paint.tools.ObjectColorTarget
import com.brushwork.paint.ui.common.LocalSheetGroup
import com.brushwork.paint.ui.common.LocalSheetHost
import com.brushwork.paint.ui.common.ObjectColorLabels110
import com.brushwork.paint.ui.common.PaintMode
import com.brushwork.paint.ui.common.V110Tags
import com.brushwork.paint.ui.common.checkerboard
import com.brushwork.paint.ui.common.longPressLabel
import com.brushwork.paint.ui.theme.IbisColors
import com.brushwork.paint.ui.theme.IbisDims
import com.brushwork.paint.ui.vector.FRAME_DARK
import com.brushwork.paint.ui.vector.FRAME_LIGHT
import com.brushwork.paint.ui.vector.ObjectColorExtras
import com.brushwork.paint.ui.vector.ObjectColorsSheet
import com.brushwork.paint.ui.vector.SLASH

/**
 * v1.10 (R2, J20, J21, UQ3; signature frozen by F0, body A2's): the X / Y pill's Object colors
 * cell (`PillCell.COLOR`), the first cell of row 2 under the move icon at every width (folded:
 * right after it). The editor screen passes it to [CoordinatePill] only while the tool's
 * `ColoredObjectTool.objectColors` is not null: an object is open in the Shape, Curve, Polyline,
 * Path or Text tool and the eraser is off.
 *
 * Its face is the object's colors ([ColorCellGlyph]), framed by a 1 dp light outer ring and a 1 dp
 * dark inner ring as the bottom bar's color square, so a black or dark-navy object stays visible
 * on the dark pill. Icon only, 40 dp touch at every font scale ([IbisDims.PillIconTouch]); a long
 * press shows its name [ObjectColorLabels110.CELL] (also its content description); tag
 * [V110Tags.PILL_COLOR].
 *
 * A tap opens the [ObjectColorsSheet] on [editor]; the cell is accent (and selected) while the
 * sheet is open, and a tap then shows it again on top (minimized, or under another sheet). The
 * sheet is composed here, in [editor]'s group of sheets (`LocalSheetGroup`), while the editor's
 * `ObjectColorSheetState` says it is open on the object open now. So it follows the cell into the
 * folded row, and the end of its object's session (✓, ✕, a tool switch, an undo at the bottom of
 * the in-tool steps) drops it: the tool's ✓ keeps the previewed colors in its one step, its ✕
 * gives them back with everything else. Each object's sheet is its own composition (`key`), so no
 * picker state carries over to the next object. An editor that is not one of A2's (a test's) keeps
 * the open flag in the cell.
 */
@Composable
fun PillColorCell(editor: ObjectColorEditor) {
    val sheet = (editor as? ObjectColorExtras)?.sheet
    var openHere by remember(editor) { mutableStateOf(false) }
    val open = sheet?.isOpen ?: openHere
    val host = LocalSheetHost.current
    val name = ObjectColorLabels110.CELL
    Box(
        Modifier
            .width(IbisDims.PillIconTouch)
            .requiredHeight(IbisDims.PillCellTouch)
            .testTag(V110Tags.PILL_COLOR)
            .longPressLabel(name)
            .clickable(onClickLabel = name, role = Role.Button) {
                when {
                    open -> host?.bringToFront(editor)
                    sheet != null -> sheet.open()
                    else -> openHere = true
                }
            }
            .semantics {
                contentDescription = name
                selected = open
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(IbisDims.PillCellHeight).cellFill(if (open) IbisColors.Accent else IbisColors.PillCell),
            contentAlignment = Alignment.Center,
        ) {
            ColorCellGlyph(editor)
        }
    }
    if (open) {
        key(editor, sheet?.current) {
            CompositionLocalProvider(LocalSheetGroup provides editor) {
                ObjectColorsSheet(editor) { if (sheet != null) sheet.close() else openHere = false }
            }
        }
    }
}

/**
 * The COLOR cell's face (design R2 (a)): a disc of the fill (a text: its color) in a [RING] ring of
 * the stroke (a text: its outline), inside the two frame rings. A fill of none is the checker with
 * a slash; a kept gradient is a sweep of its colors; a stroke that is not painted (paint mode Fill,
 * a text without an outline) leaves its ring to the checker; a translucent color shows the checker
 * through. A line or an arrow (its stroke only) is one disc of the stroke.
 */
@Composable
internal fun ColorCellGlyph(editor: ObjectColorEditor, modifier: Modifier = Modifier) {
    val targets = editor.targets
    val disc: ObjectColorFace
    val ring: ObjectColorFace?
    when {
        ObjectColorTarget.TEXT in targets -> {
            disc = editor.face(ObjectColorTarget.TEXT)
            ring = editor.face(ObjectColorTarget.OUTLINE)
        }
        ObjectColorTarget.FILL in targets -> {
            disc = editor.face(ObjectColorTarget.FILL)
            ring = if (editor.paintMode == PaintMode.FILL) ObjectColorFace.None else editor.face(ObjectColorTarget.STROKE)
        }
        else -> {
            disc = targets.firstOrNull()?.let { editor.face(it) } ?: ObjectColorFace.None
            ring = null
        }
    }
    Box(modifier.size(GLYPH).clip(CircleShape).checkerboard(GLYPH / 6)) {
        Canvas(Modifier.size(GLYPH)) {
            val r = size.minDimension / 2f
            val c = Offset(r, r)
            val one = 1.dp.toPx()
            // Inside the two frame rings.
            val inner = r - 2f * one
            val ringPx = RING.toPx()
            val discR = if (ring != null) inner - ringPx else inner
            if (ring != null) paintFace(ring, c, inner - ringPx / 2f, Stroke(ringPx))
            paintFace(disc, c, discR, Fill)
            if (disc == ObjectColorFace.None) {
                val k = discR * 0.75f
                drawLine(SLASH, Offset(c.x - k, c.y + k), Offset(c.x + k, c.y - k), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
            drawCircle(FRAME_LIGHT, r - one / 2f, c, style = Stroke(one))
            drawCircle(FRAME_DARK, r - one * 1.5f, c, style = Stroke(one))
        }
    }
}

/** [face] as a circle of [radius] around [c] ([style]: a disc or a ring); none draws nothing (the checker shows). */
private fun DrawScope.paintFace(face: ObjectColorFace, c: Offset, radius: Float, style: DrawStyle) {
    when (face) {
        is ObjectColorFace.Solid -> drawCircle(Color(face.argb), radius, c, style = style)
        is ObjectColorFace.Gradient -> {
            val stops = face.stops.map { Color(it) }
            when {
                stops.size >= 2 -> drawCircle(Brush.sweepGradient(stops + stops.first(), c), radius, c, style = style)
                stops.size == 1 -> drawCircle(stops[0], radius, c, style = style)
            }
        }
        ObjectColorFace.None -> Unit
    }
}

/** The face's diameter (inside the pill's 28 dp cell). */
private val GLYPH: Dp = 24.dp

/** The width of the stroke (outline) ring around the fill disc. */
private val RING: Dp = 3.dp

/** The pill cell's beveled rounded square in [fill] (as the pill's other icon cells). */
private fun Modifier.cellFill(fill: Color): Modifier = drawBehind {
    val r = IbisDims.PillCellRadius.toPx()
    val b = IbisDims.PillBevel.toPx()
    drawRoundRect(fill, cornerRadius = CornerRadius(r, r))
    drawLine(IbisColors.BevelLight, Offset(r * 0.6f, b / 2f), Offset(size.width - r * 0.6f, b / 2f), strokeWidth = b)
    drawLine(IbisColors.BevelDark, Offset(r * 0.6f, size.height - b / 2f), Offset(size.width - r * 0.6f, size.height - b / 2f), strokeWidth = b)
}
