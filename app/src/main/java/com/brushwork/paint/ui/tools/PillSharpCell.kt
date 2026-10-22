package com.brushwork.paint.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChangeHistory
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.outlined.ChangeHistory
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.brushwork.paint.tools.points.CornerState
import com.brushwork.paint.tools.points.PointCornerEditor
import com.brushwork.paint.ui.common.LocalLongPressBubbles
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.common.PointLabels110
import com.brushwork.paint.ui.common.V110Tags
import com.brushwork.paint.ui.common.longPressLabel
import com.brushwork.paint.ui.theme.IbisColors
import com.brushwork.paint.ui.theme.IbisDims
import com.brushwork.paint.ui.vector.ShapeStripItems

/**
 * v1.10 (R12, J28; signature frozen by F0, body A5's): the X / Y pill's Sharp / Smooth cell
 * (`PillCell.SHARP`), at the end of row 2 under "Select several" (never under the trash). The
 * editor screen passes it to [CoordinatePill] only while the tool's `CornerTool.pointCorners` is
 * not null and its `corner` is not null.
 *
 * The face shows what a tap does (C11), by the [editor]'s `corner` state:
 * - SMOOTH (every selected point smooth): Filled "ChangeHistory", `ShapeStripItems.SHARP_LABEL`; a tap makes them sharp.
 * - SHARP (every one sharp): Filled "Gesture", `ShapeStripItems.SMOOTH_LABEL`; a tap makes them smooth.
 * - MIXED: Outlined "ChangeHistory", `PointLabels110.MIXED_MAKES_SHARP`; a tap makes them all sharp (UQ9).
 * - ENDS_ONLY (only the ends of an open Path): greyed, `PointLabels.ENDS_SHARP`.
 * - ALWAYS_SHARP (Polyline, UQ18): greyed, `PointLabels110.POLYLINE_ALWAYS_SHARP`.
 *
 * 40 dp touch (`IbisDims.PillIconTouch`), the tag `V110Tags.PILL_SHARP`, a long press shows its
 * name (as every pill cell). A tap on a greyed cell changes nothing and shows the name (it says why);
 * the cell is then disabled for accessibility. A tap during a group edit does nothing (the editor's
 * lock). The state is derived, so a point drag does not recompose the cell.
 */
@Composable
fun PillSharpCell(editor: PointCornerEditor) {
    val corner = editor.corner
    val face = corner?.let { faceOf(it) }
    val bubbles = LocalLongPressBubbles.current
    val bounds = remember { arrayOf(Rect.Zero) }
    Box(
        Modifier
            .width(IbisDims.PillIconTouch)
            .requiredHeight(IbisDims.PillCellTouch)
            .testTag(V110Tags.PILL_SHARP)
            .then(
                if (face == null) Modifier else Modifier
                    .onGloballyPositioned { bounds[0] = it.boundsInRoot() }
                    .longPressLabel(face.name)
                    .clickable(onClickLabel = face.clickLabel, role = Role.Button) {
                        when (val sharp = face.makesSharp) {
                            null -> bubbles?.show(face.name, bounds[0])
                            else -> editor.setSelectedSharp(sharp)
                        }
                    }
                    .semantics {
                        contentDescription = face.name
                        if (face.makesSharp == null) disabled()
                    },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (face != null) {
            Box(Modifier.size(IbisDims.PillCellHeight).cellFill(), contentAlignment = Alignment.Center) {
                Icon(
                    face.icon,
                    contentDescription = null,
                    tint = if (face.makesSharp == null) Color.White.copy(alpha = GREYED_ALPHA) else Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * A face: its glyph, its name (long press, content description), what a tap does (null: greyed)
 * and the click action's label. The Mixed face's click label is v1.9's More item name
 * [com.brushwork.paint.ui.vector.SHARP_MIXED], so v1.9 tests that press that name still reach the
 * cell (a LeadRequest asks to move them to [PointLabels110.MIXED_MAKES_SHARP]).
 */
internal class SharpFace(val icon: ImageVector, val name: String, val makesSharp: Boolean?, val clickLabel: String = name)

/** The face of [state] (design R12 (a)). */
internal fun faceOf(state: CornerState): SharpFace = when (state) {
    CornerState.SMOOTH -> SharpFace(Icons.Filled.ChangeHistory, ShapeStripItems.SHARP_LABEL, makesSharp = true)
    CornerState.SHARP -> SharpFace(Icons.Filled.Gesture, ShapeStripItems.SMOOTH_LABEL, makesSharp = false)
    CornerState.MIXED -> SharpFace(Icons.Outlined.ChangeHistory, PointLabels110.MIXED_MAKES_SHARP, makesSharp = true, clickLabel = com.brushwork.paint.ui.vector.SHARP_MIXED)
    CornerState.ENDS_ONLY -> SharpFace(Icons.Filled.ChangeHistory, PointLabels.ENDS_SHARP, makesSharp = null)
    CornerState.ALWAYS_SHARP -> SharpFace(Icons.Filled.ChangeHistory, PointLabels110.POLYLINE_ALWAYS_SHARP, makesSharp = null)
}

/** The alpha of a greyed cell's glyph. */
private const val GREYED_ALPHA = 0.38f

/** The pill cell's beveled rounded square (as the other icon cells of the pill). */
private fun Modifier.cellFill(): Modifier = drawBehind {
    val r = IbisDims.PillCellRadius.toPx()
    val b = IbisDims.PillBevel.toPx()
    drawRoundRect(IbisColors.PillCell, cornerRadius = CornerRadius(r, r))
    drawLine(IbisColors.BevelLight, Offset(r * 0.6f, b / 2f), Offset(size.width - r * 0.6f, b / 2f), strokeWidth = b)
    drawLine(IbisColors.BevelDark, Offset(r * 0.6f, size.height - b / 2f), Offset(size.width - r * 0.6f, size.height - b / 2f), strokeWidth = b)
}
